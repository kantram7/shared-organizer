package team.kotiki.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DemoStoreTest {
    private fun context():Context {
        val prefix="test_${UUID.randomUUID()}_"
        return object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)
        }
    }
    @Test fun deletingEntryAndUndoSurviveReload() {
        val context=context();val store=DemoStore(context)
        val today=LocalDate.now();val entry=Entry("delete-test","Test",today,mondayOf(today),owner=store.viewer)
        store.upsert(entry);store.deleteEntry(entry)
        assertFalse(DemoStore(context).entries.any{it.id==entry.id})
        store.restore(store.undoAction!!)
        assertEquals(entry,DemoStore(context).entries.find{it.id==entry.id})
    }
    @Test fun forbiddenDeletionCannotRemovePrivateEntry() {
        val store=DemoStore(context());val today=LocalDate.now()
        val entry=Entry("private-test","Private",today,mondayOf(today),owner=store.viewer.other(),visibility=Visibility.PRIVATE)
        store.upsert(entry);store.deleteEntry(entry)
        assertTrue(store.entries.contains(entry));assertNull(store.undoAction)
    }
    @Test fun deletingCollectionAndUndoRestoresItsItems() {
        val context=context();val store=DemoStore(context)
        store.addCollection(false,"Test",false);val id=store.lists.last().id
        store.addItem(false,id,"One");val original=store.lists.find{it.id==id}!!
        store.deleteCollection(false,id)
        assertFalse(DemoStore(context).lists.any{it.id==id})
        store.restore(store.undoAction!!)
        assertEquals(original,DemoStore(context).lists.find{it.id==id})
    }
    @Test fun itemUndoPreservesSubsequentChanges() {
        val context=context();val store=DemoStore(context)
        store.addCollection(true,"Test",false);val id=store.plans.last().id
        assertTrue(store.plans.last().shared)
        store.addItem(true,id,"One");val item=store.plans.last().items.single()
        store.deleteItem(true,id,item.id);val undo=store.undoAction!!
        store.addItem(true,id,"Two");store.restore(undo)
        assertEquals(listOf("One","Two"),DemoStore(context).plans.find{it.id==id}!!.items.map{it.title})
    }
    @Test fun migrationRemovesOnlyPrivatePlansAndPreferencesPersist() {
        val context=context();val store=DemoStore(context)
        store.plans.add(DemoCollection("old-private","Private demo",false))
        store.addCollection(false,"Private list",false)
        val listId=store.lists.last().id
        store.updateBoardOverview(false)
        val reloaded=DemoStore(context)
        assertFalse(reloaded.plans.any{!it.shared})
        assertTrue(reloaded.lists.any{it.id==listId && !it.shared})
        assertFalse(reloaded.boardOverview)
    }
    @Test fun editingThemeUpdatesPersistedRecencyButOpeningDoesNot() {
        val context=context();val store=DemoStore(context)
        store.addCollection(true,"First",true);val first=store.plans.last().id
        store.addCollection(true,"Second",true);val second=store.plans.last().id
        store.addItem(true,first,"Edited")
        val reloaded=DemoStore(context)
        assertTrue(reloaded.plans.find{it.id==first}!!.updatedAt>reloaded.plans.find{it.id==second}!!.updatedAt)
        assertEquals(reloaded.plans.find{it.id==first}!!.updatedAt,DemoStore(context).plans.find{it.id==first}!!.updatedAt)
    }
}
