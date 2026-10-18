package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudRadarWarning;

import java.util.Locale;

/**
 * What the radar's badge says ({@link HudRadarPainter} draws it): which glyph sits in the disc and
 * the line under it. Plain Java, so it is tested on the JVM.
 */
final class HudRadarBadge {
    /** What the glyph in the disc says; from the radar's own section flag and warning name. */
    enum Kind {
        /** A fixed camera or radar, the stop-line and every-rule complexes. */
        CAMERA,
        /** A patrol post or a mobile ambush: a camera on a tripod. */
        PATROL,
        /** An average-speed section: a span with an arrowhead at either end. */
        SECTION,
        /** Anything else the radar warns of - a crossing, children, works, an accident spot. */
        HAZARD,
    }

    private HudRadarBadge() {
    }

    /**
     * The glyph for a warning: the section flag first, then the radar's name for it. The radar
     * names its warnings in the driver's language and has no stable public type table, so the
     * name decides; an unknown name is a hazard, never a camera that is not there.
     */
    static Kind kind(HudRadarWarning warning) {
        if (warning.section) {
            return Kind.SECTION;
        }
        String title = warning.title.toLowerCase(Locale.ROOT);
        if (contains(title, "засад", "дпс", "тренога", "патрул", "пост ", "ambush", "patrol", "police")
                || title.endsWith("пост")) {
            return Kind.PATROL;
        }
        if (contains(title, "секцион", "средн", "участ", "section", "average")) {
            return Kind.SECTION;
        }
        if (contains(title, "камер", "радар", "комплекс", "стоп-лин", "camera", "radar")) {
            return Kind.CAMERA;
        }
        return Kind.HAZARD;
    }

    /**
     * The line under the glyphs: the metres to the warning rounded to ten, or kilometres with one
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

    private static boolean contains(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
