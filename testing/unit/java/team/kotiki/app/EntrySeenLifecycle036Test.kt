package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class EntrySeenLifecycle036Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun backgroundOrCoveredCardDoesNotBecomeSeenDuringDwell() {
        lateinit var state:EntryNewState
        lateinit var owner:LifecycleOwner
        var cardBounds=Rect.Zero
        var rootBounds=Rect.Zero
        val day=java.time.LocalDate.now()
        val entry=Entry(newId(),"Incoming",day,mondayOf(day),owner=Person.USER_2,who=Person.USER_1)
        ui.mainClock.autoAdvance=false
        ui.activityRule.scenario.onActivity { a->
            state=EntryNewState(a,Person.USER_1,null)
            owner=object:LifecycleOwner {
                val registry=LifecycleRegistry(this)
                override val lifecycle:Lifecycle get()=registry
            }.apply{registry.currentState=Lifecycle.State.STARTED}
            a.setContent{KotikiTheme("light"){CompositionLocalProvider(LocalEntryNewState provides state,LocalLifecycleOwner provides owner){
                // A full window host makes the product's boundsInWindow/root-overlap guard real.
                // The small wrap-content host previously raced Android's initial measure pass.
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Box(Modifier.size(220.dp,80.dp).testTag("incoming-card").onGloballyPositioned { coordinates->
                        cardBounds=coordinates.boundsInWindow()
                        rootBounds=coordinates.findRootCoordinates().boundsInWindow()
                    }.markNewEntry(entry)){Text(entry.title)}
                }
            }}}
        }
        val prefs=ui.activity.getSharedPreferences("entry_seen_USER_1_",0)
        fun settleFrame() {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle() // Native Android measure/layout is separate from the Compose test clock.
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        fun assertUnseen()=ui.runOnIdle {
            assertFalse(prefs.getBoolean(entry.id,false))
            state.refresh();assertTrue(state.isNew(entry))
        }
        settleFrame()
        ui.onNodeWithTag("incoming-card").assertIsDisplayed()
        ui.runOnIdle {
            assertTrue("Card must be laid out before dwell checks",cardBounds.width>0 && cardBounds.height>0)
            val overlap=cardBounds.intersect(rootBounds)
            assertTrue("Card must meet the actual visibility threshold",overlap.width>cardBounds.width*.6f && overlap.height>cardBounds.height*.6f)
        }
        // A genuinely visible card in a background lifecycle must not become seen.
        ui.mainClock.advanceTimeBy(1000);ui.waitForIdle();assertUnseen()
        // Start a real dwell while resumed, then leave before its 350 ms deadline.
        // This catches a one-time pre-delay lifecycle check as well as a missing cancellation.
        ui.runOnIdle{(owner.lifecycle as LifecycleRegistry).currentState=Lifecycle.State.RESUMED}
        settleFrame()
        ui.mainClock.advanceTimeBy(100)
        ui.runOnIdle{assertFalse(prefs.getBoolean(entry.id,false));(owner.lifecycle as LifecycleRegistry).currentState=Lifecycle.State.STARTED}
        settleFrame()
        ui.mainClock.advanceTimeBy(1000);ui.waitForIdle();assertUnseen()
        // Resuming while another app surface covers the card must not start its dwell timer.
        ui.runOnIdle{state.active=false;(owner.lifecycle as LifecycleRegistry).currentState=Lifecycle.State.RESUMED}
        settleFrame()
        ui.mainClock.advanceTimeBy(1000);ui.waitForIdle();assertUnseen()
        // Only the same visible card, now uncovered and resumed, may become seen.
        ui.runOnIdle{state.active=true}
        settleFrame()
        ui.onNodeWithTag("incoming-card").assertIsDisplayed()
        ui.mainClock.advanceTimeUntil(timeoutMillis=5000) {
            prefs.getBoolean(entry.id,false)
        }
        ui.waitForIdle()
        ui.runOnIdle{state.refresh();assertFalse(state.isNew(entry))}
    }
}
