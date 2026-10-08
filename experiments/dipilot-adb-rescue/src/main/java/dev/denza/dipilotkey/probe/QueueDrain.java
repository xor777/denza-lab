package dev.denza.dipilotkey.probe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a trusted shell can read and approve in Android's ADB prompt queue.
 *
 * <p>One prompt is on screen (or hidden) at a time. {@code AdbDebuggingManager} logs it as
 * {@code Logging key <public key>, state = 1}. {@code allowDebugging} is binder transaction 1
 * and only accepts the key whose fingerprint is the one currently pending. Five approvals is
 * the same bound the other rescue uses for rejections.
 */
final class QueueDrain {
    static final int MAX_ALLOWS = 5;
    static final String PENDING_MARKER = "prompt currently pending";
    static final String DRAINED_MARKER = "no prompts to send";
    static final String DISPATCHED_MARKER = "prompting user for adb authentication";

    enum State {
        PENDING,
        DRAINED,
        UNKNOWN
    }

    static final String LOGCAT =
            "logcat -d -b all -t 800 -v threadtime -s adbd:* AdbDebuggingManager:* adbd_auth:* libadbd_auth:* "
                    + "UsbDebuggingActivity:*";

    static final String DUMP_KEYS =
            "dumpsys activity activities | grep -a -E 'UsbDebugging|WifiDebugging|key='";

    private static final Pattern LOGGED_KEY = Pattern.compile(
            "Logging key (null|[A-Za-z0-9+/=]{200,}(?: [^,]+)?), state = (\\d+)");
    private static final Pattern DUMPED_KEY = Pattern.compile(
            "\\bkey=([A-Za-z0-9+/=]{200,}(?: [A-Za-z0-9@._+-]+)?)");
    private static final Pattern KEY_SHAPE = Pattern.compile(
            "[A-Za-z0-9+/=]{200,}(?: [^\\s]+)?");

    private QueueDrain() {
    }

    /** Reports the latest queue observation in the retained log, not a live queue-size query. */
    static boolean pending(String log) {
        return state(log) == State.PENDING;
    }

    static State state(String log) {
        State state = State.UNKNOWN;
        String pendingKey = null;
        if (log == null) {
            return state;
        }
        for (String line : log.split("\n")) {
            if (line.contains(DRAINED_MARKER)) {
                state = State.DRAINED;
                pendingKey = null;
            } else if (line.contains(PENDING_MARKER) || line.contains(DISPATCHED_MARKER)) {
                state = State.PENDING;
            } else {
                Matcher key = LOGGED_KEY.matcher(line);
                if (key.find()) {
                    if ("1".equals(key.group(2))) {
                        state = State.PENDING;
                        pendingKey = key.group(1);
                    } else if (answers(key.group(1), key.group(2), pendingKey)
                            && state == State.PENDING) {
                        // An answer alone does not prove the native queue is empty.
                        state = State.UNKNOWN;
                        pendingKey = null;
                    }
                }
            }
        }
        return state;
    }

    /** Full confirmation line, including its timestamp: duplicate keys are separate requests. */
    static String requestToken(String log) {
        if (log == null) {
            return null;
        }
        String token = null;
        String pendingKey = null;
        for (String line : log.split("\n")) {
            Matcher key = LOGGED_KEY.matcher(line);
            if (key.find()) {
                if ("1".equals(key.group(2))) {
                    token = line;
                    pendingKey = key.group(1);
                } else if (answers(key.group(1), key.group(2), pendingKey)) {
                    token = null;
                    pendingKey = null;
                }
            }
            if (line.contains(DRAINED_MARKER)) {
                token = null;
                pendingKey = null;
            }
        }
        return token;
    }

    static String publicKey(String logcat, String dumpsys) {
        String fromLog = lastLoggedKey(logcat);
        if (fromLog != null) {
            return fromLog;
        }
        // A dumpsys Activity intent normally only prints "(has extras)", not the key.
        // Do not revive a historical confirmation after it was answered.
        if (logcat != null && (LOGGED_KEY.matcher(logcat).find()
                || logcat.contains(DRAINED_MARKER))) {
            return null;
        }
        return lastDumpedKey(dumpsys);
    }

    /**
     * {@code allowDebugging(alwaysAllow = true, publicKey)} as {@code service call} spells it.
     * A null return means the text is not a key and must not be sent.
     */
    static String allowCommand(String publicKey) {
        if (publicKey == null || publicKey.indexOf('\'') >= 0) {
            return null;
        }
        if (!KEY_SHAPE.matcher(publicKey).matches()) {
            return null;
        }
        return "service call adb 1 i32 1 s16 '" + publicKey + "'";
    }

    private static String lastLoggedKey(String logcat) {
        if (logcat == null) {
            return null;
        }
        String found = null;
        for (String line : logcat.split("\n")) {
            if (line.contains(DRAINED_MARKER)) {
                found = null;
            }
            Matcher matcher = LOGGED_KEY.matcher(line);
            if (matcher.find()) {
                if ("1".equals(matcher.group(2))) {
                    found = matcher.group(1);
                } else if (answers(matcher.group(1), matcher.group(2), found)) {
                    found = null;
                }
            }
        }
        if (state(logcat) == State.DRAINED) {
            return null;
        }
        return found;
    }

    private static boolean answered(String state) {
        return "2".equals(state) || "3".equals(state) || "5".equals(state) || "6".equals(state);
    }

    private static boolean answers(String key, String state, String pendingKey) {
        return "3".equals(state) || (answered(state)
                && (pendingKey == null || pendingKey.equals(key)));
    }

    static boolean binderAccepted(String output) {
        return output != null && Pattern.compile(
                "Result:\\s*Parcel\\(\\s*(?:0x00000000:\\s*)?00000000\\b")
                .matcher(output).find();
    }

    static String redact(String text) {
        return text == null ? "" : text.replaceAll(
                "[A-Za-z0-9+/=]{200,}(?: [^\\s,]+)?", "[ADB key]");
    }

    private static String lastDumpedKey(String dumpsys) {
        if (dumpsys == null) {
            return null;
        }
        Matcher matcher = DUMPED_KEY.matcher(dumpsys);
        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }
        return found;
    }
}
