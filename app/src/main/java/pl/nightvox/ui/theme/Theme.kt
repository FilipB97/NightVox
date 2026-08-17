package pl.nightvox.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Paleta jest ciemna także w trybie jasnym: to apka, którą włącza się przy zgaszonym
 * świetle tuż przed snem. Biały ekran o 23:30 jest wrogiem użytkownika.
 */
private val NightColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0A1B33),
    primaryContainer = Color(0xFF1E3159),
    onPrimaryContainer = Color(0xFFD5E2FF),
    secondary = Color(0xFF9BC6A8),
    onSecondary = Color(0xFF0B2314),
    secondaryContainer = Color(0xFF1D3A28),
    onSecondaryContainer = Color(0xFFC7EBD3),
    tertiary = Color(0xFFE0BE7C),
    error = Color(0xFFFF9A8A),
    onError = Color(0xFF3B0A05),
    errorContainer = Color(0xFF4E1710),
    onErrorContainer = Color(0xFFFFD9D2),
    background = Color(0xFF0B0B12),
    onBackground = Color(0xFFE3E2E8),
    surface = Color(0xFF0B0B12),
    onSurface = Color(0xFFE3E2E8),
    surfaceVariant = Color(0xFF1A1A24),
    onSurfaceVariant = Color(0xFFB8B7C2),
    surfaceContainer = Color(0xFF15151F),
    surfaceContainerHigh = Color(0xFF1D1D28),
    outline = Color(0xFF4A4A57),
    outlineVariant = Color(0xFF2C2C38),
)

/** Używana tylko wtedy, gdy ktoś świadomie wymusi jasny motyw w Ustawieniach systemu. */
private val DayColors = lightColorScheme(
    primary = Color(0xFF2F5AA8),
    secondary = Color(0xFF3D6B4C),
    error = Color(0xFFB3261E),
)

private val NightTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 52.sp,
        letterSpacing = (-1).sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun NightVoxTheme(
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || isSystemInDarkTheme()
    val colors = if (dark) NightColors else DayColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = NightTypography,
        content = content,
    )
}
