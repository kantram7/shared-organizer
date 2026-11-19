package team.kotiki.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.security.MessageDigest

internal fun invitationReadKey(entry:Entry):String {
    val values=listOf(entry.id,entry.owner.name,entry.invite.name,entry.proposal?.id.orEmpty(),entry.title,
        entry.date.toString(),entry.lastDate.toString(),entry.start,entry.end,entry.note)
    val body=values.joinToString(""){"${it.length}:$it"}
    return MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString(""){"%02x".format(it)}
}

/** Reading is independent of accepting: an unanswered invitation can already be read. */
internal class InvitationReads(context:Context,profile:String,private val viewer:Person) {
    private val file=android.util.AtomicFile(localFile(context,"invitation-read",profile))
    private val seen=mutableStateMapOf<String,String>()
    init {runCatching {val j=JSONObject(String(file.readFully()));j.keys().forEach{seen[it]=j.getString(it)}}}
    fun unread(entry:Entry)=entry.canAccept(viewer) && seen[entry.id]!=invitationReadKey(entry)
    fun count(entries:List<Entry>)=entries.count(::unread)
    fun read(entry:Entry) {
        if(!unread(entry))return
        val key=invitationReadKey(entry)
        val value=JSONObject();seen.forEach{(id,stamp)->value.put(id,stamp)};value.put(entry.id,key)
        var stream:java.io.FileOutputStream?=null
        try{stream=file.startWrite();stream.write(value.toString().toByteArray());file.finishWrite(stream);seen[entry.id]=key}
        catch(_:Exception){file.failWrite(stream)}
    }
}
internal val LocalInvitationReads=staticCompositionLocalOf<InvitationReads?>{null}

@Composable internal fun InvitationReadEffect(entry:Entry,visible:Boolean=true,state:InvitationReads?=LocalInvitationReads.current) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(entry,visible,state,lifecycle) {
        fun update(){if(visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))state?.read(entry)}
        val observer=LifecycleEventObserver{_,_->update()}
        lifecycle.addObserver(observer);update()
        onDispose{lifecycle.removeObserver(observer)}
    }
}
