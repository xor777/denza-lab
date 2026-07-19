package dev.denza.mirrors.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Archived research-only service.
 *
 * Keep this outside legacy/denza-mirrors/src so it is not compiled into the frozen app.
 * The service reflected BYD vehicle APIs, registered listeners, and sampled safe getters
 * while writing results to app-private files.
 */
public class VehicleEventProbeService extends Service {
    static final String ACTION_START =
            "dev.denza.mirrors.START_VEHICLE_EVENT_PROBE";
    static final String ACTION_STOP =
            "dev.denza.mirrors.STOP_VEHICLE_EVENT_PROBE";

    static final String STATUS_FILE_NAME = "vehicle_event_probe_status.txt";
    static final String LOG_FILE_NAME = "vehicle_event_probe.log";

    private static final String TAG = "DenzaVehicleProbe";
    private static final String CHANNEL_ID = "denza_vehicle_event_probe";
    private static final String PREFS_NAME = "vehicle_event_probe";
    private static final String KEY_ENABLED = "enabled";
    private static final int NOTIFICATION_ID = 78;
    private static final long DEFAULT_SAMPLE_MS = 500L;
    private static final int MAX_LOG_BYTES = 512 * 1024;
    private static final int MAX_METHODS_PER_DEVICE = 80;
    private static final int MAX_PROPERTY_SUBSCRIPTIONS = 48;

    private static final Pattern INTERESTING_FIELD = Pattern.compile(
            ".*(TURN|SIGNAL|SPEED|GEAR|LIGHT|LAMP|DOOR|BRAKE|PARK|VEHICLE|"
                    + "MILE|SOC|POWER|ADAS|INSTRUMENT|HUD|CAMERA|PANORAMA).*");

    private static final String[][] LEGACY_DEVICES = new String[][] {
            {"light", "android.hardware.bydauto.light.BYDAutoLightDevice"},
            {"speed", "android.hardware.bydauto.speed.BYDAutoSpeedDevice"},
            {"gearbox", "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"},
            {"setting", "android.hardware.bydauto.setting.BYDAutoSettingDevice"},
            {"instrument", "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"},
            {"bodywork", "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"},
            {"power", "android.hardware.bydauto.power.BYDAutoPowerDevice"},
            {"adas", "android.hardware.bydauto.adas.BYDAutoADASDevice"},
            {"vehicledata", "android.hardware.bydauto.vehicledata.BYDAutoVehicleDataDevice"},
            {"panorama", "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice"}
    };

    private static final String[] FEATURE_ID_CLASSES = new String[] {
            "android.hardware.bydauto.BYDAutoFeatureIds$Light",
            "android.hardware.bydauto.BYDAutoFeatureIds$Speed",
            "android.hardware.bydauto.BYDAutoFeatureIds$Gearbox",
            "android.hardware.bydauto.BYDAutoFeatureIds$Setting",
            "android.hardware.bydauto.BYDAutoFeatureIds$Instrument",
            "android.hardware.bydauto.BYDAutoFeatureIds$Bodywork",
            "android.hardware.bydauto.BYDAutoFeatureIds$Power",
            "android.hardware.bydauto.BYDAutoFeatureIds$Adas",
            "android.hardware.bydauto.BYDAutoFeatureIds$Vehicle",
            "android.hardware.bydauto.BYDAutoFeatureIds$Panorama",
            "com.byd.car.property.CarProperty",
            "com.byd.car.feature.adas.driving.DrivingAssistEventIds$DrivingAssist",
            "com.byd.car.feature.adas.parking.ParkAssistEventIds$ParkAssist",
            "com.byd.car.feature.adas.safety.SafetyAssistEventIds$SafetyAssist",
            "com.byd.car.feature.vision.event.VisionEventIds$HudEvents",
            "com.byd.car.feature.vision.event.VisionEventIds$CmsEvents"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final List<SampleSource> sampleSources = new ArrayList<>();
    private final List<Object> listenerRefs = new ArrayList<>();
    private final Map<String, String> lastValues = new LinkedHashMap<>();
    private final Map<String, Integer> sampleFailureCounts = new LinkedHashMap<>();
    private final List<ContentObserver> contentObservers = new ArrayList<>();
    private final Set<Integer> carPropertyIds = new LinkedHashSet<>();
    private final Map<Integer, String> carPropertyNames = new LinkedHashMap<>();
    private final Map<String, List<Integer>> legacyFeatureIdsByLabel = new LinkedHashMap<>();

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private long sampleMs = DEFAULT_SAMPLE_MS;
    private long lastSampleErrorMs;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopProbe(true);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null) {
            sampleMs = Math.max(200L, intent.getLongExtra("sample_ms", DEFAULT_SAMPLE_MS));
        }
        moveToForeground("Starting vehicle probe");
        startProbe();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopProbe(false);
        super.onDestroy();
    }

    private void startProbe() {
        setProbeEnabled(this, true);
        if (running) {
            setStatus("vehicle probe already running sources=" + sampleSources.size());
            return;
        }
        running = true;
        sampleSources.clear();
        listenerRefs.clear();
        lastValues.clear();
        sampleFailureCounts.clear();
        carPropertyIds.clear();
        carPropertyNames.clear();
        legacyFeatureIdsByLabel.clear();
        appendLog("---- vehicle probe session start sample_ms=" + sampleMs + " ----");
        setStatus("vehicle probe starting");
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.execute(this::initializeProbe);
        executor.scheduleWithFixedDelay(this::sampleAll, sampleMs, sampleMs, TimeUnit.MILLISECONDS);
    }

    private void stopProbe(boolean disableAutostart) {
        if (disableAutostart) {
            setProbeEnabled(this, false);
        }
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        for (ContentObserver observer : new ArrayList<>(contentObservers)) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (RuntimeException e) {
                appendLog("content observer unregister failed: " + shortError(e));
            }
        }
        contentObservers.clear();
        setStatus("vehicle probe stopped");
        appendLog("---- vehicle probe stopped ----");
    }

    private void initializeProbe() {
        appendLog("device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " sdk=" + Build.VERSION.SDK_INT);
        discoverFeatureIds();
        registerContentObservers();
        discoverLegacyDevices();
        discoverDiCarProperties();
        String status = "vehicle probe running sources=" + sampleSources.size()
                + " listeners=" + listenerRefs.size()
                + " properties=" + carPropertyIds.size();
        setStatus(status);
        moveToForeground(status);
        sampleAll();
    }

    private void discoverFeatureIds() {
        for (String className : FEATURE_ID_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                int count = 0;
                for (Field field : sortedFields(clazz)) {
                    if (!Modifier.isStatic(field.getModifiers()) || !isScalarType(field.getType())) {
                        continue;
                    }
                    String fieldName = field.getName();
                    if (!INTERESTING_FIELD.matcher(fieldName).matches()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(null);
                    appendLog("field " + clazz.getName() + "." + fieldName + "=" + value);
                    if ("com.byd.car.property.CarProperty".equals(className)
                            && value instanceof Number) {
                        int propertyId = ((Number) value).intValue();
                        carPropertyIds.add(propertyId);
                        carPropertyNames.put(propertyId, fieldName);
                    }
                    String legacyLabel = legacyLabelForFeatureClass(className);
                    if (legacyLabel != null && value instanceof Number) {
                        addLegacyFeatureId(legacyLabel, ((Number) value).intValue());
                    }
                    count++;
                }
                appendLog("field scan " + className + " matched=" + count);
            } catch (Throwable e) {
                appendLog("field scan failed " + className + ": " + shortError(e));
            }
        }
    }

    private void registerContentObservers() {
        registerContentObserver("carsettings.config", Uri.parse("content://carsettings/config"));
        registerContentObserver("carsettings.global", Uri.parse("content://carsettings/global"));
        registerContentObserver("carstatus", Uri.parse("content://com.byd.carStatusProvider/car_status"));
    }

    private void registerContentObserver(String label, Uri uri) {
        try {
            ContentObserver observer = new ContentObserver(mainHandler) {
                @Override
                public void onChange(boolean selfChange, Uri changedUri) {
                    appendLog("content change " + label + " uri="
                            + (changedUri == null ? uri : changedUri));
                    executorExecute(() -> logContentSnapshot(label, uri, 24));
                }
            };
            getContentResolver().registerContentObserver(uri, true, observer);
            contentObservers.add(observer);
            appendLog("content observer registered " + label + " " + uri);
            logContentSnapshot(label, uri, 24);
        } catch (Throwable e) {
            appendLog("content observer failed " + label + ": " + shortError(e));
        }
    }

    private void discoverLegacyDevices() {
        for (String[] device : LEGACY_DEVICES) {
            String label = device[0];
            String className = device[1];
            try {
                Class<?> clazz = Class.forName(className);
                Object instance = createLegacyDevice(clazz);
                if (instance == null) {
                    appendLog("legacy " + label + " no getInstance method");
                    continue;
                }
                appendLog("legacy " + label + " instance=" + instance.getClass().getName());
                logPublicMethods("legacy." + label, clazz, true);
                int added = 0;
                for (Method method : sortedMethods(clazz)) {
                    if (added >= MAX_METHODS_PER_DEVICE) {
                        break;
                    }
                    if (isSampleGetter(method)) {
                        method.setAccessible(true);
                        sampleSources.add(new MethodSampleSource("legacy." + label + "."
                                + method.getName(), instance, method));
                        added++;
                    }
                }
                appendLog("legacy " + label + " sample getters=" + added);
                tryRegisterLegacyGenericListener(label, clazz, instance);
            } catch (Throwable e) {
                appendLog("legacy " + label + " failed: " + shortError(e));
            }
        }
    }

    private void tryRegisterLegacyGenericListener(String label, Class<?> deviceClass, Object instance) {
        int registered = 0;
        Set<Class<?>> registeredTypes = new LinkedHashSet<>();
        for (Method method : sortedMethods(deviceClass)) {
            if (!"registerListener".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 && params.length != 2) {
                continue;
            }
            Class<?> listenerType = params[0];
            if (!listenerType.getSimpleName().toUpperCase(Locale.US).contains("LISTENER")) {
                continue;
            }
            if (!listenerType.isInterface()) {
                appendLog("legacy listener skip " + label + " " + methodSignature(method)
                        + " type=" + listenerType.getName() + " not interface");
                continue;
            }
            if (registeredTypes.contains(listenerType)) {
                continue;
            }
            Object[] args;
            if (params.length == 1) {
                args = new Object[] { newLoggingProxy(listenerType, "legacy." + label) };
            } else if (params[1] == int[].class) {
                int[] featureList = readFeatureList(label, deviceClass, instance);
                args = new Object[] {
                        newLoggingProxy(listenerType, "legacy." + label),
                        featureList
                };
            } else {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(instance, args);
                listenerRefs.add(args[0]);
                registeredTypes.add(listenerType);
                registered++;
                String features = params.length == 2
                        ? " features=" + ((int[]) args[1]).length
                        : "";
                appendLog("legacy listener registered " + label
                        + " " + methodSignature(method)
                        + " type=" + listenerType.getName()
                        + features);
            } catch (Throwable e) {
                appendLog("legacy listener failed " + label + " "
                        + methodSignature(method) + ": " + shortError(e));
            }
        }
        if (registered == 0) {
            appendLog("legacy listener unavailable " + label);
        }
    }

    private int[] readFeatureList(String label, Class<?> deviceClass, Object instance) {
        try {
            Method getFeatureList = deviceClass.getMethod("getFeatureList");
            Object value = getFeatureList.invoke(instance);
            if (value instanceof int[] && ((int[]) value).length > 0) {
                return (int[]) value;
            }
        } catch (Throwable e) {
            appendLog("legacy feature list failed " + deviceClass.getSimpleName()
                    + ": " + shortError(e));
        }
        int[] fallback = legacyFeatureIds(label);
        if (fallback.length > 0) {
            appendLog("legacy feature fallback " + label + " features=" + fallback.length);
        }
        return fallback;
    }

    private void addLegacyFeatureId(String label, int value) {
        List<Integer> ids = legacyFeatureIdsByLabel.get(label);
        if (ids == null) {
            ids = new ArrayList<>();
            legacyFeatureIdsByLabel.put(label, ids);
        }
        if (!ids.contains(value)) {
            ids.add(value);
        }
    }

    private int[] legacyFeatureIds(String label) {
        List<Integer> ids = legacyFeatureIdsByLabel.get(label);
        if (ids == null || ids.isEmpty()) {
            return new int[0];
        }
        int[] values = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            values[i] = ids.get(i);
        }
        return values;
    }

    private static String legacyLabelForFeatureClass(String className) {
        String prefix = "android.hardware.bydauto.BYDAutoFeatureIds$";
        if (!className.startsWith(prefix)) {
            return null;
        }
        String suffix = className.substring(prefix.length()).toLowerCase(Locale.US);
        if ("vehicle".equals(suffix)) {
            return "vehicledata";
        }
        return suffix;
    }

    private Object createLegacyDevice(Class<?> clazz) throws ReflectiveOperationException {
        try {
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            return getInstance.invoke(null, getApplicationContext());
        } catch (NoSuchMethodException ignored) {
            try {
                Method getInstance = clazz.getMethod("getInstance");
                return getInstance.invoke(null);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }

    private void discoverDiCarProperties() {
        Object manager = null;
        Class<?> managerClass = null;
        try {
            managerClass = Class.forName("com.byd.car.property.ICarPropertyManager");
            Class<?> diCarClass = Class.forName("com.byd.car.DiCar");
            manager = diCarClass.getMethod("getCarManager", Context.class, Class.class)
                    .invoke(null, getApplicationContext(), managerClass);
            appendLog("dicar property manager=" + safeClassName(manager));
            logPublicMethods("dicar.property", managerClass, true);
        } catch (Throwable e) {
            appendLog("dicar property manager failed: " + shortError(e));
        }

        if (manager == null || managerClass == null) {
            return;
        }

        Method getCarProperty = findMethod(managerClass, "getCarProperty", int.class);
        if (getCarProperty != null) {
            getCarProperty.setAccessible(true);
            int added = 0;
            for (int propertyId : carPropertyIds) {
                if (added >= MAX_METHODS_PER_DEVICE) {
                    break;
                }
                sampleSources.add(new CarPropertySampleSource(
                        "dicar.property." + carPropertyNames.get(propertyId),
                        manager,
                        getCarProperty,
                        propertyId));
                added++;
            }
            appendLog("dicar property sample ids=" + added);
        } else {
            appendLog("dicar property getCarProperty(int) not found");
        }

        tryRegisterPropertyListeners(manager, managerClass);
        logVehicleEventWrapper();
    }

    private void tryRegisterPropertyListeners(Object manager, Class<?> managerClass) {
        int subscriptions = 0;
        for (Method method : sortedMethods(managerClass)) {
            if (!"registerPropertyListener".equals(method.getName())) {
                continue;
            }
            appendLog("dicar register candidate " + methodSignature(method));
            for (int propertyId : carPropertyIds) {
                if (subscriptions >= MAX_PROPERTY_SUBSCRIPTIONS) {
                    return;
                }
                String propertyName = carPropertyNames.get(propertyId);
                Object[] args = buildRegistrationArgs(method, propertyId, propertyName);
                if (args == null) {
                    break;
                }
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(manager, args);
                    appendLog("dicar listener registered " + propertyName
                            + " result=" + summarize(result));
                    subscriptions++;
                } catch (Throwable e) {
                    appendLog("dicar listener failed " + propertyName + ": " + shortError(e));
                    break;
                }
            }
        }
    }

    private Object[] buildRegistrationArgs(Method method, int propertyId, String propertyName) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean hasListener = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == int.class || type == Integer.class) {
                args[i] = propertyId;
            } else if (type == float.class || type == Float.class) {
                args[i] = 1.0f;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = true;
            } else if (type.isInterface()) {
                args[i] = newLoggingProxy(type, "property." + propertyName);
                hasListener = true;
            } else {
                appendLog("dicar listener unsupported arg " + type.getName()
                        + " for " + methodSignature(method));
                return null;
            }
        }
        return hasListener ? args : null;
    }

    private Object newLoggingProxy(Class<?> listenerClass, String label) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) {
                    return "VehicleEventProbeProxy(" + label + ")";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == (args == null ? null : args[0]);
                }
            }
            appendLog("callback " + label + "." + method.getName()
                    + " args=" + summarizeArgs(args));
            return defaultValue(method.getReturnType());
        };
        return Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[] { listenerClass }, handler);
    }

    private void logVehicleEventWrapper() {
        try {
            Class<?> clazz = Class.forName("com.byd.car.feature.VehicleEventWrapper");
            logPublicMethods("dicar.vehicleEventWrapper", clazz, true);
        } catch (Throwable e) {
            appendLog("dicar vehicleEventWrapper not available: " + shortError(e));
        }
    }

    private void sampleAll() {
        if (!running) {
            return;
        }
        Iterator<SampleSource> iterator = sampleSources.iterator();
        while (iterator.hasNext()) {
            SampleSource source = iterator.next();
            try {
                String value = source.sample();
                String old = lastValues.put(source.label(), value);
                if (old == null || !old.equals(value)) {
                    appendLog("sample " + source.label() + "=" + value);
                }
            } catch (Throwable e) {
                Throwable root = rootCause(e);
                int failures = sampleFailureCounts.containsKey(source.label())
                        ? sampleFailureCounts.get(source.label()) + 1
                        : 1;
                sampleFailureCounts.put(source.label(), failures);
                long now = System.currentTimeMillis();
                if (now - lastSampleErrorMs > 3000L) {
                    lastSampleErrorMs = now;
                    appendLog("sample failed " + source.label() + ": " + shortError(e));
                }
                if (root instanceof SecurityException || failures >= 3) {
                    iterator.remove();
                    appendLog("sample disabled " + source.label()
                            + " failures=" + failures
                            + " reason=" + root.getClass().getSimpleName());
                }
            }
        }
    }

    private void logContentSnapshot(String label, Uri uri, int maxRows) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor == null) {
                appendLog("content snapshot " + label + " cursor=null");
                return;
            }
            int keyIndex = cursor.getColumnIndex("key");
            int valueIndex = cursor.getColumnIndex("value");
            int rows = 0;
            while (cursor.moveToNext() && rows < maxRows) {
                String key = keyIndex >= 0 ? cursor.getString(keyIndex) : "";
                String value = valueIndex >= 0 ? cursor.getString(valueIndex) : rowToString(cursor);
                if (key.isEmpty() || INTERESTING_FIELD.matcher(key.toUpperCase(Locale.US)).matches()) {
                    appendLog("content " + label + " " + key + "=" + compact(value, 160));
                    rows++;
                }
            }
            appendLog("content snapshot " + label + " rows_logged=" + rows);
        } catch (Throwable e) {
            appendLog("content snapshot failed " + label + ": " + shortError(e));
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private String rowToString(Cursor cursor) {
        StringBuilder builder = new StringBuilder();
        String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(columns[i]).append('=').append(cursor.getString(i));
        }
        return builder.toString();
    }

    private void logPublicMethods(String label, Class<?> clazz, boolean interestingOnly) {
        int count = 0;
        for (Method method : sortedMethods(clazz)) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            String signature = methodSignature(method);
            String upper = signature.toUpperCase(Locale.US);
            if (interestingOnly && !(upper.contains("TURN")
                    || upper.contains("SIGNAL")
                    || upper.contains("SPEED")
                    || upper.contains("GEAR")
                    || upper.contains("LIGHT")
                    || upper.contains("LISTENER")
                    || upper.contains("PROPERTY")
                    || upper.contains("EVENT")
                    || upper.contains("REGISTER")
                    || upper.contains("GET"))) {
                continue;
            }
            appendLog("method " + label + "." + signature);
            count++;
            if (count >= 120) {
                appendLog("method " + label + " truncated");
                break;
            }
        }
    }

    private void executorExecute(Runnable runnable) {
        ScheduledExecutorService current = executor;
        if (current != null && !current.isShutdown()) {
            current.execute(runnable);
        }
    }

    private void setStatus(String status) {
        Log.i(TAG, status);
        writeFile(new File(getFilesDir(), STATUS_FILE_NAME), status + "\n", false);
    }

    private synchronized void appendLog(String message) {
        String line = timeFormat.format(new Date()) + " " + message;
        Log.i(TAG, line);
        File logFile = new File(getFilesDir(), LOG_FILE_NAME);
        if (logFile.length() > MAX_LOG_BYTES) {
            writeFile(logFile, timeFormat.format(new Date()) + " log rotated\n", false);
        }
        writeFile(logFile, line + "\n", true);
    }

    private void writeFile(File file, String text, boolean append) {
        try (FileOutputStream output = new FileOutputStream(file, append)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.i(TAG, "write failed " + file.getName(), e);
        }
    }

    private void moveToForeground(String text) {
        Notification notification = buildNotification(text);
        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(this, 11,
                new Intent(this, VehicleEventProbeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Denza vehicle events")
                .setContentText(text)
                .setOngoing(running)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Denza vehicle events",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Read-only vehicle event probe");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    static void start(Context context) {
        setProbeEnabled(context, true);
        Intent intent = new Intent(context, VehicleEventProbeService.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        setProbeEnabled(context, false);
        context.startService(new Intent(context, VehicleEventProbeService.class)
                .setAction(ACTION_STOP));
    }

    static boolean isProbeEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static String readStatus(Context context) {
        return readFileTail(new File(context.getFilesDir(), STATUS_FILE_NAME), 4096);
    }

    static String readLogTail(Context context) {
        return readFileTail(new File(context.getFilesDir(), LOG_FILE_NAME), 8192);
    }

    private static void setProbeEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    private static String readFileTail(File file, int maxBytes) {
        if (!file.exists()) {
            return "";
        }
        int size = (int) Math.min(file.length(), maxBytes);
        byte[] buffer = new byte[size];
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = Math.max(0L, file.length() - size);
            while (skip > 0L) {
                long skipped = input.skip(skip);
                if (skipped <= 0L) {
                    break;
                }
                skip -= skipped;
            }
            int read = input.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            return clazz.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static boolean isSampleGetter(Method method) {
        if (Modifier.isStatic(method.getModifiers())
                || method.getParameterTypes().length != 0
                || method.getReturnType() == Void.TYPE
                || method.getDeclaringClass() == Object.class) {
            return false;
        }
        String name = method.getName();
        if (!(name.startsWith("get") || name.startsWith("is"))) {
            return false;
        }
        if ("getClass".equals(name) || "getInstance".equals(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        return upper.contains("TURN")
                || upper.contains("SIGNAL")
                || upper.contains("SPEED")
                || upper.contains("GEAR")
                || upper.contains("LIGHT")
                || upper.contains("LAMP")
                || upper.contains("DOOR")
                || upper.contains("BRAKE")
                || upper.contains("PARK")
                || upper.contains("MILE")
                || upper.contains("SOC")
                || upper.contains("POWER")
                || upper.contains("VEHICLE")
                || upper.contains("HUD")
                || upper.contains("PANORAMA");
    }

    private static boolean isScalarType(Class<?> type) {
        return type.isPrimitive()
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == String.class
                || type == Character.class;
    }

    private static List<Field> sortedFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>(Arrays.asList(clazz.getFields()));
        Collections.sort(fields, (a, b) -> a.getName().compareTo(b.getName()));
        return fields;
    }

    private static List<Method> sortedMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>(Arrays.asList(clazz.getMethods()));
        Collections.sort(methods, (a, b) -> methodSignature(a).compareTo(methodSignature(b)));
        return methods;
    }

    private static String methodSignature(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getReturnType().getSimpleName())
                .append(' ')
                .append(method.getName())
                .append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(types[i].getSimpleName());
        }
        builder.append(')');
        return builder.toString();
    }

    private static String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(summarize(args[i]));
        }
        return builder.append(']').toString();
    }

    private static String summarize(Object value) {
        return summarize(value, 0);
    }

    private static String summarize(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return compact(String.valueOf(value), 240);
        }
        Class<?> clazz = value.getClass();
        if (clazz.isArray()) {
            return clazz.getComponentType().getSimpleName() + "[]";
        }
        if (depth > 1) {
            return compact(String.valueOf(value), 180);
        }
        StringBuilder builder = new StringBuilder(clazz.getSimpleName()).append('{');
        boolean appended = false;
        for (String methodName : new String[] {
                "getPropertyId", "getAreaId", "getValue", "getData", "getStatus", "getTimestamp"}) {
            try {
                Method method = clazz.getMethod(methodName);
                if (method.getParameterTypes().length != 0) {
                    continue;
                }
                Object inner = method.invoke(value);
                if (appended) {
                    builder.append(',');
                }
                builder.append(methodName.substring(3)).append('=').append(summarize(inner, depth + 1));
                appended = true;
            } catch (Throwable ignored) {
                // Best-effort object projection for unknown BYD parcelables.
            }
        }
        if (!appended) {
            builder.append(compact(String.valueOf(value), 180));
        }
        builder.append('}');
        return compact(builder.toString(), 300);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Byte.TYPE) {
            return (byte) 0;
        }
        if (type == Short.TYPE) {
            return (short) 0;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Float.TYPE) {
            return 0f;
        }
        if (type == Double.TYPE) {
            return 0d;
        }
        if (type == Character.TYPE) {
            return '\0';
        }
        return null;
    }

    private static String compact(String value, int maxLength) {
        String compact = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        while (compact.contains("  ")) {
            compact = compact.replace("  ", " ");
        }
        if (compact.length() > maxLength) {
            return compact.substring(0, maxLength) + "...";
        }
        return compact;
    }

    private static String safeClassName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String shortError(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        if (message == null || message.isEmpty()) {
            return root.getClass().getSimpleName();
        }
        return root.getClass().getSimpleName() + " " + message;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        if (throwable instanceof InvocationTargetException
                && ((InvocationTargetException) throwable).getTargetException() != null) {
            root = ((InvocationTargetException) throwable).getTargetException();
        }
        return root;
    }

    private interface SampleSource {
        String label();

        String sample() throws ReflectiveOperationException;
    }

    private static final class MethodSampleSource implements SampleSource {
        private final String label;
        private final Object target;
        private final Method method;

        MethodSampleSource(String label, Object target, Method method) {
            this.label = label;
            this.target = target;
            this.method = method;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String sample() throws ReflectiveOperationException {
            return summarize(method.invoke(target));
        }
    }

    private static final class CarPropertySampleSource implements SampleSource {
        private final String label;
        private final Object manager;
        private final Method method;
        private final int propertyId;

        CarPropertySampleSource(String label, Object manager, Method method, int propertyId) {
            this.label = label;
            this.manager = manager;
            this.method = method;
            this.propertyId = propertyId;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String sample() throws ReflectiveOperationException {
            return summarize(method.invoke(manager, propertyId));
        }
    }
}
