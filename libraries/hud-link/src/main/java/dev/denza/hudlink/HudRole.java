package dev.denza.hudlink;

import dev.denza.hudlink.HudProtocol.Road;

/**
 * The placement table (docs/hud-output-architecture.md, 3.5): what each kind of content is, which
 * window it goes to, at which z, and which source wins it. The only place with product meaning;
 * {@link HudScene} knows windows, z, ranks and fields, never maps, manoeuvres or cameras.
 *
 * <p>Every role shows only in {@link HudMode#NAVIGATION}. Without a route nothing of ours is on
 * the glass, the radar's warnings included: a route is always needed (owner, 2026-10-18).
 */
public enum HudRole {
    /** z 100, the map window's base: one source, the other not drawn at all; the module's map before the capture. */
    MAP(HudWindow.MAP, 100, null, new int[0], HudSource.MODULE, HudSource.CAPTURE),
    /** z 200: marks the base's own source draws over its map; shown only from the source whose map is shown. */
    MAP_MARKS(HudWindow.MAP, 200, MAP, new int[0], HudSource.MODULE, HudSource.CAPTURE),
    /** z 300: other sources' marks over the map - the radar's GPS bars. */
    MAP_BADGE(HudWindow.MAP, 300, null, new int[0], HudSource.RADAR),
    /** The manoeuvre block - picture, metres, street, summaries, stock code - and the remaining route. */
    MANEUVER(HudWindow.MANEUVER, 0, null, new int[] {
            Road.REMAINING_METERS, Road.REMAINING_SECONDS, Road.MANEUVER_PNG, Road.MANEUVER_METERS,
            Road.ROAD_NAME, Road.SUMMARY_DISTANCE, Road.SUMMARY_TIME, Road.STOCK_MANEUVER,
    }, HudSource.MODULE, HudSource.CAPTURE),
    /**
     * The lane picture and the number of lanes in it: the radar's warning while it has one, the
     * navigator's lanes otherwise - a camera ahead matters more than the lanes, the owner's choice
     * of 2026-10-18 (docs/hud-output-architecture.md, 7.4). The warning has no other place.
     */
    LANES(HudWindow.LANES, 0, null, new int[] {Road.LANE_COUNT, Road.LANES_PNG},
            HudSource.RADAR, HudSource.MODULE, HudSource.CAPTURE),
    /**
     * The road's limit, km/h: field 11, and the car's own sign, which only Denza Apps writes and
     * which follows this role's winner with or without navigation ({@link HudComposition#roadLimitKmh}).
     */
    ROAD_LIMIT(HudWindow.FIELDS, 0, null, new int[] {Road.SPEED_LIMIT},
            HudSource.MODULE, HudSource.CAPTURE),
    /** The AR arrow's geometry; the module's own before Denza Apps' approximation. */
    AR(HudWindow.FIELDS, 0, null, new int[] {
            Road.LONGITUDE, Road.LATITUDE, Road.SPEED, Road.ALTITUDE,
            Road.AR_LINE, Road.AR_POINT, Road.HEADING, Road.PROGRESS,
    }, HudSource.MODULE, HudSource.CAPTURE);

    /**
     * The role whose winner opens {@link HudMode#NAVIGATION}, and the fields it must carry for
     * that: a manoeuvre block without them is empty, and the HUD fills an empty one with Chinese
     * captions. Field 9, the metres to the manoeuvre, is in every hint Denza Apps' capture sends.
     *
     * <p>The module is the exception: it has always opened navigation for the whole of a route it
     * holds, a manoeuvre ahead or not, and its map shows only then. Stage 3 moved it onto the
     * compositor without changing the glass, so its route alone opens navigation. Whether the
     * HUD then fills a block without field 9 with captions is a live check
     * (docs/hud-output-architecture.md, open questions).
     */
    public static final HudRole NAVIGATION = MANEUVER;
    private static final int[] NAVIGATION_REQUIRES = {Road.MANEUVER_METERS};

    public final HudWindow window;
    /** Draw order in a layered window, low first; 0 for a role of fields. */
    public final int z;
    /** For a layer role: shown only from the source that wins this role, or null. */
    public final HudRole follows;
    private final int[] fields;
    private final HudSource[] sources;

    HudRole(HudWindow window, int z, HudRole follows, int[] fields, HudSource... sources) {
        this.window = window;
        this.z = z;
        this.follows = follows;
        this.fields = fields;
        this.sources = sources;
    }

    /** A role of layers in a layered window, as against a role of road-packet fields. */
    public boolean layered() {
        return window.layered;
    }

    /** The road-packet fields of this role; empty for a role of layers. */
    public int[] fields() {
        return fields.clone();
    }

    public boolean carries(int field) {
        for (int each : fields) {
            if (each == field) {
                return true;
            }
        }
        return false;
    }

    /** {@code source}'s rank for this role, 0 the highest; -1 when the role takes nothing from it. */
    public int rank(HudSource source) {
        for (int index = 0; index < sources.length; index++) {
            if (sources[index] == source) {
                return index;
            }
        }
        return -1;
    }

    /** Whether {@code source}'s {@code fields}, published to {@link #NAVIGATION}, open navigation. */
    public static boolean opensNavigation(HudSource source, HudFields fields) {
        return source == HudSource.MODULE || fillsNavigation(fields);
    }

    /** Whether {@code fields}, published to {@link #NAVIGATION}, fill the manoeuvre block. */
    public static boolean fillsNavigation(HudFields fields) {
        for (int field : NAVIGATION_REQUIRES) {
            if (!fields.has(field)) {
                return false;
            }
        }
        return true;
    }
}
