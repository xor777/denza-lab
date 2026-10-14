package com.ts.car.someip.service.manager;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.*;
import android.util.Base64;
import android.util.Log;
import java.io.*;
import org.json.JSONObject;

/** Disposable emulator substitute, never install on a vehicle or distribute with the candidate. */
public final class SomeIpServerService extends Service {
    private long frames, starts, stops, roads, blanks, bad, hints, limits;
    private boolean active, session;
    private JSONObject lastHint = new JSONObject();
    private final Binder endpoint = new Binder() {
        @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString("ts.car.someip.sdk.ISomeIpServerInterface"); return true; }
            data.enforceInterface("ts.car.someip.sdk.ISomeIpServerInterface");
            int result = 0;
            try {
                if (code == 4 || code == 5) {
                    if (data.readLong() != 3097367205183488L) throw new IllegalArgumentException("service id");
                    session = code == 4;
                    if (session) starts++; else stops++;
                } else if (code == 6) {
                    if (!session || data.readInt() != 1) throw new IllegalArgumentException("session/handle");
                    long topic = data.readLong();
                    if (data.readLong() != 0) throw new IllegalArgumentException("instance");
                    int length = data.readInt();
                    byte[] payload = data.createByteArray();
                    if (payload.length != length) throw new IllegalArgumentException("length");
                    if (topic == 1127042368241665L) {
                        active = road(payload);
                        roads++;
                    } else if (topic == 1127042368241667L) {
                        int[] cursor = {0};
                        if (varint(payload, cursor) != 10) throw new IllegalArgumentException("map field");
                        int size = varint(payload, cursor);
                        if (cursor[0] + size != payload.length) throw new IllegalArgumentException("map envelope");
                        byte[] png = Base64.decode(java.util.Arrays.copyOfRange(payload, cursor[0], payload.length), Base64.NO_WRAP);
                        Bitmap image = BitmapFactory.decodeByteArray(png, 0, png.length);
                        if (image == null || image.getWidth() != 300 || image.getHeight() != 180) throw new IllegalArgumentException("PNG size");
                        int[] pixels = new int[54000]; image.getPixels(pixels,0,300,0,0,300,180); image.recycle();
                        boolean black = true; for (int color : pixels) if ((color & 0xffffff) != 0) { black = false; break; }
                        if (black) blanks++;
                        else if (!active) throw new IllegalArgumentException("map without road active");
                        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), String.format("frame-%02d.png", frames % 60)))) { out.write(png); }
                        frames++;
                    } else throw new IllegalArgumentException("topic");
                } else return false;
            } catch (Exception error) { result = -1; bad++; Log.e("HudReceiver", "invalid transaction", error); }
            save();
            reply.writeNoException(); reply.writeInt(result); return true;
        }
    };
    @Override public IBinder onBind(Intent intent) {
        if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) return null;
        return endpoint;
    }
    private void save() {
        try {
            JSONObject value = new JSONObject().put("frames",frames).put("starts",starts).put("stops",stops)
                    .put("roads",roads).put("blanks",blanks).put("bad",bad).put("hints",hints).put("limits",limits).put("lastHint",lastHint).put("active",active).put("session",session)
                    .put("uptime",SystemClock.elapsedRealtime());
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(),"report.json"))) { out.write(value.toString().getBytes("UTF-8")); }
        } catch (Exception error) { Log.e("HudReceiver", "report failed",error); }
    }
    /**
     * Road packet 0x8001 as Denza Apps sends it: field 1 wraps 2 = 2, 16 = 1 (clear, nothing else) or
     * 16 = 2 (navigating) with the hint fields 3/4, 8 (192×192 PNG), 9, 10, 11 (5..130 by 5), 26, 27, 28.
     */
    private boolean road(byte[] payload) throws Exception {
        int[] cursor = {0};
        if (varint(payload, cursor) != 10) throw new IllegalArgumentException("road envelope");
        int size = varint(payload, cursor);
        if (cursor[0] + size != payload.length) throw new IllegalArgumentException("road length");
        java.util.Map<Integer, Object> fields = new java.util.TreeMap<>();
        while (cursor[0] < payload.length) {
            int key = varint(payload, cursor), field = key >>> 3, type = key & 7;
            if (fields.containsKey(field)) throw new IllegalArgumentException("repeated field " + field);
            if (type == 0) fields.put(field, varint(payload, cursor));
            else if (type == 2) {
                int length = varint(payload, cursor);
                fields.put(field, java.util.Arrays.copyOfRange(payload, cursor[0], cursor[0] + length));
                cursor[0] += length;
            } else throw new IllegalArgumentException("wire type " + type);
        }
        if (!Integer.valueOf(2).equals(fields.get(2))) throw new IllegalArgumentException("field 2");
        Object state = fields.get(16);
        if (Integer.valueOf(1).equals(state)) {
            if (fields.size() != 2) throw new IllegalArgumentException("clear carries a hint");
            return false;
        }
        if (!Integer.valueOf(2).equals(state)) throw new IllegalArgumentException("field 16");
        if (fields.containsKey(8)) {
            byte[] png = (byte[]) fields.get(8);
            Bitmap icon = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (icon == null || icon.getWidth() != 192 || icon.getHeight() != 192) throw new IllegalArgumentException("manoeuvre PNG");
            icon.recycle();
            if (!(fields.get(9) instanceof Integer) || !(fields.get(28) instanceof Integer)) throw new IllegalArgumentException("manoeuvre without distance/id");
            hints++;
        }
        if (fields.containsKey(11)) {
            int limit = (Integer) fields.get(11);
            if (limit < 5 || limit > 130 || limit % 5 != 0) throw new IllegalArgumentException("limit " + limit);
            limits++;
        }
        JSONObject hint = new JSONObject();
        for (java.util.Map.Entry<Integer, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey() == 8) hint.put("8", ((byte[]) value).length + " bytes");
            else hint.put(String.valueOf(entry.getKey()), value instanceof byte[] ? new String((byte[]) value, "UTF-8") : value);
        }
        lastHint = hint;
        return true;
    }

    private static int varint(byte[] input, int[] offset) {
        int value = 0, shift = 0;
        while (true) { int item = input[offset[0]++] & 255; value |= (item & 127) << shift; if ((item & 128) == 0) return value; shift += 7; if (shift > 28) throw new IllegalArgumentException("varint"); }
    }
}
