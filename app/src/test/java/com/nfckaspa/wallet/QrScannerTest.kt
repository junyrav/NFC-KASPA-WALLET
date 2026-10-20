package com.nfckaspa.wallet

import android.content.ComponentName
import android.content.pm.ActivityInfo
import com.journeyapps.barcodescanner.ScanOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class QrScannerTest {
    @Test fun scannerUsesPrivatePortraitActivityInsteadOfDefaultLandscapeActivity() {
        val context=RuntimeEnvironment.getApplication()
        val intent=ScanOptions().setCaptureActivity(QrCaptureActivity::class.java)
            .setOrientationLocked(true).createScanIntent(context)
        val expected=ComponentName(context,QrCaptureActivity::class.java)
        assertEquals(expected,intent.component)
        val info=context.packageManager.getActivityInfo(expected,0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,info.screenOrientation)
        assertFalse(info.exported)
    }
}
