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
class ChatHandoff036Test {
    private fun row(id:String="a",pending:Boolean=true)=ChatMessage(id,"general","me","me",Person.USER_1,"Hello",100,false,pending)
    @Test fun ackBeforeSnapshotNeverRemovesRowEvenWhenAnotherObserverClearsOutbox(){
        val handoff=mutableMapOf<String,ChatMessage>()
        assertEquals(listOf("a"),mergeChatOutgoing(emptyList(),listOf(row()),handoff).map{it.id})
        val ack=mergeChatOutgoing(emptyList(),listOf(row(pending=false)),handoff)
        assertEquals(1,ack.size);assertFalse(ack.single().pending)
        assertEquals(ack,mergeChatOutgoing(emptyList(),emptyList(),handoff))
        val remote=row(pending=false).copy(time=110)
        assertEquals(listOf(remote),mergeChatOutgoing(listOf(remote),emptyList(),handoff));assertTrue(handoff.isEmpty())
    }
    @Test fun snapshotBeforeAckDoesNotDuplicateMessage(){
        val handoff=mutableMapOf<String,ChatMessage>()
        val remote=row(pending=false)
        assertEquals(listOf(remote),mergeChatOutgoing(listOf(remote),listOf(row()),handoff))
        assertTrue(handoff.isEmpty())
    }
    @Test fun confirmedDeliverySurvivesRestartUntilObserverTakesOver(){
        val context=ApplicationProvider.getApplicationContext<Context>();val uid=newId();val space=newId()
        val box=ChatOutbox(context,uid,space)
        box.put(PendingChatMessage("a",ChatTarget("general","Chat"),"Hello",100))
        box.acknowledge("a")
        val restarted=ChatOutbox(context,uid,space)
        assertTrue(restarted.all().single().delivered);assertEquals("Hello",restarted.all().single().text)
        restarted.remove("a");restarted.acknowledge("a")
        assertTrue(ChatOutbox(context,uid,space).all().isEmpty())
    }
}
