package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class InviteWorkflowTest {
    private val day=LocalDate.of(2027,1,20)
    private val original=Entry("invitation","Встреча",day,mondayOf(day),start="18:00",end="19:00",kind=Kind.EVENT,owner=Person.USER_1,invite=InviteState.ACCEPTED,resolvedDay=day.minusDays(2))
    private val changed=original.copy(title="Другое место",date=day.plusDays(4),lastDate=day.plusDays(4),start="20:00",end="21:00",note="Давай позже")
    private fun offer(entry:Entry=original,actor:Person=Person.USER_2,id:String="offer-1",expected:String?=null)=InviteWorkflow.propose(entry,changed,actor,id,expected)!!

    @Test fun proposedTimeDoesNotReplaceAcceptedTime(){
        val offered=offer()
        assertEquals(original,offered.copy(proposal=null))
        assertEquals(changed.date,offered.proposal!!.date)
        assertEquals(Person.USER_1,InviteWorkflow.responder(offered))
    }
    @Test fun acceptanceMovesOnlyAllowedFieldsAndClearsProposal(){
        val accepted=InviteWorkflow.respond(offer(),Person.USER_1,true,"offer-1",day)!!
        assertEquals(changed.date,accepted.date);assertEquals(mondayOf(changed.date!!),accepted.week)
        assertEquals("20:00",accepted.start);assertEquals(changed.title,accepted.title);assertEquals(changed.note,accepted.note)
        assertEquals(original.owner,accepted.owner);assertEquals(InviteState.ACCEPTED,accepted.invite)
        assertEquals(day,accepted.resolvedDay);assertNull(accepted.proposal)
        assertEquals("offer-1",accepted.proposalResponseId)
    }
    @Test fun rejectionLeavesPreviouslyAcceptedMeetingIntact(){
        assertEquals(original.copy(proposalResponseId="offer-1"),InviteWorkflow.respond(offer(),Person.USER_1,false,"offer-1",day))
    }
    @Test fun cannotRespondToOwnProposalOrUseStaleId(){
        val offered=offer()
        assertNull(InviteWorkflow.respond(offered,Person.USER_2,true,"offer-1",day))
        assertNull(InviteWorkflow.respond(offered,Person.USER_1,true,"old-offer",day))
        assertNull(InviteWorkflow.respond(offered,Person.USER_1,false,null,day))
    }
    @Test fun counterCounterProposalNeedsOtherPersonsConfirmation(){
        val first=offer()
        val second=offer(first,Person.USER_1,"offer-2","offer-1")
        assertEquals(original,second.copy(proposal=null))
        assertEquals(Person.USER_2,InviteWorkflow.responder(second))
        assertNull(InviteWorkflow.respond(second,Person.USER_1,true,"offer-2",day))
        assertNull(InviteWorkflow.respond(second,Person.USER_2,true,"offer-1",day))
        assertNotNull(InviteWorkflow.respond(second,Person.USER_2,true,"offer-2",day))
    }
    @Test fun staleOrRepeatedActionsCannotOverwriteNewerProposal(){
        val first=offer()
        assertNull(InviteWorkflow.propose(first,changed,Person.USER_1,"offer-2"))
        assertNull(InviteWorkflow.propose(first,changed,Person.USER_1,"offer-1","offer-1"))
        val accepted=InviteWorkflow.respond(first,Person.USER_1,true,"offer-1",day)!!
        assertNull(InviteWorkflow.respond(accepted,Person.USER_1,true,"offer-1",day))
    }
    @Test fun initialRecipientCanDeclineOrAcceptButAuthorCannot(){
        val pending=original.copy(invite=InviteState.PENDING,resolvedDay=null)
        assertEquals(Person.USER_2,InviteWorkflow.responder(pending))
        assertEquals(InviteState.DECLINED,InviteWorkflow.respond(pending,Person.USER_2,false,null,day)!!.invite)
        assertEquals(InviteState.ACCEPTED,InviteWorkflow.respond(pending,Person.USER_2,true,null,day)!!.invite)
        assertNull(InviteWorkflow.respond(pending,Person.USER_1,true,null,day))
        val proposed=offer(pending)
        assertEquals(pending.copy(proposalResponseId="offer-1"),InviteWorkflow.respond(proposed,Person.USER_1,false,"offer-1",day))
    }
    @Test fun invalidScheduleAndNonParticipantsCannotPropose(){
        assertNull(InviteWorkflow.propose(original,changed.copy(date=null),Person.USER_2,"p"))
        assertNull(InviteWorkflow.propose(original,changed.copy(end="19:00"),Person.USER_2,"p"))
        assertNull(InviteWorkflow.propose(original,changed.copy(id="another"),Person.USER_2,"p"))
        assertNull(InviteWorkflow.propose(original,changed,Person.BOTH,"p"))
        assertNull(InviteWorkflow.propose(original.copy(visibility=Visibility.PRIVATE),changed,Person.USER_2,"p"))
        assertNull(InviteWorkflow.propose(original.copy(invite=InviteState.DECLINED),changed,Person.USER_2,"p"))
    }
    @Test fun gatheringStaysOneDayWhileOrdinaryEventMaySpanDays(){
        val spanning=changed.copy(lastDate=changed.date!!.plusDays(1),end="07:00")
        assertNotNull(InviteWorkflow.propose(original,spanning,Person.USER_2,"p"))
        assertNull(InviteWorkflow.propose(original.copy(isGathering=true),spanning,Person.USER_2,"p"))
    }
    @Test fun newProposalClearsPreviousResponseMarker(){
        val withOldMarker=original.copy(proposalResponseId="older")
        assertEquals("",offer(withOldMarker).proposalResponseId)
    }
    @Test fun candidatePrefillsProposalWithoutChangingBase(){
        val offered=offer()
        val candidate=InviteWorkflow.candidate(offered)
        assertEquals(changed.title,candidate.title);assertEquals(changed.date,candidate.date)
        assertEquals(original.date,offered.date)
    }
}
