package team.kotiki.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Interaction035Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun content(block:@Composable ()->Unit){ui.activityRule.scenario.onActivity{it.setContent{KotikiTheme("light",content=block)}}}
    private fun message(id:String,time:Long=10,sender:String="other",deleted:Boolean=false,pending:Boolean=false)=
        ChatMessage(id,"general",sender,sender,Person.USER_2,"https://example.com · текст",time,deleted,pending)

    @Test fun linksPreserveRangesTrimSentencePunctuationAndRejectNonWebSchemes() {
        val text="Смотри (https://example.com/a(b)), www.example.org. И пример.рф/путь"
        val found=webLinks(text)
        assertEquals(listOf("https://example.com/a(b)","https://www.example.org","https://пример.рф/путь"),found.map{it.url})
        assertEquals("https://example.com/a(b)",text.substring(found[0].start,found[0].end))
        assertTrue(webLinks("test@example.com javascript:alert(1) file:///etc/passwd intent://test").isEmpty())
        val intent=browserIntent(context,"https://example.com")
        assertEquals(Intent.ACTION_VIEW,intent.action);assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertThrows(IllegalArgumentException::class.java){browserIntent(context,"javascript:alert(1)")}
    }

    @Test fun incomingMessageCanBeCopiedFromLongPressButCannotBeDeleted() {
        val message=message("incoming")
        content{MessageBubble(message,"User 2",false,{fail("Cannot delete incoming message")})}
        ui.onNodeWithText(message.text).performTouchInput{longClick()}
        ui.onNodeWithText("Удалить").assertDoesNotExist()
        ui.onNodeWithText("Копировать").performClick()
        ui.runOnIdle{assertEquals(message.text,context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())}
        ui.onNodeWithContentDescription("Действия: сообщение").performClick()
        ui.onNodeWithText("Копировать").assertIsDisplayed()
    }

    @Test fun personalOrderPersistsWithoutChangingOtherProfilesScopesOrHiddenItems() {
        val profile="test/${newId()}";val store=LocalOrderStore(context,profile)
        val ids=listOf("a","hidden","b","c")
        assertTrue(store.move("day/one",ids,"c","a"))
        assertEquals(listOf("c","a","hidden","b"),LocalOrderStore(context,profile).sorted("day/one",ids){it})
        assertEquals(listOf("c","a","b"),store.sorted("day/one",ids.filterNot{it=="hidden"}){it})
        assertEquals(ids,store.sorted("day/two",ids){it})
        assertEquals(ids,LocalOrderStore(context,"other/$profile").sorted("day/one",ids){it})
        assertFalse(store.move("day/one",ids,"outside","a"))
        assertEquals(listOf("c","a","hidden","b","new"),store.sorted("day/one",ids+"new"){it})
        assertTrue(localFile(context,"order",profile).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }

    @Test fun holdAndDragReordersWithinColumnAndDoesNotOpenCard() {
        val store=LocalOrderStore(context,"gesture/${newId()}");var opened=false
        content{ReorderColumn(store,"notes/group",listOf("A","B","C"),{it}) {id->
            Surface(Modifier.fillMaxWidth().height(80.dp).testTag(id).clickable{opened=true}){Text(id)}
        }}
        val a=ui.onNodeWithTag("A").fetchSemanticsNode().boundsInRoot
        val c=ui.onNodeWithTag("C").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("A").performTouchInput{
            down(center);advanceEventTime(700);moveTo(Offset(center.x,center.y+c.center.y-a.center.y),400);up()
        }
        ui.runOnIdle{
            assertFalse(opened)
            assertEquals(listOf("B","C","A"),store.sorted("notes/group",listOf("A","B","C")){it})
        }
    }

    @Test fun readCursorDoesNotCountOwnDeletedOrPendingAndHandlesEqualTimestamps() {
        val state=ChatUnreadState(context,"me","unread/${newId()}")
        val a=message("a");val b=message("b");val own=message("own",sender="me")
        state.receive(ChatSnapshot(listOf(a,b,own,message("gone",deleted=true),message("pending",pending=true)),false))
        assertEquals(2,state.count)
        state.readThrough(listOf(a));assertEquals(1,state.count)
        state.readThrough(listOf(b));assertEquals(0,state.count)
        state.receive(ChatSnapshot(listOf(a,b,message("later",11)),false));assertEquals(1,state.count)
    }

    @Test fun readStateSurvivesRestartAndBadgeHasAccessibleCount() {
        val profile="persist/${newId()}";val state=ChatUnreadState(context,"me",profile)
        state.readThrough(listOf(message("a")))
        val next=ChatUnreadState(context,"me",profile)
        next.receive(ChatSnapshot(listOf(message("a"),message("b",11)),false));assertEquals(1,next.count)
        content{NavigationGlyph(Glyph.CHAT,105)}
        ui.onNodeWithContentDescription("Непрочитанных сообщений: больше 99").assertIsDisplayed()
        ui.onNodeWithText("99+").assertIsDisplayed()
    }

    @Test fun notificationUsesApprovedPaw() {
        val entry=newGathering(Person.USER_1).copy(date=java.time.LocalDate.now(),lastDate=java.time.LocalDate.now(),start="19:00",end="20:00")
        GatheringNotifications.createChannel(context)
        val notification=GatheringNotifications.buildPreview(context,entry,DemoIdentity())
        assertEquals(R.drawable.ic_notification_paw,notification.smallIcon.resId)
        assertEquals(R.drawable.ic_notification_paw,notification.publicVersion.smallIcon.resId)
    }
}
