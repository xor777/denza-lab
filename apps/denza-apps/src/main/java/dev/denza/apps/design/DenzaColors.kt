package dev.denza.apps.design

import androidx.compose.ui.graphics.Color

/**
 * [DenzaPalette] as Compose sees it.
 *
 * The palette is `Int` ARGB because the instruments draw onto a [android.graphics.Canvas], and
 * Compose cannot consume an `Int` as a colour. That gap is the whole reason this app carried five
 * palettes at once: the canvas renderers took the vehicle's colours, and every Compose screen
 * invented its own because the vehicle's were out of reach. This is the adapter, and it is
 * deliberately nothing else - every value below is [DenzaPalette] read through [Color], never a
 * colour of its own. Put a literal here and the two halves of the app start drifting apart again.
 */
object DenzaColors {

    val Background: Color = Color(DenzaPalette.BACKGROUND)
    val SurfaceQuiet: Color = Color(DenzaPalette.SURFACE_QUIET)
    val Surface: Color = Color(DenzaPalette.SURFACE)
    val SurfaceRaised: Color = Color(DenzaPalette.SURFACE_RAISED)
    val SurfaceHigh: Color = Color(DenzaPalette.SURFACE_HIGH)

    val Ink: Color = Color(DenzaPalette.INK)
    val InkSecondary: Color = Color(DenzaPalette.INK_SECONDARY)
    val Muted: Color = Color(DenzaPalette.MUTED)
    val MutedDeep: Color = Color(DenzaPalette.MUTED_DEEP)

    val Accent: Color = Color(DenzaPalette.ACCENT)
    val OnAccent: Color = Color(DenzaPalette.ON_ACCENT)
    val DataPeak: Color = Color(DenzaPalette.DATA_PEAK)

    val Return: Color = Color(DenzaPalette.RETURN)
    val ReturnInk: Color = Color(DenzaPalette.RETURN_INK)

    val Warning: Color = Color(DenzaPalette.WARNING)
    val Danger: Color = Color(DenzaPalette.DANGER)

    /**
     * The one dimming behind anything modal.
     *
     * There were three: raw black at 0.78 behind the ADB gate - the only colour in the app that
     * never came from the palette at all - the ground at 0.55 behind a settings panel, and the
     * theme's own `scrim` at 0.72 that nothing read. Three depths of dark mean the screen behind a
     * window changes brightness depending on which window opened, which reads as the dashboard
     * flickering rather than as a scrim.
     */
    val Scrim: Color = Background.copy(alpha = 0.72f)

    // Track, TrackMark and Hairline were adapters for gauges that draw on a Canvas and take the
    // palette as Int. No Compose code ever read them; the gauges read DenzaPalette directly, which
    // is where those three still live.

    fun ink(alpha: Float): Color = Ink.copy(alpha = alpha)

    fun accent(alpha: Float): Color = Accent.copy(alpha = alpha)

    fun returned(alpha: Float): Color = Return.copy(alpha = alpha)
}
