package dev.denza.hud.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only snapshot of the pinned Windshield API. API values do not establish server freshness. */
final class TrafficSignalSnapshot {
    static Map<String, Object> read(Reflect api, Object windshield, String routeId) {
        return new Copy(api, routeId).read(windshield);
    }

    private interface Value { Object copy(Object value, String path); }
    private static final class Copy {
        final Reflect api;
        final String routeId;
        final Map<String, Object> availability = map(), issues = map();
        Copy(Reflect api, String routeId) { this.api=api; this.routeId=routeId; }

        Map<String, Object> read(Object windshield) {
            Map<String, Object> out=map();
            out.put("schema", "denza-yandex-traffic-signals-v1"); out.put("routeId",routeId);
            out.put("status","unknown"); out.put("trafficLightsWithSignal",null);
            out.put("sourceTimestamp",null); // This API exposes no per-signal sample timestamp or validity period.
            availability.put("sourceTimestamp","unknown");
            try {
                if (routeId==null || routeId.isEmpty()) throw new IllegalArgumentException("Missing route identity");
                if (windshield==null) throw new IllegalArgumentException("Missing windshield");
                Object valid=api.call(windshield,"isValid");
                if (!(valid instanceof Boolean)) throw new IllegalArgumentException("Invalid windshield validity type");
                out.put("windshieldValid",valid);
                if (!Boolean.TRUE.equals(valid)) throw new IllegalStateException("Invalid windshield");
                Object raw=api.call(windshield,"getTrafficLightsWithSignal");
                if (!(raw instanceof List)) throw new IllegalArgumentException("Missing traffic signal list");
                List<?> source=(List<?>)raw;
                if (source.size()>4096) throw new IllegalArgumentException("Traffic signal list size bound");
                List<Object> lights=new ArrayList<>(source.size());
                for (int i=0;i<source.size();i++) {
                    String path="trafficLightsWithSignal["+i+"]";
                    if (source.get(i)==null) { lights.add(null); unknown(path,"null_item",null); }
                    else lights.add(light(source.get(i),path));
                }
                out.put("trafficLightsWithSignal",lights); out.put("status","known");
                availability.put("trafficLightsWithSignal",issues.isEmpty()?"known":"partial");
            } catch (RuntimeException error) { failure("trafficLightsWithSignal",error); }
            out.put("availability",availability); out.put("issues",issues); out.put("complete",issues.isEmpty());
            return out;
        }

        Map<String, Object> light(Object source,String path) {
            Map<String, Object> out=map();
            field(out,path,"id",source,"getId",(value,name)-> {
                if (!(value instanceof String)) throw new IllegalArgumentException("Expected signal identity");
                return value;
            },true);
            for (String key:new String[]{"signal","arrow","sectionType"})
                field(out,path,key,source,"get"+Character.toUpperCase(key.charAt(0))+key.substring(1),(value,name)-> {
                    if (!(value instanceof Enum)) throw new IllegalArgumentException("Expected enum");
                    return ((Enum<?>)value).name();
                },true);
            field(out,path,"secondsLeft",source,"getSecondsLeft",(value,name)-> {
                if (!(value instanceof Integer)) throw new IllegalArgumentException("Expected integer seconds");
                return value; // Preserve the literal nullable API value, including any sentinel; no countdown extrapolation.
            },true);
            field(out,path,"position",source,"getPosition",this::routePosition,false);
            return out;
        }

        Object routePosition(Object source,String path) {
            Object matched=api.call(source,"onRoute",routeId);
            if (!(matched instanceof Boolean)) throw new IllegalArgumentException("Expected route membership");
            Map<String,Object> out=map(); out.put("onRoute",matched); out.put("polylinePosition",null);
            if (!Boolean.TRUE.equals(matched)) {
                unknown(path+".polylinePosition","not_on_current_route",null);
                return out;
            }
            Object position=api.call(source,"positionOnRoute",routeId);
            if (position==null) throw new IllegalArgumentException("Missing polyline position");
            Object index=api.call(position,"getSegmentIndex"), fraction=api.call(position,"getSegmentPosition");
            if (!(index instanceof Integer) || !(fraction instanceof Double) || ((Integer)index)<0
                    || !Double.isFinite((Double)fraction) || ((Double)fraction)<0 || ((Double)fraction)>1)
                throw new IllegalArgumentException("Invalid polyline position");
            Map<String,Object> polyline=map(); polyline.put("segmentIndex",index); polyline.put("segmentPosition",fraction);
            out.put("polylinePosition",polyline); availability.put(path+".polylinePosition","known");
            return out;
        }

        void field(Map<String,Object> out,String path,String key,Object source,String getter,Value copy,boolean nullable) {
            String name=path+"."+key;
            try {
                Object value=api.call(source,getter);
                if (value==null && !nullable) throw new IllegalArgumentException("Missing required value");
                int before=issues.size();
                out.put(key,value==null?null:copy.copy(value,name));
                availability.put(name,before==issues.size()?"known":"partial");
            } catch(RuntimeException error) { out.put(key,null); failure(name,error); }
        }
        void failure(String path,RuntimeException error) {
            Throwable cause=error;
            while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
            boolean missing=error.getMessage()!=null && error.getMessage().startsWith("Missing method ");
            unknown(path,missing?"missing_getter":"read_or_validation_error",cause.getClass().getName());
        }
        void unknown(String path,String kind,String exception) {
            availability.put(path,"unknown"); Map<String,Object> issue=map(); issue.put("kind",kind);
            if(exception!=null)issue.put("exception",exception); issues.put(path,issue);
        }
    }
    private static Map<String,Object> map() { return new LinkedHashMap<>(); }
}
