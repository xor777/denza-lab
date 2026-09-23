package dev.denza.apps.design

/**
 * The one palette the app's Material surfaces draw with, taken from the vehicle rather than invented.
 *
 * Since the Luminofor design (2026-09-23) these are the car's own colours in Luminofor's grounds:
 * the ground is the dashboard's black, a surface is an idle plate (`#17161B`) or a lit one
 * (`#2C2B33`), and the accent is the stock switch's blue (`byd_pvt_switch_track_on_color_dark`,
 * `#3388FF`). The `_denza` SystemUI skin's pale champagne was the accent before; the owner found it
 * nowhere else in the car, and it went with the old dashboard. The settings surfaces themselves draw
 * from `LuminoforSpec.Sheet`; what is left here is what Material widgets - a spinner, the split
 * picker's surface - read through the theme.
 *
 * Roles stay separated the way the firmware separates them: blue is *on* and *chosen*, instrument
 * data is drawn in [INK], energy going back into the pack in [RETURN], and [WARNING] and [DANGER] are
 * the car's two alarm colours and nothing else.
 */
object DenzaPalette {

    /** Luminofor's ground: the dashboard's black. */
    const val BACKGROUND: Int = 0xFF000000.toInt()

    /** An idle plate, `head.cardOff`: a settings panel's ground. */
    const val SURFACE_QUIET: Int = 0xFF17161B.toInt()

    /** A lit plate, `head.cardOn`: a group of settings, a dialog. */
    const val SURFACE: Int = 0xFF2C2B33.toInt()

    /** A step above a lit plate. */
    const val SURFACE_RAISED: Int = 0xFF3A3942.toInt()

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

    /** Words on the accent: the stock primary button's white. */
    const val ON_ACCENT: Int = 0xFFFFFFFF.toInt()

    /** `byd_pvt_switch_track_on_color_dark`: *on* and *chosen*. Interface only - never instrument data. */
    const val ACCENT: Int = 0xFF3388FF.toInt()

    /** The accent's pale end: the head unit's blue core, `#80BEFF`. */
    const val DATA_PEAK: Int = 0xFF80BEFF.toInt()

    /** `sys_color_function` in the dark theme. Energy going back into the pack. */
    const val RETURN: Int = 0xFF2D82D7.toInt()

    /** `sys_color_abnormal`. Something needs a decision. */
    const val WARNING: Int = 0xFFFF9F19.toInt()

    /** `sys_color_warning` / `sys_red_400`. Already in this app as the DiShare exit glyph. */
    const val DANGER: Int = 0xFFFF4046.toInt()
}
