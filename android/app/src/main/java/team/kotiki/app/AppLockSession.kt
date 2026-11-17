package team.kotiki.app

import android.content.Context

/** Memory only. A new process, an explicit close or an account switch starts locked. */
internal class ProcessSession<T>(private val dispose:(T)->Unit) {
    private var identity:String?=null
    private var value:T?=null
    fun get(uid:String,create:()->T):T {
        require(uid.isNotBlank())
        retainFor(uid)
        return value ?: create().also{identity=uid;value=it}
    }
    fun retainFor(uid:String?){if(identity!=uid)clear()}
    fun clear(){value?.let(dispose);value=null;identity=null}
}

internal object AppLockSessions {
    private val session=ProcessSession<AppLockController>{it.close()}
    fun get(context:Context,uid:String)=session.get(uid){AppLockController(context.applicationContext,uid)}
    fun retainFor(uid:String?)=session.retainFor(uid)
    fun clear()=session.clear()
}
