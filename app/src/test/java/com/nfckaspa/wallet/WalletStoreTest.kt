package com.nfckaspa.wallet

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WalletStoreTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("kaspa_nfc_wallet",0).edit().clear().commit() }
    @Test fun walletAutomaticallyReloadsWithoutSecretsOrCards() {
        val address=KaspaAddress.testnet(KaspaAddress.newPrivateKey())
        WalletStore.saveAddress(context,address)
        val recreatedContext=context.createConfigurationContext(context.resources.configuration)
        assertEquals(address,WalletStore.loadAddress(recreatedContext))
        assertEquals(setOf("tn10_wallet_address"),context.getSharedPreferences("kaspa_nfc_wallet",0).all.keys)
        assertTrue(WalletStore.loadCards(recreatedContext).isEmpty())
    }
    @Test fun metadataAndTransfersSurviveContextRecreation() {
        val address=KaspaAddress.testnet(KaspaAddress.newPrivateKey())
        WalletStore.saveCards(context,setOf("A","B"))
        WalletStore.bindCard(context,"001122","A")
        WalletStore.record(context,address,"test-txid",address,"0.1",1234)
        val reloaded=context.createConfigurationContext(context.resources.configuration)
        assertEquals(setOf("A","B"),WalletStore.loadCards(reloaded))
        assertEquals("A",WalletStore.cardOwner(reloaded,"001122"))
        assertEquals("0.1",WalletStore.history(reloaded).single().amount)
        WalletStore.record(context,address,"test-txid",address,"0.1",1234)
        assertEquals(1,WalletStore.history(context).size)
    }
    @Test fun invalidAddressesNeverSaved() {
        assertThrows(IllegalArgumentException::class.java) { WalletStore.saveAddress(context,"kaspatest:invalid") }
        assertEquals("",WalletStore.loadAddress(context))
    }
}
