package dev.denza.dipilotkey.probe;

import android.content.Context;
import android.provider.Settings;

import dev.denza.disharebridge.LocalAdbClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Frees a stuck ADB prompt queue while authenticated as BydDipilot.
 *
 * <p>The probe never submits a public key. Dipilot already did, and another submission would
 * take another slot. This signs with that same key, clicks Allow on the dialog wherever it was
 * put, and once the key has a shell approves whatever the log still says is pending.
 */
final class PortRescue {
    private static final String CHECK_MARKER = "DENZA_DIPILOT_OK";
    private static final int SHELL_TIMEOUT_MS = 8000;
    private static final long CLICK_WAIT_MS = 12000;
    private static final long RETRY_WAIT_MS = 3000;

    enum Access {
        TRUSTED,
        AUTHORIZATION_REQUIRED,
        UNAVAILABLE,
        ERROR
    }

    private final Context context;
    private final LocalAdbClient client;
    private String lastFailure = "";

    PortRescue(Context context) throws Exception {
        this.context = context.getApplicationContext();
        AdbKeyFile.install(this.context);
        this.client = new LocalAdbClient(
                this.context, DipilotIdentity.COMMENT, LocalAdbClient.AuthorizationPolicy.PASSIVE);
    }

    String rescue() {
        return rescue(text -> { });
    }

    String rescue(Consumer<String> progress) {
        StringBuilder report = new StringBuilder();
        PromptClicker.resetDiagnostics();
        boolean clicker = PromptClicker.enabled(context);
        try {
            report.append("Ключ Dipilot (").append(DipilotIdentity.COMMENT).append(")\n");
            report.append("Отпечаток: ").append(DipilotIdentity.fingerprint()).append('\n');
        } catch (Exception error) {
            report.append("Ключ не читается: ").append(error.getClass().getSimpleName()).append('\n');
        }
        report.append("adb_enabled: ").append(systemSwitch()).append('\n');
        report.append("Служба нажатий: ")
                .append(clicker ? "включена" : "ВЫКЛЮЧЕНА")
                .append('\n');
        report.append("\n1. Открываю shell ключом Dipilot (пассивно)…\n");
        progress.accept(report.toString());
        try {
            Access access = check();
            appendCheck(report, "Первая проверка shell", access);
            progress.accept(report.toString());
            // Show the shell result before arming any authorization clicks.
            if (access != Access.TRUSTED && clicker) {
                report.append("Очередь: пока НЕИЗВЕСТНО — нет shell для чтения журнала.\n");
                PromptClicker.arm();
                report.append("\n2. Ищу доступное службе окно ADB и нажимаю «Разрешить»…\n");
                progress.accept(report.toString());
                long deadline = android.os.SystemClock.uptimeMillis() + CLICK_WAIT_MS;
                int observedClicks = 0;
                try {
                    while (access != Access.TRUSTED) {
                        long remaining = deadline - android.os.SystemClock.uptimeMillis();
                        if (remaining <= 0 || observedClicks >= PromptDecision.MAX_CLICKS
                                || !waitForClick(observedClicks, remaining)) {
                            break;
                        }
                        observedClicks = PromptClicker.clicks();
                        access = check();
                        appendCheck(report, "Shell после нажатий " + observedClicks, access);
                        progress.accept(report.toString() + PromptClicker.diagnostics());
                    }
                } finally {
                    PromptClicker.disarm();
                }
                report.append(PromptClicker.diagnostics());
                access = check();
                appendCheck(report, "Shell после нажатий", access);
                progress.accept(report.toString());
            } else if (access != Access.TRUSTED) {
                report.append("Без службы нажатий окно не нажать, "
                        + "а новый запрос этот ключ не отправляет.\n");
                report.append("Включите службу кнопкой выше и откройте спасатель снова.\n");
            }
            if (access == Access.TRUSTED) {
                report.append("\n3. Читаю журнал очереди…\n");
                progress.accept(report.toString());
                drain(report, progress);
            } else {
                report.append("Очередь: НЕИЗВЕСТНО — без shell журнал недоступен.\n");
            }
            PromptClicker.disarm();
            Access after = check();
            report.append('\n');
            appendCheck(report, "Итоговая повторная проверка shell", after);
            if (clicker) {
                report.append("\nНажатия за этот запуск:\n").append(PromptClicker.diagnostics());
            } else {
                report.append("Нажатия: не выполнялись, служба выключена.\n");
            }
            report.append("Наличие shell само по себе не доказывает пустую очередь.\n");
            progress.accept(report.toString());
        } catch (InterruptedException interrupted) {
            report.append("\nОстановлено.\n");
            Thread.currentThread().interrupt();
        } finally {
            PromptClicker.disarm();
        }
        return report.toString();
    }

    private void drain(StringBuilder report, Consumer<String> progress) throws InterruptedException {
        String log;
        try {
            log = shell(QueueDrain.LOGCAT);
        } catch (Exception error) {
            report.append("Очередь: НЕИЗВЕСТНО. Журнал не прочитан: ")
                    .append(failure(error)).append('\n');
            progress.accept(report.toString());
            return;
        }
        appendQueue(report, "Очередь до разрешений", log);
        appendTail(report, log, 6);
        progress.accept(report.toString());
        String lastAttempt = null;
        int allows = 0;
        while (QueueDrain.pending(log) && allows < QueueDrain.MAX_ALLOWS) {
            String key = QueueDrain.publicKey(log, "");
            if (key == null) {
                key = keyFromDump(report);
            }
            String command = QueueDrain.allowCommand(key);
            String request = QueueDrain.requestToken(log);
            String attempt = request != null ? request : key;
            if (command == null || Objects.equals(lastAttempt, attempt)) {
                int before = PromptClicker.clicks();
                report.append(command == null
                        ? "Ключ текущего запроса не найден; жду нажатие службы.\n"
                        : "Новый запрос после разрешения пока не подтверждён; жду службу.\n");
                progress.accept(report.toString());
                boolean clicked = false;
                if (PromptClicker.enabled(context)) {
                    PromptClicker.arm();
                    try {
                        clicked = waitForClick(before, RETRY_WAIT_MS);
                    } finally {
                        PromptClicker.disarm();
                    }
                }
                if (clicked) {
                    String updated = waitForQueueChange(log, report, progress);
                    if (updated != null && !updated.equals(log)) {
                        log = updated;
                        continue;
                    }
                }
                report.append(command == null
                        ? "Ключ текущего запроса в журнале не найден.\n"
                        : "Продвижение очереди не подтверждено; останавливаюсь.\n");
                break;
            }
            try {
                String output = shell(command);
                lastAttempt = attempt;
                allows++;
                boolean accepted = QueueDrain.binderAccepted(output);
                report.append(allows).append(") allowDebugging: ")
                        .append(accepted ? "Binder принял вызов; проверяю журнал.\n"
                                : "успешный ответ Binder не получен.\n");
                appendTail(report, output, 4);
                progress.accept(report.toString());
                if (!accepted) {
                    break;
                }
            } catch (Exception error) {
                report.append("allow не выполнен: ").append(failure(error)).append('\n');
                break;
            }
            log = waitForQueueChange(log, report, progress);
            appendQueue(report, "После разрешения " + allows, log);
            progress.accept(report.toString());
        }
        if (allows >= QueueDrain.MAX_ALLOWS && QueueDrain.pending(log)) {
            report.append("Остановлено на ").append(QueueDrain.MAX_ALLOWS)
                    .append(" вызовах allowDebugging, журнал ещё показывает ожидающий запрос.\n");
        }
        // A new reading is required even if the loop did not run or had to stop.
        log = reread(report);
        appendQueue(report, "Очередь после", log);
        appendTail(report, log, 6);
        progress.accept(report.toString());
    }

    private String waitForQueueChange(
            String previous, StringBuilder report, Consumer<String> progress)
            throws InterruptedException {
        String previousRequest = QueueDrain.requestToken(previous);
        long deadline = android.os.SystemClock.uptimeMillis() + RETRY_WAIT_MS;
        String updated;
        do {
            Thread.sleep(250);
            updated = reread(report);
            String currentRequest = QueueDrain.requestToken(updated);
            if (updated == null || QueueDrain.state(updated) == QueueDrain.State.DRAINED
                    || (currentRequest != null && !Objects.equals(previousRequest, currentRequest))) {
                return updated;
            }
            progress.accept(report.toString() + "Жду подтверждение продвижения очереди…\n");
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        return updated;
    }

    private static void appendQueue(StringBuilder report, String label, String log) {
        report.append(label).append(" (последнее событие журнала): ");
        switch (QueueDrain.state(log)) {
            case PENDING:
                report.append("ЕСТЬ ожидающий запрос; размер очереди неизвестен.\n");
                break;
            case DRAINED:
                report.append("сообщение «no prompts to send» — очередь была пуста.\n");
                break;
            default:
                report.append("НЕИЗВЕСТНО; отсутствие сообщений не означает пустую очередь.\n");
        }
    }

    private String keyFromDump(StringBuilder report) {
        try {
            return QueueDrain.publicKey("", shell(QueueDrain.DUMP_KEYS));
        } catch (Exception error) {
            report.append("dumpsys не прочитан: ").append(failure(error)).append('\n');
            return null;
        }
    }

    private String reread(StringBuilder report) {
        try {
            return shell(QueueDrain.LOGCAT);
        } catch (Exception error) {
            report.append("Журнал не перечитан: ").append(failure(error)).append('\n');
            return null;
        }
    }

    private Access check() {
        lastFailure = "";
        try {
            String output = client.shell("printf " + CHECK_MARKER);
            if (!output.contains(CHECK_MARKER)) {
                lastFailure = "Shell не вернул контрольную строку.";
            }
            return output.contains(CHECK_MARKER) ? Access.TRUSTED : Access.ERROR;
        } catch (Exception error) {
            lastFailure = failure(error);
            return classify(error);
        }
    }

    private void appendCheck(StringBuilder report, String label, Access access) {
        report.append(label).append(": ")
                .append(access == Access.TRUSTED ? "ДА — " : "НЕТ — ")
                .append(describe(access)).append('\n');
        if (!lastFailure.isEmpty()) {
            report.append("Причина: ").append(lastFailure).append('\n');
        }
    }

    private String shell(String command) throws Exception {
        return client.shell(command, SHELL_TIMEOUT_MS);
    }

    private boolean waitForClick(int clicksBefore, long budgetMs) throws InterruptedException {
        long deadline = android.os.SystemClock.uptimeMillis() + budgetMs;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (PromptClicker.clicks() > clicksBefore) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            Thread.sleep(400);
        }
        return PromptClicker.clicks() > clicksBefore;
    }

    private String systemSwitch() {
        try {
            int raw = Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED);
            return raw != 0 ? "включена (" + raw + ")" : "ВЫКЛЮЧЕНА (0)";
        } catch (Exception ignored) {
            return "прочитать не удалось";
        }
    }

    private static String describe(Access access) {
        switch (access) {
            case TRUSTED:
                return "shell есть";
            case AUTHORIZATION_REQUIRED:
                return "adbd отвечает, этот ключ ещё не доверен";
            case UNAVAILABLE:
                return "локальный adbd не отвечает";
            default:
                return "проверка не удалась";
        }
    }

    private static String failure(Exception error) {
        String message = error.getMessage();
        return QueueDrain.redact(error.getClass().getSimpleName()
                + (message == null ? "" : " " + message));
    }

    private static void appendTail(StringBuilder report, String text, int lines) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String[] all = QueueDrain.redact(text).split("\n");
        int from = Math.max(0, all.length - lines);
        for (int i = from; i < all.length; i++) {
            if (!all[i].isEmpty()) {
                report.append("  ").append(all[i]).append('\n');
            }
        }
    }

    static Access classify(Throwable error) {
        Access unavailable = null;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof LocalAdbClient.AuthorizationRequiredException) {
                return Access.AUTHORIZATION_REQUIRED;
            }
            String message = current.getMessage();
            if (message != null && message.contains("ADB authorization pending")) {
                return Access.AUTHORIZATION_REQUIRED;
            }
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof NoRouteToHostException) {
                unavailable = Access.UNAVAILABLE;
            } else if (current instanceof IOException
                    && message != null
                    && message.toLowerCase(Locale.ROOT).contains("connection refused")) {
                unavailable = Access.UNAVAILABLE;
            }
        }
        return unavailable != null ? unavailable : Access.ERROR;
    }
}
