package team.kotiki.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchNavigateAndCloseEditor() {
        compose.onNodeWithText("Kotiki team").assertIsDisplayed()
        compose.onNodeWithText("+ Добавить").performClick()
        compose.onNodeWithText("Новая запись").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть редактирование").performClick()
        compose.onNodeWithText("Планы").performClick()
        compose.onNodeWithText("Наши планы").assertIsDisplayed()
        compose.onNodeWithText("Календарь").performClick()
        compose.onNodeWithText("+ Событие").assertIsDisplayed()
        compose.onNodeWithText("Списки").performClick()
        compose.onNodeWithText("+ Список").assertIsDisplayed()
        compose.onNodeWithText("Зовут").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Приглашения").assertIsDisplayed()
        compose.onNodeWithText("Настройки").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Профиль").assertIsDisplayed()
    }
}
