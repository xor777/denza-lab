package dev.denza.tools;

import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Asks the firmware's own visualiser subsystem to switch the session-0 effect on.
 *
 * Why this exists (2026-08-27): the car's spectrum analyser drew silence over loud music because
 * the one Visualizer effect on audio session 0 was registered but disabled, and the client holding
 * control of it was `system_server`. The corpus says why:
 * `com.android.server.audio.visualizer.AmpVisualizerEffect` builds a `Visualizer` on session 0 once
 * and afterwards only toggles `setEnabled` — it never releases — so whoever else attaches gets a
 * handle without control and can never enable the shared effect.
 *
 * The lever is `AudioManager.startAudioOutput(packageName)` — a BYD addition to the framework class,
 * the one the DiLink SDK's own `DiLinkAudioManager` calls — which reaches
 * `AudioServiceMultiUserImpl.startAudioOutput` and then
 * `AudioVisualizerControl.startAudioVisualizer`. Two conditions gate it, both read off the corpus:
 *
 *  - `AudioVisualizerStore.getVisualizer(packageName)` returns null unless the package is one of
 *    its allow-listed players (`com.byd.mediacenter`, `cn.kuwo.kwmusiccar`, `com.kugou.android.auto`,
 *    `com.byd.dynaudio_app`, `com.netease.cloudmusic.iot`, `com.byd.caraudioaosp`, mini-karaoke).
 *    `ru.yandex.music` is not among them, which is why nothing enabled the effect while it played.
 *    The package is a plain string parameter and the service clears the calling identity, so it is
 *    claimed, not proven.
 *  - `Session.isController()` needs the system to be using the vehicle speaker; otherwise the
 *    session is counted but `visualizer.start()` is not called.
 *
 * The session dies with the binder token, so this process holds it for the requested seconds and
 * then stops it explicitly. Killing the probe releases it too.
 *
 * HAZARD: starting the visualiser also starts the firmware's own consumers — the atmosphere lamp
 * pulses with the music while this holds.
 */
public final class AudioVisualizerProbe {
    private static final String TAG = "DenzaAudioVisualizerProbe";
    private static final String DEFAULT_PACKAGE = "com.byd.mediacenter";

    private AudioVisualizerProbe() {
    }

    public static void main(String[] args) {
        exemptHiddenApis();
        String mode = args.length > 0 ? args[0] : "dryrun";
        String packageName = args.length > 2 ? args[2] : DEFAULT_PACKAGE;
        int userId = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int holdSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;

        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // Already prepared; AudioManager only needs one to exist.
        }
        try {
            AudioManager audio = (AudioManager)
                    systemContext().getSystemService(Context.AUDIO_SERVICE);
            for (Method method : AudioManager.class.getMethods()) {
                String name = method.getName();
                if (name.contains("AudioOutput") || name.contains("isualizer")) {
                    out("candidate: " + method);
                }
            }
            Method start = resolve(audio, "startAudioOutput");
            Method stop = resolve(audio, "stopAudioOutput");
            out("startAudioOutput -> " + start);
            out("stopAudioOutput  -> " + stop);
            if ("dryrun".equals(mode)) {
                out("dryrun: nothing was started (userId " + userId + " unused on this path)");
                return;
            }

            report("before");
            out("start packageName=" + packageName);
            start.invoke(audio, packageName);
            Thread.sleep(2000L);
            report("holding");
            out("holding " + holdSeconds + "s");
            Thread.sleep(holdSeconds * 1000L);
            report("still holding");
            stop.invoke(audio, packageName);
            Thread.sleep(2000L);
            report("after stop");
        } catch (Throwable error) {
            out("failed: " + error);
            Throwable cause = error.getCause();
            if (cause != null) {
                out("cause: " + cause);
            }
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * The session-0 effect as AudioFlinger itself reports it, so a claim about the enable is the
     * car's own answer rather than this probe's opinion.
     */
    private static void report(String when) {
        try {
            Process process = new ProcessBuilder("sh", "-c", "dumpsys media.audio_flinger")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean inChain = false;
            int printed = 0;
            while ((line = reader.readLine()) != null) {
                if (line.contains("effects for session 0")) {
                    inChain = true;
                }
                if (!inChain) {
                    continue;
                }
                String trimmed = line.trim().replaceAll("\\s+", " ");
                if (trimmed.startsWith("Effect ID") ||
                        trimmed.startsWith("Session State") ||
                        trimmed.matches("[0-9]{5} [0-9]{3} [yn] [yn] [yn]") ||
                        trimmed.matches("[0-9]+ [0-9]+ (yes|no) (yes|no) [0-9]+ [0-9]+")) {
                    out(when + " | " + trimmed);
                    printed++;
                }
                if (printed >= 6) {
                    break;
                }
            }
            reader.close();
            process.destroy();
            if (printed == 0) {
                out(when + " | no session-0 effect chain at all");
            }
        } catch (Throwable error) {
            out(when + " | dump failed: " + error);
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }

    /**
     * The two methods are BYD additions to `AudioManager`, so they are looked up by name rather
     * than called through a compiled interface: a firmware without them must say so plainly here
     * instead of failing to link.
     */
    private static Method resolve(Object target, String name) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == String.class) {
                return method;
            }
        }
        throw new IllegalStateException(
                name + "(String) is not on this firmware's AudioManager");
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Object instance = runtime.getMethod("getRuntime").invoke(null);
            runtime.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(instance, (Object) new String[]{"L"});
        } catch (Throwable error) {
            out("hidden-api exemption failed (continuing): " + error);
        }
    }

    private static void out(String message) {
        System.out.println("[AVP] " + message);
        Log.i(TAG, "[AVP] " + message);
    }
}
