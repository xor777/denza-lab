package android.content;
public abstract class Context {
    public static final int BIND_AUTO_CREATE=1;
    public Context getApplicationContext() { return this; }
    public abstract String getPackageName();
    public abstract boolean bindService(Intent intent, ServiceConnection connection, int flags);
    public abstract void unbindService(ServiceConnection connection);
}
