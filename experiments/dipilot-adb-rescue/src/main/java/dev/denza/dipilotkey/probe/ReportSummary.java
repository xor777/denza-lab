package dev.denza.dipilotkey.probe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Four visible status lines; log excerpts and individual window details stay in the scroll view. */
final class ReportSummary {
    private static final Pattern WINDOWS = Pattern.compile(
            "Проверок окон: \\d+; окон в последней проверке: (\\d+)");
    private static final Pattern FOUND = Pattern.compile("Найдено окон ADB с кнопкой: (\\d+)");
    private static final Pattern CLICKS = Pattern.compile(
            "Попыток «Разрешить»: (\\d+); ACTION_CLICK принят: (\\d+)");

    private ReportSummary() { }

    static String from(CharSequence report) {
        String shell = "не проверен";
        String service = "не проверена";
        String windows = "?", found = "0", attempts = "0", accepted = "0";
        String queue = "неизвестно";
        String denza = "не проверен";
        for (String line : report.toString().split("\n")) {
            if (line.startsWith("Denza Apps: ")) denza = line.substring("Denza Apps: ".length());
            if (line.startsWith("Первая проверка shell:") || line.startsWith("Shell после")
                    || line.startsWith("Итоговая повторная проверка shell:")) {
                if (line.contains(": ДА")) {
                    shell = "ДА";
                } else if (line.contains(": НЕТ")) {
                    shell = "НЕТ";
                }
            }
            if (line.startsWith("Служба нажатий:")) {
                service = line.contains("ВЫКЛЮЧЕНА") ? "выключена" : "жду подключения";
            } else if (line.startsWith("Служба подключена:")) {
                service = line.contains(": ДА") ? "подключена" : "не подключена";
            }
            Matcher match = WINDOWS.matcher(line);
            if (match.find()) {
                windows = match.group(1);
            }
            match = FOUND.matcher(line);
            if (match.find()) {
                found = match.group(1);
            }
            match = CLICKS.matcher(line);
            if (match.find()) {
                attempts = match.group(1);
                accepted = match.group(2);
            }
            if (line.startsWith("Очередь") || line.startsWith("После разрешения ")) {
                if (line.contains(": НЕИЗВЕСТНО")) {
                    queue = "неизвестно";
                } else if (line.contains(": ЕСТЬ ожидающий запрос")) {
                    queue = "есть ожидающий запрос";
                } else if (line.contains(": сообщение «no prompts to send»")) {
                    queue = "была пуста";
                }
            }
        }
        return "Shell: " + shell + " · служба: " + service
                + "\nDenza Apps: " + denza
                + "\nОкон: " + windows + " · ADB: " + found + " · нажатий: " + attempts + "/" + accepted
                + "\nОчередь (журнал): " + queue;
    }
}
