package dev.denza.vehiclecontrol.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class VoiceCommandsTest {
    Path directory;
    JSONObject catalog;
    FakeVehicle fake;
    NativeController controller;

    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("native-voice-test");
        catalog = new JSONObject(Files.readString(Path.of(System.getProperty("vehicle.catalog"))));
        fake = new FakeVehicle(catalog);
        controller = controller(fake);
    }

    NativeController controller(NativeController.Shell shell) throws Exception {
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

    static JSONObject recognized(String text, double confidence) throws Exception {
        JSONArray words = new JSONArray();
        for (String word : text.split(" "))
            words.put(new JSONObject().put("word", word).put("conf", confidence));
        return new JSONObject().put("text", text).put("result", words);
    }

    static List<String> targets(VoiceCommands.Plan plan) {
        return plan.commands().stream().map(VoiceCommands.Command::target).toList();
    }

    @Test public void wholePhrasesMapOnlyToNamedWindowsAndImmutablePlans() throws Exception {
        assertEquals(List.of("fl", "fr", "rl", "rr"), targets(VoiceCommands.parseText("открой окна")));
        assertEquals(List.of("fl", "fr"), targets(VoiceCommands.parseText("закрой передние окна")));
        assertEquals(List.of("rl", "rr"), targets(VoiceCommands.parseText("останови задние окна")));
        assertEquals(List.of("fl"), targets(VoiceCommands.parseText("открой водительское окно")));
        assertEquals(List.of("fr"), targets(VoiceCommands.parseText("закрой переднее правое окно")));
        assertEquals(List.of("rl"), targets(VoiceCommands.parseText("открой заднее левое окно")));
        assertEquals(List.of("rr"), targets(VoiceCommands.parseText("останови заднее правое окно")));
        VoiceCommands.Plan plan = VoiceCommands.parseText("  ОТКРОЙ   ВСЕ ОКНА  ");
        assertEquals("открой все окна", plan.text());
        assertThrows(UnsupportedOperationException.class, () -> plan.commands().clear());
        assertThrows(UnsupportedOperationException.class, () -> VoiceCommands.phrases().clear());
        assertThrows(IllegalArgumentException.class, () -> new VoiceCommands.Command("door.trunk", "open"));
        assertThrows(IllegalArgumentException.class, () -> new VoiceCommands.Command("fl", "1"));
    }

    @Test public void grammarAndParserAgreeAndIncludeUnknownEscape() throws Exception {
        JSONArray grammar = new JSONArray(VoiceCommands.grammarJson());
        int accepted=0,rejected=0;
        java.util.Set<String> positive = java.util.Set.copyOf(VoiceCommands.phrases());
        for (int i = 0; i < grammar.length() - 1; i++) {
            String phrase = grammar.getString(i);
            if(!positive.contains(phrase)){assertThrows(IOException.class,()->VoiceCommands.parseVosk(recognized(phrase,.99).toString()));rejected++;}
            else{assertEquals(phrase, VoiceCommands.parseVosk(recognized(phrase, .99).toString()).text());accepted++;}
        }
        assertEquals(VoiceCommands.phrases().size(),accepted);
        assertTrue("ASR must retain negation instead of dropping it",rejected>0);
        assertEquals("[unk]", grammar.getString(grammar.length() - 1));
    }

    @Test public void clearObjectsWithWeakConjunctionStillComposeButWeakDirectionRejects() throws Exception {
        // Actual offline RU-model sample: the short «и» scored .67835, named directions scored1.
        JSONObject result=recognized("закрой левое и правое окно",1);
        result.getJSONArray("result").getJSONObject(2).put("conf",.67835);
        assertEquals(List.of("fl","fr"),targets(VoiceCommands.parseVosk(result.toString())));
        result.getJSONArray("result").getJSONObject(1).put("conf",.84);
        assertThrows(IOException.class,()->VoiceCommands.parseVosk(result.toString()));
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void unknownNegationExtraWordsAndMultipleClausesNeverTouchTransport() {
        for (String text : List.of("", "окна", "не открывай окна", "не открой окна", "открой окна потом замки",
                "открой окна!", "открой окно", "открой люк", "открой fl", "открой 1222639670", "[unk]")) {
            assertThrows(text, IOException.class, () -> VoiceCommands.execute(
                    VoiceCommands.parseText(text), controller, () -> true));
        }
        assertEquals(0, fake.opens);
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void naturalDirectionalAliasesRemainExactAndRearWinsOverBareSide() throws Exception {
        for (String target : List.of("левое окно", "окно слева", "водительское", "окно водителя", "левое переднее окно", "окно спереди слева"))
            assertEquals(target, List.of("fl"), targets(VoiceCommands.parseText("открой " + target)));
        for (String target : List.of("правое окно", "пассажирское окно", "окно переднего пассажира", "правое переднее", "окно спереди справа"))
            assertEquals(target, List.of("fr"), targets(VoiceCommands.parseText("закрой " + target)));
        for (String target : List.of("левое заднее", "заднее левое окно", "окно сзади слева", "левое заднее сзади", "окно левое сзади"))
            assertEquals(target, List.of("rl"), targets(VoiceCommands.parseText("опусти " + target)));
        for (String target : List.of("правое заднее", "заднее правое окно", "окно сзади справа", "правое заднее сзади", "окно правое сзади"))
            assertEquals(target, List.of("rr"), targets(VoiceCommands.parseText("подними " + target)));
        assertEquals(List.of("fl", "rl"), targets(VoiceCommands.parseText("открыть левые окна")));
        assertEquals(List.of("fr", "rr"), targets(VoiceCommands.parseText("закрыть окна справа")));
        assertEquals(List.of("rl", "rr"), targets(VoiceCommands.parseText("останови движение задние окна")));
        assertEquals(List.of("fl", "fr", "rl", "rr"), targets(VoiceCommands.parseText("прекрати движение все окна")));
    }

    @Test public void conjunctionsPolitenessAndCorrectionsProduceFinalPerWindowActionsBeforeWrites() throws Exception {
        VoiceCommands.Plan shared = VoiceCommands.parseText("пожалуйста открой левое и правое окно пожалуйста");
        assertEquals(List.of(new VoiceCommands.Command("fl", "open"), new VoiceCommands.Command("fr", "open")), shared.commands());
        VoiceCommands.Plan frames = VoiceCommands.parseText("открой левое окно и закрой правое");
        assertEquals(List.of(new VoiceCommands.Command("fl", "open"), new VoiceCommands.Command("fr", "close")), frames.commands());
        VoiceCommands.Plan correction = VoiceCommands.parseVosk(recognized("открой левое окно нет закрой", .99).toString());
        assertEquals(List.of(new VoiceCommands.Command("fl", "close")), correction.commands());
        assertTrue(VoiceCommands.execute(correction, controller, () -> true).complete());
        assertEquals(expectedWrites("close", "fl"), fake.writes);
        assertEquals(List.of(new VoiceCommands.Command("fl", "close")),
                VoiceCommands.parseText("открой левое окно и закрой левое окно").commands());
        VoiceCommands.Plan group = VoiceCommands.parseText("открой все окна и закрой задние окна");
        assertEquals(List.of(new VoiceCommands.Command("fl", "open"), new VoiceCommands.Command("fr", "open"),
                new VoiceCommands.Command("rl", "close"), new VoiceCommands.Command("rr", "close")), group.commands());
    }

    @Test public void anyUnknownTailAmbiguousRetargetOrNegationRejectsEntireCompositeWithoutWrites() {
        for (String text : List.of("открой левое окно и закрой дверь", "открой левое окно и", "открой левое окно закрой правое",
                "открой левое окно и правое на половину", "открой окна на пятьдесят процентов", "открой окна на 50",
                "открой левое окно и не закрой правое", "открой левое окно нет закрой правое окно", "открой левое правое окно",
                "открой левое заднее спереди", "открой окно сзади", "нет закрой левое окно", "открой окна нет", "открой окна нет не закрывай")) {
            assertThrows(text, IOException.class, () -> VoiceCommands.execute(VoiceCommands.parseText(text), controller, () -> true));
        }
        assertEquals(0, fake.opens);
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void finalRecognitionNeedsEveryWordAboveThresholdAndExactTextAgreement() throws Exception {
        assertEquals("открой окна", VoiceCommands.parseVosk(recognized("открой окна", .85).toString()).text());
        JSONObject low = recognized("открой окна", .99);
        low.getJSONArray("result").getJSONObject(1).put("conf", .849999);
        JSONObject mismatch = recognized("открой окна", .99);
        mismatch.getJSONArray("result").getJSONObject(0).put("word", "закрой");
        JSONObject unknown = recognized("открой окна", .99);
        unknown.getJSONArray("result").getJSONObject(0).put("word", "[unk]");
        JSONObject stringScore = recognized("открой окна", .99);
        stringScore.getJSONArray("result").getJSONObject(0).put("conf", "0.99");
        JSONObject missing = recognized("открой окна", .99);
        missing.getJSONArray("result").getJSONObject(0).remove("conf");
        JSONObject combinedWords = recognized("открой окна", .99);
        combinedWords.put("result", new JSONArray().put(new JSONObject().put("word", "открой окна").put("conf", .99)));
        for (String json : List.of(low.toString(), mismatch.toString(), unknown.toString(), stringScore.toString(),
                missing.toString(), combinedWords.toString(), recognized("открой окна", 1.01).toString(),
                recognized("открой окна", -1).toString(), "{}", "{\"text\":\"открой окна\"}",
                "{\"partial\":\"открой окна\"}", "{\"text\":\"открой окна\",\"result\":[]}",
                recognized("открой окна", .99) + "{}", "broken")) {
            assertThrows(json, IOException.class, () -> VoiceCommands.execute(
                    VoiceCommands.parseVosk(json), controller, () -> true));
        }
        assertEquals(0, fake.opens);
        assertTrue(fake.writes.isEmpty());
    }

    List<String> expectedWrites(String action, String... targets) throws Exception {
        java.util.ArrayList<String> writes = new java.util.ArrayList<>();
        for (String target : targets) writes.add(controller.command(target, action).shell());
        return writes;
    }

    @Test public void allWindowsUseExistingCatalogSeriallyAndReadFreshGuardsForEach() throws Exception {
        VoiceCommands.Execution result = VoiceCommands.execute(
                VoiceCommands.parseVosk(recognized("открой окна", .99).toString()), controller, () -> true);
        assertTrue(result.complete());
        assertEquals(expectedWrites("open", "fl", "fr", "rl", "rr"), fake.writes);
        assertEquals(4, fake.opens);
        assertEquals(4, result.outcomes().size());
        for (var outcome : result.outcomes()) {
            assertEquals("accepted", outcome.status());
            assertTrue(outcome.message().contains("движение не подтверждено"));
        }
        assertThrows(UnsupportedOperationException.class, () -> result.outcomes().clear());
    }

    @Test public void namedRightWindowCloseUsesExactSingleWrite() throws Exception {
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("закрой переднее правое окно"), controller, () -> true).complete());
        assertEquals(expectedWrites("close", "fr"), fake.writes);
    }

    @Test public void feminineDoorAliasesAreSeparateFromWindowAliasesAndCompose() throws Exception {
        for (String target : List.of("левую дверь", "водительскую дверь", "дверь водителя", "левую", "дверь спереди слева"))
            assertEquals(target, List.of("door.fl"), targets(VoiceCommands.parseText("открой " + target)));
        for (String target : List.of("правую дверь", "пассажирскую дверь", "дверь переднего пассажира", "правую", "дверь спереди справа"))
            assertEquals(target, List.of("door.fr"), targets(VoiceCommands.parseText("закрой " + target)));
        for (String target : List.of("левую заднюю дверь", "заднюю левую дверь", "дверь сзади слева", "левую дверь сзади"))
            assertEquals(target, List.of("door.rl"), targets(VoiceCommands.parseText("открыть " + target)));
        for (String target : List.of("правую заднюю дверь", "заднюю правую дверь", "дверь сзади справа", "правую дверь сзади"))
            assertEquals(target, List.of("door.rr"), targets(VoiceCommands.parseText("закрывай " + target)));
        assertEquals(List.of("door.fl", "door.fr"), targets(VoiceCommands.parseText("открой левую и правую дверь")));
        assertEquals(List.of("door.fl", "door.rl"), targets(VoiceCommands.parseText("открой левые двери")));
        assertEquals(List.of("door.fr", "door.rr"), targets(VoiceCommands.parseText("закрой правые двери")));
        assertEquals(List.of("door.rl", "door.rr"), targets(VoiceCommands.parseText("останови задние двери")));
        assertEquals(List.of("fl"), targets(VoiceCommands.parseText("открой левое")));
        assertEquals(List.of("rl"), targets(VoiceCommands.parseText("открой сзади слева")));
        assertEquals(List.of(new VoiceCommands.Command("fl", "open"), new VoiceCommands.Command("door.fr", "close")),
                VoiceCommands.parseText("открой левое окно и закрой правую дверь").commands());
        assertEquals(List.of(new VoiceCommands.Command("door.fl", "close")),
                VoiceCommands.parseText("открой водительскую дверь нет закрой").commands());
    }

    @Test public void incompatibleDoorActionsContradictoryNounsLocksAndUnknownTailNeverTouchTransport() {
        for (String text : List.of("опусти левую дверь", "подними дверь водителя", "открой левую дверь нет опусти",
                "опусти левое окно и правую дверь", "подними двери", "открой левое дверь", "открой левую окно",
                "открой водительское дверь", "открой водительскую окно", "открой двери окна", "запри двери", "отопри двери",
                "открой багажник", "открой двери и закрой багажник", "открой дверь", "открой левую правую дверь",
                "открой левую дверь и потом не закрой правую дверь", "открой левую дверь затем закрой люк"))
            assertThrows(text, IOException.class, () -> VoiceCommands.execute(VoiceCommands.parseText(text), controller, () -> true));
        assertEquals(0, fake.opens);
        assertTrue(fake.writes.isEmpty());
    }

    List<String> catalogDoorWrites(int value, String... doors) throws Exception {
        java.util.ArrayList<String> expected = new java.util.ArrayList<>();
        for (String door : doors) {
            String property = catalog.getJSONObject("doors").getJSONObject(door).getString("set");
            JSONArray address = catalog.getJSONObject("properties").getJSONArray(property);
            expected.add("service call autoservice 6 i32 " + address.getInt(0) + " i32 " + address.getInt(1) + " i32 " + value + " null");
        }
        return expected;
    }

    @Test public void allFourDoorOpenAndCloseWritesComeOnlyFromSharedCatalog() throws Exception {
        assertTrue(VoiceCommands.execute(VoiceCommands.parseVosk(recognized("открой все двери", 1).toString()), controller, () -> true).complete());
        assertEquals(catalogDoorWrites(1, "fl", "fr", "rl", "rr"), fake.writes);
        assertEquals(4, fake.opens);
        fake.writes.clear();
        VoiceCommands.Execution result = VoiceCommands.execute(VoiceCommands.parseText("закрой двери"), controller, () -> true);
        assertTrue(result.complete());
        assertEquals(catalogDoorWrites(2, "fl", "fr", "rl", "rr"), fake.writes);
        assertTrue(result.message().contains("Передняя левая дверь · закрыть: Запрос принят"));
        assertTrue(result.message().contains("Задняя правая дверь · закрыть: Запрос принят"));
    }

    @Test public void everyExistingDoorGuardIsFreshAndFailsClosedBeforeAnyWrite() throws Exception {
        controller.snapshot();
        JSONObject door = catalog.getJSONObject("doors").getJSONObject("fl");
        JSONObject locks = catalog.getJSONObject("locks");
        java.util.Map<String, Object> bad = new java.util.LinkedHashMap<>();
        bad.put("park", 0);
        bad.put(locks.getString("config"), 0);
        bad.put(locks.getString("thermal"), 1);
        bad.put(door.getString("initialized"), 0);
        bad.put(door.getString("position"), 65535);
        bad.put(door.getString("obstacle"), 1);
        bad.put(door.getString("protection"), 1);
        for (var guard : bad.entrySet()) {
            Object previous = fake.values.get(guard.getKey());
            for (Object invalid : new Object[] {guard.getValue(), null}) {
                fake.values.put(guard.getKey(), invalid);
                VoiceCommands.Execution result = VoiceCommands.execute(VoiceCommands.parseText("открой водительскую дверь"), controller, () -> true);
                assertFalse(guard.getKey(), result.complete());
                assertTrue(guard.getKey(), fake.writes.isEmpty());
                assertEquals("refused", result.outcomes().get(0).status());
            }
            fake.values.put(guard.getKey(), previous);
        }
        NativeController changedAfterFirst = controller((command, timeout) -> {
            String output = fake.run(command, timeout);
            if (command.startsWith("service call autoservice 6")) fake.values.put("park", 0);
            return output;
        });
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("открой двери"), changedAfterFirst, () -> true).complete());
        assertEquals(catalogDoorWrites(1, "fl"), fake.writes);
    }

    @Test public void doorStopBypassesMotionGuardsButStillRequiresPinnedIdentityAndBoot() throws Exception {
        controller.snapshot();
        fake.values.put("park", 65535);
        JSONObject locks = catalog.getJSONObject("locks");
        fake.values.put(locks.getString("config"), null);
        fake.values.put(locks.getString("thermal"), 1);
        JSONObject driver = catalog.getJSONObject("doors").getJSONObject("fl");
        for (String field : List.of("initialized", "position", "obstacle", "protection")) fake.values.put(driver.getString(field), null);
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("останови двери"), controller, () -> true).complete());
        assertEquals(catalogDoorWrites(3, "fl", "fr", "rl", "rr"), fake.writes);
        fake.writes.clear();
        fake.boot = "22222222-2222-2222-2222-222222222222";
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("стоп двери"), controller, () -> true).complete());
        assertTrue(fake.writes.isEmpty());
        fake.badHash = true;
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("останови двери"), controller(fake), () -> true).complete());
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void doorRefusalContinuesButUnknownAndCancellationStopWithoutRetry() throws Exception {
        for (String raw : List.of("Result: Parcel(00000000    '....')", "broken")) {
            fake.writes.clear();
            fake.setReply = raw;
            VoiceCommands.Execution result = VoiceCommands.execute(VoiceCommands.parseText("открой двери"), controller, () -> true);
            assertFalse(result.complete());
            boolean refused = raw.startsWith("Result:");
            assertEquals(refused ? catalogDoorWrites(1, "fl", "fr", "rl", "rr") : catalogDoorWrites(1, "fl"), fake.writes);
            assertEquals(refused ? 4 : 1, result.outcomes().size());
        }
        fake.writes.clear();
        fake.setReply = FakeVehicle.OK;
        int before = fake.opens;
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("открой двери"), controller, () -> false).complete());
        assertEquals(before, fake.opens);
        AtomicBoolean foreground = new AtomicBoolean(true);
        NativeController cancelledDuringRead = controller((command, timeout) -> {
            String output = fake.run(command, timeout);
            if (command.contains("echo @park")) foreground.set(false);
            return output;
        });
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("открой двери"), cancelledDuringRead, foreground::get).complete());
        assertTrue(fake.writes.isEmpty());
        foreground.set(true);
        NativeController cancelled = controller((command, timeout) -> {
            String output = fake.run(command, timeout);
            if (command.startsWith("service call autoservice 6")) foreground.set(false);
            return output;
        });
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("открой двери потом закрой окна"), cancelled, foreground::get).complete());
        assertEquals(catalogDoorWrites(1, "fl"), fake.writes);
    }

    @Test public void changedParkAfterFirstWindowStopsRemainingGroup() throws Exception {
        NativeController guarded = controller((command, timeout) -> {
            String result = fake.run(command, timeout);
            if (command.startsWith("service call autoservice 6")) fake.values.put("park", 65535);
            return result;
        });
        VoiceCommands.Execution result = VoiceCommands.execute(VoiceCommands.parseText("открой окна"), guarded, () -> true);
        assertFalse(result.complete());
        assertEquals(expectedWrites("open", "fl"), fake.writes);
        assertEquals(4, fake.opens);
        assertEquals("refused", result.outcomes().get(1).status());
        assertTrue(result.outcomes().get(1).message().contains("только в P"));
        assertTrue(result.message().contains("Переднее левое · открыть: Запрос принят"));
        assertTrue(result.message().contains("Переднее правое · открыть: Управление доступно только в P"));
    }

    @Test public void thermalOrUnknownPositionBlocksVoiceExactlyAsManualControl() throws Exception {
        fake.values.put("fr_thermal", 1);
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("закрой передние окна"), controller, () -> true).complete());
        assertEquals(expectedWrites("close", "fl"), fake.writes);
        fake.writes.clear();
        fake.values.put("fl_percent", null);
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("открой водительское окно"), controller, () -> true).complete());
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void stopRetainsNativeIdentityGuardWhileBypassingParkAndThermal() throws Exception {
        fake.values.put("park", 65535);
        fake.values.put("fl_thermal", 1);
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("останови окна"), controller, () -> true).complete());
        assertEquals(expectedWrites("stop", "fl", "fr", "rl", "rr"), fake.writes);
        fake.writes.clear();
        fake.boot = "22222222-2222-2222-2222-222222222222";
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("останови окна"), controller, () -> true).complete());
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void definiteRefusalContinuesWithoutRetryButUncertaintyStopsRemainder() throws Exception {
        for (String reply : List.of(FakeVehicle.reply(0), "Result: Parcel(00000000    '....')", "broken")) {
            fake.writes.clear();
            fake.setReply = reply;
            VoiceCommands.Execution result = VoiceCommands.execute(VoiceCommands.parseText("открой окна"), controller, () -> true);
            assertFalse(result.complete());
            boolean refused = reply.startsWith("Result: Parcel(00000000    '");
            assertEquals(refused ? expectedWrites("open", "fl", "fr", "rl", "rr") : expectedWrites("open", "fl"), fake.writes);
            assertEquals(refused ? 4 : 1, result.outcomes().size());
            assertEquals(refused ? "refused" : "uncertain", result.outcomes().get(0).status());
        }
    }

    @Test public void pausedOrCancelledCommandCannotStartOrQueueRemainingWindows() throws Exception {
        VoiceCommands.Plan plan = VoiceCommands.parseText("открой окна");
        assertFalse(VoiceCommands.execute(plan, controller, () -> false).complete());
        assertFalse(VoiceCommands.execute(VoiceCommands.parseText("останови окна"), controller, () -> false).complete());
        assertEquals(0, fake.opens);
        AtomicBoolean foreground = new AtomicBoolean(true);
        NativeController cancelledDuringRead = controller((command, timeout) -> {
            String result = fake.run(command, timeout);
            if (command.contains("echo @park")) foreground.set(false);
            return result;
        });
        VoiceCommands.Execution result = VoiceCommands.execute(plan, cancelledDuringRead, foreground::get);
        assertFalse(result.complete());
        assertEquals("cancelled", result.outcomes().get(0).status());
        assertTrue(fake.writes.isEmpty());
        foreground.set(true);
        NativeController cancelledAfterWrite = controller((command, timeout) -> {
            String output = fake.run(command, timeout);
            if (command.startsWith("service call autoservice 6")) foreground.set(false);
            return output;
        });
        result = VoiceCommands.execute(plan, cancelledAfterWrite, foreground::get);
        assertFalse(result.complete());
        assertEquals(expectedWrites("open", "fl"), fake.writes);
        assertEquals(1, result.outcomes().size());
    }
}
