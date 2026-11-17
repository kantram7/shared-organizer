package team.kotiki.app

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Before
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
    @Before fun waitForWelcome(){compose.mainClock.advanceTimeBy(2200);compose.waitForIdle();if(compose.onAllNodesWithText("Посмотреть локальное демо").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithText("Посмотреть локальное демо").performScrollTo().performClick()}
    @Test fun launchNavigateAndCloseEditor() {
        compose.onNodeWithContentDescription("Kotiki team").assertIsDisplayed()
        compose.onNodeWithText("+ Добавить").performClick()
        compose.onNodeWithText("Новая запись").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть редактирование").performClick()
        compose.onNodeWithText("Планы").performScrollTo().performClick()
        compose.onNodeWithText("Наши планы").assertIsDisplayed()
        compose.onNodeWithText("Календарь").performScrollTo().performClick()
        compose.onNodeWithText("+ Событие").assertIsDisplayed()
        compose.onNodeWithText("Списки").performScrollTo().performClick()
        compose.onNodeWithText("+ Список").assertIsDisplayed()
        compose.onNodeWithText("Зовут").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Приглашения").assertIsDisplayed()
        compose.onNodeWithText("Настройки").assertIsDisplayed().performClick()
        compose.onNodeWithText("Профиль").assertIsDisplayed()
    }

    @Test fun boardRetainsWeekFilterAndModeAcrossSections() {
        compose.onNodeWithText("Дни").performClick()
        compose.onNodeWithText("Мои").performClick()
        compose.onNodeWithContentDescription("Следующая неделя").performClick()
        val week=mondayOf(java.time.LocalDate.now()).plusWeeks(1)
        val label="Неделя ${dateLabel(week)} — ${dateLabel(week.plusDays(6))}"
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("Календарь").performScrollTo().performClick()
        compose.onNodeWithText("Доска").performScrollTo().performClick()
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("Дни").assertIsSelected()
        compose.onNodeWithText("Мои").assertIsSelected()
    }
    @Test fun draftSurvivesActivityRecreation() {
        compose.onNodeWithText("+ Добавить").performClick()
        compose.onNodeWithText("Название").performTextInput("Draft survives")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Draft survives").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть редактирование").performClick()
    }
    @Test fun cyrillicThemePersistsAfterRecreation() {
        compose.onNodeWithText("Планы").performScrollTo().performClick()
        compose.onNodeWithText("+ Тема").performClick()
        compose.onNodeWithText("Название").performTextInput("Ёжики и путешествия")
        compose.onNodeWithText("Добавить").performClick()
        compose.onAllNodesWithText("Ёжики и путешествия").onFirst().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onAllNodesWithText("Ёжики и путешествия").onFirst().assertIsDisplayed()
    }
    @Test fun gatheringHasFixedParticipantsAndRequiresSchedule() {
        compose.onNodeWithText("Общий сбор").performScrollTo().performClick()
        compose.onNodeWithText("Участники — мы оба").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Дело").assertDoesNotExist()
        compose.onNodeWithText("Есть точное время").assertDoesNotExist()
        compose.onNodeWithText("Выбрать дату").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Время начала").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Время окончания").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить приглашение").performClick()
        compose.onNodeWithText("Выбери дату").assertIsDisplayed()
        compose.onNodeWithText("Название").performScrollTo().performTextClearance()
        compose.onNodeWithText("Название").performTextInput("Собрание для проверки")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Собрание для проверки").assertIsDisplayed()
        compose.onNodeWithText("Выбрать дату").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть редактирование").performClick()
    }
    @Test fun incomingInvitationCanBeAcceptedDirectlyOnBoard() {
        val store=DemoStore(compose.activity)
        val today=java.time.LocalDate.now()
        val entry=Entry("ui-incoming","Входящее для проверки",today,mondayOf(today),kind=Kind.EVENT,owner=store.viewer.other(),invite=InviteState.PENDING)
        store.upsert(entry)
        store.upsert(entry.copy(id="ui-outgoing",title="Исходящее для проверки",owner=store.viewer))
        store.updateBoardOverview(false)
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeBy(2200)
        compose.onNodeWithContentDescription("Принять приглашение «Исходящее для проверки»").assertDoesNotExist()
        compose.onNodeWithContentDescription("Принять приглашение «Входящее для проверки»").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.waitForIdle()
        org.junit.Assert.assertEquals(InviteState.ACCEPTED,DemoStore(compose.activity).entries.single{it.id==entry.id}.invite)
        compose.onNodeWithContentDescription("Принять приглашение «Входящее для проверки»").assertDoesNotExist()
        compose.onNodeWithText("Отменить").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.waitForIdle()
        org.junit.Assert.assertEquals(InviteState.PENDING,DemoStore(compose.activity).entries.single{it.id==entry.id}.invite)
        compose.onNodeWithText("Обзор недели").performClick()
        compose.onNodeWithContentDescription("Принять приглашение «Входящее для проверки»").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.waitForIdle()
        org.junit.Assert.assertEquals(InviteState.ACCEPTED,DemoStore(compose.activity).entries.single{it.id==entry.id}.invite)
        compose.onNodeWithText("Зовут").performScrollTo().performClick()
        compose.onNodeWithText(entry.title).performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        val sender=store.identity.names[entry.owner] ?: entry.owner.label
        val recipient=store.identity.names[store.viewer] ?: store.viewer.label
        compose.onNodeWithText("Отправитель: $sender").assertIsDisplayed()
        compose.onNodeWithText("Получатель: $recipient").assertIsDisplayed()
        compose.onNodeWithText("Приглашение принято").assertIsDisplayed()
    }
}