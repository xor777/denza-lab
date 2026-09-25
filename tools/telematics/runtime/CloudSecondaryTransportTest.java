package dev.denza.tools.runtime;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** No network: actual byte-count and cancellation semantics use injected channels. */
public final class CloudSecondaryTransportTest {
    private static final String HOST="secondary.denzacloud.com";
    private static void need(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    private static final class FakeChannel implements CloudSecondaryTransport.Channel {
        final AtomicInteger writes=new AtomicInteger();
        volatile boolean closed;
        volatile CountDownLatch blockWrite;
        public int read(byte[] destination,int timeoutMs){
            byte[] value="reply".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(value,0,destination,0,value.length);return value.length;
        }
        public int write(byte[] bytes)throws Exception{
            writes.incrementAndGet();
            CountDownLatch pending=blockWrite;
            if(pending!=null)pending.await();
            return bytes.length;
        }
        public void close(){closed=true;CountDownLatch pending=blockWrite;if(pending!=null)pending.countDown();}
    }
    private static void opaqueBytesAndExactEndpoint()throws Exception{
        FakeChannel backend=new FakeChannel();AtomicInteger connects=new AtomicInteger();
        try(CloudSecondaryTransport channel=new CloudSecondaryTransport(
            (host,port)->{if(!HOST.equals(host)||port!=6022)throw new IOException("endpoint_not_authenticated");},
            (host,selected,port,owner)->{connects.incrementAndGet();return backend;})){
            channel.connect(HOST,6022,1000);
            need(channel.write("opaque".getBytes(StandardCharsets.US_ASCII),1000)==6,"write count");
            need(new String(channel.read(16,1000),StandardCharsets.US_ASCII).equals("reply"),"read bytes");
            need(connects.get()==1&&backend.writes.get()==1,"unexpected replay");
        }
        need(backend.closed,"TLS handle survived close");
        try(CloudSecondaryTransport refused=new CloudSecondaryTransport(
            (host,port)->{throw new IOException("endpoint_not_authenticated");},
            (host,selected,port,owner)->{throw new AssertionError("unauthorized connector reached");})){
            try{refused.connect(HOST,6022,1000);throw new AssertionError("endpoint admitted");}
            catch(IOException expected){}
        }
        try(CloudSecondaryTransport refused=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->{throw new AssertionError("raw endpoint reached");})){
            try{refused.connect("192.0.2.1",6022,1000);throw new AssertionError("IP endpoint admitted");}
            catch(IOException expected){}
        }
    }
    private static void cancellationAndNoUncertainWriteReplay()throws Exception{
        FakeChannel backend=new FakeChannel();backend.blockWrite=new CountDownLatch(1);
        CloudSecondaryTransport channel=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->backend);
        channel.connect(HOST,6022,1000);
        AtomicReference<Throwable> failed=new AtomicReference<>();
        Thread send=new Thread(()->{
            try{channel.write(new byte[]{1,2,3},1000);}
            catch(Throwable error){failed.set(error);}
        });
        send.start();
        for(int i=0;i<100&&backend.writes.get()==0;i++)Thread.sleep(10);
        need(backend.writes.get()==1,"write did not enter backend");
        channel.close();send.join(2000);
        need(!send.isAlive()&&failed.get() instanceof IOException,"cancelled write reported success");
        need(backend.writes.get()==1,"ambiguous bytes replayed");
    }
    private static void blockedConnectOwnsSocketAndSlot()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);
        AtomicReference<Throwable> failed=new AtomicReference<>();
        FakeChannel late=new FakeChannel();
        CloudSecondaryTransport first=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->{
                Socket raw=new Socket();owner.own(raw);entered.countDown();
                while(!raw.isClosed())Thread.sleep(5);
                return late;
            });
        Thread connect=new Thread(()->{
            try{first.connect(HOST,6022,1000);}
            catch(Throwable error){failed.set(error);}
        });
        connect.start();need(entered.await(2,TimeUnit.SECONDS),"connect did not publish socket");
        first.close();connect.join(2000);
        need(!connect.isAlive()&&failed.get() instanceof IOException,"late connect became active");
        need(late.closed,"late TLS result was retained");
        try(CloudSecondaryTransport second=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->new FakeChannel())){
            second.connect(HOST,6022,1000);
        }
    }
    private static void timeoutIsNotAZeroByteReply()throws Exception{
        CloudSecondaryTransport.Channel timeout=new CloudSecondaryTransport.Channel(){
            public int read(byte[] bytes,int ms)throws Exception{throw new SocketTimeoutException("idle");}
            public int write(byte[] bytes){return bytes.length;}
            public void close(){}
        };
        try(CloudSecondaryTransport channel=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->timeout)){
            channel.connect(HOST,6022,1000);
            try{channel.read(16,1000);throw new AssertionError("timeout converted to data");}
            catch(SocketTimeoutException expected){}
            need(channel.write(new byte[]{1},1000)==1,"idle timeout poisoned stream");
        }
    }
    private static void unjoinedConnectorBlocksReplacement()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<Throwable> firstResult=new AtomicReference<>();
        CloudSecondaryTransport first=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->{
                entered.countDown();
                for(;;)try{release.await();break;}catch(InterruptedException ignored){}
                return new FakeChannel();
            });
        Thread starting=new Thread(()->{
            try{first.connect(HOST,6022,10000);}
            catch(Throwable error){firstResult.set(error);}
        });
        starting.start();need(entered.await(2,TimeUnit.SECONDS),"stalled connector not entered");
        try{first.close();throw new AssertionError("unjoined connector accepted cleanup");}
        catch(IOException expected){}
        CloudSecondaryTransport second=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->new FakeChannel());
        try{second.connect(HOST,6022,1000);throw new AssertionError("orphan slot reused");}
        catch(SocketTimeoutException expected){}
        finally{second.close();}
        release.countDown();first.close();starting.join(2000);
        need(!starting.isAlive()&&firstResult.get() instanceof IOException,"stale connector survived");
        try(CloudSecondaryTransport third=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->new FakeChannel())){
            third.connect(HOST,6022,1000);
        }
    }
    private static void rawCloseUnblocksTlsClose()throws Exception{
        Socket raw=new Socket();
        CloudSecondaryTransport.Channel tls=new CloudSecondaryTransport.Channel(){
            public int read(byte[] bytes,int timeout){return -1;}
            public int write(byte[] bytes){return bytes.length;}
            public void close()throws Exception{while(!raw.isClosed())Thread.sleep(5);}
        };
        CloudSecondaryTransport channel=new CloudSecondaryTransport((host,port)->{},
            (host,selected,port,owner)->{owner.own(raw);return tls;});
        channel.connect(HOST,6022,1000);
        channel.close();need(raw.isClosed(),"TLS close ran before raw cancellation");
    }
    public static void main(String[]args)throws Exception{
        opaqueBytesAndExactEndpoint();cancellationAndNoUncertainWriteReplay();
        blockedConnectOwnsSocketAndSlot();timeoutIsNotAZeroByteReply();
        unjoinedConnectorBlocksReplacement();
        rawCloseUnblocksTlsClose();
        System.out.println("PASS secondary mutual-TLS opaque stream, endpoint, cancellation cases=6");
    }
}
