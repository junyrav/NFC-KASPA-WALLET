package com.nfckaspa.wallet

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic, already-read-back card data for storage/UI tests; no NFC or real share is used. */
internal object TestCleanup {
    fun uid(slot:Slot)="0${slot.n}"
    fun record(c:Context,slot:Slot)=CardRecord(slot,ByteArray(60),ByteArray(77),KaspaAddress.mainnetPayload(WalletStore.loadAddress(c)),1,3,
        WalletStore.generation(c).chunked(2).map{it.toInt(16).toByte()}.toByteArray())
    fun mark(c:Context,slot:Slot) {
        WalletStore.ensureRegistrationRound(c);val record=record(c,slot);WalletStore.bindCard(c,uid(slot),slot.name)
        WalletStore.markClean(c,uid(slot),record,CardSnapshot(record.encode(),ByteArray(288)),WalletStore.cleanupRound(c))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CleanupVerificationTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val prefs get()=c.getSharedPreferences("kaspa_nfc_wallet_mainnet",0)
    @Before fun setup() {
        prefs.edit().clear().commit();WalletStore.saveAddress(c,KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1}))
        WalletStore.beginProvision(c);WalletStore.saveSecureCards(c,setOf("A","B","C"));WalletStore.verifyBackup(c)
    }
    @Test fun repeatedAAndBDoNotReplaceMissingC() {
        repeat(3){TestCleanup.mark(c,Slot.A)}
        assertEquals(setOf("A"),WalletStore.cleanedCards(c));assertFalse(WalletStore.backupVerified(c))
        TestCleanup.mark(c,Slot.B);repeat(3){TestCleanup.mark(c,Slot.A)}
        assertEquals(setOf("A","B"),WalletStore.cleanedCards(c));assertFalse(WalletStore.backupVerified(c))
        TestCleanup.mark(c,Slot.C);assertTrue(WalletStore.backupVerified(c))
    }
    @Test fun oldCompletionFlagsCannotProveCurrentCleanup() {
        prefs.edit().remove("registration_round_v5").putStringSet("cleaned_cards_v3",setOf("A","B","C")).commit()
        assertTrue(WalletStore.recoveryVerified(c));assertFalse(WalletStore.backupVerified(c));assertTrue(WalletStore.cleanedCards(c).isEmpty())
        WalletStore.ensureRegistrationRound(c);assertTrue(WalletStore.cleanedCards(c).isEmpty())
        TestCleanup.mark(c,Slot.A);assertFalse(WalletStore.backupVerified(c))
    }
    @Test fun registrationReceiptsSurviveRecoveryCheckButNotGenerationChange() {
        Slot.entries.forEach{TestCleanup.mark(c,it)};assertTrue(WalletStore.backupVerified(c))
        val round=WalletStore.cleanupRound(c);val record=TestCleanup.record(c,Slot.C)
        WalletStore.verifyBackup(c)
        assertEquals(round,WalletStore.cleanupRound(c));assertTrue(WalletStore.backupVerified(c))
        WalletStore.beginProvision(c);WalletStore.ensureRegistrationRound(c)
        assertNotEquals(round,WalletStore.cleanupRound(c));assertTrue(WalletStore.cleanedCards(c).isEmpty());assertFalse(WalletStore.backupVerified(c))
        assertThrows(IllegalStateException::class.java){WalletStore.markClean(c,TestCleanup.uid(Slot.C),record,CardSnapshot(record.encode(),ByteArray(288)),round)}
    }
    @Test fun threeRegistrationReadbacksAndTwoCardRecoveryAreSufficientWithoutCleanupStep() {
        WalletStore.saveSecureCards(c,setOf("A","B","C"))
        Slot.entries.forEach{TestCleanup.mark(c,it)}
        assertFalse(WalletStore.recoveryVerified(c));assertFalse(WalletStore.backupVerified(c))
        WalletStore.verifyBackup(c);assertTrue(WalletStore.backupVerified(c))
        WalletStore.beginCardReplacement(c,TestCleanup.uid(Slot.C),Slot.C)
        assertFalse(WalletStore.backupVerified(c));assertEquals(setOf("A","B"),WalletStore.cleanedCards(c))
    }
    @Test fun failedReadbackWrongUidAndWrongGenerationNeverAdvanceProgress() {
        WalletStore.ensureRegistrationRound(c);val r=TestCleanup.record(c,Slot.C);val uid=TestCleanup.uid(Slot.C);WalletStore.bindCard(c,uid,"C")
        fun save(record:CardRecord,after:CardSnapshot,id:String=uid)=WalletStore.markClean(c,id,record,after,WalletStore.cleanupRound(c))
        assertThrows(IllegalStateException::class.java){save(r,CardSnapshot(r.encode(),ByteArray(288){1}))}
        assertThrows(IllegalStateException::class.java){save(r,CardSnapshot(r.encode(),byteArrayOf()))}
        assertThrows(IllegalStateException::class.java){save(r,CardSnapshot(r.encode(),ByteArray(288)),"ff")}
        val old=r.copy(generation=ByteArray(16){9})
        assertThrows(IllegalStateException::class.java){save(old,CardSnapshot(old.encode(),ByteArray(288)))}
        assertTrue(WalletStore.cleanedCards(c).isEmpty())
    }
    @Test fun receiptsPersistButUidReassignmentAndDamagedEvidenceFailClosed() {
        Slot.entries.forEach{TestCleanup.mark(c,it)}
        assertTrue(WalletStore.backupVerified(c.createConfigurationContext(c.resources.configuration)))
        WalletStore.bindCard(c,TestCleanup.uid(Slot.C),"A");assertFalse(WalletStore.backupVerified(c))
        prefs.edit().putString("registration_receipts_v5","broken").commit();assertTrue(WalletStore.cleanedCards(c).isEmpty())
    }
}
