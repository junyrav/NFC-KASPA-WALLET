package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

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
}
