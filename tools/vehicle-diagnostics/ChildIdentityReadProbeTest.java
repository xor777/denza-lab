package dev.denza.diagnostics.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/** Synthetic transport/lifecycle cases; none is vehicle evidence. Plain host Java. */
public final class ChildIdentityReadProbeTest {
    static int assertions;
    static void check(boolean v, String name) {
        assertions++; if (!v) throw new AssertionError(name);
    }
    static final class Event implements Comparable<Event> {
        long time; Runnable task; int ordinal;
        Event(long time, Runnable task, int ordinal) { this.time=time; this.task=task; this.ordinal=ordinal; }
        public int compareTo(Event o) { int c=Long.compare(time,o.time); return c==0?Integer.compare(ordinal,o.ordinal):c; }
    }
    static final class Fake implements ChildIdentityReadProbe.Host {
        long now; int order, guards; boolean safe=true, throwSend; String finish;
        final PriorityQueue<Event> events=new PriorityQueue<>();
        final List<byte[]> requests=new ArrayList<>(); final List<String> lines=new ArrayList<>();
        final ChildIdentityReadProbe.Engine e=new ChildIdentityReadProbe.Engine(this);
        public long nanos() { return now; }
        public boolean safe() { guards++; return safe; }
        public void later(Runnable r,int ms) { events.add(new Event(now+ms*1_000_000L,r,order++)); }
        public void send(byte[] r) { if(throwSend)throw new IllegalStateException(); requests.add(r.clone()); }
        public void out(String s) { lines.add(s); }
        public void finish(String reason,int requests,int completed) { finish=reason; }
        void start() { e.start(); }
        void advance(int ms) {
            long end=now+ms*1_000_000L;
            while(!events.isEmpty() && events.peek().time<=end) { Event v=events.remove();now=v.time;v.task.run(); }
            now=end;
        }
        void receive(byte[] b) { e.received(b,now); }
        void ready() { start();advance(2000); }
    }
    static byte[] frame(ChildIdentityReadProbe.Step s,int result,byte[] uds) {
        byte[] b=new byte[7+uds.length];b[0]=10;b[1]=(byte)((s.address+8)>>8);b[2]=(byte)(s.address+8);
        b[3]=(byte)uds.length;b[4]=1;b[5]=1;b[6]=(byte)result;System.arraycopy(uds,0,b,7,uds.length);return b;
    }
    static byte[] positive(ChildIdentityReadProbe.Step s) {
        byte[] uds=new byte[12];uds[0]=0x62;uds[1]=(byte)(s.did>>8);uds[2]=(byte)s.did;
        for(int i=0;i<9;i++)uds[3+i]=(byte)Integer.parseInt(s.cached.substring(i*2,i*2+2),16);
        return frame(s,1,uds);
    }
    static Fake ready() { Fake f=new Fake();f.ready();return f; }
    static void eightGood() {
        Fake f=ready();
        for(int i=0;i<8;i++) {
            check(f.requests.size()==i+1,"one current request");
            byte[] b=positive(ChildIdentityReadProbe.STEPS[i]);f.receive(b);f.receive(b);
            f.receive(Arrays.copyOf(b,b.length+20)); // Duplicate comparison uses declared bytes.
            f.advance(500);
        }
        check("PLAN_DONE".equals(f.finish),"complete plan");check(f.requests.size()==8,"exactly8");
        check(f.e.completed==8 && f.guards==8,"guard per request");f.advance(60000);
        check(f.requests.size()==8,"old timers cannot resend");
    }
    static void refusals() {
        Fake f=new Fake();f.start();f.receive(positive(ChildIdentityReadProbe.STEPS[0]));
        check("PREEXISTING_DIAGNOSTIC_TRAFFIC".equals(f.finish)&&f.requests.isEmpty(),"quiet gate");
        f=ready();f.safe=false;f.receive(positive(ChildIdentityReadProbe.STEPS[0]));f.advance(500);
        check("VEHICLE_GUARD".equals(f.finish)&&f.requests.size()==1,"guard transition");
        f=new Fake();f.safe=false;f.ready();check(f.requests.isEmpty(),"initial unsafe");
        for(int variant=0;variant<7;variant++) {
            f=ready();byte[] b=positive(ChildIdentityReadProbe.STEPS[0]);
            if(variant==0)b[0]=9;
            if(variant==1)b[2]++;
            if(variant==2)b[4]=2;
            if(variant==3)b[5]=2;
            if(variant==4)b=Arrays.copyOf(b,b.length-1);
            if(variant==5)b[9]++;
            if(variant==6)b[6]=0;
            f.receive(b);f.advance(5000);check(f.finish!=null&&f.requests.size()==1,"foreign/partial/mismatched refusal"+variant);
        }
        f=ready();f.e.received(positive(ChildIdentityReadProbe.STEPS[0]),f.now-1);
        check("UNEXPECTED_DIAGNOSTIC_TRAFFIC".equals(f.finish),"stale queued frame");
        f=ready();f.e.received(positive(ChildIdentityReadProbe.STEPS[0]),f.now+4_000_000_000L);
        check("LATE_RESPONSE".equals(f.finish),"deadline arrival");
        f=ready();f.receive(frame(ChildIdentityReadProbe.STEPS[0],5,new byte[0]));f.advance(5000);
        check("GATEWAY_BUSY".equals(f.finish)&&f.requests.size()==1,"busy stops");
    }
    static void timeoutAndPending() {
        Fake f=ready();f.receive(frame(ChildIdentityReadProbe.STEPS[0],2,new byte[]{0x7F,0x22,0x78}));
        f.advance(3999);check(f.e.open&&f.requests.size()==1,"pending no resend");
        f.advance(1);check(!f.e.open&&f.e.completed==1,"original deadline");
        f.advance(500);check(f.requests.size()==2,"timeout advances once");
        f.advance(60000);check("PLAN_DONE".equals(f.finish)&&f.requests.size()==8,"timeouts eight only");
        f=ready();byte[] z=positive(ChildIdentityReadProbe.STEPS[0]);Arrays.fill(z,10,z.length,(byte)0);f.receive(z);
        check(f.lines.contains("STEP_END step=0 state=ZERO_PLACEHOLDER"),"zero not identity");
        f=ready();z=positive(ChildIdentityReadProbe.STEPS[0]);z[10]^=1;f.receive(z);
        check(f.lines.contains("STEP_END step=0 state=CACHE_MISMATCH"),"different cache not confirmed");
        f=ready();f.receive(frame(ChildIdentityReadProbe.STEPS[0],2,new byte[]{0x7F,0x22,0x31}));
        check(f.lines.contains("STEP_END step=0 state=UDS_NEGATIVE_31"),"negative recorded");
        f=ready();f.e.abort("CALLER_CLOSED_OR_INPUT");f.advance(60000);check(f.requests.size()==1,"cancel no further request");
        f=new Fake();f.throwSend=true;f.ready();check("SEND_OR_GUARD_FAILED".equals(f.finish),"transport no retry");
    }
    public static void main(String[] args) {
        eightGood();refusals();timeoutAndPending();
        check(ChildIdentityReadProbe.STEPS.length==8,"fixed plan length");
        System.out.println("PASS assertions="+assertions+" synthetic_only=true");
    }
}
