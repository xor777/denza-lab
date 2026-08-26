package dev.denza.tools;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Fires the stock "IVI is using the vehicle speaker" pulse that a normal
 * MediaPlayer.start() triggers, without MediaCenter and without any BYDAUTO_*
 * permission. Runs as shell UID via app_process; no APK install.
 *
 * Background: docs/speaker-lift-findings.md, "Stock Java API behind that LOCAL
 * pulse". BYD patched MediaPlayer.startImpl() to call
 * AudioManager.isStreamAllowed(stream, pkg); for streams {3,2,0} from a caller
 * that is not uid 1013, AudioServiceMultiUserImpl side-effects
 * IviVehicleAudioBroker.setUseVehicleSpeaker() -> setIviUseSelfAudio(REASON_MEDIA=4)
 * -> BYDAutoSettingDevice.set(SET_USE_AUDIO_SCENE_SET=0x33F00024, 4) with the
 * broker's own calling identity. ExoPlayer/AudioTrack never reach it, which is
 * the standing explanation for why Yandex does not extend the covers.
 *
 * Every mode prints the audio-scene and flip FIDs before and after, so a run is
 * self-documenting even if nobody is watching the covers at that second.
 *
 * Modes:
 *   snap                     read-only FIDs + broker state, no writes
 *   usespeaker               VehicleAudioStateManager.setUseVehicleSpeaker()      (transact 30)
 *   requestspeaker           VehicleAudioStateManager.requestUseVehicleSpeaker()  (transact 32)
 *   streamallowed <s> <pkg>  AudioManager.isStreamAllowed(s, pkg)                 (transact 1)
 *   mediaplayer <path> <s>   plain MediaPlayer on a local file, stream 3, s seconds
 *
 * The only binder transaction this probe issues by hand is autoservice getInt
 * (code 5). Everything else goes through the framework's own client classes so
 * the framework shapes the parcel. Never touch transact 10/12/14/16 — see the
 * hazard log in the findings doc.
 */
public final class VehicleSpeakerPulse {

    private static final String TAG = "VehicleSpeakerPulse";

    private static final int DEV_AUDIO = 1002;
    private static final int DEV_INSTRUMENT = 1007;
    private static final int DEV_SETTING = 1023;

    private static final int F_USE_AUDIO_SCENE_SET = 0x33F00024; // 871366692
    private static final int F_FLIP_CONFIG = 0x35A000D8;
    private static final int F_FLIP_SETTING = 0x35A000DA;
    private static final int F_RLSA_STATE = 0x4C00000B;
    private static final int F_MASTER_VOL = 0x4FD00018;

    private VehicleSpeakerPulse() {
    }

    public static void main(String[] args) {
        out("mode args: " + join(args));
        exemptHiddenApis();
        // VehicleAudioStateManager.getInstance() and MediaPlayer both build a
        // Handler, so this process needs a Looper before either is touched.
        try {
            Looper.prepareMainLooper();
        } catch (Throwable t) {
            out("prepareMainLooper: " + t);
        }
        if (args.length == 0) {
            out("usage: snap | usespeaker | requestspeaker"
                    + " | streamallowed <stream> <pkg> | mediaplayer <path> <secs>");
            return;
        }
        try {
            report("before");
            switch (args[0]) {
                case "snap":
                    break;
                case "dryrun":
                    dryRun();
                    break;
                case "usespeaker":
                    out("setUseVehicleSpeaker() -> " + invokeManager("setUseVehicleSpeaker"));
                    break;
                case "requestspeaker":
                    out("requestUseVehicleSpeaker() -> " + invokeManager("requestUseVehicleSpeaker"));
                    break;
                case "streamallowed":
                    streamAllowed(Integer.parseInt(args[1]), args[2]);
                    break;
                case "mediaplayer":
                    mediaPlayer(args[1], Integer.parseInt(args[2]));
                    break;
                default:
                    out("unknown mode: " + args[0]);
                    return;
            }
            // The MCU write is asynchronous to our binder return.
            Thread.sleep(1500L);
            report("after");
        } catch (Throwable t) {
            out("FAIL: " + t);
            t.printStackTrace(System.out);
        }
        out("done: " + args[0]);
    }

    // ------------------------------------------------------------ read-only

    /** getInt only (autoservice transact 5) plus broker state through its manager. */
    private static void report(String phase) {
        IBinder svc;
        try {
            svc = getService("autoservice");
        } catch (Throwable t) {
            out(phase + ": autoservice lookup failed: " + t);
            return;
        }
        show(phase, svc, DEV_SETTING, F_USE_AUDIO_SCENE_SET, "SET_USE_AUDIO_SCENE_SET");
        show(phase, svc, DEV_INSTRUMENT, F_USE_AUDIO_SCENE_SET, "SET_USE_AUDIO_SCENE_SET@1007");
        show(phase, svc, DEV_AUDIO, F_FLIP_SETTING, "AUDIO_SPEAKER_FLIP_SETTING_STATUS");
        show(phase, svc, DEV_AUDIO, F_FLIP_CONFIG, "AUDIO_SPEAKER_FLIP_COVER_CONFIG");
        show(phase, svc, DEV_AUDIO, F_RLSA_STATE, "AUDIO_RLSA_STATE");
        show(phase, svc, DEV_AUDIO, F_MASTER_VOL, "AUDIO_MASTER_VOLUME_STATE");
        Object mgr;
        try {
            mgr = manager();
            out(phase + ": VehicleAudioStateManager instance = " + mgr);
        } catch (Throwable t) {
            out(phase + ": getInstance failed: " + describe(t));
            return;
        }
        readBack(phase, mgr, "getCurrentUseVehicleSpeakerDevice");
        readBack(phase, mgr, "isCurrentSystemUseVehicleSpeaker");
        readBack(phase, mgr, "getVehiclePhoneState");
    }

    private static void show(String phase, IBinder svc, int dev, int fid, String name) {
        try {
            out(String.format("%s: %-38s dev=%d fid=0x%08X -> %d",
                    phase, name, dev, fid, getInt(svc, dev, fid)));
        } catch (Throwable t) {
            out(phase + ": " + name + " -> ERR " + t);
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

    // --------------------------------------------------------------- pulses

    private static Object manager() throws Exception {
        Class<?> cls = Class.forName("android.media.VehicleAudioStateManager");
        return cls.getMethod("getInstance", Context.class).invoke(null, systemContext());
    }

    private static Object invokeManager(String method) throws Exception {
        Object mgr = manager();
        try {
            return mgr.getClass().getMethod(method).invoke(mgr);
        } catch (Throwable t) {
            return "THREW " + describe(t);
        }
    }

    private static void readBack(String phase, Object mgr, String method) {
        try {
            out(phase + ": " + method + "() = "
                    + mgr.getClass().getMethod(method).invoke(mgr));
        } catch (Throwable t) {
            out(phase + ": " + method + "() -> " + describe(t));
        }
    }

    /** Unwraps reflection wrappers so the real failure is visible in one line. */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        for (int i = 0; cur != null && i < 5; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(cur.getClass().getName());
            if (cur.getMessage() != null) sb.append(": ").append(cur.getMessage());
            cur = cur.getCause();
        }
        return sb.toString();
    }

    /**
     * Resolves every method the firing modes need, without invoking any of
     * them. Lets the whole harness be validated off-test, so a live run cannot
     * fail on a missing symbol while someone is watching the covers.
     */
    private static void dryRun() throws Exception {
        Object mgr = manager();
        for (String m : new String[]{"setUseVehicleSpeaker", "requestUseVehicleSpeaker"}) {
            out("resolve " + m + " -> " + mgr.getClass().getMethod(m));
        }
        out("resolve isStreamAllowed -> "
                + AudioManager.class.getMethod("isStreamAllowed", int.class, String.class));
        out("resolve MediaPlayer.setAudioStreamType -> "
                + MediaPlayer.class.getMethod("setAudioStreamType", int.class));
        // requestUseVehicleSpeaker only acts when mCurrentUse != TYPE_IVI(1).
        Object cur = mgr.getClass().getMethod("getCurrentUseVehicleSpeakerDevice").invoke(mgr);
        out("mCurrentUse = " + cur
                + (Integer.valueOf(1).equals(cur)
                ? "  => requestspeaker would be a NO-OP; use usespeaker"
                : "  => requestspeaker would fire"));
    }

    /** The exact call BYD's patched MediaPlayer.startImpl() makes. */
    private static void streamAllowed(int stream, String pkg) throws Exception {
        AudioManager am = (AudioManager) systemContext().getSystemService(Context.AUDIO_SERVICE);
        Method m = AudioManager.class.getMethod("isStreamAllowed", int.class, String.class);
        out("isStreamAllowed(" + stream + ", " + pkg + ") -> " + m.invoke(am, stream, pkg));
    }

    /**
     * Plain MediaPlayer on a local file, shaped like stock: setDataSource on a
     * FileDescriptor (which is what sets MediaPlayer.mIsLocalSource) and legacy
     * stream 3, so startImpl() takes the isStreamAllowed branch.
     */
    private static void mediaPlayer(String path, int secs) throws Exception {
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignore) {
            // Already prepared in this process; MediaPlayer only needs one.
        }
        FileInputStream in = new FileInputStream(path);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(in.getFD());
            try {
                mp.prepare();
            } catch (SecurityException e) {
                // prepare() runs _prepare() and only then scans subtitle tracks,
                // which reaches the Settings provider through CaptioningManager.
                // That provider rejects this process: shell uid 2000 holding a
                // system context whose package name is "android". The native
                // player is already prepared, so this is safe to swallow.
                out("prepare(): subtitle scan denied as shell UID, continuing: " + e.getMessage());
            }
            out("MediaPlayer prepared: " + path + " duration=" + mp.getDuration() + "ms");
            mp.start();
            out("MediaPlayer.start() issued, isPlaying=" + mp.isPlaying());
            Thread.sleep(secs * 1000L);
        } finally {
            try {
                mp.stop();
            } catch (Throwable ignore) {
            }
            mp.release();
            in.close();
            out("MediaPlayer released");
        }
    }

    // -------------------------------------------------------------- helpers

    private static IBinder getService(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
    }

    private static Context systemContext() throws Exception {
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
        System.out.println("[VSP] " + msg);
        Log.i(TAG, "[VSP] " + msg);
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
