package dev.denza.hud.probe;
import android.content.Context;import android.os.SystemClock;
final class AdasPassiveClient implements AutoCloseable {
    interface Sink {boolean enqueue(Runnable task);default boolean enqueue(long topic,Runnable task){return enqueue(task);}void state(String event,String detail);void event(long topic,long timestamp,long receivedAt,byte[] payload);}
    static AdasPassiveClient latest;final Sink sink;boolean closed,ready=true;long epoch=1;
    AdasPassiveClient(Context c,Sink s,boolean mode){if(!mode)throw new AssertionError("bridge subscription");sink=s;latest=this;}
    void start(){sink.state("binder_connected","fake boundary");}
    boolean liveAnswersReady(){return ready&&!closed;}long epoch(){return epoch;}boolean isClosed(){return closed;}
    void answer(long t,byte[] b){long now=SystemClock.now;sink.enqueue(t,()->sink.event(t,0,now,b));}
    public void close(){closed=true;epoch++;sink.state("closed","own session");}
}
