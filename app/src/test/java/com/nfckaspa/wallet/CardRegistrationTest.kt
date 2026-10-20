package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class CardRegistrationTest {
    private fun record(seq:Long)=CardRecord(Slot.A,ByteArray(60){seq.toByte()},ByteArray(77){seq.toByte()},KaspaAddress.xOnlyPublicKey(ByteArray(32).apply{this[31]=1}),seq,3,ByteArray(16){1})
    @Test fun oldBanksAreZeroBeforeNewRecordAndNoOldRecordRemainsAfterSuccess() {
        val banks=arrayOf(record(1).encode(),record(2).encode());val fresh=record(3);var writes=0
        val after=CardRegistration.replace(CardSnapshot(banks[0].copyOf(),banks[1].copyOf()),fresh,{b,i,data->
            if(writes==36)assertTrue(banks.all{bank->bank.all{it==0.toByte()}})
            writes++;data.copyInto(banks[b],i*16)
        },{banks[it].copyOf()})
        assertEquals(55,writes);assertEquals(3L,after.latest!!.sequence)
        assertTrue(after.first.all{it==0.toByte()});assertArrayEquals(fresh.encode(),after.second)
        assertEquals(listOf(3L),after.records.map{it.sequence})
    }
    @Test fun everyWriteInterruptionFailsAndNeverReportsRegistrationComplete() {
        repeat(55){failAt->
            val banks=arrayOf(record(1).encode(),record(2).encode());var writes=0
            assertThrows(IllegalStateException::class.java){CardRegistration.replace(CardSnapshot(banks[0].copyOf(),banks[1].copyOf()),record(3),{b,i,data->
                if(writes++==failAt)error("RF loss");data.copyInto(banks[b],i*16)
            },{banks[it].copyOf()})}
            if(failAt>=2)assertTrue(CardSnapshot(banks[0],banks[1]).records.none{it.sequence<3})
        }
    }
    @Test fun failedEraseVerificationNeverWritesNewRecordAndFinalReadbackFailureIsNotSuccess() {
        val old=record(1).encode();var writes=0
        assertThrows(IllegalStateException::class.java){CardRegistration.replace(CardSnapshot(old,ByteArray(288)),record(2),{_,_,_->writes++},{old.copyOf()})}
        assertEquals(36,writes)
        val banks=arrayOf(old.copyOf(),ByteArray(288));var reads=0
        assertThrows(IllegalStateException::class.java){CardRegistration.replace(CardSnapshot(banks[0].copyOf(),banks[1].copyOf()),record(2),{b,i,data->data.copyInto(banks[b],i*16)},{b->
            reads++;if(reads>=4)error("RF loss during final readback");banks[b].copyOf()
        })}
    }
}
