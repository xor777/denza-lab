#!/usr/bin/env python3
"""Where the time of one command goes, on the channel the product actually opens.

Wave 16 asks one question: a round trip on the return path costs 89 ms, the command itself costs
8-18 ms on the device and the wrapper costs 1 ms, so what is the rest? This separates the two
answers that lead to different fixes:

  * a fixed price per trip  -> send fewer trips (batch requests through the resident helper);
  * a price per byte        -> send smaller answers (a compact reply instead of 8 KB of text).

It also compares the two services adbd offers for the same shell: `shell:sh`, the legacy one the
product opens, which hands back a terminal, and `exec:sh`, which hands back a plain pipe. Same
link, same command, same frame - so the difference between them is what the terminal costs.

Read-only: every command is a read. The device-side cost of each command is measured separately
(tools/split_frame_bench.sh) and printed here for subtraction.

Usage: tools/split_transport_probe.py [serial] [repeats]
"""
import socket
import statistics
import sys
import time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:5555"
REPEATS = int(sys.argv[2]) if len(sys.argv) > 2 else 15
SERVER = ("127.0.0.1", 5037)
RECORD = b"\x1e"
UNIT = b"\x1f"

# command, and what it costs on the device itself (median, tools/split_frame_bench.sh)
COMMANDS = [
    ("echo hi", 0.2),
    ("service call activity_task 30", 8.1),
    ("settings get global development_settings_enabled", 17.9),
    ("am stack list", 18.9),
    ("am stack list; am stack list", 37.8),
    ("am stack list; am stack list; am stack list; am stack list", 75.6),
]


def quoted(value):
    return "'" + value.replace("'", "'\\''") + "'"


def frame(command, marker):
    """The product's own frame (LocalAdbClient.frameInteractiveCommand, v32)."""
    return ("if print -n '' 2>/dev/null; "
            "then __denza_emit() { print -n \"\\036$1\\037\"; }; "
            "else __denza_emit() { printf '\\036%s\\037' \"$1\"; }; fi; "
            "__denza_emit " + quoted(marker + ":BEGIN") + "; "
            "( eval " + quoted(command) + " ) 2>&1; "
            "__denza_adb_status=$?; "
            '__denza_emit "' + marker + ':$__denza_adb_status"\n')


class Stream:
    def __init__(self, serial, service):
        self.service = service
        self.sock = socket.create_connection(SERVER, timeout=10)
        self._ask("host:transport:" + serial)
        self._ask(service)
        self.sock.settimeout(0.2)

    def _ask(self, name):
        payload = name.encode()
        self.sock.sendall(b"%04x" % len(payload) + payload)
        status = b""
        while len(status) < 4:
            chunk = self.sock.recv(4 - len(status))
            if not chunk:
                raise SystemExit("the adb server closed the connection on " + name)
            status += chunk
        if status != b"OKAY":
            raise SystemExit("adb server refused %r: %s" % (name, self.sock.recv(4096)))

    def drain(self, seconds=0.3):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                self.sock.recv(65536)
            except socket.timeout:
                pass

    def round_trip(self, command, marker):
        """@return (milliseconds, answer bytes, bytes received on the wire)."""
        self.drain(0.15)
        began = time.perf_counter()
        self.sock.sendall(frame(command, marker).encode())
        received = b""
        deadline = time.time() + 20
        begin = RECORD + marker.encode() + b":BEGIN" + UNIT
        status = RECORD + marker.encode() + b":"
        while time.time() < deadline:
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            received += chunk
            start = received.find(begin)
            if start < 0:
                continue
            start += len(begin)
            ends = received.find(status, start)
            if ends < 0:
                continue
            if received.find(UNIT, ends + len(status)) < 0:
                continue
            return (time.perf_counter() - began) * 1000, received[start:ends], len(received)
        return None, b"", len(received)

    def close(self):
        self.sock.close()


def measure(service):
    print("\n--- %s ---" % service)
    stream = Stream(SERIAL, service)
    stream.drain(0.6)
    rows = []
    for index, (command, device_ms) in enumerate(COMMANDS):
        times, answer, wire = [], b"", 0
        for repeat in range(REPEATS):
            elapsed, answer, wire = stream.round_trip(command, "M%d_%d" % (index, repeat))
            if elapsed is None:
                print("  %-58s NO ANSWER" % command[:58])
                break
            times.append(elapsed)
        if not times:
            continue
        median = statistics.median(times)
        rows.append((len(answer), median, device_ms))
        print("  %-58s answer=%6dB wire=%6dB trip=%6.1fms device=%5.1fms overhead=%6.1fms"
              % (command[:58], len(answer), wire, median, device_ms, median - device_ms))
    stream.close()
    return rows


def slope(rows):
    """Least squares of overhead against answer size: the fixed part and the per-KB part."""
    if len(rows) < 2:
        return None
    xs = [size / 1024.0 for size, _, _ in rows]
    ys = [median - device for _, median, device in rows]
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    denominator = sum((x - mean_x) ** 2 for x in xs)
    if denominator == 0:
        return None
    per_kb = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / denominator
    return mean_y - per_kb * mean_x, per_kb


print("host -> adb server -> %s, %d repeats each" % (SERIAL, REPEATS))
print("`shell:sh` is the service the product opens; `exec:sh` is the same shell without a terminal")
results = {}
for service in ("shell:sh", "exec:sh"):
    try:
        results[service] = measure(service)
    except SystemExit as refused:
        print("  unavailable: %s" % refused)

print("\n--- what the overhead is made of ---")
for service, rows in results.items():
    fitted = slope(rows)
    if fitted:
        print("%-10s fixed %.1f ms per trip + %.1f ms per KB of answer" % (service, fitted[0], fitted[1]))
