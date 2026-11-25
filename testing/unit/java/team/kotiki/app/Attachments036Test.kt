package team.kotiki.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Attachments036Test {
    private fun session()=CloudSession("user-${newId()}","space",Person.USER_1,listOf("user-a","user-b"),mapOf("user-a" to Person.USER_1,"user-b" to Person.USER_2),emptyMap())
    @Test fun byteCopyPreservesOriginalHashAndEnforcesStreamingLimit()=runBlocking {
        val source=ByteArray(140_013){((it*31)%256).toByte()};val out=ByteArrayOutputStream()
        assertEquals(source.size.toLong(),copyAttachmentOriginal(ByteArrayInputStream(source),out,source.size.toLong()))
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),MessageDigest.getInstance("SHA-256").digest(out.toByteArray()))
        try{copyAttachmentOriginal(ByteArrayInputStream(source),ByteArrayOutputStream(),source.size-1L);fail("Oversized stream accepted")}catch(_:IllegalArgumentException){}
        Unit
    }
    @Test fun countAndTotalLimitsAreIndependent(){
        AttachmentLimits.validate(List(10){50L*1024*1024})
        listOf(List(11){1L},listOf(0L),listOf(AttachmentLimits.fileBytes+1),List(6){AttachmentLimits.fileBytes}).forEach{sizes->
            try{AttachmentLimits.validate(sizes);fail("Invalid selection accepted: $sizes")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun processRestartKeepsUploadIdentityAndCaptionButChangedSelectionGetsNewIdentity()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val account=session();val target=ChatTarget("general","Общий чат")
        val store=AttachmentsStore(context,account);val id=newId();val bytes=byteArrayOf(0,1,2,3,-1)
        store.original(id).writeBytes(bytes)
        val draft=AttachmentDraft(newId(),listOf(ChatAttachment(id,"example.bin","application/octet-stream",bytes.size.toLong())),"Подпись","reply-source")
        store.saveDraft(target,draft)
        val restarted=AttachmentsStore(context,account)
        assertEquals(draft,restarted.readDraft(target))
        assertArrayEquals(bytes,restarted.original(id).readBytes())
        restarted.markAttempted(draft)
        val changed=restarted.forkDraft(draft.files,draft.text,draft.replyTo)
        assertEquals("reply-source",changed.replyTo)
        assertNotEquals(draft.messageId,changed.messageId)
        assertNotEquals(id,changed.files.single().id)
        assertArrayEquals(bytes,restarted.original(changed.files.single().id).readBytes())
        restarted.discardDraft(target);assertNull(restarted.readDraft(target))
        // Discarding an upload draft must not delete an original already saved on the phone.
        assertArrayEquals(bytes,restarted.original(id).readBytes())
    }
    @Test fun anotherAccountCannotReadPendingAttachmentDraft(){
        val context=RuntimeEnvironment.getApplication();val target=ChatTarget("general","Общий чат")
        val one=AttachmentsStore(context,session());val two=AttachmentsStore(context,session())
        one.saveDraft(target,AttachmentDraft(newId(),emptyList(),"Личный черновик"))
        assertNull(two.readDraft(target))
    }
    @Test fun defaultAnyNetworkPolicyKeepsExplicitChoiceAndRequiresConnection(){
        val store=AttachmentsStore(RuntimeEnvironment.getApplication(),session())
        assertEquals(AttachmentAutoDownload.ANY,store.autoDownload())
        assertTrue(attachmentAutoDownloadAllowed(store.autoDownload(),true,false))
        assertFalse(attachmentAutoDownloadAllowed(store.autoDownload(),false,false))
        assertTrue(attachmentAutoDownloadAllowed(AttachmentAutoDownload.WIFI,true,true))
        assertFalse(attachmentAutoDownloadAllowed(AttachmentAutoDownload.WIFI,true,false))
        assertFalse(attachmentAutoDownloadAllowed(AttachmentAutoDownload.WIFI,false,true))
        assertTrue(attachmentAutoDownloadAllowed(AttachmentAutoDownload.ANY,true,false))
        assertFalse(attachmentAutoDownloadAllowed(AttachmentAutoDownload.MANUAL,true,true))
        store.setAutoDownload(AttachmentAutoDownload.MANUAL)
        assertEquals(AttachmentAutoDownload.MANUAL,store.autoDownload())
    }
    @Test fun clearingLocalCopiesKeepsPendingUploadsAndOtherAccounts()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val one=AttachmentsStore(context,session());val two=AttachmentsStore(context,session())
        val target=ChatTarget("general","Общий чат");val keep=newId();val clear=newId();val other=newId()
        one.original(keep).writeBytes(byteArrayOf(1,2,3));one.original(clear).writeBytes(byteArrayOf(4,5));two.original(other).writeBytes(byteArrayOf(6))
        one.saveDraft(target,AttachmentDraft(newId(),listOf(ChatAttachment(keep,"pending.bin","application/octet-stream",3))))
        assertEquals(5L,one.storageUsage().appBytes)
        assertEquals(0,one.clearLocalFiles(false))
        assertTrue(one.original(keep).exists());assertFalse(one.original(clear).exists());assertTrue(two.original(other).exists())
        one.discardDraft(target,deleteFiles=true)
        assertFalse(one.original(keep).exists())
    }
    @Test fun richCaptionKeepsItsFormatAndReplyAcrossRestartAndChangedFileSelection()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val account=session();val target=ChatTarget("general","Общий чат")
        val store=AttachmentsStore(context,account);val id=newId();store.original(id).writeBytes(byteArrayOf(1,2))
        val caption="**Маршрут** и [карта](https://example.com/map)"
        val original=AttachmentDraft(newId(),listOf(ChatAttachment(id,"route.bin","application/octet-stream",2)),caption,"reply-original",MARKDOWN_TEXT_FORMAT)
        store.saveDraft(target,original)
        val restarted=AttachmentsStore(context,account);val restored=restarted.readDraft(target)!!
        assertEquals(caption,restored.text);assertEquals(MARKDOWN_TEXT_FORMAT,restored.format);assertEquals("reply-original",restored.replyTo)
        assertEquals("Маршрут и карта",richPlainText(restored.text,restored.format))
        restarted.markAttempted(restored)
        val changed=restarted.forkDraft(restored.files,restored.text,restored.replyTo,restored.format)
        assertNotEquals(original.messageId,changed.messageId)
        restarted.saveDraft(target,changed)
        val reloaded=AttachmentsStore(context,account).readDraft(target)!!
        assertEquals(changed,reloaded)
        assertEquals(MARKDOWN_TEXT_FORMAT,reloaded.format);assertEquals(caption,reloaded.text);assertEquals("reply-original",reloaded.replyTo)
        assertNotEquals(original.messageId,reloaded.messageId)
        assertNotEquals(id,reloaded.files.single().id)
    }
}
