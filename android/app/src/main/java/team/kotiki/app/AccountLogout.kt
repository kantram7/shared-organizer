package team.kotiki.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth

/** One exit path for ready, loading and access-denied screens. Never await an offline write forever. */
internal object AccountLogout {
    fun signOut(context:Context,auth:FirebaseAuth,onComplete:()->Unit) {
        val uid=auth.currentUser?.uid ?: run{onComplete();return}
        val app=context.applicationContext
        val profile=AndroidPush.binding(app)?.takeIf{it.authUid==uid}
        finishAfterUnregister(
            unregister={done->AndroidPush.unregister(app,uid,profile){done()}},
            clearLocal={AndroidPush.clearLocal(app,uid)},
            signOut={if(auth.currentUser?.uid==uid)auth.signOut()},
            onComplete=onComplete
        )
    }
    internal fun finishAfterUnregister(unregister:((()->Unit)->Unit),clearLocal:()->Unit,signOut:()->Unit,onComplete:()->Unit) {
        val main=Handler(Looper.getMainLooper())
        var finished=false
        val finish=Runnable {
            if(!finished){
                finished=true
                // Clear again in case an in-flight snapshot completed during remote unregister.
                clearLocal();signOut();onComplete()
            }
        }
        clearLocal()
        main.postDelayed(finish,5_000)
        try { unregister {main.post{main.removeCallbacks(finish);finish.run()}} }
        catch(_:Exception){main.removeCallbacks(finish);finish.run()}
    }
}
