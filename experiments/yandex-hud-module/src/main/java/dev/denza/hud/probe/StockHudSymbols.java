package dev.denza.hud.probe;

import java.util.List;

/** Semantic translation to the primary cyan BydLaunchermap artwork, never enum ordinals. */
final class StockHudSymbols {
    static final int MAX_LANES = 6, LANE_PITCH = 41, LANE_HEIGHT = 60;
    private static final String[] NONE = new String[0];

    /**
     * One cell key per lane for the output worker, as LANE_ART in hud-scene.js lays it out: every lane
     * gets a cell and every cell is BYD's pixels. Keys are a stock name ("landback_hud_0"), "tram:N"
     * or "taxi:N" (N = 1 when the lane is the one to take), or "shift:L|R:N". Only an empty or
     * oversized sign gives no row.
     */
    static String[] lanes(List<NavigatorSource.Lane> lanes) {
        if (lanes.isEmpty() || lanes.size() > MAX_LANES) return NONE;
        String[] result = new String[lanes.size()];
        for (int i = 0; i < result.length; i++) result[i] = cell(lanes.get(i));
        return result;
    }

    private static final String EMPTY = "landback_hud_22", SHIFT = "landfront_hud_d", BUS = "15";
    /** Bits a combination loses, first to last, while BYD has no picture for it: U-turns, then the outer branches. */
    private static final int[] DROP = {16, 8, 4, 2};

    static String cell(NavigatorSource.Lane lane) {
        int on = lane.highlight != null ? 1 : 0;
        switch (lane.kind) {
            case "BUS_LANE": return (on == 1 ? "landfront_hud_" : "landback_hud_") + BUS;
            case "TRAM_LANE": return "tram:" + on;
            case "TAXI_LANE": return "taxi:" + on;
            default: break;
        }
        if (lane.directions.contains("leftshift") || lane.directions.contains("rightshift"))
            return "shift:" + (lane.directions.contains("rightshift") ? "R" : "L") + ":" + on;
        int mask = 0;
        for (String direction : lane.directions) mask |= direction(direction);
        if (mask == 0) return EMPTY;
        int selected = lane.highlight == null ? 0 : direction(lane.highlight);
        for (int bit : DROP) {
            if (laneAction(mask) >= 0) break;
            if (bit != selected && (mask & bit) != 0 && Integer.bitCount(mask) > 1) mask &= ~bit;
        }
        int back = laneAction(mask);
        if (back < 0) return EMPTY;
        String suffix = Integer.toHexString(back);
        if (selected == 0 || (mask & selected) == 0) return "landback_hud_" + suffix;
        if (Integer.bitCount(mask) > 1) suffix += Integer.toHexString(laneAction(selected));
        return FRONT.contains(suffix) ? "landfront_hud_" + suffix : "landback_hud_" + Integer.toHexString(back);
    }

    /** The stock pictures a cell is made of, for the builder's check that each one is packaged. */
    static String[] assets(String key) {
        if (key.startsWith("tram:")) return new String[]{key.endsWith("1") ? "landfront_hud_" + BUS : "landback_hud_" + BUS};
        if (key.startsWith("taxi:")) return new String[0];
        if (key.startsWith("shift:")) return new String[]{SHIFT};
        return new String[]{key};
    }

    /** Every recommended-lane picture BYD drew (`landfront_hud_*`). */
    static final java.util.Set<String> FRONT = new java.util.HashSet<>(java.util.Arrays.asList(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "10", "11", "12", "13", "14", "15", "17", "20", "21", "22", "25",
            "40", "43", "61", "63", "70", "71", "73", "90", "95", "100", "101", "105", "113", "115", "121", "123", "125", "130", "133", "135",
            "141", "148", "a0", "a8", "b1", "b5", "c3", "c8", "d", "e1", "e5"));

    private static int direction(String name) {
        switch (name) {
            case "straightahead": return 1;
            // A turn across from the far lane is the plain turn: BYD has no other picture for it.
            case "left45": case "left90": case "left135": case "leftfromright": return 2;
            case "right45": case "right90": case "right135": case "rightfromleft": return 4;
            case "left180": return 8;
            case "right180": return 16;
            default: return 0; // unknown: BYD's empty lane if nothing else is known
        }
    }

    private static int laneAction(int mask) {
        switch (mask) {
            case 1: return 0; case 2: return 1; case 3: return 2;
            case 4: return 3; case 5: return 4; case 8: return 5;
            case 6: return 6; case 7: return 7; case 16: return 8;
            case 9: return 9; case 17: return 10; case 10: return 11;
            case 20: return 12; case 11: return 16; case 12: return 17;
            case 14: return 18; case 13: return 19; case 18: return 20;
            default: return -1;
        }
    }

    static String manoeuvre(NavigatorSource.Hint hint) {
        if (hint == null || hint.icon == null) return null;
        int id;
        switch (hint.icon) {
            case "straight": id = 9; break;
            case "left": id = 2; break; case "right": id = 3; break;
            case "slight-left": id = 4; break; case "slight-right": id = 5; break;
            case "sharp-left": id = 6; break; case "sharp-right": id = 7; break;
            case "uturn-left": id = 8; break; case "uturn-right": id = 19; break;
            case "finish": id = hint.stockId == 45 ? 10 : 15; break;
            case "roundabout":
                id = hint.exit >= 1 && hint.exit <= 10 ? 49 + hint.exit : hint.stockId == 24 ? 12 : 11;
                break;
            default: return null;
        }
        return "ar_hud_sou" + id;
    }

    private StockHudSymbols() {}
}
