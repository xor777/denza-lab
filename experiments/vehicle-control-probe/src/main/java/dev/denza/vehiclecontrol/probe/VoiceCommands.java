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

/** Offline grammar for named window and electric-door actions, never a HAL value. */
public final class VoiceCommands {
    // A recognizer score is a heuristic, not a probability that the command is correct.
    public static final double MIN_WORD_CONFIDENCE = 0.85;
    private static final List<String> ALL = List.of("fl", "fr", "rl", "rr");
    private static final List<String> ALL_DOORS = List.of("door.fl", "door.fr", "door.rl", "door.rr");
    private static final Set<String> ACTIONS = Set.of("open", "close", "stop");
    private static final Set<String> WINDOW_ONLY_ACTIONS = Set.of("опусти", "опустить", "опускай", "подними", "поднять", "поднимай");
    private static final Set<String> FILLERS = Set.of("пожалуйста", "давай", "слушай");
    private static final Set<String> CONJUNCTIONS = Set.of("и", "а", "потом", "затем", "еще", "and", "then");
    private static final Map<String, Boolean> CONNECTORS = connectors();
    private static final Map<String, String> ACTION_ALIASES = actionAliases();
    private static final Map<String, List<String>> TARGET_ALIASES = targetAliases();
    private static final List<String> PHRASES = grammarPhrases();

    private VoiceCommands() { }

    public record Command(String target, String action) {
        public Command {
            if ((!ALL.contains(target) && !ALL_DOORS.contains(target)) || !ACTIONS.contains(action))
                throw new IllegalArgumentException("Only named window and door actions are permitted");
        }
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

    private static Map<String, List<String>> targetAliases() {
        Map<String, List<String>> targets = new LinkedHashMap<>();
        aliases(targets, ALL, "окна", "все окна", "все", "стекла", "все стекла");
        aliases(targets, List.of("fl", "fr"), "передние окна", "все передние окна", "окна спереди", "спереди окна", "передние", "спереди");
        aliases(targets, List.of("rl", "rr"), "задние окна", "все задние окна", "окна сзади", "сзади окна", "задние", "сзади");
        aliases(targets, List.of("fl", "rl"), "левые окна", "все левые окна", "окна слева", "слева окна", "левые", "левую сторону");
        aliases(targets, List.of("fr", "rr"), "правые окна", "все правые окна", "окна справа", "справа окна", "правые", "правую сторону");
        aliases(targets, List.of("fl"), "окно водителя", "окно у водителя", "окно слева", "слева окно");
        aliases(targets, List.of("fr"), "окно пассажира", "окно переднего пассажира", "окно у пассажира", "окно справа", "справа окно");
        aliases(targets,List.of("fl"),"левое окно спереди","переднее окно слева");
        aliases(targets,List.of("fr"),"правое окно спереди","переднее окно справа");
        aliases(targets,List.of("rl"),"левое окно сзади","заднее окно слева");
        aliases(targets,List.of("rr"),"правое окно сзади","заднее окно справа");
        windowAliases(targets, "fl", "левое", "водительское", "переднее левое", "левое переднее", "спереди слева", "слева спереди", "левое спереди", "переднее слева");
        windowAliases(targets, "fr", "правое", "пассажирское", "переднее правое", "правое переднее", "спереди справа", "справа спереди", "правое спереди", "переднее справа");
        windowAliases(targets, "rl", "заднее левое", "левое заднее", "сзади слева", "слева сзади", "левое сзади", "заднее слева", "левое заднее сзади", "заднее левое сзади");
        windowAliases(targets, "rr", "заднее правое", "правое заднее", "сзади справа", "справа сзади", "правое сзади", "заднее справа", "правое заднее сзади", "заднее правое сзади");
        aliases(targets, ALL_DOORS, "двери", "все двери");
        aliases(targets, List.of("door.fl", "door.fr"), "передние двери", "все передние двери", "двери спереди", "спереди двери");
        aliases(targets, List.of("door.rl", "door.rr"), "задние двери", "все задние двери", "двери сзади", "сзади двери");
        aliases(targets, List.of("door.fl", "door.rl"), "левые двери", "все левые двери", "двери слева", "слева двери");
        aliases(targets, List.of("door.fr", "door.rr"), "правые двери", "все правые двери", "двери справа", "справа двери");
        aliases(targets, List.of("door.fl"), "дверь водителя", "дверь у водителя", "дверь слева", "слева дверь", "левую дверь спереди", "переднюю дверь слева");
        aliases(targets, List.of("door.fr"), "дверь пассажира", "дверь переднего пассажира", "дверь у пассажира", "дверь справа", "справа дверь", "правую дверь спереди", "переднюю дверь справа");
        aliases(targets, List.of("door.rl"), "левую дверь сзади", "заднюю дверь слева");
        aliases(targets, List.of("door.rr"), "правую дверь сзади", "заднюю дверь справа");
        doorAliases(targets, "door.fl", true, "левую", "водительскую", "переднюю левую", "левую переднюю", "левую спереди", "переднюю слева");
        doorAliases(targets, "door.fr", true, "правую", "пассажирскую", "переднюю правую", "правую переднюю", "правую спереди", "переднюю справа");
        doorAliases(targets, "door.rl", true, "заднюю левую", "левую заднюю", "левую сзади", "заднюю слева", "левую заднюю сзади", "заднюю левую сзади");
        doorAliases(targets, "door.rr", true, "заднюю правую", "правую заднюю", "правую сзади", "заднюю справа", "правую заднюю сзади", "заднюю правую сзади");
        // Positional adverbs alone keep their existing window meaning; a door noun is mandatory.
        doorAliases(targets, "door.fl", false, "спереди слева", "слева спереди");
        doorAliases(targets, "door.fr", false, "спереди справа", "справа спереди");
        doorAliases(targets, "door.rl", false, "сзади слева", "слева сзади");
        doorAliases(targets, "door.rr", false, "сзади справа", "справа сзади");
        return java.util.Collections.unmodifiableMap(targets);
    }

    private static void aliases(Map<String, List<String>> aliases, List<String> targets, String... phrases) {
        for (String phrase : phrases) aliases.put(phrase, targets);
    }

    private static void windowAliases(Map<String, List<String>> aliases, String target, String... positions) {
        for (String position : positions)
            aliases(aliases, List.of(target), position, position + " окно", "окно " + position);
    }

    private static void doorAliases(Map<String, List<String>> aliases, String target, boolean feminine, String... positions) {
        for (String position : positions) {
            aliases(aliases, List.of(target), position + " дверь", "дверь " + position);
            if (feminine) aliases(aliases, List.of(target), position);
        }
    }

    private static Map<String, Boolean> connectors() {
        Map<String, Boolean> connectors = new LinkedHashMap<>();
        for (String phrase : List.of("и", "а", "потом", "затем", "еще", "и потом", "а потом", "и затем", "а затем", "и еще", "а еще", "and", "then", "and then"))
            connectors.put(phrase, Boolean.TRUE);
        return java.util.Collections.unmodifiableMap(connectors);
    }

    private static boolean permitted(String actionAlias, Iterable<String> targets) {
        if (!WINDOW_ONLY_ACTIONS.contains(actionAlias)) return true;
        for (String target : targets) if (target.startsWith("door.")) return false;
        return true;
    }

    private static Map<String, String> actionAliases() {
        Map<String, String> actions = new LinkedHashMap<>();
        for (String word : List.of("открой", "открыть", "открывай", "опусти", "опустить", "опускай")) actions.put(word, "open");
        for (String word : List.of("закрой", "закрыть", "закрывай", "подними", "поднять", "поднимай")) actions.put(word, "close");
        for (String word : List.of("останови", "остановить", "стоп", "останови движение", "прекрати движение")) actions.put(word, "stop");
        return java.util.Collections.unmodifiableMap(actions);
    }

    private static List<String> grammarPhrases() {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        for (String action : ACTION_ALIASES.keySet()) {
            for (var target : TARGET_ALIASES.entrySet())
                if (permitted(action, target.getValue())) phrases.add(action + " " + target.getKey());
            // Transition examples teach the small grammar conjunctions, new frames and corrections.
            // The parser composes them without enumerating every possible sentence.
            phrases.add(action + " левое и правое окно");
            phrases.add(action + " левое окно и " + action + " правое окно");
            phrases.add("открой окна нет " + action);
            phrases.add("пожалуйста " + action + " окна");
            phrases.add(action + " окна пожалуйста");
            phrases.add("давай " + action + " окна");
            phrases.add("слушай " + action + " окна");
            if (!WINDOW_ONLY_ACTIONS.contains(action)) {
                phrases.add(action + " левую и правую дверь");
                phrases.add(action + " левую дверь и " + action + " правую дверь");
                phrases.add("открой двери нет " + action);
                phrases.add("пожалуйста " + action + " двери");
            }
        }
        phrases.add("открой левое окно и закрой правое");
        phrases.add("открой передние окна а закрой задние окна");
        for (String connector : CONNECTORS.keySet()) {
            phrases.add("открой левое окно " + connector + " правую дверь");
            phrases.add("открой левое окно " + connector + " закрой заднюю правую дверь");
            phrases.add("закрой окна " + connector + " открой двери");
        }
        return List.copyOf(phrases);
    }

    public static List<String> phrases() { return PHRASES; }

    /** [unk] gives the recognizer an escape instead of forcing all speech into an action. */
    public static String grammarJson() {
        List<String> grammar = new ArrayList<>(PHRASES);
        // Rejection vocabulary is intentional: ASR must retain negation, not force it into a command.
        for(String action:ACTION_ALIASES.keySet()){
            grammar.add("не " + action + " левое окно");
            grammar.add("не надо " + action + " окна");
            if (!WINDOW_ONLY_ACTIONS.contains(action)) {
                grammar.add("не " + action + " левую дверь");
                grammar.add("не надо " + action + " двери");
            } else grammar.add(action + " левую дверь");
        }
        grammar.add("[unk]");
        return new JSONArray(grammar).toString();
    }

    private static String normalize(String text) throws IOException {
        if (text == null || text.length() > 600) throw rejected();
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').trim().replaceAll("\\s+", " ");
    }

    public static Plan parseText(String text) throws IOException {
        String normalized = normalize(text);
        if (normalized.isEmpty()) throw rejected();
        List<String> tokens = new ArrayList<>(List.of(normalized.split(" ")));
        if (tokens.size() > 40 || tokens.contains("не") || tokens.contains("[unk]")) throw rejected();
        tokens.removeIf(FILLERS::contains);
        Cursor cursor = new Cursor(tokens);
        LinkedHashMap<String, String> desired = new LinkedHashMap<>();
        while (!cursor.end()) {
            String actionAlias = cursor.alias(ACTION_ALIASES);
            String action = cursor.take(ACTION_ALIASES);
            if (action == null) throw rejected();
            Set<String> targets = cursor.targets();
            if (!permitted(actionAlias, targets)) throw rejected();
            while (cursor.at("нет")) {
                cursor.position++;
                actionAlias = cursor.alias(ACTION_ALIASES);
                action = cursor.take(ACTION_ALIASES);
                if (action == null) throw rejected();
                if (!permitted(actionAlias, targets)) throw rejected();
                // A spoken correction inherits exactly the preceding frame's targets.
                // Explicit replacement targets must agree; ambiguous retargeting is refused.
                if (cursor.matches(TARGET_ALIASES) && !cursor.targets().equals(targets)) throw rejected();
            }
            for (String target : targets) desired.put(target, action);
            if (cursor.end()) break;
            if (cursor.take(CONNECTORS) == null) throw rejected();
            if (cursor.end()) throw rejected();
        }
        if (desired.isEmpty()) throw rejected();
        List<Command> commands = new ArrayList<>();
        for (var command : desired.entrySet()) commands.add(new Command(command.getKey(), command.getValue()));
        return new Plan(normalized, commands);
    }

    /** Longest exact aliases consume every token; no substring/fuzzy/directional inference. */
    private static final class Cursor {
        final List<String> tokens;
        int position;
        Cursor(List<String> tokens) { this.tokens = tokens; }
        boolean end() { return position >= tokens.size(); }
        boolean at(String token) { return !end() && tokens.get(position).equals(token); }
        <T> String alias(Map<String, T> aliases) {
            String longest = null;
            int length = 0;
            for (String alias : aliases.keySet()) {
                String[] words = alias.split(" ");
                if (words.length <= length || position + words.length > tokens.size()) continue;
                boolean matches = true;
                for (int i = 0; i < words.length; i++) matches &= words[i].equals(tokens.get(position + i));
                if (matches) { longest = alias; length = words.length; }
            }
            return longest;
        }
        <T> boolean matches(Map<String, T> aliases) { return alias(aliases) != null; }
        <T> T take(Map<String, T> aliases) {
            String alias = alias(aliases);
            if (alias == null) return null;
            position += alias.split(" ").length;
            return aliases.get(alias);
        }
        Set<String> targets() throws IOException {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            List<String> first = take(TARGET_ALIASES);
            if (first == null) throw rejected();
            targets.addAll(first);
            while (!end() && matches(CONNECTORS)) {
                int saved = position;
                take(CONNECTORS);
                if (matches(ACTION_ALIASES)) { position = saved; break; }
                List<String> next = take(TARGET_ALIASES);
                if (next == null) throw rejected();
                targets.addAll(next);
            }
            return targets;
        }
    }

    /** Accept only a complete SetWords(true) result, with every spoken word accounted for. */
    public static Plan parseVosk(String json) throws IOException {
        if (json == null || json.length() > 16384) throw rejected();
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object decoded = tokener.nextValue();
            if (!(decoded instanceof JSONObject result) || tokener.nextClean() != 0) throw rejected();
            Object text = result.opt("text");
            if (!(text instanceof String)) throw rejected();
            Plan plan = parseText((String) text);
            JSONArray words = result.optJSONArray("result");
            if (words == null || words.length() == 0 || words.length() > 40) throw rejected();
            List<String> recognized = new ArrayList<>();
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                Object spelling = word.opt("word"), confidence = word.opt("conf");
                if (!(spelling instanceof String) || !(confidence instanceof Number score)) throw rejected();
                String normalized = normalize((String) spelling);
                if (normalized.isEmpty() || normalized.contains(" ")) throw rejected();
                double value = score.doubleValue();
                // A weak short conjunction must not veto otherwise clear named actions/targets.
                double minimum = CONJUNCTIONS.contains(normalized)||FILLERS.contains(normalized) ? 0.5 : MIN_WORD_CONFIDENCE;
                if (!Double.isFinite(value) || value < minimum || value > 1) throw rejected();
                recognized.add(normalized);
            }
            if (!String.join(" ", recognized).equals(plan.text())) throw rejected();
            return plan;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw rejected();
        }
    }

    private static IOException rejected() {
        return new IOException("Команда не распознана уверенно. Ничего не отправлено");
    }

    /** Each target goes through the existing controller and its fresh guards. Never retries. */
    public static Execution execute(Plan plan, NativeController controller, BooleanSupplier foreground) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(controller);
        Objects.requireNonNull(foreground);
        List<Outcome> outcomes = new ArrayList<>();
        boolean allAccepted = true;
        for (Command command : plan.commands()) {
            if (!foreground.getAsBoolean()) return stopped(outcomes, "Отменено; оставшиеся команды не отправлены");
            try {
                JSONObject result = controller.act(command.target(), command.action(), foreground);
                boolean uncertain = result.optBoolean("uncertain");
                boolean accepted = result.optBoolean("accepted") && !uncertain;
                String status = uncertain ? "uncertain" : accepted ? "accepted" : "refused";
                String message = uncertain ? "Исход неизвестен; команда не повторялась"
                        : accepted ? "Запрос принят; движение не подтверждено" : "Автомобиль отказал в запросе";
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
        String summary = allAccepted ? "Все запросы приняты; положение окон и дверей не подтверждено"
                : anyAccepted ? "Часть запросов принята; проверьте результаты каждой команды"
                : "Ни один запрос не принят; проверьте результаты каждой команды";
        return new Execution(outcomes, allAccepted, report(outcomes, summary));
    }

    private static Execution stopped(List<Outcome> outcomes, String message) {
        return new Execution(outcomes, false, report(outcomes, message));
    }

    private static String report(List<Outcome> outcomes, String summary) {
        Map<String, String> labels = Map.of("fl", "Переднее левое", "fr", "Переднее правое", "rl", "Заднее левое", "rr", "Заднее правое",
                "door.fl", "Передняя левая дверь", "door.fr", "Передняя правая дверь", "door.rl", "Задняя левая дверь", "door.rr", "Задняя правая дверь");
        Map<String, String> actions = Map.of("open", "открыть", "close", "закрыть", "stop", "остановить");
        StringBuilder report = new StringBuilder();
        for (Outcome outcome : outcomes)
            report.append(labels.get(outcome.target())).append(" · ").append(actions.get(outcome.action()))
                    .append(": ").append(outcome.message()).append('\n');
        return report.append(summary).toString();
    }
}
