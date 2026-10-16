package dev.denza.vehiclecontrol.probe;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Whole-utterance checks for composing the two supported kinds of actuator. */
public class VoiceMultiCommandTest {
    private Path directory;
    private JSONObject catalog;
    private FakeVehicle fake;
    private NativeController controller;

    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("native-voice-composition-test");
        catalog = new JSONObject(Files.readString(Path.of(System.getProperty("vehicle.catalog"))));
        fake = new FakeVehicle(catalog);
        controller = controller(fake);
    }

    private NativeController controller(NativeController.Shell shell) throws Exception {
        return new NativeController(catalog, () -> {
            fake.opens++;
            return new NativeController.Connection("same-key", shell);
        }, directory.resolve("actions.jsonl").toFile());
    }

    @After public void cleanup() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static List<String> commands(VoiceCommands.Plan plan) {
        return plan.commands().stream().map(command -> command.target() + ":" + command.action()).toList();
    }

    private List<String> expectedWrites(String... commands) throws Exception {
        List<String> writes = new ArrayList<>();
        for (String command : commands) {
            String[] parts = command.split(":");
            writes.add(controller.command(parts[0], parts[1]).shell());
        }
        return writes;
    }

    @Test public void mixedObjectsSharedVerbsAndSequencingCompileWithoutOpeningTransport() throws Exception {
        assertEquals(List.of("fl:close", "fr:close", "rl:close", "rr:close", "door.fr:open"),
                commands(VoiceCommands.parseText("закрой окна и открой правую дверь")));
        assertEquals(List.of("fl:open", "door.fr:open"),
                commands(VoiceCommands.parseText("открой левое окно и правую дверь")));
        assertEquals(List.of("fl:open", "fr:open", "door.rr:close"),
                commands(VoiceCommands.parseText("открой левое и правое окно потом закрой заднюю правую дверь")));
        assertEquals(List.of("fl:close", "door.fr:open"),
                commands(VoiceCommands.parseText("пожалуйста закрой водительское окно и потом открой пассажирскую дверь")));
        assertEquals(0, fake.opens);
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void compiledMixedPlanUsesExistingControllerInSpokenOrder() throws Exception {
        VoiceCommands.Plan plan = VoiceCommands.parseVosk(VoiceCommandsTest.recognized(
                "закрой окна и открой правую дверь", .99).toString());
        VoiceCommands.Execution result = VoiceCommands.execute(plan, controller, () -> true);
        assertTrue(result.message(), result.complete());
        assertEquals(expectedWrites("fl:close", "fr:close", "rl:close", "rr:close", "door.fr:open"), fake.writes);
        assertEquals(List.of("fl", "fr", "rl", "rr", "door.fr"),
                result.outcomes().stream().map(VoiceCommands.Outcome::target).toList());
        assertEquals(1, fake.opens);
        assertTrue(result.outcomes().stream().allMatch(outcome -> outcome.status().equals("accepted")));
    }

    @Test public void spokenCorrectionIsResolvedBeforeAnyWriteAndCanPrecedeAnotherFrame() throws Exception {
        VoiceCommands.Plan plan = VoiceCommands.parseText("открой левое окно нет закрой и открой правую дверь");
        assertEquals(List.of("fl:close", "door.fr:open"), commands(plan));
        assertEquals(0, fake.opens);
        assertTrue(VoiceCommands.execute(plan, controller, () -> true).complete());
        assertEquals(expectedWrites("fl:close", "door.fr:open"), fake.writes);
    }

    @Test public void lastActionCollapsesOnlyItsOwnTargetAcrossDeviceKinds() throws Exception {
        VoiceCommands.Plan plan = VoiceCommands.parseText(
                "открой левое окно и левую дверь потом закрой левое окно");
        assertEquals(List.of("fl:close", "door.fl:open"), commands(plan));
        assertTrue(VoiceCommands.execute(plan, controller, () -> true).complete());
        assertEquals(expectedWrites("fl:close", "door.fl:open"), fake.writes);
    }

    @Test public void unsupportedOrNegatedLaterClauseRejectsTheWholeUtteranceBeforeTransport() {
        for (String text : List.of(
                "открой левое окно и открой люк",
                "открой левое окно потом включи музыку",
                "открой левое окно и не открывай правую дверь",
                "закрой окна и открой правую дверь пожалуйста неизвестно",
                "открой левое окно и потом")) {
            assertThrows(text, IOException.class, () ->
                    VoiceCommands.execute(VoiceCommands.parseText(text), controller, () -> true));
            assertEquals(text, 0, fake.opens);
            assertTrue(text, fake.writes.isEmpty());
        }
    }

    @Test public void weakDoorDirectionInLaterClauseRejectsOtherwiseClearEarlierWindow() throws Exception {
        JSONObject recognized = VoiceCommandsTest.recognized("закрой левое окно и открой правую дверь", .99);
        recognized.getJSONArray("result").getJSONObject(5).put("conf", .84);
        assertThrows(IOException.class, () ->
                VoiceCommands.execute(VoiceCommands.parseVosk(recognized.toString()), controller, () -> true));
        assertEquals(0, fake.opens);
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void blockedDoorReportsRefusalWhileIndependentWindowStillExecutes() throws Exception {
        String obstacle = catalog.getJSONObject("doors").getJSONObject("fl").getString("obstacle");
        fake.values.put(obstacle, 1);
        VoiceCommands.Execution result = VoiceCommands.execute(
                VoiceCommands.parseText("открой левую дверь и закрой правое окно"), controller, () -> true);
        assertFalse(result.complete());
        assertEquals(List.of("door.fl", "fr"),
                result.outcomes().stream().map(VoiceCommands.Outcome::target).toList());
        assertEquals(List.of("refused", "accepted"),
                result.outcomes().stream().map(VoiceCommands.Outcome::status).toList());
        assertEquals(expectedWrites("fr:close"), fake.writes);
    }

    @Test public void cancellationBeforeTheScriptSendsNoDevice() throws Exception {
        AtomicBoolean foreground = new AtomicBoolean(true);
        NativeController cancellable = controller((command, timeout) -> {
            String reply = fake.run(command, timeout);
            if (command.contains("echo @park")) foreground.set(false);
            return reply;
        });
        VoiceCommands.Execution result = VoiceCommands.execute(
                VoiceCommands.parseText("открой левое окно и правую дверь"), cancellable, foreground::get);
        assertFalse(result.complete());
        assertTrue(fake.writes.isEmpty());
        assertEquals(List.of("cancelled", "cancelled"), result.outcomes().stream().map(VoiceCommands.Outcome::status).toList());
        assertEquals(1, fake.opens);
    }
}
