package team.kotiki.app

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

internal data class ReadCursor(val time:Long=0,val ids:Set<String> = emptySet(),val seen:Set<String> = emptySet()) {
    fun unread(message:ChatMessage,viewer:String)=message.sender!=viewer && !message.deleted && !message.pending && message.id !in seen &&
        (message.time>time || message.time==time && message.id !in ids)
    fun advance(visible:List<ChatMessage>,viewer:String,known:List<ChatMessage> = visible):ReadCursor {
        val read=seen+visible.filter{it.sender!=viewer && !it.pending}.map{it.id}
        val incoming=known.filter{it.sender!=viewer && !it.pending && it.time>=time}.sortedBy{it.time}
        // Never jump over unseen older messages when the chat initially scrolls to the bottom.
        val firstUnread=incoming.firstOrNull{!it.deleted && it.id !in read && (it.time>time || it.id !in ids)}?.time
        val through=incoming.filter{firstUnread==null || it.time<firstUnread}
        val latest=maxOf(time,through.maxOfOrNull{it.time} ?: time)
        val boundary=(if(latest==time)ids else emptySet())+incoming.filter{it.time==latest && (it.id in read || it.deleted)}.map{it.id}
        val compact=read-incoming.filter{it.time<=latest}.map{it.id}.toSet()
        return ReadCursor(latest,boundary,compact)
    }
}

/** A local cursor for one chat; own, deleted and pending messages never count. */
internal class ChatUnreadState(context:Context,private val viewer:String,profile:String) {
    private val file=android.util.AtomicFile(localFile(context,"chat-read",profile))
    private var cursor=runCatching{val j=JSONObject(String(file.readFully()));val ids=j.getJSONArray("ids")
        ReadCursor(j.getLong("time"),(0 until ids.length()).map{ids.getString(it)}.toSet(),j.optJSONArray("seen")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()} ?: emptySet())}.getOrDefault(ReadCursor())
    var since by mutableLongStateOf(cursor.time);private set
    private var messages:List<ChatMessage> = emptyList()
    private var complete=false
    var count by mutableIntStateOf(0);private set
    fun receive(snapshot:ChatSnapshot){
        messages=snapshot.messages
        complete=!snapshot.loading && !snapshot.fromCache && snapshot.error==null
        if(complete)save(cursor.advance(emptyList(),viewer,messages))
        recount()
    }
    private fun recount(){count=messages.count{cursor.unread(it,viewer)}}
    fun readThrough(visible:List<ChatMessage>) {
        // The visible chat can load before the complete unread query (or while offline).
        // Until that query is confirmed, record IDs only: never skip unseen history.
        val next=if(complete)cursor.advance(visible,viewer,messages) else
            cursor.copy(seen=cursor.seen+visible.filter{it.sender!=viewer && !it.pending}.map{it.id})
        save(next)
    }
    private fun save(next:ReadCursor) {
        if(next==cursor)return
        var stream:java.io.FileOutputStream?=null
        try {
            stream=file.startWrite();stream.write(JSONObject().put("time",next.time).put("ids",JSONArray(next.ids.toList())).put("seen",JSONArray(next.seen.toList())).toString().toByteArray())
            file.finishWrite(stream);cursor=next;since=cursor.time;recount()
        } catch(_:Exception){file.failWrite(stream)}
    }
}
internal val LocalChatUnread=staticCompositionLocalOf<ChatUnreadState?>{null}
