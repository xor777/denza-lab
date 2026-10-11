package dev.denza.dipilotkey.probe;

import android.content.Context;
import android.provider.Settings;
import dev.denza.disharebridge.LocalAdbClient;

/** Device operations kept separate so the complete rescue can run against recorded scenarios. */
interface RescueDevice extends DenzaKeyRescue.Device {
    String shell(String command, int timeoutMs) throws Exception;
    String systemSwitch();
    boolean clickerEnabled();
    boolean clickerConnected();
    void enableClicker() throws Exception;
    void resetClicks();
    void armClicks(long budgetMs);
    void disarmClicks();
    int clicks();
    String clickDiagnostics();
    String windowDiagnostics();
    long now();
    void pause(long millis) throws InterruptedException;
}

final class AndroidRescueDevice implements RescueDevice {
    private final Context context;
    private final LocalAdbClient client;

    AndroidRescueDevice(Context context) throws Exception {
        this.context = context.getApplicationContext();
        AdbKeyFile.install(this.context);
        client = new LocalAdbClient(this.context, DipilotIdentity.COMMENT,
                LocalAdbClient.AuthorizationPolicy.PASSIVE);
    }

    @Override public String shell(String command, int timeoutMs) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        return client.shell(command, timeoutMs);
    }
    @Override public String systemSwitch() {
        try {
            int raw = Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED);
            return raw != 0 ? "включена (" + raw + ")" : "ВЫКЛЮЧЕНА (0)";
        } catch (Exception ignored) {
            return "прочитать не удалось";
        }
    }
    @Override public boolean clickerEnabled() { return PromptClicker.enabled(context); }
    @Override public boolean clickerConnected() { return PromptClicker.connected(); }
    @Override public void enableClicker() throws Exception {
        AccessibilitySetup.enable(command -> shell(command, 8000));
    }
    @Override public void resetClicks() { PromptClicker.resetDiagnostics(); }
    @Override public void armClicks(long budgetMs) { PromptClicker.arm(budgetMs); }
    @Override public void disarmClicks() { PromptClicker.disarm(); }
    @Override public int clicks() { return PromptClicker.clicks(); }
    @Override public String clickDiagnostics() { return PromptClicker.diagnostics(); }
    @Override public String windowDiagnostics() { return PromptClicker.windowDiagnostics(); }
    @Override public DenzaKeyRescue.Request requestPublicKey(String key,
            DenzaKeyRescue.BeforeSend beforeSend) throws Exception {
        return PublicKeyRequest.open(key, beforeSend);
    }
    @Override public boolean targetRequestAttempted(String fingerprint) {
        return context.getSharedPreferences("denza_key_rescue", Context.MODE_PRIVATE)
                .getBoolean("request_" + fingerprint, false);
    }
    @Override public void rememberTargetRequest(String fingerprint) throws Exception {
        if (!context.getSharedPreferences("denza_key_rescue", Context.MODE_PRIVATE).edit()
                .putBoolean("request_" + fingerprint, true).commit()) {
            throw new java.io.IOException("Не удалось сохранить отметку запроса");
        }
    }
    @Override public String verifyTargetKey() throws Exception {
        return shell(TargetIdentityCheck.command(context.getApplicationInfo().sourceDir), 20000);
    }
    @Override public void enableAdbOnly() throws Exception {
        if (shell("settings get global adb_enabled", 4000).trim().equals("0")) {
            shell("settings put global adb_enabled 1", 4000);
        }
    }
    @Override public String grantWritableKey(DenzaPublicKey key) throws Exception {
        return shell(writableGrantCommand(key), 4000);
    }
    static String writableGrantCommand(DenzaPublicKey key) {
        return "f=/data/misc/adb/adb_keys; if [ -f \"$f\" ] && [ -r \"$f\" ] && [ -w \"$f\" ]; then "
                + "if grep -q -F " + DenzaPublicKey.quote(key.blob) + " \"$f\"; then echo TARGET_KEY_ALREADY; "
                + "elif [ \"$?\" = 1 ]; then if printf '\\n%s\\n' " + DenzaPublicKey.quote(key.payload)
                + " >>\"$f\"; then echo TARGET_KEY_ADDED; else echo TARGET_KEY_WRITE_FAILED; fi; "
                + "else echo NOT_WRITABLE; fi; else echo NOT_WRITABLE; fi";
    }
    @Override public long now() { return android.os.SystemClock.uptimeMillis(); }
    @Override public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
}
