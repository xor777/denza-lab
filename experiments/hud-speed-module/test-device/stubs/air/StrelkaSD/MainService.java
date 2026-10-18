package air.StrelkaSD;
/** Compile-only shape. NEVER packaged: the test runs the real patched upstream service. */
public class MainService extends android.app.Service {
    public android.os.IBinder onBind(android.content.Intent intent) { return null; }
}
