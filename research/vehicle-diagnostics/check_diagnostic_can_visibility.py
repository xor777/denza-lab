#!/usr/bin/env python3
"""Strict offline replay of the completed can-visibility attempt, including its failure.

No transport is imported or invoked. Only the frozen host's health parsing
functions/constants are compiled, never its operational functions or main.
"""
import argparse
import ast
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "captures/diagnostics-can-visibility-20261021"
LIVE = BASE / "live-01"
OUTPUT = Path(__file__).with_name("diagnostic-can-visibility-20261021.json")
DECODER = Path(__file__).with_name("decode_supported_dtc.py")
HAL = ROOT / "captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/hw/auto.default.so"
STREAMS = {"ota": (1032, "9900001E"), "gb": (1039, "9900001F"),
           "bigdata": (1061, "99000020"), "yun": (1034, "99000021"),
           "mqtt": (1033, "99000165"), "secretobd": (1023, "99000241")}


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def elf_bytes(image, va, length):
    require(image[:6] == b"\x7fELF\x02\x01", "ELF64 LE")
    off = struct.unpack_from("<Q", image, 32)[0]
    stride, count = struct.unpack_from("<HH", image, 54)
    require(stride == 56 and count < 100, "ELF bound")
    for index in range(count):
        kind, _, pos, address, _, size, _, _ = struct.unpack_from("<IIQQQQQQ", image, off + index * stride)
        if kind == 1 and address <= va and va + length <= address + size:
            return image[pos + va - address:pos + va - address + length]
    raise ValueError("unmapped native anchor")


def health_parser(source):
    tree = ast.parse(source)
    namespace = {"re": re}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name in ("FIRMWARE", "STOCK", "EXPORT_PORTS"):
                namespace[name] = ast.literal_eval(node.value)
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                 and node.name in ("parse_health", "acceptable")]
    require(len(functions) == 2, "frozen host health functions")
    exec(compile(ast.Module(body=functions, type_ignores=[]), "<frozen-health-parsers-only>", "exec"), namespace)
    return namespace


def derive():
    require((LIVE / "run.json").is_file(), "run.json absent: do not read an intermediate probe")
    state = json.loads((LIVE / "run.json").read_text())
    require(state["state"] in ("process_exited", "probe_failed", "stopped"), "run not terminal")
    paths = {Path(__file__), DECODER, LIVE / "run.json", BASE / "experiment-plan.json"}
    plan = json.loads((BASE / "experiment-plan.json").read_text())
    manifest = json.loads((LIVE / "manifest.json").read_text())
    build = BASE / plan["build"]
    require(plan["build"] == "build-04" and
            state["manifestSha256"] == sha(LIVE / "manifest.json") == sha(build / "manifest.json"), "frozen build manifest")
    require(state["jarSha256"] == manifest["jarSha256"] == plan["jarSha256"] ==
            sha(LIVE / manifest["jar"]) == sha(build / manifest["jar"]), "frozen built/staged jar")
    require(manifest["plans"]["can-visibility"] == plan["fixedPlan"], "fixed plan binding")
    require(state["plan"] == "can-visibility" and not state["apkInstalled"], "plan/install boundary")
    for path in (LIVE / "manifest.json", build / "manifest.json", LIVE / manifest["jar"], build / manifest["jar"]):
        paths.add(path)
    for item in manifest["sources"]:
        frozen = build / manifest["sourceSnapshotDirectory"] / item["path"]
        require(sha(frozen) == item["sha256"], "frozen source drift " + item["path"])
        paths.add(frozen)
    host_path = LIVE / "host-source.py"
    require(sha(host_path) == state["hostSourceSha256"], "frozen host hash")
    paths.add(host_path)
    host = health_parser(host_path.read_text())
    health = {}
    executed = "processStartedUTC" in state
    for stage, parent_key in (("preflight", "before"), ("postflight", "after")):
        directory = LIVE / stage
        if not directory.is_dir():
            require(not executed, "executed run requires raw health directory: " + stage)
            continue
        raw = {p.stem: p.read_text() for p in directory.glob("*.txt")}
        saved = json.loads((directory / "status.json").read_text())
        paths.update(p for p in directory.iterdir() if p.is_file())
        require(saved == state[parent_key], "host/status health mismatch")
        require(not executed or saved["state"] == "read_complete", "executed run requires completed raw health: " + stage)
        if saved["state"] == "read_complete":
            parsed = host["parse_health"](raw)
            require(all(saved[key] == value for key, value in parsed.items()), "health reparsing mismatch")
            for name, command in saved["commands"].items():
                require(command["exitCode"] == 0 and command["stdoutBytes"] == len((directory / (name + ".txt")).read_bytes()), "health command result")
            require(all(not p.read_bytes() for p in directory.glob("*.stderr")), "health stderr")
            health[stage] = {key: saved[key] for key in ("utc", "state", "guardPass", "firmwareHashMatch", "crashEmpty", "stockPids", "stockPresent", "activeResearchProcesses", "exportIdle", "appSha256")}
        else:
            health[stage] = {"state": saved["state"], "error": saved.get("error"), "acceptable": False}
    probe_path = LIVE / "probe.txt"
    text = probe_path.read_text() if probe_path.exists() else ""
    paths.update(p for p in (probe_path, LIVE / "probe.stderr") if p.exists())
    if not executed:
        require(not any(line.startswith("REQUEST ") for line in text.splitlines()), "preflight failure must send no requests")
        return {"schema": "denza-lab.diagnostic-can-visibility.v1", "status": "preflight_failed_no_ecu_requests",
                "wireRequestCount": 0, "health": health, "error": state.get("error"),
                "replayVehicleOperations": 0, "sources": source_records(paths)}
    require(set(health) == {"preflight", "postflight"}, "both raw health stages reparsed")
    require(host["acceptable"](state["before"]) and host["acceptable"](state["after"]), "pre/post health gates")
    require(state["before"]["appSha256"] == state["after"]["appSha256"] and
            state["before"]["stockPids"] == state["after"]["stockPids"] and state["healthStable"], "health stability")
    for command, code in (("app-immediate", 0), ("stage", 0), ("jar-hash", 0), ("remove-own-jar", 0),
                          ("verify-jar-absent", 1), ("verify-process-absent", 1)):
        require(state["hostCommands"][command]["exitCode"] == code, "host command " + command)
        for suffix in (".stdout", ".stderr"):
            path = LIVE / (command + suffix)
            paths.add(path)
            if command.startswith("verify-"):
                require(not path.read_bytes().strip(), "cleanup absence proof")
    require((LIVE / "jar-hash.stdout").read_text().split() == [state["jarSha256"], state["remoteJar"]], "remote jar identity")
    require((LIVE / "app-immediate.stdout").read_text().split()[0] == state["before"]["appSha256"], "immediate current APK baseline")
    require(state["jarRemoved"] and state["processAbsent"] and not state.get("hostTerminationRequired"), "physical cleanup")
    require(not (LIVE / "probe.stderr").read_bytes(), "probe stderr")
    require(len(probe_path.read_bytes()) <= plan["operationLimits"]["maxHostOutputBytes"], "host output cap")
    lines = text.splitlines()
    main_end = [index for index, line in enumerate(lines) if line.startswith("END ")]
    unregister = [index for index, line in enumerate(lines) if line == "UNREGISTERED"]
    rejection = [index for index, line in enumerate(lines) if line.startswith("REJECTED_FRAME ")]
    flush = [index for index, line in enumerate(lines) if line.startswith(
        ("CAN_OBSERVER_FRAME ", "CAN_OBSERVER_STREAM ", "CAN_OBSERVER_END "))]
    require(len(main_end) == 1 and main_end[0] == len(lines) - 1, "no lines after main END")
    require(len(unregister) == len(rejection) == 1 and
            rejection[0] < unregister[0] < main_end[0] and
            flush == list(range(rejection[0] + 1, unregister[0])) and bool(flush),
            "observer flush must follow rejection and precede unregister/main END")

    spec = importlib.util.spec_from_file_location("can_visibility_offline_decoder", DECODER)
    decoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(decoder)
    diagnostic_text = "\n".join(line for line in text.splitlines() if not line.startswith("CAN_OBSERVER_")) + "\n"
    parsed = decoder.parse_probe(diagnostic_text, manifest, allow_partial=True)
    events, end = parsed["events"], parsed["end"]
    require(len(events) == 6 and end["reason"] == "DUPLICATE_DTC_CODE" and
            int(end["completed"]) == 5 and end["targets"] == "1" and end["cleanup"] == "false" and
            end["queued"] == "1" and end["callbacks"] == "12", "exact failed collector outcome")
    require(state["state"] == "probe_failed" and state["exitCode"] == 2 and not state["lifecycleVerified"], "failed host lifecycle preserved")
    exchanges = []
    for event in events:
        require(event["response"] is not None, "all six exchanges have wire replies")
        exchanges.append({"seq": event["seq"], "target": event["target"], "requestHex": event["request"],
                          "responseHex": event["response"]["uds"], "responseFragments": int(event["response"]["frames"]),
                          "latencyMs": int(event["response"]["latency_ms"]),
                          "requestNanos": event["requestNanos"], "replyNanos": int(event["response"]["t_ns"]),
                          "collectorStepCompleted": event["stepEnd"] is not None,
                          "duplicateCallbackDeliveriesLogged": len(event["duplicates"])})
    for index, target_plan in ((0, plan["fixedPlan"]["targets"][0]), (3, plan["fixedPlan"]["targets"][1])):
        require(exchanges[index]["responseHex"] == "62F194" + target_plan["expectedSoftwareCode"], "exact F194")
        count = decoder.parse_count(bytes.fromhex(exchanges[index + 1]["responseHex"]))
        require(f"{count['formatId']:02X}" == target_plan["expectedFormatIdHex"] and count["memoryCountForMask09"] == 0, "format/count prerequisites")
    require(exchanges[2]["responseHex"] == "7F1913", "ECM observed NRC13")
    isg_bytes = bytes.fromhex(exchanges[5]["responseHex"])
    registry = decoder.parse_supported(isg_bytes)
    require(len(isg_bytes) == 231 and registry["recordCount"] == 57 and registry["statusAvailabilityHex"] == "09", "ISG positive shape")
    positions = defaultdict(list)
    records = []
    for position, row in enumerate(registry["records"], 1):
        positions[row["raw24Hex"]].append(position)
        records.append({"positionOneBased": position, "raw24Hex": row["raw24Hex"], "statusHex": row["statusHex"]})
    duplicates = [{"raw24Hex": code, "positionsOneBased": indices, "statusesHex": [records[i - 1]["statusHex"] for i in indices]}
                  for code, indices in positions.items() if len(indices) > 1]
    require(len(positions) == 56 and duplicates == [{"raw24Hex": "C1D487", "positionsOneBased": [28, 40], "statusesHex": ["00", "00"]}]
            and all(row["statusHex"] == "00" for row in records), "preserved duplicate/raw statuses")
    rejected = parsed["rejectedFrames"]
    require(len(rejected) == 1 and rejected[0]["reason"] == "DUPLICATE_DTC_CODE" and
            rejected[0]["t_ns"] == events[-1]["frames"][0]["t_ns"], "one callback with two diagnostic views")
    source_buffer = bytes.fromhex(rejected[0]["raw"])
    declared_frame = bytes.fromhex(events[-1]["frames"][0]["raw"])
    require(source_buffer == declared_frame + b"\0\0" and len(source_buffer) == 240 and len(declared_frame) == 238,
            "two outer-buffer padding bytes are not UDS data")
    old_path = ROOT / "captures/diagnostics-isg-followup-20261021/isg-trace/probe.txt"
    paths.add(old_path)
    old_line = next(line for line in old_path.read_text().splitlines() if line.startswith("GATEWAY_TRACE "))
    old_trace = bytes.fromhex(old_line.split(" raw=", 1)[1])[3:]
    old_rx = [old_trace[i + 5:i + 13] for i in range(0, len(old_trace), 13) if old_trace[i + 3:i + 5] == b"\x07\xee"]
    old_prefix = old_rx[0][2:] + b"".join(frame[1:] for frame in old_rx[1:])
    require(len(old_prefix) == 125 and isg_bytes[:125] == old_prefix, "all prior125-byte prefix agrees")

    ready, streams, retained = {}, {}, []
    observer_end = None
    for line in text.splitlines():
        if not line.startswith("CAN_OBSERVER_"):
            continue
        kind, fields = decoder.pairs(line)
        require(observer_end is None, "observer data after END")
        if kind == "CAN_OBSERVER_END":
            observer_end = fields
            continue
        name = fields["stream"]
        require(name in STREAMS, "known observer stream")
        if kind == "CAN_OBSERVER_READY":
            require(name not in ready and (int(fields["device"]), fields["fid"]) == STREAMS[name], "observer registration identity")
            require(int(fields["t_ns"]) < events[0]["requestNanos"], "READY before first ECU request")
            ready[name] = fields
        elif kind == "CAN_OBSERVER_FRAME":
            raw = bytes.fromhex(fields["raw"])
            require(name in ready and len(raw) == int(fields["bytes"]) <= 1024 and
                    int(fields["seq"]) == 1 and fields["kind"] == "SAMPLE", "retained first-sample bounds")
            item = {"stream": name, "sequence": 1, "receivedNanos": int(fields["t_ns"]), "kind": fields["kind"],
                    "bytes": len(raw), "rawHex": fields["raw"], "opaque": name == "secretobd"}
            if name != "secretobd":
                require(len(raw) >= 10, "raw envelope sample")
                identifier = int.from_bytes(raw[:4], "big")
                require(identifier not in (0x7E8, 0x7EE), "sample cannot hide a target candidate")
                item.update(canIdHex=f"{identifier:X}", subIdHex=f"{raw[4]:02X}", internalSelector=raw[5],
                            recordCounterBE32=int.from_bytes(raw[6:10], "big"), canPayloadHex=raw[10:].hex().upper())
            retained.append(item)
        elif kind == "CAN_OBSERVER_STREAM":
            require(name not in streams and name in ready, "one summary per observer")
            numeric = {key: int(fields[key]) for key in ("callbacks", "candidates", "malformed", "first_ns", "last_ns")}
            require(numeric["callbacks"] >= 0 and numeric["candidates"] == numeric["malformed"] == 0, "observed stream counts")
            if numeric["callbacks"]:
                sample = [row for row in retained if row["stream"] == name]
                require(len(sample) == 1 and sample[0]["receivedNanos"] == numeric["first_ns"] and
                        int(ready[name]["t_ns"]) <= numeric["first_ns"] <= numeric["last_ns"] <= exchanges[-1]["replyNanos"], "stream sample/time ledger")
            else:
                require(numeric["first_ns"] == numeric["last_ns"] == 0 and not any(row["stream"] == name for row in retained), "empty stream ledger")
            streams[name] = numeric
        else:
            raise ValueError("unknown observer record")
    require(set(ready) == set(streams) == set(STREAMS), "all six observer records")
    require(lines[0] == "REGISTERED" and
            all(lines[index + 1].startswith("CAN_OBSERVER_READY stream=" + name + " ")
                for index, name in enumerate(STREAMS)) and
            lines[7].startswith("START plan=can-visibility "), "six READY records before START")
    require(events[0]["requestNanos"] - max(int(row["t_ns"]) for row in ready.values()) >= 2_000_000_000,
            "two-second quiet interval after final observer READY")
    count = sum(row["callbacks"] for row in streams.values())
    require(observer_end is not None and observer_end == {"callbacks": str(count), "retained": str(len(retained)), "failed": "false", "cleanup": "true"}, "observer END ledger")
    require(count == 3664 < 50000 and len(retained) == 4 < 512, "raw delivery/retention caps")

    logs = BASE / "existing-logs"
    commands = json.loads((logs / "commands.json").read_text())
    corrected = json.loads((logs / "mculog-pid-regex.json").read_text())
    paths.update(p for p in logs.iterdir() if p.is_file())
    require(commands[0]["name"] == "log-level" and commands[0]["exitCode"] == 0 and
            (logs / "log-level.txt").read_text().strip() == "2", "existing loglevel")
    require(commands[1]["exitCode"] == 1 and "Permission denied" in (logs / "log-dir.stderr").read_text(), "restricted log directory")
    require(commands[2]["exitCode"] == 1 and "Invalid filter expression" in (logs / "mculog.stderr").read_text(), "failed colon-tag command")
    require(corrected["exitCode"] == 0 and corrected["stdoutBytes"] == 0 and
            "--pid=128" in corrected["command"] and not (logs / "mculog-pid-regex.txt").read_bytes() and
            not (logs / "mculog-pid-regex.stderr").read_bytes(), "corrected existing-ring window")
    require(sha(HAL) == "5d65fd3b430aec7f2e06496ba427e652613fef995c48c2d4066126dec9f446c7", "HAL identity")
    paths.add(HAL)
    paths.update(p for p in (BASE / "hal").iterdir() if p.is_file())
    image = HAL.read_bytes()
    anchors = [{"startVA": hex(va), "bytesHex": elf_bytes(image, va, size).hex(), "meaning": meaning}
               for va, size, meaning in ((0x83CB4, 4, "getOwner miss returns-10011; no wildcard insertion here"),
                                        (0xB148C, 32, "raw owner log format and severity3"),
                                        (0x1B3954, 12, "customLogLine rejects level greater than2"),
                                        (0x66178, 24, "mculog string logged at severity0"))]
    for report in ("diagnostic-can-mcu-visibility-20261021.json", "diagnostic-can-observer-candidates-20261021.json"):
        paths.add(OUTPUT.with_name(report))
    return {"schema": "denza-lab.diagnostic-can-visibility.v1", "date": "2026-10-21",
            "status": "failed_collector_policy_after_complete_isg_wire_response", "replayVehicleOperations": 0,
            "historicalVehicleRequests": 6, "fullPassAccepted": False,
            "build": {"name": "build-04", "jarSha256": state["jarSha256"], "manifestSha256": state["manifestSha256"],
                      "hostSourceSha256": state["hostSourceSha256"], "tests": manifest["tests"], "frozenSourceCount": len(manifest["sources"])},
            "health": health, "exchanges": exchanges, "completion": parsed["completion"], "probeEnd": end,
            "cleanup": {"observerUnregisterReported": True, "diagnosticUnregisteredObserved": True,
                        "collectorCleanup": False, "queuedDiagnosticCallbacks": 1,
                        "queuedContentKnown": False, "hostJarRemoved": True, "hostProcessAbsent": True,
                        "healthStable": True, "hostLifecycleVerified": False, "hostExitCode": 2,
                        "meaning": "Observer/diagnostic unregister and host artifact/process cleanup are separate from the failed collector cleanup flag caused by a nonempty queue."},
            "diagnosticCallbackAccounting": {"deliveredCallbacksReported": 12,
                                             "processedDistinctDeliveriesLogged": 11,
                                             "duplicateDeliveriesLogged": 5,
                                             "rejectedFrameIsSecondViewOfSameCallback": True,
                                             "unprocessedQueuedCallbacks": 1,
                                             "uniquePhysicalCanFrames": None},
            "ecm": {"softwareCode": "32440011E004000000", "formatId": 0, "statusAvailabilityHex": "FF",
                    "mask09Count": 0, "supportedRequest": "190A", "observedReply": "7F1913",
                    "nrcOriginQualified": False, "rootCauseQualified": False},
            "isg": {"softwareCode": "48370011E0EA000000", "formatId": 1, "statusAvailabilityHex": "09",
                    "mask09Count": 0, "positiveUdsComplete": True, "udsBytes": 231, "outerFragments": 1,
                    "originalCallbackBytes": 240, "declaredOuterFrameBytes": 238, "ignoredOuterPaddingHex": "0000",
                    "recordCount": 57, "uniqueRawCodes": 56, "duplicates": duplicates, "records": records,
                    "collectorUniqueCodePolicyAccepted": False, "udsViolationEstablished": False,
                    "ecuCorruptionEstablished": False, "qualifiedFactoryDefinitions": [], "format01RenderedRaw": True,
                    "priorFailureReportComparison": {"source": str(old_path.relative_to(ROOT)), "priorPrefixBytes": 125,
                        "allPriorPrefixBytesEqual": True, "completeTuplePrefixCount": 30, "all30TupleBytesEqual": True,
                        "newWireBytesBeyondPriorPrefix": 106,
                        "priorPartialTupleTailHex": "183F", "availabilityUnchanged": True, "formatUnchanged": True,
                        "mask09CountUnchanged": True, "priorTimeoutCauseQualified": False}},
            "observers": {"sdkReadyCount": 6, "readyDoesNotProveNativeDelivery": True,
                          "streams": streams, "samples": retained, "callbackDeliveryCount": count,
                          "retainedBuffers": len(retained), "targetCandidatesReported": 0,
                          "ledgerFailed": False, "unregisterCleanupReported": True,
                          "caps": {"callbackDeliveries": 50000, "retainedBuffers": 512, "bufferBytes": 1024},
                          "allCallbackPayloadsRetained": False, "secretObdOpaque": True,
                          "uniquePhysicalFrameCount": None, "canWireAbsenceEstablished": False},
            "existingLogs": {"loglevel": 2, "autohalDirectoryReadable": False,
                             "firstColonTagCommandFailed": True, "failedCommandCountedAsEmpty": False,
                             "correctedPid128ExistingRingQueryExitCode": 0, "correctedWindowMatchingBytes": 0,
                             "continuousMcuLogCoverageEstablished": False, "loggingChanged": False},
            "native": {"halSha256": sha(HAL), "anchors": anchors,
                       "ownerMissCode": -10011, "wildcardOnMiss": False,
                       "rawOwnerLogSeverity": 3, "loggerMaximumSeverity": 2,
                       "rawOwnerLogSuppressedByThreshold": True,
                       "limits": "Byte anchors and inspected source only; no native execution or installed-MCU hash claim."},
            "limits": ["Six READY lines prove SDK registration calls returned, not native enable success or universal raw coverage.",
                       "Callback counts include duplicate interface deliveries; four first samples do not preserve all3664 payloads.",
                       "Individual stream totals are retained ledger claims: balanced redistribution keeping3664 total deliveries cannot be independently disproved from four samples; default pinned-source checks detect alteration of the original transcript.",
                       "No target candidates in selected streams does not establish absence on CAN or identify the reason for missing visibility.",
                       "SecretOBD remains opaque because its extended-packet native pointer semantics are unqualified.",
                       "Complete positive ISG bytes and collector policy rejection are separate facts; no UDS violation, ECU corruption or prior-timeout root cause is inferred.",
                       "Main cleanupfalse/queued1 is preserved despite successful unregister indications and host cleanup.",
                       "No historical result was overwritten; all comparison inputs remain separate retained captures."],
            "sources": source_records(paths)}


def source_records(paths):
    return [{"path": str(p.relative_to(ROOT)), "bytes": p.stat().st_size, "sha256": sha(p)} for p in sorted(paths)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="save derived offline aggregate")
    args = parser.parse_args()
    if not args.write:
        saved = json.loads(OUTPUT.read_text())
        for row in saved["sources"]:
            path = ROOT / row["path"]
            require(path.stat().st_size == row["bytes"] and sha(path) == row["sha256"], "pinned source drift: " + row["path"])
    derived = derive()
    if args.write:
        OUTPUT.write_text(json.dumps(derived, ensure_ascii=False, indent=2) + "\n")
    else:
        require(derived == saved, "aggregate differs from strict replay")
    print("PASS: terminal run and frozen build/host/health; 6 wire replies, failed collector policy, "
          "ISG57records/56unique, six observer ledgers and physical cleanup replayed; no CAN absence/rootcause claim")


if __name__ == "__main__":
    main()
