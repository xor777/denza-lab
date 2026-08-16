package dev.denza.apps.feature.split;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.List;

/** Narrow shell-UID task operations with component postconditions. */
public final class SplitTaskProxyMain {
    private static final String RESULT_PREFIX = "DENZA_SPLIT_RESULT:";
    private SplitTaskProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Context context = systemContext();
        if (args.length == 2 && "focus-task".equals(args[0])) {
            int taskId = positiveTaskId(args[1]);
            result(focusLaunchableTask(context, taskId));
            return;
        }
        if (args.length == 6 && "remove-task".equals(args[0])) {
            int taskId = positiveTaskId(args[1]);
            result(removeExactTask(
                    context,
                    taskId,
                    args[2],
                    args[3],
                    optional(args[4]),
                    optional(args[5])));
            return;
        }
        throw new IllegalArgumentException(
                "focus-task <id>, or remove-task <id> <basePkg> <baseActivity> "
                        + "<topPkg|-> <topActivity|-> required");
    }

    private static void result(boolean value) {
        System.out.println(RESULT_PREFIX + value);
        System.out.flush();
        // ActivityThread/system Binder setup can keep app_process alive after main returns.
        // This helper is deliberately one-shot, just like ClusterProxyMain.
        System.exit(0);
    }

    private static int positiveTaskId(String raw) {
        int taskId = Integer.parseInt(raw);
        if (taskId <= 0) throw new IllegalArgumentException("invalid task id");
        return taskId;
    }

    private static String optional(String value) {
        return "-".equals(value) ? null : value;
    }

    private static boolean focusLaunchableTask(Context context, int taskId) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return false;
        List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(100);
        ActivityManager.RunningTaskInfo selected = null;
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (task.taskId == taskId) {
                selected = task;
                break;
            }
        }
        if (selected == null) return false;
        String packageName = packageName(selected.topActivity, selected.baseActivity);
        if (packageName == null || context.getPackageManager()
                .getLaunchIntentForPackage(packageName) == null) {
            return false;
        }
        return invokeSetFocusedTask(taskId);
    }

    private static boolean removeExactTask(
            Context context,
            int taskId,
            String expectedBasePackage,
            String expectedBaseActivity,
            String expectedTopPackage,
            String expectedTopActivity) {
        ActivityManager.RunningTaskInfo selected = findTask(context, taskId);
        if (selected == null || !matchesBaseIdentity(
                selected,
                expectedBasePackage,
                expectedBaseActivity)) {
            return false;
        }
        if ((expectedTopPackage != null || expectedTopActivity != null)
                && !matches(selected.topActivity, expectedTopPackage, expectedTopActivity)) {
            return false;
        }
        return invokeRemoveTask(taskId);
    }

    /**
     * Vendor `am stack list` reports the launcher alias for a task while
     * {@link ActivityManager.RunningTaskInfo#baseActivity} reports the concrete activity behind
     * that alias. Both are identities supplied by ActivityTaskManager for the same exact task.
     * Accepting either the base intent/real activity or the concrete base activity preserves the
     * task-id and component postcondition without rejecting every launcher-alias task.
     */
    private static boolean matchesBaseIdentity(
            ActivityManager.RunningTaskInfo task,
            String expectedPackage,
            String expectedActivity) {
        if (task == null) return false;
        ComponentName intentComponent = task.baseIntent == null
                ? null
                : task.baseIntent.getComponent();
        return matches(task.baseActivity, expectedPackage, expectedActivity)
                || matches(intentComponent, expectedPackage, expectedActivity);
    }

    private static ActivityManager.RunningTaskInfo findTask(Context context, int taskId) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return null;
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
            if (task.taskId == taskId) return task;
        }
        return null;
    }

    private static boolean matches(
            ComponentName actual,
            String expectedPackage,
            String expectedActivity) {
        if (actual == null || expectedPackage == null || expectedActivity == null) return false;
        String normalizedActivity = expectedActivity.startsWith(".")
                ? expectedPackage + expectedActivity
                : expectedActivity;
        return expectedPackage.equals(actual.getPackageName())
                && normalizedActivity.equals(actual.getClassName());
    }

    private static String packageName(ComponentName top, ComponentName base) {
        if (top != null) return top.getPackageName();
        return base == null ? null : base.getPackageName();
    }

    @SuppressLint({"PrivateApi", "BlockedPrivateApi"})
    private static boolean invokeSetFocusedTask(int taskId) {
        try {
            Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
            Object service = managerClass.getDeclaredMethod("getService").invoke(null);
            Method method = service.getClass().getMethod("setFocusedTask", int.class);
            method.setAccessible(true);
            Object result = method.invoke(service, taskId);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    @SuppressLint({"PrivateApi", "BlockedPrivateApi"})
    private static boolean invokeRemoveTask(int taskId) {
        try {
            Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
            Object service = managerClass.getDeclaredMethod("getService").invoke(null);
            Method method = service.getClass().getMethod("removeTask", int.class);
            method.setAccessible(true);
            Object result = method.invoke(service, taskId);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(activityThread);
    }
}
