package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Yandex point facilities projected into common.path.model.FacilityType (not NaviFacilityType). */
public final class RoadFacilities {
    public static final int MAX_ITEMS = 8192;
    /** Adapter visibility policy, not a recovered AutoSDK display threshold. */
    public static final double LOOK_AHEAD_M = 600;
    private static final String[] FIELDS = {"pedestrianCrossings", "railwayCrossings", "checkpoints", "speedBumps",
            "restrictedEntries", "restrictedTurns", "fordCrossings", "ruggedRoads", "events"};

    public static final class Item {
        public final String field, key, decision;
        public final int index, type;
        public final double scalar;
        /** Exact captured JSON value, also retained for objects without a proven wire equivalent. */
        public final Object raw;
        Item(String field, int index, double scalar, int type, String decision, Object raw) {
            this.field=field; this.index=index; this.scalar=scalar; this.type=type; this.decision=decision; this.raw=raw;
            key=field+":"+scalar+":"+type+":"+Json.write(raw);
        }
        public boolean mapped() { return type>0 && Double.isFinite(scalar); }
    }
    public static final class Source {
        public final List<Item> items;
        public final Map<String,Object> availability;
        public final List<Map<String,Object>> issues;
        public final Map<String,Object> sourceAvailability;
        public final Map<String,Object> sourceIssues;
        public final int rawCount;
        Source(List<Item> items,Map<String,Object> availability,List<Map<String,Object>> issues,int rawCount,
               Map<String,Object> sourceAvailability,Map<String,Object> sourceIssues) {
            this.items=Collections.unmodifiableList(items); this.availability=Collections.unmodifiableMap(availability);
            this.issues=Collections.unmodifiableList(issues); this.rawCount=rawCount;
            this.sourceAvailability=Collections.unmodifiableMap(sourceAvailability);this.sourceIssues=Collections.unmodifiableMap(sourceIssues);
        }
        public Map<String,Object> note() {
            Map<String,Object> out=Json.object(); out.put("rawCount",rawCount);out.put("retainedCount",items.size());
            int mapped=0; for(Item item:items)if(item.mapped())mapped++;
            out.put("mappedCount",mapped);out.put("unmappedCount",items.size()-mapped);
            out.put("availability",availability);out.put("issues",issues);
            out.put("sourceAvailability",sourceAvailability);out.put("sourceIssues",sourceIssues);
            List<Object> decisions=new ArrayList<>();
            Map<String,Object> counts=Json.object();
            for(Item item:items) {
                Object n=counts.get(item.decision);counts.put(item.decision,n==null?1:((Number)n).intValue()+1);
                if(decisions.size()<32) { Map<String,Object> d=Json.object();d.put("field",item.field);d.put("index",item.index);
                    d.put("type",item.type>0?item.type:null);d.put("decision",item.decision);decisions.add(d); }
            }
            out.put("decisionCounts",counts);out.put("decisionExamples",decisions);
            out.put("additionalItemsInRouteSnapshot",Math.max(0,items.size()-decisions.size()));return out;
        }
    }
    public static final class Placed {
        public final Item source;
        public final double metres, lon, lat;
        Placed(Item source,double metres,double lon,double lat) {this.source=source;this.metres=metres;this.lon=lon;this.lat=lat;}
    }
    public static final class View {
        public final Source source;
        public final List<Placed> items;
        View(Source source,List<Placed> items) {this.source=source;this.items=Collections.unmodifiableList(items);}
        /** The wire has one slot. Coincident objects use stable source order; all remain in Source. */
        public Placed next(double passedM,double lookAheadM) {
            if(!Double.isFinite(passedM)||passedM<0||!Double.isFinite(lookAheadM)||lookAheadM<0)return null;
            for(Placed p:items)if(p.metres>=passedM && p.metres-passedM<=lookAheadM)return p;
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Source readSource(Map<String,Object> snapshot,int edges) {
        List<Item> items=new ArrayList<>();Map<String,Object> availability=Json.object();List<Map<String,Object>> issues=new ArrayList<>();
        int count=0;
        for(String field:FIELDS) {
            if(!YandexRoute.known(snapshot,field)) {availability.put(field,"unavailable");continue;}
            Object value=snapshot.get(field);
            if(!(value instanceof List)) {availability.put(field,"malformed");issues.add(issue(field,-1,"expected list"));continue;}
            List<?> rows=(List<?>)value;count+=rows.size();availability.put(field,rows.isEmpty()?"known_empty":"reported");
            for(int index=0;index<rows.size();index++) {
                if(items.size()>=MAX_ITEMS) {issues.add(issue(field,index,"item cap; remaining raw objects retained in route snapshot"));break;}
                Object raw=rows.get(index);double scalar=Double.NaN;int type=0;String decision;
                if(!(raw instanceof Map)) {decision="malformed object retained; no wire output";}
                else {
                    Map<String,Object> row=(Map<String,Object>)raw;
                    try {
                        Object position=row.get(field.equals("events")?"polylinePosition":"position");
                        if(field.equals("fordCrossings")||field.equals("ruggedRoads")) {
                            Object range=position==null?row:position;
                            position=range instanceof Map?((Map<?,?>)range).get("begin"):null;
                        }
                        scalar=position(position,edges);
                    } catch(RuntimeException invalid) {issues.add(issue(field,index,"invalid position; raw object retained"));}
                    switch(field) {
                        case "pedestrianCrossings": type=31;decision="named pedestrian crossing -> Zebra31";break;
                        case "checkpoints": type=91;decision="named Checkpoint -> CheckPoint91; no police inference";break;
                        case "railwayCrossings":
                            Object kind=row.get("type");
                            if("REGULAR".equals(kind)||"UNKNOWN".equals(kind)) {
                                type=8;decision="RailwayCrossing family -> generic Railway8; gate subtype not inferred";
                            } else decision="unrecognised railway subtype retained; mapping requires review";
                            break;
                        case "events":
                            Object tags=row.get("tags");
                            if(tags instanceof List && ((List<?>)tags).contains("SCHOOL")) {type=17;decision="SCHOOL tag -> School17; other tags retained";}
                            else if(tags instanceof List && ((List<?>)tags).contains("DANGER")) {type=30;decision="DANGER tag -> Danger30; other tags retained";}
                            else decision="event retained; no proven road-facility mapping for these tags";
                            break;
                        case "speedBumps": decision="speed bump retained; Amble29 equivalence unproved";break;
                        case "fordCrossings": decision="ford range retained; WaterPavement27 equivalence unproved";break;
                        case "ruggedRoads": decision="rugged range retained; Irregularity28 equivalence unproved";break;
                        default: decision="restriction retained; no proven facility equivalent";
                    }
                }
                if(!Double.isFinite(scalar)) {type=0;decision+="; invalid or missing placement";}
                items.add(new Item(field,index,scalar,type,decision,raw));
            }
        }
        Map<String,Object> sourceAvailability=Json.object(),sourceIssues=Json.object();
        Object sourceStates=snapshot.get("availability"),sourceFailures=snapshot.get("issues");
        if(sourceStates instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)sourceStates).entrySet())
            if(relevant(String.valueOf(e.getKey())))sourceAvailability.put(String.valueOf(e.getKey()),e.getValue());
        if(sourceFailures instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)sourceFailures).entrySet())
            if(relevant(String.valueOf(e.getKey())))sourceIssues.put(String.valueOf(e.getKey()),e.getValue());
        return new Source(items,availability,issues,count,sourceAvailability,sourceIssues);
    }
    private static boolean relevant(String path) {
        for(String field:FIELDS)if(path.equals(field)||path.startsWith(field+"[")||path.startsWith(field+"."))return true;
        return false;
    }
    private static double position(Object raw,int edges) {
        if(!(raw instanceof Map))throw new IllegalArgumentException("position missing");
        Object index=((Map<?,?>)raw).get("segmentIndex");
        if(!(index instanceof Number)||!Double.isFinite(((Number)index).doubleValue())||((Number)index).doubleValue()!=((Number)index).intValue())
            throw new IllegalArgumentException("non-integral segmentIndex");
        return YandexRoute.position(raw,edges);
    }
    private static Map<String,Object> issue(String field,int index,String reason) {
        Map<String,Object> d=Json.object();d.put("field",field);d.put("index",index);d.put("reason",reason);return d;
    }

    private RoadFacilities() {}
}
