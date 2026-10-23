#!/usr/bin/env python3
"""Converted route -> the stock route event bytes -> fake local receiver.

The MessageSend is the stock oracle's (tools/adas_bridge/stock.py): no JSON null anywhere, the
stock envelope (endPOIInfo, mapProvider, RouteType) from the stock code itself, NaviDataDetail
chunks exactly as n.a() cuts them (F7). The captured golden only lends its SOME/IP header for the
native framing. No transport exists.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from . import stock
from .bridge import ContractError, read_json
from .receiver import FakeReceiver
from .wire import decode_someip_route


def null_paths(value, prefix="$"):
    if value is None:
        return [prefix]
    if isinstance(value, dict):
        return [path for key, child in value.items() for path in null_paths(child, prefix + "." + key)]
    if isinstance(value, list):
        return [path for i, child in enumerate(value) for path in null_paths(child, f"{prefix}[{i}]")]
    return []


def header_bytes(golden_record):
    h = golden_record["header"]
    return struct.pack("!HHIHHBBBB", h["service"], h["method"], 0, h["client"], h["session"], h["protocol"],
                       h["interface"], h["messageType"], h["returnCode"])


def make_bench(converted, golden_record, *, time_ms, receiver=None, route_type=1):
    payload_text = converted["messageSendJson"]
    if route_type != 1:
        payload_text = stock.message_send(converted["stockPath"], route_type)
    payload = json.loads(payload_text)
    nulls = null_paths(payload)
    if nulls:
        raise ContractError("stock payload must not contain JSON null: " + ", ".join(nulls[:5]))
    try:
        details = stock.chunks(payload_text, 1, time_ms)
    except IndexError as exc:
        raise ContractError("the stock n.a() would throw on this length and send a partial route") from exc
    header = header_bytes(golden_record)
    binders = [stock.route(detail, 1) for detail in details]
    someips = [stock.someip_frame(header, detail, 1) for detail in details]
    for raw, detail in zip(someips, details):
        if decode_someip_route(raw)["wireJson"] != detail:
            raise ContractError("bench SOME/IP framing roundtrip changed the chunk")
    receiver = receiver if receiver is not None else FakeReceiver()
    receiver.offer_binder_route(binders, converted)
    path = payload["data"][0]["pathID"]
    selection = stock.path_selected(path)
    receiver.select_binder(selection)
    receiver.confirm(path, publication_epoch=receiver.publication_epoch)
    return {"payload": payload, "details": details, "binder": binders[0], "binders": binders,
            "someip": someips[0], "someips": someips, "selection": selection, "receiver": receiver,
            "manifest": {"offlineOnly": True, "validAdasRouteEstablished": False, "publicationReady": False,
                         "sourceRouteId": converted["routeId"], "pathID": path, "chunks": len(details),
                         "payloadJavaChars": stock.java_length(payload_text), "nullFields": nulls,
                         "unknownAttributePolicy": converted.get("unknownAttributePolicy"),
                         "binderBytes": [len(b) for b in binders], "someipBytes": [len(s) for s in someips],
                         "selectionSHA256": hashlib.sha256(selection).hexdigest(),
                         "binderSHA256": [hashlib.sha256(b).hexdigest() for b in binders],
                         "someipSHA256": [hashlib.sha256(s).hexdigest() for s in someips],
                         "fakeReceiverTrace": receiver.trace,
                         "limits": ["stock NULL values for unknown attributes are not proved acceptable to ADAS",
                                    "SOME/IP header client/session copied from capture for framing only",
                                    "mock ACK is local bookkeeping, never car evidence"]}}


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
        for i, (binder, someip) in enumerate(zip(result["binders"], result["someips"])):
            (out / f"bench.route-{i:02d}.binder.protobuf.bin").write_bytes(binder)
            (out / f"bench.route-{i:02d}.someip.bin").write_bytes(someip)
        (out / "bench.selection.binder.protobuf.bin").write_bytes(result["selection"])
        (out / "bench.payload.json").write_text(json.dumps(result["payload"], ensure_ascii=False, indent=2) + "\n")
        (out / "bench.manifest.json").write_text(json.dumps(result["manifest"], ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"out": str(out), "chunks": len(result["binders"]), "publicationReady": False}))
    except (ValueError, TypeError, KeyError) as exc:
        parser.exit(2, f"invalid bench input: {exc}\n")


if __name__ == "__main__":
    main()
