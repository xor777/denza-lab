package dev.denza.personbean.probe;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell-only seam that exercises the AutoVoice PersonBean provider from an ordinary app UID.
 *
 * <p>Every action answers on the ordered-broadcast result channel with code 0 (the operation ran)
 * or 1 (it threw) and one line of space-separated {@code key=value} tokens. Judging the values -
 * row counts, matched counts, readbacks - is the host script's job, not this receiver's.
 */
public final class ProbeReceiver extends BroadcastReceiver {

    static final String TAG = "PersonBeanProbe";

    private static final String PREFIX = "dev.denza.personbean.probe.";
    static final String ACTION_QUERY = PREFIX + "QUERY";
    static final String ACTION_UPDATE = PREFIX + "UPDATE";
    static final String ACTION_OBSERVE = PREFIX + "OBSERVE";
    static final String ACTION_REPORT = PREFIX + "REPORT";

    static final Uri AUTHORITY_URI = Uri.parse("content://com.byd.autovoice");
    static final Uri PERSON_BEAN_URI = Uri.parse("content://com.byd.autovoice/PersonBean");

    private static final String COLUMN_SETTING = "SETTING";
    private static final String COLUMN_VALUE = "VALUE";
    private static final String[] PROJECTION = {"_id", COLUMN_SETTING, COLUMN_VALUE};
    private static final String[] ROLES = {"DEFAULT_MAP_SWITCH", "MUSIC_SWITCH", "VIDEO_SWITCH"};

    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private static final long NOTIFY_TIMEOUT_MS = 3000L;

    /** One value per process, so the host can tell a survived process from a restarted one. */
    static final String NONCE = String.format(Locale.US, "%08x", new SecureRandom().nextInt());

    @Override
    public void onReceive(Context context, Intent intent) {
        // This receiver always answers and never throws, so formatting and the result channel
        // sit inside the guard as well, not only the provider work.
        try {
            String action = intent == null ? null : intent.getAction();
            StringBuilder line = new StringBuilder()
                    .append("op=").append(operation(action))
                    .append(" uid=").append(Process.myUid())
                    .append(" pkg=").append(context == null ? "none" : token(context.getPackageName()))
                    .append(" nonce=").append(NONCE);

            boolean ok;
            try {
                line.append(' ').append(run(context, action, intent));
                ok = true;
            } catch (Throwable error) {
                line.append(" error=").append(errorName(error))
                        .append(" detail=").append(detail(error));
                ok = false;
            }

            String result = line.toString();
            Log.i(TAG, result);
            if (isOrderedBroadcast()) {
                setResultCode(ok ? 0 : 1);
                setResultData(result);
            }
        } catch (Throwable fatal) {
            Log.e(TAG, "probe failed to answer", fatal);
        }
    }

    private String run(Context context, String action, Intent intent) {
        if (context == null || action == null) {
            throw new IllegalArgumentException("action=" + action);
        }
        switch (action) {
            case ACTION_QUERY:
                return query(context, intent.getStringExtra("role"));
            case ACTION_UPDATE:
                return update(context, intent.getStringExtra("role"),
                        intent.getStringExtra("expected"), intent.getStringExtra("value"));
            case ACTION_OBSERVE:
                return "registered=true already=" + ProcessObserver.register(context);
            case ACTION_REPORT:
                return report();
            default:
                throw new IllegalArgumentException("action=" + action);
        }
    }

    private String query(Context context, String role) {
        String selection = role == null ? "SETTING IN (?,?,?)" : "SETTING=?";
        String[] arguments = role == null ? ROLES.clone() : new String[] {requireRole(role)};

        StringBuilder rows = new StringBuilder();
        int count = 0;
        long elapsedNanos;
        long startedNanos = SystemClock.elapsedRealtimeNanos();
        Cursor cursor = context.getContentResolver()
                .query(PERSON_BEAN_URI, PROJECTION, selection, arguments, null);
        if (cursor == null) {
            throw new NullCursorException("selection=" + selection);
        }
        try {
            int settingColumn = cursor.getColumnIndexOrThrow(COLUMN_SETTING);
            int valueColumn = cursor.getColumnIndexOrThrow(COLUMN_VALUE);
            while (cursor.moveToNext()) {
                rows.append(" r").append(count).append('=')
                        .append(token(cursor.getString(settingColumn))).append(':')
                        .append(token(cursor.getString(valueColumn)));
                count++;
            }
            elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedNanos;
        } finally {
            cursor.close();
        }
        return "rows=" + count + " query_ms=" + millis(elapsedNanos) + rows;
    }

    private String update(Context context, String role, String expected, String value) {
        if (role == null || expected == null || value == null) {
            throw new IllegalArgumentException("missing_extras_role_expected_value");
        }
        requireRole(role);
        requirePackage(expected);
        requirePackage(value);

        ContentValues values = new ContentValues();
        values.put(COLUMN_VALUE, value);
        ContentResolver resolver = context.getContentResolver();

        // The observer gets its own looper so that waiting on the latch below - which happens on
        // the receiver's main thread - cannot starve the callback it is waiting for.
        HandlerThread thread = new HandlerThread("personbean-update");
        thread.start();
        CountDownLatch change = new CountDownLatch(1);
        long[] notifiedAtNanos = {-1L};
        ContentObserver observer = new ContentObserver(new Handler(thread.getLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                notifiedAtNanos[0] = SystemClock.elapsedRealtimeNanos();
                change.countDown();
            }
        };
        resolver.registerContentObserver(PERSON_BEAN_URI, true, observer);
        try {
            long startedNanos = SystemClock.elapsedRealtimeNanos();
            int count = resolver.update(PERSON_BEAN_URI, values, "SETTING=? AND VALUE=?",
                    new String[] {role, expected});
            long updateNanos = SystemClock.elapsedRealtimeNanos() - startedNanos;

            boolean notified = change.await(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // Measured from the moment the write was issued, not from the moment it returned.
            String notifyMs = notified ? millis(notifiedAtNanos[0] - startedNanos) : "-1";

            return "role=" + token(role)
                    + " expected=" + token(expected)
                    + " value=" + token(value)
                    + " count=" + count
                    + " update_ms=" + millis(updateNanos)
                    + " notified=" + notified
                    + " notify_ms=" + notifyMs
                    + " " + readback(resolver, role);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted_waiting_for_change");
        } finally {
            resolver.unregisterContentObserver(observer);
            thread.quitSafely();
        }
    }

    private String readback(ContentResolver resolver, String role) {
        String value = "none";
        int rows = 0;
        long elapsedNanos;
        long startedNanos = SystemClock.elapsedRealtimeNanos();
        Cursor cursor = resolver.query(PERSON_BEAN_URI, PROJECTION, "SETTING=?",
                new String[] {role}, null);
        if (cursor == null) {
            throw new NullCursorException("readback=" + role);
        }
        try {
            int valueColumn = cursor.getColumnIndexOrThrow(COLUMN_VALUE);
            while (cursor.moveToNext()) {
                if (rows == 0) {
                    value = token(cursor.getString(valueColumn));
                }
                rows++;
            }
            elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedNanos;
        } finally {
            cursor.close();
        }
        return "readback=" + value + " readback_rows=" + rows + " readback_ms=" + millis(elapsedNanos);
    }

    private String report() {
        StringBuilder line = new StringBuilder("events=").append(ProcessObserver.eventCount());
        List<String> events = ProcessObserver.lastEvents();
        for (int i = 0; i < events.size(); i++) {
            line.append(" e").append(i).append('=').append(events.get(i));
        }
        return line.toString();
    }

    private static String requireRole(String role) {
        for (String known : ROLES) {
            if (known.equals(role)) {
                return role;
            }
        }
        throw new IllegalArgumentException("role=" + role);
    }

    private static void requirePackage(String candidate) {
        if (!PACKAGE_NAME.matcher(candidate).matches()) {
            throw new IllegalArgumentException("package=" + candidate);
        }
    }

    private static String operation(String action) {
        if (ACTION_QUERY.equals(action)) {
            return "query";
        }
        if (ACTION_UPDATE.equals(action)) {
            return "update";
        }
        if (ACTION_OBSERVE.equals(action)) {
            return "observe";
        }
        if (ACTION_REPORT.equals(action)) {
            return "report";
        }
        return "unknown";
    }

    /** Milliseconds with one decimal, from a nanosecond span. */
    private static String millis(long nanos) {
        return String.format(Locale.US, "%.1f", nanos / 1_000_000.0d);
    }

    /** One token, never empty and never carrying a space that would split the result line. */
    static String token(String raw) {
        if (raw == null) {
            return "null";
        }
        String collapsed = raw.trim().replaceAll("\\s+", "_");
        return collapsed.isEmpty() ? "empty" : collapsed;
    }

    /**
     * The exception name without its {@code Exception} suffix, so the protocol reads
     * {@code error=IllegalArgument} and {@code error=NullCursor}.
     */
    private static String errorName(Throwable error) {
        String name = error.getClass().getSimpleName();
        if (name.isEmpty()) {
            name = error.getClass().getName();
        }
        if (name.length() > "Exception".length() && name.endsWith("Exception")) {
            name = name.substring(0, name.length() - "Exception".length());
        }
        return token(name);
    }

    private static String detail(Throwable error) {
        String message = token(error.getMessage());
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /** A null cursor is a distinct falsifier for H1, so it gets its own name on the wire. */
    private static final class NullCursorException extends RuntimeException {
        NullCursorException(String message) {
            super(message);
        }
    }
}
