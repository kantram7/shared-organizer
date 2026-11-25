package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ChatComposer036Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun field(v:android.view.View):VisualEditText? {
        if(v is VisualEditText)return v
        if(v is android.view.ViewGroup)for(i in 0 until v.childCount)field(v.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun expandingComposerKeepsFocusedEditorAndUsesFullWidth(){
        val value=mutableStateOf("Hi");lateinit var native:VisualEditText
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){
            Box(Modifier.width(300.dp)){ChatComposer(value.value,{value.value=it},{},{})}
        }}}
        ui.waitForIdle()
        val small=ui.onNodeWithTag("chat-composer-text",true).fetchSemanticsNode().boundsInRoot
        ui.runOnIdle {native=requireNotNull(field(ui.activity.window.decorView));native.requestFocus();native.setSelection(native.length());native.append(" long message".repeat(15))}
        ui.waitForIdle()
        val wide=ui.onNodeWithTag("chat-composer-text",true).fetchSemanticsNode().boundsInRoot
        val send=ui.onNodeWithTag("chat-send",true).fetchSemanticsNode().boundsInRoot
        val attach=ui.onNodeWithTag("chat-attach",true).fetchSemanticsNode().boundsInRoot
        assertTrue("Expansion: $small -> $wide",wide.width>small.width);assertTrue("Buttons: $send below $wide",send.top>=wide.bottom);assertEquals(send.top,attach.top,.1f)
        ui.runOnIdle{assertSame(native,field(ui.activity.window.decorView));assertTrue(native.hasFocus());assertEquals(native.length(),native.selectionStart);native.setText("Hi")}
        ui.waitForIdle()
        val compact=ui.onNodeWithTag("chat-composer-text",true).fetchSemanticsNode().boundsInRoot
        assertEquals(small.width,compact.width,.1f)
    }
    @Test fun deliveryIndicatorDoesNotResizeBubble(){
        val message=mutableStateOf(ChatMessage("a","general","me","me",Person.USER_1,"Hello",100,false,true))
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){
            Box(Modifier.width(300.dp)){MessageBubble(message.value,"User",true,{})}
        }}}
        ui.waitForIdle();val before=ui.onNodeWithTag("message-bubble-a").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle{message.value=message.value.copy(pending=false)};ui.waitForIdle()
        val after=ui.onNodeWithTag("message-bubble-a").fetchSemanticsNode().boundsInRoot
        assertEquals(before.width,after.width,.1f);assertEquals(before.height,after.height,.1f)
    }
}
