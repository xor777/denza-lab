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

    private NativeController controller(NativeController.Shell shell) throws Exception {
        return controller(shell, "actions.jsonl");
    }

    private NativeController controller(NativeController.Shell shell, String journal) throws Exception {
        return new NativeController(catalog, () -> {
            fake.opens++;
            return new NativeController.Connection("same-key", shell);
        }, directory.resolve(journal).toFile());
    }

    /** The fake car, except that every 255 window-target reset gets this reply. */
    private NativeController.Shell resetReply(String reply) {
        return (command, timeout) -> {
            if (command.startsWith("service call autoservice 6") && command.endsWith(" i32 255 null")) {
                fake.writes.add(command);
                return reply;
            }
            return fake.run(command, timeout);
        };
    }

    private static final String REFUSED = "Result: Parcel(00000000    '....')";

    private static void rejects(String... texts) {
        for (String text : texts) assertThrows(text, IOException.class, () -> VoiceCommands.parseText(text));
    }

    private static List<String> statuses(VoiceCommands.Execution execution) {
        return execution.outcomes().stream().map(VoiceCommands.Outcome::status).toList();
    }

    private String climateState(String key) throws Exception {
        return catalog.getJSONObject("climate").getJSONObject(key).getString("state");
    }

    private static List<String> phases(List<String> rows) throws Exception {
        List<String> phases = new ArrayList<>();
        for (String row : rows) phases.add(new JSONObject(row).getString("phase"));
        return phases;
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
        assertEquals(List.of("light.headlights:off", "light.rear_fog:off"), plan("выключи фары и задние противотуманки"));
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
        // The reset is journalled before its write, so a crash between the two leaves the window latched.
        assertEquals(List.of("attempt", "reset_attempt", "result"), phases(journal));
        JSONObject logged = new JSONObject(journal.get(2));
        assertEquals("30%", logged.getString("action"));
        assertTrue(logged.getBoolean("reset_accepted"));
        assertTrue(controller.pendingResets.isEmpty());

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

    // ------------------------------------------------------------ ambiguity rules of the whole-car grammar

    @Test public void impliedSettingOnlyForALaterObjectOfAMovementVerb() throws Exception {
        assertEquals(List.of("fl:close", "fr:close", "rl:close", "rr:close", "climate.driver_temperature:23"),
                plan("закрой окна и климат на двадцать три"));
        assertEquals(List.of("fl:close", "fr:close", "rl:close", "rr:close", "climate.driver_fan:3"),
                plan("закрой окна и вентилятор на три"));
        rejects("выключи свет на авто", "выключи фары на авто", "выключи обдув в ноги", "выключи климат сзади на двадцать",
                "выключи температуру на двадцать", "закрой вентилятор на три", "закрой массаж плечи", "открой климат на двадцать");
        assertEquals(0, fake.opens);
    }

    @Test public void aBareNumberWithRolesIsATemperatureOrNothingNeverAWindow() throws Exception {
        rejects("поставь пассажиру сто", "поставь три водителю", "пассажиру три", "поставь 3 водителю",
                "поставь пассажиру шестнадцать", "сделай сорок слева", "поставь двадцать два водителю и пассажиру три",
                "поставь сзади наполовину");
        assertEquals(List.of("climate.passenger_temperature:24"), plan("поставь пассажиру двадцать четыре"));
        assertEquals(List.of("climate.driver_temperature:22", "climate.passenger_temperature:22"),
                plan("поставь двадцать два водителю и пассажиру"));
        // A movement verb or a gendered position still names windows.
        assertEquals(List.of("rl:50%", "rr:50%"), plan("открой сзади на пятьдесят"));
        assertEquals(List.of("fl:50%"), plan("поставь левое на пятьдесят процентов"));
    }

    @Test public void windowPositionsNeedAPrepositionOrAUnitAndANumberBeforeAnObjectCountsIt() throws Exception {
        rejects("открой два окна", "открой все четыре окна", "три окна открой", "открой левое окно сорок", "включи подогрев на три сиденья");
        assertEquals(List.of("fl:40%"), plan("открой левое окно сорок процентов"));
        assertEquals(List.of("fl:40%"), plan("открой левое окно на сорок"));
        assertEquals(List.of("fl:50%", "fr:50%", "rl:50%", "rr:50%"), plan("открой окна 50%"));
        // Temperature keeps bare cardinal numbers.
        assertEquals(List.of("climate.driver_temperature:22"), plan("поставь двадцать два"));
        assertEquals(List.of("climate.driver_temperature:23"), plan("температура двадцать три"));
    }

    @Test public void valuesRecordTheirPrepositionAndNumberSource() throws Exception {
        VoiceParser.Value on = VoiceParser.item(VoiceParser.chunk(List.of("на", "пятьдесят"))).value;
        assertTrue(on.preposition());
        assertFalse(on.target());
        assertTrue(on.cardinal());
        assertFalse(VoiceParser.item(VoiceParser.chunk(List.of("пятьдесят"))).value.preposition());
        VoiceParser.Value until = VoiceParser.item(VoiceParser.chunk(List.of("до", "двадцать"))).value;
        assertTrue(until.preposition() && until.target());
        assertFalse(VoiceParser.item(VoiceParser.chunk(List.of("на", "тройку"))).value.cardinal());
        assertFalse(VoiceParser.item(VoiceParser.chunk(List.of("на", "третий", "уровень"))).value.cardinal());
    }

    @Test public void colloquialAndOrdinalNumbersOnlySetLevels() throws Exception {
        rejects("открой окно водителя на тройку", "сделай потеплее на тройку", "поставь температуру на тройку",
                "прибавь вентилятор на двойку", "сделай подогрев посильнее на двойку");
        assertEquals(List.of("climate.driver_fan:5"), plan("включи вентилятор на пятёрку"));
        assertEquals(List.of("comfort.fl.heat:3"), plan("включи подогрев на третий уровень"));
        assertEquals(List.of("massage.fl:level2"), plan("поставь массаж на второй"));
    }

    @Test public void positionsSaidOncePlaceEveryObjectOfTheCommand() throws Exception {
        assertEquals(List.of("comfort.fr.heat:2", "comfort.fr.vent:2"), plan("включи подогрев и вентиляцию пассажира"));
        assertEquals(List.of("comfort.fr.heat:2", "massage.fr:on"), plan("включи подогрев и массаж пассажиру"));
        assertEquals(List.of("climate.middle_left_power:off", "climate.middle_right_power:off", "comfort.rl.heat:0", "comfort.rr.heat:0"),
                plan("выключи климат и подогрев сзади"));
        assertEquals(List.of("fl:open", "rl:open", "door.fl:open", "door.rl:open"), plan("открой окна и двери слева"));
        // Gendered adjectives are never shared; objects without seats or zones keep no positions.
        assertEquals(List.of("fl:close", "fr:close", "rl:close", "rr:close", "door.fl:close"), plan("закрой окна и левую дверь"));
        assertEquals(List.of("climate.ac:on", "comfort.fr.heat:2"), plan("включи кондиционер и подогрев пассажира"));
        // Two objects of one kind with their own nouns are a group and its member, not one place.
        assertEquals(List.of("fl:20%", "fr:open", "rl:open", "rr:open"), plan("открой окна и окно водителя на двадцать"));
        // «сидений» already names its seats.
        assertEquals(7, plan("включи подогрев сидений и вентиляцию пассажира").size());
    }

    @Test public void aSingleValueGoesToEveryObjectOfItsKindButNeverOntoItsOwnTargets() throws Exception {
        assertEquals(List.of("comfort.fl.heat:3", "comfort.fl.vent:3"), plan("включи подогрев и вентиляцию на тройку"));
        assertEquals(List.of("comfort.fl.heat:3", "comfort.fr.heat:3"), plan("включи подогрев на тройку водителю и пассажиру"));
        assertEquals(List.of("comfort.fl.vent:1", "comfort.fr.vent:1"), plan("включи вентиляцию на минимум водителю и пассажиру"));
        assertEquals(List.of("massage.fl:level3", "comfort.fl.heat:3"), plan("включи массаж и подогрев на тройку"));
        assertEquals(List.of("fl:20%", "fr:open", "rl:open", "rr:open"), plan("открой окна и левое на двадцать"));
        assertEquals(List.of("comfort.fl.heat:3", "comfort.fr.heat:3"), plan("подогрев водителя и пассажира на тройку"));
        // Another kind keeps its own default.
        assertEquals(List.of("comfort.fl.heat:2", "climate.power:on", "climate.driver_temperature:23"),
                plan("включи подогрев и климат на двадцать три"));
    }

    @Test public void twoActionsForOneTargetInOneCommandRejectExceptCombinedAirflow() throws Exception {
        assertEquals(List.of("climate.driver_mode:face_feet"), plan("обдув в ноги и в лицо"));
        assertEquals(List.of("climate.driver_mode:feet_windshield"), plan("обдув на стекло и в ноги"));
        assertEquals(List.of("climate.driver_mode:face_windshield"), plan("обдув на стекло и в лицо"));
        assertEquals(List.of("climate.driver_mode:face_feet_windshield"), plan("обдув в лицо и в ноги и на стекло"));
        assertEquals(List.of("climate.middle_left_mode:face_feet", "climate.middle_right_mode:face_feet"), plan("обдув в ноги сзади и в лицо"));
        assertEquals(List.of("climate.middle_left_mode:face_feet", "climate.middle_right_mode:face_feet"), plan("обдув в лицо и в ноги сзади"));
        rejects("обдув в ноги сзади и на стекло", "включи режим рециркуляции и свежий воздух", "сделай двадцать два и потеплее",
                "включи климат на двадцать три и температуру водителя на двадцать пять");
        // A member named next to its group keeps its own action.
        assertEquals(List.of("comfort.fl.heat:3", "comfort.fr.heat:2", "comfort.rl.heat:2", "comfort.rr.heat:2", "comfort.tl.heat:2",
                "comfort.tr.heat:2"), plan("включи подогрев сидений и подогрев водителя на тройку"));
        // Across commands the last action still wins.
        assertEquals(List.of("fl:close"), plan("открой левое окно и закрой левое окно"));
        assertEquals(List.of("climate.recirculation:external"), plan("включи режим рециркуляции и включи свежий воздух"));
    }

    @Test public void relativeWordsTakeALevelStepAndUntilIsAlwaysAbsolute() throws Exception {
        assertEquals(List.of("climate.driver_fan:delta:+2"), plan("сделай вентилятор посильнее на два"));
        assertEquals(List.of("comfort.fl.heat:delta:+2"), plan("сделай подогрев посильнее на два"));
        assertEquals(List.of("comfort.fl.heat:delta:+2"), plan("сделай подогрев посильнее на два уровня"));
        rejects("сделай подогрев посильнее на четыре", "сделай вентилятор посильнее на восемь", "сделай вентилятор посильнее на два градуса");
        assertEquals(List.of("climate.driver_temperature:20"), plan("убавь температуру до двадцать"));
        assertEquals(List.of("climate.driver_temperature:20"), plan("сделай похолоднее до двадцать"));
        assertEquals(List.of("climate.driver_fan:5"), plan("сделай вентилятор посильнее до пять"));
        assertEquals(List.of("fl:50%"), plan("открой окно водителя до пятьдесят процентов"));
    }

    @Test public void climateOnWithZonesSwitchesThoseZones() throws Exception {
        assertEquals(List.of("climate.middle_left_power:on", "climate.middle_right_power:on",
                "climate.middle_left_temperature:23", "climate.middle_right_temperature:23"), plan("включи климат сзади на двадцать три"));
        assertEquals(List.of("climate.power:on", "climate.driver_temperature:23"), plan("включи климат на двадцать три"));
    }

    @Test public void onlyTheRearFogLampCanBeNamed() throws Exception {
        rejects("противотуманки", "включи противотуманки", "включи противотуманные фары", "выключи противотуманные огни");
        assertEquals(List.of("light.rear_fog:on"), plan("включи задние противотуманные фары"));
        assertEquals(List.of("light.rear_fog:off"), plan("выключи задние противотуманки"));
        assertTrue(VoiceCommands.phrases().contains("включи задние противотуманки"));
        assertFalse(VoiceCommands.phrases().contains("включи противотуманки"));
        // The bare words stay recognizable so they are refused rather than forced into the rear lamp.
        assertTrue(VoiceCommands.grammarJson().contains("\"включи противотуманки\""));
    }

    @Test public void bareProgramsAndAutoNameNoFunction() throws Exception {
        rejects("волна", "плечи", "авто", "в режим авто", "включи фары и авто", "в ноги");
        assertEquals(List.of("climate.driver_mode:feet"), plan("обдув в ноги"));
        assertEquals(List.of("massage.fl:wave"), plan("включи массаж волна"));
        assertEquals(List.of("light.headlights:auto"), plan("фары в авто"));
    }

    @Test public void oneSeatOfARowIsAmbiguousButTheRowsSeatsAreBoth() throws Exception {
        rejects("подогрев заднего сиденья", "включи подогрев заднего сиденья", "включи массаж заднего сиденья");
        assertEquals(List.of("comfort.rl.heat:2", "comfort.rr.heat:2"), plan("включи подогрев задних сидений"));
        assertEquals(List.of("comfort.rl.heat:2", "comfort.rr.heat:2"), plan("включи подогрев сидений сзади"));
        assertEquals(List.of("comfort.rl.vent:2"), plan("включи вентиляцию заднего левого сиденья"));
    }

    // ------------------------------------------------------------ execution

    @Test public void acceptedWindowTargetWithUnconfirmedResetIsUncertainAndStopsThePlan() throws Exception {
        int run = 0;
        for (String reply : List.of("broken", REFUSED)) {
            fake.writes.clear();
            NativeController car = controller(resetReply(reply), "unconfirmed-" + run++ + ".jsonl");
            VoiceCommands.Execution result = VoiceCommands.execute(
                    VoiceCommands.parseText("открой левое окно на сорок процентов и закрой правое окно"), car, () -> true);
            assertFalse(result.complete());
            assertEquals(List.of("uncertain"), statuses(result));
            assertTrue(result.message(), result.outcomes().get(0).message().contains("сброс цели окна не подтверждён"));
            assertTrue(result.message().contains("оставшиеся команды не отправлены"));
            // The target and its single reset; neither is retried and the right window is never sent.
            assertEquals(List.of(car.command("fl", "40%").shell(), car.windowTargetReset("fl")), fake.writes);
        }
        JSONObject direct = controller(resetReply(REFUSED), "direct.jsonl").act("rr", "30%");
        assertTrue(direct.getBoolean("uncertain"));
        assertFalse(direct.getBoolean("accepted"));
        assertEquals(1, direct.getInt("code"));
        assertFalse(direct.getBoolean("reset_accepted"));
    }

    @Test public void anUnconfirmedResetLatchesDurablyAndPrecedesEveryLaterWindowCommand() throws Exception {
        NativeController failing = controller(resetReply(REFUSED));
        assertTrue(failing.act("fl", "40%").getBoolean("uncertain"));
        assertEquals(Set.of("fl"), failing.pendingResets);
        assertEquals("[\"fl\"]", failing.snapshot().getJSONArray("pending_window_resets").toString());

        // A restarted controller rebuilds the latch; a refused reset refuses the window command and sends nothing else.
        NativeController restarted = controller(resetReply(REFUSED));
        assertEquals(Set.of("fl"), restarted.pendingResets);
        fake.writes.clear();
        JSONObject refused = restarted.act("fl", "close");
        assertFalse(refused.getBoolean("accepted"));
        assertTrue(refused.getBoolean("reset_required"));
        assertEquals(List.of(restarted.windowTargetReset("fl")), fake.writes);
        assertEquals(Set.of("fl"), restarted.pendingResets);

        // By voice: a refused reset refuses only that window; an unknown reset outcome stops the plan.
        fake.writes.clear();
        VoiceCommands.Execution voice = VoiceCommands.execute(VoiceCommands.parseText("закрой левое окно и правое окно"), restarted, () -> true);
        assertEquals(List.of("refused", "accepted"), statuses(voice));
        assertTrue(voice.outcomes().get(0).message().contains("Сброс прежней цели окна не принят"));
        assertEquals(List.of(restarted.windowTargetReset("fl"), restarted.command("fr", "close").shell()), fake.writes);
        fake.writes.clear();
        voice = VoiceCommands.execute(VoiceCommands.parseText("закрой левое окно и правое окно"), controller(resetReply("broken")), () -> true);
        assertEquals(List.of("uncertain"), statuses(voice));
        assertEquals(List.of(restarted.windowTargetReset("fl")), fake.writes);

        // An accepted reset clears the latch, journalled, before even STOP; the old target is never sent again.
        NativeController accepting = controller(fake);
        assertEquals(Set.of("fl"), accepting.pendingResets);
        fake.writes.clear();
        assertTrue(accepting.act("fl", "stop").getBoolean("accepted"));
        assertEquals(List.of(accepting.windowTargetReset("fl"), accepting.command("fl", "stop").shell()), fake.writes);
        assertTrue(accepting.pendingResets.isEmpty());
        List<String> journal = Files.readAllLines(directory.resolve("actions.jsonl"));
        assertEquals(List.of("reset_attempt", "reset_result", "attempt", "result"), phases(journal.subList(journal.size() - 4, journal.size())));
        assertTrue(controller(fake).pendingResets.isEmpty());
        fake.writes.clear();
        assertTrue(accepting.act("fl", "open").getBoolean("accepted"));
        assertEquals(List.of(accepting.command("fl", "open").shell()), fake.writes);

        // A new percent on a latched window: the lone reset first, then the new target and its own reset.
        assertTrue(failing.act("rr", "20%").getBoolean("uncertain"));
        NativeController next = controller(fake);
        assertEquals(Set.of("rr"), next.pendingResets);
        fake.writes.clear();
        assertTrue(next.act("rr", "30%").getBoolean("accepted"));
        assertEquals(List.of(next.windowTargetReset("rr"), next.command("rr", "30%").shell(), next.windowTargetReset("rr")), fake.writes);
        assertTrue(next.pendingResets.isEmpty());
    }

    @Test public void relativeStepsOfOnePlanComeFromOneReadingBeforeTheFirstWrite() throws Exception {
        List<String> zones = List.of("driver", "passenger", "middle_left", "middle_right", "rear");
        for (String zone : zones) fake.values.put(climateState(zone + "_temperature"), 22);
        // Climate sync: every write moves all zones; a reading per command would compound the step.
        NativeController synced = controller((command, timeout) -> {
            String reply = fake.run(command, timeout);
            if (command.startsWith("service call autoservice 6"))
                for (String zone : zones) fake.values.put(climateState(zone + "_temperature"), 30);
            return reply;
        });
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("сделай потеплее везде"), synced, () -> true).complete());
        assertEquals(writes("climate.driver_temperature:23", "climate.passenger_temperature:23", "climate.middle_left_temperature:23",
                "climate.middle_right_temperature:23", "climate.rear_temperature:23"), fake.writes);

        // A fan step never switches the fan off or weakens a fan that is off; «выключи вентилятор» still writes 0.
        String fan = climateState("driver_fan");
        fake.writes.clear();
        fake.values.put(fan, 1);
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("вентилятор послабее"), controller, () -> true).complete());
        assertEquals(writes("climate.driver_fan:1"), fake.writes);
        fake.writes.clear();
        fake.values.put(fan, 0);
        VoiceCommands.Execution off = VoiceCommands.execute(VoiceCommands.parseText("вентилятор послабее"), controller, () -> true);
        assertEquals(List.of("refused"), statuses(off));
        assertTrue(fake.writes.isEmpty());
        fake.values.put(fan, 3);
        assertTrue(VoiceCommands.execute(VoiceCommands.parseText("выключи вентилятор"), controller, () -> true).complete());
        assertEquals(writes("climate.driver_fan:0"), fake.writes);
    }
}
