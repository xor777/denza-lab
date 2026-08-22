package dev.denza.tools;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;

import java.lang.reflect.Method;

/** Sends the verified stock LOCAL play/pause intent to BYD MediaCenter. */
public final class SpeakerLiftLocalPulse {
    private static final String ACTION_START_MEDIA = "byd.intent.action.START_MEDIA";
    private static final String MEDIA_CENTER_PACKAGE = "com.byd.mediacenter";
    private static final String MEDIA_CENTER_SERVICE =
            "com.byd.mediacenter.main.MediaService";

    private static void initializeShellProcess() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        activityThread.getMethod("getSystemContext").invoke(thread);
    }

    private static void exemptHiddenApis() throws Exception {
        Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
        Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
        vmRuntime.getMethod("setHiddenApiExemptions", String[].class)
                .invoke(runtime, (Object) new String[]{"L"});
    }

    private static Object startAsShell(Intent intent) throws Exception {
        Class<?> activityManager = Class.forName("android.app.ActivityManager");
        Object service = activityManager.getMethod("getService").invoke(null);
        Class<?> appThread = Class.forName("android.app.IApplicationThread");
        Method startService = service.getClass().getMethod(
                "startService",
                appThread,
                Intent.class,
                String.class,
                boolean.class,
                String.class,
                String.class,
                int.class);
        return startService.invoke(
                service, null, intent, null, true, "com.android.shell", null, 0);
    }

    private static Intent mediaIntent(int mediaAction, Bundle params) {
        Intent intent = new Intent(ACTION_START_MEDIA);
        intent.setComponent(new ComponentName(
                MEDIA_CENTER_PACKAGE, MEDIA_CENTER_SERVICE));
        intent.putExtra("MediaMode", 1);
        intent.putExtra("MediaAction", mediaAction);
        intent.putExtra("sdkVersion", 501000);
        intent.putExtra("MediaParams", params);
        return intent;
    }

    private static Bundle baseParams() {
        Bundle params = new Bundle();
        params.putInt("source", 0);
        params.putString("package", "com.android.shell");
        return params;
    }

    private static void usage() {
        System.out.println("usage:");
        System.out.println("  SpeakerLiftLocalPulse play-path <canonical-ivi-path>");
        System.out.println("  SpeakerLiftLocalPulse play-id <signed-music-id>");
        System.out.println("  SpeakerLiftLocalPulse pause");
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                usage();
                System.exit(2);
            }

            exemptHiddenApis();
            Looper.prepareMainLooper();
            initializeShellProcess();

            Bundle params = baseParams();
            int mediaAction;
            Integer musicId = null;
            if (args.length == 2 && "play-path".equals(args[0])) {
                mediaAction = 14;
                musicId = args[1].hashCode();
            } else if (args.length == 2 && "play-id".equals(args[0])) {
                mediaAction = 14;
                musicId = Integer.parseInt(args[1]);
            } else if (args.length == 1 && "pause".equals(args[0])) {
                mediaAction = 2;
            } else {
                usage();
                System.exit(2);
                return;
            }

            if (musicId != null) {
                params.putLong("media_id", musicId.longValue());
                params.putInt("media_list_type", 0);
                System.out.println("musicId=" + musicId);
            }

            Object started = startAsShell(mediaIntent(mediaAction, params));
            System.out.println("started=" + started);
            System.out.println("source=LOCAL action=" + mediaAction);
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
