package dev.denza.apps.feature.navigation;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;

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
    private static final String TAG = "DenzaNavProxy";
    // Exact transaction codes from the live DiLink 5.1 framework pulled on
    // 2026-08-14. Raw Binder avoids hidden-API reflection on package-private
    // AIDL proxy implementations while preserving their generated wire format.
    private static final int GET_WINDOW_ORGANIZER_CONTROLLER_TRANSACTION = 70;
    private static final int GET_TASK_ORGANIZER_CONTROLLER_TRANSACTION = 6;
    private static final int CREATE_ROOT_TASK_TRANSACTION = 3;
    private static final String ACTIVITY_TASK_MANAGER_DESCRIPTOR =
            "android.app.IActivityTaskManager";
    private static final String WINDOW_ORGANIZER_DESCRIPTOR =
            "android.window.IWindowOrganizerController";
    private static final String TASK_ORGANIZER_DESCRIPTOR =
            "android.window.ITaskOrganizerController";

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
                requireCount(args, 7);
                result(commands.projectTask(
                        args[1], integer(args[2]), integer(args[3]), integer(args[4]),
                        integer(args[5]), integer(args[6])));
                return;
            case "return-task":
                requireCount(args, 6);
                result(commands.returnTask(
                        args[1], integer(args[2]), integer(args[3]),
                        integer(args[4]), integer(args[5]), true));
                return;
            case "restore-task":
                requireCount(args, 6);
                result(commands.returnTask(
                        args[1], integer(args[2]), integer(args[3]),
                        integer(args[4]), integer(args[5]), false));
                return;
            case "projection-origin":
                requireCount(args, 3);
                result(commands.projectionOrigin(args[1], integer(args[2])));
                return;
            case "create-root":
                requireCount(args, 2);
                result(commands.createProjectionRoot(integer(args[1])));
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
        System.out.flush();
        // Some organizer Binder proxies keep the app_process runtime alive
        // after main returns. This helper is deliberately one-shot: make the
        // shell sentinel observable immediately instead of timing out and
        // rolling back a successful command.
        System.exit(0);
    }

    static final class Commands {
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
                String packageName,
                int taskId,
                int projectionRootTaskId,
                int displayId,
                int width,
                int height) {
            enforceTask(packageName, taskId);
            int sourceRootTaskId = rootTaskIdContaining(taskId);
            Log.i(TAG, "project task=" + taskId + " sourceRoot=" + sourceRootTaskId
                    + " targetRoot=" + projectionRootTaskId + " display=" + displayId);
            if (sourceRootTaskId < 0) return false;

            // A standalone navigation task keeps the older proven whole-root
            // path. A child of a native split root is detached into a genuinely
            // empty organizer root. Never mix an ActivityRecord host with a
            // child Task: this ROM's focus path casts every root child to Task.
            if (sourceRootTaskId == taskId) {
                if (!moveTask(packageName, taskId, displayId)) return false;
                projectionRootTaskId = taskId;
            } else {
                enforceEmptyProjectionRoot(projectionRootTaskId, displayId);
                Log.i(TAG, "target root verified empty root=" + projectionRootTaskId);
                if (!moveTaskToRoot(taskId, projectionRootTaskId, true)) {
                    Log.e(TAG, "moveTaskToRootTask rejected task=" + taskId
                            + " root=" + projectionRootTaskId);
                    return false;
                }
                Log.i(TAG, "task moved task=" + taskId + " root=" + projectionRootTaskId);
            }
            Rect projectedBounds = new Rect(0, 0, width, height);
            // createRootTask(..., WINDOWING_MODE_FULLSCREEN, ...) already gives
            // the organizer root the full display bounds. DiLink rejects
            // resizeTask() for that organizer root; only its nested app task
            // needs the split-pane bounds cleared here.
            if (!resizeTaskUnchecked(taskId, projectedBounds)) {
                Log.e(TAG, "task resize rejected task=" + taskId);
                return false;
            }
            boolean focused = focusTask(packageName, taskId);
            Log.i(TAG, "project complete task=" + taskId + " focused=" + focused);
            return focused;
        }

        boolean returnTask(
                String packageName,
                int taskId,
                int sourceRootTaskId,
                int companionTaskId,
                int companionRootTaskId,
                boolean focusNavigation) {
            enforceTask(packageName, taskId);
            int currentDisplay = taskDisplayId(packageName, taskId);
            // Clear the virtual-display bounds before changing displays. Clearing
            // them after the move sends two rapid configuration changes on the
            // IVI (virtual size, then fullscreen); 2GIS exits between those two
            // relaunches and Android removes its now-empty task.
            if (!setTaskBounds(packageName, taskId, 0, 0, 0, 0)) return false;
            if (companionTaskId > 0 && companionRootTaskId > 0) {
                if (!isNativeSplitRoot(companionRootTaskId)
                        || !rootExistsOnDisplay(companionRootTaskId, 0)
                        || !taskExists(companionTaskId)
                        || companionTaskId == taskId) {
                    return false;
                }
                if (!resizeTaskUnchecked(companionTaskId, null)) return false;
                if (!moveTaskToRoot(companionTaskId, companionRootTaskId, true)) return false;
                if (!resizeTaskUnchecked(companionTaskId, null)) return false;
            }
            if (sourceRootTaskId != taskId && rootExistsOnDisplay(sourceRootTaskId, 0)) {
                if (!moveTaskToRoot(taskId, sourceRootTaskId, true)) return false;
                if (!setTaskBounds(packageName, taskId, 0, 0, 0, 0)) return false;
            } else if (currentDisplay > 0 && !moveTask(packageName, taskId, 0)) {
                return false;
            }
            return focusNavigation
                    ? focusTask(packageName, taskId)
                    : backgroundTask(packageName, taskId);
        }

        String projectionOrigin(String packageName, int taskId) {
            enforceTask(packageName, taskId);
            int sourceRootTaskId = rootTaskIdContaining(taskId);
            int firstRootTaskId = rootTaskIdForArea(1);
            int secondRootTaskId = rootTaskIdForArea(2);
            int companionRootTaskId = sourceRootTaskId == firstRootTaskId
                    ? secondRootTaskId
                    : sourceRootTaskId == secondRootTaskId ? firstRootTaskId : 0;
            int companionTaskId = companionRootTaskId > 0
                    ? topTaskInRoot(companionRootTaskId, taskId)
                    : 0;
            return sourceRootTaskId + "," + companionTaskId + "," + companionRootTaskId;
        }

        int createProjectionRoot(int displayId) {
            DisplayManager displayManager = context.getSystemService(DisplayManager.class);
            Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
            if (display == null || !"Denza Navigation".equals(display.getName())) return -1;

            List<?> before = rootTaskInfosOnDisplay(displayId);
            Set<Integer> existingRootIds = new HashSet<>();
            for (Object info : before) {
                Integer id = rootTaskInfoId(info);
                if (id != null) existingRootIds.add(id);
            }
            if (!invokeCreateRootTask(displayId)) return -1;

            for (int attempt = 0; attempt < 20; attempt++) {
                int createdRootId = findNewEmptyRootId(
                        displayId,
                        existingRootIds,
                        rootTaskInfosOnDisplay(displayId));
                if (createdRootId > 0) {
                    Log.i(TAG, "created empty root=" + createdRootId + " display=" + displayId);
                    return createdRootId;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            logProjectionRootSnapshot(displayId, existingRootIds);
            Log.e(TAG, "new empty root not found display=" + displayId);
            return -1;
        }

        private boolean invokeCreateRootTask(int displayId) {
            try {
                Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
                Object service = managerClass.getDeclaredMethod("getService").invoke(null);
                if (!(service instanceof IInterface)) return false;
                IBinder activityTaskManager = ((IInterface) service).asBinder();
                IBinder windowOrganizer = transactForBinder(
                        activityTaskManager,
                        ACTIVITY_TASK_MANAGER_DESCRIPTOR,
                        GET_WINDOW_ORGANIZER_CONTROLLER_TRANSACTION);
                IBinder taskOrganizer = transactForBinder(
                        windowOrganizer,
                        WINDOW_ORGANIZER_DESCRIPTOR,
                        GET_TASK_ORGANIZER_CONTROLLER_TRANSACTION);
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(TASK_ORGANIZER_DESCRIPTOR);
                    data.writeInt(displayId);
                    data.writeInt(1); // WINDOWING_MODE_FULLSCREEN
                    // A local Binder here keeps the one-shot app_process alive
                    // while system_server retains the launch cookie. This root
                    // is not matched to a registered organizer, so the cookie
                    // is intentionally null (TaskInfo ignores null cookies).
                    data.writeStrongBinder(null);
                    if (!taskOrganizer.transact(
                            CREATE_ROOT_TASK_TRANSACTION,
                            data,
                            reply,
                            0)) {
                        return false;
                    }
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
                return true;
            } catch (ReflectiveOperationException | RemoteException | RuntimeException error) {
                Log.e(TAG, "createRootTask Binder call failed", error);
                return false;
            }
        }

        private IBinder transactForBinder(
                IBinder target,
                String descriptor,
                int transaction) throws RemoteException {
            if (target == null) throw new RemoteException("missing Binder target");
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(descriptor);
                if (!target.transact(transaction, data, reply, 0)) {
                    throw new RemoteException("Binder transaction rejected");
                }
                reply.readException();
                IBinder result = reply.readStrongBinder();
                if (result == null) throw new RemoteException("Binder result missing");
                return result;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        boolean moveTask(String packageName, int taskId, int displayId) {
            enforceTask(packageName, taskId);
            int rootTaskId = rootTaskIdContaining(taskId);
            if (rootTaskId < 0) return false;
            if (rootTaskId != taskId && !invokeTaskManager(
                    new String[] {"moveTaskToRootTask"},
                    new Class<?>[] {int.class, int.class, boolean.class},
                    taskId,
                    rootTaskId,
                    true)) {
                return false;
            }
            return invokeTaskManager(
                    new String[] {"moveRootTaskToDisplay", "moveStackToDisplay"},
                    new Class<?>[] {int.class, int.class},
                    rootTaskId,
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
            return resizeTaskUnchecked(taskId, bounds);
        }

        private boolean resizeTaskUnchecked(int taskId, Rect bounds) {
            return invokeTaskManager(
                    new String[] {"resizeTask"},
                    new Class<?>[] {int.class, Rect.class, int.class},
                    taskId,
                    bounds,
                    0);
        }

        private boolean moveTaskToRoot(int taskId, int rootTaskId, boolean toTop) {
            return invokeTaskManager(
                    new String[] {"moveTaskToRootTask"},
                    new Class<?>[] {int.class, int.class, boolean.class},
                    taskId,
                    rootTaskId,
                    toTop);
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

        private void enforceEmptyProjectionRoot(int rootTaskId, int displayId) {
            for (Object info : rootTaskInfos()) {
                Integer id = rootTaskInfoId(info);
                Integer rootDisplayId = rootTaskInfoDisplayId(info);
                int[] children = rootTaskInfoChildTaskIds(info);
                if (id != null && id == rootTaskId
                        && rootDisplayId != null && rootDisplayId == displayId
                        && isEmptyOrganizerRoot(rootTaskId, children)) {
                    return;
                }
            }
            throw new SecurityException("task is not an empty navigation projection root");
        }

        private boolean taskExists(int taskId) {
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (task.taskId == taskId) return true;
            }
            return false;
        }

        private int topTaskInRoot(int rootTaskId, int excludedTaskId) {
            int fallbackTaskId = 0;
            for (ActivityManager.RunningTaskInfo task : tasks()) {
                if (task.taskId != excludedTaskId
                        && rootTaskIdContaining(task.taskId) == rootTaskId) {
                    if (isTaskVisible(task)) return task.taskId;
                    if (fallbackTaskId == 0) fallbackTaskId = task.taskId;
                }
            }
            return fallbackTaskId;
        }

        private boolean isTaskVisible(ActivityManager.RunningTaskInfo task) {
            try {
                return task.getClass().getField("isVisible").getBoolean(task);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }

        private boolean isNativeSplitRoot(int rootTaskId) {
            return rootTaskId == rootTaskIdForArea(1) || rootTaskId == rootTaskIdForArea(2);
        }

        private int rootTaskIdForArea(int areaId) {
            try {
                Class<?> managerClass = Class.forName("android.app.UnionActivityManager");
                Method getInstance = managerClass.getMethod("getInstance", Context.class);
                Object manager = getInstance.invoke(null, context);
                Method getRoot = managerClass.getMethod("getRootTaskIdByAreaId", int.class);
                Object value = getRoot.invoke(manager, areaId);
                return value instanceof Integer ? (Integer) value : -1;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return -1;
            }
        }

        private int rootTaskIdContaining(int taskId) {
            List<?> rootInfos = rootTaskInfos();
            int[] rootTaskIds = new int[rootInfos.size()];
            int[][] childTaskIds = new int[rootInfos.size()][];
            for (int index = 0; index < rootInfos.size(); index++) {
                Object info = rootInfos.get(index);
                Integer rootTaskId = rootTaskInfoId(info);
                if (rootTaskId == null) return -1;
                rootTaskIds[index] = rootTaskId;
                childTaskIds[index] = rootTaskInfoChildTaskIds(info);
            }
            return containingRootId(taskId, rootTaskIds, childTaskIds);
        }

        private boolean rootExistsOnDisplay(int rootTaskId, int displayId) {
            for (Object info : rootTaskInfos()) {
                Integer id = rootTaskInfoId(info);
                Integer rootDisplayId = rootTaskInfoDisplayId(info);
                if (id != null && id == rootTaskId
                        && rootDisplayId != null && rootDisplayId == displayId) {
                    return true;
                }
            }
            return false;
        }

        private List<?> rootTaskInfos() {
            try {
                Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
                Object service = managerClass.getDeclaredMethod("getService").invoke(null);
                Method method = service.getClass().getMethod("getAllRootTaskInfos");
                method.setAccessible(true);
                Object result = method.invoke(service);
                return result instanceof List<?>
                        ? (List<?>) result
                        : java.util.Collections.emptyList();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return java.util.Collections.emptyList();
            }
        }

        private List<?> rootTaskInfosOnDisplay(int displayId) {
            try {
                Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
                Object service = managerClass.getDeclaredMethod("getService").invoke(null);
                Method method = service.getClass().getMethod(
                        "getAllRootTaskInfosOnDisplay",
                        int.class);
                method.setAccessible(true);
                Object result = method.invoke(service, displayId);
                return result instanceof List<?>
                        ? (List<?>) result
                        : java.util.Collections.emptyList();
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e(TAG, "root snapshot failed display=" + displayId, error);
                return java.util.Collections.emptyList();
            }
        }

        private void logProjectionRootSnapshot(
                int displayId,
                Set<Integer> existingRootIds) {
            List<?> infos = rootTaskInfosOnDisplay(displayId);
            Log.e(TAG, "root snapshot display=" + displayId + " count=" + infos.size());
            for (Object info : infos) {
                Integer id = rootTaskInfoId(info);
                Integer rootDisplayId = rootTaskInfoDisplayId(info);
                int[] children = rootTaskInfoChildTaskIds(info);
                Log.e(TAG, "root candidate id=" + id + " display=" + rootDisplayId
                        + " children=" + (children == null ? "null" : children.length)
                        + " existed=" + (id != null && existingRootIds.contains(id)));
            }
        }

        private Integer rootTaskInfoId(Object info) {
            try {
                return info.getClass().getField("taskId").getInt(info);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private Integer rootTaskInfoDisplayId(Object info) {
            try {
                return info.getClass().getField("displayId").getInt(info);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private int[] rootTaskInfoChildTaskIds(Object info) {
            try {
                return (int[]) info.getClass().getField("childTaskIds").get(info);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private int findNewEmptyRootId(
                int displayId,
                Set<Integer> existingRootIds,
                List<?> rootInfos) {
            int[] existingIds = new int[existingRootIds.size()];
            int existingIndex = 0;
            for (Integer existingRootId : existingRootIds) {
                existingIds[existingIndex++] = existingRootId == null ? -1 : existingRootId;
            }
            int[] rootTaskIds = new int[rootInfos.size()];
            int[] displayIds = new int[rootInfos.size()];
            int[][] childTaskIds = new int[rootInfos.size()][];
            for (int index = 0; index < rootInfos.size(); index++) {
                Object info = rootInfos.get(index);
                Integer id = rootTaskInfoId(info);
                Integer rootDisplayId = rootTaskInfoDisplayId(info);
                rootTaskIds[index] = id == null ? -1 : id;
                displayIds[index] = rootDisplayId == null ? -1 : rootDisplayId;
                childTaskIds[index] = rootTaskInfoChildTaskIds(info);
            }
            return newEmptyRootId(
                    displayId,
                    existingIds,
                    rootTaskIds,
                    displayIds,
                    childTaskIds);
        }

        static int newEmptyRootId(
                int expectedDisplayId,
                int[] existingRootTaskIds,
                int[] rootTaskIds,
                int[] displayIds,
                int[][] childTaskIds) {
            if (existingRootTaskIds == null || rootTaskIds == null
                    || displayIds == null || childTaskIds == null
                    || rootTaskIds.length != displayIds.length
                    || rootTaskIds.length != childTaskIds.length) {
                return -1;
            }
            int candidate = -1;
            for (int index = 0; index < rootTaskIds.length; index++) {
                int rootTaskId = rootTaskIds[index];
                if (rootTaskId <= 0 || displayIds[index] != expectedDisplayId
                        || !isEmptyOrganizerRoot(rootTaskId, childTaskIds[index])
                        || contains(existingRootTaskIds, rootTaskId)) {
                    continue;
                }
                // A concurrent organizer could create another root between the
                // two snapshots. Never guess which empty root belongs to us.
                if (candidate > 0) return -1;
                candidate = rootTaskId;
            }
            return candidate;
        }

        private static boolean isEmptyOrganizerRoot(int rootTaskId, int[] childTaskIds) {
            // DiLink's RootTaskInfo flattens leaf tasks. A root without nested
            // tasks is itself the single leaf, so its representation is [rootId],
            // not an empty array. Once an app task is nested, its task id replaces
            // this self-only representation.
            return childTaskIds != null
                    && childTaskIds.length == 1
                    && childTaskIds[0] == rootTaskId;
        }

        private static boolean contains(int[] values, int expected) {
            for (int value : values) {
                if (value == expected) return true;
            }
            return false;
        }

        static int containingRootId(
                int taskId,
                int[] rootTaskIds,
                int[][] childTaskIds) {
            if (rootTaskIds == null || childTaskIds == null
                    || rootTaskIds.length != childTaskIds.length) {
                return -1;
            }
            for (int index = 0; index < rootTaskIds.length; index++) {
                if (rootTaskIds[index] == taskId) return rootTaskIds[index];
                int[] children = childTaskIds[index];
                if (children == null) continue;
                for (int childTaskId : children) {
                    if (childTaskId == taskId) return rootTaskIds[index];
                }
            }
            return -1;
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
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e(TAG, "activity task Binder call failed names=" + Arrays.toString(names), error);
                return false;
            }
            return false;
        }
    }
}
