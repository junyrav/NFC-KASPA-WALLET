package com.nfckaspa.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class CardRecord(val slot:Slot,val daily:ByteArray,val recovery:ByteArray,val pub:ByteArray=byteArrayOf(),val sequence:Long=0,val version:Int=1) {
    fun header():ByteArray {
        require(version==2&&pub.size==32&&sequence>0)
        return ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN).put(byteArrayOf(0x4e,0x4b,0x32,0x30)).put(2.toByte())
            .put(slot.n.toByte()).putLong(sequence).put(pub).put(60.toByte()).put(77.toByte()).array()
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
                require(bytes.size==288&&bytes[4].toInt()==2&&bytes[46].toInt()==60&&bytes[47].toInt()==77)
                require(java.security.MessageDigest.isEqual(Crypto.sha(bytes.copyOfRange(0,185)),bytes.copyOfRange(185,217)))
                val slot=Slot.entries.single{it.n==bytes[5].toInt()}
                val seq=ByteBuffer.wrap(bytes,6,8).order(ByteOrder.BIG_ENDIAN).long;require(seq>0)
                CardRecord(slot,bytes.copyOfRange(48,108),bytes.copyOfRange(108,185),bytes.copyOfRange(14,46),seq,2)
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
internal data class OverwritePermit(val uid:String,val digest:ByteArray,val slot:Slot,val created:Long) {
    fun matches(id:String,bytes:ByteArray,target:Slot,now:Long)=id==uid&&target==slot&&now-created in 0..60_000&&
        java.security.MessageDigest.isEqual(digest,Crypto.sha(bytes))
}
internal class CardSnapshot(val first:ByteArray,val second:ByteArray) {
    val records=listOfNotNull(CardRecord.decode(first),CardRecord.decode(second))
    val latest:CardRecord?=records.maxByOrNull{it.sequence}
    val bytes get()=first+second
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
