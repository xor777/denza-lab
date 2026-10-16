package dev.denza.vehiclecontrol.probe;

import org.json.*;
import org.junit.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Complete ASR endpoints use the same guarded transport as manual controls. */
public class VoiceCommandRouteTest {
    private Path dir;
    private FakeVehicle car;
    private NativeController controller;
    @Before public void setup()throws Exception{
        dir=Files.createTempDirectory("voice-route");
        JSONObject catalog=new JSONObject(Files.readString(Path.of(System.getProperty("vehicle.catalog"))));
        car=new FakeVehicle(catalog);
        controller=new NativeController(catalog,()->{car.opens++;return new NativeController.Connection("same-key",car);},dir.resolve("actions.jsonl").toFile());
    }
    @After public void cleanup()throws Exception{try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    private static String endpoint(String text,double confidence)throws Exception{
        JSONArray words=new JSONArray();for(String word:text.split(" "))words.put(new JSONObject().put("word",word).put("conf",confidence));
        return new JSONObject().put("text",text).put("result",words).toString();
    }
    @Test public void endpointRoutesCompoundWindowClimateAndOtherRemoteFamilies()throws Exception{
        VoiceCommandRoute route=VoiceCommandRoute.prepare(endpoint("открой левое окно и поставь климат на двадцать три градуса и включи подогрев пассажира на тройку",1));
        assertTrue(route.description().contains("температура: 23 °C"));
        VoiceCommands.Execution result=route.execute(controller,()->true,true,false);
        assertTrue(result.message(),result.complete());
        assertEquals(List.of("fl","climate.driver_temperature","comfort.fr.heat"),result.outcomes().stream().map(VoiceCommands.Outcome::target).toList());
        assertEquals(3,car.writes.size());
        assertEquals(controller.command("fl","open").shell(),car.writes.get(0));
        assertEquals(controller.command("climate.driver_temperature","23").shell(),car.writes.get(1));
        assertEquals(controller.command("comfort.fr.heat","3").shell(),car.writes.get(2));
        car.writes.clear();
        result=VoiceCommandRoute.prepare(endpoint("закрой правую дверь и открой шторку и включи массаж и фары и заблокируй двери",1))
                .execute(controller,()->true,true,false);
        assertTrue(result.message(),result.complete());
        assertEquals(List.of("door.fr","shade.front","massage.fl","light.headlights","locks"),result.outcomes().stream().map(VoiceCommands.Outcome::target).toList());
        assertEquals(5,car.writes.size());
    }
    @Test public void previewNeverOpensTransportEvenWithConfirmedPlan()throws Exception{
        VoiceCommandRoute route=VoiceCommandRoute.prepare(endpoint("открой окна и включи кондиционер",1));
        assertFalse(route.execute(controller,()->true,false,true).complete());
        assertEquals(0,car.opens);assertTrue(car.writes.isEmpty());assertFalse(Files.exists(dir.resolve("actions.jsonl")));
    }
    @Test public void weakWordsNeedExplicitConfirmationAndStillUseFreshGuards()throws Exception{
        VoiceCommandRoute route=VoiceCommandRoute.prepare(endpoint("включи кондиционер",.6));
        assertTrue(route.assessment.needsConfirmation());
        assertFalse(route.execute(controller,()->true,true,false).complete());assertEquals(0,car.opens);
        assertTrue(route.execute(controller,()->true,true,true).complete());assertEquals(1,car.writes.size());
        car.writes.clear();car.values.put("park",0);
        assertFalse(route.execute(controller,()->true,true,true).complete());assertTrue(car.writes.isEmpty());
    }
    @Test public void cancelledGenerationAndInvalidWholeUtteranceCannotSendPrefix()throws Exception{
        VoiceCommandRoute route=VoiceCommandRoute.prepare(endpoint("открой окно водителя и включи кондиционер",1));
        assertFalse(route.execute(controller,()->false,true,false).complete());assertEquals(0,car.opens);
        for(String text:List.of("открой левое окно и не включай кондиционер","открой окна и включи подогрев руля"))
            assertThrows(java.io.IOException.class,()->VoiceCommandRoute.prepare(endpoint(text,1)));
        assertTrue(car.writes.isEmpty());assertEquals(0,car.opens);
    }
    @Test public void unknownWriteOutcomeStopsRemainingCommandsWithoutRetry()throws Exception{
        car.setReply="broken";
        VoiceCommands.Execution result=VoiceCommandRoute.prepare(endpoint("включи кондиционер и открой левое окно",1)).execute(controller,()->true,true,false);
        assertFalse(result.complete());assertEquals(1,car.writes.size());assertEquals("uncertain",result.outcomes().get(0).status());
    }
}
