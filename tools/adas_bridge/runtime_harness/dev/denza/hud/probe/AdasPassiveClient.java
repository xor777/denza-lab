package dev.denza.hud.probe;
import android.content.Context;import android.os.SystemClock;import java.util.*;
final class AdasPassiveClient implements AutoCloseable {
    interface Sink {boolean enqueue(Runnable task);default boolean enqueue(long topic,Runnable task){return enqueue(task);}void state(String event,String detail);void event(long topic,long timestamp,long receivedAt,byte[] payload);}
    static AdasPassiveClient latest,answers,core;final Sink sink;final boolean coreMode;boolean closed,ready=true;long epoch=1;
    final List<Map<Long,Long[]>> starts=new ArrayList<>(),stops=new ArrayList<>(),unsubscribes=new ArrayList<>();
    AdasPassiveClient(Context c,Sink s,boolean mode){this(c,s,mode,false);}
    AdasPassiveClient(Context c,Sink s,boolean mode,boolean coreMode){sink=s;this.coreMode=coreMode;latest=this;if(coreMode)core=this;else answers=this;}
    void start(){sink.state("binder_connected","fake SDK boundary");sink.state("subscribed","fake SDK boundary");}
    boolean liveAnswersReady(){return ready&&!closed;}boolean binderAlive(){return ready&&!closed;}long epoch(){return epoch;}boolean isClosed(){return closed;}
    void nativeStart(Map<Long,Long[]> values){if(closed)throw new AssertionError("subscriptions after close");starts.add(values);}
    void nativeStop(Map<Long,Long[]> values){if(closed)throw new AssertionError("stop after close");stops.add(values);}
    void nativeUnsubscribe(Map<Long,Long[]> values){if(closed)throw new AssertionError("unsubscribe after close");unsubscribes.add(values);}
    void stopAdmission(){ready=false;}
    void answer(long topic,byte[] payload){long at=SystemClock.now;sink.enqueue(topic,()->sink.event(topic,0,at,payload));}
    public void close(){closed=true;epoch++;}
}
