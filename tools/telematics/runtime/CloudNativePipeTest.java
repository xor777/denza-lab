package dev.denza.tools.runtime;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CloudNativePipeTest {
    static int tests;
    static void check(boolean value){if(!value)throw new AssertionError();}
    static Process child(String mode)throws Exception{
        return new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),
            CloudNativePipeTest.class.getName(),"--child",mode).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    static void simulate(String mode)throws Exception{
        if(mode.equals("ready")){System.out.println("WRONG");System.out.flush();Thread.sleep(10000);return;}
        System.out.println("READY");System.out.println("CAPS REG=1 DATA=1 CONTROL532=0");System.out.flush();
        BufferedReader r=new BufferedReader(new InputStreamReader(System.in));
        for(String line;(line=r.readLine())!=null;){
            String[] words=line.split(" ");String id=words[1],epoch=words[2];
            if(mode.equals("exit_after_op"))System.exit(23);
            if(mode.equals("hang")){Thread.sleep(10000);continue;}
            if(mode.equals("bound")){System.out.println("x".repeat(5000));System.out.flush();continue;}
            if(mode.equals("flood")){for(int i=0;i<200;i++)System.out.println("NET "+id+" "+epoch+" 00");System.out.flush();continue;}
            if(mode.equals("epoch"))epoch="12";
            if(mode.equals("id"))id="100";
            if(mode.equals("secondary")){
                System.out.println("CALL "+id+" "+epoch+" SECONDARY_CONNECT 746573742e64656e7a61636c6f75642e636f6d 3139322e302e322e31 6003");System.out.flush();
                check(r.readLine().equals("RET "+id+" "+epoch+" CONNECTED 1"));
                System.out.println("CALL "+id+" "+epoch+" SECONDARY_WRITE "+"ab".repeat(1024));System.out.flush();
                check(r.readLine().equals("RET "+id+" "+epoch+" WRITTEN 1024"));
                System.out.println("CALL "+id+" "+epoch+" SECONDARY_CLOSE");System.out.flush();
                check(r.readLine().equals("RET "+id+" "+epoch+" OK"));
            }else if(mode.equals("call")){
                System.out.println("CALL "+id+" "+epoch+" PROPERTY_SET name value");System.out.flush();
                String ret=r.readLine();if(ret==null)return;
                if(ret.endsWith(" ERR"))return;
            }else System.out.println("NET "+id+" "+epoch+" abcd");
            System.out.println("RESULT "+id+" "+epoch+" LOGIN 1");
            System.out.println("DONE "+id+" "+epoch+" RX");System.out.flush();
        }
    }
    static CloudNativePipe.Effects effects(AtomicInteger count){return new CloudNativePipe.Effects(){
        public void event(String kind,List<String> args){count.incrementAndGet();check(kind.equals("NET"));check(args.get(0).equals("abcd"));}
        public String call(String kind,List<String> args){count.incrementAndGet();return "OK";}
    };}
    static void happy()throws Exception{
        Process child=child("ok");AtomicInteger count=new AtomicInteger();
        try(CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1)){
            pipe.requireCapabilities("REG","DATA");
            try{pipe.requireCapabilities("CONTROL532");throw new AssertionError();}catch(IOException expected){}
            for(int i=0;i<5;i++)check(pipe.exchange("RX abcd",effects(count),1000).equals(Arrays.asList("LOGIN 1","DONE RX")));
            check(count.get()==5);
        }check(!child.isAlive());tests++;
    }
    static void fullSecondaryPayload()throws Exception{
        Process process=child("secondary");AtomicInteger calls=new AtomicInteger();
        try(CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),process,1)){
            pipe.exchange("RX abcd",new CloudNativePipe.Effects(){
                public void event(String kind,List<String> args){throw new AssertionError();}
                public String call(String kind,List<String> args){
                    calls.incrementAndGet();
                    if(kind.equals("SECONDARY_CONNECT")){check(args.size()==3);return "CONNECTED 1";}
                    if(kind.equals("SECONDARY_WRITE")){check(args.size()==1&&args.get(0).length()==2048);return "WRITTEN 1024";}
                    check(kind.equals("SECONDARY_CLOSE")&&args.isEmpty());return "OK";
                }
            },2000);
            check(calls.get()==3);
        }
        check(!process.isAlive());tests++;
    }
    static void mismatchedEffect(String mode)throws Exception{
        Process child=child(mode);AtomicInteger count=new AtomicInteger();
        try(CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1)){
            try{pipe.exchange("RX abcd",effects(count),1000);throw new AssertionError();}
            catch(CloudNativePipe.ProtocolFailure expected){}
            check(count.get()==0 && !child.isAlive());
            try{pipe.exchange("RX abcd",effects(count),1000);throw new AssertionError();}catch(IOException expected){}
        }tests++;
    }
    static void callFailure()throws Exception{
        Process child=child("call");AtomicInteger count=new AtomicInteger();
        try(CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1)){
            try{pipe.exchange("RX abcd",new CloudNativePipe.Effects(){
                public void event(String k,List<String>a){count.incrementAndGet();}
                public String call(String k,List<String>a)throws Exception{throw new IOException("sensitive-data");}
            },1000);throw new AssertionError();}catch(IOException expected){check(!expected.getMessage().contains("sensitive"));}
            check(!child.isAlive()&&count.get()==0);
        }tests++;
    }
    static void permanentCallFailureSurvivesBrokenErrReply()throws Exception{
        Process child=child("call");
        try(CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1)){
            try{pipe.exchange("RX abcd",new CloudNativePipe.Effects(){
                public void event(String k,List<String>a){throw new AssertionError();}
                public String call(String k,List<String>a)throws Exception{
                    child.destroyForcibly();child.waitFor(1,TimeUnit.SECONDS);
                    throw new CloudSessionLoop.PermanentFailure(
                        CloudRuntimeSupervisor.Code.STOCK_OWNER_COMPETED);
                }
            },1000);throw new AssertionError("permanent refusal erased");}
            catch(CloudSessionLoop.PermanentFailure expected){
                check(expected.code==CloudRuntimeSupervisor.Code.STOCK_OWNER_COMPETED);
            }
            check(!child.isAlive());
        }tests++;
    }
    static void cancellation()throws Exception{
        Process child=child("hang");CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1);AtomicReference<Throwable> result=new AtomicReference<>();
        Thread t=new Thread(()->{try{pipe.exchange("RX abcd",effects(new AtomicInteger()),10000);}catch(Throwable error){result.set(error);}});
        t.start();Thread.sleep(50);long begin=System.nanoTime();pipe.close();t.join(1000);
        check(!child.isAlive()&&!t.isAlive()&&result.get()!=null&&System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(2));tests++;
    }
    static void oversizedOutput()throws Exception{
        Process child=child("bound");try(CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1)){
            try{pipe.exchange("RX abcd",effects(new AtomicInteger()),1000);throw new AssertionError();}
            catch(CloudNativePipe.ProtocolFailure expected){}
            check(!child.isAlive());
        }tests++;
    }
    static void failedStartup()throws Exception{
        Process child=child("ready");try{CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1);throw new AssertionError();}catch(IOException expected){}
        check(!child.isAlive());tests++;
    }
    static void childExitAfterOperation()throws Exception{
        Process child=child("exit_after_op");
        try(CloudNativePipe pipe=CloudNativePipe.open(new CloudRuntimeSupervisor.Scope(),child,1)){
            try{
                pipe.exchange("RX 73656e736974697665",effects(new AtomicInteger()),1000);
                throw new AssertionError("child exit accepted");
            }catch(CloudNativePipe.NativeFailure expected){}
            java.lang.reflect.Field field=CloudNativePipe.class.getDeclaredField("readTerminationStage");
            field.setAccessible(true);
            check("native_pipe_eof_exit_23_op_RX_after_NONE".equals(field.get(pipe)));
            check(!child.isAlive());
        }tests++;
    }
    static void boundaryPrivacy(){
        check(CloudNativePipe.diagnosticBoundary(Arrays.asList("PROPERTY_GET",
            "706572736973742e7379732e7265636f72645f3631305f75706c6f6164"))
            .equals("PROPERTY_GET_persist.sys.record_610_upload"));
        check(CloudNativePipe.diagnosticBoundary(Arrays.asList("PROPERTY_SET","name","private-value"))
            .equals("PROPERTY_SET"));
        check(CloudNativePipe.diagnosticBoundary(Arrays.asList("SECONDARY_WRITE","private-packet"))
            .equals("SECONDARY_WRITE"));
        check(CloudNativePipe.diagnosticBoundary(Arrays.asList("GET_BUFFER","1027","2566914586"))
            .equals("GET_BUFFER_1027_2566914586"));
        check(CloudNativePipe.diagnosticBoundary(Arrays.asList("PROPERTY_GET","zz"))
            .equals("PROPERTY_GET"));
        tests++;
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0){simulate(args[1]);return;}
        happy();fullSecondaryPayload();mismatchedEffect("id");mismatchedEffect("epoch");callFailure();
        permanentCallFailureSurvivesBrokenErrReply();cancellation();oversizedOutput();failedStartup();
        childExitAfterOperation();
        boundaryPrivacy();
        System.out.println("PASS native IPC cases="+tests);
    }
}
