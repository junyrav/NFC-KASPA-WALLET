package com.nfckaspa.wallet

import android.view.Window
import android.view.WindowManager

internal object ScreenCapturePolicy {
    fun apply(window:Window,allowed:Boolean,secretVisible:Boolean) {
        if(allowed&&!secretVisible)window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
