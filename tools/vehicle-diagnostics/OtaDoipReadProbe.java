import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** One fixed TCP route, routing activation and only mapped 22 identity DIDs. */
public final class OtaDoipReadProbe {
    static volatile Socket socket;
    static long end;
    static IBinder auto;
    static java.io.PrintStream protocol;
    static String prop(String key) throws Exception {
        return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key);
    }
    static int word(int transaction,int device,int fid) throws Exception {
        Parcel a=Parcel.obtain(),b=Parcel.obtain();
        try {
            a.writeInterfaceToken(auto.getInterfaceDescriptor());a.writeInt(device);a.writeInt(fid);
            OtaDoipWire.require(auto.transact(transaction,a,b,0),"guard_transaction");b.readException();
            OtaDoipWire.require(b.dataAvail()==4,"guard_shape");return b.readInt();
        } finally {a.recycle();b.recycle();}
    }
    static void guard() throws Exception {
        OtaDoipWire.require("170".equals(prop("persist.sys.AutoType")) && "CANFD".equals(prop("sys.car.protocol")) &&
                "r@-1_0&w@-1_0".equals(prop("sys.diagmgrsrv.task_bk")),"profile_or_manager");
        OtaDoipWire.require(word(5,1011,555745336)==1 && word(5,1011,89129008)==1 &&
                Float.intBitsToFloat(word(7,1013,-1807745016))==0f,"not_stationary");
    }
    static String endpoint(String ip,int port) {
        String[] b=ip.split("\\.");StringBuilder s=new StringBuilder();
        for(int i=3;i>=0;i--)s.append(String.format(Locale.ROOT,"%02X",Integer.parseInt(b[i])));
        return s+String.format(Locale.ROOT,":%04X",port);
    }
    static void noOtherSession(OtaDoipPlan.Group group) throws Exception {
        for(String path:new String[]{"/proc/net/tcp","/proc/net/tcp6"}) {
            for(String line:Files.readAllLines(Paths.get(path))) {
                String[] f=line.trim().split("\\s+");if(f.length<4 || f[0].equals("sl"))continue;
                String local=f[1].toUpperCase(Locale.ROOT),remote=f[2].toUpperCase(Locale.ROOT),state=f[3];
                if(state.equals("06") || state.equals("0A"))continue;
                boolean own=socket!=null && socket.isConnected() && local.endsWith(endpoint("192.168.195.2",socket.getLocalPort())) &&
                        remote.endsWith(endpoint(group.ip,13400));
                OtaDoipWire.require(own || (!local.endsWith(":3458") && !remote.endsWith(":3458")),"other_doip_session");
            }
        }
    }
    static int remaining(long limit) throws Exception {
        long ms=(Math.min(limit,end)-System.nanoTime())/1000000L;
        OtaDoipWire.require(ms>0,"deadline");return (int)Math.min(ms,2500);
    }
    static byte[] bytes(InputStream in,int count,long limit) throws Exception {
        byte[] data=new byte[count];int offset=0;
        while(offset<count) {
            socket.setSoTimeout(remaining(limit));int n=in.read(data,offset,count-offset);
            OtaDoipWire.require(n>0,"receive_eof");offset+=n;
        }
        return data;
    }
    static byte[] receive(InputStream in,int[] type,long limit) throws Exception {
        int[] header=OtaDoipWire.header(bytes(in,8,limit));type[0]=header[0];return bytes(in,header[1],limit);
    }
    static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02X",b&255));return out.toString();
    }
    static void send(byte[] frame,OtaDoipPlan.Group group,JSONArray sent) throws Exception {
        guard();noOtherSession(group);remaining(end);
        socket.getOutputStream().write(frame);socket.getOutputStream().flush();sent.put(hex(frame));
    }
    static String failure(Throwable error) {
        if(error instanceof OtaDoipWire.Refused)return ((OtaDoipWire.Refused)error).code;
        if(error instanceof SocketTimeoutException)return "socket_timeout";
        if(error instanceof ConnectException)return "connect_refused";
        if(error instanceof BindException)return "bind_failed";
        if(error instanceof NoRouteToHostException)return "route_unavailable";
        return "operation_failed";
    }
    public static void run(int groupIndex,String[] args) {
        protocol=System.out;
        System.setOut(new java.io.PrintStream(new OutputStream(){public void write(int b){}}));System.setErr(System.out);
        Thread deadline=new Thread(()->{try{Thread.sleep(20000);}catch(InterruptedException ignored){} Runtime.getRuntime().halt(91);});
        deadline.setDaemon(true);deadline.start();end=System.nanoTime()+18000000000L;
        JSONObject result=new JSONObject();JSONArray sent=new JSONArray(),exchanges=new JSONArray();boolean closed=false;
        try(RandomAccessFile file=new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock","rw");FileLock lock=file.getChannel().tryLock()) {
            OtaDoipWire.require(Process.myUid()==2000 && args.length==0 && lock!=null,"scope_or_lock");
            OtaDoipPlan.Group group=OtaDoipPlan.GROUPS[groupIndex];
            result.put("group",group.name);result.put("ip",group.ip);result.put("target",String.format(Locale.ROOT,"%04X",group.target));
            auto=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"autoservice");
            guard();noOtherSession(group);Thread.sleep(1000);guard();noOtherSession(group);
            try {
                socket=new Socket();socket.bind(new InetSocketAddress("192.168.195.2",0));
                socket.connect(new InetSocketAddress(group.ip,13400),Math.min(1800,remaining(end)));
                send(OtaDoipWire.routing(),group,sent);
                int[] type=new int[1];InputStream input=socket.getInputStream();
                byte[] routing=receive(input,type,System.nanoTime()+2500000000L);
                int server=OtaDoipWire.routingResponse(type[0],routing);
                result.put("routingAccepted",true);result.put("routingServer",String.format(Locale.ROOT,"%04X",server));
                for(int did:group.dids) {
                    Thread.sleep(300);
                    JSONObject exchange=new JSONObject();exchange.put("did",String.format(Locale.ROOT,"%04X",did));exchanges.put(exchange);
                    send(OtaDoipWire.request(group.target,did),group,sent);
                    boolean ack=false,terminal=false;long requestEnd=System.nanoTime()+2500000000L;
                    for(int frame=0;frame<4;frame++) {
                        byte[] body=receive(input,type,requestEnd);
                        String kind=OtaDoipWire.response(type[0],body,group.target,did);
                        if(kind.equals("ack")){OtaDoipWire.require(!ack,"duplicate_ack");ack=true;continue;}
                        if(kind.equals("pending")){exchange.put("responsePending",true);continue;}
                        exchange.put("ack",ack);exchange.put("outcome",kind);exchange.put("uds",hex(Arrays.copyOfRange(body,4,body.length)));terminal=true;
                        if(kind.equals("negative"))throw new OtaDoipWire.Refused("uds_negative");
                        break;
                    }
                    OtaDoipWire.require(terminal,"response_frame_limit");
                }
                result.put("outcome","complete");
            } finally {
                if(socket!=null)socket.close();closed=socket==null || socket.isClosed();
                // The lock is released only after our TCP connection is closed.
            }
        } catch(Throwable error) {
            try{result.put("outcome","stopped");result.put("error",failure(error));}catch(Exception ignored){}
        } finally {
            try {
                if(socket!=null)socket.close();closed=socket==null || socket.isClosed();
                result.put("socketClosed",closed);result.put("sent",sent);result.put("exchanges",exchanges);
                protocol.println("DENZA_OTA_DOIP_READ:"+result);protocol.flush();
            } catch(Throwable ignored){}
        }
        System.exit(closed?0:2);
    }
}
