package dev.denza.dipilotkey.probe;

import java.io.Closeable;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** A finite, autonomous attempt for the installed identity, followed by signed shell/reconnect. */
final class DenzaKeyRescue {
    interface Request extends Closeable { }
    interface BeforeSend { void run() throws Exception; }
    interface Device {
        String shell(String command, int timeoutMs) throws Exception;
        Request requestPublicKey(String key, BeforeSend beforeSend) throws Exception;
        boolean targetRequestAttempted(String fingerprint);
        void rememberTargetRequest(String fingerprint) throws Exception;
        default String verifyTargetKey() throws Exception { return "DENZA_TARGET_CHECK=UNSUPPORTED"; }
        default String grantWritableKey(DenzaPublicKey key) throws Exception { return "NOT_WRITABLE"; }
        default void enableAdbOnly() throws Exception { }
        long now();
        void pause(long millis) throws InterruptedException;
    }
    private final Device device;
    private final int approvalBudget;
    DenzaKeyRescue(Device device) { this(device, QueueDrain.MAX_ALLOWS); }
    DenzaKeyRescue(Device device, int approvalBudget) {
        this.device = device; this.approvalBudget = Math.max(0, approvalBudget);
    }

    void run(StringBuilder report, Consumer<String> progress) throws InterruptedException {
        report.append("\n6. Восстанавливаю доступ существующего ключа Denza Apps…\n");
        progress.accept(report.toString());
        Request request = null;
        try {
            String output = shell(DenzaPublicKey.READ);
            DenzaPublicKey key;
            try { key = DenzaPublicKey.read(output); }
            catch (Exception unavailable) {
                status(report, progress, "ключ недоступен");
                report.append("run-as не вернул публичный ключ: ")
                        .append(output != null && output.contains("not debuggable") ? "сборка Denza Apps не debuggable"
                                : output != null && output.contains("unknown package") ? "пакет не найден"
                                : "файл/формат публичного ключа недоступен").append(".\n");
                return;
            }
            report.append("Публичный ключ прочитан. Отпечаток Denza Apps: ").append(key.fingerprint).append('\n');
            long deadline = device.now() + 45000;
            AdbState state = state();
            report.append(state.describe(key));
            if (verify(key, report, progress)) return;
            device.enableAdbOnly(); // Turns an explicitly disabled switch on; never toggles it off.
            if (!key.fingerprint.equals(state.fingerprint) && approvalBudget > 0) {
                if (device.targetRequestAttempted(key.fingerprint)) {
                    report.append("Попытка отправки этого ключа уже была; повторно очередь не пополняю.\n");
                } else {
                    request = device.requestPublicKey(key.payload,
                            () -> device.rememberTargetRequest(key.fingerprint));
                    report.append("Отправлен один запрос публичного ключа Denza Apps.\n");
                }
            }
            Set<String> answered = new HashSet<>();
            device.pause(400);
            int allows = 0;
            boolean unknownTargetTried = false;
            while (device.now() < deadline && allows < approvalBudget) {
                state = state();
                String log = shell(QueueDrain.LOGCAT);
                String candidate = null;
                boolean checked = false;
                String token = QueueDrain.requestToken(log);
                String attempt = state.fingerprint == null ? "target-without-dump" : state.fingerprint;
                if (token != null) attempt += ":" + token;
                if (key.fingerprint.equals(state.fingerprint)) {
                    candidate = key.payload;
                } else if (state.fingerprint != null) {
                    candidate = state.matchingKey(log);
                } else if (!unknownTargetTried) {
                    // The service itself checks fingerprints; a parser/missing dump must not
                    // prevent one exact-target attempt. Never approve a guessed preceding key.
                    candidate = key.payload;
                    unknownTargetTried = true;
                    report.append("Отпечаток системы недоступен; один вызов для точного ключа Denza Apps.\n");
                }
                if (candidate != null && answered.add(attempt)) {
                    String command = QueueDrain.allowCommand(candidate);
                    if (command != null) {
                        boolean target = candidate.startsWith(key.blob);
                        report.append(target ? "Разрешаю ключ Denza Apps.\n"
                                : "Разрешаю предшествующий ключ с совпавшим отпечатком системы.\n");
                        progress.accept(report.toString());
                        allows++;
                        String response = shell(command);
                        report.append("allowDebugging ").append(allows).append(": ")
                                .append(QueueDrain.binderAccepted(response) ? "Binder принял вызов.\n"
                                        : "успешный ответ Binder не получен.\n");
                        device.pause(800);
                        if (target) {
                            state = state();
                            checked = true;
                            if (verify(key, report, progress)) {
                                report.append(state.describe(key));
                                return;
                            }
                            // A rejected target call is not repeated without a fresh request token.
                        }
                    }
                }
                AdbState updated = state();
                if (!sameFingerprint(state, updated)) report.append(updated.describe(key));
                if (!checked && Boolean.TRUE.equals(updated.stored(key)) && verify(key, report, progress)) return;
                // With no identifiable preceding key, further polling cannot legitimately
                // approve it. A target attempt above still handles unavailable dump formats.
                if (updated.fingerprint != null && !updated.fingerprint.equals(key.fingerprint)
                        && updated.matchingKey(log) == null) {
                    report.append("Предшествующий отпечаток известен, его публичный ключ недоступен.\n");
                    break;
                }
                if (candidate != null && answered.contains(attempt)
                        && sameFingerprint(state, updated) && QueueDrain.requestToken(log) == null) break;
                state = updated;
                progress.accept(report.toString() + "Жду продвижения разрешений…\n");
                device.pause(800);
            }
            state = state();
            report.append(state.describe(key));
            if (allows >= approvalBudget) report.append("Достигнут общий предел Binder-разрешений за запуск.\n");
            report.append("Проверяю возможность добавить этот публичный ключ в существующий файл доверия.\n");
            String grant = device.grantWritableKey(key);
            report.append("Файл доверия: ").append(grant.contains("TARGET_KEY_ADDED") ? "ключ добавлен.\n"
                    : grant.contains("TARGET_KEY_ALREADY") ? "ключ уже присутствует.\n"
                    : grant.contains("TARGET_KEY_WRITE_FAILED") ? "ошибка записи; добавление не подтверждено.\n"
                    : "запись недоступна; файл не менялся.\n");
            if (verify(key, report, progress)) return;
            status(report, progress, Boolean.FALSE.equals(state.threadPresent)
                    ? "нет потока разрешений ADB" : "доступ не восстановлен");
            report.append("Автоматические разрешения завершены; ключи и работающий доступ Dipilot сохранены.\n");
        } catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception failed) {
            status(report, progress, "проверка не удалась");
            report.append("Сбой Denza Apps: ").append(failed.getClass().getSimpleName()).append('\n');
            // Even failed authorization orchestration must leave a conclusive signed check.
            try {
                DenzaPublicKey key = DenzaPublicKey.read(shell(DenzaPublicKey.READ));
                verify(key, report, progress);
            } catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception ignored) { report.append("Итоговая проверка ключом Denza Apps недоступна.\n"); }
        } finally {
            if (request != null) try { request.close(); }
            catch (Exception ignored) { report.append("Ошибка закрытия сокета запроса.\n"); }
            progress.accept(report.toString());
        }
    }

    private boolean verify(DenzaPublicKey key, StringBuilder report, Consumer<String> progress) throws Exception {
        progress.accept(report.toString() + "Проверяю shell и новое подключение ключом Denza Apps…\n");
        String result = device.verifyTargetKey();
        String fp = value(result, "DENZA_TARGET_FP");
        String check = value(result, "DENZA_TARGET_CHECK");
        boolean trusted = key.fingerprint.equals(fp) && "TRUSTED".equals(check)
                && "TRUSTED".equals(value(result, "DENZA_TARGET_FIRST"))
                && "TRUSTED".equals(value(result, "DENZA_TARGET_SECOND"));
        report.append("Shell ключом Denza Apps: ").append(trusted ? "ДА; новое подключение: ДА.\n"
                : "НЕ подтверждён — " + describeCheck(check) + ".\n");
        if (!trusted) {
            report.append("Первая проверка UID: ").append(describeCheck(value(result, "DENZA_TARGET_FIRST")))
                    .append("; повторная: ").append(describeCheck(value(result, "DENZA_TARGET_SECOND")))
                    .append(".\n");
            String error = value(result, "DENZA_TARGET_ERROR");
            if (error != null && error.matches("[A-Za-z0-9_$]{1,80}")) {
                report.append("Сбой проверочного процесса: ").append(error).append('\n');
            }
        }
        if (fp != null && !key.fingerprint.equals(fp)) {
            report.append("Отпечаток внутри UID Denza Apps изменился; идентичность не заменялась пробником.\n");
            throw new IllegalStateException("Identity changed during rescue");
        }
        if (trusted) status(report, progress, "shell и повторное подключение: ДА");
        return trusted;
    }

    private AdbState state() throws Exception {
        String adb = shell(DenzaPublicKey.DUMP);
        AdbState primary = AdbState.read(adb);
        if (primary.recognized) return primary;
        // Some firmware keeps authorization under the older USB service.
        return AdbState.read(adb + "\n" + shell("dumpsys usb"));
    }

    private static boolean sameFingerprint(AdbState a, AdbState b) {
        return java.util.Objects.equals(a.fingerprint, b.fingerprint);
    }

    static String value(String output, String name) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            if (line.startsWith(name + "=")) return line.substring(name.length() + 1).trim();
        }
        return null;
    }

    private static String describeCheck(String check) {
        if ("TRUSTED".equals(check)) return "ДА — подписанный shell";
        if ("NOT_RUN".equals(check)) return "не запускалась после отказа первой проверки";
        if ("AUTH_REQUIRED".equals(check)) return "подпись существующим ключом отклонена";
        if ("UNAVAILABLE".equals(check)) return "adbd недоступен из UID Denza Apps";
        if ("NO_RSA_CHALLENGE".equals(check)) return "adbd не запросил подпись RSA";
        if ("TIMEOUT".equals(check)) return "истёк срок проверки";
        if ("SHELL_FAILED".equals(check)) return "shell не вернул контрольную строку";
        if ("PROTOCOL_ERROR".equals(check)) return "неожиданный ответ adbd";
        return "проверка внутри UID недоступна/ошибка";
    }

    private String shell(String command) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        return device.shell(command, 4000);
    }

    private static void status(StringBuilder report, Consumer<String> progress, String value) {
        report.append("Denza Apps: ").append(value).append('\n');
        progress.accept(report.toString());
    }
}
