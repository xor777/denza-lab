package dev.denza.hud.probe;

import android.content.*;
import android.os.*;
import java.util.*;

/** Tests the production client; only the Android/Binder boundary is replaced. No device access. */
public final class AdasPassiveClientTest {
    private static final String CLIENT="ts.car.someip.sdk.ISomeIpClientInterface", CALLBACK="ts.car.someip.sdk.ISomeIpCallback";
    private static final long PA=0x0001000D000D0001L, PU=PA+1, RA=0x0001002C002C0001L, RU=RA+1;
    private static int checks;
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    private static final class Sink implements AdasPassiveClient.Sink {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>(); final List<Long> topics=new ArrayList<>();
        final List<byte[]> payloads=new ArrayList<>(); final List<String> states=new ArrayList<>(); boolean admit=true;
        public boolean enqueue(Runnable task) { if(!admit)return false; if(queue.size()>=1000)throw new AssertionError("Queue cap"); queue.add(task); return true; }
        public void state(String event,String detail) { states.add(event+":"+detail); }
        public void event(long topic,long stamp,long at,byte[] payload) { topics.add(topic); payloads.add(payload); }
        void drain() { int n=0; while(!queue.isEmpty()) { if(++n>1000)throw new AssertionError("Drain cap"); queue.remove().run(); } }
    }
    private static final class App extends Context {
        ServiceConnection connection; Intent intent; int unbinds;
        public String getPackageName() { return "test.navigator"; }
        public boolean bindService(Intent intent,ServiceConnection connection,int flags) { this.intent=intent; this.connection=connection; return true; }
        public void unbindService(ServiceConnection connection) { check(this.connection==connection,"Own binding only"); unbinds++; }
        void connect(Hal hal) { connection.onServiceConnected(intent.component,hal); }
        void disconnect() { connection.onServiceDisconnected(intent.component); }
    }
    private static final class Call {
        final int tx; final long value; Call(int tx,long value) {this.tx=tx;this.value=value;}
    }
    private static final class Hal extends Binder {
        final Map<Integer,Integer> references=new HashMap<>(); final Map<Long,Integer> subs=new HashMap<>();
        final Set<Integer> available=new HashSet<>(Arrays.asList(13,44)); final List<Call> calls=new ArrayList<>();
        IBinder callback; int failStart=-1, queries, unregisters; long failTopic=-1; boolean alive=true, availableHal=true;
        @Override public boolean isBinderAlive(){return alive;}
        static int service(long topic){return (int)((topic>>>32)&0xffff);}
        int refs(int id){return references.getOrDefault(id,0);}
        int count(int tx){int n=0;for(Call c:calls)if(c.tx==tx)n++;return n;}
        int call(int tx,long value)throws RemoteException {
            Parcel d=Parcel.obtain(),r=Parcel.obtain();d.writeInterfaceToken(CLIENT);d.writeLong(value);
            transact(tx,d,r,0);r.readException();return r.readInt();
        }
        @Override protected boolean onTransact(int tx,Parcel data,Parcel reply,int flags)throws RemoteException {
            data.enforceInterface(CLIENT);
            if(tx==1||tx==2){IBinder cb=data.readStrongBinder(); if(tx==1)callback=cb;else{check(callback==cb,"Unregister own callback");callback=null;unregisters++;}reply.writeNoException();return true;}
            if(tx==3){queries++;reply.writeNoException();reply.writeInt(availableHal?1:0);return true;}
            long value=data.readLong();int id=service(value),result=0;calls.add(new Call(tx,value));
            if(tx==4){if(id==failStart)result=11;else{result=refs(id)>0?13:0;references.put(id,refs(id)+1);}}
            else if(tx==5){check(refs(id)>0,"Stop only owned reference");references.put(id,refs(id)-1);}
            else if(tx==8){
                if(refs(id)==0||value==failTopic)result=11;
                else{subs.put(value,subs.getOrDefault(value,0)+1);if((value==PA||value==RA)&&available.contains(id))emit(value,new byte[0]);}
            }else if(tx==9){int n=subs.getOrDefault(value,0);check(n>0,"Unsubscribe only successful own subscription");subs.put(value,n-1);}
            else throw new AssertionError("Forbidden transaction "+tx);
            reply.writeNoException();reply.writeInt(result);return true;
        }
        void emit(long topic,byte[] payload)throws RemoteException {
            if(callback==null)return;
            Parcel d=Parcel.obtain(),r=Parcel.obtain();d.writeInterfaceToken(CALLBACK);d.writeInt(1);
            d.writeLong(topic);d.writeLong(123);d.writeInt(payload==null?-1:payload.length);if(payload!=null)d.writeByteArray(payload);
            check(callback.transact(1,d,r,0),"Callback delivered");r.readException();
            check(r.readInt()==1&&r.readLong()==topic&&r.readLong()==123,"SDK callback echo identity");
            int size=r.readInt();check(size==(payload==null?-1:payload.length),"SDK callback echo length");
            if(payload!=null){byte[] echo=new byte[size];r.readByteArray(echo);check(Arrays.equals(echo,payload),"SDK callback echo bytes");}
        }
        void hal(boolean value)throws RemoteException {
            availableHal=value;Parcel d=Parcel.obtain(),r=Parcel.obtain();d.writeInterfaceToken(CALLBACK);d.writeInt(value?1:0);
            callback.transact(2,d,r,0);r.readException();
        }
    }
    private static final class Fixture {
        final App app=new App();final Sink sink=new Sink();final Hal hal=new Hal(); final AdasPassiveClient client;
        Fixture(boolean bridge){client=new AdasPassiveClient(app,sink,bridge);}
        void start(){client.start();app.connect(hal);sink.drain();}
    }
    private static void fresh()throws Exception {
        Hal boundary=new Hal();check(boundary.call(8,PA)==11,"Fresh native boundary rejects subscribe before start");
        Fixture f=new Fixture(true);f.start();
        check(f.hal.calls.get(0).tx==4&&Hal.service(f.hal.calls.get(0).value)==13,"Start 13 first");
        check(f.hal.calls.get(1).tx==4&&Hal.service(f.hal.calls.get(1).value)==44,"Start 44 before subscriptions");
        check(f.hal.count(4)==2&&f.hal.count(8)==9,"All required clients/subscriptions");
        check(f.sink.topics.equals(Arrays.asList(PA,RA)),"Current availability replay exact topics");
        check(f.sink.payloads.get(0).length==0&&f.sink.payloads.get(1).length==0,"Empty replay payload accepted");
        check(f.client.liveAnswersReady()&&f.hal.queries==1,"Ready only with fresh native HAL query");
        check(f.app.intent.type.startsWith("test.navigator.session-"),"Unique app-owned session");
        f.client.close();check(!f.client.liveAnswersReady(),"Close revokes readiness");
        check(f.hal.count(9)==9&&f.hal.count(5)==2&&f.hal.unregisters==1&&f.app.unbinds==1,"Own setup completely cleaned");
        int calls=f.hal.calls.size();f.client.close();check(f.hal.calls.size()==calls&&f.app.unbinds==1,"Close idempotent");
    }
    private static void existing()throws Exception {
        Fixture f=new Fixture(true);f.hal.references.put(13,2);f.hal.references.put(44,3);f.hal.subs.put(AdasWire.SELECTED,2);f.start();
        check(f.sink.states.stream().filter(s->s.startsWith("start_client:")&&s.endsWith(":13")).count()==2,"Already-started 13 accepted");
        check(f.client.liveAnswersReady(),"Already-available replay produces readiness");
        f.client.close();check(f.hal.refs(13)==2&&f.hal.refs(44)==3,"Foreign client references preserved");
        check(f.hal.subs.get(AdasWire.SELECTED)==2,"Foreign subscription references preserved");
        check(f.hal.count(5)==2,"Only two own stopClient operations");
    }
    private static void failures()throws Exception {
        Fixture failedStart=new Fixture(true);failedStart.hal.failStart=44;failedStart.start();
        check(!failedStart.client.liveAnswersReady(),"Failed start never ready");failedStart.client.close();
        check(failedStart.hal.count(5)==1,"Failed start not stopped");
        Fixture failedSub=new Fixture(true);failedSub.hal.failTopic=AdasWire.SELECTED;failedSub.start();
        check(!failedSub.client.liveAnswersReady(),"One failed subscription never ready despite availability");failedSub.client.close();
        check(failedSub.hal.count(9)==8,"Failed subscription not unsubscribed");
        Fixture unavailable=new Fixture(true);unavailable.hal.available.remove(44);unavailable.start();
        check(!unavailable.client.liveAnswersReady(),"Missing current availability blocks");unavailable.client.close();
    }
    private static void epochs()throws Exception {
        Fixture f=new Fixture(true);f.start();long first=f.client.epoch();
        f.hal.emit(RU,new byte[0]);check(f.client.epoch()>first&&!f.client.liveAnswersReady(),"Unavailable revokes immediately before queue drain");
        long lost=f.client.epoch();f.hal.emit(RA,null);check(f.client.liveAnswersReady()&&f.client.epoch()==lost,"Reavailable uses new readiness epoch, null payload valid");
        f.hal.emit(AdasWire.SELECTED,AdasWire.selected(902,200));int delivered=f.sink.topics.size();
        f.app.disconnect();check(!f.client.liveAnswersReady()&&!f.client.transportAlive(),"Binder loss revokes immediately");
        f.sink.drain();check(f.sink.topics.size()==delivered,"Queued old connection events discarded");
        check(f.sink.states.stream().anyMatch(s->s.startsWith("late_callback_discarded:")),"Late discard diagnosed");
        f.app.connect(f.hal);f.sink.drain();check(f.client.liveAnswersReady()&&f.client.epoch()>lost,"New connection requires complete setup/replay");
        f.hal.hal(false);check(!f.client.liveAnswersReady(),"Global HAL loss revokes");
        f.hal.hal(true);f.hal.emit(PA,new byte[0]);f.hal.emit(RA,new byte[0]);
        check(!f.client.liveAnswersReady(),"Availability alone cannot restore invalidated subscription proof");f.client.close();
    }
    private static void admission()throws Exception {
        Fixture f=new Fixture(true);f.start();f.sink.admit=false;f.hal.emit(AdasWire.MODE,AdasWire.mode(1));
        check(!f.client.liveAnswersReady(),"Full queue invalidates readiness");f.sink.admit=true;f.client.close();
        Fixture stopped=new Fixture(true);stopped.start();stopped.client.stopAdmission();int n=stopped.sink.queue.size();
        stopped.hal.emit(PA,new byte[0]);check(!stopped.client.liveAnswersReady()&&stopped.sink.queue.size()==n,"Stopped admission rejects callback");stopped.client.close();
        Fixture closing=new Fixture(true);closing.client.start();closing.app.connect(closing.hal);closing.client.close();closing.sink.drain();
        check(closing.hal.count(4)==0&&closing.hal.count(8)==0,"Queued setup cannot run after close");
    }
    private static void passive()throws Exception {
        Fixture f=new Fixture(false);f.start();check(f.hal.count(4)==4&&f.hal.count(8)==8,"Passive overlay service/topic set unchanged");
        check(!f.client.liveAnswersReady(),"Passive overlay never a live-answer readiness source");f.client.close();
    }
    public static void main(String[] args)throws Exception {
        fresh();existing();failures();epochs();admission();passive();
        System.out.println("AdasPassiveClient production Binder harness: "+checks+" checks PASS");
    }
}
