package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import android.view.WindowManager.LayoutParams.FLAG_SECURE

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ScreenCapturePolicyTest {
    @Test fun capturesAreOptInAndPlaintextSecretsAlwaysRemainSecure() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try{val window=controller.get().window
            ScreenCapturePolicy.apply(window,false,false);assertTrue(window.attributes.flags and FLAG_SECURE!=0)
            ScreenCapturePolicy.apply(window,true,false);assertEquals(0,window.attributes.flags and FLAG_SECURE)
            ScreenCapturePolicy.apply(window,true,true);assertTrue(window.attributes.flags and FLAG_SECURE!=0)
            ScreenCapturePolicy.apply(window,false,false);assertTrue(window.attributes.flags and FLAG_SECURE!=0)
        }finally{controller.pause().stop().destroy()}
    }
}
