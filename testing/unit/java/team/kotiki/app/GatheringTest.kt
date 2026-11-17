package team.kotiki.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GatheringTest {
    private val day=LocalDate.of(2026,11,20)
    private fun valid()=newGathering(Person.USER_1,day).copy(date=day,lastDate=day,start="18:30",end="19:30")
    private fun context():Context {
        val prefix="gathering_${UUID.randomUUID()}_"
        return object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)
        }
    }
    @Test fun datesAndTimesRequireExplicitChoice() {
        val draft=newGathering(Person.USER_2,day)
        assertNull(draft.date);assertEquals("",draft.start);assertEquals("",draft.end)
        assertEquals("Выбери дату",draft.gatheringError())
        val dated=draft.copy(date=day,lastDate=day)
        assertEquals("Выбери время начала",dated.gatheringError())
        assertEquals("Выбери время окончания",dated.copy(start="19:00").gatheringError())
        assertNull(valid().gatheringError())
    }
    @Test fun rejectsInvalidTimeAndChangesToFixedFields() {
        val entry=valid()
        listOf(entry.copy(end="18:30"),entry.copy(end="17:00"),entry.copy(start="25:00"),
            entry.copy(kind=Kind.TASK),entry.copy(who=Person.USER_1),entry.copy(invite=InviteState.NONE),
            entry.copy(visibility=Visibility.PRIVATE),entry.copy(lastDate=day.plusDays(1))).forEach {assertNotNull(it.gatheringError())}
    }
    @Test fun gatheringSurvivesReloadWithoutNotifyingItsAuthor() {
        val context=context();val store=DemoStore(context);val entry=valid()
        store.upsert(entry)
        assertEquals(entry,DemoStore(context).entries.single{it.id==entry.id})
        assertTrue(DemoStore(context).entries.filter{it.id!=entry.id}.none{it.isGathering})
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
    @Test fun invalidGatheringCannotBePersisted() {
        val context=context();val store=DemoStore(context);val draft=newGathering(store.viewer)
        assertThrows(IllegalArgumentException::class.java){store.upsert(draft)}
        assertFalse(DemoStore(context).entries.any{it.id==draft.id})
    }
    @Test fun invitationNotificationHasDedicatedChannelPrivateContentAndExactTarget() {
        val context=context();val entry=valid();val identity=DemoIdentity()
        GatheringNotifications.createChannel(context)
        val notification=GatheringNotifications.buildPreview(context,entry,identity)
        assertEquals(GatheringNotifications.CHANNEL,notification.channelId)
        assertEquals("Общий сбор · приглашение",notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Локальный пример",notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("18:30–19:30"))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE,notification.visibility)
        assertFalse(notification.publicVersion.extras.getString(Notification.EXTRA_TEXT)!!.contains(entry.title))
        val target=GatheringNotifications.target(shadowOf(notification.contentIntent).savedIntent)
        assertEquals(GatheringNotificationTarget(entry.id,identity.viewer.name),target)
    }
}
