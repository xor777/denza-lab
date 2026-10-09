package dev.denza.dipilotkey.probe;

import android.content.Context;
import android.provider.Settings;
import dev.denza.disharebridge.LocalAdbClient;

/** Device operations kept separate so the complete rescue can run against recorded scenarios. */
interface RescueDevice {
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
    @Override public long now() { return android.os.SystemClock.uptimeMillis(); }
    @Override public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
}
