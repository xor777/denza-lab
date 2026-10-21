package dev.denza.diagnostics.probe;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import dev.denza.apps.platform.shell.ShellProxyBootstrap;
import dev.denza.apps.feature.diagnostics.bus.DiagnosticFrameAssembler;
import java.io.RandomAccessFile;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed read-only research plans. Supported DTCs are capabilities, not active faults.
 * No arbitrary requests, sessions, security, retries, setters or product whitelist edits.
 * Frame/byte limits below are conservative policy caps, not physical channel maxima.
 */
public final class SupportedDtcReadProbe {
    static final int TX = 0xAA00010A, RX = 0x9900010A;
    static final int REQUEST_MS = 4_000, SILENCE_MS = 2_000, GAP_MS = 500;
    static final int MAX_CALLBACKS = 1024, MAX_QUEUED = 32;
    static final String LOCK_PATH = "/data/local/tmp/denza-dtc-read.lock";
    static final class Target {
        final String name, code;
        final int address;
        Target(String name, int address, String code) { this.name=name; this.address=address; this.code=code; }
    }
    static final class Plan {
        final String name;
        final int overallMs;
        final int listSubfunction;
        final boolean readRecord;
        final Target[] targets;
        Plan(String name,int overallMs,Target... targets) { this(name,overallMs,0x0A,false,targets); }
        Plan(String name,int overallMs,int listSubfunction,Target... targets) {
            this(name,overallMs,listSubfunction,false,targets);
        }
        Plan(String name,int overallMs,int listSubfunction,boolean readRecord,Target... targets) {
            if(listSubfunction!=0x0A&&listSubfunction!=0x03)throw new IllegalArgumentException("fixed subfunction");
            if(readRecord&&(listSubfunction!=3||targets.length!=1||targets[0]!=BMS))throw new IllegalArgumentException("fixed BMS record");
            this.name=name;this.overallMs=overallMs;this.listSubfunction=listSubfunction;this.readRecord=readRecord;this.targets=targets;
        }
        int requestsPerTarget() { return readRecord?4:3; }
        int maxRequests() { return targets.length*requestsPerTarget(); }
        boolean observesCan() { return "can-visibility".equals(name); }
        boolean collectsTrace() { return "isg-trace".equals(name)||observesCan(); }
    }
    static final Target ECM = new Target("ECM", 0x7E0, "32440011E004000000");
    static final Target BMS = new Target("BMS", 0x7E7, "4A520011E00D00047B");
    static final Target FMCU = new Target("FMCU", 0x7E3, "48370011E075000000");
    static final Target ISG = new Target("ISG", 0x7E6, "48370011E0EA000000");
    static final Target SMCU = new Target("SMCU", 0x744, "48370011E086001100");
    static final Target SMCU3 = new Target("SMCU3", 0x745, "48370011E088001100");
    static Plan plan(String name) {
        if ("ecm-bms".equals(name)) return new Plan(name,35_000,ECM,BMS);
        if ("drives".equals(name)) return new Plan(name,65_000,FMCU,ISG,SMCU,SMCU3);
        if ("rear-drives".equals(name)) return new Plan(name,35_000,SMCU,SMCU3);
        if ("isg-observe".equals(name)) return new Plan(name,15_000,ISG);
        if ("isg-trace".equals(name)) return new Plan(name,15_000,ISG);
        if ("can-visibility".equals(name)) return new Plan(name,35_000,ECM,ISG);
        if ("snapshot-ecm-bms".equals(name)) return new Plan(name,35_000,0x03,ECM,BMS);
        if ("snapshot-drives".equals(name)) return new Plan(name,65_000,0x03,FMCU,ISG,SMCU,SMCU3);
        if ("bms-snapshot-record".equals(name)) return new Plan(name,25_000,0x03,true,BMS);
        throw new IllegalArgumentException("Known fixed plan required");
    }
    static byte[] payload(Plan plan,int phase) {
        if (phase==0) return new byte[]{0x22,(byte)0xF1,(byte)0x94};
        if (phase==1) return new byte[]{0x19,1,9};
        if (phase==2) return new byte[]{0x19,(byte)plan.listSubfunction};
        if (phase==3&&plan.readRecord) return new byte[]{0x19,4,0x1A,0x3A,0x22,1};
        throw new IllegalArgumentException("phase");
    }
    static byte[] envelope(Plan plan,Target target,int phase) {
        byte[] p=payload(plan,phase),r=new byte[4+p.length];r[0]=10;r[1]=(byte)(target.address>>8);
        r[2]=(byte)target.address;r[3]=(byte)p.length;System.arraycopy(p,0,r,4,p.length);return r;
    }
    static boolean dispatchAllowed(AtomicBoolean closed,AtomicBoolean rejected) {
        return !closed.get()&&!rejected.get();
    }
    static String rejectedLine(int seq,String target,long at,String reason,byte[] bytes) {
        int count=bytes==null?-1:bytes.length;
        int logged=Math.max(0,Math.min(count,DiagnosticFrameAssembler.MAX_BUFFER));
        return "REJECTED_FRAME seq="+seq+" target="+target+" t_ns="+at+" reason="+reason
            +" raw_bytes="+count+" logged_bytes="+logged+" truncated="+(count>logged)
            +" raw="+(bytes==null?"NULL":hex(Arrays.copyOf(bytes,logged)));
    }
    static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02X",b&255));return s.toString();
    }
    interface Host {
        long nanos(); boolean safe() throws Exception; void later(Runnable task,int delayMs);
        void send(byte[] request) throws Exception; void out(String line);
        void finish(String reason,int requests,int completed);
    }
    static final class Engine {
        final Host host; final Plan plan;
        final DiagnosticFrameAssembler assembler=new DiagnosticFrameAssembler();
        final List<byte[]> currentFrames=new ArrayList<>();
        List<byte[]> previousFrames=new ArrayList<>();
        volatile boolean done,open;
        boolean quiet=true;
        int targetIndex,phase,seq,completed,targetsFinished,callbacks,exchangeCallbacks;
        volatile int requests; // Published before send; passive callbacks may arrive on another thread.
        int previousSeq,previousAddress,format=-1,availability=-1;
        volatile long sentAt;
        byte[] lastSource;
        long lastSourceAt;
        long deadline;
        final ByteArrayOutputStream tracePayload=new ByteArrayOutputStream();
        int traceTotal,traceNext=1;
        Engine(Plan plan,Host host) { this.plan=plan;this.host=host; }
        Target target() { return plan.targets[targetIndex]; }
        String id(int address) { return String.format("%03X",address); }
        void start() {
            deadline=host.nanos()+plan.overallMs*1_000_000L;
            host.out("START plan="+plan.name+" targets="+plan.targets.length+" max_requests="+plan.maxRequests()
                +" silence_ms=2000 request_ms=4000 overall_ms="+plan.overallMs);
            host.later(()->abort("OVERALL_TIMEOUT"),plan.overallMs);host.later(this::sendNext,SILENCE_MS);
        }
        void abort(String reason) {
            if(done)return;done=true;open=false;assembler.end();host.finish(reason,requests,completed);
        }
        String contextTarget() { int i=targetIndex;return i>=0&&i<plan.targets.length?id(plan.targets[i].address):"NONE"; }
        void reject(byte[] bytes,long at,String reason) {
            if(done)return;
            host.out(rejectedLine(seq,contextTarget(),at,reason,bytes));
            abort(reason);
        }
        void sendNext() {
            if(done)return;
            if(host.nanos()>=deadline) { abort("OVERALL_TIMEOUT");return; }
            if(targetIndex==plan.targets.length) { abort("PLAN_DONE");return; }
            quiet=false;
            try {
                if(!host.safe()) { abort("VEHICLE_GUARD");return; }
                if(done)return;
                if(host.nanos()>=deadline) { abort("OVERALL_TIMEOUT");return; }
                byte[] uds=payload(plan,phase),request=envelope(plan,target(),phase);
                boolean known=target()==ECM||target()==BMS||target()==FMCU||target()==ISG||target()==SMCU||target()==SMCU3;
                if(!known || requests>=plan.maxRequests())
                    throw new IllegalStateException("fixed plan");
                assembler.begin(target().address,uds);currentFrames.clear();exchangeCallbacks=0;
                tracePayload.reset();traceTotal=0;traceNext=1;
                sentAt=host.nanos();open=true;requests++;seq++;
                final int currentSeq=seq;
                host.later(()->{if(!done&&open&&seq==currentSeq)end("REQUEST_TIMEOUT",true);},REQUEST_MS);
                host.out("REQUEST seq="+seq+" target="+id(target().address)+" ecu="+target().name
                    +" request="+hex(uds)+" t_ns="+sentAt+" raw="+hex(request));
                if(host.nanos()>=sentAt+REQUEST_MS*1_000_000L) { abort("DISPATCH_DEADLINE");return; }
                host.send(request);
            } catch(Exception error) { abort("SEND_OR_GUARD_FAILED"); }
        }
        void end(String state,boolean stopTarget) {
            if(!open||done)return;
            open=false;assembler.end();completed++;
            previousFrames=new ArrayList<>(currentFrames);previousSeq=seq;previousAddress=target().address;
            host.out("STEP_END seq="+seq+" state="+state);
            if(stopTarget||phase==plan.requestsPerTarget()-1) {
                host.out("TARGET_END target="+id(target().address)+" state="+state);
                targetsFinished++;targetIndex++;phase=0;format=-1;availability=-1;
            } else phase++;
            host.later(this::sendNext,GAP_MS);
        }
        boolean contains(List<byte[]> frames,byte[] b) { for(byte[] known:frames)if(Arrays.equals(known,b))return true;return false; }
        void duplicate(int origin,int address,byte[] frame,long at) {
            host.out("DUPLICATE seq="+origin+" target="+id(address)+" t_ns="+at+" raw="+hex(frame));
        }
        void trace(byte[] source,byte[] frame,long at,int count,int index,int length,int offset) {
            if((traceTotal==0&&!currentFrames.isEmpty()) || index!=traceNext || (traceTotal!=0&&count!=traceTotal)
                    || length>(index==1?233:230) || (index<count&&length!=(index==1?233:230))
                    || (index==1&&(length<3||(frame[6]&255)!=3||(frame[7]&255)!=0x19||(frame[8]&255)!=0x0A))
                    || (index>1&&length==0) || tracePayload.size()+length>4095) {
                reject(source,at,"TRACE_SHAPE_OR_SEQUENCE");return;
            }
            traceTotal=count;traceNext++;
            currentFrames.add(frame);tracePayload.write(frame,offset,length);
            host.out("GATEWAY_TRACE_FRAME seq="+seq+" target="+id(target().address)+" t_ns="+at+" raw="+hex(frame));
            if(index==count) {
                byte[] joined=tracePayload.toByteArray();
                host.out("GATEWAY_TRACE seq="+seq+" target="+id(target().address)+" result=03 frames="+count
                    +" payload_bytes="+joined.length+" trace_bytes="+(joined.length-3)
                    +" request_prefix="+hex(Arrays.copyOf(joined,3))+" raw="+hex(joined));
                // This is a complete gateway failure report, never an accepted UDS registry.
                end("GATEWAY_TRACE_CAPTURED",true);
            }
        }
        void received(byte[] bytes,long at) {
            if(done)return;
            lastSource=bytes;lastSourceAt=at;
            if(++callbacks>MAX_CALLBACKS) { reject(bytes,at,"CALLBACK_BOUND");return; }
            if(quiet) { reject(bytes,at,"PREEXISTING_DIAGNOSTIC_TRAFFIC");return; }
            if(bytes==null||bytes.length<6||bytes.length>DiagnosticFrameAssembler.MAX_BUFFER) { reject(bytes,at,"FRAME_SHAPE");return; }
            int count=bytes[4]&255,index=bytes[5]&255,length=bytes[3]&255,offset=index==1?7:6;
            if(bytes[0]!=10||count<1||count>DiagnosticFrameAssembler.MAX_FRAMES||index<1||index>count
                    ||bytes.length<offset+length) { reject(bytes,at,"FRAME_SHAPE");return; }
            byte[] frame=Arrays.copyOf(bytes,offset+length);
            if(open&&++exchangeCallbacks>DiagnosticFrameAssembler.MAX_BUFFERS_PER_EXCHANGE) { reject(bytes,at,"EXCHANGE_CALLBACK_BOUND");return; }
            if(open&&contains(currentFrames,frame)) { duplicate(seq,target().address,frame,at);return; }
            if(contains(previousFrames,frame)) { duplicate(previousSeq,previousAddress,frame,at);return; }
            if(!open) { reject(bytes,at,"UNEXPECTED_DIAGNOSTIC_TRAFFIC");return; }
            int reply=((frame[1]&255)<<8)|(frame[2]&255);
            if(reply!=target().address+8||at<sentAt) { reject(bytes,at,"UNEXPECTED_DIAGNOSTIC_TRAFFIC");return; }
            if(at>=sentAt+REQUEST_MS*1_000_000L) { reject(bytes,at,"LATE_RESPONSE");return; }
            if(plan.collectsTrace()&&target()==ISG&&phase==2&&format==1
                    &&(traceTotal!=0||(index==1&&(frame[6]&255)==3&&length>0))) {
                trace(bytes,frame,at,count,index,length,offset);return;
            }
            if(index==1) {
                int result=frame[6]&255;
                if((result==1||result==2)&&length==0) { reject(bytes,at,"FRAME_SHAPE");return; }
                if(result==2&&(frame[7]&255)!=0x7F) { reject(bytes,at,"NEGATIVE_SHAPE");return; }
                if(length>0&&(frame[7]&255)==0x7F&&(count!=1||length!=3
                        ||(frame[8]&255)!=(payload(plan,phase)[0]&255))) { reject(bytes,at,"NEGATIVE_SHAPE");return; }
                if(result>=3&&(count!=1||length!=0)) { reject(bytes,at,"GATEWAY_SHAPE");return; }
                // Do not inherit the shared assembler's permissive same-address service echo rule.
                if((result==1||result==2)&&(frame[7]&255)!=((payload(plan,phase)[0]&255)+0x40)
                        &&(frame[7]&255)!=0x7F) { reject(bytes,at,"RESPONSE_MISMATCH");return; }
            } else if(length==0) { reject(bytes,at,"FRAME_SHAPE");return; }
            currentFrames.add(frame);
            host.out("FRAME seq="+seq+" target="+id(target().address)+" t_ns="+at+" raw="+hex(frame));
            DiagnosticFrameAssembler.Outcome outcome=assembler.accept(frame);
            switch(outcome.kind) {
                case SHAPE: case FOREIGN: reject(bytes,at,"ASSEMBLY_"+outcome.kind);return;
                case BUSY: reject(bytes,at,"GATEWAY_BUSY");return;
                case NO_REPLY: end(outcome.gatewayResult==3?"NO_RESPONSE":"GATEWAY_TIMEOUT",true);return;
                case WAITING:
                    if(index==1&&count==1&&length==3&&(frame[7]&255)==0x7F&&(frame[9]&255)==0x78)
                        host.out("PENDING seq="+seq+" target="+id(target().address)+" uds="+hex(Arrays.copyOfRange(frame,7,10)));
                    return;
                case ANSWER:
                    host.out("RESPONSE seq="+seq+" target="+id(target().address)+" request="+hex(payload(plan,phase))
                        +" frames="+outcome.frames+" latency_ms="+((at-sentAt)/1_000_000L)+" t_ns="+at+" uds="+hex(outcome.uds));
                    answer(outcome.uds);return;
                default: abort("ASSEMBLY_UNKNOWN");
            }
        }
        void answer(byte[] uds) {
            if(uds.length==3&&(uds[0]&255)==0x7F&&(uds[1]&255)==(payload(plan,phase)[0]&255)) {
                end("UDS_NEGATIVE_"+String.format("%02X",uds[2]&255),true);return;
            }
            if(phase==0) {
                if(uds.length!=12||(uds[0]&255)!=0x62||(uds[1]&255)!=0xF1||(uds[2]&255)!=0x94) { reject(lastSource,lastSourceAt,"IDENTITY_SHAPE");return; }
                if(!hex(Arrays.copyOfRange(uds,3,12)).equals(target().code)) { reject(lastSource,lastSourceAt,"IDENTITY_MISMATCH");return; }
                end("IDENTITY_MATCH",false);return;
            }
            if(phase==1) {
                if(uds.length!=6||(uds[0]&255)!=0x59||(uds[1]&255)!=1) { reject(lastSource,lastSourceAt,"FORMAT_RESPONSE_SHAPE");return; }
                availability=uds[2]&255;format=uds[3]&255;
                host.out("FORMAT seq="+seq+" target="+id(target().address)+" format="+String.format("%02X",format)
                    +" status_available="+String.format("%02X",availability)+" mask09_count="+(((uds[4]&255)<<8)|(uds[5]&255)));
                if((plan.readRecord&&format!=0)||(plan.collectsTrace()&&target()==ISG&&format!=1)
                        ||(plan.observesCan()&&target()==ECM&&format!=0)) { end("FORMAT_MISMATCH",true);return; }
                end("FORMAT_READ",false);return;
            }
            if(phase==3&&plan.readRecord) {
                if(format!=0||(uds.length!=6&&uds.length<8)||(uds[0]&255)!=0x59||(uds[1]&255)!=4
                        ||(uds[2]&255)!=0x1A||(uds[3]&255)!=0x3A||(uds[4]&255)!=0x22
                        ||(uds.length>=8&&(uds[6]&255)!=1)) {
                    reject(lastSource,lastSourceAt,"SNAPSHOT_RECORD_RESPONSE_SHAPE");return;
                }
                if(((uds[5]&255)&~availability)!=0) { reject(lastSource,lastSourceAt,"STATUS_OUTSIDE_AVAILABILITY");return; }
                if(uds.length==6) {
                    host.out("SNAPSHOT_RECORD_EMPTY seq="+seq+" target="+id(target().address)+" format=00 dtc=1A3A22 status="+String.format("%02X",uds[5]&255));
                    end("SNAPSHOT_RECORD_EMPTY",true);return;
                }
                host.out("SNAPSHOT_RECORD seq="+seq+" target="+id(target().address)+" format=00 dtc=1A3A22 status="+String.format("%02X",uds[5]&255)
                    +" record=01 did_count="+(uds[7]&255)+" payload_bytes="+(uds.length-8));
                end("SNAPSHOT_RECORD_READ",true);return;
            }
            if(plan.listSubfunction==0x03) {
                if(format<0||uds.length<2||(uds[0]&255)!=0x59||(uds[1]&255)!=3||(uds.length-2)%4!=0) {
                    reject(lastSource,lastSourceAt,"SNAPSHOT_INDEX_RESPONSE_SHAPE");return;
                }
                Set<Long> pairs=new HashSet<>();
                for(int i=2;i<uds.length;i+=4) {
                    long code=((uds[i]&255L)<<16)|((uds[i+1]&255L)<<8)|(uds[i+2]&255L);
                    long pair=(code<<8)|(uds[i+3]&255L);
                    if(!pairs.add(pair)) { reject(lastSource,lastSourceAt,"DUPLICATE_SNAPSHOT_PAIR");return; }
                }
                host.out("SNAPSHOT_INDEX seq="+seq+" target="+id(target().address)+" format="+String.format("%02X",format)
                    +" record_count="+pairs.size()+" active_fault_count=not_measured");
                if(plan.readRecord) {
                    if(!pairs.contains(0x1A3A2201L)) { end("SNAPSHOT_PAIR_UNAVAILABLE",true);return; }
                    end("SNAPSHOT_INDEX_READ",false);return;
                }
                end("SNAPSHOT_INDEX_READ",true);return;
            }
            if(format<0||uds.length<3||(uds[0]&255)!=0x59||(uds[1]&255)!=0x0A||(uds.length-3)%4!=0) { reject(lastSource,lastSourceAt,"SUPPORTED_RESPONSE_SHAPE");return; }
            if((uds[2]&255)!=availability) { reject(lastSource,lastSourceAt,"STATUS_AVAILABILITY_CHANGED");return; }
            Set<Integer> codes=new HashSet<>();
            for(int i=3;i<uds.length;i+=4) {
                int code=((uds[i]&255)<<16)|((uds[i+1]&255)<<8)|(uds[i+2]&255);
                // A repeated supported code is retained in RESPONSE, in wire order.
                // Count unique codes separately; do not turn this into a transport failure.
                codes.add(code);
                if(((uds[i+3]&255)&~availability)!=0) { reject(lastSource,lastSourceAt,"STATUS_OUTSIDE_AVAILABILITY");return; }
            }
            host.out("SUPPORTED seq="+seq+" target="+id(target().address)+" format="+String.format("%02X",format)
                +" status_available="+String.format("%02X",availability)+" supported_count="+((uds.length-3)/4)+" active_fault_count=not_measured"
                +" unique_code_count="+codes.size()+" duplicate_record_count="+((uds.length-3)/4-codes.size()));
            end("SUPPORTED_READ",true);
        }
    }
    private Plan selectedPlan;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger callbackCount = new AtomicInteger();
    private final AtomicBoolean callbackRejected = new AtomicBoolean();
    private final AtomicReference<String> rejectedCallbackFrame = new AtomicReference<>();
    private final AtomicBoolean callerClosed = new AtomicBoolean();
    private volatile Handler main;
    private volatile Engine engine;
    private RandomAccessFile lockFile;
    private FileLock lock;
    private IBinder auto;
    private Object device, listener;
    private Class<?> deviceClass, listenerClass, valueClass;
    private boolean registered;
    private DiagnosticCanObserver canObserver;
    static String prop(String key) throws Exception {
        return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
    }
    int word(int tx, int dev, int fid) throws Exception {
        Parcel a = Parcel.obtain(), b = Parcel.obtain();
        try {
            a.writeInterfaceToken(auto.getInterfaceDescriptor()); a.writeInt(dev); a.writeInt(fid);
            if (!auto.transact(tx, a, b, 0)) throw new IllegalStateException("guard refused");
            b.readException();
            if (b.dataAvail() != 4) throw new IllegalStateException("guard shape");
            return b.readInt();
        } finally { a.recycle(); b.recycle(); }
    }
    boolean safe() throws Exception {
        return "170".equals(prop("persist.sys.AutoType"))
            && "CANFD".equals(prop("sys.car.protocol"))
            && "r@-1_0&w@-1_0".equals(prop("sys.diagmgrsrv.task_bk"))
            && word(5, 1011, 89129008) == 1
            && Float.intBitsToFloat(word(7, 1013, -1807745016)) == 0f;
    }
    static void out(String s) { System.out.println(s); System.out.flush(); }
    void finish(String reason, int requests, int completed) {
        if (!closing.compareAndSet(false, true)) return;
        String rejected = rejectedCallbackFrame.getAndSet(null);
        if (rejected != null) out(rejected);
        boolean cleaned = true;
        if(canObserver!=null)cleaned=canObserver.close();
        try {
            if (registered) {
                deviceClass.getMethod("unregisterListener", listenerClass).invoke(device, listener);
                registered = false; out("UNREGISTERED");
            }
        } catch (Throwable e) { cleaned = false; out("UNREGISTER_FAILED"); }
        try {
            if (lock != null) lock.release();
            if (lockFile != null) lockFile.close();
        } catch (Exception e) { cleaned = false; }
        if (queued.get() != 0) cleaned = false;
        out("END reason=" + reason + " requests=" + requests + " completed=" + completed
            + " targets=" + (engine == null ? 0 : engine.targetsFinished) + " cleanup=" + cleaned + " callbacks=" + callbackCount.get() + " queued=" + queued.get());
        System.exit(cleaned && "PLAN_DONE".equals(reason) && requests == completed && engine != null && engine.targetsFinished == selectedPlan.targets.length ? 0 : 2);
    }
    void deliver(byte[] b) {
        long at = SystemClock.elapsedRealtimeNanos();
        if (closing.get() || callbackRejected.get()) return;
        if (callbackCount.incrementAndGet() > MAX_CALLBACKS || b == null || b.length > DiagnosticFrameAssembler.MAX_BUFFER) {
            rejectCallbackFrame(b, at, "CALLBACK_BOUND_OR_SHAPE");
            return;
        }
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet(); // This rejected buffer was never queued.
            rejectCallbackFrame(b, at, "CALLBACK_QUEUE_BOUND");
            return;
        }
        byte[] copy = b.clone();
        main.post(() -> { queued.decrementAndGet(); engine.received(copy, at); });
    }
    void rejectCallbackFrame(byte[] bytes,long at,String reason) {
        Engine current=engine;
        String line=rejectedLine(current==null?0:current.seq,current==null?"NONE":current.contextTarget(),at,reason,bytes);
        if (rejectedCallbackFrame.compareAndSet(null,line)) {
            callbackRejected.set(true);
            main.post(() -> engine.abort(reason));
        }
    }
    void callbackFailure(String why) {
        if (callbackRejected.compareAndSet(false, true)) main.post(() -> engine.abort(why));
    }
    void run(Plan plan) {
        selectedPlan = plan;
        // Independent physical deadline also handles a stuck main looper or Binder call.
        Thread watchdog = new Thread(() -> {
            final long started = SystemClock.elapsedRealtimeNanos();
            while (true) {
                long now = SystemClock.elapsedRealtimeNanos();
                Engine e = engine;
                if (e != null && e.open && now - e.sentAt >= (REQUEST_MS + 2_000L) * 1_000_000L) {
                    Runtime.getRuntime().halt(3); // No IO: a blocked stdout cannot delay the bound.
                }
                if (now - started >= (plan.overallMs + 2_000L) * 1_000_000L) {
                    Runtime.getRuntime().halt(3);
                }
                if (now - started >= plan.overallMs * 1_000_000L && main != null && e != null)
                    main.post(() -> e.abort("OVERALL_TIMEOUT"));
                try { Thread.sleep(50); } catch (InterruptedException ignored) { return; }
            }
        }, "supported-dtc-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        try {
            if (Process.myUid() != 2000) { finish("WRONG_UID", 0, 0); return; }
            lockFile = new RandomAccessFile(LOCK_PATH, "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) { finish("LOCK_BUSY", 0, 0); return; }
            ShellProxyBootstrap.exemptHiddenApis();
            auto = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "autoservice");
            if (auto == null || !safe()) { finish("INITIAL_GUARD", 0, 0); return; }
            ShellProxyBootstrap.prepareMainLooper(); main = new Handler(Looper.getMainLooper());
            engine = new Engine(plan, new Host() {
                public long nanos() { return SystemClock.elapsedRealtimeNanos(); }
                public boolean safe() throws Exception {
                    return dispatchAllowed(callerClosed,callbackRejected) && SupportedDtcReadProbe.this.safe()
                        && dispatchAllowed(callerClosed,callbackRejected);
                }
                public void later(Runnable r, int ms) { main.postDelayed(r, ms); }
                public void send(byte[] request) throws Exception {
                    if (!dispatchAllowed(callerClosed,callbackRejected)) throw new IllegalStateException("caller closed or callback rejected");
                    Object v = valueClass.getConstructor().newInstance();
                    valueClass.getField("bufferDataValue").set(v, request);
                    if (!dispatchAllowed(callerClosed,callbackRejected)) throw new IllegalStateException("caller closed or callback rejected");
                    Object rc = deviceClass.getMethod("set", int[].class, valueClass).invoke(device, new int[]{TX}, v);
                    out("TRANSPORT_RESULT seq=" + engine.seq + " result=" + rc);
                    if (!Integer.valueOf(0).equals(rc)) throw new IllegalStateException("transport refused");
                }
                public void out(String line) { SupportedDtcReadProbe.out(line); }
                public void finish(String why, int req, int complete) { SupportedDtcReadProbe.this.finish(why, req, complete); }
            });
            Context context = ShellProxyBootstrap.systemContext();
            deviceClass = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
            listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Class<?> eventClass = Class.forName("android.hardware.IBYDAutoEvent");
            device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            listener = Proxy.newProxyInstance(SupportedDtcReadProbe.class.getClassLoader(), new Class<?>[]{listenerClass}, (p, m, a) -> {
                try {
                    if (m.getName().equals("onDataEventChanged") && a != null && a.length > 1 && Integer.valueOf(RX).equals(a[0]))
                        deliver((byte[]) valueClass.getField("bufferDataValue").get(a[1]));
                    else if (m.getName().equals("onDataChanged") && a != null && a.length > 0 && a[0] != null
                            && Integer.valueOf(RX).equals(eventClass.getMethod("getEventType").invoke(a[0])))
                        deliver((byte[]) eventClass.getMethod("getBufferData").invoke(a[0]));
                    else if (m.getName().equals("onError")) callbackFailure("CALLBACK_ERROR");
                    else if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                    else if (m.getName().equals("equals")) return a != null && a.length == 1 && p == a[0];
                    else if (m.getName().equals("toString")) return "SupportedDtcReadProbe";
                } catch (Throwable error) { callbackFailure("CALLBACK_EXCEPTION"); }
                return null;
            });
            registered = true; // Cleanup is attempted even if registration partially fails.
            deviceClass.getMethod("registerListener", listenerClass, int[].class).invoke(device, listener, new int[]{RX});
            out("REGISTERED");
            if(plan.observesCan()) {
                canObserver=new DiagnosticCanObserver();
                canObserver.start(context,()->callbackFailure("CAN_OBSERVER_FAILED"),()->engine.requests==0);
            }
            Thread input = new Thread(() -> {
                try { System.in.read(); } catch (Exception ignored) { }
                callerClosed.set(true);
                main.post(() -> engine.abort("CALLER_CLOSED_OR_INPUT"));
            }, "supported-dtc-caller");
            input.setDaemon(true); input.start();
            engine.start(); Looper.loop();
        } catch (Throwable error) { finish("START_OR_RUNTIME_FAILED", engine == null ? 0 : engine.requests, engine == null ? 0 : engine.completed); }
    }
    public static void main(String[] args) {
        if(args.length!=1)throw new IllegalArgumentException("One fixed plan name required");
        new SupportedDtcReadProbe().run(plan(args[0]));
    }
}
