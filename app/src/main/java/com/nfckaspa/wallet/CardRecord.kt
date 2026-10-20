package com.nfckaspa.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class CardRecord(val slot:Slot,val daily:ByteArray,val recovery:ByteArray,val pub:ByteArray=byteArrayOf(),val sequence:Long=0,val version:Int=1,val generation:ByteArray=byteArrayOf()) {
    fun header():ByteArray {
        require(version in 2..3&&pub.size==32&&sequence>0)
        val header=ByteBuffer.allocate(if(version==3)64 else 48).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x4e,0x4b,0x32,0x30)).put(version.toByte()).put(slot.n.toByte()).putLong(sequence).put(pub).put(60.toByte()).put(77.toByte())
        if(version==3){require(generation.size==16);header.put(generation)}
        return header.array()
    }
    fun encode():ByteArray {
        require(daily.size==60&&recovery.size==77)
        val body=header()+daily+recovery
        return (body+Crypto.sha(body)).copyOf(288)
    }
    companion object {
        fun decode(bytes:ByteArray):CardRecord?=runCatching {
            require(bytes.size>=240)
            if(bytes.copyOfRange(0,4).contentEquals(byteArrayOf(0x4e,0x4b,0x32,0x30))) {
                val version=bytes[4].toInt();require(bytes.size==288&&version in 2..3&&bytes[46].toInt()==60&&bytes[47].toInt()==77)
                val h=if(version==3)64 else 48;val end=h+137
                require(java.security.MessageDigest.isEqual(Crypto.sha(bytes.copyOfRange(0,end)),bytes.copyOfRange(end,end+32)))
                val slot=Slot.entries.single{it.n==bytes[5].toInt()}
                val seq=ByteBuffer.wrap(bytes,6,8).order(ByteOrder.BIG_ENDIAN).long;require(seq>0)
                CardRecord(slot,bytes.copyOfRange(h,h+60),bytes.copyOfRange(h+60,end),bytes.copyOfRange(14,46),seq,version,if(version==3)bytes.copyOfRange(48,64) else byteArrayOf())
            } else {
                require(bytes.copyOfRange(0,4).contentEquals(byteArrayOf(0x4e,0x4b,0x57,0x35)))
                require(bytes[239]==bytes.take(239).fold(0){a,b->a xor (b.toInt() and 255)}.toByte())
                require(bytes[5].toInt()==60&&bytes[6].toInt()==77)
                CardRecord(Slot.entries.single{it.n==bytes[4].toInt()},bytes.copyOfRange(7,67),bytes.copyOfRange(67,144))
            }
        }.getOrNull()
    }
}

/** An authorization is specific to a snapshot, slot and UID, is short-lived and is consumed by the UI. */
internal data class OverwritePermit(val uid:String,val digest:ByteArray,val slot:Slot,val created:Long,val newDigest:ByteArray=byteArrayOf()) {
    fun matches(id:String,bytes:ByteArray,target:Slot,now:Long,newBytes:ByteArray=byteArrayOf())=id==uid&&target==slot&&now-created in 0..60_000&&
        java.security.MessageDigest.isEqual(digest,Crypto.sha(bytes))&&(newDigest.isEmpty()||java.security.MessageDigest.isEqual(newDigest,Crypto.sha(newBytes)))
}
internal class CardSnapshot(val first:ByteArray,val second:ByteArray) {
    val records=listOfNotNull(CardRecord.decode(first),CardRecord.decode(second))
    val latest:CardRecord?=records.maxByOrNull{it.sequence}
    val bytes get()=first+second
    fun recoveryRecords()=listOfNotNull(latest)
    fun nextSequence()=Math.addExact(latest?.sequence?:0L,1L)
    fun activeBank():Int=if(latest==null)error("저장된 기록 없음") else if(CardRecord.decode(first)?.sequence==latest.sequence)0 else 1
    val occupied get()=bytes.any{it!=0.toByte()&&it!=0xff.toByte()}
    fun destination():Int {
        val a=CardRecord.decode(first);val b=CardRecord.decode(second)
        // Legacy data in bank 0 is preserved throughout the first migration.
        return if(a==null&&b!=null)0 else if(b==null)1 else if(a!=null&&a.sequence>=b.sequence)1 else 0
    }
}

/** Pure journal protocol shared by NFC I/O and power-loss fault-injection tests. */
internal object CardJournal {
    fun commit(snapshot:CardSnapshot,record:CardRecord,write:(Int,Int,ByteArray)->Unit,read:(Int)->ByteArray) {
        require(record.sequence>(snapshot.latest?.sequence?:0))
        val bank=snapshot.destination();val bytes=record.encode()
        write(bank,0,ByteArray(16))
        for(i in 1 until 18)write(bank,i,bytes.copyOfRange(i*16,(i+1)*16))
        write(bank,0,bytes.copyOfRange(0,16))
        check(read(bank).contentEquals(bytes)){"새 카드 영역의 저장 검증에 실패했습니다. 기존 영역은 보존됩니다"}
    }
}

/** Destructive replacement after UID/snapshot-bound overwrite consent. Old card data is not retained. */
internal object CardRegistration {
    fun replace(snapshot:CardSnapshot,record:CardRecord,write:(Int,Int,ByteArray)->Unit,read:(Int)->ByteArray):CardSnapshot {
        require(record.version==3&&record.sequence>(snapshot.latest?.sequence?:0L))
        record.encode() // Validate the complete prepared record before deleting anything.
        for(bank in 0..1)write(bank,0,ByteArray(16))
        for(bank in 0..1)for(block in 1 until 18)write(bank,block,ByteArray(16))
        val empty=CardSnapshot(read(0),read(1))
        check(empty.first.size==288&&empty.second.size==288&&empty.bytes.all{it==0.toByte()}){"기존 지갑 영역 초기화 검증 실패 · 새 데이터를 저장하지 않았습니다"}
        CardJournal.commit(empty,record,write,read)
        val after=CardSnapshot(read(0),read(1))
        check(after.latest?.encode()?.contentEquals(record.encode())==true&&listOf(after.first,after.second)[1-after.activeBank()].size==288&&
            listOf(after.first,after.second)[1-after.activeBank()].all{it==0.toByte()}){"새 등록 데이터 또는 빈 영역 검증 실패"}
        return after
    }
}

/** Only an authenticated, currently verified generation can authorize deletion of the inactive bank. */
internal object CardCleanup {
    fun erase(snapshot:CardSnapshot,record:CardRecord,write:(Int,Int,ByteArray)->Unit,read:(Int)->ByteArray):CardSnapshot {
        require(record.version==3&&snapshot.latest?.encode()?.contentEquals(record.encode())==true)
        val active=snapshot.activeBank();val inactive=1-active
        for(i in 0 until 18)write(inactive,i,ByteArray(16))
        val cleared=read(inactive);val retained=read(active)
        check(cleared.size==288&&cleared.all{it==0.toByte()}){"이전 영역 삭제를 확인하지 못했습니다. 같은 카드를 다시 태그하세요"}
        check(retained.contentEquals(record.encode())){"현재 영역 검증 실패"}
        return if(active==0)CardSnapshot(retained,cleared) else CardSnapshot(cleared,retained)
    }
}

internal object CardRecovery {
    fun openLatest(snapshot:CardSnapshot,password:CharArray):Pair<CardRecord,ByteArray>? {
        val rec=snapshot.latest?:return null
        val share=runCatching{Crypto.passDec(rec,password)}.getOrNull()?:return null
        if(share.size!=33||share[0].toInt()!=rec.slot.n){share.fill(0);return null}
        return rec to share
    }
}
