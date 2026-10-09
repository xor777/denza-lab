package dev.denza.apps.platform.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * What a shell-UID helper does before its own work: a main looper and the system context.
 *
 * <p>A class that {@code app_process} starts as the shell user has neither. Binder callbacks and
 * {@code ActivityThread} want the main looper, and every framework service a helper asks - the
 * activity task manager, the BYD light device - wants a {@link Context}; the one available outside
 * an application is the system context of {@code ActivityThread.systemMain()}. Each helper used to
 * carry these few lines of its own; this class is compiled into every helper jar instead
 * ({@code PackShellProxy} in the app's build script), so it depends on nothing but the platform.
 */
public final class ShellProxyBootstrap {
    private ShellProxyBootstrap() {
    }

    /**
     * Prepares the main looper. {@code app_process} enters a helper without one; should a
     * runtime ever prepare it first, that looper is the one the helper uses.
     */
    public static void prepareMainLooper() {
        try {
            Looper.prepareMainLooper();
        } catch (IllegalStateException alreadyPrepared) {
            // The runtime prepared it before entering the helper.
        }
    }

    /** {@code ActivityThread.systemMain().getSystemContext()}. */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    public static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(activityThread);
    }

    /**
     * Lifts the hidden-API checks for this process, for a helper that reflects into vendor
     * classes. Best effort: if the framework refuses, the reflective calls that follow fail
     * closed on their own.
     */
    @SuppressLint({"PrivateApi", "BlockedPrivateApi"})
    public static void exemptHiddenApis() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getMethod("getRuntime").invoke(null);
            vm.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
        } catch (Exception ignored) {
            // Registration will fail closed if the framework denies the reflective calls.
        }
    }
}
