package dev.denza.adaslink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static dev.denza.adaslink.RealPathAck.Decision.*;

/** Presence, routing and connection epochs; no live publisher or Android runtime. */
public final class RealPathAckTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static final class Rig {
        long current = 7, token;
        final List<Long> selected = new ArrayList<>();
        final RealPathAck ack = new RealPathAck(() -> current, selected::add, 100, 1000, 3);
        Rig() { token = ack.sessionReady(1000); }
        void arm() { check(ack.selectionSent(token,current,1010,1020)==ARMED,"arm after successful selection fire"); }
        RealPathAck.Result event(byte[] payload,long at,long now) { return ack.event(token,Topics.PATH_SELECT_CONFIRM,at,payload,now); }
    }
    public static void main(String[] args) throws Exception {
        parsing(); lifecycle(); bounds();
        if (args.length > 0) stockFeed(new File(args[0]));
        System.out.println("RealPathAckTest: "+checks+" checks passed");
    }
    private static void parsing() {
        // Physical parked v4 callback, trace SHA ab40a423...604ac; exact schema fixture, not generated here.
        RealPathAck.Selection real=RealPathAck.decode(Pb.unhex("0a11089aeadbaa051038188cbe97950420c801"));
        check(real.pathId==1118166796L&&real.statusCode==200,"real car explicit pathIDResp/status200");
        RealPathAck.Selection empty=RealPathAck.decode(Pb.unhex("0a00"));
        check(empty.pathId==null&&empty.statusCode==null,"absent scalars stay unknown");
        check(RealPathAck.decode(Pb.unhex("0a021807")).statusCode==null,"missing status is not zero or success");
        check(RealPathAck.decode(Pb.unhex("0a0320c801")).pathId==null,"missing ID stays unknown");
        check(RealPathAck.decode(Pb.unhex("0a0418072000")).statusCode==0,"explicit zero distinguishable from absent");
        check(RealPathAck.decode(StockMessages.pathSelectConfirm(Long.MAX_VALUE,200)).pathId==Long.MAX_VALUE,"uint64 ID never rounded through double");
        check(RealPathAck.decode(StockMessages.pathSelectConfirm(7,-1)).statusCode==-1,"signed int32 negative status preserved");
        byte[] extension=join(StockMessages.pathSelectConfirm(7,200),Pb.unhex("980601"));
        check(RealPathAck.decode(extension).statusCode==200,"unknown outer scalar skipped");
        check(RealPathAck.decode(wrap(Pb.unhex("180720c801980601"))).statusCode==200,"unknown inner scalar skipped");
        bad(null,"null payload"); bad(new byte[65537],"payload cap"); bad(new byte[0],"no wrapper");
        bad(Pb.unhex("0a00"+"0a00"),"duplicate wrapper"); bad(Pb.unhex("0801"),"wrong wrapper wire");
        bad(wrap(Pb.unhex("1807180720c801")),"duplicate path ID even with equal value");
        bad(wrap(Pb.unhex("180720c80120c801")),"duplicate status");
        bad(wrap(Pb.unhex("19000000000000000020c801")),"ID fixed64 is not uint64 varint");
        bad(wrap(Pb.unhex("180725c8000000")),"status float is not int32");
        bad(Pb.unhex("0a06180720c8"),"truncated body");
        bad(wrap(Pb.unhex("1807208080808010")),"larger-than-uint32 status cannot wrap to success");
        bad(wrap(Pb.unhex("180720c881808010")),"upper status bits cannot wrap to200");
        bad(wrap(Pb.unhex("1880808080808080808002")),"uint64 overflow");
        bad(wrap(Pb.unhex("180720c8010e")),"unsupported group/wire kind");
        bad(wrap(Pb.unhex("180720c80100")),"zero field tag");
        bad(wrap(Pb.unhex("180720c801088080808010")),"uint32 CRC overflow");
        ByteArrayOutputStream fields=new ByteArrayOutputStream();
        for(int i=0;i<4097;i++){fields.write(0x48);fields.write(1);}
        bad(wrap(fields.toByteArray()),"field count cap");
    }
    private static void lifecycle() {
        byte[] ok=StockMessages.pathSelectConfirm(7,200);
        Rig r=new Rig();r.arm();
        check(r.ack.event(r.token,Topics.ROUTE,1015,ok,1020).decision==WRONG_TOPIC,"outbound route cannot acknowledge itself");
        check(r.ack.event(r.token,Topics.event(44,0x8001),1015,ok,1020).decision==WRONG_TOPIC,"PathMatch is not selection ACK");
        check(r.event(Pb.unhex("0a021807"),1015,1020).decision==MISSING_FIELDS,"missing status cannot arm feed");
        check(r.event(StockMessages.pathSelectConfirm(8,200),1015,1020).decision==UNRELATED,"foreign ACK never triggers stock resend");
        check(r.event(ok,1009,1020).decision==STALE,"callback before pre-fire send time rejects");
        check(r.event(ok,1021,1020).decision==FUTURE,"future local callback rejects");
        check(r.event(ok,1015,1020).decision==ACCEPTED,"fast ACK during synchronous fire is accepted after commit");
        check(r.selected.size()==1&&r.selected.get(0)==7&&r.ack.confirmedPathId()==7,"only explicit matching ACK reaches sink");
        for(int i=0;i<10;i++)check(r.event(ok,1021,1022).decision==DUPLICATE,"duplicate does not redeliver");
        check(r.selected.size()==1,"one sink call only");
        r.ack.routeChanged();
        check(r.ack.confirmedPathId()==-1&&r.event(ok,1021,1022).decision==NO_REQUEST,"route replacement clears confirmation");
        check(r.ack.selectionSent(r.token,7,1025,1026)==REUSED_ID,"same wire ID cannot rearm a local request epoch");
        check(r.event(ok,1027,1028).decision==NO_REQUEST,"late ACK for reused current ID cannot arm replacement");

        r=new Rig();r.arm();
        check(r.event(StockMessages.pathSelectConfirm(7,500),1015,1020).decision==FAILED,"explicit matching failure disarms");
        check(r.event(ok,1021,1022).decision==DUPLICATE&&r.selected.isEmpty(),"success after failed request does not rearm");
        r=new Rig();r.arm();
        check(r.event(Pb.unhex("0a0418072000"),1015,1020).decision==FAILED,"explicit0 is failure, not missing/success");
        check(r.selected.isEmpty()&&r.ack.confirmedPathId()==-1,"failure emits no selected callback");
        r=new Rig();r.arm();long old=r.token;
        r.ack.invalidateSession();
        check(r.event(ok,1020,1021).decision==NOT_READY,"disconnect rejects pending ACK");
        r.token=r.ack.sessionReady(1030);r.current=8;
        check(r.ack.selectionSent(r.token,8,1031,1032)==ARMED,"new session needs new ID and genuine new send");
        check(r.ack.selectionSent(old,9,1025,1033)==WRONG_SESSION,"late old-session send completion rejected without mutation");
        check(r.ack.event(old,Topics.PATH_SELECT_CONFIRM,1033,StockMessages.pathSelectConfirm(8,200),1034).decision==WRONG_SESSION,
                "callback captured on previous transport epoch cannot reach new request");
        check(r.event(StockMessages.pathSelectConfirm(8,200),1033,1034).decision==ACCEPTED,"current epoch exact new path succeeds");
        check(r.ack.selectionSent(old,9,1025,1035)==WRONG_SESSION&&r.ack.confirmedPathId()==8,
                "old-session send completion cannot clear confirmed current path");
        r.ack.invalidateSession();r.token=r.ack.sessionReady(1040);r.current=7;
        check(r.ack.selectionSent(r.token,7,1041,1042)==REUSED_ID,"reconnect does not clear used wire IDs");

        r=new Rig();r.arm();r.current=8;
        check(r.event(ok,1020,1021).decision==REPLACED,"current published path checked at worker consumption");
        r.current=7;
        check(r.event(ok,1021,1022).decision==NO_REQUEST,"return to old current path does not resurrect old request");
        r=new Rig();r.arm();
        check(r.event(ok,1015,1116).decision==STALE,"queued old callback exceeds explicit queue-delay policy");
        check(r.selected.isEmpty(),"queued stale callback has no feed side effect");
        check(r.event(ok,2011,2011).decision==EXPIRED,"pending request expiration never resends");
        check(r.ack.confirmedPathId()==-1,"expired request is not selected");
    }
    private static void bounds() {
        Rig r=new Rig();
        for(long id=7;id<10;id++){r.current=id;check(r.ack.selectionSent(r.token,id,1010,1020)==ARMED,"bounded distinct ID admitted");}
        r.current=10;
        check(r.ack.selectionSent(r.token,10,1010,1020)==ID_CAPACITY,"ID retention saturation fails closed");
        r.token=r.ack.sessionReady(1030);
        check(r.ack.selectionSent(r.token,11,1031,1032)==NOT_READY,"reconnect cannot reset exhausted readiness");
        long[] path={7};int[] calls={0};
        RealPathAck throwing=new RealPathAck(()->path[0],id->{calls[0]++;throw new IllegalStateException("sink");},100,1000);
        long token=throwing.sessionReady(1000);throwing.selectionSent(token,7,1010,1020);
        check(throwing.event(token,Topics.PATH_SELECT_CONFIRM,1015,StockMessages.pathSelectConfirm(7,200),1020).decision==DELIVERY_FAILED,
                "local sink failure is explicit and contained");
        check(throwing.event(token,Topics.PATH_SELECT_CONFIRM,1021,StockMessages.pathSelectConfirm(7,200),1022).decision==DUPLICATE&&calls[0]==1,
                "sink failure is not automatically retried");
        check(throwing.confirmedPathId()==-1,"failed local delivery is not presented as consumed confirmation");
    }
    private static void stockFeed(File root) throws Exception {
        YandexRoute route=YandexRoute.fromSnapshot(ConverterTests.read(new File(root,DynamicTests.M4)));
        StockFeed.Config config=DynamicTests.config();config.emulatedAckDelayMs=-1;
        DynamicTests.Rig rig=new DynamicTests.Rig(2_000_000,config,true,true);
        rig.observe(DynamicTests.guidance(2_000_100,route.routeId,route,1,10,1));
        RealPathAck adapter=new RealPathAck(rig.feed::publishedPathId,
                id->rig.feed.onPathSelectConfirm(StockMessages.pathSelectConfirm(id,200)),100,1000);
        long token=adapter.sessionReady(2_000_000),path=rig.feed.publishedPathId();
        check(path==100002&&adapter.selectionSent(token,path,2_000_100,2_000_100)==ARMED,"stock feed actual published path armed");
        check(adapter.confirmedPathId()==-1,"no emulated ACK reaches real adapter");
        int fires=rig.fires(Topics.ROUTE).size();
        check(adapter.event(token,Topics.PATH_SELECT_CONFIRM,2_000_110,StockMessages.pathSelectConfirm(path+1,200),2_000_110).decision==UNRELATED,
                "real adapter filters foreign success before stock retry code");
        rig.clock.advanceTo(2_000_110);
        check(rig.fires(Topics.ROUTE).size()==fires,"foreign answer cannot start resend conflict");
        check(adapter.event(token,Topics.PATH_SELECT_CONFIRM,2_000_120,StockMessages.pathSelectConfirm(path,200),2_000_120).decision==ACCEPTED,
                "matching real answer reaches existing feed consumer");
        check(((Number)rig.feed.debugState().get(2)).longValue()==path,"feed selected path updated only after matching explicit200");
        check(rig.fires(Topics.ROUTE).size()==fires,"genuine success does not resend");
    }
    private static void bad(byte[] bytes,String why) {
        boolean rejected=false;
        try { RealPathAck.decode(bytes); }catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,why);
    }
    private static byte[] join(byte[] a,byte[] b) {
        byte[] out=new byte[a.length+b.length];System.arraycopy(a,0,out,0,a.length);System.arraycopy(b,0,out,a.length,b.length);return out;
    }
    private static byte[] wrap(byte[] body) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(10);Pb.varint(out,body.length);out.write(body,0,body.length);return out.toByteArray();
    }
}
