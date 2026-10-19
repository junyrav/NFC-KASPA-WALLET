package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class AuditRemediationTest {
    private val key=ByteArray(32).apply{this[31]=1}
    private val pub=KaspaAddress.xOnlyPublicKey(key)
    private fun record(seq:Long,generation:Byte=1)=CardRecord(Slot.A,ByteArray(60),ByteArray(77),pub,seq,3,ByteArray(16){generation})
    @Test fun offCurveAndOutOfFieldRecipientsNeverReachSigning() {
        val from=KaspaAddress.mainnet(key)
        val input=KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32),0),200000000,0,KaspaTx.p2pkScript(pub))
        val invalid=listOf(0,5,7).map{ByteArray(32).apply{this[31]=it.toByte()}}+listOf(ByteArray(32){0xff.toByte()})
        invalid.forEach{x->
            val address=KaspaAddress.fromPublicKey(x)
            assertArrayEquals(x,KaspaAddress.mainnetPayload(address))
            assertThrows(IllegalArgumentException::class.java){KaspaAddress.recipientPayload(address)}
            assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,address,100000000,listOf(input),100.0,KaspaMass::calculate)}
            val tx=KaspaTx.Tx(inputs=listOf(KaspaTx.Input(input)),outputs=listOf(KaspaTx.Output(100000000,script=KaspaTx.p2pkScript(x))))
            assertThrows(IllegalArgumentException::class.java){KaspaTx.signAll(tx,key)}
            assertTrue(tx.inputs.single().signatureScript.isEmpty())
        }
    }
    @Test fun latestRecordOnlyAndGenerationIsAuthenticated() {
        val share=Shares.split(key).getValue(Slot.A)
        val oldPassword="Old-Backup!2026-ab".toCharArray();val newPassword="New-Backup!2026-ab".toCharArray()
        val old=record(1).let{it.copy(recovery=Crypto.passEnc(share,oldPassword,it.header()))}
        val fresh=record(2,2).let{it.copy(recovery=Crypto.passEnc(share,newPassword,it.header()))}
        val snapshot=CardSnapshot(old.encode(),fresh.encode())
        assertNull(CardRecovery.openLatest(snapshot,oldPassword))
        assertArrayEquals(share,CardRecovery.openLatest(snapshot,newPassword)!!.second)
        assertThrows(Exception::class.java){Crypto.passDec(fresh.copy(generation=ByteArray(16){3}),newPassword)}
        assertEquals(3,CardRecord.decode(fresh.encode())!!.version)
        assertEquals(listOf(2L),snapshot.recoveryRecords().map{it.sequence})
    }
    @Test fun cleanupPowerLossNeverErasesActiveGenerationAndCanResume() {
        repeat(19){failAt->
            val fresh=record(2,2);val banks=arrayOf(record(1).encode(),fresh.encode());val active=banks[1].copyOf();var calls=0
            runCatching{CardCleanup.erase(CardSnapshot(banks[0],banks[1]),fresh,{b,i,bytes->if(calls++==failAt)error("RF lost");bytes.copyInto(banks[b],i*16)},{banks[it]})}
            assertArrayEquals(active,banks[1])
            CardCleanup.erase(CardSnapshot(banks[0],banks[1]),fresh,{b,i,bytes->bytes.copyInto(banks[b],i*16)},{banks[it]})
            assertTrue(banks[0].all{it==0.toByte()});assertArrayEquals(active,banks[1])
        }
    }
    @Test fun overwriteConsentIsBoundToNewRecordAndSequenceDoesNotUseClock() {
        val snapshot=CardSnapshot(record(9000000000000).encode(),ByteArray(288));val bytes=record(9000000000001,2).encode()
        assertEquals(9000000000001L,snapshot.nextSequence())
        val permit=OverwritePermit("uid",Crypto.sha(snapshot.bytes),Slot.A,1000,Crypto.sha(bytes))
        assertTrue(permit.matches("uid",snapshot.bytes,Slot.A,1001,bytes))
        assertFalse(permit.matches("uid",snapshot.bytes,Slot.A,1001,record(9000000000001,3).encode()))
    }
    @Test fun lowValuePartialPaymentUsesActualRelayAdmissionFloor() {
        val from=KaspaAddress.mainnet(key);val to=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=2})
        val input=KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32),0),500000000,0,KaspaTx.p2pkScript(pub))
        val p=KaspaTransaction.build(from,to,50000000,listOf(input),100.0,KaspaMass::calculate)
        assertTrue(p.relayFloor);assertTrue(KaspaMass.parts(p.tx).storage>KaspaMass.parts(p.tx).relay)
        assertEquals(KaspaMass.parts(p.tx).relay*100,p.fee)
        KaspaTransaction.validateIntent(p,from,to,"0.5")
    }
}
