package team.kotiki.app

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ShareReceiverTest {
    @Test fun incomingTextIsPlainAndPreservesLinkAndTitle() {
        val parsed=SharedContentInbox.parse(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,"**literal** https://example.com/place").putExtra(Intent.EXTRA_SUBJECT,"A place"))!!
        assertEquals("**literal** https://example.com/place",parsed.text)
        assertEquals("A place",parsed.subject);assertEquals(PLAIN_TEXT_FORMAT,parsed.textFormat)
    }
    @Test fun htmlFallbackBecomesTextWithoutActiveMarkup() {
        val parsed=SharedContentInbox.parse(Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_HTML_TEXT,"<b>Hello</b>"))!!
        assertEquals("Hello",parsed.text)
    }
    @Test fun unexpectedActionsFilesAndOversizedInputAreNotAccepted() {
        assertNull(SharedContentInbox.parse(Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Text")))
        assertNull(SharedContentInbox.parse(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT,"Text")))
        assertNull(SharedContentInbox.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"x".repeat(100001))))
    }
}
