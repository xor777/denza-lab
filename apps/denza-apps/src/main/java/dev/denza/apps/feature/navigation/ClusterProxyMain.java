package dev.denza.apps.feature.navigation;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-shot shell-UID task command. It intentionally exposes only fixed
 * operations for an allowlisted navigation task and always exits.
 */
public final class ClusterProxyMain {
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "ru.yandex.yandexnavi",
            "ru.yandex.yandexmaps",
            "com.google.android.apps.maps",
            "com.waze",
            "ru.dublgis.dgismobile"));
    private static final String RESULT_PREFIX = "DENZA_RESULT:";

    private ClusterProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("operation required");
        Looper.prepareMainLooper();
        Commands commands = new Commands(systemContext());
        switch (args[0]) {
            case "find-task":
                requireCount(args, 2);
                result(commands.findAllowedTask(args[1]));
                return;
            case "project-task":
                requireCount(args, 6);
                result(commands.projectTask(
                        args[1], integer(args[2]), integer(args[3]),
                        integer(args[4]), integer(args[5])));
                return;
            case "return-task":
                requireCount(args, 3);
                result(commands.returnTask(args[1], integer(args[2]), true));
                return;
            case "restore-task":
                requireCount(args, 3);
                result(commands.returnTask(args[1], integer(args[2]), false));
                return;
            case "move-task":
                requireCount(args, 4);
                result(commands.moveTask(args[1], integer(args[2]), integer(args[3])));
                return;
            case "set-bounds":
                requireCount(args, 7);
                result(commands.setTaskBounds(
                        args[1], integer(args[2]), integer(args[3]), integer(args[4]),
                        integer(args[5]), integer(args[6])));
                return;
            case "focus-task":
                requireCount(args, 3);
                result(commands.focusTask(args[1], integer(args[2])));
                return;
            case "background-task":
                requireCount(args, 3);
                result(commands.backgroundTask(args[1], integer(args[2])));
                return;
            case "task-display":
                requireCount(args, 3);
                result(commands.taskDisplayId(args[1], integer(args[2])));
                return;
            default:
                throw new IllegalArgumentException("unsupported operation");
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(activityThread);
    }

    private static void requireCount(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("invalid argument count");
    }

    private static int integer(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new IllegalArgumentException("negative integer");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid integer", error);
        }
    }

    private static void result(Object value) {
        System.out.println(RESULT_PREFIX + value);
    }

    private static final class Commands {
        private final Context context;

        Commands(Context context) {
            this.context = context;
        }

        int findAllowedTask(String packageName) {
            if (!isAllowedPackage(packageName)) return -1;
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (belongsToPackage(task, packageName)) return task.taskId;
            }
            return -1;
        }

        boolean projectTask(
                String packageName, int taskId, int displayId, int width, int height) {
            if (!moveTask(packageName, taskId, displayId)) return false;
            if (!setTaskBounds(packageName, taskId, 0, 0, width, height)) return false;
            return focusTask(packageName, taskId);
        }

        boolean returnTask(String packageName, int taskId, boolean focusNavigation) {
            enforceTask(packageName, taskId);
            int currentDisplay = taskDisplayId(packageName, taskId);
            // Clear the virtual-display bounds before changing displays. Clearing
            // them after the move sends two rapid configuration changes on the
            // IVI (virtual size, then fullscreen); 2GIS exits between those two
            // relaunches and Android removes its now-empty task.
            if (!setTaskBounds(packageName, taskId, 0, 0, 0, 0)) return false;
            if (currentDisplay > 0 && !moveTask(packageName, taskId, 0)) return false;
            return focusNavigation
                    ? focusTask(packageName, taskId)
                    : backgroundTask(packageName, taskId);
        }

        boolean moveTask(String packageName, int taskId, int displayId) {
            enforceTask(packageName, taskId);
            return invokeTaskManager(
                    new String[] {"moveRootTaskToDisplay", "moveTaskToDisplay", "moveStackToDisplay"},
                    new Class<?>[] {int.class, int.class},
                    taskId,
                    displayId);
        }

        boolean setTaskBounds(
                String packageName, int taskId, int left, int top, int right, int bottom) {
            enforceTask(packageName, taskId);
            boolean clear = left == 0 && top == 0 && right == 0 && bottom == 0;
            if (!clear && (right <= left || bottom <= top)) {
                throw new IllegalArgumentException("invalid bounds");
            }
            Rect bounds = clear ? null : new Rect(left, top, right, bottom);
            return invokeTaskManager(
                    new String[] {"resizeTask"},
                    new Class<?>[] {int.class, Rect.class, int.class},
                    taskId,
                    bounds,
                    0);
        }

        boolean focusTask(String packageName, int taskId) {
            enforceTask(packageName, taskId);
            return invokeTaskManager(
                    new String[] {"setFocusedTask"},
                    new Class<?>[] {int.class},
                    taskId);
        }

        boolean backgroundTask(String packageName, int taskId) {
            enforceTask(packageName, taskId);
            // Running tasks are ordered front-to-back. Once the navigation
            // root returns to display 0, the first non-navigation task is the
            // central scene that was visible before projection.
            for (ActivityManager.RunningTaskInfo candidate : tasks()) {
                if (candidate.taskId == taskId || displayIdOf(candidate) != 0) continue;
                if (belongsToAllowedPackage(candidate) || candidate.topActivity == null) continue;
                return invokeTaskManager(
                        new String[] {"setFocusedTask"},
                        new Class<?>[] {int.class},
                        candidate.taskId);
            }
            return false;
        }

        int taskDisplayId(String packageName, int taskId) {
            enforceTask(packageName, taskId);
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (task.taskId == taskId && belongsToPackage(task, packageName)) {
                    return displayIdOf(task);
                }
            }
            return -1;
        }

        private List<ActivityManager.RunningTaskInfo> tasks() {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            return manager == null ? java.util.Collections.emptyList() : manager.getRunningTasks(100);
        }

        private boolean belongsToPackage(
                ActivityManager.RunningTaskInfo task, String packageName) {
            return hasPackage(task.topActivity, packageName)
                    || hasPackage(task.baseActivity, packageName);
        }

        private boolean belongsToAllowedPackage(ActivityManager.RunningTaskInfo task) {
            for (String packageName : ALLOWED_PACKAGES) {
                if (belongsToPackage(task, packageName)) return true;
            }
            return false;
        }

        private boolean hasPackage(ComponentName component, String packageName) {
            return component != null && packageName.equals(component.getPackageName());
        }

        private boolean isAllowedPackage(String packageName) {
            return ALLOWED_PACKAGES.contains(packageName);
        }

        private int displayIdOf(ActivityManager.RunningTaskInfo task) {
            try {
                Field field = task.getClass().getField("displayId");
                return field.getInt(task);
            } catch (ReflectiveOperationException ignored) {
                try {
                    Field configurationField = task.getClass().getField("configuration");
                    Object configuration = configurationField.get(task);
                    Field windowConfiguration = configuration.getClass().getField("windowConfiguration");
                    Object value = windowConfiguration.get(configuration);
                    Method getDisplayId = value.getClass().getMethod("getDisplayId");
                    return (Integer) getDisplayId.invoke(value);
                } catch (ReflectiveOperationException | RuntimeException nested) {
                    return -1;
                }
            }
        }

        private void enforceTask(String packageName, int taskId) {
            if (!isAllowedPackage(packageName)) {
                throw new SecurityException("package is not allowed for navigation");
            }
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (task.taskId == taskId && belongsToPackage(task, packageName)) return;
            }
            throw new SecurityException("task is not an allowed navigation task");
        }

        // This shell-UID helper targets the fixed DiLink 5.1 framework surface;
        // public SDK APIs cannot move an allowlisted task across vendor displays.
        @SuppressLint({"PrivateApi", "BlockedPrivateApi"})
        private boolean invokeTaskManager(
                String[] names,
                Class<?>[] parameterTypes,
                Object... args) {
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
    }
}
