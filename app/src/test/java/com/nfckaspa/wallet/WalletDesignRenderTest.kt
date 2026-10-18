package com.nfckaspa.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
        compose.runOnIdle {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val view=activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir=File("build/visual-review").apply{mkdirs()}
            File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun renderFiveReferenceScreensAndReceive() {
        val address=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1})
        val base=WalletVisualState(backupVerified=true,address=address,balance="2,000.00000000 KAS".replace(",",""),to=address,amount="100",fee=10000,
            cards=setOf("A","B","C"),dates=mapOf("A" to "2026.10.17","B" to "2026.10.17","C" to "2026.10.17"),krw=122.8,usd=.09,change=2.5)
        var state by mutableStateOf(base)
        var overwrite by mutableStateOf(false)
        compose.setContent { KaspaWalletTheme { WalletDesign(state,DesignActions());if(overwrite)OverwriteConsentDialog("기존 등록: B 카드\n새 등록: 현재 지갑의 A 카드\n\n같은 슬롯 카드가 중복되면 기존 지갑을 복구하지 못할 수 있습니다. 계속하려면 영향을 확인하세요.",false,{}, {}, {}) } }
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
        compose.onNodeWithContentDescription("메인넷 수신 주소 QR").assertIsDisplayed();capture("06-receive")
        compose.runOnIdle{state=base.copy(tab="Transactions",history=listOf(
            WalletStore.Transfer(address,"a".repeat(64),address,"100.00000000",10000,1792233120000,"in","accepted"),
            WalletStore.Transfer(address,"b".repeat(64),address,"1.00000000",10000,1792233000000,"out","submitted")
        ))}
        compose.onNodeWithText("입금").assertIsDisplayed()
        compose.onNodeWithText("출금").assertIsDisplayed()
        compose.onNodeWithText("100.00000000 KAS").assertIsDisplayed()
        compose.onNodeWithText("네트워크 승인").assertIsDisplayed()
        capture("07-transactions")
        compose.runOnIdle{overwrite=true}
        compose.onNodeWithText("덮어쓰기").assertIsNotEnabled();capture("08-overwrite-consent")
    }
}
