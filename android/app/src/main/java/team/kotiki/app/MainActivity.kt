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
        if (BuildConfig.AGENT_TEST && com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
            val config=assets.open("agent-firebase.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
            val options=com.google.firebase.FirebaseOptions.Builder()
                .setProjectId(config.getString("projectId")).setApplicationId(config.getString("applicationId"))
                .setApiKey(config.getString("apiKey")).setGcmSenderId(config.optString("messagingSenderId").ifBlank{config.getString("applicationId").split(":").getOrNull(1).orEmpty()}).build()
            com.google.firebase.FirebaseApp.initializeApp(this,options)
        }
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

            KotikiTheme("system") {
                BrandWelcome(welcome,{welcome=false}) {
                    CloudGate(notificationTarget){notificationTarget=null}
                }
            }
        }
    }
}
