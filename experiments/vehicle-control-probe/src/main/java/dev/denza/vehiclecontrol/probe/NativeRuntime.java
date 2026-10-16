package dev.denza.vehiclecontrol.probe;

import android.content.Context;
import dev.denza.disharebridge.LocalAdbClient;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process owner survives Activity recreation; no boot service and no autonomous polling. */
final class NativeRuntime {
    private static NativeRuntime instance;
    final NativeController controller;
    final AtomicBoolean reading=new AtomicBoolean(), writing=new AtomicBoolean();
    final ExecutorService operations=Executors.newSingleThreadExecutor();
    private final ExecutorService release=Executors.newSingleThreadExecutor();
    volatile NativeController.Hold activeHold;
    private NativeRuntime(NativeController controller) { this.controller=controller; }
    static synchronized NativeRuntime get(Context context) throws Exception {
        if(instance==null) {
            Context app=context.getApplicationContext();
            String json;
            try(var input=app.getAssets().open("vehicle_controls.json")) {json=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
            instance=new NativeRuntime(new NativeController(new JSONObject(json),()-> {
                if(!new File(app.getNoBackupFilesDir(),"adb_auth_key_v1").isFile()) throw new java.io.IOException("Сначала разрешите доступ ADB");
                LocalAdbClient client=LocalAdbClient.loopbackOnly(app);
                return new NativeController.Connection(client.publicKeyFingerprint(),client::shell);
            },new File(app.getFilesDir(),"actions/actions.jsonl")));
        }
        return instance;
    }
    static synchronized void useForTest(NativeController controller) { instance=controller==null?null:new NativeRuntime(controller); }
    void releaseHold() {
        NativeController.Hold hold=activeHold;
        if(hold==null) return;
        hold.released.set(true); // cancels even while preflight is reading, before the worker can START.
        release.execute(()-> {try {controller.release(hold);}catch(Exception ignored){/* device deadline remains armed; never claim STOP from flag write */}});
    }
}
