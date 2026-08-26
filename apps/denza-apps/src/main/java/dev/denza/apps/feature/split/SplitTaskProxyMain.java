package dev.denza.apps.feature.split;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Narrow shell-UID task operations with component postconditions.
 *
 * <p>Two ways in, one set of operations. {@code main} runs one command and exits, which is what
 * the product used for every removal: correct, and 506-681 ms of {@code app_process} start-up per
 * call on this car. {@code serve} boots the same way once and then answers request lines on its
 * own stdin, which costs the car the work and nothing else - measured on this vehicle at 3-9 ms
 * for a whole world read and 0 ms for an {@code activity_task} transaction.
 *
 * <p>The one-shot path stays exactly as it was, because it is the fallback: anything at all that
 * goes wrong with the resident helper leaves the product doing what it did before.
 */
public final class SplitTaskProxyMain {
    private static final String RESULT_PREFIX = "DENZA_SPLIT_RESULT:";
    private static final String USAGE =
            "focus-task <id>, remove-task (<id> <basePkg> <baseActivity> "
                    + "<topPkg|-> <topActivity|->)+, start-in-task <id> <hostPkg> "
                    + "<hostActivity> <targetPkg> <targetActivity>, or serve <nonce> required";

    private SplitTaskProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Context context = systemContext();
        if (args.length == 2 && "serve".equals(args[0])) {
            serve(context, args[1]);
            return;
        }
        StringBuilder answer = new StringBuilder();
        if (!dispatch(context, args, answer)) {
            throw new IllegalArgumentException(USAGE);
        }
        System.out.print(answer);
        System.out.flush();
        // ActivityThread/system Binder setup can keep app_process alive after main returns.
        // This entry point is deliberately one-shot, just like ClusterProxyMain.
        System.exit(0);
    }

    // region resident

    /**
     * Answers request lines until the stream that carries them goes away.
     *
     * <p>The channel is one ADB shell stream of the product and nothing else: no socket is opened,
     * nothing is listened on, and when that stream closes stdin reaches EOF and this process ends.
     * Every answer is wrapped in the caller's own nonce, so a caller can never mistake shell noise,
     * a late answer or a half-written one for the reply it is waiting for.
     */
    private static void serve(Context context, String nonce) throws Exception {
        String begin = "DENZA_SERVE_" + nonce + ":BEGIN";
        String end = "DENZA_SERVE_" + nonce + ":END";
        System.out.println("DENZA_SERVE_" + nonce + ":READY");
        System.out.flush();
        BufferedReader requests = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = requests.readLine()) != null) {
            String request = line.trim();
            if (request.isEmpty()) {
                continue;
            }
            if ("quit".equals(request)) {
                break;
            }
            StringBuilder answer = new StringBuilder();
            String failure = null;
            try {
                if (!answer(context, request, answer)) {
                    failure = "unsupported request";
                }
            } catch (Throwable error) {
                // A helper that guesses is worse than no helper: the caller runs the command it
                // would have run anyway and gets the firmware's own words for what went wrong.
                failure = String.valueOf(error);
            }
            System.out.println(begin);
            if (failure == null) {
                System.out.print(answer);
                if (answer.length() > 0 && answer.charAt(answer.length() - 1) != '\n') {
                    System.out.println();
                }
                System.out.println(end + " ok");
            } else {
                System.out.println(end + " err " + failure.replace('\n', ' '));
            }
            System.out.flush();
        }
        System.exit(0);
    }

    /** One request, in the exact words the shell command it replaces would have answered with. */
    private static boolean answer(Context context, String request, StringBuilder answer)
            throws Exception {
        List<String> words = splitArguments(request);
        if (words.isEmpty()) {
            return false;
        }
        String verb = words.get(0);
        if ("world".equals(verb) && words.size() == 1) {
            appendWorld(answer);
            return true;
        }
        if ("call-int".equals(verb)) {
            answer.append(parcelInt(transactInt(words.subList(1, words.size()))));
            answer.append('\n');
            return true;
        }
        return dispatch(context, words.toArray(new String[0]), answer);
    }

    /**
     * Every root task, in the very text {@code am stack list} prints.
     *
     * <p>That is not a format this class invents: {@code am stack list} is
     * {@code getAllRootTaskInfos()} followed by {@code toString()} on each element, so the product
     * keeps its one parser and its one set of tests. Read off this vehicle byte for byte against
     * the command it replaces.
     */
    @SuppressLint({"PrivateApi", "BlockedPrivateApi"})
    private static void appendWorld(StringBuilder answer) throws Exception {
        Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
        Object service = managerClass.getDeclaredMethod("getService").invoke(null);
        Method getAllRootTaskInfos = service.getClass().getMethod("getAllRootTaskInfos");
        getAllRootTaskInfos.setAccessible(true);
        for (Object info : (List<?>) getAllRootTaskInfos.invoke(service)) {
            answer.append(info).append('\n');
        }
    }

    /**
     * The same transaction {@code service call activity_task …} performs, argument for argument.
     *
     * <p>The interface token is read from the binder itself rather than written from a constant,
     * exactly as {@code service call} does, so this cannot drift from the firmware it talks to.
     */
    private static int transactInt(List<String> arguments) throws Exception {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("call-int needs a transaction code");
        }
        int code = Integer.parseInt(arguments.get(0));
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "activity_task");
        if (service == null) {
            throw new IllegalStateException("no activity_task service");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            for (int at = 1; at < arguments.size(); at += 2) {
                String kind = arguments.get(at);
                String value = arguments.get(at + 1);
                if ("i32".equals(kind)) {
                    data.writeInt(Integer.parseInt(value));
                } else if ("s16".equals(kind)) {
                    data.writeString(value);
                } else {
                    throw new IllegalArgumentException("unsupported argument " + kind);
                }
            }
            service.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** The one line {@code service call} prints for an int reply, byte for byte. */
    static String parcelInt(int value) {
        return String.format("Result: Parcel(00000000 %08x '........')", value);
    }

    /**
     * Splits a request line into the argv the shell would have built from the same text.
     *
     * <p>The product quotes every argument it sends with the one rule a POSIX shell has, so this
     * is that rule read backwards and nothing more. It is the exact inverse of the caller's
     * quoting, which is what lets a resident request carry an application id or an activity name
     * with any character in it and still mean precisely what the one-shot command line meant.
     */
    static List<String> splitArguments(String line) {
        ArrayList<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        for (int at = 0; at < line.length(); at++) {
            char symbol = line.charAt(at);
            if (quoted) {
                if (symbol == '\'') {
                    quoted = false;
                } else {
                    word.append(symbol);
                }
            } else if (symbol == '\'') {
                quoted = true;
                started = true;
            } else if (symbol == '\\') {
                // Outside quotes a backslash makes the next character literal. This is not an
                // exotic case: `'\''` is exactly how a POSIX shell - and therefore this project's
                // own quoting - carries a single quote through a single-quoted word.
                if (at + 1 >= line.length()) {
                    throw new IllegalArgumentException("trailing backslash in request");
                }
                word.append(line.charAt(++at));
                started = true;
            } else if (symbol == '"') {
                // The callers of this helper quote with single quotes and nothing else, so a
                // double quote is a request nobody meant to send. Refusing it is free: the caller
                // runs the command it would have run anyway.
                throw new IllegalArgumentException("unsupported double quote in request");
            } else if (symbol == ' ' || symbol == '\t') {
                if (started) {
                    words.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else {
                word.append(symbol);
                started = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quote in request");
        }
        if (started) {
            words.add(word.toString());
        }
        return words;
    }

    // endregion

    /** @return whether [args] named an operation at all. */
    private static boolean dispatch(Context context, String[] args, StringBuilder answer) {
        if (args.length == 2 && "focus-task".equals(args[0])) {
            int taskId = positiveTaskId(args[1]);
            result(answer, focusLaunchableTask(context, taskId));
            return true;
        }
        // One invocation, any number of tasks. Loading this class costs far more than the removals
        // themselves, so a recipe that clears several tasks asks for them all at once; the answer
        // stays one line per task so the caller still learns exactly which ones went.
        if (args.length >= 6 && args.length % 5 == 1 && "remove-task".equals(args[0])) {
            for (int at = 1; at < args.length; at += 5) {
                int taskId = positiveTaskId(args[at]);
                result(answer, taskId, removeExactTask(
                        context,
                        taskId,
                        args[at + 1],
                        args[at + 2],
                        optional(args[at + 3]),
                        optional(args[at + 4])));
            }
            return true;
        }
        if (args.length == 6 && "start-in-task".equals(args[0])) {
            int taskId = positiveTaskId(args[1]);
            result(answer, startExactComponentInHostTask(
                    context,
                    taskId,
                    args[2],
                    args[3],
                    args[4],
                    args[5]));
            return true;
        }
        return false;
    }

    private static void result(StringBuilder answer, boolean value) {
        answer.append(RESULT_PREFIX).append(value).append('\n');
    }

    /** One line per task of a batch, so a caller of many learns the fate of each. */
    private static void result(StringBuilder answer, int taskId, boolean value) {
        answer.append(RESULT_PREFIX).append(taskId).append('=').append(value).append('\n');
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
