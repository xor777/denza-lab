package dev.denza.apps;

/**
 * Turns observations of the real DiShare accessibility window into idempotent exit-overlay
 * commands. An unknown observation represents the existing close-grace interval and deliberately
 * preserves the last confirmed state.
 */
final class SimulcastDialogVisibilityTracker {
    enum Observation {
        OPEN,
        UNKNOWN,
        CLOSED_CONFIRMED,
    }

    enum Command {
        NONE,
        HIDE_EXIT,
        RESTORE_EXIT,
    }

    private boolean open;

    Command observe(Observation observation) {
        if (observation == Observation.OPEN) {
            if (open) {
                return Command.NONE;
            }
            open = true;
            return Command.HIDE_EXIT;
        }
        if (observation == Observation.CLOSED_CONFIRMED) {
            if (!open) {
                return Command.NONE;
            }
            open = false;
            return Command.RESTORE_EXIT;
        }
        return Command.NONE;
    }
}
