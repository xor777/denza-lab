package dev.denza.adaslink.stocknative;

import dev.denza.adaslink.Json;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static dev.denza.adaslink.stocknative.NativeModels.*;

/** Host driver. ORIGINAL outputs are comparison references only, never sender inputs.
 * A Java worker is bound to each original ART thread. Workers yield at actual
 * getter/clock initiation and completion, processing entries, queues and egress;
 * the original global ledger orders these yields, including overlapping reads.
 */
public final class NativePhase2Replay implements StockRuntime, NativeEnvironment, StockTransport {
    static final String E0="k.h.a0.c.c.a.e0", G="k.h.e0.c.g";
    final List<Map<String,Object>> rows;
    final Writer writer;
    final NativeTypedValues values;
    final StockSender sender;
    final Map<String,Worker> workers=new LinkedHashMap<>();
    final Map<String,Map<String,Object>> calls=new HashMap<>();
    final Set<String> ownedCalls=new HashSet<>();
    final Set<String> completeInputIds=new HashSet<>();
    final Map<String,Object> ownedStateBindings=new HashMap<>();
    final IdentityHashMap<Object,String> ownedStateReverse=new IdentityHashMap<>();
    final Map<String,Post> pending=new LinkedHashMap<>();
    final Map<String,Read> reads=new HashMap<>();
    final Map<String,Object> boundOriginal=new HashMap<>();
    final IdentityHashMap<Object,String> portIds=new IdentityHashMap<>();
    final Map<String,Object> reverseBindings=new HashMap<>();
    final ThreadLocal<Worker> current=new ThreadLocal<>();
    final Object lock=new Object();
    long identity; int cursor; boolean stopped; int gaps;
    String mainThreadId;
    boolean hasTransportReads,hasServiceReads,hasTunnelWrites;
    boolean comparingContents, mutationEvidenceAbsent;
    List<?> retainedLightbar;
    final Map<String,Object[]> processingArguments=new HashMap<>();
    final Map<String,Object[]> rootArguments=new HashMap<>();
    static final class Failure extends Error {Failure(String s){super(s);}}
    static final class Op {
        final String kind,method; final Object[] args; final Object receiver; Object result; boolean done;
        Op(String kind,String method,Object receiver,Object...args){this.kind=kind;this.method=method;this.receiver=receiver;this.args=args;}
    }
    final class Worker {
        final String id; final ExecutorService executor; Op op; boolean busy; Throwable failure;
        Object callbackResult;boolean callbackHasResult;String processingRoot;final Deque<ProcessingFrame> processingFrames=new ArrayDeque<>();
        Worker(String id){this.id=id;executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"original-thread-"+id);t.setDaemon(true);return t;});}
        void submit(Runnable action){synchronized(lock){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(busy&&op==null&&failure==null){long left=deadline-System.nanoTime();if(left<=0)break;try{TimeUnit.NANOSECONDS.timedWait(lock,left);}catch(InterruptedException e){throw new Failure("interrupted awaiting worker completion");}}if(busy)throw new Failure("overlapping root work on original thread "+id);busy=true;}executor.execute(()->{current.set(this);StockRuntime previousTrace=NativeStateTrace.attach(NativePhase2Replay.this);try{action.run();}catch(Throwable e){synchronized(lock){failure=e;}}finally{synchronized(lock){busy=false;lock.notifyAll();}NativeStateTrace.attach(previousTrace);current.remove();}});}
    }
    static final class ProcessingFrame {final String method;final boolean root;ProcessingFrame(String method,boolean root){this.method=method;this.root=root;}}
    static final class Post {
        final Map<String,Object> source;final Queue queue;final Object runnable,token;final Runnable action; final long due;
        Post(Map<String,Object>s,Queue q,Object r,Object t,Runnable a,long due){source=s;queue=q;runnable=r;token=t;action=a;this.due=due;}
    }
    static final class Read {
        final Op request;final Worker worker;final String root;final boolean diagnostic; boolean emitted;
        Read(Op o,Worker w,String root,boolean d){request=o;worker=w;this.root=root;diagnostic=d;}
    }
    NativePhase2Replay(List<Map<String,Object>> rows,Writer writer){this.rows=rows;this.writer=writer;for(Map<String,Object> r:rows){String observed=methodBase(str(r.get("method"))==null?"":str(r.get("method")));if(observed.equals("com.autosdk.bussiness.pilot.someip.SomeIpServer.sendSomeIpEvent"))hasTransportReads=true;if(observed.equals("com.autosdk.bussiness.pilot.someip.SomeIpServer.startSomeIpService"))hasServiceReads=true;if(observed.equals("com.autosdk.bussiness.location.instrument.TunnelDetector.setLeaveTunnelDist"))hasTunnelWrites=true;if(r.get("thread") instanceof Map&&"main".equals(map(r.get("thread")).get("name"))){String id=thread(r);if(mainThreadId!=null&&!mainThreadId.equals(id))throw new Failure("main ART thread identity changed");mainThreadId=id;}}values=new NativeTypedValues(this::reference);sender=new StockSender(this,this,this);}
    static Map<String,Object> obj(Object...parts){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<parts.length;i+=2)m.put((String)parts[i],parts[i+1]);return m;}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){if(!(o instanceof Map))throw new Failure("expected object, got "+o);return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") static List<Object> list(Object o){if(!(o instanceof List))throw new Failure("expected list");return (List<Object>)o;}
    static String str(Object o){return o==null?null:String.valueOf(o);}
    static String methodBase(String method){int i=method.indexOf('(');return i<0?method:method.substring(0,i);}
    static String shortMethod(String method){String base=methodBase(method);return base.substring(base.lastIndexOf('.')+1);}
    static String thread(Map<String,Object> r){return str(map(r.get("thread")).get("id"));}
    Worker worker(Map<String,Object>r){String t=thread(r);Worker w=workers.get(t);if(w==null){w=new Worker(t);workers.put(t,w);}return w;}
    synchronized void emit(Map<String,Object> row){try{writer.write(Json.write(row));writer.write('\n');writer.flush();}catch(IOException e){throw new UncheckedIOException(e);}}
    void gap(Map<String,Object>r,String reason){gaps++;emit(obj("kind","coverage_gap","source_seq",r.get("seq"),"reason",reason,"original_kind",r.get("kind"),"method",r.get("method")));}
    void receipt(Map<String,Object>r,String operation){emit(obj("kind","consumption_receipt","source_seq",r.get("seq"),"operation",operation,"method",r.get("method")));}
    void observation(Map<String,Object>r,String reason){emit(obj("kind","environment_observation","source_seq",r.get("seq"),"method",r.get("method"),"reason",reason));}
    Object await(Op op){Worker w=current.get();if(w==null)throw new Failure("sender operation outside original worker: "+op.kind);synchronized(lock){if(w.op!=null)throw new Failure("nested unmatched operation");w.op=op;lock.notifyAll();while(!op.done&&!stopped)try{lock.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("worker interrupted");}if(stopped&&!op.done)throw new Failure("ledger stopped before "+op.kind+" "+op.method);if(op.result instanceof Failure)throw (Failure)op.result;return op.result;}}
    Op take(Map<String,Object>r,String kind){Worker w=worker(r);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);synchronized(lock){while(w.busy&&w.failure==null&&!stopped){if(w.op!=null){if(w.op.kind.equals("environment_write")){Op write=w.op;gap(r,"original environment-write seam not captured: "+write.method+"; computed value "+Arrays.toString(write.args));finish(w,write,null);}else break;}long remaining=deadline-System.nanoTime();if(remaining<=0)break;try{TimeUnit.NANOSECONDS.timedWait(lock,remaining);}catch(InterruptedException e){throw new Failure("driver interrupted");}}if(w.failure!=null){Throwable e=w.failure;w.failure=null;gap(r,"core worker failure: "+e);return null;}if(w.op==null)return null;if(!kind.equals(w.op.kind))return null;return w.op;}}
    void finish(Worker w,Op op,Object value){synchronized(lock){if(w.op!=op)throw new Failure("operation ownership changed");w.op=null;op.result=value;op.done=true;lock.notifyAll();}}
    void mismatch(Map<String,Object>r,Worker w,Op op,String reason){Map<String,Object> diagnostic=obj("kind","replay_mismatch","source_seq",r.get("seq"),"reason",reason,"computed_operation",op.kind,"computed_method",op.method,"computed_arguments",Arrays.toString(op.args),"original_arguments",r.get("args"));if(op.kind.equals("transport"))diagnostic.put("computed_transport",obj("topic",Long.toString((Long)op.args[0]),"payload_b64",Base64.getEncoder().encodeToString((byte[])op.args[1]),"timestamp_ms",Long.toString((Long)op.args[2])));emit(diagnostic);finish(w,op,new Failure(reason));}
    boolean equal(Object actual,Object typed){
        if(!(typed instanceof Map))return Objects.equals(actual,typed);
        Map<String,Object> v=map(typed);String type=str(v.get("type"));
        if("null".equals(type))return actual==null;if(actual==null)return false;
        Map<String,Object> original=values.original(actual);if(v.containsKey("id")&&original!=null){if(!Objects.equals(v.get("id"),original.get("id")))return false;if(!comparingContents)return true;}
        if("i32".equals(type))return actual instanceof Number&&(!v.containsKey("boxed_class")||actual.getClass().getName().equals(v.get("boxed_class")))&&((Number)actual).intValue()==((Number)v.get("value")).intValue();
        if("i64".equals(type))return actual instanceof Number&&((Number)actual).longValue()==Long.parseLong(str(v.get("decimal")));
        if("f32".equals(type))return actual instanceof Number&&Float.floatToRawIntBits(((Number)actual).floatValue())==(int)Long.parseLong(str(v.get("bits")),16);
        if("f64".equals(type))return actual instanceof Number&&Double.doubleToRawLongBits(((Number)actual).doubleValue())==Long.parseUnsignedLong(str(v.get("bits")),16);
        if("boolean".equals(type)||"string".equals(type))return Objects.equals(actual,v.get("value"));
        if("bytes".equals(type))return actual instanceof byte[]&&Arrays.equals((byte[])actual,Base64.getDecoder().decode(str(v.get("base64"))));
        if("list".equals(type)){if(actual.getClass().isArray()){List<Object> items=new ArrayList<>();for(int i=0;i<Array.getLength(actual);i++)items.add(Array.get(actual,i));actual=items;}if(!(actual instanceof List))return false;List<?> a=(List<?>)actual,b=list(v.get("items"));if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!equal(a.get(i),b.get(i)))return false;return true;}
        if("struct".equals(type)){Map<String,Object> fields=map(v.get("fields"));if(actual instanceof QueryAppointLanesInfoBean){if(!"com.byd.map.signal.someip.pilot.bean.QueryAppointLanesInfoBean".equals(v.get("class")))return false;int count=0;for(Field field:QueryAppointLanesInfoBean.class.getDeclaredFields()){if(Modifier.isStatic(field.getModifiers())||field.getName().equals("clock"))continue;count++;if(!fields.containsKey(field.getName()))return false;field.setAccessible(true);try{if(!equal(field.get(actual),fields.get(field.getName())))return false;}catch(IllegalAccessException e){throw new Failure(e.toString());}}return count==fields.size();}if(actual instanceof Map&&!fields.containsKey("entries")){Map<?,?> actualMap=(Map<?,?>)actual;if(actualMap.size()!=fields.size())return false;for(Map.Entry<String,Object> e:fields.entrySet())if(!actualMap.containsKey(e.getKey())||!equal(actualMap.get(e.getKey()),e.getValue()))return false;return true;}if(actual instanceof Map&&fields.containsKey("entries")){Map<?,?> entries=(Map<?,?>)actual;List<Object> expected=list(map(fields.get("entries")).get("items"));if(entries.size()!=expected.size())return false;for(Object item:expected){List<Object> pair=list(map(item).get("items"));Object key=values.decode(pair.get(0));if(!entries.containsKey(key)||!equal(entries.get(key),pair.get(1)))return false;}return true;}boolean used=false;for(Field f:actual.getClass().getFields()){if(Modifier.isStatic(f.getModifiers())||!fields.containsKey(f.getName()))continue;used=true;try{if(!equal(f.get(actual),fields.get(f.getName())))return false;}catch(IllegalAccessException e){throw new Failure(e.toString());}}return used;}
        if(original!=null)return Objects.equals(original.get("id"),v.get("id"));if("object".equals(type)&&actual.getClass().getName().startsWith("dev.denza.adaslink.stocknative.")&&str(v.get("class")).endsWith("."+actual.getClass().getSimpleName())){String id=str(v.get("id"));Object old=ownedStateBindings.get(id);String oldId=ownedStateReverse.get(actual);if(old!=null&&old!=actual||oldId!=null&&!oldId.equals(id))return false;ownedStateBindings.put(id,actual);ownedStateReverse.put(actual,id);return true;}return false;
    }
    void verifyArgs(Op op,Map<String,Object>r){List<Object>a=list(r.get("args"));if(a.size()!=op.args.length)throw new Failure("argument count differs for "+op.method+": original "+a.size()+" computed "+op.args.length);for(int i=0;i<a.size();i++)if(!equal(op.args[i],a.get(i)))throw new Failure("argument "+i+" differs for "+op.method);}
    /** Reject missing consumed raw fields before Java defaults could masquerade as input. */
    void validateInput(Object typed){
        if(!(typed instanceof Map))throw new Failure("typed original input required");
        Map<String,Object> value=map(typed);String type=str(value.get("type")),klass=str(value.get("class"));
        if("list".equals(type)){for(Object item:list(value.get("items")))validateInput(item);return;}
        if(!"struct".equals(type)&&!"object".equals(type))return;
        Class<?> model=null;
        if(klass!=null){String simple=klass.substring(klass.lastIndexOf('.')+1);
            if(simple.equals("Coord2DDouble"))model=NativePath.Point.class;
            else if(simple.equals("Coord2DInt32"))model=NativePath.IntPoint.class;
            else if(simple.equals("GroupSegment"))model=NativePath.Group.class;
            else if(simple.equals("ViaRoadInfo"))model=NativePath.ViaRoad.class;
            else if(klass.equals("com.byd.map.signal.someip.pilot.bean.TrafficLightState"))model=TrafficLightState.class;
            else if(klass.equals("com.autosdk.bussiness.common.POI")||klass.equals("com.autosdk.bussiness.common.AlongWaySearchPoi")||klass.equals("com.autosdk.bussiness.common.AlongWayPoiDeepInfo")){/* Native virtual POI view, not a raw DTO. */}
            else try{Class<?> candidate=Class.forName(NativeModels.class.getName()+"$"+simple);if(!candidate.isInterface())model=candidate;}catch(ClassNotFoundException absent){}
        }
        String id=str(value.get("id"));
        if("object".equals(type)){if(model!=null&&!completeInputIds.contains(id))throw new Failure("original raw DTO reference lacks an earlier complete snapshot: "+klass+"#"+id);return;}
        Map<String,Object> fields=map(value.get("fields"));
        if(model!=null){for(Field field:model.getFields())if(!Modifier.isStatic(field.getModifiers())&&!fields.containsKey(field.getName()))throw new Failure("missing original consumed raw field "+klass+"."+field.getName());if(id!=null)completeInputIds.add(id);}
        for(Object field:fields.values())validateInput(field);
    }
    Object reference(Map<String,Object> typed){String klass=str(typed.get("class"));Class<?> api;
        if(klass.endsWith(".PathInfo"))api=NativePath.class;else if(klass.endsWith(".SegmentInfo"))api=NativePath.Segment.class;else if(klass.endsWith(".LinkInfo"))api=NativePath.Link.class;else if(klass.endsWith(".RouteCarResultData"))api=RouteCarResultData.class;else if(klass.endsWith(".RouteOption"))api=RouteOption.class;else if(klass.endsWith(".POI")||klass.endsWith(".AlongWaySearchPoi")||klass.endsWith(".AlongWayPoiDeepInfo"))api=Poi.class;else return new RawReference(typed);
        return Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,m,args)->{if(m.getDeclaringClass()==Object.class){if(m.getName().equals("toString"))return klass+"#"+typed.get("id");if(m.getName().equals("hashCode"))return System.identityHashCode(proxy);if(m.getName().equals("equals"))return proxy==args[0];}Object v=read((api==Poi.class?"com.autosdk.bussiness.common.POI":klass)+"."+m.getName(),proxy,args==null?new Object[0]:args);if(v instanceof Number){Number n=(Number)v;if(m.getReturnType()==int.class)return n.intValue();if(m.getReturnType()==long.class)return n.longValue();}return v;});
    }
    static final class RawReference {final Map<String,Object> original;RawReference(Map<String,Object> v){original=v;}}
    Object read(String method,Object receiver,Object...args){return await(new Op("getter",method,receiver,args));}
    long clock(String method){return ((Number)await(new Op("clock",method,null))).longValue();}
    public long uptimeMillis(){return clock("android.os.SystemClock.uptimeMillis");}
    public long elapsedRealtime(){return clock("android.os.SystemClock.elapsedRealtime");}
    public long currentTimeMillis(){return clock("java.lang.System.currentTimeMillis");}
    public void stateEnter(String method,Object receiver,Object...args){await(new Op("state_enter",method,receiver,args));}
    public void stateExit(String method,Object receiver,Object result){await(new Op("state_exit",method,receiver,result));}
    public void processingEnter(String method,Object...args){Worker w=current.get();boolean root=w.processingFrames.isEmpty()&&method.equals(w.processingRoot);w.processingFrames.push(new ProcessingFrame(method,root));if(!root)await(new Op("processing_enter",method,null,args));}
    public void processingExit(String method){Worker w=current.get();if(w.processingFrames.isEmpty())throw new Failure("processing exit without entry "+method);ProcessingFrame frame=w.processingFrames.pop();if(!frame.method.equals(method))throw new Failure("processing scope order differs");if(!frame.root)await(new Op("processing_exit",method,null));}
    public void post(Queue queue,Object runnable,Object token,long delay,Runnable action){long due=uptimeMillis()+Math.max(0,delay);await(new Op("post",null,null,queue,runnable,token,due,action));}
    public void removeCallbacks(Queue queue,Object runnable){await(new Op("cancel","removeCallbacks",null,queue,runnable));}
    public void removeToken(Queue queue,Object token){await(new Op("cancel","removeCallbacksAndMessages",null,queue,token));}
    int output(String operation,long topic,byte[]payload){Object r=await(new Op("output",operation,null,Long.toString(topic),payload));return ((Number)r).intValue();}
    public int fire(long topic,byte[]payload){long timestamp=currentTimeMillis();if(hasTransportReads)return ((Number)await(new Op("transport","com.autosdk.bussiness.pilot.someip.SomeIpServer.sendSomeIpEvent",null,topic,payload,timestamp))).intValue();return output("fire",topic,payload);}
    public int startService(long topic){if(hasServiceReads)return Boolean.TRUE.equals(await(new Op("service_start","com.autosdk.bussiness.pilot.someip.SomeIpServer.startSomeIpService",null,topic)))?0:-1;return output("start_service",topic,null);}
    public int stopService(long topic){return output("stop_service",topic,null);}
    public int request(long topic,byte[]payload,boolean response){currentTimeMillis();return output("client_send",topic,payload);}
    private Object singleton(String klass){return read(klass+".getInstance",null);}
    public boolean tipIsland(){return (Boolean)read("k.h.e0.b.f.r",null);}
    public boolean laneUi(){return (Boolean)read("k.h.e0.g.n.b",null);}
    public boolean noaConfigured(){return (Boolean)read("k.h.e0.b.f.q",null);}
    public void startClientOrSubscribe(Map<Long,Long[]> values){read("com.autosdk.bussiness.pilot.someip.SomeIpClient.startClientOrSubscribe",null,values);}
    public void stopClient(Map<Long,Long[]> values){read("com.autosdk.bussiness.pilot.someip.SomeIpClient.stopClient",null,values);}
    public void unSubscribe(Map<Long,Long[]> values){read("com.autosdk.bussiness.pilot.someip.SomeIpClient.unSubscribe",null,values);}
    public void resetRouteSignals(){read("k.h.e0.c.j.f0.l",null);}
    public void setAvpSupport(int support){read("k.e.c.c0.k",null,support);}
    public int adasType(){return ((Number)read("k.h.e0.b.f.h",null)).intValue();}
    public boolean someIpV2(){return (Boolean)read("com.autosdk.bussiness.vehicle.PlatformUtils.isSomeIpV2",null);}
    public boolean isNaving(){Object n=singleton("com.autosdk.bussiness.navi.NaviController");return (Boolean)read("com.autosdk.bussiness.navi.NaviController.isNaving",n);}
    public boolean isKD(){return (Boolean)read("com.autosdk.bussiness.vehicle.PlatformUtils.isKD",null);}
    public boolean isMainThread(){if(mainThreadId==null)throw new Failure("captured MAIN thread identity absent");return mainThreadId.equals(current.get().id);}
    public double realSpeed(){Object p=singleton("com.autosdk.bussiness.vehicle.PlatformUtils");return ((Number)read("com.autosdk.bussiness.vehicle.PlatformUtils.realSpeed",p)).doubleValue();}
    Object mapService(){Object controller=singleton("com.autosdk.bussiness.data.MapDataController");return read("com.autosdk.bussiness.data.MapDataController.getMapDataService",controller);}
    public boolean hasMapDataService(){return mapService()!=null;}
    public int getAdcodeByLonLat(double lon,double lat){return ((Number)read("com.autonavi.gbl.data.MapDataService.getAdcodeByLonLat",mapService(),lon,lat)).intValue();}
    public int getTownAdcodeByLonLat(double lon,double lat){return ((Number)read("com.autonavi.gbl.data.MapDataService.getTownAdcodeByLonLat",mapService(),lon,lat)).intValue();}
    public Integer getCityProvince(int adcode){Object result=read("com.autonavi.gbl.data.MapDataService.getCityInfo",mapService(),adcode);if(result==null)return null;if(result instanceof Map)return ((Number)((Map<?,?>)result).get("belongedProvince")).intValue();throw new Failure("city DTO missing belongedProvince");}
    public long queryAppointLanesInfo(int segment,int link,int count){Object n=singleton("com.autosdk.bussiness.navi.NaviController");Object g=read("com.autosdk.bussiness.navi.NaviController.getGuideService",n);return ((Number)read("com.autonavi.gbl.guide.GuideService.queryAppointLanesInfo",g,new QueryLanesInfo(segment,link,count))).longValue();}
    public String cachedOpenCityJson(){return (String)read("k.h.c.z.t1.a",null,"KEY_OPEN_CITY","");}
    public void writeCachedOpenCityJson(String value){read("k.h.c.z.t1.c",null,"KEY_OPEN_CITY",value);}
    @SuppressWarnings("unchecked") public List<Integer> avpMapIds(String id){Object a=singleton("com.autosdk.bussiness.noa.avp.AvpManager");return (List<Integer>)read("com.autosdk.bussiness.noa.avp.AvpManager.getAvpMapIdsByPoiId",a,id);}
    public double distanceBetweenPoints(double a,double b,double c,double d){return ((Number)read("com.autosdk.bussiness.location.utils.LocationUtil.calcDistanceBetweenPoints",null,new NativePath.Point(a,b),new NativePath.Point(c,d))).doubleValue();}
    public Coord2DDouble lastLocation(){Object c=singleton("com.autosdk.bussiness.location.LocationController");Object loc=read("com.autosdk.bussiness.location.LocationController.getLastLocation",c);if(loc==null)return null;double lon=((Number)read("android.location.Location.getLongitude",loc)).doubleValue(),lat=((Number)read("android.location.Location.getLatitude",loc)).doubleValue();return new NativePath.Point(lon,lat);}
    public void setLeaveTunnelDist(int distance){if(hasTunnelWrites)read("com.autosdk.bussiness.location.instrument.TunnelDetector.setLeaveTunnelDist",null,distance);else await(new Op("environment_write","leave_tunnel_distance",null,distance));}
    public void setEnterTunnelDist(int distance){if(hasTunnelWrites)read("com.autosdk.bussiness.location.instrument.TunnelDetector.setEnterTunnelDist",null,distance);else await(new Op("environment_write","enter_tunnel_distance",null,distance));}
    public String stringResource(String name){return (String)await(new Op("getter","android.content.Context.getString",null,name));}
    final Set<String> barrierMethods=new HashSet<>(Arrays.asList(
        E0+".B0",E0+".D0",E0+".F",E0+".H",E0+".J",E0+".L",E0+".N",E0+".P",E0+".R",E0+".T",E0+".V",E0+".X",E0+".Z",E0+".b0",E0+".d0",E0+".f0",E0+".h0",E0+".i0",E0+".k0",E0+".l0",E0+".n0",E0+".p0",E0+".r0",E0+".t0",E0+".v0",E0+".x0",E0+".z0",E0+".z",E0+".F0",
        G+".C",G+".E",G+".G",G+".I",G+".K","k.h.e0.c.f.e","k.h.e0.d.f.k.f","k.h.e0.d.f.k.g","k.h.e0.d.f.k.i"));
    final Map<String,Deque<String>> readStacks=new HashMap<>();
    {for(char name='c';name<='s';name++)barrierMethods.add("k.h.a0.c.c.a.f0."+name);}
    final Set<String> stateMethods=new HashSet<>(Arrays.asList(
        "com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurSegIdx","com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurLinkIdx",
        "com.byd.map.signal.someip.pilot.bean.NaviInfoParam.setNaviInfoParam","com.byd.map.signal.someip.pilot.bean.NaviInfoParam.reSetNaviInfo",
        "com.byd.map.signal.someip.pilot.bean.FontRoundBean.getFont3kmIdx","com.byd.map.signal.someip.pilot.bean.FontRoundBean.setParam"));
    final Map<String,Object> stateIdentities=new HashMap<>();final IdentityHashMap<Object,String> stateReverse=new IdentityHashMap<>();
    final Map<String,Object[]> stateArguments=new HashMap<>();
    void stateBinding(Object actual,Object typed,String path,List<Object> bindings){
        if(!(typed instanceof Map)||actual==null)return;Map<String,Object> value=map(typed);String id=str(value.get("id"));
        if(id!=null){Object prior=stateIdentities.get(id);String reverse=stateReverse.get(actual);if(prior!=null&&prior!=actual||reverse!=null&&!reverse.equals(id))throw new Failure("computed sender-state identity differs at "+path);stateIdentities.put(id,actual);stateReverse.put(actual,id);bindings.add(obj("argument_path",path,"original_id",id,"original_class",value.get("class"),"port_id",identity(actual),"port_class",actual.getClass().getName()));}
        if("list".equals(value.get("type"))){List<Object> items=list(value.get("items"));for(int n=0;n<items.size();n++)stateBinding(actual.getClass().isArray()?Array.get(actual,n):((List<?>)actual).get(n),items.get(n),path+".items["+n+"]",bindings);}
    }
    void stateBarrier(Map<String,Object> row,boolean entering){boolean previousContents=comparingContents;comparingContents=true;try{stateBarrierContents(row,entering);}finally{comparingContents=previousContents;}}
    void stateBarrierContents(Map<String,Object> row,boolean entering){Worker w=worker(row);Op op=take(row,entering?"state_enter":"state_exit");if(op==null){gap(row,"computed sender-state boundary not reached: "+row.get("method"));return;}try{
        String method=methodBase(str(row.get("method")));Map<String,Object> stateEntry=entering?row:calls.get(str(row.get("call_id")));Object receiver=stateEntry==null?null:stateEntry.get("receiver");if(!method.equals(op.method)||!equal(op.receiver,receiver))throw new Failure("computed sender-state receiver/method differs");List<Object> bindings=new ArrayList<>();stateBinding(op.receiver,receiver,"receiver",bindings);
        if(entering){verifyArgs(op,row);stateArguments.put(str(row.get("call_id")),op.args);List<Object> args=list(row.get("args"));for(int n=0;n<op.args.length;n++)stateBinding(op.args[n],args.get(n),"args["+n+"]",bindings);}
        else{if(row.get("error")!=null||!row.containsKey("result")||!equal(op.args[0],row.get("result")))throw new Failure("computed sender-state result/error differs");Object[] before=stateArguments.remove(str(row.get("call_id")));List<Object> after=list(row.get("args_after"));if(before==null||before.length!=after.size())throw new Failure("sender-state exit argument count differs");for(int n=0;n<before.length;n++){if(!equal(before[n],after.get(n)))throw new Failure("computed sender-state argument mutation differs");stateBinding(before[n],after.get(n),"args_after["+n+"]",bindings);}stateBinding(op.args[0],row.get("result"),"result",bindings);}
        emit(obj("kind","consumption_receipt","source_seq",row.get("seq"),"operation",entering?"state entry":"state exit","method",row.get("method"),"args_verified",entering,"args_after_verified",!entering,"result_verified",!entering,"receiver_verified",true,"state_bindings",bindings));finish(w,op,null);
    }catch(Failure failure){mismatch(row,w,op,failure.getMessage());}}
    boolean ownedAncestor(Map<String,Object>r){String id=str(r.get("parent_call_id"));Set<String>seen=new HashSet<>();while(id!=null&&seen.add(id)){Map<String,Object>p=calls.get(id);if(ownedCalls.contains(id)){String base=p==null?"":methodBase(str(p.get("method")));if(!base.equals("k.h.e0.b.d.i")&&!base.equals("k.h.e0.b.d.k")&&!base.equals("k.h.e0.b.d.l"))return true;}id=p==null?null:str(p.get("parent_call_id"));}return false;}
    void root(Map<String,Object>r){String method=methodBase(str(r.get("method")));for(Object arg:list(r.get("args")))validateInput(arg);Object[]args=list(r.get("args")).stream().map(values::decode).toArray();
        if(r.get("gate_snapshot") instanceof Map){Map<String,Object>g=map(r.get("gate_snapshot"));sender.gates(Boolean.TRUE.equals(g.get("b")),Boolean.TRUE.equals(g.get("a")));sender.memoryGate(Boolean.TRUE.equals(g.get("p")));}
        Runnable action=callback(method,args);
        if(action==null){gap(r,"public callback/lifecycle not represented by native core: "+method);return;}
        ownedCalls.add(str(r.get("call_id")));rootArguments.put(str(r.get("call_id")),args);receipt(r,"external callback receipt");Worker w=worker(r);w.submit(()->{w.callbackHasResult=false;w.callbackResult=null;w.processingRoot=method.equals("k.h.e0.d.f.k.f")?method:null;try{action.run();}finally{w.processingRoot=null;}});
    }
    int i(Object[]a,int n){return ((Number)a[n]).intValue();}long l(Object[]a,int n){return ((Number)a[n]).longValue();}double d(Object[]a,int n){return ((Number)a[n]).doubleValue();}boolean b(Object[]a,int n){return (Boolean)a[n];}
    @SuppressWarnings({"unchecked","rawtypes"}) Runnable callback(String method,Object[]a){String name=shortMethod(method);OriginalMmtSender m=sender.mmt;
        if(method.startsWith(E0+"."))switch(name){
            case "I0":return ()->m.I0(i(a,0));case "p":return ()->m.p(i(a,0));case "onLocInfoUpdate":return ()->m.onLocInfoUpdate((LocInfo)a[0]);case "l":return ()->m.l((NaviInfo)a[0],(NativePath)a[1]);case "t":return ()->m.t((RouteCarResultData)a[0]);
            case "d":return m::d;case "onCruiseStart":return m::onCruiseStart;case "onCruiseStop":return m::onCruiseStop;case "onNaviSimStart":return m::onNaviSimStart;case "onNavStop":return m::onNavStop;
            case "onChangeNaviPath":return ()->m.onChangeNaviPath(l(a,0),l(a,1));case "onSelectMainPathStatus":return ()->m.onSelectMainPathStatus(l(a,0),i(a,1));case "onCurrentRoadSpeed":return ()->m.onCurrentRoadSpeed(i(a,0));
            case "onEnterTunnel":return ()->m.onEnterTunnel(d(a,0),d(a,1));case "onLeaveTunnel":return ()->m.onLeaveTunnel(d(a,0),d(a,1));case "onNaviArrive":return ()->m.onNaviArrive(l(a,0),i(a,1));
            case "onNearEnterTunnel":return ()->m.onNearEnterTunnel(i(a,0));case "onNearLeaveTunnel":return ()->m.onNearLeaveTunnel(i(a,0));case "onPlayTTS":return ()->m.onPlayTTS((SoundInfo)a[0]);case "onQueryAppointLanesInfo":return ()->m.onQueryAppointLanesInfo(l(a,0),(ArrayList)a[1]);case "onReroute":return ()->m.onReroute((RouteOption)a[0]);
            case "onShowNaviWeather":return ()->m.onShowNaviWeather((ArrayList)a[0]);case "onShowSameDirectionMixForkInfo":return ()->m.onShowSameDirectionMixForkInfo((ArrayList)a[0]);case "onUpdateCruisePredictiveTunnelInfo":return ()->m.onUpdateCruisePredictiveTunnelInfo((CruisePredictiveTunnelInfo)a[0]);case "onUpdateCruiseRoadSpeed":return ()->m.onUpdateCruiseRoadSpeed((CruiseRoadSpeed)a[0]);
            case "q":return ()->m.q((List)a[0]);case "c":return ()->m.c(b(a,0),(List)a[1]);case "f":return ()->m.f(b(a,0),(List)a[1]);case "m":return ()->m.m(b(a,0),(ArrayList)a[2]);case "a":return ()->m.a(b(a,0),(ArrayList)a[1]);case "b":return ()->m.b(b(a,0),(List)a[4],l(a,2),l(a,3));case "r":return ()->m.r(b(a,0),(ArrayList)a[4],i(a,3));case "s":return ()->m.s(b(a,0),(ArrayList)a[1]);
            case "u":return ()->m.u(b(a,0),l(a,1),l(a,2),(TrafficLightState)a[9],(Coord2DDouble)a[6],i(a,7),i(a,8));case "x":return ()->m.x(b(a,0),(ArrayList)a[4],(ArrayList)a[5],(ArrayList)a[8],(ArrayList)a[9],(ArrayList)a[10],(ArrayList)a[11],i(a,12),i(a,13),(ArrayList)a[6]);default:return null;
        }
        if(method.startsWith(G+".")){OriginalRoutePolicy p=sender.policy;if(p==null)throw new Failure("g callback before captured navigation lifecycle creation");switch(name){case "t":return ()->p.t((RouteCarResultData)a[0]);case "l":return ()->p.l((NaviInfo)a[0],(NativePath)a[1]);case "g":return ()->p.g(l(a,0));case "w":return p::w;case "onChangeNaviPath":return ()->p.onChangeNaviPath(l(a,0),l(a,1));case "onDnpSwitchState":return ()->p.onDnpSwitchState(b(a,0),b(a,1),b(a,2));case "onNavStop":return p::onNavStop;case "onSelectMainPathStatus":return ()->p.onSelectMainPathStatus(l(a,0),i(a,1));case "onSpeedChange":return ()->{Object speed=a[0];float v=speed instanceof Number?((Number)speed).floatValue():((Number)map(speed).get("currentSpeed")).floatValue();p.onSpeedChange(v);};default:return null;}}
        if(method.equals("k.h.e0.d.f.k.f"))return ()->sender.multipleRoutes((RouteCarResultData)a[0]);
        if(method.equals("k.h.a0.c.c.a.f0.t"))return ()->sender.noSleep(b(a,0));
        if(method.equals("k.h.e0.d.f.m.a"))return ()->sender.openCitiesUpdated((List)a[0]);
        if(method.startsWith("k.h.e0.b.d$a.")){if(name.equals("onDnpSwitchState"))return ()->sender.linkSwitchChanged(b(a,0));if(name.equals("onSupportTipIsland"))return ()->sender.tipIslandSupportChanged(b(a,0));}
        if(method.startsWith("k.h.e0.b.d$c.")&&name.equals("onInitBindResult"))return ()->sender.clientInitialized(b(a,0));
        if(method.startsWith("k.h.e0.b.d.")){if(name.equals("m"))return ()->{current.get().callbackResult=sender.serviceAvailable(l(a,0));current.get().callbackHasResult=true;};if(name.equals("q"))return ()->{current.get().callbackResult=sender.serviceUnavailable(l(a,0));current.get().callbackHasResult=true;};if(name.equals("o"))return sender::serverInitialized;if(name.equals("i"))return ()->sender.navigationCreated();if(name.equals("j"))return sender::navigationDestroyed;if(name.equals("k"))return sender::navigationStarted;if(name.equals("l"))return sender::navigationStopped;}
        return null;
    }
    void barrier(Map<String,Object>r,boolean entering){boolean argsVerified=false,lightbarVerified=false;Worker w=worker(r);Op op=take(r,entering?"processing_enter":"processing_exit");if(op==null){gap(r,"processing boundary not reached by core: "+r.get("method"));return;}
        if(!methodBase(str(r.get("method"))).equals(op.method)){mismatch(r,w,op,"processing method order differs: original "+r.get("method"));return;}
        try{if(entering){for(Object a:list(r.get("args"))){validateInput(a);values.refresh(a);}verifyArgs(op,r);processingArguments.put(str(r.get("call_id")),op.args);if(op.method.equals(E0+".B0")&&op.args[0]!=null)retainedLightbar=(List<?>)op.args[0];}else{Object[] before=processingArguments.remove(str(r.get("call_id")));if(r.get("args_after") instanceof List&&before!=null){List<Object> after=list(r.get("args_after"));comparingContents=true;try{if(after.size()!=before.length)throw new Failure("mutation checkpoint argument count differs");for(Object raw:after)validateInput(raw);for(int a=0;a<before.length;a++)if(!equal(before[a],after.get(a)))throw new Failure("raw input mutation differs for "+op.method+" argument "+a);for(int a=0;a<before.length;a++)if(values.original(before[a])!=null)values.bindVerifiedMutation(before[a],after.get(a));argsVerified=true;}finally{comparingContents=false;}}else mutationEvidenceAbsent=true;if(r.get("retained_inputs_after") instanceof Map){Map<String,Object> retained=map(r.get("retained_inputs_after"));if(retained.containsKey("lightbar")){comparingContents=true;try{if(!equal(retainedLightbar,retained.get("lightbar")))throw new Failure("retained raw lightbar mutation differs after reset");values.bindVerifiedMutation(retainedLightbar,retained.get("lightbar"));lightbarVerified=true;}finally{comparingContents=false;}}}}if(entering)receipt(r,"processing entry");else emit(obj("kind","consumption_receipt","source_seq",r.get("seq"),"operation","processing exit","method",r.get("method"),"args_after_verified",argsVerified,"retained_lightbar_verified",lightbarVerified));finish(w,op,null);}catch(Failure failure){mismatch(r,w,op,failure.getMessage());}
    }
    boolean diagnosticClock(Map<String,Object>r){Object sites=r.get("sites");if(!(sites instanceof List))return false;for(Object s:(List<?>)sites){String v=str(s);if(v.startsWith("java.util.logging.")||v.startsWith("java.util.Calendar.")||v.startsWith("java.util.GregorianCalendar."))return true;}return false;}
    Map<String,Object> readEnd(Map<String,Object>start){for(int n=cursor+1;n<rows.size();n++){Map<String,Object>r=rows.get(n);if(Objects.equals(r.get("read_id"),start.get("read_id"))&&("getter".equals(r.get("kind"))||"clock".equals(r.get("kind"))))return r;}throw new Failure("unclosed read "+start.get("read_id"));}
    void readEnter(Map<String,Object>r){String tid=thread(r),id=str(r.get("read_id")),kind=str(r.get("kind")).startsWith("getter")?"getter":"clock";Deque<String>s=readStacks.computeIfAbsent(tid,k->new ArrayDeque<>());
        if(!s.isEmpty()){Read parent=reads.get(s.peek());reads.put(id,new Read(null,parent==null?null:parent.worker,s.peek(),parent!=null&&parent.diagnostic));s.push(id);observation(r,"nested external native/platform read; delegated original implementation");return;}
        Worker w=worker(r);boolean transportRead=methodBase(str(r.get("method"))).equals("com.autosdk.bussiness.pilot.someip.SomeIpServer.sendSomeIpEvent");boolean serviceRead=methodBase(str(r.get("method"))).equals("com.autosdk.bussiness.pilot.someip.SomeIpServer.startSomeIpService");Op op=take(r,transportRead?"transport":serviceRead?"service_start":kind);Map<String,Object>end=readEnd(r);boolean diagnostic="clock".equals(kind)&&diagnosticClock(end);
        if(diagnostic){reads.put(id,new Read(null,w,null,true));s.push(id);observation(r,"source-proven diagnostic Calendar/Java logger clock; value never enters sender state");return;}
        if(op==null){gap(r,"unconsumed original "+kind+" initiation: "+r.get("method"));reads.put(id,new Read(null,w,null,false));s.push(id);return;}
        String expected=op.method,actual=methodBase(str(r.get("method")));
        if(!actual.equals(expected)){mismatch(r,w,op,"getter/clock order differs: original "+actual+", computed "+expected);reads.put(id,new Read(null,w,null,false));s.push(id);return;}
        try{
            if(op.receiver!=null&&!equal(op.receiver,r.get("receiver")))throw new Failure("getter receiver identity differs for "+actual);
            if(actual.equals("android.content.Context.getString")){if(op.args.length!=1||!Objects.equals(op.args[0],end.get("resource_name")))throw new Failure("original string resource differs: "+end.get("resource_name"));}
            else if(transportRead){List<Object> nativeArgs=list(r.get("args"));if(nativeArgs.size()!=1)throw new Failure("transport getter argument count differs");Map<String,Object> fields=map(map(nativeArgs.get(0)).get("fields"));if(!equal(op.args[0],fields.get("mTopic"))||!equal(op.args[1],fields.get("mPayload"))||!equal(op.args[2],fields.get("mTimeStamp")))throw new Failure("computed SomeIpData differs at external transport seam");}
            else if(actual.equals("k.h.c.z.t1.a")||actual.equals("k.h.c.z.t1.c")){List<Object> originalArgs=list(r.get("args"));if(originalArgs.size()!=3||!equal(op.args[0],originalArgs.get(1))||!equal(op.args[1],originalArgs.get(2)))throw new Failure("open city getter key/default differs");}
            else verifyArgs(op,r);
            reads.put(id,new Read(op,w,null,false));s.push(id);receipt(r,kind+" initiation");
        }catch(Failure e){mismatch(r,w,op,e.getMessage());reads.put(id,new Read(null,w,null,false));s.push(id);}
    }
    final Map<String,Object> environmentArgumentBindings=new HashMap<>();
    final IdentityHashMap<Object,String> environmentArgumentReverse=new IdentityHashMap<>();
    void environmentArgumentBinding(Object actual,Map<String,Object> original,Map<String,Object> row,String path){
        if(!original.containsKey("id"))return;String id=str(original.get("id"));Object before=environmentArgumentBindings.get(id);String old=environmentArgumentReverse.get(actual);
        if(before!=null&&before!=actual||old!=null&&!old.equals(id))throw new Failure("SDK argument identity mapping changed at "+path);
        environmentArgumentBindings.put(id,actual);environmentArgumentReverse.put(actual,id);
        emit(obj("kind","environment_argument_binding","source_seq",row.get("seq"),"read_id",row.get("read_id"),"argument_path",path,"original_id",id,"original_class",original.get("class"),"port_id",identity(actual),"port_class",actual.getClass().getName(),"reason","SDK API argument identity/class after complete content verification"));
    }
    Object environmentArgument(Object actual,Object typed,Map<String,Object> row,String path){
        Map<String,Object> value=map(typed);String type=str(value.get("type"));if(!equal(actual,typed))throw new Failure("computed SDK argument differs at "+path);
        if(type.equals("null"))return obj("type","null");
        if(type.equals("i64"))return obj("type","i64","decimal",Long.toString(((Number)actual).longValue()));
        if(type.equals("i32"))return obj("type","i32","value",((Number)actual).intValue());
        environmentArgumentBinding(actual,value,row,path);
        Map<String,Object> out=obj("type",type);if(value.containsKey("class"))out.put("class",value.get("class"));if(value.containsKey("id"))out.put("id",value.get("id"));
        if(type.equals("list")){List<Object> items=new ArrayList<>(),templates=list(value.get("items"));for(int n=0;n<templates.size();n++){Object item=actual.getClass().isArray()?Array.get(actual,n):((List<?>)actual).get(n);items.add(environmentArgument(item,templates.get(n),row,path+".items["+n+"]"));}out.put("items",items);return out;}
        if(type.equals("struct")&&actual instanceof Map){List<Object> entries=new ArrayList<>(),templates=list(map(map(value.get("fields")).get("entries")).get("items"));Map<?,?> input=(Map<?,?>)actual;
            for(Map.Entry<?,?> entry:input.entrySet()){int n=0;for(;n<templates.size();n++){List<Object> candidate=list(map(templates.get(n)).get("items"));if(equal(entry.getKey(),candidate.get(0)))break;}if(n==templates.size())throw new Failure("computed SDK key lacks original argument template");List<Object> pair=list(map(templates.get(n)).get("items"));entries.add(obj("type","list","class","entry","items",Arrays.asList(environmentArgument(entry.getKey(),pair.get(0),row,path+".fields.entries.items["+n+"].items[0]"),environmentArgument(entry.getValue(),pair.get(1),row,path+".fields.entries.items["+n+"].items[1]"))));}
            out.put("fields",obj("entries",obj("type","list","class","entries","items",entries)));return out;}
        throw new Failure("unsupported SDK argument typed value "+type);
    }
    void environmentOperation(Map<String,Object> row){
        Read read=reads.get(str(row.get("read_id")));if(read==null||read.request==null)throw new Failure("SDK operation lacks independently consumed public API initiation");Op request=read.request;
        String base=request.method,operation=base.endsWith(".startClientOrSubscribe")?"client_start":base.endsWith(".stopClient")?"client_stop":base.endsWith(".unSubscribe")?"client_unsubscribe":base.equals("k.h.e0.c.j.f0.l")?"route_signal_reset":base.equals("k.e.c.c0.k")?"avp_support":null;
        String descriptor=base+("route_signal_reset".equals(operation)?"()":"avp_support".equals(operation)?"(int)":"(java.util.Map)");
        if(operation==null||!operation.equals(row.get("operation"))||!descriptor.equals(row.get("method")))throw new Failure("SDK operation method/order differs");
        List<Object> original=list(row.get("args")),computed=new ArrayList<>();if(original.size()!=request.args.length)throw new Failure("SDK operation argument count differs");
        for(int n=0;n<request.args.length;n++)if(!equal(request.args[n],original.get(n)))throw new Failure("computed SDK API map differs before identity binding");
        for(int n=0;n<request.args.length;n++)computed.add(environmentArgument(request.args[n],original.get(n),row,"args["+n+"]"));
        emit(obj("kind","environment_operation","operation",operation,"method",descriptor,"read_id",row.get("read_id"),"args",computed,"source_seq",row.get("seq")));
    }
    void readComplete(Map<String,Object>r){String id=str(r.get("read_id"));Read read=reads.remove(id);Deque<String>s=readStacks.get(thread(r));if(s==null||s.isEmpty()||!id.equals(s.pop()))throw new Failure("read span nesting differs "+id);
        if(read==null){gap(r,"read completion has no initiation");return;}
        if(read.request==null){observation(r,read.diagnostic?"source-proven diagnostic clock completion":"external delegated read completion");return;}
        if(r.get("error")!=null){finish(read.worker,read.request,new Failure("original external read threw: "+r.get("error")));return;}
        boolean sdkOperation=read.request.method!=null&&(read.request.method.startsWith("com.autosdk.bussiness.pilot.someip.SomeIpClient.")||read.request.method.equals("k.h.e0.c.j.f0.l")||read.request.method.equals("k.e.c.c0.k"));
        if(sdkOperation)verifyArgs(read.request,r);
        for(Object a:list(r.get("args")))values.refresh(a);validateInput(r.get("result"));Object value=values.decode(r.get("result"));
        if(sdkOperation)emit(obj("kind","consumption_receipt","source_seq",r.get("seq"),"operation","read completion","method",r.get("method"),"sdk_args_verified",true));else receipt(r,"read completion");finish(read.worker,read.request,value);
    }
    String identity(Object value){if(value==null)return null;String id=portIds.get(value);if(id==null){id="port-"+(++identity);portIds.put(value,id);}return id;}
    Object bind(Map<String,Object>r,String kind,Queue queue,Object actual,Object original){Map<String,Object>ref=map(original);if("null".equals(ref.get("type"))){if(actual!=null)throw new Failure("nonnull computed "+kind+" for original null");return original;}
        if(actual==null)throw new Failure("null computed "+kind+" for original object");String originalId=str(ref.get("id")),key=kind+"/"+queue+"/"+originalId,portId=identity(actual);Object prior=boundOriginal.get(key);if(prior!=null&&prior!=actual)throw new Failure("original "+kind+" identity changed computed instance");String inverse=kind+"/"+queue+"/"+portId;Object old=reverseBindings.get(inverse);if(old!=null&&!old.equals(originalId))throw new Failure("computed "+kind+" aliases distinct original objects");
        if(prior==null){String reason=kind.equals("handler")?"first handler queue binding":kind.equals("runnable")?"first originalClass-matched runnable post":kind.equals("token")?"literal token value binding":"environment Message allocation identity";emit(obj("kind","identity_binding","identity_kind",kind,"original",original,"port_id",portId,"queue_id",queue.name(),"source_seq",r.get("seq"),"reason",reason));}
        boundOriginal.put(key,actual);reverseBindings.put(inverse,originalId);return original;
    }
    final Map<Queue,Object> handlers=new EnumMap<>(Queue.class);final Map<String,Object> messages=new HashMap<>();
    Object handler(Queue q){return handlers.computeIfAbsent(q,k->new Object());}
    void timerPost(Map<String,Object>r){Worker w=worker(r);Op op=take(r,"post");if(op==null){gap(r,"original enqueue has no computed native post");return;}
        try{Queue q=(Queue)op.args[0];Object task=op.args[1],token=op.args[2];long due=(Long)op.args[3];if(!q.name().equals(r.get("queue_id")))throw new Failure("post queue differs");if(!(task instanceof StockTask))throw new Failure("computed runnable lacks original class provenance");if(!((StockTask)task).originalClass.equals(map(r.get("runnable")).get("class")))throw new Failure("original runnable class differs: "+map(r.get("runnable")).get("class")+" vs "+((StockTask)task).originalClass);
            if(!equal(token,r.get("token_value")))throw new Failure("literal post token differs");
            bind(r,"handler",q,handler(q),r.get("handler"));bind(r,"runnable",q,task,r.get("runnable"));bind(r,"token",q,token,r.get("token"));String mid=str(map(r.get("message")).get("id"));Object message=messages.computeIfAbsent(mid,k->new Object());bind(r,"message",q,message,r.get("message"));
            Map<String,Object>computed=obj("kind","timer_post_enter","post_id",r.get("post_id"),"handler",r.get("handler"),"runnable",r.get("runnable"),"token",r.get("token"),"message",r.get("message"),"queue_id",q.name(),"due_uptime_ms",Long.toString(due),"insertion_seq",r.get("insertion_seq"),"what",0,"source_seq",r.get("seq"));emit(computed);
            Post p=new Post(r,q,task,token,(Runnable)op.args[4],due);pending.put(str(r.get("post_id")),p);postInFlight.put(str(r.get("post_id")),new AbstractMap.SimpleEntry<>(op,p));receipt(r,"independently computed enqueue; original occurrence identity bound after validation");
        }catch(Failure e){mismatch(r,w,op,e.getMessage());}
    }
    final Map<String,Map.Entry<Op,Post>> postInFlight=new HashMap<>();
    final Set<String> executedPosts=new HashSet<>();
    void postResult(Map<String,Object>r){String id=str(r.get("post_id"));Map.Entry<Op,Post>flight=postInFlight.remove(id);if(flight==null){gap(r,"enqueue result has no computed enqueue");return;}boolean ok=Boolean.TRUE.equals(r.get("result"));if(!ok){if(executedPosts.contains(id))throw new Failure("original dispatch preceded rejected enqueue "+id);pending.remove(id);}emit(obj("kind","timer_post_result","post_id",id,"result",ok,"source_seq",r.get("seq")));finish(worker(r),flight.getKey(),null);}
    void cancel(Map<String,Object>r){Worker w=worker(r);Op op=take(r,"cancel");if(op==null){gap(r,"original cancel has no computed native cancellation");return;}try{Queue q=(Queue)op.args[0];Object target=op.args[1];if(!shortMethod(str(r.get("method"))).equals(op.method))throw new Failure("cancel method differs");bind(r,"handler",q,handler(q),r.get("handler"));List<Object>args=list(r.get("args")),argValues=list(r.get("arg_values"));if(args.size()!=1)throw new Failure("unsupported native Handler cancel overload");if(op.method.equals("removeCallbacks")){if(target==null){if(!"null".equals(map(args.get(0)).get("type")))throw new Failure("null removeCallbacks argument differs");}else{if(!(target instanceof StockTask)||!((StockTask)target).originalClass.equals(map(args.get(0)).get("class")))throw new Failure("cancel runnable class differs");bind(r,"runnable",q,target,args.get(0));}}else{if(!equal(target,argValues.get(0)))throw new Failure("cancel token value differs");bind(r,"token",q,target,args.get(0));}
            List<String>matched=new ArrayList<>();for(Map.Entry<String,Post>e:pending.entrySet()){Post p=e.getValue();if(p.queue==q&&(op.method.equals("removeCallbacks")?p.runnable==target:target==null||p.token==target))matched.add(e.getKey());}for(String id:matched)pending.remove(id);
            emit(obj("kind","timer_cancel","handler",r.get("handler"),"method",r.get("method"),"args",args,"matched_post_ids",matched,"source_seq",r.get("seq")));finish(w,op,null);
        }catch(Failure e){mismatch(r,w,op,e.getMessage());}}
    void execute(Map<String,Object>r){String id=str(r.get("post_id"));Post p=pending.remove(id);executedPosts.add(id);if(p==null){gap(r,"original dispatch lacks independently computed pending post "+id);return;}Worker w=worker(r);emit(obj("kind","timer_execute_enter","post_id",id,"handler",p.source.get("handler"),"runnable",p.source.get("runnable"),"message",p.source.get("message"),"queue_id",p.queue.name(),"source_seq",r.get("seq")));ownedCalls.add("timer:"+id);w.submit(()->{p.action.run();await(new Op("execute_exit",id,null));});}
    void executeExit(Map<String,Object>r){Worker w=worker(r);Op op=take(r,"execute_exit");if(op==null){gap(r,"original dispatch exit not reached by core");return;}if(!Objects.equals(op.method,str(r.get("post_id")))){mismatch(r,w,op,"timer execution occurrence differs");return;}emit(obj("kind","timer_execute_exit","post_id",op.method,"handler",r.get("handler"),"runnable",r.get("runnable"),"message",r.get("message"),"queue_id",r.get("queue_id"),"source_seq",r.get("seq")));finish(w,op,null);}
    void transport(Map<String,Object>r){Deque<String> stack=readStacks.get(thread(r));if(stack!=null&&!stack.isEmpty()){Read read=reads.get(stack.peek());while(read!=null&&read.root!=null)read=reads.get(read.root);if(read!=null&&read.request!=null&&(read.request.kind.equals("transport")||read.request.kind.equals("service_start"))){if(read.emitted){gap(r,"external transport invoked more than one original egress for one computed sender call");return;}read.emitted=true;Op call=read.request;emit(obj("kind","output","operation",call.kind.equals("service_start")?"start_service":"fire","topic",Long.toString((Long)call.args[0]),"payload_b64",call.kind.equals("service_start")?null:Base64.getEncoder().encodeToString((byte[])call.args[1]),"source_seq",r.get("seq")));return;}}Worker w=worker(r);Op op=take(r,"output");if(op==null){gap(r,"original transport effect has no computed effect");return;}String operation=op.method,topic=(String)op.args[0];byte[]payload=(byte[])op.args[1];emit(obj("kind","output","operation",operation,"topic",topic,"payload_b64",payload==null?null:Base64.getEncoder().encodeToString(payload),"source_seq",r.get("seq")));
        // Emulator fixture boundary implements these operations with returned code0.
        // The reference payload is never decoded or supplied to the core.
        finish(w,op,0);
    }
    void rootExit(Map<String,Object>r){
        Worker w=worker(r);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        synchronized(lock){
            while(w.busy&&w.op==null&&w.failure==null){long left=deadline-System.nanoTime();if(left<=0)break;try{TimeUnit.NANOSECONDS.timedWait(lock,left);}catch(InterruptedException e){throw new Failure("interrupted waiting for root exit");}}
            if(w.op!=null){Op op=w.op;gap(r,"computed operation exceeds original callback scope: "+op.kind+" "+op.method);finish(w,op,new Failure("computed callback did not reach original exit"));}
            if(w.failure!=null){gap(r,"core callback failure: "+w.failure);w.failure=null;}
        }
        String base=methodBase(str(r.get("method")));boolean returnsBoolean=base.equals("k.h.e0.b.d.m")||base.equals("k.h.e0.b.d.q");
        if(!r.containsKey("result")||!r.containsKey("error")||r.get("error")!=null||returnsBoolean&&!w.callbackHasResult||!equal(w.callbackResult,r.get("result")))throw new Failure("original callback result/error differs or is missing for "+base);
        boolean resultVerified=true;
        Object[] before=rootArguments.remove(str(r.get("call_id")));boolean verified=false;
        if(before!=null&&r.get("args_after") instanceof List){
            List<Object>after=list(r.get("args_after"));if(after.size()!=before.length)throw new Failure("external callback mutation argument count differs");
            comparingContents=true;
            try{for(int a=0;a<before.length;a++){validateInput(after.get(a));if(!equal(before[a],after.get(a)))throw new Failure("external raw input mutation differs for "+r.get("method")+" argument "+a);}for(int a=0;a<before.length;a++)if(values.original(before[a])!=null)values.bindVerifiedMutation(before[a],after.get(a));verified=true;}finally{comparingContents=false;}
        }else mutationEvidenceAbsent=true;
        emit(obj("kind","consumption_receipt","source_seq",r.get("seq"),"operation","external callback exit","method",r.get("method"),"args_after_verified",verified,"retained_lightbar_verified",false,"result_verified",resultVerified));
    }
    void run(){for(cursor=1;cursor<rows.size();cursor++){Map<String,Object>r=rows.get(cursor);String kind=str(r.get("kind"));try{
            if(kind.equals("hook")||kind.equals("ready"))continue;

            switch(kind){
                case "call_enter":{
                    calls.put(str(r.get("call_id")),r);String method=methodBase(str(r.get("method")));
                    if("external_input".equals(r.get("role"))&&!ownedAncestor(r))root(r);
                    else if(stateMethods.contains(method))stateBarrier(r,true);else if(barrierMethods.contains(method))barrier(r,true);
                    break;
                }
                case "call_exit":{
                    String method=methodBase(str(r.get("method")));Map<String,Object>entry=calls.get(str(r.get("call_id")));
                    boolean externalRoot=entry!=null&&ownedCalls.contains(str(r.get("call_id")));if(stateMethods.contains(method))stateBarrier(r,false);else if(barrierMethods.contains(method)&&!externalRoot)barrier(r,false);
                    if(externalRoot)rootExit(r);
                    break;
                }
                case "getter_enter":case "clock_enter":readEnter(r);break;
                case "getter":case "clock":readComplete(r);break;
                case "environment_operation":environmentOperation(r);break;
                case "timer_post_enter":timerPost(r);break;
                case "timer_post_result":postResult(r);break;
                case "timer_cancel":cancel(r);break;
                case "timer_execute_enter":execute(r);break;
                case "timer_execute_exit":executeExit(r);break;
                case "output":transport(r);break;
                case "capture_end":{
                    Set<String>expected=new LinkedHashSet<>();for(Object p:list(r.get("pending_posts")))expected.add(str(map(p).get("post_id")));
                    if(!expected.equals(pending.keySet()))gap(r,"terminal pending queue differs: original "+expected+", computed "+pending.keySet());
                    for(Post p:pending.values()){Map<String,Object>original=null;for(Object item:list(r.get("pending_posts")))if(Objects.equals(map(item).get("post_id"),p.source.get("post_id")))original=map(item);if(original==null||!Objects.equals(original.get("due_uptime_ms"),Long.toString(p.due)))gap(r,"terminal pending due time differs");}
                    for(Worker w:workers.values())if(w.busy||w.op!=null)gap(r,"computed sender thread not quiet at original capture_end: "+w.id);
                    break;
                }
                default:gap(r,"unknown original ledger record retained: "+kind);
            }
        }catch(Throwable failure){gap(r,"driver rejected original event: "+failure);Worker w=worker(r);if(w.op!=null)finish(w,w.op,new Failure(failure.toString()));}}
        for(Worker w:workers.values()){if(w.op!=null){gap(rows.get(rows.size()-1),"unconsumed computed operation at end: "+w.op.kind+" "+w.op.method);finish(w,w.op,new Failure("capture ended"));}}
        synchronized(lock){stopped=true;lock.notifyAll();}for(Worker w:workers.values())w.executor.shutdownNow();
        if(!hasTransportReads)gap(rows.get(rows.size()-1),"actual external transport return-code read spans absent; fixture code0 fallback is not full returned-code proof");
        if(mutationEvidenceAbsent)gap(rows.get(rows.size()-1),"original processing exits lack raw input mutation snapshots; dispatch refresh cannot establish endogenous mutation equivalence");
        emit(obj("kind","replay_complete","unconsumed",gaps,"pending_posts",new ArrayList<>(pending.keySet())));
    }
    static String sha(byte[]data)throws Exception{byte[]hash=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format("%02x",b&255));return s.toString();}
    @SuppressWarnings("unchecked") public static void main(String[]args)throws Exception{if(args.length!=2)throw new IllegalArgumentException("NativePhase2Replay ledger.jsonl port-ledger.jsonl");Path input=Paths.get(args[0]),output=Paths.get(args[1]);byte[]bytes=Files.readAllBytes(input);List<Map<String,Object>>rows=new ArrayList<>();for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\r?\\n"))if(!line.trim().isEmpty())rows.add((Map<String,Object>)Json.parse(line));if(rows.isEmpty()||!"denza-stock-sender-phase2-ledger-v1".equals(rows.get(0).get("schema")))throw new IllegalArgumentException("original phase2 ledger required");Files.createDirectories(output.toAbsolutePath().getParent());try(Writer out=Files.newBufferedWriter(output,StandardCharsets.UTF_8)){NativePhase2Replay replay=new NativePhase2Replay(rows,out);replay.emit(obj("kind","header","schema","denza-stock-sender-phase2-port-v1","ledger_sha256",sha(bytes),"source",rows.get(0).get("source"),"input_boundary","public sender callback receipts plus k.f planning component entry; original processing snapshots refresh retained raw DTOs only","root_processing_entries",Collections.singletonList("k.h.e0.d.f.k.f"),"clock_policy","independent semantic uptime/elapsed/wall reads; explicitly reported diagnostic clocks stay outside state","queue_policy","computed posts/cancels with actual original dispatch order and lateness; no tolerance","transport_return_policy",replay.hasTransportReads?"actual SomeIpServer getter return; computed SomeIpData verified before external call; reference payload never enters state":"emulator fixture code0 fallback; explicit incomplete evidence gap"));replay.run();}}
}
