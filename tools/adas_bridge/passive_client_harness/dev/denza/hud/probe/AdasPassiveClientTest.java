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
        List<Integer> cities; int cityUpdates;
        final List<Long> enqueuedTopics=new ArrayList<>(); int lifecycle;
        public boolean enqueue(Runnable task) { lifecycle++; return add(task); }
        public boolean enqueue(long topic,Runnable task) { enqueuedTopics.add(topic); return add(task); }
        boolean add(Runnable task) { if(!admit)return false; if(queue.size()>=1000)throw new AssertionError("Queue cap"); queue.add(task); return true; }
        public void state(String event,String detail) { states.add(event+":"+detail); }
        public void event(long topic,long stamp,long at,byte[] payload) { topics.add(topic); payloads.add(payload); }
        public void oddRegionCodes(List<Integer> codes) { cities=new ArrayList<>(codes); cityUpdates++; }
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
        IBinder callback; int failStart=-1, queries, unregisters, oddRequests, oddResult; long failTopic=-1; boolean alive=true, availableHal=true, absentOddReply;
        byte[] oddPayload = new byte[]{10,2,8,1,10,3,8,(byte)244,2};
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
            if(tx==10){ // request(SomeIpData in, SomeIpData out): only the ODD region read is ever expected here
                check(data.readInt()==1,"ODD request object present");
                long topic=data.readLong(),stamp=data.readLong();
                check(topic==563005788848130L,"the only request is 13/0x0002, never 13/0x0001: "+Long.toHexString(topic));
                check(refs(13)>0,"the request goes on a started service-13 client");
                check(data.readInt()==0,"Empty protobuf request");data.readByteArray(new byte[0]);
                check(data.readInt()==1,"Firmware requires a nonnull response object");
                check(data.readLong()==0&&data.readLong()==0&&data.readInt()==-1,"Default response object");
                calls.add(new Call(tx,topic));
                oddRequests++;reply.writeNoException();reply.writeInt(oddResult);
                reply.writeInt(1);reply.writeLong(topic);reply.writeLong(stamp);reply.writeInt(0);reply.writeByteArray(new byte[0]);
                if(absentOddReply)reply.writeInt(0);
                else{reply.writeInt(1);reply.writeLong(0x0003000D000D0002L);reply.writeLong(123);reply.writeInt(oddPayload.length);reply.writeByteArray(oddPayload);}
                return true;
            }
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
    /** A bridge receiver sees each frame's topic, so it can keep service-44 answers through a backlog. */
    private static void topics()throws Exception {
        Fixture f=new Fixture(true);f.start();int lifecycle=f.sink.lifecycle;
        check(f.sink.enqueuedTopics.equals(Arrays.asList(PA,RA)),"replayed availability frames carry their topics");
        f.hal.emit(AdasWire.SELECTED,AdasWire.selected(901,200));f.hal.emit(AdasWire.MODE,AdasWire.mode(2));
        check(f.sink.enqueuedTopics.subList(2,4).equals(Arrays.asList(AdasWire.SELECTED,AdasWire.MODE)),"data frames carry their own topics");
        check(f.sink.lifecycle==lifecycle,"frames never use the topicless lifecycle path");
        f.app.disconnect();check(f.sink.lifecycle==lifecycle+1,"binder loss is lifecycle work");f.client.close();
    }
    private static void passive()throws Exception {
        Fixture f=new Fixture(false);f.start();check(f.hal.count(4)==4&&f.hal.count(8)==8,"Passive overlay service/topic set unchanged");
        check(!f.client.liveAnswersReady(),"Passive overlay never a live-answer readiness source");
        check(f.client.requestOddRegionCodes()<0&&f.hal.count(10)==0,"the overlay's client never makes the ODD request");f.client.close();
    }
    /** The bridge's one request: the stock ODD region read on its own started service-13 client, once per call. */
    private static void oddRequest()throws Exception {
        Fixture before=new Fixture(true);before.client.start();
        check(before.client.requestOddRegionCodes()<0&&before.hal.count(10)==0,"no binder yet: no request");before.client.close();
        Fixture f=new Fixture(true);f.start();int fourBefore=f.hal.count(4),eightBefore=f.hal.count(8);
        check(f.hal.count(10)==0,"setup makes no request");
        check(f.client.requestOddRegionCodes()==0&&f.hal.count(10)==1,"one call, one transaction 10");
        check(f.hal.calls.get(f.hal.calls.size()-1).value==AdasPassiveClient.ODD_REGION_REQUEST,"its topic is 13/0x0002");
        check(f.hal.count(4)==fourBefore&&f.hal.count(8)==eightBefore,"the request changes no client or subscription");
        check(f.sink.states.stream().anyMatch(s->s.startsWith("odd_region_response:")),"the request and its result are recorded");
        Fixture failed=new Fixture(true);failed.hal.failStart=13;failed.start();
        check(failed.client.requestOddRegionCodes()<0&&failed.hal.count(10)==0,"no started service-13 client: no request");failed.client.close();
        f.client.close();check(f.client.requestOddRegionCodes()<0&&f.hal.count(10)==1,"a closed client makes no request");
    }
    private static void oddRegionQuery()throws Exception {
        Fixture passive=new Fixture(false);passive.start();
        check(passive.client.requestOddRegionCodes()==-1&&passive.hal.oddRequests==0,"Passive overlay cannot issue requests");passive.client.close();
        Fixture f=new Fixture(true);
        check(f.client.requestOddRegionCodes()==-1&&f.hal.oddRequests==0,"No query before readiness");
        f.start();check(f.client.requestOddRegionCodes()==0&&f.hal.oddRequests==1,"Actual Binder codec receives ODD response");
        check(f.sink.states.stream().anyMatch(s->s.startsWith("odd_region_response:")&&s.contains("payloadBase64=CgIIAQoDCPQC")),"Raw response retained");
        check(f.sink.cities.equals(Arrays.asList(1,372)) && f.sink.cityUpdates==1,"Decoded actual response reaches typed SDK boundary");
        f.hal.oddResult=11;check(f.client.requestOddRegionCodes()==11,"Nonzero SDK result is preserved");
        f.hal.oddResult=0;f.hal.absentOddReply=true;check(f.client.requestOddRegionCodes()==-200,"Absent response is not a successful read");
        f.hal.absentOddReply=false;f.hal.oddPayload=new byte[]{10,5,8};
        check(f.client.requestOddRegionCodes()==-200&&f.sink.cityUpdates==1,"Malformed response preserves previous city cache");
        f.client.close();check(f.client.requestOddRegionCodes()==-1&&f.hal.oddRequests==4,"Closed client never queries");
    }
    private static void coreMaps()throws Exception {
        App app=new App();Sink sink=new Sink();Hal hal=new Hal();
        AdasPassiveClient core=new AdasPassiveClient(app,sink,false,true);core.start();app.connect(hal);sink.drain();
        check(hal.count(4)==0&&hal.count(8)==0,"core connection has no admission bootstrap subscriptions");
        Map<Long,Long[]> map=new LinkedHashMap<>();map.put(0x000B000D000D0000L,new Long[]{PA});
        core.nativeStart(map);sink.drain();check(hal.refs(13)==1&&sink.topics.contains(PA),"computed availability subscription reaches core callback");
        int starts=hal.count(4),subs=hal.count(8);core.nativeStart(map);check(hal.count(4)==starts&&hal.count(8)==subs,"same core map does not acquire duplicate references");
        core.nativeUnsubscribe(map);check(hal.subs.get(PA)==0,"core unsubscribe releases exact subscription");
        core.nativeStop(map);check(hal.refs(13)==0,"core stop releases its own service");core.close();sink.drain();
        check(app.unbinds==1,"core connection independently closed");
    }

    public static void main(String[] args)throws Exception {
        coreMaps();
        fresh();existing();failures();epochs();admission();topics();passive();oddRegionQuery();oddRequest();
        System.out.println("AdasPassiveClient production Binder harness: "+checks+" checks PASS");
    }
}
