"""Replay captured native sender inputs into StockFeed and compare ORIGINAL DEX egress.

The reference is an independently recorded emulator fireEvent stream, never this
module's output. Every packet is compared by ordinal; a missing packet does not
shift alignment to hide a lifecycle/order defect. No field is silently normalized.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
JAVA = Path(__file__).with_name("NativeSenderReplay.java")
INPUT_SCHEMA = "denza-stock-sender-input-v1"
REFERENCE_SCHEMA = "denza-stock-sender-reference-v1"
ROUTE_TOPIC = 1126084593287170
HASH_KEYS = ("apk_sha256", "dex_sha256", "recorder_sha256")
REQUIRED_COVERAGE = ("nav_start", "route", "ack", "location", "progress", "reroute", "nav_stop", "arrival", "advance")
COVERAGE_PRESETS = {"full": REQUIRED_COVERAGE,
                    "start-arrival": tuple(e for e in REQUIRED_COVERAGE if e != "reroute"),
                    "reroute": tuple(e for e in REQUIRED_COVERAGE if e != "arrival")}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    for lineno, line in enumerate(path.read_text().splitlines(), 1):
        if line.strip():
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{lineno}: object required")
            rows.append(row)
    if not rows:
        raise ValueError(f"{path}: empty trace")
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows))


def source(header: dict[str, Any]) -> dict[str, str]:
    if header.get("origin") != "original-stock-dex":
        raise ValueError("reference/input origin must be original-stock-dex")
    value = header.get("source")
    if not isinstance(value, dict):
        raise ValueError("missing original source provenance")
    for key in HASH_KEYS:
        if not re.fullmatch(r"[0-9a-f]{64}", str(value.get(key, ""))):
            raise ValueError(f"missing/invalid provenance {key}")
    return value


def validate_inputs(rows: list[dict[str, Any]]) -> dict[str, int]:
    header = rows[0]
    if header.get("schema") != INPUT_SCHEMA or header.get("kind") != "header":
        raise ValueError("original input header schema missing")
    source(header)
    if header.get("locale") not in ("EN", "DEFAULT"):
        raise ValueError("original stock resource locale required")
    gates = header.get("gates", {})
    if any(type(gates.get(k)) is not bool for k in ("service", "switch", "no_sleep")):
        raise ValueError("original gate snapshot required")
    now = header.get("start_ms")
    if type(now) is not int:
        raise ValueError("integer start_ms required")
    seq = -1
    counts: dict[str, int] = {}
    for row in rows[1:]:
        if row.get("kind") != "event" or type(row.get("seq")) is not int or row["seq"] <= seq:
            raise ValueError("event seq must increase strictly")
        if type(row.get("at_ms")) is not int or row["at_ms"] < now:
            raise ValueError("event time must increase monotonically in integer ms")
        if not isinstance(row.get("data"), dict):
            raise ValueError("event data snapshot missing")
        if counts.get("end"):
            raise ValueError("event after end")
        event = row.get("event")
        if not isinstance(event, str):
            raise ValueError("event name missing")
        counts[event] = counts.get(event, 0) + 1
        seq, now = row["seq"], row["at_ms"]
    if counts.get("end") != 1:
        raise ValueError("explicit end event/timer tail required")
    return counts


def validate_artifacts(manifest_path: Path, provenance: dict[str, str]) -> list[dict[str, str]]:
    manifest = json.loads(manifest_path.read_text())
    artifacts = manifest.get("artifacts") if isinstance(manifest, dict) else manifest
    if not isinstance(artifacts, list):
        raise ValueError("artifact manifest must list original APK, DEX and recorder")
    result = []
    for key in HASH_KEYS:
        role = key.removesuffix("_sha256")
        matching = [a for a in artifacts if a.get("role") == role]
        if len(matching) != 1:
            raise ValueError(f"artifact manifest needs exactly one {role}")
        a = matching[0]
        path = Path(a["path"])
        if not path.is_absolute():
            path = manifest_path.parent / path
        actual = digest(path)
        if actual != a.get("sha256") or actual != provenance[key]:
            raise ValueError(f"original {role} bytes do not match recorded provenance")
        result.append({"role": role, "path": str(path.resolve()), "sha256": actual})
    entries = provenance.get("dex_entries")
    primary = provenance.get("dex_entry")
    if not isinstance(entries, dict) or not entries or primary not in entries:
        raise ValueError("original APK DEX entry inventory and primary sender DEX entry required")
    apk_path = Path(next(a["path"] for a in result if a["role"] == "apk"))
    with zipfile.ZipFile(apk_path) as apk:
        names = {n for n in apk.namelist() if re.fullmatch(r"classes[0-9]*\.dex", n)}
        if names != set(entries):
            raise ValueError("DEX inventory does not cover every original APK DEX entry")
        for name in sorted(names):
            if hashlib.sha256(apk.read(name)).hexdigest() != entries[name]:
                raise ValueError(f"APK DEX entry bytes differ: {name}")
    if entries[primary] != provenance["dex_sha256"]:
        raise ValueError("primary sender DEX is not the original APK entry")
    components = [a for a in artifacts if a.get("role") == "dex_component"]
    seen = set()
    for a in components:
        name = a.get("entry")
        if name not in entries or name in seen:
            raise ValueError("unknown or duplicate DEX component entry")
        seen.add(name)
        path = Path(a["path"])
        if not path.is_absolute():
            path = manifest_path.parent / path
        if digest(path) != entries[name] or a.get("sha256") != entries[name]:
            raise ValueError(f"extracted DEX component differs: {name}")
        result.append({"role": "dex_component", "entry": name, "path": str(path.resolve()), "sha256": entries[name]})
    runtime_roles = {"runtime_report", "runtime_agent"}
    has_runtime = isinstance(manifest, dict) and (
        "runtime_identity" in manifest or manifest.get("schema") == "denza-stock-sender-capture-v1"
        or any(a.get("role") in runtime_roles for a in artifacts))
    if has_runtime:
        identity = manifest.get("runtime_identity")
        if not isinstance(identity, dict) or not identity:
            raise ValueError("capture runtime_identity is required")
        verified_runtime = {}
        for role, path_key, hash_key in (("runtime_report", "report_path", "report_sha256"),
                                         ("runtime_agent", "agent_path", "agent_sha256")):
            matching = [a for a in artifacts if a.get("role") == role]
            if len(matching) != 1:
                raise ValueError(f"artifact manifest needs exactly one {role}")
            artifact = matching[0]
            path = Path(artifact["path"])
            if not path.is_absolute():
                path = manifest_path.parent / path
            declared_path = identity.get(path_key)
            if not isinstance(declared_path, str):
                raise ValueError(f"runtime identity missing {path_key}")
            declared_path = Path(declared_path)
            if not declared_path.is_absolute():
                declared_path = manifest_path.parent / declared_path
            if declared_path.resolve() != path.resolve():
                raise ValueError(f"runtime {role} path differs from identity")
            try:
                actual = digest(path)
            except OSError as exc:
                raise ValueError(f"runtime {role} artifact missing: {path}") from exc
            if actual != artifact.get("sha256") or actual != identity.get(hash_key):
                raise ValueError(f"runtime {role} bytes differ from recorded identity")
            verified_runtime[role] = {"role": role, "path": str(path.resolve()), "sha256": actual}
        actual_agent = verified_runtime["runtime_agent"]["sha256"]
        if identity.get("actual_agent_sha256") != actual_agent:
            raise ValueError("runtime actual agent digest differs from loaded agent artifact")
        try:
            runtime_report = json.loads(Path(verified_runtime["runtime_report"]["path"]).read_text())
        except (OSError, ValueError) as exc:
            raise ValueError("runtime report cannot be parsed") from exc
        if not isinstance(runtime_report, dict):
            raise ValueError("runtime report object required")
        if (runtime_report.get("apk_sha256") != provenance["apk_sha256"]
                or identity.get("apk_sha256") != provenance["apk_sha256"]):
            raise ValueError("runtime APK differs from original provenance")
        if runtime_report.get("agent_sha256") != actual_agent:
            raise ValueError("runtime report agent differs from loaded agent artifact")
        result.extend(verified_runtime.values())
    return result


def jdk() -> Path:
    home = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
    if (home / "bin/javac").exists():
        return home / "bin"
    executable = shutil.which("javac")
    if not executable:
        raise RuntimeError("JDK required; no replay was performed")
    return Path(executable).parent


def replay(inputs: Path, output: Path, build: Path) -> dict[str, Any]:
    rows = read_jsonl(inputs)
    counts = validate_inputs(rows)
    build.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    sources = [p for p in sorted((ROOT / "libraries/adas-link/src/main/java").rglob("*.java")) if "import android." not in p.read_text()]
    runtime = jdk()
    command = [str(runtime / "javac"), "--release", "8", "-encoding", "UTF-8", "-nowarn", "-d", str(build)] + [str(p) for p in sources] + [str(JAVA)]
    def checked(command, stage):
        try:
            return subprocess.run(command, check=True, capture_output=True, text=True, cwd=ROOT, timeout=120)
        except subprocess.CalledProcessError as exc:
            raise RuntimeError(f"{stage} failed (exit {exc.returncode}):\n{exc.stderr or exc.stdout or '(no diagnostic output)'}") from exc
    checked(command, "Native sender harness compilation")
    result = checked([str(runtime / "java"), "-cp", str(build), "dev.denza.adaslink.NativeSenderReplay", str(inputs), str(output)],
                     "Native sender replay")
    return {"input_sha256": digest(inputs), "output_sha256": digest(output), "harness_sha256": digest(JAVA),
            "library_sources_sha256": hashlib.sha256(b"".join(str(p.relative_to(ROOT)).encode() + b"\0" + p.read_bytes() for p in sources)).hexdigest(),
            "java_version": subprocess.run([str(runtime / "java"), "-version"], capture_output=True, text=True, check=True).stderr.strip(),
            "event_counts": counts, "stderr": result.stderr}


def packet(row: dict[str, Any]) -> dict[str, Any]:
    topic, payload, at = row.get("topic"), row.get("payloadHex"), row.get("at_ms")
    if not re.fullmatch(r"[0-9]+", str(topic)) or not isinstance(payload, str) or not re.fullmatch(r"(?:[0-9a-fA-F]{2})*", payload):
        raise ValueError("fire needs native topic and complete payloadHex")
    if type(at) is not int:
        raise ValueError("fire needs integer at_ms")
    return {"topic": int(topic), "payloadHex": payload.lower(), "at_ms": at}


def decoded_difference(topic: int, original: bytes, port: bytes) -> dict[str, Any]:
    # The recovered schema decodes only; it neither generates nor supplies expected packets.
    from tools.adas_bridge.feed import TOPICS, decode
    schema = TOPICS.get(topic)
    if not schema:
        return {"original_hex": original.hex(), "port_hex": port.hex()}
    try:
        return {"message": schema[0], "original": decode(schema[1], original), "port": decode(schema[1], port)}
    except (ValueError, UnicodeDecodeError, IndexError) as exc:
        return {"decode_error": str(exc), "original_hex": original.hex(), "port_hex": port.hex()}


def route_wall_time_only(original: bytes, port: bytes) -> tuple[bool, dict[str, Any] | None]:
    """Explicit opt-in for exactly NaviDataDetail.time; every other byte must agree.

    We parse protobuf to locate the literal JSON timestamp, replace only that byte
    span, and retain its original values. Decoding an entire message and comparing
    object values would incorrectly hide missing fields, key ordering, or duplicate
    keys, so it is deliberately not done here.
    """
    def envelope(data: bytes) -> tuple[bytes, int]:
        # naviSDRouteNotify{1:naviSDRoute{3:int navigationSDLink2Num,4:string navigationSDLink2}}.
        def varint(offset: int) -> tuple[int, int]:
            begin = offset
            value = shift = 0
            while offset < len(data) and shift <= 63:
                byte = data[offset]; offset += 1; value |= (byte & 127) << shift
                if byte < 128:
                    if offset - begin > 1 and byte == 0:
                        raise ValueError("noncanonical route varint is not wall-clock nondeterminism")
                    return value, offset
                shift += 7
            raise ValueError("truncated/oversize varint")
        tag, p = varint(0)
        if tag != 10:
            raise ValueError("unexpected route wrapper")
        size, p = varint(p)
        if p + size != len(data):
            raise ValueError("route wrapper has trailing/absent bytes")
        body_start = p
        tag, p = varint(p)
        if tag != 24:
            raise ValueError("unexpected route chunk-count field")
        _, p = varint(p)
        tag, p = varint(p)
        if tag != 34:
            raise ValueError("unexpected route text")
        before_length = p
        length, p = varint(p)
        text = data[p:p + length]
        if len(text) != length:
            raise ValueError("truncated route JSON")
        obj = json.loads(text)
        if set(obj) != {"currentIndex", "length", "pathCount", "payload", "time"} or type(obj["time"]) is not int:
            raise ValueError("unknown route envelope")
        # Only the top-level terminal time field, exactly as the original Gson writer emits.
        match = re.search(rb',"time":(-?[0-9]+)\}$', text)
        if not match:
            raise ValueError("unrecognized route timestamp layout")
        normalized = text[:match.start(1)] + b"<WALL_CLOCK_MS>" + text[match.end(1):]
        # Compare the trailing protobuf bytes too. Wrapper/string size differences follow the timestamp's text length.
        return data[body_start:before_length] + b"\0" + normalized + b"\0" + data[p + length:], obj["time"]
    try:
        a, ta = envelope(original); b, tb = envelope(port)
        return a == b, {"field": "NaviDataDetail.time", "original": ta, "port": tb}
    except (ValueError, UnicodeDecodeError, KeyError):
        return False, None


def causal_comparison(input_rows: list[dict[str, Any]], original_rows: list[dict[str, Any]],
                      port_rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Supplemental attribution only. Never changes the strict global verdict.

    Compare exact packets and absolute times inside one captured P/v0 call.
    Missing attribution is reported, not inferred from nearest time or topic.
    """
    def input_seq(row):
        raw = row.get("raw", {})
        value = row.get("input_seq", raw.get("sender_input_seq"))
        return value if type(value) is int else None
    originals, ports = {}, {}
    for rows, groups in ((original_rows, originals), (port_rows, ports)):
        for row in rows:
            if row.get("kind") == "fire":
                seq = input_seq(row)
                if seq is not None:
                    groups.setdefault(seq, []).append(packet(row))
    reports = []
    for event in input_rows[1:]:
        if event.get("event") not in ("location", "progress"):
            continue
        seq = event["seq"]
        original, actual = originals.get(seq, []), ports.get(seq, [])
        differences = []
        for i in range(max(len(original), len(actual))):
            if i >= len(original) or i >= len(actual):
                differences.append({"packet": i, "type": "causal_packet_count", "original": original[i] if i < len(original) else None,
                                    "port": actual[i] if i < len(actual) else None})
                continue
            a, b = original[i], actual[i]
            if a["topic"] != b["topic"]:
                differences.append({"packet": i, "type": "causal_topic_order", "original": a, "port": b})
                continue
            if a["at_ms"] != b["at_ms"]:
                differences.append({"packet": i, "type": "causal_absolute_time", "original_ms": a["at_ms"], "port_ms": b["at_ms"]})
            if a["payloadHex"] != b["payloadHex"]:
                differences.append({"packet": i, "type": "causal_payload_bytes", "topic": str(a["topic"]),
                                    "original_hex": a["payloadHex"], "port_hex": b["payloadHex"],
                                    "decoded": decoded_difference(a["topic"], bytes.fromhex(a["payloadHex"]), bytes.fromhex(b["payloadHex"]))})
        reports.append({"input_seq": seq, "event": event["event"], "input_at_ms": event["at_ms"],
                        "original_packets": len(original), "port_packets": len(actual),
                        "original_topics": [str(p["topic"]) for p in original], "port_topics": [str(p["topic"]) for p in actual],
                        "verdict": "UNATTRIBUTED" if not original else "DIFFERENT" if differences else "EQUAL",
                        "differences": differences})
    return {"scope": "supplemental original thread-local call attribution; exact topics, bytes and absolute time; no initialization filtering",
            "affects_global_verdict": False, "inputs": reports,
            "equal_inputs": sum(r["verdict"] == "EQUAL" for r in reports),
            "different_inputs": sum(r["verdict"] == "DIFFERENT" for r in reports),
            "unattributed_inputs": sum(r["verdict"] == "UNATTRIBUTED" for r in reports)}


def transport_lifecycle(original_rows: list[dict[str, Any]], port_rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Lifecycle is compared separately from wire packets; absent APIs are explicit gaps."""
    original = [{"kind": r["kind"], "topic": str(r["topic"]), "at_ms": r["at_ms"]}
                for r in original_rows if r.get("kind") in ("start_service", "stop_service")]
    actual = [{"kind": r["note"], "topic": str(r["data"]["topic"]), "at_ms": r["at_ms"]}
              for r in port_rows if r.get("kind") == "note" and r.get("note") in ("start_service", "stop_service")]
    differences = []
    for i in range(max(len(original), len(actual))):
        a = original[i] if i < len(original) else None
        b = actual[i] if i < len(actual) else None
        if a != b:
            differences.append({"ordinal": i, "original": a, "port": b})
    return {"scope": "start/stop service lifecycle, exact ordinal/topic/absolute time; distinct from IVI-to-ADAS fire packets",
            "original": original, "port": actual, "differences": differences,
            "verdict": "DIFFERENT" if differences else "EQUAL"}


def compare(inputs: Path, reference: Path, port: Path, *, timing_tolerance_ms: int = 0,
            allow_route_wall_clock: bool = False, required_coverage: tuple[str, ...] = REQUIRED_COVERAGE) -> dict[str, Any]:
    if timing_tolerance_ms < 0:
        raise ValueError("timing tolerance must be explicit and nonnegative")
    in_rows, original_rows, port_rows = read_jsonl(inputs), read_jsonl(reference), read_jsonl(port)
    counts = validate_inputs(in_rows)
    rh, ph = original_rows[0], port_rows[0]
    if rh.get("schema") != REFERENCE_SCHEMA or rh.get("kind") != "header":
        raise ValueError("independent ORIGINAL DEX reference header required")
    if source(rh) != source(in_rows[0]) or rh.get("input_sha256") != digest(inputs):
        raise ValueError("reference provenance/input digest mismatch")
    if ph.get("schema") != "denza-stock-sender-port-v1" or ph.get("input_header") != in_rows[0]:
        raise ValueError("port did not replay this original input header")
    original = [packet(r) for r in original_rows[1:] if r.get("kind") == "fire"]
    actual = [packet(r) for r in port_rows[1:] if r.get("kind") == "fire"]
    if not original or not actual:
        raise ValueError("empty original/port packet stream cannot establish equivalence")
    gaps = [r["data"] for r in port_rows if r.get("kind") == "note" and r.get("note") == "coverage_gap"]
    gaps += [{"missing_scenario_event": event} for event in required_coverage if not counts.get(event)]
    lifecycle = transport_lifecycle(original_rows, port_rows)
    if lifecycle["differences"]:
        gaps.append({"transport_lifecycle_unrepresented_or_different": lifecycle["differences"]})
    requests = [r for r in original_rows if r.get("kind") == "client_send"]
    gaps += [{"unrepresented_original_client_request": r} for r in requests]
    gaps += [{"original_capture_gap": r} for r in original_rows if r.get("kind") in ("capture_error", "coverage_gap")]
    mismatches, allowed = [], []
    for index in range(max(len(original), len(actual))):
        if index >= len(original) or index >= len(actual):
            mismatches.append({"packet": index, "type": "extra_port_packet" if index >= len(original) else "missing_port_packet",
                               "original": original[index] if index < len(original) else None,
                               "port": actual[index] if index < len(actual) else None})
            continue
        a, b = original[index], actual[index]
        if a["topic"] != b["topic"]:
            mismatches.append({"packet": index, "type": "topic_order", "original": a, "port": b})
            continue
        delta = b["at_ms"] - a["at_ms"]
        if abs(delta) > timing_tolerance_ms:
            mismatches.append({"packet": index, "type": "timing", "original_ms": a["at_ms"], "port_ms": b["at_ms"], "delta_ms": delta})
        elif delta:
            allowed.append({"packet": index, "type": "explicit_timing_tolerance", "delta_ms": delta})
        if a["payloadHex"] != b["payloadHex"]:
            ab, bb = bytes.fromhex(a["payloadHex"]), bytes.fromhex(b["payloadHex"])
            only_clock, exception = route_wall_time_only(ab, bb) if allow_route_wall_clock and a["topic"] == ROUTE_TOPIC else (False, None)
            if only_clock:
                allowed.append({"packet": index, "type": "explicit_route_wall_clock", **exception})
            else:
                mismatches.append({"packet": index, "type": "payload_bytes", "topic": str(a["topic"]),
                                   "original_hex": a["payloadHex"], "port_hex": b["payloadHex"],
                                   "decoded": decoded_difference(a["topic"], ab, bb)})
    return {"schema": "denza-stock-sender-parity-report-v1", "comparator_sha256": digest(Path(__file__)), "verdict": "DIFFERENT" if mismatches else "INCOMPLETE" if gaps else "EQUAL",
            "input_sha256": digest(inputs), "reference_sha256": digest(reference), "port_sha256": digest(port),
            "source": source(rh), "event_counts": counts, "original_packet_count": len(original), "port_packet_count": len(actual),
            "comparison": "all fireEvent packets by ordinal: topic, full bytes, monotonic timestamp; no resync/drop/dedupe",
            "interpretation_limits": ["Original process constructors and timer phase may predate sender_start; port start initializes its own immediate status and 1 Hz phase. Global differences include this initialization gap and are observations, not automatically port defects.",
                                      "Native progress fields and other callbacks without a port input remain explicit coverage gaps; causal comparison does not waive them."],
            "causal_comparison": causal_comparison(in_rows, original_rows, port_rows),
            "transport_lifecycle": lifecycle,
            "required_events": list(required_coverage),
            "directions": {"packet_comparison": "IVI-to-ADAS fireEvent egress", "client_requests": "preserved as coverage gaps", "ADAS-to-IVI answers": "scripted emulator reply captured before original receiver; replay injects retained reply without another ACK generator"},
            "policy": {"timing_tolerance_ms": timing_tolerance_ms, "allow_route_wall_clock": allow_route_wall_clock},
            "allowed_nondeterminism": allowed, "coverage_gaps": gaps, "mismatches": mismatches}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--artifact-manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "captures/stock-sender-parity-20261025/port")
    parser.add_argument("--timing-tolerance-ms", type=int, default=0)
    parser.add_argument("--scenario", choices=tuple(COVERAGE_PRESETS), default="full")
    parser.add_argument("--allow-route-wall-clock", action="store_true")
    args = parser.parse_args()
    rows = read_jsonl(args.inputs)
    validate_inputs(rows)
    artifacts = validate_artifacts(args.artifact_manifest, source(rows[0]))
    args.out.mkdir(parents=True, exist_ok=True)
    port = args.out / "port.jsonl"
    proof = replay(args.inputs.resolve(), port.resolve(), (args.out / "classes").resolve())
    report = compare(args.inputs, args.reference, port, timing_tolerance_ms=args.timing_tolerance_ms,
                     allow_route_wall_clock=args.allow_route_wall_clock, required_coverage=COVERAGE_PRESETS[args.scenario])
    report["scenario"] = args.scenario
    report["verified_artifacts"], report["port_build"] = artifacts, proof
    (args.out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"verdict": report["verdict"], "mismatches": len(report["mismatches"]),
                      "coverage_gaps": len(report["coverage_gaps"]), "report": str(args.out / "report.json")}))
    return 0 if report["verdict"] == "EQUAL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
