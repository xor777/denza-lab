package android.os;
import java.util.ArrayList;
import java.util.List;
public class Handler {
    private static final List<Task> tasks=new ArrayList<>();
    private static long serial;
    private static final class Task {Handler owner;Object token;Runnable action;long at,serial;}
    public Handler(Looper ignored){}
    public Looper getLooper(){return Looper.getMainLooper();}
    public boolean post(Runnable r){return postAtTime(r,null,SystemClock.now);}
    public boolean postDelayed(Runnable r,long d){return postAtTime(r,null,SystemClock.now+d);}
    public boolean postAtTime(Runnable r,Object token,long at){Task t=new Task();t.owner=this;t.token=token;t.action=r;t.at=at;t.serial=serial++;tasks.add(t);return true;}
    public void removeCallbacksAndMessages(Object token){tasks.removeIf(t->t.owner==this&&(token==null||token.equals(t.token)));}
    public static void reset(){tasks.clear();serial=0;SystemClock.now=100000;}
    public static void advance(long ms){long end=SystemClock.now+ms;int runs=0;while(true){Task next=null;for(Task t:tasks)if(t.at<=end&&(next==null||t.at<next.at||t.at==next.at&&t.serial<next.serial))next=t;if(next==null)break;tasks.remove(next);SystemClock.now=next.at;next.action.run();if(++runs>20000)throw new AssertionError("runaway scheduler");}SystemClock.now=end;}
}
