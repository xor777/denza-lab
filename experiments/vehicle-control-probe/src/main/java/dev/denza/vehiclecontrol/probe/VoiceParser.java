package dev.denza.vehiclecontrol.probe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole-car Russian command grammar over the remote's controls.
 *
 * Text → chunks (longest exact lexicon match) → clauses (one verb, coordinated items) → items
 * (object, position, value) → named controller actions. A connector followed by its own verb opens a
 * new clause; without a verb it coordinates another item under the same verb. The verb may stand
 * after its object («заднее окно приоткрыть на пятьдесят»). Units decide where a number goes:
 * degrees only to temperature, percent only to windows; a window position needs «на»/«до» or a unit
 * («два окна» counts windows), level words («тройку», «третий») go only to levels and «до» is always an
 * absolute value. There is no fuzzy matching: an unknown word, a negation, a conflicting position, two
 * different actions for one target in one clause or an item no verb applies to rejects the whole utterance.
 */
final class VoiceParser {
    private VoiceParser() { }

    enum Family {
        WINDOW, DOOR, SHADE, LOCKS, CLIMATE, AC, RECIRC, FRESH_AIR, FRONT_DEFROST, REAR_DEFROST,
        TEMP, FAN, AUTO, AIRFLOW, HEAT, VENT, MASSAGE, HEADLIGHTS, AUTO_HEADLIGHTS, MATRIX, DRL, REAR_FOG, LOGO
    }
    enum Intent { OPEN, AJAR, CLOSE, STOP, ON, OFF, SET, UP, DOWN, LOCK, UNLOCK }
    /** Which bare noun a positional word can stand for; ANY agrees with every noun. */
    enum Form { NEUT, FEM, PL, ANY }
    enum UnitKind { DEGREE, PERCENT, LEVEL }
    enum SpecialKind { HALF, QUARTER, MAX, MIN, FULL, LITTLE, AUTO, AIRFLOW, MASSAGE_MODE }
    /** PREP is «на»; TARGET is «до», which always names an absolute value, never a step. */
    enum WordKind { CONJ, FILLER, PREP, TARGET, CORRECTION, NEGATION }

    sealed interface Chunk permits Verb, Noun, Seat, Position, Num, Unit, Special, Relative, Word { }
    /** level: the meaning for level-like objects («подними температуру»); windowOnly: «опусти/подними». */
    record Verb(Intent intent, Intent level, boolean windowOnly) implements Chunk { }
    record Noun(Family family, Form form, boolean plural, boolean needsPosition) implements Chunk { }
    /** «сиденья», «кресла»: complement of heating, ventilation and massage; «сидений» names all seats. */
    record Seat(boolean plural) implements Chunk { }
    /** side 'L'/'R', row 1..3; all = «все», «везде». */
    record Position(Form form, Character side, Integer row, boolean all) implements Chunk { }
    /** cardinal: «три», «3»; otherwise a colloquial or ordinal level word («тройку», «третий»). */
    record Num(int value, boolean cardinal) implements Chunk { }
    record Unit(UnitKind kind) implements Chunk { }
    record Special(SpecialKind kind, String mode) implements Chunk { }
    record Relative(int delta, boolean temperature) implements Chunk { }
    record Word(WordKind kind) implements Chunk { }

    static final Set<Family> MOVABLE = EnumSet.of(Family.WINDOW, Family.DOOR, Family.SHADE);
    static final Set<Family> LEVELS = EnumSet.of(Family.TEMP, Family.FAN, Family.HEAT, Family.VENT, Family.MASSAGE);
    static final Set<Family> ZONED = EnumSet.of(Family.TEMP, Family.FAN, Family.AUTO, Family.AIRFLOW);
    static final Set<Family> SEATED = EnumSet.of(Family.HEAT, Family.VENT, Family.MASSAGE);
    static final List<String> ZONES = List.of("driver", "passenger", "middle_left", "middle_right", "rear");
    static final List<String> SEATS = List.of("fl", "fr", "rl", "rr", "tl", "tr");
    static final List<String> MASSAGE_SEATS = List.of("fl", "fr", "rl", "rr");
    static final Set<String> REAR_ZONE_MODES = Set.of("face", "face_feet", "feet");
    static final int AJAR_PERCENT = 20, COMFORT_DEFAULT = 2;

    // ---------------------------------------------------------------- lexicon

    static final Map<String, Chunk> LEXICON;
    static final Map<String, Integer> UNITS_WORDS, TENS_WORDS, WHOLE_NUMBERS, SPOKEN_NUMBERS;
    static final int MAX_WORDS;

    private static final Map<String, Chunk> lexicon = new LinkedHashMap<>();

    private static void put(Chunk chunk, String... phrases) {
        for (String phrase : phrases) {
            if (lexicon.put(phrase, chunk) != null) throw new IllegalStateException("Duplicate phrase " + phrase);
        }
    }
    private static Verb verb(Intent intent) { return new Verb(intent, intent, false); }
    private static Noun noun(Family family, Form form, boolean plural) { return new Noun(family, form, plural, false); }
    private static Position at(Form form, Character side, Integer row) { return new Position(form, side, row, false); }

    static {
        // Verbs. «приоткрой» is absent from the recognizer's vocabulary; the infinitive is present.
        put(verb(Intent.OPEN), "открой", "открыть", "открывай");
        put(new Verb(Intent.OPEN, Intent.DOWN, true), "опусти", "опустить", "опускай");
        put(verb(Intent.AJAR), "приоткрыть");
        put(verb(Intent.CLOSE), "закрой", "закрыть", "закрывай");
        put(new Verb(Intent.CLOSE, Intent.UP, true), "подними", "поднять", "поднимай");
        put(verb(Intent.STOP), "останови", "остановить", "стоп", "останови движение", "прекрати движение");
        put(verb(Intent.ON), "включи", "включить", "включай", "запусти");
        put(verb(Intent.OFF), "выключи", "выключить", "выключай", "отключи", "отключить");
        put(verb(Intent.SET), "поставь", "поставить", "установи", "установить", "сделай", "сделать", "задай", "переключи");
        put(verb(Intent.UP), "прибавь", "добавь", "увеличь", "повысь");
        put(verb(Intent.DOWN), "убавь", "уменьши", "понизь", "сбавь");
        put(verb(Intent.LOCK), "заблокируй", "заблокировать", "запри", "закрой на замок");
        put(verb(Intent.UNLOCK), "разблокируй", "разблокировать");

        // Windows and doors keep the forms the window/door prototype accepted.
        put(noun(Family.WINDOW, Form.NEUT, false), "окно");
        put(noun(Family.WINDOW, Form.PL, true), "окна", "стекла");
        put(noun(Family.DOOR, Form.FEM, false), "дверь");
        put(noun(Family.DOOR, Form.PL, true), "двери");
        put(noun(Family.SHADE, Form.FEM, false), "шторку", "шторка", "штору");
        put(noun(Family.SHADE, Form.PL, true), "шторки", "шторы");
        put(noun(Family.LOCKS, Form.ANY, true), "замки", "машину", "автомобиль");

        put(noun(Family.CLIMATE, Form.ANY, false), "климат", "климата");
        put(noun(Family.AC, Form.ANY, false), "кондиционер", "кондиционера");
        put(noun(Family.RECIRC, Form.ANY, false), "рециркуляции", "режим рециркуляции", "внутренний воздух");
        put(noun(Family.FRESH_AIR, Form.ANY, false), "забор воздуха снаружи", "воздух снаружи", "внешний воздух", "свежий воздух");
        put(noun(Family.FRONT_DEFROST, Form.ANY, false), "обдув лобового стекла", "обдув лобового", "обогрев лобового стекла",
                "обогрев лобового", "обдув стекла");
        put(noun(Family.REAR_DEFROST, Form.ANY, false), "обогрев заднего стекла", "обогрев зеркал",
                "обогрев заднего стекла и зеркал");
        put(noun(Family.TEMP, Form.ANY, false), "температуру", "температура", "температуры");
        put(noun(Family.FAN, Form.ANY, false), "вентилятор", "вентилятора", "обдув", "обдува", "скорость вентилятора",
                "скорость обдува");
        put(noun(Family.AUTO, Form.ANY, false), "авто режим", "автоматический режим", "режим авто", "авторежим");
        put(noun(Family.AIRFLOW, Form.ANY, false), "направление воздуха", "направление обдува", "воздух");
        put(noun(Family.HEAT, Form.ANY, false), "подогрев", "подогрева");
        put(new Noun(Family.HEAT, Form.ANY, false, true), "обогрев", "обогрева");
        put(noun(Family.VENT, Form.ANY, false), "вентиляцию", "вентиляция", "вентиляции");
        put(noun(Family.MASSAGE, Form.ANY, false), "массаж", "массажа");
        put(new Seat(false), "сиденья", "сиденье", "кресла", "кресло");
        put(new Seat(true), "сидений", "кресел");

        put(noun(Family.HEADLIGHTS, Form.ANY, true), "фары", "свет", "основной свет", "ближний свет", "ближний");
        put(noun(Family.AUTO_HEADLIGHTS, Form.ANY, true), "автоматический свет", "автоматические фары", "автосвет");
        put(noun(Family.MATRIX, Form.ANY, true), "матричный свет", "матричные фары", "матрицу");
        put(noun(Family.DRL, Form.ANY, true), "дневные ходовые огни", "ходовые огни", "дневные огни");
        // Only the rear fog lamp is in the catalog: a bare «противотуманки» could mean the front ones and is refused.
        put(noun(Family.REAR_FOG, Form.ANY, true), "задние противотуманные фары", "задние противотуманки");
        put(noun(Family.LOGO, Form.ANY, false), "подсветку логотипа", "подсветка логотипа", "логотип");

        // Positions. Neuter → a window, feminine → a door or a shade, plural → windows (prototype rules).
        put(at(Form.NEUT, 'L', null), "левое");
        put(at(Form.NEUT, 'R', null), "правое");
        put(at(Form.NEUT, null, 1), "переднее");
        put(at(Form.NEUT, null, 2), "заднее");
        put(at(Form.NEUT, 'L', 1), "водительское");
        put(at(Form.NEUT, 'R', 1), "пассажирское");
        put(at(Form.FEM, 'L', null), "левую", "левая");
        put(at(Form.FEM, 'R', null), "правую", "правая");
        put(at(Form.FEM, null, 1), "переднюю", "передняя");
        put(at(Form.FEM, null, 2), "заднюю", "задняя");
        put(at(Form.FEM, 'L', 1), "водительскую");
        put(at(Form.FEM, 'R', 1), "пассажирскую");
        put(at(Form.PL, 'L', null), "левые");
        put(at(Form.PL, 'R', null), "правые");
        put(at(Form.PL, null, 1), "передние", "передних");
        put(at(Form.PL, null, 2), "задние", "задних");
        put(new Position(Form.PL, null, null, true), "все");
        put(at(Form.ANY, 'L', null), "слева", "левого", "с левой стороны");
        put(at(Form.ANY, 'R', null), "справа", "правого", "с правой стороны");
        put(at(Form.ANY, null, 1), "спереди", "переднего", "первого ряда", "первый ряд", "переднего ряда");
        put(at(Form.ANY, null, 2), "сзади", "заднего", "второго ряда", "второй ряд", "во втором ряду", "заднего ряда");
        put(at(Form.ANY, null, 3), "третьего ряда", "третий ряд", "в третьем ряду");
        put(new Position(Form.PL, 'L', null, false), "левую сторону");
        put(new Position(Form.PL, 'R', null, false), "правую сторону");
        put(at(Form.ANY, 'L', 1), "водителя", "водителю", "у водителя", "для водителя", "водительского");
        put(at(Form.ANY, 'R', 1), "пассажира", "пассажиру", "переднего пассажира", "у пассажира", "для пассажира",
                "у переднего пассажира", "пассажирского");
        put(new Position(Form.ANY, null, null, true), "везде", "во всем салоне", "по всему салону", "весь салон", "всем", "для всех");

        put(new Unit(UnitKind.DEGREE), "градус", "градуса", "градусов");
        put(new Unit(UnitKind.PERCENT), "процент", "процента", "процентов");
        put(new Unit(UnitKind.LEVEL), "уровень", "уровня", "скорость", "скорости");

        put(new Special(SpecialKind.HALF, null), "наполовину", "на половину");
        put(new Special(SpecialKind.QUARTER, null), "на четверть");
        put(new Special(SpecialKind.FULL, null), "полностью");
        put(new Special(SpecialKind.MAX, null), "на максимум", "максимально", "на полную", "на максимальную");
        put(new Special(SpecialKind.MIN, null), "на минимум", "минимально", "на минимальную");
        put(new Special(SpecialKind.LITTLE, null), "немного", "чуть");
        put(new Special(SpecialKind.AUTO, null), "авто", "в авто", "на авто", "автоматически", "в режим авто");
        put(new Special(SpecialKind.AIRFLOW, "face"), "в лицо");
        put(new Special(SpecialKind.AIRFLOW, "feet"), "в ноги");
        put(new Special(SpecialKind.AIRFLOW, "windshield"), "на стекло", "на лобовое", "на лобовое стекло");
        put(new Special(SpecialKind.AIRFLOW, "face_feet"), "в лицо и ноги", "в лицо и в ноги");
        put(new Special(SpecialKind.AIRFLOW, "feet_windshield"), "в ноги и на стекло", "в ноги и стекло");
        put(new Special(SpecialKind.AIRFLOW, "face_windshield"), "в лицо и на стекло");
        put(new Special(SpecialKind.AIRFLOW, "face_feet_windshield"), "в лицо ноги и стекло");
        put(new Special(SpecialKind.MASSAGE_MODE, "wave"), "волна", "волну");
        put(new Special(SpecialKind.MASSAGE_MODE, "pulse"), "пульс", "пульсацию");
        put(new Special(SpecialKind.MASSAGE_MODE, "stretch"), "растяжку", "растяжка");
        put(new Special(SpecialKind.MASSAGE_MODE, "waist"), "поясницу", "поясницы");
        put(new Special(SpecialKind.MASSAGE_MODE, "shoulders"), "плечи", "плеч");

        put(new Relative(1, true), "потеплее", "теплее", "погорячее", "жарче");
        put(new Relative(-1, true), "похолоднее", "холоднее", "прохладнее");
        put(new Relative(1, false), "посильнее", "сильнее", "побольше", "мощнее", "интенсивнее");
        put(new Relative(-1, false), "послабее", "слабее", "поменьше", "потише");

        put(new Word(WordKind.CONJ), "и", "а", "потом", "затем", "еще", "и потом", "а потом", "и затем", "а затем",
                "и еще", "а еще", "а также", "также");
        // Accepted by the window/door prototype; absent from the recognizer's vocabulary, so never in the grammar.
        put(new Word(WordKind.CONJ), "and", "then", "and then");
        put(new Word(WordKind.FILLER), "пожалуйста", "давай", "слушай");
        put(new Word(WordKind.PREP), "на");
        put(new Word(WordKind.TARGET), "до");
        put(new Word(WordKind.CORRECTION), "нет");
        put(new Word(WordKind.NEGATION), "не", "не надо");

        LEXICON = Collections.unmodifiableMap(lexicon);
        int longest = 1;
        for (String phrase : LEXICON.keySet()) longest = Math.max(longest, phrase.split(" ").length);
        MAX_WORDS = longest;

        Map<String, Integer> units = new LinkedHashMap<>(), tens = new LinkedHashMap<>(), whole = new LinkedHashMap<>(),
                spokenNumbers = new LinkedHashMap<>();
        String[] unitWords = {"один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
        for (int i = 0; i < unitWords.length; i++) units.put(unitWords[i], i + 1);
        units.put("одну", 1);
        units.put("две", 2);
        String[] tenWords = {"двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
        for (int i = 0; i < tenWords.length; i++) tens.put(tenWords[i], (i + 2) * 10);
        String[] teens = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
                "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
        for (int i = 0; i < teens.length; i++) whole.put(teens[i], 10 + i);
        whole.put("ноль", 0);
        whole.put("сто", 100);
        String[][] spoken = {{"единицу", "1"}, {"двойку", "2"}, {"тройку", "3"}, {"четверку", "4"}, {"пятерку", "5"},
                {"шестерку", "6"}, {"семерку", "7"}, {"первый", "1"}, {"первую", "1"}, {"второй", "2"}, {"вторую", "2"},
                {"третий", "3"}, {"третью", "3"}, {"четвертый", "4"}, {"четвертую", "4"}, {"пятый", "5"}, {"пятую", "5"},
                {"шестой", "6"}, {"шестую", "6"}, {"седьмой", "7"}, {"седьмую", "7"}};
        for (String[] pair : spoken) spokenNumbers.put(pair[0], Integer.parseInt(pair[1]));
        UNITS_WORDS = Collections.unmodifiableMap(units);
        TENS_WORDS = Collections.unmodifiableMap(tens);
        WHOLE_NUMBERS = Collections.unmodifiableMap(whole);
        SPOKEN_NUMBERS = Collections.unmodifiableMap(spokenNumbers);
    }

    /** The recognizer spells these words with «ё»; parsing folds «ё» into «е». */
    static final Map<String, String> SPELLING = Map.of("еще", "ещё", "четверку", "четвёрку", "пятерку", "пятёрку",
            "шестерку", "шестёрку", "семерку", "семёрку", "четвертый", "четвёртый", "четвертую", "четвёртую",
            "во всем салоне", "во всём салоне");

    static String spell(String phrase) {
        String exact = SPELLING.get(phrase);
        if (exact != null) return exact;
        StringBuilder spelled = new StringBuilder();
        for (String word : phrase.split(" ")) {
            if (spelled.length() > 0) spelled.append(' ');
            spelled.append(SPELLING.getOrDefault(word, word));
        }
        return spelled.toString();
    }

    // ---------------------------------------------------------------- chunking

    static List<Chunk> chunk(List<String> words) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            Chunk found = null;
            int length = 0;
            for (int n = Math.min(MAX_WORDS, words.size() - i); n >= 1 && found == null; n--) {
                Chunk candidate = LEXICON.get(String.join(" ", words.subList(i, i + n)));
                if (candidate != null) { found = candidate; length = n; }
            }
            if (found != null) {
                chunks.add(found);
                i += length;
                continue;
            }
            String word = words.get(i);
            if (word.matches("[0-9]{1,3}%?")) {
                boolean percent = word.endsWith("%");
                chunks.add(new Num(Integer.parseInt(percent ? word.substring(0, word.length() - 1) : word), true));
                if (percent) chunks.add(new Unit(UnitKind.PERCENT));
                i++;
            } else if (TENS_WORDS.containsKey(word)) {
                int value = TENS_WORDS.get(word);
                i++;
                if (i < words.size() && UNITS_WORDS.containsKey(words.get(i))) value += UNITS_WORDS.get(words.get(i++));
                chunks.add(new Num(value, true));
            } else if (UNITS_WORDS.containsKey(word)) {
                chunks.add(new Num(UNITS_WORDS.get(word), true));
                i++;
            } else if (WHOLE_NUMBERS.containsKey(word)) {
                chunks.add(new Num(WHOLE_NUMBERS.get(word), true));
                i++;
            } else if (SPOKEN_NUMBERS.containsKey(word)) {
                chunks.add(new Num(SPOKEN_NUMBERS.get(word), false));
                i++;
            } else throw rejected();
        }
        return chunks;
    }

    // ---------------------------------------------------------------- clauses and items

    static final class Item {
        Noun noun;
        /** borrowed: the noun came from a sibling («подогрев водителя и пассажира», «обдув в ноги и в лицо»). */
        boolean seat, seatPlural, borrowed;
        final List<Position> positions = new ArrayList<>();
        Value value;
        Relative relative;
    }
    /**
     * preposition: «на» or «до» stood before the number; target: it was «до» (an absolute value even after
     * «прибавь» or «потеплее»); cardinal: see {@link Num}.
     */
    record Value(SpecialKind special, String mode, Integer number, UnitKind unit, boolean preposition, boolean target,
                 boolean cardinal) {
        static Value special(Special special) { return new Value(special.kind(), special.mode(), null, null, false, false, true); }
    }

    static final class Clause {
        Verb verb;
        boolean correction;
        final List<Item> items = new ArrayList<>();
    }

    private static boolean isWord(Chunk chunk, WordKind kind) { return chunk instanceof Word word && word.kind() == kind; }

    /** Splits chunks into clauses; each verb-free span after a connector coordinates another item. */
    static List<Clause> clauses(List<Chunk> chunks) throws IOException {
        List<Clause> clauses = new ArrayList<>();
        List<List<Chunk>> spans = new ArrayList<>();
        List<Chunk> span = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (isWord(chunk, WordKind.CONJ)) { spans.add(span); span = new ArrayList<>(); }
            else span.add(chunk);
        }
        spans.add(span);
        for (List<Chunk> part : spans) if (part.isEmpty()) throw rejected();
        Clause current = null;
        boolean firstSpan = true;
        for (List<Chunk> part : spans) {
            boolean spanOpensClause = firstSpan;
            for (Chunk chunk : part) {
                if (isWord(chunk, WordKind.CORRECTION)) break;
                if (chunk instanceof Verb) spanOpensClause = true;
            }
            List<Chunk> portion = new ArrayList<>();
            Verb portionVerb = null;
            boolean portionCorrection = false, afterVerb = false, startsClause = spanOpensClause;
            for (Chunk chunk : part) {
                if (isWord(chunk, WordKind.CORRECTION)) {
                    current = flush(clauses, current, portion, portionVerb, portionCorrection, startsClause);
                    portion = new ArrayList<>();
                    portionVerb = null;
                    portionCorrection = true;
                    afterVerb = false;
                    startsClause = true;
                } else if (chunk instanceof Verb verb) {
                    if (portionVerb != null) {
                        // A verb-first clause may follow another without a connector; anything else is ambiguous.
                        if (!afterVerb) throw rejected();
                        current = flush(clauses, current, portion, portionVerb, portionCorrection, startsClause);
                        portion = new ArrayList<>();
                        portionCorrection = false;
                        startsClause = true;
                    }
                    portionVerb = verb;
                    afterVerb = false;
                } else {
                    portion.add(chunk);
                    if (portionVerb != null) afterVerb = true;
                }
            }
            current = flush(clauses, current, portion, portionVerb, portionCorrection, startsClause);
            firstSpan = false;
        }
        return clauses;
    }

    private static Clause flush(List<Clause> clauses, Clause current, List<Chunk> portion, Verb verb,
                                boolean correction, boolean startsClause) throws IOException {
        if (correction && verb == null) throw rejected();
        Item item = portion.isEmpty() ? null : item(portion);
        if (startsClause || current == null) {
            Clause clause = new Clause();
            clause.verb = verb;
            clause.correction = correction;
            if (item != null) clause.items.add(item);
            else if (!correction) throw rejected();
            clauses.add(clause);
            return clause;
        }
        if (item == null) throw rejected();
        current.items.add(item);
        return current;
    }

    /** One item: an optional object, positions, at most one value and at most one relative word. */
    static Item item(List<Chunk> chunks) throws IOException {
        Item item = new Item();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (chunk instanceof Noun noun) {
                if (item.noun != null) throw rejected();
                item.noun = noun;
            } else if (chunk instanceof Seat seat) {
                if (item.seat) throw rejected();
                item.seat = true;
                item.seatPlural = seat.plural();
            } else if (chunk instanceof Position position) {
                item.positions.add(position);
            } else if (chunk instanceof Relative relative) {
                if (item.relative != null) throw rejected();
                item.relative = relative;
            } else if (chunk instanceof Special special) {
                if (item.value != null) throw rejected();
                item.value = Value.special(special);
            } else if (isWord(chunk, WordKind.PREP) || isWord(chunk, WordKind.TARGET) || chunk instanceof Num) {
                if (item.value != null) throw rejected();
                boolean preposition = !(chunk instanceof Num), target = isWord(chunk, WordKind.TARGET);
                if (preposition && (++i >= chunks.size() || !(chunks.get(i) instanceof Num))) throw rejected();
                Num number = (Num) chunks.get(i);
                UnitKind unit = null;
                if (i + 1 < chunks.size() && chunks.get(i + 1) instanceof Unit u) { unit = u.kind(); i++; }
                // «открой два окна», «три окна открой»: a number right before an object counts objects.
                if (unit == null && i + 1 < chunks.size() && (chunks.get(i + 1) instanceof Noun || chunks.get(i + 1) instanceof Seat))
                    throw rejected();
                item.value = new Value(null, null, number.value(), unit, preposition, target, number.cardinal());
            } else throw rejected(); // a dangling unit, a filler left in place or another stray word
        }
        if (item.noun == null && !item.seat && item.positions.isEmpty() && item.value == null && item.relative == null)
            throw rejected();
        return item;
    }

    // ---------------------------------------------------------------- resolution

    /** Utterance-wide context: the side of the last explicitly sided window or door. */
    static final class Context {
        Character side;
        Context copy() {
            Context copy = new Context();
            copy.side = side;
            return copy;
        }
    }

    static List<VoiceCommands.Command> parse(List<String> words) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        for (Chunk chunk : chunk(words)) {
            if (isWord(chunk, WordKind.NEGATION)) throw rejected();
            if (!isWord(chunk, WordKind.FILLER)) chunks.add(chunk);
        }
        if (chunks.isEmpty()) throw rejected();
        List<Clause> clauses = clauses(chunks);
        Context context = new Context();
        LinkedHashMap<String, String> desired = new LinkedHashMap<>();
        List<List<VoiceCommands.Command>> resolved = new ArrayList<>();
        Clause previous = null;
        for (Clause clause : clauses) {
            List<VoiceCommands.Command> commands;
            if (clause.correction) {
                if (previous == null || resolved.isEmpty()) throw rejected();
                // A spoken correction keeps exactly the preceding frame's targets.
                Clause replacement = new Clause();
                replacement.verb = clause.verb;
                if (!clause.items.isEmpty()) replacement.items.addAll(clause.items);
                else for (Item earlier : previous.items) {
                    Item copy = new Item();
                    copy.noun = earlier.noun;
                    copy.seat = earlier.seat;
                    copy.seatPlural = earlier.seatPlural;
                    copy.positions.addAll(earlier.positions);
                    replacement.items.add(copy);
                }
                commands = resolve(replacement, context);
                if (!targets(commands).equals(targets(resolved.get(resolved.size() - 1)))) throw rejected();
            } else {
                commands = resolve(clause, context);
                previous = clause;
            }
            resolved.add(commands);
            for (VoiceCommands.Command command : commands) {
                desired.remove(command.target());
                desired.put(command.target(), command.action());
            }
        }
        if (desired.isEmpty()) throw rejected();
        // Later actions replace earlier ones for a target, in the order targets were first named.
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        for (List<VoiceCommands.Command> commands : resolved)
            for (VoiceCommands.Command command : commands) ordered.putIfAbsent(command.target(), desired.get(command.target()));
        List<VoiceCommands.Command> result = new ArrayList<>();
        for (var entry : ordered.entrySet()) result.add(new VoiceCommands.Command(entry.getKey(), entry.getValue()));
        return result;
    }

    private static Set<String> targets(List<VoiceCommands.Command> commands) {
        Set<String> targets = new LinkedHashSet<>();
        for (VoiceCommands.Command command : commands) targets.add(command.target());
        return targets;
    }

    /**
     * One clause: nouns, positions and a value said once are shared between its items, each item resolves on
     * its own, and their actions merge. Two different actions for one target in one clause reject the whole
     * utterance, except airflow directions of one zone (combined) and a named member of a named group
     * («открой окна и левое на двадцать»: the left window keeps its own value).
     */
    static List<VoiceCommands.Command> resolve(Clause clause, Context context) throws IOException {
        List<Item> items = clause.items;
        inheritNouns(items);
        sharePositions(items, clause.verb, context);
        shareValue(items, clause.verb, context);
        List<List<VoiceCommands.Command>> resolved = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) resolved.add(resolve(items.get(i), clause.verb, context, i > 0));
        return merge(resolved);
    }

    private static boolean agrees(Position position, Noun noun) {
        return position.form() == Form.ANY || noun.form() == Form.ANY || position.form() == noun.form();
    }

    /**
     * «левую и правую дверь», «подогрев водителя и пассажира»: a noun-less item with positions borrows a sibling's
     * noun. «обдув в ноги и в лицо»: a bare airflow direction borrows the noun of a sibling that sets airflow.
     */
    private static void inheritNouns(List<Item> items) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.noun != null) continue;
            boolean airflow = item.positions.isEmpty() && !item.seat && item.relative == null
                    && special(item.value, SpecialKind.AIRFLOW);
            if (item.positions.isEmpty() && !airflow) continue;
            Noun borrowed = null;
            for (int j = i - 1; j >= 0 && borrowed == null; j--)
                borrowed = airflow ? airflowNoun(items.get(j)) : borrowable(items.get(j), item);
            for (int j = i + 1; j < items.size() && borrowed == null; j++)
                borrowed = airflow ? airflowNoun(items.get(j)) : borrowable(items.get(j), item);
            if (borrowed != null) {
                item.noun = borrowed;
                item.borrowed = true;
                if (!airflow && items.stream().anyMatch(other -> other.seat)) item.seat = true;
            }
        }
    }

    private static Noun borrowable(Item sibling, Item item) {
        if (sibling.noun == null) return null;
        for (Position position : item.positions) if (!agrees(position, sibling.noun)) return null;
        // Bare adverbs and roles («спереди», «пассажира») keep the prototype's window meaning next to windows
        // and doors; they borrow only functions positioned by seat or zone («подогрев водителя и пассажира»).
        boolean gendered = item.positions.stream().anyMatch(p -> p.form() != Form.ANY);
        if (!gendered && (sibling.noun.family() == Family.WINDOW || sibling.noun.family() == Family.DOOR)) return null;
        // A plural sibling noun lends its family, not its number: «левое и правое окно» are two windows.
        if (sibling.noun.plural() && !sibling.noun.family().equals(Family.LOCKS)) {
            boolean singular = item.positions.stream().anyMatch(p -> p.form() == Form.NEUT || p.form() == Form.FEM);
            if (singular) return new Noun(sibling.noun.family(), item.positions.get(0).form(), false, sibling.noun.needsPosition());
        }
        return sibling.noun;
    }

    private static Noun airflowNoun(Item sibling) {
        if (sibling.noun == null) return null;
        Family family = sibling.noun.family();
        if (family == Family.AIRFLOW) return sibling.noun;
        return (family == Family.FAN || family == Family.CLIMATE) && special(sibling.value, SpecialKind.AIRFLOW) ? sibling.noun : null;
    }

    /**
     * «выключи климат и подогрев сзади», «включи подогрев и вентиляцию пассажира»: positions said once — adverbs,
     * roles and rows only, on the first or the last item, the only item with positions — also place the other
     * objects of the clause that positions can place. Two objects of one kind, each with its own noun, are a
     * group and its member («открой окна и окно водителя на двадцать»); an elided object shares its sibling's
     * noun and its positions («обдув в ноги сзади и в лицо»). Gendered adjectives («левую», «правое») stay
     * with their noun: «закрой окна и левую дверь» still closes every window.
     */
    private static void sharePositions(List<Item> items, Verb verb, Context context) {
        if (items.size() < 2) return;
        int giver = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).positions.isEmpty() && !items.get(i).seatPlural) continue;
            if (giver >= 0) return;
            giver = i;
        }
        if (giver != 0 && giver != items.size() - 1) return;
        Item source = items.get(giver);
        if (source.noun == null || source.positions.isEmpty()) return;
        for (Position position : source.positions) if (position.form() != Form.ANY) return;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (i == giver || item.noun == null) continue;
            if (!item.borrowed && !source.borrowed && item.noun.family() == source.noun.family()) continue;
            item.positions.addAll(source.positions);
            // An object without seats or zones (the A/C, the lights) or one the positions cannot place keeps none.
            if (!placeable(item, verb, context)) item.positions.clear();
        }
    }

    private static boolean placeable(Item item, Verb verb, Context context) {
        try {
            switch (family(item, verb)) {
                case WINDOW: case DOOR: quadrants(item, context.copy()); return true;
                case SHADE: shades(item); return true;
                case CLIMATE: case TEMP: case FAN: case AUTO: case AIRFLOW: zones(item); return true;
                case HEAT: case VENT: seats(item, SEATS); return true;
                case MASSAGE: seats(item, MASSAGE_SEATS); return true;
                default: return false;
            }
        } catch (IOException refused) {
            return false;
        }
    }

    /**
     * «подогрев водителя и пассажира на тройку», «включи подогрев и вентиляцию на тройку»: the only value of a
     * clause, said on its first or last item, goes to the items without one whose kind takes it — degrees to a
     * temperature; a level to a fan, heating, ventilation or massage; a percentage to a window — but never to an
     * item acting on the value item's own targets. «закрой окна и климат на двадцать три» keeps the number with
     * the climate; «открой окна и левое на двадцать» keeps it with the left window.
     */
    private static void shareValue(List<Item> items, Verb verb, Context context) {
        if (items.size() < 2) return;
        int holder = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).value == null && items.get(i).relative == null) continue;
            if (holder >= 0) return;
            holder = i;
        }
        if (holder != 0 && holder != items.size() - 1) return;
        Item source = items.get(holder);
        Family sourceFamily;
        try { sourceFamily = family(source, verb); } catch (IOException unknown) { return; }
        Set<String> sourceTargets = attempt(source, verb, context, holder > 0);
        if (sourceTargets == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (i == holder) continue;
            Item item = items.get(i);
            item.value = source.value;
            item.relative = source.relative;
            boolean keep;
            try { keep = takes(family(item, verb), sourceFamily, source.value); } catch (IOException unknown) { keep = false; }
            if (keep) {
                Set<String> targets = attempt(item, verb, context, i > 0);
                keep = targets != null && Collections.disjoint(targets, sourceTargets);
            }
            if (!keep) { item.value = null; item.relative = null; }
        }
    }

    private static UnitKind kind(Family family) {
        switch (family) {
            case TEMP: return UnitKind.DEGREE;
            case FAN: case HEAT: case VENT: case MASSAGE: return UnitKind.LEVEL;
            case WINDOW: return UnitKind.PERCENT;
            default: return null;
        }
    }

    private static boolean takes(Family family, Family sourceFamily, Value value) {
        if (family == sourceFamily) return true;
        // A direction, a massage program or AUTO belongs to its own function only.
        if (value != null && value.special() != null
                && EnumSet.of(SpecialKind.AIRFLOW, SpecialKind.MASSAGE_MODE, SpecialKind.AUTO).contains(value.special())) return false;
        return kind(family) != null && kind(family) == kind(sourceFamily);
    }

    /** The targets an item would resolve to, or null when it would be refused; the real context is untouched. */
    private static Set<String> attempt(Item item, Verb verb, Context context, boolean coordinated) {
        try { return targets(resolve(item, verb, context.copy(), coordinated)); }
        catch (IOException refused) { return null; }
    }

    /**
     * Merges the items of one clause; see {@link #resolve(Clause, Context)}. A member overrides its group only
     * when its targets of that kind are a strict part of the group's («окна» and «левое»), never when both
     * items name the same targets («сделай двадцать два и потеплее»).
     */
    private static List<VoiceCommands.Command> merge(List<List<VoiceCommands.Command>> items) throws IOException {
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        Map<String, Integer> owners = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            for (VoiceCommands.Command command : items.get(i)) {
                String target = command.target(), earlier = actions.get(target);
                if (earlier == null) { actions.put(target, command.action()); owners.put(target, i); continue; }
                if (earlier.equals(command.action())) continue;
                if (target.endsWith("_mode")) { actions.put(target, airflow(target, earlier, command.action())); continue; }
                Set<String> before = scope(items.get(owners.get(target)), target), now = scope(items.get(i), target);
                if (narrower(now, before)) { actions.put(target, command.action()); owners.put(target, i); }
                else if (!narrower(before, now)) throw rejected(); // «сделай двадцать два и потеплее»: which one?
            }
        }
        List<VoiceCommands.Command> result = new ArrayList<>();
        for (var entry : actions.entrySet()) result.add(new VoiceCommands.Command(entry.getKey(), entry.getValue()));
        return result;
    }

    /** The item's targets of the same kind as this one: every window, every zone temperature, every seat heating. */
    private static Set<String> scope(List<VoiceCommands.Command> commands, String target) {
        Set<String> scope = new LinkedHashSet<>();
        for (VoiceCommands.Command command : commands) if (kind(command.target()).equals(kind(target))) scope.add(command.target());
        return scope;
    }

    private static String kind(String target) {
        if (List.of("fl", "fr", "rl", "rr").contains(target)) return "window";
        int dot = target.lastIndexOf('.'), underscore = target.lastIndexOf('_');
        if (target.startsWith("climate.") && underscore > dot) return "climate." + target.substring(underscore + 1);
        if (target.startsWith("comfort.")) return "comfort" + target.substring(dot);
        if (target.startsWith("door.") || target.startsWith("shade.") || target.startsWith("massage.")) return target.substring(0, target.indexOf('.'));
        return target;
    }

    private static boolean narrower(Set<String> inner, Set<String> outer) {
        return outer.containsAll(inner) && !inner.containsAll(outer);
    }

    /** «обдув в ноги и в лицо»: directions for one zone combine; the rear zones have no windshield outlet. */
    private static String airflow(String target, String first, String second) throws IOException {
        Set<String> parts = new LinkedHashSet<>(List.of((first + "_" + second).split("_")));
        List<String> ordered = new ArrayList<>();
        for (String part : List.of("face", "feet", "windshield")) if (parts.remove(part)) ordered.add(part);
        if (!parts.isEmpty()) throw rejected();
        String mode = String.join("_", ordered);
        boolean front = target.equals("climate.driver_mode") || target.equals("climate.passenger_mode");
        if (!front && !REAR_ZONE_MODES.contains(mode)) throw rejected();
        return mode;
    }

    private static boolean temperatureValue(Value value) {
        if (value == null || value.number() == null || !value.cardinal()) return false;
        if (value.unit() == UnitKind.DEGREE) return true;
        return value.unit() == null && value.number() >= 17 && value.number() <= 33;
    }

    private static boolean movable(Verb verb) {
        return verb != null && (verb.windowOnly() || EnumSet.of(Intent.OPEN, Intent.AJAR, Intent.CLOSE, Intent.STOP).contains(verb.intent()));
    }

    private static Family family(Item item, Verb verb) throws IOException {
        Value value = item.value;
        if (item.noun != null) {
            Family family = item.noun.family();
            if (item.noun.needsPosition() && item.positions.isEmpty() && !item.seat) throw rejected();
            if (family == Family.CLIMATE) {
                if (temperatureValue(value) || (item.relative != null && item.relative.temperature())) return Family.TEMP;
                if (value != null && value.special() == SpecialKind.AUTO) return Family.AUTO;
                if (value != null && value.special() == SpecialKind.AIRFLOW) return Family.AIRFLOW;
            }
            if (family == Family.FAN && value != null && value.special() == SpecialKind.AIRFLOW) return Family.AIRFLOW;
            if (family == Family.HEADLIGHTS && value != null && value.special() == SpecialKind.AUTO) return Family.AUTO_HEADLIGHTS;
            // «заблокируй двери»: locking names the doors but acts on the central lock.
            if (family == Family.DOOR && verb != null && (verb.intent() == Intent.LOCK || verb.intent() == Intent.UNLOCK)) return Family.LOCKS;
            return family;
        }
        if (item.seat) throw rejected(); // «сиденья» alone names no function
        // «волна», «авто», «в режим авто» alone: a program, AUTO or a direction names no function by itself.
        if (value != null && value.special() != null
                && EnumSet.of(SpecialKind.AIRFLOW, SpecialKind.MASSAGE_MODE, SpecialKind.AUTO).contains(value.special())) throw rejected();
        if (!item.positions.isEmpty()) {
            Form form = Form.ANY;
            for (Position position : item.positions) if (position.form() != Form.ANY) form = position.form();
            if (!movable(verb)) {
                // «двадцать четыре градуса пассажиру»: a temperature names the function, positions the zone.
                if ((value != null && value.unit() == UnitKind.DEGREE) || (item.relative != null && item.relative.temperature())) return Family.TEMP;
                // «поставь пассажиру сто», «сделай сорок слева»: adverbs, roles and rows without a movement verb never
                // name a window; a bare number with them is a temperature 17..33 or nothing.
                if (form == Form.ANY) {
                    if (temperatureValue(value)) return Family.TEMP;
                    throw rejected();
                }
            }
            if (form == Form.FEM) return Family.DOOR;
            return Family.WINDOW; // neuter, plural and adverbs alone keep the prototype's window meaning
        }
        if (item.relative != null && item.relative.temperature()) return Family.TEMP;
        if (temperatureValue(value)) return Family.TEMP;
        throw rejected();
    }

    private static boolean plural(Item item) {
        if (item.noun != null && item.noun.form() != Form.ANY) return item.noun.plural();
        boolean sawForm = false;
        for (Position position : item.positions) {
            if (position.form() == Form.PL) return true;
            if (position.form() != Form.ANY) sawForm = true;
        }
        if (item.noun != null) return item.noun.plural();
        return !sawForm; // adverbs alone: «сзади» names both rear windows
    }

    record Where(Character side, Integer row, boolean all) { }

    private static Where where(Item item) throws IOException {
        Character side = null;
        Integer row = null;
        boolean all = false;
        for (Position position : item.positions) {
            if (item.noun != null && !agrees(position, item.noun)) throw rejected();
            if (position.side() != null) {
                if (side != null && !side.equals(position.side())) throw rejected();
                side = position.side();
            }
            if (position.row() != null) {
                if (row != null && !row.equals(position.row())) throw rejected();
                row = position.row();
            }
            all |= position.all();
        }
        return new Where(side, row, all);
    }

    private static String quadrant(Character side, int row) {
        return (row == 1 ? "f" : "r") + (side == 'L' ? "l" : "r");
    }

    /** Windows and doors: the prototype's rules, plus the side of an earlier window or door. */
    private static List<String> quadrants(Item item, Context context) throws IOException {
        Where where = where(item);
        boolean plural = plural(item) || where.all();
        if (where.row() != null && where.row() == 3) throw rejected();
        List<String> result = new ArrayList<>();
        if (where.side() != null && where.row() != null) result.add(quadrant(where.side(), where.row()));
        else if (where.side() != null) {
            result.add(quadrant(where.side(), 1));
            if (plural) result.add(quadrant(where.side(), 2));
        } else if (where.row() != null) {
            if (plural) { result.add(quadrant('L', where.row())); result.add(quadrant('R', where.row())); }
            else if (context.side != null) result.add(quadrant(context.side, where.row()));
            else throw rejected(); // «окно сзади»: which one?
        } else if (plural) result.addAll(List.of("fl", "fr", "rl", "rr"));
        else throw rejected(); // «открой окно»: which one?
        if (where.side() != null) context.side = where.side();
        return result;
    }

    private static List<String> seats(Item item, List<String> available) throws IOException {
        Where where = where(item);
        boolean plural = where.all() || item.seatPlural || item.positions.stream().anyMatch(p -> p.form() == Form.PL);
        List<String> result = new ArrayList<>();
        String[] rows = {null, "f", "r", "t"};
        if (where.side() != null && where.row() != null) result.add(rows[where.row()] + (where.side() == 'L' ? "l" : "r"));
        else if (where.side() != null) result.add("f" + (where.side() == 'L' ? "l" : "r"));
        else if (where.row() != null) {
            // «подогрев заднего сиденья»: one seat of a row of two — which one? «задних сидений» names both.
            if (item.seat && !plural) throw rejected();
            result.add(rows[where.row()] + "l");
            result.add(rows[where.row()] + "r");
        }
        else if (plural) result.addAll(SEATS);
        else result.add("fl"); // «включи подогрев»: the driver's seat
        for (String seat : result) if (!available.contains(seat)) throw rejected();
        return result;
    }

    private static List<String> zones(Item item) throws IOException {
        Where where = where(item);
        List<String> result = new ArrayList<>();
        if (where.all()) return ZONES;
        if (where.row() != null && where.row() == 3) result.add("rear");
        else if (where.row() != null && where.side() != null)
            result.add(where.row() == 1 ? (where.side() == 'L' ? "driver" : "passenger") : (where.side() == 'L' ? "middle_left" : "middle_right"));
        else if (where.row() != null) result.addAll(where.row() == 1 ? List.of("driver", "passenger") : List.of("middle_left", "middle_right"));
        else if (where.side() != null) result.add(where.side() == 'L' ? "driver" : "passenger");
        else result.add("driver"); // a temperature with no zone is the driver's
        return result;
    }

    private static List<String> shades(Item item) throws IOException {
        Where where = where(item);
        if (where.side() != null || (where.row() != null && where.row() == 3)) throw rejected();
        if (where.row() != null) return List.of(where.row() == 1 ? "front" : "rear");
        if (plural(item) || where.all()) return List.of("front", "rear");
        return List.of("front");
    }

    private static void noPositions(Item item) throws IOException {
        if (!item.positions.isEmpty() || item.seat) throw rejected();
    }

    private static Intent intent(Verb verb, Family family, Item item) throws IOException {
        if (verb == null) {
            if (item.value != null || item.relative != null) return Intent.SET;
            throw rejected();
        }
        if (verb.windowOnly() && family != Family.WINDOW && !LEVELS.contains(family)) throw rejected();
        return LEVELS.contains(family) ? verb.level() : verb.intent();
    }

    /** coordinated: the item is not the first of its clause. */
    private static List<VoiceCommands.Command> resolve(Item item, Verb verb, Context context, boolean coordinated) throws IOException {
        Family family = family(item, verb);
        Intent intent = intent(verb, family, item);
        List<VoiceCommands.Command> result = actions(family, intent, item, context);
        // «закрой окна и климат на двадцать три»: a movement verb shared with a later object that does not move
        // leaves the value to set it. The verb's own object («открой климат на двадцать»), any other verb
        // («выключи свет на авто») and a verb that applies («закрой окна на пятьдесят») never turn into setting.
        if (result == null && coordinated && movable(verb) && (item.value != null || item.relative != null)
                && !applies(family, intent, item, context))
            result = actions(family, Intent.SET, item, context);
        if (result == null || result.isEmpty()) throw rejected();
        return result;
    }

    private static boolean applies(Family family, Intent intent, Item item, Context context) throws IOException {
        Item bare = new Item();
        bare.noun = item.noun;
        bare.seat = item.seat;
        bare.seatPlural = item.seatPlural;
        bare.positions.addAll(item.positions);
        return actions(family, intent, bare, context) != null;
    }

    private static List<VoiceCommands.Command> each(String prefix, List<String> keys, String suffix, String action) {
        List<VoiceCommands.Command> result = new ArrayList<>();
        for (String key : keys) result.add(new VoiceCommands.Command(prefix + key + suffix, action));
        return result;
    }

    private static Integer number(Value value, UnitKind... allowed) {
        if (value == null || value.number() == null) return null;
        if (value.unit() == null) return value.number();
        for (UnitKind unit : allowed) if (unit == value.unit()) return value.number();
        return null;
    }

    private static boolean special(Value value, SpecialKind kind) { return value != null && value.special() == kind; }

    /** A step after «прибавь» or «потеплее»: one without a value, else a cardinal number with this unit or none. */
    private static int step(Value value, UnitKind unit, int maximum) throws IOException {
        if (value == null) return 1;
        if (value.number() == null || !value.cardinal() || (value.unit() != null && value.unit() != unit)) throw rejected();
        if (value.number() < 1 || value.number() > maximum) throw rejected();
        return value.number();
    }

    /** «до N» after «убавь» or «посильнее»: the absolute value N, never a step. */
    private static String target(Value value, UnitKind unit, int minimum, int maximum, boolean cardinalOnly) throws IOException {
        Integer number = number(value, unit);
        if (number == null || (cardinalOnly && !value.cardinal()) || number < minimum || number > maximum) throw rejected();
        return Integer.toString(number);
    }

    /** Returns null when the intent does not apply to the family; throws when a value is invalid. */
    private static List<VoiceCommands.Command> actions(Family family, Intent intent, Item item, Context context) throws IOException {
        Value value = item.value;
        Relative relative = item.relative;
        switch (family) {
            case WINDOW: {
                if (relative != null) return null;
                String action = windowAction(intent, value);
                if (action == null) return null;
                return each("", quadrants(item, context), "", action);
            }
            case DOOR: case SHADE: {
                if (relative != null) return null;
                if (value != null && !special(value, SpecialKind.FULL)) return null;
                String action = switch (intent) { case OPEN -> "open"; case CLOSE -> "close"; case STOP -> "stop"; default -> null; };
                if (action == null) return null;
                return family == Family.DOOR ? each("door.", quadrants(item, context), "", action)
                        : each("shade.", shades(item), "", action);
            }
            case LOCKS: {
                if (value != null || relative != null) return null;
                Where where = where(item);
                if (where.side() != null || where.row() != null || item.seat) throw rejected();
                String action = switch (intent) { case LOCK, CLOSE -> "lock"; case UNLOCK -> "unlock"; default -> null; };
                return action == null ? null : List.of(new VoiceCommands.Command("locks", action));
            }
            case CLIMATE: {
                if (value != null || relative != null) return null;
                String action = onOff(intent);
                if (action == null) return null;
                if (item.positions.isEmpty()) return List.of(new VoiceCommands.Command("climate.power", action));
                return each("climate.", zones(item), "_power", action);
            }
            case AC: case FRONT_DEFROST: case REAR_DEFROST: case RECIRC: case FRESH_AIR:
            case MATRIX: case DRL: case REAR_FOG: case LOGO: {
                noPositions(item);
                if (value != null || relative != null) return null;
                String action = onOff(intent);
                if (action == null) return null;
                return switch (family) {
                    case AC -> List.of(new VoiceCommands.Command("climate.ac", action));
                    case FRONT_DEFROST -> List.of(new VoiceCommands.Command("climate.front_defrost", action));
                    case REAR_DEFROST -> List.of(new VoiceCommands.Command("climate.rear_defrost", action));
                    case RECIRC -> List.of(new VoiceCommands.Command("climate.recirculation", action.equals("on") ? "internal" : "external"));
                    case FRESH_AIR -> List.of(new VoiceCommands.Command("climate.recirculation", action.equals("on") ? "external" : "internal"));
                    case MATRIX -> List.of(new VoiceCommands.Command("light.matrix", action));
                    case DRL -> List.of(new VoiceCommands.Command("light.drl", action));
                    case REAR_FOG -> List.of(new VoiceCommands.Command("light.rear_fog", action));
                    default -> List.of(new VoiceCommands.Command("light.logo", action));
                };
            }
            case HEADLIGHTS: case AUTO_HEADLIGHTS: {
                noPositions(item);
                if (relative != null) return null;
                if (family == Family.AUTO_HEADLIGHTS || special(value, SpecialKind.AUTO)) {
                    if (intent != Intent.ON && intent != Intent.SET) return null;
                    return List.of(new VoiceCommands.Command("light.headlights", "auto"));
                }
                if (value != null) return null;
                String action = switch (intent) { case ON -> "low"; case OFF -> "off"; default -> null; };
                return action == null ? null : List.of(new VoiceCommands.Command("light.headlights", action));
            }
            case TEMP: return temperature(intent, item);
            case FAN: return fan(intent, item);
            case AUTO: {
                if (relative != null || (value != null && !special(value, SpecialKind.AUTO))) return null;
                String action = intent == Intent.SET && special(value, SpecialKind.AUTO) ? "on" : onOff(intent);
                return action == null ? null : each("climate.", zones(item), "_auto", action);
            }
            case AIRFLOW: {
                if (relative != null || !special(value, SpecialKind.AIRFLOW)) return null;
                if (intent != Intent.SET && intent != Intent.ON) return null;
                List<String> zones = zones(item);
                for (String zone : zones)
                    if (!zone.equals("driver") && !zone.equals("passenger") && !REAR_ZONE_MODES.contains(value.mode())) throw rejected();
                return each("climate.", zones, "_mode", value.mode());
            }
            case HEAT: case VENT: return comfort(family, intent, item);
            case MASSAGE: return massage(intent, item);
            default: return null;
        }
    }

    private static String onOff(Intent intent) {
        return switch (intent) { case ON -> "on"; case OFF -> "off"; default -> null; };
    }

    private static String windowAction(Intent intent, Value value) throws IOException {
        Integer percent = null;
        if (value != null) {
            if (special(value, SpecialKind.FULL)) {
                if (intent == Intent.OPEN || intent == Intent.CLOSE) return intent == Intent.OPEN ? "open" : "close";
                return null;
            }
            if (special(value, SpecialKind.HALF)) percent = 50;
            else if (special(value, SpecialKind.QUARTER)) percent = 25;
            else if (special(value, SpecialKind.LITTLE)) percent = AJAR_PERCENT;
            else if (value.special() != null) return null;
            else {
                // «на тройку» names a level and «два окна» counts windows: a position needs «на»/«до» or «процентов».
                if (!value.cardinal() || (value.unit() == null && !value.preposition())) throw rejected();
                percent = number(value, UnitKind.PERCENT);
                if (percent == null) return null;
                if (percent > 100) throw rejected();
            }
        }
        switch (intent) {
            case OPEN: case AJAR: case SET:
                if (percent == null) return intent == Intent.OPEN ? "open" : intent == Intent.AJAR ? AJAR_PERCENT + "%" : null;
                return percent == 0 ? "close" : percent == 100 ? "open" : percent + "%";
            case CLOSE: return percent == null ? "close" : null;
            case STOP: return percent == null ? "stop" : null;
            default: return null;
        }
    }

    private static List<VoiceCommands.Command> temperature(Intent intent, Item item) throws IOException {
        Value value = item.value;
        Relative relative = item.relative;
        String action;
        if (relative != null && !relative.temperature()) return null;
        if (relative != null || intent == Intent.UP || intent == Intent.DOWN) {
            if (intent != Intent.SET && intent != Intent.UP && intent != Intent.DOWN) return null;
            if (value != null && value.target()) action = target(value, UnitKind.DEGREE, 17, 33, true);
            else {
                int direction = relative != null ? relative.delta() : intent == Intent.UP ? 1 : -1;
                action = delta(direction * step(value, UnitKind.DEGREE, 16));
            }
        } else if (intent == Intent.SET || intent == Intent.ON) {
            if (special(value, SpecialKind.MAX)) action = "33";
            else if (special(value, SpecialKind.MIN)) action = "17";
            else {
                Integer degrees = number(value, UnitKind.DEGREE);
                if (degrees == null) return null;
                // «на тройку» is a level word, never degrees.
                if (!value.cardinal() || degrees < 17 || degrees > 33) throw rejected();
                action = Integer.toString(degrees);
            }
        } else return null;
        List<VoiceCommands.Command> result = new ArrayList<>();
        // «включи климат на двадцать три»: the climate is switched on, then the temperature is set;
        // «включи климат сзади на двадцать три» switches on the named zones instead.
        if (intent == Intent.ON && item.noun != null && item.noun.family() == Family.CLIMATE)
            result.addAll(item.positions.isEmpty() ? List.of(new VoiceCommands.Command("climate.power", "on"))
                    : each("climate.", zones(item), "_power", "on"));
        result.addAll(each("climate.", zones(item), "_temperature", action));
        return result;
    }

    private static List<VoiceCommands.Command> fan(Intent intent, Item item) throws IOException {
        Value value = item.value;
        Relative relative = item.relative;
        String action;
        if (relative != null && relative.temperature()) return null;
        if (relative != null || intent == Intent.UP || intent == Intent.DOWN) {
            if (intent != Intent.SET && intent != Intent.UP && intent != Intent.DOWN) return null;
            if (value != null && value.target()) action = target(value, UnitKind.LEVEL, 0, 7, false);
            else {
                // «вентилятор посильнее на два»: a relative word takes a step as «прибавь» does.
                int direction = relative != null ? relative.delta() : intent == Intent.UP ? 1 : -1;
                action = delta(direction * step(value, UnitKind.LEVEL, 7));
            }
        } else if (intent == Intent.OFF) {
            if (value != null) return null;
            action = "0";
        } else if (intent == Intent.SET || intent == Intent.ON) {
            if (special(value, SpecialKind.MAX)) action = "7";
            else if (special(value, SpecialKind.MIN)) action = "1";
            else {
                Integer level = number(value, UnitKind.LEVEL);
                if (level == null) return null;
                if (level > 7) throw rejected();
                action = Integer.toString(level);
            }
        } else return null;
        return each("climate.", zones(item), "_fan", action);
    }

    private static List<VoiceCommands.Command> comfort(Family family, Intent intent, Item item) throws IOException {
        Value value = item.value;
        Relative relative = item.relative;
        String kind = family == Family.HEAT ? ".heat" : ".vent";
        String action;
        if (relative != null && relative.temperature()) return null;
        if (relative != null || intent == Intent.UP || intent == Intent.DOWN) {
            if (intent != Intent.SET && intent != Intent.UP && intent != Intent.DOWN) return null;
            if (value != null && value.target()) action = target(value, UnitKind.LEVEL, 0, 3, false);
            else if (relative != null) action = delta(relative.delta() * step(value, UnitKind.LEVEL, 3));
            else {
                if (value != null) return null;
                action = delta(intent == Intent.UP ? 1 : -1);
            }
        } else if (intent == Intent.OFF) {
            if (value != null) return null;
            action = "0";
        } else if (intent == Intent.ON || intent == Intent.SET) {
            if (value == null) {
                if (intent == Intent.SET) return null;
                action = Integer.toString(COMFORT_DEFAULT);
            } else if (special(value, SpecialKind.MAX)) action = "3";
            else if (special(value, SpecialKind.MIN)) action = "1";
            else {
                Integer level = number(value, UnitKind.LEVEL);
                if (level == null) return null;
                if (level > 3) throw rejected();
                action = Integer.toString(level);
            }
        } else return null;
        return each("comfort.", seats(item, SEATS), kind, action);
    }

    private static List<VoiceCommands.Command> massage(Intent intent, Item item) throws IOException {
        if (item.relative != null) return null;
        Value value = item.value;
        List<String> seats = seats(item, MASSAGE_SEATS);
        if (intent == Intent.OFF) return value == null ? each("massage.", seats, "", "off") : null;
        if (intent != Intent.ON && intent != Intent.SET) return null;
        if (special(value, SpecialKind.MASSAGE_MODE)) return each("massage.", seats, "", value.mode());
        if (value == null) return intent == Intent.ON ? each("massage.", seats, "", "on") : null;
        Integer level = special(value, SpecialKind.MAX) ? Integer.valueOf(3) : special(value, SpecialKind.MIN) ? Integer.valueOf(1)
                : number(value, UnitKind.LEVEL);
        if (level == null) return null;
        if (level < 1 || level > 3) throw rejected();
        return each("massage.", seats, "", "level" + level);
    }

    private static String delta(int value) { return "delta:" + (value > 0 ? "+" : "") + value; }

    static IOException rejected() {
        return new IOException("Не удалось разобрать всю фразу: неподдерживаемые слова или неполная команда. Ничего не отправлено");
    }
}
