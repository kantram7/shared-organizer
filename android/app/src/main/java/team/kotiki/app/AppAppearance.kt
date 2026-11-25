package team.kotiki.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal object AppAppearance {
    private fun preferences(context:Context)=context.getSharedPreferences("app_appearance",Context.MODE_PRIVATE)
    fun read(context:Context)=preferences(context).getString("theme","light").let{if(it in setOf("light","dark","system","winter"))it!! else "light"}
    fun save(context:Context,theme:String) { preferences(context).edit().putString("theme",theme).apply() }
    fun observe(context:Context,onChange:()->Unit):()->Unit {
        val prefs=preferences(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key->if(key=="theme")onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
}
@Composable internal fun rememberAppTheme():String {
    val context=LocalContext.current.applicationContext
    var theme by remember(context){mutableStateOf(AppAppearance.read(context))}
    DisposableEffect(context){
        val stop=AppAppearance.observe(context){theme=AppAppearance.read(context)}
        // A profile may migrate its theme during the same first composition.
        // Refresh after registration so that update cannot fall between initial read and observer.
        theme=AppAppearance.read(context)
        onDispose{stop()}
    }
    return theme
}
