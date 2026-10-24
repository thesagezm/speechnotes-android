package com.speechnotes.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Material 3 Expressive design system for Speechnotes.
 *
 * Expressive differs from classic M3 in four deliberate ways, all used here:
 *  - SHAPES: larger corner radii (28dp "large" on cards, 36dp "extra large"
 *    on sheets) — rounded, friendly, tactile.
 *  - MOTION: the springy motion tokens M3 expressive specifies; the
 *    animation call sites use MaterialTheme's expressive springs directly.
 *  - COLOR: vibrant tonal palettes — the light scheme below is the M3
 *    Expressive baseline with the app's teal accent swapped in.
 *  - COMPONENTS: the expressive component set (button groups, split
 *    buttons, loading indicators, floating toolbars, wavy progress).
 *
 * Dynamic color is honored on Android 12+ when the user picks the "System"
 * accent; otherwise the accent's palette is generated below.
 */

// MARK: - The 12 accents, mirroring the iOS app's choices.

enum class AccentChoice(val label: String) {
    SYSTEM("System"),
    BLUE("Blue"),
    INDIGO("Indigo"),
    PURPLE("Purple"),
    PINK("Pink"),
    RED("Red"),
    ORANGE("Orange"),
    YELLOW("Yellow"),
    GREEN("Green"),
    TEAL("Teal"),
    MINT("Mint"),
    CYAN("Cyan");

    companion object {
        fun fromKey(key: String): AccentChoice =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: SYSTEM
    }
}

/** Seed colors — one per non-system accent, matching the iOS AccentColorChoice list. */
private val SeedColors: Map<AccentChoice, Color> = mapOf(
    AccentChoice.BLUE to Color(0xFF0B57D0),
    AccentChoice.INDIGO to Color(0xFF4F46E5),
    AccentChoice.PURPLE to Color(0xFF7C3AED),
    AccentChoice.PINK to Color(0xFFDB2777),
    AccentChoice.RED to Color(0xFFDC2626),
    AccentChoice.ORANGE to Color(0xFFEA580C),
    AccentChoice.YELLOW to Color(0xFFCA8A04),
    AccentChoice.GREEN to Color(0xFF16A34A),
    AccentChoice.TEAL to Color(0xFF0D9488),
    AccentChoice.MINT to Color(0xFF10B981),
    AccentChoice.CYAN to Color(0xFF0891B2),
)

/** Swatch colors for the picker; SYSTEM is absent (the caller draws a rainbow). */
val AccentSwatch: Map<AccentChoice, Color> = SeedColors

// MARK: - Light + dark schemes (M3 Expressive baseline, teal-tinted accents)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF006C4E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF89F8C0),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4B635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9DA),
    onSecondaryContainer = Color(0xFF072018),
    tertiary = Color(0xFF3F6375),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC2E8FD),
    onTertiaryContainer = Color(0xFF001E2C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C3),
    surfaceTint = Color(0xFF006C4E),
    inverseSurface = Color(0xFF2E312F),
    inverseOnSurface = Color(0xFFEFF1EE),
    inversePrimary = Color(0xFF6CDBAA),
    surfaceBright = Color(0xFFFBFDF8),
    surfaceDim = Color(0xFFD9DBD7),
    surfaceContainer = Color(0xFFEFEFEB),
    surfaceContainerHigh = Color(0xFFE9EAE6),
    surfaceContainerHighest = Color(0xFFE3E4E0),
    surfaceContainerLow = Color(0xFFF5F5F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF6CDBAA),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFF89F8C0),
    secondary = Color(0xFFB1CCBE),
    onSecondary = Color(0xFF1D352C),
    secondaryContainer = Color(0xFF344C42),
    onSecondaryContainer = Color(0xFFCDE9DA),
    tertiary = Color(0xFFA7CCE0),
    onTertiary = Color(0xFF0B3446),
    tertiaryContainer = Color(0xFF274B5D),
    onTertiaryContainer = Color(0xFFC2E8FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1A),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
    surfaceTint = Color(0xFF6CDBAA),
    inverseSurface = Color(0xFFE1E3DF),
    inverseOnSurface = Color(0xFF2E312F),
    inversePrimary = Color(0xFF006C4E),
    surfaceBright = Color(0xFF353937),
    surfaceDim = Color(0xFF101412),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF272B28),
    surfaceContainerHighest = Color(0xFF323633),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainerLowest = Color(0xFF0B0F0D),
)

// MARK: - Theme state (accent + appearance + text scale)

enum class AppearanceMode { SYSTEM, LIGHT, DARK }

@Immutable
data class ThemeState(
    val accent: AccentChoice = AccentChoice.SYSTEM,
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
    val previewTextScale: Float = 1.0f,
    /** Reader theme for books: white / sepia / dark. */
    val readerTheme: ReaderTheme = ReaderTheme.WHITE,
    /** Reader rhythm multipliers (iOS Appearance → Spacing parity). */
    val readerLineSpacing: Double = 1.0,
    val readerBlockSpacing: Double = 1.0,
    val readerTableSpacing: Double = 1.0,
)

enum class ReaderTheme(val label: String, val jsTheme: String) {
    WHITE("White", "light"), SEPIA("Sepia", "sepia"), DARK("Dark", "dark"), TRUE_BLACK("True Black", "trueBlack")
}

val LocalThemeState: ProvidableCompositionLocal<ThemeState> = compositionLocalOf { ThemeState() }

/**
 * M3 Expressive shapes: larger radii than classic M3, up to the 36dp
 * extra-large quadrant used on sheets and the FAB menu.
 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Typography stays on the platform font (Roboto/Google Sans) — no custom
 * fonts, matching the iOS app's system-font choice.
 */
private val AppTypography = Typography()

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpeechnotesTheme(
    state: ThemeState = LocalThemeState.current,
    content: @Composable () -> Unit,
) {
    val dark = when (state.appearance) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }

    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val base = if (dark) DarkScheme else LightScheme
    val scheme = when {
        dynamicAvailable && state.accent == AccentChoice.SYSTEM ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        state.accent in SeedColors ->
            // Re-tint the baseline scheme around the accent by overriding the
            // primary roles with the accent hue's own tones (a compact port
            // of the iOS app's "everything accent-tinted derives from the
            // chosen color" rule).
            base.withAccent(SeedColors.getValue(state.accent), dark)
        else -> base
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }

    CompositionLocalProvider(LocalThemeState provides state) {
        // Press feedback follows the accent: the platform default ripples in
        // the button's CONTENT color (a grey wash over the green), which read
        // as a two-step grey→green flash on every tap. An accent-tinted
        // ripple with a slightly stronger pressed layer responds in the hue
        // the button is about to act in — no color jump, immediate feedback.
        val rippleConfiguration = androidx.compose.material3.RippleConfiguration(
            color = scheme.primary,
            rippleAlpha = androidx.compose.material.ripple.RippleAlpha(
                pressedAlpha = 0.18f,
                focusedAlpha = 0.10f,
                hoveredAlpha = 0.08f,
                draggedAlpha = 0.16f,
            ),
        )
        CompositionLocalProvider(
            androidx.compose.material3.LocalRippleConfiguration provides rippleConfiguration,
        ) {
            // NOTE: the M3 Expressive motionScheme parameter is internal in
            // material3 1.4.0 (kotlin.Metadata marks it internal even though the
            // java flags say public), so the public MaterialTheme overload is
            // used. The expressive look still lands: shapes, color and the
            // expressive component set are all public API.
            MaterialTheme(
                colorScheme = scheme,
                shapes = ExpressiveShapes,
                typography = AppTypography,
                content = content,
            )
        }
    }
}

/**
 * Accent re-tint: swaps the primary/secondary/tertiary families for the
 * accent hue while keeping surfaces/on-colors from the baseline scheme.
 * Light/dark aware — a hue's tones are picked per mode.
 */
private fun ColorScheme.withAccent(seed: Color, dark: Boolean): ColorScheme {
    val primary = if (dark) seed.lighten(0.35f) else seed
    val onPrimary = if (dark) Color(0xFF00110C) else Color(0xFFFFFFFF)
    val container = if (dark) seed.darken(0.45f) else seed.lighten(0.62f)
    val onContainer = if (dark) seed.lighten(0.75f) else seed.darken(0.55f)
    return copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        surfaceTint = primary,
        inversePrimary = if (dark) primary else onPrimary,
    )
}

/** Mixes [this] toward white by [amount] (0…1). */
private fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

/** Mixes [this] toward black by [amount] (0…1). */
private fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
