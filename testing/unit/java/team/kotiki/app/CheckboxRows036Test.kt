package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CheckboxRows036Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun tappingVisibilityLabelCreatesPrivateGroupAndDoesNotDoubleToggle() {
        lateinit var store:NotesStore
        ui.activityRule.scenario.onActivity{a->
            store=NotesStore(a,Person.USER_1);store.file.delete();store=NotesStore(a,Person.USER_1)
            a.setContent{KotikiTheme("light"){NotesScreen(store,LocalOrderStore(a,"checkbox-qa")){}}}
        }
        ui.onNodeWithText("+ Группа").performClick()
        ui.onNodeWithText("Название").performTextInput("Private by label")
        // Tap the actual label bounds, not an assumed merged semantics node.
        ui.onNodeWithText("Видно всем",useUnmergedTree=true).assertIsDisplayed().performTouchInput { click(center) }
        ui.onNodeWithText("Совместное редактирование").assertDoesNotExist()
        ui.onNodeWithText("Создать").performClick()
        ui.runOnIdle{assertFalse(store.groups.single().shared)}
    }
}
