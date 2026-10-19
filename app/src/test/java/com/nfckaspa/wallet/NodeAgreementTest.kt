package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NodeAgreementTest {
    private val address=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1})
    private val script=KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(address))
    private val coin=KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32){1},0),200000000,0,script,100,false)
    private val nodes=listOf(NodeAgreement.endpoint("one","wss://emma.kaspa.stream/kaspa/mainnet/wrpc/json"),NodeAgreement.endpoint("two","wss://luna.kaspa.blue/kaspa/mainnet/wrpc/json"))
    private fun row(rpc:Boolean,amount:Any=coin.amount):JSONObject {
        val spk=if(rpc)"0000"+script.hex() else JSONObject().put("version",0).put("scriptPublicKey",script.hex())
        return JSONObject().put("address",address).put("outpoint",JSONObject().put("transactionId",coin.outpoint.transactionId.hex()).put("index",0))
            .put("utxoEntry",JSONObject().put("amount",amount).put("scriptPublicKey",spk).put("blockDaaScore",100).put("isCoinbase",false))
    }
    private fun agree(rest:List<KaspaTx.Utxo> = listOf(coin),other:List<KaspaTx.Utxo> = rest,ep:List<NodeEndpoint> = nodes,daa:List<Long> = listOf(100,110))=
        NodeAgreement.agree(address,rest,ep,listOf(rest,other),daa,listOf(100.0,101.0,100.0),1000)
    @Test fun restAndRpcSchemasNormalizeToSameExactFingerprint() {
        val rest=NodeAgreement.parseUtxos(address,JSONArray().put(row(false)),false)
        val rpc=NodeAgreement.parseUtxos(address,JSONArray().put(row(true)),true)
        assertEquals(NodeAgreement.digest(rest),NodeAgreement.digest(rpc));assertEquals(100.0,agree(rest,rpc).witness.feeRate,0.0)
    }
    @Test fun everyInputFieldAndMissingExtraOrDuplicateOutpointFailsClosed() {
        for(other in listOf(emptyList(),listOf(coin.copy(amount=coin.amount+1)),listOf(coin.copy(blockDaaScore=101)),listOf(coin.copy(isCoinbase=true)),listOf(coin.copy(script=ByteArray(34))),listOf(coin.copy(outpoint=coin.outpoint.copy(index=1))))){
            assertThrows(IllegalStateException::class.java){agree(other=other)}
        }
        assertThrows(IllegalArgumentException::class.java){NodeAgreement.parseUtxos(address,JSONArray().put(row(true)).put(row(true)),true)}
        assertThrows(IllegalArgumentException::class.java){NodeAgreement.parseUtxos(address,JSONArray().put(row(true,"200000000")),true)}
        assertThrows(IllegalArgumentException::class.java){NodeAgreement.parseUtxos(address,JSONArray().put(row(true,200000000.5)),true)}
    }
    @Test fun sameHostIdentityStaleOrTestnetNodesCannotFormQuorum() {
        assertThrows(IllegalArgumentException::class.java){agree(ep=listOf(nodes[0],nodes[0]))}
        assertThrows(IllegalArgumentException::class.java){agree(ep=listOf(nodes[0],nodes[1].copy(uid=nodes[0].uid)))}
        assertThrows(IllegalArgumentException::class.java){agree(daa=listOf(0,1201))}
        val info=JSONObject().put("networkId","testnet-10").put("isSynced",true).put("hasUtxoIndex",true).put("virtualDaaScore",100)
        assertThrows(IllegalArgumentException::class.java){NodeAgreement.serverInfo(info)}
        info.put("networkId","mainnet").put("isSynced",false)
        assertThrows(IllegalArgumentException::class.java){NodeAgreement.serverInfo(info)}
        for(url in listOf("ws://emma.kaspa.stream/kaspa/mainnet/wrpc/json","wss://localhost/kaspa/mainnet/wrpc/json","wss://emma.kaspa.stream@evil.example/kaspa/mainnet/wrpc/json","wss://emma.kaspa.stream/kaspa/testnet-10/wrpc/json"))
            assertThrows(IllegalArgumentException::class.java){NodeAgreement.endpoint("uid",url)}
    }
    @Test fun staleProofAndMutatedTransactionCannotReachSigningGate() {
        val witness=agree().witness;val tx=KaspaTx.Tx(inputs=listOf(KaspaTx.Input(coin)),outputs=listOf(KaspaTx.Output(199800000,script=script)))
        NodeAgreement.requireFresh(witness,address,tx,1000)
        assertThrows(IllegalArgumentException::class.java){NodeAgreement.requireFresh(witness,address,tx,31001)}
        assertThrows(IllegalArgumentException::class.java){NodeAgreement.requireFresh(null,address,tx,1000)}
        assertThrows(IllegalStateException::class.java){NodeAgreement.requireFresh(witness,address,tx.copy(inputs=listOf(KaspaTx.Input(coin.copy(amount=coin.amount+1)))),1000)}
    }
    @Test fun positiveChainMembershipIsRequiredNotJustIndexerApprovalOrUnspentInputs() {
        val id="01".repeat(32);val block="02".repeat(32)
        val json=JSONObject().put("removedChainBlockHashes",JSONArray()).put("addedChainBlockHashes",JSONArray().put(block)).put("acceptedTransactionIds",JSONArray().put(JSONObject().put("acceptingBlockHash",block).put("acceptedTransactionIds",JSONArray().put(id))))
        assertTrue(NodeAgreement.acceptedInChain(json,id,block))
        assertFalse(NodeAgreement.acceptedInChain(json,"03".repeat(32),block))
        json.put("removedChainBlockHashes",JSONArray().put(block));assertFalse(NodeAgreement.acceptedInChain(json,id,block))
        json.put("removedChainBlockHashes",JSONArray()).put("addedChainBlockHashes",JSONArray());assertFalse(NodeAgreement.acceptedInChain(json,id,block))
    }
}
