package com.nfckaspa.wallet

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WalletUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun clear() { RuntimeEnvironment.getApplication().getSharedPreferences("kaspa_nfc_wallet_mainnet",0).edit().clear().commit() }
    @Test fun emptyWalletShowsOnboarding() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("지갑 시작하기").assertIsDisplayed()
            compose.onNodeWithText("새 메인넷 지갑 생성").assertIsNotEnabled()
        }
    }
    @Test fun savedWalletReloadsAcrossActivityRecreationAndTabsWork() {
        val c=RuntimeEnvironment.getApplication()
        WalletStore.saveAddress(c,KaspaAddress.mainnet(KaspaAddress.newPrivateKey()))
        WalletStore.saveCards(c,setOf("A","B","C"))
        WalletStore.saveSecureCards(c,setOf("A","B","C"));WalletStore.verifyBackup(c);listOf("A","B","C").forEach{WalletStore.markClean(c,it)}
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("내 지갑 잔액").assertIsDisplayed()
            compose.onNodeWithText("지갑 시작하기").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithText("내 지갑 잔액").assertIsDisplayed()
            compose.onAllNodesWithText("거래내역",useUnmergedTree=true)[1].performClick()
            compose.onNodeWithText("메인넷 입금 · 출금 내역").assertIsDisplayed()
            compose.onNodeWithText("카드관리",useUnmergedTree=true).performClick()
            compose.onNodeWithText("카드 관리").assertIsDisplayed()
            compose.onNodeWithText("설정",useUnmergedTree=true).performClick()
            compose.onNodeWithText("개인키 잠금").assertExists()
            compose.onNodeWithText("지갑",useUnmergedTree=true).performClick()
            compose.onNodeWithText("보내기").performScrollTo().performClick()
            compose.onNodeWithText("받는 주소 (kaspa)").assertExists()
            compose.onNodeWithContentDescription("돌아가기").performClick()
            compose.onNodeWithText("받기").performScrollTo().performClick()
            compose.onNodeWithContentDescription("메인넷 수신 주소 QR").assertExists()
        }
    }
    @Test fun backgroundingClearsPasswordAndWindowPreventsCapture() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            scenario.onActivity{assert(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)}
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("Correct-Backup!2026")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onAllNodes(hasSetTextAction())[0].assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        }
    }

}
