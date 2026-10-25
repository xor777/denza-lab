package dev.denza.hud.probe;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import dev.denza.adaslink.stocknative.StockRuntime;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Three owned Handler queues. Disposal fences dequeued work as well as pending callbacks. */
final class AndroidStockRuntime implements StockRuntime, AutoCloseable {
    private final EnumMap<Queue, Handler> handlers = new EnumMap<>(Queue.class);
    private final EnumMap<Queue, IdentityHashMap<Object, java.util.List<Runnable>>> callbacks = new EnumMap<>(Queue.class);
    private final EnumMap<Queue, IdentityHashMap<Runnable,Object>> tokens = new EnumMap<>(Queue.class);
    private final HandlerThread mmt = new HandlerThread("denza-stock-mmt"), someip = new HandlerThread("denza-stock-someip");
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Consumer<Throwable> failure;
    AndroidStockRuntime(Consumer<Throwable> failure) {
        this.failure=failure;
        mmt.start(); someip.start();
        handlers.put(Queue.MMT,new Handler(mmt.getLooper()));
        handlers.put(Queue.MAIN,new Handler(Looper.getMainLooper()));
        handlers.put(Queue.SOMEIP,new Handler(someip.getLooper()));
        for(Queue q:Queue.values()){callbacks.put(q,new IdentityHashMap<>());tokens.put(q,new IdentityHashMap<>());}
    }
    public long uptimeMillis(){return SystemClock.uptimeMillis();}
    public long elapsedRealtime(){return SystemClock.elapsedRealtime();}
    public long currentTimeMillis(){return System.currentTimeMillis();}
    public void post(Queue q,Object identity,Object token,long delay,Runnable action){
        if(delay<0)throw new IllegalArgumentException("negative delay");
        final Runnable[] slot=new Runnable[1];
        slot[0]=()->{
            synchronized(this){tokens.get(q).remove(slot[0]);java.util.List<Runnable> list=callbacks.get(q).get(identity);if(list!=null){list.remove(slot[0]);if(list.isEmpty())callbacks.get(q).remove(identity);}}
            if(!active.get())return;
            try{action.run();}catch(Throwable error){if(active.getAndSet(false))failure.accept(error);close();}
        };
        synchronized(this){
            if(!active.get())return;
            callbacks.get(q).computeIfAbsent(identity,k->new java.util.ArrayList<>()).add(slot[0]);
            tokens.get(q).put(slot[0],token);
            if(!handlers.get(q).postAtTime(slot[0],token,uptimeMillis()+delay)){
                callbacks.get(q).get(identity).remove(slot[0]);tokens.get(q).remove(slot[0]);
                throw new IllegalStateException("sender queue stopped");
            }
        }
    }
    public synchronized void removeCallbacks(Queue q,Object identity){
        if(identity==null)return;
        java.util.List<Runnable> list=callbacks.get(q).remove(identity);
        if(list!=null)for(Runnable action:list){handlers.get(q).removeCallbacks(action);tokens.get(q).remove(action);}
    }
    public synchronized void removeToken(Queue q,Object token){
        handlers.get(q).removeCallbacksAndMessages(token);
        java.util.Iterator<java.util.Map.Entry<Object,java.util.List<Runnable>>> keys=callbacks.get(q).entrySet().iterator();
        while(keys.hasNext()){
            java.util.List<Runnable> actions=keys.next().getValue();
            java.util.Iterator<Runnable> iterator=actions.iterator();
            while(iterator.hasNext()){Runnable action=iterator.next();if(token==null||tokens.get(q).get(action)==token){iterator.remove();tokens.get(q).remove(action);}}
            if(actions.isEmpty())keys.remove();
        }
    }
    public synchronized void close(){
        active.set(false);
        for(Map.Entry<Queue,Handler> entry:handlers.entrySet())entry.getValue().removeCallbacksAndMessages(null);
        for(IdentityHashMap<Object,java.util.List<Runnable>> map:callbacks.values())map.clear();
        for(IdentityHashMap<Runnable,Object> map:tokens.values())map.clear();
        mmt.quitSafely(); someip.quitSafely();
    }
    synchronized int pendingCount(){int count=0;for(IdentityHashMap<Runnable,Object> map:tokens.values())count+=map.size();return count;}
    boolean active(){return active.get();}
}
