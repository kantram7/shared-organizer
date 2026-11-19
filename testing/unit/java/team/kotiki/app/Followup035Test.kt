package team.kotiki.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Followup035Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun invitation()=Entry("incoming","Invitation",LocalDate.now(),mondayOf(LocalDate.now()),
        kind=Kind.EVENT,owner=Person.USER_2,invite=InviteState.PENDING,start="19:00",end="20:00")
    @Test fun timeDefaultsOnlyForNewEventsAndNavigationOrderIsCalendarThenPlans() {
        val event=invitation().copy(start="",end="",invite=InviteState.NONE)
        assertTrue(initialEntryTimed(event,true))
        assertFalse(initialEntryTimed(event,false))
        assertFalse(initialEntryTimed(event.copy(kind=Kind.TASK),true))
        assertTrue(initialEntryTimed(event.copy(start="10:00"),false))
        assertEquals(listOf(Section.BOARD,Section.CALENDAR,Section.PLANS),Section.entries.take(3))
    }
    @Test fun readingDoesNotAcceptAndAnotherProposalIsUnreadAgain() {
        val profile="invite-test/${newId()}";val reads=InvitationReads(context,profile,Person.USER_1)
        val entry=invitation()
        assertEquals(1,reads.count(listOf(entry)))
        reads.read(entry);assertEquals(0,reads.count(listOf(entry)));assertEquals(InviteState.PENDING,entry.invite)
        assertFalse(InvitationReads(context,profile,Person.USER_1).unread(entry))
        val proposal=InviteWorkflow.propose(entry,entry.copy(start="20:00",end="21:00"),Person.USER_2,"proposal-one")!!
        assertTrue(reads.unread(proposal));reads.read(proposal);assertFalse(reads.unread(proposal))
        val updated=proposal.copy(proposal=proposal.proposal!!.copy(id="proposal-two"))
        assertTrue(reads.unread(updated))
        assertFalse(reads.unread(entry.copy(invite=InviteState.DECLINED)))
        assertFalse(reads.unread(entry.copy(invite=InviteState.ACCEPTED)))
        assertFalse(reads.unread(entry.copy(owner=Person.USER_1)))
        assertTrue(InvitationReads(context,"different/$profile",Person.USER_1).unread(entry))
    }
    @Test fun newEventEditorShowsCheckedTimeAndClearFromToLabels() {
        ui.activityRule.scenario.onActivity{a->a.setContent{KotikiTheme("light") {
            EntryEditor(invitation().copy(title="",invite=InviteState.NONE,start="",end=""),{}, {},isNew=true)
        }}}
        ui.onNodeWithText("С 19:00").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("По 20:00").assertIsDisplayed()
    }
    @Test fun displayedInvitationIsMarkedReadWithoutResponding() {
        val reads=InvitationReads(context,"screen/${newId()}",Person.USER_1)
        lateinit var store:DemoStore
        ui.activityRule.scenario.onActivity{a->store=DemoStore(a);store.entries.clear();store.entries.add(invitation())
            a.setContent{KotikiTheme("light"){CompositionLocalProvider(LocalInvitationReads provides reads){InvitationsScreen(store,{}, {})}}}}
        ui.onNodeWithText("Invitation").assertIsDisplayed()
        ui.runOnIdle{assertEquals(0,reads.count(store.entries));assertEquals(InviteState.PENDING,store.entries.single().invite)}
    }
}
