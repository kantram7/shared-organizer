package team.kotiki.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

/** Appearance only: no identity, access rules, or private data in Remote Config. */
object RemoteDesigns {
    const val WINTER_KEY = "winter_together_enabled"
    var winterEnabled by mutableStateOf(false)
        private set

    fun attach(activity: ComponentActivity) {
        if (BuildConfig.WINTER_PREVIEW || FirebaseApp.getApps(activity).isEmpty()) return
        val config = FirebaseRemoteConfig.getInstance()
        config.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(60).setFetchTimeoutInSeconds(8).build())
        config.setDefaultsAsync(mapOf(WINTER_KEY to false))
        fun refresh() {
            // Cached activated values work offline. First install stays ordinary until a successful fetch.
            config.ensureInitialized().addOnSuccessListener {
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    winterEnabled = config.getBoolean(WINTER_KEY)
                }
                config.fetchAndActivate().addOnSuccessListener {
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        winterEnabled = config.getBoolean(WINTER_KEY)
                        android.util.Log.i("KotikiDesign", "Remote appearance fetched: winter=$winterEnabled, source=${config.getValue(WINTER_KEY).source}")
                    }
                }.addOnFailureListener { android.util.Log.w("KotikiDesign", "Appearance fetch unavailable: ${it.javaClass.simpleName}") }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) refresh()
        }
        activity.lifecycle.addObserver(observer)
        // Deliberately no live listener: don't change the theme while somebody is editing.
    }
}
