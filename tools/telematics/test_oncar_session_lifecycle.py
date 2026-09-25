#!/usr/bin/env python3
"""Host-only lifecycle regression: real helper class bodies, synthetic processes/TLS.

No Android runtime, ADB, firmware, signing or network. The Java nested classes
are extracted verbatim at test time, so these tests do not duplicate cleanup.
"""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import textwrap
import unittest


HERE = Path(__file__).resolve().parent


def nested(source, start, end):
    text = (HERE / source).read_text()
    return text[text.index(start):text.index(end)]


WORKER_SCRIPT = """\
import os, pathlib, signal, sys
directory = pathlib.Path(__file__).parent
signal.signal(signal.SIGTERM, signal.SIG_IGN)
signal.alarm(12)
(directory / ('pid-' + str(os.getpid()))).write_text(str(os.getpid()))
mode = (directory / 'mode').read_text()
print('BROKEN' if mode == 'bad_ready' else 'READY', flush=True)
if mode == 'bad_ready':
    while True: signal.pause()
for line in sys.stdin:
    command = line.split()[0]
    if mode == 'bad_input':
        print('REJECTED', flush=True)
        while True: signal.pause()
    if command in ('HANG', 'QUIT'):
        (directory / ('hanging-' + str(os.getpid()))).touch()
        while True: signal.pause()
    print('WIRE 0102' if command == 'L' else
          ('LOGIN 0' if mode == 'login_reject' else 'LOGIN 1') if command == 'R220' else
          'OK', flush=True)
"""

SOURCE_SCRIPT = """\
import os, pathlib, signal, sys
directory = pathlib.Path(__file__).parent
mode = os.environ.get('SOURCE_MODE', (directory / 'mode').read_text())
signal.signal(signal.SIGTERM, signal.SIG_IGN)
signal.alarm(20)
(directory / ('pid-' + str(os.getpid()))).write_text(str(os.getpid()))
if mode != 'source_timeout':
    print('[CloudCanSnapshotProbe] READY callback=1', flush=True)
if mode == 'source_timeout':
    while True: signal.pause()
for line in sys.stdin:
    if line == 'STOP\\n' and mode != 'source_stubborn':
        (directory / ('stop-' + str(os.getpid()))).touch()
        if mode != 'source_missing_unregistered':
            print('[CloudCanSnapshotProbe] UNREGISTERED', flush=True)
        print('[CloudCanSnapshotProbe] DONE dropped=' +
              ('1' if mode == 'source_dirty_done' else '0') +
              ' callback_errors=0 queued=0', flush=True)
        break
    if mode == 'source_stubborn':
        while True: signal.pause()
"""


HOST_JAVA = r"""
package dev.denza.tools;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.net.*;
import java.security.Permission;
import javax.net.ssl.*;

class OncarNativeSession {
    static String dir;static int signatureTotal;
    static void need(boolean ok,String why){OncarTls.need(ok,why);}
    static void event(String message){}
    static byte[] le(int value){return new byte[4];}
    static byte[] nonce(){return new byte[16];}
    static byte[] frame(InputStream input)throws IOException {
        if(input.read()<0)throw new EOFException();return new byte[]{1};
    }
    static final class Inputs {
        final byte[] vin=new byte[17],parameters=new byte[33],serial=new byte[0],
            iccid=new byte[20],imsi=new byte[15];
    }
    __WORKER__
    __SOURCE__
}
class SystemClock {static long elapsedRealtime(){return System.nanoTime()/1000000;}}
class OncarControlSession {
    static int signedSessions;
    static void need(boolean ok,String why){OncarTls.need(ok,why);}
    __CHANNEL__
}
class OncarTls {
    static int signatureCount(){return 1;}static FakeSocket socket;
    static void need(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    static String hex(byte[] value){return HexFormat.of().formatHex(value);}
    static byte[] unhex(String value){return HexFormat.of().parseHex(value);}
    static SSLSocket connect(String host,int port){return socket;}
}
class FakeSocket extends SSLSocket {
    boolean closed,failRead,failTimeout;
    public InputStream getInputStream(){return new InputStream(){
        public int read()throws IOException{if(failRead)throw new IOException("read failure");return 1;}
    };}
    public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
    public void setSoTimeout(int value)throws SocketException{if(failTimeout)throw new SocketException("timeout setup failure");}
    public void close(){closed=true;}
    public String[] getSupportedCipherSuites(){return new String[0];}
    public String[] getEnabledCipherSuites(){return new String[0];}
    public void setEnabledCipherSuites(String[] value){}
    public String[] getSupportedProtocols(){return new String[0];}
    public String[] getEnabledProtocols(){return new String[0];}
    public void setEnabledProtocols(String[] value){}
    public SSLSession getSession(){return null;}
    public void addHandshakeCompletedListener(HandshakeCompletedListener listener){}
    public void removeHandshakeCompletedListener(HandshakeCompletedListener listener){}
    public void startHandshake(){}
    public void setUseClientMode(boolean value){}
    public boolean getUseClientMode(){return true;}
    public void setNeedClientAuth(boolean value){}
    public boolean getNeedClientAuth(){return false;}
    public void setWantClientAuth(boolean value){}
    public boolean getWantClientAuth(){return false;}
    public void setEnableSessionCreation(boolean value){}
    public boolean getEnableSessionCreation(){return true;}
}
public class LifecycleHarness {
    static void need(boolean ok,String why){OncarTls.need(ok,why);}
    static OncarNativeSession.Worker worker()throws Exception {
        return new OncarNativeSession.Worker(new OncarNativeSession.Inputs());
    }
    static Process sourceChild(Path directory)throws Exception {
        return new ProcessBuilder(directory.resolve("source-reader").toString()).start();
    }
    static Process sourceChild(Path directory,String behavior)throws Exception {
        ProcessBuilder builder=new ProcessBuilder(directory.resolve("source-reader").toString());
        builder.environment().put("SOURCE_MODE",behavior);return builder.start();
    }
    static final class StuckProcess extends Process {
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int waitFor(){throw new IllegalThreadStateException();}
        public boolean waitFor(long value,TimeUnit unit){return false;}
        public int exitValue(){throw new IllegalThreadStateException();}
        public void destroy(){}
        public Process destroyForcibly(){return this;}
        public boolean isAlive(){return true;}
    }
    static void awaitMarker(Path marker)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(5);
        need(Files.exists(marker),"worker did not reach blocked read");
    }
    static void verifyChildrenExited(Path directory)throws Exception {
        try(var files=Files.list(directory)){
            for(Path file:files.filter(p->p.getFileName().toString().startsWith("pid-")).toList()){
                long pid=Long.parseLong(Files.readString(file));
                need(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"constructor leaked child "+pid);
            }
        }
    }
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);String mode=args[1];OncarNativeSession.dir=args[0];
        Files.writeString(directory.resolve("mode"),mode);
        if(mode.equals("source_normal")){
            Process child=sourceChild(directory);OncarNativeSession.Source source=new OncarNativeSession.Source(child);
            source.close();source.close();
            need(!child.isAlive()&&source.unregistered&&source.done&&source.clean,"normal STOP/unregister missing");
            need(Files.exists(directory.resolve("stop-"+child.pid())),"STOP was not received");
        }else if(mode.equals("source_stubborn")){
            Process child=sourceChild(directory);OncarNativeSession.Source source=new OncarNativeSession.Source(child);
            long start=System.nanoTime();boolean rejected=false;
            try{source.close();}catch(IllegalStateException expected){rejected="SDK reader shutdown incomplete".equals(expected.getMessage());}
            need(rejected&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(10)&&!child.isAlive(),
                "stubborn source survived bounded close or falsely succeeded");
        }else if(mode.equals("source_dirty_done")||mode.equals("source_missing_unregistered")){
            Process child=sourceChild(directory);OncarNativeSession.Source source=new OncarNativeSession.Source(child);
            boolean rejected=false;
            try{source.close();}catch(IllegalStateException expected){rejected="SDK reader shutdown incomplete".equals(expected.getMessage());}
            need(rejected&&!child.isAlive()&&source.done,"dirty or incomplete source shutdown falsely succeeded");
        }else if(mode.equals("source_sibling")){
            Process first=sourceChild(directory,"source_stubborn"),second=sourceChild(directory,"source_normal");
            OncarNativeSession.Source one=new OncarNativeSession.Source(first),two=new OncarNativeSession.Source(second);
            boolean rejected=false;
            try{one.close();}catch(IllegalStateException expected){rejected="SDK reader shutdown incomplete".equals(expected.getMessage());}
            need(rejected&&!first.isAlive()&&second.isAlive(),"failed source cleanup touched sibling or falsely succeeded");
            two.close();need(!second.isAlive(),"sibling source remained");
        }else if(mode.equals("source_timeout")){
            Process child=sourceChild(directory);boolean rejected=false;
            try{new OncarNativeSession.Source(child);}catch(IllegalStateException expected){rejected="SDK reader readiness".equals(expected.getMessage());}
            need(rejected&&!child.isAlive(),"readiness failure leaked source or lost original exception");
        }else if(mode.equals("source_thread_failure")){
            Process child=sourceChild(directory);boolean rejected=false;
            System.setSecurityManager(new SecurityManager(){
                public void checkPermission(Permission permission){}
                public void checkAccess(ThreadGroup group){throw new SecurityException("source thread creation failure");}
            });
            try{new OncarNativeSession.Source(child);}
            catch(SecurityException expected){rejected="source thread creation failure".equals(expected.getMessage());}
            finally{System.setSecurityManager(null);}
            need(rejected&&!child.isAlive(),"thread creation failure leaked source or lost original exception");
        }else if(mode.equals("source_interrupted_constructor")){
            Process child=sourceChild(directory);boolean rejected=false;
            Thread.currentThread().interrupt();
            try{new OncarNativeSession.Source(child);}catch(InterruptedException expected){rejected=true;}
            need(rejected&&Thread.interrupted()&&!child.isAlive(),"interrupted constructor leaked source or interrupt");
        }else if(mode.equals("source_interrupted_close")){
            Process child=sourceChild(directory);OncarNativeSession.Source source=new OncarNativeSession.Source(child);
            boolean rejected=false;Thread.currentThread().interrupt();
            try{source.close();}catch(IllegalStateException expected){rejected="SDK reader shutdown incomplete".equals(expected.getMessage());}
            need(rejected&&Thread.interrupted()&&!child.isAlive(),
                "interrupted close leaked source, interrupt, or falsely succeeded");
        }else if(mode.equals("source_suppressed_cleanup")){
            boolean original=false,suppressed=false;Thread.currentThread().interrupt();
            try{new OncarNativeSession.Source(new StuckProcess());}
            catch(InterruptedException expected){original=true;suppressed=expected.getSuppressed().length==1
                &&"SDK reader child did not terminate".equals(expected.getSuppressed()[0].getMessage());}
            need(original&&suppressed&&Thread.interrupted(),"startup error or suppressed cleanup lost");
        }else if(mode.equals("bad_ready")||mode.equals("bad_input")){
            boolean rejected=false;
            try(OncarNativeSession.Worker unexpected=worker()){}
            catch(IllegalStateException expected){rejected=true;}
            need(rejected,"constructor unexpectedly succeeded");verifyChildrenExited(directory);
        }else if(mode.equals("blocked_close")){
            try(OncarNativeSession.Worker first=worker();OncarNativeSession.Worker sibling=worker()){
                Thread blocked=new Thread(()->{try{first.exchange("HANG");}catch(Exception expected){}});
                blocked.start();awaitMarker(directory.resolve("hanging-"+first.process.pid()));
                long start=System.nanoTime();first.close();
                need(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(5),"close exceeded bound");
                blocked.join(500);need(!blocked.isAlive(),"IPC reader remained blocked");
                need(!first.process.isAlive()&&sibling.process.isAlive(),"wrong process terminated");
            }
            verifyChildrenExited(directory);
        }else if(mode.equals("interrupted_close")){
            try(OncarNativeSession.Worker current=worker()){
                Thread.currentThread().interrupt();current.close();
                need(Thread.interrupted(),"interrupt was lost");need(!current.process.isAlive(),"interrupted close leaked child");
            }
        }else{
            try(OncarNativeSession.Worker current=worker()){
                FakeSocket socket=new FakeSocket();OncarTls.socket=socket;
                socket.failRead=mode.equals("read_failure");socket.failTimeout=mode.equals("reader_setup_failure");
                boolean rejected=false;
                if(mode.equals("thread_creation_failure"))System.setSecurityManager(new SecurityManager(){
                    public void checkPermission(Permission permission){}
                    public void checkAccess(ThreadGroup group){throw new SecurityException("thread creation failure");}
                });
                try{new OncarControlSession.Channel(current);}
                catch(Exception expected){rejected=true;}
                finally{if(mode.equals("thread_creation_failure"))System.setSecurityManager(null);}
                need(rejected&&socket.closed,"failed Channel leaked socket");
                need(current.process.isAlive(),"Channel took ownership of caller's worker");
            }
        }
        System.out.println("PASS "+mode);
    }
}
"""


class SessionLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="denza-session-lifecycle-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        java_home = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
        javac = str(Path(java_home) / "bin/javac")
        cls.java = str(Path(java_home) / "bin/java")
        if not Path(javac).is_file():
            javac, cls.java = shutil.which("javac"), shutil.which("java")
        if not javac or not cls.java:
            raise unittest.SkipTest("JDK 17 required for host lifecycle checks")
        worker = nested("OncarNativeSession.java", "    static final class Worker ", "    static final class Row ")
        source_body = nested("OncarNativeSession.java", "    static final class Row ", "    static byte[] frame(")
        channel = nested("OncarControlSession.java", "    static final class Channel ", "    static int exchange(")
        source = cls.root / "LifecycleHarness.java"
        source.write_text(HOST_JAVA.replace("__WORKER__", worker).replace("__SOURCE__", source_body)
                          .replace("__CHANNEL__", channel))
        subprocess.run([javac, "-d", str(cls.root), str(source)], check=True, capture_output=True, text=True, timeout=20)

    def run_case(self, mode):
        directory = self.root / mode
        directory.mkdir()
        executable = directory / "native-session-worker"
        executable.write_text("#!" + sys.executable + "\n" + textwrap.dedent(WORKER_SCRIPT))
        executable.chmod(0o700)
        source_executable = directory / "source-reader"
        source_executable.write_text("#!" + sys.executable + "\n" + textwrap.dedent(SOURCE_SCRIPT))
        source_executable.chmod(0o700)
        try:
            result = subprocess.run([self.java, "-cp", str(self.root), "dev.denza.tools.LifecycleHarness",
                                     str(directory), mode], capture_output=True, text=True, timeout=25)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("PASS " + mode, result.stdout)
        finally:
            for file in directory.glob("pid-*"):
                try:
                    pid = int(file.read_text())
                    command = subprocess.run(["ps", "-p", str(pid), "-o", "command="],
                                             capture_output=True, text=True, timeout=2).stdout
                    # A finished child's PID may have been reused; never kill by PID alone.
                    if str(executable) in command:
                        os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_close_unblocks_reader_and_only_kills_owned_process(self):
        self.run_case("blocked_close")

    def test_partial_constructor_closes_process(self):
        self.run_case("bad_ready")

    def test_failed_initialization_closes_process(self):
        self.run_case("bad_input")

    def test_interrupted_close_still_terminates_process(self):
        self.run_case("interrupted_close")

    def test_rejected_login_closes_socket(self):
        self.run_case("login_reject")

    def test_failed_login_read_closes_socket(self):
        self.run_case("read_failure")

    def test_failed_reader_setup_closes_socket(self):
        self.run_case("reader_setup_failure")

    def test_failed_thread_creation_closes_socket(self):
        self.run_case("thread_creation_failure")

    def test_source_normal_stop_unregisters_and_exits(self):
        self.run_case("source_normal")

    def test_source_stubborn_child_is_forcibly_terminated(self):
        self.run_case("source_stubborn")

    def test_source_dirty_done_cannot_pass(self):
        self.run_case("source_dirty_done")

    def test_source_missing_unregister_cannot_pass(self):
        self.run_case("source_missing_unregistered")

    def test_source_close_preserves_sibling_process(self):
        self.run_case("source_sibling")

    def test_source_readiness_failure_closes_child(self):
        self.run_case("source_timeout")

    def test_source_thread_creation_failure_closes_child(self):
        self.run_case("source_thread_failure")

    def test_source_interrupted_constructor_closes_child_and_restores_interrupt(self):
        self.run_case("source_interrupted_constructor")

    def test_source_interrupted_close_closes_child_and_restores_interrupt(self):
        self.run_case("source_interrupted_close")

    def test_source_startup_error_keeps_suppressed_cleanup_error(self):
        self.run_case("source_suppressed_cleanup")


if __name__ == "__main__":
    unittest.main()
