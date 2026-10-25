package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.Coordinates;
import dev.denza.adaslink.RouteAttributes;
import dev.denza.adaslink.YandexRoute;
import dev.denza.adaslink.stocknative.NativeModels;
import dev.denza.adaslink.stocknative.NativePath;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** The single Yandex-to-native input policy. It does not publish, schedule, handle ACKs,
 * or use the historical bridge's converter, feed, variants, or expected output bytes.
 * Geometry and indices are an adapter partition, not Amap topology. Internal geometry stays
 * WGS84; the explicit frame policy converts only native output coordinate boundaries.
 */
public final class YandexNativeAdapter {
    public enum MissingBooleans { REJECT, EXPLICIT_FALSE }
    public enum RoadClassPolicy { UNKNOWN, EXPERIMENTAL_FUNCTIONAL }
    public enum HistorySpeedPolicy { OBSERVED_HISTORY_ONLY, EXPERIMENTAL_YANDEX_SECTION_FREE_FLOW_PROXY }
    public static final class Policy {
        public static final Policy STRICT=new Policy(MissingBooleans.REJECT);
        /** Owner-selected experimental assumption, not a claim about eligibility or road facts. */
        public static final Policy EXPLICIT_FALSE=new Policy(MissingBooleans.EXPLICIT_FALSE);
        /** Test-ground approximation of source functional groups; not an exact native class. */
        public static final Policy TEST_ROAD_DATA=new Policy(MissingBooleans.EXPLICIT_FALSE,RoadClassPolicy.EXPERIMENTAL_FUNCTIONAL);
        public final MissingBooleans missingBooleans;
        public final RoadClassPolicy roadClass;
        public final Coordinates.Frame frame;
        public final HistorySpeedPolicy historySpeed;
        public final PublicRoadEvidence publicRoads;
        public Policy(MissingBooleans value){this(value,RoadClassPolicy.UNKNOWN);}
        public Policy(MissingBooleans value,RoadClassPolicy roadClass){this(value,roadClass,Coordinates.Frame.WGS84);}
        public Policy(MissingBooleans value,RoadClassPolicy roadClass,Coordinates.Frame frame){this(value,roadClass,frame,HistorySpeedPolicy.OBSERVED_HISTORY_ONLY);}
        private Policy(MissingBooleans value,RoadClassPolicy roadClass,Coordinates.Frame frame,HistorySpeedPolicy historySpeed){this(value,roadClass,frame,historySpeed,PublicRoadEvidence.EMPTY);}
        private Policy(MissingBooleans value,RoadClassPolicy roadClass,Coordinates.Frame frame,HistorySpeedPolicy historySpeed,PublicRoadEvidence publicRoads){if(value==null||roadClass==null||frame==null||historySpeed==null||publicRoads==null)throw new IllegalArgumentException("adapter policy");missingBooleans=value;this.roadClass=roadClass;this.frame=frame;this.historySpeed=historySpeed;this.publicRoads=publicRoads;}
        public Policy withFrame(Coordinates.Frame value){return new Policy(missingBooleans,roadClass,value,historySpeed,publicRoads);}
        public Policy withHistorySpeed(HistorySpeedPolicy value){return new Policy(missingBooleans,roadClass,frame,value,publicRoads);}
        public Policy withPublicRoadEvidence(PublicRoadEvidence value){return new Policy(missingBooleans,roadClass,frame,historySpeed,value);}
    }
    /** Current source observations; NaN remains absent and is never made stationary/arrived. */
    public static final class Guidance {
        public double scalar=Double.NaN,timeToFinishS=Double.NaN;
        /** Actual corresponding source only. Yandex free-flow/traffic speed is not historical speed. */
        public Integer historicalSpeedKmh;
        /** Current position freshness/association required for the experimental section proxy. */
        public long fixAgeMs=-1;
        public boolean onRoute;
    }
    public static final class Location {
        public double scalar=Double.NaN,matchedLon=Double.NaN,matchedLat=Double.NaN;
        public double rawLon=Double.NaN,rawLat=Double.NaN,speedMps=Double.NaN,headingDeg=Double.NaN,altitudeM=Double.NaN;
        public boolean simulated;
        /** False omits old-link attributes. The original sender retains its last progress
         * envelope until replacement; this flag does not clear the factory route/indices. */
        public boolean onRoute=true;
    }
    public static final class Position {
        public final int segmentIndex,linkIndex,pointIndex,linkRemainM,segmentRemainM,routeRemainM;
        public final double bearingDeg,scalar;
        private Position(int s,int l,int p,int lr,int sr,int rr,double bearing,double at){
            segmentIndex=s;linkIndex=l;pointIndex=p;linkRemainM=lr;segmentRemainM=sr;routeRemainM=rr;bearingDeg=bearing;scalar=at;
        }
    }

    public static AdaptedRoute prepare(YandexRoute source,long pathId,long requestId,int routeType){
        return prepare(source,pathId,requestId,routeType,source==null?null:source.additionalAttributes,Policy.STRICT);
    }
    public static AdaptedRoute prepare(YandexRoute source,long pathId,long requestId,int routeType,RouteAttributes attributes){
        return prepare(source,pathId,requestId,routeType,attributes,Policy.STRICT);
    }
    public static AdaptedRoute prepare(YandexRoute source,long pathId,long requestId,int routeType,RouteAttributes attributes,Policy policy){
        return new AdaptedRoute(source,pathId,requestId,routeType,attributes,policy);
    }

    public static final class AdaptedRoute implements NativePath {
        private final String routeId;
        private final long pathId,requestId;
        private final int routeType;
        private final Coordinates.Frame frame;
        private final boolean geometrySupported;
        private final double[] lon,lat,cumulative,lights;
        private final List<NativeSegment> segments;
        private final List<FieldDecision> decisions;
        private final List<String> blockers;
        private final java.util.Map<String,Object> attributeEvidence;
        private final boolean emptyUnavailableLightProjection;
        private final HistorySpeedPolicy historySpeedPolicy;
        private final double[] sectionFreeFlowKmh;
        private final boolean conditionsOutdated;
        private AdaptedRoute(YandexRoute source,long id,long request,int type,RouteAttributes attributes,Policy policy){
            if(source==null||policy==null)throw new IllegalArgumentException("route and policy required");
            if(id<=0)throw new IllegalArgumentException("positive synthetic path identity required");
            routeId=source.routeId;pathId=id;requestId=request;routeType=type;frame=policy.frame;
            historySpeedPolicy=policy.historySpeed;
            conditionsOutdated=Boolean.TRUE.equals(source.conditionsProvenance().get("conditionsOutdated"));
            emptyUnavailableLightProjection=policy.missingBooleans==MissingBooleans.EXPLICIT_FALSE;
            lon=source.lon.clone();lat=source.lat.clone();cumulative=new double[lon.length];
            if(lon.length>100000)throw new IllegalArgumentException("route point limit 100000");
            boolean frameSupported=true;
            for(int i=0;i<lon.length;i++){
                requireCoordinate(lon[i],lat[i]);
                if(!supportsCoordinate(lon[i],lat[i]))frameSupported=false;
                if(i>0)cumulative[i]=cumulative[i-1]+Coordinates.distance(lon[i-1],lat[i-1],lon[i],lat[i]);
            }
            geometrySupported=frameSupported;
            if(!(cumulative[cumulative.length-1]>0))throw new IllegalArgumentException("zero length route");
            lights=source.trafficLights==null?null:source.trafficLights.clone();
            if(lights!=null){for(double at:lights)requireScalar(at);java.util.Arrays.sort(lights);}
            if(attributes!=null&&!attributes.matches(source))throw new IllegalArgumentException("attributes belong to another route snapshot");
            java.util.Map<String,Object> evidence=new java.util.LinkedHashMap<>();if(attributes!=null)evidence.putAll(attributes.summary());
            List<FieldDecision> ds=new ArrayList<>();List<String> bs=new ArrayList<>();
            if(!geometrySupported)bs.add("coordinate frame "+frame+": route outside supported envelope");
            ds.add(d("pathID/requestID","driver route epoch","synthetic transport identity; never native topology","new identity for replacement route",FieldDecision.Kind.SYNTHETIC_ID));
            ds.add(d("points","YandexRoute.lon/lat WGS84","frame="+frame+"; one Coordinates.toSdk conversion at native boundary, then ×3600000 rounded; internal metric/scalars/IDs/bearings remain WGS84","route outside selected frame envelope blocks admission; no fallback frame",FieldDecision.Kind.DERIVED));
            ds.add(d("segment/link/point indices","Yandex sections and interval cuts","one coherent synthetic partition for route, location and guidance","invalid scalar rejects current callback",FieldDecision.Kind.DERIVED));
            ds.add(d("linkID","route epoch + segment/link + WGS84 endpoints SHA-256","positive signed-64 synthetic identity shared by static and dynamic inputs","never copied from Yandex package-local/native IDs",FieldDecision.Kind.SYNTHETIC_ID));
            ds.add(d("roadClass","whole-interval typed renderer class/fc; no legacy converted roadClass","policy="+policy.roadClass+"; experimental functional projection is approximate, not native topology or NOA eligibility","RoadClassNULL=-1 for missing/conflicting source or UNKNOWN policy",FieldDecision.Kind.SDK_NULL));
            ds.add(d("formway","Yandex section ENTER_ROUNDABOUT","FormwayRoundCircle=4 only on the explicitly entered roundabout section","FormayNULL=-1 otherwise; renderer layers do not prove formway",FieldDecision.Kind.SDK_NULL));
            ds.add(d("linkType","Yandex route tunnel/ferry ranges","Tunnel=2 / Ferry=1 for reported containing range","LinkTypeNULL=-1 elsewhere; absence of these two structures does not prove common/bridge/elevated",FieldDecision.Kind.SDK_NULL));
            ds.add(d("ownership","owner-confirmed exact traversed WGS84 source-edge catalog; SHA256="+policy.publicRoads.sha256()+"; edges="+policy.publicRoads.edgeCount(),
                "OwnershipPublicRoad=0 only when every intersecting original source edge is catalogued; owner evidence, not a Yandex public/private fact; no proximity or on-route fill",
                "AUTO_UNKNOWN_ERROR=MIN_VALUE for incomplete/no coverage and off-route; original P maps it to -1",policy.publicRoads.edgeCount()==0?FieldDecision.Kind.SDK_NULL:FieldDecision.Kind.FACT));
            ds.add(d("laneNum","no continuous lane-count source","point lane signs cannot provide a continuous link lane count","LinkItem.laneNum model default 0, literal l.b forwarding; not a lane count fact",FieldDecision.Kind.UNKNOWN_MODEL_DEFAULT));
            ds.add(d("direction","Yandex oneway source retained but native field semantics unproved","directed-edge presence and old 0/1 conversion do not establish SDK roadDirection","LinkItem.direction model default 0; this default does not prove two-way travel",FieldDecision.Kind.UNKNOWN_MODEL_DEFAULT));
            ds.add(d("adminCode/URID","no Yandex-to-Amap administrative namespace mapping","Amap identifiers; Yandex region/package/ISO IDs are not interchangeable","LinkItem.adminCode and region/ODD model defaults 0; not an administrative fact",FieldDecision.Kind.UNKNOWN_MODEL_DEFAULT));
            for(String field:new String[]{"hasMultiOut","hasParallelRoad","hasMixFork"}){
                boolean explicit=policy.missingBooleans==MissingBooleans.EXPLICIT_FALSE;
                ds.add(d(field,"no corresponding Yandex observation","native road boolean; v0 maps parallel/mix false to 2, true to 1",explicit?"owner-selected false; UNKNOWN, not observed absence":"no SDK unknown; route admission blocked",explicit?FieldDecision.Kind.UNKNOWN_EXPLICIT_FALSE:FieldDecision.Kind.UNAVAILABLE));
                if(!explicit)bs.add(field+": source unavailable and native boolean has no unknown value");
            }
            ds.add(d("hasTrafficLight/trafficLightNum/routeRemainLightCount","YandexRoute.trafficLights","known positions only; static geometry gives no live signal phase",lights==null&&emptyUnavailableLightProjection?"unavailable source projected to empty SDK list under explicit test policy; not observed absence":"unreported list stays unavailable; explicit-false policy needed for link flag",lights==null?FieldDecision.Kind.UNAVAILABLE:FieldDecision.Kind.FACT));
            if(lights==null&&policy.missingBooleans==MissingBooleans.REJECT)bs.add("hasTrafficLight: trafficLights source unavailable");
            ds.add(d("viaRoadInfo/ToPOI","no compatible native via-road/POI namespace source","waypoints do not define native lane/speed aggregate records or AVP POI IDs","empty via-road list and null POI; no fabricated AVP IDs",FieldDecision.Kind.UNAVAILABLE));
            ds.add(d("travelTime","section weight.timeWithTraffic else weight.time seconds","rounded nonnegative section duration","missing duration remains SDK output-model default 0, not zero-time observation",FieldDecision.Kind.UNKNOWN_MODEL_DEFAULT));
            ds.add(d("mainAction/assistantAction/crossManeuverID","Yandex section-entry Action + original SDK enum names","next section action owns prior section end; explicit matching enum names only","AutoSDK MainActionNULL/AssiActionNULL/ManeuverIconNull = 0; unmapped action diagnosed",FieldDecision.Kind.ENUM_MAPPING));
            ds.add(d("LocInfo.speed/gpsPos/matchInfo","current guided fix plus independent raw GNSS","m/s × 3.6 km/h; raw remains separate; road attributes from exact same path partition","missing speed/raw/matched position/heading rejects current matched callback",FieldDecision.Kind.DERIVED));
            ds.add(d("NaviInfo.routeRemain/linkRemainDist/NaviInfoData","current scalar + route metric + Yandex timeToFinishS","all distances use same partition; NaviInfoFlag=0 selects single current panel","missing scalar/ETA rejects callback; never NaN→0",FieldDecision.Kind.DERIVED));
            boolean freeFlowProxy=historySpeedPolicy==HistorySpeedPolicy.EXPERIMENTAL_YANDEX_SECTION_FREE_FLOW_PROXY;
            ds.add(d("NaviInfo.curLinkSpeed",freeFlowProxy?"Yandex section.metadata.weight.distance.value metres / weight.time.value seconds × 3.6":"no historical-speed observation",
                "policy="+historySpeedPolicy+"; original v0 forwards as historySpeed; section-average static routing estimate rounded to nearest integer km/h is an experimental proxy, not observed historical or per-edge speed; fix age checks current association, not underlying speed measurement age; never timeWithTraffic or legal limit",
                "0 for absent/negative/nonfinite/out-of-int-range value, outdated conditions, stale/off-route fix or route end; observed historical input takes precedence",
                freeFlowProxy?FieldDecision.Kind.DERIVED:FieldDecision.Kind.UNKNOWN_MODEL_DEFAULT));
            List<YandexRoute.Section> sections=checkedSections(source);
            sectionFreeFlowKmh=new double[sections.size()];
            for(int i=0;i<sections.size();i++)sectionFreeFlowKmh[i]=sections.get(i).freeFlowKmh;
            // A polyline vertex is geometry, not a road-property boundary. Keep those points
            // inside links instead of multiplying native link records by the point count.
            TreeSet<Double> cuts=new TreeSet<>();cuts.add(0d);cuts.add((double)lon.length-1);
            if(attributes!=null)for(double cut:attributes.cuts()){requireScalar(cut);cuts.add(cut);}
            addRanges(cuts,source.tunnels);addRanges(cuts,source.ferries);
            if(lights!=null)for(double at:lights)cuts.add(at);
            for(YandexRoute.Section s:sections){cuts.add(s.begin);cuts.add(s.end);}
            List<NativeSegment> built=new ArrayList<>();
            final int diagnosticLimit=128;int retainedClassDecisions=0,omittedClassDecisions=0,totalLinks=0;
            java.util.Map<String,Integer> projectedCounts=new java.util.LinkedHashMap<>();
            java.util.Map<String,Integer> rawQualityCounts=new java.util.LinkedHashMap<>();
            for(int si=0;si<sections.size();si++){
                YandexRoute.Section section=sections.get(si);
                String endAction=si+1<sections.size()?sections.get(si+1).action:"FINISH";
                NativeEnums.Action action=NativeEnums.action(endAction);
                if(endAction!=null&&(endAction.startsWith("FORK_")||endAction.startsWith("EXIT_")))
                    ds.add(d("segment["+si+"].action",endAction,"directional branch projection only: AutoSDK AssiActionEntryLeftBranch=8 / RightBranch=7; no inferred merge, freeway/ramp or turn angle","MainActionNULL=0 and ManeuverIconNull=0; native icon semantics unavailable",FieldDecision.Kind.ENUM_MAPPING));
                if(!action.mapped)ds.add(d("segment["+si+"].action",String.valueOf(endAction),"unmapped instruction retained as SDK NULL","0, no guessed turn projection",FieldDecision.Kind.UNAVAILABLE));
                List<NativeLink> links=new ArrayList<>();
                List<Double> part=new ArrayList<>(cuts.subSet(section.begin,true,section.end,true));
                for(int li=0;li+1<part.size();li++){
                    double begin=part.get(li),end=part.get(li+1),middle=(begin+end)/2;
                    // The existing attribute DTO already contains the old bridge's converted
                    // numeric class/direction. Its acquisition evidence and cuts are reusable;
                    // these numbers are not independent proof of an AutoSDK enum mapping.
                    RouteAttributes.RendererClass raw=attributes==null?RouteAttributes.RendererClass.unknown("source_unavailable"):attributes.rendererClassBetween(begin,end);
                    if(raw==null)raw=RouteAttributes.RendererClass.unknown("source_unavailable");
                    int roadClass=projectRoadClass(raw,policy.roadClass);
                    totalLinks++;String nativeClassKey=Integer.toString(roadClass);projectedCounts.put(nativeClassKey,projectedCounts.getOrDefault(nativeClassKey,0)+1);
                    rawQualityCounts.put(raw.quality,rawQualityCounts.getOrDefault(raw.quality,0)+1);
                    Integer functional=raw.functionalClass();
                    String candidates=functional!=null&&functional==1?"[0,6]":roadClass<0?"[]":"["+roadClass+"]";
                    if(retainedClassDecisions<diagnosticLimit){retainedClassDecisions++;
                        ds.add(d("segment["+si+"].link["+li+"].roadClass","interval=["+begin+","+end+"]; class="+raw.rawClass+" type="+raw.classType+"; fc="+raw.rawFc+" type="+raw.fcType+"; quality="+raw.quality,
                            "policy="+policy.roadClass+"; chosen="+roadClass+"; nativeCandidates="+candidates+(functional!=null&&functional==1?"; canonical CitySpeedway=6 represents ambiguous freeway/urban expressway group; exact 0 versus 6 unknown":"; coarse functional-group approximation, not an exact native road-class observation"),
                            "unknown/type conflict/gap/non-driving/truncation or UNKNOWN policy -> -1; no NOA-success inference",roadClass<0?FieldDecision.Kind.SDK_NULL:FieldDecision.Kind.ENUM_MAPPING));
                    }else omittedClassDecisions++;
                    int linkType=contains(source.tunnels,middle)?NativeEnums.LINK_TUNNEL:contains(source.ferries,middle)?NativeEnums.LINK_FERRY:
                            NativeEnums.LINK_NULL;
                    int formway="ENTER_ROUNDABOUT".equals(section.action)?NativeEnums.FORMWAY_ROUNDABOUT:NativeEnums.FORMWAY_NULL;
                    double[] first=point(begin),last=point(end);
                    int length=nonnegativeInt(Math.round(metres(end)-metres(begin)));
                    int lightCount=0;if(lights!=null)for(double at:lights)if(at>begin&&at<=end)lightCount++;
                    NativeEnums.Action linkAction=li+2==part.size()?action:NativeEnums.action(null);
                    int ownership=policy.publicRoads.covers(lon,lat,begin,end)?0:NativeEnums.OWNERSHIP_ERROR;
                    links.add(new NativeLink(begin,end,length,syntheticId(routeId,id,si,li,first,last),roadClass,linkType,formway,ownership,section.toponym,lightCount,linkAction,geometryBetween(begin,end),frame,geometrySupported));
                }
                built.add(new NativeSegment(si,section.begin,section.end,section.timeS,action,links));
            }
            long totalLength=0;for(NativeSegment s:built)totalLength+=s.length;nonnegativeInt(totalLength);
            java.util.Map<String,Object> projection=new java.util.LinkedHashMap<>();
            projection.put("policy",policy.roadClass.name());projection.put("links",totalLinks);
            projection.put("nativeClassCounts",Collections.unmodifiableMap(projectedCounts));
            projection.put("rawQualityCounts",Collections.unmodifiableMap(rawQualityCounts));
            projection.put("diagnosticLimit",diagnosticLimit);projection.put("retainedDecisions",retainedClassDecisions);projection.put("omittedDecisions",omittedClassDecisions);
            evidence.put("rendererClassProjection",Collections.unmodifiableMap(projection));evidence.put("coordinateFrame",frame.name());evidence.put("publicRoadEvidence",policy.publicRoads.provenance());attributeEvidence=Collections.unmodifiableMap(evidence);
            segments=Collections.unmodifiableList(built);decisions=Collections.unmodifiableList(ds);blockers=Collections.unmodifiableList(bs);
        }
        public String routeId(){return routeId;}
        public Coordinates.Frame frame(){return frame;}
        /** Source coordinates in, selected native frame out; unsupported fixes stay absent. */
        public double[] sdkCoordinate(double lon,double lat){return YandexNativeAdapter.sdkCoordinate(lon,lat,frame);}
        public boolean supportsCoordinate(double lon,double lat){return YandexNativeAdapter.supportsCoordinate(lon,lat,frame);}
        public boolean admitted(){return blockers.isEmpty();}
        public List<String> blockers(){return blockers;}
        public List<FieldDecision> fieldDecisions(){return decisions;}
        public java.util.Map<String,Object> attributeEvidence(){return attributeEvidence;}
        public NativeModels.RouteCarResultData result(){
            final AdaptedRoute self=this;
            return new NativeModels.RouteCarResultData(){
                public List<NativePath> getPathInfos(){ArrayList<NativePath> out=new ArrayList<>();out.add(self);return out;}
                public int getFocusIndex(){return 0;}public long getRequestId(){return requestId;}
                public int getRouteType(){return routeType;}public NativeModels.Poi getToPOI(){return null;}
            };
        }
        public Position position(double scalar){
            requireScalar(scalar);
            int si=segments.size()-1;
            for(int i=0;i<segments.size();i++)if(scalar<segments.get(i).end){si=i;break;}
            NativeSegment s=segments.get(si);int li=s.links.size()-1;
            for(int i=0;i<s.links.size();i++)if(scalar<s.links.get(i).end){li=i;break;}
            NativeLink l=s.links.get(li);double denominator=metres(l.end)-metres(l.begin);
            double fraction=denominator==0?1:(metres(scalar)-metres(l.begin))/denominator;
            int lr=nonnegativeInt(Math.round(l.length*(1-Math.max(0,Math.min(1,fraction)))));
            long sr=lr;for(int i=li+1;i<s.links.size();i++)sr+=s.links.get(i).length;
            long rr=sr;for(int i=si+1;i<segments.size();i++)rr+=segments.get(i).length;
            int sourceEdge=Math.min((int)scalar,lon.length-2);
            double[] a=point(Math.max(l.begin,sourceEdge)),b=point(Math.min(l.end,sourceEdge+1));
            int pointIndex=0;for(int i=1;i<l.scalars.length;i++){if(l.scalars[i]>scalar)break;pointIndex=i;}
            return new Position(si,li,pointIndex,lr,nonnegativeInt(sr),nonnegativeInt(rr),bearing(a[0],a[1],b[0],b[1]),scalar);
        }
        public double[] coordinate(double scalar){requireScalar(scalar);return point(scalar);}
        private int freeFlowProxy(Guidance input,Position p){
            if(historySpeedPolicy!=HistorySpeedPolicy.EXPERIMENTAL_YANDEX_SECTION_FREE_FLOW_PROXY||conditionsOutdated
                    ||!input.onRoute||input.fixAgeMs<0||input.fixAgeMs>1500||input.scalar>=lon.length-1)return 0;
            double speed=sectionFreeFlowKmh[p.segmentIndex];
            return finiteNonnegative(speed)&&speed<=Integer.MAX_VALUE?(int)Math.round(speed):0;
        }
        public int remainingLights(double scalar){requireScalar(scalar);int n=0;if(lights!=null)for(double at:lights)if(at>scalar)n++;return n;}
        public long getPathID(){return pathId;}public long getSegmentCount(){return segments.size();}
        public Segment getSegmentInfo(long index){return index>=0&&index<segments.size()?segments.get((int)index):null;}
        public long getGroupSegmentCount(){return 0;}public Group getGroupSegment(long index){return null;}
        public List<Point> getAllTrafficLights(){if(!geometrySupported)return Collections.emptyList();if(lights==null)return emptyUnavailableLightProjection?Collections.<Point>emptyList():null;List<Point> out=new ArrayList<>();for(double at:lights){double[] p=point(at),nativePoint=sdkCoordinate(p[0],p[1]);out.add(new Point(nativePoint[0],nativePoint[1]));}return out;}
        public List<ViaRoad> getPathViaRoadInfo(){return Collections.emptyList();}public boolean isValid(){return admitted();}
        private void requireScalar(double value){if(!Double.isFinite(value)||value<0||value>lon.length-1)throw new IllegalArgumentException("scalar outside route: "+value);}
        private double metres(double scalar){int i=Math.min((int)scalar,lon.length-2);double f=scalar-i;return cumulative[i]+(cumulative[i+1]-cumulative[i])*f;}
        private double[] point(double scalar){int i=Math.min((int)scalar,lon.length-2);double f=scalar-i;return new double[]{lon[i]+(lon[i+1]-lon[i])*f,lat[i]+(lat[i+1]-lat[i])*f};}
        private double[][] geometryBetween(double begin,double end){List<double[]> points=new ArrayList<>();double[] p=point(begin);points.add(new double[]{begin,p[0],p[1]});for(int i=(int)Math.floor(begin)+1;i<end;i++){p=point(i);points.add(new double[]{i,p[0],p[1]});}p=point(end);points.add(new double[]{end,p[0],p[1]});return points.toArray(new double[points.size()][]);}
        private List<YandexRoute.Section> checkedSections(YandexRoute source){
            if(source.sections.isEmpty())return Collections.singletonList(new YandexRoute.Section(0,lon.length-1,null,null,Double.NaN));
            double through=0;for(YandexRoute.Section s:source.sections){requireScalar(s.begin);requireScalar(s.end);if(s.begin!=through||s.end<=s.begin)throw new IllegalArgumentException("sections must cover route without gaps/overlap");through=s.end;}
            if(through!=lon.length-1)throw new IllegalArgumentException("sections do not cover route end");
            return new ArrayList<>(source.sections);
        }
        private void addRanges(TreeSet<Double> cuts,List<double[]> ranges){if(ranges==null)return;for(double[] r:ranges){if(r==null||r.length!=2)throw new IllegalArgumentException("range");requireScalar(r[0]);requireScalar(r[1]);if(r[1]<r[0])throw new IllegalArgumentException("reversed range");cuts.add(r[0]);cuts.add(r[1]);}}
    }

    /** Missing scalar/ETA returns no observation, not a fabricated zero-distance or zero-time fix. */
    public static NativeModels.NaviInfo navi(AdaptedRoute route,Guidance input){
        if(route==null||input==null||!route.admitted()||!Double.isFinite(input.scalar)||!finiteNonnegative(input.timeToFinishS))return null;
        Position p;try{p=route.position(input.scalar);}catch(IllegalArgumentException missing){return null;}
        if(input.timeToFinishS>Integer.MAX_VALUE)return null;
        NativeModels.NaviInfo out=new NativeModels.NaviInfo();out.pathID=route.pathId;
        out.routeRemain=new NativeModels.TimeAndDist(p.routeRemainM,(int)Math.round(input.timeToFinishS));
        out.curSegIdx=p.segmentIndex;out.curLinkIdx=p.linkIndex;out.curPointIdx=p.pointIndex;out.linkRemainDist=p.linkRemainM;
        out.routeRemainLightCount=route.remainingLights(input.scalar);out.crossManeuverID=route.segments.get(p.segmentIndex).action.icon;
        out.curLinkSpeed=input.historicalSpeedKmh==null?route.freeFlowProxy(input,p):input.historicalSpeedKmh;
        if(out.curLinkSpeed<0)throw new IllegalArgumentException("historical speed must be nonnegative");
        out.NaviInfoFlag=0;out.NaviInfoData=new ArrayList<>();NativeModels.NaviInfoPanel panel=new NativeModels.NaviInfoPanel();
        panel.segmentRemain=new NativeModels.TimeAndDist(p.segmentRemainM,0);out.NaviInfoData.add(panel);return out;
    }

    /** Raw GNSS and matched Yandex fix are independent required observations. Vehicle speed
     * belongs to NativeEnvironment.realSpeed and never falls back to this GPS speed. */
    public static NativeModels.LocInfo location(AdaptedRoute route,Location input){
        if(route==null||input==null||!route.admitted())return null;
        NativeModels.LocInfo out=new NativeModels.LocInfo();out.isSimulate=input.simulated?1:0;
        if(input.simulated)return out; // Original P returns before reading any other field.
        if(!coordinateValid(input.matchedLon,input.matchedLat)||!coordinateValid(input.rawLon,input.rawLat)||!finiteNonnegative(input.speedMps)||!Double.isFinite(input.headingDeg))return null;
        double[] raw=route.sdkCoordinate(input.rawLon,input.rawLat),matched=route.sdkCoordinate(input.matchedLon,input.matchedLat);
        if(raw==null||matched==null)return null;
        Position p=null;if(input.onRoute)try{p=route.position(input.scalar);}catch(IllegalArgumentException missing){return null;}
        double kmh=input.speedMps*3.6;if(kmh>Integer.MAX_VALUE)return null;
        out.speed=(float)kmh;out.alt=Double.isFinite(input.altitudeM)?(float)input.altitudeM:0;
        out.sourType=0; // LocInfo constructor default; P reads this only for logging.
        out.gpsPos=new NativeModels.Coord3DDouble(raw[0],raw[1]);
        NativeModels.LocMatchInfo match=new NativeModels.LocMatchInfo();match.stPos=new NativeModels.Coord3DDouble(matched[0],matched[1]);
        match.course=(float)((input.headingDeg%360+360)%360);
        if(input.onRoute){
            NativeLink link=route.segments.get(p.segmentIndex).links.get(p.linkIndex);
            match.roadCourse=(float)p.bearingDeg;match.roadLen=link.length;
            match.roadClass=link.roadClass;match.formway=link.formway;match.linkType=link.type;match.ownership=link.ownership;match.linkId=link.id.longValue();
        }else{
            // A current FINE source coordinate is usable even while Yandex reports deviation.
            // There is no source for its road identity. The unchanged sender still uses its
            // last native path/progress envelope and the reroute callback's offCourse state.
            match.roadClass=NativeEnums.ROAD_NULL;match.formway=NativeEnums.FORMWAY_NULL;
            match.linkType=NativeEnums.LINK_NULL;match.ownership=NativeEnums.OWNERSHIP_ERROR;match.linkId=-1;
        }
        out.matchInfo=new ArrayList<>();out.matchInfo.add(match);return out;
    }

    private static final class NativeSegment implements NativePath.Segment {
        final int index;final double begin,end;final long length,time;final NativeEnums.Action action;final List<NativeLink> links;
        NativeSegment(int i,double b,double e,double seconds,NativeEnums.Action a,List<NativeLink> l){index=i;begin=b;end=e;action=a;links=Collections.unmodifiableList(l);long sum=0;for(NativeLink x:l)sum+=x.length;length=sum;if(Double.isFinite(seconds)&&seconds>Integer.MAX_VALUE)throw new IllegalArgumentException("section time exceeds native int");time=finiteNonnegative(seconds)?Math.round(seconds):0;}
        public int getSegmentIndex(){return index;}public long getLinkCount(){return links.size();}
        public NativePath.Link getLinkInfo(long i){return i>=0&&i<links.size()?links.get((int)i):null;}
        public long getLength(){return length;}public long getTravelTime(){return time;}
        public int getMainAction(){return action.main;}public int getAssistantAction(){return action.assistant;}
        public long getTrafficLightNum(){long n=0;for(NativeLink l:links)n+=l.lightCount;return n;}
    }
    private static final class NativeLink implements NativePath.Link {
        final double begin,end;final int length,roadClass,formway,type,lanes,direction,ownership;final BigInteger id;
        final String name;final int lightCount;final NativeEnums.Action action;final int[] lons,lats;final double[] scalars;
        NativeLink(double b,double e,int len,BigInteger identity,int roadClass,int t,int form,int ownership,String n,int tl,NativeEnums.Action act,double[][] geometry,Coordinates.Frame frame,boolean supported){
            begin=b;end=e;length=len;id=identity;this.roadClass=roadClass;formway=form;type=t;
            lanes=0;direction=0;this.ownership=ownership;
            name=n==null?"":n;lightCount=tl;action=act;lons=new int[supported?geometry.length:0];lats=new int[lons.length];scalars=new double[geometry.length];
            for(int i=0;i<geometry.length;i++){scalars[i]=geometry[i][0];if(supported){double[] p=sdkCoordinate(geometry[i][1],geometry[i][2],frame);lons[i]=units(p[0]);lats[i]=units(p[1]);}}
        }
        public BigInteger get64TopoID(){return id;}public int getRoadClass(){return roadClass;}public String getRoadName(){return name;}
        public int getLength(){return length;}public int getLinkType(){return type;}public int getFormway(){return formway;}
        public boolean hasTrafficLight(){return lightCount>0;}public int getLaneNum(){return lanes;}public boolean hasMultiOut(){return false;}
        public int getMainAction(){return action.main;}public int getAssistantAction(){return action.assistant;}
        public long getAdcode(){return 0;}public int getOwnership(){return ownership;}public boolean hasParallelRoad(){return false;}
        public int getRoadDirection(){return direction;}public boolean hasMixFork(){return false;}public int getURID(){return 0;}
        public List<NativePath.IntPoint> getPoints(){List<NativePath.IntPoint> out=new ArrayList<>();for(int i=0;i<lons.length;i++)out.add(new NativePath.IntPoint(lons[i],lats[i]));return out;}
    }
    private static FieldDecision d(String f,String s,String m,String a,FieldDecision.Kind k){return new FieldDecision(f,s,m,a,k);}
    public static boolean supportsCoordinate(double lon,double lat,Coordinates.Frame frame){
        return frame!=null&&coordinateValid(lon,lat)&&(frame==Coordinates.Frame.WGS84||lon>=35&&lon<=40&&lat>=54&&lat<=58);
    }
    /** The formula remains in Coordinates; product admission prevents it throwing on unavailable inputs. */
    public static double[] sdkCoordinate(double lon,double lat,Coordinates.Frame frame){
        return supportsCoordinate(lon,lat,frame)?Coordinates.toSdk(lon,lat,frame):null;
    }
    /** Independent official functional grouping -> named AutoSDK classes. Category 1 remains
     * ambiguous between Freeway=0 and CitySpeedway=6; the test policy chooses 6 explicitly. */
    private static int projectRoadClass(RouteAttributes.RendererClass raw,RoadClassPolicy policy){
        if(policy!=RoadClassPolicy.EXPERIMENTAL_FUNCTIONAL)return NativeEnums.ROAD_NULL;
        Integer category=raw.functionalClass();if(category==null)return NativeEnums.ROAD_NULL;
        return category==1?NativeEnums.ROAD_CITY_SPEEDWAY:category<=4?NativeEnums.ROAD_MAIN
                :category<=6?NativeEnums.ROAD_SECONDARY:NativeEnums.ROAD_COMMON;
    }
    private static boolean contains(List<double[]> ranges,double at){if(ranges!=null)for(double[] r:ranges)if(at>=r[0]&&at<r[1])return true;return false;}
    private static boolean finiteNonnegative(double v){return Double.isFinite(v)&&v>=0;}
    private static boolean coordinateValid(double lon,double lat){return Double.isFinite(lon)&&Double.isFinite(lat)&&lon>=-180&&lon<=180&&lat>=-90&&lat<=90;}
    private static void requireCoordinate(double lon,double lat){if(!coordinateValid(lon,lat))throw new IllegalArgumentException("coordinate");}
    private static int units(double v){long value=Math.round(v*3600000.0);if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("coordinate grid overflow");return (int)value;}
    private static int nonnegativeInt(long value){if(value<0||value>Integer.MAX_VALUE)throw new IllegalArgumentException("distance exceeds native int");return (int)value;}
    private static double bearing(double x1,double y1,double x2,double y2){double a=Math.toRadians(y1),b=Math.toRadians(y2),d=Math.toRadians(x2-x1);return (Math.toDegrees(Math.atan2(Math.sin(d)*Math.cos(b),Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(d)))+360)%360;}
    private static BigInteger syntheticId(String route,long path,int segment,int link,double[] a,double[] b){
        try{String key=route+"\n"+path+"\n"+segment+"\n"+link+"\n"+Double.toHexString(a[0])+","+Double.toHexString(a[1])+"\n"+Double.toHexString(b[0])+","+Double.toHexString(b[1]);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));byte[] first=new byte[8];System.arraycopy(digest,0,first,0,8);first[0]&=0x7f;BigInteger out=new BigInteger(1,first);return out.signum()==0?BigInteger.ONE:out;
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private YandexNativeAdapter(){}
}
