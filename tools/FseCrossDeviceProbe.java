package dev.denza.tools;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Read-only probe for BYD's stock IVI-to-FSE cross-device API. */
public final class FseCrossDeviceProbe extends Activity {
    private static final String TAG = "FseCrossDeviceProbe";
    private static final int CROSS_ID_IVI_ONLINE_STATUS = -15728639;
    private static final int CROSS_ID_FSE_ONLINE_STATUS = -15728638;
    private static final int CROSS_ID_FSE_IP = -13631483;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Class<?> deviceClass = Class.forName("android.cross.device.BYDCrossDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, this);

            Method getOne = deviceClass.getMethod("get", int.class, Class.class);
            Object ipValue = getOne.invoke(device, CROSS_ID_FSE_IP, byte[].class);
            Field bufferDataValue = ipValue.getClass().getField("bufferDataValue");
            byte[] address = (byte[]) bufferDataValue.get(ipValue);
            Log.i(TAG, "FSE_IP=" + new String(address, StandardCharsets.UTF_8));

            Method getMany = deviceClass.getMethod("get", int[].class, Class.class);
            Object onlineValue = getMany.invoke(device,
                    new int[]{CROSS_ID_IVI_ONLINE_STATUS, CROSS_ID_FSE_ONLINE_STATUS},
                    int[].class);
            Field intArrayValue = onlineValue.getClass().getField("intArrayValue");
            int[] online = (int[]) intArrayValue.get(onlineValue);
            Log.i(TAG, "ONLINE_IVI_FSE=" + Arrays.toString(online));
            Log.i(TAG, "READ_ONLY_PROBE_OK");
        } catch (Throwable error) {
            Log.e(TAG, "READ_ONLY_PROBE_FAILED", unwrap(error));
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
