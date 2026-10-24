package dev.denza.adasplayer.probe;

import dev.denza.adaslink.Topics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays one scenario and <b>always</b> ends with the stock stop sequence (status 110 for 3 s, then
 * 100) and releases every service it started - on a clean finish, on an exception, and on a stop
 * request from the watchdog or a kill. The stop request only halts the normal feed; the end
 * sequence and release run in the {@code finally}. Plain Java and clock-injected, so the host
 * harness drives it with a virtual clock and a fake transport.
 *
 * <p>It never fires 13/0x0001 (the DNP/ICC switch): there is no such call here. The H6 NaviSt side
 * channel is not the player's: device 1023 needs the shell UID, while SOME/IP needs this app UID,
 * so the host runner (tools/adas_bridge/parked_session.py) reads, writes and restores it over adb.
 */
final class PlayerEngine {
    private final AtomicBoolean stop = new AtomicBoolean();

    /** Ask the engine to stop the normal feed; the end sequence and release still run. */
    void requestStop() { stop.set(true); }

    boolean stopRequested() { return stop.get(); }

    void run(ScenarioPlan plan, Transport transport, Clock clock, Recorder recorder, long pathId) {
        if (plan.listenOnly) {
            recorder.note("listen_started", "read-only answers; no publisher, ODD or end sequence");
            long end = clock.nowMs() + plan.durationMs;
            try {
                while (!stop.get() && clock.nowMs() < end) clock.sleepUntil(Math.min(end, clock.nowMs() + 100));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                recorder.note("listen_finished", "read-only duration ended");
            }
            return;
        }
        List<Long> started = new ArrayList<>();
        try {
            for (long service : plan.services()) {
                int result = transport.startServer(service);
                recorder.service("start", service, result, clock.nowMs(), clock.wallMs());
                if (result == 0 || result == 13) started.add(service);
            }
            body(plan, transport, clock, recorder, pathId);
        } catch (Throwable error) {
            recorder.note("engine_error", error.getClass().getSimpleName() + ":" + error.getMessage());
        } finally {
            endSequence(plan, transport, clock, recorder);
            for (int i = started.size() - 1; i >= 0; i--) {
                long service = started.get(i);
                int result = transport.stopServer(service);
                recorder.service("stop", service, result, clock.nowMs(), clock.wallMs());
            }
            recorder.note("released", "end sequence sent, services released");
        }
    }

    private void body(ScenarioPlan plan, Transport transport, Clock clock, Recorder recorder, long pathId)
            throws InterruptedException {
        if (plan.oddRequest) {
            int result = transport.oddRequest();
            recorder.fire("oddRequest", ScenarioPlan.ODD_TOPIC, result, clock.nowMs(), clock.wallMs());
        }
        long t = clock.nowMs();
        for (int[] step : plan.preRoutePhase()) {
            if (stop.get()) return;
            fire(transport, recorder, clock, "NavigationStatus", Topics.NAVIGATION_STATUS,
                    dev.denza.adaslink.StockMessages.navigationStatus(step[0]));
            t += step[1];
            clock.sleepUntil(t);
        }
        if (stop.get()) return;
        long wall = clock.wallMs();
        fire(transport, recorder, clock, "route", Topics.ROUTE, plan.routePayload(pathId, wall));
        fire(transport, recorder, clock, "pathSelected", Topics.PATH_SELECTED, plan.selectionPayload(pathId));
        long loopStart = clock.nowMs() + 100;
        long endMs = loopStart + plan.durationMs;
        for (ScenarioPlan.Tick tick : plan.loopTicks(pathId, loopStart, endMs)) {
            if (stop.get()) return;
            clock.sleepUntil(tick.tMs);
            fire(transport, recorder, clock, tick.name, tick.topic, tick.payload);
        }
    }

    private void endSequence(ScenarioPlan plan, Transport transport, Clock clock, Recorder recorder) {
        for (int[] step : plan.endSequence()) {
            fire(transport, recorder, clock, "NavigationStatus(end)", Topics.NAVIGATION_STATUS,
                    dev.denza.adaslink.StockMessages.navigationStatus(step[0]));
            try {
                clock.sleep(step[1]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void fire(Transport transport, Recorder recorder, Clock clock, String label, long topic, byte[] payload) {
        int result;
        try {
            result = transport.fire(topic, payload);
        } catch (RuntimeException failure) {
            recorder.note("fire_exception", label + ":" + failure.getClass().getSimpleName());
            result = -200;
        }
        recorder.fire(label, topic, result, clock.nowMs(), clock.wallMs());
    }
}
