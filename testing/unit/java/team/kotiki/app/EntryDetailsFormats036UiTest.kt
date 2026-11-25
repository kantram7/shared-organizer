package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EntryDetailsFormats036UiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun markdownDetailsShowVisibleTextWhilePlainMarkersStayLiteral() {
        val day=LocalDate.now()
        val entry=Entry("detail-format","**Formatted title**",day,mondayOf(day),note="A <u>formatted description</u>",
            titleFormat=MARKDOWN_TEXT_FORMAT,descriptionFormat=MARKDOWN_TEXT_FORMAT)
        ui.activityRule.scenario.onActivity { activity->activity.setContent { KotikiTheme("light") {
            EntryDetailsDialog(entry,{},{},{}){}
        }}}
        ui.onNodeWithText("Formatted title").assertIsDisplayed()
        ui.onNodeWithText("A formatted description").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("**Formatted title**").assertDoesNotExist()
        ui.activityRule.scenario.onActivity { activity->activity.setContent { KotikiTheme("light") {
            EntryDetailsDialog(entry.copy(titleFormat=PLAIN_TEXT_FORMAT,descriptionFormat=PLAIN_TEXT_FORMAT),{},{},{}){}
        }}}
        ui.onNodeWithText("**Formatted title**").assertIsDisplayed()
        ui.onNodeWithText("A <u>formatted description</u>").performScrollTo().assertIsDisplayed()
    }
}
