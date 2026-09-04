package dev.denza.apps.feature.mirrors;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.TextureView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual production renderer against a tiny host Android shim; not an AVC/firmware emulator. */
public class RendererContractTest {
    @Test public void baselinePreservesAllExistingTransactions() {
        Fixture f = new Fixture();
        f.start();
        assertEquals(Arrays.asList(1, 6, 7, 5), f.context.calls);
        assertEquals(2005, f.context.viewpoint);
        assertEquals(1, f.ready.size());
        assertTrue(f.failures.isEmpty());
    }

    @Test public void baselinePreservesDiagnosticFailureBehavior() {
        Fixture f = new Fixture();
        f.context.rejectDiagnostics = true;
        f.start();
        assertTrue(f.ready.isEmpty());
        assertEquals(1, f.failures.size());
        assertEquals(Arrays.asList(1), f.context.calls);
        f.frame();
        assertTrue(f.frames.isEmpty());
    }

    @Test public void rejectedInitNeverSetsViewpointOrReportsReadyOrFrame() {
        Fixture f = new Fixture();
        f.context.acceptInit = false;
        f.start();
        f.frame();
        assertFalse(f.context.calls.contains(5));
        assertTrue(f.ready.isEmpty());
        assertTrue(f.frames.isEmpty());
        assertEquals(1, f.failures.size());
    }

    @Test public void reportsFirstTextureUpdateOnlyOnceAndOnlyAfterReady() {
        Fixture f = new Fixture();
        SystemClock.now = 100;
        f.renderer.start(2005, false);
        f.frame();
        assertTrue(f.frames.isEmpty());
        SystemClock.now = 120;
        f.context.connect();
        assertEquals(1, f.ready.size());
        assertTrue("READY is not a rendered frame", f.frames.isEmpty());
        SystemClock.now = 150;
        f.frame();
        assertEquals(1, f.frames.size());
        assertTrue(f.frames.get(0), f.frames.get(0).contains("renderer_to_frame_ms=50"));
        assertTrue(f.frames.get(0), f.frames.get(0).contains("ready_to_frame_ms=30"));
        int clockReads = SystemClock.reads;
        for (int i = 0; i < 100; i++) f.frame();
        assertEquals(1, f.frames.size());
        assertEquals("no steady-state per-frame clock reads", clockReads, SystemClock.reads);
    }

    @Test public void bindBeforeSurfaceStillInitializesOnce() {
        Fixture f = new Fixture();
        f.texture.available = false;
        f.renderer.start(2005, false);
        f.context.connect();
        assertTrue(f.context.calls.isEmpty());
        f.texture.available = true;
        f.renderer.onSurfaceTextureAvailable(f.texture.texture, 720, 450);
        f.renderer.onSurfaceTextureSizeChanged(f.texture.texture, 720, 450);
        assertEquals(Arrays.asList(1, 6, 7, 5), f.context.calls);
        f.frame();
        assertEquals(1, f.frames.size());
    }

    @Test public void stopAndDisconnectSuppressLateFrameMetrics() {
        Fixture stopped = new Fixture();
        stopped.start();
        stopped.renderer.stop();
        stopped.frame();
        assertTrue(stopped.frames.isEmpty());
        assertEquals(1, stopped.context.unbinds);
        assertEquals(Integer.valueOf(9), stopped.context.calls.get(stopped.context.calls.size() - 1));

        Fixture disconnected = new Fixture();
        disconnected.start();
        disconnected.context.connection.onServiceDisconnected(new ComponentName());
        disconnected.frame();
        assertTrue(disconnected.frames.isEmpty());
        assertEquals(1, disconnected.failures.size());
    }

    @Test public void oldTextureAndDestroyedSurfaceCannotProduceFirstFrameMetrics() {
        Fixture f = new Fixture();
        f.start();
        f.renderer.onSurfaceTextureUpdated(new SurfaceTexture());
        assertTrue(f.frames.isEmpty());
        f.renderer.onSurfaceTextureDestroyed(f.texture.texture);
        f.frame();
        assertTrue(f.frames.isEmpty());
    }

    @Test public void aLaterStartGetsItsOwnFirstFrameMeasurement() {
        Fixture f = new Fixture();
        SystemClock.now = 100;
        f.start();
        SystemClock.now = 125;
        f.frame();
        SystemClock.now = 200;
        f.start();
        SystemClock.now = 210;
        f.frame();
        assertEquals(2, f.frames.size());
        assertTrue(f.frames.get(0).contains("renderer_to_frame_ms=25"));
        assertTrue(f.frames.get(1).contains("renderer_to_frame_ms=10"));
    }

    @Test public void recordsEachBinderStageWithoutChangingTheCalls() {
        Fixture f = new Fixture();
        SystemClock.now = 100;
        f.context.advanceClock = true;
        f.start();
        SystemClock.now += 11;
        f.frame();
        String details = f.frames.get(0);
        for (String metric : Arrays.asList("get_name_ms=1", "buffer_type_ms=6",
                "init_display_ms=7", "viewpoint_ms=5", "ready_to_frame_ms=11",
                "renderer_to_frame_ms=30")) assertTrue(details, details.contains(metric));
        assertEquals(Arrays.asList(1, 6, 7, 5), f.context.calls);
    }

    private static final class Fixture implements AvcCameraRenderer.Listener {
        final FakeContext context = new FakeContext();
        final TextureView texture = new TextureView(context);
        final List<String> ready = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<String> frames = new ArrayList<>();
        final AvcCameraRenderer renderer = new AvcCameraRenderer(context, texture, this);
        void start() { renderer.start(2005, false); context.connect(); }
        void frame() { renderer.onSurfaceTextureUpdated(texture.texture); }
        public void onReady(String details) { ready.add(details); }
        public void onFailure(String details) { failures.add(details); }
        public void onLocalSurfaceReleased() { }
        // Deliberately also compiles against the old Listener so RED is behavioural, not a
        // missing-method/compiler error. The instrumented renderer invokes this new callback.
        public void onFirstFrame(String details) { frames.add(details); }
    }

    private static final class FakeContext extends Context implements IBinder {
        final List<Integer> calls = new ArrayList<>();
        ServiceConnection connection;
        boolean rejectDiagnostics;
        boolean acceptInit = true;
        boolean advanceClock;
        int viewpoint;
        int unbinds;
        public boolean bindService(Intent intent, ServiceConnection value, int flags) {
            connection = value;
            return true;
        }
        public void unbindService(ServiceConnection value) { unbinds++; }
        void connect() { connection.onServiceConnected(new ComponentName(), this); }
        public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            calls.add(code);
            if (advanceClock) SystemClock.now += code;
            if (rejectDiagnostics && (code == 1 || code == 6)) throw new RemoteException("diagnostic only");
            switch (code) {
                case 1: reply.writeString("fixture"); break;
                case 6: reply.writeInt(0); break;
                case 7: reply.writeInt(acceptInit ? 1 : 0); break;
                case 5: data.readString(); viewpoint = data.readInt(); break;
                case 9: break;
                default: throw new AssertionError("unexpected transaction " + code);
            }
            return true;
        }
    }
}
