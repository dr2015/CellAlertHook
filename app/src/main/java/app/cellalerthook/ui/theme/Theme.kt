package app.cellalerthook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class StatusPalette(
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val neutral: Color,
)

private val LightStatus = StatusPalette(
    ok = Color(0xFF17693F),
    warn = Color(0xFF8F5600),
    bad = Color(0xFFB3261E),
    neutral = Color(0xFF55636A),
)

private val DarkStatus = StatusPalette(
    ok = Color(0xFF6FD49B),
    warn = Color(0xFFF2B85B),
    bad = Color(0xFFFFA49C),
    neutral = Color(0xFF9FB0B8),
)

val LocalStatusPalette = staticCompositionLocalOf { LightStatus }

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F3D4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6EC),
    onPrimaryContainer = Color(0xFF0B2C37),
    secondary = Color(0xFF3F5A65),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE7EA),
    onSecondaryContainer = Color(0xFF233840),
    background = Color(0xFFEFF2F3),
    onBackground = Color(0xFF151C1F),
    surface = Color(0xFFFAFBFB),
    onSurface = Color(0xFF151C1F),
    surfaceVariant = Color(0xFFE1E7E9),
    onSurfaceVariant = Color(0xFF43515A),
    surfaceContainer = Color(0xFFFAFBFB),
    outline = Color(0xFF71808A),
    outlineVariant = Color(0xFFC6D0D4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD0E3),
    onPrimary = Color(0xFF06303C),
    primaryContainer = Color(0xFF1C4A59),
    onPrimaryContainer = Color(0xFFCBE8F1),
    secondary = Color(0xFFADC2CB),
    onSecondary = Color(0xFF17292F),
    secondaryContainer = Color(0xFF2B3E46),
    onSecondaryContainer = Color(0xFFD5E2E7),
    background = Color(0xFF0D1214),
    onBackground = Color(0xFFE0E6E8),
    surface = Color(0xFF151C1F),
    onSurface = Color(0xFFE0E6E8),
    surfaceVariant = Color(0xFF243035),
    onSurfaceVariant = Color(0xFFB5C3CA),
    surfaceContainer = Color(0xFF151C1F),
    outline = Color(0xFF7F9099),
    outlineVariant = Color(0xFF34424A),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
)

/** Package names, IPs and event ids. */
val TechnicalTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

@Composable
fun CellAlertHookTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusPalette provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
