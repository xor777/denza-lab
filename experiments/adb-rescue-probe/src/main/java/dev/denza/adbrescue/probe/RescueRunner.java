package dev.denza.adbrescue.probe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

import dev.denza.disharebridge.LocalAdbClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A second ADB identity, for the one case the product's own recovery cannot cover.
 *
 * <p>Denza Apps owns exactly one ADB key and one prompt slot, and it deliberately spends the slot
 * once. On a car where the prompt never renders, that leaves nobody able to ask a question: the
 * app has spent its attempt, the owner sees an instruction they cannot carry out, and there is no
 * trusted shell to look at the queue with. This probe is the second asker. It is a separate
 * application with a key of its own, so its request is independent of whatever Denza Apps already
 * spent, and its answer is a fact about the vehicle rather than about one app's state:
 *
 * <ul>
 *   <li>a prompt appears for this key - the authorization path works, and Denza Apps' problem is
 *       its own latch or its own key;</li>
 *   <li>no prompt appears for this key either - the path itself is broken, and the queue is the
 *       first thing to look at.</li>
 * </ul>
 *
 * <p>The second case is why the probe is worth installing at all: once <em>this</em> key is
 * trusted, there is a shell, and a shell can read the auth log and drain the queue that is holding
 * the product's own request. Until then it can only read what any app may read.
 */
final class RescueRunner {
    static final String DENZA_APPS = "dev.denza.apps";
    private static final String DENZA_APPS_LAUNCHER =
            DENZA_APPS + "/" + DENZA_APPS + ".DenzaLauncherActivity";
    private static final String CHECK_MARKER = "DENZA_RESCUE_OK";

    /** The signature of the documented stuck queue, as adbd itself writes it. */
    private static final String PENDING_MARKER = "prompt currently pending";
    private static final String DRAINED_MARKER = "no prompts to send";

    /** One rejection per observation, and never more than this many. */
    private static final int MAX_DRAIN_CALLS = 5;

    private static final String[] INTERESTING_PROPERTIES = {
            // The BYD switch. SystemUI's dialog auto-approves every key when this reads 1,
            // and draws nothing at all - which is what "ADB unlocked" means on this platform.
            "persist.sys.factory.version.flag.config",
            "ro.adb.secure",
            "ro.debuggable",
            "service.adb.tcp.port",
            "service.adb.tls.port",
            "persist.adb.tls_server.enable",
            "init.svc.adbd",
            "sys.usb.state",
            "persist.sys.usb.config",
            "ro.build.version.release",
            "ro.build.display.id",
            "ro.product.model",
    };

    private static final String LOGCAT_AUTH =
            "logcat -d -t 400 -s adbd_auth:* libadbd_auth:* AdbDebuggingManager:* "
                    + "UsbDebuggingActivity:*";

    enum Access {
        /** A framed answer came back: this key is trusted and there is a shell. */
        TRUSTED,
        /** adbd answered and refused the key. A prompt is the only way forward. */
        AUTHORIZATION_REQUIRED,
        /** Nothing answered on the endpoint. No key was offered to anything. */
        UNAVAILABLE,
        /** Something else went wrong; the text says what. */
        ERROR,
    }

    private final Context context;
    private final LocalAdbClient passive;
    private final LocalAdbClient requester;

    private int requestsSent;

    RescueRunner(Context context) {
        this.context = context.getApplicationContext();
        // Two clients over one key: the checking one may never enqueue anything, and only the
        // explicit button is allowed to spend a queue slot.
        this.passive = new LocalAdbClient(
                this.context, "denza-rescue@probe", LocalAdbClient.AuthorizationPolicy.PASSIVE);
        this.requester = new LocalAdbClient(
                this.context, "denza-rescue@probe", LocalAdbClient.AuthorizationPolicy.AUTOMATIC);
    }

    // --- readings any application may take, with no ADB and no permission -----------------

    /**
     * Android's own debugging switch.
     *
     * <p>A car with the switch off still answers on the local endpoint and still refuses the key,
     * which looks exactly like an untrusted key on a working car - and sends the owner off to
     * approve a prompt that will never be drawn. Reading the flag is what separates the two.
     */
    String systemSwitch() {
        try {
            int raw = Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.ADB_ENABLED);
            return raw != 0 ? "включена (adb_enabled=" + raw + ")" : "ВЫКЛЮЧЕНА (adb_enabled=0)";
        } catch (Exception ignored) {
            // A row that was never written throws, and so can a failing settings provider. Neither
            // is evidence of an off switch, and it must never be reported as one.
            return "прочитать не удалось";
        }
    }

    /**
     * Whether wireless debugging has ever been turned on, and whether it is on now.
     *
     * <p>This matters because pairing by code is the one authorization path on this platform that
     * does not go through the dialog that is broken on the reported car: the code is drawn by
     * Settings itself, not by the confirmation component that never renders. It is the only
     * remaining lead that is a different mechanism rather than another attempt at the same one.
     */
    String wifiDebugging() {
        try {
            // Not a public constant, but the lookup is by name and any app may read the row.
            int raw = Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled");
            return raw != 0 ? "включена (adb_wifi_enabled=" + raw + ")"
                    : "выключена (adb_wifi_enabled=0)";
        } catch (Exception ignored) {
            return "никогда не включалась (строки adb_wifi_enabled нет)";
        }
    }

    /** The addresses a laptop would have to reach to pair with this car. */
    String localAddresses() {
        StringBuilder found = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                        if (found.length() > 0) {
                            found.append(", ");
                        }
                        found.append(address.getHostAddress())
                                .append(" (").append(networkInterface.getName()).append(')');
                    }
                }
            }
        } catch (SocketException ignored) {
            // Reported as "none found"; the owner can read the address off the Wi-Fi screen.
        }
        return found.length() > 0 ? found.toString() : "не найдено (машина не в Wi-Fi?)";
    }

    Map<String, String> systemProperties() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : INTERESTING_PROPERTIES) {
            values.put(name, "?");
        }
        try {
            Process process = new ProcessBuilder("/system/bin/getprop")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // getprop prints `[name]: [value]`.
                    int split = line.indexOf("]: [");
                    if (!line.startsWith("[") || split < 0 || !line.endsWith("]")) {
                        continue;
                    }
                    String name = line.substring(1, split);
                    if (values.containsKey(name)) {
                        values.put(name, line.substring(split + 4, line.length() - 1));
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException ignored) {
            // Best effort: the placeholders already say the value was not read.
        }
        return values;
    }

    String denzaAppsDescription() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(DENZA_APPS, 0);
            return "установлено, версия " + info.versionName
                    + " (versionCode " + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return "НЕ УСТАНОВЛЕНО";
        } catch (Exception error) {
            return "прочитать не удалось (" + error.getClass().getSimpleName() + ")";
        }
    }

    String fingerprint() {
        try {
            return passive.publicKeyFingerprint();
        } catch (Exception error) {
            return "не удалось вычислить (" + error.getClass().getSimpleName() + ")";
        }
    }

    int requestsSent() {
        return requestsSent;
    }

    // --- the endpoint ---------------------------------------------------------------------

    /** Proves trust without ever offering the public key. */
    Access check() {
        try {
            String output = passive.shell("printf " + CHECK_MARKER);
            return output.contains(CHECK_MARKER) ? Access.TRUSTED : Access.ERROR;
        } catch (LocalAdbClient.AuthorizationRequiredException required) {
            return Access.AUTHORIZATION_REQUIRED;
        } catch (Exception error) {
            return isUnavailable(error) ? Access.UNAVAILABLE : Access.ERROR;
        }
    }

    String checkDetail(Access access) {
        switch (access) {
            case TRUSTED:
                return "ключ спасателя уже доверен, shell есть";
            case AUTHORIZATION_REQUIRED:
                return "adbd отвечает и отклоняет этот ключ - нужен системный запрос";
            case UNAVAILABLE:
                return "локальный adbd не отвечает (ключ никому не предлагался)";
            default:
                return "неожиданный ответ";
        }
    }

    /**
     * Submits this app's public key once.
     *
     * <p>This is the only call in the probe that puts anything into Android's prompt queue, and
     * every press costs one slot in it. The count is kept and shown rather than latched away: on a
     * car where nothing is drawn, a latch would leave the owner with no move at all, and the honest
     * trade is to say what a press costs and let them make it.
     */
    String requestOnce() {
        requestsSent++;
        try {
            LocalAdbClient.AuthorizationRequestResult result = requester.requestAuthorization();
            if (result == LocalAdbClient.AuthorizationRequestResult.ALREADY_AUTHORIZED) {
                return "ключ уже доверен - запрос не потребовался";
            }
            return "запрос отправлен (всего отправлено: " + requestsSent + ")."
                    + " Разрешите его на экране машины и нажмите «Проверить доступ»";
        } catch (Exception error) {
            return "отправка не подтверждена: " + error.getClass().getSimpleName();
        }
    }

    // --- what a trusted shell can do for Denza Apps ----------------------------------------

    /**
     * The repair, once there is a shell.
     *
     * <p>It does not touch Denza Apps' data. The product can already clear its own one-shot latch
     * from its own screen; what it cannot do is clear Android's prompt queue, which is what holds
     * its request. So this drains the queue - one rejection per reading of the log, never a loop -
     * and then restarts the app so it asks again on a car that can now answer.
     */
    String rescueDenzaApps() {
        StringBuilder log = new StringBuilder();
        try {
            String authLog = shellOrThrow(LOGCAT_AUTH);
            boolean pending = authLog.contains(PENDING_MARKER);
            log.append("Журнал авторизации: ")
                    .append(pending ? "есть зависший запрос" : "зависших запросов не видно")
                    .append('\n');
            append(log, "Последние строки", tail(authLog, 12));

            if (pending) {
                log.append("\nОтклоняю очередь по одному запросу:\n");
                for (int attempt = 1; attempt <= MAX_DRAIN_CALLS; attempt++) {
                    shellOrThrow("service call adb 2");
                    String after = shellOrThrow(LOGCAT_AUTH);
                    boolean drained = after.contains(DRAINED_MARKER)
                            || !after.contains(PENDING_MARKER);
                    log.append("  ").append(attempt).append(") ")
                            .append(drained ? "очередь пуста" : "ещё есть ожидающие")
                            .append('\n');
                    if (drained) {
                        break;
                    }
                }
            }

            log.append('\n');
            append(log, "Состояние пакета", denzaAppsState());

            shellOrThrow("am force-stop " + DENZA_APPS);
            log.append("\nDenza Apps остановлено.\n");
            shellOrThrow("am start -n " + DENZA_APPS_LAUNCHER);
            log.append("Denza Apps запущено заново.\n");
            log.append("\nТеперь в самом Denza Apps: «Разрешить новую попытку», затем "
                    + "«Отправить один запрос» - и разрешите системный диалог.\n");
        } catch (Exception error) {
            log.append("\nПрервано: ").append(error.getClass().getSimpleName())
                    .append(' ').append(String.valueOf(error.getMessage())).append('\n');
        }
        return log.toString();
    }

    private String denzaAppsState() {
        try {
            return shellOrThrow(
                    "dumpsys package " + DENZA_APPS + " | grep -E 'versionName|versionCode|"
                            + "userId=|stopped=|enabled='").trim();
        } catch (Exception error) {
            return "не прочитано (" + error.getClass().getSimpleName() + ")";
        }
    }

    private String shellOrThrow(String command) throws Exception {
        return passive.shell(command, 8000);
    }

    // --- shared helpers ---------------------------------------------------------------------

    private static void append(StringBuilder target, String label, String body) {
        target.append(label).append(":\n");
        if (body == null || body.trim().isEmpty()) {
            target.append("  (пусто)\n");
            return;
        }
        for (String line : body.split("\n")) {
            target.append("  ").append(line).append('\n');
        }
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        int from = Math.max(0, all.length - lines);
        StringBuilder result = new StringBuilder();
        for (int i = from; i < all.length; i++) {
            result.append(all[i]).append('\n');
        }
        return result.toString();
    }

    static boolean isUnavailable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof NoRouteToHostException) {
                return true;
            }
            if (current instanceof IOException
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase().contains("connection refused")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one line at the top of the report: which of the four situations this car is in.
     *
     * <p>Kept free of Android so it can be unit-tested, because it is the sentence the owner will
     * read out over the phone and the only part of the report that draws a conclusion.
     */
    static String verdict(
            Access access, boolean switchOn, boolean switchReadable, String factoryFlag) {
        boolean factoryMode = "1".equals(factoryFlag);
        switch (access) {
            case TRUSTED:
                return "ДОСТУП ЕСТЬ. Можно чинить Denza Apps.";
            case AUTHORIZATION_REQUIRED:
                if (switchReadable && !switchOn) {
                    return "ОТЛАДКА ВЫКЛЮЧЕНА В СИСТЕМЕ. Запрос отправлять бесполезно: "
                            + "диалог не будет показан, пока её не включат в машине.";
                }
                // Both branches are established from this firmware's own SystemUI, not guessed.
                if (factoryMode) {
                    return "ЗАВОДСКОЙ РЕЖИМ ВКЛЮЧЁН, НО КЛЮЧ ВСЁ РАВНО ОТКЛОНЁН. "
                            + "Прошивка должна была разрешить его молча - значит, диалог "
                            + "вообще не запускается. Дальше только по логам с shell.";
                }
                return "МАШИНА НЕ РАЗБЛОКИРОВАНА. Заводской флаг выключен, "
                        + "а обычный диалог на этой машине не появляется. "
                        + "Нужен сервис: persist.sys.factory.version.flag.config = 1";
            case UNAVAILABLE:
                return "ЛОКАЛЬНЫЙ ADB НЕ ОТВЕЧАЕТ. Ни один ключ никуда не отправлялся.";
            default:
                return "НЕ УДАЛОСЬ ПРОВЕРИТЬ. Смотрите подробности ниже.";
        }
    }
}
