#!/usr/bin/env python3
"""Proves on the car that the wave-15 command frame executes exactly like the frame before it.

Read-only: every case is an `echo`, a read command or a status, run inside both frames on the
device's own shell, and the two answers are compared byte for byte (hex of the whole stream,
so a trailing newline or a missing byte cannot hide).

    OLD - base64 in a command substitution plus two /system/bin/printf (product up to v30).
    NEW - single quoting plus mksh's `print`, chosen by a probe that writes nothing
          (LocalAdbClient.frameInteractiveCommand, wave 15).

Usage: tools/split_frame_identity.py [serial]
"""
import base64
import subprocess
import sys

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:5555"

RECORD = "\x1e"
UNIT = "\x1f"
PRELUDE = (
    "if print -nr '' 2>/dev/null; "
    "then __denza_emit() { print -nr \"$1\"; }; "
    "else __denza_emit() { printf '%s' \"$1\"; }; fi; "
)

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


def old_frame(command, marker):
    encoded = base64.b64encode(command.encode()).decode()
    return ("__denza_adb_command=$(printf '%s' '" + encoded + "' | base64 -d); "
            "printf '\\036" + marker + ":BEGIN\\037'; "
            '( eval "$__denza_adb_command" ) 2>&1; '
            "__denza_adb_status=$?; "
            "printf '\\036" + marker + ":%s\\037' \"$__denza_adb_status\"")


def new_frame(command, marker):
    return (PRELUDE
            + "__denza_emit " + quoted(RECORD + marker + ":BEGIN" + UNIT) + "; "
            + "( eval " + quoted(command) + " ) 2>&1; "
            + "__denza_adb_status=$?; "
            + '__denza_emit "' + RECORD + marker + ':$__denza_adb_status' + UNIT + '"')


script = ["hex() { od -An -tx1 | tr -d ' \\n'; }", "FAIL=0",
          'if print -nr "" 2>/dev/null; then echo "emitter=print"; else echo "emitter=printf"; fi']
for index, (name, command) in enumerate(CASES):
    marker = "M%d" % index
    script.append("a%d() { %s\n}" % (index, old_frame(command, marker)))
    script.append("b%d() { %s\n}" % (index, new_frame(command, marker)))
    script.append("A=$(a%d 2>&1 | hex)" % index)
    script.append("B=$(b%d 2>&1 | hex)" % index)
    script.append('if [ "$A" = "$B" ]; then echo "OK   %s (${#A} hex digits)"; '
                  'else echo "DIFF %s"; echo "  old=$A"; echo "  new=$B"; FAIL=1; fi'
                  % (name, name))
script.append('echo "identity failures: $FAIL"')

done = subprocess.run(["adb", "-s", SERIAL, "shell"],
                      input="\n".join(script), text=True, capture_output=True)
sys.stdout.write(done.stdout)
sys.stderr.write(done.stderr)
sys.exit(1 if ("DIFF" in done.stdout or done.returncode != 0) else 0)
