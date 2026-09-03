package dev.denza.tools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Looper;
import android.os.Parcel;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Speaker-lift (Devialet pop-out covers) live probe. Runs on-device as shell
 * UID via app_process; no APK install. See docs/speaker-lift-findings.md.
 *
 * Modes:
 *   snap                          read-only FID snapshot via autoservice getInt (transact 5)
 *   focus <stream> <secs>         requestAudioFocus(listener, stream, GAIN), hold, abandon
 *   tone <stream> <ct|-1> <secs> [focus|nofocus]
 *                                 play a quiet sine on the given legacy stream type; ct is an
 *                                 AudioAttributes contentType applied via reflection when >= 0
 *   fidset <fid> <v0> [v1..]      BYDAutoAudioDevice.set(int[]{fid}, intArrayValue=[v...])
 *                                 via runtime framework reflection (intArray shape)
 *   params <k=v> [k=v..]          AudioSystem.setParameters via reflection
 *   attrs <channelId>             print AudioAttributes from IBYDCarAudioService (read-only)
 *
 * Hard rules baked in: the only autoservice binder transaction this probe
 * issues itself is getInt (code 5). SETs go through the framework's own
 * client classes so the framework shapes the parcel. Never touch transact
 * 10/12/14/16 (SIGSEGV history, see doc hazard log).
 */
public final class SpeakerLiftProbe {

    private static final String TAG = "SpeakerLiftProbe";

    // Device family (docs/speaker-lift-findings.md).
    private static final int DEV_AUDIO = 1002;
    private static final int DEV_INSTRUMENT = 1007;

    // FIDs from the live snapshot table.
    private static final int F_RLSA_CONFIG = 0x4C000010;
    private static final int F_RLSA_STATE = 0x4C00000B;
    private static final int F_FLIP_CONFIG = 0x35A000D8;
    private static final int F_FLIP_SETTING = 0x35A000DA;
    private static final int F_MEDIA_SRC_STATE = 0x4C60000C;
    private static final int F_MASTER_VOL = 0x4FD00018;
    private static final int F_MUTE_STATE = 0x4FD0002D;
    private static final int F_AMP_CONFIG = 0x4FD00030;
    private static final int F_INSTR_SRC = 0x33F00030;

    private SpeakerLiftProbe() {
    }

    public static void main(String[] args) {
        Log.i(TAG, "[SLP] main: " + join(args, " "));
        out("mode args: " + join(args, " "));
        exemptHiddenApis();
        if (args.length == 0) {
            out("usage: snap | focus <stream> <secs> | tone <stream> <ct|-1> <secs> [focus|nofocus]"
                    + " | fidset <fid> <v0> [v1..] | params <k=v>... | attrs <channelId>");
            return;
        }
        try {
            switch (args[0]) {
                case "snap":
                    snap();
                    break;
                case "focus":
                    doFocus(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                    break;
                case "tone": {
                    boolean focus = args.length < 5 || !"nofocus".equals(args[4]);
                    doTone(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                            Integer.parseInt(args[3]), focus);
                    break;
                }
                case "fidset": {
                    int fid = Integer.decode(args[1]);
                    int[] vals = new int[args.length - 2];
                    for (int i = 2; i < args.length; i++) vals[i - 2] = Integer.decode(args[i]);
                    fidSet(fid, vals);
                    break;
                }
                case "params": {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < args.length; i++) {
                        if (i > 1) sb.append(';');
                        sb.append(args[i]);
                    }
                    setParams(sb.toString());
                    break;
                }
                case "attrs":
                    queryAttrs(Integer.parseInt(args[1]));
                    break;
                default:
                    out("unknown mode: " + args[0]);
            }
        } catch (Throwable t) {
            out("FAIL: " + t);
            t.printStackTrace(System.out);
        }
        out("done: " + args[0]);
    }

    // ---------------------------------------------------------------- snap

    /** Read-only: autoservice getInt (transact 5), the only binder call we make. */
    private static void snap() throws Exception {
        IBinder svc = getService("autoservice");
        if (svc == null) {
            out("autoservice: not found");
            return;
        }
        report(svc, DEV_AUDIO, F_RLSA_CONFIG, "AUDIO_RLSA_COFIG");
        report(svc, DEV_AUDIO, F_RLSA_STATE, "AUDIO_RLSA_STATE");
        report(svc, DEV_AUDIO, F_FLIP_CONFIG, "AUDIO_SPEAKER_FLIP_COVER_CONFIG");
        report(svc, DEV_AUDIO, F_FLIP_SETTING, "AUDIO_SPEAKER_FLIP_SETTING_STATUS");
        report(svc, DEV_AUDIO, F_MEDIA_SRC_STATE, "AUDIO_MEDIA_SOUND_SOURCE_STATE");
        report(svc, DEV_AUDIO, F_MASTER_VOL, "AUDIO_MASTER_VOLUME_STATE");
        report(svc, DEV_AUDIO, F_MUTE_STATE, "AUDIO_MEDIA_SOUND_MUTE_STATE");
        report(svc, DEV_AUDIO, F_AMP_CONFIG, "AMP_CONFIG");
        report(svc, DEV_INSTRUMENT, F_INSTR_SRC, "INSTRUMENT_MUSIC_SOURCE_SET");
    }

    private static void report(IBinder svc, int dev, int fid, String name) {
        try {
            out(String.format("%-38s dev=%d fid=0x%08X -> %d", name, dev, fid, getInt(svc, dev, fid)));
        } catch (Throwable t) {
            out(name + " -> ERR " + t);
        }
    }

    private static int getInt(IBinder svc, int dev, int fid) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.gui.BYDAutoServer");
            data.writeInt(dev);
            data.writeInt(fid);
            if (!svc.transact(5, data, reply, 0)) {
                throw new IllegalStateException("transact(5) returned false");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // --------------------------------------------------------------- focus

    private static void doFocus(int streamType, int secs) throws Exception {
        AudioManager am = audioManager();
        AudioManager.OnAudioFocusChangeListener l = c -> Log.i(TAG, "[SLP] focus change: " + c);
        int res = am.requestAudioFocus(l, streamType, AudioManager.AUDIOFOCUS_GAIN);
        out("requestAudioFocus(stream=" + streamType + ") -> " + res + " (1=granted)");
        Thread.sleep(secs * 1000L);
        am.abandonAudioFocus(l);
        out("focus abandoned");
    }

    // ---------------------------------------------------------------- tone

    private static void doTone(int streamType, int contentType, int secs, boolean withFocus)
            throws Exception {
        AudioManager am = audioManager();
        AudioManager.OnAudioFocusChangeListener l = c -> Log.i(TAG, "[SLP] focus change: " + c);
        if (withFocus) {
            int res = am.requestAudioFocus(l, streamType, AudioManager.AUDIOFOCUS_GAIN);
            out("requestAudioFocus(stream=" + streamType + ") -> " + res);
        }
        AudioTrack track = null;
        try {
            track = buildTrack(streamType, contentType);
            out("track built: state=" + track.getState() + " stream=" + streamType
                    + " ct=" + contentType + " actualStream=" + actualStreamType(track)
                    + actualAttrs(track));
            int actual = actualStreamType(track);
            if (actual != Integer.MIN_VALUE && actual != streamType) {
                out("WARNING: framework remapped stream " + streamType + " -> " + actual
                        + " — this run does NOT test the requested stream");
            }
            byte[] pcm = sine(440.0, 44100, 0.15);
            track.play();
            long end = System.currentTimeMillis() + secs * 1000L;
            while (System.currentTimeMillis() < end) {
                track.write(pcm, 0, pcm.length);
            }
            out("tone played " + secs + "s");
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignore) {
                }
                track.release();
            }
            if (withFocus) am.abandonAudioFocus(l);
        }
    }

    private static AudioTrack buildTrack(int streamType, int contentType) throws Exception {
        int rate = 44100;
        int ch = AudioFormat.CHANNEL_OUT_MONO;
        int enc = AudioFormat.ENCODING_PCM_16BIT;
        int buf = Math.max(AudioTrack.getMinBufferSize(rate, ch, enc), rate * 2);
        if (contentType < 0) {
            // Legacy constructor: BYD framework must accept its custom stream ids.
            return new AudioTrack(streamType, rate, ch, enc, buf, AudioTrack.MODE_STREAM);
        }
        AudioAttributes attrs = buildAttrs(1 /*USAGE_MEDIA*/, contentType, streamType);
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(rate).setChannelMask(ch).setEncoding(enc).build();
        return new AudioTrack(attrs, fmt, buf, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    private static AudioAttributes buildAttrs(int usage, int contentType, int legacyStream)
            throws Exception {
        AudioAttributes.Builder b = new AudioAttributes.Builder().setUsage(usage);
        // Legacy stream first: AOSP setLegacyStreamType() resets mContentType
        // to UNKNOWN, so contentType must be applied after it.
        try {
            b.setLegacyStreamType(legacyStream);
        } catch (Throwable t) {
            out("Builder.setLegacyStreamType(" + legacyStream + ") rejected: " + t);
        }
        try {
            b.setContentType(contentType);
        } catch (Throwable t) {
            out("Builder.setContentType(" + contentType + ") rejected: " + t
                    + " — falling back to field poke");
        }
        AudioAttributes attrs = b.build();
        // If the builder silently dropped a non-AOSP content type, poke fields directly.
        try {
            Field f = AudioAttributes.class.getDeclaredField("mContentType");
            f.setAccessible(true);
            if ((int) f.get(attrs) != contentType) {
                f.set(attrs, contentType);
                out("mContentType poked to " + contentType);
            }
        } catch (Throwable t) {
            out("field poke failed: " + t);
        }
        return attrs;
    }

    private static int actualStreamType(AudioTrack track) {
        try {
            Method m = AudioTrack.class.getMethod("getStreamType");
            return (int) m.invoke(track);
        } catch (Throwable t) {
            try {
                Field f = AudioTrack.class.getDeclaredField("mStreamType");
                f.setAccessible(true);
                return f.getInt(track);
            } catch (Throwable t2) {
                return Integer.MIN_VALUE; // unknown — treat as suspect
            }
        }
    }

    private static String actualAttrs(AudioTrack track) {
        try {
            Method m = AudioTrack.class.getMethod("getAudioAttributes");
            Object attrs = m.invoke(track);
            return " attrs=" + attrs;
        } catch (Throwable t) {
            return " attrs=?";
        }
    }

    private static byte[] sine(double freq, int rate, double amp) {
        int samples = rate / 10; // 100 ms chunks
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short v = (short) (Math.sin(2 * Math.PI * freq * i / rate) * amp * Short.MAX_VALUE);
            out[i * 2] = (byte) (v & 0xff);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }

    // -------------------------------------------------------------- fidset

    /**
     * intArray-shaped SET through the framework's own client
     * (BYDAutoAudioDevice.set(int[], BYDAutoEventValue{intArrayValue})).
     * The framework builds the binder parcel — we never hand-craft one.
     */
    private static void fidSet(int fid, int[] values) throws Exception {
        Context ctx = systemContext();
        Class<?> devCls = Class.forName("android.hardware.bydauto.audio.BYDAutoAudioDevice");
        Object dev;
        try {
            dev = devCls.getMethod("getInstance", Context.class).invoke(null, ctx);
        } catch (NoSuchMethodException e) {
            dev = devCls.getMethod("getInstance").invoke(null);
        }
        Class<?> evCls = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
        Object ev = evCls.newInstance();
        Field arr = evCls.getField("intArrayValue");
        arr.set(ev, values);
        int res = (int) devCls.getMethod("set", int[].class, evCls)
                .invoke(dev, new int[]{fid}, ev);
        out(String.format("fidset 0x%08X <- %s -> %d", fid, arrToStr(values), res));
    }

    // -------------------------------------------------------------- params

    private static void setParams(String kv) throws Exception {
        Class<?> as = Class.forName("android.media.AudioSystem");
        Method m = as.getMethod("setParameters", String.class);
        m.invoke(null, kv);
        out("setParameters(\"" + kv + "\") ok");
    }

    // --------------------------------------------------------------- attrs

    /** Read-only: dump the AudioAttributes a trusted app gets for a channel id. */
    private static void queryAttrs(int channelId) throws Exception {
        AudioManager am = audioManager();
        Object svc = null;
        for (Method m : am.getClass().getDeclaredMethods()) {
            if (m.getReturnType() != null
                    && m.getReturnType().getName().contains("IBYDCarAudioService")) {
                m.setAccessible(true);
                svc = m.invoke(am);
                out("got IBYDCarAudioService via " + m.getName());
                break;
            }
        }
        if (svc == null) {
            out("IBYDCarAudioService accessor not found on AudioManager");
            return;
        }
        Method getAttrs = svc.getClass().getMethod("getAudioAttributes", int.class);
        Object attrs = getAttrs.invoke(svc, channelId);
        out("channel " + channelId + " attrs: " + attrs);
    }

    // -------------------------------------------------------------- helpers

    private static IBinder getService(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
    }

    private static AudioManager audioManager() throws Exception {
        return (AudioManager) systemContext().getSystemService(Context.AUDIO_SERVICE);
    }

    private static Context systemContext() throws Exception {
        // ActivityThread.systemMain() builds its own Handler, so this process needs a Looper
        // before it is touched. Without this the tone and focus modes die inside
        // ActivityThread.<init> with "Can't create handler inside thread that has not called
        // Looper.prepare()", which is what happened on the N9 on 2026-08-30.
        try {
            Looper.prepareMainLooper();
        } catch (IllegalStateException alreadyPrepared) {
            // A second call in the same process is not an error for us.
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object rt = vm.getMethod("getRuntime").invoke(null);
            vm.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(rt, (Object) new String[]{"L"});
        } catch (Throwable t) {
            out("hidden-api exemption failed (continuing): " + t);
        }
    }

    private static void out(String msg) {
        System.out.println("[SLP] " + msg);
        Log.i(TAG, "[SLP] " + msg);
    }

    private static String join(String[] a, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static String arrToStr(int[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
