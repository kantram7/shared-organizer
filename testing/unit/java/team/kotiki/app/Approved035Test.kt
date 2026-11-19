package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Approved035Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun event()=Entry(UUID.randomUUID().toString(),"Новая встреча",LocalDate.now().plusDays(2),mondayOf(LocalDate.now()),kind=Kind.EVENT,start="19:00",end="20:00")
    @Test fun newEntryHasReminderAndPreferenceExistsBeforeSaveCallback() {
        val entry=event();val account="create-test";var captured=-1
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){
            EntryEditor(entry,{}, {captured=ReminderPreferences(a).preference(account,it.id).minutesBefore},isNew=true,reminderAccount=account)
        }}}
        ui.onNodeWithContentDescription("Настроить моё напоминание").performScrollTo().performClick()
        ui.onNodeWithText("За 1 час").performScrollTo().performClick()
        ui.runOnIdle{assertEquals(30,ReminderPreferences(ui.activity).preference(account,entry.id).minutesBefore)}
        ui.onNodeWithText("Сохранить").performClick()
        ui.runOnIdle{assertEquals(60,captured)}
    }
    @Test fun cancelDoesNotChangePreviouslySavedReminder() {
        val entry=event();val account="cancel-test";var closed=false
        ReminderPreferences(ui.activity).setPreference(account,entry.id,EntryReminderPreference(minutesBefore=15))
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("dark"){
            EntryEditor(entry,{closed=true},{},reminderAccount=account)
        }}}
        ui.onNodeWithContentDescription("Настроить моё напоминание").performScrollTo().performClick()
        ui.onNodeWithText("За 1 час").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Закрыть редактирование").performClick()
        ui.runOnIdle{assertTrue(closed);assertEquals(15,ReminderPreferences(ui.activity).preference(account,entry.id).minutesBefore)}
    }
    @Test fun summaryIsReadOnlyAndUsesTheSavedPersonalValue() {
        val entry=event();val account="view-test"
        ReminderPreferences(ui.activity).setPreference(account,entry.id,EntryReminderPreference(minutesBefore=90))
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){EntryReminderSummary(entry,account)}}}
        ui.onNodeWithText("За 90 мин").assertIsDisplayed().assertHasNoClickAction()
        ui.onNodeWithText("Моё напоминание").assertHasNoClickAction()
        ui.onNodeWithContentDescription("Настроить моё напоминание").assertDoesNotExist()
    }
    @Test fun invalidCustomMinutesDoNotReplaceSavedPreferencesButDisabledCanBeSaved() {
        val entry=event();val account="validation-test";val prefs=ReminderPreferences(ui.activity)
        val draft=EntryReminderDraft(EntryReminderPreference(minutesBefore=15))
        draft.minutes="10081"
        assertFalse(draft.save(ui.activity,account,entry));assertEquals(30,prefs.preference(account,entry.id).minutesBefore)
        draft.enabled=false
        assertTrue(draft.save(ui.activity,account,entry));assertFalse(prefs.preference(account,entry.id).enabled)
        draft.enabled=true;draft.minutes="90"
        assertTrue(draft.save(ui.activity,account,entry));assertEquals(90,prefs.preference(account,entry.id).minutesBefore)
    }
    @Test fun winterIciclesNeverIncreaseCardMeasurement() {
        ui.activityRule.scenario.onActivity{a->a.setContent{
            MaterialTheme {CompositionLocalProvider(LocalWinterDesign provides WinterDesignState(true)) {Column {
                Box(Modifier.testTag("plain").size(120.dp,50.dp))
                Box(Modifier.testTag("ice").winterIcicles("last",8.dp).size(120.dp,50.dp))
                Box(Modifier.testTag("no-gap").winterIcicles("middle").size(120.dp,50.dp))
            }}}
        }}
        val baseline=ui.onNodeWithTag("plain").fetchSemanticsNode().boundsInRoot.size
        assertEquals(baseline,ui.onNodeWithTag("ice").fetchSemanticsNode().boundsInRoot.size)
        assertEquals(baseline,ui.onNodeWithTag("no-gap").fetchSemanticsNode().boundsInRoot.size)
    }
}
