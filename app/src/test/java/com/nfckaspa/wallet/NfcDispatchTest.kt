package com.nfckaspa.wallet

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NfcDispatchTest {
    private fun tag():Tag=Tag::class.java.getDeclaredMethod("createMockTag",ByteArray::class.java,IntArray::class.java,Array<Bundle>::class.java)
        .invoke(null,byteArrayOf(1,2,3,4),intArrayOf(),arrayOf<Bundle>()) as Tag
    private fun intent(tag:Tag,action:String=NfcAdapter.ACTION_TECH_DISCOVERED)=Intent(action).putExtra(NfcAdapter.EXTRA_TAG,tag)
    private fun MainActivity.deliver(i:Intent) = MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply{isAccessible=true}.invoke(this,i)
    private fun MainActivity.postResume() = MainActivity::class.java.getDeclaredMethod("onPostResume").apply{isAccessible=true}.invoke(this)
    @Test fun pausedNfcIntentIsDeliveredOnceAfterResume() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get();controller.pause()
            assertEquals(Lifecycle.State.STARTED,activity.lifecycle.currentState)
            var count=0;val card=tag()
            ReflectionHelpers.setField(activity,"onTag",{received:Tag->assertSame(card,received);count++})
            activity.deliver(intent(card));assertEquals(0,count)
            controller.resume();activity.postResume()
            assertEquals(1,count)
            activity.postResume();assertEquals(1,count)
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun backgroundAndUnrelatedIntentsCannotDeliverTags() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get();controller.pause();var count=0
            ReflectionHelpers.setField(activity,"onTag",{_:Tag->count++})
            activity.deliver(intent(tag(),Intent.ACTION_VIEW))
            activity.postResume();assertEquals(0,count)
            activity.deliver(intent(tag()))
            controller.stop();activity.postResume();assertEquals(0,count)
            activity.deliver(intent(tag()));activity.postResume();assertEquals(0,count)
            controller.start().resume()
        } finally {controller.pause().stop().destroy()}
    }
}
