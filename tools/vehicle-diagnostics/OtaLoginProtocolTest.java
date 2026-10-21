import java.nio.charset.StandardCharsets;
import java.util.*;

public class OtaLoginProtocolTest {
    static int assertions;
    interface Check { void run() throws Exception; }
    static void check(boolean condition) { assertions++; if(!condition) throw new AssertionError(); }
    static void fails(Check action, String code) throws Exception {
        try { action.run(); throw new AssertionError("accepted"); }
        catch(EcuMapMetadataProbe.Failure error) { check(code.equals(error.code)); }
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        EcuMapMetadataProbe.Inputs inputs = new EcuMapMetadataProbe.Inputs(null,null,"SYNTHETIC_OLD_TOKEN","","V1.7.0");
        OtaLoginProtocol.identity = new OtaLoginProtocol.Identity("TEST1234567890001","12345678901234567890","86");
        fails(()->OtaLoginProtocol.request(inputs), "login_request_order");
        OtaLoginProtocol.loginRequest=true;
        String request=new String(OtaLoginProtocol.request(inputs),StandardCharsets.UTF_8);
        check(request.startsWith("POST /ota/otaService/loginIn HTTP/1.1\r\n"));
        check(!request.contains("Authorization") && !request.contains(inputs.token));
        String[] split=request.split("\r\n\r\n",2);
        Map<String,Object> fields=EcuMapMetadataProbe.object(EcuMapMetadataProbe.Json.parse(split[1]));
        check(fields.keySet().equals(new HashSet<>(Arrays.asList("vin","iccid","uuid","countryCode"))));
        check(fields.get("uuid").equals(""));
        check(split[0].contains("Content-Length: "+bytes(split[1]).length));
        fails(()->OtaLoginProtocol.request(inputs), "login_request_order");
        EcuMapMetadataProbe.Inputs fresh=OtaLoginProtocol.parse(bytes("{\"status\":\"100\",\"token\":\"SYNTHETIC_FRESH_TOKEN\"}"), inputs);
        check(fresh.token.equals("SYNTHETIC_FRESH_TOKEN") && fresh.uuid.equals(inputs.uuid) && fresh.version.equals(inputs.version));
        OtaLoginProtocol.loginRequest=false;
        check(Arrays.equals(OtaLoginProtocol.request(fresh),EcuMapMetadataProbe.httpRequest(fresh)));
        fails(()->OtaLoginProtocol.request(fresh), "login_request_order");
        OtaLoginProtocol.loginRequest=true;
        fails(()->OtaLoginProtocol.request(fresh), "login_request_order");
        for(String[] row:new String[][]{{"114","login_registration_required"},{"117","login_iccid_binding"},{"113","login_vin_unknown"},{"101","login_auth_required"},{"999","login_application_status"}})
            fails(()->OtaLoginProtocol.parse(bytes("{\"status\":\""+row[0]+"\",\"message\":\"PRIVATE\"}"),inputs),row[1]);
        for(String raw:new String[]{"null","\"null\"","\"\"","\"bad\\r\\nHeader: x\""})
            fails(()->OtaLoginProtocol.parse(bytes("{\"status\":\"100\",\"token\":"+raw+"}"),inputs),"login_token_invalid");
        fails(()->OtaLoginProtocol.parse(bytes("{\"status\":\"100\",\"status\":\"114\"}"),inputs),"json_duplicate");
        fails(()->new OtaLoginProtocol.Identity("00000000000000000","12345678901234567890","86"),"login_inputs_vin");
        fails(()->new OtaLoginProtocol.Identity("TEST1234567890001",null,"86"),"login_inputs_iccid_missing");
        fails(()->new OtaLoginProtocol.Identity("TEST1234567890001","UNKNOWN","86"),"login_inputs_iccid_shape");
        fails(()->new OtaLoginProtocol.Identity("TEST1234567890001","12345678901234567890","86\r\n"),"login_inputs_country");
        OtaLoginProtocol.oldToken=inputs.token;
        for(String privateValue:new String[]{inputs.token,OtaLoginProtocol.identity.vin,OtaLoginProtocol.identity.iccid})
            fails(()->OtaLoginProtocol.privacy(Collections.singletonMap("ecuId",privateValue)),"privacy_projection");
        System.out.println("PASS isolated login protocol assertions="+assertions+"; sockets=0; no Android getter invoked");
    }
}
