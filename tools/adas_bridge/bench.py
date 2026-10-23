#!/usr/bin/env python3
"""Actual input → incomplete experimental route bytes → fake local receiver.

Explicit stock-golden envelope template; null primitive fields are intentionally
retained and are NOT asserted to form a valid ADAS route. No transport exists.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path

from .bridge import ContractError, read_json, recursive_diff
from .receiver import FakeReceiver
from .wire import (decode_someip_route, encode_binder_route, encode_someip_route)
from .dynamic import selection_bytes


def null_paths(value, prefix="$"):
    if value is None:
        return [prefix]
    if isinstance(value, dict):
        return [path for key, child in value.items() for path in null_paths(child, prefix+"."+key)]
    if isinstance(value, list):
        return [path for i, child in enumerate(value) for path in null_paths(child, f"{prefix}[{i}]")]
    return []


def make_bench(converted, golden_record, *, time_ms, receiver=None):
    route = copy.deepcopy(converted["candidateNaviToEHPData"])
    # Verified native enum null sentinels only. Remaining nulls remain visible.
    substitutions = []
    for index, link in enumerate(route["links"]):
        for key in ("roadclass", "linktype"):
            if link[key] is None:
                link[key] = -1
                substitutions.append({"path": f"links[{index}].{key}", "value": -1,
                                      "reason": "native enum NULL sentinel; receiver semantics unverified"})
    payload = copy.deepcopy(golden_record["payload"])
    if not isinstance(payload, dict):
        raise ContractError("bench requires an explicit complete stock golden envelope template")
    payload["data"] = [route]
    template = {key: copy.deepcopy(value) for key, value in payload.items() if key != "data"}
    wrapper = {"currentIndex": 0, "length": 1, "pathCount": 1,
               "payload": json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False),
               "time": time_ms}
    text = json.dumps(wrapper, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    binder = encode_binder_route(text)
    record = copy.deepcopy(golden_record)
    record.update(wireJson=text, checksCRC32=0, counter=0, navigationSDLink2Num=1)
    someip = encode_someip_route(record)
    decoded = decode_someip_route(someip)
    if recursive_diff(decoded["payload"], payload):
        raise ContractError("bench SOME/IP JSON roundtrip changed payload")
    receiver = receiver if receiver is not None else FakeReceiver()
    receiver.offer_binder_route(binder, converted)
    path = route["pathID"]
    selection = selection_bytes(path)
    receiver.select_binder(selection)
    receiver.confirm(path, publication_epoch=receiver.publication_epoch)
    return {"payload": payload, "wrapper": wrapper, "binder": binder, "someip": someip,
            "selection": selection,
            "receiver": receiver,
            "manifest": {"offlineOnly": True, "experimentalIncompleteDraft": True,
                         "validAdasRouteEstablished": False, "publicationReady": False,
                         "sourceRouteId": converted["routeId"], "sourceGeneration": converted.get("generation"),
                         "stockTemplateMetadata": template, "nativeNullSentinelSubstitutions": substitutions,
                         "remainingNullFields": null_paths(route),
                         "binderBytes": len(binder), "someipBytes": len(someip),
                         "selectionBytes": len(selection), "selectionSHA256": hashlib.sha256(selection).hexdigest(),
                         "binderSHA256": hashlib.sha256(binder).hexdigest(),
                         "someipSHA256": hashlib.sha256(someip).hexdigest(),
                         "fakeReceiverTrace": receiver.trace,
                         "limits": ["JSON null primitives are not proved acceptable to ADAS",
                                    "stock template metadata is explicit test input, not Yandex-derived",
                                    "SOME/IP header client/session copied from capture for framing only",
                                    "mock ACK epochs are local bookkeeping; real ACK has no epoch token"]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("converted")
    parser.add_argument("golden_someip")
    parser.add_argument("--time-ms", required=True, type=int)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    try:
        result = make_bench(read_json(args.converted), decode_someip_route(Path(args.golden_someip).read_bytes()), time_ms=args.time_ms)
        out = Path(args.out_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "bench.route.binder.protobuf.bin").write_bytes(result["binder"])
        (out / "bench.route.someip.bin").write_bytes(result["someip"])
        (out / "bench.selection.binder.protobuf.bin").write_bytes(result["selection"])
        (out / "bench.payload.json").write_text(json.dumps(result["payload"], ensure_ascii=False, indent=2)+"\n")
        (out / "bench.manifest.json").write_text(json.dumps(result["manifest"], ensure_ascii=False, indent=2)+"\n")
        print(json.dumps({"out": str(out), "binderBytes": len(result["binder"]), "someipBytes": len(result["someip"]), "publicationReady": False}))
    except (ValueError, TypeError, KeyError) as exc:
        parser.exit(2, f"invalid bench input: {exc}\n")


if __name__ == "__main__":
    main()
