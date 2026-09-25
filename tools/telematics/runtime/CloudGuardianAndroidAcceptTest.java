package dev.denza.tools.runtime;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator-only app_process probe; never uses the production guardian socket. */
public final class CloudGuardianAndroidAcceptTest {
    private static void need(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        String socketName="denza.cloud.accept.probe."+android.os.Process.myPid();
        LocalSocketAddress address=new LocalSocketAddress(socketName,LocalSocketAddress.Namespace.ABSTRACT);
        try(LocalServerSocket server=new LocalServerSocket(socketName)){
            Semaphore clients=new Semaphore(4);
            AtomicBoolean shutdown=new AtomicBoolean(),flushed=new AtomicBoolean(),ownerDone=new AtomicBoolean();
            AtomicInteger accepts=new AtomicInteger(),handled=new AtomicInteger();
            AtomicReference<Throwable> failure=new AtomicReference<>();
            CountDownLatch secondAccept=new CountDownLatch(1),stopReady=new CountDownLatch(1);
            Thread owner=new Thread(()->{
                try{
                    CloudNativeGuardian.acceptUntilShutdown(shutdown::get,()->{
                        if(accepts.incrementAndGet()==2)secondAccept.countDown();
                        return server.accept();
                    },client->{
                        need(clients.tryAcquire(),"client capacity");
                        handled.incrementAndGet();
                        Thread handler=new Thread(()->{
                            try(LocalSocket incoming=client){
                                incoming.setSoTimeout(3000);
                                need(incoming.getInputStream().read()==1,"request byte");
                                need(secondAccept.await(2,TimeUnit.SECONDS),"second accept not blocked");
                                shutdown.set(true);stopReady.countDown();
                                Thread.sleep(200); // Make accept wake race the STOP reply.
                                incoming.getOutputStream().write("STOPPED\n".getBytes(StandardCharsets.US_ASCII));
                                incoming.getOutputStream().flush();flushed.set(true);
                            }catch(Throwable error){failure.compareAndSet(null,error);}
                            finally{clients.release();}
                        },"guardian-stop-probe-handler");
                        handler.setDaemon(true);handler.start();
                    });
                    server.close();
                    CloudNativeGuardian.awaitClientHandlers(clients);
                    ownerDone.set(true);
                }catch(Throwable error){failure.compareAndSet(null,error);}
            },"guardian-stop-probe-accept");
            owner.setDaemon(true);owner.start();
            try(LocalSocket control=new LocalSocket()){
                control.connect(address);control.setSoTimeout(3000);
                control.getOutputStream().write(1);control.getOutputStream().flush();
                need(stopReady.await(3,TimeUnit.SECONDS),"STOP cleanup did not complete");
                CloudNativeGuardian.wakeAccept(socketName);
                need("STOPPED".equals(CloudLocalControl.readLine(control.getInputStream(),16)),"STOP reply lost");
            }
            owner.join(5000);
            need(!owner.isAlive()&&ownerDone.get()&&flushed.get(),"owner exited before handler drained");
            need(handled.get()==1&&accepts.get()==2,"wake peer reached handler");
            need(failure.get()==null,"accept or STOP handler failed: "+failure.get());
            System.out.println("PASS Android abstract Unix accept wake and STOP reply drain");
        }
    }
}
