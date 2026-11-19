package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EntryDetailsUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun event()=Entry("detail-test","Прогулка и ужин",LocalDate.now(),mondayOf(LocalDate.now()),start="19:00",end="21:00",kind=Kind.EVENT,note="Описание")
    @Test fun viewHasSeparateFieldsAndEditAndCloseWork() {
        var edited=false;var closed=false
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){
            EntryDetailsDialog(event(),{closed=true},{edited=true},{}){}
        }}}
        ui.onNodeWithText("Название").assertIsDisplayed()
        ui.onNodeWithText("Прогулка и ужин").assertIsDisplayed()
        ui.onNodeWithText("С 19:00").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("По 21:00").assertIsDisplayed()
        ui.onNodeWithText("Редактировать").performClick();ui.runOnIdle{assertTrue(edited)}
        ui.onNodeWithContentDescription("Закрыть событие").performClick();ui.runOnIdle{assertTrue(closed)}
    }
    @Test fun compactCardKeepsTitleAndActionsButReducesHeight() {
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){Column{
            Column(Modifier.testTag("regular")){RecordCard(event(),{},{},{},{})}
            Column(Modifier.testTag("compact")){RecordCard(event(),{},{},{},{},compact=true)}
        }}}}
        val regular=ui.onNodeWithTag("regular").fetchSemanticsNode().boundsInRoot
        val compact=ui.onNodeWithTag("compact").fetchSemanticsNode().boundsInRoot
        assertTrue(compact.height<regular.height)
        ui.onAllNodesWithText("Прогулка и ужин").assertCountEquals(2)
    }
}
