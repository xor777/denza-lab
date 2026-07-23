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
        void add(WindowSpec spec);

        void update(WindowSpec spec);

        void remove(WindowSpec spec);

        void raiseDrawLayer();
    }

    static final class Result {
        final int relayouts;
        final boolean semanticRebuild;

        Result(int relayouts, boolean semanticRebuild) {
            this.relayouts = relayouts;
            this.semanticRebuild = semanticRebuild;
        }
    }

    private final Host host;
    private final LinkedHashMap<String, WindowSpec> applied = new LinkedHashMap<>();

    SimulcastWindowReconciler(Host host) {
        this.host = host;
    }

    Result apply(List<WindowSpec> plan) {
        LinkedHashMap<String, WindowSpec> desired = new LinkedHashMap<>();
        for (WindowSpec spec : plan) {
            desired.put(spec.id, spec);
        }

        boolean semanticRebuild = false;
        for (Map.Entry<String, WindowSpec> entry
                : new LinkedHashMap<>(applied).entrySet()) {
            if (!desired.containsKey(entry.getKey())) {
                host.remove(entry.getValue());
                applied.remove(entry.getKey());
                semanticRebuild = true;
            }
        }

        int relayouts = 0;
        for (WindowSpec spec : desired.values()) {
            WindowSpec previous = applied.get(spec.id);
            if (previous == null) {
                host.add(spec);
                semanticRebuild = true;
            } else if (!spec.sameGeometry(previous)) {
                host.update(spec);
                relayouts++;
            }
            applied.put(spec.id, spec);
        }

        if (semanticRebuild) {
            host.raiseDrawLayer();
        }
        return new Result(relayouts, semanticRebuild);
    }

    void reset() {
        applied.clear();
    }
}
