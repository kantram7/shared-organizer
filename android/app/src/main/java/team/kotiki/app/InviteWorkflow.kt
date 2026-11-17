package team.kotiki.app

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/** Proposed fields are separate from the currently agreed event until the other person accepts. */
data class InviteProposal(
    val id:String,
    val proposedBy:Person,
    val title:String,
    val date:LocalDate,
    val lastDate:LocalDate,
    val start:String,
    val end:String,
    val note:String,
):Serializable

object InviteWorkflow {
    private fun participant(entry:Entry,actor:Person)=actor!=Person.BOTH && entry.owner!=Person.BOTH &&
        entry.visibility==Visibility.SHARED && (actor==entry.owner || entry.who==Person.BOTH || entry.who==actor)

    fun responder(entry:Entry):Person? {
        if(entry.invite !in listOf(InviteState.PENDING,InviteState.ACCEPTED))return null
        val actor=entry.proposal?.proposedBy?.takeIf{it!=Person.BOTH}?.other()
            ?: if(entry.proposal==null && entry.invite==InviteState.PENDING)entry.owner.other() else return null
        return actor.takeIf{participant(entry,it)}
    }

    fun proposalError(entry:Entry,candidate:Entry):String? {
        if(candidate.title.isBlank() || candidate.title.length>100)return "Укажи название до 100 символов"
        if(candidate.note.length>300)return "Комментарий должен быть не длиннее 300 символов"
        val date=candidate.date ?: return "Выбери точную дату"
        val last=candidate.lastDate ?: date
        if(last<date)return "Окончание не может быть раньше начала"
        val start=runCatching{LocalTime.parse(candidate.start)}.getOrNull() ?: return "Выбери время начала"
        val end=runCatching{LocalTime.parse(candidate.end)}.getOrNull() ?: return "Выбери время окончания"
        if(last==date && end<=start)return "Время окончания должно быть позже начала"
        if(entry.isGathering && last!=date)return "Общий сбор должен закончиться в тот же день"
        return null
    }

    fun propose(entry:Entry,candidate:Entry,actor:Person,proposalId:String,expectedProposalId:String?=null):Entry? {
        if(entry.invite !in listOf(InviteState.PENDING,InviteState.ACCEPTED) || !participant(entry,actor))return null
        if(entry.proposal?.id!=expectedProposalId || candidate.id!=entry.id)return null
        if(proposalId.isBlank() || proposalId.length>128 || '/' in proposalId || proposalId==expectedProposalId)return null
        if(proposalError(entry,candidate)!=null)return null
        val proposal=InviteProposal(proposalId,actor,candidate.title.trim(),candidate.date!!,
            candidate.lastDate ?: candidate.date,candidate.start,candidate.end,candidate.note.trim())
        return entry.copy(proposal=proposal,proposalResponseId="")
    }

    /** expectedProposalId must come from the displayed card, not be read again when submitting. */
    fun respond(entry:Entry,actor:Person,accept:Boolean,expectedProposalId:String?,today:LocalDate=LocalDate.now()):Entry? {
        if(!participant(entry,actor) || responder(entry)!=actor || entry.proposal?.id!=expectedProposalId)return null
        val proposal=entry.proposal
        if(proposal!=null){
            if(!accept)return entry.copy(proposal=null,proposalResponseId=proposal.id)
            val accepted=entry.copy(title=proposal.title,date=proposal.date,lastDate=proposal.lastDate,
                week=mondayOf(proposal.date),start=proposal.start,end=proposal.end,note=proposal.note,
                invite=InviteState.ACCEPTED,resolvedDay=today,proposal=null,proposalResponseId=proposal.id)
            if(proposalError(entry,accepted)!=null)return null
            return accepted
        }
        if(entry.invite!=InviteState.PENDING)return null
        return entry.copy(invite=if(accept)InviteState.ACCEPTED else InviteState.DECLINED,resolvedDay=today)
    }

    fun candidate(entry:Entry):Entry=entry.proposal?.let{proposal->entry.copy(
        title=proposal.title,date=proposal.date,lastDate=proposal.lastDate,week=mondayOf(proposal.date),
        start=proposal.start,end=proposal.end,note=proposal.note)} ?: entry
}
