package dev.denza.tools.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Injected resolver tests: no DNS packet, cloud call or vehicle access. */
public final class CloudDnsResolverTest {
    private static final String HOST="dilinkaddr-cn.denzacloud.com";
    private static void need(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static InetAddress ipv4(int last)throws Exception{return InetAddress.getByAddress(new byte[]{1,2,3,(byte)last});}
    private static void boundedAndCancelled()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean cancelled=new AtomicBoolean();AtomicReference<Throwable> result=new AtomicReference<>();
        CloudDnsResolver.Lookup stuck=host->{
            entered.countDown();
            for(;;)try{release.await();break;}catch(InterruptedException ignored){}
            return new InetAddress[]{ipv4(4)};
        };
        Thread caller=new Thread(()->{try{CloudDnsResolver.resolve(HOST,cancelled::get,stuck);}
            catch(Throwable error){result.set(error);}});
        caller.start();need(entered.await(1,TimeUnit.SECONDS),"resolver did not start");
        long at=System.nanoTime();cancelled.set(true);caller.join(1000);
        need(!caller.isAlive()&&result.get() instanceof CancellationException&&
            System.nanoTime()-at<TimeUnit.SECONDS.toNanos(1),"STOP waited on system DNS");
        try{CloudDnsResolver.resolve(HOST,()->false,host->new InetAddress[]{ipv4(5)});
            throw new AssertionError("second resolver thread accumulated");}
        catch(IOException expected){need(expected.getMessage().equals("native_dns_busy"),"wrong busy result");}
        release.countDown();
        boolean recovered=false;
        for(int i=0;i<100;i++)try{
            byte[][] addresses=CloudDnsResolver.resolve(HOST,()->false,host->new InetAddress[]{ipv4(5)});
            need(addresses.length==1&&addresses[0][3]==5,"actual DNS bytes lost");recovered=true;break;
        }catch(IOException busy){need(busy.getMessage().equals("native_dns_busy"),"unexpected DNS error");Thread.sleep(10);}
        need(recovered,"resolver slot not released by finished system call");
    }
    private static void timeoutAndShape()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        long at=System.nanoTime();
        try{CloudDnsResolver.resolve(HOST,()->false,host->{entered.countDown();
            for(;;)try{release.await();break;}catch(InterruptedException ignored){}
            return new InetAddress[]{ipv4(4)};});throw new AssertionError("resolver hung");}
        catch(CloudSessionLoop.NetworkFailure expected){}
        need(entered.getCount()==0&&System.nanoTime()-at<TimeUnit.SECONDS.toNanos(7),"DNS deadline exceeded");
        release.countDown();
        boolean recovered=false;
        for(int i=0;i<100;i++)try{
            byte[][] ordered=CloudDnsResolver.resolve(HOST,()->false,host->new InetAddress[]{ipv4(4),ipv4(5)});
            need(ordered.length==2&&ordered[0][3]==4&&ordered[1][3]==5,"resolver order changed");recovered=true;break;
        }catch(IOException busy){need(busy.getMessage().equals("native_dns_busy"),"unexpected DNS error");Thread.sleep(10);}
        need(recovered,"resolver slot not released after timeout");
        try{CloudDnsResolver.resolve("example.invalid",()->false,host->{throw new AssertionError("untrusted hostname resolved");});
            throw new AssertionError("untrusted hostname admitted");}
        catch(IOException expected){}
        try{CloudDnsResolver.resolve(HOST,()->false,host->new InetAddress[]{InetAddress.getByAddress(new byte[16])});
            throw new AssertionError("IPv6 silently truncated");}
        catch(CloudNativePipe.ProtocolFailure expected){}
        need(CloudDnsResolver.resolve(HOST,()->false,host->new InetAddress[0]).length==0,"empty resolver result changed");
    }
    public static void main(String[] args)throws Exception{
        boundedAndCancelled();timeoutAndShape();
        System.out.println("PASS native DNS cancellation, slot, timeout and address-shape cases=2");
    }
}
