package dev.denza.dipilotkey.probe;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presence in a dump is separate from visibility, accessibility and native queue state. */
final class AdbWindowDump {
    static final String ACTIVITIES = "dumpsys activity activities";
    static final String WINDOWS = "dumpsys window windows";
    private static final Pattern COMPONENT = Pattern.compile(
            "\\b(?:UsbDebugging(?:SecondaryUser)?Activity|WifiDebugging(?:SecondaryUser)?Activity)(?:Alias)?\\b");

    private AdbWindowDump() { }

    static String describe(String source, String dump) {
        if (dump == null || dump.trim().isEmpty() || dump.contains("Permission Denial")
                || dump.contains("Permission denial") || dump.contains("DUMP TIMEOUT")
                || dump.contains("SecurityException") || dump.contains("Can't find service")
                || dump.contains("Error dumping service") || dump.contains("Exception occurred")) {
            return source + ": НЕИЗВЕСТНО — дамп пуст или недоступен.\n";
        }
        Set<String> names = new LinkedHashSet<>();
        for (String line : dump.split("\n")) {
            if (!line.contains("com.android.systemui")) {
                continue;
            }
            Matcher matcher = COMPONENT.matcher(line);
            while (matcher.find()) {
                names.add(matcher.group());
            }
        }
        return source + (names.isEmpty()
                ? ": компоненты ADB-диалога в этом снимке не найдены.\n"
                : ": найдены " + String.join(", ", names) + ".\n");
    }
}
