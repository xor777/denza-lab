package dev.denza.tools;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** One-shot sender for an explicitly supplied stock BYD cross-device JSON message. */
public final class FseCrossMessageProbe extends Activity {
    private static final String TAG = "FseCrossMessageProbe";
    private static final int CROSS_ID_CHANGE_THEME = -13631467;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String encoded = getIntent().getStringExtra("message_base64");
            if (encoded == null) {
                throw new IllegalArgumentException("missing message_base64");
            }
            byte[] message = Base64.decode(encoded, Base64.DEFAULT);

            Class<?> deviceClass = Class.forName("android.cross.device.BYDCrossDevice");
            Object device = deviceClass.getMethod("getInstance", Context.class)
                    .invoke(null, this);
            Class<?> valueClass = Class.forName("android.cross.BYDCrossEventValue");
            Constructor<?> valueConstructor = valueClass.getConstructor(byte[].class);
            Object value = valueConstructor.newInstance((Object) message);
            Method set = deviceClass.getMethod("set", int[].class, valueClass);
            Object result = set.invoke(device, new int[]{CROSS_ID_CHANGE_THEME}, value);
            Log.i(TAG, "CROSS_SEND_RESULT=" + result);
            Log.i(TAG, "CROSS_MESSAGE=" + new String(message, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable error) {
            Log.e(TAG, "CROSS_SEND_FAILED", unwrap(error));
        } finally {
            finish();
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
