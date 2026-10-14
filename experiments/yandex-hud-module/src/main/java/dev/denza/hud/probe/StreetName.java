package dev.denza.hud.probe;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compact route toponyms without a View, Activity or native UI presenter. Main thread only. */
final class StreetName {
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern WORD = Pattern.compile("(?<![\\p{L}\\p{N}_-])\\p{L}+(?![\\p{L}\\p{N}_.-])");
    private static final Map<String, String> TYPES;
    static {
        Map<String, String> types = new HashMap<>();
        String[][] pairs = {
            {"улица", "ул."}, {"проспект", "просп."}, {"переулок", "пер."},
            {"шоссе", "ш."}, {"набережная", "наб."}, {"площадь", "пл."},
            {"бульвар", "б-р"}, {"проезд", "пр-д"},
            {"street", "St"}, {"avenue", "Ave"}, {"boulevard", "Blvd"},
            {"road", "Rd"}, {"lane", "Ln"}, {"highway", "Hwy"}, {"square", "Sq"}
        };
        for (String[] pair : pairs) types.put(pair[0], pair[1]);
        TYPES = Collections.unmodifiableMap(types);
    }

    private String previous, compact = "";

    String compact(String full) {
        if (full == null) full = "";
        if (full.equals(previous)) return compact;
        String clean = SPACES.matcher(full).replaceAll(" ").trim();
        Matcher words = WORD.matcher(clean);
        StringBuffer result = new StringBuffer(clean.length());
        while (words.find()) {
            String abbreviation = TYPES.get(words.group().toLowerCase(Locale.ROOT));
            if (abbreviation != null) words.appendReplacement(result, Matcher.quoteReplacement(abbreviation));
        }
        words.appendTail(result);
        previous = full;
        // Never truncate the proper name or UTF-8 bytes: N9's actual length limit is unknown.
        compact = result.toString();
        return compact;
    }
}
