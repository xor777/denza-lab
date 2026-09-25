package dev.denza.tools;

import android.os.SystemClock;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;

/** One generated pair between original controls, two real status sessions, bounded recovery. */
public final class OncarGeneratedPairSession {
    static boolean paused;
    static void event(String value) { OncarNativeSession.event(value); }
    static void need(boolean value,String why) { OncarTls.need(value,why); }
    static byte[] generated(String prefix,int size,byte[] original) throws Exception {
        SecureRandom random=new SecureRandom();StringBuilder value=new StringBuilder(prefix);
        while(value.length()<size)value.append((char)('0'+random.nextInt(10)));
        byte[] result=value.toString().getBytes("US-ASCII");
        if(Arrays.equals(result,original))result[size-1]=(byte)('0'+(result[size-1]-'0'+1)%10);
        return result;
    }
    static void bootstrap(OncarNativeSession.Worker worker,String label) throws Exception {
        event("LEG name="+label);
        if(paused)need(OncarNativeSession.tcp()==0,"stock session competed");
        String result=OncarNativeSession.bootstrap(worker,"G",211,"dilinkreg-cn.denzacloud.com",6001);
        need(result.equals("REG 0")||result.equals("REG 1"),"native registration rejected");
        if(result.equals("REG 0")) {
            event("REGISTRATION_DELAY seconds=70");
            for(int i=0;i<14;i++){Thread.sleep(5000);if(paused)need(OncarNativeSession.tcp()==0,"stock session competed");}
        }
        need(OncarNativeSession.bootstrap(worker,"D",200,"dilinkaddr-cn.denzacloud.com",6021)
            .equals("ENDPOINT dilinknat0-cn.denzacloud.com 6041"),"discovery changed");
    }
    static void control(OncarNativeSession.Worker worker,String label) throws Exception {
        need(OncarNativeSession.tcp()==0,"stock session competed");
        try(SSLSocket socket=OncarTls.connect("dilinknat0-cn.denzacloud.com",6041)) {
            OncarNativeSession.signatureTotal+=OncarTls.signatureCount();
            socket.getOutputStream().write(worker.produce("L"));socket.getOutputStream().flush();
            need(worker.exchange("R220 "+OncarTls.hex(OncarNativeSession.frame(socket.getInputStream())))
                .equals("LOGIN 1"),"native control login rejected");
            event("CONTROL_LOGIN name="+label+" accepted=true");
        }
    }
    public static void main(String[] args) {
        need(args.length==1,"directory required");OncarNativeSession.dir=args[0];
        OncarNativeSession.statusWaitMs=90000;
        OncarNativeSession.Inputs original=null;boolean variantAttempted=false;
        boolean originalRestored=false,stockRestored=false,passed=false;
        Thread deadline=new Thread(()->{try{Thread.sleep(480000);}catch(InterruptedException ignored){}
            event("HARD_DEADLINE");System.exit(124);},"bounded-generated-pair");deadline.setDaemon(true);deadline.start();
        try {
            OncarNativeSession.exempt();
            need(OncarNativeSession.tcp()==1&&OncarNativeSession.prop("persist.sys.byd.apn_type").equals("double_apn")
                &&OncarNativeSession.prop("persist.sys.cloud.token_flag").equals("1"),"baseline");
            original=new OncarNativeSession.Inputs();OncarTls.factoryIdentity();
            try(OncarNativeSession.Worker worker=new OncarNativeSession.Worker(original)) {
                bootstrap(worker,"original_before");paused=true;OncarNativeSession.gate(-5);
                long end=SystemClock.elapsedRealtime()+8000;
                while(OncarNativeSession.tcp()!=0&&SystemClock.elapsedRealtime()<end)Thread.sleep(200);
                need(OncarNativeSession.tcp()==0,"stock did not pause");control(worker,"original_before");
            }
            OncarNativeSession.Inputs changed=new OncarNativeSession.Inputs(original,
                generated("898607",20,original.iccid),generated("46001",15,original.imsi));
            event("VARIANT iccid_prefix=898607 imsi_prefix=46001 both_changed=true");
            variantAttempted=true;
            try(OncarNativeSession.Worker worker=new OncarNativeSession.Worker(changed)) {
                bootstrap(worker,"generated_pair");
                OncarNativeSession.session(worker,1,SystemClock.elapsedRealtime()+100000);
            }
            event("RECONNECT starting_new_worker=true identity_source=same_generated_pair");
            try(OncarNativeSession.Worker worker=new OncarNativeSession.Worker(changed)) {
                OncarNativeSession.session(worker,2,SystemClock.elapsedRealtime()+100000);
            }
            passed=true;event("PASS generated_pair_status_sessions=2");
        } catch(Exception error) {
            event("FAIL type="+error.getClass().getSimpleName()+" reason="+
                (error instanceof IllegalStateException?error.getMessage():"see stage"));
        } finally {
            if(variantAttempted&&original!=null) {
                try(OncarNativeSession.Worker worker=new OncarNativeSession.Worker(original)) {
                    bootstrap(worker,"original_restore");control(worker,"original_restore");originalRestored=true;
                }catch(Exception error){event("ORIGINAL_RESTORE_FAILED type="+error.getClass().getSimpleName());}
            }
            try {
                if(paused)OncarNativeSession.gate(4);
                long end=SystemClock.elapsedRealtime()+40000;
                while(OncarNativeSession.tcp()!=1&&SystemClock.elapsedRealtime()<end)Thread.sleep(500);
                stockRestored=OncarNativeSession.tcp()==1;
                if(stockRestored)Files.deleteIfExists(Paths.get(args[0],"armed"));
                Files.deleteIfExists(Paths.get(args[0],"identity.txt"));
            }catch(Exception error){event("STOCK_RESTORE_FAILED type="+error.getClass().getSimpleName());}
            event("FINAL pass="+passed+" original_restored="+originalRestored+" stock_restored="+stockRestored+
                " signatures="+OncarNativeSession.signatureTotal);
        }
        System.exit(passed&&originalRestored&&stockRestored?0:1);
    }
}
