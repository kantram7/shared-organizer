package team.kotiki.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.NotificationCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Unread035Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun message(id:String,time:Long=10,chat:String="entry_one",sender:String="other")=
        ChatMessage(id,chat,sender,sender,Person.USER_2,"Message $id",time,false,false)
    private fun content(body:@Composable ()->Unit){ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){body()}}}}
    @Test fun olderUnseenMessagesAreNotReadByOpeningLatestAndSurviveRestart() {
        val profile=newId();val state=ChatUnreadState(ui.activity,"me",profile)
        val old=message("old",10);val last=message("last",20)
        state.receive(ChatSnapshot(listOf(old,last),false));state.readThrough(listOf(last))
        assertEquals(1,state.count)
        val restored=ChatUnreadState(ui.activity,"me",profile)
        restored.receive(ChatSnapshot(listOf(old,last),false));assertEquals(1,restored.count)
        restored.readThrough(listOf(old));assertEquals(0,restored.count)
        restored.receive(ChatSnapshot(listOf(old,last,message("next",21)),false));assertEquals(1,restored.count)
    }
    @Test fun roomsProfilesOwnPendingAndDeletedMessagesAreIsolated() {
        val profile=newId();val reads=EntryChatReads(ui.activity,"me",profile)
        val one=reads.state("one");val two=reads.state("two")
        val incoming=message("incoming")
        one.receive(ChatSnapshot(listOf(incoming,message("own",sender="me"),message("deleted").copy(deleted=true),message("pending").copy(pending=true)),false))
        two.receive(ChatSnapshot(listOf(message("two",chat="entry_two")),false))
        assertEquals(1,reads.count("one"));assertEquals(1,reads.count("two"))
        one.readThrough(listOf(incoming));assertEquals(0,reads.count("one"));assertEquals(1,reads.count("two"))
        val restored=EntryChatReads(ui.activity,"me",profile);restored.state("one").receive(ChatSnapshot(listOf(incoming),false));assertEquals(0,restored.count("one"))
        val other=EntryChatReads(ui.activity,"me",newId());other.state("one").receive(ChatSnapshot(listOf(incoming),false));assertEquals(1,other.count("one"))
    }
    @Test fun visibleChatLoadingBeforeFullUnreadQueryDoesNotLoseHistory() {
        val profile=newId();val state=ChatUnreadState(ui.activity,"me",profile)
        val old=message("old",10);val latest=message("latest",20)
        state.receive(ChatSnapshot(listOf(latest),false,fromCache=true))
        state.readThrough(listOf(latest))
        assertEquals(0L,state.since)
        val restored=ChatUnreadState(ui.activity,"me",profile)
        restored.receive(ChatSnapshot(listOf(old,latest),false))
        assertEquals(1,restored.count)
        restored.readThrough(listOf(old));assertEquals(0,restored.count)
    }
    @Test fun viewingEventDoesNotClearChatNotificationAndOpeningChatDoes() {
        val context=ui.activity;val manager=context.getSystemService(NotificationManager::class.java)
        val uid=newId();val event="one";val route=NotificationReadState.chat("entry_$event")
        manager.createNotificationChannel(NotificationChannel("unread-test","Test",3))
        manager.notify("event-chat",7,NotificationCompat.Builder(context,"unread-test").setSmallIcon(R.drawable.ic_notification_paw).addExtras(NotificationReadState.extras(uid,route)).build())
        NotificationReadState.show(context,uid,NotificationReadState.entry(event))
        try {
            assertEquals(1,manager.activeNotifications.size)
            assertTrue(NotificationReadState.shouldPost(context,uid,route,null))
        }finally{NotificationReadState.hide(uid,NotificationReadState.entry(event))}
        NotificationReadState.show(context,uid,route)
        try {assertEquals(0,manager.activeNotifications.size);assertFalse(NotificationReadState.shouldPost(context,uid,route,null))}
        finally{NotificationReadState.hide(uid,route)}
        assertTrue(NotificationReadState.shouldPost(context,uid,route,null))
    }
    @Test fun pushIntentOpensEventDetailInsteadOfChatAndKeepsBadge() {
        val id="routing-event";val date=LocalDate.now()
        val store=DemoStore(ui.activity);store.entries.clear()
        store.entries.add(Entry(id,"Event from notification",date,mondayOf(date),kind=Kind.EVENT,visibility=Visibility.SHARED))
        val intent=Intent().putExtra("push_auth_uid","").putExtra("push_record_id","entry_$id").putExtra("push_chat_id","entry_$id").putExtra("push_item_id",id)
        val target=GatheringNotifications.target(intent)!!
        assertEquals(id,eventIdFromChat(target));var handled=false
        content{KotikiApp(store,target,{handled=true})}
        ui.onNodeWithContentDescription("Закрыть событие").assertIsDisplayed()
        ui.onNodeWithText("Название").assertIsDisplayed()
        ui.onNodeWithText("Сообщение").assertDoesNotExist()
        ui.runOnIdle{assertTrue(handled)}
    }
    @Test fun eventBadgeRemainsWhenCardOpenedAndDisappearsAfterRead() {
        val reads=EntryChatReads(ui.activity,"me",newId());val entry=Entry("one","Event",LocalDate.now(),mondayOf(LocalDate.now()),kind=Kind.EVENT)
        val incoming=message("incoming");reads.state(entry.id).receive(ChatSnapshot(listOf(incoming),false));var opened=false
        content{CompositionLocalProvider(LocalEntryChatReads provides reads){EntryChatBadge(entry){opened=true}}}
        ui.onNodeWithContentDescription("Непрочитанных сообщений: 1").assertIsDisplayed().performClick()
        ui.runOnIdle{assertTrue(opened);assertEquals(1,reads.count(entry.id));reads.state(entry.id).readThrough(listOf(incoming))}
        ui.onNodeWithContentDescription("Непрочитанных сообщений: 1").assertDoesNotExist()
    }
    @Test fun numericPinPolicyAndLegacyMigration() {
        listOf("1234","01234","000000").forEach{assertTrue(PinPolicy.valid(it));assertFalse(PinPolicy.needsReplacement(it.length))}
        listOf("123","1234567","abcd","１２３４").forEach{assertFalse(PinPolicy.valid(it))}
        assertTrue(PinPolicy.legacyValid("123456789012"));assertTrue(PinPolicy.needsReplacement(12));assertTrue(PinPolicy.needsReplacement(0))
    }
    @Test fun weekOverviewAlsoShowsUnreadBadge() {
        val reads=EntryChatReads(ui.activity,"me",newId());val entry=Entry("one","Overview event",LocalDate.now(),mondayOf(LocalDate.now()),kind=Kind.EVENT)
        reads.state(entry.id).receive(ChatSnapshot(listOf(message("incoming")),false))
        content{CompositionLocalProvider(LocalEntryChatReads provides reads){OverviewRecord(entry,{},{},{},{})}}
        ui.onNodeWithContentDescription("Непрочитанных сообщений: 1").assertIsDisplayed()
    }
    @Test fun pinDotsAndKeypadMatchSelectedLength() {
        var length by mutableIntStateOf(4);var entered by mutableStateOf("")
        content{PinKeypad(entered,length,true,true,{entered+=it},{entered=entered.dropLast(1)},{},{},null)}
        ui.onNodeWithContentDescription("PIN из 4 цифр, введено 0").assertIsDisplayed()
        ui.onNodeWithText("1").performClick()
        ui.onNodeWithContentDescription("PIN из 4 цифр, введено 1").assertIsDisplayed()
        ui.runOnIdle{length=6;entered=""}
        ui.onNodeWithContentDescription("PIN из 6 цифр, введено 0").assertIsDisplayed()
    }
    @Test fun gatheringHasAllThreeResponses() {
        val entry=newGathering(Person.USER_2);var response=""
        content{Column{InvitationResponses(entry,Person.USER_1,{response="accept"},{response="decline"},{response="propose"})}}
        ui.onNodeWithText("Принять").assertIsDisplayed()
        ui.onNodeWithText("Отказаться").performClick();ui.runOnIdle{assertEquals("decline",response)}
        ui.onNodeWithText("Предложить изменения").performClick();ui.runOnIdle{assertEquals("propose",response)}
    }
}
