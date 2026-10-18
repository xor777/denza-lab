package dev.denza.hudsurfaces.probe;

/**
 * One step of a scenario, done in order by {@link ScenarioRunner}. A scenario is a fixed list of
 * these, built in plain Java by {@link Plans}, so the exact packet sequence is data a JVM test
 * reads, and the runner only does what the list says.
 */
final class Step {
    enum Kind {
        /** A named stretch the owner watches: logged and shown on the screen with its length. */
        PHASE,
        /** Bind the session's intent type and start service 266. */
        OPEN,
        /** One event: {@code topic} with {@code payload}. */
        FIRE,
        /** Sleep {@code millis}; the reset button cuts it short. */
        PAUSE,
        /** tx 5 for this session only: no clear goes with it. */
        STOP,
        UNBIND,
    }

    final Kind kind;
    final SessionId session;
    final long topic;
    final byte[] payload;
    final long millis;
    /** PHASE: its title. OPEN, STOP: what result to expect and what it means, for the log. */
    final String text;
    /** PHASE: what to look at on the glass. */
    final String hint;

    private Step(Kind kind, SessionId session, long topic, byte[] payload, long millis,
            String text, String hint) {
        this.kind = kind;
        this.session = session;
        this.topic = topic;
        this.payload = payload;
        this.millis = millis;
        this.text = text;
        this.hint = hint;
    }

    static Step phase(String title, String hint, long millis) {
        return new Step(Kind.PHASE, null, 0L, null, millis, title, hint);
    }

    static Step open(SessionId session, String expect) {
        return new Step(Kind.OPEN, session, 0L, null, 0L, expect, null);
    }

    static Step fire(SessionId session, long topic, byte[] payload) {
        return new Step(Kind.FIRE, session, topic, payload, 0L, null, null);
    }

    static Step pause(long millis) {
        return new Step(Kind.PAUSE, null, 0L, null, millis, null, null);
    }

    static Step stop(SessionId session, String note) {
        return new Step(Kind.STOP, session, 0L, null, 0L, note, null);
    }

    static Step unbind(SessionId session) {
        return new Step(Kind.UNBIND, session, 0L, null, 0L, null, null);
    }
}
