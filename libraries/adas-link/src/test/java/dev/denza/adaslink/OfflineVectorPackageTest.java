package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Host catalog/decode contract tests. Fake decode uses pinned independently decompressed bytes;
 * it deliberately does not claim to exercise the native Zstd implementation. */
public final class OfflineVectorPackageTest {
    private static int checks;
    private static final int ROOT=64, VT=16, VECTOR=512, FIRST=736, SECOND=800;
    private static final String PACKAGE_SHA="053bb300f26fc699b8f6e5d8d972480179ef78872426946caad2f43e8ec35a1e";
    private static final String DICTIONARY_SHA="1c5e9cfded2036e07e9b5a66dd8992d43ffef2b440a32e0398b438eb86252d63";
    private static final String FIRST_COMPRESSED_SHA="5119333335b6140b5e3c71cf7da9ebbee42428e5cc34f15597be7e5e1e0d3b3d";
    private static final String LAST_COMPRESSED_SHA="7c74c58114b0be41e4d385bc17a07c8f33db6ac521839da52213babbc5e0cde6";
    private static final String FIRST_DECODED_SHA="9698d05fbe5330f871c6d1dd76847b66b6f330273de6c9624ec3428e3bb3c842";
    private static final String LAST_DECODED_SHA="3a3de2ca35d4f97ce950d24ad065203e760b5b609c83bdf24668e4985597bbf9";
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static void fails(Runnable action,String message) {
        try { action.run(); } catch(IllegalArgumentException expected) { checks++;return; }
        throw new AssertionError(message);
    }
    private static void indexFails(Runnable action,String message) {
        try { action.run(); } catch(IndexOutOfBoundsException expected) { checks++;return; }
        throw new AssertionError(message);
    }
    private static void ref(ByteBuffer b,int at,int to) { b.putInt(at,to-at); }
    private static void string(ByteBuffer b,int at,String text) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);b.putInt(at,bytes.length);
        for(int i=0;i<bytes.length;i++)b.put(at+4+i,bytes[i]);b.put(at+4+bytes.length,(byte)0);
    }
    private static byte[] tiny() {
        ByteBuffer b=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,ROOT);
        b.putShort(VT,(short)28);b.putShort(VT+2,(short)32);b.putInt(ROOT,ROOT-VT);
        for(int i=0;i<5;i++)b.putShort(VT+4+2*i,(short)(4+4*i));
        b.putShort(VT+4+20,(short)24);b.putShort(VT+4+22,(short)28);
        int[] strings={128,160,192,224};String[] text={"en_RU","20261024T000000","vmap3","test"};
        for(int i=0;i<4;i++){string(b,strings[i],text[i]);ref(b,ROOT+4+4*i,strings[i]);}
        ref(b,ROOT+20,VECTOR);b.put(ROOT+24,(byte)1);ref(b,ROOT+28,256);
        b.putInt(256,4);b.putInt(260,0x3230bb61);b.putInt(VECTOR,2);
        for(int i=0;i<2;i++) {
            int table=i==0?FIRST:SECOND,vtable=i==0?720:784,etag=928+16*i,payload=960+16*i;
            ref(b,VECTOR+4+4*i,table);b.putInt(table,table-vtable);
            b.putShort(vtable,(short)10);b.putShort(vtable+2,(short)24);
            b.putShort(vtable+4,(short)(i==0?0:8));b.putShort(vtable+6,(short)4);b.putShort(vtable+8,(short)16);
            b.putLong(table+8,i==0?0:0x0400000020000000L);string(b,etag,i==0?"a":"b");ref(b,table+4,etag);
            ref(b,table+16,payload);b.putInt(payload,3);b.put(payload+4,(byte)i);b.put(payload+5,(byte)2);b.put(payload+6,(byte)3);
        }
        return b.array();
    }
    private static ByteBuffer le(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static OfflineVectorPackage read(byte[] bytes) { return new OfflineVectorPackage(ByteBuffer.wrap(bytes).asReadOnlyBuffer()); }
    static void synthetic() {
        byte[] raw=tiny();OfflineVectorPackage p=read(raw);
        check(p.size()==2&&p.entry(0).packedId==0,"omitted first field0 defaults to zero");
        check(p.entry(1).tile.equals(new VectorRoadTile.TileId(1,0,1)),"native packed golden key");
        for(int z:new int[]{0,1,15,29}) {
            VectorRoadTile.TileId id=new VectorRoadTile.TileId((int)((1L<<z)-1),(int)((1L<<z)-1),z);
            check(OfflineVectorPackage.unpack(OfflineVectorPackage.pack(id)).equals(id),"canonical max coordinate roundtrip zoom"+z);
        }
        check(p.find(new VectorRoadTile.TileId(0,1,1))==null,"absent tile is not substituted with a parent");
        final int[] decodeCalls={0};check(p.decode(new VectorRoadTile.TileId(0,1,1),(a,d,m)->{decodeCalls[0]++;return a;})==null&&decodeCalls[0]==0,"missing tile never calls decoder");
        check(p.language.equals("en_RU")&&p.version.equals("test")&&p.format.equals("vmap3"),"metadata reads");
        byte[] dictionary=p.dictionary();dictionary[0]^=1;check(!Arrays.equals(dictionary,p.dictionary()),"dictionary copy cannot mutate backing bytes");
        byte[] compressed=p.compressed(p.entry(0));compressed[0]^=1;check(!Arrays.equals(compressed,p.compressed(p.entry(0))),"compressed copy cannot mutate backing bytes");
        indexFails(()->p.entry(-1),"negative catalog index");indexFails(()->p.entry(2),"catalog index upper bound");
        fails(()->OfflineVectorPackage.unpack(Long.MIN_VALUE),"negative packed key");
        fails(()->OfflineVectorPackage.unpack(1),"coordinate outside zoom0");
        fails(()->OfflineVectorPackage.pack(new VectorRoadTile.TileId(0,0,30)),"catalog format does not admit zoom30");
        fails(()->OfflineVectorPackage.pack(null),"missing tile identity");fails(()->p.compressed(null),"missing catalog entry");
        byte[] other=tiny();other[932]='x';OfflineVectorPackage foreign=read(other);
        fails(()->p.compressed(foreign.entry(0)),"foreign entry with a different etag cannot select bytes");
        fails(()->p.decode(p.entry(0).tile,null),"decoder mandatory");
        fails(()->p.decode(p.entry(0).tile,(a,d,m)->null),"null decoded payload");
        fails(()->p.decode(p.entry(0).tile,(a,d,m)->new byte[0]),"empty decoded payload");
        fails(()->p.decode(p.entry(0).tile,(a,d,m)->new byte[m+1]),"decoded output bound");
        fails(()->p.decode(p.entry(0).tile,(a,d,m)->new byte[]{(byte)255}),"malformed protobuf from decoder");
        // A slice of a read-only buffer, with unrelated bytes around it, is its entire file.
        ByteBuffer input=ByteBuffer.allocate(raw.length+16).order(ByteOrder.BIG_ENDIAN);input.position(7);input.put(raw);input.limit(7+raw.length);input.position(7);
        ByteBuffer readonly=input.asReadOnlyBuffer();int position=readonly.position(),limit=readonly.limit();
        OfflineVectorPackage sliced=new OfflineVectorPackage(readonly);
        check(sliced.size()==2&&readonly.position()==position&&readonly.limit()==limit&&readonly.order()==ByteOrder.BIG_ENDIAN,"read-only input position/limit/order unchanged");
        check(((Number)sliced.summary().get("fileBytes")).intValue()==raw.length,"reader bounds are relative to input remaining slice");
        ByteBuffer direct=ByteBuffer.allocateDirect(raw.length);direct.put(raw).flip();check(new OfflineVectorPackage(direct.asReadOnlyBuffer()).size()==2,"read-only direct buffer supported");
        malformed();
    }
    static void malformed() {
        fails(()->read(new byte[7]),"truncated file header");fails(()->new OfflineVectorPackage(null),"null buffer");
        fails(()->read(Arrays.copyOf(tiny(),970)),"truncated last payload");
        byte[] b=tiny();le(b).putInt(0,Integer.MAX_VALUE);fails(()->read(b),"root outside file");
        byte[] v=tiny();le(v).putInt(ROOT,ROOT+1);fails(()->read(v),"vtable before file");
        byte[] codec=tiny();codec[ROOT+24]=2;fails(()->read(codec),"unsupported compression codec");
        byte[] noCodec=tiny();le(noCodec).putShort(VT+24,(short)0);fails(()->read(noCodec),"absent codec cannot default to supported");
        byte[] noDict=tiny();le(noDict).putShort(VT+26,(short)0);fails(()->read(noDict),"required dictionary missing");
        byte[] format=tiny();format[196]='x';fails(()->read(format),"wrong package format");
        byte[] utf=tiny();utf[132]=(byte)255;fails(()->read(utf),"invalid metadata UTF8");
        byte[] count=tiny();le(count).putInt(VECTOR,OfflineVectorPackage.MAX_TILES+1);fails(()->read(count),"catalog count bound");
        byte[] dict=tiny();le(dict).putInt(256,OfflineVectorPackage.MAX_DICTIONARY_BYTES+1);fails(()->read(dict),"dictionary byte bound");
        byte[] tile=tiny();le(tile).putInt(960,OfflineVectorPackage.MAX_COMPRESSED_BYTES+1);fails(()->read(tile),"compressed tile byte bound");
        byte[] duplicate=tiny();le(duplicate).putLong(SECOND+8,0);fails(()->read(duplicate),"duplicate keys rejected");
        byte[] unsorted=tiny();le(unsorted).putShort(724,(short)8).putLong(FIRST+8,0x0800000040000001L);fails(()->read(unsorted),"descending canonical keys rejected");
        byte[] key=tiny();le(key).putLong(SECOND+8,Long.MIN_VALUE);fails(()->read(key),"noncanonical catalog key rejected");
        byte[] field=tiny();le(field).putShort(788,(short)20);fails(()->read(field),"uint64 field crosses table boundary");
        byte[] relative=tiny();le(relative).putInt(FIRST+16,2);fails(()->read(relative),"invalid relative reference");
        byte[] terminated=tiny();terminated[933]=1;fails(()->read(terminated),"string terminator required");
    }
    static void largeScopedMapping() throws Exception {
        Path file=Files.createTempFile("offline-vector-large-", ".fb");
        try {
            try(java.io.RandomAccessFile output=new java.io.RandomAccessFile(file.toFile(),"rw")) {
                output.write(tiny()); output.setLength(826573656L);
            }
            try(java.nio.channels.FileChannel input=java.nio.channels.FileChannel.open(file)) {
                ByteBuffer mapping=input.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,input.size());
                OfflineVectorPackage catalog=new OfflineVectorPackage(mapping);
                check(catalog.size()==2,"large mapped container uses bounded catalog access");
                check(((Number)catalog.summary().get("fileBytes")).intValue()==826573656,"Moscow-sized mapping is not a heap decode budget");
                check(catalog.dictionary().length==4,"large file keeps independent dictionary allocation bound");
            }
        } finally { Files.delete(file); }
    }
    static void corpus(Path root)throws Exception {
        Path packagePath=root.resolve("captures/yandex-offline-region-20261024/region-43/vmap3fb.fb");
        Path fixtures=root.resolve("captures/yandex-vector-enrichment-20261024/offline-vmap");
        byte[] bytes=Files.readAllBytes(packagePath);check(bytes.length==66793664&&VectorRoadTile.digest(bytes).equals(PACKAGE_SHA),"pinned package identity");
        OfflineVectorPackage p=new OfflineVectorPackage(ByteBuffer.wrap(bytes).asReadOnlyBuffer());
        check(p.size()==1596,"all actual catalog records");check(p.version.equals("26.10.07-1")&&p.language.equals("en_RU")&&p.buildTimestamp.equals("20261007T183026"),"actual metadata independent of driving.fb version");
        for(int i=0;i<p.size();i++) { OfflineVectorPackage.Entry e=p.entry(i);check(OfflineVectorPackage.pack(e.tile)==e.packedId,"all actual keys roundtrip");check(p.find(e.tile).packedId==e.packedId,"all actual keys found by exact lookup"); }
        OfflineVectorPackage.Entry first=p.entry(0),last=p.entry(1595);
        check(first.tile.equals(new VectorRoadTile.TileId(0,0,0))&&first.packedId==0&&first.compressedBytes==89992,"first native catalog golden");
        check(last.tile.equals(new VectorRoadTile.TileId(20878,10274,15))&&last.packedId==0x3c000a31c0002822L&&last.compressedBytes==16662,"last native catalog golden");
        check(VectorRoadTile.digest(p.compressed(first)).equals(FIRST_COMPRESSED_SHA),"first compressed bytes hash");
        check(VectorRoadTile.digest(p.compressed(last)).equals(LAST_COMPRESSED_SHA),"last compressed bytes hash");
        check(p.dictionary().length==102400&&VectorRoadTile.digest(p.dictionary()).equals(DICTIONARY_SHA),"root11 dictionary hash");
        check(Arrays.equals(p.dictionary(),Files.readAllBytes(fixtures.resolve("dictionary.bin"))),"dictionary matches independent decoder fixture");
        byte[] firstRaw=Files.readAllBytes(fixtures.resolve("first-tile.bin")),lastRaw=Files.readAllBytes(fixtures.resolve("last-tile.bin"));
        check(firstRaw.length==337388&&VectorRoadTile.digest(firstRaw).equals(FIRST_DECODED_SHA),"first independently decoded fixture hash");
        check(lastRaw.length==44770&&VectorRoadTile.digest(lastRaw).equals(LAST_DECODED_SHA),"last independently decoded fixture hash");
        final int[] calls={0};OfflineVectorPackage.Decoder decoder=(compressed,dictionary,max)->{
            calls[0]++;check(max==OfflineVectorPackage.MAX_DECODED_BYTES,"decoder receives explicit output bound");
            check(VectorRoadTile.digest(dictionary).equals(DICTIONARY_SHA),"matching package dictionary supplied to decoder");
            String sha=VectorRoadTile.digest(compressed);
            if(sha.equals(FIRST_COMPRESSED_SHA))return firstRaw.clone();
            if(sha.equals(LAST_COMPRESSED_SHA))return lastRaw.clone();
            throw new AssertionError("unexpected compressed fixture");
        };
        VectorRoadTile decodedFirst=p.decode(first.tile,decoder),decodedLast=p.decode(last.tile,decoder);
        check(decodedFirst.tileId().equals(first.tile)&&decodedFirst.sha256().equals(FIRST_DECODED_SHA),"first exact catalog frame reaches vector decoder");
        check(decodedLast.tileId().equals(last.tile)&&decodedLast.sha256().equals(LAST_DECODED_SHA)&&!decodedLast.features().isEmpty(),"last exact catalog frame and road literals reach vector decoder");
        check(calls[0]==2,"one decoder call per requested existing tile");
        check(p.find(new VectorRoadTile.TileId(0,0,14))==null,"actual missing z14 stays absent");
        check(p.find(new VectorRoadTile.TileId(20878,10275,15))==null,"key after last catalog entry stays absent");
        String immutableHash=VectorRoadTile.digest(bytes);p.compressed(last);p.dictionary();check(VectorRoadTile.digest(bytes).equals(immutableHash),"reader and extraction preserve immutable backing bytes");
    }
    public static void main(String[]args)throws Exception {
        synthetic();largeScopedMapping();corpus(Paths.get(args.length==0?".":args[0]));
        System.out.println("OfflineVectorPackageTest: "+checks+" checks passed; fake decode does not validate native Zstd");
    }
}
