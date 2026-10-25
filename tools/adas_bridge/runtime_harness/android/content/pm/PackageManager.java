package android.content.pm;
public class PackageManager {
    public static final int COMPONENT_ENABLED_STATE_DISABLED_USER=3;
    public int state=3;
    public long nextReadDelayMs;
    public Runnable afterReadDelay;
    public int getApplicationEnabledSetting(String name){long delay=nextReadDelayMs;nextReadDelayMs=0;if(delay>0)android.os.Handler.block(delay);Runnable after=afterReadDelay;afterReadDelay=null;if(after!=null)after.run();return state;}
}
