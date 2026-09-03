package dev.denza.dicarmedia.probe;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import com.byd.spi.ipc.cursor.BinderCursor;

import java.util.Locale;

/**
 * Shell-only seam that exercises the stock car media service from an ordinary app UID.
 *
 * <p>Two questions, one per action. {@code LOOKUP} asks whether the exported car service provider
 * hands this UID the media binder at all; it reads and changes nothing in the vehicle.
 * {@code STATE} calls {@code setPlaybackState}, which is the one write, and which the cluster
 * receives as {@code INSTRUMENT_MUSIC_STATE_SET 0x43E0000A}.
 *
 * <p>Every action answers on the ordered-broadcast result channel with code 0 (the operation ran)
 * or 1 (it threw) and one line of space-separated {@code key=value} tokens. Judging the values is
 * the host script's job, not this receiver's.
 */
public final class ProbeReceiver extends BroadcastReceiver {

    static final String TAG = "DiCarMediaProbe";

    private static final String PREFIX = "dev.denza.dicarmedia.probe.";
    static final String ACTION_LOOKUP = PREFIX + "LOOKUP";
    static final String ACTION_STATE = PREFIX + "STATE";

    /** Exported provider the vendor SDK itself uses to reach any car service. */
    private static final Uri PROVIDER_URI =
            Uri.parse("content://com.byd.car.server.provider.CarServiceProvider");

    private static final String MEDIA_SERVICE = "com.byd.car.feature.media.ICarMediaService";
    private static final String PLAYBACK_STATE_CLASS = "com.byd.car.feature.media.PlaybackState";

    /** setPlaybackState is transaction 2 of the media interface. */
    private static final int TRANSACTION_SET_PLAYBACK_STATE = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        // This receiver always answers and never throws, so formatting and the result channel sit
        // inside the guard as well, not only the binder work.
        try {
            String action = intent == null ? null : intent.getAction();
            StringBuilder line = new StringBuilder()
                    .append("op=").append(operation(action))
                    .append(" uid=").append(Process.myUid());

            boolean ok;
            try {
                if (ACTION_LOOKUP.equals(action)) {
                    ok = lookup(context, line);
                } else if (ACTION_STATE.equals(action)) {
                    ok = state(context, intent, line);
                } else {
                    line.append(" error=unknown_action");
                    ok = false;
                }
            } catch (Throwable t) {
                line.append(" error=").append(token(t.getClass().getSimpleName()))
                        .append(" detail=").append(token(t.getMessage()));
                ok = false;
            }

            String report = line.toString();
            Log.i(TAG, report);
            if (isOrderedBroadcast()) {
                setResult(ok ? 0 : 1, report, null);
            }
        } catch (Throwable t) {
            Log.e(TAG, "probe failed outright", t);
        }
    }

    /** Asks the provider for the media binder and reports what came back. */
    private boolean lookup(Context context, StringBuilder line) throws Exception {
        IBinder binder = mediaBinder(context, line);
        if (binder == null) {
            return false;
        }
        line.append(" descriptor=").append(token(binder.getInterfaceDescriptor()))
                .append(" alive=").append(binder.isBinderAlive());
        return true;
    }

    /**
     * Sends one playback-state report to the car. The state name comes from the intent so the host
     * decides the sequence; the receiver never invents an edge of its own.
     */
    private boolean state(Context context, Intent intent, StringBuilder line) throws Exception {
        String state = intent == null ? null : intent.getStringExtra("state");
        if (state == null || !isKnownState(state)) {
            line.append(" error=bad_state detail=").append(token(state));
            return false;
        }
        line.append(" state=").append(state);

        IBinder binder = mediaBinder(context, line);
        if (binder == null) {
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MEDIA_SERVICE);
            // EnumParcel is written by the vendor's reflective parcelable: a non-null marker, the
            // class name of the wrapped value, then the enum constant name.
            data.writeInt(1);
            data.writeString(PLAYBACK_STATE_CLASS);
            data.writeString(state);

            boolean delivered = binder.transact(TRANSACTION_SET_PLAYBACK_STATE, data, reply, 0);
            line.append(" delivered=").append(delivered);
            reply.readException();
            if (reply.readInt() == 0) {
                line.append(" status=null");
                return false;
            }
            int code = reply.readInt();
            String message = reply.readString();
            line.append(" code=").append(code).append(" message=").append(token(message));
            return code == 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** The lookup the vendor SDK performs: one provider query whose extras carry the binder. */
    private IBinder mediaBinder(Context context, StringBuilder line) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(
                PROVIDER_URI, null, null, new String[]{MEDIA_SERVICE}, null)) {
            if (cursor == null) {
                line.append(" error=no_cursor");
                return null;
            }
            Bundle extras = cursor.getExtras();
            if (extras == null) {
                line.append(" error=no_extras");
                return null;
            }
            extras.setClassLoader(BinderCursor.BinderParcelable.class.getClassLoader());
            BinderCursor.BinderParcelable wrapper =
                    extras.getParcelable(BinderCursor.KEY_BINDER);
            if (wrapper == null || wrapper.getBinder() == null) {
                line.append(" error=no_binder");
                return null;
            }
            line.append(" binder=yes");
            return wrapper.getBinder();
        } catch (SecurityException e) {
            line.append(" error=security detail=").append(token(e.getMessage()));
            return null;
        }
    }

    private static boolean isKnownState(String state) {
        return "PLAYING".equals(state) || "PAUSED".equals(state) || "CLOSED".equals(state);
    }

    private static String operation(String action) {
        if (action == null) {
            return "none";
        }
        return action.startsWith(PREFIX)
                ? action.substring(PREFIX.length()).toLowerCase(Locale.US)
                : token(action);
    }

    /** Keeps the answer one line of space-separated tokens whatever the vendor put in a string. */
    private static String token(String value) {
        if (value == null) {
            return "none";
        }
        String flattened = value.replaceAll("\\s+", "_");
        return flattened.isEmpty() ? "empty" : flattened;
    }
}
