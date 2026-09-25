package dev.denza.tools.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Host control-plane tests. Uses a fake owner and never opens a vehicle or network connection. */
public final class CloudRuntimeProtocolTest {
    static void need(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static final class Clock implements CloudRuntimeSupervisor.Clock {
        volatile long now;
        public long nowMs() { return now; }
    }
    static final class Owner implements CloudRuntimeSupervisor.OwnerLock {
        boolean held, closed;
        public boolean acquire() { held = true; return true; }
        public void release() { held = false; }
        public void close() { held = false; closed = true; }
    }
    static CloudRuntimeSupervisor supervisor(Clock clock, Owner owner) {
        return new CloudRuntimeSupervisor(clock, owner, () -> { },
            (scope, identity, sink) -> {
                while (!scope.cancelled()) Thread.sleep(5);
            }, code -> { throw new AssertionError("fatal exit " + code); });
    }
    static void rejects(CloudRuntimeProtocol protocol, String raw) {
        try { protocol.handle(raw); throw new AssertionError("accepted malformed request"); }
        catch (IllegalArgumentException expected) { }
    }
    static void commands() throws Exception {
        Clock clock = new Clock(); Owner owner = new Owner();
        CloudRuntimeSupervisor supervisor = supervisor(clock, owner);
        CloudRuntimeProtocol protocol = new CloudRuntimeProtocol(supervisor, 4242);
        rejects(protocol, "{\"id\":1.5,\"op\":\"STATUS\"}");
        rejects(protocol, "{\"id\":\"1\",\"op\":\"STATUS\"}");
        rejects(protocol, "{\"id\":1,\"op\":\"STATUS\",\"extra\":0}");
        rejects(protocol, "{\"id\":1,\"op\":\"START\",\"iccid\":\"123\",\"imsi\":\"123\"}");
        rejects(protocol, "{\"id\":1,\"op\":\"STATUS\",\"id\":2}");
        rejects(protocol, "{\"id\":1,\"op\":\"STATUS\"}" + " ".repeat(4096));
        JSONObject probe = new JSONObject(protocol.handle("{\"id\":1,\"op\":\"PROBE\"}"));
        need(probe.getBoolean("ok") && probe.getInt("protocol") == 1 &&
            probe.getString("code").equals("owner_absent_confirmed"), "probe contract");
        need(probe.getInt("pid") == 4242 && probe.has("native_events") &&
            probe.has("callback_age_ms") && probe.has("commands_completed"), "status fields");
        String[] appFields = {"id","op","protocol","ok","pid","session_live","stage","code",
            "updated_elapsed_ms","connected_elapsed_ms","last_rx_elapsed_ms","last_tx_elapsed_ms",
            "last_report_elapsed_ms","next_retry_elapsed_ms","attempts","reports_sent",
            "status_replies","commands_forwarded","commands_completed","reconnects",
            "callback_age_ms","native_events"};
        for (String field : appFields) need(probe.has(field), "missing app field " + field);
        rejects(protocol, "{\"id\":1,\"op\":\"STATUS\"}");
        String pair = "{\"id\":2,\"op\":\"START\",\"iccid\":\"89010000000000000001\",\"imsi\":\"001010123456789\",\"lease_until_uptime_ms\":30000}";
        String started = protocol.handle(pair);
        need(!started.contains("890100") && !started.contains("001010"), "SIM pair leaked");
        JSONObject start = new JSONObject(started);
        need(start.getBoolean("ok") && start.getString("op").equals("START"), "start contract");
        JSONObject status = new JSONObject(protocol.handle("{\"id\":3,\"op\":\"STATUS\",\"lease_until_uptime_ms\":30000}"));
        need(status.getBoolean("ok") && status.getLong("id") == 3, "status contract");
        clock.now=5000;
        JSONObject permit = new JSONObject(protocol.handle("{\"id\":4,\"op\":\"PERMIT\",\"lease_until_uptime_ms\":35000}"));
        need(permit.getBoolean("ok"),"explicit permit contract");
        JSONObject stopped = new JSONObject(protocol.handle("{\"id\":5,\"op\":\"STOP\"}"));
        need(stopped.getBoolean("ok") && stopped.getString("stage").equals("stopped") &&
            !stopped.getBoolean("session_live"), "stop contract");
        supervisor.awaitCleanup(1000); need(owner.closed, "owner lock not closed");
    }
    static void malformedDoesNotRenew() throws Exception {
        Clock clock = new Clock(); Owner owner = new Owner();
        CloudRuntimeSupervisor supervisor = supervisor(clock, owner);
        CloudRuntimeProtocol protocol = new CloudRuntimeProtocol(supervisor, 4242);
        clock.now = 29_000;
        rejects(protocol, "{\"id\":1,\"op\":\"STATUS\",\"extra\":0}");
        clock.now = 30_001;
        supervisor.watchdogTick(); supervisor.awaitCleanup(1000);
        need(supervisor.snapshot().code == CloudRuntimeSupervisor.Code.LEASE_EXPIRED,
            "malformed command renewed lease");
    }
    static void statusCannotExtendAbsoluteCeiling() throws Exception {
        Clock clock=new Clock();Owner owner=new Owner();
        CloudRuntimeSupervisor supervisor=supervisor(clock,owner);
        CloudRuntimeProtocol protocol=new CloudRuntimeProtocol(supervisor,4242);
        protocol.handle("{\"id\":1,\"op\":\"PROBE\"}");
        JSONObject start=new JSONObject(protocol.handle("{\"id\":2,\"op\":\"START\",\"iccid\":\"89010000000000000001\",\"imsi\":\"001010123456789\",\"lease_until_uptime_ms\":30000}"));
        need(start.getBoolean("ok"),"bounded START refused");
        clock.now=29_999;
        JSONObject status=new JSONObject(protocol.handle("{\"id\":3,\"op\":\"STATUS\",\"lease_until_uptime_ms\":30000}"));
        need(status.getBoolean("ok"),"same ceiling STATUS refused");
        clock.now=30_000;supervisor.watchdogTick();supervisor.awaitCleanup(1000);
        need(supervisor.snapshot().code==CloudRuntimeSupervisor.Code.LEASE_EXPIRED && !supervisor.snapshot().sessionLive,
            "STATUS extended worker beyond guardian ceiling");
        JSONObject late=new JSONObject(protocol.handle("{\"id\":4,\"op\":\"PERMIT\",\"lease_until_uptime_ms\":60000}"));
        need(!late.getBoolean("ok") && !owner.held,"expired worker accepted late renewal");
    }
    static void residentFraming() throws Exception {
        Clock clock = new Clock(); Owner owner = new Owner();
        CloudRuntimeSupervisor supervisor = supervisor(clock, owner);
        CloudRuntimeProtocol protocol = new CloudRuntimeProtocol(supervisor, 4242);
        String input = "{\"id\":1,\"op\":\"PROBE\"}\n{\"id\":2,\"op\":\"STATUS\",\"lease_until_uptime_ms\":30000}\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        protocol.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(bytes, true, "UTF-8"), "00112233445566778899aabbccddeeff");
        String output = bytes.toString("UTF-8");
        need(output.startsWith("DENZA_SERVE_00112233445566778899aabbccddeeff:READY\n"), "READY framing");
        need(output.split(":BEGIN\\n", -1).length == 3 &&
            output.split(":END ok\\n", -1).length == 3, "request framing");
        need(!output.contains("iccid") && !output.contains("imsi"), "identifier label on stdout");
        supervisor.awaitCleanup(1000); need(owner.closed, "EOF did not close owner");
        CloudRuntimeSupervisor malformedSupervisor = supervisor(new Clock(), new Owner());
        ByteArrayOutputStream malformedBytes = new ByteArrayOutputStream();
        new CloudRuntimeProtocol(malformedSupervisor, 4242).serve(
            new ByteArrayInputStream("{\"id\":1,\"op\":\"STATUS\",\"bad\":1}\n".getBytes(StandardCharsets.UTF_8)),
            new PrintStream(malformedBytes, true, "UTF-8"), "00112233445566778899aabbccddeeff");
        need(!malformedBytes.toString("UTF-8").contains(":BEGIN"), "malformed line got answer");
        malformedSupervisor.awaitCleanup(1000);
        CloudRuntimeSupervisor badUtfSupervisor = supervisor(new Clock(), new Owner());
        try {
            new CloudRuntimeProtocol(badUtfSupervisor, 4242).serve(
                new ByteArrayInputStream(new byte[]{(byte)0xc3, (byte)0x28, '\n'}),
                new PrintStream(new ByteArrayOutputStream()), "00112233445566778899aabbccddeeff");
            throw new AssertionError("invalid UTF-8 accepted");
        } catch (IOException expected) { badUtfSupervisor.awaitCleanup(1000); }
    }
    static void protocolFdChild() throws Exception {
        Clock clock=new Clock();Owner owner=new Owner();
        CloudRuntimeSupervisor supervisor=supervisor(clock,owner);
        CloudRuntimeProtocol protocol=new CloudRuntimeProtocol(supervisor,4242);
        String start="{\"id\":1,\"op\":\"START\",\"iccid\":\"89010000000000000001\","
            +"\"imsi\":\"001010123456789\",\"lease_until_uptime_ms\":30000}\n";
        String status="{\"id\":2,\"op\":\"STATUS\",\"lease_until_uptime_ms\":30000}\n";
        String stop="{\"id\":3,\"op\":\"STOP\"}\n";
        AtomicBoolean injected=new AtomicBoolean();
        ByteArrayInputStream input=new ByteArrayInputStream((start+status+stop).getBytes(StandardCharsets.UTF_8)){
            @Override public synchronized int read(){
                if(!injected.get()&&pos==start.length()){
                    System.out.print("incidental Java stdout after START\n");
                    try{
                        int result=new ProcessBuilder("/bin/echo","incidental native stdout after START")
                            .inheritIO().start().waitFor();
                        need(result==0,"native stdout injection failed");
                    }catch(Exception failure){throw new AssertionError(failure);}
                    injected.set(true);
                }
                return super.read();
            }
        };
        // The test launcher supplies fd 3 as the protocol pipe and fd 1 as
        // /dev/null, matching the worker's dup + dup2 descriptor topology.
        try(PrintStream channel=new PrintStream(new FileOutputStream("/dev/fd/3"),true,"UTF-8")){
            protocol.serve(input,channel,"00112233445566778899aabbccddeeff");
        }
        need(injected.get(),"stdout injection missed");
        supervisor.awaitCleanup(1000);need(owner.closed,"worker owner remained after EOF");
    }
    static void incidentalStdoutAfterStartCannotCorruptStatus() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder("/bin/sh","-c","exec 3>&1 1>/dev/null; exec \"$@\"","sh",
            java,"-cp",System.getProperty("java.class.path"),
            CloudRuntimeProtocolTest.class.getName(),"protocol-fd-child").start();
        child.getOutputStream().close();
        String output;
        try{
            need(child.waitFor(10,TimeUnit.SECONDS),"isolated protocol process hung");
            need(child.exitValue()==0,"isolated protocol process failed: "
                +new String(child.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));
            output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }finally{child.destroyForcibly();}
        String[] lines=output.split("\n");
        need(lines.length==10&&lines[1].endsWith(":BEGIN")&&lines[4].endsWith(":BEGIN")&&
            lines[7].endsWith(":BEGIN")&&new JSONObject(lines[5]).getString("op").equals("STATUS")&&
            !output.contains("incidental"),
            "incidental stdout corrupted STATUS framing");
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("protocol-fd-child")){protocolFdChild();return;}
        commands(); malformedDoesNotRenew(); statusCannotExtendAbsoluteCeiling(); residentFraming();
        incidentalStdoutAfterStartCannotCorruptStatus();
        System.out.println("PASS cloud runtime protocol");
    }
}
