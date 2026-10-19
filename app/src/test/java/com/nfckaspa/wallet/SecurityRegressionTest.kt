package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SecurityRegressionTest {
    private val c get()=RuntimeEnvironment.getApplication()
    @Before fun clear(){listOf("",".bak",".new").forEach{java.io.File(c.filesDir,"pending-payment-v3.json$it").delete()};c.getSharedPreferences("kaspa_nfc_wallet_mainnet",0).edit().clear().commit()}
    @Test fun perOperationBiometricKeyInvalidatesWithEnrollmentOnAndroid28() {
        val spec=Crypto.keySpec()
        assertTrue(spec.isUserAuthenticationRequired);assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertEquals(-1,spec.userAuthenticationValidityDurationSeconds)
    }
    @Test fun backupGateRequiresThreeSecureCardsAndIndependentRecoveryVerification() {
        WalletStore.saveCards(c,setOf("A","B","C"));assertFalse(WalletStore.backupVerified(c))
        WalletStore.saveSecureCards(c,setOf("A","B"));assertThrows(IllegalStateException::class.java){WalletStore.verifyBackup(c)}
        WalletStore.saveSecureCards(c,setOf("A","B","C"));assertFalse(WalletStore.backupVerified(c))
        WalletStore.verifyBackup(c);assertFalse(WalletStore.backupVerified(c))
        listOf("A","B","C").forEach{WalletStore.markClean(c,it)};assertTrue(WalletStore.backupVerified(c))
        WalletStore.beginProvision(c);assertFalse(WalletStore.backupVerified(c))
    }
    @Test fun uncertainPaymentPersistsAndCannotBeReplacedByNewPayment() {
        val p=WalletStore.Pending("wallet","a".repeat(64),"signed-bytes","recipient","1",2000,123)
        WalletStore.savePending(c,p)
        val fresh=c.createConfigurationContext(c.resources.configuration)
        assertEquals(p,WalletStore.pending(fresh))
        assertThrows(IllegalStateException::class.java){WalletStore.savePending(c,p.copy(txid="b".repeat(64)))}
        assertThrows(IllegalStateException::class.java){WalletStore.clearPending(c,"other")}
        WalletStore.clearPending(c,p.txid);assertNull(WalletStore.pending(c))
    }
    @Test fun corruptPendingRecordDoesNotUnlockAnotherPayment() {
        c.getSharedPreferences("kaspa_nfc_wallet_mainnet",0).edit().putString("pending_transaction","damaged").commit()
        assertNotNull(WalletStore.pending(c))
    }
}
