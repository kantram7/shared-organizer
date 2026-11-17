package team.kotiki.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    private var notificationTarget by mutableStateOf<GatheringNotificationTarget?>(null)
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationTarget=GatheringNotifications.target(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null)notificationTarget=GatheringNotifications.target(intent)
        enableEdgeToEdge()
        if(Build.VERSION.SDK_INT>=31)splashScreen.setOnExitAnimationListener{it.remove()}
        setContent {
            var welcome by rememberSaveable {mutableStateOf(true)}
            DisposableEffect(Unit) {
                val observer=LifecycleEventObserver { _,event ->
                    if(event==Lifecycle.Event.ON_STOP)welcome=false
                }
                lifecycle.addObserver(observer)
                onDispose{lifecycle.removeObserver(observer)}
            }

            var store by remember { mutableStateOf(DemoStore(applicationContext)) }
            CompositionLocalProvider(LocalDemoIdentity provides store.identity) {
                key(store.viewer) {
                    KotikiTheme(store.theme) {
                        BrandWelcome(welcome,{welcome=false}) {
                            KotikiApp(store,notificationTarget,{notificationTarget=null}) { person ->
                                getSharedPreferences("local_test_profiles", MODE_PRIVATE).edit().putString("selected",person.name).apply()
                                store = DemoStore(applicationContext,person)
                            }
                        }
                    }
                }
            }
        }
    }
}
