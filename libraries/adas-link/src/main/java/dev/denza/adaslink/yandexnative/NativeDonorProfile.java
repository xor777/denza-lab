package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.RouteAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Declared adapter experiment inputs, never changes to the literal factory sender.
 * One profile supplies adapter, product environment and audit values with their sources.
 */
public final class NativeDonorProfile {
    public static final String HIGHWAY_SCHEME="HIGHWAY_SCHEME", FUNCTIONAL_CLASS="FUNCTIONAL_CLASS", UNKNOWN_FALLBACK="UNKNOWN_FALLBACK";
    private static final String STOCK="factory 2026-10-23 drive-02/drive.log; stock_holds.py; stock-semantics.json";
    private static final String BEIJING="factory Beijing package; 15 adas-bench/beijing/run-*/stock-trace.json runs; median TrafficInfo historySpeed";
    private static final String RECIPE="owner DONOR-BY-ROAD-20261026.md; typed Yandex functional-group projection, experimental native mapping";
    private static final String CN="historical original Beijing survey-01-p006 donor";
    /** All selected numeric profile inputs are declared here. No source road facts are invented. */
    public static final NativeDonorProfile STOCK_LIKE_BY_ROAD=new NativeDonorProfile(
        "stock-like-by-road",STOCK,990001,990001,372,1,0,1,1,0,0,true,false,false,
        new ClassRule[]{new ClassRule(HIGHWAY_SCHEME,"schemeId == HIGHWAY",null,null,6,RECIPE),
            new ClassRule(FUNCTIONAL_CLASS,"typed consistent fc",1,1,6,RECIPE),
            new ClassRule(FUNCTIONAL_CLASS,"typed consistent fc",2,4,7,RECIPE),
            new ClassRule(FUNCTIONAL_CLASS,"typed consistent fc",5,6,8,RECIPE),
            new ClassRule(FUNCTIONAL_CLASS,"typed consistent fc",7,9,9,RECIPE),
            new ClassRule(UNKNOWN_FALLBACK,"fc absent, invalid, conflicting or unavailable",null,null,9,RECIPE)},
        new SpeedRule[]{new SpeedRule(6,85,STOCK+"; HNOA Kievskoye highway"),new SpeedRule(7,47,BEIJING),
            new SpeedRule(8,25,BEIJING),new SpeedRule(9,25,STOCK+"; CNOA settlement")});
    /** Retained only for historical adapter policy tests. */
    public static final NativeDonorProfile COMPATIBILITY_CN=new NativeDonorProfile(
        "compatibility-cn",CN,110000,110000,1,1,0,1,1,0,0,true,false,false,
        new ClassRule[]{new ClassRule("FIXED_CN","every link",null,null,7,CN)},new SpeedRule[]{new SpeedRule(7,25,CN)});
    public final String id,source;
    public final int regionCode,adminCode,urid,countryCode,speedLimitKmh,formway,direction,laneNum,ownership;
    public final boolean hasMultiOut,hasParallelRoad,hasMixFork;
    private final ClassRule[] classRules;
    private final SpeedRule[] speedRules;
    private NativeDonorProfile(String id,String source,int region,int admin,int urid,int country,int limit,
            int form,int direction,int lanes,int ownership,boolean multi,boolean parallel,boolean mix,ClassRule[] classes,SpeedRule[] speeds){
        this.id=id;this.source=source;regionCode=region;adminCode=admin;this.urid=urid;countryCode=country;speedLimitKmh=limit;
        formway=form;this.direction=direction;laneNum=lanes;this.ownership=ownership;hasMultiOut=multi;hasParallelRoad=parallel;hasMixFork=mix;
        classRules=classes.clone();speedRules=speeds.clone();
    }
    public static final class ClassDecision {
        public final int roadClass;
        public final String rule,source;
        private ClassDecision(ClassRule row){roadClass=row.roadClass;rule=row.rule;source=row.source;}
    }
    public ClassDecision roadClass(String schemeId,RouteAttributes.RendererClass raw){
        Integer fc=raw==null?null:raw.functionalClass();
        for(ClassRule row:classRules){
            if(HIGHWAY_SCHEME.equals(row.rule)){if(!"HIGHWAY".equals(schemeId))continue;}
            else if(FUNCTIONAL_CLASS.equals(row.rule)){if(fc==null||fc<row.minFc||fc>row.maxFc)continue;}
            return new ClassDecision(row);
        }
        throw new AssertionError("profile has no fallback");
    }
    public int historySpeedKmh(int roadClass){for(SpeedRule row:speedRules)if(row.roadClass==roadClass)return row.speed;return 0;}
    public Map<String,Object> evidence(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",id);out.put("source",source);
        put(out,"region",regionCode);put(out,"adminCode",adminCode);put(out,"urid",urid);put(out,"country",countryCode);
        put(out,"speedLimit",speedLimitKmh);put(out,"formway",formway);put(out,"direction",direction);put(out,"laneNum",laneNum);put(out,"ownership",ownership);
        out.put("hasMultiOut",hasMultiOut);out.put("hasParallelRoad",hasParallelRoad);out.put("hasMixFork",hasMixFork);
        Map<String,Object> sources=new LinkedHashMap<>();
        for(String field:new String[]{"region","adminCode","country","speedLimit","ownership"})sources.put(field,source);
        sources.put("urid",id.equals("stock-like-by-road")?"factory global.db 990001 -> 372; retained vehicle ODD response includes 372":source);
        for(String field:new String[]{"formway","direction","laneNum","hasMultiOut","hasParallelRoad","hasMixFork"})sources.put(field,CN+"; owner retains these donor values");
        out.put("sources",Collections.unmodifiableMap(sources));
        List<Object> road=new ArrayList<>();for(ClassRule row:classRules){
            Map<String,Object> item=new LinkedHashMap<>();item.put("rule",row.rule);item.put("condition",row.condition);item.put("minFc",row.minFc);item.put("maxFc",row.maxFc);
            put(item,"roadClass",row.roadClass);item.put("source",row.source);road.add(Collections.unmodifiableMap(item));
        }
        out.put("roadClassRules",Collections.unmodifiableList(road));
        List<Object> speeds=new ArrayList<>();for(SpeedRule row:speedRules){
            Map<String,Object> item=new LinkedHashMap<>();put(item,"roadClass",row.roadClass);put(item,"historySpeed",row.speed);item.put("source",row.source);speeds.add(Collections.unmodifiableMap(item));
        }
        out.put("historySpeedByRoadClass",Collections.unmodifiableList(speeds));
        if(this==COMPATIBILITY_CN){put(out,"roadclass",classRules[0].roadClass);put(out,"historySpeed",speedRules[0].speed);}
        out.put("geometry","real Yandex route; selected coordinate frame");return Collections.unmodifiableMap(out);
    }
    private static void put(Map<String,Object> map,String key,int value){map.put(key,(long)value);}
    private static final class ClassRule {
        final String rule,condition,source;final Integer minFc,maxFc;final int roadClass;
        ClassRule(String rule,String condition,Integer min,Integer max,int roadClass,String source){this.rule=rule;this.condition=condition;minFc=min;maxFc=max;this.roadClass=roadClass;this.source=source;}
    }
    private static final class SpeedRule {
        final int roadClass,speed;final String source;
        SpeedRule(int roadClass,int speed,String source){this.roadClass=roadClass;this.speed=speed;this.source=source;}
    }
}
