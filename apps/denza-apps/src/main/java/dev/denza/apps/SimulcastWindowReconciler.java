package dev.denza.apps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciles the small touch/input windows independently from the full-screen draw
 * layer. Geometry changes update existing windows in place; only a changed semantic
 * window set may alter z-order.
 */
final class SimulcastWindowReconciler {
    enum Kind {
        ROW_PLATE,
        CENTRAL_ICON,
        SLOT,
        CENTRAL_TOUCH
    }

    static final class WindowSpec {
        final String id;
        final Kind kind;
        final int left;
        final int top;
        final int width;
        final int height;

        WindowSpec(String id, Kind kind, int left, int top, int width, int height) {
            this.id = id;
            this.kind = kind;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        boolean sameGeometry(WindowSpec other) {
            return other != null
                    && left == other.left
                    && top == other.top
                    && width == other.width
                    && height == other.height;
        }
    }

    interface Host {
        boolean add(WindowSpec spec);

        boolean update(WindowSpec spec);

        boolean remove(WindowSpec spec);

        boolean raiseDrawLayer();
    }

    static final class Result {
        final int relayouts;
        final boolean semanticRebuild;
        final boolean pendingOperations;

        Result(int relayouts, boolean semanticRebuild, boolean pendingOperations) {
            this.relayouts = relayouts;
            this.semanticRebuild = semanticRebuild;
            this.pendingOperations = pendingOperations;
        }
    }

    private final Host host;
    private final LinkedHashMap<String, WindowSpec> applied = new LinkedHashMap<>();
    private boolean drawLayerRaisePending;

    SimulcastWindowReconciler(Host host) {
        this.host = host;
    }

    Result apply(List<WindowSpec> plan) {
        LinkedHashMap<String, WindowSpec> desired = new LinkedHashMap<>();
        for (WindowSpec spec : plan) {
            desired.put(spec.id, spec);
        }

        boolean semanticRebuild = false;
        boolean operationFailed = false;
        for (Map.Entry<String, WindowSpec> entry
                : new LinkedHashMap<>(applied).entrySet()) {
            if (!desired.containsKey(entry.getKey())) {
                if (host.remove(entry.getValue())) {
                    applied.remove(entry.getKey());
                    semanticRebuild = true;
                } else {
                    operationFailed = true;
                }
            }
        }

        int relayouts = 0;
        for (WindowSpec spec : desired.values()) {
            WindowSpec previous = applied.get(spec.id);
            if (previous == null) {
                if (host.add(spec)) {
                    applied.put(spec.id, spec);
                    semanticRebuild = true;
                } else {
                    operationFailed = true;
                }
            } else if (!spec.sameGeometry(previous)) {
                if (host.update(spec)) {
                    applied.put(spec.id, spec);
                    relayouts++;
                } else {
                    operationFailed = true;
                }
            }
        }

        if (desired.isEmpty()) {
            // Dialog close removes the full-screen draw layer separately; raising it
            // here would recreate an overlay while teardown is in progress.
            drawLayerRaisePending = false;
        } else if (semanticRebuild) {
            drawLayerRaisePending = true;
        }
        if (drawLayerRaisePending && host.raiseDrawLayer()) {
            drawLayerRaisePending = false;
        }
        return new Result(
                relayouts,
                semanticRebuild,
                operationFailed || drawLayerRaisePending);
    }

    boolean hasAppliedWindows() {
        return !applied.isEmpty();
    }

    void reset() {
        applied.clear();
        drawLayerRaisePending = false;
    }
}
