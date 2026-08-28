package dev.denza.apps.design

import android.graphics.Color

/**
 * The one palette this app draws with, taken from the vehicle rather than invented.
 *
 * Every value below was read out of `com.android.systemui.apk` pulled from this car - the build
 * whose SHA-256 `docs/instrument-display-findings.md` records - using the resource names given in
 * each comment. BYD ships several skins in one SystemUI; these are the `_denza` variants, whose
 * distinguishing move is that the accent is pale champagne where the base BYD skin uses cyan.
 *
 * Which skin the car actually renders is not yet established: `ro.byd.ui.platformized` is captured
 * but no theme property is. If it turns out to be the base skin, [ACCENT] becomes `#FF00ACEB` and
 * nothing else here moves.
 *
 * Roles are separated the way the firmware itself separates them, and the separation is the point:
 * champagne marks *interface* only, because warm yellow already means caution in a car and an
 * accent that competes with [WARNING] costs a driver the glance. Instrument data is drawn in
 * [INK]; energy going back into the pack - regeneration and engine generation alike, which are the
 * same event - is drawn in [RETURN].
 */
object DenzaPalette {

    /** `sys_gray_900`. The deepest ground the platform defines. */
    const val BACKGROUND: Int = 0xFF07080A.toInt()

    /** `sys_gray_800`. A surface that is off, disabled, or asleep. */
    const val SURFACE_QUIET: Int = 0xFF15181F.toInt()

    /** `qs_panel_start_color_bg_denza`. The live surface; the gradient's two stops are identical. */
    const val SURFACE: Int = 0xFF212429.toInt()

    /** `scene_mode_button_bg_normal_denza`. */
    const val SURFACE_RAISED: Int = 0xFF323538.toInt()

    /** `qs_adjust_icon_tint_color_denza`. */
    const val SURFACE_HIGH: Int = 0xFF484E55.toInt()

    /** `qs_icon_text_denza`. The Denza signature ink, cool pale blue-grey. */
    const val INK: Int = 0xFFDAE1EB.toInt()

    /** `qs_adjust_text_color_denza`. */
    const val INK_SECONDARY: Int = 0xFFC5CDD9.toInt()

    /** `qs_adjust_seekbar_text_color_denza`. */
    const val MUTED: Int = 0xFF86909B.toInt()

    /**
     * Muted, one step further down, for captions that must not compete.
     *
     * The car's own `0xFF6E767F` reads 3.9:1 on [SURFACE_QUIET] - under the 4.5 a 15 px caption
     * needs at a glance. Lifted to the nearest value that clears 4.5:1 on both grounds while
     * staying visibly under [MUTED], so the step down survives.
     */
    const val MUTED_DEEP: Int = 0xFF7C858F.toInt()

    /** `qs_icon_on_denza`. Reads on top of [ACCENT]. */
    const val ON_ACCENT: Int = 0xFF262D33.toInt()

    /** `vc_denza_progress_blue`, despite the name. Interface accent only - never instrument data. */
    const val ACCENT: Int = 0xFFFEEFAB.toInt()

    /** The live end of a data run: ink lifted towards the accent, so the newest value reads first. */
    const val DATA_PEAK: Int = 0xFFFFF8DA.toInt()

    /** `sys_color_function` in the dark theme. Energy going back into the pack. */
    const val RETURN: Int = 0xFF2D82D7.toInt()

    /** [RETURN] lightened for text, which needs more lift than a stroke does. */
    const val RETURN_INK: Int = 0xFF4B9BE0.toInt()

    /** `sys_color_abnormal`. Something needs a decision. */
    const val WARNING: Int = 0xFFFF9F19.toInt()

    /** `sys_color_warning` / `sys_red_400`. Already in this app as the DiShare exit glyph. */
    const val DANGER: Int = 0xFFFF4046.toInt()

    /** The unlit track behind any gauge. */
    const val TRACK: Int = 0xFF22262E.toInt()

    /** A tick or scale mark on a track. */
    const val TRACK_MARK: Int = 0xFF3F434D.toInt()

    /** `sys_qs_number_keyboard_divide_line_color_denza`: 12% white, over whatever is behind it. */
    const val HAIRLINE: Int = 0x1FFFFFFF

    /** [INK] at a given opacity, for the many shades a gauge needs between full and absent. */
    fun ink(alpha: Float): Int = Color.argb((alpha * 255f).toInt(), 0xDA, 0xE1, 0xEB)

    /** [RETURN] at a given opacity. */
    fun returned(alpha: Float): Int = Color.argb((alpha * 255f).toInt(), 0x2D, 0x82, 0xD7)

    /** [ACCENT] at a given opacity. */
    fun accent(alpha: Float): Int = Color.argb((alpha * 255f).toInt(), 0xFE, 0xEF, 0xAB)
}
