package dev.denza.vehiclecontrol.probe;
import static org.junit.Assert.*;
import org.junit.Test;
import androidx.test.platform.app.InstrumentationRegistry;
import android.view.*;
import android.widget.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class RuntimeTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final android.content.Context context=instrumentation.getTargetContext();
    private JSONObject catalog()throws Exception{try(var input=context.getAssets().open("vehicle_controls.json")){return new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}
    private View find(View root,String text){if(root instanceof TextView t&&t.getText().toString().contains(text))return root;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),text);if(result!=null)return result;}return null;}
    private void noBrowser(View root){assertFalse(root.getClass().getName().contains("WebView"));if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)noBrowser(group.getChildAt(i));}
    private void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(40);assertTrue(condition.getAsBoolean());}
    private void shot(String name)throws Exception{try(var output=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name))){instrumentation.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}}
    @Test public void nativeSectionsAndHoldReleaseOnPause()throws Exception{
        // Existing key data is deliberately retained across native Activity starts/recreation.
        dev.denza.disharebridge.LocalAdbClient.loopbackOnly(context).publicKeyFingerprint();
        Path key=context.getNoBackupFilesDir().toPath().resolve("adb_auth_key_v1");byte[] keyBefore=Files.readAllBytes(key);
        JSONObject catalog=catalog();FakeVehicle fake=new FakeVehicle(catalog);
        try(var input=instrumentation.getContext().getAssets().open("hvac-n9-20261016.json")){String[] lines=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getString("raw").trim().split("\\n");for(int i=0;i<lines.length;i+=2)fake.rawOverrides.put(lines[i],lines[i+1]);}
        NativeController controller=new NativeController(catalog,()->new NativeController.Connection("fake",fake),new java.io.File(context.getCacheDir(),"native-ui-"+UUID.randomUUID()+".jsonl"));NativeRuntime.useForTest(controller);
        var intent=new android.content.Intent(context,RemoteActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        RemoteActivity activity=(RemoteActivity)instrumentation.startActivitySync(intent);instrumentation.runOnMainSync(activity::showRemote);
        until(()->find(activity.findViewById(android.R.id.content),"42%")!=null);
        shot("native-windows.png");
        instrumentation.runOnMainSync(()->{noBrowser(activity.findViewById(android.R.id.content));find(activity.findViewById(android.R.id.content),"Сиденья").performClick();});
        until(()->find(activity.findViewById(android.R.id.content),"Обогрев")!=null);
        instrumentation.waitForIdleSync();Thread.sleep(150);shot("native-seats.png");
        fake.holdStarted=new CountDownLatch(1);fake.holdRelease=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->{
            View button=find(activity.findViewById(android.R.id.content),"Вперёд");assertNotNull(button);
            long t=android.os.SystemClock.uptimeMillis();button.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,20,20,0));
        });
        assertTrue(fake.holdStarted.await(2,TimeUnit.SECONDS));
        instrumentation.runOnMainSync(activity::onPause); // must release through the separate channel.
        assertTrue(fake.holdRelease.await(2,TimeUnit.SECONDS));until(()->controller.pendingStops.isEmpty());
        instrumentation.runOnMainSync(()->{activity.onResume();find(activity.findViewById(android.R.id.content),"Климат").performClick();});
        until(()->find(activity.findViewById(android.R.id.content),"A/C")!=null);instrumentation.waitForIdleSync();Thread.sleep(150);shot("native-climate.png");
        instrumentation.runOnMainSync(()->{noBrowser(activity.findViewById(android.R.id.content));activity.finish();});
        assertArrayEquals(keyBefore,Files.readAllBytes(key));NativeRuntime.useForTest(null);
    }
    private record Rig(RemoteActivity activity,FakeVehicle fake,NativeRuntime runtime) { }
    private Rig rig()throws Exception{
        dev.denza.disharebridge.LocalAdbClient.loopbackOnly(context).publicKeyFingerprint();JSONObject c=catalog();FakeVehicle fake=new FakeVehicle(c);
        NativeController controller=new NativeController(c,()->new NativeController.Connection("fake",fake),new java.io.File(context.getCacheDir(),"poll-test-"+UUID.randomUUID()+".jsonl"));NativeRuntime.useForTest(controller);
        RemoteActivity activity=(RemoteActivity)instrumentation.startActivitySync(new android.content.Intent(context,RemoteActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->find(activity.findViewById(android.R.id.content),"42%")!=null);until(()->!NativeRuntimeGet().reading.get());
        return new Rig(activity,fake,NativeRuntimeGet());
    }
    private NativeRuntime NativeRuntimeGet(){try{return NativeRuntime.get(context);}catch(Exception e){throw new AssertionError(e);}}
    private View root(Rig rig){return rig.activity().findViewById(android.R.id.content);}
    private void click(Rig rig,String title){instrumentation.runOnMainSync(()->{View v=find(root(rig),title);assertNotNull(title,v);v.performClick();});}
    private void blockedRead(Rig rig)throws Exception{rig.fake().readStarted=new CountDownLatch(1);rig.fake().readRelease=new CountDownLatch(1);click(rig,"Обновить");assertTrue(rig.fake().readStarted.await(1,TimeUnit.SECONDS));}
    private void finish(Rig rig){rig.fake().readError=null;if(rig.fake().readRelease!=null)rig.fake().readRelease.countDown();if(rig.fake().holdRelease!=null)rig.fake().holdRelease.countDown();instrumentation.runOnMainSync(rig.activity()::finish);NativeRuntime.useForTest(null);}
    @Test public void pollingKeepsViewsFocusAndAcceptsOneClickDuringRead()throws Exception{
        Rig rig=rig();try{
            Button open=(Button)find(root(rig),"Открыть");java.util.concurrent.atomic.AtomicInteger detached=new java.util.concurrent.atomic.AtomicInteger();
            instrumentation.runOnMainSync(()->{open.setFocusableInTouchMode(true);open.requestFocus();open.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){detached.incrementAndGet();}});});
            blockedRead(rig);rig.fake().values.put("fl_percent",73);
            instrumentation.runOnMainSync(()->{assertSame(open,find(root(rig),"Открыть"));assertTrue(open.isEnabled());assertNotNull(find(root(rig),"42%"));assertNull(find(root(rig),"ADB занят"));assertNull(find(root(rig),"Обновляю показания"));});
            rig.fake().readRelease.countDown();until(()->find(root(rig),"73%")!=null);
            instrumentation.runOnMainSync(()->{assertSame(open,find(root(rig),"Открыть"));assertTrue("Read-only poll retains focus",open.hasFocus());assertEquals(0,detached.get());});
            until(()->!rig.runtime().reading.get());blockedRead(rig);rig.fake().values.put("fl_percent",74);
            instrumentation.runOnMainSync(()->{open.performClick();open.performClick();});rig.fake().readRelease.countDown();until(()->!rig.runtime().writing.get()&&rig.fake().writes.size()==1);until(()->find(root(rig),"74%")!=null);
            instrumentation.runOnMainSync(()->{assertSame(open,find(root(rig),"Открыть"));assertEquals(0,detached.get());});assertEquals(1,rig.fake().writes.size());
        }finally{finish(rig);}
    }
    @Test public void holdReleaseWhileWaitingReadNeverStartsAndActiveHoldRetainsTouch()throws Exception{
        Rig rig=rig();try{
            click(rig,"Сиденья");Button forward=(Button)find(root(rig),"Вперёд");assertNotNull(forward);
            blockedRead(rig);
            instrumentation.runOnMainSync(()->{long t=android.os.SystemClock.uptimeMillis();forward.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,20,20,0));forward.dispatchTouchEvent(MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,20,20,0));});
            rig.fake().readRelease.countDown();until(()->!rig.runtime().writing.get());assertTrue(rig.fake().writes.isEmpty());until(()->!rig.runtime().reading.get());
            blockedRead(rig);rig.fake().holdStarted=new CountDownLatch(1);rig.fake().holdRelease=new CountDownLatch(1);
            instrumentation.runOnMainSync(()->{long t=android.os.SystemClock.uptimeMillis();forward.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,20,20,0));});
            rig.fake().readRelease.countDown();assertTrue(rig.fake().holdStarted.await(2,TimeUnit.SECONDS));instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{assertSame(forward,find(root(rig),"Вперёд"));assertTrue("Active hold must keep ACTION_UP",forward.isEnabled());long t=android.os.SystemClock.uptimeMillis();forward.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_UP,20,20,0));});
            assertTrue(rig.fake().holdRelease.await(1,TimeUnit.SECONDS));until(()->!rig.runtime().writing.get());assertEquals(1,rig.fake().writes.size());assertTrue(rig.runtime().controller.pendingStops.isEmpty());
        }finally{finish(rig);}
    }
    @Test public void failedReadRetainsStaleDataAndOldLifecycleCallbackIsIgnored()throws Exception{
        Rig rig=rig();try{
            View open=find(root(rig),"Открыть");rig.fake().readError="fake disconnected";click(rig,"Обновить");until(()->find(root(rig),"Данные устарели")!=null);
            instrumentation.runOnMainSync(()->{assertSame(open,find(root(rig),"Открыть"));assertNotNull(find(root(rig),"42%"));assertFalse(open.isEnabled());assertTrue(find(root(rig),"Стоп").isEnabled());});
            rig.fake().readError=null;blockedRead(rig);rig.fake().values.put("fl_percent",91);instrumentation.runOnMainSync(rig.activity()::onPause);rig.fake().readRelease.countDown();until(()->!rig.runtime().reading.get());instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{assertNotNull(find(root(rig),"42%"));assertNull(find(root(rig),"91%"));rig.activity().onResume();});until(()->find(root(rig),"91%")!=null);assertSame(open,find(root(rig),"Открыть"));
        }finally{finish(rig);}
    }
    private <T extends View>T type(View root,Class<T> cls){if(cls.isInstance(root))return cls.cast(root);if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){T found=type(group.getChildAt(i),cls);if(found!=null)return found;}return null;}
    @Test public void stepperBoundsRefreshBindingsWithoutReplacingSelectorOrControls()throws Exception{
        Rig rig=rig();try{
            click(rig,"Климат");String title=catalog().getJSONObject("climate").getJSONObject("driver_fan").getString("name");ViewGroup card=(ViewGroup)find(root(rig),title).getParent();Button plus=(Button)find(card,"+"),minus=(Button)find(card,"−");Spinner picker=type(root(rig),Spinner.class);Object adapter=picker.getAdapter();
            instrumentation.runOnMainSync(()->{assertFalse(minus.isEnabled());assertTrue(plus.isEnabled());});
            for(int level:new int[]{1,7,6}){
                until(()->!rig.runtime().reading.get());rig.fake().values.put("AC_WIND_LEVEL",level);click(rig,"Обновить");until(()->!rig.runtime().reading.get());instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(()->{assertSame(card,find(root(rig),title).getParent());assertSame(plus,find(card,"+"));assertSame(minus,find(card,"−"));assertTrue(minus.isEnabled());assertEquals(level<7,plus.isEnabled());assertSame(picker,type(root(rig),Spinner.class));assertSame(adapter,picker.getAdapter());});
            }
            instrumentation.runOnMainSync(plus::performClick);until(()->!rig.runtime().writing.get());assertEquals(1,rig.fake().writes.size());assertEquals(rig.runtime().controller.command("climate.driver_fan","7").shell(),rig.fake().writes.get(0));
        }finally{finish(rig);}
    }
    @Test public void recoveryPanelChangesDoNotDetachOrdinaryControls()throws Exception{
        Rig rig=rig();try{
            View open=find(root(rig),"Открыть");until(()->!rig.runtime().reading.get());rig.runtime().controller.pendingStops.add("seat.fl.horizontal");click(rig,"Обновить");until(()->find(root(rig),"Проверьте остановку кресла")!=null);assertSame(open,find(root(rig),"Открыть"));
            until(()->!rig.runtime().reading.get());rig.runtime().controller.pendingStops.clear();click(rig,"Обновить");until(()->find(root(rig),"Проверьте остановку кресла")==null);assertSame(open,find(root(rig),"Открыть"));
        }finally{finish(rig);}
    }
    private String token(){return "/data/local/tmp/denza-remote-seat-"+UUID.randomUUID().toString().replace("-","");}
    private String shell(String start,String stop,Path release){return NativeController.holdScript(start,stop,token()).replaceAll("/data/local/tmp/denza-remote-seat-[0-9a-f]{32}",release.toString());}
    private String echo(String value){return "printf '%s\\n' \""+value+"\"";}
    private String output(Process p)throws Exception{assertTrue(p.waitFor(7,TimeUnit.SECONDS));return new String(p.getInputStream().readAllBytes()).trim();}
    @Test public void androidShellEarlyReleaseAndRefusedUnknownStartNeverRefresh()throws Exception{
        for(String response:List.of("Result: Parcel(00000000    '....')","malformed")){
            Path release=context.getCacheDir().toPath().resolve(UUID.randomUUID().toString());String script=shell(echo(response),echo(FakeVehicle.OK),release);
            String result=output(new ProcessBuilder("/system/bin/sh","-c",script).start());assertFalse(result.contains("@keepalive"));assertTrue(NativeController.holdResult(result).getBoolean("stop_accepted"));
        }
        Path release=context.getCacheDir().toPath().resolve(UUID.randomUUID().toString());Files.createFile(release);
        String result=output(new ProcessBuilder("/system/bin/sh","-c",shell(echo(FakeVehicle.OK),echo(FakeVehicle.OK),release)).start());assertFalse(result.contains("@start"));assertTrue(NativeController.holdResult(result).getBoolean("cancelled"));
    }
    @Test public void androidShellReleaseKeepaliveAndDeadline()throws Exception{
        Path release=context.getCacheDir().toPath().resolve(UUID.randomUUID().toString());
        Process p=new ProcessBuilder("/system/bin/sh","-c",shell(echo(FakeVehicle.OK),echo(FakeVehicle.OK),release)).start();Thread.sleep(450);Files.createFile(release);String out=output(p);assertTrue(out.contains("@keepalive"));assertTrue(NativeController.holdResult(out).getBoolean("accepted"));
        Path deadline=context.getCacheDir().toPath().resolve(UUID.randomUUID().toString());long start=android.os.SystemClock.elapsedRealtime();out=output(new ProcessBuilder("/system/bin/sh","-c",shell(echo(FakeVehicle.OK),echo(FakeVehicle.OK),deadline)).start());long elapsed=android.os.SystemClock.elapsedRealtime()-start;android.util.Log.i("NativeRemoteTest","deadline-ms="+elapsed);assertTrue("deadline ms="+elapsed,elapsed>=4700&&elapsed<5400);assertTrue(NativeController.holdResult(out).getBoolean("stop_accepted"));
    }
    @Test public void androidShellBrokenOutputStillStopsBeforePrintingStopAck()throws Exception{
        Path release=context.getCacheDir().toPath().resolve(UUID.randomUUID().toString()),evidence=context.getCacheDir().toPath().resolve(UUID.randomUUID().toString());String stop="echo stopped > "+evidence+"; "+echo(FakeVehicle.OK);
        Process p=new ProcessBuilder("/system/bin/sh","-c",shell(echo(FakeVehicle.OK),stop,release)).start();p.getInputStream().close();assertTrue(p.waitFor(3,TimeUnit.SECONDS));assertTrue(Files.exists(evidence));
    }
}
