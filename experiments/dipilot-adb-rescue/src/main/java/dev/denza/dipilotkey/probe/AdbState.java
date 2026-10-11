package dev.denza.dipilotkey.probe;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Both generations of Android's dump format, retaining only key-state metadata for display. */
final class AdbState {
    private static final Pattern FIELD = Pattern.compile("(?mi)^\\h*(connected_to_adb|"
            + "(?:ADB )?connected to (?:adbd|daemon)|last[_ ]key[_ ]received|user[_ ]keys|"
            + "system[_ ]keys|key[_ ]?store)\\h*[:=]\\h*");
    private static final Pattern FP = Pattern.compile("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){15}");
    private static final Pattern KEY = Pattern.compile(
            "(?<![A-Za-z0-9+/])([A-Za-z0-9+/=]{700})(?![A-Za-z0-9+/=])(?: ([A-Za-z0-9@._+-]+))?");
    final String fingerprint;
    final Boolean threadPresent;
    final boolean recognized;
    final String format;
    private final List<String> keySections;
    private final String dump;

    private AdbState(String dump) {
        this.dump = dump == null ? "" : dump;
        keySections = new ArrayList<>();
        String last = null;
        Boolean thread = null;
        boolean known = false, modern = false, legacy = false;
        Matcher fields = FIELD.matcher(this.dump);
        String previous = null;
        int start = 0;
        while (fields.find()) {
            if (isKeys(previous)) keySections.add(this.dump.substring(start, fields.start()));
            String name = fields.group(1).toLowerCase(Locale.ROOT);
            known = true;
            modern |= name.contains("_"); legacy |= name.contains(" ");
            previous = name.replace(' ', '_');
            start = fields.end();
            int end = this.dump.indexOf('\n', start);
            String value = this.dump.substring(start, end < 0 ? this.dump.length() : end).trim();
            if (previous.equals("last_key_received")) {
                last = FP.matcher(value).matches() ? value.toUpperCase(Locale.ROOT) : null;
            } else if (name.startsWith("connected") || name.startsWith("adb connected")) {
                if (value.equalsIgnoreCase("true")) thread = true;
                if (value.equalsIgnoreCase("false")) thread = false;
            }
        }
        if (isKeys(previous)) keySections.add(this.dump.substring(start));
        fingerprint = last;
        threadPresent = thread;
        recognized = known && !denied(this.dump);
        format = modern && legacy ? "оба формата" : modern ? "field=value" : legacy ? "текстовый" : "не распознан";
    }

    static AdbState read(String dump) { return new AdbState(dump); }
    private static boolean isKeys(String name) { return "user_keys".equals(name) || "system_keys".equals(name); }
    private static boolean denied(String text) {
        return text.contains("Permission Denial") || text.contains("SecurityException")
                || text.contains("DUMP TIMEOUT") || text.contains("Can't find service");
    }

    Boolean stored(DenzaPublicKey key) {
        if (!recognized || keySections.isEmpty()) return null;
        boolean incomplete = false;
        for (String section : keySections) {
            Matcher keys = KEY.matcher(section);
            while (keys.find()) if (keys.group(1).equals(key.blob)) return true;
            incomplete |= section.contains("IOException") || section.contains("Error")
                    || (!section.endsWith("\n") && !section.trim().endsWith("}"));
        }
        return incomplete ? null : false;
    }

    /** A candidate only if its decoded key has the exact last-received fingerprint. */
    String matchingKey(String extraLog) {
        if (fingerprint == null) return null;
        Matcher keys = KEY.matcher(dump + "\n" + (extraLog == null ? "" : extraLog));
        while (keys.find()) {
            try {
                byte[] blob = Base64.getDecoder().decode(keys.group(1));
                if (blob.length != 524 || java.nio.ByteBuffer.wrap(blob)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() != 64) continue;
                if (fingerprint.equals(DenzaPublicKey.fingerprint(blob))) {
                    return keys.group(1) + (keys.group(2) == null ? "" : " " + keys.group(2));
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    String describe(DenzaPublicKey key) {
        Boolean saved = stored(key);
        return "ADB-служба: формат " + format + "; поток разрешений: "
                + (threadPresent == null ? "неизвестно" : threadPresent ? "есть" : "НЕТ")
                + "\nПоследний отпечаток системы: " + (fingerprint == null ? "недоступен/не задан" : fingerprint)
                + (fingerprint == null ? "" : fingerprint.equals(key.fingerprint) ? " (Denza Apps)" : " (другой ключ)")
                + "\nКлюч Denza Apps в списке системы: "
                + (saved == null ? "неизвестно" : saved ? "ЕСТЬ" : "НЕТ") + "\n";
    }
}
