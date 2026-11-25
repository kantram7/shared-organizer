package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test

class SourceTaskPrivacy036Test {
    private fun privateTask(text:String)=taskSourcePrivate(text,
        notePrivate={when(it){"private_note"->true;"shared_note"->false;else->null}},
        entryVisibility={when(it){"shared_entry"->Visibility.SHARED;"private_entry"->Visibility.PRIVATE;"busy_entry"->Visibility.BUSY;else->null}})

    @Test fun earlySharedLinkCannotPublishAnEntirePrivateNote() {
        val text="Personal note with reference kotiki://entry/shared_entry\nPrivate content\n\nИсточник: kotiki://note/private_note"
        assertTrue(privateTask(text))
    }
    @Test fun onlyFinalGeneratedSourceDeterminesPrivacy() {
        assertFalse(privateTask("Text kotiki://note/private_note\n\nИсточник: kotiki://entry/shared_entry"))
        assertTrue(privateTask("Text\n\nИсточник: kotiki://entry/private_entry"))
        assertTrue(privateTask("Text\n\nИсточник: kotiki://entry/busy_entry"))
        assertFalse(privateTask("Text\n\nИсточник: kotiki://note/shared_note"))
    }
    @Test fun missingAndMalformedSourcesFailPrivate() {
        listOf("Text kotiki://entry/shared_entry","Text","\n\nИсточник: kotiki://note/missing",
            "\n\nИсточник: kotiki://entry/missing","\n\nИсточник: kotiki://note/private_note?source=shared",
            "\n\nИсточник: kotiki://entry/shared_entry\nMore text","\n\nИсточник: kotiki://entry/shared_entry\n").forEach{assertTrue(it,privateTask(it))}
    }
    @Test fun generatedSharedChatAndPlanLinksStayShared() {
        assertFalse(privateTask("Message\n\nkotiki://chat/general/message/message_id"))
        assertFalse(privateTask("Selected text\n\nИсточник: kotiki://chat/entry_shared_entry/message/message_id"))
        assertFalse(privateTask("Plan text\n\nИсточник: kotiki://plan/group_id/item_id"))
        assertTrue(privateTask("kotiki://chat/general/message/message_id is only body text"))
    }
}
