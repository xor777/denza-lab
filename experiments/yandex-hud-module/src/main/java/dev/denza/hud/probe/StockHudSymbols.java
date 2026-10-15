package dev.denza.hud.probe;

import java.util.List;

/** Semantic translation to the primary cyan BydLaunchermap artwork, never enum ordinals. */
final class StockHudSymbols {
    static final int MAX_LANES = 6, LANE_PITCH = 41, LANE_HEIGHT = 60;
    private static final String[] NONE = new String[0];

    /** Snapshot for the output worker. Empty cells preserve special lanes; unknown signs are hidden. */
    static String[] lanes(List<NavigatorSource.Lane> lanes) {
        if (lanes.isEmpty() || lanes.size() > MAX_LANES) return NONE;
        String[] result = new String[lanes.size()];
        boolean any = false;
        for (int i = 0; i < result.length; i++) {
            NavigatorSource.Lane lane = lanes.get(i);
            if (!lane.plain) continue;
            int mask = 0;
            for (String direction : lane.directions) {
                int bit = direction(direction);
                // Two different branches collapsing to one stock turn would lose information.
                if (bit == 0 || (mask & bit) != 0) return NONE;
                mask |= bit;
            }
            int back = laneAction(mask);
            if (back < 0) return NONE;
            String suffix = Integer.toHexString(back);
            if (lane.highlight == null) result[i] = "landback_hud_" + suffix;
            else {
                if (!lane.directions.contains(lane.highlight)) return NONE;
                int selected = direction(lane.highlight);
                if (selected == 0 || (mask & selected) == 0) return NONE;
                if (Integer.bitCount(mask) > 1) suffix += Integer.toHexString(laneAction(selected));
                result[i] = "landfront_hud_" + suffix;
            }
            any = true;
        }
        return any ? result : NONE;
    }

    private static int direction(String name) {
        switch (name) {
            case "straightahead": return 1;
            case "left45": case "left90": case "left135": return 2;
            case "right45": case "right90": case "right135": return 4;
            case "left180": return 8;
            case "right180": return 16;
            default: return 0; // Lane shifts have no equivalent in the recovered stock set.
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
