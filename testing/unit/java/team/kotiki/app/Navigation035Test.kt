package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
class Navigation035Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun content(body:@Composable ()->Unit){ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light"){body()}}}}
    @Test fun compactItemsKeepSameLabelOffsetWithTwoLineGathering() {
        content{Column(Modifier.width(64.dp).verticalScroll(rememberScrollState())){
            Section.entries.forEach{section->Box(Modifier.testTag(section.name)){NavigationItem(section,section==Section.SETTINGS,false){}}}
            Box(Modifier.testTag("gather")){GatherCatsButton(false){}}
        }}
        val gathering=ui.onNodeWithTag("gather").getUnclippedBoundsInRoot()
        val gatherText=ui.onNodeWithText("Собрать\nкотиков",useUnmergedTree=true).getUnclippedBoundsInRoot()
        println("NAV gathering=$gathering text=$gatherText density=${ui.activity.resources.displayMetrics.density} fontScale=${ui.activity.resources.configuration.fontScale}")
        Section.entries.forEach {section->
            val bounds=ui.onNodeWithTag(section.name).getUnclippedBoundsInRoot()
            val text=ui.onNodeWithText(section.label,useUnmergedTree=true).getUnclippedBoundsInRoot()
            println("NAV ${section.name}: $bounds text=$text")
            assertTrue(section.name,(gathering.bottom-gathering.top).value >= (bounds.bottom-bounds.top).value)
            assertEquals(section.name,(gatherText.top-gathering.top).value,(text.top-bounds.top).value,.5f)
        }
    }
    @Test fun unreadBadgeDoesNotStretchNavigation() {
        var count by mutableIntStateOf(0)
        content{Box(Modifier.width(64.dp).testTag("chat")){NavigationItem(Section.CHAT,false,false,count){}}}
        val before=ui.onNodeWithTag("chat").getUnclippedBoundsInRoot()
        ui.runOnIdle{count=120}
        ui.onNodeWithContentDescription("Непрочитанных сообщений: больше 99").assertIsDisplayed()
        val after=ui.onNodeWithTag("chat").getUnclippedBoundsInRoot()
        assertEquals((before.bottom-before.top).value,(after.bottom-after.top).value,.5f)
        assertEquals((before.right-before.left).value,(after.right-after.left).value,.5f)
    }
    @Test fun winterIsVisibleButUnavailableInPhoneBuild() {
        val store=DemoStore(ui.activity);store.updateTheme("light")
        content{SettingsScreen(store,{})}
        ui.onNodeWithContentDescription("Тема: Светлая").performScrollTo().performClick()
        ui.onNodeWithText("Зима вместе").assertIsDisplayed()
        if(!BuildConfig.WINTER_PREVIEW){
            ui.onNode(hasText("Зима вместе") and hasClickAction()).assertIsNotEnabled().performClick()
            ui.runOnIdle{assertEquals("light",store.theme)}
        }
    }
}
