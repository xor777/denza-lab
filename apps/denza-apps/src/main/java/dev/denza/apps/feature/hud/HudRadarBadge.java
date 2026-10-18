package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudRadarWarning;

import java.util.Locale;

/**
 * The words of HUD Speed's warning on the HUD ({@link HudRadarPainter} draws them beside HUD
 * Speed's own tiles). Plain Java, so it is tested on the JVM.
 */
final class HudRadarBadge {
    private HudRadarBadge() {
    }

    /**
     * The number beside the tiles: the metres to the warning rounded to ten, or kilometres with
     * one decimal from 1 km - «350», «1,2»; in a section, the average speed so far, «Ø 97».
     */
    static String amount(HudRadarWarning warning) {
        if (warning.section && warning.averageSpeed >= 0) {
            return "Ø " + warning.averageSpeed;
        }
        int meters = warning.meters;
        if (meters < 995) {
            return Integer.toString(Math.round(meters / 10f) * 10);
        }
        return String.format(Locale.ROOT, "%.1f", meters / 1000f).replace('.', ',');
    }

    /** The number's unit, drawn small beside it: «м», «км», or nothing for a section's average. */
    static String unit(HudRadarWarning warning) {
        if (warning.section && warning.averageSpeed >= 0) {
            return "";
        }
        return warning.meters < 995 ? "м" : "км";
    }

    /** A section's average above its limit: the line turns red. */
    static boolean overLimit(HudRadarWarning warning) {
        return warning.section && warning.limit > 0 && warning.averageSpeed > warning.limit;
    }
}
