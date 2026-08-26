#!/usr/bin/env python3
"""What the resident shell-UID split helper costs on the car, against the one-shot it replaces.

Reads only. It stands the product's own helper up on one adb shell stream, asks it the read
requests the recipes ask, and compares its world read with `am stack list` byte for byte - which
is the one thing about this helper that a unit test cannot prove. It never sends `remove-task`.

It needs the packed jar on the device, so it pushes one copy to /data/local/tmp (the same place
every other shell-UID helper of this project is staged) and removes it at the end. Build it first:

    ./gradlew :denza-apps:packDebugSplitTaskProxy

Usage: tools/split_resident_bench.py [serial]
"""
import os
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
helper = subprocess.Popen(["adb", "-s", SERIAL, "shell", launch],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, bufsize=1)

BEGIN = "DENZA_SERVE_%s:BEGIN" % NONCE
END = "DENZA_SERVE_%s:END" % NONCE
READY = "DENZA_SERVE_%s:READY" % NONCE


def ask(request):
    started = time.time()
    helper.stdin.write(request + "\n")
    helper.stdin.flush()
    payload, collecting = [], False
    while True:
        line = helper.stdout.readline()
        if not line:
            return None, None, "the helper died"
        stripped = line.rstrip("\r\n")
        if stripped == BEGIN:
            collecting = True
            continue
        if stripped.startswith(END):
            status = stripped[len(END):].strip()
            return int((time.time() - started) * 1000), "".join(payload), status
        if collecting:
            payload.append(line)


started = time.time()
while True:
    line = helper.stdout.readline()
    if not line:
        sys.exit("the helper never came up")
    if line.rstrip("\r\n") == READY:
        break
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

helper.stdin.write("quit\n")
helper.stdin.flush()
helper.wait(timeout=10)
adb("shell", "rm -f " + REMOTE)
left = adb("shell", "pidof denza_split_serve").stdout.strip()
print("left running after quit: %s" % (left or "nothing"))
sys.exit(0 if same and not left else 1)
