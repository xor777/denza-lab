package dev.denza.hud.probe;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Private-file acceptance harness, inert on the IVI. No exported receiver, socket, or vehicle command. */
final class HudEmulatorControl {
    private final boolean enabled = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    private final Context context;
    private final Reflect api;
    private long pollAt, sequence;
    private String lastCommand = "";
    HudEmulatorControl(Context context, Reflect api) { this.context = context; this.api = api; }

    void poll(Object guidance, long now) {
        if (!enabled || now < pollAt) return;
        pollAt = now + 500;
        File command = new File(context.getFilesDir(), "denza-hud-command.json");
        if (!command.isFile()) return;
        try {
            byte[] bytes = new byte[(int) Math.min(4096, command.length())];
            try (FileInputStream in = new FileInputStream(command)) {
                if (in.read(bytes) != bytes.length) return;
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            if (raw.equals(lastCommand)) return;
            lastCommand = raw;
            JSONObject value = new JSONObject(raw);
            if (!command.delete()) throw new IOException("Cannot consume emulator command");
            String action = value.getString("action");
            if (action.equals("simulate")) {
                if (api.call(guidance, "route") == null
                        || !Boolean.TRUE.equals(api.call(guidance, "isGuidanceResumed"))
                        || !api.call(guidance, "getSimulationStatus").toString().equals("IDLE"))
                    throw new IllegalStateException("Select and start a route; simulation must be idle");
                api.call(guidance, "startSimulationWithExistingRoute");
            } else if (action.equals("stop")) {
                // stopSimulation() asserts natively when already idle. Cleanup must be idempotent.
                if (!api.call(guidance, "getSimulationStatus").toString().equals("IDLE"))
                    api.call(guidance, "stopSimulation");
                if (api.call(guidance, "route") != null) api.call(guidance, "stop");
            } else if (action.equals("background")) {
                api.call(api.call(guidance, "bgConfigurator"), "setBackgroundOnRouteEnabled", value.getBoolean("enabled"));
            } else throw new IllegalArgumentException("Unknown emulator action");
            Log.i("DenzaYandexHud", "emulator action=" + action);
        } catch (Exception error) { Log.w("DenzaYandexHud", "emulator action failed", error); }
    }

    void frame(byte[] png, String mode, boolean visible, boolean background, boolean resumed) {
        if (!enabled) return;
        try {
            File directory = new File(context.getFilesDir(), "denza-hud-evidence");
            if (!directory.isDirectory() && !directory.mkdir()) return;
            // Bounded 60-frame ring for emulator acceptance; never stores locations or routes in logs.
            long index = sequence++;
            try (FileOutputStream out = new FileOutputStream(new File(directory, String.format("frame-%02d.png", index % 60)))) { out.write(png); }
            JSONObject info = new JSONObject().put("sequence", index).put("mode", mode).put("visible", visible)
                    .put("background", background).put("resumed", resumed).put("uptime", android.os.SystemClock.elapsedRealtime());
            try (FileOutputStream out = new FileOutputStream(new File(directory, "latest.json"))) { out.write(info.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception error) { Log.w("DenzaYandexHud", "emulator evidence failed", error); }
    }
}
