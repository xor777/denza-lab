package android.content;
public final class Intent {
    public ComponentName component; public String type;
    public Intent setComponent(ComponentName value) { component=value; return this; }
    public Intent setType(String value) { type=value; return this; }
}
