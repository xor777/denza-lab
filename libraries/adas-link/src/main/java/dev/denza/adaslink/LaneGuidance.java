package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Junction signs only; no inference of continuous road lanes. Factory LaneAction/f0.g/i. */
public final class LaneGuidance {
    public static final int MAX_SIGNS=4096, MAX_LANES=64, MAX_AHEAD=6;
    public static final class Source {
        public final List<Sign> signs;
        public final List<Map<String,Object>> diagnostics;
        public final boolean complete;
        public final int rawSignCount;
        Source(List<Sign> signs,List<Map<String,Object>> diagnostics,boolean complete,int rawSignCount) {
            this.signs=Collections.unmodifiableList(signs);this.diagnostics=Collections.unmodifiableList(diagnostics);
            this.complete=complete;this.rawSignCount=rawSignCount;
        }
    }
    public static final class Sign {
        public final double scalar;
        public final String rejectedReason;
        /** Yandex annotation preference; no equivalent factory wire field. */
        public final Boolean annotated;
        final byte[] back, front, backType, frontType, extend, recommend;
        public final List<Map<String,Object>> decisions;
        Sign(double scalar, byte[] back, byte[] front, byte[] backType, byte[] frontType, List<Map<String,Object>> decisions, Boolean annotated, String rejectedReason) {
            this.annotated=annotated; this.scalar=scalar; this.back=back; this.front=front; this.backType=backType; this.frontType=frontType;
            this.extend=new byte[back==null?0:back.length]; this.recommend=new byte[back==null?0:back.length];
            java.util.Arrays.fill(this.extend,(byte)255); java.util.Arrays.fill(this.recommend,(byte)255);
            this.decisions=Collections.unmodifiableList(decisions); this.rejectedReason=rejectedReason;
        }
        public boolean supported() { return rejectedReason==null; }
    }
    public static final class Placed {
        public final Sign sign;
        public final double metres;
        public final int segmentIndex, linkIndex;
        Placed(Sign sign, double metres, int segmentIndex, int linkIndex) {
            this.sign=sign; this.metres=metres; this.segmentIndex=segmentIndex; this.linkIndex=linkIndex;
        }
        public byte[] back() { return sign.back.clone(); }
        public byte[] front() { return sign.front.clone(); }
        public byte[] backType() { return sign.backType.clone(); }
        public byte[] frontType() { return sign.frontType.clone(); }
        public byte[] extend() { return sign.extend.clone(); }
        public byte[] recommend() { return sign.recommend.clone(); }
    }
    public final List<Placed> signs;
    public final boolean known;
    public final int rawSignCount;
    public final List<Map<String,Object>> diagnostics;
    LaneGuidance(Source source, RouteConverter.Converted c) {
        known=source!=null && source.complete; rawSignCount=source==null?-1:source.rawSignCount;
        diagnostics=source==null?Collections.singletonList(issue(-1,"source unavailable",null)):source.diagnostics;
        List<Placed> placed=new ArrayList<>();
        if(source!=null)for(Sign s:source.signs) {
            double at=c.metres(s.scalar); int owner=-1;
            // A sign at an exact link end belongs to that approach link, not the following one.
            for(int i=0;i<c.linkEndScalar.length;i++)
                if(s.scalar>c.linkBeginScalar[i] && s.scalar<=c.linkEndScalar[i]) { owner=i; break; }
            if(owner<0 && s.scalar==0)owner=0;
            if(owner>=0)placed.add(new Placed(s,at,c.linkSegment[owner],c.linkInSegment[owner]));
        }
        placed.sort((a,b)->Double.compare(a.metres,b.metres)); signs=Collections.unmodifiableList(placed);
    }
    /** Nearest actual sign, including an unsupported barrier; never skips to a later intersection. */
    public Placed next(double passedM) {
        if(!known || !Double.isFinite(passedM))return null;
        for(Placed s:signs)if(s.metres>=passedM && s.metres-passedM<=3000)return s;
        return null;
    }
    public List<Placed> ahead(double passedM) {
        List<Placed> out=new ArrayList<>();
        if(!Double.isFinite(passedM))return out;
        for(Placed s:signs)if(s.sign.supported() && s.metres>=passedM && s.metres-passedM<=3000) {
            out.add(s); if(out.size()==MAX_AHEAD)break;
        }
        return out;
    }
    public String aheadJson(double passedM,long pathId,long timestamp) {
        List<Object> out=new ArrayList<>();
        for(Placed s:ahead(passedM)) {
            // QueryAppointLanesInfoBean fields, ART/Gson alphabetical order; requestId is transient.
            Map<String,Object> b=Json.object(); b.put("backLane",numbers(s.sign.back)); b.put("backLaneType",numbers(s.sign.backType));
            b.put("extendLane",numbers(s.sign.extend)); b.put("frontLane",numbers(s.sign.front)); b.put("frontLaneType",numbers(s.sign.frontType));
            b.put("linkIndex",s.linkIndex); b.put("pathId",pathId); b.put("recommendLane",numbers(s.sign.recommend));
            b.put("segmentIndex",s.segmentIndex); b.put("timestamp",timestamp);out.add(b);
        }
        return Json.write(out);
    }
    private static List<Integer> numbers(byte[] a) { List<Integer> out=new ArrayList<>();for(byte b:a)out.add(b&255);return out; }

    static List<Sign> readSigns(Map<String,Object> snapshot,int edges) { return readSource(snapshot,edges).signs; }
    private static Map<String,Object> issue(int index,String code,Object detail) {
        Map<String,Object> out=Json.object();out.put("index",index);out.put("code",code);
        String text=String.valueOf(detail);out.put("detail",detail instanceof Number || detail instanceof Boolean || detail==null?detail:text.substring(0,Math.min(text.length(),256)));
        return Collections.unmodifiableMap(out);
    }
    @SuppressWarnings("unchecked")
    static Source readSource(Map<String,Object> snapshot,int edges) {
        List<Sign> out=new ArrayList<>();List<Map<String,Object>> diagnostics=new ArrayList<>();
        if(!YandexRoute.known(snapshot,"laneSigns")) {
            diagnostics.add(issue(-1,"laneSigns unavailable",snapshot.get("availability")));
            return new Source(out,diagnostics,false,-1);
        }
        if(!(snapshot.get("laneSigns") instanceof List)) {
            diagnostics.add(issue(-1,"laneSigns not a list",String.valueOf(snapshot.get("laneSigns"))));
            return new Source(out,diagnostics,false,-1);
        }
        List<Object> raw=(List<Object>)snapshot.get("laneSigns"); boolean complete=raw.size()<=MAX_SIGNS;
        if(!complete)diagnostics.add(issue(-1,"sign cap exceeded; prefix retained",raw.size()));
        for(int index=0;index<Math.min(raw.size(),MAX_SIGNS);index++) {
            Object o=raw.get(index);
            if(!(o instanceof Map)) {complete=false;diagnostics.add(issue(index,"sign not an object",String.valueOf(o)));continue;}
            Map<String,Object> s=(Map<String,Object>)o; double scalar;
            try { scalar=YandexRoute.position(s.get("position"),edges); }
            catch(RuntimeException invalid) {complete=false;diagnostics.add(issue(index,"invalid position; unplaced sign retained in source snapshot",String.valueOf(s.get("position"))));continue;}
            Object ll=s.get("lanes"); String reason=null; byte[] back=null,front=null,backType=null,frontType=null;
            List<Map<String,Object>> decisions=new ArrayList<>();
            if(!(ll instanceof List) || ((List<?>)ll).isEmpty() || ((List<?>)ll).size()>MAX_LANES)reason="lane count unavailable/outside1..64";
            else {
                List<?> lanes=(List<?>)ll;back=new byte[lanes.size()];front=new byte[lanes.size()];backType=new byte[lanes.size()];frontType=new byte[lanes.size()];
                for(int i=0;i<lanes.size();i++) {
                    Map<String,Object> lane=lanes.get(i) instanceof Map ? (Map<String,Object>)lanes.get(i) : Collections.emptyMap();
                    Map<String,Object> decision=Json.object();List<String> losses=new ArrayList<>();
                    String kind=String.valueOf(lane.get("laneKind"));int category=category(kind);
                    if("TRAM_LANE".equals(kind)||"TAXI_LANE".equals(kind)||"BIKE_LANE".equals(kind))losses.add("special subtype projected to Other category; no access inference");
                    else if(category==255)losses.add("unknown category retained as NULL255; directions independent");
                    Object dd=lane.get("directions"); List<?> rawDirections=dd instanceof List?(List<?>)dd:Collections.emptyList();
                    List<?> directions=rawDirections.subList(0,Math.min(rawDirections.size(),14));
                    int mask=0;boolean unknown=directions.isEmpty() || rawDirections.size()>14;
                    for(Object d:directions) {
                        int bit=bit(d);if(bit==0)unknown=true;mask|=bit;
                        String loss=directionLoss(d);if(loss!=null)losses.add(loss);
                    }
                    int action=unknown?-1:action(mask);
                    if(action<0)losses.add(unknown?"incomplete source direction set; known subset retained in diagnostic, composite not asserted":"factory has no union for projected mask="+mask+"; background NULL255, explicit highlight independent");
                    back[i]=(byte)(action<0?255:action);Object highlight=lane.get("highlightedDirection");
                    int foreground=highlight==null?255:(directions.contains(highlight)&&bit(highlight)!=0?action(bit(highlight)):255);
                    if(highlight!=null && foreground==255)losses.add("highlight unavailable/inconsistent; foreground NULL255 only for this lane");
                    front[i]=(byte)foreground;backType[i]=(byte)category;frontType[i]=(byte)(foreground==255?255:category);
                    decision.put("lane",i);decision.put("sourceKind",lane.get("laneKind"));decision.put("sourceDirections",new ArrayList<Object>(directions));
                    decision.put("sourceDirectionCount",dd instanceof List?rawDirections.size():-1);decision.put("directionsTruncated",rawDirections.size()>14);
                    decision.put("sourceDirectionsRawType",dd==null?"null":dd.getClass().getSimpleName());
                    if(!(dd instanceof List)) {String rawText=String.valueOf(dd);decision.put("sourceDirectionsRaw",rawText.substring(0,Math.min(rawText.length(),256)));}
                    decision.put("knownDirectionMask",mask);decision.put("knownSubsetFactoryAction",action(mask)<0?255:action(mask));
                    decision.put("backgroundDecision",unknown?"incomplete source set; preserve known subset separately":action<0?"no matching factory union":"factory union after declared projection");
                    decision.put("sourceHighlight",highlight);decision.put("background",back[i]&255);decision.put("foreground",front[i]&255);
                    decision.put("backCategory",category);decision.put("frontCategory",frontType[i]&255);decision.put("losses",losses);
                    decisions.add(Collections.unmodifiableMap(decision));
                }
            }
            if(reason!=null)diagnostics.add(issue(index,reason,ll instanceof List?((List<?>)ll).size():String.valueOf(ll)));
            out.add(new Sign(scalar,back,front,backType,frontType,decisions,s.get("annotated") instanceof Boolean?(Boolean)s.get("annotated"):null,reason));
        }
        return new Source(out,diagnostics,complete,raw.size());
    }
    private static int bit(Object d) {
        if("STRAIGHT_AHEAD".equals(d)||"LEFT_SHIFT".equals(d)||"RIGHT_SHIFT".equals(d))return 1;
        if("LEFT45".equals(d)||"LEFT90".equals(d)||"LEFT135".equals(d)||"LEFT_FROM_RIGHT".equals(d))return 2;
        if("RIGHT45".equals(d)||"RIGHT90".equals(d)||"RIGHT135".equals(d)||"RIGHT_FROM_LEFT".equals(d))return 4;
        if("LEFT180".equals(d))return 8;
        if("RIGHT180".equals(d))return 16;
        return 0;
    }
    private static int category(String kind) {
        if("PLAIN_LANE".equals(kind))return 0;
        if("BUS_LANE".equals(kind))return 1;
        if("TRAM_LANE".equals(kind)||"TAXI_LANE".equals(kind)||"BIKE_LANE".equals(kind))return 2;
        return 255;
    }
    private static String directionLoss(Object direction) {
        String d=String.valueOf(direction);
        if(d.equals("LEFT45")||d.equals("LEFT135")||d.equals("RIGHT45")||d.equals("RIGHT135"))return d+": angle collapsed to generic left/right";
        if(d.equals("LEFT_FROM_RIGHT")||d.equals("RIGHT_FROM_LEFT"))return d+": generic turn retained; approach/crossover shape lost (no proved factory13/14 equivalence)";
        if(d.equals("LEFT_SHIFT")||d.equals("RIGHT_SHIFT"))return d+": within-lane continuation projected to Ahead0; lateral shape lost, not a lane-change command";
        if(d.equals("UNKNOWN_DIRECTION")||bit(direction)==0)return d+": no source direction contract";
        return null;
    }
    // Named factory LaneAction combinations after the explicit coarse projection above.
    private static int action(int mask) {
        switch(mask) {
            case 1:return 0; case 2:return 1; case 3:return 2; case 4:return 3; case 5:return 4;
            case 8:return 5; case 6:return 6; case 7:return 7; case 16:return 8; case 9:return 9;
            case 17:return 10; case 10:return 11; case 20:return 12; case 11:return 16;
            case 12:return 17; case 14:return 18; case 13:return 19; case 18:return 20;
            default:return -1;
        }
    }
}
