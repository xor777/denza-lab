package dev.denza.tools.runtime;

import dev.denza.tools.OncarTls;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One opaque mutual-TLS byte stream for the original secondary sender. */
final class CloudSecondaryTransport implements AutoCloseable {
    interface EndpointAuthorizer {
        /** Must match the host and port to an authenticated native endpoint. */
        void verify(String host,int port)throws Exception;
    }
    interface Channel extends AutoCloseable {
        int read(byte[] destination,int timeoutMs)throws Exception;
        /** Number of plaintext bytes consumed; an exception leaves completion unknown. */
        int write(byte[] bytes)throws Exception;
        @Override void close()throws Exception;
    }
    interface Connector {
        Channel connect(String host,java.net.InetAddress selected,int port,OncarTls.SocketOwner owner)throws Exception;
    }
    private static final Semaphore SLOT=new Semaphore(1);
    private static final int MAX_CHUNK=1024;
    private final EndpointAuthorizer authorizer;
    private final Connector connector;
    private final Object lock=new Object(),closeLock=new Object(),writeLock=new Object(),readLock=new Object();
    private final ArrayList<Socket> ownedSockets=new ArrayList<>();
    private Channel channel;
    private Thread opener,writer,closer;
    private Throwable openFailure;
    private Exception cleanupFailure;
    private boolean opening,closed,slotHeld;

    CloudSecondaryTransport(EndpointAuthorizer authorizer){
        this(authorizer,(host,selected,port,owner)->{
            javax.net.ssl.SSLSocket tls=OncarTls.connect(host,selected,port,owner);
            return new Channel(){
                public int read(byte[] destination,int timeoutMs)throws Exception{
                    tls.setSoTimeout(timeoutMs);
                    return tls.getInputStream().read(destination);
                }
                public int write(byte[] bytes)throws Exception{
                    tls.getOutputStream().write(bytes);tls.getOutputStream().flush();return bytes.length;
                }
                public void close()throws Exception{tls.close();}
            };
        });
    }
    CloudSecondaryTransport(EndpointAuthorizer authorizer,Connector connector){
        if(authorizer==null||connector==null)throw new IllegalArgumentException("secondary_boundary");
        this.authorizer=authorizer;this.connector=connector;
    }
    void connect(String host,int port,int deadlineMs)throws Exception{
        connect(host,null,port,deadlineMs);
    }
    void connect(String host,java.net.InetAddress selected,int port,int deadlineMs)throws Exception{
        CloudDnsResolver.requirePublicHost(host);
        if(port<1||port>65535||deadlineMs<1000||deadlineMs>30000)
            throw new IllegalArgumentException("secondary_connect_bound");
        authorizer.verify(host,port);
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(deadlineMs);
        synchronized(lock){if(closed||opening||channel!=null)throw new IOException("secondary_already_used");opening=true;}
        try{
            for(;;){
                synchronized(lock){if(closed)throw new IOException("secondary_cancelled");}
                long remaining=end-System.nanoTime();
                if(remaining<=0)throw new SocketTimeoutException("secondary_slot_timeout");
                if(SLOT.tryAcquire(Math.min(50,Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining))),
                    TimeUnit.MILLISECONDS))break;
            }
            synchronized(lock){slotHeld=true;if(closed)throw new IOException("secondary_cancelled");}
            Thread task=new Thread(()->{
                Channel fresh=null;Throwable failure=null;
                try{fresh=connector.connect(host,selected,port,this::ownSocket);}
                catch(Throwable error){failure=error;}
                synchronized(lock){
                    if(closed||!opening){if(fresh!=null)try{fresh.close();}catch(Exception cleanup){cleanupFailure=cleanup;}}
                    else if(failure==null&&fresh!=null)channel=fresh;
                    if(failure==null&&fresh==null)failure=new IOException("secondary_no_channel");
                    openFailure=failure;opening=false;lock.notifyAll();
                }
            },"cloud-secondary-connect");
            task.setDaemon(true);
            synchronized(lock){opener=task;if(closed)throw new IOException("secondary_cancelled");task.start();}
            synchronized(lock){
                while(opening&&!closed){
                    long remaining=end-System.nanoTime();
                    if(remaining<=0)throw new SocketTimeoutException("secondary_connect_deadline");
                    lock.wait(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
                }
                if(closed)throw new IOException("secondary_cancelled");
                if(openFailure instanceof Exception)throw (Exception)openFailure;
                if(openFailure!=null)throw new IOException("secondary_connect_failed",openFailure);
                if(channel==null)throw new IOException("secondary_no_channel");
            }
        }catch(Exception failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private void ownSocket(Socket socket)throws Exception{
        if(socket==null)throw new IOException("secondary_null_socket");
        synchronized(lock){
            if(closed){socket.close();throw new IOException("secondary_cancelled");}
            ownedSockets.add(socket);
        }
    }
    private Channel active()throws IOException{
        synchronized(lock){if(closed||channel==null)throw new IOException("secondary_closed");return channel;}
    }
    byte[] read(int maximum,int timeoutMs)throws Exception{
        if(maximum<1||maximum>MAX_CHUNK||timeoutMs<1||timeoutMs>10000)
            throw new IllegalArgumentException("secondary_read_bound");
        synchronized(readLock){
            Channel current=active();byte[] block=new byte[maximum];
            try{
                int count=current.read(block,timeoutMs);
                synchronized(lock){if(closed||channel!=current)throw new IOException("secondary_stale_read");}
                if(count==-1)return null;
                if(count<1||count>maximum)throw new IOException("secondary_read_count");
                return java.util.Arrays.copyOf(block,count);
            }catch(SocketTimeoutException idle){throw idle;}
            catch(Exception failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
    }
    int write(byte[] bytes,int deadlineMs)throws Exception{
        if(bytes==null||bytes.length<1||bytes.length>MAX_CHUNK||deadlineMs<1||deadlineMs>10000)
            throw new IllegalArgumentException("secondary_write_bound");
        synchronized(writeLock){
            Channel current=active();byte[] payload=bytes.clone();
            AtomicReference<Integer> count=new AtomicReference<>();
            AtomicReference<Throwable> error=new AtomicReference<>();
            Thread task=new Thread(()->{
                try{count.set(current.write(payload));}catch(Throwable failure){error.set(failure);}
                synchronized(lock){lock.notifyAll();}
            },"cloud-secondary-write");
            task.setDaemon(true);
            synchronized(lock){writer=task;if(closed)throw new IOException("secondary_closed");task.start();}
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(deadlineMs);
            try{
                synchronized(lock){
                    while(task.isAlive()&&!closed){
                        long remaining=end-System.nanoTime();
                        if(remaining<=0)throw new SocketTimeoutException("secondary_ambiguous_write");
                        lock.wait(Math.min(50,Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining))));
                    }
                    if(closed||channel!=current)throw new IOException("secondary_ambiguous_write");
                }
                if(System.nanoTime()>end)throw new SocketTimeoutException("secondary_ambiguous_write");
                if(error.get() instanceof Exception)throw (Exception)error.get();
                if(error.get()!=null)throw new IOException("secondary_write_failed",error.get());
                Integer sent=count.get();
                if(sent==null||sent<1||sent>payload.length)throw new IOException("secondary_write_count");
                return sent;
            }catch(Exception failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
    }
    @Override public void close()throws Exception{
        synchronized(closeLock){
            Channel active;ArrayList<Socket> sockets;Thread connectTask,writeTask,closeTask;
            synchronized(lock){
                closed=true;opening=false;lock.notifyAll();active=channel;
                sockets=new ArrayList<>(ownedSockets);connectTask=opener;writeTask=writer;
                if(closer==null){
                    // Raw sockets are published before TLS wrappers. Closing
                    // them first releases a blocked TLS read/write/close.
                    closer=new Thread(()->{
                        Exception failed=null;
                        for(Socket socket:sockets)try{socket.close();}
                            catch(Exception error){failed=append(failed,error);}
                        if(active!=null)try{active.close();}
                            catch(Exception error){failed=append(failed,error);}
                        synchronized(lock){if(failed!=null)cleanupFailure=append(cleanupFailure,failed);lock.notifyAll();}
                    },"cloud-secondary-close");
                    closer.setDaemon(true);closer.start();
                }
                closeTask=closer;
            }
            Exception failure=cleanupFailure;
            boolean interrupted=false;
            for(Thread task:new Thread[]{closeTask,connectTask,writeTask})if(task!=null&&task!=Thread.currentThread()){
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(task.isAlive()&&System.nanoTime()<end){
                    try{task.join(50);}catch(InterruptedException stop){interrupted=true;}
                }
                if(task.isAlive())failure=append(failure,new IOException("secondary_thread_cleanup_unresolved"));
            }
            synchronized(lock){
                if(cleanupFailure!=null)failure=append(failure,cleanupFailure);
                if(failure==null&&slotHeld){SLOT.release();slotHeld=false;channel=null;ownedSockets.clear();}
            }
            if(interrupted)Thread.currentThread().interrupt();
            if(failure!=null)throw failure;
        }
    }
    private static Exception append(Exception first,Exception next){
        if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;
    }
}
