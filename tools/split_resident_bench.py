#!/usr/bin/env python3
"""What the resident shell-UID split helper costs on the car, against the one-shot it replaces.

Reads only. It stands the product's own helper up and asks it the read requests the recipes ask,
then compares its world read with `am stack list` byte for byte - which is the one thing about
this helper that a unit test cannot prove. It never sends `remove-task`.

The channel is the one the product opens and not a convenient substitute: the adb server's own
`shell:sh` service, which on this adbd hands back a terminal. That distinction is not academic -
it is exactly what a `adb shell <command>` probe hid in v31, when the frame bytes the product put
in its command lines were eaten by the terminal's line editor.

It needs the packed jar on the device, so it pushes one copy to /data/local/tmp (the same place
every other shell-UID helper of this project is staged) and removes it at the end. Build it first:

    ./gradlew :denza-apps:packDebugSplitTaskProxy

Usage: tools/split_resident_bench.py [serial]
"""
import os
import socket
import subprocess
import sys
import time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:5555"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(
    ROOT, "apps/denza-apps/build/generated/assets/packDebugSplitTaskProxy/split-task-proxy.jar")
REMOTE = "/data/local/tmp/denza-split-resident-bench.jar"
CLASS = "dev.denza.apps.feature.split.SplitTaskProxyMain"
NONCE = "bench%d" % (time.time() * 1000)


def adb(*args, **kwargs):
    return subprocess.run(["adb", "-s", SERIAL, *args], text=True, capture_output=True, **kwargs)


if not os.path.isfile(JAR):
    sys.exit("no packed jar at %s - run ./gradlew :denza-apps:packDebugSplitTaskProxy" % JAR)

pushed = adb("push", JAR, REMOTE)
if pushed.returncode != 0:
    sys.exit("could not stage the jar: " + pushed.stderr.strip())

launch = ("CLASSPATH=%s exec app_process /system/bin --nice-name=denza_split_serve %s serve %s"
          % (REMOTE, CLASS, NONCE))

BEGIN = "DENZA_SERVE_%s:BEGIN" % NONCE
END = "DENZA_SERVE_%s:END" % NONCE
READY = "DENZA_SERVE_%s:READY" % NONCE


class LegacyShell:
    """The adb server's `shell:sh` on this serial - the product's own channel, terminal and all."""

    def __init__(self, serial):
        self.sock = socket.create_connection(("127.0.0.1", 5037), timeout=10)
        self._service("host:transport:" + serial)
        self._service("shell:sh")
        self.sock.settimeout(0.25)
        self.received = ""

    def _service(self, name):
        payload = name.encode()
        self.sock.sendall(b"%04x" % len(payload) + payload)
        status = b""
        while len(status) < 4:
            chunk = self.sock.recv(4 - len(status))
            if not chunk:
                sys.exit("the adb server closed the connection")
            status += chunk
        if status != b"OKAY":
            sys.exit("adb server refused %r: %s" % (name, status + self.sock.recv(4096)))

    def send(self, line):
        self.sock.sendall((line + "\n").encode())

    def until(self, ends, budget=15.0):
        """Reads until [ends] answers on the text received so far, or the budget runs out."""
        deadline = time.time() + budget
        while time.time() < deadline:
            found = ends(self.received)
            if found is not None:
                return found
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            self.received += chunk.decode("utf-8", "replace")
        return ends(self.received)

    def close(self):
        self.sock.close()


helper = LegacyShell(SERIAL)
started = time.time()
helper.send(launch)
if helper.until(lambda text: True if READY in text else None) is None:
    sys.exit("the helper never came up:\n" + helper.received[-600:])
helper.received = ""


def ask(request):
    """@return (milliseconds, payload, status)."""
    helper.received = ""
    began = time.time()
    helper.send(request)

    def finished(text):
        start = text.find(BEGIN)
        if start < 0:
            return None
        start = text.find("\n", start)
        if start < 0:
            return None
        end = text.find(END, start)
        if end < 0:
            return None
        status_end = text.find("\n", end)
        if status_end < 0:
            return None
        return text[start + 1:end], text[end + len(END):status_end].strip()

    answered = helper.until(finished)
    if answered is None:
        return None, None, "no answer: " + repr(helper.received[-300:])
    return int((time.time() - began) * 1000), answered[0], answered[1]


print("channel: adb server -> host:transport:%s -> shell:sh (the product's own service)"
      % SERIAL)
print("start: %d ms" % int((time.time() - started) * 1000))

for request, repeats in (("world", 10), ("call-int 30", 10), ("call-int 118 i32 1", 5)):
    times = []
    answer = status = ""
    for _ in range(repeats):
        elapsed, answer, status = ask(request)
        if elapsed is None:
            sys.exit("%s: %s" % (request, status))
        times.append(elapsed)
    times.sort()
    print("%-20s n=%d min=%dms median=%dms max=%dms status=%s first line: %s"
          % (request, repeats, times[0], times[len(times) // 2], times[-1], status,
             answer.splitlines()[:1]))

resident_world = ask("world")[1]
shell_world = adb("shell", "am stack list").stdout
same = resident_world.replace("\r\n", "\n") == shell_world.replace("\r\n", "\n")
print("world identical to `am stack list`: %s (%d vs %d bytes)"
      % (same, len(resident_world), len(shell_world)))
if not same:
    for index, (left, right) in enumerate(
            zip(resident_world.splitlines(), shell_world.splitlines())):
        if left != right:
            print("  first difference on line %d:\n    resident: %r\n    am stack: %r"
                  % (index, left, right))
            break

pid = adb("shell", "pidof denza_split_serve").stdout.strip()
if pid:
    meminfo = adb("shell", "dumpsys meminfo %s" % pid).stdout
    for line in meminfo.splitlines():
        if "TOTAL PSS" in line or line.strip().startswith("TOTAL"):
            print("memory: " + " ".join(line.split()))
            break

# The stream is the helper's life: closing it is what the product does, so that is what is tested.
helper.close()
time.sleep(1.0)
adb("shell", "rm -f " + REMOTE)
left = adb("shell", "pidof denza_split_serve").stdout.strip()
print("left running after the stream closed: %s" % (left or "nothing"))
sys.exit(0 if same and not left else 1)
