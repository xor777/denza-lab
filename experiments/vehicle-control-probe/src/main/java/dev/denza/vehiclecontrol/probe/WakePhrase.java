package dev.denza.vehiclecontrol.probe;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * «Привет, Денза». The name is not in the recognizer's vocabulary, so the narrow grammar spells it
 * phonetically («ден за», «дэн за»). The narrow pass decides; the unrestricted pass only vetoes.
 *
 * The unrestricted model writes the name differently from one utterance to the next («дэн за», «дэнс»,
 * «за», «бензо», «энзо»), so requiring an exact transcript rejected real calls: on the car two of
 * three owner calls were refused that way. Now the unrestricted pass must hear «привет» where the
 * narrow pass heard it, and it vetoes only when it is sure of a word in the name's place that sounds
 * unlike «денза» («Денис», «где заправка»).
 *
 * Words after the name are allowed: «Привет, Денза, открой окно» in one breath is a call followed by
 * a command, which starts at {@link Detection#nameEnd()}.
 */
final class WakePhrase {
    static final String GRAMMAR = "[\"привет ден за\",\"привет дэн за\",\"[unk]\"]";
    static final double NARROW_MINIMUM = 0.75, VETO_CONFIDENCE = 0.9, VETO_DISTANCE = 0.3;
    static final double MAX_HELLO_GAP = 0.9, MIN_LENGTH = 0.45, MAX_LENGTH = 3.0;

    /** accepted: a call; hello: only «привет» was said, the name may follow in the next utterance. */
    record Detection(boolean accepted, boolean hello, String reason, double nameEnd) {
        static Detection no(String reason) { return new Detection(false, false, reason, 0); }
    }

    private record Word(String text, double conf, double start, double end) {
        double middle() { return (start + end) / 2; }
    }

    private static Word word(JSONArray words, int i) {
        JSONObject w = words.optJSONObject(i);
        if (w == null) return null;
        double conf = w.optDouble("conf", Double.NaN), start = w.optDouble("start", Double.NaN), end = w.optDouble("end", Double.NaN);
        if (!Double.isFinite(conf) || !Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start) return null;
        return new Word(w.optString("word").toLowerCase(Locale.ROOT), conf, start, end);
    }

    /** Narrow pass alone: the call at the very start of the utterance, any words after it. */
    static Detection narrow(JSONObject result) {
        JSONArray words = result == null ? null : result.optJSONArray("result");
        if (words == null || words.length() == 0) return Detection.no("слов не разобрано");
        Word hello = word(words, 0);
        if (hello == null) return Detection.no("нет оценок слов");
        if (!hello.text().equals("привет")) return Detection.no("фраза начинается не с «привет»: «" + result.optString("text") + "»");
        if (hello.conf() < NARROW_MINIMUM) return Detection.no(String.format(Locale.ROOT, "«привет» %.2f ниже порога %.2f", hello.conf(), NARROW_MINIMUM));
        if (words.length() == 1) return new Detection(false, true, "только «привет»", 0);
        Word den = word(words, 1), za = words.length() > 2 ? word(words, 2) : null;
        if (den == null || za == null || !(den.text().equals("ден") || den.text().equals("дэн")) || !za.text().equals("за"))
            return Detection.no("после «привет» нет имени: «" + result.optString("text") + "»");
        for (Word w : new Word[]{den, za})
            if (w.conf() < NARROW_MINIMUM)
                return Detection.no(String.format(Locale.ROOT, "слово «%s»: %.2f ниже порога %.2f", w.text(), w.conf(), NARROW_MINIMUM));
        if (den.start() < hello.end() - 0.02 || za.start() < den.end() - 0.02) return Detection.no("слова перекрываются");
        if (den.start() - hello.end() > MAX_HELLO_GAP) return Detection.no("пауза после «привет» длиннее 0,9 с");
        if (za.start() - den.end() > 0.25) return Detection.no("имя разорвано паузой");
        double length = za.end() - hello.start();
        if (length < MIN_LENGTH || length > MAX_LENGTH) return Detection.no("длительность обращения не подходит");
        return new Detection(true, false, "обращение в начале реплики", za.end());
    }

    /** Both passes: the narrow call, «привет» heard by the unrestricted model, no confident other name. */
    static Detection detect(JSONObject narrowResult, JSONObject full) {
        Detection candidate = narrow(narrowResult);
        if (!candidate.accepted()) return candidate;
        JSONArray narrowWords = narrowResult.optJSONArray("result");
        Word hello = word(narrowWords, 0), den = word(narrowWords, 1), za = word(narrowWords, 2);
        JSONArray words = full == null ? null : full.optJSONArray("result");
        if (words == null || words.length() == 0) return Detection.no("полная модель не разобрала слов");
        boolean heardHello = false, confident = false;
        double spanStart = den.start() - 0.05, spanEnd = za.end() + 0.1;
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < words.length(); i++) {
            Word w = word(words, i);
            if (w == null) continue;
            if (!heardHello && w.middle() >= hello.start() - 0.15 && w.middle() <= hello.end() + 0.15 && distance(key(w.text()), key("привет")) <= 0.34) {
                heardHello = true;
                continue; // the greeting itself is never part of the name
            }
            if (w.middle() >= spanStart && w.middle() <= spanEnd) {
                if (name.length() > 0) name.append(' ');
                name.append(w.text());
                confident |= w.conf() >= VETO_CONFIDENCE;
            }
        }
        if (!heardHello) return Detection.no("полная модель не слышит «привет»");
        // The words in the name's place are compared together: «ден за» is the name, «ден» alone is not.
        if (confident && distance(key(name.toString()), key("денза")) > VETO_DISTANCE)
            return Detection.no("полная модель уверенно слышит «" + name + "» на месте имени");
        return candidate;
    }

    // Names kept from the earlier probe API.
    static boolean accepts(JSONObject result) { return narrow(result).accepted(); }
    static boolean matches(JSONObject narrow, JSONObject full) { return detect(narrow, full).accepted(); }

    /** A rough Russian sound key: voicing pairs merged, unstressed vowels folded, soft and hard signs dropped. */
    static String key(String word) {
        StringBuilder key = new StringBuilder();
        for (char c : word.toLowerCase(Locale.ROOT).toCharArray()) {
            char k = switch (c) {
                case 'б' -> 'п'; case 'в' -> 'ф'; case 'г' -> 'к'; case 'д' -> 'т'; case 'ж' -> 'ш'; case 'з' -> 'с';
                case 'ё', 'э', 'и', 'ы' -> 'е'; case 'о', 'я' -> 'а'; case 'ю' -> 'у';
                case 'ъ', 'ь', ' ', '-' -> 0;
                default -> c;
            };
            if (k != 0 && (key.length() == 0 || key.charAt(key.length() - 1) != k)) key.append(k);
        }
        return key.toString();
    }

    /** Levenshtein distance divided by the longer key. */
    static double distance(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()] / (double) Math.max(a.length(), b.length());
    }
}
