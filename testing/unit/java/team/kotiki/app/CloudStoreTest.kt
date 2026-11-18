package team.kotiki.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CloudStoreTest {
    private val session=CloudSession("test-one","home",Person.USER_1,listOf("test-one","test-two"),
        mapOf("test-one" to Person.USER_1,"test-two" to Person.USER_2),mapOf(Person.USER_1 to "User 1",Person.USER_2 to "User 2"))
    private fun store()=DemoStore(RuntimeEnvironment.getApplication(),cloudSession=session)
    private val day=LocalDate.of(2026,11,17)
    @Test fun cloudStartsEmptyAndNeverUploadsExistingDemo() {
        val context=RuntimeEnvironment.getApplication() as Context
        DemoStore(context).upsert(Entry("local-only","Local private title",day,mondayOf(day),visibility=Visibility.PRIVATE))
        val cloud=store()
        assertTrue(cloud.entries.isEmpty());assertTrue(cloud.plans.isEmpty());assertTrue(cloud.lists.isEmpty());assertNull(cloud.preset)
    }
    @Test fun remoteSnapshotsDoNotTriggerWrites() {
        val cloud=store();var writes=0;cloud.onCloudSave={writes++}
        cloud.receiveCloud(StoreContent(listOf(Entry("remote","Received",day,mondayOf(day))),emptyList(),emptyList()))
        assertEquals(0,writes);assertEquals("Received",cloud.entries.single().title)
        cloud.upsert(cloud.entries.single().copy(title="Edited"));assertEquals(1,writes)
    }
    @Test fun privateProjectionDoesNotDiscloseEvenSchedule() {
        val record=StoreContent(listOf(Entry("secret","Secret title",day,mondayOf(day),start="19:00",note="Secret note",visibility=Visibility.PRIVATE)),emptyList(),emptyList()).records(session).single()
        val projection=record.projection("revision")
        assertEquals(setOf("owner","revision","active"),projection.keys)
        assertEquals(false,projection["active"])
    }
    @Test fun busyProjectionHasScheduleWithoutPrivateDetails() {
        val e=Entry("busy","Private title",day,mondayOf(day),start="19:00",note="Private note",visibility=Visibility.BUSY)
        val record=StoreContent(listOf(e),emptyList(),emptyList()).records(session).single()
        val projection=record.projection("revision")
        assertEquals("19:00",projection["start"]);assertFalse(projection.containsKey("title"));assertFalse(projection.containsKey("note"))
        assertEquals(listOf("test-one"),record.envelope(session,"revision",0L)["readers"])
        assertEquals(false,record.copy(deleted=true).projection("revision")["active"])
    }
    @Test fun cloudRecipientCannotDeleteAcceptedInvitation() {
        val e=Entry("invite","Invite",day,mondayOf(day),owner=Person.USER_2,invite=InviteState.ACCEPTED)
        assertFalse(store().canDelete(e))
    }

    @Test fun planDescriptionIsIncludedInCloudPayload() {
        val item=CollectionItem("item","Visit",description="Buy tickets")
        val record=StoreContent(emptyList(),listOf(DemoCollection("group","Plans",items=listOf(item))),emptyList()).records(session).single()
        val decoded=readCollection(org.json.JSONObject(record.data))
        assertEquals(item,decoded.items.single())
    }
}
