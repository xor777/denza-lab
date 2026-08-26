package dev.denza.apps.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The app's theme, made to carry the design instead of decorating it.
 *
 * The theme this replaces declared six colour roles and was then never read: across two thousand
 * lines there was not one `MaterialTheme.colorScheme.*`, `.typography.*` or `.shapes.*`, and every
 * coloured component was repainted by hand instead - twenty-nine explicit `*Defaults.colors(...)`
 * calls, including every `Button` and every `Card` in the file. A theme nothing reads is not a
 * theme, it is a comment.
 *
 * So all three slots are filled, out of [DenzaColors] and [DenzaMetrics] and out of nothing else.
 * Filling `typography` and `shapes` is the half that does the work: with them set, a plain `Text`
 * or `Card` lands on the ladder without being told, and a size off the ladder has to be written out
 * by hand - which makes it visible in review instead of ordinary.
 */
@Composable
fun DenzaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DenzaColorScheme,
        typography = DenzaTypography,
        shapes = DenzaShapes,
        content = content,
    )
}

/**
 * Every role Material draws with, not the six that happened to be overridden.
 *
 * The vehicle separates its own roles and this keeps that separation: champagne is interface - the
 * live tile, the chosen button - and never instrument data, because warm yellow already means
 * caution in a car and an accent competing with [DenzaColors.Warning] costs a driver the glance.
 */
private val DenzaColorScheme = darkColorScheme(
    primary = DenzaColors.Accent,
    onPrimary = DenzaColors.OnAccent,
    primaryContainer = DenzaColors.SurfaceRaised,
    onPrimaryContainer = DenzaColors.Ink,
    secondary = DenzaColors.Return,
    onSecondary = DenzaColors.Ink,
    secondaryContainer = DenzaColors.SurfaceRaised,
    onSecondaryContainer = DenzaColors.Ink,
    tertiary = DenzaColors.DataPeak,
    onTertiary = DenzaColors.OnAccent,
    background = DenzaColors.Background,
    onBackground = DenzaColors.Ink,
    surface = DenzaColors.Surface,
    onSurface = DenzaColors.Ink,
    surfaceVariant = DenzaColors.SurfaceQuiet,
    onSurfaceVariant = DenzaColors.Muted,
    surfaceContainer = DenzaColors.Surface,
    surfaceContainerHigh = DenzaColors.SurfaceRaised,
    surfaceContainerHighest = DenzaColors.SurfaceHigh,
    surfaceContainerLow = DenzaColors.SurfaceQuiet,
    surfaceContainerLowest = DenzaColors.Background,
    inverseSurface = DenzaColors.Ink,
    inverseOnSurface = DenzaColors.Background,
    error = DenzaColors.Danger,
    onError = DenzaColors.OnAccent,
    errorContainer = DenzaColors.SurfaceQuiet,
    onErrorContainer = DenzaColors.Danger,
    outline = DenzaColors.SurfaceHigh,
    outlineVariant = DenzaColors.ink(0.10f),
    scrim = DenzaColors.Background.copy(alpha = 0.72f),
)

/**
 * Fifteen Material roles onto six rungs of [DenzaMetrics.Type].
 *
 * Every role is filled, including the ones this app has no use for today, because an unfilled role
 * falls back to Material's own scale - which is where 11, 12, 13 and 16 sp came from in the first
 * place. Weight carries what size no longer does: medium for anything a finger points at, normal
 * for everything it only reads.
 */
private val DenzaTypography = Typography().let { base ->
    base.copy(
        displayLarge = name(DenzaMetrics.Type.DISPLAY, FontWeight.Light),
        displayMedium = name(DenzaMetrics.Type.DISPLAY, FontWeight.Light),
        displaySmall = name(DenzaMetrics.Type.HEADLINE, FontWeight.Light),
        headlineLarge = name(DenzaMetrics.Type.HEADLINE, FontWeight.Normal),
        headlineMedium = name(DenzaMetrics.Type.TITLE, FontWeight.Normal),
        headlineSmall = name(DenzaMetrics.Type.SECTION, FontWeight.Medium),
        titleLarge = name(DenzaMetrics.Type.SECTION, FontWeight.Medium),
        titleMedium = name(DenzaMetrics.Type.LABEL, FontWeight.Medium),
        titleSmall = name(DenzaMetrics.Type.BODY, FontWeight.Medium),
        bodyLarge = prose(DenzaMetrics.Type.LABEL, FontWeight.Normal),
        bodyMedium = prose(DenzaMetrics.Type.BODY, FontWeight.Normal),
        bodySmall = prose(DenzaMetrics.Type.BODY, FontWeight.Normal),
        labelLarge = name(DenzaMetrics.Type.LABEL, FontWeight.Medium),
        labelMedium = name(DenzaMetrics.Type.BODY, FontWeight.Medium),
        labelSmall = name(DenzaMetrics.Type.BODY, FontWeight.Medium, tracking = 0.6f),
    )
}

/** A name, a figure or a label: two lines of it are still one thing, so they sit close. */
private fun name(size: TextUnit, weight: FontWeight, tracking: Float = 0f) =
    style(size, weight, DenzaMetrics.Type.LEADING_TIGHT, tracking)

/** Something read as a sentence. */
private fun prose(size: TextUnit, weight: FontWeight, tracking: Float = 0f) =
    style(size, weight, DenzaMetrics.Type.LEADING_BODY, tracking)

private fun style(size: TextUnit, weight: FontWeight, leading: Float, tracking: Float) = TextStyle(
    fontSize = size,
    fontWeight = weight,
    lineHeight = (size.value * leading).sp,
    letterSpacing = tracking.sp,
)

/** The four corner rungs; Material's fifth is the fourth again rather than a rung invented for it. */
private val DenzaShapes = Shapes(
    extraSmall = RoundedCornerShape(DenzaMetrics.Radius.XS),
    small = RoundedCornerShape(DenzaMetrics.Radius.S),
    medium = RoundedCornerShape(DenzaMetrics.Radius.M),
    large = RoundedCornerShape(DenzaMetrics.Radius.L),
    extraLarge = RoundedCornerShape(DenzaMetrics.Radius.L),
)
