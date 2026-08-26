package pl.nightvox.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Jedna paleta, zawsze ciemna — także wtedy, gdy telefon jest w trybie jasnym.
 *
 * To apka, którą włącza się przy zgaszonym świetle tuż przed snem; biały ekran o 23:30 jest
 * wrogiem użytkownika. Wcześniej obok tej palety stał szkic jasnej z trzema kolorami, z
 * którego i tak nigdy nie korzystaliśmy — resztę dopowiadał Material i wychodziło z tego
 * coś, czego nikt nie zaprojektował. Lepszy jeden motyw, który jest decyzją.
 *
 * Tło jest prawie czarne z niebieskim podkładem, powierzchnie różnią się od niego o kilka
 * procent jasności, a akcent jest tylko jeden. Wszystko, co ma wybijać się z tła — poziom,
 * próg, ostrzeżenie — dostaje kolor; reszta zostaje szara.
 */
private val NightColors = darkColorScheme(
    primary = Color(0xFFA6C0FF),
    onPrimary = Color(0xFF0A1730),
    primaryContainer = Color(0xFF1B2B4E),
    onPrimaryContainer = Color(0xFFD9E4FF),
    inversePrimary = Color(0xFF2F4B84),

    secondary = Color(0xFF9CC8AC),
    onSecondary = Color(0xFF0A2115),
    secondaryContainer = Color(0xFF1B3628),
    onSecondaryContainer = Color(0xFFC9ECD8),

    tertiary = Color(0xFFE6C48D),
    onTertiary = Color(0xFF2E2109),
    tertiaryContainer = Color(0xFF3D2F14),
    onTertiaryContainer = Color(0xFFF7E3C4),

    error = Color(0xFFFF9086),
    onError = Color(0xFF390A05),
    errorContainer = Color(0xFF4A1611),
    onErrorContainer = Color(0xFFFFDAD4),

    background = Color(0xFF0A0A0F),
    onBackground = Color(0xFFE6E5EC),
    surface = Color(0xFF0A0A0F),
    onSurface = Color(0xFFE6E5EC),

    surfaceVariant = Color(0xFF16161E),
    onSurfaceVariant = Color(0xFFA9A8B5),
    surfaceContainerLowest = Color(0xFF07070B),
    surfaceContainerLow = Color(0xFF101016),
    surfaceContainer = Color(0xFF13131A),
    surfaceContainerHigh = Color(0xFF1A1A23),
    surfaceContainerHighest = Color(0xFF22222C),

    outline = Color(0xFF4C4C59),
    outlineVariant = Color(0xFF24242E),
    scrim = Color(0xFF000000),
)

/**
 * Cyfry w tabelarycznej szerokości.
 *
 * Poziom, próg i licznik czasu odświeżają się kilka razy na sekundę. Bez `tnum` każda zmiana
 * cyfry przesuwa cały napis w bok i wykres wygląda, jakby drgał.
 */
private const val TABULAR = "tnum"

private val NightTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Light,
            fontSize = 54.sp,
            lineHeight = 60.sp,
            letterSpacing = (-1.5).sp,
            fontFeatureSettings = TABULAR,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Light,
            fontSize = 28.sp,
            letterSpacing = (-0.5).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 23.sp,
            letterSpacing = (-0.3).sp,
            fontFeatureSettings = TABULAR,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(lineHeight = 18.sp, color = Color.Unspecified),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelMedium = base.labelMedium.copy(fontFeatureSettings = TABULAR),
        // Podpisy sekcji i kafelków — wersaliki z rozstrzeleniem czytają się jak etykieta,
        // a nie jak urwane zdanie.
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            letterSpacing = 0.8.sp,
        ),
    )
}

/** Miękkie, ale nie owalne: 20 dp na kartach, mniej na drobnicy. */
private val NightShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NightVoxTheme(content: @Composable () -> Unit) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = NightColors.background.toArgb()
            window.navigationBarColor = NightColors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = NightColors,
        typography = NightTypography,
        shapes = NightShapes,
        content = content,
    )
}
