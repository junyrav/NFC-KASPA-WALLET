package com.nfckaspa.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RpcTransactionProofTest {
    private val input=KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32){1},2),200000000,0,byteArrayOf())
    private val tx=KaspaTx.Tx(inputs=listOf(KaspaTx.Input(input)),outputs=listOf(KaspaTx.Output(199800000,script=byteArrayOf(0x51))))
    private val hash="02".repeat(32)
    private val request=KaspaTransaction.signedRequest(tx)
    private fun nativeTx()=JSONObject().put("version",0).put("subnetworkId","0".repeat(40)).put("gas",0).put("payload","")
        .put("lockTime",0).put("inputs",JSONArray().put(JSONObject().put("previousOutpoint",JSONObject().put("transactionId",input.outpoint.transactionId.hex()).put("index",2)).put("sequence",0)))
        .put("outputs",JSONArray().put(JSONObject().put("value",199800000).put("scriptPublicKey","000051")))
    private fun block(row:JSONObject)=JSONObject().put("verboseData",JSONObject().put("hash",hash)).put("transactions",JSONArray().put(row))
    @Test fun nodeTransactionMustReproduceIdAndActuallySpendOriginalOutpoint() {
        val id=KaspaTx.transactionId(tx)
        assertEquals(id,KaspaTx.transactionId(RpcTransactionProof.decode(nativeTx())))
        assertTrue(RpcTransactionProof.provesConflict(block(nativeTx()),hash,id,request))
        assertFalse(RpcTransactionProof.provesConflict(block(nativeTx()),hash,"03".repeat(32),request))
        val unrelated=KaspaTransaction.signedRequest(tx.copy(inputs=listOf(KaspaTx.Input(input.copy(outpoint=input.outpoint.copy(index=3))))))
        assertFalse(RpcTransactionProof.provesConflict(block(nativeTx()),hash,id,unrelated))
        val mutated=nativeTx();mutated.getJSONArray("outputs").getJSONObject(0).put("value",199800001)
        assertFalse(RpcTransactionProof.provesConflict(block(mutated),hash,id,request))
    }
    @Test fun unsupportedOrMalformedTransactionCannotUnlockPendingPayment() {
        val id=KaspaTx.transactionId(tx)
        for(row in listOf(nativeTx().put("version",1),nativeTx().put("gas",1),nativeTx().put("payload","00"),nativeTx().put("lockTime",-1),nativeTx().put("subnetworkId","01".repeat(20))))
            assertFalse(RpcTransactionProof.provesConflict(block(row),hash,id,request))
        val bad=nativeTx();bad.getJSONArray("outputs").getJSONObject(0).put("value","199800000")
        assertFalse(RpcTransactionProof.provesConflict(block(bad),hash,id,request))
        assertThrows(IllegalArgumentException::class.java){RpcTransactionProof.provesConflict(block(nativeTx()),"04".repeat(32),id,request)}
    }
}
