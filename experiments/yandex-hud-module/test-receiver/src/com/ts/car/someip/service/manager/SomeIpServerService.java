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
import org.json.JSONArray;

/** Disposable emulator substitute, never install on a vehicle or distribute with the candidate. */
public final class SomeIpServerService extends Service {
    private long frames, starts, stops, roads, blanks, bad, hints, limits, lanePackets, laneClears;
    private int laneCount;
    private long arPackets, arClears;
    private boolean arActive;
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
                    .put("lanePackets",lanePackets).put("laneClears",laneClears).put("laneCount",laneCount)
                    .put("arPackets",arPackets).put("arClears",arClears).put("arActive",arActive)
                    .put("uptime",SystemClock.elapsedRealtime());
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(),"report.json"))) { out.write(value.toString().getBytes("UTF-8")); }
        } catch (Exception error) { Log.e("HudReceiver", "report failed",error); }
    }
    /**
     * Road packet 0x8001 as Denza Apps sends it: field 1 wraps 2 = 2, 16 = 1 (clear, zero lanes) or
     * 16 = 2 (navigating) with the hint fields 3/4, 5/7 (native lanes), 8 (80×80 stock PNG), 9, 10, 11 (5..130 by 5), 26, 27, 28.
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
            else if (type == 1) {
                double value = java.nio.ByteBuffer.wrap(payload,cursor[0],8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getDouble();
                if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite double");
                fields.put(field,value);cursor[0]+=8;
            }
            else if (type == 2) {
                int length = varint(payload, cursor);
                fields.put(field, java.util.Arrays.copyOfRange(payload, cursor[0], cursor[0] + length));
                cursor[0] += length;
            } else throw new IllegalArgumentException("wire type " + type);
        }
        if (!Integer.valueOf(2).equals(fields.get(2))) throw new IllegalArgumentException("field 2");
        Object state = fields.get(16);
        Object count = fields.get(5);
        if (!(count instanceof Integer)) throw new IllegalArgumentException("Missing lane count/clear");
        int nextLanes = (Integer) count;
        if (nextLanes < 0 || nextLanes > 6 || (nextLanes > 0) != fields.containsKey(7)) throw new IllegalArgumentException("Lane count/image mismatch");
        if (nextLanes > 0) {
            byte[] png = (byte[]) fields.get(7);
            Bitmap row = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (row == null || row.getWidth() != nextLanes * 41 || row.getHeight() != 60) throw new IllegalArgumentException("Lane PNG dimensions");
            row.recycle(); lanePackets++;
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(),"latest-lanes.png"))) { out.write(png); }
        } else if (laneCount > 0 && Integer.valueOf(2).equals(state)) laneClears++;
        laneCount = nextLanes;
        boolean hasAr=fields.containsKey(30);
        for(int field:new int[]{19,20,21,22,30,31,32,33})
            if(fields.containsKey(field)!=hasAr)throw new IllegalArgumentException("partial AR snapshot");
        if(arActive&&!hasAr)arClears++;
        arActive=hasAr;
        if(hasAr) {
            if(!Integer.valueOf(2).equals(state)||!fields.containsKey(28))throw new IllegalArgumentException("AR without guidance");
            JSONArray line=new JSONArray(new String((byte[])fields.get(30),"UTF-8"));
            if(line.length()<3||line.length()>256)throw new IllegalArgumentException("AR vertex count");
            for(int i=0;i<line.length();i++) {
                JSONArray point=line.getJSONArray(i);
                if(point.length()!=3||Math.abs(point.getDouble(0))>180||Math.abs(point.getDouble(1))>90||point.getDouble(2)!=0)
                    throw new IllegalArgumentException("AR coordinate");
            }
            String[] target=new String((byte[])fields.get(31),"UTF-8").split(",");
            if(target.length!=3)throw new IllegalArgumentException("guidePoint");
            boolean found=false;
            for(int i=1;i<line.length()-1;i++) {
                JSONArray point=line.getJSONArray(i);
                found|=point.getDouble(0)==Double.parseDouble(target[0])&&point.getDouble(1)==Double.parseDouble(target[1]);
            }
            double progress=(Double)fields.get(33),heading=(Double)fields.get(32);
            if(!found||progress<0||progress>1||heading<0||heading>=360)throw new IllegalArgumentException("AR target/progress/heading");
            JSONObject sample=new JSONObject().put("line",line).put("target",new JSONArray().put(Double.parseDouble(target[0])).put(Double.parseDouble(target[1])))
                    .put("lon",fields.get(19)).put("lat",fields.get(20)).put("heading",heading).put("progress",progress);
            try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),String.format("ar-%02d.json",arPackets%60)))) {
                out.write(sample.toString().getBytes("UTF-8"));
            }
            arPackets++;
        }
        if (Integer.valueOf(1).equals(state)) {
            if (fields.size() != 3 || laneCount != 0) throw new IllegalArgumentException("clear carries a hint");
            return false;
        }
        if (!Integer.valueOf(2).equals(state)) throw new IllegalArgumentException("field 16");
        if (fields.containsKey(8)) {
            byte[] png = (byte[]) fields.get(8);
            Bitmap icon = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (icon == null || icon.getWidth() != 80 || icon.getHeight() != 80) throw new IllegalArgumentException("manoeuvre PNG");
            icon.recycle();
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(),"latest-manoeuvre.png"))) { out.write(png); }
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
            if (entry.getKey() == 8 || entry.getKey() == 7) hint.put(String.valueOf(entry.getKey()), ((byte[]) value).length + " bytes");
            else hint.put(String.valueOf(entry.getKey()), value instanceof byte[] ? new String((byte[]) value, "UTF-8") : value);
        }
        lastHint = hint;
        return true;
    }

    private static int varint(byte[] input, int[] offset) {
        long value = 0; int shift = 0;
        while (true) { int item = input[offset[0]++] & 255; value |= (long)(item & 127) << shift; if ((item & 128) == 0) return (int)value; shift += 7; if (shift > 63) throw new IllegalArgumentException("varint"); }
    }

}
