import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.Process;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.Arrays;

/** Read-only SIM state and ICCID availability; never emits SIM identifiers. */
public final class OtaSimInputs {
    static String property(String name) throws Exception {
        return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class,String.class).invoke(null,name,"");
    }
    static JSONObject shape(String value, String property) throws Exception {
        JSONObject out=new JSONObject();
        out.put("isNull",value==null); out.put("length",value==null?0:value.length());
        out.put("decimal19To22",value!=null && value.matches("[0-9]{19,22}"));
        out.put("matchesRilProperty",value!=null && !value.isEmpty() && value.equals(property));
        return out;
    }
    public static void main(String[] args) {
        Thread deadline=new Thread(()->{try{Thread.sleep(15000);}catch(InterruptedException ignored){} Process.killProcess(Process.myPid());});
        deadline.setDaemon(true);deadline.start();
        java.io.PrintStream output=System.out;
        EcuMapMetadataProbe.silenceRuntimeOutput();
        int exit=1;
        JSONObject result=new JSONObject();
        String phase="guard";
        try(RandomAccessFile f=new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock","rw"); FileLock lock=f.getChannel().tryLock()) {
            if(Process.myUid()!=2000 || args.length!=0 || lock==null)throw new IllegalStateException();
            phase="telephonyBootstrapWasMissing";
            result.put("telephonyBootstrapWasMissing",OtaTelephonyBootstrap.prepare());
            phase="context";
            if(Looper.myLooper()==null)Looper.prepareMainLooper();
            Class<?> activity=Class.forName("android.app.ActivityThread");
            Object thread=activity.getMethod("systemMain").invoke(null);
            Context context=(Context)activity.getMethod("getSystemContext").invoke(thread);
            if(context.getPackageManager().getPackageUid("com.android.shell",0)!=Process.myUid())throw new IllegalStateException();
            Context shell=new ContextWrapper(context.createPackageContext("com.android.shell",0)) {
                @Override public String getPackageName(){return "com.android.shell";}
                @Override public String getOpPackageName(){return "com.android.shell";}
                @Override public Context getApplicationContext(){return this;}
            };
            phase="phone";
            TelephonyManager phone=TelephonyManager.class.getConstructor(Context.class).newInstance(shell);
            phase="subscriptions";
            SubscriptionManager subscriptions=SubscriptionManager.class.getConstructor(Context.class).newInstance(shell);
            phase="defaultSubscriptionId";
            result.put("defaultSubscriptionId",SubscriptionManager.getDefaultSubscriptionId());
            phase="defaultDataSubscriptionId";
            result.put("defaultDataSubscriptionId",SubscriptionManager.getDefaultDataSubscriptionId());
            phase="defaultVoiceSubscriptionId";
            result.put("defaultVoiceSubscriptionId",SubscriptionManager.getDefaultVoiceSubscriptionId());
            phase="simState";
            result.put("simState",phone.getSimState());
            phase="phoneSubInfoServicePresent";
            Class<?> manager=Class.forName("android.os.ServiceManager");
            result.put("phoneSubInfoServicePresent",manager.getMethod("getService",String.class).invoke(null,"iphonesubinfo")!=null);
            phase="rilIccid";
            String prop=property("ril.csim.iccid");
            result.put("rilIccid",shape(prop, ""));
            phase="defaultIccid";
            result.put("defaultIccid",shape(phone.getSimSerialNumber(),prop));
            phase="gsmSimState";
            String sim=property("gsm.sim.state");
            result.put("gsmSimState",sim.matches("[A-Z_,]{0,64}")?sim:"unrecognized");
            phase="activeSubscriptions";
            int[] active=(int[])SubscriptionManager.class.getMethod("getActiveSubscriptionIdList").invoke(subscriptions);
            if(active==null || active.length>4)throw new IllegalStateException();
            JSONArray values=new JSONArray();
            for(int id:active) {
                String iccid=phone.createForSubscriptionId(id).getSimSerialNumber();
                JSONObject row=shape(iccid,prop);row.put("subscriptionId",id);values.put(row);
            }
            result.put("activeSubscriptions",values);
            result.put("outcome","complete");
            output.println("DENZA_OTA_SIM_INPUTS:"+result);output.flush();exit=0;
        } catch(Throwable failure) {
            try {
                Throwable cause=failure instanceof java.lang.reflect.InvocationTargetException?failure.getCause():failure;
                String kind=cause instanceof SecurityException?"permission":cause instanceof NullPointerException?"null":
                    cause instanceof ReflectiveOperationException?"reflection":cause instanceof LinkageError?"linkage":"other";
                result.put("outcome","partial");result.put("errorStage",phase);result.put("errorKind",kind);
                output.println("DENZA_OTA_SIM_INPUTS:"+result);output.flush();exit=0;
            } catch(Throwable ignored) { output.println("DENZA_OTA_SIM_INPUTS_ERROR");output.flush(); }
        }
        System.exit(exit);
    }
}
