package team.kotiki.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NotesUiTest {
    private fun editor(label:String="Текст блока"):VisualEditText {
        fun find(view:android.view.View):VisualEditText? {
            if(view is VisualEditText && view.contentDescription?.toString()==label)return view
            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        return requireNotNull(find(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView))
    }
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun openingNoteShowsEditorWithoutFocusAndTypingPersistsImmediately() {
        lateinit var store:NotesStore
        ui.activityRule.scenario.onActivity{activity->
            store=NotesStore(activity,Person.USER_1);store.file.delete();store=NotesStore(activity,Person.USER_1)
            store.addGroup("Personal",false,true);store.addNote(store.groups.single());store.edit(store.notes.single().copy(title="My note"))
            activity.setContent{KotikiTheme("light"){NotesScreen(store,LocalOrderStore(ui.activity,"notes-ui-test")){}}}
        }
        ui.onNodeWithText("My note").performSemanticsAction(SemanticsActions.OnClick){it()}
        ui.onNodeWithContentDescription("Закрыть заметку").assertIsDisplayed()
        ui.runOnIdle{assertFalse(editor("Название").hasFocus())}
        ui.runOnIdle{editor().requestFocus();editor().text.append("Text saved now")}
        ui.runOnIdle{assertEquals("Text saved now",NotesStore(ui.activity,Person.USER_1).notes.single().preview)}
        ui.onNodeWithContentDescription("Закрыть заметку").performClick()
        ui.onNodeWithText("Text saved now").assertIsDisplayed()
    }
    @Test fun archiveAndMoveAreInSameMenuAndArchiveIsSeparate() {
        lateinit var store:NotesStore
        ui.activityRule.scenario.onActivity{a->store=NotesStore(a,Person.USER_1);store.file.delete();store=NotesStore(a,Person.USER_1)
            store.addGroup("Group",false,true);store.addNote(store.groups.single());store.edit(store.notes.single().copy(title="Archive this"))
            a.setContent{KotikiTheme("light"){NotesScreen(store,LocalOrderStore(ui.activity,"notes-ui-test")){}}}}
        ui.onNodeWithContentDescription("Действия: Archive this").performClick()
        ui.onNodeWithText("Переместить").assertIsDisplayed();ui.onNodeWithText("В архив").performClick()
        ui.onNodeWithText("Archive this").assertDoesNotExist()
        ui.onNodeWithText("Архив заметок").performClick();ui.onNodeWithText("Archive this").assertIsDisplayed()
    }
    @Test fun blankAreaStartsNewLineAndSavesWithoutClose() {
        lateinit var store:NotesStore
        ui.activityRule.scenario.onActivity{a->
            store=NotesStore(a,Person.USER_1);store.file.delete();store=NotesStore(a,Person.USER_1)
            store.addGroup("Group",false,true);store.addNote(store.groups.single())
            store.edit(store.notes.single().copy(title="Blank tap",blocks=listOf(NoteBlock(text="First line"))))
            a.setContent{KotikiTheme("light"){NotesScreen(store,LocalOrderStore(a,"blank-tap")){}}}
        }
        ui.onNodeWithText("Blank tap").performClick()
        ui.runOnIdle{
            val input=editor();input.requestFocus();input.text.append("\nSecond line")
            assertEquals("First line\nSecond line",NotesStore(ui.activity,Person.USER_1).notes.single().preview)
            input.text.append("\n")
            assertEquals("First line\nSecond line\n",store.notes.single().preview)
        }
    }
    @Test fun blankTapDoesNotAddLeadingOrRepeatedEmptyLines() {
        assertEquals("",noteBlankLine(""))
        assertEquals("text\n",noteBlankLine("text"))
        assertEquals("text\n",noteBlankLine("text\n"))
    }

}
