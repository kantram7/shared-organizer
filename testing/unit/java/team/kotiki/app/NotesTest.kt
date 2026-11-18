package team.kotiki.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NotesTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun store():NotesStore {val s=NotesStore(context,Person.USER_1);s.file.delete();return NotesStore(context,Person.USER_1)}
    @Test fun localNoteExistsImmediatelyAndEveryEditSurvivesRestart() {
        val s=store();val gid=s.addGroup("Personal",false,true);assertTrue(s.error,s.healthy);val id=s.addNote(s.groups.single());assertTrue(s.error,s.healthy);assertEquals("Creation returned "+id,1,s.notes.size)
        assertTrue(s.file.baseFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertEquals(s.notes.single(),noteValue(s.notes.single().json())); val first=NotesStore(context,Person.USER_1);assertTrue(first.readFailure?.stackTraceToString() ?: s.file.baseFile.readText(),first.healthy);assertEquals(s.file.baseFile.readText(),1,first.notes.size);assertEquals(id,first.notes.single().id)
        s.edit(s.notes.single().copy(title="A title",blocks=listOf(NoteBlock(kind=NoteBlockKind.TEXT,text="Some text"),NoteBlock(kind=NoteBlockKind.CHECK,text="Buy coffee",checked=true))))
        val reloaded=NotesStore(context,Person.USER_1);assertTrue(reloaded.readFailure?.stackTraceToString() ?: s.file.baseFile.readText(),reloaded.healthy)
        assertEquals(gid,reloaded.notes.single().groupId);assertEquals("A title",reloaded.notes.single().title)
        assertTrue(reloaded.notes.single().blocks.last().checked);assertFalse(reloaded.pending)
        assertFalse(reloaded.groups.single().shared)
    }
    @Test fun archiveIsSeparateSearchIncludesBodiesAndRestoreKeepsChecks() {
        val s=store();s.addGroup("One",false,true);s.addNote(s.groups.single())
        s.edit(s.notes.single().copy(blocks=listOf(NoteBlock(kind=NoteBlockKind.CHECK,text="Кофе",checked=true))))
        assertTrue(s.notes.single().matches("коФ"));s.archive(s.notes.single())
        val reload=NotesStore(context,Person.USER_1);assertTrue(reload.readFailure?.stackTraceToString() ?: "healthy",reload.healthy);assertEquals(s.file.baseFile.readText(),1,reload.notes.size);assertTrue(reload.notes.single().archived)
        reload.archive(reload.notes.single(),false);assertFalse(reload.notes.single().archived);assertTrue(reload.notes.single().blocks.single().checked)
        reload.purge(reload.notes.single());assertEquals(1,reload.notes.size)
        reload.archive(reload.notes.single());reload.purge(reload.notes.single());assertTrue(NotesStore(context,Person.USER_1).notes.isEmpty())
    }
    @Test fun moveBetweenLocalGroupsChangesAccessWithoutCloning() {
        val s=store();s.addGroup("Private",false,true);s.addNote(s.groups.single());s.addGroup("Shared demo",true,true)
        val n=s.notes.single();s.move(n,s.groups.last());assertEquals(1,s.notes.size);assertEquals(n.id,s.notes.single().id)
        assertTrue(s.groupOf(s.notes.single())!!.shared);s.move(s.notes.single(),s.groups.first());assertFalse(s.groupOf(s.notes.single())!!.shared)
    }
    @Test fun profilesNeverShareLocalNotesAndCorruptionNeverResetsFile() {
        val s=store();s.addGroup("Secret",false,true);s.addNote(s.groups.single())
        val other=NotesStore(context,Person.USER_2);assertNotEquals(s.file.baseFile,other.file.baseFile)
        s.file.baseFile.writeText("broken data")
        val damaged=NotesStore(context,Person.USER_1);assertFalse(damaged.healthy);assertEquals("",damaged.addGroup("No",false,true));assertEquals("broken data",s.file.baseFile.readText())
        s.file.delete()
    }
    @Test fun readonlyGroupBlocksContentEditsButAllowsMovingForAnyParticipant() {
        val s=store();val g=NoteGroup("g","Shared",true,Person.USER_2,editable=false)
        s.groups.add(g);s.notes.add(Note("n",g.id,title="Original"));s.edit(s.notes.single().copy(title="Forbidden"));assertEquals("Original",s.notes.single().title)
        s.addGroup("Mine",false,true);s.move(s.notes.single(),s.groups.last());assertEquals("Mine",s.groupOf(s.notes.single())!!.name)
    }
}
