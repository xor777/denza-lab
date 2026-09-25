package dev.denza.tools.runtime;

import java.util.function.LongSupplier;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

/** Fresh physical ACC/MCU proof for an awake-only owner; no firmware command semantics. */
final class CloudPowerGuard {
    interface Source { int getInt(int device,int fid)throws Exception; }
    private final Source source;
    private final LongSupplier bootMs,uptimeMs;
    private final Continuity continuity;
    /** Lives across connection epochs when used by the owner/session loop. */
    static final class Continuity {
        private long lastBoot=-1,lastUptime=-1;
        void check(long boot,long uptime)throws CloudSessionLoop.PermanentFailure{
            if(boot<0||uptime<0||boot<uptime||
               (lastBoot>=0&&(boot<lastBoot||uptime<lastUptime||
                boot-lastBoot-(uptime-lastUptime)>1000)))
                throw new CloudSessionLoop.PermanentFailure(Code.POWER_LOST);
            lastBoot=boot;lastUptime=uptime;
        }
    }
    CloudPowerGuard(Source source,LongSupplier bootMs,LongSupplier uptimeMs){
        this(source,bootMs,uptimeMs,new Continuity());
    }
    CloudPowerGuard(Source source,LongSupplier bootMs,LongSupplier uptimeMs,Continuity continuity){
        this.source=source;this.bootMs=bootMs;this.uptimeMs=uptimeMs;this.continuity=continuity;
    }
    void check()throws CloudSessionLoop.PermanentFailure{
        long uptime=uptimeMs.getAsLong(),boot=bootMs.getAsLong();
        continuity.check(boot,uptime);
        final int acc,mcu;
        try{acc=source.getInt(CloudPlatform.POWER,CloudPlatform.POWER_ACC);
            mcu=source.getInt(CloudPlatform.POWER,CloudPlatform.MCU_STATE);}
        catch(Exception unavailable){throw new CloudSessionLoop.PermanentFailure(Code.POWER_UNAVAILABLE);}
        if(acc!=1||mcu!=1)throw new CloudSessionLoop.PermanentFailure(Code.POWER_LOST);
    }
    static void checkEvent(int device,int fid,int value)throws CloudSessionLoop.PermanentFailure{
        if(device==CloudPlatform.POWER&&(fid==CloudPlatform.POWER_ACC||fid==CloudPlatform.MCU_STATE)&&value!=1)
            throw new CloudSessionLoop.PermanentFailure(Code.POWER_LOST);
    }
    static void verifyAndroid(CloudRuntimeSupervisor.Identity pair)throws Exception{
        Scope scope=new Scope();
        try{
            CloudPlatform platform=CloudPlatform.open(scope,pair.iccid,pair.imsi,16);
            new CloudPowerGuard(platform::getInt,android.os.SystemClock::elapsedRealtime,
                android.os.SystemClock::uptimeMillis).check();
        }finally{scope.close();}
    }
}
