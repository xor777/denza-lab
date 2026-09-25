package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Host Unix socket check for the guardian's accept-loop shutdown handshake. */
public final class CloudGuardianAcceptTest {
    private static void need(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        if(args.length==2 && args[0].equals("control-exit-child")){
            Thread survivor=new Thread(()->{
                try{Thread.sleep(10_000);}catch(InterruptedException ignored){}
            },"simulated-android-runtime-thread");
            survivor.setDaemon(false);survivor.start();
            CloudNativeMain.main(new String[]{"control",args[1],"0123456789abcdef0123456789abcdef"});
            throw new AssertionError("control entry point returned without exiting");
        }
        Path directory=Files.createTempDirectory("guardian-accept-");
        Path endpoint=directory.resolve("owner.sock");
        try(ServerSocketChannel server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)){
            UnixDomainSocketAddress address=UnixDomainSocketAddress.of(endpoint);
            server.bind(address);
            CountDownLatch secondAccept=new CountDownLatch(1);
            AtomicInteger calls=new AtomicInteger(),handled=new AtomicInteger();
            AtomicBoolean shutdown=new AtomicBoolean(),failed=new AtomicBoolean();
            Thread owner=new Thread(()->{
                try{
                    CloudNativeGuardian.acceptUntilShutdown(shutdown::get,()->{
                        if(calls.incrementAndGet()==2)secondAccept.countDown();
                        return server.accept();
                    },client->{handled.incrementAndGet();client.close();});
                }catch(IOException error){failed.set(true);}
            },"guardian-accept-test");
            owner.setDaemon(true);owner.start();
            try(SocketChannel ordinary=SocketChannel.open(address)){}
            need(secondAccept.await(2,TimeUnit.SECONDS),"accept did not block again");
            need(handled.get()==1,"ordinary client was not served");
            shutdown.set(true);
            // A real Unix connection wakes accept; the post-accept check must
            // close this peer without starting another handler.
            try(SocketChannel wake=SocketChannel.open(address)){}
            owner.join(2000);
            need(!owner.isAlive()&&!failed.get(),"owner did not exit after the wake connection");
            need(handled.get()==1,"wake connection was served after shutdown");
            System.out.println("PASS guardian Unix accept wake exits without serving wake client");
        }finally{
            Files.deleteIfExists(endpoint);Files.deleteIfExists(directory);
        }
        controlEntryExitsWithRuntimeThread();
    }
    private static void controlEntryExitsWithRuntimeThread()throws Exception{
        Path directory=Files.createTempDirectory("control-exit-");
        Path marker=directory.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker,"{\"protocol\":2,\"install_id\":\"0123456789abcdef0123456789abcdef\","
            +"\"generation\":1,\"desired\":\"off\"}");
        try{
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),CloudGuardianAcceptTest.class.getName(),
                "control-exit-child",marker.toString()).start();
            child.getOutputStream().close();
            try{
                need(child.waitFor(3,TimeUnit.SECONDS),"control app_process entry survived bridge EOF");
                need(child.exitValue()==0,"control app_process entry failed: "
                    +new String(child.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));
                need(new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8)
                    .contains(":READY\n"),"control bridge did not serve READY");
            }finally{child.destroyForcibly();}
        }finally{Files.deleteIfExists(marker);Files.deleteIfExists(marker.getParent());
            Files.deleteIfExists(marker.getParent().getParent());
            Files.deleteIfExists(marker.getParent().getParent().getParent());
            Files.deleteIfExists(marker.getParent().getParent().getParent().getParent());
            Files.deleteIfExists(marker.getParent().getParent().getParent().getParent().getParent());
            Files.deleteIfExists(directory);}
        System.out.println("PASS control entry exits after EOF with non-daemon runtime thread");
    }
}
