package dev.denza.vehiclecontrol.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline voice commands for every control of the remote, never a HAL value.
 *
 * {@link VoiceParser} turns a whole utterance into named controller actions; this class keeps the
 * recognizer contract (complete endpoint, every word scored, text agreement), resolves relative
 * values from one fresh read taken before the first write and executes through the existing
 * controller and its guards.
 */
public final class VoiceCommands {
    // A recognizer score is a heuristic, not a probability that the command is correct.
    public static final double MIN_WORD_CONFIDENCE = 0.85;
    private static final Set<String> FILLERS = Set.of("пожалуйста", "давай", "слушай");
    private static final Set<String> SHORT_WORDS = Set.of("и", "а", "потом", "затем", "еще", "также", "на", "до", "в", "во",
            "у", "для", "по", "с");
    private static final List<String> WINDOWS = List.of("fl", "fr", "rl", "rr");
    private static final Set<String> MOVEMENT = Set.of("open", "close", "stop");
    private static final Set<String> MASSAGE_ACTIONS = Set.of("on", "off", "wave", "pulse", "stretch", "waist", "shoulders",
            "level1", "level2", "level3");
    private static final Set<String> MODES = Set.of("face", "face_feet", "feet", "feet_windshield", "windshield",
            "face_feet_windshield", "face_windshield");
    private static final Pattern PERCENT = Pattern.compile("([1-9][0-9]?)%");
    private static final Pattern DELTA = Pattern.compile("delta:([+-][1-9][0-9]?)");
    private static final Pattern ZONED = Pattern.compile("climate\\.(driver|passenger|middle_left|middle_right|rear)_(power|auto|temperature|fan|mode)");
    private static final Pattern COMFORT = Pattern.compile("comfort\\.(fl|fr|rl|rr|tl|tr)\\.(heat|vent)");

    private VoiceCommands() { }

    /** A named controller action. The allowlist mirrors the remote's controls and their option keys. */
    public record Command(String target, String action) {
        public Command {
            if (!valid(target, action)) throw new IllegalArgumentException("Only catalogued remote actions are permitted");
        }
    }

    static boolean valid(String target, String action) {
        if (target == null || action == null) return false;
        if (WINDOWS.contains(target)) return MOVEMENT.contains(action) || PERCENT.matcher(action).matches();
        if (target.matches("door\\.(fl|fr|rl|rr)") || target.matches("shade\\.(front|rear)")) return MOVEMENT.contains(action);
        if (target.equals("locks")) return action.equals("lock") || action.equals("unlock");
        if (target.equals("light.headlights")) return Set.of("off", "auto", "low").contains(action);
        if (target.matches("light\\.(matrix|drl|rear_fog|logo)")) return action.equals("on") || action.equals("off");
        if (target.matches("climate\\.(power|ac|front_defrost|rear_defrost)")) return action.equals("on") || action.equals("off");
        if (target.equals("climate.recirculation")) return action.equals("internal") || action.equals("external");
        if (target.matches("massage\\.(fl|fr|rl|rr)")) return MASSAGE_ACTIONS.contains(action);
        Matcher zoned = ZONED.matcher(target);
        if (zoned.matches()) {
            String zone = zoned.group(1);
            return switch (zoned.group(2)) {
                case "power", "auto" -> action.equals("on") || action.equals("off");
                case "temperature" -> DELTA.matcher(action).matches() || inRange(action, 17, 33);
                case "fan" -> DELTA.matcher(action).matches() || inRange(action, 0, 7);
                default -> zone.equals("driver") || zone.equals("passenger") ? MODES.contains(action)
                        : VoiceParser.REAR_ZONE_MODES.contains(action);
            };
        }
        if (COMFORT.matcher(target).matches()) return DELTA.matcher(action).matches() || inRange(action, 0, 3);
        return false;
    }

    private static boolean inRange(String action, int minimum, int maximum) {
        if (!action.matches("[0-9]{1,2}")) return false;
        int value = Integer.parseInt(action);
        return value >= minimum && value <= maximum;
    }

    /** Only this grammar can create a plan; its command list cannot be changed by a caller. */
    public static final class Plan {
        private final String text;
        private final List<Command> commands;
        private Plan(String text, List<Command> commands) {
            this.text = text;
            this.commands = List.copyOf(commands);
        }
        public String text() { return text; }
        public List<Command> commands() { return commands; }
    }

    public record Outcome(String target, String action, String status, String message) { }

    public record Execution(List<Outcome> outcomes, boolean complete, String message) {
        public Execution { outcomes = List.copyOf(outcomes); }
    }

    public record WordConfidence(String word, double confidence, double minimum) {
        public boolean weak() { return confidence < minimum; }
    }

    /** A valid named plan can require an explicit user decision when ASR scores are low. */
    public record Assessment(Plan plan, List<WordConfidence> words, boolean needsConfirmation, String reason) {
        public Assessment { words = List.copyOf(words); }
    }

    /** Strict callers retain their no-low-confidence-plan contract and receive the exact reason. */
    public static final class ConfidenceRejected extends IOException {
        private final Assessment assessment;
        private ConfidenceRejected(Assessment assessment) {
            super(assessment.reason());
            this.assessment = assessment;
        }
        public Assessment assessment() { return assessment; }
    }

    /** Built on first use: every generated phrase is parsed, which needs this class initialised. */
    private static final class Phrases { static final List<String> LIST = VoiceGrammar.phrases(); }

    public static List<String> phrases() { return Phrases.LIST; }

    /** [unk] gives the recognizer an escape instead of forcing all speech into an action. */
    public static String grammarJson() {
        List<String> grammar = new ArrayList<>(phrases());
        // Rejection vocabulary is intentional: ASR must retain negation, not force it into a command.
        grammar.addAll(VoiceGrammar.negatives());
        grammar.add("[unk]");
        return new JSONArray(grammar).toString();
    }

    static String normalize(String text) throws IOException {
        if (text == null || text.length() > 600) throw VoiceParser.rejected();
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim().replaceAll("\\s+", " ");
    }

    public static Plan parseText(String text) throws IOException {
        String normalized = normalize(text);
        if (normalized.isEmpty()) throw VoiceParser.rejected();
        List<String> tokens = List.of(normalized.split(" "));
        if (tokens.size() > 40 || tokens.contains("[unk]")) throw VoiceParser.rejected();
        return new Plan(normalized, VoiceParser.parse(tokens));
    }

    /** Accept only a complete SetWords(true) result, with every spoken word accounted for. */
    public static Plan parseVosk(String json) throws IOException {
        Assessment assessment = assessVosk(json);
        if (assessment.needsConfirmation()) throw new ConfidenceRejected(assessment);
        return assessment.plan();
    }

    /** Low scores alone can request confirmation; malformed or unsupported speech never can. */
    public static Assessment assessVosk(String json) throws IOException {
        if (json == null || json.length() > 16384) throw invalidRecognition();
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object decoded = tokener.nextValue();
            if (!(decoded instanceof JSONObject result) || tokener.nextClean() != 0) throw invalidRecognition();
            Object text = result.opt("text");
            if (!(text instanceof String)) throw invalidRecognition();
            Plan plan = parseText((String) text);
            JSONArray words = result.optJSONArray("result");
            if (words == null || words.length() == 0 || words.length() > 40) throw invalidRecognition();
            List<String> recognized = new ArrayList<>();
            List<WordConfidence> scores = new ArrayList<>();
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                Object spelling = word.opt("word"), confidence = word.opt("conf");
                if (!(spelling instanceof String) || !(confidence instanceof Number score)) throw invalidRecognition();
                String normalized;
                try { normalized = normalize((String) spelling); }
                catch (IOException invalidWord) { throw invalidRecognition(); }
                if (normalized.isEmpty() || normalized.contains(" ")) throw invalidRecognition();
                double value = score.doubleValue();
                // Content words and short connecting words retain distinct thresholds.
                double minimum = SHORT_WORDS.contains(normalized) || FILLERS.contains(normalized) ? 0.5 : MIN_WORD_CONFIDENCE;
                if (!Double.isFinite(value) || value < 0 || value > 1) throw invalidRecognition();
                recognized.add(normalized);
                scores.add(new WordConfidence(normalized, value, minimum));
            }
            if (!String.join(" ", recognized).equals(plan.text())) throw invalidRecognition();
            List<String> weak = new ArrayList<>();
            for (WordConfidence score : scores)
                if (score.weak()) weak.add("«" + score.word() + "» " + score.confidence() + " < " + score.minimum());
            boolean needsConfirmation = !weak.isEmpty();
            String reason = needsConfirmation ? "Низкая уверенность: " + String.join("; ", weak) + ". Ничего не отправлено"
                    : "Все слова прошли пороги уверенности";
            return new Assessment(plan, scores, needsConfirmation, reason);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw invalidRecognition();
        }
    }

    private static IOException invalidRecognition() {
        return new IOException("Некорректный результат распознавания: нет полных данных слов и оценок или текст не совпадает. Ничего не отправлено");
    }

    // ---------------------------------------------------------------- execution

    /** Each target goes through the existing controller and its fresh guards. Never retries. */
    public static Execution execute(Plan plan, NativeController controller, BooleanSupplier foreground) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(controller);
        Objects.requireNonNull(foreground);
        List<Outcome> outcomes = new ArrayList<>();
        boolean allAccepted = true;
        // Every relative step of the plan comes from one reading taken before the first write: a zone written
        // first must not move the base of the next («потеплее везде» would compound under climate sync).
        JSONObject state = null;
        if (plan.commands().stream().anyMatch(command -> DELTA.matcher(command.action()).matches())) {
            if (!foreground.getAsBoolean()) return stopped(outcomes, "Отменено; оставшиеся команды не отправлены");
            try { state = controller.snapshot(); } catch (Exception unknown) { state = null; }
            if (!foreground.getAsBoolean()) return stopped(outcomes, "Отменено; оставшиеся команды не отправлены");
        }
        for (Command planned : plan.commands()) {
            if (!foreground.getAsBoolean()) return stopped(outcomes, "Отменено; оставшиеся команды не отправлены");
            Command command = planned;
            if (DELTA.matcher(planned.action()).matches()) {
                try {
                    if (state == null) throw new IOException("Текущее значение неизвестно");
                    command = absolute(planned, state);
                } catch (Exception unknown) {
                    // Nothing was sent: an unknown current value is a refusal of this target only.
                    allAccepted = false;
                    String reason = unknown instanceof IOException && unknown.getMessage() != null ? unknown.getMessage()
                            : "Текущее значение неизвестно";
                    outcomes.add(new Outcome(planned.target(), planned.action(), "refused", reason + "; ничего не отправлено"));
                    continue;
                }
            }
            try {
                JSONObject result = controller.act(command.target(), command.action(), foreground);
                boolean uncertain = result.optBoolean("uncertain");
                boolean accepted = result.optBoolean("accepted") && !uncertain;
                // reset_required: the window had an unconfirmed earlier target and its 255 reset was not accepted,
                // so the command itself was never sent.
                boolean blocked = result.optBoolean("reset_required") && !result.optBoolean("reset_accepted");
                boolean resetUnconfirmed = result.has("reset_accepted") && !result.optBoolean("reset_accepted");
                String status = uncertain ? "uncertain" : accepted ? "accepted" : "refused";
                String message;
                if (blocked) message = uncertain ? "Исход сброса прежней цели окна неизвестен; команда не отправлена"
                        : "Сброс прежней цели окна не принят; команда не отправлена";
                else if (uncertain) message = resetUnconfirmed ? "Исход неизвестен: сброс цели окна не подтверждён; команда не повторялась"
                        : "Исход неизвестен; команда не повторялась";
                else if (accepted) message = movable(command.target()) ? "Запрос принят; движение не подтверждено"
                        : "Запрос принят; состояние не подтверждено";
                else message = "Автомобиль отказал в запросе" + (resetUnconfirmed ? "; сброс цели окна не подтверждён" : "");
                outcomes.add(new Outcome(command.target(), command.action(), status, message));
                if (uncertain) return stopped(outcomes, "Исход неизвестен; оставшиеся команды не отправлены");
                allAccepted &= accepted;
            } catch (NativeController.Refused refusal) {
                // A known pre-write guard refusal affects this target, not independent targets.
                allAccepted = false;
                outcomes.add(new Outcome(command.target(), command.action(), "refused", refusal.getMessage()));
            } catch (Exception error) {
                boolean cancelled = !foreground.getAsBoolean();
                outcomes.add(new Outcome(command.target(), command.action(), cancelled ? "cancelled" : "failed",
                        error.getMessage() == null ? "Ошибка; команда не повторялась" : error.getMessage()));
                return stopped(outcomes, cancelled ? "Отменено; оставшиеся команды не отправлены"
                        : "Ошибка; проверьте машину. Оставшиеся команды не отправлены");
            }
        }
        boolean anyAccepted = outcomes.stream().anyMatch(outcome -> outcome.status().equals("accepted"));
        String summary = allAccepted ? "Все запросы приняты; результат на машине не подтверждён"
                : anyAccepted ? "Часть запросов принята; проверьте результаты каждой команды"
                : "Ни один запрос не принят; проверьте результаты каждой команды";
        return new Execution(outcomes, allAccepted, report(outcomes, summary));
    }

    private static boolean movable(String target) {
        return WINDOWS.contains(target) || target.startsWith("door.") || target.startsWith("shade.");
    }

    /**
     * «потеплее», «вентилятор посильнее»: a fresh reading plus the step, kept inside the control's range. A fan
     * step never switches the fan off (floor 1) and never weakens a fan that is already off; only «выключи
     * вентилятор» writes 0.
     */
    static Command absolute(Command relative, JSONObject state) throws Exception {
        Matcher delta = DELTA.matcher(relative.action());
        if (!delta.matches()) return relative;
        int step = Integer.parseInt(delta.group(1));
        String target = relative.target();
        Integer current;
        int minimum, maximum;
        Matcher zoned = ZONED.matcher(target), comfort = COMFORT.matcher(target);
        if (zoned.matches()) {
            JSONObject control = state.getJSONObject("climate").optJSONObject(target.substring("climate.".length()));
            String reading = control == null || control.isNull("state") ? null : control.optString("state", null);
            current = reading != null && reading.matches("[0-9]{1,2}") ? Integer.valueOf(reading) : null;
            boolean temperature = zoned.group(2).equals("temperature");
            minimum = temperature ? 17 : 1;
            maximum = temperature ? 33 : 7;
            if (!temperature && current != null && current == 0 && step < 0) throw new IOException("Вентилятор уже выключен");
        } else if (comfort.matches()) {
            JSONObject control = state.getJSONObject("seats").getJSONObject(comfort.group(1)).getJSONObject("comfort")
                    .getJSONObject(comfort.group(2));
            current = control.isNull("level") ? null : control.getInt("level");
            minimum = 0;
            maximum = 3;
        } else throw new IOException("Нет относительного значения для этого контрола");
        if (current == null) throw new IOException("Текущее значение неизвестно");
        int next = Math.max(minimum, Math.min(maximum, current + step));
        return new Command(target, Integer.toString(next));
    }

    private static Execution stopped(List<Outcome> outcomes, String message) {
        return new Execution(outcomes, false, report(outcomes, message));
    }

    private static String report(List<Outcome> outcomes, String summary) {
        StringBuilder report = new StringBuilder();
        for (Outcome outcome : outcomes)
            report.append(label(outcome.target())).append(" · ").append(actionLabel(outcome.target(), outcome.action()))
                    .append(": ").append(outcome.message()).append('\n');
        return report.append(summary).toString();
    }

    // ---------------------------------------------------------------- labels

    private static final Map<String, String> LABELS = labels();

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.putAll(Map.of("fl", "Переднее левое", "fr", "Переднее правое", "rl", "Заднее левое", "rr", "Заднее правое",
                "door.fl", "Передняя левая дверь", "door.fr", "Передняя правая дверь", "door.rl", "Задняя левая дверь",
                "door.rr", "Задняя правая дверь", "shade.front", "Передняя шторка", "shade.rear", "Задняя шторка"));
        labels.putAll(Map.of("locks", "Замки", "climate.power", "Климат салона", "climate.ac", "Кондиционер",
                "climate.recirculation", "Забор воздуха", "climate.front_defrost", "Обдув лобового стекла",
                "climate.rear_defrost", "Обогрев заднего стекла и зеркал", "light.headlights", "Основной свет",
                "light.matrix", "Матричный свет", "light.drl", "Дневные ходовые огни"));
        labels.putAll(Map.of("light.rear_fog", "Задняя противотуманная фара", "light.logo", "Подсветка логотипа"));
        Map<String, String> zones = Map.of("driver", "Водитель", "passenger", "Передний пассажир", "middle_left", "Второй ряд слева",
                "middle_right", "Второй ряд справа", "rear", "Третий ряд");
        Map<String, String> kinds = Map.of("power", "климат", "auto", "AUTO", "temperature", "температура", "fan", "вентилятор",
                "mode", "направление воздуха");
        for (var zone : zones.entrySet())
            for (var kind : kinds.entrySet()) labels.put("climate." + zone.getKey() + "_" + kind.getKey(), zone.getValue() + " · " + kind.getValue());
        Map<String, String> seats = Map.of("fl", "Водитель", "fr", "Передний пассажир", "rl", "Второй ряд слева", "rr", "Второй ряд справа",
                "tl", "Третий ряд слева", "tr", "Третий ряд справа");
        for (var seat : seats.entrySet()) {
            labels.put("comfort." + seat.getKey() + ".heat", seat.getValue() + " · подогрев");
            labels.put("comfort." + seat.getKey() + ".vent", seat.getValue() + " · вентиляция");
            labels.put("massage." + seat.getKey(), seat.getValue() + " · массаж");
        }
        return java.util.Collections.unmodifiableMap(labels);
    }

    static String label(String target) { return LABELS.getOrDefault(target, target); }

    static String actionLabel(String target, String action) {
        Matcher percent = PERCENT.matcher(action), delta = DELTA.matcher(action);
        if (percent.matches()) return "открыть на " + percent.group(1) + " %";
        if (delta.matches()) return (target.endsWith("_temperature") ? "на " + delta.group(1) + " °C" : "на " + delta.group(1));
        if (target.endsWith("_temperature")) return action + " °C";
        if (target.endsWith("_fan")) return action.equals("0") ? "выключить" : "скорость " + action;
        if (target.startsWith("comfort.")) return action.equals("0") ? "выключить" : "уровень " + action;
        Map<String, String> words = new LinkedHashMap<>();
        words.putAll(Map.of("open", "открыть", "close", "закрыть", "stop", "остановить", "lock", "заблокировать",
                "unlock", "разблокировать", "on", "включить", "off", "выключить", "low", "ближний", "auto", "авто",
                "internal", "рециркуляция"));
        words.putAll(Map.of("external", "воздух снаружи", "face", "в лицо", "feet", "в ноги", "windshield", "на стекло",
                "face_feet", "в лицо и ноги", "feet_windshield", "в ноги и на стекло", "face_windshield", "в лицо и на стекло",
                "face_feet_windshield", "в лицо, ноги и стекло", "wave", "волна", "pulse", "пульс"));
        words.putAll(Map.of("stretch", "растяжка", "waist", "поясница", "shoulders", "плечи", "level1", "уровень 1",
                "level2", "уровень 2", "level3", "уровень 3"));
        return words.getOrDefault(action, action);
    }

    /** The plan in words for the confirmation line: «Водитель · температура: 23 °C; Заднее левое: открыть на 50 %». */
    public static String describe(Plan plan) {
        List<String> parts = new ArrayList<>();
        for (Command command : plan.commands()) parts.add(label(command.target()) + ": " + actionLabel(command.target(), command.action()));
        return String.join("; ", parts);
    }

    static Set<String> targets(Plan plan) {
        Set<String> targets = new LinkedHashSet<>();
        for (Command command : plan.commands()) targets.add(command.target());
        return targets;
    }
}
