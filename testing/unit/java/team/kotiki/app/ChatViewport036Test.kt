package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ChatViewport036Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun followsEndOfOversizedAlbumAfterReactionAndViewportResize() {
        val height=mutableStateOf(220)
        val reaction=mutableStateOf(false)
        lateinit var state:LazyListState
        ui.activityRule.scenario.onActivity { a->a.setContent { KotikiTheme("light") {
            state=rememberLazyListState()
            FollowChatBottom(state,true)
            LazyColumn(Modifier.height(height.value.dp),state=state) {
                item(key="older"){Text("Older message",Modifier.height(if(reaction.value)180.dp else 50.dp))}
                item(key="album"){Column {Box(Modifier.height(700.dp));Text("Album caption")}}
                item(key="end"){Spacer(Modifier.height(1.dp).testTag("end"))}
            }
        }}}
        ui.waitForIdle()
        ui.onNodeWithText("Album caption").assertIsDisplayed()
        ui.runOnIdle { assertFalse(state.canScrollForward);reaction.value=true;height.value=140 }
        ui.waitForIdle()
        ui.onNodeWithText("Album caption").assertIsDisplayed()
        ui.runOnIdle { assertFalse(state.canScrollForward) }
    }

    @Test fun editingDialogSurvivesSourceCardRemovalAndKeepsTypedDraft() {
        val showCard=mutableStateOf(true)
        val message=ChatMessage("edit","general","me","me",Person.USER_1,"Original",1,false,false)
        var saved=""
        ui.activityRule.scenario.onActivity { a->a.setContent { KotikiTheme("light") {
            if(showCard.value)Text("Source card")
            MessageEditDialog(message,{}, {saved=it})
        }}}
        ui.waitForIdle()
        ui.runOnIdle {
            val decor=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            fun find(v:android.view.View):VisualEditText? {
                if(v is VisualEditText)return v
                if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
                return null
            }
            val field=requireNotNull(find(decor))
            field.requestFocus();field.setSelection(field.length());field.append(" changed")
            showCard.value=false
        }
        ui.waitForIdle()
        ui.onNodeWithText("Изменить сообщение").assertIsDisplayed()
        ui.onNodeWithText("Сохранить").performClick()
        ui.runOnIdle { assertEquals("Original changed",saved) }
    }
}
