package team.kotiki.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
    private fun nativeEditor(label:String):VisualEditText {
        fun find(view:android.view.View):VisualEditText? {
            if(view is VisualEditText && view.contentDescription?.toString()==label)return view
            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        return requireNotNull(find(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView))
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun waitForWelcome(){compose.mainClock.advanceTimeBy(2200);compose.waitForIdle();if(compose.onAllNodesWithText("Посмотреть локальное демо").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithText("Посмотреть локальное демо").performScrollTo().performClick()}
    @Test fun winterIsNotSelectableBeforeRemoteLaunchAndSystemButtonsStayTransparent() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithContentDescription("Тема: Светлая").performScrollTo().performClick()
        compose.onNodeWithText("Зима вместе").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Зима вместе · скоро").assertDoesNotExist()
        org.junit.Assert.assertFalse(compose.activity.window.isNavigationBarContrastEnforced)
        @Suppress("DEPRECATION")
        org.junit.Assert.assertEquals(android.graphics.Color.TRANSPARENT,compose.activity.window.navigationBarColor)
    }
    @Test fun launchNavigateAndCloseEditor() {
        compose.onNodeWithContentDescription("Kotiki team").assertIsDisplayed()
        compose.onNodeWithText("+ Добавить").performClick()
        compose.onNodeWithText("Новая запись").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть редактирование").performClick()
        compose.onNodeWithText("Планы").performScrollTo().performClick()
        compose.onNodeWithText("Наши планы").assertIsDisplayed()
        compose.onNodeWithText("Календарь").performScrollTo().performClick()
        compose.onNodeWithText("+ Событие").assertIsDisplayed()
        compose.onNodeWithText("Заметки").performScrollTo().performClick()
        compose.onNodeWithText("+ Группа").assertIsDisplayed()
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
        compose.runOnIdle{nativeEditor("Название").requestFocus();nativeEditor("Название").text.append("Draft survives")}
        compose.activityRule.scenario.recreate()
        compose.runOnIdle{org.junit.Assert.assertEquals("Draft survives",nativeEditor("Название").text.toString())}
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
        compose.onNodeWithText("Собрать\nкотиков").performScrollTo().performClick()
        compose.onNodeWithText("Участники — мы оба").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Дело").assertDoesNotExist()
        compose.onNodeWithText("Есть точное время").assertDoesNotExist()
        compose.onNodeWithText("Выбрать дату").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Время начала").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Время окончания").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить приглашение").performClick()
        compose.onNodeWithText("Выбери дату").assertIsDisplayed()
        compose.runOnIdle{nativeEditor("Название").requestFocus();nativeEditor("Название").text.replace(0,nativeEditor("Название").length(),"Собрание для проверки")}
        compose.activityRule.scenario.recreate()
        compose.runOnIdle{org.junit.Assert.assertEquals("Собрание для проверки",nativeEditor("Название").text.toString())}
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
        compose.onNodeWithText("Отправитель: $sender").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Получатель: $recipient").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Приглашение принято").performScrollTo().assertIsDisplayed()
    }

    @Test fun recordTapShowsDetailsBeforeEditor() {
        val store=DemoStore(compose.activity);val day=java.time.LocalDate.now()
        store.upsert(Entry("detail-first","Details first",day,mondayOf(day),owner=store.viewer,note="Read this first"))
        compose.activityRule.scenario.recreate();compose.mainClock.advanceTimeBy(2200)
        compose.onNodeWithText("Details first").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithText("Read this first").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть редактирование").assertDoesNotExist()
        compose.onNodeWithText("Редактировать").performClick()
        compose.onNodeWithContentDescription("Закрыть редактирование").assertIsDisplayed()
    }
    @Test fun planOpensDescriptionAndCommentsThenEditing() {
        val store=DemoStore(compose.activity)
        store.addCollection(true,"Newest topic",true);val group=store.plans.last().id
        store.addItem(true,group,"Plan details","Initial description")
        compose.activityRule.scenario.recreate();compose.mainClock.advanceTimeBy(2200)
        compose.onNodeWithText("Планы").performScrollTo().performClick()
        compose.onNodeWithText("Plan details").performScrollTo().performClick()
        compose.onNodeWithText("Развернуть").performClick()
        compose.onNodeWithText("Initial description").assertIsDisplayed()
        compose.onNodeWithText("Комментарии").assertIsDisplayed()
        compose.onNodeWithText("Редактировать").performClick()
        compose.runOnIdle{nativeEditor("Описание").requestFocus();nativeEditor("Описание").text.replace(0,nativeEditor("Описание").length(),"Updated description")}
        compose.onNodeWithText("Сохранить").performClick()
        compose.onNodeWithText("Updated description").assertIsDisplayed()
    }
}
