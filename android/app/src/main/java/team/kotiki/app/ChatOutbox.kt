package team.kotiki.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PendingChatMessage(val id:String,val target:ChatTarget,val text:String,val time:Long,
    val replyTo:String="",val format:String="markdown-v1",val attachmentIds:List<String> = emptyList(),val error:String="",val delivered:Boolean=false)

/** Stored before the composer is cleared; stable IDs make retries safe after process death. */
class ChatOutbox(context:Context,uid:String,space:String) {
    private val file=localFile(context,"chat-outbox","$space/$uid")
    private val values=linkedMapOf<String,PendingChatMessage>()
    init { runCatching {
        val a=JSONArray(file.inputStream().bufferedReader().use{it.readText()})
        repeat(a.length()){i->val j=a.getJSONObject(i);val ids=j.optJSONArray("attachments") ?: JSONArray()
            val m=PendingChatMessage(j.getString("id"),ChatTarget(j.getString("chat"),j.optString("title"),j.optString("collection"),j.optString("item")),
                j.getString("text"),j.getLong("time"),j.optString("reply"),j.optString("format","markdown-v1"),
                (0 until ids.length()).map{ids.getString(it)},j.optString("error"),j.optBoolean("delivered",false));values[m.id]=m
        }
    } }
    @Synchronized fun all():List<PendingChatMessage> = values.values.toList()
    @Synchronized fun put(message:PendingChatMessage){val old=values.put(message.id,message);try{save()}catch(e:Exception){if(old==null)values.remove(message.id)else values[message.id]=old;throw e}}
    // Acknowledgement may precede the Firestore listener. Keep the visible row
    // until that listener has a replacement, and never resend an acknowledged item.
    @Synchronized fun acknowledge(id:String){values[id]?.let{put(it.copy(delivered=true,error=""))}}
    @Synchronized fun remove(id:String){val old=values.remove(id) ?: return;try{save()}catch(e:Exception){values[id]=old;throw e}}
    private fun save(){
        val a=JSONArray(values.values.map{m->JSONObject().put("id",m.id).put("chat",m.target.id).put("title",m.target.title)
            .put("collection",m.target.collectionId).put("item",m.target.itemId).put("text",m.text).put("time",m.time)
            .put("reply",m.replyTo).put("format",m.format).put("attachments",JSONArray(m.attachmentIds)).put("error",m.error).put("delivered",m.delivered)})
        file.parentFile?.mkdirs()
        val next=File(file.path+".new")
        java.io.FileOutputStream(next,false).use{it.write(a.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
        java.nio.file.Files.move(next.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
