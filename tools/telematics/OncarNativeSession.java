package dev.denza.tools;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.net.ssl.SSLSocket;

/** Bounded on-car experiment. All vehicle protocol work belongs to native worker. */
public final class OncarNativeSession {
    static String dir;
    static int signatureTotal;
    static int statusWaitMs = 60000;
    static void need(boolean b,String why){OncarTls.need(b,why);}
    static void event(String s){System.out.println("t_ms="+System.currentTimeMillis()+" "+s);System.out.flush();}
    static String prop(String key)throws Exception{return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key);}
    static void exempt()throws Exception{Class<?> v=Class.forName("dalvik.system.VMRuntime");Object r=v.getMethod("getRuntime").invoke(null);v.getMethod("setHiddenApiExemptions",String[].class).invoke(r,(Object)new String[]{"L"});}
    static Parcel query(int transaction,int device,int fid)throws Exception{
        IBinder service=OncarTls.service("autoservice");Parcel q=Parcel.obtain(),r=Parcel.obtain();
        try{q.writeInterfaceToken(service.getInterfaceDescriptor());q.writeInt(device);q.writeInt(fid);need(service.transact(transaction,q,r,0),"vehicle getter transaction");need(r.readInt()==0,"vehicle getter status");return r;}
        catch(Exception|Error error){r.recycle();throw error;}finally{q.recycle();}
    }
    static byte[] buffer(int device,int fid,int count)throws Exception{Parcel r=query(13,device,fid);try{byte[] b=r.createByteArray();need(b!=null&&b.length==count,"vehicle buffer length");return b;}finally{r.recycle();}}
    static int integer(int device,int fid)throws Exception{Parcel r=query(5,device,fid);try{return r.readInt();}finally{r.recycle();}}
    static float soc()throws Exception{Parcel r=query(7,1014,0x4a505038);try{return r.readFloat();}finally{r.recycle();}}
    static int tcp()throws Exception{IBinder b=OncarTls.service("cloudmanager");Parcel q=Parcel.obtain(),r=Parcel.obtain();try{q.writeInterfaceToken(b.getInterfaceDescriptor());need(b.transact(7,q,r,0),"TCP query");need(r.readInt()==0,"TCP status");int value=r.readInt();need(value==0||value==1,"TCP value");return value;}finally{q.recycle();r.recycle();}}
    static void gate(int value)throws Exception{need(value==-5||value==4,"gate value");IBinder b=OncarTls.service("cloudmanager");Parcel q=Parcel.obtain(),r=Parcel.obtain();try{q.writeInterfaceToken(b.getInterfaceDescriptor());q.writeInt(value);need(b.transact(1,q,r,0),"cloud notification");}finally{q.recycle();r.recycle();}}
    static byte[] le(int v){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();}
    static byte[] nonce(){byte[] b=new byte[16];new SecureRandom().nextBytes(b);return b;}
    static final class Inputs {
        final byte[] vin,parameters,serial,iccid,imsi;
        Inputs(Inputs original, byte[] suppliedIccid, byte[] suppliedImsi) {
            vin=original.vin;parameters=original.parameters;serial=original.serial;
            need(suppliedIccid.length==20&&suppliedImsi.length==15,"pair dimensions");
            iccid=suppliedIccid.clone();imsi=suppliedImsi.clone();
        }
        Inputs()throws Exception{
            List<String> lines=Files.readAllLines(Paths.get(dir,"identity.txt"));need(lines.size()==2&&lines.get(0).matches("[0-9]{20}")&&lines.get(1).matches("[0-9]{15}"),"provided original pair missing");
            iccid=lines.get(0).getBytes("US-ASCII");imsi=lines.get(1).getBytes("US-ASCII");Files.delete(Paths.get(dir,"identity.txt"));
            vin=buffer(1001,0x9900021a,17);need(OncarTls.hex(MessageDigest.getInstance("SHA-256").digest(vin)).equals("102d46beb9421745e31923bdceb59c3170869bfbe9ff0c2cca69c64728664938"),"owner VIN");
            parameters=buffer(1034,0x99000005,33);need(parameters[0]!=0,"cloud parameters not ready");serial=prop("debug.ro.serialno").getBytes("US-ASCII");need(serial.length<=91,"serial bound");
            event("IDENTITY source=provided_pair runtime_modem_identity_reads=0");
        }
    }
    static final class Worker implements AutoCloseable {
        final Process process;final BufferedReader reader;final BufferedWriter writer;
        Worker(Inputs x)throws Exception{
            process=new ProcessBuilder(dir+"/native-session-worker").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            BufferedReader openedReader=null;BufferedWriter openedWriter=null;
            try{
                openedReader=new BufferedReader(new InputStreamReader(process.getInputStream(),"US-ASCII"));
                openedWriter=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),"US-ASCII"));
                reader=openedReader;writer=openedWriter;need("READY".equals(reader.readLine()),"worker startup");
                set("V",x.vin);set("K",Arrays.copyOfRange(x.parameters,1,17));set("U",Arrays.copyOfRange(x.parameters,17,33));set("C",x.iccid);set("M",x.imsi);set("S",x.serial);set("T",le((int)(System.currentTimeMillis()/1000)));set("N",nonce());need(exchange("START").equals("OK"),"worker identity preparation");
            }catch(Exception|Error error){
                try{stop(process,openedReader,openedWriter);}catch(Exception|Error cleanup){error.addSuppressed(cleanup);}
                throw error;
            }
        }
        String exchange(String s)throws Exception{need(s.length()<2100,"IPC bound");writer.write(s);writer.newLine();writer.flush();String reply=reader.readLine();need(reply!=null&&reply.length()<=2100&&!reply.startsWith("{"),"native worker rejected");return reply;}
        void set(String key,byte[] value)throws Exception{need(exchange(key+" "+OncarTls.hex(value)).equals("OK"),"native input rejected");}
        byte[] produce(String command)throws Exception{set("T",le((int)(System.currentTimeMillis()/1000)));set("N",nonce());String s=exchange(command);need(s.startsWith("WIRE "),"native producer rejected");return OncarTls.unhex(s.substring(5));}
        private static void closePipe(Closeable pipe){if(pipe!=null)try{pipe.close();}catch(IOException ignored){}}
        private static void stop(Process process,BufferedReader reader,BufferedWriter writer){
            boolean interrupted=false;
            try{
                // No protocol exchange here: a worker stuck in native code cannot answer QUIT.
                process.destroy();
                try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted=true;}
                if(process.isAlive()){
                    process.destroyForcibly();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(process.isAlive()){
                        long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                        try{process.waitFor(remaining,TimeUnit.NANOSECONDS);}catch(InterruptedException error){interrupted=true;}
                    }
                }
                need(!process.isAlive(),"native worker did not terminate");
                // Closing a BufferedReader before exit can wait behind its blocked readLine.
                closePipe(reader);closePipe(writer);closePipe(process.getInputStream());
                closePipe(process.getOutputStream());closePipe(process.getErrorStream());
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
        public void close(){stop(process,reader,writer);}
    }
    static final class Row {final long when;final byte[] data;Row(byte[] d){when=SystemClock.elapsedRealtime();data=d;}}
    static final class Source implements AutoCloseable {
        final Process process;final Thread reader;final ArrayList<Row> rows=new ArrayList<>();final CountDownLatch ready=new CountDownLatch(1);volatile boolean unregistered,done,clean;volatile String failure;boolean closed;
        static ProcessBuilder builder(){ProcessBuilder pb=new ProcessBuilder("/system/bin/app_process","/system/bin","dev.denza.tools.CloudCanSnapshotProbe","40","512","--opaque");pb.environment().put("CLASSPATH",dir+"/adapter.jar");pb.redirectError(ProcessBuilder.Redirect.INHERIT);return pb;}
        Source()throws Exception{this(builder().start());}
        // Package-private process injection keeps host lifecycle tests on this exact class body.
        Source(Process child)throws Exception{
            process=child;Thread started=null;
            try{
                started=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(process.getInputStream(),"US-ASCII"))){String line;Pattern p=Pattern.compile("\\[CloudCanSnapshotProbe\\] OPAQUE seq=\\d+ t_ns=\\d+ bytes=(\\d+) raw=([0-9a-f]+)");while((line=in.readLine())!=null){if(line.contains("READY callback="))ready.countDown();if(line.contains("UNREGISTERED"))unregistered=true;if(line.contains("DONE ")){done=true;clean=line.contains("dropped=0 callback_errors=0 queued=0");}Matcher m=p.matcher(line);if(m.matches()){byte[] d=OncarTls.unhex(m.group(2));need(d.length==Integer.parseInt(m.group(1)),"callback length");synchronized(rows){need(rows.size()<5000,"callback cap");rows.add(new Row(d));}}}}catch(Exception e){failure=e.getClass().getSimpleName();}},"opaque-callback-reader");
                reader=started;reader.setDaemon(true);reader.start();need(ready.await(5,TimeUnit.SECONDS),"SDK reader readiness");
            }catch(Exception|Error error){
                if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                try{stop(process,started,false);}catch(Exception|Error cleanup){error.addSuppressed(cleanup);}
                throw error;
            }
        }
        private static void closePipe(Closeable pipe){if(pipe!=null)try{pipe.close();}catch(IOException ignored){}}
        private static void stop(Process child,Thread reader,boolean graceful)throws Exception{
            boolean interrupted=Thread.interrupted();
            try{
                if(graceful&&!interrupted&&child.isAlive()){
                    try{child.getOutputStream().write("STOP\n".getBytes("US-ASCII"));child.getOutputStream().flush();}catch(IOException ignored){}
                    try{child.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted=true;}
                }
                if(child.isAlive()){
                    child.destroy();try{child.waitFor(1,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted=true;}
                }
                if(child.isAlive()){
                    child.destroyForcibly();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(child.isAlive()){
                        long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                        try{child.waitFor(remaining,TimeUnit.NANOSECONDS);}catch(InterruptedException error){interrupted=true;}
                    }
                }
                need(!child.isAlive(),"SDK reader child did not terminate");
            }finally{
                closePipe(child.getOutputStream());
                // Let the reader consume the child's final UNREGISTERED/DONE lines.
                if(reader!=null&&!child.isAlive())try{reader.join(1000);}catch(InterruptedException error){interrupted=true;}
                closePipe(child.getInputStream());closePipe(child.getErrorStream());
                if(reader!=null&&reader.isAlive())try{reader.join(1000);}catch(InterruptedException error){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
        }
        void ingest(Worker w)throws Exception{ArrayList<Row> snapshot;synchronized(rows){snapshot=new ArrayList<>(rows);}need(failure==null&&!snapshot.isEmpty()&&SystemClock.elapsedRealtime()-snapshot.get(snapshot.size()-1).when<2000,"fresh SDK source unavailable");for(Row row:snapshot)w.set("I",row.data);event("CALLBACKS buffers="+snapshot.size()+" age_ms="+(SystemClock.elapsedRealtime()-snapshot.get(snapshot.size()-1).when));}
        public synchronized void close()throws Exception{
            if(!closed){stop(process,reader,true);closed=true;event("SOURCE_CLOSED unregistered="+unregistered+" clean="+(done&&clean&&failure==null));}
            need(unregistered&&done&&clean&&failure==null,"SDK reader shutdown incomplete");
        }
    }
    static byte[] frame(InputStream in)throws Exception{byte[] header=new byte[5];readFully(in,header,0,5);need(header[0]==(byte)254&&header[1]==(byte)254&&header[2]==3,"envelope header");int n=((header[3]&255)<<8)|(header[4]&255);need(n>=48&&n<=1019,"envelope bound");byte[] value=Arrays.copyOf(header,n+5);readFully(in,value,5,n);return value;}
    static void readFully(InputStream in,byte[] dst,int offset,int n)throws IOException{while(n>0){int got=in.read(dst,offset,n);if(got<=0)throw new EOFException();offset+=got;n-=got;}}
    static String bootstrap(Worker w,String producer,int command,String host,int port)throws Exception{
        try(SSLSocket socket=OncarTls.connect(host,port)){signatureTotal+=OncarTls.signatureCount();byte[] wire=w.produce(producer);socket.getOutputStream().write(wire);socket.getOutputStream().flush();String answer=w.exchange("R"+command+" "+OncarTls.hex(frame(socket.getInputStream())));event("BOOTSTRAP command="+command+" native_result="+answer);return answer;}
    }
    static void session(Worker w,int index,long deadline)throws Exception{
        try(SSLSocket socket=OncarTls.connect("dilinknat0-cn.denzacloud.com",6041)){
            signatureTotal+=OncarTls.signatureCount();socket.getOutputStream().write(w.produce("L"));socket.getOutputStream().flush();need(w.exchange("R220 "+OncarTls.hex(frame(socket.getInputStream()))).equals("LOGIN 1"),"native login rejected");need(tcp()==0,"stock session competed");event("SESSION_READY index="+index+" native_login=1");
            long remaining=deadline-SystemClock.elapsedRealtime()-7000;need(remaining>=5000,"experiment time remaining");socket.setSoTimeout((int)Math.min(statusWaitMs,remaining));byte[] request=frame(socket.getInputStream());need(tcp()==0,"stock session competed");try(Source source=new Source()){Thread.sleep(3000);source.ingest(w);int charge=integer(1009,0x34400018);need(charge>=0&&charge<=255,"charging getter");w.set("H",le(charge));float soc=soc();
            String[] reply=w.exchange("R511 "+OncarTls.hex(request)).split(" ");need(reply.length==3&&reply[0].equals("STATUS"),"native status reply missing");float actual=Integer.parseInt(reply[1])/10f;need(Float.isFinite(soc)&&soc>=0&&soc<=100&&Math.abs(actual-soc)<=0.2f,"SOC mismatch");byte[] wire=OncarTls.unhex(reply[2]);need(wire.length==181,"status frame length");socket.getOutputStream().write(wire);socket.getOutputStream().flush();event("STATUS_SENT index="+index+" incoming=511 bytes="+wire.length+" soc="+actual+" independent_soc="+soc);Thread.sleep(1000);}
        }
    }
    public static void main(String[] args){
        dir=args[0];boolean paused=false;boolean restored=false;long sessionDeadline=0;
        Thread deadline=new Thread(()->{try{Thread.sleep(105000);}catch(InterruptedException ignored){}event("HARD_DEADLINE");System.exit(124);},"bounded-lifetime");deadline.setDaemon(true);deadline.start();
        try{exempt();need(tcp()==1&&prop("persist.sys.byd.apn_type").equals("double_apn")&&prop("persist.sys.cloud.token_flag").equals("1"),"baseline");Inputs inputs=new Inputs();OncarTls.factoryIdentity();
            try(Worker w=new Worker(inputs)){
                if(args.length==2&&args[1].equals("--login-only")){
                    String prior=new String(Files.readAllBytes(Paths.get(dir,"prior-bootstrap.log")),"UTF-8");
                    need(prior.contains("command=211 native_result=REG 1")&&prior.contains("command=200 native_result=ENDPOINT dilinknat0-cn.denzacloud.com 6041"),"previous native discovery proof missing");event("BOOTSTRAP_REUSED from_prior_successful_run=true");
                }else{
                    String reg=bootstrap(w,"G",211,"dilinkreg-cn.denzacloud.com",6001);need(reg.equals("REG 1")||reg.equals("REG 2"),"registration rejected");
                    need(bootstrap(w,"D",200,"dilinkaddr-cn.denzacloud.com",6021).equals("ENDPOINT dilinknat0-cn.denzacloud.com 6041"),"discovery endpoint changed");
                }
                paused=true;gate(-5);long end=SystemClock.elapsedRealtime()+8000;while(tcp()!=0&&SystemClock.elapsedRealtime()<end)Thread.sleep(200);need(tcp()==0,"cloud did not pause");event("STOCK_PAUSED");sessionDeadline=SystemClock.elapsedRealtime()+80000;session(w,1,sessionDeadline);
            }
            event("RECONNECT starting_new_worker=true identity_source=provided_pair");
            try(Worker w=new Worker(inputs)){session(w,2,sessionDeadline);}
            event("PASS oncar_native_bidirectional_sessions=2 signatures="+signatureTotal);
        }catch(Exception e){event("FAIL type="+e.getClass().getSimpleName()+" reason="+(e instanceof IllegalStateException?e.getMessage():"see stage"));}
        finally{
            try{if(paused){gate(4);long end=SystemClock.elapsedRealtime()+30000;while(tcp()!=1&&SystemClock.elapsedRealtime()<end)Thread.sleep(500);restored=tcp()==1;}else restored=tcp()==1;event("RESTORED tcp="+(restored?1:0));if(restored)Files.deleteIfExists(Paths.get(dir,"armed"));Files.deleteIfExists(Paths.get(dir,"identity.txt"));}catch(Exception e){event("RESTORE_UNCONFIRMED type="+e.getClass().getSimpleName());}
        }
        System.exit(restored?0:1);
    }
}
