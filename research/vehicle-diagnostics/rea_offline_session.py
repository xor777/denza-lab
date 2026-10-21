#!/usr/bin/env python3
"""One owned, offline REA stdio session; retain full replies, never run firmware.

Usage: python3 rea_offline_session.py CAPTURE_DIR ELF
Then JSON lines: {"label":"...", "tool":"batch_decompile", "arguments":{...}}
Finish with {"close":true}. Uses the installed launcher, without registration.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path
import selectors
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
ALLOWED = {
    "open_binary", "close_binary", "inspect_native_load_image",
    "batch_decompile", "procedure_pseudo_code", "procedure_references",
    "analyze_function", "get_procedure_at", "read_memory", "read_bytes",
    "address_to_file_offset", "search_procedures", "search_strings",
    "resolve_native_call_targets", "inspect_native_data_type",
}


def main():
    out, target = (Path(x).resolve() for x in sys.argv[1:])
    out.mkdir(parents=True, exist_ok=False)
    original = target.read_bytes()
    source_hash = hashlib.sha256(original).hexdigest()
    assert original[:6] == b"\x7fELF\x02\x01", "ELF64 LE only"
    assert struct.unpack_from("<H", original, 18)[0] == 183, "AArch64 only"
    env = dict(os.environ, DENZA_REA_TMPDIR=str(out / "scratch"))
    stderr = (out / "server-stderr.log").open("wb")
    proc = subprocess.Popen([str(ROOT / "tools/rea"), "--mcp"], cwd=ROOT,
                            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=stderr, bufsize=0)
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    buffer = b""
    counter = 0
    calls = []

    def save(label, value):
        assert label.replace("-", "").replace("_", "").isalnum()
        path = out / (label + ".json.gz")
        with path.open("xb") as stream:
            stream.write(gzip.compress(json.dumps(value, ensure_ascii=False,
                                                 indent=2).encode(), mtime=0))

    def request(method, params, label):
        nonlocal counter, buffer
        counter += 1
        started = time.monotonic()
        proc.stdin.write((json.dumps({"jsonrpc": "2.0", "id": counter,
                                     "method": method, "params": params}) + "\n").encode())
        deadline = started + 240
        while True:
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                reply = json.loads(line)
                if reply.get("id") == counter:
                    save(label, reply)
                    item = {"label": label, "method": method, "params": params,
                            "seconds": round(time.monotonic() - started, 3),
                            "rpcError": reply.get("error"),
                            "isError": reply.get("result", {}).get("isError", False)}
                    calls.append(item)
                    print(json.dumps(item), flush=True)
                    return reply
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not selector.select(remaining):
                raise TimeoutError(label)
            data = os.read(proc.stdout.fileno(), 1024 * 1024)
            if not data:
                raise EOFError("REA exited during " + label)
            buffer += data

    def call(label, tool, args):
        assert tool in ALLOWED and tool in known_tools, "unapproved/unknown tool"
        return request("tools/call", {"name": tool, "arguments": args}, label)

    known_tools = set()
    try:
        request("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                               "clientInfo": {"name": "denza-offline-research", "version": "1"}},
                "initialize")
        proc.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
        listing = request("tools/list", {}, "tools")
        known_tools = {t["name"] for t in listing["result"]["tools"]}
        call("open", "open_binary", {"path": str(target), "provider_id": "ghidra"})
        call("load-image", "inspect_native_load_image", {})
        print("READY: JSON request lines or {\"close\":true}", flush=True)
        for line in sys.stdin:
            command = json.loads(line)
            if command.get("close"):
                break
            call(command["label"], command["tool"], command.get("arguments", {}))
    finally:
        if proc.poll() is None:
            try:
                call("close", "close_binary", {})
            finally:
                proc.stdin.close()
                try:
                    proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    proc.terminate()  # Only our exact owned REA parent.
                    proc.wait(timeout=10)
        stderr.close()
        save("calls", calls)
        save("receipt", {"target": str(target), "sha256Before": source_hash,
                         "sha256After": hashlib.sha256(target.read_bytes()).hexdigest(),
                         "serverPid": proc.pid, "serverExitCode": proc.returncode,
                         "scratchEntries": sorted(str(p.relative_to(out))
                                                  for p in (out / "scratch").glob("*")),
                         "vehicleOperations": 0, "firmwareExecutions": 0})


if __name__ == "__main__":
    main()
