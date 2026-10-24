package android.os;
public final class SystemClock {
    public static long now=100000;
    public static long elapsedRealtime(){return now;}
    public static long uptimeMillis(){return now;}
}
