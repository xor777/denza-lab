package dev.denza.hud.probe;

/** The HUD must get the same compact text without a visible Activity, preserving the actual name. */
public final class StreetNameTest {
    public static void main(String[] args) {
        String[][] cases = {
            {null, ""}, {"", ""}, {" \t\n\u00a0", ""},
            {"Моховая улица", "Моховая ул."}, {"улица 1905 года", "ул. 1905 года"},
            {"проспект Мира", "просп. Мира"}, {"Ракетостроителей проспект", "Ракетостроителей просп."},
            {"Большой переулок", "Большой пер."}, {"Дмитровское шоссе", "Дмитровское ш."},
            {"Кремлёвская набережная", "Кремлёвская наб."}, {"Трубная площадь", "Трубная пл."},
            {"Цветной бульвар", "Цветной б-р"}, {"Садовый проезд", "Садовый пр-д"},
            {"МОХОВАЯ УЛИЦА", "МОХОВАЯ ул."}, {"  Моховая\u00a0 улица\n", "Моховая ул."},
            {"Mokhovaya Street", "Mokhovaya St"}, {"Raketostroiteley Avenue", "Raketostroiteley Ave"},
            {"Sunset Boulevard", "Sunset Blvd"}, {"Main Road", "Main Rd"}, {"Park Lane", "Park Ln"},
            {"Pacific Highway", "Pacific Hwy"}, {"Main Square", "Main Sq"},
            {"Моховая ул.", "Моховая ул."}, {"Mokhovaya St", "Mokhovaya St"},
            {"Streetwise Avenue", "Streetwise Ave"}, {"Переулочный проезд", "Переулочный пр-д"},
            {"М-11 / E105", "М-11 / E105"}, {"Rue de Rivoli", "Rue de Rivoli"},
            {"東京 / Straße des 17. Juni", "東京 / Straße des 17. Juni"},
            {"Очень длинное собственное название без известного типа дороги", "Очень длинное собственное название без известного типа дороги"}
        };
        StreetName formatter = new StreetName();
        for (String[] item : cases) {
            String actual = formatter.compact(item[0]);
            if (!item[1].equals(actual)) throw new AssertionError(item[0] + " -> " + actual);
            if (formatter.compact(item[0]) != actual) throw new AssertionError("Cache misses unchanged input");
            if (!formatter.compact(actual).equals(actual)) throw new AssertionError("Not idempotent: " + actual);
        }
        System.out.println(cases.length + " street-name cases passed (including cache and idempotence)");
    }
}
