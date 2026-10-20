import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Offline fixtures only: never calls fetch/post/main or opens a socket.
 * Embedded identity was generated solely for these tests, not from a vehicle.
 */
public final class EcuMapMetadataProbeTest {
    static final String CERT="MIIDLjCCAhagAwIBAgIUHGv5QvK1xox44VQHciMkPaa+qvIwDQYJKoZIhvcNAQELBQAwJjEkMCIGA1UEAwwbT0ZGTElORSBTWU5USEVUSUMgVEVTVCBPTkxZMB4XDTI2MTAxODEyMjU1MVoXDTM2MTAxNTEyMjU1MVowJjEkMCIGA1UEAwwbT0ZGTElORSBTWU5USEVUSUMgVEVTVCBPTkxZMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1F0+f/jkYngNaeJIl9lDNV+11UvsoT5HJtODnq8MqIRNFeNsDQh9R3gtbbdjbcrOnTsr5AWakUs/x4g7Q2ueD74E0xC6f7HSHKFAee9oBui8v9ejLRdKjn+DNjgOFmJGtmJ61WmS3rxT3rCyVVeOdews6zjD2pS0XnBQ1G6qyQ2lpQEqtHRwxsQOj8dCWXtww1VjBa2gP5A+WWxIF29cjN77PzusH+DgEXeMjIiWQvgrmjKNO2DuR+whCSlmmNGE59rq28UWSA0wZu0Aq8iG6MK/iXMD88N3TBNzZsdoNObBKd75M0ckm4n4ABK7pXY/zAdRNXVSsMWfdqzTk0xtKQIDAQABo1QwUjAdBgNVHQ4EFgQU0FJ1jFCTzNQpUg1q1Bp79y9GheIwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwEwYDVR0lBAwwCgYIKwYBBQUHAwIwDQYJKoZIhvcNAQELBQADggEBAJ8syWnbY0E5Rfqt9IRkHIKzh1hlaf2XDvYGN5ykURylZSjIftFIH9/vqCZO7Oht3ZZ63588ICQmnoa7ov0fazvEOPBlNNIyJMXjLbxcG5s/ytJm7g60snq5d5sVmdvdlyqAxzpZ96QUNuK8v7tTcbkKxgXPF8u2Y/v4vt90ec3Xfp6CMBoGusnciYeIKnZA7268XD9cdgdxgDIWZiPOL/pKQc8MwdzP+0mnzBBeNEwTGYjSqliJQ2gqiwjhG4hScCDAGETvU1ENL3KeeGXQ7ikZpvYXTFdXD59+Bkvk9V5Vwq+uvCj9UVWklsKIYdl2dmmQ8o03mGixaGS+GQyjkr4=";
    static final String KEY="MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDUXT5/+ORieA1p4kiX2UM1X7XVS+yhPkcm04OerwyohE0V42wNCH1HeC1tt2Ntys6dOyvkBZqRSz/HiDtDa54PvgTTELp/sdIcoUB572gG6Ly/16MtF0qOf4M2OA4WYka2YnrVaZLevFPesLJVV4517CzrOMPalLRecFDUbqrJDaWlASq0dHDGxA6Px0JZe3DDVWMFraA/kD5ZbEgXb1yM3vs/O6wf4OARd4yMiJZC+CuaMo07YO5H7CEJKWaY0YTn2urbxRZIDTBm7QCryIbowr+JcwPzw3dME3Nmx2g05sEp3vkzRySbifgAEruldj/MB1E1dVKwxZ92rNOTTG0pAgMBAAECggEAB23Y+TfHID1m6roEeXDGTcsIB2rdNP7YFQmijx3nPstHZGlM/F+8UjsStnCLRT9VCbcU3/3k3F8E34Fqi/9Adk3ayVofzBvNEpH+5Bp19IKWfSJEDm3EKUauBmrg7fOI2b80NjG39kbMVaavdmuBC6sJdJPLIOMW3umQSlOHcfpnQgBUfJbu6f/TPCdTDCjMzWWWv0nJpRAt1oMRUBwYPmW3NBEG16vFpyVU6E1tzeRSp7sJsNNbqX3TzL7FiKbt4026K+yOpSf+Ub/ROxsvZUyckMO8R84MBQGYHT2lgzbDBiMcqkA6zU1ThhDl46F0J5pgdW0Vwp29rlc06U1FSQKBgQDtqmPIvayseZ0dwQ/SfsxC0LDMuaZISAJ5lizTIOqj2LZlteDL+D2z1DxEokdUNKe7ikyO9BIsX3czmb/hle+YRTNx9/YYG+muJnC7anjR/P7q9PoAcD/ptTcMUB5JWbOI8qwHMWnh97E0BQjOysU97ixmIXxnFOeoszuEN0bhZwKBgQDkvy7b3m2/4TSmEmUL3Tt3ZftLPNuLAy+mY2qm2veeJehjBBTM3xBgsQZsIkVXH7gSaQmj7F+Z1rj4dn6hI7vAKyVoxnjgWpexsvWSt/9BVep63+voPtfp2h+lYE8JxJ0yKPc6qFyVnmX1/3VF5nOxR1ohN9Q820V6JDC0d4tS7wKBgQCHXSfuOtHxfmbVfpOTcXLHj3NTd13VB2aZd8ZaDgJ2hcUFUz0RD3edSAF3mfIEbOUn9NI8DZmYOlt7QVO/mZVYZfmyLOe2vZcwAGbu5+qet3k9u3/05foRE4yh77QAVR3jn0wJ4MQM1vXPyhpFJcqHxnL1TJc9YvO66zK30IAHewKBgEHASqHcn8Nl4pJR+0Wbw/ogEEmiJqEgu1OihAeOLVsCHnhHtgrlm3mPqP7wIupWLbztAF5kFV7M/8I7nU6ITPGB9XDWi+xBXhUwEbKovPB017+WGeNaHQoVLr+EnXN7VowShm4P8vz22OwLEBSVl2+/yMJ/n7wWczQWrwFQ03V1AoGBAM49eaz19VhuOXYOV2Fwc8Vu+OMRhI0l4u7IAhUx8gPCBKLR9kDiIQ8DTHtvF1idi59P2bot3RYOfRg/1KSg1enJTqJT4wFab2IRofMKKfHirdGm9UlA5m+7pjacBsBt3obmqZO/VplsUoYb179IF7SVusOmEAhB1dKF35XdS8G2";
    static int assertions;
    interface Action { void run() throws Exception; }
    static void check(boolean value) { assertions++;if(!value)throw new AssertionError("fixture assertion "+assertions); }
    static void fails(Action action) throws Exception { assertions++;try{action.run();}catch(Exception expected){return;}throw new AssertionError("expected rejection "+assertions); }
    static void failsWith(String code, Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (EcuMapMetadataProbe.Failure failure) {
            if (code.equals(failure.code)) return;
            throw new AssertionError("wrong failure code");
        }
        throw new AssertionError("expected rejection " + assertions);
    }
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    static long end(){return EcuMapMetadataProbe.deadline(1000);}
    static byte[] prefs(){return bytes("<?xml version='1.0' encoding='utf-8' standalone='yes'?><map><string name='user_token'>SYNTHETIC_TOKEN_ONLY</string><string name='upgradeUuid'>SYNTHETIC_UUID_ONLY</string><string name='private_other'>NEVER_EMIT</string></map>");}
    static EcuMapMetadataProbe.Inputs inputs()throws Exception{return EcuMapMetadataProbe.prepare(new byte[][]{bytes(CERT),bytes(KEY),prefs(),bytes("V1.7.0\n")});}
    static String row(){return "{\"ecuId\":\"BMS_BYD_SYNTHETIC\",\"ecuDiaId\":\"601/620\",\"ecuReprogramType\":\"can\",\"ecuDId\":\"F195/F194\",\"ecuHardDid\":null,\"token\":\"PRIVATE_UNKNOWN\"}";}
    static byte[] response(String row){return bytes("{\"status\":\"100\",\"ecuList\":["+row+"]}");}
    static byte[] transfer(int index,byte[] file,String metadata)throws Exception{
        String name=EcuMapMetadataProbe.PATHS[index].substring(EcuMapMetadataProbe.PATHS[index].lastIndexOf('/')+1);
        if(metadata==null)metadata="{\"code\":0,\"fileName\":\""+name+"\",\"fileSize\":"+file.length+",\"md5\":\""+EcuMapMetadataProbe.hex(MessageDigest.getInstance("MD5").digest(file))+"\"}";
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(out);
        data.writeInt(EcuMapMetadataProbe.MAGIC);data.writeInt(0);data.writeInt(bytes(metadata).length);data.write(bytes(metadata));data.write(file);return out.toByteArray();
    }
    static byte[] http(String headers,String body){return bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json;charset=UTF-8\r\n"+headers+"\r\n"+body);}
    static void runtimeOutputIsolation() throws Exception {
        PrintStream originalOut=System.out, originalErr=System.err;
        ByteArrayOutputStream wireBytes=new ByteArrayOutputStream(), errorBytes=new ByteArrayOutputStream();
        PrintStream protocol=new PrintStream(wireBytes,true,"UTF-8");
        try {
            System.setOut(protocol); System.setErr(new PrintStream(errorBytes,true,"UTF-8"));
            EcuMapMetadataProbe.silenceRuntimeOutput();
            System.out.println("[com.mediatek.cta.CtaAdapter]:check permission begin!");
            System.out.write(bytes("SYNTHETIC_PRIVATE_CANARY"));
            new Exception("SYNTHETIC_PRIVATE_CANARY").printStackTrace();
            System.out.flush(); System.err.flush();
            protocol.println("{\"state\":\"INPUTS_READY\"}"); protocol.flush();
            check(wireBytes.toString("UTF-8").trim().equals("{\"state\":\"INPUTS_READY\"}"));
            check(errorBytes.size()==0);
        } finally { System.setOut(originalOut); System.setErr(originalErr); }
    }
    public static void main(String[] ignored)throws Exception{
        runtimeOutputIsolation();
        // Host guard itself fails before all network/identity work outside Android.
        if("Dalvik".equals(System.getProperty("java.vm.name"))) { EcuMapMetadataProbe.androidShellGuard(); assertions++; }
        else fails(EcuMapMetadataProbe::androidShellGuard);
        EcuMapMetadataProbe.Inputs i=inputs();check(i.token.equals("SYNTHETIC_TOKEN_ONLY"));
        check(i.uuid.equals("SYNTHETIC_UUID_ONLY"));check(i.version.equals("V1.7.0"));
        check(EcuMapMetadataProbe.tlsContext(i)!=null);
        EcuMapMetadataProbe.verifyPair(i.privateKey,i.certificate.getPublicKey());assertions++;
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair wrong=generator.generateKeyPair();
        fails(()->EcuMapMetadataProbe.verifyPair(wrong.getPrivate(),i.certificate.getPublicKey()));
        fails(()->EcuMapMetadataProbe.prepare(new byte[][]{bytes(CERT),bytes(KEY),prefs(),bytes("\r\nBAD_HEADER: nope")}));
        fails(()->EcuMapMetadataProbe.der(bytes("AAAA= PRIVATE"),"PRIVATE KEY"));
        fails(()->EcuMapMetadataProbe.der(bytes("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----"),"PRIVATE KEY"));
        byte[] certDer=Base64.getDecoder().decode(CERT);byte[] joined=Arrays.copyOf(certDer,certDer.length*2);System.arraycopy(certDer,0,joined,certDer.length,certDer.length);
        fails(()->EcuMapMetadataProbe.certificate(bytes(Base64.getEncoder().encodeToString(joined))));
        fails(()->EcuMapMetadataProbe.preferences(bytes("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///never-opened'>]><map/>")));
        fails(()->EcuMapMetadataProbe.preferences(bytes("<map><string name='user_token'>a</string><string name='user_token'>b</string><string name='upgradeUuid'>u</string></map>")));
        fails(()->EcuMapMetadataProbe.preferences(bytes("<map><int name='user_token' value='1'/><string name='upgradeUuid'>u</string></map>")));
        fails(()->EcuMapMetadataProbe.preferences(bytes("<map><string name='user_token'><x/>a</string><string name='upgradeUuid'>u</string></map>")));
        fails(()->EcuMapMetadataProbe.preferences(bytes("<map><?external hidden?></map>")));
        check(EcuMapMetadataProbe.preferences(prefs()).size()==2);
        check(!EcuMapMetadataProbe.preferences(prefs()).containsKey("private_other"));
        String tokenOnly="<map><string name='user_token'>SYNTHETIC_TOKEN_ONLY</string></map>";
        EcuMapMetadataProbe.Inputs noUuid=EcuMapMetadataProbe.prepare(new byte[][]{bytes(CERT),bytes(KEY),bytes(tokenOnly),bytes("V1.7.0")});
        check(noUuid.uuid.isEmpty());
        String noUuidRequest=new String(EcuMapMetadataProbe.httpRequest(noUuid),StandardCharsets.UTF_8);
        check(noUuidRequest.endsWith("{\"version\":\"V1.7.0\",\"uuid\":\"\"}"));
        check(noUuidRequest.contains("Authorization: basicSYNTHETIC_TOKEN_ONLY\r\n"));
        String emptyUuid=tokenOnly.replace("</map>","<string name='upgradeUuid'></string></map>");
        check(EcuMapMetadataProbe.prepare(new byte[][]{bytes(CERT),bytes(KEY),bytes(emptyUuid),bytes("V1.7.0")}).uuid.isEmpty());
        EcuMapMetadataProbe.privacyCheck(Collections.singletonList(""),noUuid);assertions++;
        failsWith("privacy_projection",()->EcuMapMetadataProbe.privacyCheck(Collections.singletonList(noUuid.token),noUuid));
        failsWith("token_missing",()->EcuMapMetadataProbe.preferences(bytes("<map/>")));
        failsWith("token_missing",()->EcuMapMetadataProbe.preferences(bytes("<map><string name='upgradeUuid'>SYNTHETIC_UUID_ONLY</string></map>")));
        failsWith("token_invalid",()->EcuMapMetadataProbe.prepare(new byte[][]{bytes(CERT),bytes(KEY),bytes("<map><string name='user_token'></string></map>"),bytes("V1.7.0")}));
        for(String nullToken:new String[]{"null","NuLl"}) {
            String xml="<map><string name='user_token'>"+nullToken+"</string></map>";
            failsWith("token_invalid",()->EcuMapMetadataProbe.prepare(new byte[][]{bytes(CERT),bytes(KEY),bytes(xml),bytes("V1.7.0")}));
        }
        failsWith("prefs_invalid",()->EcuMapMetadataProbe.preferences(bytes(tokenOnly.replace("</map>","<int name='upgradeUuid' value='1'/></map>"))));
        failsWith("prefs_invalid",()->EcuMapMetadataProbe.preferences(bytes(tokenOnly.replace("</map>","<string name='upgradeUuid'></string><string name='upgradeUuid'>again</string></map>"))));
        for(String invalidUuid:new String[]{" ","\n",String.join("",Collections.nCopies(257,"x"))}) {
            String xml=tokenOnly.replace("</map>","<string name='upgradeUuid'>"+invalidUuid+"</string></map>");
            failsWith("uuid_invalid",()->EcuMapMetadataProbe.prepare(new byte[][]{bytes(CERT),bytes(KEY),bytes(xml),bytes("V1.7.0")}));
        }
        String nonce="0123456789abcdef0123456789abcdef";
        EcuMapMetadataProbe.awaitGo(new ByteArrayInputStream(bytes("GO "+nonce+"\n")),nonce);assertions++;
        fails(()->EcuMapMetadataProbe.awaitGo(new ByteArrayInputStream(bytes("GO "+nonce+"\r\n")),nonce));
        fails(()->EcuMapMetadataProbe.awaitGo(new ByteArrayInputStream(bytes("GO wrong\n")),nonce));
        fails(()->EcuMapMetadataProbe.awaitGo(new ByteArrayInputStream(new byte[0]),nonce));
        for(int n=0;n<4;n++){
            byte[] request=EcuMapMetadataProbe.transferRequest(n);DataInputStream data=new DataInputStream(new ByteArrayInputStream(request));
            check(data.readInt()==EcuMapMetadataProbe.MAGIC);check(data.readInt()==1);int length=data.readInt();check(length==data.available());
            check(new String(request,12,request.length-12,StandardCharsets.UTF_8).contains(EcuMapMetadataProbe.PATHS[n].substring(1)));
            byte[] file=bytes("fixture");check(Arrays.equals(file,EcuMapMetadataProbe.decodeTransfer(new ByteArrayInputStream(transfer(n,file,null)),n,end())));
        }
        fails(()->EcuMapMetadataProbe.transferRequest(4));
        byte[] packet=transfer(3,bytes("fixture"),null);packet[packet.length-1]^=1;
        fails(()->EcuMapMetadataProbe.decodeTransfer(new ByteArrayInputStream(packet),3,end()));
        byte[] complete=transfer(3,bytes("fixture"),null);
        fails(()->EcuMapMetadataProbe.decodeTransfer(new ByteArrayInputStream(Arrays.copyOf(complete,complete.length-1)),3,end()));
        fails(()->EcuMapMetadataProbe.decodeTransfer(new ByteArrayInputStream(Arrays.copyOf(complete,complete.length+1)),3,end()));
        fails(()->EcuMapMetadataProbe.decodeTransfer(new ByteArrayInputStream(transfer(3,bytes("x"),"{\"code\":0,\"fileName\":\"wrong\",\"fileSize\":1,\"md5\":\"00000000000000000000000000000000\"}")),3,end()));
        byte[] missing=transfer(0,new byte[0],"{\"code\":1}");missing[7]=1;
        try { EcuMapMetadataProbe.decodeTransfer(new ByteArrayInputStream(missing),0,end());throw new AssertionError(); }
        catch(EcuMapMetadataProbe.Failure failure) { check("missing_input0".equals(failure.code)); }
        for(String json:new String[]{"{\"status\":1,\"status\":2}","[1,]","{\"a\":01}","{\"a\":NaN}","{}trailing","\"\\uD800\"",String.join("",Collections.nCopies(34,"["))+"0"+String.join("",Collections.nCopies(34,"]"))})fails(()->EcuMapMetadataProbe.Json.parse(json));
        fails(()->EcuMapMetadataProbe.Json.parse("{\"unknown\":{\"same\":1,\"same\":2}}"));
        fails(()->EcuMapMetadataProbe.utf8(new byte[]{(byte)0xc3,0x28}));
        List<Object> projected=EcuMapMetadataProbe.projectResponse(response(row()));String output=EcuMapMetadataProbe.Json.encode(projected);
        check(output.contains("\"responseId\":1568"));check(output.contains("\"ecuHardDid\":null"));check(!output.contains("PRIVATE_UNKNOWN"));
        check(output.contains("server_candidate_only"));check(output.contains("\"eligibleForProductAllowlist\":false"));
        EcuMapMetadataProbe.privacyCheck(projected,i);assertions++;
        Map<String,Object> spacedRow=EcuMapMetadataProbe.object(EcuMapMetadataProbe.Json.parse(row()));
        for(String stockId:new String[]{"HTH-2021_rear CPD radar_Valeo_00100001",
                "HTH-2021_rear CPD radar_Mindcruise_00100001",
                "HTH-2021_front CPD radar_Valeo_00200001",
                "HTH-2021_front CPD radar_Mindcruise_00100001",
                "HTH-2021_ECP_FULIN PM_00100001"}) {
            spacedRow.put("ecuId",stockId);
            List<Object> rows=EcuMapMetadataProbe.projectResponse(response(EcuMapMetadataProbe.Json.encode(spacedRow)));
            check(stockId.equals(EcuMapMetadataProbe.object(rows.get(0)).get("ecuId")));
        }
        for(String invalidId:new String[]{" ","BAD\tID","BAD\nID","BAD\u00a0ID","BAD/ID"}) {
            spacedRow.put("ecuId",invalidId);
            failsWith("invalid_ecu_projection",()->EcuMapMetadataProbe.projectResponse(
                response(EcuMapMetadataProbe.Json.encode(spacedRow))));
        }
        Map<String,Object> doipRow=EcuMapMetadataProbe.object(EcuMapMetadataProbe.Json.parse(row()));
        doipRow.put("ecuReprogramType","ethernet");doipRow.put("logicAddress","071A");
        for(String absentPair:new String[]{null,""}) {
            doipRow.put("ecuDiaId",absentPair);
            Map<String,Object> route=EcuMapMetadataProbe.object(EcuMapMetadataProbe.object(
                EcuMapMetadataProbe.projectResponse(response(EcuMapMetadataProbe.Json.encode(doipRow))).get(0)).get("route"));
            check(route.get("pairTarget")==null && route.get("pairClient")==null &&
                ((Number)route.get("target")).intValue()==0x71A);
        }
        doipRow.remove("ecuDiaId");
        check(EcuMapMetadataProbe.projectResponse(response(EcuMapMetadataProbe.Json.encode(doipRow))).size()==1);
        doipRow.put("logicAddress","");
        failsWith("invalid_ecu_projection",()->EcuMapMetadataProbe.projectResponse(response(EcuMapMetadataProbe.Json.encode(doipRow))));
        doipRow.put("logicAddress","071A");doipRow.put("ecuReprogramType","canfd");
        failsWith("invalid_ecu_projection",()->EcuMapMetadataProbe.projectResponse(response(EcuMapMetadataProbe.Json.encode(doipRow))));
        fails(()->EcuMapMetadataProbe.privacyCheck(Collections.singletonList(i.token),i));
        fails(()->EcuMapMetadataProbe.privacyCheck(Collections.singletonList("prefix"+i.uuid),i));
        fails(()->EcuMapMetadataProbe.projectResponse(response(row()+","+row())));
        fails(()->EcuMapMetadataProbe.projectResponse(response(row()+",{\"ecuId\":\"BAD\"}")));
        fails(()->EcuMapMetadataProbe.projectResponse(bytes("{\"status\":\"100\",\"ecuList\":[]}")));
        fails(()->EcuMapMetadataProbe.projectResponse(bytes("{\"status\":\"101\",\"ecuList\":["+row()+"]}")));
        try { EcuMapMetadataProbe.projectResponse(bytes("{\"status\":\"101\"}"));throw new AssertionError(); }
        catch(EcuMapMetadataProbe.Failure failure) { check("application_auth".equals(failure.code)); }
        fails(()->EcuMapMetadataProbe.projectResponse(response(row().replace("\"can\"","\"unknown\""))));
        fails(()->EcuMapMetadataProbe.projectResponse(response(row().replace("601/620","FFFFFFFF/620"))));
        String body=new String(response(row()),StandardCharsets.UTF_8);
        check(Arrays.equals(bytes(body),EcuMapMetadataProbe.readHttp(new ByteArrayInputStream(http("Content-Length: "+bytes(body).length+"\r\n",body)),end())));
        String chunk=Integer.toHexString(bytes(body).length)+"\r\n"+body+"\r\n0\r\n\r\n";
        check(Arrays.equals(bytes(body),EcuMapMetadataProbe.readHttp(new ByteArrayInputStream(http("Transfer-Encoding: chunked\r\n",chunk)),end())));
        // A front proxy may repeat headers which this isolated client does not
        // use. Never retain cookie values or reject an otherwise valid body.
        String ignoredHeaders="Set-Cookie: SYNTHETIC_PRIVATE_COOKIE_A\r\n"+
            "sEt-CoOkIe: SYNTHETIC_PRIVATE_COOKIE_B\r\nVary: Accept-Encoding\r\n"+
            "Vary: Origin\r\nX-Trace: synthetic-a\r\nX-Trace: synthetic-b\r\n";
        for(String framing:new String[]{"Content-Length: "+bytes(body).length+"\r\n",
                "Transfer-Encoding: chunked\r\n",""}) {
            String wireBody=framing.startsWith("Transfer")?chunk:body;
            check(Arrays.equals(bytes(body),EcuMapMetadataProbe.readHttp(
                new ByteArrayInputStream(http(ignoredHeaders+framing,wireBody)),end())));
        }
        check(Arrays.equals(bytes(body),EcuMapMetadataProbe.readHttp(new ByteArrayInputStream(
            http("Content-Length:\t"+bytes(body).length+"\t\r\nX-Ignored:\tvalue\r\n",body)),end())));
        for(String repeated:new String[]{
                "Content-Length: 2\r\ncontent-length: 2\r\n",
                "Content-Length: 2\r\nContent-Length: 3\r\n",
                "Content-Type: application/json\r\n",
                "Content-Encoding: identity\r\ncontent-encoding: gzip\r\n",
                "Transfer-Encoding: chunked\r\ntransfer-encoding: chunked\r\n"}) {
            failsWith("http_header_duplicate",()->EcuMapMetadataProbe.readHttp(
                new ByteArrayInputStream(http(repeated,body)),end()));
        }
        for(String malformed:new String[]{"Bad header: value\r\n","MissingColon\r\n",
                " Folded: value\r\n","\tFolded: value\r\n",": value\r\n"}) {
            failsWith("http_header_syntax",()->EcuMapMetadataProbe.readHttp(
                new ByteArrayInputStream(http(malformed,body)),end()));
        }
        String oversized=String.join("",Collections.nCopies(8,"X-Ignored: "+
            String.join("",Collections.nCopies(2048,"x"))+"\r\n"));
        failsWith("http_headers_limit",()->EcuMapMetadataProbe.readHttp(
            new ByteArrayInputStream(http(oversized,body)),end()));
        failsWith("http_line",()->EcuMapMetadataProbe.readHttp(
            new ByteArrayInputStream(http("X-Ignored: bad\u0001value\r\n",body)),end()));
        for(byte[] h:new byte[][]{http("Content-Encoding: gzip\r\n",body),http("Content-Length: 2\r\nTransfer-Encoding: chunked\r\n",body),http("Content-Length: 2\r\nContent-Length: 2\r\n",body),http("Content-Length: 999\r\n",body),http("Content-Length: 2\r\n","{}extra"),bytes("HTTP/1.1 302 Redirect\r\nLocation: https://elsewhere.invalid/\r\n\r\n")})fails(()->EcuMapMetadataProbe.readHttp(new ByteArrayInputStream(h),end()));
        String request=new String(EcuMapMetadataProbe.httpRequest(i),StandardCharsets.UTF_8);
        check(request.startsWith("POST /ota/otaService/getEcuList HTTP/1.1\r\n"));check(request.contains("Authorization: basicSYNTHETIC_TOKEN_ONLY\r\n"));
        check(request.contains("Accept-Encoding: identity\r\n"));check(request.endsWith("{\"version\":\"V1.7.0\",\"uuid\":\"SYNTHETIC_UUID_ONLY\"}"));
        System.out.println("offline assertions="+assertions+"; sockets=0");
    }
}
