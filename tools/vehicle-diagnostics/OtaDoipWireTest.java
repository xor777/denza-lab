import java.util.Arrays;
public class OtaDoipWireTest {
    static int checks;
    interface Attempt { void run() throws Exception; }
    static void check(boolean b){checks++;if(!b)throw new AssertionError();}
    static void fails(Attempt a)throws Exception{try{a.run();throw new AssertionError();}catch(OtaDoipWire.Refused e){checks++;}}
    static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    public static void main(String[] args)throws Exception{
        check(Arrays.equals(OtaDoipWire.routing(),hex("02FD00050000000B0001000000000000000000")));
        check(Arrays.equals(OtaDoipWire.request(0x71a,0xf195),hex("02FD8001000000070001071A22F195")));
        check(OtaDoipWire.response(0x8002,hex("071A000100"),0x71a,0xf195).equals("ack"));
        check(OtaDoipWire.response(0x8001,hex("071A000162F195A1BF1A061D00"),0x71a,0xf195).equals("positive"));
        check(OtaDoipWire.response(0x8001,hex("071A00017F2231"),0x71a,0xf195).equals("negative"));
        check(OtaDoipWire.response(0x8001,hex("071A00017F2278"),0x71a,0xf195).equals("pending"));
        check(OtaDoipWire.routingResponse(6,hex("0001071A1000000000"))==0x71a);
        fails(()->OtaDoipWire.routingResponse(6,hex("0001071A0300000000")));
        fails(()->OtaDoipWire.routingResponse(6,hex("0002071A1000000000")));
        fails(()->OtaDoipWire.header(hex("03FC80010000000D")));
        fails(()->OtaDoipWire.header(hex("02FD8001FFFFFFFF")));
        fails(()->OtaDoipWire.header(hex("02FD800100000401")));
        check(Arrays.equals(OtaDoipWire.header(hex("02FD80010000000D")),new int[]{0x8001,13}));
        fails(()->OtaDoipWire.response(0x8001,hex("071B000162F195A1BF1A061D00"),0x71a,0xf195));
        fails(()->OtaDoipWire.response(0x8001,hex("071A000162F194A1BF1A061D00"),0x71a,0xf195));
        fails(()->OtaDoipWire.response(0x8001,hex("071A00017F1031"),0x71a,0xf195));
        fails(()->OtaDoipWire.response(7,hex("071A000100"),0x71a,0xf195));
        fails(()->OtaDoipWire.response(0x8003,hex("071A000102"),0x71a,0xf195));
        fails(()->OtaDoipWire.frame(0x8001,hex("0001071A1003")));
        fails(()->OtaDoipWire.frame(8,hex("0001")));
        for(int target:new int[]{0x10,0x71a,0x73e,0x78c,0x75f,0x712}){
            int accepted=0;
            for(int did=0;did<65536;did++){
                try{OtaDoipWire.request(target,did);accepted++;}catch(OtaDoipWire.Refused ignored){}
            }
            check(accepted==(target==0x10?4:target==0x712?0:2));
        }
        System.out.println("PASS wire checks="+checks+"; exhaustive target/DID whitelist=393216 pairs; sockets=0");
    }
}
