package team.kotiki.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
