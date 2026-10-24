package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only catalog of the downloaded vmap3fb container. Payload decoding is supplied by the
 * owner; this reader never executes a native SDK method or changes the renderer's camera.
 * Native catalog: MultiRegionMapData::subscribeForTile 0x1b63068, key comparator 0x1b608f4,
 * payload selector 0x1b65e34 in pinned libmaps-mobile c2842633cc3db830... . */
public final class OfflineVectorPackage {
    public static final int MAX_FILE_BYTES=512*1024*1024, MAX_TILES=1000000, MAX_COMPRESSED_BYTES=2*1024*1024;
    public static final int MAX_DICTIONARY_BYTES=2*1024*1024, MAX_DECODED_BYTES=2*1024*1024;
    public interface Decoder { byte[] decode(byte[] compressed,byte[] dictionary,int maximumOutputBytes); }
    public static final class Entry {
        public final VectorRoadTile.TileId tile;
        public final long packedId;
        public final String etag;
        public final int compressedBytes;
        private final int payload;
        private Entry(VectorRoadTile.TileId tile,long packed,String etag,int payload,int bytes) {
            this.tile=tile;packedId=packed;this.etag=etag;this.payload=payload;compressedBytes=bytes;
        }
    }
    private final ByteBuffer data;
    private final int vector, count, dictionary, dictionarySize;
    public final String language,version,format,buildTimestamp;

    /** The caller keeps the backing bytes immutable for this reader's lifetime. */
    public OfflineVectorPackage(ByteBuffer input) {
        if(input==null||input.remaining()<8||input.remaining()>MAX_FILE_BYTES)throw bad("file byte budget");
        data=input.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int root=offset(u32(0));language=text(root,0);buildTimestamp=text(root,1);format=text(root,2);version=text(root,3);
        if(!"vmap3".equals(format)||version.isEmpty())throw bad("unsupported vector package identity");
        int codec=field(root,10,1);
        if(codec==0||(data.get(codec)&255)!=1)throw bad("unsupported vector compression codec");
        vector=ref(root,4);count=bounded(u32(vector),MAX_TILES,"tile catalog");span(vector+4,4L*count);
        dictionary=ref(root,11);dictionarySize=bounded(u32(dictionary),MAX_DICTIONARY_BYTES,"dictionary");span(dictionary+4,dictionarySize);
        long previous=-1;
        for(int i=0;i<count;i++) {
            Entry entry=entry(i);
            if(entry.packedId<=previous)throw bad("tile catalog ordering/duplicate");
            previous=entry.packedId;
        }
    }
    public int size() { return count; }
    public Entry entry(int index) {
        if(index<0||index>=count)throw new IndexOutOfBoundsException("tile catalog index");
        int at=vector+4+4*index,table=relative(at),keyAt=field(table,0,8);
        long key=keyAt==0?0:i64(keyAt);
        VectorRoadTile.TileId tile=unpack(key);
        int payload=ref(table,2),length=bounded(u32(payload),MAX_COMPRESSED_BYTES,"compressed tile");span(payload+4,length);
        return new Entry(tile,key,text(table,1),payload+4,length);
    }
    public Entry find(VectorRoadTile.TileId tile) {
        long target=pack(tile);int low=0,high=count-1;
        while(low<=high) {
            int mid=(low+high)>>>1;Entry entry=entry(mid);
            if(entry.packedId==target)return entry;
            if(entry.packedId<target)low=mid+1;else high=mid-1;
        }
        return null;
    }
    public byte[] dictionary() { return bytes(dictionary+4,dictionarySize); }
    public byte[] compressed(Entry entry) {
        if(entry==null)throw bad("tile entry");
        Entry own=find(entry.tile);
        if(own==null||own.payload!=entry.payload||own.compressedBytes!=entry.compressedBytes||!own.etag.equals(entry.etag))throw bad("foreign tile entry");
        return bytes(own.payload,own.compressedBytes);
    }
    public VectorRoadTile decode(VectorRoadTile.TileId tile,Decoder decoder) {
        if(decoder==null)throw bad("payload decoder required");Entry entry=find(tile);if(entry==null)return null;
        byte[] raw=decoder.decode(compressed(entry),dictionary(),MAX_DECODED_BYTES);
        if(raw==null||raw.length==0||raw.length>MAX_DECODED_BYTES)throw bad("decoded tile byte budget");
        return VectorRoadTile.decode(raw,tile,tile,tile,new VectorRoadTile.Limits());
    }
    public Map<String,Object> summary() {
        Map<String,Object> m=new LinkedHashMap<>();m.put("schema","denza-yandex-offline-vector-package-v1");
        m.put("format",format);m.put("version",version);m.put("language",language);m.put("buildTimestamp",buildTimestamp);
        m.put("tiles",count);m.put("dictionaryBytes",dictionarySize);m.put("fileBytes",data.limit());return Collections.unmodifiableMap(m);
    }
    public static long pack(VectorRoadTile.TileId tile) {
        if(tile==null||tile.z<0||tile.z>29||tile.x<0||tile.y<0||tile.x>=(1L<<tile.z)||tile.y>=(1L<<tile.z))throw bad("tile coordinates");
        return ((long)tile.z<<58)|((long)tile.x<<29)|tile.y;
    }
    public static VectorRoadTile.TileId unpack(long key) {
        int zoom=(int)(key>>>58),x=(int)((key>>>29)&0x1fffffffL),y=(int)(key&0x1fffffffL);
        if(key<0||zoom>29||x>=(1L<<zoom)||y>=(1L<<zoom))throw bad("packed tile coordinates");
        return new VectorRoadTile.TileId(x,y,zoom);
    }
    private int field(int table,int index,int width) {
        span(table,4);long vt=(long)table-data.getInt(table);
        if(vt<0||vt>Integer.MAX_VALUE)throw bad("vtable offset");int v=(int)vt;span(v,4);
        int length=data.getShort(v)&65535,object=data.getShort(v+2)&65535;
        if(length<4||length>128||(length&1)!=0||object<4||object>4096)throw bad("vtable header");
        span(v,length);span(table,object);if(4+index*2>=length)return 0;
        int offset=data.getShort(v+4+index*2)&65535;if(offset==0)return 0;
        if(offset<4||offset>object-width)throw bad("field outside table");return table+offset;
    }
    private int ref(int table,int index) { int at=field(table,index,4);if(at==0)throw bad("missing required field "+index);return relative(at); }
    private int relative(int at) { long distance=u32(at);if(distance<4)throw bad("relative reference");return offset(at+distance); }
    private String text(int table,int index) {
        int value=ref(table,index),n=bounded(u32(value),4096,"string");span(value+4,(long)n+1);
        if(data.get(value+4+n)!=0)throw bad("unterminated string");
        byte[] raw=bytes(value+4,n);String out=new String(raw,StandardCharsets.UTF_8);
        if(!Arrays.equals(raw,out.getBytes(StandardCharsets.UTF_8)))throw bad("invalid UTF8");return out;
    }
    private byte[] bytes(int at,int n) { span(at,n);byte[] out=new byte[n];ByteBuffer copy=data.duplicate();copy.position(at);copy.get(out);return out; }
    private long u32(int at) { span(at,4);return data.getInt(at)&0xffffffffL; }
    private long i64(int at) { span(at,8);return data.getLong(at); }
    private int offset(long at) { if(at<0||at>data.limit()-4)throw bad("offset outside file");return(int)at; }
    private void span(int at,long bytes) { if(at<0||bytes<0||bytes>data.limit()-(long)at)throw bad("read outside file"); }
    private static int bounded(long n,int max,String name) { if(n<0||n>max)throw bad(name+" budget");return(int)n; }
    private static IllegalArgumentException bad(String why) { return new IllegalArgumentException("offline vector: "+why); }
}
