package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class RegistrationResumeTest {
    private val key=ByteArray(32).apply{this[31]=1}
    private val address=KaspaAddress.mainnet(key)
    private val gen="a1".repeat(16)
    private fun record(slot:Slot)=CardRecord(slot,ByteArray(60),ByteArray(77),KaspaAddress.xOnlyPublicKey(key),1,3,gen.hexBytes())
    @Test fun oneCommittedCardResumesOriginalPolynomialAfterMemoryIsLost() {
        val original=Shares.split(key)
        for(slot in Slot.entries){
            val resumed=RegistrationResume.restore(record(slot),key,original.getValue(slot),gen,setOf(slot.name),false,address,slot.name)
            Slot.entries.forEach{assertArrayEquals(original.getValue(it),resumed.getValue(it))}
            assertArrayEquals(key,Shares.join(resumed.getValue(Slot.B),resumed.getValue(Slot.C)))
        }
    }
    @Test fun committedCardCanRepairProgressLostImmediatelyAfterNfcWrite() {
        val share=Shares.split(key).getValue(Slot.A)
        val resumed=RegistrationResume.restore(record(Slot.A),key,share,gen,emptySet(),false,address,null)
        assertArrayEquals(key,Shares.join(resumed.getValue(Slot.A),resumed.getValue(Slot.C)))
    }
    @Test fun completedBackupAndWrongGenerationWalletSlotOrKeyCannotResume() {
        val rec=record(Slot.A);val share=Shares.split(key).getValue(Slot.A)
        fun restore(r:CardRecord=rec,k:ByteArray=key,sh:ByteArray=share,g:String=gen,marks:Set<String> = setOf("A"),verified:Boolean=false,owner:String?="A")=
            RegistrationResume.restore(r,k,sh,g,marks,verified,address,owner)
        assertThrows(IllegalArgumentException::class.java){restore(marks=setOf("A","B","C"))}
        assertThrows(IllegalArgumentException::class.java){restore(verified=true)}
        assertThrows(IllegalArgumentException::class.java){restore(g="b2".repeat(16))}
        assertThrows(IllegalArgumentException::class.java){restore(r=rec.copy(version=2))}
        assertThrows(IllegalArgumentException::class.java){restore(k=ByteArray(32).apply{this[31]=2})}
        assertThrows(IllegalArgumentException::class.java){restore(sh=share.copyOf().apply{this[0]=2})}
        assertThrows(IllegalArgumentException::class.java){restore(owner="B")}
    }
    @Test fun passwordStillAuthenticatesShareBeforeOneCardContinuation() {
        val pass="Kasp8!xy".toCharArray();val plain=Shares.split(key).getValue(Slot.A)
        val rec=record(Slot.A).let{it.copy(recovery=Crypto.passEnc(plain,pass,it.header()))}
        assertThrows(Exception::class.java){Crypto.passDec(rec,"incorrect".toCharArray())}
        val share=Crypto.passDec(rec,pass)
        val restored=RegistrationResume.restore(rec,key,share,gen,setOf("A"),false,address,"A")
        assertArrayEquals(key,Shares.join(restored.getValue(Slot.A),restored.getValue(Slot.B)))
    }
}
