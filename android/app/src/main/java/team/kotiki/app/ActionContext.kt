package team.kotiki.app

import androidx.compose.runtime.staticCompositionLocalOf

val LocalCreateTask = staticCompositionLocalOf<((String)->Unit)?> { null }
val LocalCreateNote = staticCompositionLocalOf<((String)->Unit)?> { null }
val LocalOpenInternalLink = staticCompositionLocalOf<((String)->Unit)?> { null }
