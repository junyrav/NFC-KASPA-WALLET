package com.nfckaspa.wallet

import android.nfc.Tag
import android.nfc.tech.MifareClassic

internal object NfcCards {
    private fun <T> connected(tag:Tag,block:(MifareClassic)->T):T {
        var last:Exception?=null
        repeat(5) {
            val m=MifareClassic.get(tag)?:error("MIFARE Classic 카드가 아닙니다")
            try {m.connect();require(m.sectorCount>=13){"1K 이상의 카드가 필요합니다"};return block(m)}
            catch(e:Exception){last=e}finally{runCatching{m.close()}}
        }
        throw IllegalStateException("카드 처리 실패 · 카드를 다시 태그하세요",last)
    }
    private fun auth(m:MifareClassic,sector:Int) {check(m.authenticateSectorWithKeyA(sector,MifareClassic.KEY_DEFAULT)){"카드 영역을 읽을 수 없습니다. 내용을 확인하지 못해 쓰기를 중단했습니다"}}
    private fun bank(m:MifareClassic,index:Int):ByteArray {
        val data=ByteArray(288);var offset=0
        for(s in (1+index*6)..(6+index*6)){auth(m,s);val start=m.sectorToBlock(s);for(j in 0..2){m.readBlock(start+j).copyInto(data,offset);offset+=16}}
        return data
    }
    fun read(tag:Tag):CardSnapshot=connected(tag){CardSnapshot(bank(it,0),bank(it,1))}
    fun write(tag:Tag,record:CardRecord,expected:ByteArray) = connected(tag) { m->
        val snapshot=CardSnapshot(bank(m,0),bank(m,1))
        check(java.security.MessageDigest.isEqual(Crypto.sha(snapshot.bytes),expected)){"확인한 뒤 카드 데이터가 바뀌었습니다. 다시 확인하세요"}
        check(record.sequence>(snapshot.latest?.sequence?:0)){"카드 순서가 변경되었습니다. 다시 준비하세요"}
        CardRegistration.replace(snapshot,record,{ bank,index,bytes->
            val sector=1+bank*6+index/3
            auth(m,sector);m.writeBlock(m.sectorToBlock(sector)+index%3,bytes)
        },{bank->bank(m,bank)})
    }
    fun cleanup(tag:Tag,record:CardRecord,expected:ByteArray)=connected(tag){m->
        val snapshot=CardSnapshot(bank(m,0),bank(m,1))
        check(java.security.MessageDigest.isEqual(Crypto.sha(snapshot.bytes),expected)){"카드 내용이 변경되었습니다"}
        CardCleanup.erase(snapshot,record,{b,i,bytes->val sector=1+b*6+i/3;auth(m,sector);m.writeBlock(m.sectorToBlock(sector)+i%3,bytes)},{bank(m,it)})
    }

}
