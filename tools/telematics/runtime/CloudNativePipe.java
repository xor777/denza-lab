package dev.denza.tools.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bounded process IPC only. Firmware owns every vehicle/cloud payload. */
public final class CloudNativePipe implements AutoCloseable {
    /** Native ABI or primitive mismatch: another socket cannot repair it. */
    public static final class ProtocolFailure extends CloudSessionLoop.PermanentFailure {
        public ProtocolFailure(){super(CloudRuntimeSupervisor.Code.NATIVE_UNAVAILABLE);}
    }
    /** A child exit, stall, or platform call failure receives only bounded recovery. */
    public static final class NativeFailure extends IOException {
        public NativeFailure(){super("native_process_failed");}
    }
    public interface Effects {
        /** Already tagged with the current operation and connection epoch. */
        void event(String kind, List<String> arguments) throws Exception;
        /** Return only after the actual platform operation succeeded. */
        String call(String kind, List<String> arguments) throws Exception;
    }
    private static final int INPUT_BOUND=2099, OUTPUT_BOUND=4096, QUEUE_BOUND=64, OP_LINES=128;
    private final Process process;
    private final Thread reader;
    private final OutputStream input;
    private final ArrayBlockingQueue<String> output=new ArrayBlockingQueue<>(QUEUE_BOUND);
    private final Object requestLock=new Object(), closeLock=new Object(), effectGate=new Object();
    private final long epoch;
    private volatile boolean closed, readFailed, malformedOutput;
    private volatile String lastOperationVerb="NONE", readTerminationStage;
    private volatile String lastCallBoundary="NONE";
    private boolean reaped;
    private long nextId;
    private Map<String,Boolean> capabilities;

    /** Register before READY/CAPS so even failed startup retains teardown debt. */
    public static CloudNativePipe open(CloudRuntimeSupervisor.Scope scope,Process process,long epoch) throws Exception {
        CloudNativePipe pipe=scope.own(new CloudNativePipe(process,epoch));
        try { pipe.initialize(); return pipe; }
        catch(Exception|Error failure) {
            try{scope.retire(pipe);}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
        }
    }
    private CloudNativePipe(Process process,long epoch) {
        if(process==null) throw new IllegalArgumentException("process_required");
        this.process=process; this.epoch=epoch; input=process.getOutputStream();
        reader=new Thread(this::readOutput,"cloud-native-output");reader.setDaemon(true);
    }
    private void initialize() throws Exception {
        if(epoch<1 || epoch>0xffffffffL) throw new ProtocolFailure();
        reader.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        if(!"READY".equals(take(deadline))) throw new ProtocolFailure();
        capabilities=Collections.unmodifiableMap(parseCapabilities(take(deadline)));
    }
    public Map<String,Boolean> capabilities(){return capabilities;}
    private static Map<String,Boolean> parseCapabilities(String line) throws IOException {
        String[] words=line.split(" ",-1);
        if(words.length<2 || words.length>17 || !words[0].equals("CAPS")) throw new ProtocolFailure();
        Map<String,Boolean> result=new HashMap<>();
        for(int i=1;i<words.length;i++){
            String[] item=words[i].split("=",-1);
            if(item.length!=2 || !item[0].matches("[A-Z][A-Z0-9_]{0,31}") ||
               !(item[1].equals("0")||item[1].equals("1")) || result.containsKey(item[0]))
                throw new ProtocolFailure();
            result.put(item[0],item[1].equals("1"));
        }
        return result;
    }
    public void requireCapabilities(String... names) throws IOException {
        for(String name:names)if(!Boolean.TRUE.equals(capabilities.get(name)))throw new IOException("native_path_unavailable");
    }
    private void readOutput(){
        String termination="eof";
        String protocolReason="unspecified";
        try(InputStream stream=process.getInputStream()){
            ByteArrayOutputStream line=new ByteArrayOutputStream(OUTPUT_BOUND);
            for(int c;(c=stream.read())!=-1;){
                if(c=='\n'){
                    String value=line.toString("US-ASCII");line.reset();
                    if(value.matches("\\{\"passed\":false,\"stage\":\"[a-z0-9_]{1,80}\"\\}")) {
                        String stage=value.substring(value.indexOf("stage\":\"")+8,value.length()-2);
                        CloudSessionLoop.diagnostic("native_"+stage,new ProtocolFailure());
                        protocolReason="native_refusal";
                        throw new ProtocolFailure();
                    }
                    if(value.isEmpty()){protocolReason="empty_line";throw new ProtocolFailure();}
                    if(!output.offer(value)){protocolReason="output_queue_full";throw new ProtocolFailure();}
                } else {
                    if(c<32||c>126){protocolReason="non_ascii";throw new ProtocolFailure();}
                    if(line.size()>=OUTPUT_BOUND){protocolReason="line_too_long";throw new ProtocolFailure();}
                    line.write(c);
                }
            }
        } catch(Exception failure) {
            malformedOutput=failure instanceof ProtocolFailure;
            termination=malformedOutput?"protocol_"+protocolReason:failure instanceof IOException?"read_io":"reader_exception";
        }
        finally {
            if(!closed){
                // Observe the child before destroy() can replace its exit code.
                // Java exposes a raw exit code, not a portable signal identity.
                readTerminationStage="native_pipe_"+termination+"_"+childExitObservation()
                    +"_op_"+lastOperationVerb+"_after_"+lastCallBoundary;
            }
            synchronized(effectGate){readFailed=true;}
            output.offer("");process.destroy();
            if(readTerminationStage!=null)
                CloudSessionLoop.diagnostic(readTerminationStage,new NativeFailure());
        }
    }
    private String childExitObservation(){
        try{
            return process.waitFor(50,TimeUnit.MILLISECONDS)
                ?"exit_"+process.exitValue():"alive";
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();return "alive";
        }
    }
    private String take(long deadline) throws Exception {
        for(;;){
            if(closed||readFailed)throw readFailure();
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new NativeFailure();
            String value=output.poll(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);
            if(value!=null){if(readFailed||closed||value.isEmpty())throw readFailure();return value;}
            if(readFailed)throw readFailure();
        }
    }
    private IOException readFailure(){return malformedOutput?new ProtocolFailure():new NativeFailure();}
    /** Operation names and property names only; never values or packet bytes. */
    static String diagnosticBoundary(List<String> args){
        String kind=args.get(0);
        if(!kind.matches("[A-Z][A-Z0-9_]{0,31}"))return "UNKNOWN";
        if(kind.equals("PROPERTY_GET")&&args.size()==2){
            String hex=args.get(1);
            if(hex.matches("[0-9a-f]{2,192}")&&hex.length()%2==0){
                byte[] name=new byte[hex.length()/2];
                for(int i=0;i<name.length;i++)name[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
                String key=new String(name,StandardCharsets.US_ASCII);
                if(key.matches("(persist|sys|ro)\\.[a-z_][a-z0-9_.]{0,90}"))return kind+"_"+key;
            }
        }
        if((kind.equals("GET_INT")||kind.equals("GET_FLOAT")||kind.equals("GET_BUFFER"))
            &&args.size()==3&&args.get(1).matches("[0-9]{1,4}")&&args.get(2).matches("[0-9]{1,10}"))
            return kind+"_"+args.get(1)+"_"+args.get(2);
        return kind;
    }
    private void send(String line) throws IOException {
        if(closed)throw new NativeFailure();
        if(line.length()>INPUT_BOUND || !line.matches("[ -~]+"))throw new ProtocolFailure();
        try{input.write((line+"\n").getBytes(StandardCharsets.US_ASCII));input.flush();}
        catch(IOException broken){throw new NativeFailure();}
    }
    /** All operations include a generation; stale output can never become a current effect. */
    public List<String> exchange(String operation,Effects effects,long timeoutMs) throws Exception {
        synchronized(requestLock){
            if(timeoutMs<1 || timeoutMs>10000 || operation==null ||
               !operation.matches("[A-Z][A-Z0-9_]*( [ -~]+)?"))throw new ProtocolFailure();
            long id=++nextId;if(id>0xffffffffL)throw new ProtocolFailure();
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            List<String> result=new ArrayList<>();
            try{
                String verb=operation.split(" ",2)[0];
                lastOperationVerb=verb.matches("[A-Z][A-Z0-9_]{0,31}")?verb:"UNKNOWN";
                lastCallBoundary="NONE";
                send("OP "+id+" "+epoch+" "+operation);
                for(int n=0;n<OP_LINES;n++){
                    String[] words=take(deadline).split(" ",-1);
                    if(words.length<3 || !words[1].equals(Long.toString(id)) || !words[2].equals(Long.toString(epoch)))
                        throw new ProtocolFailure();
                    List<String> args=Collections.unmodifiableList(Arrays.asList(Arrays.copyOfRange(words,3,words.length)));
                    if(readFailed||closed)throw readFailure();
                    switch(words[0]){
                        case "RESULT":
                            if(result.size()>=8)throw new ProtocolFailure();
                            result.add(String.join(" ",args));break;
                        case "DONE":
                            result.add("DONE"+(args.isEmpty()?"":" "+String.join(" ",args)));
                            return Collections.unmodifiableList(result);
                        case "CALL":
                            if(args.isEmpty()||effects==null)throw new ProtocolFailure();
                            lastCallBoundary=diagnosticBoundary(args);
                            String reply;
                            try{synchronized(effectGate){
                                if(readFailed||closed)throw readFailure();
                                reply=effects.call(args.get(0),args.subList(1,args.size()));
                            }}
                            catch(Exception e){
                                CloudSessionLoop.diagnostic("native_call",e);
                                try{send("RET "+id+" "+epoch+" ERR");}
                                catch(Exception replyFailure){if(replyFailure!=e)e.addSuppressed(replyFailure);}
                                if(e instanceof CloudSessionLoop.PermanentFailure || e instanceof CancellationException)throw e;
                                if(e instanceof CloudSessionLoop.NetworkFailure)throw e;
                                if(e instanceof IllegalArgumentException || e instanceof IllegalStateException)throw new ProtocolFailure();
                                throw new NativeFailure();
                            }
                            if(reply==null||reply.length()>1800||!reply.matches("(OK|ERR|CONNECTED [01]|BYTES [0-9a-f]{32}|WRITTEN [1-9][0-9]{0,3}|VALUE [ -~]+|BUFFER -?[0-9]+ (-|[0-9a-f]{2,1024})|IPV4 (-|[0-9a-f]{8}( [0-9a-f]{8}){0,3})|TIMEOUT [0-9]+( [0-9]+){0,2}|EVENT [ -~]+|TM (-?[0-9]+ ){8}-?[0-9]+)"))
                                throw new ProtocolFailure();
                            send("RET "+id+" "+epoch+" "+reply);break;
                        case "NET":case "AUTO":case "ARM":case "CANCEL":case "FIRED":case "NOTIFY":
                            if(effects==null)throw new ProtocolFailure();
                            synchronized(effectGate){
                                if(readFailed||closed)throw readFailure();
                                try{effects.event(words[0],args);}
                                catch(IllegalArgumentException|IllegalStateException mismatch){throw new ProtocolFailure();}
                            }break;
                        default:throw new ProtocolFailure();
                    }
                }
                throw new ProtocolFailure();
            } catch(Exception|Error failure){
                if(failure instanceof ProtocolFailure)
                    CloudSessionLoop.diagnostic("native_exchange_"+lastOperationVerb+"_after_"+lastCallBoundary,failure);
                try{close();}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
    }
    /** Never wait for a QUIT acknowledgement from a child stuck inside original firmware. */
    @Override public void close() throws IOException {
        closed=true;output.offer("");
        synchronized(closeLock){
            if(reaped)return;
            boolean interrupted=Thread.interrupted();
            try{
                process.destroy();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(process.isAlive()&&System.nanoTime()<deadline){
                    try{process.waitFor(50,TimeUnit.MILLISECONDS);}catch(InterruptedException e){interrupted=true;}
                }
                if(process.isAlive())process.destroyForcibly();
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(process.isAlive()&&System.nanoTime()<deadline){
                    try{process.waitFor(50,TimeUnit.MILLISECONDS);}catch(InterruptedException e){interrupted=true;}
                }
                if(process.isAlive())throw new IOException("native_cleanup_failed");
                closeQuietly(input);closeQuietly(process.getInputStream());closeQuietly(process.getErrorStream());
                try{reader.join(500);}catch(InterruptedException e){interrupted=true;}
                if(reader.isAlive())throw new IOException("native_reader_cleanup_failed");
                reaped=true;
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private static void closeQuietly(Closeable value){try{value.close();}catch(IOException ignored){}}
}
