package dev.denza.adasstatus.probe;

import java.util.Map;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Host-only grounded wire/state checks, not ECU acceptance. */
public final class StatusProtocolTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length()/2]; for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return bytes;
    }
    private static void reject(long topic, String hex) {
        checks++; try { StatusProtocol.decode(topic, hex(hex)); throw new AssertionError("Accepted malformed " + hex); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> pilot=StatusProtocol.decode(StatusProtocol.PILOT,hex("0a06200228013000"));
        check(pilot.get("icc").equals(2),"ICC available2"); check(pilot.get("dnp").equals(1),"DNP active1");
        Map<String,Object> idle=StatusProtocol.decode(StatusProtocol.PILOT,hex("0a00"));
        check(idle.get("dnp").equals(0),"Proto3 default is0");
        check(idle.get("takeoverStatus").equals(false),"Generated takeoverStatus BOOL default false");
        check(StatusProtocol.decode(StatusProtocol.PILOT,hex("0a023001")).get("takeoverStatus").equals(true),"Generated takeoverStatus BOOL true");
        check(StatusProtocol.availability(2).startsWith("AVAILABLE"),"2notactive");
        check(StatusProtocol.availability(1).startsWith("ACTIVE"),"1notavailable");
        check(StatusProtocol.mode(2).startsWith("CNOA"),"mode2city");
        check(StatusProtocol.mode(1).startsWith("HNOA"),"mode1highway");
        Map<String,Object> mode=StatusProtocol.decode(StatusProtocol.MODE,hex("0a021802"));
        check(mode.get("noaMode").equals(2),"NoaMode field3"); check(!mode.containsKey("dnp"),"Mode never availability");
        Map<String,Object> ack=StatusProtocol.decode(StatusProtocol.SELECTED,hex("0a0d18ffffffffffffffff7f20c801"));
        check(ack.get("pathIDResp").equals(Long.MAX_VALUE),"Exact int64 ACK"); check(ack.get("selectionConfirmed").equals(true),"200success");
        Map<String,Object> badAck=StatusProtocol.decode(StatusProtocol.SELECTED,hex("0a0418012001"));
        check(badAck.get("selectionConfirmed").equals(false),"1not200");
        // Schema-grounded SYNTHETIC service44 fixture: these topics were not in
        // the 2026-10-23 native capture and are never claimed as live evidence.
        Map<String,Object> matched=StatusProtocol.decode(StatusProtocol.PATH_MATCH,
                hex("0a1d18c801221408ffffffffffffffff7f10b9601803200728c8012a026f6b"));
        check(matched.get("code").equals(200L),"PathMatch code field3");
        List<?> routes=(List<?>)matched.get("routeInfoArray");check(routes.size()==1,"PathMatch repeated RouteInfo");
        Map<?,?> route=(Map<?,?>)routes.get(0);
        check(route.get("pathID").equals(Long.MAX_VALUE),"PathMatch uint64 raw bits in Java long");
        check(route.get("totalNOADistance").equals(12345L),"PathMatch distance field2");
        check(route.get("routeBeginIdx").equals(3L)&&route.get("routeEndIdx").equals(7L),"PathMatch range fields3/4");
        check(matched.get("msg").equals("ok"),"PathMatch UTF8 message");
        // Actual first alarm packet from status-car/adas-status-01.jsonl. Both
        // timestamps are DOUBLE/wire1; retain numeric values without assigning units.
        Map<String,Object> alarm=StatusProtocol.decode(StatusProtocol.ALARM,
                hex("0a18088ebed888093190647922117a194349bc0cb954197a1943"));
        check(alarm.get("alarmTimestamp").equals(1792772107295012.0),"Native alarm DOUBLE field6 exact");
        check(alarm.get("noticeTimestamp").equals(1792780907987759.0),"Native notice DOUBLE field9 exact");
        check(alarm.get("pilotAlarmReason").equals(0)&&alarm.get("alarmStage").equals(0),"Native omitted alarm fields default0");
        check(StatusProtocol.decode(StatusProtocol.ALARM,hex("0a00")).get("alarmTimestamp").equals(0.0),"Double default0.0");
        reject(StatusProtocol.ALARM,"0a023001"); // A varint is not a DOUBLE timestamp.
        reject(StatusProtocol.ALARM,"0a0731000000000000");
        reject(StatusProtocol.ALARM,"0a0931000000000000f87f"); // NaN cannot enter JSON/state.
        reject(StatusProtocol.PILOT, "091802200228023000"); // Wrong outer fixed64 previously impersonated availability.
        reject(StatusProtocol.PILOT, "0a0520022802"); reject(StatusProtocol.PILOT,"0a0120"); reject(StatusProtocol.PILOT,"0a020e00");
        reject(StatusProtocol.SELECTED,"0a0518012d0000"); reject(0x0004000700078001L,"0a00");
        StatusModel model=new StatusModel();model.control("binder_connected", "own-session");model.apply(pilot,1000,false);
        check(model.freshness(1100).startsWith("RECENT"),"Recent observed status");
        check(model.freshness(10000).startsWith("UNKNOWN"),"Silence is unknown freshness");
        check(model.dnp.equals(1),"Silence retains last value");
        model.apply(mode,11000,false);check(model.dnp.equals(1),"Mode doesnotupgradeavailability");
        model.control("binder_disconnected", "");check(model.freshness(1200).equals("UNKNOWN"),"Disconnect invalidates");
        model.control("binder_connected", "new");check(model.freshness(1200).equals("UNKNOWN"),"Reconnect needs new observation");
        check(model.render(1200,3).contains("активен (DNP 1)"),"Last raw value retained and labelled");
        StatusTransitions transitions=new StatusTransitions();
        int nativeFrames=0,pilotFrames=0,alarmFrames=0,modeFrames=0,changes=0;
        for(String line:Files.readAllLines(Paths.get("experiments/adas-status-probe/src/test/fixtures/native-status-car-20261023.txt"))) {
            if(line.startsWith("#")||line.trim().isEmpty())continue;
            String[] parts=line.split(" ");long topic=Long.decode(parts[0]);
            Map<String,Object> event=StatusProtocol.decode(topic,hex(parts[1]));nativeFrames++;
            if(topic==StatusProtocol.PILOT)pilotFrames++;
            else if(topic==StatusProtocol.ALARM)alarmFrames++;
            else if(topic==StatusProtocol.MODE)modeFrames++;
            if(transitions.accept("native_someip",event)!=null)changes++;
        }
        check(nativeFrames==384&&pilotFrames==128&&alarmFrames==128&&modeFrames==128,"All384 actual native callback fixtures decode");
        check(changes==3,"384 unchanged native packets produce only3 initial semantic observations");
        java.util.Map<String,Object> changedPilot=new java.util.LinkedHashMap<>(pilot);
        check(transitions.accept("native_someip",changedPilot)!=null,"Actual pilot value transition recorded");
        check(transitions.accept("native_someip",changedPilot)==null,"Repeated pilot value coalesced");
        java.util.Map<String,Object> timestampOnly=new java.util.LinkedHashMap<>(alarm);
        timestampOnly.put("alarmTimestamp",1.0);timestampOnly.put("noticeTimestamp",2.0);timestampOnly.put("alarmDistance",300);
        check(transitions.accept("native_someip",timestampOnly)==null,"Timestamp/counter/distance churn is not an alarm identity change");
        timestampOnly.put("pilotAlarmReason",501);
        check(transitions.accept("native_someip",timestampOnly)!=null,"Alarm reason transition recorded");
        System.out.println("Native regression:384 packets,3 observed topics;44/selected and44/PathMatch synthetic schema fixtures");
        System.out.println("PASS " + checks + " host wire/state checks");
    }
}
