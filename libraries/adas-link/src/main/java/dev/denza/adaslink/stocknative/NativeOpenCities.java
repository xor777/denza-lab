package dev.denza.adaslink.stocknative;

import java.util.ArrayList;
import java.util.List;

/** Original ODDRegionCodeResp: repeated field 1 message, each with uint32 field 1.
 * Schema: stock SomeipPilotStatusAlarmInfoService, ODDRegionCodeResp/ODDRegionCodeInfo.
 * Empty lists and proto3 scalar defaults retain generated-parser semantics. */
public final class NativeOpenCities {
    private NativeOpenCities() { }
    public static List<Integer> parse(byte[] payload) {
        if (payload == null || payload.length > 65536) throw new IllegalArgumentException("ODD response size");
        Cursor c = new Cursor(payload,0,payload.length);
        List<Integer> codes = new ArrayList<>();
        while(c.at<c.end) {
            int tag=c.tag();
            if(tag!=10) {c.skip(tag,0);continue;}
            int end=c.lengthEnd(), code=0;Cursor item=new Cursor(payload,c.at,end);
            while(item.at<item.end) {int key=item.tag();if(key==8)code=(int)item.varint();else item.skip(key,0);}
            codes.add(code);c.at=end;
        }
        return codes;
    }
    private static final class Cursor {
        final byte[] bytes;final int end;int at;
        Cursor(byte[] bytes,int begin,int end){this.bytes=bytes;this.at=begin;this.end=end;}
        long varint(){long v=0;for(int n=0;n<10;n++){if(at>=end)throw new IllegalArgumentException("truncated varint");int b=bytes[at++]&255;v|=(long)(b&127)<<(7*n);if((b&128)==0)return v;}throw new IllegalArgumentException("malformed varint");}
        int tag(){int t=(int)varint();if((t>>>3)==0)throw new IllegalArgumentException("zero field");return t;}
        int lengthEnd(){long n=varint();if(n<0||n>end-at)throw new IllegalArgumentException("truncated field");return at+(int)n;}
        void skip(int t,int depth){switch(t&7){case 0:varint();return;case 1:advance(8);return;case 2:at=lengthEnd();return;case 3:if(depth>=100)throw new IllegalArgumentException("recursion");while(at<end){int next=tag();if((next&7)==4){if((next>>>3)!=(t>>>3))throw new IllegalArgumentException("group mismatch");return;}skip(next,depth+1);}throw new IllegalArgumentException("truncated group");case 5:advance(4);return;default:throw new IllegalArgumentException("wire type");}}
        void advance(int n){if(n>end-at)throw new IllegalArgumentException("truncated fixed");at+=n;}
    }
}
