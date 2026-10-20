package com.nfckaspa.wallet

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SecurityUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun overwriteRequiresAcknowledgementAndNeverWritesOnCheckboxOnly() {
        var accepted by mutableStateOf(false);var confirmations=0
        compose.setContent{KaspaWalletTheme{OverwriteConsentDialog("기존 B 카드를 A 카드로 바꾸면 복구에 영향을 줍니다.",accepted,{accepted=it},{confirmations++},{})}}
        compose.onNodeWithText("덮어쓰기").assertIsNotEnabled()
        compose.onNode(isToggleable()).performClick()
        assertEquals(0,confirmations)
        compose.onNodeWithText("덮어쓰기").assertIsEnabled().performClick()
        assertEquals(1,confirmations)
    }
    @Test fun creatingWalletRequiresMatchingStrongConfirmation() {
        var state by mutableStateOf(WalletVisualState(password="Correct-Backup!2026",passwordConfirm="wrong"))
        compose.setContent{KaspaWalletTheme{WalletDesign(state,DesignActions())}}
        compose.onNodeWithText("새 메인넷 지갑 생성").assertIsNotEnabled()
        compose.runOnIdle{state=state.copy(passwordConfirm="Correct-Backup!2026")}
        compose.onNodeWithText("새 메인넷 지갑 생성").assertIsEnabled()
    }
    @Test fun confirmationContainsFullRecipientAddress() {
        val to=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=2})
        compose.setContent{KaspaWalletTheme{WalletDesign(WalletVisualState(address=to,to=to,amount="1",fee=2000,page="Confirm"),DesignActions())}}
        compose.onNodeWithText(to).assertIsDisplayed()
    }
    @Test fun unspendableRecipientDisablesNextAndMaximumUntilReplaced() {
        val invalid=KaspaAddress.fromPublicKey(ByteArray(32))
        var state by mutableStateOf(WalletVisualState(address=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1}),to=invalid,amount="1",page="Send"))
        compose.setContent{KaspaWalletTheme{WalletDesign(state,DesignActions())}}
        compose.onNodeWithText("다음").assertIsNotEnabled()
        compose.onNodeWithText("전액 입력").assertIsNotEnabled()
        compose.onNodeWithText("주소 형식·체크섬·공개키를 확인하세요").assertExists()
        compose.runOnIdle{state=state.copy(to=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=2}))}
        compose.onNodeWithText("다음").assertIsEnabled()
    }
    @Test fun interruptedInitialRegistrationOffersOneCardContinuation() {
        var calls=0
        compose.setContent{KaspaWalletTheme{WalletDesign(WalletVisualState(address=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1}),tab="Cards",cards=setOf("A"),password="Kasp8!xy",resumeAvailable=true),DesignActions(resumeRegistration={calls++}))}}
        compose.onNodeWithText("등록한 카드로 이어서 등록").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1,calls)
    }
    @Test fun eightCharacterPasswordWithMatchingConfirmationEnablesCreation() {
        compose.setContent{KaspaWalletTheme{WalletDesign(WalletVisualState(password="Kasp8!xy",passwordConfirm="Kasp8!xy"),DesignActions())}}
        compose.onNodeWithText("새 메인넷 지갑 생성").assertIsEnabled()
    }
    @Test fun legacyCardsRequireReregistrationRatherThanASeparateCleanupButton() {
        compose.setContent{KaspaWalletTheme{WalletDesign(WalletVisualState(address=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1}),tab="Cards",cards=setOf("A","B","C"),password="Kasp8!xy",recoveryVerified=true),DesignActions())}}
        compose.onNodeWithText("카드 3장 검증 완료 · 새 등록 방식 확인 0/3").assertExists()
        compose.onNodeWithText("이전 데이터 정리").assertDoesNotExist()
    }
}
