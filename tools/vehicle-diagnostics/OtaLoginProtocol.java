import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.SSLContext;

/** One loginIn followed by one getEcuList. Fixed URLs; no registration or persistence. */
public final class OtaLoginProtocol {
    static final String LOGIN = "/ota/otaService/loginIn";
    static Identity identity;
    static boolean loginRequest;
    static int requests;
    static String oldToken;

    static final class Identity {
        final String vin, iccid, country;
        Identity(String vin, String iccid, String country) throws Exception {
            EcuMapMetadataProbe.require(vin != null && vin.matches("[A-HJ-NPR-Z0-9]{17}") && !vin.matches("0{17}"), "login_inputs_vin");
            EcuMapMetadataProbe.require(iccid != null && !iccid.isEmpty(), "login_inputs_iccid_missing");
            EcuMapMetadataProbe.require(iccid.matches("[0-9]{19,22}"), "login_inputs_iccid_shape");
            EcuMapMetadataProbe.require(country != null && country.matches("[0-9]{1,4}"), "login_inputs_country");
            this.vin=vin; this.iccid=iccid; this.country=country;
        }
    }

    static byte[] request(EcuMapMetadataProbe.Inputs inputs) throws Exception {
        EcuMapMetadataProbe.require(identity != null && ((requests == 0 && loginRequest) ||
                (requests == 1 && !loginRequest)), "login_request_order");
        requests++;
        if (!loginRequest) return EcuMapMetadataProbe.httpRequest(inputs);
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("vin", identity.vin); values.put("iccid", identity.iccid);
        values.put("uuid", inputs.uuid); values.put("countryCode", identity.country);
        byte[] body = EcuMapMetadataProbe.Json.encode(values).getBytes(StandardCharsets.UTF_8);
        // Stock logs out before loginIn; consequently its interceptor omits Authorization.
        String headers = "POST "+LOGIN+" HTTP/1.1\r\nHost: "+EcuMapMetadataProbe.HOST+":"+EcuMapMetadataProbe.PORT+
            "\r\nUser-Agent: BYD\r\nContent-Type: application/json;charset=utf-8\r\nAccept: application/json\r\n"+
            "Accept-Encoding: identity\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(headers.getBytes(StandardCharsets.US_ASCII)); result.write(body);
        Arrays.fill(body, (byte)0);
        return result.toByteArray();
    }

    static EcuMapMetadataProbe.Inputs parse(byte[] body, EcuMapMetadataProbe.Inputs inputs) throws Exception {
        EcuMapMetadataProbe.require(body.length > 0 && body.length <= 16384, "login_response_shape");
        Map<String,Object> result = EcuMapMetadataProbe.object(EcuMapMetadataProbe.Json.parse(EcuMapMetadataProbe.utf8(body)));
        Object status = result.get("status");
        if ("114".equals(status)) throw new EcuMapMetadataProbe.Failure("login_registration_required");
        if ("117".equals(status)) throw new EcuMapMetadataProbe.Failure("login_iccid_binding");
        if ("113".equals(status)) throw new EcuMapMetadataProbe.Failure("login_vin_unknown");
        if ("101".equals(status)) throw new EcuMapMetadataProbe.Failure("login_auth_required");
        EcuMapMetadataProbe.require("100".equals(status), "login_application_status");
        Object token = result.get("token");
        EcuMapMetadataProbe.require(token instanceof String && ((String)token).matches("[!-~]{1,4096}") &&
                !"null".equalsIgnoreCase((String)token), "login_token_invalid");
        return new EcuMapMetadataProbe.Inputs(inputs.certificate, inputs.privateKey, (String)token, inputs.uuid, inputs.version);
    }

    static EcuMapMetadataProbe.Inputs login(EcuMapMetadataProbe.Inputs inputs, SSLContext context, long end) throws Exception {
        oldToken = inputs.token;
        loginRequest = true;
        byte[] body = null;
        try {
            body = EcuMapMetadataProbe.post(inputs, context, end);
            return parse(body, inputs);
        } finally {
            loginRequest = false;
            if (body != null) Arrays.fill(body, (byte)0);
        }
    }

    static void privacy(Object value) throws Exception {
        if (value instanceof String) {
            for (String privateValue : new String[]{identity.vin, identity.iccid, oldToken})
                EcuMapMetadataProbe.require(!EcuMapMetadataProbe.containsPrivate((String)value, privateValue), "privacy_projection");
        } else if (value instanceof Map) {
            for (Object child : ((Map<?,?>)value).values()) privacy(child);
        } else if (value instanceof List) {
            for (Object child : (List<?>)value) privacy(child);
        }
    }
}
