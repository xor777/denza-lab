package dev.denza.tools.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Android default-network DNS for the original native resolver; never supplies an address fallback. */
final class CloudDnsResolver {
    interface Lookup { InetAddress[] lookup(String host)throws Exception; }
    private static final Semaphore SLOT=new Semaphore(1);
    private static final long DEADLINE_NS=TimeUnit.SECONDS.toNanos(5);
    private CloudDnsResolver(){}

    static byte[][] resolve(String host,BooleanSupplier cancelled)throws Exception{
        return resolve(host,cancelled,InetAddress::getAllByName);
    }
    static byte[][] resolve(String host,BooleanSupplier cancelled,Lookup lookup)throws Exception{
        requirePublicHost(host);
        if(cancelled.getAsBoolean())throw new CancellationException("owner_stopped");
        if(!SLOT.tryAcquire())throw new IOException("native_dns_busy");
        FutureTask<InetAddress[]> task=new FutureTask<>(()->lookup.lookup(host));
        Thread worker=new Thread(()->{try{task.run();}finally{SLOT.release();}},"cloud-native-dns");
        worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException|Error failure){SLOT.release();throw failure;}
        long deadline=System.nanoTime()+DEADLINE_NS;
        try{
            for(;;){
                if(cancelled.getAsBoolean())throw new CancellationException("owner_stopped");
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new CloudSessionLoop.NetworkFailure();
                try{
                    InetAddress[] addresses=task.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);
                    if(cancelled.getAsBoolean())throw new CancellationException("owner_stopped");
                    if(addresses==null)throw new CloudSessionLoop.NetworkFailure();
                    // getAllByName returns both families and may return more
                    // records than the original IPv4 hostent can hold. Keep
                    // supported answers in resolver order; this is a normal
                    // network result, not evidence of a broken native ABI.
                    List<byte[]> ipv4=new ArrayList<>(4);
                    for(InetAddress address:addresses){
                        if(address==null)continue;
                        byte[] raw=address.getAddress();
                        if(raw.length!=4)continue;
                        if(ipv4.stream().noneMatch(value->Arrays.equals(value,raw)))ipv4.add(raw.clone());
                        if(ipv4.size()==4)break;
                    }
                    if(ipv4.isEmpty())throw new CloudSessionLoop.NetworkFailure();
                    return ipv4.toArray(new byte[0][]);
                }catch(TimeoutException pending){/* Poll STOP while the system resolver is blocked. */}
            }
        }catch(ExecutionException failed){
            Throwable cause=failed.getCause();
            throw new CloudSessionLoop.NetworkFailure();
        }finally{if(!task.isDone())task.cancel(true);}
    }
    static void requirePublicHost(String host)throws IOException{
        if(host==null||host.length()<16||host.length()>253||!host.endsWith(".denzacloud.com"))
            throw new CloudNativePipe.ProtocolFailure();
        for(String label:host.split("\\.",-1)){
            if(label.length()<1||label.length()>63||label.charAt(0)=='-'||
               label.charAt(label.length()-1)=='-'||!label.matches("[a-z0-9-]+"))
                throw new CloudNativePipe.ProtocolFailure();
        }
    }
}
