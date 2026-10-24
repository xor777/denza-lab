package dev.denza.adasplayer.probe;

import android.os.Process;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stock {@code SETTING_MPC_IFC_NAVI_ST_SET} side channel (device 1023, {@code 0x4CA0004B}),
 * written by the stock through {@code BydAutoSettingProxy.setEventValue}. Reached with the same
 * {@code service call autoservice} transactions {@code HudNativeSpeedLimit} uses for device 1023
 * (get = 5, set = 6). That device needs a {@code BYDAUTO_*} permission the app UID does not hold,
 * so this channel is {@link #enabled()} only when the process runs as the shell UID (app_process);
 * for an ordinary app-UID launch the H6 step is left disabled, with no invented path. Reads before
 * writing so the vendor setting (which keeps state) can be restored.
 */
final class NaviStShell implements NaviStChannel {
    private static final int DEVICE_SETTING = 1023;
    private static final int NAVI_ST_FID = 0x4CA0004B;     // SETTING_MPC_IFC_NAVI_ST_SET
    private static final int TX_GET = 5, TX_SET = 6;
    private static final int SHELL_UID = 2000;
    private static final Pattern PARCEL =
            Pattern.compile("Result:\\s*Parcel\\(([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})");

    public boolean enabled() { return Process.myUid() == SHELL_UID; }

    public String reason() {
        return enabled() ? "shell UID can write device 1023"
                : "app UID lacks the BYDAUTO setting permission for device 1023; run as shell to enable H6";
    }

    public Integer read() {
        String out = exec("service call autoservice " + TX_GET + " i32 " + DEVICE_SETTING + " i32 " + NAVI_ST_FID);
        if (out == null) return null;
        Matcher m = PARCEL.matcher(out);
        if (!m.find() || !m.group(1).equals("00000000")) return null;
        return (int) Long.parseLong(m.group(2), 16);
    }

    public boolean write(int value) {
        String out = exec("service call autoservice " + TX_SET + " i32 " + DEVICE_SETTING
                + " i32 " + NAVI_ST_FID + " i32 " + value + " null");
        return out != null && PARCEL.matcher(out).find();
    }

    private static String exec(String command) {
        try {
            java.lang.Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            process.waitFor();
            return sb.toString();
        } catch (Exception failure) {
            return null;
        }
    }
}
