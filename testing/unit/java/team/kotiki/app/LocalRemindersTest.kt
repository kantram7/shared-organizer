package team.kotiki.app

import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LocalRemindersTest {
    private val zone=ZoneId.of("Europe/Moscow")
    private val day=LocalDate.of(2026,11,20)
    private fun entry()=Entry("event-a","Тестовое дело",day,mondayOf(day),start="19:00",end="20:00",kind=Kind.EVENT)
    @Test fun defaultsAreThirtyMinutesForTimedAndNineForDay() {
        assertEquals(Instant.parse("2026-11-20T15:30:00Z"),reminderInstant(entry(),Person.USER_1,EntryReminderPreference(),zone))
        assertEquals(Instant.parse("2026-11-20T06:00:00Z"),reminderInstant(entry().copy(start="",end="",kind=Kind.TASK),Person.USER_1,EntryReminderPreference(),zone))
        assertNull(reminderInstant(entry().copy(date=null),Person.USER_1,EntryReminderPreference(),zone))
    }
    @Test fun inactiveAndOtherPersonsEntriesNeverNotify() {
        listOf(entry().copy(done=true),entry().copy(invite=InviteState.PENDING),entry().copy(invite=InviteState.DECLINED),
            entry().copy(who=Person.USER_2),entry().copy(visibility=Visibility.PRIVATE,owner=Person.USER_2),
            entry().copy(visibility=Visibility.BUSY,owner=Person.USER_2)).forEach {
            assertNull(reminderInstant(it,Person.USER_1,EntryReminderPreference(),zone))
        }
        assertNotNull(reminderInstant(entry().copy(invite=InviteState.ACCEPTED),Person.USER_1,EntryReminderPreference(),zone))
        assertNull(reminderInstant(entry(),Person.USER_1,EntryReminderPreference(enabled=false),zone))
    }
    @Test fun personalOffsetsAndDeviceZoneAreRespected() {
        assertEquals(Instant.parse("2026-11-20T16:00:00Z"),reminderInstant(entry(),Person.USER_1,EntryReminderPreference(minutesBefore=0),zone))
        val allDay=entry().copy(start="",end="")
        assertEquals(Instant.parse("2026-11-20T05:15:00Z"),reminderInstant(allDay,Person.USER_1,EntryReminderPreference(dayTime="08:15"),zone))
        assertEquals(Instant.parse("2026-11-20T08:15:00Z"),reminderInstant(allDay,Person.USER_1,EntryReminderPreference(dayTime="08:15"),ZoneId.of("UTC")))
        assertNull(reminderInstant(entry(),Person.USER_1,EntryReminderPreference(minutesBefore=-1),zone))
    }
    @Test fun identitySeparatesProfilesAndEntriesWithoutRawIds() {
        assertNotEquals(reminderIdentity("profile-a","entry-a"),reminderIdentity("profile-b","entry-a"))
        assertNotEquals(reminderIdentity("profile-a","entry-a"),reminderIdentity("profile-a","entry-b"))
        assertEquals(64,reminderIdentity("profile-a","entry-a").length)
    }
    @Test fun settingsAreIsolatedPerProfileAndEntry() {
        val preferences=ReminderPreferences(RuntimeEnvironment.getApplication())
        preferences.setPreference("profile-a","entry-a",EntryReminderPreference(false,10,"08:00"))
        assertFalse(preferences.preference("profile-a","entry-a").enabled)
        assertTrue(preferences.preference("profile-b","entry-a").enabled)
        assertTrue(preferences.preference("profile-a","entry-b").enabled)
    }
    @Test fun scheduleMoveCompleteAndLogoutReplaceOrCancelAlarms() {
        val context=RuntimeEnvironment.getApplication() as Context
        val uid="test-${UUID.randomUUID()}"
        val tomorrow=LocalDate.now().plusDays(2)
        val scheduled=entry().copy(date=tomorrow,lastDate=tomorrow,week=mondayOf(tomorrow))
        val alarms=shadowOf(context.getSystemService(AlarmManager::class.java))
        LocalReminders.sync(context,uid,listOf(scheduled),Person.USER_1)
        assertEquals(1,alarms.scheduledAlarms.size)
        val first=alarms.scheduledAlarms.single().triggerAtTime
        LocalReminders.sync(context,uid,listOf(scheduled.copy(start="20:00")),Person.USER_1)
        assertEquals(1,alarms.scheduledAlarms.size)
        assertEquals(first+3600000,alarms.scheduledAlarms.single().triggerAtTime)
        LocalReminders.sync(context,uid,listOf(scheduled.copy(done=true)),Person.USER_1)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        LocalReminders.sync(context,uid,listOf(scheduled),Person.USER_1)
        LocalReminders.clearProfile(context,uid)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertFalse(uid in ReminderPreferences(context).profiles())
    }
    @Test fun deletedEntriesAndPendingInvitationsCancelExistingAlarms() {
        val context=RuntimeEnvironment.getApplication() as Context
        val uid="test-${UUID.randomUUID()}";val tomorrow=LocalDate.now().plusDays(2)
        val saved=entry().copy(date=tomorrow,lastDate=tomorrow)
        val alarms=shadowOf(context.getSystemService(AlarmManager::class.java))
        LocalReminders.sync(context,uid,listOf(saved),Person.USER_1)
        LocalReminders.sync(context,uid,emptyList(),Person.USER_1)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        LocalReminders.sync(context,uid,listOf(saved),Person.USER_1)
        LocalReminders.sync(context,uid,listOf(saved.copy(invite=InviteState.PENDING)),Person.USER_1)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
    @Test fun privateNotificationHasNeutralPublicCopyAndExactTarget() {
        val context=RuntimeEnvironment.getApplication() as Context
        val notification=LocalReminders.buildNotification(context,"profile-a",entry())
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE,notification.visibility)
        assertFalse(notification.publicVersion.extras.getString(Notification.EXTRA_TEXT)!!.contains(entry().title))
        val intent=shadowOf(notification.contentIntent).savedIntent
        assertEquals(LocalReminders.Target("profile-a",entry().id),LocalReminders.target(intent))
        assertFalse(intent.data.toString().contains("profile-a"))
        val other=LocalReminders.buildNotification(context,"profile-b",entry())
        assertNotEquals(intent.data,shadowOf(other.contentIntent).savedIntent.data)
    }
}
