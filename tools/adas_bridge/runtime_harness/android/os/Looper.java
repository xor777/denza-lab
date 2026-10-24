package android.os;
public final class Looper {
    private static final Looper INSTANCE=new Looper();
    public static Looper getMainLooper(){return INSTANCE;}
    public boolean isCurrentThread(){return true;}
}
