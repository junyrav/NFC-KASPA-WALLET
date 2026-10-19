package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Read-only public-network smoke test. No funded transaction, card or user address is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NodeLiveTest {
    @Test fun twoPublicNodesAndRestAgreeOnAnUnusedDeterministicAddress() {
        assumeTrue(System.getProperty("kaspa.liveChecks")=="true")
        val key=Crypto.sha("kaspa-nfc-wallet-public-read-only-check-v011".toByteArray())
        val address=KaspaAddress.mainnet(key);key.fill(0)
        val result=runBlocking{NodeQuorum.verified(address)}
        assertEquals(2,result.witness.nodes.map{it.host}.toSet().size)
        assertEquals(2,result.witness.nodes.map{it.uid}.toSet().size)
        assertTrue(result.utxos.isEmpty())
        println("READ_ONLY_NODE_CHECK_OK: REST + 2 PNN identities; native signing vectors run separately")
    }
    @Test fun aRealNonEmptyRestUtxoMatchesBothNodeScriptAndIntegerEncodings() {
        assumeTrue(System.getProperty("kaspa.liveChecks")=="true")
        // Public REST documentation address first; recent block outputs are a read-only fallback.
        val addresses=linkedSetOf("kaspa:qqkqkzjvr7zwxxmjxjkmxxdwju9kjs6e9u82uh59z07vgaks6gg62v8707g73")
        val nodes=NodeQuorum.resolve()
        fun probe():Boolean {
            for(address in addresses.toList()) {
                val success=runCatching {
                    KaspaAddress.recipientPayload(address)
                    val rows=JSONArray(KaspaNetwork.request("GET","/addresses/$address/utxos"))
                    if(rows.length() !in 1..NodeAgreement.MAX_UTXOS)return@runCatching false
                    val rest=NodeAgreement.parseUtxos(address,rows,false)
                    val first=rest.first();val fingerprint=NodeAgreement.digest(listOf(first))
                    nodes.all { node->WrpcSession(node.url).use { rpc->
                        NodeAgreement.serverInfo(rpc.call("getServerInfo"))
                        val entries=rpc.call("getUtxosByAddresses",JSONObject().put("addresses",JSONArray().put(address))).getJSONArray("entries")
                        val parsed=NodeAgreement.parseUtxos(address,entries,true)
                        val matching=parsed.filter { it.outpoint.transactionId.contentEquals(first.outpoint.transactionId)&&it.outpoint.index==first.outpoint.index }
                        matching.size==1&&NodeAgreement.digest(matching)==fingerprint
                    }}
                }.getOrDefault(false)
                if(success){println("READ_ONLY_NONEMPTY_UTXO_CHECK_OK: actual REST entries match both public nodes; no signing or broadcast");return true}
                addresses.remove(address)
            }
            return false
        }
        if(probe())return
        var hash=JSONObject(KaspaNetwork.request("GET","/info/blockdag")).getJSONArray("virtualParentHashes").getString(0)
        repeat(6) {
            val block=JSONObject(KaspaNetwork.request("GET","/blocks/$hash?includeTransactions=true"))
            val transactions=block.getJSONArray("transactions")
            for(i in 0 until transactions.length()) {
                val outputs=transactions.getJSONObject(i).getJSONArray("outputs")
                for(j in 0 until outputs.length()) {
                    val address=outputs.getJSONObject(j).optJSONObject("verboseData")?.optString("scriptPublicKeyAddress").orEmpty()
                    if(address.startsWith("kaspa:"))addresses.add(address)
                    if(addresses.size>=12)break
                }
                if(addresses.size>=12)break
            }
            if(probe())return
            hash=block.getJSONObject("verboseData").getString("selectedParentHash")
        }
        fail("No non-empty public UTXO could be cross-checked; this populated-response release check must not silently skip")
    }
}
