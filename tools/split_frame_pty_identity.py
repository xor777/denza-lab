#!/usr/bin/env python3
"""Proves the command frame on the very channel the product opens: legacy `shell:sh`.

The product does not run commands one at a time. It opens the legacy `shell:sh` service and
writes command lines into it (LocalAdbClient.openInteractiveShell), and on this adbd that service
hands back a terminal - which is why mksh runs its line editor over everything the product types,
and why a control byte inside a command line is read as a keystroke rather than as data. v31 put
the two frame bytes there and every command of the product timed out.

`adb shell <command>` and a host `/bin/sh` under a pipe cannot see that: neither runs a line
editor. So this probe talks to the adb server's own socket and asks for `shell:sh` by name, which
is byte for byte the service the product asks for, and drives it exactly as the product does.

Each case is run twice - inside the frame the product used up to v30 and inside the frame it uses
now - and the two answers are compared byte for byte. Read-only: every case is an `echo`, a read
command, or a status.

Usage: tools/split_frame_pty_identity.py [serial]
"""
import base64
import socket
import sys
import time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:5555"
SERVER = ("127.0.0.1", 5037)
RECORD = b"\x1e"
UNIT = b"\x1f"
BUDGET_S = 6.0

CASES = [
    ("plain", "echo hi"),
    ("squote", "echo 'it'\"'\"'s here'"),
    ("dquote", 'echo "double \\"quoted\\" text"'),
    ("dollar", "echo '$HOME $(id) `id` ${x}'"),
    ("newline", "echo one\necho two"),
    ("unicode", "echo 'привет мир ✓ 日本語'"),
    ("backslash", "echo 'a\\\\b\\tc'"),
    ("semicolon", "echo a; echo b"),
    ("status7", "echo before; exit 7"),
    ("stderr", "echo out; echo err 1>&2"),
    ("amstack", "am stack list"),
    ("settings", "settings get global development_settings_enabled"),
    ("txn30", "service call activity_task 30"),
    ("nested", "sh -c 'echo \"nested '\"'\"'quotes'\"'\"'\"'"),
]


def quoted(value):
    return "'" + value.replace("'", "'\\''") + "'"


def v30_frame(command, marker):
    """base64 in a command substitution plus two /system/bin/printf."""
    encoded = base64.b64encode(command.encode()).decode()
    return ("__denza_adb_command=$(printf '%s' '" + encoded + "' | base64 -d); "
            "printf '\\036" + marker + ":BEGIN\\037'; "
            '( eval "$__denza_adb_command" ) 2>&1; '
            "__denza_adb_status=$?; "
            "printf '\\036" + marker + ":%s\\037' \"$__denza_adb_status\"\n")


def v32_frame(command, marker):
    """Single quoting plus mksh's `print`, with the marker bytes made on the far side."""
    return ("if print -n '' 2>/dev/null; "
            "then __denza_emit() { print -n \"\\036$1\\037\"; }; "
            "else __denza_emit() { printf '\\036%s\\037' \"$1\"; }; fi; "
            "__denza_emit " + quoted(marker + ":BEGIN") + "; "
            "( eval " + quoted(command) + " ) 2>&1; "
            "__denza_adb_status=$?; "
            '__denza_emit "' + marker + ':$__denza_adb_status"\n')


def v31_frame(command, marker):
    """The frame that broke the product: the two marker bytes carried inside the command line."""
    return ("if print -nr '' 2>/dev/null; "
            "then __denza_emit() { print -nr \"$1\"; }; "
            "else __denza_emit() { printf '%s' \"$1\"; }; fi; "
            "__denza_emit " + quoted("\x1e" + marker + ":BEGIN\x1f") + "; "
            "( eval " + quoted(command) + " ) 2>&1; "
            "__denza_adb_status=$?; "
            '__denza_emit "\x1e' + marker + ':$__denza_adb_status\x1f"\n')


class LegacyShell:
    """The adb server's `shell:sh` on this serial - the product's own channel, PTY and all."""

    def __init__(self, serial):
        self.sock = socket.create_connection(SERVER, timeout=10)
        self._service("host:transport:" + serial)
        self._service("shell:sh")
        self.sock.settimeout(0.25)

    def _service(self, name):
        payload = name.encode()
        self.sock.sendall(b"%04x" % len(payload) + payload)
        status = self._exactly(4)
        if status != b"OKAY":
            raise SystemExit("adb server refused %r: %s" % (name, status + self.sock.recv(4096)))

    def _exactly(self, count):
        buffer = b""
        while len(buffer) < count:
            chunk = self.sock.recv(count - len(buffer))
            if not chunk:
                raise SystemExit("the adb server closed the connection")
            buffer += chunk
        return buffer

    def drain(self, seconds=0.4):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                if not self.sock.recv(65536):
                    return
            except socket.timeout:
                pass

    def run(self, frame, marker):
        """@return (payload, whole stream) - payload is None when no frame ever arrived."""
        self.drain(0.3)
        self.sock.sendall(frame.encode())
        received = b""
        deadline = time.time() + BUDGET_S
        while time.time() < deadline:
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            received += chunk
            found = framed(received, marker)
            if found is not None:
                return found, received
        return None, received


def framed(received, marker):
    """The transport's own scan: the payload between BEGIN and the status marker, in bytes."""
    begin = RECORD + marker.encode() + b":BEGIN" + UNIT
    status_prefix = RECORD + marker.encode() + b":"
    start = received.find(begin)
    if start < 0:
        return None
    start += len(begin)
    status = received.find(status_prefix, start)
    if status < 0:
        return None
    end = received.find(UNIT, status + len(status_prefix))
    if end < 0:
        return None
    return received[start:status]


shell = LegacyShell(SERIAL)
shell.drain(0.8)
print("channel: adb server -> host:transport:%s -> shell:sh (the product's own service)" % SERIAL)

failures = 0
for index, (name, command) in enumerate(CASES):
    marker = "M%d" % index
    old_payload, old_stream = shell.run(v30_frame(command, marker), marker)
    new_payload, new_stream = shell.run(v32_frame(command, marker), marker)
    if new_payload is None:
        failures += 1
        print("LOST %-10s the new frame never produced a marker at all" % name)
        print("     frame bytes seen in the answer: %s"
              % ("none" if RECORD not in new_stream else "present"))
        print("     stream: %r" % new_stream[:400])
        continue
    if old_payload is None:
        failures += 1
        print("LOST %-10s the OLD frame produced no marker - the channel itself is wrong" % name)
        continue
    if old_payload == new_payload:
        print("OK   %-10s %d bytes" % (name, len(new_payload)))
    else:
        failures += 1
        print("DIFF %-10s" % name)
        print("     v30: %r" % old_payload[:300])
        print("     v32: %r" % new_payload[:300])

# The probe has to be able to see the defect it exists for, or it proves nothing. v31 carried the
# frame bytes inside the command line; the terminal's line editor ate them, and no marker ever came
# back. If this ever starts finding a marker, the channel under test is not the product's any more.
broken_payload, broken_stream = shell.run(v31_frame("echo hi", "V31"), "V31")
if broken_payload is None:
    print("self-check: the v31 frame still produces no marker on this channel "
          "(frame bytes in the answer: %s) - the probe can see the defect"
          % ("none" if RECORD not in broken_stream else "present"))
else:
    failures += 1
    print("self-check FAILED: the v31 frame framed an answer here, so this channel is not the "
          "one the product opens and nothing above is evidence")

print("identity failures: %d" % failures)
sys.exit(1 if failures else 0)
