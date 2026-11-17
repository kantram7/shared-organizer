package team.kotiki.app

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Local read state controls this device's notification dot, never cloud access rights. */
internal object NotificationReadState {
    private const val UID="kotiki_notice_uid"
    private const val ROUTE="kotiki_notice_route"
    private val visible=ConcurrentHashMap<String,Int>()
    fun entry(id:String)="entry:$id"
    fun chat(id:String)="chat:$id"
    private fun key(uid:String,route:String)="$uid/$route"
    private fun prefs(context:Context)=context.getSharedPreferences("notification_reads",Context.MODE_PRIVATE)
    fun extras(uid:String,route:String)=Bundle().apply{putString(UID,uid);putString(ROUTE,route)}
    internal fun matches(extras:Bundle,uid:String,route:String)=extras.getString(UID)==uid && extras.getString(ROUTE)==route
    internal fun alreadyRead(createdAt:String?,readAt:Long):Boolean {
        val stamp=runCatching{Instant.parse(createdAt).toEpochMilli()}.getOrNull() ?: return false
        return readAt>0 && stamp<=readAt
    }
    fun shouldPost(context:Context,uid:String,route:String,createdAt:String?):Boolean {
        if(visible.containsKey(key(uid,route))){markRead(context,uid,route);return false}
        return !alreadyRead(createdAt,prefs(context).getLong(key(uid,route),0))
    }
    fun markRead(context:Context,uid:String,route:String){
        prefs(context).edit().putLong(key(uid,route),System.currentTimeMillis()).apply()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter{matches(it.notification.extras,uid,route)}.forEach{manager.cancel(it.tag,it.id)}
    }
    fun show(context:Context,uid:String,route:String){visible.merge(key(uid,route),1,Int::plus);markRead(context,uid,route)}
    fun hide(uid:String,route:String){visible.computeIfPresent(key(uid,route)){_,count->if(count<=1)null else count-1}}
}

@Composable internal fun NotificationReadEffect(uid:String?,route:String?) {
    if(uid.isNullOrBlank() || route.isNullOrBlank())return
    val context=LocalContext.current.applicationContext
    val owner=LocalLifecycleOwner.current
    DisposableEffect(uid,route,owner){
        var shown=false
        fun update(){
            val active=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(active && !shown){NotificationReadState.show(context,uid,route);shown=true}
            if(!active && shown){NotificationReadState.hide(uid,route);shown=false}
        }
        val observer=LifecycleEventObserver{_,_->update()}
        owner.lifecycle.addObserver(observer);update()
        onDispose{owner.lifecycle.removeObserver(observer);if(shown)NotificationReadState.hide(uid,route)}
    }
}
