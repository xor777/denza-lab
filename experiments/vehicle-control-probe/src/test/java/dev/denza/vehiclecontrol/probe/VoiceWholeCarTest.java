package dev.denza.vehiclecontrol.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Voice over every remote control: climate, seats, massage, lights, locks, shades and window positions. */
public class VoiceWholeCarTest {
    private Path directory;
    private JSONObject catalog;
    private FakeVehicle fake;
    private NativeController controller;

    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("native-voice-whole-car-test");
        catalog = new JSONObject(Files.readString(Path.of(System.getProperty("vehicle.catalog"))));
        fake = new FakeVehicle(catalog);
        controller = new NativeController(catalog, () -> {
            fake.opens++;
            return new NativeController.Connection("same-key", fake);
        }, directory.resolve("actions.jsonl").toFile());
    }

    @After public void cleanup() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static List<String> plan(String text) throws IOException {
        return VoiceCommands.parseText(text).commands().stream().map(c -> c.target() + ":" + c.action()).toList();
    }

    private List<String> writes(String... commands) throws Exception {
        List<String> writes = new ArrayList<>();
        for (String command : commands) {
            int split = command.lastIndexOf(':');
            writes.add(controller.command(command.substring(0, split), command.substring(split + 1)).shell());
        }
        return writes;
    }

    @Test public void theOwnersThreePartSentenceParsesWithOrderUnitsAndInheritedSide() throws Exception {
        assertEquals(List.of("fl:open", "climate.driver_temperature:23", "rl:50%"),
                plan("открой окно слева и поставь климат на двадцать три градуса а заднее окно приоткрыть на пятьдесят процентов"));
        // Without an earlier side «заднее окно» stays ambiguous.
        assertThrows(IOException.class, () -> plan("заднее окно приоткрыть на пятьдесят процентов"));
        assertEquals(List.of("climate.driver_temperature:23", "fl:50%"),
                plan("поставь двадцать три градуса и левое окно открой наполовину"));
        assertEquals(0, fake.opens);
    }

    @Test public void formerlyRejectedPositionsAndLocksAreNowNamedActions() throws Exception {
        assertEquals(List.of("fl:open", "fr:close"), plan("открой левое окно закрой правое"));
        assertEquals(List.of("fl:50%", "fr:50%"), plan("открой левое окно и правое на половину"));
        assertEquals(List.of("fl:50%", "fr:50%", "rl:50%", "rr:50%"), plan("открой окна на пятьдесят процентов"));
        assertEquals(List.of("fl:50%", "fr:50%", "rl:50%", "rr:50%"), plan("открой окна на 50"));
        assertEquals(List.of("locks:lock"), plan("запри двери"));
        assertEquals(List.of("locks:lock"), plan("заблокируй все двери"));
        assertEquals(List.of("locks:unlock"), plan("разблокируй машину"));
        assertEquals(List.of("locks:lock"), plan("закрой машину"));
        assertEquals(List.of("fl:20%"), plan("приоткрыть окно водителя"));
        assertEquals(List.of("fl:20%", "fr:20%"), plan("открой передние окна немного"));
        assertEquals(List.of("rr:25%"), plan("открой заднее правое окно на четверть"));
        assertEquals(List.of("fl:open"), plan("открой левое окно на сто процентов"));
        assertEquals(List.of("fl:close"), plan("открой левое окно на ноль"));
        assertEquals(List.of("fl:close"), plan("закрой левое окно полностью"));
    }

    @Test public void climateTemperaturesZonesAndSwitches() throws Exception {
        for (int t = 17; t <= 33; t++)
            assertEquals(List.of("climate.driver_temperature:" + t), plan("поставь температуру " + VoiceGrammar.degrees(t)));
        assertEquals(List.of("climate.power:on", "climate.driver_temperature:23"), plan("включи климат на двадцать три"));
        assertEquals(List.of("climate.driver_temperature:22", "climate.passenger_temperature:24"),
                plan("поставь двадцать два водителю и двадцать четыре пассажиру"));
        assertEquals(List.of("climate.middle_left_temperature:21", "climate.middle_right_temperature:21"),
                plan("поставь температуру двадцать один градус сзади"));
        assertEquals(List.of("climate.rear_temperature:20"), plan("поставь двадцать градусов в третьем ряду"));
        assertEquals(5, plan("поставь двадцать два градуса везде").size());
        assertEquals(List.of("climate.power:off"), plan("выключи климат"));
        assertEquals(List.of("climate.middle_left_power:off", "climate.middle_right_power:off"), plan("выключи климат сзади"));
        assertEquals(List.of("climate.ac:on", "climate.recirculation:internal"), plan("включи кондиционер и режим рециркуляции"));
        assertEquals(List.of("climate.recirculation:external"), plan("включи свежий воздух"));
        assertEquals(List.of("climate.front_defrost:on"), plan("включи обдув лобового стекла"));
        assertEquals(List.of("climate.rear_defrost:off"), plan("выключи обогрев заднего стекла"));
        assertEquals(List.of("climate.driver_auto:on"), plan("включи авто режим"));
        assertEquals(List.of("climate.driver_auto:on"), plan("включи климат авто"));
        assertEquals(List.of("climate.driver_temperature:delta:+1"), plan("сделай потеплее"));
        assertEquals(List.of("climate.passenger_temperature:delta:-2"), plan("сделай похолоднее на два градуса пассажиру"));
        assertEquals(List.of("climate.driver_temperature:delta:+2"), plan("прибавь температуру на два градуса"));
        assertEquals(List.of("climate.driver_temperature:delta:-1"), plan("опусти температуру"));
    }

    @Test public void fanAndAirflowUseCatalogOptions() throws Exception {
        assertEquals(List.of("climate.driver_fan:5"), plan("включи вентилятор на пятёрку"));
        assertEquals(List.of("climate.driver_fan:3"), plan("обдув на три"));
        assertEquals(List.of("climate.driver_fan:7"), plan("поставь вентилятор на максимум"));
        assertEquals(List.of("climate.driver_fan:0"), plan("выключи вентилятор"));
        assertEquals(List.of("climate.driver_fan:delta:+1"), plan("вентилятор посильнее"));
        assertEquals(List.of("climate.driver_mode:feet"), plan("обдув в ноги"));
        assertEquals(List.of("climate.passenger_mode:face_feet"), plan("поставь обдув в лицо и ноги пассажиру"));
        assertEquals(List.of("climate.middle_left_mode:feet", "climate.middle_right_mode:feet"), plan("обдув в ноги сзади"));
    }

    @Test public void seatsMassageLightsAndShades() throws Exception {
        assertEquals(List.of("comfort.fl.heat:2"), plan("включи подогрев"));
        assertEquals(List.of("comfort.fl.heat:3", "comfort.fr.heat:3"), plan("включи подогрев водителя и пассажира на тройку"));
        assertEquals(List.of("comfort.rl.vent:1"), plan("включи вентиляцию сзади слева на минимум"));
        assertEquals(6, plan("выключи подогрев сидений").size());
        assertEquals(List.of("comfort.tl.heat:2", "comfort.tr.heat:2"), plan("включи подогрев третьего ряда"));
        assertEquals(List.of("comfort.fr.heat:2"), plan("включи обогрев пассажира"));
        assertEquals(List.of("comfort.fl.heat:delta:-1"), plan("сделай подогрев послабее"));
        assertEquals(List.of("massage.fl:on"), plan("включи массаж"));
        assertEquals(List.of("massage.fr:wave"), plan("включи массаж волна пассажиру"));
        assertEquals(List.of("massage.fl:level2"), plan("массаж на двойку"));
        assertEquals(List.of("massage.rr:off"), plan("выключи массаж сзади справа"));
        assertEquals(List.of("light.headlights:low"), plan("включи фары"));
        assertEquals(List.of("light.headlights:auto"), plan("фары в авто"));
        assertEquals(List.of("light.headlights:off", "light.rear_fog:off"), plan("выключи фары и противотуманки"));
        assertEquals(List.of("light.matrix:on", "light.drl:off"), plan("включи матричный свет и выключи дневные ходовые огни"));
        assertEquals(List.of("light.logo:on"), plan("включи подсветку логотипа"));
        assertEquals(List.of("shade.front:open"), plan("открой шторку"));
        assertEquals(List.of("shade.front:close", "shade.rear:close"), plan("закрой шторки"));
        assertEquals(List.of("shade.rear:stop"), plan("останови заднюю шторку"));
    }

    @Test public void correctionsCompositionAndLastActionPerTargetAcrossControls() throws Exception {
        assertEquals(List.of("comfort.fl.heat:0"), plan("включи подогрев нет выключи"));
        assertEquals(List.of("fl:close", "fr:close", "rl:close", "rr:close", "climate.ac:on"), plan("закрой окна и включи кондиционер"));
        assertEquals(List.of("climate.driver_temperature:21", "climate.driver_fan:3"),
                plan("поставь температуру двадцать один градус и вентилятор на три"));
        assertEquals(List.of("fl:close", "fr:close", "rl:close", "rr:close", "climate.driver_temperature:23"),
                plan("закрой окна и климат на двадцать три"));
        assertEquals(List.of("comfort.fl.heat:3", "massage.fr:on"), plan("подогрев водителя на тройку а массаж пассажиру включи"));
        assertEquals(List.of("fl:close", "fr:close", "rl:close", "rr:close"), plan("открой окна на пятьдесят процентов нет закрой"));
    }

    @Test public void incompleteAmbiguousOrOutOfRangeCommandsRejectTheWholeUtterance() {
        for (String text : List.of("поставь температуру сорок градусов", "поставь температуру пятнадцать градусов", "открой климат",
                "подними двери", "включи массаж третьего ряда слева", "обдув на стекло сзади", "включи вентилятор",
                "открой машину", "сделай посильнее", "поставь двадцать два процента", "включи свет сзади",
                "поставь вентилятор на девять", "включи подогрев на пять", "открой окна на двадцать градусов",
                "включи кондиционер и не включай подогрев", "поставь", "включи сиденья", "включи обогрев",
                "закрой окна и включи подогрев руля", "открой шторку слева", "выключи автоматический свет",
                "сделай потеплее на сорок градусов", "открой окно немного"))
            assertThrows(text, IOException.class, () -> VoiceCommands.parseText(text));
        assertEquals(0, fake.opens);
    }

    @Test public void everyGeneratedPhraseCompilesToActionsTheControllerAccepts() throws Exception {
        Set<String> seen = new TreeSet<>();
        for (String phrase : VoiceCommands.phrases()) {
            for (VoiceCommands.Command command : VoiceCommands.parseText(phrase).commands()) {
                String action = command.action().startsWith("delta:") ? absoluteExample(command) : command.action();
                controller.command(command.target(), action);
                seen.add(command.target());
            }
        }
        // Every family is reachable by voice.
        for (String target : List.of("fl", "door.rr", "shade.rear", "locks", "climate.power", "climate.ac", "climate.recirculation",
                "climate.front_defrost", "climate.rear_defrost", "climate.driver_temperature", "climate.rear_temperature",
                "climate.driver_fan", "climate.driver_auto", "climate.driver_mode", "comfort.tr.vent", "massage.rr",
                "light.headlights", "light.matrix", "light.drl", "light.rear_fog", "light.logo"))
            assertTrue(target, seen.contains(target));
        assertEquals(0, fake.opens);
    }

    private static String absoluteExample(VoiceCommands.Command command) {
        if (command.target().endsWith("_temperature")) return "22";
        if (command.target().endsWith("_fan")) return "3";
        return "1";
    }

    /** Every phrase the window/door prototype 0.3.4 accepted keeps its exact plan (snapshot of its own parser). */
    @Test public void everyWindowAndDoorPhraseOfThePreviousGrammarKeepsItsPlan() throws Exception {
        List<String> rows;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("voice-window-door-0.3.4.tsv")) {
            rows = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        assertTrue(rows.size() > 3_000);
        List<String> changed = new ArrayList<>();
        for (String row : rows) {
            String[] columns = row.split("\t");
            String actual;
            try { actual = String.join(" ", plan(columns[0])); } catch (IOException rejected) { actual = "rejected"; }
            if (!actual.equals(columns[1])) changed.add(row + " → " + actual);
        }
        assertEquals(List.of(), changed);
    }

    @Test public void commandAllowlistMatchesTheRemote() {
        for (String[] bad : new String[][]{{"fl", "50"}, {"fl", "0%"}, {"fl", "100%"}, {"door.fl", "50%"}, {"climate.driver_temperature", "16"},
                {"climate.driver_temperature", "34"}, {"climate.driver_fan", "8"}, {"climate.rear_mode", "windshield"},
                {"comfort.fl.heat", "4"}, {"massage.tl", "on"}, {"light.matrix", "low"}, {"climate.driver_temperature", "delta:+0"}})
            assertThrows(bad[0] + ":" + bad[1], IllegalArgumentException.class, () -> new VoiceCommands.Command(bad[0], bad[1]));
        new VoiceCommands.Command("climate.driver_mode", "windshield");
        new VoiceCommands.Command("rr", "35%");
    }

    @Test public void windowPositionWritesStockTargetThenResetUnderFreshGuards() throws Exception {
        VoiceCommands.Execution result = VoiceCommands.execute(VoiceCommands.parseText("открой заднее левое окно на тридцать процентов"),
                controller, () -> true);
        assertTrue(result.message(), result.complete());
        JSONArray target = catalog.getJSONObject("properties").getJSONArray("BODYWORK_LR_WINDOW_TARGET_POSITION_SET");
        String prefix = "service call autoservice 6 i32 " + target.getInt(0) + " i32 " + target.getInt(1);
        assertEquals(List.of(prefix + " i32 30 null", prefix + " i32 255 null"), fake.writes);
        assertTrue(result.message().contains("Заднее левое · открыть на 30 %: Запрос принят; движение не подтверждено"));
        List<String> journal = Files.readAllLines(directory.resolve("actions.jsonl"));
        assertEquals(2, journal.size());
        JSONObject logged = new JSONObject(journal.get(1));
        assertEquals("30%", logged.getString("action"));
        assertTrue(logged.getBoolean("reset_accepted"));

        // A guard refuses before either write.
        fake.writes.clear();
        fake.values.put("rl_thermal", 1);
        VoiceCommands.Execution refused = VoiceCommands.execute(VoiceCommands.parseText("открой заднее левое окно на тридцать процентов"),
                controller, () -> true);
        assertFalse(refused.complete());
        assertTrue(fake.writes.isEmpty());
    }

    @Test public void unknownTargetOutcomeStillSendsTheResetButNoRetry() throws Exception {
        fake.setReply = "broken";
        VoiceCommands.Execution result = VoiceCommands.execute(VoiceCommands.parseText("открой левое окно на сорок"), controller, () -> true);
        assertFalse(result.complete());
        assertEquals("uncertain", result.outcomes().get(0).status());
        assertEquals(2, fake.writes.size());
        assertTrue(fake.writes.get(1).endsWith("i32 255 null"));
    }

    @Test public void relativeStepsReadFreshStateAndStayInRange() throws Exception {
        String temperature = catalog.getJSONObject("climate").getJSONObject("driver_temperature").getString("state");
        fake.values.put(temperature, 22);
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("сделай потеплее"), controller, () -> true).complete());
        assertEquals(writes("climate.driver_temperature:23"), fake.writes);
        fake.writes.clear();
        fake.values.put(temperature, 33);
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("сделай теплее на два градуса"), controller, () -> true).complete());
        assertEquals(writes("climate.driver_temperature:33"), fake.writes);
        fake.writes.clear();
        String heat = catalog.getJSONObject("seats").getJSONObject("fl").getJSONObject("comfort").getJSONObject("heat").getString("state");
        fake.values.put(heat, 2); // raw 2 = level 1
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("сделай подогрев посильнее"), controller, () -> true).complete());
        assertEquals(writes("comfort.fl.heat:2"), fake.writes);
        fake.writes.clear();
        fake.values.put(temperature, null);
        VoiceCommands.Execution unknown = VoiceCommands.execute(VoiceCommands.parseText("сделай потеплее и включи кондиционер"),
                controller, () -> true);
        assertFalse(unknown.complete());
        assertEquals(List.of("refused", "accepted"), unknown.outcomes().stream().map(VoiceCommands.Outcome::status).toList());
        assertEquals(writes("climate.ac:on"), fake.writes);
    }

    @Test public void describedPlanNamesEveryControl() throws Exception {
        assertEquals("Переднее левое: открыть; Водитель · температура: 23 °C; Заднее левое: открыть на 50 %",
                VoiceCommands.describe(VoiceCommands.parseText(
                        "открой окно слева и поставь климат на двадцать три градуса а заднее окно приоткрыть на пятьдесят процентов")));
        assertEquals("Передний пассажир · подогрев: уровень 3; Замки: заблокировать",
                VoiceCommands.describe(VoiceCommands.parseText("включи подогрев пассажира на тройку и заблокируй двери")));
    }

    /** Vosk drops grammar words its model does not know; every word must be in vosk-model-small-ru-0.22. */
    @Test public void everyGrammarWordIsInTheRecognizerVocabulary() throws Exception {
        Path model = Path.of(System.getProperty("vehicle.catalog")).getParent().getParent()
                .resolve("experiments/vehicle-control-probe/build/voice-input/voice-model.zip");
        Assume.assumeTrue("prepare_voice_model.py has not been run", Files.isRegularFile(model));
        Set<String> vocabulary = vocabulary(model);
        assertTrue(vocabulary.size() > 100_000);
        Set<String> missing = new TreeSet<>();
        JSONArray grammar = new JSONArray(VoiceCommands.grammarJson());
        for (int i = 0; i < grammar.length(); i++)
            for (String word : grammar.getString(i).split(" ")) if (!vocabulary.contains(word)) missing.add(word);
        assertEquals(Set.of(), missing);
    }

    /** The output symbol table of graph/Gr.fst (OpenFst binary header). */
    static Set<String> vocabulary(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile());
             InputStream input = zip.getInputStream(zip.getEntry("vosk-model-small-ru-0.22/graph/Gr.fst"));
             DataInputStream data = new DataInputStream(new java.io.BufferedInputStream(input, 1 << 16))) {
            if (readInt(data) != 2125659606) throw new IOException("Not an FST");
            readString(data);
            readString(data);
            readInt(data);
            int flags = readInt(data);
            data.skipNBytes(8 + 8 + 8 + 8);
            Set<String> words = new HashSet<>();
            for (int bit : new int[]{1, 2}) {
                if ((flags & bit) == 0) continue;
                if (readInt(data) != 2125658996) throw new IOException("Not a symbol table");
                readString(data);
                data.skipNBytes(8);
                long size = readLong(data);
                Set<String> table = new HashSet<>();
                for (long i = 0; i < size; i++) {
                    table.add(readString(data));
                    data.skipNBytes(8);
                }
                words = table;
            }
            return words;
        }
    }

    private static int readInt(DataInputStream data) throws IOException {
        return ByteBuffer.wrap(data.readNBytes(4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long readLong(DataInputStream data) throws IOException {
        return ByteBuffer.wrap(data.readNBytes(8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static String readString(DataInputStream data) throws IOException {
        return new String(data.readNBytes(readInt(data)), StandardCharsets.UTF_8);
    }
}
