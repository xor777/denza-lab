package dev.denza.apps.feature.split;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
        // One invocation, any number of tasks. Loading this class costs far more than the removals
        // themselves, so a recipe that clears several tasks asks for them all at once; the answer
        // stays one line per task so the caller still learns exactly which ones went.
        if (args.length >= 6 && args.length % 5 == 1 && "remove-task".equals(args[0])) {
            for (int at = 1; at < args.length; at += 5) {
                int taskId = positiveTaskId(args[at]);
                result(taskId, removeExactTask(
                        context,
                        taskId,
                        args[at + 1],
                        args[at + 2],
                        optional(args[at + 3]),
                        optional(args[at + 4])));
            }
            System.exit(0);
            return;
        }
        if (args.length == 6 && "start-in-task".equals(args[0])) {
            int taskId = positiveTaskId(args[1]);
            result(startExactComponentInHostTask(
                    context,
                    taskId,
                    args[2],
                    args[3],
                    args[4],
                    args[5]));
            return;
        }
        throw new IllegalArgumentException(
                "focus-task <id>, remove-task (<id> <basePkg> <baseActivity> "
                        + "<topPkg|-> <topActivity|->)+, or start-in-task <id> <hostPkg> "
                        + "<hostActivity> <targetPkg> <targetActivity> required");
    }

    private static void result(boolean value) {
        System.out.println(RESULT_PREFIX + value);
        System.out.flush();
        // ActivityThread/system Binder setup can keep app_process alive after main returns.
        // This helper is deliberately one-shot, just like ClusterProxyMain.
        System.exit(0);
    }

    /** One line per task of a batch, so a caller of many learns the fate of each. */
    private static void result(int taskId, boolean value) {
        System.out.println(RESULT_PREFIX + taskId + "=" + value);
        System.out.flush();
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

    @SuppressLint("BlockedPrivateApi")
    private static boolean startExactComponentInHostTask(
            Context context,
            int taskId,
            String expectedHostPackage,
            String expectedHostActivity,
            String targetPackage,
            String targetActivity) {
        ActivityManager.RunningTaskInfo selected = findTask(context, taskId);
        if (selected == null || !matchesBaseIdentity(
                selected,
                expectedHostPackage,
                expectedHostActivity)) {
            return false;
        }
        ComponentName target = new ComponentName(
                targetPackage,
                normalizedActivity(targetPackage, targetActivity));
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(target);
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            Method setLaunchTaskId = ActivityOptions.class
                    .getDeclaredMethod("setLaunchTaskId", int.class);
            setLaunchTaskId.setAccessible(true);
            setLaunchTaskId.invoke(options, taskId);
            return invokeStartActivity(launch, options.toBundle());
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    @SuppressLint({"PrivateApi", "BlockedPrivateApi"})
    private static boolean invokeStartActivity(Intent intent, android.os.Bundle options) {
        try {
            Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
            Object service = managerClass.getDeclaredMethod("getService").invoke(null);
            for (Method method : service.getClass().getMethods()) {
                if (!"startActivity".equals(method.getName())
                        || method.getParameterTypes().length != 11) {
                    continue;
                }
                method.setAccessible(true);
                Object result = method.invoke(
                        service,
                        null,
                        "com.android.shell",
                        null,
                        intent,
                        null,
                        null,
                        null,
                        0,
                        0,
                        null,
                        options);
                return result instanceof Integer && (Integer) result >= 0;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
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
        String normalizedActivity = normalizedActivity(expectedPackage, expectedActivity);
        return expectedPackage.equals(actual.getPackageName())
                && normalizedActivity.equals(actual.getClassName());
    }

    private static String normalizedActivity(String packageName, String activityName) {
        return activityName.startsWith(".") ? packageName + activityName : activityName;
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
