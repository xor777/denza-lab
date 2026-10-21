package dev.denza.diagnostics.probe;

import android.content.Context;
import android.os.SystemClock;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Passive listeners only. Never installs or changes a CAN collection table.
 * Retains target candidates in bounded memory, so callback formatting/ADB cannot
 * delay diagnostic exchanges. SecretOBD buffers remain opaque: its native pointer
 * differs for extended outer packets. Missing candidates are not wire absence.
 */
final class DiagnosticCanObserver {
    static final String[] NAMES={"ota","gb","bigdata","yun","mqtt","secretobd"};
    static final String[] DEVICES={"ota.BYDAutoOtaDevice","gb.BYDAutoGBDevice",
        "bigdata.BYDAutoBigDataDevice","yun.BYDAutoYunDevice","mqtt.BYDAutoMqttDevice",
        "setting.BYDAutoSettingDevice"};
    static final int[] IDS={1032,1039,1061,1034,1033,1023};
    static final int[] FIDS={0x9900001E,0x9900001F,0x99000020,0x99000021,0x99000165,0x99000241};
    static final int MAX_CALLBACKS=50000,MAX_RETAINED=512,MAX_BUFFER=1024;
    static final class Record {
        final int stream;final long at,sequence;final String kind;final byte[] bytes;
        Record(int stream,long at,long sequence,String kind,byte[] bytes) {
            this.stream=stream;this.at=at;this.sequence=sequence;this.kind=kind;this.bytes=bytes.clone();
        }
    }
    static final class Ledger {
        final long[] received=new long[6],matched=new long[6],malformed=new long[6],first=new long[6],last=new long[6];
        final boolean[] sampled=new boolean[6];
        final List<Record> records=new ArrayList<>();
        long callbacks;boolean failed,closed;
        synchronized void fail() {failed=true;}
        static long id(byte[] b,int offset) {
            return ((b[offset]&255L)<<24)|((b[offset+1]&255L)<<16)|((b[offset+2]&255L)<<8)|(b[offset+3]&255L);
        }
        static boolean target(long id) { return id==0x7E8||id==0x7EE; }
        boolean accept(int stream,byte[] b,long at) {return accept(stream,b,at,false);}
        synchronized boolean accept(int stream,byte[] b,long at,boolean initialQuiet) {
            if(closed||failed)return false;
            if(stream<0||stream>=6) {failed=true;return false;}
            callbacks++;long seq=++received[stream];
            if(seq==1)first[stream]=at;last[stream]=at;
            if(callbacks>MAX_CALLBACKS||b==null||b.length>MAX_BUFFER) {failed=true;return false;}
            boolean opaque=stream==5;
            boolean shape=b.length>=10;
            if(!opaque&&!shape)malformed[stream]++;
            boolean candidate=opaque ? ((b.length>=4&&target(id(b,0)))||(b.length>=5&&target(id(b,1))))
                                     : shape&&target(id(b,0));
            if(candidate)matched[stream]++;
            if(candidate||!sampled[stream]) {
                if(records.size()>=MAX_RETAINED) {failed=true;return false;}
                records.add(new Record(stream,at,seq,candidate?(opaque?"OPAQUE_CANDIDATE":"TARGET_CANDIDATE"):"SAMPLE",b));
                sampled[stream]=true;
            }
            if(candidate&&initialQuiet) {failed=true;return false;}
            return true;
        }
        synchronized List<String> finish(boolean cleanup) {
            closed=true;List<String> lines=new ArrayList<>();
            for(Record r:records)lines.add("CAN_OBSERVER_FRAME stream="+NAMES[r.stream]+" seq="+r.sequence
                +" t_ns="+r.at+" kind="+r.kind+" bytes="+r.bytes.length+" raw="+SupportedDtcReadProbe.hex(r.bytes));
            for(int i=0;i<6;i++)lines.add("CAN_OBSERVER_STREAM stream="+NAMES[i]+" callbacks="+received[i]
                +" candidates="+matched[i]+" malformed="+malformed[i]+" first_ns="+first[i]+" last_ns="+last[i]);
            lines.add("CAN_OBSERVER_END callbacks="+callbacks+" retained="+records.size()+" failed="+failed+" cleanup="+cleanup);
            return lines;
        }
    }
    final Ledger ledger=new Ledger();
    final Object[] devices=new Object[6],listeners=new Object[6];
    final Class<?>[] classes=new Class<?>[6];
    final boolean[] attempted=new boolean[6];
    Class<?> listenerClass;
    void start(Context context,Runnable failure,BooleanSupplier noRequestSent) throws Exception {
        listenerClass=Class.forName("android.hardware.IBYDAutoListener");
        Class<?> eventClass=Class.forName("android.hardware.IBYDAutoEvent");
        Class<?> valueClass=Class.forName("android.hardware.bydauto.BYDAutoEventValue");
        for(int i=0;i<6;i++) {
            final int stream=i;
            classes[i]=Class.forName("android.hardware.bydauto."+DEVICES[i]);
            devices[i]=classes[i].getMethod("getInstance",Context.class).invoke(null,context);
            listeners[i]=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{listenerClass},(proxy,m,a)->{
                try {
                    byte[] bytes=null;boolean delivered=false;
                    if(m.getName().equals("onDataEventChanged")&&a!=null&&a.length>1&&Integer.valueOf(FIDS[stream]).equals(a[0])) {
                        bytes=(byte[])valueClass.getField("bufferDataValue").get(a[1]);delivered=true;
                    } else if(m.getName().equals("onDataChanged")&&a!=null&&a.length>0&&a[0]!=null
                            &&Integer.valueOf(FIDS[stream]).equals(eventClass.getMethod("getEventType").invoke(a[0]))
                            &&Integer.valueOf(IDS[stream]).equals(eventClass.getMethod("getDeviceType").invoke(a[0]))) {
                        bytes=(byte[])eventClass.getMethod("getBufferData").invoke(a[0]);delivered=true;
                    } else if(m.getName().equals("onError")) {ledger.fail();failure.run();}
                    else if(m.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    else if(m.getName().equals("equals"))return a!=null&&a.length==1&&proxy==a[0];
                    else if(m.getName().equals("toString"))return "DiagnosticCanObserver-"+NAMES[stream];
                    if(delivered&&!ledger.accept(stream,bytes,SystemClock.elapsedRealtimeNanos(),noRequestSent.getAsBoolean()))failure.run();
                } catch(Throwable error) {ledger.fail();failure.run();}
                return null;
            });
            attempted[i]=true;
            // MQTT also carries the primary diagnostic listener. Preserve its RX
            // filter even if native enableDevice replaces the feature set.
            int[] filters=i==4?new int[]{SupportedDtcReadProbe.RX,FIDS[i]}:new int[]{FIDS[i]};
            classes[i].getMethod("registerListener",listenerClass,int[].class).invoke(devices[i],listeners[i],filters);
            SupportedDtcReadProbe.out("CAN_OBSERVER_READY stream="+NAMES[i]+" device="+IDS[i]+" fid="+String.format("%08X",FIDS[i])
                +" t_ns="+SystemClock.elapsedRealtimeNanos());
        }
    }
    boolean close() {
        boolean ok=true;
        for(int i=5;i>=0;i--)if(attempted[i]) {
            try {classes[i].getMethod("unregisterListener",listenerClass).invoke(devices[i],listeners[i]);attempted[i]=false;}
            catch(Throwable error) {ok=false;}
        }
        for(String line:ledger.finish(ok))SupportedDtcReadProbe.out(line);
        return ok&&!ledger.failed;
    }
}
