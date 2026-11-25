package team.kotiki.app

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/** App-created native pickers follow our palette; external/system activities keep their own theme. */
@Composable internal fun rememberPickerContext():Context {
    val context=LocalContext.current
    val winter=winterDesignEnabled()
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val style=if(winter)R.style.KotikiPickerWinter else if(dark)R.style.KotikiPickerDark else R.style.KotikiPickerLight
    return remember(context,style){ContextThemeWrapper(context,style)}
}
