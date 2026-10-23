package dev.denza.hud.probe;

/**
 * The last thing heard on each topic and when (monotonic ms of the Binder callback), kept on the
 * overlay's worker and handed to the main thread as a copy. Silence never rewrites a value: a
 * stale observation stays what it was and the overlay says how old it is. A session change is
 * remembered: nothing heard before it counts as fresh, because a new session has to be heard again.
 */
final class AdasObservations {
    /** The Binder session is up (connected and subscribed); false while binding or after a drop. */
    boolean connected;
    /** When the session last came up or went down; -1 before the first bind. */
    long sessionAt = -1;
    int dnp = -1, icc = -1, noaMode;
    boolean takeover;
    int alarmReason, notice;
    long pilotAt = -1, alarmAt = -1, modeAt = -1;
    long selectedPath = Long.MIN_VALUE;
    int selectedCode;
    long selectedAt = -1;
    long[] matchedPaths = new long[0];
    long matchAt = -1;
    /** Any message of the stock map's feed, and the last one that said "no route". */
    long stockAt = -1, stockEmptyAt = -1;

    void apply(AdasWire.Event e, long at) {
        switch (e.kind) {
            case PILOT: dnp = e.dnp; icc = e.icc; takeover = e.takeover; pilotAt = at; break;
            case ALARM: alarmReason = e.alarmReason; notice = e.notice; alarmAt = at; break;
            case MODE: noaMode = e.noaMode; modeAt = at; break;
            case SELECTED: selectedPath = e.pathId; selectedCode = e.statusCode; selectedAt = at; break;
            case MATCH: matchedPaths = e.matchedPaths.clone(); matchAt = at; break;
            default:
                stockAt = at;
                if (e.stockEmpty()) stockEmptyAt = at;
        }
    }

    /** A new or lost Binder session: nothing heard before it is fresh any more. */
    void session(boolean up, long at) { connected = up; sessionAt = at; }

    /** Heard at [at] on the current session. */
    boolean current(long at) { return connected && at >= 0 && at >= sessionAt; }

    AdasObservations copy() {
        AdasObservations o = new AdasObservations();
        o.connected = connected; o.sessionAt = sessionAt; o.dnp = dnp; o.icc = icc; o.noaMode = noaMode; o.takeover = takeover;
        o.alarmReason = alarmReason; o.notice = notice; o.pilotAt = pilotAt; o.alarmAt = alarmAt; o.modeAt = modeAt;
        o.selectedPath = selectedPath; o.selectedCode = selectedCode; o.selectedAt = selectedAt;
        o.matchedPaths = matchedPaths.clone(); o.matchAt = matchAt; o.stockAt = stockAt; o.stockEmptyAt = stockEmptyAt;
        return o;
    }
}
