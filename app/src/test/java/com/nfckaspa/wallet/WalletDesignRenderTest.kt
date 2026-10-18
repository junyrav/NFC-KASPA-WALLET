package com.nfckaspa.wallet

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Reference-layout review uses deterministic display data, never a live wallet or submission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletDesignRenderTest {
    @get:Rule val compose=createComposeRule()
    private fun capture(name:String) {
        compose.waitForIdle()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val dir=File("build/visual-review").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun renderFiveReferenceScreensAndReceive() {
        val address=KaspaAddress.testnet(ByteArray(32).apply{this[31]=1})
        val base=WalletVisualState(address=address,balance="2,000.00000000 KAS".replace(",",""),to=address,amount="100",fee=10000,
            cards=setOf("A","B","C"),dates=mapOf("A" to "2026.10.17","B" to "2026.10.17","C" to "2026.10.17"),krw=122.8,usd=.09,change=2.5)
        var state by mutableStateOf(base)
        compose.setContent { KaspaWalletTheme { WalletDesign(state,DesignActions()) } }
        compose.onNodeWithText("내 지갑 잔액").assertIsDisplayed()
        capture("01-wallet")
        compose.runOnIdle{state=base.copy(page="Send")}
        compose.onNodeWithText("KAS 보내기").assertIsDisplayed();capture("02-send")
        compose.runOnIdle{state=base.copy(page="Confirm")}
        compose.onNodeWithText("송금 확인").assertIsDisplayed();capture("03-confirm")
        compose.runOnIdle{state=base.copy(page="Success",txid="1564558d52d58024e527c2".padEnd(64,'a'),sentAt=1792233120000)}
        compose.onNodeWithText("송금이 제출되었습니다.").assertIsDisplayed();capture("04-success")
        compose.runOnIdle{state=base.copy(tab="Settings")}
        compose.onNodeWithText("표시 설정").assertIsDisplayed();capture("05-settings")
        compose.onNodeWithText("오픈소스 라이선스").performScrollTo()
        capture("05-settings-lower")
        compose.runOnIdle{state=base.copy(page="Receive")}
        compose.onNodeWithContentDescription("TN10 수신 주소 QR").assertExists();capture("06-receive")
    }
}
