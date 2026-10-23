#!/usr/bin/env python3
"""Replay actual emulator guidance ring into route-byte parser and fake receiver.

Uses recorded uptime with zero simulated delivery delay, never current freshness.
Accepts one source route snapshot; other route IDs are explicitly unavailable.
"""
import argparse
import json
from pathlib import Path

from .bridge import ContractError, read_json
from .watch import LiveBridge
from .wire import decode_someip_route


def replay_capture(snapshot, samples, golden_record, *, target_frame):
    samples = sorted(samples, key=lambda row: row.get("capturedUptimeMs", -1))
    identities = [(row.get("sourceSessionId"), row.get("sequence")) for row in samples]
    if (not identities or any(type(sequence) is not int or sequence < 0 for _, sequence in identities) or
            len(set(identities)) != len(identities)):
        raise ContractError("guidance ring needs unique session/sequence identities")
    engine, outputs, bench_outputs = LiveBridge(golden_record, target_frame), [], []
    for sample in samples:
        now = sample.get("capturedUptimeMs")
        if type(now) is not int:
            raise ContractError("guidance sample missing capturedUptimeMs")
        if sample.get("routeId") != snapshot.get("routeId"):
            engine.receiver.end(now_ms=now)
            outputs.append({"sequence": sample["sequence"], "accepted": False,
                            "reason": "cancelled_or_route_not_in_supplied_archive", "status": engine.receiver.status(now_ms=now)})
            continue
        old_bench = engine.bench
        receipt = engine.tick(sample, now_ms=now, snapshot=snapshot)
        if engine.bench is not None and engine.bench is not old_bench:
            bench_outputs.append(engine.bench)
        result = receipt.get("positionCandidate", {"accepted": False, "reason": receipt["reason"]})
        outputs.append({"sequence": sample["sequence"], "sourceGeneration": sample.get("generation"),
                        "sourceSessionId": sample.get("sourceSessionId"),
                        "publicationEpoch": engine.receiver.publication_epoch, "result": result,
                        "dynamicBinderPackets": receipt.get("dynamicBinderPackets")})
    return {"schema": "denza-adas-capture-replay-v1", "offlineOnly": True,
            "publicationReady": False, "receiverAcceptanceEstablished": False,
            "clockPolicy": "recorded uptime, zero simulated delivery delay; no assertion of current freshness",
            "sourceRouteId": snapshot.get("routeId"), "samples": len(samples),
            "acceptedOfflineGeometrySamples": sum(bool(row.get("result", {}).get("accepted")) for row in outputs),
            "sourceGenerations": sorted({row.get("generation") for row in samples if type(row.get("generation")) is int}),
            "outputs": outputs, "trace": engine.receiver.trace}, bench_outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot")
    parser.add_argument("progress_directory")
    parser.add_argument("golden_someip")
    parser.add_argument("--target-frame", choices=("wgs84", "native-gcj-unrestricted-candidate"), required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    try:
        samples = [read_json(path) for path in Path(args.progress_directory).glob("sample-*.json")]
        result, benches = replay_capture(read_json(args.snapshot), samples,
                                         decode_someip_route(Path(args.golden_someip).read_bytes()),
                                         target_frame=args.target_frame)
        out = Path(args.out_dir)
        out.mkdir(parents=True, exist_ok=True)
        for i, bench in enumerate(benches):
            (out / f"generation-{i:03d}.binder.protobuf.bin").write_bytes(bench["binder"])
            (out / f"generation-{i:03d}.someip.bin").write_bytes(bench["someip"])
            (out / f"generation-{i:03d}.selection.binder.protobuf.bin").write_bytes(bench["selection"])
            (out / f"generation-{i:03d}.manifest.json").write_text(json.dumps(bench["manifest"], ensure_ascii=False, indent=2)+"\n")
        (out / "replay.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
        print(json.dumps({key: value for key, value in result.items() if key not in ("outputs", "trace")}))
    except (ValueError, TypeError, KeyError) as exc:
        parser.exit(2, f"invalid captured replay: {exc}\n")


if __name__ == "__main__":
    main()
