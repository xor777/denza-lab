package dev.denza.adaslink.stocknative;

import dev.denza.adaslink.Json;
import java.lang.reflect.*;
import java.util.*;

/** Gson serializeNulls/disableHtmlEscaping with ART's field-name ordering; transient/static fields excluded. */
final class NativeJson {
    static Object tree(Object value) {
        if(value instanceof Float)return new Json.F((Float)value);
        if(value==null || value instanceof String || value instanceof Boolean || value instanceof Number)return value;
        if(value instanceof Map)return value;
        if(value instanceof Iterable){List<Object>out=new ArrayList<>();for(Object item:(Iterable<?>)value)out.add(tree(item));return out;}
        if(value.getClass().isArray()){List<Object>out=new ArrayList<>();for(int i=0;i<Array.getLength(value);i++)out.add(tree(Array.get(value,i)));return out;}
        List<Field> fields=new ArrayList<>();for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())fields.addAll(Arrays.asList(c.getDeclaredFields()));
        fields.sort(Comparator.comparing(Field::getName));Map<String,Object> out=Json.object();
        for(Field field:fields){if(Modifier.isStatic(field.getModifiers())||Modifier.isTransient(field.getModifiers())||field.isSynthetic())continue;
            field.setAccessible(true);try{out.put(field.isAnnotationPresent(WireName.class)?field.getAnnotation(WireName.class).value():field.getName(),tree(field.get(value)));}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
        return out;
    }
    static String write(Object value){return Json.write(tree(value));}
    private NativeJson(){}
}
