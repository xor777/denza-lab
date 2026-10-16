package dev.denza.vehiclecontrol.probe;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
final class FakeVehicle implements NativeController.Shell {
    static final String OK="Result: Parcel(00000001    '....')";
    static String reply(int value){return String.format("Result: Parcel(00000000 %08x   '........')",value);}
        final JSONObject catalog;final Map<String,Object> values=new HashMap<>();final Map<String,String> rawOverrides=new HashMap<>();final List<String>writes=new ArrayList<>();
        volatile String readError;
        String boot="11111111-1111-1111-1111-111111111111",setReply=OK,holdReply="@start\n"+OK+"\n@stop\n"+OK;boolean badHash;int opens;
        CountDownLatch readStarted,readRelease,holdStarted,holdRelease;
        FakeVehicle(JSONObject catalog)throws Exception{this.catalog=catalog;
            JSONObject properties=catalog.getJSONObject("properties");for(String name:NativeController.keys(properties))values.put(name,properties.getJSONArray(name).getString(2).equals("float")?42.0:1);
            values.put("park",1);values.put("permit",0);
            for(String k:List.of("fl","fr","rl","rr")){values.put(k+"_percent",42);values.put(k+"_initialized",1);values.put(k+"_thermal",0);}
            JSONObject locks=catalog.getJSONObject("locks");values.put(locks.getString("config"),5);values.put(locks.getString("thermal"),0);
            for(String key:NativeController.keys(catalog.getJSONObject("doors"))){JSONObject c=catalog.getJSONObject("doors").getJSONObject(key);values.put(c.getString("position"),2);values.put(c.getString("obstacle"),0);values.put(c.getString("protection"),0);}
            for(String key:NativeController.keys(catalog.getJSONObject("seats"))){JSONObject s=catalog.getJSONObject("seats").getJSONObject(key);for(String axis:NativeController.keys(s.getJSONObject("axes"))){JSONObject c=s.getJSONObject("axes").getJSONObject(axis);JSONArray allowed=c.getJSONArray("config_values");values.put(c.getString("config"),allowed.getInt(allowed.length()-1));}for(String kind:NativeController.keys(s.getJSONObject("comfort"))){JSONObject c=s.getJSONObject("comfort").getJSONObject(kind);values.put(c.getString("config"),3);}}
            for(String key:NativeController.keys(catalog.getJSONObject("shades"))){JSONObject c=catalog.getJSONObject("shades").getJSONObject(key);values.put(c.getString("config"),8);values.put(c.getString("position"),0);values.put(c.getString("thermal"),0);}
            for(String key:NativeController.keys(catalog.getJSONObject("lights"))){JSONObject c=catalog.getJSONObject("lights").getJSONObject(key);JSONArray configs=c.getJSONArray("configs"),states=c.getJSONArray("states");for(int i=0;i<configs.length();i++)values.put(configs.getJSONArray(i).getString(0),configs.getJSONArray(i).getJSONArray(1).getInt(0));for(int i=0;i<states.length();i++)values.put(states.getString(i),1);}
            if(catalog.has("climate"))for(String key:NativeController.keys(catalog.getJSONObject("climate"))){JSONObject c=catalog.getJSONObject("climate").getJSONObject(key);JSONArray configs=c.getJSONArray("configs");for(int i=0;i<configs.length();i++){JSONArray config=configs.getJSONArray(i);values.put(config.getString(0),config.getJSONArray(1).getInt(0));}values.put(c.getString("state"),Integer.parseInt(NativeController.keys(c.getJSONObject("state_values")).get(0)));}
            for(String key:NativeController.keys(catalog.getJSONObject("massages"))){JSONObject c=catalog.getJSONObject("massages").getJSONObject(key);values.put(c.getString("mode"),1);values.put(c.getString("level"),0);}
        }
        String identity(){return "CANFD\n168\n"+boot;}
        public String run(String command,int timeout)throws Exception{
            if(command.startsWith("umask")){if(holdRelease!=null)holdRelease.countDown();return "";}
            if(command.startsWith("sleep 4.8")){writes.add(command);if(holdStarted!=null){holdStarted.countDown();assertTrue(holdRelease.await(2,TimeUnit.SECONDS));}return holdReply;}
            if(command.startsWith("service call autoservice 6")){writes.add(command);return setReply;}
            if(command.contains("sha256sum")){StringBuilder out=new StringBuilder(identity());JSONObject h=catalog.getJSONObject("protocol").getJSONObject("hashes");for(String path:NativeController.keys(h))out.append('\n').append(badHash?"bad":h.getString(path)).append("  ").append(path);return out.toString();}
            if(command.equals(catalog.getJSONObject("protocol").getString("identity")))return identity();
            if(readStarted!=null){readStarted.countDown();assertTrue(readRelease.await(5,TimeUnit.SECONDS));}
            if(readError!=null)throw new java.io.IOException(readError);
            StringBuilder out=new StringBuilder(identity());Matcher m=Pattern.compile("echo @(\\w+); service call autoservice ([57]) i32 (\\d+) i32 (-?\\d+)").matcher(command);
            while(m.find()){Object value=values.get(m.group(1));int raw=value==null?-10011:m.group(2).equals("7")?Float.floatToIntBits(((Number)value).floatValue()):((Number)value).intValue();out.append('\n').append('@').append(m.group(1)).append('\n').append(rawOverrides.getOrDefault(m.group(1),reply(raw)));}
            return out.toString();
        }
    }
