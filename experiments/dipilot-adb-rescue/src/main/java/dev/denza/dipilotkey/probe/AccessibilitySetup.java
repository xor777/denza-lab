package dev.denza.dipilotkey.probe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Enables only this probe, preserving all other enabled accessibility components. */
final class AccessibilitySetup {
    static final String PACKAGE = "dev.denza.dipilotkey.probe";
    static final String COMPONENT = PACKAGE + "/" + PACKAGE + ".PromptClicker";
    private static final String SHORT_COMPONENT = PACKAGE + "/.PromptClicker";
    private static final String SERVICES =
            "settings --user current get secure enabled_accessibility_services";
    private static final String ENABLED =
            "settings --user current get secure accessibility_enabled";

    interface Shell {
        String run(String command) throws Exception;
    }

    private AccessibilitySetup() { }

    static void enable(Shell shell) throws Exception {
        String existing = read(shell.run(SERVICES));
        if (!existing.isEmpty() && !existing.equals("null")) {
            for (String component : existing.split(":")) {
                if (!component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) {
                    throw new IOException("Ответ списка служб не распознан; настройки не изменены");
                }
            }
        }
        String merged = withProbe(existing);
        String output = shell.run(
                "settings --user current put secure enabled_accessibility_services "
                        + quote(merged)
                        + " && settings --user current put secure accessibility_enabled 1");
        read(output);
        if (!containsProbe(read(shell.run(SERVICES)))
                || !"1".equals(read(shell.run(ENABLED)))) {
            throw new IOException("Система не включила службу нажатий");
        }
    }

    static String withProbe(String setting) {
        List<String> entries = new ArrayList<>();
        if (setting != null && !setting.trim().equals("null")) {
            for (String entry : setting.split(":")) {
                if (!entry.trim().isEmpty()) {
                    entries.add(entry.trim());
                }
            }
        }
        if (!containsProbe(String.join(":", entries))) {
            entries.add(COMPONENT);
        }
        return String.join(":", entries);
    }

    static boolean containsProbe(String setting) {
        if (setting == null) {
            return false;
        }
        for (String entry : setting.split(":")) {
            if (COMPONENT.equals(entry.trim()) || SHORT_COMPONENT.equals(entry.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String read(String output) throws IOException {
        if (output == null || output.contains("Exception") || output.contains("Error")
                || output.contains("Permission denial")) {
            throw new IOException("Настройки службы недоступны: " + QueueDrain.redact(output));
        }
        return output.trim();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
