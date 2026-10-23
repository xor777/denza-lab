package dev.denza.hud.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the ADAS overlay shows, in the words of its board (tools/design-canvas/adas-overlay/adas.js,
 * the state object at its top), and the rules that turn observations into it. Plain Java: every
 * rule here runs in AdasOverlayTest.
 *
 * <ul>
 * <li>The pilot: NOA or ICC, whichever is doing the driving (ICC when only ICC is active), lit
 *     when active, outlined when available, dim when off; NOA's family (город / трасса) beside it.</li>
 * <li>Staleness: no fresh pilot status on the current session greys the whole row and names the
 *     age of the last answer. A stale row is never drawn active and never raises a takeover.</li>
 * <li>Route receipt, only while we feed a route: a clock until ADAS selects our pathId, one tick
 *     for selection with 200, two once its path match names our pathId, a cross for a refusal.</li>
 * <li>Alarms, only while we feed a route: «Яндекс молчит» when our feed stopped, «Штатная карта /
 *     шлёт пустой маршрут» when the stock map's own feed says "no route" over ours.</li>
 * <li>The details line (who feeds ADAS and how old each side is) appears only with a deviation.</li>
 * <li>At one third of the screen the secondary function folds away, and an alarm replaces the row
 *     instead of stacking above it.</li>
 * </ul>
 */
final class AdasOverlayState {
    enum Fn { ACTIVE, AVAILABLE, OFF }
    enum Mode { CITY, HIGHWAY }
    enum Route { WAITING, SELECTED, MATCHED, REJECTED }
    enum Alarm { STOCK_EMPTY, YANDEX_SILENT }
    enum Plate { ROW, ALARM, TAKEOVER }

    /** No pilot status for this long and the row is stale; the car sends one about ten times a second. */
    static final long STALE_MS = 2000;
    /** Our bridge fires once a second: three missed sends and Yandex is silent. */
    static final long SILENT_MS = 3000;
    /** A "no route" from the stock map counts for this long; it repeats about once a second. */
    static final long STOCK_WINDOW_MS = 3000;
    static final String HANDS = "Руки на руль";
    /** One third of the screen and narrower: under this many dp. */
    static final int NARROW_DP = 600;

    Fn noa = Fn.OFF, icc = Fn.OFF;
    Mode mode;
    Route route;
    boolean stale;
    /** Whole seconds since the last ADAS answer while stale; -1 when none was ever heard. */
    int staleSeconds = -1;
    Alarm alarm;
    String takeover;
    /** The details line, or null: label, value («0,4», «12»), whether it is the deviation. */
    List<Detail> details;
    boolean narrow;

    static final class Detail {
        final String label, value; final boolean warn;
        Detail(String label, String value, boolean warn) { this.label = label; this.value = value; this.warn = warn; }
        @Override public String toString() { return label + " " + value + (warn ? "!" : ""); }
    }

    /** The plates bottom to top. At one third an alarm takes the row's place; red outranks orange. */
    List<Plate> plates() {
        if (narrow) {
            if (takeover != null) return Collections.singletonList(Plate.TAKEOVER);
            if (alarm != null) return Collections.singletonList(Plate.ALARM);
            return Collections.singletonList(Plate.ROW);
        }
        List<Plate> out = new ArrayList<>();
        out.add(Plate.ROW);
        if (alarm != null) out.add(Plate.ALARM);
        if (takeover != null) out.add(Plate.TAKEOVER);
        return out;
    }

    /** NOA leads unless only ICC drives. */
    boolean iccLeads() { return noa != Fn.ACTIVE && icc == Fn.ACTIVE; }

    static AdasOverlayState of(AdasObservations o, AdasFeed.Snapshot feed, long now, boolean narrow) {
        AdasOverlayState s = new AdasOverlayState();
        s.narrow = narrow;
        boolean fresh = o.current(o.pilotAt) && now - o.pilotAt <= STALE_MS;
        s.stale = !fresh;
        if (s.stale) s.staleSeconds = o.pilotAt < 0 ? -1 : (int) (Math.max(0, now - o.pilotAt) / 1000);
        s.noa = fn(o.dnp);
        s.icc = fn(o.icc);
        s.mode = o.modeAt < 0 ? null : o.noaMode == 2 ? Mode.CITY : o.noaMode == 1 ? Mode.HIGHWAY : null;
        boolean alarmFresh = o.current(o.alarmAt) && now - o.alarmAt <= STALE_MS;
        if (fresh && (o.takeover || alarmFresh && (o.alarmReason == AdasWire.TOR_HANDS || o.notice == AdasWire.TOR_HANDS)))
            s.takeover = HANDS;
        long yandexAge = feed.lastSendMs < 0 ? Long.MAX_VALUE : Math.max(0, now - feed.lastSendMs);
        boolean stockEmpty = false;
        if (feed.feeding()) {
            if (o.selectedPath != feed.pathId) s.route = Route.WAITING;
            else if (o.selectedCode != AdasWire.SUCCESS) s.route = Route.REJECTED;
            else s.route = contains(o.matchedPaths, feed.pathId) ? Route.MATCHED : Route.SELECTED;
            stockEmpty = o.current(o.stockEmptyAt) && now - o.stockEmptyAt <= STOCK_WINDOW_MS;
            if (yandexAge > SILENT_MS) s.alarm = Alarm.YANDEX_SILENT;
            else if (stockEmpty) s.alarm = Alarm.STOCK_EMPTY;
        }
        if (s.alarm != null || s.stale) {
            List<Detail> d = new ArrayList<>();
            if (feed.mode != AdasFeed.Mode.DISABLED && feed.lastSendMs >= 0)
                d.add(new Detail("Яндекс", age(yandexAge), yandexAge > SILENT_MS));
            if (s.alarm == Alarm.STOCK_EMPTY) d.add(new Detail("штатная карта", age(now - o.stockEmptyAt), true));
            else if (o.pilotAt >= 0) d.add(new Detail("ответ ADAS", age(Math.max(0, now - o.pilotAt)), s.stale));
            s.details = d.isEmpty() ? null : d;
        }
        return s;
    }

    static Fn fn(int value) { return value == 1 ? Fn.ACTIVE : value == 2 ? Fn.AVAILABLE : Fn.OFF; }

    /** «0,4» under a second, whole seconds after: the board's figures. */
    static String age(long ms) {
        if (ms < 1000) return "0," + Math.max(0, ms / 100);
        return String.valueOf(ms / 1000);
    }

    private static boolean contains(long[] values, long value) {
        for (long v : values) if (v == value) return true;
        return false;
    }

    /**
     * Where the stack sits: its bottom-right corner in window pixels, from the window's size and its
     * system-bar insets. Placement A of the board: the free bottom-right corner, level with
     * Navigator's route panel. Browsing, Navigator keeps a toolbar down the right edge, so the
     * overlay stands left of it; at one third the route panel spans the pane, so it stands above it.
     * Numbers are the board's anchors at density 2 (boards.js FRAMES), in dp.
     */
    static int[] anchor(int width, int height, int insetRight, int insetBottom, float density, boolean navigating) {
        boolean narrow = isNarrow(width, density);
        float right = narrow ? 12 : navigating ? 12 : 92;
        float bottom = narrow ? 82 : 10;
        return new int[]{Math.round(width - insetRight - right * density), Math.round(height - insetBottom - bottom * density)};
    }

    static boolean isNarrow(int width, float density) { return width / density < NARROW_DP; }

    @Override public String toString() {
        return (stale ? "stale" + staleSeconds + " " : "") + "noa=" + noa + " icc=" + icc + " mode=" + mode + " route=" + route
                + " alarm=" + alarm + " takeover=" + takeover + " details=" + details + " narrow=" + narrow;
    }
}
