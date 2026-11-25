package team.kotiki.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF75508C), onPrimary = Color.White, primaryContainer = Color(0xFFEEE5F4), onPrimaryContainer = Color(0xFF543368),
    background = Color(0xFFFAF7F3), onBackground = Color(0xFF302B36), surface = Color.White, onSurface = Color(0xFF302B36),
    surfaceVariant = Color(0xFFEEE8F0), onSurfaceVariant = Color(0xFF716779), outlineVariant = Color(0xFFE8DFEA),
    secondary = Color(0xFF23756B), secondaryContainer = Color(0xFFE8F2EF), onSecondaryContainer = Color(0xFF244B44),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFDAC0EE), onPrimary = Color(0xFF36213F), primaryContainer = Color(0xFF43324F), onPrimaryContainer = Color(0xFFEFDAFF),
    background = Color(0xFF201D24), onBackground = Color(0xFFF7EFF8), surface = Color(0xFF2B2630), onSurface = Color(0xFFF7EFF8),
    surfaceVariant = Color(0xFF39313F), onSurfaceVariant = Color(0xFFBBB0C4), outlineVariant = Color(0xFF4B4053),
    secondary = Color(0xFF8BD5C9), secondaryContainer = Color(0xFF223F3C), onSecondaryContainer = Color(0xFFB4E9E0),
)
@Composable fun KotikiTheme(theme: String, content: @Composable () -> Unit) {
    val inherited=LocalWinterDesign.current
    val winter=if(BuildConfig.DEBUG && BuildConfig.WINTER_PREVIEW) theme=="winter" else inherited?.enabled==true || RemoteDesigns.winterEnabled
    val context=androidx.compose.ui.platform.LocalContext.current
    val snowPreferences=remember(context){context.getSharedPreferences("winter_appearance",0)}
    val design=remember(winter,snowPreferences){WinterDesignState(winter,snowPreferences.getBoolean("snow",true)){snowPreferences.edit().putBoolean("snow",it).apply()}}
    val dark = !winter && when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.findActivity()?.window?.let { window ->
            // Keep Android's buttons/gesture handle; only remove the system panel's scrim.
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalWinterDesign provides (inherited?.takeIf{it.enabled} ?: design)) {
        MaterialTheme(colorScheme = if(winter) WinterColors else if (dark) Dark else Light, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Subtle, consistent participant colors; task/event shape and markers remain distinct. */
@Composable fun participantTint(person: Person, compactTask: Boolean = false): Color {
    if(winterDesignEnabled())return when(person){
        Person.USER_1->Color(0xFFF1E6D3)
        Person.USER_2->Color(0xFFD9ECE2)
        Person.BOTH->Color(0xFFCDE3F5)
    }.copy(alpha=if(compactTask).94f else .90f)
    val colors=MaterialTheme.colorScheme
    val accent=when(person) {
        Person.USER_1 -> Color(0xFF899EC5)
        Person.USER_2 -> Color(0xFFC493AC)
        Person.BOTH -> Color(0xFF83B3A3)
    }
    return winterSurface(lerp(colors.surface,accent,if(compactTask) .24f else .13f))
}


@Composable fun gatheringAccent():Color = if(winterDesignEnabled())Color(0xFF2866A5) else if(MaterialTheme.colorScheme.background.luminance()<.5f)Color(0xFFF0ABC3) else Color(0xFF99536C)
@Composable fun gatheringTint():Color = if(winterDesignEnabled())Color(0xFFCDE3F5).copy(alpha=.92f) else winterSurface(lerp(MaterialTheme.colorScheme.surface,gatheringAccent(),.13f))
@Composable fun GatheringLabel() {
    androidx.compose.foundation.layout.Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        KotikiIcon(Glyph.MEETING,androidx.compose.ui.Modifier.size(18.dp),gatheringAccent())
        androidx.compose.material3.Text("Общий сбор",style=MaterialTheme.typography.labelSmall,color=gatheringAccent())
    }
}
