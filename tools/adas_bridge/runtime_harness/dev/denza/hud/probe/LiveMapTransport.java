package dev.denza.hud.probe;
import android.content.Context;import android.os.Handler;import android.os.SystemClock;
import dev.denza.adaslink.*;import java.util.*;
/** Fake Binder boundary: records actual calls made by the production guard/runtime. */
final class LiveMapTransport implements Publisher,AutoCloseable {
    interface Listener {void changed(String state);}
    interface Admission {boolean allow(long topic);}
    static LiveMapTransport latest;
    final List<Long> topics=new ArrayList<>();final List<byte[]> payloads=new ArrayList<>();final List<Long> times=new ArrayList<>();final Set<Long> offered=new HashSet<>();
    boolean closed,ready=true;long generation=1,last=-1;int result;final Listener listener;final Admission admission;
    Runnable beforeAdmission;
    LiveMapTransport(Context c,Handler h,Listener l,Publisher p,Admission a){latest=this;listener=l;admission=a;}
    void start(){listener.changed("server_connected");}
    boolean ready(){return ready&&!closed;}long generation(){return generation;}
    boolean servicesOffered(){return offered.size()==4&&ready();}
    boolean isClosed(){return closed;}long lastActualSend(){return last;}
    public BridgeStatus.Mode mode(){return BridgeStatus.Mode.LIVE;}
    public int startService(long t){if(!ready())return -1;offered.add(t);return 0;}
    public int fire(long t,byte[] b,long now){if(!ready())throw new AssertionError("fire after close");Runnable before=beforeAdmission;beforeAdmission=null;if(before!=null)before.run();if(!admission.allow(t))return -203;topics.add(t);payloads.add(b);times.add(SystemClock.now);if(result==0)last=SystemClock.now;return result;}
    public void note(String k,Map<String,Object> f,long n){}
    public void close(){closed=true;generation++;}
    int count(long t){int count=0;for(long x:topics)if(x==t)count++;return count;}
    /** NavigationStatus values actually sent, in order. */
    List<Long> statuses(){List<Long> out=new ArrayList<>();for(int i=0;i<topics.size();i++)if(topics.get(i)==Topics.NAVIGATION_STATUS){
        Map<?,?> body=(Map<?,?>)Pb.NAVIGATION_STATUS_NOTIFY.decode(payloads.get(i)).get("navigationStatusLinkInfoNotifyStruct");
        out.add(((Number)body.get("navigationStatus")).longValue());}return out;}
    /** Path ID of the last actual selection; -1 when none. */
    long lastSelected(){for(int i=topics.size()-1;i>=0;i--)if(topics.get(i)==Topics.PATH_SELECTED){
        Map<?,?> body=(Map<?,?>)Pb.PATH_SELECT_NOTIFY.decode(payloads.get(i)).get("naviPathUserSelectStsStruct");
        return ((Number)body.get("pathIDReq")).longValue();}return -1;}
    /** Time of the first actual send of {@code t} at or after {@code from}; -1 when none. */
    long first(long t,long from){for(int i=0;i<topics.size();i++)if(topics.get(i)==t&&times.get(i)>=from)return times.get(i);return -1;}
}
