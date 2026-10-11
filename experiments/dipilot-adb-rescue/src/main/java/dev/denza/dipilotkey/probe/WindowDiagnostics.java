package dev.denza.dipilotkey.probe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded window observations. Only ADB-related SystemUI text and button labels are retained. */
final class WindowDiagnostics {
    static final int LIMIT = 16;
    private final Map<String, String> rows = new LinkedHashMap<>();

    synchronized void clear() {
        rows.clear();
    }

    synchronized PromptDecision observe(int display, int id, int type, String packageName,
            String text, List<PromptDecision.Node> nodes) {
        PromptDecision decision = nodes == null ? PromptDecision.NONE
                : PromptDecision.decide(packageName, text, nodes);
        String reason;
        if (nodes == null) {
            reason = "НЕТ дерева окна";
        } else if (!"com.android.systemui".equals(packageName)) {
            reason = "другой пакет";
        } else if (!PromptDecision.isDebuggingWindow(packageName, text)) {
            reason = "текст не распознан как ADB";
        } else if (decision.clickIndex < 0) {
            reason = "ADB распознан, кнопка «Разрешить» не найдена";
        } else {
            reason = "ADB с кнопкой «Разрешить»";
        }
        String row = "d=" + display + " w=" + id + " type=" + type
                + " pkg=" + (packageName == null ? "?" : packageName)
                + " nodes=" + (nodes == null ? 0 : nodes.size()) + ": " + reason + "\n";
        if ("com.android.systemui".equals(packageName) && nodes != null) {
            row += hints(text, nodes);
        }
        put(display + "/" + id, row);
        return decision;
    }

    synchronized void error(int display, int id, int type, RuntimeException error) {
        put(display + "/" + id, "d=" + display + " w=" + id + " type=" + type
                + ": ошибка чтения окна " + error.getClass().getSimpleName() + "\n");
    }

    synchronized String render() {
        StringBuilder text = new StringBuilder(
                "\nРассмотренные окна (последнее наблюдение каждого; до " + LIMIT + "):\n");
        if (rows.isEmpty()) {
            text.append("Наблюдений нет.\n");
        }
        for (String row : rows.values()) {
            text.append(row);
        }
        return text.toString();
    }

    private void put(String key, String row) {
        rows.remove(key);
        rows.put(key, row);
        if (rows.size() > LIMIT) {
            rows.remove(rows.keySet().iterator().next());
        }
    }

    private static String hints(String text, List<PromptDecision.Node> nodes) {
        StringBuilder result = new StringBuilder();
        if (text != null) {
            for (String line : text.split("\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("usb") || lower.contains("adb") || lower.contains("rsa")
                        || lower.contains("отлад") || lower.contains("调试") || lower.contains("偵錯")) {
                    result.append("  ADB-текст: ").append(shortText(line, 160)).append('\n');
                    if (result.length() > 500) {
                        break;
                    }
                }
            }
        }
        int count = 0;
        for (PromptDecision.Node node : nodes) {
            if (node.viewId != null
                    && (node.viewId.endsWith("/button1") || node.viewId.endsWith("/button2"))) {
                result.append("  ").append(node.viewId).append(": ")
                        .append(shortText(node.text, 40)).append('\n');
                if (++count == 4) {
                    break;
                }
            }
        }
        return result.toString();
    }

    private static String shortText(String text, int limit) {
        String safe = QueueDrain.redact(text).replace('\n', ' ').trim();
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "…";
    }
}
