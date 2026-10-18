package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class TransactionSecurityTest {
    private val from=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1})
    private val to=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=2})
    private fun utxo(amount:Long)=KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32),0),amount,0,KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(from)))
    @Test fun maliciousApiFeeCannotConsumeWalletBalance() {
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,to,10_00000000L,listOf(utxo(1000_00000000L)),989000.0){100000}}
        assertThrows(IllegalArgumentException::class.java){FeePolicy.check(989_00000000L,10_00000000L,100000)}
    }
    @Test fun localMassReproducesSmallSplitRestrictionButAllowsMaximum() {
        val input=utxo(19821000)
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,to,9000000,listOf(input),1.0,KaspaMass::calculate)}
        val max=KaspaTransaction.build(from,to,null,listOf(input),1.0,KaspaMass::calculate)
        assertEquals(1624L,max.fee)
        KaspaTransaction.validateIntent(max,from,to,java.math.BigDecimal.valueOf(max.tx.outputs[0].amount,8).toPlainString())
    }
    @Test fun confirmationMustMatchRecipientAmountFeeAndChange() {
        val p=KaspaTransaction.build(from,to,100000000,listOf(utxo(200000000)),1.0,KaspaMass::calculate)
        KaspaTransaction.validateIntent(p,from,to,"1")
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.validateIntent(p,from,from,"1")}
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.validateIntent(p,from,to,"0.9")}
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.validateIntent(p.copy(fee=p.fee+1),from,to,"1")}
        val bad=p.tx.copy(outputs=listOf(p.tx.outputs[0],p.tx.outputs[1].copy(script=p.tx.outputs[0].script)))
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.validateIntent(p.copy(tx=bad),from,to,"1")}
    }
    @Test fun transactionIdMatchesCoreAndDoesNotChangeWithSignatures() {
        assertEquals("2c18d5e59ca8fc4c23d9560da3bf738a8f40935c11c162017fbf2c907b7e665c",KaspaTx.transactionId(KaspaTx.Tx(inputs=emptyList(),outputs=emptyList())))
        val tx=KaspaTx.Tx(inputs=listOf(KaspaTx.Input(utxo(200000000))),outputs=listOf(KaspaTx.Output(199990000,script=utxo(1).script)))
        val before=KaspaTx.transactionId(tx);tx.inputs[0].signatureScript=ByteArray(66){5}
        assertEquals(before,KaspaTx.transactionId(tx))
        assertNotEquals(before,KaspaTx.transactionId(tx.copy(outputs=listOf(tx.outputs[0].copy(amount=199990001)))))
    }
}
