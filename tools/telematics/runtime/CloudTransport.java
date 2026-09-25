package dev.denza.tools.runtime;

import dev.denza.tools.OncarTls;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

/** One verified mutual-TLS connection per native session. No cloud packet parsing. */
public final class CloudTransport implements AutoCloseable {
    public static final class IdleTimeout extends SocketTimeoutException {
        IdleTimeout(){super("native stream idle");}
    }
    public interface Connector { SSLSocket connect(String host, int port) throws Exception; }
    public interface CancellableConnector { SSLSocket connect(String host,int port,OncarTls.SocketOwner owner) throws Exception; }
    private static final int MIN_BODY = 48, MAX_BODY = 1019;
    // A stalled resolver cannot accumulate unbounded connector threads across retries.
    private static final Semaphore CONNECT_SLOT = new Semaphore(1);
    private final CancellableConnector connector;
    private final Object lock = new Object(), readLock = new Object(), writeLock = new Object();
    private SSLSocket socket;
    private Socket connecting;
    private Thread connectorThread;
    private final Object closeLock=new Object();
    private boolean opening, closed;
    private Throwable openFailure;
    private IOException cleanupFailure;
    private int readTimeoutMs = 10000, idleTimeoutMs=1000;

    public CloudTransport() { this((CancellableConnector)OncarTls::connect); }
    public CloudTransport(Connector connector) { this((host,port,owner)->connector.connect(host,port)); }
    public CloudTransport(CancellableConnector connector) {
        if (connector == null) throw new IllegalArgumentException("connector");
        this.connector = connector;
    }
    /** Validate the pinned BYD root and the current car's leaf/hardware proof. */
    public static void initializeFactoryIdentity() throws Exception { OncarTls.factoryIdentity(); }

    /** Returns on cancellation; a late successful TLS result is closed before it can become active. */
    public void open(String host, int port, long deadlineMs) throws Exception {
        if (host == null || host.isEmpty() || port < 1 || port > 65535 ||
            deadlineMs < 1000 || deadlineMs > 30000) throw new IllegalArgumentException("connect bound");
        synchronized (lock) {
            if (opening || closed || socket != null) throw new IllegalStateException("transport already used");
            opening = true;
        }
        long end = System.nanoTime() + deadlineMs * 1000000L;
        boolean acquired = false;
        try {
            while (!acquired) {
                synchronized (lock) { if (closed) throw new SocketTimeoutException("TLS connect cancelled"); }
                long remaining = end - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("TLS connector occupied");
                acquired = CONNECT_SLOT.tryAcquire(Math.min(100, Math.max(1, remaining / 1000000L)), TimeUnit.MILLISECONDS);
            }
            synchronized (lock) { if (closed) throw new SocketTimeoutException("TLS connect cancelled"); }
        } catch (Exception failure) {
            if (acquired) CONNECT_SLOT.release();
            synchronized (lock) { opening = false; closed = true; lock.notifyAll(); }
            throw failure;
        }
        Thread worker = new Thread(() -> {
            SSLSocket created = null; Throwable failure = null;
            try { created = connector.connect(host, port, candidate -> {
                synchronized(lock) { if(closed) { recordClose(candidate); throw new IOException("TLS connect cancelled"); } connecting=candidate; }
            }); }
            catch (Throwable error) { failure = error; }
            finally {
                synchronized (lock) {
                    if (closed || !opening) recordClose(created);
                    else if (failure == null && created != null) {
                        try { created.setSoTimeout(readTimeoutMs); socket = created; }
                        catch (Throwable error) { failure = error; recordClose(created); }
                    }
                    if (failure == null && created == null) failure = new IOException("connector returned no socket");
                    openFailure = failure; opening = false; lock.notifyAll();
                }
                CONNECT_SLOT.release();
            }
        }, "cloud-tls-connect");
        worker.setDaemon(true);
        synchronized(lock) {
            if(closed) { CONNECT_SLOT.release(); opening=false; throw new IOException("TLS connect cancelled"); }
            connectorThread=worker;
            try { worker.start(); }
            catch (RuntimeException|Error failure) {
                CONNECT_SLOT.release(); opening = false; closed = true; lock.notifyAll(); throw failure;
            }
        }
        try {
        synchronized (lock) {
            while (opening && !closed) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) { closed = true; lock.notifyAll(); break; }
                long millis = Math.max(1, remaining / 1000000L);
                try { lock.wait(millis); }
                catch (InterruptedException e) { closed = true; lock.notifyAll(); Thread.currentThread().interrupt(); throw e; }
            }
            if (closed) throw new SocketTimeoutException("TLS connect cancelled or timed out");
            if (openFailure != null) {
                if (openFailure instanceof Exception) throw (Exception)openFailure;
                throw new IOException("TLS connector failed", openFailure);
            }
            if (socket == null) throw new IOException("TLS socket unavailable");
        }
        } catch(Exception failure) {
            try{close();}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
        }
    }

    public void setReadTimeoutMs(int timeoutMs) throws Exception {
        if (timeoutMs < 1000 || timeoutMs > 120000) throw new IllegalArgumentException("read timeout");
        synchronized (lock) {
            if (closed) throw new IOException("transport closed");
            readTimeoutMs = timeoutMs;
            if (socket != null) socket.setSoTimeout(timeoutMs);
        }
    }
    public void setIdleTimeoutMs(int timeoutMs) {
        if(timeoutMs<100||timeoutMs>30000)throw new IllegalArgumentException("idle timeout");
        synchronized(lock){idleTimeoutMs=timeoutMs;}
    }
    private SSLSocket active() throws IOException {
        synchronized (lock) {
            if (closed || socket == null) throw new IOException("transport closed or disconnected");
            return socket;
        }
    }
    void requireConnected() throws IOException { active(); }
    public byte[] readFrame() throws Exception {
        synchronized (readLock) {
            try {
                SSLSocket current = active(); InputStream input = current.getInputStream();
                final int budget,idle;
                synchronized(lock){budget=readTimeoutMs;idle=idleTimeoutMs;}
                current.setSoTimeout(idle);
                final int first;
                try{first=input.read();}catch(SocketTimeoutException timeout){throw new IdleTimeout();}
                if(first<0)throw new EOFException("native stream ended");
                long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget);
                byte[] header=new byte[5];header[0]=(byte)first;
                readFully(current,input,header,1,4,deadline);
                if ((header[0] & 255) != 0xfe || (header[1] & 255) != 0xfe || (header[2] & 255) != 3)
                    throw new IOException("native envelope header");
                int length = ((header[3] & 255) << 8) | (header[4] & 255);
                if (length < MIN_BODY || length > MAX_BODY) throw new IOException("native envelope length");
                byte[] result = Arrays.copyOf(header, 5 + length);
                readFully(current,input,result,5,length,deadline);
                synchronized (lock) {
                    if (closed || socket != current) throw new IOException("stale TLS frame");
                }
                return result;
            } catch(IdleTimeout idle) { throw idle; }
            catch(Exception failure) {
                // A partial frame cannot be retried from its middle as a new envelope.
                try{close();}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
            }
        }
    }
    public void sendNativeFrame(byte[] nativeFrame) throws Exception {
        if (nativeFrame == null || nativeFrame.length < 5 + MIN_BODY || nativeFrame.length > 5 + MAX_BODY ||
            (nativeFrame[0] & 255) != 0xfe || (nativeFrame[1] & 255) != 0xfe ||
            (nativeFrame[2] & 255) != 3 ||
            (((nativeFrame[3] & 255) << 8) | (nativeFrame[4] & 255)) != nativeFrame.length - 5)
            throw new IllegalArgumentException("native frame bound");
        sendOpaqueBytes(nativeFrame);
    }
    /** Original sender bytes, including its own framing; never re-encoded here. */
    public void sendOpaqueBytes(byte[] nativeFrame) throws Exception {
        if(nativeFrame==null||nativeFrame.length<1||nativeFrame.length>1024)
            throw new IllegalArgumentException("native write bound");
        synchronized (writeLock) {
            try {
                SSLSocket current = active();
                current.getOutputStream().write(nativeFrame.clone());
                current.getOutputStream().flush();
                synchronized (lock) { if (closed || socket != current) throw new IOException("ambiguous native send"); }
            } catch(Exception failure) {
                try{close();}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
            }
        }
    }
    private static void readFully(SSLSocket socket,InputStream in,byte[] bytes,int offset,int length,long deadline) throws IOException {
        int end=offset+length;
        while(offset<end){
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new SocketTimeoutException("native frame deadline");
            socket.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
            int n=in.read(bytes,offset,end-offset);
            if(n<0)throw new EOFException("partial native frame");
            if(n==0)throw new IOException("native frame reader stalled");
            offset+=n;
        }
        if(System.nanoTime()>deadline)throw new SocketTimeoutException("native frame deadline");
    }
    @Override public void close() throws IOException {
        synchronized(closeLock){
            Socket old,pending;Thread worker;
            synchronized(lock){
                closed=true;old=socket;pending=connecting;worker=connectorThread;lock.notifyAll();
            }
            recordClose(old);if(pending!=old)recordClose(pending);
            boolean interrupted=Thread.interrupted();
            try{
                if(worker!=null && worker!=Thread.currentThread()){
                    worker.interrupt();
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(worker.isAlive()&&System.nanoTime()<end){
                        try{worker.join(50);}catch(InterruptedException e){interrupted=true;}
                    }
                    if(worker.isAlive())throw new IOException("TLS connector cleanup unresolved");
                }
                synchronized(lock){if(cleanupFailure!=null)throw new IOException("TLS socket cleanup unresolved");socket=null;connecting=null;}
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private void recordClose(Socket socket) {
        if(socket!=null)try{socket.close();}catch(IOException failure){synchronized(lock){cleanupFailure=failure;}}
    }
}
