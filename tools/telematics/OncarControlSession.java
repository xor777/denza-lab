package dev.denza.tools;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLSocket;

/** Bounded owner-operated climate test. Original firmware owns commands and responses. */
public final class OncarControlSession {
    static Context context;
    static Object auto, yunDevice;
    static Method setBuffer;
    static int writes, signedSessions;
    static long finishBy;
    static void need(boolean ok,String why){OncarTls.need(ok,why);}
    static void event(String message){OncarNativeSession.event(message);}
    static int get(int device,int fid)throws Exception{return OncarNativeSession.integer(device,fid);}
    static int temperature()throws Exception{return get(1000,0x40400028);}
    static byte[] environment()throws Exception {
        int acc=get(1001,0x12d0002a),mcu=get(1005,0x99000003),speed=get(1014,0x14400008);
        int batteryOnly=get(1023,0x2f4000fa),power=get(1000,0x40400010);
        float soc=OncarNativeSession.soc();
        String repair=OncarNativeSession.prop("persist.sys.repair_mode.enable");
        String energy=OncarNativeSession.prop("persist.sys.energytype");
        need(repair.isEmpty()||repair.equals("0"),"repair mode active or unknown");
        need(energy.matches("[0-2]"),"energy type unavailable");
        need(acc==2&&mcu==1&&speed==0&&power==1,"car must stay awake stationary with climate on");
        need((batteryOnly==0||batteryOnly==1)&&Float.isFinite(soc)&&soc>=20&&soc<=100,"battery state unavailable or low");
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(acc).putInt(mcu)
            .putInt(speed).putInt(batteryOnly).putFloat(soc).putInt(0).putInt(Integer.parseInt(energy)).putInt(power).array();
    }
    static final class Row {final int fid;final byte[] raw;final long time=SystemClock.elapsedRealtime();Row(int f,byte[] r){fid=f;raw=r.clone();}}
    static final class Source implements AutoCloseable {
        final ArrayBlockingQueue<Row> queue=new ArrayBlockingQueue<>(1024);
        final Object device,listener;final Class<?> listenerType;
        volatile boolean failed;int callbacks;
        Source()throws Exception {
            Class<?> type=Class.forName("android.hardware.bydauto.yun.BYDAutoYunDevice");
            listenerType=Class.forName("android.hardware.IBYDAutoListener");
            Class<?> ev=Class.forName("android.hardware.IBYDAutoEvent");
            Method dev=ev.getMethod("getDeviceType"),fid=ev.getMethod("getEventType"),buffer=ev.getMethod("getBufferData");
            device=yunDevice;
            listener=Proxy.newProxyInstance(Source.class.getClassLoader(),new Class<?>[]{listenerType},(proxy,method,args)->{
                if(method.getName().equals("onDataChanged")){
                    try{
                        if((Integer)dev.invoke(args[0])!=1034)return null;
                        int id=(Integer)fid.invoke(args[0]);
                        if(id!=0x99000021&&id!=0x99000004)return null;
                        byte[] raw=(byte[])buffer.invoke(args[0]);need(raw!=null,"empty callback");
                        if(id==0x99000021){need(raw.length>=18&&raw.length<=74,"SDK buffer bound");
                            long can=ByteBuffer.wrap(raw).getInt()&0xffffffffL;
                            if(!CloudCanSnapshotProbe.allowedCanId(can))return null;
                        }else need(raw.length>=6&&raw.length<=256,"MCU buffer bound");
                        if(!queue.offer(new Row(id,raw)))failed=true;
                    }catch(Throwable error){failed=true;}
                    return null;
                }
                if(method.getName().equals("onError")){failed=true;return null;}
                if(method.getName().equals("toString"))return "DenzaBoundedControlListener";
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return args!=null&&args.length==1&&proxy==args[0];
                return null;
            });
            type.getMethod("registerListener",listenerType,int[].class).invoke(device,listener,new int[]{0x99000021,0x99000004});
            event("SDK_REGISTERED control_callback=true passive_data=true");
        }
        public void close()throws Exception {
            device.getClass().getMethod("unregisterListener",listenerType).invoke(device,listener);
            event("SDK_UNREGISTERED failed="+failed+" callbacks="+callbacks);queue.clear();
        }
    }
    static final class Channel implements AutoCloseable {
        final SSLSocket socket;final ArrayBlockingQueue<byte[]> incoming=new ArrayBlockingQueue<>(16);
        final Thread reader;volatile String failure;volatile boolean closing;
        Channel(OncarNativeSession.Worker worker)throws Exception {
            socket=OncarTls.connect("dilinknat0-cn.denzacloud.com",6041);
            try{
                OncarNativeSession.signatureTotal+=OncarTls.signatureCount();signedSessions++;
                socket.getOutputStream().write(worker.produce("L"));socket.getOutputStream().flush();
                need(worker.exchange("R220 "+OncarTls.hex(OncarNativeSession.frame(socket.getInputStream()))).equals("LOGIN 1"),"control login rejected");
                socket.setSoTimeout(95000);
                reader=new Thread(()->{try{while(!closing){byte[] f=OncarNativeSession.frame(socket.getInputStream());need(incoming.offer(f),"cloud input overflow");}}
                    catch(Exception error){if(!closing)failure=error.getClass().getSimpleName();}},"control-cloud-reader");reader.start();
            }catch(Exception|Error error){
                closing=true;try{socket.close();}catch(Exception cleanup){error.addSuppressed(cleanup);}
                throw error;
            }
        }
        void send(byte[] raw)throws Exception{socket.getOutputStream().write(raw);socket.getOutputStream().flush();}
        public void close()throws Exception{closing=true;socket.close();reader.join(1500);}
    }
    static int exchange(OncarNativeSession.Worker worker,Channel channel,String command)throws Exception {
        need(command.length()<2100,"control IPC bound");
        worker.writer.write(command);worker.writer.newLine();worker.writer.flush();
        for(int i=0;i<10;i++){
            String answer=worker.reader.readLine();
            if(answer==null){need(false,"native worker exited="+(worker.process.isAlive()?"pipe_closed":worker.process.exitValue()));}
            if(answer.matches("\\{\"passed\":false,\"stage\":\"[a-z_]+\"\\}"))
                throw new IllegalStateException("native stage="+answer.split("\"")[5]);
            need(answer.length()<2200&&!answer.startsWith("{"),"native control worker rejected input");
            if(answer.startsWith("META ")){need(answer.matches("META [a-z=0-9 ]+"),"metadata IPC");event(answer);continue;}
            if(answer.startsWith("NET ")){byte[] raw=OncarTls.unhex(answer.substring(4));channel.send(raw);event("NATIVE_CLOUD_REPLY bytes="+raw.length);}
            else if(answer.startsWith("AUTO ")){
                String[] parts=answer.split(" ");need(parts.length==3,"native action IPC");
                long rawId=Long.parseLong(parts[1]);need(rawId==0xaa000004L||rawId==0xaa00001eL,"native action outside test");
                byte[] raw=OncarTls.unhex(parts[2]);need(raw.length<=256,"native action size");
                // A second fresh check immediately before relaying the original bytes.
                environment();need(OncarNativeSession.tcp()==0,"stock connection competed");
                need(++writes<=8,"bounded native relay writes");
                int result=(Integer)setBuffer.invoke(auto,1034,(int)rawId,raw);
                event("NATIVE_MCU_RELAY fid="+Long.toHexString(rawId)+" bytes="+raw.length+" sdk_result="+result);
                need(result==0,"SDK refused native relay");
            }else if(answer.startsWith("CONTROL ")){
                String[] parts=answer.split(" ");need(parts.length==4,"control result IPC");
                event("NATIVE_CONTROL command="+parts[1]+" results="+parts[2]+" terminal="+parts[3]);
                return Integer.parseInt(parts[3]);
            }else if(answer.startsWith("STATUS ")){
                String[] parts=answer.split(" ");need(parts.length==3,"status IPC");
                channel.send(OncarTls.unhex(parts[2]));event("NATIVE_STATUS_REPLY soc="+(Integer.parseInt(parts[1])/10f));return 0;
            }else throw new IllegalStateException("unexpected control IPC record");
        }
        throw new IllegalStateException("native effect limit");
    }
    static void session(OncarNativeSession.Worker worker,int index,int expectedTemperature)throws Exception {
        environment();int before=temperature();need(before>=18&&before<=28,"temperature baseline");
        try(Channel channel=new Channel(worker);Source source=new Source()){
            need(OncarNativeSession.tcp()==0,"stock connection competed");
            worker.set("E",environment());
            long end=SystemClock.elapsedRealtime()+(index==1?75000:120000), lastData=0, nextCheck=0;
            long settleUntil=0;
            int terminal=0;boolean ready=false;
            while(SystemClock.elapsedRealtime()<(settleUntil==0?end:settleUntil)){
                need(!source.failed&&channel.failure==null,"control source failed");
                Row row=source.queue.poll(10,TimeUnit.MILLISECONDS);
                if(row!=null){
                    need(SystemClock.elapsedRealtime()-row.time<2000,"stale callback queue");source.callbacks++;
                    if(row.fid==0x99000021){worker.set("I",row.raw);lastData=row.time;}
                    else{worker.set("E",environment());int outcome=exchange(worker,channel,"MCU "+OncarTls.hex(row.raw));if(outcome!=0)terminal=outcome;}
                }
                if(!ready&&source.callbacks>=100){ready=true;event("CONTROL_READY index="+index+" temperature="+before+" requested="+expectedTemperature+" window_s="+(index==1?70:115));}
                if(SystemClock.elapsedRealtime()>=nextCheck){need(OncarNativeSession.tcp()==0,"stock connection competed");environment();nextCheck=SystemClock.elapsedRealtime()+1000;}
                byte[] packet=ready?channel.incoming.poll():null;
                if(packet!=null){
                    need(ready&&SystemClock.elapsedRealtime()-lastData<2000,"fresh SDK cache required");
                    worker.set("E",environment());worker.set("H",OncarNativeSession.le(get(1009,0x34400018)));
                    worker.set("T",OncarNativeSession.le((int)(System.currentTimeMillis()/1000)));
                    int outcome=exchange(worker,channel,"RX "+OncarTls.hex(packet));if(outcome!=0)terminal=outcome;
                }
                need(terminal!=2,"MCU rejected command");
                if(terminal==1&&settleUntil==0){settleUntil=SystemClock.elapsedRealtime()+15000;
                    event("CONTROL_TERMINAL index="+index+" settle_seconds=15");}
                need(SystemClock.elapsedRealtime()<finishBy-100000,"restoration time reserved");
            }
            need(terminal==1,"no successful terminal MCU result");
            Thread.sleep(600);int after=temperature();
            need(after==expectedTemperature&&get(1000,0x40400010)==1,"independent temperature result mismatch");
            event("CONTROL_PASS index="+index+" temperature_before="+before+" temperature_after="+after+" stock_tcp=0");
        }
    }
    static void run(String dir){
        OncarNativeSession.Inputs original=null;boolean changedAttempted=false,restored=false,stock=false,passed=false;
        try{
            need(OncarNativeSession.tcp()==1&&OncarNativeSession.prop("persist.sys.byd.apn_type").equals("double_apn"),"stock baseline");
            environment();int initialTemperature=temperature();need(initialTemperature>=19&&initialTemperature<=28,"temperature baseline");
            original=new OncarNativeSession.Inputs();OncarTls.factoryIdentity();
            try(OncarNativeSession.Worker w=new OncarNativeSession.Worker(original)){
                OncarGeneratedPairSession.bootstrap(w,"original_before");
                OncarGeneratedPairSession.paused=true;OncarNativeSession.gate(-5);
                long deadline=SystemClock.elapsedRealtime()+8000;
                while(OncarNativeSession.tcp()!=0&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(200);
                need(OncarNativeSession.tcp()==0,"stock did not pause");
                OncarGeneratedPairSession.control(w,"original_before");
            }
            OncarNativeSession.Inputs changed=new OncarNativeSession.Inputs(original,
                OncarGeneratedPairSession.generated("898607",20,original.iccid),OncarGeneratedPairSession.generated("46001",15,original.imsi));
            changedAttempted=true;
            try(OncarNativeSession.Worker w=new OncarNativeSession.Worker(changed)){
                OncarGeneratedPairSession.bootstrap(w,"generated_control_pair");session(w,1,initialTemperature-1);
            }
            event("CONTROL_RECONNECT new_worker=true same_pair=true");
            try(OncarNativeSession.Worker w=new OncarNativeSession.Worker(changed)){session(w,2,initialTemperature);}
            passed=true;
        }catch(Exception error){event("CONTROL_FAIL type="+error.getClass().getSimpleName()+" reason="+(error instanceof IllegalStateException?error.getMessage():"see stage"));}
        finally{
            if(changedAttempted&&original!=null)try(OncarNativeSession.Worker w=new OncarNativeSession.Worker(original)){
                OncarGeneratedPairSession.bootstrap(w,"original_restore");OncarGeneratedPairSession.control(w,"original_restore");restored=true;
            }catch(Exception error){event("ORIGINAL_RESTORE_FAILED type="+error.getClass().getSimpleName());}
            try{
                if(OncarGeneratedPairSession.paused)OncarNativeSession.gate(4);
                long end=SystemClock.elapsedRealtime()+40000;
                while(OncarNativeSession.tcp()!=1&&SystemClock.elapsedRealtime()<end)Thread.sleep(500);
                stock=OncarNativeSession.tcp()==1;if(stock)Files.deleteIfExists(Paths.get(dir,"armed"));
                Files.deleteIfExists(Paths.get(dir,"identity.txt"));
            }catch(Exception error){event("STOCK_RESTORE_FAILED type="+error.getClass().getSimpleName());}
            event("FINAL pass="+passed+" original_restored="+restored+" stock_restored="+stock+" native_writes="+writes);
        }
        System.exit(passed&&restored&&stock?0:1);
    }
    public static void main(String[] args)throws Exception{
        need(args.length==1||(args.length==2&&args[1].equals("--preflight")),"directory and optional preflight required");OncarNativeSession.dir=args[0];OncarNativeSession.exempt();
        finishBy=SystemClock.elapsedRealtime()+480000;
        Thread bound=new Thread(()->{try{Thread.sleep(480000);}catch(Exception ignored){}event("HARD_DEADLINE");System.exit(124);},"control-deadline");bound.setDaemon(true);bound.start();
        try{Looper.prepareMainLooper();}catch(IllegalStateException ignored){}
        Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
        context=(Context)at.getMethod("getSystemContext").invoke(thread);
        auto=context.getSystemService("auto");need(auto!=null,"SDK manager unavailable");
        setBuffer=auto.getClass().getMethod("setBuffer",int.class,int.class,byte[].class);
        yunDevice=Class.forName("android.hardware.bydauto.yun.BYDAutoYunDevice").getMethod("getInstance",Context.class).invoke(null,context);
        if(args.length==2){
            new Thread(()->{
                try(Source source=new Source()){
                    environment();long end=SystemClock.elapsedRealtime()+5000;int count=0;
                    while(SystemClock.elapsedRealtime()<end){Row row=source.queue.poll(100,TimeUnit.MILLISECONDS);if(row!=null){source.callbacks++;count++;}}
                    need(!source.failed&&count>100,"passive SDK preflight");
                    event("PREFLIGHT_PASS callbacks="+count+" temperature="+temperature()+" tcp="+OncarNativeSession.tcp()+" writes="+writes);
                }catch(Exception error){event("PREFLIGHT_FAIL type="+error.getClass().getSimpleName()+" reason="+error.getMessage());System.exit(1);}
                System.exit(0);
            },"passive-control-preflight").start();Looper.loop();return;
        }
        new Thread(()->run(args[0]),"bounded-native-control").start();Looper.loop();
    }
}
