package dev.denza.alicewheel.probe;

/** Own a complete press, even if its foreground gate closes before UP. */
final class VoicePress {
    record Result(boolean consumed, boolean click) {}
    private boolean owned;
    private int device;
    private long downTime;

    Result event(int action, int repeat, int deviceId, long downAt,
                 boolean eligible, boolean canceled) {
        if (action == 0 && repeat == 0 && !owned) {
            owned = eligible;
            device = deviceId;
            downTime = downAt;
        }
        boolean matches = owned && device == deviceId && downTime == downAt;
        if (action == 1 && matches) {
            owned = false;
            return new Result(true, !canceled);
        }
        return new Result(matches, false);
    }
}
