package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PendingRecoveryTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val prefs get()=c.getSharedPreferences("kaspa_nfc_wallet_mainnet",0)
    private val key=ByteArray(32).apply{this[31]=1}
    private val wallet=KaspaAddress.mainnet(key)
    private val tx get()=KaspaTx.Tx(inputs=listOf(KaspaTx.Input(KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32){1},3),200000000,0,KaspaTx.p2pkScript(KaspaAddress.xOnlyPublicKey(key))),signatureScript=ByteArray(66))),outputs=listOf(KaspaTx.Output(199800000,script=KaspaTx.p2pkScript(KaspaAddress.xOnlyPublicKey(key)))))
    private fun pending()=WalletStore.Pending(wallet,KaspaTx.transactionId(tx),KaspaTransaction.signedRequest(tx),wallet,"1.998",200000,123)
    @Before fun clear(){prefs.edit().clear().commit();listOf("",".bak",".new").forEach{java.io.File(c.filesDir,"pending-payment-v3.json$it").delete()};WalletStore.saveAddress(c,wallet)}
    @Test fun damagedPrimaryRecoversMirrorAndDualDamageStaysLocked() {
        val p=pending();WalletStore.savePending(c,p)
        prefs.edit().putString("pending_transaction","broken").commit();assertEquals(p,WalletStore.pending(c))
        java.io.File(c.filesDir,"pending-payment-v3.json").writeText("broken")
        assertEquals("damaged-record",WalletStore.pending(c)!!.txid)
        assertThrows(IllegalStateException::class.java){WalletStore.savePending(c,p)}
    }
    @Test fun abandonmentRequiresSpecificRecordAndExplicitRiskAcknowledgement() {
        val p=pending();WalletStore.savePending(c,p)
        assertThrows(IllegalArgumentException::class.java){WalletStore.abandonPending(c,false,wallet.takeLast(8),p.txid)}
        assertThrows(IllegalArgumentException::class.java){WalletStore.abandonPending(c,true,"wrong",p.txid)}
        assertThrows(IllegalArgumentException::class.java){WalletStore.abandonPending(c,true,wallet.takeLast(8),"different")}
        WalletStore.abandonPending(c,true,wallet.takeLast(8),p.txid)
        assertNull(WalletStore.pending(c));assertEquals(p.request,JSONArray(prefs.getString("abandoned_payments","[]")).getJSONObject(0).getString("request"))
    }
    @Test fun onlyPositiveAcceptanceOrAcceptedInputConflictResolvesPayment() {
        val p=pending();val hash="01".repeat(32)
        fun row(id:String,accepted:Boolean)=JSONObject().put("transaction_id",id).put("is_accepted",accepted).put("inputs",JSONArray().put(JSONObject().put("previous_outpoint_hash",hash).put("previous_outpoint_index","3")))
        assertEquals(KaspaBroadcast.Resolution.UNKNOWN,KaspaBroadcast.evidence(p.txid,p.request,"[]"))
        assertEquals(KaspaBroadcast.Resolution.UNKNOWN,KaspaBroadcast.evidence(p.txid,p.request,JSONArray().put(row(p.txid,false)).toString()))
        assertEquals(KaspaBroadcast.Resolution.ACCEPTED,KaspaBroadcast.evidence(p.txid,p.request,JSONArray().put(row(p.txid,true)).toString()))
        assertEquals(KaspaBroadcast.Resolution.CONFLICTED,KaspaBroadcast.evidence(p.txid,p.request,JSONArray().put(row("ab".repeat(32),true)).toString()))
    }
    @Test fun storedRequestMustMatchLocallyComputedTransactionIdBeforeResending() {
        val p=pending();KaspaBroadcast.validateStoredRequest(p.txid,p.request)
        val changed=JSONObject(p.request);changed.getJSONObject("transaction").getJSONArray("outputs").getJSONObject(0).put("amount",1)
        assertThrows(IllegalArgumentException::class.java){KaspaBroadcast.validateStoredRequest(p.txid,changed.toString())}
    }
    @Test fun verifiedGenerationStillRequiresAllThreeCardsCleaned() {
        WalletStore.beginProvision(c);val generation=WalletStore.generation(c)
        WalletStore.saveSecureCards(c,setOf("A","B","C"));WalletStore.verifyBackup(c)
        assertTrue(WalletStore.recoveryVerified(c));assertFalse(WalletStore.backupVerified(c))
        listOf("A","B","C").forEach{TestCleanup.mark(c,Slot.valueOf(it))};assertTrue(WalletStore.backupVerified(c))
        WalletStore.beginProvision(c);assertNotEquals(generation,WalletStore.generation(c));assertFalse(WalletStore.backupVerified(c))
    }
}
