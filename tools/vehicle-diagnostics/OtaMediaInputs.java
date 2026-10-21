package dev.denza.diagnostics.probe;

import android.content.Context;
import android.os.Process;
import org.json.JSONObject;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Five fixed properties and the stock MCU-version getter. No query/set/listener API. */
public final class OtaMediaInputs {
    public static void main(String[] args) {
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(15000); } catch (InterruptedException ignored) { }
            Process.killProcess(Process.myPid());
        });
        deadline.setDaemon(true); deadline.start();
        int exit = 1;
        try (RandomAccessFile file = new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock", "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (Process.myUid() != 2000 || args.length != 0 || lock == null) throw new IllegalStateException("scope/lock");
            ShellProxyBootstrap.prepareMainLooper();
            ShellProxyBootstrap.exemptHiddenApis();
            JSONObject result = new JSONObject();
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String[] names = {"apps.setting.product.outswver", "ro.build.car.series",
                    "ro.build.system.fission_single_os", "ro.build.multi_display_user", "ro.hardware.info"};
            for (String name : names) result.put(name, properties.getMethod("get", String.class, String.class).invoke(null, name, ""));
            Class<?> device = Class.forName("android.hardware.bydauto.version.BYDAutoVersionDevice");
            Object version = device.getMethod("getInstance", Context.class).invoke(null, ShellProxyBootstrap.systemContext());
            Object mcu = device.getMethod("getMcuVersion").invoke(version);
            if (!(mcu instanceof String) || ((String)mcu).length() > 128) throw new IllegalStateException("MCU shape");
            result.put("mcuVersion", mcu);
            System.out.println("DENZA_OTA_MEDIA_INPUTS:" + result);
            exit = 0;
        } catch (Throwable failure) {
            System.err.println("DENZA_OTA_MEDIA_INPUTS_ERROR:" + failure.getClass().getSimpleName());
        }
        System.exit(exit);
    }
}
