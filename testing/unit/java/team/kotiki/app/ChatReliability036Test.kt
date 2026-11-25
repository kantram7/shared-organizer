package team.kotiki.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ChatReliability036Test {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Test fun pendingTextAndStableIdSurviveProcessRestartAndRetry(){
        val uid=newId();val space=newId();val message=PendingChatMessage(newId(),ChatTarget("general","Chat"),"Original text",1234L,replyTo="source")
        ChatOutbox(context,uid,space).put(message)
        val next=ChatOutbox(context,uid,space);assertEquals(message,next.all().single())
        next.put(message.copy(error="Access denied"));val retry=ChatOutbox(context,uid,space)
        assertEquals(message.id,retry.all().single().id);assertEquals("Original text",retry.all().single().text)
        retry.put(message);retry.remove(message.id);assertTrue(ChatOutbox(context,uid,space).all().isEmpty())
    }
    @Test fun outboxNeverCrossesAccountsOrSpaces(){
        val space=newId();val a=newId();val b=newId();ChatOutbox(context,a,space).put(PendingChatMessage(newId(),ChatTarget("general","Chat"),"Private draft",1))
        assertTrue(ChatOutbox(context,b,space).all().isEmpty());assertTrue(ChatOutbox(context,a,newId()).all().isEmpty())
    }
    @Test fun remoteReadReceiptDoesNotMarkOtherMessagesRead(){
        val first=ChatMessage("a","general","other","other",Person.USER_2,"one",100,false,false,readByMe=true)
        val second=first.copy(id="b",time=101,readByMe=false)
        assertFalse(ReadCursor().unread(first,"me"));assertTrue(ReadCursor().unread(second,"me"))
    }
    @Test fun latePushSuppressionIsExactMessageAndAccount(){
        val uid=newId();NotificationReadState.markMessageRead(context,uid,"general","a")
        assertTrue(NotificationReadState.messageRead(context,uid,"general","a"))
        assertFalse(NotificationReadState.messageRead(context,uid,"general","b"))
        assertFalse(NotificationReadState.messageRead(context,newId(),"general","a"))
    }
    @Test fun newFrameRemainsUntilRefreshUnlessThatCardOpened(){
        val day=java.time.LocalDate.now();val a=Entry(newId(),"A",day,mondayOf(day),owner=Person.USER_2,who=Person.USER_1)
        val b=a.copy(id=newId(),title="B");val state=EntryNewState(context,Person.USER_1,null)
        assertTrue(state.isNew(a));state.seen(a);assertTrue(state.isNew(a));assertTrue(state.isNew(b))
        state.seen(a,true);assertFalse(state.isNew(a));assertTrue(state.isNew(b))
        state.seen(b);assertTrue(state.isNew(b));state.refresh();assertFalse(state.isNew(b))
    }
    @Test fun merelyVisibleOtherPersonsTaskIsNotNewForViewer(){
        val day=java.time.LocalDate.now();val a=Entry(newId(),"Other",day,mondayOf(day),owner=Person.USER_2,who=Person.USER_2)
        val state=EntryNewState(context,Person.USER_1,null);assertFalse(state.isNew(a));state.seen(a,true);assertTrue(state.isNew(a.copy(who=Person.BOTH)))
    }
}
