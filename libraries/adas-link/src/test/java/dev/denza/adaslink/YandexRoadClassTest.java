package dev.denza.adaslink;

import java.util.LinkedHashMap;
import java.util.Map;

/** Semantic decision table, including categories not yet present in the captured tile corpus. */
public final class YandexRoadClassTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static Map<String,Object> attributes(Object category, Object fc) {
        Map<String,Object> map = new LinkedHashMap<>(); map.put("class", category); map.put("fc", fc); return map;
    }
    public static void main(String[] args) {
        int[] expected = {-1, -1, 7, 7, 7, 8, 8, 9, 9, 9};
        for (int category = 1; category <= 9; category++) for (String suffix : new String[]{"", "_link"}) {
            YandexRoadClass.Decision decision = YandexRoadClass.map(attributes(category + suffix, (long) category));
            if (category == 1) {
                check(decision.roadClass == null, "class 1 does not distinguish motorway from urban expressway");
                check("motorway_or_urban_expressway".equals(decision.rule), "ambiguity has an explicit decision");
            } else {
                check(decision.roadClass == expected[category], "functional mapping " + category + suffix);
                check("functional_approximation".equals(decision.evidence().get("quality")), "approximation is not a decoded factory enum");
                check(decision.roadClass != 0 && decision.roadClass != 6, "no inferred freeway or urban expressway");
            }
        }
        for (String category : new String[]{"10_pedestrian", "7_pedestrian", "construction", "10_bike", "stairs", "future"})
            check(YandexRoadClass.map(attributes(category, 7L)).roadClass == null, "fc alone is not a driving category: " + category);
        check(YandexRoadClass.map(attributes("4", 5L)).roadClass == null, "conflicting class and fc");
        check(YandexRoadClass.map(attributes("4", "4")).roadClass == null, "string fc is not a captured integer");
        check(YandexRoadClass.map(attributes("4", 4.0)).roadClass == null, "floating fc is not silently coerced");
        check(YandexRoadClass.map(attributes("4", null)).roadClass == null, "missing fc remains explicit");
        check(YandexRoadClass.map(attributes(4L, 4L)).roadClass == null, "numeric class is a different source type");
        Map<String,Object> source = attributes("4", 4L);
        YandexRoadClass.map(source);
        check(source.size() == 2 && source.get("class").equals("4"), "literal source values preserved");
        System.out.println("YandexRoadClassTest: " + checks + " checks");
    }
}
