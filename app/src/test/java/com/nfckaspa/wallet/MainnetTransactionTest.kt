package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MainnetTransactionTest {
    private val from=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1})
    private val to=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=2})
    private val utxo=KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32),0),200000000L,0,KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(from)))
    @Test fun feeRecalculatedAfterChangeShrinksAndConfirmedOutputsMatch() {
        var calls=0
        val p=KaspaTransaction.build(from,to,100000000L,listOf(utxo),2.5){tx->
            calls++;assertEquals(66,tx.inputs.single().signatureScript.size)
            if(tx.outputs[1].amount>99999900L)100L else 200L
        }
        assertEquals(3,calls);assertEquals(500L,p.fee)
        assertEquals(100000000L,p.tx.outputs[0].amount);assertEquals(99999500L,p.tx.outputs[1].amount)
        assertTrue(p.tx.inputs.single().signatureScript.isEmpty())
        assertEquals(p.fee,utxo.amount-p.tx.outputs.sumOf{it.amount})
    }
    @Test fun maximumSendSubtractsRoundedFee() {
        val p=KaspaTransaction.build(from,to,null,listOf(utxo),1.251){100L}
        assertEquals(126L,p.fee);assertEquals(199999874L,p.tx.outputs.single().amount)
    }
    @Test fun foreignAndDuplicateUtxosRejectedBeforePricing() {
        val foreign=utxo.copy(script=KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(to)))
        for(inputs in listOf(listOf(foreign),listOf(utxo,utxo))) {
            assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,to,1,inputs,1.0){error("must not reach mass API")}}
        }
    }
    @Test fun wrongNetworkAndInvalidFeeResponsesRejected() {
        val old="kaspatest:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhqrxplya"
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,old,1,listOf(utxo),1.0){100}}
        for(rate in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0)) {
            assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,to,1,listOf(utxo),rate){100}}
        }
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,to,1,listOf(utxo),1.0){100001}}
        assertThrows(IllegalArgumentException::class.java){KaspaTransaction.build(from,to,utxo.amount,listOf(utxo),1.0){100}}
    }
    @Test fun broadcastRequiresSuccessfulValidTxid() {
        val txid="a".repeat(64)
        assertEquals(txid,KaspaBroadcast.parseResponse("{\"transactionId\":\"$txid\"}"))
        assertThrows(IllegalStateException::class.java){KaspaBroadcast.parseResponse("{\"error\":\"rejected\"}")}
        assertThrows(IllegalStateException::class.java){KaspaBroadcast.parseResponse("{\"transactionId\":\"bad\"}")}
    }
    @Test fun networkEndpointsAreMainnet() {
        assertEquals("https://api.kaspa.org",KaspaNetwork.API)
        assertEquals("https://explorer.kaspa.org",KaspaNetwork.EXPLORER)
    }
}
