package dev.denza.apps.feature.split;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.List;

/** Fixed shell-UID operation used only to focus a launchable task inside its current split root. */
public final class SplitTaskProxyMain {
    private static final String RESULT_PREFIX = "DENZA_SPLIT_RESULT:";

    private SplitTaskProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"focus-task".equals(args[0])) {
            throw new IllegalArgumentException("focus-task and task id required");
        }
        int taskId = Integer.parseInt(args[1]);
        if (taskId <= 0) throw new IllegalArgumentException("invalid task id");
        Looper.prepareMainLooper();
        System.out.println(RESULT_PREFIX + focusLaunchableTask(systemContext(), taskId));
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

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(activityThread);
    }
}
