package dev.denza.adaslink.yandexnative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, arm-scoped adapter experiments. Never a factory sender variant. */
public final class AdapterExperiments {
    public static final String SCHEMA="denza-adas-adapter-experiments-v3";
    public static final AdapterExperiments BASELINE=new AdapterExperiments(false,false,false,false,false);
    public static final AdapterExperiments CANDIDATE=new AdapterExperiments(true,false,true,true,true);
    public final boolean lightWithin500,speedFromFixes,coherentProgress,mergeShortLinks;
    /** Fresh valid CAN is primary; otherwise retain original SDK speed without derivation. */
    public final boolean speedFromVehicle;

    /** Source compatibility for the four-flag policy: the CAN speed policy remains disabled. */
    public AdapterExperiments(boolean lightWithin500,boolean speedFromFixes,
                              boolean coherentProgress,boolean mergeShortLinks){
        this(lightWithin500,speedFromFixes,coherentProgress,mergeShortLinks,false);
    }

    public AdapterExperiments(boolean lightWithin500,boolean speedFromFixes,
                              boolean coherentProgress,boolean mergeShortLinks,boolean speedFromVehicle){
        this.speedFromVehicle=speedFromVehicle;
        this.lightWithin500=lightWithin500;this.speedFromFixes=speedFromFixes;
        this.coherentProgress=coherentProgress;this.mergeShortLinks=mergeShortLinks;
    }

    public Map<String,Object> toMap(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("schema",SCHEMA);out.put("lightWithin500",lightWithin500);
        out.put("speedFromFixes",speedFromFixes);out.put("coherentProgress",coherentProgress);
        out.put("mergeShortLinks",mergeShortLinks);out.put("speedFromVehicle",speedFromVehicle);return Collections.unmodifiableMap(out);
    }

    /** Every flag must be explicit; unknown keys and coercions are rejected. */
    public static AdapterExperiments read(Object value){
        if(!(value instanceof Map))throw new IllegalArgumentException("adapter experiments object required");
        Map<?,?> m=(Map<?,?>)value;
        if(m.size()!=6||!SCHEMA.equals(m.get("schema")))throw new IllegalArgumentException("adapter experiments schema");
        return new AdapterExperiments(flag(m,"lightWithin500"),flag(m,"speedFromFixes"),
                flag(m,"coherentProgress"),flag(m,"mergeShortLinks"),flag(m,"speedFromVehicle"));
    }
    private static boolean flag(Map<?,?> m,String key){
        Object v=m.get(key);if(!(v instanceof Boolean))throw new IllegalArgumentException("adapter flag "+key);
        return (Boolean)v;
    }
}
