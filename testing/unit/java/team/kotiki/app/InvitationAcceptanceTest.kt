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
class InvitationAcceptanceTest {
    private fun context():Context {
        val prefix="accept_${UUID.randomUUID()}_"
        return object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)
        }
    }
    private fun invitation(store:DemoStore)=newGathering(store.viewer.other()).let {
        val day=LocalDate.now()
        it.copy(date=day,lastDate=day,start="19:00",end="20:00",incoming=false)
    }
    @Test fun recipientCanAcceptPersistAndUndoGathering() {
        val context=context();val store=DemoStore(context);val original=invitation(store)
        store.upsert(original);store.acceptInvitation(original)
        val saved=DemoStore(context).entries.single{it.id==original.id}
        assertEquals(InviteState.ACCEPTED,saved.invite)
        assertEquals(LocalDate.now(),saved.resolvedDay)
        assertTrue(saved.isGathering);assertFalse(saved.isTentative)
        store.restore(store.undoAction!!)
        assertEquals(original,DemoStore(context).entries.single{it.id==original.id})
    }
    @Test fun authorNonParticipantAndHiddenEntryCannotBeAccepted() {
        val store=DemoStore(context());val base=invitation(store).copy(isGathering=false)
        listOf(base.copy(id="own",owner=store.viewer,incoming=true),
            base.copy(id="hidden",visibility=Visibility.PRIVATE,incoming=true),
            base.copy(id="other",who=store.viewer.other(),incoming=true)).forEach {
            store.upsert(it);store.acceptInvitation(it)
            assertEquals(it,store.entries.single{saved->saved.id==it.id})
        }
        assertNull(store.undoAction)
    }
    @Test fun repeatedStaleOrDeletedActionsCannotChangeCurrentState() {
        val context=context();val store=DemoStore(context);val pending=invitation(store)
        val accepted=pending.copy(invite=InviteState.ACCEPTED,resolvedDay=LocalDate.now().minusDays(2))
        store.upsert(accepted);store.acceptInvitation(pending)
        assertEquals(accepted,store.entries.single{it.id==pending.id})
        store.deleteEntry(accepted);store.acceptInvitation(pending)
        assertFalse(DemoStore(context).entries.any{it.id==pending.id})
    }
}
