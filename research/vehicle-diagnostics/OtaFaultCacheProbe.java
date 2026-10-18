package dev.denza.tools;

import android.content.Context;
import android.os.Looper;
import android.os.Process;

/** One fixed cached SDK read; no query, setter, listener, or OTA workflow. */
public final class OtaFaultCacheProbe {
    private static void out(String s) { System.out.println(s); System.out.flush(); }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("No arguments");
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) { return; }
            out("HARD_TIMEOUT"); System.exit(3);
        }, "ota-fault-cache-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            vm.getMethod("setHiddenApiExemptions", String[].class).invoke(
                    vm.getMethod("getRuntime").invoke(null), (Object) new String[]{"L"});
            Class<?> props = Class.forName("android.os.SystemProperties");
            String profile = (String) props.getMethod("get", String.class).invoke(null, "persist.sys.AutoType");
            String manager = (String) props.getMethod("get", String.class).invoke(null, "sys.diagmgrsrv.task_bk");
            if (Process.myUid() != 2000 || !"170".equals(profile) || !"r@-1_0&w@-1_0".equals(manager)) {
                out("REFUSED uid/profile/manager"); System.exit(2);
            }
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context context = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.ota.BYDAutoOtaDevice");
            Object device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            byte[] value = (byte[]) deviceClass.getMethod("getFaultCodeInfo").invoke(device);
            if (value == null) { out("getFaultCodeInfo NULL"); System.exit(0); }
            if (value.length > 4096) { out("getFaultCodeInfo BOUND_EXCEEDED length=" + value.length); System.exit(2); }
            StringBuilder hex = new StringBuilder();
            for (byte b : value) hex.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
            out("getFaultCodeInfo length=" + value.length + " raw=" + hex);
            out("END cached_read_only"); System.exit(0);
        } catch (Throwable e) {
            out("FAILED " + e.getClass().getSimpleName());
            if (e.getCause() != null) out("CAUSE " + e.getCause().getClass().getSimpleName());
            System.exit(2);
        }
    }
}
