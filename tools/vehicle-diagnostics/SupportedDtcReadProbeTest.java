package dev.denza.diagnostics.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-only synthetic transport, protocol and lifecycle tests. No vehicle evidence. */
public final class SupportedDtcReadProbeTest {
    static int assertions;
    static void check(boolean value,String name) { assertions++;if(!value)throw new AssertionError(name); }
    static final class Event implements Comparable<Event> {
        final long time;final int order;final Runnable task;
        Event(long time,int order,Runnable task) {this.time=time;this.order=order;this.task=task;}
        public int compareTo(Event e) {int t=Long.compare(time,e.time);return t==0?Integer.compare(order,e.order):t;}
    }
    static final class Fake implements SupportedDtcReadProbe.Host {
        long now;int order,guards;boolean safe=true,throwSend,rejectDuringGuard;String finish;
        final AtomicBoolean callerClosed=new AtomicBoolean(),callbackRejected=new AtomicBoolean();
        final List<byte[]> sent=new ArrayList<>();final List<String> lines=new ArrayList<>();
        final PriorityQueue<Event> events=new PriorityQueue<>();
        final SupportedDtcReadProbe.Engine e;
        Fake(String plan) {e=new SupportedDtcReadProbe.Engine(SupportedDtcReadProbe.plan(plan),this);}
        public long nanos() {return now;}
        public boolean safe() {
            guards++;boolean result=safe&&SupportedDtcReadProbe.dispatchAllowed(callerClosed,callbackRejected);
            if(rejectDuringGuard)callbackRejected.set(true); // Fatal callback queued, abort not delivered yet.
            return result;
        }
        public void later(Runnable r,int ms) {events.add(new Event(now+ms*1_000_000L,order++,r));}
        public void send(byte[] r) {
            if(throwSend||!SupportedDtcReadProbe.dispatchAllowed(callerClosed,callbackRejected))throw new IllegalStateException();
            sent.add(r.clone());
        }
        public void out(String line) {lines.add(line);}
        public void finish(String why,int requests,int completed) {finish=why;lines.add("FINISH reason="+why);}
        void advance(int ms) {long end=now+ms*1_000_000L;while(!events.isEmpty()&&events.peek().time<=end) {Event v=events.remove();now=v.time;v.task.run();}now=end;}
        void ready() {e.start();advance(2000);}
        void receive(byte[] b) {e.received(b,now);}
        boolean line(String part) {for(String s:lines)if(s.contains(part))return true;return false;}
    }
    static byte[] unhex(String s) {byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    static byte[] frame(int address,int count,int index,int result,byte[] payload) {
        int start=index==1?7:6;byte[] b=new byte[start+payload.length];b[0]=10;b[1]=(byte)((address+8)>>8);b[2]=(byte)(address+8);
        b[3]=(byte)payload.length;b[4]=(byte)count;b[5]=(byte)index;if(index==1)b[6]=(byte)result;System.arraycopy(payload,0,b,start,payload.length);return b;
    }
    static byte[] single(Fake f,byte[] uds) {return frame(f.e.target().address,1,1,1,uds);}
    static byte[] identity(Fake f) {return unhex("62F194"+f.e.target().code);}
    static byte[] count(int format,int available,int n) {return new byte[]{0x59,1,(byte)available,(byte)format,(byte)(n>>8),(byte)n};}
    static byte[] supported(int available,int n) {
        byte[] b=new byte[3+n*4];b[0]=0x59;b[1]=10;b[2]=(byte)available;
        for(int i=0;i<n;i++){int code=i+1;b[3+i*4]=(byte)(code>>16);b[4+i*4]=(byte)(code>>8);b[5+i*4]=(byte)code;b[6+i*4]=0;}return b;
    }
    static byte[] snapshots(int n) {
        byte[] b=new byte[2+n*4];b[0]=0x59;b[1]=3;
        for(int i=0;i<n;i++){int code=i+1;b[2+i*4]=(byte)(code>>16);b[3+i*4]=(byte)(code>>8);b[4+i*4]=(byte)code;b[5+i*4]=(byte)255;}return b;
    }
    static Fake ready() {Fake f=new Fake("ecm-bms");f.ready();return f;}
    static void answer(Fake f,byte[] uds) {f.receive(single(f,uds));}
    static void atList(Fake f,int format,int available) {answer(f,identity(f));f.advance(500);answer(f,count(format,available,42));f.advance(500);}
    static List<byte[]> fragmented(Fake f,byte[] uds,int size) {
        List<byte[]> frames=new ArrayList<>();int n=(uds.length+size-1)/size;
        for(int i=0;i<n;i++)frames.add(frame(f.e.target().address,n,i+1,1,Arrays.copyOfRange(uds,i*size,Math.min(uds.length,(i+1)*size))));return frames;
    }
    static void goodPlans() {
        for(String name:new String[]{"ecm-bms","drives","rear-drives","isg-observe"}) {
            Fake f=new Fake(name);f.ready();int n=f.e.plan.targets.length;
            for(int t=0;t<n;t++) {
                check(Arrays.equals(f.sent.get(f.sent.size()-1),SupportedDtcReadProbe.envelope(f.e.plan,f.e.target(),0)),"fixed identity envelope");
                byte[] id=single(f,identity(f));f.receive(id);f.receive(id);f.receive(Arrays.copyOf(id,id.length+23));f.advance(500);
                check(f.e.phase==1,"identity gates count");answer(f,count(t%2,255,42));f.advance(500);
                byte[] list=single(f,supported(255,2));f.receive(list);f.receive(list);f.advance(500);
            }
            check("PLAN_DONE".equals(f.finish),"fixed plan done "+name);check(f.sent.size()==n*3&&f.guards==n*3,"guard every send");
            check(f.e.targetsFinished==n&&f.e.completed==n*3,"completion accounting");
            check(f.line("supported_count=2 active_fault_count=not_measured"),"supported not active");
            f.advance(100000);check(f.sent.size()==n*3,"no retry after completion");
        }
        try {SupportedDtcReadProbe.plan("7DF");throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"no arbitrary plan");}
        check(SupportedDtcReadProbe.plan("ecm-bms").targets[0].address==0x7E0&&SupportedDtcReadProbe.plan("ecm-bms").targets[1].address==0x7E7,"two initial targets");
        check(SupportedDtcReadProbe.plan("drives").targets.length==4,"four drives");
        SupportedDtcReadProbe.Plan rear=SupportedDtcReadProbe.plan("rear-drives");
        check(rear.targets.length==2&&rear.targets[0]==SupportedDtcReadProbe.SMCU&&rear.targets[1]==SupportedDtcReadProbe.SMCU3,"rear-only no FMCU or ISG repeat");
        check(rear.overallMs==35000,"rear finite deadline");
        SupportedDtcReadProbe.Plan isg=SupportedDtcReadProbe.plan("isg-observe");
        check(isg.targets.length==1&&isg.targets[0]==SupportedDtcReadProbe.ISG&&isg.listSubfunction==10,"one explicitly fixed ISG observation");
        check(isg.overallMs==15000,"ISG finite deadline");
    }
    static void negativeAndPending() {
        for(int phase=0;phase<3;phase++) {
            Fake f=ready();if(phase>0){answer(f,identity(f));f.advance(500);}if(phase>1){answer(f,count(1,255,0));f.advance(500);}
            int before=f.sent.size(),sid=SupportedDtcReadProbe.payload(f.e.plan,phase)[0]&255;
            f.receive(frame(f.e.target().address,1,1,2,new byte[]{0x7F,(byte)sid,0x22}));
            check(f.line("state=UDS_NEGATIVE_22"),"NRC22 generic negative "+phase);f.advance(500);
            check(f.e.target().address==0x7E7&&f.e.phase==0&&f.sent.size()==before+1,"negative skips target no retry "+phase);
        }
        Fake f=ready();answer(f,new byte[]{0x7F,0x22,0x78});check(f.e.open,"pending waits");
        f.advance(3999);check(f.sent.size()==1&&f.e.open,"pending unchanged deadline");f.advance(1);
        check(f.line("state=REQUEST_TIMEOUT")&&!f.e.open,"pending original timeout");f.advance(500);
        check(f.e.target().address==0x7E7&&f.e.phase==0,"pending timeout skips target");
        f=ready();answer(f,new byte[]{0x7F,0x22,0x78});f.advance(100);answer(f,identity(f));f.advance(500);
        check(f.e.phase==1&&f.sent.size()==2,"pending then final identity");
        f=ready();f.receive(frame(f.e.target().address,1,1,3,new byte[0]));f.advance(500);
        check(f.e.target().address==0x7E7&&f.e.phase==0,"no response skips target");
        f=ready();f.receive(frame(f.e.target().address,1,1,5,new byte[0]));check("GATEWAY_BUSY".equals(f.finish),"busy stops all");
    }
    static void malformedAndGuards() {
        Fake f=new Fake("ecm-bms");f.e.start();f.receive(frame(0x7E0,1,1,1,unhex("62F19432440011E004000000")));
        check("PREEXISTING_DIAGNOSTIC_TRAFFIC".equals(f.finish)&&f.sent.isEmpty(),"two second quiet gate");
        f=new Fake("ecm-bms");f.safe=false;f.ready();check("VEHICLE_GUARD".equals(f.finish)&&f.sent.isEmpty(),"initial unsafe");
        f=ready();answer(f,identity(f));f.safe=false;f.advance(500);check(f.sent.size()==1&&"VEHICLE_GUARD".equals(f.finish),"guard before count");
        f=ready();byte[] mismatch=identity(f);mismatch[3]^=1;answer(f,mismatch);f.advance(10000);
        check("IDENTITY_MISMATCH".equals(f.finish)&&f.sent.size()==1,"wrong identity cannot progress");
        for(int k=0;k<10;k++) {
            f=ready();byte[] b=single(f,identity(f));
            if(k==0)b[0]=9;if(k==1)b[2]++;if(k==2)b[4]=17;if(k==3)b[5]=0;if(k==4)b=Arrays.copyOf(b,b.length-1);
            if(k==5)b[6]=0;if(k==6)b[7]=0x59;if(k==7)b[6]=2;if(k==8)b=new byte[1025];if(k==9)b=null;
            f.receive(b);check(f.finish!=null&&f.sent.size()==1,"malformed stops "+k);
        }
        f=ready();f.e.received(single(f,identity(f)),f.now-1);check("UNEXPECTED_DIAGNOSTIC_TRAFFIC".equals(f.finish),"stale queue");
        f=ready();f.e.received(single(f,identity(f)),f.now+4_000_000_000L);check("LATE_RESPONSE".equals(f.finish),"strict request deadline");
        f=ready();answer(f,identity(f));byte[] altered=single(f,identity(f));altered[10]^=1;f.receive(altered);
        check("UNEXPECTED_DIAGNOSTIC_TRAFFIC".equals(f.finish),"new same id during gap stops");
        f=ready();answer(f,identity(f));f.advance(500);answer(f,unhex("62F19432440011E004000001"));
        check("RESPONSE_MISMATCH".equals(f.finish),"new old service not swallowed");
        f=ready();f.e.abort("CALLER_CLOSED_OR_INPUT");f.advance(100000);check(f.sent.size()==1,"caller cancellation");
        f=new Fake("ecm-bms");f.throwSend=true;f.ready();check("SEND_OR_GUARD_FAILED".equals(f.finish),"transport failure no retry");
        f=new Fake("ecm-bms");f.rejectDuringGuard=true;f.ready();
        check(f.sent.isEmpty()&&"SEND_OR_GUARD_FAILED".equals(f.finish),"known fatal callback cannot dispatch before queued abort");
        f=new Fake("ecm-bms");f.callerClosed.set(true);f.ready();check(f.sent.isEmpty(),"closed caller cannot dispatch");
        f=ready();for(int i=0;i<65&&!f.e.done;i++)answer(f,new byte[]{0x7F,0x22,0x78});
        check("EXCHANGE_CALLBACK_BOUND".equals(f.finish),"duplicate pending flood bounded");
    }
    static void longAndStrictLists() {
        Fake f=ready();atList(f,1,255);List<byte[]> frames=fragmented(f,supported(255,1019),255);
        check(frames.size()==16,"sixteen fragment policy boundary");
        for(byte[] b:frames){f.receive(b);f.receive(Arrays.copyOf(b,b.length+3));}
        check(f.line("supported_count=1019 active_fault_count=not_measured"),"long raw supported list");
        check(f.line("format=01"),"prior format retained without interpreting 190A format");
        f=ready();atList(f,0,9);answer(f,supported(9,0));check(f.line("supported_count=0"),"empty supported list valid");
        f=ready();atList(f,1,9);byte[] statusList=supported(9,1);statusList[6]=9;answer(f,statusList);
        check(f.line("format=01 status_available=09 supported_count=1 active_fault_count=not_measured"),"supported status bits allowed without active count or format byte confusion");
        f=ready();atList(f,0,255);frames=fragmented(f,supported(255,1024),255);check(frames.size()==17,"seventeen advertised fragments");f.receive(frames.get(0));
        check("FRAME_SHAPE".equals(f.finish),"seventeen frames stop without partial success");
        for(int k=0;k<6;k++) {
            f=ready();atList(f,0,9);byte[] b=supported(9,2);
            if(k==0)b[2]=8;if(k==1)b[6]=2;if(k==2){b[7]=b[3];b[8]=b[4];b[9]=b[5];}
            if(k==3)b=Arrays.copyOf(b,b.length-1);if(k==4)b[1]=2;if(k==5)b=new byte[]{0x59,10};
            answer(f,b);check(f.finish!=null,"strict list invalid "+k);
        }
        f=ready();atList(f,0,255);frames=fragmented(f,supported(255,10),10);f.receive(frames.get(1));check(f.finish!=null,"missing first fragment");
        f=ready();atList(f,0,255);frames=fragmented(f,supported(255,10),10);f.receive(frames.get(0));byte[] changed=frames.get(1).clone();changed[4]--;f.receive(changed);check(f.finish!=null,"frame count changes");
        f=ready();atList(f,0,255);frames=fragmented(f,supported(255,10),10);f.receive(frames.get(0));f.receive(frames.get(2));check(f.finish!=null,"out of order");
        f=ready();atList(f,0,255);frames=fragmented(f,supported(255,10),10);f.receive(frames.get(0));byte[] changed2=frames.get(0).clone();changed2[changed2.length-1]^=1;f.receive(changed2);check(f.finish!=null,"altered duplicate stops");
        f=ready();f.e.deadline=f.now;f.e.sendNext();check("OVERALL_TIMEOUT".equals(f.finish),"finite overall");
    }
    static void rejectedEvidence() {
        Fake f=ready();byte[] bad=frame(0x7E0,1,1,3,new byte[]{0x44});
        f.receive(bad);
        check("GATEWAY_SHAPE".equals(f.finish),"gateway shape remains refused");
        check(f.line("REJECTED_FRAME seq=1 target=7E0 t_ns=2000000000 reason=GATEWAY_SHAPE raw_bytes=8 logged_bytes=8 truncated=false raw="+SupportedDtcReadProbe.hex(bad)),"rejected source bytes retained");
        check(f.lines.get(f.lines.size()-2).startsWith("REJECTED_FRAME ")&&f.lines.get(f.lines.size()-1).equals("FINISH reason=GATEWAY_SHAPE"),"rejected evidence precedes stop");
        boolean accepted=false;for(String s:f.lines)if(s.startsWith("FRAME seq="))accepted=true;
        check(!accepted,"rejected frame not accepted or assembled");
        f=ready();f.receive(new byte[1025]);
        String line=f.lines.get(f.lines.size()-2);
        check(line.contains("raw_bytes=1025 logged_bytes=1024 truncated=true raw="),"oversize evidence explicitly truncated");
        check(line.substring(line.indexOf(" raw=")+5).length()==2048,"logged raw cap exactly1024bytes");
        f=ready();f.receive(null);check(f.line("raw_bytes=-1 logged_bytes=0 truncated=false raw=NULL"),"null callback labelled not invented");
        f=ready();atList(f,0,255);byte[] malformed=supported(255,1);malformed[1]=2;byte[] source=single(f,malformed);f.receive(source);
        check("SUPPORTED_RESPONSE_SHAPE".equals(f.finish)&&f.line("reason=SUPPORTED_RESPONSE_SHAPE"),"response failure keeps source frame and reason");
        f=ready();byte[] foreign=frame(0x7E7,1,1,1,identity(f));f.receive(foreign);
        check(f.line("reason=UNEXPECTED_DIAGNOSTIC_TRAFFIC")&&f.line("raw="+SupportedDtcReadProbe.hex(foreign)),"foreign source retained before abort");
    }
    static void snapshotPlansAndGrammar() {
        for(String name:new String[]{"snapshot-ecm-bms","snapshot-drives"}) {
            Fake f=new Fake(name);f.ready();SupportedDtcReadProbe.Plan p=f.e.plan;
            boolean primary=name.equals("snapshot-ecm-bms");
            check(p.targets.length==(primary?2:4)&&p.overallMs==(primary?35000:65000),"snapshot fixed membership and deadline "+name);
            check(Arrays.equals(p.targets,primary?new SupportedDtcReadProbe.Target[]{SupportedDtcReadProbe.ECM,SupportedDtcReadProbe.BMS}
                :new SupportedDtcReadProbe.Target[]{SupportedDtcReadProbe.FMCU,SupportedDtcReadProbe.ISG,SupportedDtcReadProbe.SMCU,SupportedDtcReadProbe.SMCU3}),"snapshot exact target order "+name);
            check(p.listSubfunction==3,"snapshot only subfunction03 "+name);
            for(int t=0;t<p.targets.length;t++) {
                atList(f,t%2,0);
                check(SupportedDtcReadProbe.hex(f.sent.get(f.sent.size()-1)).equals("0A"+String.format("%04X",f.e.target().address)+"021903"),"snapshot request exact1903 without mask");
                byte[] source=single(f,t==0?snapshots(0):unhex("5903ABCDEF00ABCDEFFF"));
                f.receive(source);f.receive(source);f.advance(500);
            }
            check("PLAN_DONE".equals(f.finish)&&f.e.requests==p.targets.length*3&&f.e.completed==p.targets.length*3,"snapshot complete lifecycle "+name);
            check(f.e.targetsFinished==p.targets.length&&f.guards==p.targets.length*3,"snapshot all targets and guards "+name);
            check(f.line("record_count=0 active_fault_count=not_measured"),"empty5903 valid empty index "+name);
            check(f.line("format=01 record_count=2 active_fault_count=not_measured"),"same code distinct record IDs and prior format "+name);
            boolean availabilityInProjection=false;
            for(String line:f.lines)if(line.startsWith("SNAPSHOT_INDEX ")&&line.contains("status_available="))availabilityInProjection=true;
            check(!availabilityInProjection&&!f.line("SUPPORTED seq="),"snapshot no status mask or supported projection "+name);
            f.advance(100000);check(f.sent.size()==p.targets.length*3,"snapshot no retry or record payload read "+name);
        }
        Fake f=new Fake("snapshot-ecm-bms");f.ready();atList(f,1,0);
        List<byte[]> frames=fragmented(f,snapshots(1019),255);
        check(frames.size()==16,"long snapshot reaches sixteen fragment policy boundary");
        for(byte[] b:frames){f.receive(b);f.receive(Arrays.copyOf(b,b.length+5));}
        check(f.line("format=01 record_count=1019 active_fault_count=not_measured")&&f.line("state=SNAPSHOT_INDEX_READ"),"long snapshot index accepts recordFF without status availability semantics");
        for(int k=0;k<5;k++) {
            f=new Fake("snapshot-ecm-bms");f.ready();atList(f,0,9);byte[] b=snapshots(2);
            if(k==0)b=unhex("5903ABCDEF01ABCDEF01");
            if(k==1)b=Arrays.copyOf(b,b.length-1);
            if(k==2)b[1]=10;
            if(k==3)b=new byte[]{0x59};
            if(k==4)b=unhex("590309");
            answer(f,b);
            String expected=k==0?"DUPLICATE_SNAPSHOT_PAIR":"SNAPSHOT_INDEX_RESPONSE_SHAPE";
            check(expected.equals(f.finish)&&f.line("REJECTED_FRAME seq=3")&&f.line("reason="+expected),"snapshot malformed evidence retained "+k);
            check(!f.line("SNAPSHOT_INDEX seq="),"malformed snapshot no success projection "+k);
        }
        f=new Fake("snapshot-ecm-bms");f.ready();atList(f,0,9);answer(f,new byte[]{0x7F,0x19,0x78});f.advance(3999);
        check(f.e.open&&f.sent.size()==3,"snapshot NRC78 original window");f.advance(1);
        check(f.line("state=REQUEST_TIMEOUT")&&!f.e.open,"snapshot pending no extended deadline");f.advance(500);
        check(f.e.target()==SupportedDtcReadProbe.BMS&&f.e.phase==0&&f.sent.size()==4,"snapshot timeout skips target no repeat");
        f=new Fake("snapshot-ecm-bms");f.ready();atList(f,0,9);f.receive(frame(f.e.target().address,1,1,2,unhex("7F1931")));f.advance(500);
        check(f.line("state=UDS_NEGATIVE_31")&&f.e.target()==SupportedDtcReadProbe.BMS&&f.e.phase==0,"snapshot NRC31 generic negative stops target");
    }
    static Fake atRecord() {
        Fake f=new Fake("bms-snapshot-record");f.ready();atList(f,0,9);answer(f,unhex("59031A3A2201"));f.advance(500);return f;
    }
    static void bmsSnapshotRecord() {
        SupportedDtcReadProbe.Plan p=SupportedDtcReadProbe.plan("bms-snapshot-record");
        check(p.targets.length==1&&p.targets[0]==SupportedDtcReadProbe.BMS&&p.readRecord&&p.listSubfunction==3,"one pinned BMS record plan");
        check(p.maxRequests()==4&&p.overallMs==25000,"BMS record request and deadline bounds");
        for(int phase=0;phase<4;phase++)check(SupportedDtcReadProbe.hex(SupportedDtcReadProbe.payload(p,phase)).equals(new String[]{"22F194","190109","1903","19041A3A2201"}[phase]),"BMS record fixed request "+phase);
        try {SupportedDtcReadProbe.payload(SupportedDtcReadProbe.plan("ecm-bms"),3);throw new AssertionError();}
        catch(IllegalArgumentException expected){check(true,"fourth request unavailable to ordinary plans");}
        try {new SupportedDtcReadProbe.Plan("bad",25000,3,true,SupportedDtcReadProbe.ECM);throw new AssertionError();}
        catch(IllegalArgumentException expected){check(true,"record cannot target another ECU");}
        for(String response:new String[]{"59041A3A22000100","59041A3A220901021234AB", "59041A3A220001FF"}) {
            Fake f=atRecord();check(f.e.phase==3&&f.sent.size()==4,"pair gate permits exactly one fourth send");
            check(SupportedDtcReadProbe.hex(f.sent.get(3)).equals("0A07E70619041A3A2201"),"exact fixed BMS record envelope");
            byte[] uds=unhex(response);byte[] source=single(f,uds);f.receive(source);f.receive(source);f.advance(500);
            check("PLAN_DONE".equals(f.finish)&&f.e.requests==4&&f.e.completed==4&&f.e.targetsFinished==1,"BMS record complete accounting");
            check(f.line("SNAPSHOT_RECORD seq=4 target=7E7 format=00 dtc=1A3A22 status="+String.format("%02X",uds[5]&255)
                +" record=01 did_count="+(uds[7]&255)+" payload_bytes="+(uds.length-8)),"minimal header and opaque remainder projection");
            check(f.line("state=SNAPSHOT_RECORD_READ")&&f.guards==4,"record terminal state and guard");
            f.advance(100000);check(f.sent.size()==4,"record no retry or next service");
        }
        for(String index:new String[]{"5903","59031A3A2202","59031A3A2301"}) {
            Fake f=new Fake("bms-snapshot-record");f.ready();atList(f,0,9);answer(f,unhex(index));f.advance(500);
            check("PLAN_DONE".equals(f.finish)&&f.sent.size()==3&&f.e.completed==3,"absent exact pair no record read");
            check(f.line("state=SNAPSHOT_PAIR_UNAVAILABLE")&&!f.line("SNAPSHOT_RECORD seq="),"absent pair explicit unavailable");
        }
        Fake f=new Fake("bms-snapshot-record");f.ready();answer(f,identity(f));f.advance(500);answer(f,count(1,9,0));f.advance(500);
        check(f.line("FORMAT seq=2 target=7E7 format=01")&&f.line("state=FORMAT_MISMATCH"),"nonzero format retained and gated");
        check("PLAN_DONE".equals(f.finish)&&f.sent.size()==2,"wrong format cannot send03 or04");
        f=new Fake("bms-snapshot-record");f.ready();atList(f,0,9);answer(f,unhex("59031A3A22011A3A2201"));f.advance(500);
        check("DUPLICATE_SNAPSHOT_PAIR".equals(f.finish)&&f.sent.size()==3,"duplicate pair cannot authorize04");
        for(int k=0;k<6;k++) {
            f=atRecord();byte[] uds=unhex("59041A3A22000101ABCD");
            if(k==0)uds=Arrays.copyOf(uds,7);if(k==1)uds[1]=3;if(k==2)uds[2]^=1;
            if(k==3)uds[4]^=1;if(k==4)uds[6]=2;if(k==5)uds[5]=2;
            answer(f,uds);String expected=k==5?"STATUS_OUTSIDE_AVAILABILITY":"SNAPSHOT_RECORD_RESPONSE_SHAPE";
            check(expected.equals(f.finish)&&f.line("REJECTED_FRAME seq=4")&&!f.line("SNAPSHOT_RECORD seq="),"strict04 refusal with retained evidence "+k);
        }
        f=atRecord();f.receive(frame(0x7E7,1,1,2,unhex("7F1931")));f.advance(500);
        check("PLAN_DONE".equals(f.finish)&&f.line("state=UDS_NEGATIVE_31")&&f.sent.size()==4,"record negative raw31 no retry");
        f=atRecord();answer(f,unhex("7F1978"));f.advance(3999);check(f.e.open&&f.sent.size()==4,"record pending original window");f.advance(1);f.advance(500);
        check(f.line("state=REQUEST_TIMEOUT")&&"PLAN_DONE".equals(f.finish)&&f.sent.size()==4,"record pending timeout no resend");
        f=new Fake("bms-snapshot-record");f.ready();atList(f,0,9);answer(f,unhex("59031A3A2201"));f.safe=false;f.advance(500);
        check("VEHICLE_GUARD".equals(f.finish)&&f.sent.size()==3,"vehicle guard rechecked before04");
        f=atRecord();byte[] longUds=new byte[4008];Arrays.fill(longUds,(byte)0xA5);System.arraycopy(unhex("59041A3A22090105"),0,longUds,0,8);
        List<byte[]> frames=fragmented(f,longUds,255);for(byte[] b:frames)f.receive(b);
        check(frames.size()==16&&f.line("did_count=5 payload_bytes=4000")&&f.line("state=SNAPSHOT_RECORD_READ"),"fragmented opaque record retained without DID interpretation");
    }
    static void bmsEmptySnapshotRecord() {
        for(int status:new int[]{0,9}) {
            Fake f=atRecord();byte[] uds=unhex("59041A3A22"+String.format("%02X",status));
            byte[] source=single(f,uds);f.receive(source);f.receive(source);f.advance(500);
            String projection="SNAPSHOT_RECORD_EMPTY seq=4 target=7E7 format=00 dtc=1A3A22 status="+String.format("%02X",status);
            check(f.lines.contains(projection),"exact6byte empty snapshot retains only returned header fields "+status);
            check("PLAN_DONE".equals(f.finish)&&f.e.requests==4&&f.e.completed==4&&f.e.targetsFinished==1,"empty snapshot complete lifecycle "+status);
            check(f.line("STEP_END seq=4 state=SNAPSHOT_RECORD_EMPTY")&&f.line("TARGET_END target=7E7 state=SNAPSHOT_RECORD_EMPTY"),"empty snapshot distinct terminal state "+status);
            check(!f.line("SNAPSHOT_RECORD seq=")&&!projection.contains("record=")&&!projection.contains("did_count=")&&!projection.contains("payload_bytes="),"empty snapshot no invented record/count/payload fields "+status);
            f.advance(100000);check(f.sent.size()==4,"empty snapshot no further read "+status);
        }
        for(String response:new String[]{"59041A3A22","59041A3A220001","59041A3A2300","59041A3A2202"}) {
            Fake f=atRecord();answer(f,unhex(response));
            String expected=response.endsWith("02")?"STATUS_OUTSIDE_AVAILABILITY":"SNAPSHOT_RECORD_RESPONSE_SHAPE";
            check(expected.equals(f.finish)&&f.line("REJECTED_FRAME seq=4"),"empty snapshot bad length/DTC/status refused "+response);
            check(!f.line("SNAPSHOT_RECORD_EMPTY seq=")&&!f.line("SNAPSHOT_RECORD seq="),"invalid empty shape no success projection "+response);
        }
    }
    // First fragment is retained vehicle evidence. The four-byte continuation is synthetic.
    static byte[] retainedIsgTraceFirst() { return unhex("0A07EEE9020103190A9F02030007E602190A5555555555000C0007E6300000555555555500000007EE10E7590A0918021900030007EE210018350000183600060007EE220000183700001800010007EE233800001806170000000007EE241806160018390000000007EE2500183A0000181A00000007EE260000191001001900000007EE271101001912010000010007EE281803960018049600000007EE290018059600180800010007EE2A9600180996001800010007EE2B3C0000180F190000040007EE2C1810170018170000000007EE2D0018110000181200000007EE2E0000180E00001800010007EE2F160000"); }
    static Fake atTrace() { Fake f=new Fake("isg-trace");f.ready();atList(f,1,9);return f; }
    static void isgTraceCapture() {
        byte[] first=retainedIsgTraceFirst(),second=frame(0x7E6,2,2,0,unhex("01020304"));
        Fake f=atTrace();f.receive(first);f.receive(Arrays.copyOf(first,first.length+19));
        check(f.e.open&&!f.e.done&&f.e.completed==2,"trace first is not a successful UDS exchange");
        f.receive(second);f.receive(second);f.advance(500);
        check("PLAN_DONE".equals(f.finish)&&f.e.completed==3&&f.e.targetsFinished==1,"trace collection completes lifecycle");
        check(f.line("GATEWAY_TRACE seq=3 target=7E6 result=03 frames=2 payload_bytes=237 trace_bytes=234 request_prefix=190A9F"),"separate failure transcript exact joined lengths");
        check(f.line("state=GATEWAY_TRACE_CAPTURED")&&!f.line("SUPPORTED seq=3")&&!f.line("RESPONSE seq=3"),"trace never promotes to UDS registry");
        f.advance(100000);check(f.sent.size()==3,"trace no retry or extra request");
        f=atTrace();f.receive(first);f.advance(4000);
        check(f.line("state=REQUEST_TIMEOUT")&&!f.line("GATEWAY_TRACE seq="),"missing fragment uses original deadline and stays partial");
        f.advance(100000);check(f.sent.size()==3,"partial trace no retry");
        f=atTrace();answer(f,supported(9,2));f.advance(500);
        check("PLAN_DONE".equals(f.finish)&&f.line("state=SUPPORTED_READ"),"ordinary successful response remains valid");
        f=new Fake("isg-trace");f.ready();atList(f,0,9);
        check(f.sent.size()==2&&f.line("state=FORMAT_MISMATCH"),"trace exact format01 gate before190A");
        for(int k=0;k<9;k++) {
            f=atTrace();byte[] a=first.clone(),b=second.clone();
            if(k==0)a[8]=3; // wrong echoed subfunction
            if(k==1)a[3]=(byte)232; // short nonfinal chunk
            if(k==2)a[4]=17; // frame count bound
            if(k==3)a[2]--; // foreign route
            if(k==4)a[6]=4; // other gateway failure still refused
            if(k==5)b[4]=3; // changed fragment count
            if(k==6)b[5]=1; // changed nonduplicate first fragment
            if(k==7)b=frame(0x7E6,2,2,0,new byte[231]);
            if(k==8)b=frame(0x7E6,2,2,0,new byte[0]);
            f.receive(a);if(!f.e.done)f.receive(b);
            check(f.e.done&&f.finish!=null&&!f.line("GATEWAY_TRACE seq="),"bad trace cannot complete "+k);
            f.advance(100000);check(f.sent.size()==3,"bad trace cannot dispatch again "+k);
        }
        f=atTrace();f.receive(second);check(f.e.done&&!f.line("GATEWAY_TRACE seq="),"continuation before first refused");
        f=atTrace();f.receive(frame(0x7E6,2,1,1,unhex("590A09180219")));f.receive(first);
        check("TRACE_SHAPE_OR_SEQUENCE".equals(f.finish)&&!f.line("GATEWAY_TRACE seq="),"ordinary partial answer cannot turn into failure transcript");
        f=atTrace();answer(f,unhex("7F1978"));f.receive(first);
        check("TRACE_SHAPE_OR_SEQUENCE".equals(f.finish),"trace capture conservatively rejects mixed pending and failure transcript");
        f=atTrace();f.receive(first);byte[] altered=first.clone();altered[20]^=1;f.receive(altered);
        check("TRACE_SHAPE_OR_SEQUENCE".equals(f.finish),"altered duplicate cannot replace trace");
        f=atTrace();f.receive(first);f.e.received(second,f.now+4_000_000_000L);
        check("LATE_RESPONSE".equals(f.finish),"trace cannot extend original request deadline");
        f=atTrace();for(int i=0;i<65&&!f.e.done;i++)f.receive(first);
        check("EXCHANGE_CALLBACK_BOUND".equals(f.finish),"trace duplicates remain bounded");
        f=new Fake("isg-observe");f.ready();atList(f,1,9);f.receive(first);
        check("GATEWAY_SHAPE".equals(f.finish),"old observation plan retains original strict rejection");
    }
    public static void main(String[] args) {
        goodPlans();negativeAndPending();malformedAndGuards();longAndStrictLists();rejectedEvidence();snapshotPlansAndGrammar();bmsSnapshotRecord();bmsEmptySnapshotRecord();isgTraceCapture();
        System.out.println("PASS assertions="+assertions+" synthetic_only=true");
    }
}
