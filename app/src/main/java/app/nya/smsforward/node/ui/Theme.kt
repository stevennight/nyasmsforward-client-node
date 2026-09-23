package app.nya.smsforward.node.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Same tokens as the web console and docs/prototype.html.
private val Light = lightColorScheme(
    primary = Color(0xFF4964D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF1FF),
    onPrimaryContainer = Color(0xFF354FC0),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF181C23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181C23),
    surfaceVariant = Color(0xFFEEF2F8),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFFE1E6F0),
    error = Color(0xFFB42318),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF91A7FF),
    onPrimary = Color(0xFF0F1319),
    primaryContainer = Color(0xFF202A52),
    onPrimaryContainer = Color(0xFFB0BDFF),
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
    val shapes = Shapes(
        small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    )
    val typography = Typography().run {
        copy(
            headlineSmall = headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            titleLarge = titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            bodyLarge = bodyLarge.copy(lineHeight = 22.sp),
            bodyMedium = bodyMedium.copy(lineHeight = 20.sp),
        )
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = shapes, typography = typography, content = content)
}
