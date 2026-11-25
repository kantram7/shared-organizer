package team.kotiki.app

import android.app.Notification
import android.content.Context
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
class InviteFormats036Test {
    private val day=LocalDate.of(2027,1,20)
    private val original=Entry("format-invite","**Literal title**",day,mondayOf(day),start="18:00",end="19:00",
        kind=Kind.EVENT,owner=Person.USER_1,invite=InviteState.ACCEPTED,note="_Literal note_")
    private val candidate=original.copy(title="**New title**",note="A <u>new note</u>",titleFormat=MARKDOWN_TEXT_FORMAT,descriptionFormat=MARKDOWN_TEXT_FORMAT)
    private fun offered()=InviteWorkflow.propose(original,candidate,Person.USER_2,"format-offer")!!

    @Test fun proposalFormatsRoundTripAndAcceptanceChangesOnlyAfterConfirmation() {
        val offered=offered()
        assertEquals(original,offered.copy(proposal=null))
        val reloaded=readEntry(writeEntry(offered),emptyMap())
        assertEquals(offered,reloaded)
        val draft=InviteWorkflow.candidate(reloaded)
        assertEquals(MARKDOWN_TEXT_FORMAT,draft.titleFormat)
        assertEquals(MARKDOWN_TEXT_FORMAT,draft.descriptionFormat)
        assertEquals("New title",richPlainText(draft.title,draft.titleFormat))
        val accepted=InviteWorkflow.respond(reloaded,Person.USER_1,true,"format-offer",day)!!
        assertEquals("New title",richPlainText(accepted.title,accepted.titleFormat))
        assertEquals("A new note",richPlainText(accepted.note,accepted.descriptionFormat))
        assertEquals(original.visibility,accepted.visibility)
        assertEquals(original.owner,accepted.owner)
    }

    @Test fun rejectionKeepsOriginalLiteralFormatsAndContent() {
        val rejected=InviteWorkflow.respond(offered(),Person.USER_1,false,"format-offer",day)!!
        assertEquals(original.copy(proposalResponseId="format-offer"),rejected)
        assertEquals("**Literal title**",richPlainText(rejected.title,rejected.titleFormat))
    }

    @Test fun legacyProposalWithoutFormatFieldsRemainsLiteral() {
        val json=writeEntry(offered())
        json.getJSONObject("proposal").apply {
            remove("titleFormat");remove("descriptionFormat")
            put("title","**Legacy stars**");put("note","_Legacy underscores_")
        }
        val legacy=readEntry(json,emptyMap())
        assertEquals(PLAIN_TEXT_FORMAT,legacy.proposal!!.titleFormat)
        val accepted=InviteWorkflow.respond(legacy,Person.USER_1,true,"format-offer",day)!!
        assertEquals("**Legacy stars**",richPlainText(accepted.title,accepted.titleFormat))
        assertEquals("_Legacy underscores_",richPlainText(accepted.note,accepted.descriptionFormat))
    }

    @Test fun proposalsUseTheSameDescriptionLimitAsTheEntryEditor() {
        assertNull(InviteWorkflow.proposalError(original,candidate.copy(note="n".repeat(10000))))
        assertNotNull(InviteWorkflow.propose(original,candidate.copy(note="n".repeat(301)),Person.USER_2,"long-offer"))
        assertNotNull(InviteWorkflow.proposalError(original,candidate.copy(note="n".repeat(10001))))
        assertNull(InviteWorkflow.propose(original,candidate.copy(descriptionFormat="unknown-v9"),Person.USER_2,"bad-format"))
    }

    @Test fun reminderSnapshotAndNotificationUseVisibleTitleButPreserveLegacyLiterals() {
        val context=RuntimeEnvironment.getApplication() as Context
        val uid="reminder-formats-"+UUID.randomUUID()
        val tomorrow=LocalDate.now().plusDays(2)
        val rich=candidate.copy(date=tomorrow,lastDate=tomorrow,week=mondayOf(tomorrow))
        try {
            LocalReminders.sync(context,uid,listOf(rich),Person.USER_1)
            val row=ReminderPreferences(context).snapshot(uid)!!.getJSONArray("entries").getJSONObject(0)
            assertEquals("New title",row.getString("title"))
            assertEquals("New title",LocalReminders.buildNotification(context,uid,rich).extras.getString(Notification.EXTRA_TITLE))
            val plain=rich.copy(title="**Literal title**",titleFormat=PLAIN_TEXT_FORMAT)
            LocalReminders.sync(context,uid,listOf(plain),Person.USER_1)
            assertEquals("**Literal title**",ReminderPreferences(context).snapshot(uid)!!.getJSONArray("entries").getJSONObject(0).getString("title"))
            assertEquals("**Literal title**",LocalReminders.buildNotification(context,uid,plain).extras.getString(Notification.EXTRA_TITLE))
        } finally { LocalReminders.clearProfile(context,uid) }
    }
}
