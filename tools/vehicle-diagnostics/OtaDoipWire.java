import java.io.*;
import java.util.Arrays;

/** Fixed DoIP framing from retained libdoip and the successful ADC read. */
public final class OtaDoipWire {
    static final class Refused extends Exception {
        final String code;
        Refused(String code) { super(code); this.code=code; }
    }
    static void require(boolean value,String code) throws Refused { if(!value)throw new Refused(code); }
    static int u16(byte[] b,int i) { return ((b[i]&255)<<8)|(b[i+1]&255); }
    static byte[] frame(int type,byte[] payload) throws Exception {
        require(type==5 || type==0x8001,"transmit_type");
        if(type==5)require(Arrays.equals(payload,new byte[]{0,1,0,0,0,0,0,0,0,0,0}),"routing_payload");
        else require(payload.length==7 && payload[0]==0 && payload[1]==1 && payload[4]==0x22,"read_payload");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(0x02fd);out.writeShort(type);out.writeInt(payload.length);out.write(payload);return bytes.toByteArray();
    }
    static byte[] routing() throws Exception { return frame(5,new byte[]{0,1,0,0,0,0,0,0,0,0,0}); }
    static byte[] request(int target,int did) throws Exception {
        // Every supported pair comes from the returned factory catalog.
        require((target==0x10 && (did==0xf194 || did==0xf195 || did==0xf294 || did==0xf295)) ||
                ((target==0x71a || target==0x78c || target==0x73e || target==0x75f) && (did==0xf194 || did==0xf195)),"read_pair");
        return frame(0x8001,new byte[]{0,1,(byte)(target>>8),(byte)target,0x22,(byte)(did>>8),(byte)did});
    }
    static int[] header(byte[] bytes) throws Exception {
        require(bytes.length==8 && u16(bytes,0)==0x02fd,"receive_header");
        long length=((bytes[4]&255L)<<24)|((bytes[5]&255L)<<16)|((bytes[6]&255L)<<8)|(bytes[7]&255L);
        require(length<=1024,"receive_length");return new int[]{u16(bytes,2),(int)length};
    }
    static int routingResponse(int type,byte[] body) throws Exception {
        require(type==6 && (body.length==9 || body.length==13) && u16(body,0)==1,"routing_response");
        require((body[4]&255)==0x10,"routing_denied");return u16(body,2);
    }
    static String response(int type,byte[] body,int target,int did) throws Exception {
        require(body.length>=5 && u16(body,0)==target && u16(body,2)==1,"foreign_response");
        if(type==0x8002) {
            require(body[4]==0 && (body.length==5 || (body.length==8 && body[5]==0x22 && u16(body,6)==did)),"diagnostic_ack");
            return "ack";
        }
        if(type==0x8003)throw new Refused("diagnostic_nack");
        require(type==0x8001,"receive_type");
        byte[] uds=Arrays.copyOfRange(body,4,body.length);
        if(uds.length==3 && uds[0]==0x7f && uds[1]==0x22)return (uds[2]&255)==0x78?"pending":"negative";
        require(uds.length>=4 && uds.length<=67 && uds[0]==0x62 && u16(uds,1)==did,"uds_response");
        return "positive";
    }
}
