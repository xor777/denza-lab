package android.os;
import java.util.ArrayList;
public final class Handler {
    private final ArrayList<Runnable> tasks = new ArrayList<>();
    public Handler(Looper looper) { }
    public boolean postDelayed(Runnable task, long delay) { tasks.add(task); return true; }
    public void removeCallbacksAndMessages(Object token) { tasks.clear(); }
}
