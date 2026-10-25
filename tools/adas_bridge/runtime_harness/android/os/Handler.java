package android.os;
import java.util.ArrayList;
import java.util.List;
public class Handler {
    private static final List<Task> tasks=new ArrayList<>();
    private static long serial;
    private static final class Task {Handler owner;Object token;Runnable action;long at,serial;}
    private final Looper looper;
    public Handler(Looper value){looper=value;}
    public Looper getLooper(){return looper;}
    public boolean post(Runnable r){
        // Model a completed synchronous worker handoff without blocking this single-thread test driver.
        if(r instanceof java.util.concurrent.FutureTask){Looper previous=Looper.current;Looper.current=looper;try{r.run();}finally{Looper.current=previous;}return true;}
        return postAtTime(r,null,SystemClock.now);
    }
    public boolean postDelayed(Runnable r,long d){return postAtTime(r,null,SystemClock.now+d);}
    public boolean postAtTime(Runnable r,Object token,long at){Task t=new Task();t.owner=this;t.token=token;t.action=r;t.at=at;t.serial=serial++;tasks.add(t);return true;}
    public void removeCallbacksAndMessages(Object token){tasks.removeIf(t->t.owner==this&&(token==null||token==t.token));}
    public void removeCallbacks(Runnable action){tasks.removeIf(t->t.owner==this&&t.action==action);}
    public static void reset(){tasks.clear();serial=0;SystemClock.now=100000;}
    /** A process death: every pending task of every handler is gone; the clock keeps going. */
    public static void killAll(){tasks.clear();}
    /** Overdue tasks run at the current time, as on a worker that was blocked. */
    public static void block(long ms){SystemClock.now+=ms;}
    public static void advance(long ms){long end=SystemClock.now+ms;int runs=0;while(true){Task next=null;for(Task t:tasks)if(t.at<=end&&(next==null||t.at<next.at||t.at==next.at&&t.serial<next.serial))next=t;if(next==null)break;tasks.remove(next);SystemClock.now=Math.max(SystemClock.now,next.at);Looper previous=Looper.current;Looper.current=next.owner.looper;try{next.action.run();}finally{Looper.current=previous;}if(++runs>20000)throw new AssertionError("runaway scheduler");}SystemClock.now=end;}
}
