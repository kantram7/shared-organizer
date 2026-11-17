package team.kotiki.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NotificationReadStateTest {
    @Test fun readingOneChatPreservesOtherChatAndOtherAccountBadges(){
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("badge-test","Test",3).apply{setShowBadge(true)})
        fun post(tag:String,uid:String,route:String){manager.notify(tag,1,NotificationCompat.Builder(context,"badge-test")
            .setSmallIcon(R.drawable.ic_meeting).setNumber(1).addExtras(NotificationReadState.extras(uid,route)).build())}
        post("read","one","chat:general");post("other-chat","one","chat:plan");post("other-user","two","chat:general")
        NotificationReadState.markRead(context,"one","chat:general")
        assertEquals(setOf("other-chat","other-user"),manager.activeNotifications.map{it.tag}.toSet())
        assertTrue(manager.getNotificationChannel("badge-test").canShowBadge())
    }
    @Test fun delayedPushForAlreadyViewedContentDoesNotRestoreBadge(){
        assertTrue(NotificationReadState.alreadyRead("2026-11-18T01:00:00Z",1794963600001L))
        assertFalse(NotificationReadState.alreadyRead("2026-11-18T01:00:00Z",0L))
        assertFalse(NotificationReadState.alreadyRead("bad",Long.MAX_VALUE))
    }
    @Test fun onlyVisibleChatSuppressesIncomingNotifications(){
        val context=RuntimeEnvironment.getApplication()
        NotificationReadState.show(context,"one","chat:visible")
        try {
            assertFalse(NotificationReadState.shouldPost(context,"one","chat:visible",null))
            assertTrue(NotificationReadState.shouldPost(context,"one","chat:other",null))
            assertTrue(NotificationReadState.shouldPost(context,"two","chat:visible",null))
        }finally{NotificationReadState.hide("one","chat:visible")}
        assertTrue(NotificationReadState.shouldPost(context,"one","chat:visible",null))
    }
}
