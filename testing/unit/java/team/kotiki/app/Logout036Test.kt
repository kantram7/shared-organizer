package team.kotiki.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class Logout036Test {
    private val context get()=RuntimeEnvironment.getApplication() as Context
    @org.junit.Before fun grantNotifications(){shadowOf(context as android.app.Application).grantPermissions("android.permission.POST_NOTIFICATIONS")}
    private fun snapshot()=JSONObject().put("viewer","USER_1").put("canonicalUid","person-a").put("spaceId","space-a")
    private val binding=PushBinding("auth-a","person-a","space-a",Person.USER_1)

    @Test fun reminderRequiresCurrentAuthAndTheSameCanonicalSpaceAndRole() {
        assertTrue(reminderProfileMatches("auth-a",snapshot(),"auth-a",binding))
        assertFalse(reminderProfileMatches("auth-a",snapshot(),null,binding))
        assertFalse(reminderProfileMatches("auth-a",snapshot(),"auth-b",binding))
        assertFalse(reminderProfileMatches("auth-a",snapshot(),"auth-a",null))
        assertFalse(reminderProfileMatches("auth-a",snapshot(),"auth-a",binding.copy(canonicalUid="person-b")))
        assertFalse(reminderProfileMatches("auth-a",snapshot(),"auth-a",binding.copy(spaceId="space-b")))
        assertFalse(reminderProfileMatches("auth-a",snapshot(),"auth-a",binding.copy(viewer=Person.USER_2)))
        assertFalse(reminderProfileMatches("auth-a",JSONObject().put("viewer","USER_1"),"auth-a",binding))
        assertTrue(reminderProfileMatches("demo/USER_1",JSONObject().put("viewer","USER_1"),null,null))
        assertFalse(reminderProfileMatches("demo/USER_2",JSONObject().put("viewer","USER_1"),null,null))
    }

    @Test fun inFlightSnapshotKeepsItsCapturedIdentityAfterMembershipRebind() {
        val app=context;val uid=binding.authUid;val live=binding.copy(canonicalUid="person-b",spaceId="space-b")
        app.getSharedPreferences("android_push",0).edit().putString("authUid",uid).putString("canonicalUid",live.canonicalUid)
            .putString("spaceId",live.spaceId).putString("viewer",live.viewer.name).commit()
        LocalReminders.sync(app,uid,emptyList(),Person.USER_1,binding)
        val saved=ReminderPreferences(app).snapshot(uid)!!
        assertEquals("person-a",saved.getString("canonicalUid"))
        assertEquals("space-a",saved.getString("spaceId"))
        assertFalse(reminderProfileMatches(uid,saved,uid,live))
        AndroidPush.clearLocal(app,uid)
    }

    @Test fun logoutClearsImmediatelyAndFinishesWhenRemoteCallbackArrives() {
        val events=mutableListOf<String>();var reply:(()->Unit)?=null
        AccountLogout.finishAfterUnregister(unregister={reply=it;events+="remote"},clearLocal={events+="clear"},signOut={events+="signout"},onComplete={events+="done"})
        assertEquals(listOf("clear","remote"),events)
        reply!!();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("clear","remote","clear","signout","done"),events)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1,events.count{it=="signout"})
    }

    @Test fun offlineLogoutFinishesAndLateCallbackCannotClearTheNextAccount() {
        var clears=0;var signouts=0;var completed=0;var reply:(()->Unit)?=null
        AccountLogout.finishAfterUnregister(unregister={reply=it},clearLocal={clears++},signOut={signouts++},onComplete={completed++})
        assertEquals(1,clears);assertEquals(0,signouts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2,clears);assertEquals(1,signouts);assertEquals(1,completed)
        reply!!();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2,clears);assertEquals(1,signouts);assertEquals(1,completed)
    }

    @Test fun deniedUnregisterStillFinishesLocalLogout() {
        var cleared=0;var signedOut=false
        AccountLogout.finishAfterUnregister(unregister={throw SecurityException("revoked")},clearLocal={cleared++},signOut={signedOut=true},onComplete={})
        assertTrue(signedOut);assertEquals(2,cleared)
    }

    @Test fun cleanupCancelsOnlyDepartingAccountsAlarmsAndPostedNotifications() {
        val app=context;val one="logout-one";val two="logout-two";val day=LocalDate.now().plusDays(2)
        val entry=Entry("reminder-one","Private title",day,mondayOf(day),start="19:00",end="20:00",kind=Kind.EVENT)
        LocalReminders.sync(app,one,listOf(entry),Person.USER_1)
        LocalReminders.sync(app,two,listOf(entry),Person.USER_1)
        val manager=app.getSystemService(NotificationManager::class.java)
        LocalReminders.createChannel(app)
        manager.notify("push-"+one+"-event",0,NotificationCompat.Builder(app,LocalReminders.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Update").build())
        manager.notify("retired-entry-not-in-snapshot",1,NotificationCompat.Builder(app,LocalReminders.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).addExtras(NotificationReadState.extras(one,NotificationReadState.entry("removed"))).build())
        manager.notify("other-account",2,NotificationCompat.Builder(app,LocalReminders.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).addExtras(NotificationReadState.extras(two,NotificationReadState.entry(entry.id))).build())
        app.getSharedPreferences("android_push",0).edit().putString("authUid",one).putString("canonicalUid","person-a").putString("spaceId","space-a").putString("viewer","USER_1").commit()
        AndroidPush.clearLocal(app,one)
        assertNull(AndroidPush.binding(app))
        assertFalse(one in ReminderPreferences(app).profiles());assertTrue(two in ReminderPreferences(app).profiles())
        assertEquals(1,shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        assertEquals(listOf("other-account"),manager.activeNotifications.map{it.tag})
        LocalReminders.clearProfile(app,two);manager.cancelAll()
    }

    @Test fun receiverRejectsPersistedCloudAlarmAfterLogoutButAllowsLocalDemo() {
        val app=context;val manager=app.getSystemService(NotificationManager::class.java)
        val time=LocalDateTime.now().minusMinutes(1);val day=time.toLocalDate()
        val entry=Entry("past","Private title",day,mondayOf(day),start=time.toLocalTime().withSecond(0).withNano(0).toString(),end="23:59",kind=Kind.EVENT)
        val due=reminderInstant(entry,Person.USER_1,EntryReminderPreference(),ZoneId.systemDefault())!!.toEpochMilli()
        fun alarm(uid:String)=Intent().putExtra(LocalReminders.EXTRA_AUTH_UID,uid).putExtra(LocalReminders.EXTRA_ENTRY_ID,entry.id).putExtra("reminder_due_at",due)
        app.getSharedPreferences("android_push",0).edit().clear().commit()
        LocalReminders.sync(app,"logged-out",listOf(entry),Person.USER_1)
        LocalReminders.receive(app,alarm("logged-out"))
        assertTrue(manager.activeNotifications.isEmpty())
        LocalReminders.sync(app,"demo/USER_1",listOf(entry),Person.USER_1)
        LocalReminders.receive(app,alarm("demo/USER_1"))
        assertEquals(1,manager.activeNotifications.size)
        LocalReminders.clearProfile(app,"logged-out");LocalReminders.clearProfile(app,"demo/USER_1")
    }
}
