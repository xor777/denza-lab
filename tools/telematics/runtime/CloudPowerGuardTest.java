package dev.denza.tools.runtime;

import java.io.IOException;
import java.util.concurrent.atomic.*;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

/** Synthetic clocks/getters; no car or SDK connection. */
public final class CloudPowerGuardTest {
    private static void need(boolean value){if(!value)throw new AssertionError();}
    private static void expect(CloudPowerGuard guard,Code code){
        try{guard.check();throw new AssertionError("unsafe power admitted");}
        catch(CloudSessionLoop.PermanentFailure expected){need(expected.code==code);}
    }
    public static void main(String[] args)throws Exception{
        AtomicLong boot=new AtomicLong(1000),uptime=new AtomicLong(900);
        AtomicInteger acc=new AtomicInteger(1),mcu=new AtomicInteger(1);
        AtomicInteger reads=new AtomicInteger();
        CloudPowerGuard.Source source=(device,fid)->{
            need(device==CloudPlatform.POWER);
            reads.incrementAndGet();
            if(fid==CloudPlatform.POWER_ACC)return acc.get();
            if(fid==CloudPlatform.MCU_STATE)return mcu.get();
            throw new AssertionError("wrong power FID");
        };
        CloudPowerGuard guard=new CloudPowerGuard(source,boot::get,uptime::get);
        guard.check();need(reads.get()==2);
        boot.addAndGet(250);uptime.addAndGet(250);guard.check();
        acc.set(0);expect(guard,Code.POWER_LOST);acc.set(1);
        mcu.set(0);expect(guard,Code.POWER_LOST);mcu.set(1);
        boot.addAndGet(5000);expect(guard,Code.POWER_LOST);
        CloudPowerGuard missing=new CloudPowerGuard((d,f)->{throw new IOException();},boot::get,uptime::get);
        expect(missing,Code.POWER_UNAVAILABLE);
        for(int fid:new int[]{CloudPlatform.POWER_ACC,CloudPlatform.MCU_STATE}){
            CloudPowerGuard.checkEvent(CloudPlatform.POWER,fid,1);
            for(int value:new int[]{0,-1,2}){
                try{CloudPowerGuard.checkEvent(CloudPlatform.POWER,fid,value);
                    throw new AssertionError("OFF/unknown callback admitted");}
                catch(CloudSessionLoop.PermanentFailure expected){need(expected.code==Code.POWER_LOST);}
            }
        }
        CloudPowerGuard.checkEvent(CloudPlatform.BODYWORK,CloudPlatform.ACC,0);
        System.out.println("PASS exact physical ACC/MCU and suspend guard cases=1");
    }
}
