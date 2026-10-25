package dev.denza.adaslink.stocknative;

/** Generated-protobuf-compatible projection of the stock selection reply.
 * Singular scalars use the last value; repeated embedded messages merge, and
 * unknown fields (including groups) are skipped. No ACK is generated here.
 */
final class NativeAck {
    boolean present;long path;int status;
    static NativeAck parse(byte[] bytes){if(bytes==null)throw new IllegalArgumentException("null protobuf");NativeAck ack=new NativeAck();Cursor c=new Cursor(bytes,0,bytes.length);while(c.at<c.end){int tag=c.tag();if(tag==10){int end=c.lengthEnd();ack.present=true;inner(new Cursor(bytes,c.at,end),ack);c.at=end;}else c.skip(tag,0);}return ack;}
    private static void inner(Cursor c,NativeAck ack){while(c.at<c.end){int tag=c.tag();if(tag==24)ack.path=c.varint();else if(tag==32)ack.status=(int)c.varint();else c.skip(tag,0);}}
    private static final class Cursor {
        final byte[]bytes;final int end;int at;
        Cursor(byte[]b,int begin,int end){bytes=b;at=begin;this.end=end;}
        long varint(){long v=0;for(int n=0;n<10;n++){if(at>=end)throw new IllegalArgumentException("truncated protobuf varint");int b=bytes[at++]&255;v|=(long)(b&127)<<(7*n);if((b&128)==0)return v;}throw new IllegalArgumentException("malformed protobuf varint");}
        int tag(){int tag=(int)varint();if((tag>>>3)==0)throw new IllegalArgumentException("zero protobuf field");return tag;}
        int lengthEnd(){long length=varint();if(length<0||length>end-at)throw new IllegalArgumentException("truncated protobuf field");return at+(int)length;}
        void skip(int tag,int depth){switch(tag&7){case 0:varint();return;case 1:advance(8);return;case 2:at=lengthEnd();return;case 3:if(depth>=100)throw new IllegalArgumentException("protobuf recursion limit");while(at<end){int next=tag();if((next&7)==4){if((next>>>3)!=(tag>>>3))throw new IllegalArgumentException("protobuf end group mismatch");return;}skip(next,depth+1);}throw new IllegalArgumentException("truncated protobuf group");case 5:advance(4);return;default:throw new IllegalArgumentException("invalid protobuf wire type");}}
        void advance(int size){if(size>end-at)throw new IllegalArgumentException("truncated fixed protobuf");at+=size;}
    }
}
