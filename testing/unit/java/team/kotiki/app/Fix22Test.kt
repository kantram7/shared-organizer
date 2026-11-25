package team.kotiki.app

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Fix22Test {
    @Test fun threeDistinctChoicesCanBeRemovedAndReplacedWithoutSilentEviction() {
        var choices=emptyList<String>()
        listOf("💜","🐾","👍").forEach{choices=ReactionChoices.toggle(choices,it,false)}
        try{ReactionChoices.toggle(choices,"😊",false);fail("Fourth reaction accepted")}catch(_:IllegalArgumentException){}
        assertEquals(listOf("💜","🐾","👍"),choices)
        choices=ReactionChoices.toggle(choices,"🐾",false)
        assertEquals(listOf("💜","👍","👀"),ReactionChoices.toggle(choices,"👀",false))
    }
    @Test fun seasonOnlyRestrictsNewSelectionsAndOldSingleReactionStillLoads() {
        assertEquals(9,ReactionChoices.available(false).size);assertEquals(11,ReactionChoices.available(true).size)
        assertEquals(listOf("🐾"),ReactionChoices.decode(mapOf("emoji" to "🐾")))
        val saved=ReactionChoices.toggle(listOf("💜"),"❄️",true)
        assertEquals(listOf("💜"),ReactionChoices.toggle(saved,"❄️",false))
        try{ReactionChoices.toggle(emptyList(),"🥶",false);fail("Winter choice leaked")}catch(_:IllegalArgumentException){}
        assertEquals(listOf("❤️","👀"),ReactionChoices.decode(mapOf("emojis" to listOf("❤️","❤️","👀","unknown"))))
    }
    @Test fun externalSharingContainsOriginalUriAndReadOnlyTemporaryGrant() {
        val context=RuntimeEnvironment.getApplication();val uri=Uri.parse("content://mock-media/original.png")
        val intent=attachmentShareIntent(context,uri,"image/png","photo.png")
        assertEquals(Intent.ACTION_SEND,intent.action);assertEquals("image/png",intent.type)
        assertEquals(uri,intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java))
        assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
        assertEquals("image/png",intent.clipData!!.description.getMimeType(0))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION !=0)
        assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
    }
}
