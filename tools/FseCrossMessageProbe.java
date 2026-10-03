package dev.denza.tools;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One-shot sender for an explicitly supplied stock BYD cross-device JSON message. */
public final class FseCrossMessageProbe extends Activity {
    private static final String TAG = "FseCrossMessageProbe";
    private static final int CROSS_ID_CHANGE_THEME = -13631467;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Object device;
    private Object listener;
    private Class<?> deviceClass;
    private Class<?> listenerClass;
    private int expectedTheme = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String encoded = getIntent().getStringExtra("message_base64");
            if (encoded == null) {
                throw new IllegalArgumentException("missing message_base64");
            }
            byte[] message = Base64.decode(encoded, Base64.DEFAULT);
            expectedTheme = new JSONObject(new String(message, StandardCharsets.UTF_8))
                    .optInt("theme_id", -1);

            deviceClass = Class.forName("android.cross.device.BYDCrossDevice");
            device = deviceClass.getMethod("getInstance", Context.class)
                    .invoke(null, this);
            Class<?> valueClass = Class.forName("android.cross.BYDCrossEventValue");
            if (expectedTheme >= 0) {
                listenerClass = Class.forName("android.cross.IBYDCrossListener");
                Class<?> eventClass = Class.forName("android.cross.IBYDCrossEvent");
                listener = Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{listenerClass},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("toString".equals(name)) return "FseCrossMessageProbeListener";
                            if ("onDataEventChanged".equals(name) && args != null && args.length >= 2 &&
                                    ((Number) args[0]).intValue() == CROSS_ID_CHANGE_THEME) {
                                receive((byte[]) valueClass.getField("bufferDataValue").get(args[1]));
                            } else if ("onDataChanged".equals(name) && args != null && args.length >= 1 &&
                                    ((Number) eventClass.getMethod("getEventType").invoke(args[0])).intValue()
                                            == CROSS_ID_CHANGE_THEME) {
                                receive((byte[]) eventClass.getMethod("getBufferData").invoke(args[0]));
                            }
                            return null;
                        });
                deviceClass.getMethod("registerListener", listenerClass, int[].class)
                        .invoke(device, listener, new int[]{CROSS_ID_CHANGE_THEME});
            }
            Constructor<?> valueConstructor = valueClass.getConstructor(byte[].class);
            Object value = valueConstructor.newInstance((Object) message);
            Method set = deviceClass.getMethod("set", int[].class, valueClass);
            Object result = set.invoke(device, new int[]{CROSS_ID_CHANGE_THEME}, value);
            Log.i(TAG, "CROSS_SEND_RESULT=" + result);
            Log.i(TAG, "CROSS_MESSAGE=" + new String(message, java.nio.charset.StandardCharsets.UTF_8));
            if (expectedTheme < 0 || !(result instanceof Number) || ((Number) result).intValue() != 0) {
                finish();
            } else {
                int seconds = Math.max(1, Math.min(120, getIntent().getIntExtra("wait_seconds", 3)));
                handler.postDelayed(() -> {
                    Log.i(TAG, "CROSS_RESPONSE_TIMEOUT theme=" + expectedTheme);
                    finish();
                }, seconds * 1000L);
            }
        } catch (Throwable error) {
            Log.e(TAG, "CROSS_SEND_FAILED", unwrap(error));
            finish();
        }
    }

    private void receive(byte[] payload) {
        if (payload == null) return;
        try {
            String text = new String(payload, StandardCharsets.UTF_8);
            JSONObject response = new JSONObject(text);
            if (response.optInt("fromDevice", -1) == 2 && response.optInt("toDevice", -1) == 1 &&
                    response.optInt("res_id", -1) == expectedTheme &&
                    "android.intent.action.using_wallpaper_result".equals(response.optString("action"))) {
                Log.i(TAG, "CROSS_RESPONSE=" + text);
                handler.post(this::finish);
            }
        } catch (Exception e) { Log.w(TAG, "Invalid cross callback", e); }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (device != null && listener != null) {
            try { deviceClass.getMethod("unregisterListener", listenerClass).invoke(device, listener); }
            catch (Exception e) { Log.w(TAG, "unregister failed", e); }
        }
        super.onDestroy();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
