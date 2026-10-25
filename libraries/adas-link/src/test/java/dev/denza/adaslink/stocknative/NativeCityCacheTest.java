package dev.denza.adaslink.stocknative;

import dev.denza.adaslink.Json;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Parser compatibility fixtures generated with Gson 2.9.1 matching the APK dependency.
 * These library-generated expectations are not original-APK execution evidence. */
public final class NativeCityCacheTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Storage {
        String value,written;int reads;
        Storage(String value){this.value=value;}
        NativeEnvironment environment(){return (NativeEnvironment)Proxy.newProxyInstance(
            NativeEnvironment.class.getClassLoader(),new Class<?>[]{NativeEnvironment.class},(o,m,a)->{
                if(m.getName().equals("cachedOpenCityJson")){reads++;return value;}
                if(m.getName().equals("writeCachedOpenCityJson")){written=(String)a[0];value=written;return null;}
                throw new AssertionError("Unexpected city-cache dependency "+m.getName());
            });}
    }
    @Test public void originalGsonIntegerArrayDomain()throws Exception{runChecks();}
    public static void main(String[] args)throws Exception{runChecks();System.out.println("NativeCityCacheTest: "+checks+" checks passed");}
    private static void runChecks()throws Exception{
        checks=0;oracleCases();cacheLifetimeAndWriter();
    }
    @SuppressWarnings("unchecked") private static void oracleCases()throws Exception{
        String path="/dev/denza/adaslink/stocknative/city-cache-gson-2.9.1.json";
        InputStream input=NativeCityCacheTest.class.getResourceAsStream(path);
        if(input==null)throw new AssertionError("Missing pinned Gson2.9.1 oracle "+path);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try{byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);}finally{input.close();}
        Map<String,Object> fixture=(Map<String,Object>)Json.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        List<Object> cases=(List<Object>)fixture.get("cases");
        check(cases.size()==74,"pinned original-Gson oracle case count");
        for(Object raw:cases){
            Map<String,Object> row=(Map<String,Object>)raw;
            Storage storage=new Storage((String)row.get("input"));NativeCityCache cache=new NativeCityCache(storage.environment());
            Set<Integer> actual=null;RuntimeException failure=null;
            try{actual=cache.i();}catch(RuntimeException error){failure=error;}
            boolean success=Boolean.TRUE.equals(row.get("success"));
            check(success==(failure==null),"Gson accepted-domain mismatch: "+row.get("input"));
            if(success){
                LinkedHashSet<Integer> expected=new LinkedHashSet<>();
                for(Object value:(List<Object>)row.get("values"))expected.add(value==null?null:((Number)value).intValue());
                check(new ArrayList<>(actual).equals(new ArrayList<>(expected)),"Gson computed integer values/order differ: "+row.get("input"));
                check(cache.i()==actual,"city-cache set identity persists");
            }else check(cache.i().isEmpty(),"parse/conversion failure must not install a partial set");
            check(storage.reads==1,"source attempted flag forbids a second read after success or failure");
        }
    }
    private static void cacheLifetimeAndWriter(){
        Storage storage=new Storage("[999]");NativeCityCache cache=new NativeCityCache(storage.environment());
        cache.a(Arrays.asList(110000,null,110000));
        check("[\n  110000,\n  null,\n  110000\n]".equals(storage.written),"original pretty writer retains list nulls and duplicates");
        check(new ArrayList<>(cache.i()).equals(Arrays.asList(110000,null)),"source set deduplicates the list in insertion order");
        check(storage.reads==0,"populated process set does not load the cache");
        cache.a(new ArrayList<Integer>());
        check("[]".equals(storage.written),"original empty-list encoding");
        check(cache.i().isEmpty()&&storage.reads==1,"empty process set loads once while attempt flag remains false");
        storage.value="[123]";
        check(cache.i().isEmpty()&&storage.reads==1,"m.b-style route reset cannot implicitly reload an attempted cache");
        cache.a(Arrays.asList(7,8));
        check(new ArrayList<>(cache.i()).equals(Arrays.asList(7,8)),"explicit city input updates the persistent set after an attempted load");
    }
}
