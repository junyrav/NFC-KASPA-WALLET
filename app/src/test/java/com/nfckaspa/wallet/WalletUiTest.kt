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
    @Before fun clear() { RuntimeEnvironment.getApplication().getSharedPreferences("kaspa_nfc_wallet",0).edit().clear().commit() }
    @Test fun emptyWalletShowsOnboarding() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("지갑 시작하기").assertIsDisplayed()
            compose.onNodeWithText("새 Testnet 지갑 생성").assertIsNotEnabled()
        }
    }
    @Test fun savedWalletReloadsAcrossActivityRecreationAndTabsWork() {
        val c=RuntimeEnvironment.getApplication()
        WalletStore.saveAddress(c,KaspaAddress.testnet(KaspaAddress.newPrivateKey()))
        WalletStore.saveCards(c,setOf("A","B"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("TOTAL BALANCE").assertIsDisplayed()
            compose.onNodeWithText("지갑 시작하기").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithText("TOTAL BALANCE").assertIsDisplayed()
            compose.onNodeWithText("거래내역",useUnmergedTree=true).performClick()
            compose.onNodeWithText("아직 제출한 거래가 없습니다").assertIsDisplayed()
            compose.onNodeWithText("카드관리",useUnmergedTree=true).performClick()
            compose.onNodeWithText("카드 관리").assertIsDisplayed()
            compose.onNodeWithText("설정",useUnmergedTree=true).performClick()
            compose.onNodeWithText("지갑 주소 자동 저장   사용 중").assertExists()
            compose.onNodeWithText("지갑",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Send · 보내기").performClick()
            compose.onNodeWithText("받는 주소 · kaspatest:").assertExists()
            compose.onNodeWithText("‹ 지갑으로").performClick()
            compose.onNodeWithText("Receive · 받기").performClick()
            compose.onNodeWithContentDescription("TN10 수신 주소 QR").assertExists()
        }
    }
}
