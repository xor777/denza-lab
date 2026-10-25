package android.os;
public class HandlerThread { private final Looper looper=new Looper();public HandlerThread(String name){}public void start(){}public Looper getLooper(){return looper;}public boolean quitSafely(){return true;} }
