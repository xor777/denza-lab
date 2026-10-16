package dev.denza.vehiclecontrol.probe;
import static org.junit.Assert.*;
import org.junit.Test;
import androidx.test.platform.app.InstrumentationRegistry;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;
public final class RuntimeTest  {
    @Test public void testEmbeddedController() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context));
        PyObject module = Python.getInstance().getModule("android_remote");
        String page = module.callAttr("page").toString();
        assertTrue(page.contains("AndroidRemote.request("));
        assertFalse(page.contains("await fetch("));
        assertTrue(page.contains("Панорама"));
        PyObject remote = module.callAttr("create", new OfflineTransport(), context.getCacheDir().getAbsolutePath());
        String answer = remote.callAttr("request", "").toString();
        assertTrue(answer.contains("Нет локального доступа ADB"));
        assertFalse(answer.contains("accepted"));
        RemoteActivity.Controller bridge = new RemoteActivity.Controller(remote);
        bridge.active = false;
        assertTrue(bridge.request("{\"window\":\"fl\",\"action\":\"open\"}").contains("не на экране"));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.content.Intent intent = new android.content.Intent(context, RemoteActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        RemoteActivity activity = (RemoteActivity) instrumentation.startActivitySync(intent);
        instrumentation.runOnMainSync(() -> activity.showRemote(remote, page));
        java.util.concurrent.atomic.AtomicReference<String> visible = new java.util.concurrent.atomic.AtomicReference<>("");
        for (int attempt = 0; attempt < 30 && !visible.get().contains("Нет локального доступа ADB"); attempt++) {
            java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
            instrumentation.runOnMainSync(() -> {
                android.widget.LinearLayout layout = (android.widget.LinearLayout) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                android.webkit.WebView web = (android.webkit.WebView) layout.getChildAt(1);
                web.evaluateJavascript("document.querySelector('#connection')?.textContent", result -> { visible.set(result); read.countDown(); });
            });
            assertTrue(read.await(2, java.util.concurrent.TimeUnit.SECONDS));
            if (!visible.get().contains("Нет локального доступа ADB")) Thread.sleep(100);
        }
        assertTrue("WebView response: " + visible.get(), visible.get().contains("Нет локального доступа ADB"));
        instrumentation.runOnMainSync(activity::finish);
        // Import and run the actual controller arithmetic, guards and parcel parsers in Android Python.
        PyObject builtins = Python.getInstance().getModule("builtins");
        builtins.callAttr("exec", "import vehicle_control as c, vehicle_windows as w, subprocess\n"
                + "assert c.read_command('BODY_DRIVER_SEAT_HORIZONTAL_POSITION').startswith('service call autoservice 7 ')\n"
                + "assert w.set_result(\"Result: Parcel(00000001    '....')\") == 1\n"
                + "assert 'sleep 0.3' in c.pulse_script('START', 'STOP')\n"
                + "assert 'trap finish EXIT' in c.pulse_script('START', 'STOP')\n"
                + "out = subprocess.run(['/system/bin/sh', '-c', c.pulse_script('echo START', 'echo STOP')], capture_output=True, text=True, timeout=3)\n"
                + "assert out.returncode == 0 and out.stdout.splitlines() == ['@start', 'START', '@stop', 'STOP'], out\n", Python.getInstance().getModule("__main__").get("__dict__"));
    }
    public static final class OfflineTransport {
        public String shell(String command, int timeout) throws java.io.IOException { throw new java.io.IOException("offline fake"); }
    }
}
