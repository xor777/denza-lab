package dev.denza.hudsurfaces.probe;

import static dev.denza.hudsurfaces.probe.SessionId.A;
import static dev.denza.hudsurfaces.probe.SessionId.B;

import dev.denza.hudlink.HudProtocol;
import dev.denza.hudsurfaces.probe.SurfacePackets.CameraPhase;
import dev.denza.hudsurfaces.probe.SurfacePackets.LanePhase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every scenario as a fixed list of steps. Packets go out at 2 Hz, the cadence earlier parked
 * probes used on this car; a phase is ten packets, 5 s. Each list ends with the reset - the
 * inactive road packet and a black map frame three times 120 ms apart, stop 350 ms after the
 * first, then unbind - and a short stretch to look for anything left behind.
 */
final class Plans {
    static final long TICK_MS = 500L;
    static final int TICKS_PER_PHASE = 10;
    static final long PHASE_MS = TICK_MS * TICKS_PER_PHASE;
    /** Scenario 5 has five stretches, so each is eight packets, 4 s. */
    static final int LANE_TICKS = 8;
    /** Scenario 3's warning: twenty packets, 300 m down to 110 m. */
    static final int WARNING_TICKS = 20;
    static final long BASELINE_MS = 3_000L;
    static final long AFTER_RESET_MS = 3_000L;
    static final long AFTER_WARNING_MS = 5_000L;

    /** The reset, as the shared session policy sends it (HudSession). */
    static final int CLEAR_SENDS = 3;
    static final long CLEAR_REPEAT_MS = 120L;
    static final long STOP_DELAY_MS = 350L;

    /** The pictures, drawn on Android by {@link ProbeArt}; tests pass any bytes. */
    interface Art {
        /** 80×80, a straight arrow for scenario 2's manoeuvre block. */
        byte[] arrow();

        /** 80×80, the camera pictogram scenario 3 puts in the manoeuvre block. */
        byte[] camera();

        /** 80×80, the session's letter. */
        byte[] letter(SessionId session);

        /** 300×180, the session's map frame. */
        byte[] map(SessionId session);

        /** 300×180 all black: what empties the map window. */
        byte[] blackMap();

        /** 123×60, three lanes as the stock picture draws them: left, ahead (white), right. */
        byte[] lanes();

        /**
         * {@code width}×{@code height}: a camera warning as the lanes window would carry it,
         * framed edge to edge with a mark in each corner, so the glass shows any crop.
         */
        byte[] lanesPicture(int width, int height);
    }

    private Plans() {
    }

    static List<Step> plan(Scenario scenario, Art art) {
        switch (scenario) {
            case CAMERAS_OFF:
                return cameras(false, art);
            case CAMERAS_ON:
                return cameras(true, art);
            case WARNING:
                return warning(art);
            case HANDOVER_QUIET:
                return handover(false, art);
            case HANDOVER_GAP:
                return handover(true, art);
            case LANES:
                return lanes(art);
            case RESET:
                return reset(art);
            default:
                throw new IllegalArgumentException("No plan for " + scenario);
        }
    }

    /** Scenarios 1 and 2: the stock camera inputs one at a time, with navigation off or on. */
    private static List<Step> cameras(boolean navigating, Art art) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.open(A, "0 или 13 (13: службу 266 уже держит другой клиент)"));
        byte[] arrow = navigating ? art.arrow() : null;
        for (CameraPhase phase : CameraPhase.values()) {
            steps.add(Step.phase(cameraTitle(phase, navigating), cameraHint(phase, navigating),
                    PHASE_MS));
            for (int tick = 0; tick < TICKS_PER_PHASE; tick++) {
                steps.add(Step.fire(A, HudProtocol.TOPIC_ROAD,
                        SurfacePackets.cameraFields(navigating, phase, tick, arrow)));
                steps.add(Step.pause(TICK_MS));
            }
        }
        steps.addAll(clearAndClose(A, Collections.singletonList(A), art));
        afterReset(steps, AFTER_RESET_MS);
        return steps;
    }

    /** Scenario 3: no session, then navigation only for the warning, then the reset. */
    private static List<Step> warning(Art art) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.phase("Исходное",
                "Сессии нет. Запомните стекло: только штатная скорость.", BASELINE_MS));
        steps.add(Step.pause(BASELINE_MS));
        steps.add(Step.open(A, "0 или 13 (13: службу 266 уже держит другой клиент)"));
        steps.add(Step.phase("Предупреждение",
                "Значок камеры, 300 → 110 м, «60». Нет ли китайских подписей в блоке манёвра? "
                        + "Открылось ли окно карты?",
                TICK_MS * WARNING_TICKS));
        byte[] camera = art.camera();
        for (int tick = 0; tick < WARNING_TICKS; tick++) {
            steps.add(Step.fire(A, HudProtocol.TOPIC_ROAD, SurfacePackets.warningManeuver(
                    camera, SurfacePackets.cameraMeters(tick),
                    Integer.toString(SurfacePackets.LIMIT_KMH))));
            steps.add(Step.pause(TICK_MS));
        }
        steps.addAll(clearAndClose(A, Collections.singletonList(A), art));
        steps.add(Step.phase("После очистки",
                "Ничего не должно остаться: ни значка, ни метров, ни «60», ни окна карты.",
                AFTER_WARNING_MS));
        steps.add(Step.pause(AFTER_WARNING_MS));
        return steps;
    }

    /**
     * Scenario 4. A writes and then, without any clear, either goes quiet while keeping its
     * session ({@code gap} false) or stops and unbinds, which withdraws the service offer if A
     * was its only holder ({@code gap} true). B then writes, first without a map frame, then with.
     */
    private static List<Step> handover(boolean gap, Art art) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.open(A, gap
                ? "0 нужен для разрыва; 13: службу держит другой клиент, предложение не снимется"
                : "0 или 13 (13: службу 266 уже держит другой клиент, 4а это не мешает)"));
        steps.add(Step.phase("A пишет",
                "Манёвр «A», 100 м, «СЕССИЯ A», 1,0 км, 1 мин; окно карты: голубая «A».",
                PHASE_MS));
        sessionTicks(steps, A, true, art);
        if (gap) {
            steps.add(Step.stop(A, "без очистки; если A держал службу один, она снимает "
                    + "предложение 266 и это ответ HAL"));
            steps.add(Step.unbind(A));
            steps.add(Step.phase("A ушёл",
                    "A остановлен и отвязан без очистки. Что осталось на стекле и сколько "
                            + "держится?",
                    PHASE_MS));
        } else {
            steps.add(Step.phase("A молчит",
                    "A ничего не шлёт и не очищает. Держится ли «A» все 5 с или гаснет само?",
                    PHASE_MS));
        }
        steps.add(Step.pause(PHASE_MS));
        steps.add(Step.open(B, gap
                ? "0: служба запускается заново"
                : "13: A ещё держит службу, B входит в неё же"));
        steps.add(Step.phase("B без карты",
                "B шлёт «B», 200 м, 2,0 км, 2 мин и не шлёт карту. Осталась ли голубая "
                        + "карта «A»?",
                PHASE_MS));
        sessionTicks(steps, B, false, art);
        steps.add(Step.phase("B с картой",
                "B шлёт и свой кадр карты. Окно карты: розовая «B»?", PHASE_MS));
        sessionTicks(steps, B, true, art);
        List<SessionId> started = new ArrayList<>();
        if (!gap) {
            started.add(A);
        }
        started.add(B);
        steps.addAll(clearAndClose(B, started, art));
        afterReset(steps, AFTER_RESET_MS);
        return steps;
    }

    /**
     * Scenario 5: the lanes window as a place for our own picture (docs/hud-output-architecture.md,
     * 7.4). Navigation is on throughout with scenario 2's manoeuvre block; the map window gets no
     * frame. The reset says «no lanes» too.
     */
    private static List<Step> lanes(Art art) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.open(A, "0 или 13 (13: службу 266 уже держит другой клиент)"));
        byte[] arrow = art.arrow();
        for (LanePhase phase : LanePhase.values()) {
            steps.add(Step.phase(laneTitle(phase), laneHint(phase), TICK_MS * LANE_TICKS));
            byte[] picture = phase == LanePhase.NONE ? null
                    : phase == LanePhase.REFERENCE ? art.lanes()
                    : art.lanesPicture(phase.width, phase.height);
            byte[] road = SurfacePackets.lanes(arrow, phase.lanes, picture);
            for (int tick = 0; tick < LANE_TICKS; tick++) {
                steps.add(Step.fire(A, HudProtocol.TOPIC_ROAD, road));
                steps.add(Step.pause(TICK_MS));
            }
        }
        steps.addAll(clearAndClose(A, Collections.singletonList(A), art, true));
        afterReset(steps, AFTER_RESET_MS);
        return steps;
    }

    /** The reset button with nothing running: open a session only to clear and close it. */
    private static List<Step> reset(Art art) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.open(A, "0 или 13 (13: службу 266 уже держит другой клиент)"));
        steps.addAll(clearAndClose(A, Collections.singletonList(A), art));
        return steps;
    }

    /**
     * The reset through {@code clearer}: three rounds of black map and inactive road packet,
     * 120 ms apart; stop 350 ms after the first round, the other sessions first so that the
     * clearer's stop is the one that withdraws the offer; then unbind every one of them.
     */
    static List<Step> clearAndClose(SessionId clearer, List<SessionId> started, Art art) {
        return clearAndClose(clearer, started, art, false);
    }

    /** The same, its road packet also saying «no lanes» when {@code lanes} were shown. */
    static List<Step> clearAndClose(SessionId clearer, List<SessionId> started, Art art,
            boolean lanes) {
        if (!started.contains(clearer)) {
            throw new IllegalArgumentException("The clearer must be started");
        }
        List<Step> steps = new ArrayList<>();
        steps.add(Step.phase("Сброс",
                "Неактивный road-пакет и чёрная карта ×3 через 120 мс, затем stop и unbind.",
                STOP_DELAY_MS));
        byte[] black = SurfacePackets.map(art.blackMap());
        byte[] clear = SurfacePackets.clear(lanes);
        for (int round = 0; round < CLEAR_SENDS; round++) {
            if (round > 0) {
                steps.add(Step.pause(CLEAR_REPEAT_MS));
            }
            steps.add(Step.fire(clearer, HudProtocol.TOPIC_MAP, black));
            steps.add(Step.fire(clearer, HudProtocol.TOPIC_ROAD, clear));
        }
        steps.add(Step.pause(STOP_DELAY_MS - (CLEAR_SENDS - 1) * CLEAR_REPEAT_MS));
        for (SessionId session : started) {
            if (session != clearer) {
                steps.add(Step.stop(session, "очистку уже отправил " + clearer.name()));
            }
        }
        steps.add(Step.stop(clearer, "последний держатель: служба снимает предложение 266"));
        for (SessionId session : started) {
            steps.add(Step.unbind(session));
        }
        return steps;
    }

    /** Planned length: the sum of the pauses. */
    static long durationMs(List<Step> steps) {
        long total = 0L;
        for (Step step : steps) {
            if (step.kind == Step.Kind.PAUSE) {
                total += step.millis;
            }
        }
        return total;
    }

    private static void sessionTicks(List<Step> steps, SessionId session, boolean withMap,
            Art art) {
        byte[] road = SurfacePackets.sessionManeuver(session, art.letter(session));
        byte[] map = withMap ? SurfacePackets.map(art.map(session)) : null;
        for (int tick = 0; tick < TICKS_PER_PHASE; tick++) {
            steps.add(Step.fire(session, HudProtocol.TOPIC_ROAD, road));
            if (map != null) {
                steps.add(Step.fire(session, HudProtocol.TOPIC_MAP, map));
            }
            steps.add(Step.pause(TICK_MS));
        }
    }

    private static void afterReset(List<Step> steps, long millis) {
        steps.add(Step.phase("После сброса", "Ничего не должно остаться.", millis));
        steps.add(Step.pause(millis));
    }

    private static String laneTitle(LanePhase phase) {
        switch (phase) {
            case REFERENCE:
                return "Эталон: три полосы";
            case PICTURE:
                return "Картинка 123×60, 5 = 3";
            case PICTURE_SIX:
                return "Картинка 246×60, 5 = 6";
            case STRIP:
                return "Полоса 960×48, 5 = 3";
            case NONE:
            default:
                return "Полос нет, 5 = 0";
        }
    }

    private static String laneHint(LanePhase phase) {
        String route = "Манёвр: стрелка прямо, 800 м, «ПРОБА». ";
        switch (phase) {
            case REFERENCE:
                return route + "Три стрелки, средняя белая. Где на стекле окно полос: под "
                        + "манёвром, у скорости, справа?";
            case PICTURE:
                return route + "Камера и «60» в белой рамке. Рамка и четыре жёлтых угла "
                        + "целиком? Читается?";
            case PICTURE_SIX:
                return route + "Вдвое шире, добавлены «300 м». Окно стало шире, картинка "
                        + "сжалась или обрезана справа? Углы видны?";
            case STRIP:
                return route + "Узкая длинная полоса. Сжата, обрезана (какие углы видны) или "
                        + "пусто?";
            case NONE:
            default:
                return route + "Окно полос погасло сразу? Манёвр остался?";
        }
    }

    private static String cameraTitle(CameraPhase phase, boolean navigating) {
        String suffix = navigating ? ", навигация" : ", без навигации";
        switch (phase) {
            case CAMERA:
                return "Камера" + suffix;
            case CAMERA_LIMIT:
                return "Камера и лимит 60" + suffix;
            case SECTION:
                return "Участок средней скорости" + suffix;
            case DANGER:
            default:
                return "Опасность" + suffix;
        }
    }

    private static String cameraHint(CameraPhase phase, boolean navigating) {
        String route = navigating ? "Манёвр: стрелка прямо, 800 м, «ПРОБА», 5,0 км, 10 мин. " : "";
        switch (phase) {
            case CAMERA:
                return route + "17 = 1, 18 = 300 → 210 м. Есть ли значок камеры или её метры?";
            case CAMERA_LIMIT:
                return route + "То же и 11 = 60. Появился ли знак «60»?";
            case SECTION:
                return route + "13 = 200 → 380 м, 14 = 2000 м, 15 = 60. Есть ли полоса участка, "
                        + "метры или знак?";
            case DANGER:
            default:
                return route + "23 = 30 («внимание, опасность»). Есть ли значок предупреждения?";
        }
    }
}
