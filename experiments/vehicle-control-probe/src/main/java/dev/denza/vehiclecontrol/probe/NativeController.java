package dev.denza.vehiclecontrol.probe;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Pure Java protocol/state engine. No Android dependencies; all writes are named allowlisted actions. */
public final class NativeController {
    public interface Shell { String run(String command, int timeoutMs) throws Exception; }
    public interface Provider { Connection open() throws Exception; }
    public record Connection(String identity, Shell shell) { }
    public record Command(String target, String action, String shell, boolean pulse) { }
    public static final class Busy extends IOException { Busy() { super("ADB занят; действие не поставлено в очередь"); } }
    /** A known per-control guard refusal before any SET, safe to report independently. */
    public static final class Refused extends IOException { Refused(String reason) { super(reason); } }
    private static final Pattern GET = Pattern.compile("Result: Parcel\\(([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})\\s+'[^\\r\\n]{8}'\\)\\s*");
    private static final Pattern SET = Pattern.compile("Result: Parcel\\(([0-9a-fA-F]{8})\\s+'[^\\r\\n]{4}'\\)\\s*");
    private static final Pattern MARKER = Pattern.compile("(?m)^@([A-Za-z0-9_]+)\\s*\\n");
    private static final Map<String,Integer> MODES = Map.of("off",1,"on",7,"wave",2,"pulse",3,"stretch",4,"waist",5,"shoulders",6);
    /** A window open percentage 1..99; 0 and 100 are the existing close/open movements. */
    private static final Pattern WINDOW_PERCENT = Pattern.compile("([1-9][0-9]?)%");
    final JSONObject catalog;
    private final Provider provider;
    private final File journal;
    private final ReentrantLock operation = new ReentrantLock();
    final Set<String> pendingStops = new TreeSet<>();
    /** Windows whose percent target may still be set: a target attempt without a later accepted 255 reset. */
    final Set<String> pendingResets = new TreeSet<>();
    private Shell shell;
    private String keyIdentity;
    private String vehicleIdentity;
    private final String identityCommand;
    private final LinkedHashMap<String,String> reads = new LinkedHashMap<>();

    public NativeController(JSONObject catalog, Provider provider, File journal) throws Exception {
        this.catalog = catalog; this.provider = provider; this.journal = journal;
        identityCommand = catalog.getJSONObject("protocol").getString("identity");
        collectReads(); replay();
    }
    static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>(); object.keys().forEachRemaining(result::add); Collections.sort(result); return result;
    }
    static JSONObject object(Object... pairs) throws JSONException {
        JSONObject value = new JSONObject();
        for (int i=0;i<pairs.length;i+=2) value.put((String)pairs[i], pairs[i+1] == null ? JSONObject.NULL : pairs[i+1]);
        return value;
    }
    static Integer parseGet(String raw) throws IOException {
        Matcher match = GET.matcher(raw.trim());
        if (!match.matches() || Long.parseLong(match.group(1),16) != 0) throw new IOException("Некорректный ответ чтения autoservice");
        return (int)Long.parseLong(match.group(2),16);
    }
    static int parseSet(String raw) throws IOException {
        Matcher match = SET.matcher(raw.trim());
        if (!match.matches()) throw new IOException("Некорректный ответ записи autoservice");
        return (int)Long.parseLong(match.group(1),16);
    }
    static Double percent(Object value) {
        if (!(value instanceof Number number)) return null;
        double result = number.doubleValue(); return Double.isFinite(result) && result>=0 && result<=100 ? result : null;
    }
    static boolean eq(Object value, int expected) { return value instanceof Number n && n.doubleValue()==expected; }
    static boolean oneOf(Object value, JSONArray allowed) throws JSONException {
        if (!(value instanceof Number n)) return false;
        for(int i=0;i<allowed.length();i++) if(n.doubleValue()==allowed.getDouble(i)) return true;
        return false;
    }
    static String first(String... reasons) { for(String reason: reasons) if(reason!=null) return reason; return null; }
    static String guard(boolean condition, String reason) { return condition ? null : reason; }
    String readCommand(String name) throws JSONException {
        JSONArray p = catalog.getJSONObject("properties").getJSONArray(name);
        return getCommand(p.getInt(0),p.getInt(1),p.getString(2).equals("float")?7:5);
    }
    static String getCommand(int device,int fid,int txn) { return "service call autoservice "+txn+" i32 "+device+" i32 "+fid; }
    String writeCommand(String property,int value) throws JSONException {
        JSONArray p = catalog.getJSONObject("properties").getJSONArray(property);
        return setCommand(p.getInt(0),p.getInt(1),value);
    }
    static String setCommand(int device,int fid,int value) { return "service call autoservice 6 i32 "+device+" i32 "+fid+" i32 "+value+" null"; }
    private void addName(Set<String> names,JSONObject spec,String... fields) throws JSONException {
        for(String field:fields) if(!spec.isNull(field) && !spec.optString(field).isEmpty()) names.add(spec.getString(field));
    }
    private void collectReads() throws JSONException {
        TreeSet<String> names = new TreeSet<>(); JSONObject locks=catalog.getJSONObject("locks"); addName(names,locks,"config","thermal");
        JSONObject doors=catalog.getJSONObject("doors");
        for(String key:keys(doors)) { JSONObject spec=doors.getJSONObject(key); for(String field:keys(spec)) if(!field.equals("set")) addName(names,spec,field); }
        JSONObject seats=catalog.getJSONObject("seats");
        for(String key:keys(seats)) {
            JSONObject seat=seats.getJSONObject(key),axes=seat.getJSONObject("axes"),comfort=seat.getJSONObject("comfort");
            for(String axis:keys(axes)) addName(names,axes.getJSONObject(axis),"position","config","initialized");
            for(String kind:keys(comfort)) addName(names,comfort.getJSONObject(kind),"config","state");
        }
        for(String group:List.of("shades","massages")) {
            JSONObject specs=catalog.getJSONObject(group);
            for(String key:keys(specs)) addName(names,specs.getJSONObject(key),group.equals("shades")?new String[]{"position","initialized","thermal","config"}:new String[]{"config","mode","level"});
        }
        JSONObject lights=catalog.getJSONObject("lights");
        for(String key:keys(lights)) {
            JSONObject spec=lights.getJSONObject(key); JSONArray states=spec.getJSONArray("states"),configs=spec.getJSONArray("configs");
            for(int i=0;i<states.length();i++) names.add(states.getString(i));
            for(int i=0;i<configs.length();i++) names.add(configs.getJSONArray(i).getString(0));
        }
        reads.put("park",getCommand(1011,89129008,5)); reads.put("permit",getCommand(1001,1222639670,5));
        JSONObject windows=catalog.getJSONObject("windows");
        for(String key:keys(windows)) {
            JSONArray spec=windows.getJSONArray(key);
            for(int i=0;i<3;i++) reads.put(key+new String[]{"_percent","_initialized","_thermal"}[i],getCommand(1001,spec.getInt(i+2),5));
        }
        JSONObject climate=catalog.optJSONObject("climate");
        if(climate!=null)for(String key:keys(climate)){
            JSONObject spec=climate.getJSONObject(key);addName(names,spec,"state");JSONArray configs=spec.getJSONArray("configs");
            for(int i=0;i<configs.length();i++)names.add(configs.getJSONArray(i).getString(0));
        }
        for(String name:names) reads.put(name,readCommand(name));
    }
    private String run(String command,int timeout) throws Exception { return shell.run(command,timeout).trim().replace("\r\n","\n"); }
    private void begin() throws Exception {
        Connection connection=provider.open(); shell=connection.shell();
        if(!Objects.equals(keyIdentity,connection.identity())) { keyIdentity=connection.identity(); vehicleIdentity=null; }
        if(vehicleIdentity==null) verify();
    }
    private void verify() throws Exception {
        JSONObject hashes=catalog.getJSONObject("protocol").getJSONObject("hashes"); List<String> paths=keys(hashes);
        String output=run(identityCommand+"; sha256sum "+String.join(" ",paths),30000);
        String[] lines=output.split("\n");
        if(lines.length!=3+paths.size() || !lines[0].equals("CANFD") || !lines[1].equals("168") || !lines[2].matches("[0-9a-f-]{36}"))
            throw new IOException("Профиль автомобиля не совпадает с исследованным CANFD N9");
        for(int i=0;i<paths.size();i++) if(!Arrays.equals(lines[i+3].trim().split("\\s+"),new String[]{hashes.getString(paths.get(i)),paths.get(i)}))
            throw new IOException("Версия файла отличается от исследованной: "+paths.get(i));
        vehicleIdentity=String.join("\n",Arrays.copyOfRange(lines,0,3));
    }
    private void checkIdentity(String observed) throws IOException {
        if(vehicleIdentity==null || !vehicleIdentity.equals(observed.trim())) throw new IOException("Машина или загрузка изменились. Перезапустите пульт для проверки версии");
    }
    private Map<String,Object> readValues() throws Exception { return readValues(reads); }
    /** The guard properties of these targets only, plus P: a plan does not read the whole car. */
    Map<String,String> readsFor(Collection<String> targets) throws JSONException {
        LinkedHashMap<String,String> subset=new LinkedHashMap<>();subset.put("park",reads.get("park"));
        for(String target:targets) for(String key:guardKeys(target)) if(reads.containsKey(key)) subset.put(key,reads.get(key));
        return subset;
    }
    /** What buildState consults to decide whether this target is blocked. */
    List<String> guardKeys(String target) throws JSONException {
        List<String> keys=new ArrayList<>();String[] p=target.split("\\.",-1);
        JSONObject locks=catalog.getJSONObject("locks");
        if(catalog.getJSONObject("windows").has(target)) { keys.add("permit");for(String s:List.of("_percent","_initialized","_thermal"))keys.add(target+s); }
        else if(target.equals("locks")) { keys.add(locks.getString("thermal"));keys.add(locks.getString("config")); }
        else if(p.length==2&&p[0].equals("door")&&catalog.getJSONObject("doors").has(p[1])) {
            JSONObject spec=catalog.getJSONObject("doors").getJSONObject(p[1]);for(String f:keys(spec))if(!f.equals("set"))keys.add(spec.getString(f));
            keys.add(locks.getString("thermal"));keys.add(locks.getString("config"));
        } else if(p.length==2&&p[0].equals("shade")&&catalog.getJSONObject("shades").has(p[1])) {
            JSONObject spec=catalog.getJSONObject("shades").getJSONObject(p[1]);for(String f:List.of("position","initialized","thermal","config"))keys.add(spec.getString(f));
        } else if(p.length==2&&p[0].equals("light")&&catalog.getJSONObject("lights").has(p[1])) {
            JSONObject spec=catalog.getJSONObject("lights").getJSONObject(p[1]);JSONArray states=spec.getJSONArray("states"),configs=spec.getJSONArray("configs");
            for(int i=0;i<states.length();i++)keys.add(states.getString(i));for(int i=0;i<configs.length();i++)keys.add(configs.getJSONArray(i).getString(0));
        } else if(p.length==2&&p[0].equals("massage")&&catalog.getJSONObject("massages").has(p[1])) {
            JSONObject spec=catalog.getJSONObject("massages").getJSONObject(p[1]);for(String f:List.of("config","mode","level"))keys.add(spec.getString(f));
        } else if(p.length==2&&p[0].equals("climate")&&catalog.has("climate")&&catalog.getJSONObject("climate").has(p[1])) {
            JSONObject spec=catalog.getJSONObject("climate").getJSONObject(p[1]);keys.add(spec.getString("state"));JSONArray configs=spec.getJSONArray("configs");
            for(int i=0;i<configs.length();i++)keys.add(configs.getJSONArray(i).getString(0));
        } else if(p.length==3&&(p[0].equals("comfort")||p[0].equals("seat"))&&catalog.getJSONObject("seats").has(p[1])) {
            JSONObject group=catalog.getJSONObject("seats").getJSONObject(p[1]).getJSONObject(p[0].equals("seat")?"axes":"comfort");
            if(group.has(p[2])){JSONObject spec=group.getJSONObject(p[2]);for(String f:p[0].equals("seat")?List.of("position","config","initialized"):List.of("config","state"))if(spec.has(f))keys.add(spec.getString(f));}
        }
        return keys;
    }
    private Map<String,Object> readValues(Map<String,String> reads) throws Exception {
        StringBuilder script=new StringBuilder(identityCommand);
        for(var entry:reads.entrySet()) script.append("; echo @").append(entry.getKey()).append("; ").append(entry.getValue());
        String output=run(script.toString(),8000); Matcher markers=MARKER.matcher(output);
        List<String> found=new ArrayList<>(),raws=new ArrayList<>(); int end=0; String previous=null;
        while(markers.find()) {
            if(previous==null) checkIdentity(output.substring(0,markers.start())); else raws.add(output.substring(end,markers.start()));
            previous=markers.group(1); found.add(previous); end=markers.end();
        }
        if(previous!=null) raws.add(output.substring(end));
        if(!found.equals(new ArrayList<>(reads.keySet()))) throw new IOException("Неполный ответ чтения автомобиля");
        Map<String,Object> values=new HashMap<>();
        for(int i=0;i<found.size();i++) {
            String key=found.get(i); Object value=null;
            try {
                int integer=parseGet(raws.get(i));
                if(catalog.getJSONObject("properties").has(key) && catalog.getJSONObject("properties").getJSONArray(key).getString(2).equals("float")) value=percent(Float.intBitsToFloat(integer));
                else {
                    JSONObject sentinels=catalog.optJSONObject("read_sentinels");JSONArray allowed=sentinels==null?null:sentinels.optJSONArray(key);
                    if(integer>=0||(allowed!=null&&oneOf(integer,allowed)))value=integer;
                }
            } catch(IOException ignored) { /* Unknown remains unknown and fails guards. */ }
            values.put(key,value);
        }
        return values;
    }
    public JSONObject snapshot() throws Exception {
        if(!operation.tryLock()) throw new Busy();
        try { begin(); return buildState(readValues()); } finally { operation.unlock(); }
    }
    JSONObject buildState(Map<String,Object> v) throws Exception {
        String parked=guard(eq(v.get("park"),1),"Управление доступно только в P");
        JSONObject state=object("model","N9","park",v.get("park"),"permit",v.get("permit"),"time",Instant.now().toString(),"pending_stops",new JSONArray(pendingStops),"pending_window_resets",new JSONArray(pendingResets));
        JSONObject windows=new JSONObject();
        for(String key:keys(catalog.getJSONObject("windows"))) {
            JSONArray spec=catalog.getJSONObject("windows").getJSONArray(key); Double position=percent(v.get(key+"_percent"));
            windows.put(key,object("name",spec.getString(0),"percent",position,"initialized",v.get(key+"_initialized"),"thermal",v.get(key+"_thermal"),"blocked",
                first(parked,guard(eq(v.get("permit"),0),"Штатное разрешение управления отсутствует или неизвестно"),guard(eq(v.get(key+"_initialized"),1),"Окно не инициализировано или состояние неизвестно"),guard(eq(v.get(key+"_thermal"),0),"Термозащита активна или состояние неизвестно"),guard(position!=null,"Положение окна неизвестно"))));
        }
        state.put("windows",windows);
        JSONObject locks=catalog.getJSONObject("locks"); Object thermal=v.get(locks.getString("thermal")),config=v.get(locks.getString("config"));
        state.put("locks",object("blocked",first(parked,guard(eq(thermal,0),"Термозащита замков или неизвестное состояние"))));
        JSONObject doors=new JSONObject();
        for(String key:keys(catalog.getJSONObject("doors"))) {
            JSONObject spec=catalog.getJSONObject("doors").getJSONObject(key),door=new JSONObject();
            for(String field:keys(spec)) if(!field.equals("set")) door.put(field,v.get(spec.getString(field))==null?JSONObject.NULL:v.get(spec.getString(field)));
            door.put("name",catalog.getJSONObject("windows").getJSONArray(key).getString(0));
            door.put("blocked",nullable(first(parked,guard(eq(config,5),"Не подтверждены четыре электродвери"),guard(eq(door.opt("initialized"),1),"Дверь не инициализирована"),guard(oneOf(door.opt("position"),new JSONArray(List.of(1,2,3))),"Положение двери неизвестно"),guard(eq(door.opt("obstacle"),0),"Препятствие или неизвестный датчик"),guard(eq(door.opt("protection"),0),"Защита привода или неизвестное состояние"),guard(eq(thermal,0),"Термозащита замков или неизвестное состояние")))); doors.put(key,door);
        }
        state.put("doors",doors);
        JSONObject seats=new JSONObject();
        for(String key:keys(catalog.getJSONObject("seats"))) {
            JSONObject spec=catalog.getJSONObject("seats").getJSONObject(key),axes=new JSONObject(),comfort=new JSONObject();
            for(String axis:keys(spec.getJSONObject("axes"))) {
                JSONObject c=spec.getJSONObject("axes").getJSONObject(axis); boolean available=oneOf(v.get(c.getString("config")),c.getJSONArray("config_values")); Double position=percent(v.get(c.getString("position")));
                String reason=first(parked,guard(pendingStops.isEmpty(),"Сначала остановите последнее кресло: ответ остановки не подтверждён"),guard(available,"Регулировка не подтверждена комплектацией"),guard(position!=null,"Нет достоверного положения"),guard(eq(v.get(c.optString("initialized")),1),"Инициализация не подтверждена"));
                axes.put(axis,object("name",c.getString("name"),"labels",c.getJSONArray("labels"),"supported",available,"percent",position,"blocked",reason));
            }
            for(String kind:keys(spec.getJSONObject("comfort"))) {
                JSONObject c=spec.getJSONObject("comfort").getJSONObject(kind); Object raw=v.get(c.getString("state")); Integer level=oneOf(raw,new JSONArray(List.of(1,2,3,4)))?((Number)raw).intValue()-1:null; boolean available=eq(v.get(c.getString("config")),3);
                comfort.put(kind,object("supported",available,"level",level,"blocked",first(parked,guard(available,"Три уровня не подтверждены комплектацией"),guard(level!=null,"Состояние неизвестно"))));
            }
            seats.put(key,object("name",spec.getString("name"),"axes",axes,"comfort",comfort));
        }
        state.put("seats",seats);
        JSONObject shades=new JSONObject();
        for(String key:keys(catalog.getJSONObject("shades"))) {
            JSONObject spec=catalog.getJSONObject("shades").getJSONObject(key); Double position=percent(v.get(spec.getString("position")));
            shades.put(key,object("name",spec.getString("name"),"percent",position,"blocked",first(parked,guard(eq(v.get(spec.getString("config")),8),"Две шторки не подтверждены комплектацией"),guard(eq(v.get(spec.getString("initialized")),1),"Шторка не инициализирована"),guard(eq(v.get(spec.getString("thermal")),0),"Термозащита или неизвестное состояние"),guard(position!=null,"Положение шторки неизвестно"))));
        }
        state.put("shades",shades);
        JSONObject lights=new JSONObject();
        for(String key:keys(catalog.getJSONObject("lights"))) {
            JSONObject spec=catalog.getJSONObject("lights").getJSONObject(key); boolean supported=true; JSONArray configs=spec.getJSONArray("configs");
            for(int i=0;i<configs.length();i++) { JSONArray c=configs.getJSONArray(i); supported &= oneOf(v.get(c.getString(0)),c.getJSONArray(1)); }
            String current=null; boolean agrees=true; JSONArray states=spec.getJSONArray("states");
            for(int i=0;i<states.length();i++) { Object raw=v.get(states.getString(i)); String decoded=raw==null?null:spec.getJSONObject("state_values").optString(String.valueOf(((Number)raw).intValue()),null); if(i==0) current=decoded; else agrees &= Objects.equals(current,decoded); }
            if(!agrees) current=null;
            lights.put(key,object("name",spec.getString("name"),"supported",supported,"state",current,"actions",new JSONArray(keys(spec.getJSONObject("values"))),"blocked",first(parked,guard(supported,"Функция не подтверждена комплектацией"),guard(current!=null,"Состояние света неизвестно или различается"))));
        }
        state.put("lights",lights);
        JSONObject massages=new JSONObject();
        for(String key:keys(catalog.getJSONObject("massages"))) {
            JSONObject spec=catalog.getJSONObject("massages").getJSONObject(key); Object mode=v.get(spec.getString("mode")),level=v.get(spec.getString("level")); boolean supported=eq(v.get(spec.getString("config")),1); boolean valid=oneOf(mode,new JSONArray(List.of(1,2,3,4,5,6,7))) && oneOf(level,new JSONArray(List.of(0,1,2,3,4)));
            Boolean enabled=null; if(eq(mode,1)||eq(level,4)) enabled=Boolean.FALSE; else if(oneOf(mode,new JSONArray(List.of(2,3,4,5,6)))&&oneOf(level,new JSONArray(List.of(1,2,3)))) enabled=Boolean.TRUE;
            massages.put(key,object("supported",supported,"mode",valid?mode:null,"level",valid?level:null,"enabled",valid?enabled:null,"blocked",first(parked,guard(supported,"Массаж не подтверждён комплектацией"),guard(valid,"Состояние массажа неизвестно"))));
        }
        state.put("massages",massages);
        JSONObject climate=new JSONObject(),specs=catalog.optJSONObject("climate");
        if(specs!=null)for(String key:keys(specs)){
            JSONObject spec=specs.getJSONObject(key);Object raw=v.get(spec.getString("state"));String decoded=null;
            if(raw instanceof Number number){double d=number.doubleValue();String rawKey=d==Math.rint(d)?Long.toString(number.longValue()):Double.toString(d);decoded=spec.getJSONObject("state_values").optString(rawKey,null);}
            boolean supported=true;JSONArray configs=spec.getJSONArray("configs");
            for(int i=0;i<configs.length();i++){JSONArray c=configs.getJSONArray(i);supported &= oneOf(v.get(c.getString(0)),c.getJSONArray(1));}
            climate.put(key,object("name",spec.getString("name"),"state",decoded,"supported",supported,"zone",spec.optString("zone","general"),"order",spec.optInt("order",999),
                "actions",spec.has("options")?spec.getJSONArray("options"):new JSONArray(keys(spec.getJSONObject("values"))),
                "labels",spec.optJSONObject("labels")==null?new JSONObject():spec.getJSONObject("labels"),"presentation",spec.optString("presentation","buttons"),"unit",spec.optString("unit",""),
                "blocked",first(parked,guard(supported,"Функция климата не подтверждена комплектацией"),guard(decoded!=null,"Состояние климата неизвестно"))));
        }
        state.put("climate",climate);return state;
    }
    private static Object nullable(Object value) { return value==null?JSONObject.NULL:value; }
    Command command(String target,String action) throws Exception {
        String[] p=target.split("\\.",-1); String property; int value; boolean pulse=false;
        if(catalog.getJSONObject("windows").has(target) && Set.of("open","close","stop").contains(action))
            return new Command(target,action,setCommand(1001,catalog.getJSONObject("windows").getJSONArray(target).getInt(1),Map.of("open",1,"close",2,"stop",3).get(action)),false);
        Matcher percent=WINDOW_PERCENT.matcher(action);
        if(catalog.getJSONObject("windows").has(target) && percent.matches() && catalog.optJSONObject("window_targets")!=null)
            return new Command(target,action,writeCommand(catalog.getJSONObject("window_targets").getString(target),Integer.parseInt(percent.group(1))),false);
        if(target.equals("locks") && Set.of("lock","unlock").contains(action)) { property=catalog.getJSONObject("locks").getString("set"); value=action.equals("lock")?2:1; }
        else if(p.length==2 && p[0].equals("door") && catalog.getJSONObject("doors").has(p[1]) && Set.of("open","close","stop").contains(action)) { property=catalog.getJSONObject("doors").getJSONObject(p[1]).getString("set"); value=Map.of("open",1,"close",2,"stop",3).get(action); }
        else if(p.length==2 && Set.of("shade","light","climate").contains(p[0])) {
            JSONObject group=catalog.getJSONObject(p[0].equals("shade")?"shades":p[0].equals("light")?"lights":"climate"); if(!group.has(p[1])) throw invalid(); JSONObject c=group.getJSONObject(p[1]);
            if(!c.getJSONObject("values").has(action)) throw invalid(); property=c.getString("set");value=c.getJSONObject("values").getInt(action);
        } else if(p.length==2 && p[0].equals("massage") && catalog.getJSONObject("massages").has(p[1])) {
            JSONObject c=catalog.getJSONObject("massages").getJSONObject(p[1]);
            if(MODES.containsKey(action)) { property=c.getString("set_mode"); value=MODES.get(action); }
            else if(Set.of("level1","level2","level3").contains(action)) { property=c.getString("set_level");value=Integer.parseInt(action.substring(5)); } else throw invalid();
        } else if(p.length==3 && Set.of("seat","comfort").contains(p[0]) && catalog.getJSONObject("seats").has(p[1])) {
            JSONObject group=catalog.getJSONObject("seats").getJSONObject(p[1]).getJSONObject(p[0].equals("seat")?"axes":"comfort");
            if(!group.has(p[2])) throw invalid(); JSONObject c=group.getJSONObject(p[2]); property=c.getString("set");
            if(p[0].equals("seat") && Set.of("plus","minus","stop").contains(action)) { value=Map.of("plus",1,"minus",2,"stop",0).get(action);pulse=!action.equals("stop"); }
            else if(p[0].equals("comfort") && Set.of("0","1","2","3").contains(action)) value=c.getJSONArray("values").getInt(Integer.parseInt(action)); else throw invalid();
        } else throw invalid();
        return new Command(target,action,writeCommand(property,value),pulse);
    }
    private static IOException invalid() { return new IOException("Неизвестный контрол или действие"); }
    /** Stock MyCar WindowManager.setWindowPercent: target N, then 255 («нет цели») 300 ms later. */
    boolean windowTarget(Command command) throws JSONException { return catalog.getJSONObject("windows").has(command.target()) && WINDOW_PERCENT.matcher(command.action()).matches(); }
    String windowTargetReset(String target) throws JSONException {
        JSONObject targets=catalog.getJSONObject("window_targets");return writeCommand(targets.getString(target),targets.getInt("reset"));
    }
    String reason(JSONObject state,String target) throws Exception {
        String[] p=target.split("\\."); JSONObject control;
        if(catalog.getJSONObject("windows").has(target)) control=state.getJSONObject("windows").getJSONObject(target);
        else if(target.equals("locks")) control=state.getJSONObject("locks");
        else if(p[0].equals("seat") || p[0].equals("comfort")) control=state.getJSONObject("seats").getJSONObject(p[1]).getJSONObject(p[0].equals("seat")?"axes":"comfort").getJSONObject(p[2]);
        else control=state.getJSONObject(Map.of("door","doors","shade","shades","light","lights","massage","massages","climate","climate").get(p[0])).getJSONObject(p[1]);
        return control.isNull("blocked")?null:control.getString("blocked");
    }
    public JSONObject act(String target,String action) throws Exception { return act(target,action,()->true); }
    JSONObject act(String target,String action,java.util.function.BooleanSupplier foreground) throws Exception {
        Command command=command(target,action); // validate before any transport.
        if(command.pulse()) throw new IOException("Для движения кресла удерживайте кнопку");
        if(!operation.tryLock()) throw new Busy();
        try {
            if(!foreground.getAsBoolean()&&!action.equals("stop")) throw new IOException("Действие отменено: пульт не на экране");
            begin();
            if(action.equals("stop")) checkIdentity(run(identityCommand,5000));
            else { String reason=reason(buildState(readValues()),target); if(reason!=null) throw new Refused(reason); }
            if(!foreground.getAsBoolean()&&!action.equals("stop")) throw new IOException("Действие отменено: пульт не на экране");
            if(pendingResets.contains(target)) {
                // An earlier percent target may still be set: clear it first and never resend that target.
                // STOP is sent even when the clearing fails: stopping a window must stay possible.
                JSONObject cleared=clearWindowTarget(target);
                if(!cleared.optBoolean("reset_accepted")&&!action.equals("stop")) return cleared;
                if(!foreground.getAsBoolean()&&!action.equals("stop")) throw new IOException("Действие отменено: пульт не на экране");
            }
            JSONObject entry=object("time",Instant.now().toString(),"target",target,"action",action,"command",command.shell(),"phase","attempt");
            record(entry);
            boolean percent=windowTarget(command);
            if(percent) pendingResets.add(target); // mirrors the journalled attempt until a reset is accepted
            JSONObject result;
            try {
                String raw=run(command.shell(),5000);
                int code=parseSet(raw);result=object("code",code,"accepted",code==1,"raw",raw);
            } catch(Exception error) { result=object("accepted",false,"uncertain",true,"error","Исход неизвестен; проверьте машину. Команда не повторялась: "+error.getMessage()); }
            if(percent) {
                // The reset follows every target attempt, as in the stock app; it is never a retry of the target.
                try { Thread.sleep(catalog.getJSONObject("window_targets").getInt("reset_delay_ms")); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                String reset=windowTargetReset(target);result.put("reset_command",reset);
                record(object("time",Instant.now().toString(),"target",target,"action","reset","command",reset,"phase","reset_attempt"));
                try { String raw=run(reset,5000);int code=parseSet(raw);result.put("reset_code",code);result.put("reset_accepted",code==1);result.put("reset_raw",raw); }
                catch(Exception error) { result.put("reset_accepted",false);result.put("reset_error",String.valueOf(error.getMessage())); }
                if(result.optBoolean("accepted")&&!result.optBoolean("reset_accepted")) {
                    // An accepted target that may still be set is not a known outcome: the window can keep moving.
                    result.put("accepted",false);result.put("uncertain",true);
                    result.put("error","Цель окна принята, но её сброс не подтверждён; проверьте окно. Команда не повторялась");
                }
            }
            JSONObject logged=new JSONObject(entry.toString());logged.put("phase","result");for(String key:keys(result)) logged.put(key,result.get(key));
            record(logged); // Only clear latch after STOP acknowledgement is durably retained.
            if(result.optBoolean("stop_accepted") || (action.equals("stop") && result.optBoolean("accepted"))) pendingStops.remove(target);
            if(percent && result.optBoolean("reset_accepted")) pendingResets.remove(target);
            return result;
        } finally { operation.unlock(); }
    }
    /** One planned write; a {@code delta:} action is resolved from the plan's reading. */
    public record Planned(String target,String action) { }
    public interface Resolver { Planned resolve(Planned planned,JSONObject state) throws Exception; }

    /**
     * A whole plan at once. One lock (waiting up to {@code waitMs} for a remote poll in progress), one
     * connection, one reading of only the plan's guard properties, one shell script with every write and,
     * 300 ms later, the window target resets. Each entry gets its own result: refused before transport,
     * accepted, refused by the car, or uncertain. A transport failure makes every sent entry uncertain;
     * nothing is retried. Per-command reads of the whole catalog took about 2 s per command on the car.
     */
    List<JSONObject> actAll(List<Planned> plan,java.util.function.BooleanSupplier foreground,Resolver resolver,long waitMs) throws Exception {
        for(Planned planned:plan) if(!planned.action().startsWith("delta:")&&command(planned.target(),planned.action()).pulse())
            throw new IOException("Для движения кресла удерживайте кнопку");
        if(!operation.tryLock(waitMs,java.util.concurrent.TimeUnit.MILLISECONDS)) throw new Busy();
        try {
            if(!foreground.getAsBoolean()) throw new IOException("Действие отменено; ничего не отправлено");
            begin();
            LinkedHashSet<String> targets=new LinkedHashSet<>();for(Planned planned:plan)targets.add(planned.target());
            JSONObject state=buildState(readValues(readsFor(targets)));
            List<JSONObject> results=new ArrayList<>();List<Command> commands=new ArrayList<>();List<Integer> send=new ArrayList<>();
            for(Planned planned:plan) {
                JSONObject result=object("target",planned.target(),"action",planned.action());results.add(result);commands.add(null);
                try {
                    Planned resolved=planned.action().startsWith("delta:")?resolver.resolve(planned,state):planned;
                    result.put("action",resolved.action());
                    Command command=command(resolved.target(),resolved.action());
                    String reason=resolved.action().equals("stop")?null:reason(state,resolved.target());
                    if(reason!=null){result.put("refused",true);result.put("error",reason);continue;}
                    commands.set(commands.size()-1,command);send.add(results.size()-1);
                } catch(Exception refusal) { result.put("refused",true);result.put("error",refusal.getMessage()==null?"Действие недоступно":refusal.getMessage()); }
            }
            if(send.isEmpty()) return results;
            if(!foreground.getAsBoolean()) { for(int i:send){results.get(i).put("cancelled",true);results.get(i).put("error","Отменено; команда не отправлена");} return results; }
            for(Iterator<Integer> it=send.iterator();it.hasNext();) {
                int i=it.next();Command command=commands.get(i);
                if(!pendingResets.contains(command.target())) continue;
                JSONObject cleared=clearWindowTarget(command.target());
                if(!cleared.optBoolean("reset_accepted")&&!command.action().equals("stop")) { for(String key:keys(cleared))results.get(i).put(key,cleared.get(key)); it.remove(); }
            }
            if(send.isEmpty()) return results;
            StringBuilder script=new StringBuilder();List<JSONObject> entries=new ArrayList<>();boolean anyTarget=false;
            for(int i:send) {
                Command command=commands.get(i);
                JSONObject entry=object("time",Instant.now().toString(),"target",command.target(),"action",command.action(),"command",command.shell(),"phase","attempt","batch",send.size());
                record(entry);entries.add(entry);
                if(windowTarget(command)){pendingResets.add(command.target());anyTarget=true;}
                if(script.length()>0)script.append("; ");
                script.append("echo @w").append(i).append("; ").append(command.shell());
            }
            if(anyTarget) {
                script.append("; sleep ").append(catalog.getJSONObject("window_targets").getInt("reset_delay_ms")/1000.0);
                for(int i:send) {
                    Command command=commands.get(i);if(!windowTarget(command))continue;
                    String reset=windowTargetReset(command.target());results.get(i).put("reset_command",reset);
                    record(object("time",Instant.now().toString(),"target",command.target(),"action","reset","command",reset,"phase","reset_attempt"));
                    script.append("; echo @r").append(i).append("; ").append(reset);
                }
            }
            Map<String,String> parts=new HashMap<>();String transport=null;
            try {
                String output=run(script.toString(),5000+1000*send.size());Matcher markers=MARKER.matcher(output+"\n");String label=null;int end=0;
                while(markers.find()){if(label!=null)parts.put(label,output.substring(end,Math.min(markers.start(),output.length())));label=markers.group(1);end=markers.end();}
                if(label!=null&&end<=output.length())parts.put(label,output.substring(end));
            } catch(Exception failure) { transport=String.valueOf(failure.getMessage()); }
            for(int n=0;n<send.size();n++) {
                int i=send.get(n);Command command=commands.get(i);JSONObject result=results.get(i);
                try { String raw=parts.get("w"+i);if(raw==null)throw new IOException("нет ответа");int code=parseSet(raw);result.put("code",code);result.put("accepted",code==1);result.put("raw",raw.trim()); }
                catch(Exception unknown) { result.put("accepted",false);result.put("uncertain",true);result.put("error","Исход неизвестен; проверьте машину. Команда не повторялась"+(transport==null?"":": "+transport)); }
                if(windowTarget(command)) {
                    try { String raw=parts.get("r"+i);if(raw==null)throw new IOException("нет ответа");int code=parseSet(raw);result.put("reset_code",code);result.put("reset_accepted",code==1); }
                    catch(Exception unknown) { result.put("reset_accepted",false); }
                    if(result.optBoolean("accepted")&&!result.optBoolean("reset_accepted")) {
                        result.put("accepted",false);result.put("uncertain",true);
                        result.put("error","Цель окна принята, но её сброс не подтверждён; проверьте окно. Команда не повторялась");
                    }
                    if(result.optBoolean("reset_accepted")) pendingResets.remove(command.target());
                }
                JSONObject logged=new JSONObject(entries.get(n).toString());logged.put("phase","result");
                for(String key:keys(result)) if(!key.equals("target")&&!key.equals("action")) logged.put(key,result.get(key));
                record(logged);
                if(command.action().equals("stop")&&result.optBoolean("accepted")) pendingStops.remove(command.target());
            }
            return results;
        } finally { operation.unlock(); }
    }
    /**
     * The 255 reset alone for a window whose earlier target is unconfirmed, journalled before and after the write.
     * The latch clears only on an accepted reset; otherwise the result refuses the window action.
     */
    private JSONObject clearWindowTarget(String target) throws Exception {
        String reset=windowTargetReset(target);
        JSONObject entry=object("time",Instant.now().toString(),"target",target,"action","reset","command",reset,"phase","reset_attempt");
        record(entry);
        JSONObject result=object("reset_required",true,"reset_command",reset);
        try { String raw=run(reset,5000);int code=parseSet(raw);result.put("reset_code",code);result.put("reset_accepted",code==1);result.put("reset_raw",raw); }
        catch(Exception error) { result.put("reset_accepted",false);result.put("uncertain",true);result.put("reset_error",String.valueOf(error.getMessage())); }
        JSONObject logged=new JSONObject(entry.toString());logged.put("phase","reset_result");for(String key:keys(result)) logged.put(key,result.get(key));
        record(logged);
        if(result.optBoolean("reset_accepted")) { pendingResets.remove(target); return result; }
        result.put("accepted",false);
        result.put("error",result.optBoolean("uncertain")?"Исход сброса прежней цели окна неизвестен; команда окна не отправлена. Проверьте окно"
                :"Сброс прежней цели окна не принят; команда окна не отправлена");
        return result;
    }
    public static final class Hold {
        final String target, action, token;
        final AtomicBoolean released=new AtomicBoolean(), releaseSent=new AtomicBoolean();
        Hold(String target,String action) {
            this.target=target;this.action=action;
            token="/data/local/tmp/denza-remote-seat-"+UUID.randomUUID().toString().replace("-","");
        }
    }
    Hold newHold(String target,String action) throws Exception {
        Command command=command(target,action);if(!command.pulse()) throw invalid();return new Hold(target,action);
    }
    static String holdScript(String start,String stop,String token) {
        if(!token.matches("/data/local/tmp/denza-remote-seat-[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid release token");
        // 4.8s watchdog leaves one polling interval before the displayed 5s bound.
        // Refresh held intent only after an exact single HAL1; unknown/refused exits to STOP.
        String accepted="^Result: Parcel\\(00000001[[:space:]]+'....'\\)[[:space:]]*$";
        return "sleep 4.8 & deadline=$!; finish() { trap - EXIT HUP INT TERM PIPE; kill \"$deadline\" 2>/dev/null; stop_reply=$("+stop+"); rm -f "+token+
            "; printf '@stop\\n%s\\n' \"$stop_reply\"; }; trap finish EXIT; trap 'exit 1' HUP INT TERM PIPE; send() { reply=$("+start+
            "); printf '%s\\n' \"$reply\"; [ \"$(printf '%s\\n' \"$reply\" | wc -l)\" -eq 1 ] && printf '%s\\n' \"$reply\" | grep -Eq \""+accepted+"\"; }; "+
            "if [ -e "+token+" ]; then echo @cancel; exit; fi; echo @start; send || exit; i=0; "+
            "while kill -0 \"$deadline\" 2>/dev/null && [ ! -e "+token+" ]; do sleep 0.1; i=$((i+1)); "+
            "if [ $((i%3)) -eq 0 ] && kill -0 \"$deadline\" 2>/dev/null && [ ! -e "+token+" ]; then echo @keepalive; send || exit; fi; done";
    }
    static JSONObject holdResult(String raw) throws Exception {
        Matcher markers=MARKER.matcher(raw+"\n");List<String> labels=new ArrayList<>(),parts=new ArrayList<>();int end=0;
        while(markers.find()){if(labels.isEmpty()&&!raw.substring(0,markers.start()).trim().isEmpty())throw new IOException("Лишний ответ удержания");if(!labels.isEmpty())parts.add(raw.substring(end,markers.start()));labels.add(markers.group(1));end=markers.end();}
        if(labels.isEmpty())throw new IOException("Нет ответа удержания");parts.add((raw+"\n").substring(end));
        if(!labels.get(labels.size()-1).equals("stop"))throw new IOException("Нет ответа STOP");
        int stop=parseSet(parts.get(parts.size()-1));boolean cancelled=labels.get(0).equals("cancel"),accepted=!cancelled;int code=-1;boolean uncertain=false;
        if(cancelled){if(labels.size()!=2||!parts.get(0).trim().isEmpty())throw new IOException("Некорректный ответ отмены");}
        else {
            if(!labels.get(0).equals("start"))throw new IOException("Нет ответа START");
            for(int i=0;i<labels.size()-1;i++){
                if(i>0&&!labels.get(i).equals("keepalive"))throw new IOException("Некорректный ответ удержания");
                try{int value=parseSet(parts.get(i));if(i==0)code=value;accepted &= value==1;}catch(IOException error){accepted=false;uncertain=true;}
            }
        }
        return object("accepted",accepted&&stop==1,"cancelled",cancelled,"uncertain",uncertain,"code",code,"stop_code",stop,"stop_accepted",stop==1,"raw",raw);
    }
    /** Separate connection used only for release signalling, never serialized behind a long hold. */
    void release(Hold hold) throws Exception {
        hold.released.set(true);
        if(hold.releaseSent.compareAndSet(false,true))
            provider.open().shell().run("umask 077; : > "+hold.token,2000);
    }
    JSONObject hold(Hold hold) throws Exception {
        if(!operation.tryLock()) throw new Busy();
        try {
            if(hold.released.get()) return object("accepted",false,"cancelled",true);
            begin();
            String reason=reason(buildState(readValues()),hold.target);if(reason!=null) throw new IOException(reason);
            if(hold.released.get()) return object("accepted",false,"cancelled",true);
            Command start=command(hold.target,hold.action),stop=command(hold.target,"stop");
            String script=holdScript(start.shell(),stop.shell(),hold.token);
            JSONObject entry=object("time",Instant.now().toString(),"target",hold.target,"action",hold.action,"command",script,"phase","attempt","mode","hold-max5s");
            record(entry);pendingStops.add(hold.target);
            JSONObject result;
            try {
                // Connection is dedicated to this hold; release() opens another client.
                // A release during journal fsync must not turn into a later START.
                String selected=hold.released.get()?"stop_reply=$("+stop.shell()+"); printf '@cancel\\n@stop\\n%s\\n' \"$stop_reply\"":script;
                String raw=run(selected,8000);
                result=holdResult(raw);
            } catch(Exception error) { result=object("accepted",false,"uncertain",true,"error","Исход неизвестен; проверьте остановку кресла. START не повторялся: "+error.getMessage()); }
            JSONObject logged=new JSONObject(entry.toString());logged.put("phase","result");for(String key:keys(result)) logged.put(key,result.get(key));record(logged);
            if(result.optBoolean("stop_accepted")) pendingStops.remove(hold.target);
            return result;
        } finally { operation.unlock(); }
    }
    private void record(JSONObject row) throws Exception {
        File parent=journal.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs()) throw new IOException("Не удалось создать журнал действий");
        try(FileOutputStream stream=new FileOutputStream(journal,true)) { stream.write((row+"\n").getBytes(StandardCharsets.UTF_8));stream.flush();stream.getFD().sync(); }
    }
    private void replay() throws Exception {
        if(!journal.exists()) return;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(journal),StandardCharsets.UTF_8))) {
            String line;while((line=reader.readLine())!=null) {
                try { JSONObject row=new JSONObject(line);String target=row.optString("target");
                    if(catalog.getJSONObject("windows").has(target)) {
                        // A percent target attempt latches until a later accepted 255 reset for the same window.
                        if(row.optString("phase").equals("attempt")&&WINDOW_PERCENT.matcher(row.optString("action")).matches()) pendingResets.add(target);
                        else if(row.optBoolean("reset_accepted")) pendingResets.remove(target);
                        continue;
                    }
                    if(!target.startsWith("seat.")) continue;
                    if(row.optString("phase").equals("attempt")&&!row.optString("action").equals("stop")) pendingStops.add(target);
                    else if(row.optBoolean("stop_accepted")||(row.optString("action").equals("stop")&&row.optBoolean("accepted"))) pendingStops.remove(target);
                } catch(JSONException ignored) { /* match existing journal replay of truncated lines */ }
            }
        }
    }
}
