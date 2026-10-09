package dev.denza.dipilotkey.probe;

import android.content.Context;
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
    private static final long SERVICE_WAIT_MS = 5000;

    enum Access {
        TRUSTED,
        AUTHORIZATION_REQUIRED,
        UNAVAILABLE,
        ERROR
    }

    static final class Result {
        final String report;
        final boolean needsServiceSettings;

        Result(String report, boolean needsServiceSettings) {
            this.report = report;
            this.needsServiceSettings = needsServiceSettings;
        }
    }

    private final RescueDevice device;
    private String lastFailure = "";

    PortRescue(Context context) throws Exception {
        this(new AndroidRescueDevice(context));
    }

    PortRescue(RescueDevice device) {
        this.device = device;
    }

    String rescue() {
        return rescue(text -> { });
    }

    String rescue(Consumer<String> progress) {
        return run(progress).report;
    }

    Result run(Consumer<String> progress) {
        StringBuilder report = new StringBuilder();
        device.resetClicks();
        boolean needsSettings = false;
        try {
            report.append("Ключ Dipilot (").append(DipilotIdentity.COMMENT).append(")\n");
            report.append("Отпечаток: ").append(DipilotIdentity.fingerprint()).append('\n');
        } catch (Exception error) {
            report.append("Ключ не читается: ").append(error.getClass().getSimpleName()).append('\n');
        }
        report.append("adb_enabled: ").append(device.systemSwitch()).append('\n');
        report.append("Служба нажатий: ")
                .append(device.clickerEnabled() ? "включена" : "ВЫКЛЮЧЕНА")
                .append('\n');
        report.append("\n1. Открываю shell ключом Dipilot (пассивно)…\n");
        progress.accept(report.toString());
        try {
            Access access = check();
            appendCheck(report, "Первая проверка shell", access);
            progress.accept(report.toString());
            if (access == Access.TRUSTED) {
                String before = reread(report);
                appendQueue(report, "Очередь до поиска окон", before);
                appendTail(report, before, 6);
            } else {
                report.append("Очередь до поиска окон: НЕИЗВЕСТНО — без shell журнал недоступен.\n");
            }
            progress.accept(report.toString());
            if (prepareClicker(access, report, progress)) {
                // Queue logs may be absent or historical. Always search, even with a trusted key.
                access = scanWindows(access, report, progress);
                if (access == Access.TRUSTED) {
                    report.append("\n4. Проверяю очередь и разрешаю подтверждённые запросы…\n");
                    progress.accept(report.toString());
                    drain(report, progress);
                } else {
                    report.append("Очередь после: НЕИЗВЕСТНО — без shell журнал недоступен.\n");
                }
            } else {
                needsSettings = true;
                report.append("Нужно включить службу «Ключ Dipilot» в открывшихся настройках "
                        + "и вернуться. Проверка продолжится автоматически.\n");
            }
            device.disarmClicks();
            Access after = check();
            report.append('\n');
            appendCheck(report, "Итоговая повторная проверка shell", after);
            report.append("\nНажатия за этот запуск:\n").append(device.clickDiagnostics());
            if (needsSettings && after == Access.TRUSTED) {
                appendQueue(report, "Очередь после", reread(report));
            }
            report.append("Наличие shell само по себе не доказывает пустую очередь.\n");
            progress.accept(report.toString());
        } catch (InterruptedException interrupted) {
            report.append("\nОстановлено.\n");
            Thread.currentThread().interrupt();
        } finally {
            device.disarmClicks();
        }
        return new Result(report.toString(), needsSettings);
    }

    private boolean prepareClicker(Access access, StringBuilder report, Consumer<String> progress)
            throws InterruptedException {
        report.append("\n2. Подготавливаю службу нажатий…\n");
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        if (!device.clickerEnabled()) {
            if (access != Access.TRUSTED) {
                report.append("Без shell автоматически включить службу нельзя.\n");
                progress.accept(report.toString());
                return false;
            }
            report.append("Включаю службу через shell; сохраняю остальные службы.\n");
            progress.accept(report.toString());
            try {
                device.enableClicker();
                report.append("Настройки службы записаны.\n");
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception error) {
                report.append("Автоматическое включение не удалось: ")
                        .append(failure(error)).append('\n');
                progress.accept(report.toString());
                return false;
            }
        }
        long deadline = device.now() + SERVICE_WAIT_MS;
        while (!device.clickerConnected() && device.now() < deadline) {
            progress.accept(report.toString() + "Жду подключения службы…\n");
            device.pause(250);
        }
        boolean ready = device.clickerEnabled() && device.clickerConnected();
        report.append("Служба подключена: ").append(ready ? "ДА\n" : "НЕТ\n");
        progress.accept(report.toString());
        return ready;
    }

    private Access scanWindows(Access access, StringBuilder report, Consumer<String> progress)
            throws InterruptedException {
        report.append("\n3. Ищу окна ADB на всех доступных службе дисплеях (до 12 секунд)…\n");
        progress.accept(report.toString());
        long deadline = device.now() + CLICK_WAIT_MS;
        int observedClicks = device.clicks();
        device.armClicks(CLICK_WAIT_MS);
        try {
            while (device.now() < deadline && device.clicks() < PromptDecision.MAX_CLICKS) {
                progress.accept(report.toString() + device.clickDiagnostics());
                long remaining = deadline - device.now();
                if (remaining <= 0) {
                    break;
                }
                device.pause(Math.min(400, remaining));
                int current = device.clicks();
                if (current > observedClicks) {
                    observedClicks = current;
                    access = check();
                    appendCheck(report, "Shell после нажатий " + current, access);
                }
            }
        } finally {
            device.disarmClicks();
        }
        report.append("Поиск окон завершён.\n").append(device.clickDiagnostics());
        access = check();
        appendCheck(report, "Shell после поиска окон", access);
        progress.accept(report.toString());
        return access;
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
                int before = device.clicks();
                report.append(command == null
                        ? "Ключ текущего запроса не найден; жду нажатие службы.\n"
                        : "Новый запрос после разрешения пока не подтверждён; жду службу.\n");
                progress.accept(report.toString());
                boolean clicked = false;
                if (device.clickerConnected()) {
                    device.armClicks(RETRY_WAIT_MS);
                    try {
                        clicked = waitForClick(before, RETRY_WAIT_MS);
                    } finally {
                        device.disarmClicks();
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
            } catch (InterruptedException interrupted) {
                throw interrupted;
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
        long deadline = device.now() + RETRY_WAIT_MS;
        String updated;
        do {
            device.pause(250);
            updated = reread(report);
            String currentRequest = QueueDrain.requestToken(updated);
            if (updated == null || QueueDrain.state(updated) == QueueDrain.State.DRAINED
                    || (currentRequest != null && !Objects.equals(previousRequest, currentRequest))) {
                return updated;
            }
            progress.accept(report.toString() + "Жду подтверждение продвижения очереди…\n");
        } while (device.now() < deadline);
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

    private Access check() throws InterruptedException {
        lastFailure = "";
        try {
            String output = shell("printf " + CHECK_MARKER);
            if (!output.contains(CHECK_MARKER)) {
                lastFailure = "Shell не вернул контрольную строку.";
            }
            return output.contains(CHECK_MARKER) ? Access.TRUSTED : Access.ERROR;
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
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
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        return device.shell(command, SHELL_TIMEOUT_MS);
    }

    private boolean waitForClick(int clicksBefore, long budgetMs) throws InterruptedException {
        long deadline = device.now() + budgetMs;
        while (device.now() < deadline) {
            if (device.clicks() > clicksBefore) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            long remaining = deadline - device.now();
            if (remaining <= 0) {
                break;
            }
            device.pause(Math.min(400, remaining));
        }
        return device.clicks() > clicksBefore;
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
