package app.nya.smsforward.node.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same tokens as the web console and docs/prototype.html.
private val Light = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF6FF),
    onPrimaryContainer = Color(0xFF1D4ED8),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF181C23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181C23),
    surfaceVariant = Color(0xFFF2F5F9),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFFDDE3EB),
    error = Color(0xFFB42318),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4F8BFF),
    onPrimary = Color(0xFF0F1319),
    primaryContainer = Color(0xFF1A2740),
    onPrimaryContainer = Color(0xFF6B9DFF),
    background = Color(0xFF0F1319),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF171C24),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF212936),
    onSurfaceVariant = Color(0xFF93A0B4),
    outline = Color(0xFF2B3441),
    error = Color(0xFFFF8A80),
)

@Composable
fun NyaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
