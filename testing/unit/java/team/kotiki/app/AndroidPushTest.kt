package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test

class AndroidPushTest {
    private fun data(kind:String="event",record:String="entry_one",chat:String="",collection:String="",item:String="")=mapOf(
        "eventId" to "event-123","authUid" to "authenticated-user","spaceId" to "space",
        "kind" to kind,"recordId" to record,"chatId" to chat,"collectionId" to collection,"itemId" to item)
    private fun parse(value:Map<String,String>)=PushPolicy.parse(value,"authenticated-user","space")
    @Test fun onlyCurrentAccountAndSpaceCanReceive(){
        assertNotNull(parse(data()))
        assertNull(PushPolicy.parse(data(),"other-user","space"))
        assertNull(PushPolicy.parse(data(),"authenticated-user","other-space"))
    }
    @Test fun entryAndPlanChatRoutesMustMatchTheirParent(){
        assertNotNull(parse(data("chat","entry_one","entry_one",item="one")))
        assertNotNull(parse(data("chat","plan_theme","plan_theme_plan","theme","plan")))
        assertNull(parse(data("chat","plan_wrong","plan_theme_plan","theme","plan")))
        assertNull(parse(data("chat","entry_other","entry_one",item="one")))
    }
    @Test fun generalChatDoesNotAcceptUnrelatedRecordTarget(){
        assertNotNull(parse(data("chat","","general")))
        assertNull(parse(data("chat","entry_one","general")))
    }
    @Test fun silentRefreshHasARecordAndCannotSmuggleChatRoute(){
        assertEquals("refresh",parse(data("refresh"))!!.kind)
        assertNull(parse(data("refresh","")))
        assertNull(parse(data("refresh",chat="general")))
    }
    @Test fun gatheringMetadataOnlyAppliesToRecordNotifications(){
        assertTrue(parse(data("invite")+mapOf("gathering" to "true"))!!.gathering)
        assertFalse(parse(data("invite"))!!.gathering)
        assertFalse(parse(data("chat","","general")+mapOf("gathering" to "true"))!!.gathering)
    }
    @Test fun malformedAndUnknownTargetsAreRejected(){
        assertNull(parse(data()+mapOf("eventId" to "")))
        assertNull(parse(data()+mapOf("eventId" to "../secret")))
        assertNull(parse(data("unknown")))
        assertNull(parse(data(record="entry_")))
        assertNull(parse(data(record="entry_a/secret")))
        assertNull(parse(data("chat","plan_bad/path","plan_bad/path_x","bad/path","x")))
    }
}
