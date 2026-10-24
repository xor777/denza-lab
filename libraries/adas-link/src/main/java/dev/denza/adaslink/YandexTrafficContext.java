package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Captured per-geometry-edge traffic context. No Amap traffic enum or wire-speed conversion. */
public final class YandexTrafficContext {
    public static final String WIRE_DECISION = "preserve_factory_default_unproved_mapping";
    public static final class Segment {
        public final String rawJamType;
        /** Exact finite nonnegative snapshot value; null means unknown, not a measured zero. */
        public final Double rawSpeed;
        public final String speedStatus, jamTypeStatus;
        private Segment(String type, Double speed, String speedStatus, String typeStatus) {
            rawJamType=type;rawSpeed=speed;this.speedStatus=speedStatus;jamTypeStatus=typeStatus;
        }
    }
    public final List<Segment> segments;
    /** Unit declared by the snapshot contract, not independently inferred from a raw number. */
    public final String declaredSpeedUnit;
    public final Boolean conditionsOutdated;
    public final Long snapshotCapturedAtMs;
    public final String unavailableReason;
    private YandexTrafficContext(List<Segment> segments,String unit,Boolean outdated,Long captured,String reason) {
        this.segments=Collections.unmodifiableList(segments);declaredSpeedUnit=unit;
        conditionsOutdated=outdated;snapshotCapturedAtMs=captured;unavailableReason=reason;
    }
    @SuppressWarnings("unchecked") public static YandexTrafficContext fromSnapshot(Map<String,Object> snapshot,int edges) {
        Object units=snapshot.get("units");Object speedUnit=units instanceof Map?((Map<?,?>)units).get("speed"):null;
        String unit=speedUnit instanceof String?(String)speedUnit:null;
        Boolean outdated=snapshot.get("conditionsOutdated") instanceof Boolean?(Boolean)snapshot.get("conditionsOutdated"):null;
        Long captured=null;Object at=snapshot.get("capturedAtMs");
        if(at instanceof Number && Double.isFinite(((Number)at).doubleValue()) && ((Number)at).longValue()>0)
            captured=((Number)at).longValue();
        List<Segment> result=new ArrayList<>();String reason=null;
        if(edges<1 || edges>200000) reason="geometry_edge_budget";
        else if(!YandexRoute.known(snapshot,"jamSegments")) reason="missing_or_unknown_jamSegments";
        else if(!(snapshot.get("jamSegments") instanceof List)) reason="invalid_jamSegments_type";
        else {
            List<?> raw=(List<?>)snapshot.get("jamSegments");
            if(raw.size()!=edges) reason="jamSegments_geometry_alignment_mismatch";
            else for(Object item:raw) {
                Map<?,?> row=item instanceof Map?(Map<?,?>)item:null;
                Object type=row==null?null:row.get("jamType"),speed=row==null?null:row.get("speed");
                Double value=speed instanceof Number?((Number)speed).doubleValue():null;
                String speedStatus=speed==null?"source_null":value==null?"type_error":
                        !Double.isFinite(value)?"invalid_nonfinite":value<0?"invalid_negative":"known";
                if(!"known".equals(speedStatus)) value=null;
                String typeStatus=type==null?"source_null":!(type instanceof String)?"type_error":
                        ((String)type).isEmpty()?"invalid_empty":"reported";
                result.add(new Segment(type instanceof String?(String)type:null,value,speedStatus,typeStatus));
            }
        }
        return new YandexTrafficContext(result,unit,outdated,captured,reason);
    }
    public Map<String,Object> at(String routeId,double scalar,boolean positionOnCurrentRoute) {
        Map<String,Object> out=Json.object();
        out.put("routeId",routeId);out.put("source","yandex_route_snapshot.jamSegments");
        out.put("snapshotContext",true);out.put("sourceAgeKnown",false);
        out.put("snapshotCapturedAtMs",snapshotCapturedAtMs);out.put("conditionsOutdated",conditionsOutdated);
        out.put("declaredSpeedUnit",declaredSpeedUnit);out.put("wireDecision",WIRE_DECISION);
        String reason=unavailableReason;
        if(!positionOnCurrentRoute || !Double.isFinite(scalar) || scalar<0 || scalar>segments.size())
            reason="no_current_route_position";
        Segment segment=null;Integer index=null;
        if(reason==null && !segments.isEmpty()) {
            index=Math.min((int)Math.floor(scalar),segments.size()-1);segment=segments.get(index);
            if(segment.rawJamType==null && segment.rawSpeed==null)reason="unknown_jam_segment";
        }
        out.put("sourceSegmentIndex",index);out.put("rawJamType",segment==null?null:segment.rawJamType);
        out.put("rawSpeed",segment==null?null:segment.rawSpeed);out.put("speedKnown",segment!=null&&segment.rawSpeed!=null);
        out.put("speedStatus",segment==null?"unavailable":segment.speedStatus);
        out.put("jamTypeStatus",segment==null?"unavailable":segment.jamTypeStatus);
        out.put("unavailableReason",reason);
        return out;
    }
}
