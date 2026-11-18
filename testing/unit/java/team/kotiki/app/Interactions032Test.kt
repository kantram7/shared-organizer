package team.kotiki.app

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Interactions032Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun content(block:@Composable ()->Unit){ui.activityRule.scenario.onActivity{it.setContent(content=block)}}
    @Test fun externalActivityDoesNotDisposeAuthorizationContent() {
        val foreground=mutableStateOf(true);var starts=0;var disposes=0
        content {RetainedForegroundContent(foreground.value) {
            DisposableEffect(Unit){starts++;onDispose{disposes++}}
            Text("Account selection stays mounted")
        }}
        ui.runOnIdle{foreground.value=false};ui.waitForIdle()
        ui.runOnIdle{foreground.value=true};ui.waitForIdle()
        assertEquals(1,starts);assertEquals(0,disposes)
    }
    @Test fun longPressAndOverflowShareConfirmationAndCancelDoesNotDelete() {
        var deleted=0
        content {KotikiTheme("light") {
            val state=rememberItemMenu()
            Row(Modifier.testTag("record").combinedClickable(onClick={},onLongClick={state.expanded=true})) {
                Text("Sample");ItemActions("Sample",{deleted++},state)
            }
        }}
        ui.onNodeWithTag("record").performSemanticsAction(SemanticsActions.OnLongClick){it()}
        ui.onNodeWithText("Удалить").performClick()
        ui.onNodeWithText("Удалить «Sample»?").assertIsDisplayed()
        ui.onNodeWithText("Отмена").performClick();assertEquals(0,deleted)
        ui.onNodeWithContentDescription("Действия: Sample").performClick()
        ui.onNodeWithText("Удалить").performClick()
        ui.onNodeWithText("Удалить").performClick();assertEquals(1,deleted)
    }
    @Test fun shortMessageDoesNotFillChatWidthAndDeletionIsInMenu() {
        content {KotikiTheme("light") {Box(Modifier.width(400.dp)) {
            MessageBubble(ChatMessage("id","general","one","one",Person.USER_1,"Привет!",0,false,false),"User",true,{})
        }}}
        ui.onNodeWithText("Удалить").assertDoesNotExist()
        val text=ui.onNodeWithText("Привет!").fetchSemanticsNode().boundsInRoot
        val menu=ui.onNodeWithContentDescription("Действия: сообщение").fetchSemanticsNode().boundsInRoot
        assertTrue(menu.right-text.left<280f)
        ui.onNodeWithContentDescription("Действия: сообщение").performClick()
        ui.onNodeWithText("Удалить").assertIsDisplayed()
    }
    @Test fun googleErrorsDistinguishConfigurationFromConnectivity() {
        assertTrue(googleAuthorizationError(10).contains("OAuth"))
        assertTrue(googleAuthorizationError(7).contains("интернет"))
        assertTrue(googleAuthorizationError(12501).contains("отменено"))
    }
}
