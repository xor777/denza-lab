package dev.denza.vehiclecontrol.probe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Example phrases for the recognizer. Vosk estimates a bigram model from them, so they need to cover
 * every word pair a command can contain, not every sentence: verb + object, object + verb, number +
 * unit, connector + next command. Every phrase must parse completely; the spelling follows the
 * recognizer's vocabulary («ещё», «пятёрку»).
 */
final class VoiceGrammar {
    private VoiceGrammar() { }

    static final List<String> OPEN = List.of("открой", "открыть", "открывай", "опусти", "опустить", "опускай");
    static final List<String> CLOSE = List.of("закрой", "закрыть", "закрывай", "подними", "поднять", "поднимай");
    static final List<String> STOP = List.of("останови", "остановить", "стоп", "останови движение", "прекрати движение");
    static final List<String> CONNECTORS = List.of("и", "а", "потом", "затем", "еще", "и потом", "а потом", "и затем",
            "а затем", "и еще", "а еще", "а также", "также");

    /** The window and door phrases the window/door prototype accepted, kept word for word. */
    static List<String> movableObjects() {
        List<String> objects = new ArrayList<>(List.of("окна", "все окна", "все", "стекла", "все стекла",
                "передние окна", "все передние окна", "окна спереди", "спереди окна", "передние", "спереди",
                "задние окна", "все задние окна", "окна сзади", "сзади окна", "задние", "сзади",
                "левые окна", "все левые окна", "окна слева", "слева окна", "левые", "левую сторону",
                "правые окна", "все правые окна", "окна справа", "справа окна", "правые", "правую сторону",
                "окно водителя", "окно у водителя", "окно слева", "слева окно",
                "окно пассажира", "окно переднего пассажира", "окно у пассажира", "окно справа", "справа окно",
                "левое окно спереди", "переднее окно слева", "правое окно спереди", "переднее окно справа",
                "левое окно сзади", "заднее окно слева", "правое окно сзади", "заднее окно справа",
                "двери", "все двери", "передние двери", "все передние двери", "двери спереди", "спереди двери",
                "задние двери", "все задние двери", "двери сзади", "сзади двери", "левые двери", "все левые двери",
                "двери слева", "слева двери", "правые двери", "все правые двери", "двери справа", "справа двери",
                "дверь водителя", "дверь у водителя", "дверь слева", "слева дверь", "левую дверь спереди",
                "переднюю дверь слева", "дверь пассажира", "дверь переднего пассажира", "дверь у пассажира",
                "дверь справа", "справа дверь", "правую дверь спереди", "переднюю дверь справа",
                "левую дверь сзади", "заднюю дверь слева", "правую дверь сзади", "заднюю дверь справа"));
        String[][] windows = {
                {"левое", "водительское", "переднее левое", "левое переднее", "спереди слева", "слева спереди", "левое спереди", "переднее слева"},
                {"правое", "пассажирское", "переднее правое", "правое переднее", "спереди справа", "справа спереди", "правое спереди", "переднее справа"},
                {"заднее левое", "левое заднее", "сзади слева", "слева сзади", "левое сзади", "заднее слева", "левое заднее сзади", "заднее левое сзади"},
                {"заднее правое", "правое заднее", "сзади справа", "справа сзади", "правое сзади", "заднее справа", "правое заднее сзади", "заднее правое сзади"}};
        for (String[] positions : windows)
            for (String position : positions) {
                objects.add(position);
                objects.add(position + " окно");
                objects.add("окно " + position);
            }
        String[][] doors = {
                {"левую", "водительскую", "переднюю левую", "левую переднюю", "левую спереди", "переднюю слева"},
                {"правую", "пассажирскую", "переднюю правую", "правую переднюю", "правую спереди", "переднюю справа"},
                {"заднюю левую", "левую заднюю", "левую сзади", "заднюю слева", "левую заднюю сзади", "заднюю левую сзади"},
                {"заднюю правую", "правую заднюю", "правую сзади", "заднюю справа", "правую заднюю сзади", "заднюю правую сзади"}};
        for (String[] positions : doors)
            for (String position : positions) {
                objects.add(position);
                objects.add(position + " дверь");
                objects.add("дверь " + position);
            }
        for (String position : List.of("спереди слева", "слева спереди", "спереди справа", "справа спереди",
                "сзади слева", "слева сзади", "сзади справа", "справа сзади")) {
            objects.add(position + " дверь");
            objects.add("дверь " + position);
        }
        return objects;
    }

    static String number(int value) {
        String[] units = {"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
        String[] teens = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
                "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
        String[] tens = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
        if (value == 0) return "ноль";
        if (value == 100) return "сто";
        if (value < 10) return units[value];
        if (value < 20) return teens[value - 10];
        return value % 10 == 0 ? tens[value / 10] : tens[value / 10] + " " + units[value % 10];
    }

    static String counted(int value, String one, String few, String many) {
        int last = value % 10, lastTwo = value % 100;
        if (last == 1 && lastTwo != 11) return one;
        if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return few;
        return many;
    }

    static String degrees(int value) { return number(value) + " " + counted(value, "градус", "градуса", "градусов"); }
    static String percent(int value) { return number(value) + " " + counted(value, "процент", "процента", "процентов"); }

    /** Representative commands for each control beyond windows and doors. */
    static List<String> controls() {
        List<String> phrases = new ArrayList<>();
        // Temperature: every degree once, zones and relative steps on a few.
        for (int t = 17; t <= 33; t++) {
            phrases.add("поставь температуру " + degrees(t));
            phrases.add("поставь температуру на " + degrees(t));
            phrases.add("поставь климат на " + degrees(t));
            phrases.add("сделай " + degrees(t));
            phrases.add("температура " + number(t));
            phrases.add("поставь " + number(t));
            phrases.add("климат на " + number(t));
            phrases.add("включи климат на " + number(t));
        }
        for (String zone : List.of("водителю", "пассажиру", "для водителя", "для пассажира", "сзади", "сзади слева",
                "сзади справа", "во втором ряду", "в третьем ряду", "третьего ряда", "везде", "во всем салоне", "всем", "спереди")) {
            phrases.add("поставь температуру " + degrees(22) + " " + zone);
            phrases.add("поставь " + zone + " " + degrees(24));
            phrases.add("сделай потеплее " + zone);
            phrases.add("выключи климат " + zone);
            phrases.add("поставь вентилятор на три " + zone);
        }
        phrases.add("поставь двадцать два водителю и двадцать четыре пассажиру");
        for (String relative : List.of("потеплее", "теплее", "погорячее", "жарче", "похолоднее", "холоднее", "прохладнее")) {
            phrases.add("сделай " + relative);
            phrases.add(relative);
            phrases.add("сделай " + relative + " на два градуса");
            phrases.add("сделай " + relative + " на один градус");
        }
        for (String verb : List.of("прибавь", "добавь", "увеличь", "повысь", "подними", "убавь", "уменьши", "понизь", "сбавь", "опусти")) {
            phrases.add(verb + " температуру");
            phrases.add(verb + " температуру на два градуса");
            phrases.add(verb + " вентилятор");
        }
        // Fan.
        for (String fan : List.of("вентилятор", "обдув", "скорость вентилятора", "скорость обдува")) {
            for (int level = 0; level <= 7; level++) phrases.add("поставь " + fan + " на " + number(level));
            phrases.add(fan + " на " + number(3));
            phrases.add(fan + " посильнее");
            phrases.add(fan + " послабее");
            phrases.add("выключи " + fan);
            phrases.add("поставь " + fan + " на максимум");
            phrases.add(fan + " на минимум");
        }
        for (String spoken : List.of("единицу", "двойку", "тройку", "четверку", "пятерку", "шестерку", "семерку"))
            phrases.add("включи вентилятор на " + spoken);
        for (String relative : List.of("посильнее", "сильнее", "побольше", "мощнее", "интенсивнее", "послабее", "слабее", "поменьше", "потише"))
            phrases.add("сделай вентилятор " + relative);
        // Climate switches.
        for (String verb : List.of("включи", "выключи", "включить", "выключить", "отключи")) {
            for (String object : List.of("климат", "кондиционер", "режим рециркуляции", "свежий воздух", "внешний воздух",
                    "воздух снаружи", "забор воздуха снаружи", "внутренний воздух", "обдув лобового стекла", "обдув лобового",
                    "обогрев лобового стекла", "обогрев лобового", "обдув стекла", "обогрев заднего стекла", "обогрев зеркал",
                    "обогрев заднего стекла и зеркал", "авто режим", "автоматический режим", "режим авто", "климат авто"))
                phrases.add(verb + " " + object);
        }
        phrases.add("включи кондиционер и режим рециркуляции");
        // Airflow.
        for (String mode : List.of("в лицо", "в ноги", "на стекло", "на лобовое", "на лобовое стекло", "в лицо и ноги",
                "в лицо и в ноги", "в ноги и на стекло", "в ноги и стекло", "в лицо и на стекло", "в лицо ноги и стекло")) {
            phrases.add("обдув " + mode);
            phrases.add("поставь обдув " + mode);
            phrases.add("направление воздуха " + mode);
            phrases.add("воздух " + mode);
        }
        phrases.add("обдув в ноги сзади");
        phrases.add("поставь обдув в лицо пассажиру");
        // Seats: heating and ventilation.
        for (String kind : List.of("подогрев", "вентиляцию")) {
            phrases.add("включи " + kind);
            phrases.add("выключи " + kind);
            for (String seat : List.of("сиденья", "сидений", "кресла", "кресел", "сиденья водителя", "сиденья пассажира",
                    "водителя", "пассажира", "водителю", "пассажиру", "сзади", "сзади слева", "сзади справа", "второго ряда",
                    "третьего ряда", "третьего ряда слева", "третьего ряда справа", "переднего пассажира", "сидений сзади",
                    "заднего левого сиденья", "заднего правого сиденья", "левого заднего сиденья", "правого заднего сиденья"))
                phrases.add("включи " + kind + " " + seat);
            for (int level = 0; level <= 3; level++) phrases.add("поставь " + kind + " на " + number(level));
            phrases.add("включи " + kind + " на тройку");
            phrases.add("включи " + kind + " на третий уровень");
            phrases.add("включи " + kind + " на максимум");
            phrases.add("включи " + kind + " на минимум");
            phrases.add("сделай " + kind + " посильнее");
            phrases.add("сделай " + kind + " послабее");
            phrases.add("включи " + kind + " водителя и пассажира на тройку");
        }
        phrases.add("включи обогрев сиденья");
        phrases.add("включи обогрев сиденья водителя");
        phrases.add("включи обогрев пассажира");
        // Massage.
        for (String seat : List.of("", " водителю", " пассажиру", " сзади слева", " сзади справа", " водителя", " пассажира")) {
            phrases.add("включи массаж" + seat);
            phrases.add("выключи массаж" + seat);
        }
        for (String mode : List.of("волна", "волну", "пульс", "пульсацию", "растяжку", "растяжка", "поясницу", "поясницы", "плечи", "плеч")) {
            phrases.add("включи массаж " + mode);
            phrases.add("поставь массаж " + mode);
        }
        for (int level = 1; level <= 3; level++) phrases.add("поставь массаж на " + number(level));
        phrases.add("включи массаж на максимум");
        phrases.add("массаж на двойку");
        // Lights.
        for (String verb : List.of("включи", "выключи")) {
            for (String light : List.of("фары", "свет", "основной свет", "ближний свет", "ближний", "матричный свет",
                    "матричные фары", "матрицу", "дневные ходовые огни", "ходовые огни", "дневные огни", "противотуманки",
                    "противотуманные фары", "задние противотуманные фары", "противотуманные огни", "подсветку логотипа", "логотип"))
                phrases.add(verb + " " + light);
        }
        for (String auto : List.of("автоматический свет", "автоматические фары", "автосвет")) phrases.add("включи " + auto);
        phrases.add("фары в авто");
        phrases.add("поставь фары на авто");
        phrases.add("переключи свет в режим авто");
        // Locks and shades.
        for (String object : List.of("двери", "все двери", "машину", "замки", "автомобиль")) {
            phrases.add("заблокируй " + object);
            phrases.add("разблокируй " + object);
            phrases.add("запри " + object);
        }
        phrases.add("закрой машину");
        phrases.add("закрой замки");
        phrases.add("закрой на замок двери");
        phrases.add("закрой двери на замок");
        for (String verb : List.of("открой", "закрой", "останови")) {
            for (String shade : List.of("шторку", "шторки", "штору", "шторы", "переднюю шторку", "заднюю шторку", "все шторки"))
                phrases.add(verb + " " + shade);
        }
        // Windows to a position.
        for (int p = 10; p <= 90; p += 10) {
            phrases.add("открой окна на " + percent(p));
            phrases.add("открой левое окно на " + number(p));
            phrases.add("опусти окна на " + percent(p));
            phrases.add("поставь окна на " + percent(p));
        }
        for (int p : new int[]{5, 15, 25, 35, 45, 55, 65, 75, 85, 95}) phrases.add("открой окна на " + percent(p));
        for (String object : List.of("окно водителя", "окна", "заднее левое окно", "заднее правое окно", "левое окно", "правое окно",
                "задние окна", "передние окна", "окно пассажира")) {
            phrases.add("приоткрыть " + object);
            phrases.add("приоткрыть " + object + " на " + percent(20));
            phrases.add("открой " + object + " наполовину");
            phrases.add("открой " + object + " на половину");
            phrases.add("открой " + object + " на четверть");
            phrases.add("открой " + object + " немного");
            phrases.add("открой " + object + " чуть");
            phrases.add("открой " + object + " полностью");
            phrases.add("закрой " + object + " полностью");
            phrases.add(object + " приоткрыть на " + percent(50));
        }
        return phrases;
    }

    /** Commands joined by every connector, across controls, including object-before-verb clauses. */
    static List<String> compositions() {
        List<String> phrases = new ArrayList<>();
        String[][] pairs = {
                {"открой окно слева", "поставь климат на двадцать три градуса"},
                {"поставь климат на двадцать три градуса", "заднее окно приоткрыть на пятьдесят процентов"},
                {"закрой окна", "включи кондиционер"},
                {"включи подогрев водителя", "сделай потеплее"},
                {"открой левое окно", "правую дверь"},
                {"закрой окна", "открой правую дверь"},
                {"открой левое окно", "закрой правое"},
                {"включи массаж", "выключи вентиляцию"},
                {"заблокируй двери", "включи фары"},
                {"выключи фары", "закрой шторки"},
                {"поставь температуру двадцать градусов", "вентилятор на три"},
                {"включи подогрев сидений", "обдув в ноги"},
                {"закрой окна", "двери"},
                {"открой окна на пятьдесят процентов", "выключи климат"},
                {"подогрев водителя на тройку", "массаж пассажиру"}};
        for (String connector : CONNECTORS)
            for (String[] pair : pairs) phrases.add(pair[0] + " " + connector + " " + pair[1]);
        phrases.add("открой окно слева и поставь климат на двадцать три градуса а заднее окно приоткрыть на пятьдесят процентов");
        phrases.add("открой левое окно нет закрой");
        phrases.add("открой левое окно нет закрой и открой правую дверь");
        phrases.add("открой левое окно и левую дверь потом закрой левое окно");
        return phrases;
    }

    static List<String> phrases() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        List<String> objects = movableObjects();
        List<String> verbs = new ArrayList<>(OPEN);
        verbs.addAll(CLOSE);
        verbs.addAll(STOP);
        for (String verb : verbs) {
            for (String object : objects) candidates.add(verb + " " + object);
            candidates.add(verb + " левое и правое окно");
            candidates.add(verb + " левое окно и " + verb + " правое окно");
            candidates.add("открой окна нет " + verb);
            candidates.add("пожалуйста " + verb + " окна");
            candidates.add(verb + " окна пожалуйста");
            candidates.add("давай " + verb + " окна");
            candidates.add("слушай " + verb + " окна");
            candidates.add(verb + " левую и правую дверь");
            candidates.add(verb + " левую дверь и " + verb + " правую дверь");
            candidates.add("открой двери нет " + verb);
            candidates.add("пожалуйста " + verb + " двери");
        }
        for (String connector : CONNECTORS) {
            for (String object : objects) {
                boolean door = object.contains("двер") || object.matches(".*(ую|юю)( .*)?");
                String counterpart = door ? "правое окно" : "левую дверь";
                candidates.add("открой " + object + " " + connector + " " + counterpart);
                candidates.add("открой " + counterpart + " " + connector + " " + object);
            }
        }
        candidates.addAll(controls());
        candidates.addAll(compositions());
        List<String> phrases = new ArrayList<>();
        for (String candidate : candidates) {
            try {
                VoiceCommands.parseText(candidate);
                phrases.add(VoiceParser.spell(candidate));
            } catch (Exception unsupported) {
                // Candidates are generated broadly; only complete commands teach the recognizer.
            }
        }
        return List.copyOf(phrases);
    }

    /** Spoken negation stays in the recognizer's vocabulary so it is never forced into a command. */
    static List<String> negatives() {
        List<String> negatives = new ArrayList<>();
        for (String verb : List.of("открой", "закрой", "включи", "выключи", "поставь", "опусти", "подними")) {
            negatives.add("не " + verb + " левое окно");
            negatives.add("не надо " + verb + " окна");
        }
        for (String phrase : List.of("не открой левую дверь", "не надо открой двери", "не включай подогрев", "не надо климат",
                "не включай кондиционер", "не трогай окна", "открой левую дверь не надо"))
            negatives.add(phrase);
        return negatives;
    }
}
