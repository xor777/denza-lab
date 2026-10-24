package dev.denza.adasplayer.probe;

/** Where the player writes what it did: each service start/stop, each fire result, and notes. The
 * ADAS answers (services 13 and 44) are recorded by {@link AdasAnswerListener} through the same
 * sink. Every row carries elapsedRealtime and wall time. */
interface Recorder {
    void service(String op, long serviceTopic, int result, long elapsedMs, long wallMs);

    void fire(String label, long topic, int result, long elapsedMs, long wallMs);

    void answer(long topic, byte[] payload, long elapsedMs, long wallMs);

    void note(String event, String detail);
}
