package android.os;
public final class Looper {
    private static final Looper MAIN=new Looper();
    static Looper current=MAIN;
    public static Looper getMainLooper(){return MAIN;}
    public static Looper myLooper(){return current;}
    public boolean isCurrentThread(){return current==this;}
}
