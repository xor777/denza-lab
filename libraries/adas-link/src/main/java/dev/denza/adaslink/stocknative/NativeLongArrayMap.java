package dev.denza.adaslink.stocknative;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Android ArrayMap ordering for the original lifecycle's Long service keys.
 * ArrayMap stores signed key hashes in ascending order; equal hashes retain
 * insertion order, and replacing a key preserves its existing position.
 */
final class NativeLongArrayMap extends AbstractMap<Long,Long[]> {
    private final ArrayList<Map.Entry<Long,Long[]>> entries=new ArrayList<>();
    NativeLongArrayMap(){}
    NativeLongArrayMap(Map<Long,Long[]> source){putAll(source);}
    @Override public Long[] put(Long key,Long[] value){
        for(Map.Entry<Long,Long[]> entry:entries)if(entry.getKey().equals(key))return entry.setValue(value);
        int hash=key.hashCode(),index=0;while(index<entries.size()&&entries.get(index).getKey().hashCode()<=hash)index++;
        entries.add(index,new SimpleEntry<>(key,value));return null;
    }
    @Override public Set<Map.Entry<Long,Long[]>> entrySet(){return new AbstractSet<Map.Entry<Long,Long[]>>(){public int size(){return entries.size();}public Iterator<Map.Entry<Long,Long[]>> iterator(){return entries.iterator();}};}
}
