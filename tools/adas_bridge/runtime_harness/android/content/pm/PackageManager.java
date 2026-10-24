package android.content.pm;
public class PackageManager {
    public static final int COMPONENT_ENABLED_STATE_DISABLED_USER=3;
    public int state=3;
    public int getApplicationEnabledSetting(String name){return state;}
}
