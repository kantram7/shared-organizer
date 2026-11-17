package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DemoModelsTest {
    @Test fun localRoleAliasesPreserveOwnershipAndPrivacy() {
        val aliases=mapOf("LEGACY_FIRST" to "USER_1", "LEGACY_SECOND" to "USER_2")
        assertEquals(Person.USER_1,decodePerson("LEGACY_FIRST",aliases))
        val owner=decodePerson("LEGACY_SECOND",aliases)
        val private=entry().copy(owner=owner,who=owner,visibility=Visibility.PRIVATE)
        assertTrue(visibleEntries(listOf(private),OwnerFilter.ALL).isEmpty())
        assertEquals(Person.BOTH,decodePerson("BOTH",aliases))
    }
    @Test(expected=IllegalArgumentException::class) fun unknownRoleNeverDefaultsToCurrentUser() {
        decodePerson("UNKNOWN_ROLE")
    }
    private val day=LocalDate.of(2026,11,17)
    private fun entry()=Entry("test","План",day,mondayOf(day))
    @Test fun mondayAnchorAcrossYearBoundary() {
        assertEquals(LocalDate.of(2025,12,29),mondayOf(LocalDate.of(2026,1,1)))
        assertEquals(LocalDate.of(2026,11,16),mondayOf(day))
    }
    @Test fun sharedTaskAppearsInBothParticipantFilters() {
        val entries=listOf(entry())
        assertEquals(1,visibleEntries(entries,OwnerFilter.MINE).size)
        assertEquals(1,visibleEntries(entries,OwnerFilter.USER_2).size)
    }
    @Test fun otherPersonsPrivateDataNeverAppears() {
        val private=entry().copy(owner=Person.USER_2,who=Person.USER_2,visibility=Visibility.PRIVATE,note="secret")
        OwnerFilter.entries.forEach { assertTrue(visibleEntries(listOf(private),it).isEmpty()) }
    }
    @Test fun busyEntryIsRedactedBeforeRendering() {
        val busy=entry().copy(owner=Person.USER_2,who=Person.USER_2,title="secret title",note="secret note",visibility=Visibility.BUSY)
        val visible=visibleEntries(listOf(busy),OwnerFilter.ALL).single()
        assertEquals("Занято",visible.title);assertEquals("",visible.note);assertFalse(visible.canEdit())
        assertEquals(day,visible.date)
    }
    @Test fun monthBoundaryAndInclusiveEndDate() {
        val range=entry().copy(date=LocalDate.of(2026,11,29),lastDate=LocalDate.of(2026,12,2),kind=Kind.EVENT)
        assertTrue(range.isMultiDay)
        assertTrue(range.occursOn(LocalDate.of(2026,12,2)))
        assertFalse(range.occursOn(LocalDate.of(2026,12,3)))
    }
    @Test fun declinedInvitationsAreExcludedButPendingRemainVisible() {
        val declined=entry().copy(invite=InviteState.DECLINED)
        val pending=entry().copy(id="pending",invite=InviteState.PENDING)
        val visible=visibleEntries(listOf(declined,pending),OwnerFilter.ALL)
        assertEquals(listOf(pending),visible);assertTrue(visible.single().isTentative)
    }
    @Test fun ownPrivateTaskIsEditableButNotInUser2Filter() {
        val private=entry().copy(who=Person.USER_1,visibility=Visibility.PRIVATE)
        assertTrue(private.canEdit());assertTrue(visibleEntries(listOf(private),OwnerFilter.USER_2).isEmpty())
    }
    @Test fun secondProfileSeesOwnPrivateTaskAndCanEditIt() {
        val own=entry().copy(owner=Person.USER_2,who=Person.USER_2,visibility=Visibility.PRIVATE)
        assertEquals(listOf(own),visibleEntries(listOf(own),OwnerFilter.MINE,Person.USER_2))
        assertTrue(own.canEdit(Person.USER_2))
        assertTrue(visibleEntries(listOf(own),OwnerFilter.ALL,Person.USER_1).isEmpty())
    }
    @Test fun secondProfileCannotReadFirstProfilesBusyDetails() {
        val busy=entry().copy(owner=Person.USER_1,who=Person.USER_1,title="private title",note="private note",visibility=Visibility.BUSY)
        val shown=visibleEntries(listOf(busy),OwnerFilter.ALL,Person.USER_2).single()
        assertEquals("Занято",shown.title);assertEquals("",shown.note)
        assertFalse(shown.canEdit(Person.USER_2))
        assertEquals("private title",visibleEntries(listOf(busy),OwnerFilter.MINE,Person.USER_1).single().title)
    }
    @Test fun participantFilterIsRelativeToSelectedProfile() {
        val first=entry().copy(id="first",who=Person.USER_1)
        val second=entry().copy(id="second",who=Person.USER_2,owner=Person.USER_2)
        val shared=entry().copy(id="shared")
        val entries=listOf(first,second,shared)
        assertEquals(listOf(second,shared),visibleEntries(entries,OwnerFilter.MINE,Person.USER_2))
        assertEquals(listOf(first,shared),visibleEntries(entries,OwnerFilter.USER_2,Person.USER_2))
    }
}
