package dev.denza.apps.feature.navigation;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Minimal shell-UID entry point. It intentionally exposes no arbitrary command execution. */
public final class ClusterProxyMain {
    private static final String ALLOWED_PACKAGE = "ru.yandex.yandexnavi";
    private static final int VIRTUAL_DISPLAY_FLAGS = 322;

    private ClusterProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].length() < 32) {
            throw new IllegalArgumentException("one-time token required");
        }
        Looper.prepareMainLooper();
        Context context = systemContext();
        TaskProxy binder = new TaskProxy(context, args[0]);
        Intent connected = new Intent(ProxyConnectionReceiver.ACTION_CONNECTED);
        connected.setComponent(new ComponentName(
                "dev.denza.apps",
                "dev.denza.apps.feature.navigation.ProxyConnectionReceiver"));
        connected.putExtra(ProxyConnectionReceiver.EXTRA_TOKEN, args[0]);
        Bundle extras = connected.getExtras();
        if (extras == null) extras = new Bundle();
        extras.putBinder(ProxyConnectionReceiver.EXTRA_BINDER, binder);
        connected.replaceExtras(extras);
        context.sendBroadcast(connected);
        Looper.loop();
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(activityThread);
    }

    private static final class TaskProxy extends IClusterTaskProxy.Stub {
        private final Context context;
        private final String token;
        private VirtualDisplay virtualDisplay;

        TaskProxy(Context context, String token) {
            this.context = context;
            this.token = token;
        }

        @Override
        public synchronized int createVirtualDisplay(String candidateToken, String name,
                int width, int height, int densityDpi, Surface surface) {
            enforceToken(candidateToken);
            if (surface == null || !surface.isValid()) return -1;
            releaseVirtualDisplay(candidateToken);
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            if (manager == null) return -1;
            virtualDisplay = manager.createVirtualDisplay(
                    safeName(name),
                    clamp(width, 320, 7680),
                    clamp(height, 240, 4320),
                    clamp(densityDpi, 120, 640),
                    surface,
                    VIRTUAL_DISPLAY_FLAGS);
            return virtualDisplay == null ? -1 : virtualDisplay.getDisplay().getDisplayId();
        }

        @Override
        public synchronized void releaseVirtualDisplay(String candidateToken) {
            enforceToken(candidateToken);
            if (virtualDisplay != null) virtualDisplay.release();
            virtualDisplay = null;
        }

        @Override
        public int findAllowedTask(String candidateToken, String packageName) {
            enforceToken(candidateToken);
            if (!ALLOWED_PACKAGE.equals(packageName)) return -1;
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (belongsToAllowedPackage(task)) return task.taskId;
            }
            return -1;
        }

        @Override
        public boolean moveTask(String candidateToken, int taskId, int displayId) {
            enforceTask(candidateToken, taskId);
            return invokeTaskManager(
                    new String[] {"moveRootTaskToDisplay", "moveTaskToDisplay", "moveStackToDisplay"},
                    new Class<?>[] {int.class, int.class},
                    taskId,
                    displayId);
        }

        @Override
        public boolean setTaskBounds(String candidateToken, int taskId,
                int left, int top, int right, int bottom) {
            enforceTask(candidateToken, taskId);
            Rect bounds = right > left && bottom > top ? new Rect(left, top, right, bottom) : null;
            return invokeTaskManager(
                    new String[] {"resizeTask"},
                    new Class<?>[] {int.class, Rect.class, int.class},
                    taskId,
                    bounds,
                    0);
        }

        @Override
        public boolean focusTask(String candidateToken, int taskId) {
            enforceTask(candidateToken, taskId);
            return invokeTaskManager(
                    new String[] {"setFocusedTask"},
                    new Class<?>[] {int.class},
                    taskId);
        }

        @Override
        public int taskDisplayId(String candidateToken, int taskId) {
            enforceTask(candidateToken, taskId);
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (task.taskId == taskId && belongsToAllowedPackage(task)) return displayIdOf(task);
            }
            return -1;
        }

        private List<ActivityManager.RunningTaskInfo> tasks() {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            return manager == null ? java.util.Collections.emptyList() : manager.getRunningTasks(100);
        }

        private boolean belongsToAllowedPackage(ActivityManager.RunningTaskInfo task) {
            return hasPackage(task.topActivity) || hasPackage(task.baseActivity);
        }

        private boolean hasPackage(ComponentName component) {
            return component != null && ALLOWED_PACKAGE.equals(component.getPackageName());
        }

        private int displayIdOf(ActivityManager.RunningTaskInfo task) {
            try {
                Field field = task.getClass().getField("displayId");
                return field.getInt(task);
            } catch (ReflectiveOperationException ignored) {
                try {
                    Field configurationField = task.getClass().getField("configuration");
                    Object configuration = configurationField.get(task);
                    Field windowConfiguration = configuration.getClass()
                            .getField("windowConfiguration");
                    Object value = windowConfiguration.get(configuration);
                    Method getDisplayId = value.getClass().getMethod("getDisplayId");
                    return (Integer) getDisplayId.invoke(value);
                } catch (ReflectiveOperationException | RuntimeException nested) {
                    return -1;
                }
            }
        }

        private void enforceTask(String candidateToken, int taskId) {
            enforceToken(candidateToken);
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (task.taskId == taskId && belongsToAllowedPackage(task)) return;
            }
            throw new SecurityException("task is not an allowed Yandex task");
        }

        private void enforceToken(String candidateToken) {
            if (!token.equals(candidateToken)) throw new SecurityException("invalid proxy token");
        }

        private boolean invokeTaskManager(String[] names, Class<?>[] parameterTypes, Object... args) {
            try {
                Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
                Object service = managerClass.getDeclaredMethod("getService").invoke(null);
                for (String name : names) {
                    try {
                        Method method = service.getClass().getMethod(name, parameterTypes);
                        method.setAccessible(true);
                        Object result = method.invoke(service, args);
                        return !(result instanceof Boolean) || (Boolean) result;
                    } catch (NoSuchMethodException ignored) {
                        // Try the equivalent method name used by another Android generation.
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
            return false;
        }

        private static String safeName(String name) {
            return name != null && name.startsWith("Denza Navigation")
                    ? name : "Denza Navigation";
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
