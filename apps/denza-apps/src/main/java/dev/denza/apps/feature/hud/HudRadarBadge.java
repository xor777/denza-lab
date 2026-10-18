package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudRadarWarning;

import java.util.Locale;

/**
 * The line of HUD Speed's warning on the HUD ({@link HudRadarPainter} draws it beside HUD Speed's
 * own tiles). Plain Java, so it is tested on the JVM.
 */
final class HudRadarBadge {
    private HudRadarBadge() {
    }

    /**
     * The line beside the tiles: the metres to the warning rounded to ten, or kilometres with one
     * decimal from 1 km; in a section, the average speed so far.
     */
    static String caption(HudRadarWarning warning) {
        if (warning.section && warning.averageSpeed >= 0) {
            return "Ø " + warning.averageSpeed;
        }
        int meters = warning.meters;
        if (meters < 995) {
            return (Math.round(meters / 10f) * 10) + " м";
        }
        return String.format(Locale.ROOT, "%.1f км", meters / 1000f).replace('.', ',');
    }

    /** A section's average above its limit: the line turns red. */
    static boolean overLimit(HudRadarWarning warning) {
        return warning.section && warning.limit > 0 && warning.averageSpeed > warning.limit;
    }
}
