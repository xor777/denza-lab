package dev.denza.adasstatus.probe;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Semantic value transitions only; native counters/timestamps never create changes. */
final class StatusTransitions {
    private final Map<String, Map<String, Object>> last = new HashMap<>();
    Map<String, Object> accept(String source, Map<String, Object> event) {
        String name = String.valueOf(event.get("event"));
        String[] keys;
        if (name.equals("pilotStatus")) keys = new String[]{"dnp", "icc", "acc", "takeoverStatus"};
        else if (name.equals("noaMode")) keys = new String[]{"noaMode", "notice"};
        else if (name.equals("pathSelected")) keys = new String[]{"pathIDResp", "statusCode", "errorMsg"};
        else if (name.equals("alarm")) keys = new String[]{"pilotAlarmReason", "alarmStage", "pilotNotice"};
        else return null; // PathMatch payload is retained in the raw ring, not an eligibility change.
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : keys) values.put(key, event.get(key));
        String identity = source + ":" + name;
        Map<String, Object> previous = last.get(identity);
        if (values.equals(previous)) return null;
        last.put(identity, values);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("event", name); change.put("change", previous == null ? "initial_observation" : "value_changed");
        change.put("values", values);
        if (previous != null) change.put("previousValues", previous);
        return change;
    }
}
