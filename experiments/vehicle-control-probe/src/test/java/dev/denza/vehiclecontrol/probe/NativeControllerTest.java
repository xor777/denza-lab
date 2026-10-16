package dev.denza.vehiclecontrol.probe;

import org.junit.*;
import static org.junit.Assert.*;
import org.json.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class NativeControllerTest {
    static final String OK="Result: Parcel(00000001    '....')";
    static JSONObject catalog() throws Exception {return new JSONObject(Files.readString(Path.of(System.getProperty("vehicle.catalog"))));}
    static String reply(int value){return String.format("Result: Parcel(00000000 %08x   '........')",value);}
    Path directory;JSONObject catalog;FakeVehicle fake;NativeController controller;
    @Before public void setup()throws Exception{directory=Files.createTempDirectory("native-vehicle-test");catalog=catalog();fake=new FakeVehicle(catalog);controller=new NativeController(catalog,()->{fake.opens++;return new NativeController.Connection("same-key",fake);},directory.resolve("actions.jsonl").toFile());}
    @After public void cleanup()throws Exception{try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    @Test public void scalarParsersAreStrictAndDistinct()throws Exception{
        assertEquals(1,NativeController.parseSet(OK));assertEquals(-1,(int)NativeController.parseGet(reply(-1)));
        for(String bad:List.of(OK+" extra",reply(1),"Result: Parcel(00000001 00000001 '........')"))assertThrows(IOException.class,()->NativeController.parseSet(bad));
        for(String bad:List.of(OK,"Result: Parcel(00000001 00000001 '........')",reply(1)+reply(2)))assertThrows(IOException.class,()->NativeController.parseGet(bad));
    }
    @Test public void snapshotIsReadOnlyAndFloatUsesTxn7()throws Exception{
        JSONObject s=controller.snapshot();assertEquals(6,s.getJSONObject("seats").length());assertEquals(4,s.getJSONObject("massages").length());assertEquals(42,s.getJSONObject("windows").getJSONObject("fl").getDouble("percent"),0);assertTrue(fake.writes.isEmpty());assertTrue(controller.readCommand("BODY_DRIVER_SEAT_HORIZONTAL_POSITION").startsWith("service call autoservice 7 "));
    }
    @Test public void exactWindowDoorShadeLockLightMassageDispatch()throws Exception{
        for(String key:NativeController.keys(catalog.getJSONObject("windows"))){assertEquals(NativeController.setCommand(1001,catalog.getJSONObject("windows").getJSONArray(key).getInt(1),1),controller.command(key,"open").shell());assertTrue(controller.act(key,"open").getBoolean("accepted"));}
        assertTrue(controller.command("shade.front","open").shell().endsWith("i32 100 null"));assertTrue(controller.command("shade.front","stop").shell().endsWith("i32 254 null"));
        assertTrue(controller.command("door.fl","stop").shell().endsWith("i32 3 null"));assertTrue(controller.command("locks","lock").shell().endsWith("i32 2 null"));
        assertTrue(controller.command("light.matrix","on").shell().endsWith("i32 1 null"));assertTrue(controller.command("light.matrix","off").shell().endsWith("i32 2 null"));
        for(String seat:NativeController.keys(catalog.getJSONObject("massages")))for(String action:List.of("on","off","wave","pulse","stretch","waist","shoulders","level1","level2","level3"))assertTrue(controller.act("massage."+seat,action).getBoolean("accepted"));
    }
    @Test public void allSeatAxesUseCatalogAndStopZeroWithoutTapPulse()throws Exception{
        for(String seat:NativeController.keys(catalog.getJSONObject("seats"))){JSONObject s=catalog.getJSONObject("seats").getJSONObject(seat);for(String axis:NativeController.keys(s.getJSONObject("axes"))){String target="seat."+seat+"."+axis;assertTrue(controller.command(target,"plus").pulse());assertTrue(controller.command(target,"minus").shell().endsWith("i32 2 null"));assertTrue(controller.command(target,"stop").shell().endsWith("i32 0 null"));assertThrows(IOException.class,()->controller.act(target,"plus"));}}
        assertTrue(controller.command("comfort.fl.heat","3").shell().endsWith("i32 6 null"));
    }
    @Test public void allowlistRejectsInjectionBeforeTransport()throws Exception{for(String target:List.of("all","seat.fl.unknown","fl;reboot","massage.third","light.unknown"))assertThrows(Exception.class,()->controller.act(target,"open"));assertEquals(0,fake.opens);assertThrows(Exception.class,()->controller.act("comfort.fl.heat","4"));assertThrows(Exception.class,()->controller.act("light.headlights","3"));}
    @Test public void freshParkUnknownAndThermalGuardsFailClosedStopBypasses()throws Exception{
        controller.snapshot();fake.values.put("park",65535);
        for(String[] a:List.of(new String[]{"fl","open"},new String[]{"door.fl","open"},new String[]{"locks","unlock"},new String[]{"shade.front","open"},new String[]{"light.headlights","auto"},new String[]{"massage.fl","on"},new String[]{"comfort.fl.heat","1"}))assertThrows(IOException.class,()->controller.act(a[0],a[1]));assertTrue(fake.writes.isEmpty());assertTrue(controller.act("fl","stop").getBoolean("accepted"));assertTrue(controller.act("shade.front","stop").getBoolean("accepted"));assertTrue(controller.act("seat.fl.horizontal","stop").getBoolean("accepted"));
    }
    @Test public void unknownConfigPositionInitObstacleAndAsymmetricLightFeedbackBlock()throws Exception{
        JSONObject door=catalog.getJSONObject("doors").getJSONObject("fl");for(String field:List.of("initialized","position","obstacle","protection")){Object old=fake.values.put(door.getString(field),null);assertThrows(IOException.class,()->controller.act("door.fl","open"));fake.values.put(door.getString(field),old);}
        JSONObject shade=catalog.getJSONObject("shades").getJSONObject("front");for(String field:List.of("config","position","initialized","thermal")){Object old=fake.values.put(shade.getString(field),null);assertThrows(IOException.class,()->controller.act("shade.front","open"));fake.values.put(shade.getString(field),old);}
        JSONArray matrix=catalog.getJSONObject("lights").getJSONObject("matrix").getJSONArray("states");fake.values.put(matrix.getString(0),1);fake.values.put(matrix.getString(1),2);assertThrows(IOException.class,()->controller.act("light.matrix","on"));assertTrue(fake.writes.isEmpty());
    }
    @Test public void hashOrBootChangeBlocksEvenStop()throws Exception{fake.badHash=true;assertThrows(IOException.class,controller::snapshot);fake.badHash=false;controller.snapshot();fake.boot="22222222-2222-2222-2222-222222222222";assertThrows(IOException.class,()->controller.act("fl","stop"));assertTrue(fake.writes.isEmpty());}
    @Test public void refusedOrUnknownSetDoesNotRetry()throws Exception{fake.setReply="broken";JSONObject r=controller.act("fl","open");assertTrue(r.getBoolean("uncertain"));assertFalse(r.getBoolean("accepted"));assertEquals(1,fake.writes.size());}
    @Test public void cancellationDuringFreshGuardsNeverStartsLater()throws Exception{
        fake.readStarted=new CountDownLatch(1);fake.readRelease=new CountDownLatch(1);NativeController.Hold h=controller.newHold("seat.fl.horizontal","plus");ExecutorService e=Executors.newSingleThreadExecutor();try{Future<JSONObject> result=e.submit(()->controller.hold(h));assertTrue(fake.readStarted.await(1,TimeUnit.SECONDS));controller.release(h);fake.readRelease.countDown();assertTrue(result.get().getBoolean("cancelled"));assertTrue(fake.writes.isEmpty());}finally{e.shutdownNow();}
    }
    @Test public void releaseUsesIndependentConnectionWhileHoldBusyNoQueuedStart()throws Exception{
        fake.holdStarted=new CountDownLatch(1);fake.holdRelease=new CountDownLatch(1);NativeController.Hold h=controller.newHold("seat.fl.horizontal","plus");ExecutorService e=Executors.newSingleThreadExecutor();try{Future<JSONObject>result=e.submit(()->controller.hold(h));assertTrue(fake.holdStarted.await(1,TimeUnit.SECONDS));assertThrows(NativeController.Busy.class,controller::snapshot);assertThrows(NativeController.Busy.class,()->controller.act("fl","open"));controller.release(h);assertTrue(result.get().getBoolean("stop_accepted"));assertTrue(controller.pendingStops.isEmpty());assertEquals(1,fake.writes.size());assertTrue(fake.opens>=2);}finally{e.shutdownNow();}
    }
    @Test public void missingStopLatchesAcrossNativeAndExistingJournalReplay()throws Exception{
        fake.holdReply="@start\n"+OK;controller.hold(controller.newHold("seat.fl.horizontal","plus"));assertEquals(Set.of("seat.fl.horizontal"),controller.pendingStops);
        NativeController recreated=new NativeController(catalog,()->new NativeController.Connection("same-key",fake),directory.resolve("actions.jsonl").toFile());assertEquals(controller.pendingStops,recreated.pendingStops);assertThrows(IOException.class,()->recreated.hold(recreated.newHold("seat.fr.horizontal","plus")));assertTrue(recreated.act("seat.fl.horizontal","stop").getBoolean("accepted"));assertTrue(recreated.pendingStops.isEmpty());
    }
    @Test public void holdParsesKeepaliveAndMalformedStartButKnownStopSeparately()throws Exception{
        assertTrue(NativeController.holdResult("@start\n"+OK+"\n@keepalive\n"+OK+"\n@stop\n"+OK).getBoolean("accepted"));JSONObject malformed=NativeController.holdResult("@start\nbroken\n@stop\n"+OK);assertFalse(malformed.getBoolean("accepted"));assertTrue(malformed.getBoolean("uncertain"));assertTrue(malformed.getBoolean("stop_accepted"));assertTrue(NativeController.holdResult("@cancel\n@stop\n"+OK).getBoolean("cancelled"));
    }
    @Test public void massageUnknownAndMode7OffBaselineNeverUnboxNull()throws Exception{
        JSONObject spec=catalog.getJSONObject("massages").getJSONObject("fl");
        for(Integer mode:new Integer[]{7,65535,null}){
            fake.values.put(spec.getString("mode"),mode);fake.values.put(spec.getString("level"),0);
            JSONObject massage=controller.snapshot().getJSONObject("massages").getJSONObject("fl");assertTrue(massage.isNull("enabled"));
            if(mode==null||mode==65535)assertThrows(IOException.class,()->controller.act("massage.fl","on"));
        }
    }
    @Test public void journalFailurePreventsStartAndPausedIntentCannotWrite()throws Exception{
        Files.createDirectory(directory.resolve("actions.jsonl"));assertThrows(Exception.class,()->controller.hold(controller.newHold("seat.fl.horizontal","plus")));assertTrue(fake.writes.isEmpty());
        assertThrows(IOException.class,()->controller.act("fl","open",()->false));assertTrue(fake.writes.isEmpty());
    }

    void useRealClimateFixture()throws Exception{
        JSONObject fixture;try(var input=getClass().getResourceAsStream("/hvac-n9-20261016.json")){fixture=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        String[] lines=fixture.getString("raw").trim().split("\\n");for(int i=0;i<lines.length;i+=2)fake.rawOverrides.put(lines[i],lines[i+1]);
    }
    @Test public void capturedClimateParcelsTraverseReadPipelineAndDecodeAllThirtyControls()throws Exception{
        useRealClimateFixture();JSONObject climate=controller.snapshot().getJSONObject("climate");assertEquals(30,climate.length());
        for(String key:NativeController.keys(climate))assertTrue(key+": "+climate.getJSONObject(key).optString("blocked"),climate.getJSONObject(key).isNull("blocked"));
        assertEquals("23",climate.getJSONObject("driver_temperature").getString("state"));assertEquals("21",climate.getJSONObject("middle_left_temperature").getString("state"));assertEquals("24",climate.getJSONObject("rear_temperature").getString("state"));assertEquals("off",climate.getJSONObject("driver_auto").getString("state"));assertEquals("0",climate.getJSONObject("driver_fan").getString("state"));
        assertTrue(controller.command("climate.driver_fan","0").shell().endsWith("i32 8 null"));assertTrue(controller.command("climate.rear_auto","on").shell().endsWith("i32 0 null"));assertTrue(controller.command("climate.driver_power","off").shell().endsWith("i32 2 null"));assertTrue(controller.command("climate.power","off").shell().endsWith("i32 0 null"));assertTrue(controller.command("climate.driver_temperature","23").shell().endsWith("i32 23 null"));
        for(String key:NativeController.keys(catalog.getJSONObject("climate"))){JSONObject c=catalog.getJSONObject("climate").getJSONObject(key);for(String action:NativeController.keys(c.getJSONObject("values")))assertTrue(controller.act("climate."+key,action).getBoolean("accepted"));}
    }
    @Test public void climateSentinelOnlyAllowsObservedPrecisionErrorOtherErrorsAndUnitsBlock()throws Exception{
        useRealClimateFixture();fake.rawOverrides.put("AC_TEMPERATURE_ADJUST_ACCURACY",reply(-10012));assertThrows(IOException.class,()->controller.act("climate.driver_temperature","23"));assertTrue(controller.act("climate.rear_temperature","24").getBoolean("accepted"));
        fake.rawOverrides.put("AC_TEMPERATURE_ADJUST_ACCURACY",reply(-10011));fake.rawOverrides.put("AC_TEMPERATURE_UNIT",reply(2));assertThrows(IOException.class,()->controller.act("climate.rear_temperature","24"));
        fake.rawOverrides.put("AC_TEMPERATURE_UNIT",reply(1));fake.rawOverrides.put("AC_COMPRESSOR_MODE",reply(-10011));assertThrows(IOException.class,()->controller.act("climate.ac","on"));
        assertThrows(Exception.class,()->controller.command("climate.driver_temperature","16"));assertThrows(Exception.class,()->controller.command("climate.driver_temperature","34"));assertThrows(Exception.class,()->controller.command("climate.rear_mode","windshield"));
    }

}
