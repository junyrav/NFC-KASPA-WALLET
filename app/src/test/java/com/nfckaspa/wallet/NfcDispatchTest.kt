package com.nfckaspa.wallet

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NfcDispatchTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun clear(){RuntimeEnvironment.getApplication().getSharedPreferences("kaspa_nfc_wallet_mainnet",0).edit().clear().commit()}
    private fun tag():Tag=Tag::class.java.getDeclaredMethod("createMockTag",ByteArray::class.java,IntArray::class.java,Array<Bundle>::class.java)
        .invoke(null,byteArrayOf(1,2,3,4),intArrayOf(),arrayOf<Bundle>()) as Tag
    private fun intent(tag:Tag,action:String=NfcAdapter.ACTION_TECH_DISCOVERED)=Intent(action).putExtra(NfcAdapter.EXTRA_TAG,tag)
    private fun MainActivity.deliver(i:Intent) = MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply{isAccessible=true}.invoke(this,i)
    @Test fun pausedNfcIntentIsDeliveredOnceAfterResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            compose.waitForIdle();scenario.moveToState(Lifecycle.State.STARTED)
            var count=0;val card=tag()
            scenario.onActivity { activity->
                assertEquals(Lifecycle.State.STARTED,activity.lifecycle.currentState)
                val handler:(Tag)->Unit={received->assertSame(card,received);count++}
                ReflectionHelpers.setField(activity,"onTag",handler)
                activity.deliver(intent(card));assertEquals(0,count)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity{assertEquals(1,count)}
            scenario.moveToState(Lifecycle.State.STARTED).moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity{assertEquals(1,count)}
        }
    }
    @Test fun backgroundAndUnrelatedIntentsCannotDeliverTags() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            compose.waitForIdle();scenario.moveToState(Lifecycle.State.STARTED);var count=0
            scenario.onActivity { activity->
                val handler:(Tag)->Unit={count++}
                ReflectionHelpers.setField(activity,"onTag",handler)
                activity.deliver(intent(tag(),Intent.ACTION_VIEW))
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity{assertEquals(0,count)}
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity{it.deliver(intent(tag()))}
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity{it.deliver(intent(tag()))}
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity{assertEquals(0,count)}
        }
    }
}
