#!/usr/bin/env python3
"""Captured stock SOME/IP and Binder-protobuf route formats, HOST ONLY.

These are separate encodings. Binder fireEvent receives protobuf; vsomeip wire
captures include a SOME/IP header and a native fixed-field/string struct.
Roundtrip preserves original JSON bytes, BOM, terminator, CRC/counter/header.
No sockets, ADB, Binder or publisher exist here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct

from .bridge import ContractError, validate_payload

MAX_JSON_BYTES = 32 * 1024 * 1024


def varint(value):
    if type(value) is not int or value < 0:
        raise ContractError("protobuf varint requires unsigned integer")
    output = bytearray()
    while value > 127:
        output.append((value & 127) | 128)
        value >>= 7
    output.append(value)
    return bytes(output)


def decode_varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ContractError("truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ContractError("oversized protobuf varint")


def protobuf_fields(data):
    offset, fields = 0, {}
    while offset < len(data):
        tag, offset = decode_varint(data, offset)
        number, kind = tag >> 3, tag & 7
        if not number or number in fields:
            raise ContractError("unsupported duplicate/zero protobuf field")
        if kind == 0:
            value, offset = decode_varint(data, offset)
        elif kind == 2:
            size, offset = decode_varint(data, offset)
            if size > MAX_JSON_BYTES or offset+size > len(data):
                raise ContractError("invalid protobuf length")
            value = data[offset:offset+size]
            offset += size
        elif kind in (1, 5):
            size = 8 if kind == 1 else 4
            if offset+size > len(data):
                raise ContractError("truncated fixed-width protobuf field")
            value = data[offset:offset+size]
            offset += size
        else:
            raise ContractError(f"unsupported protobuf wire kind {kind}")
        fields[number] = (kind, value)
    return fields


def encode_binder_route(wire_json, *, route_count=1, checksum=0, counter=0):
    """naviSDRouteNotify field1 → struct fields 1 CRC,2 counter,3 count,4 JSON.

    Generated proto3 omits default zero fields. This is not SOME/IP framing.
    """
    data = wire_json.encode("utf-8")
    if len(data) > MAX_JSON_BYTES:
        raise ContractError("route JSON too large")
    inner = b""
    for number, value in ((1, checksum), (2, counter), (3, route_count)):
        if value:
            inner += varint(number << 3) + varint(value)
    inner += b"\x22" + varint(len(data)) + data
    return b"\x0a" + varint(len(inner)) + inner


def decode_binder_route(data):
    outer = protobuf_fields(data)
    if set(outer) != {1} or outer[1][0] != 2:
        raise ContractError("expected naviSDRouteNotify.naviSDRouteStruct field 1")
    inner = protobuf_fields(outer[1][1])
    if 4 not in inner or inner[4][0] != 2:
        raise ContractError("missing route JSON protobuf field 4")
    if any(key not in (1, 2, 3, 4) for key in inner):
        raise ContractError("unexpected route protobuf field")
    for key in (1, 2, 3):
        if key in inner and inner[key][0] != 0:
            raise ContractError("expected integer route protobuf field")
    return {"checksCRC32": inner.get(1, (0, 0))[1],
            "counter": inner.get(2, (0, 0))[1],
            "navigationSDLink2Num": inner.get(3, (0, 0))[1],
            "wireJson": inner[4][1].decode("utf-8")}


def decode_someip_route(data):
    if len(data) < 27:
        raise ContractError("truncated SOME/IP route")
    service, method, length, client, session, protocol, interface, msgtype, returncode = struct.unpack("!HHIHHBBBB", data[:16])
    if length+8 != len(data):
        raise ContractError("SOME/IP header length mismatch")
    if (service, method, protocol, interface, msgtype, returncode) != (43, 0x8002, 1, 1, 2, 0):
        raise ContractError("not the captured stock 43/0x8002 notification format")
    checksum, counter, count, string_size = struct.unpack("!IBHI", data[16:27])
    string_bytes = data[27:]
    if string_size != len(string_bytes) or string_size > MAX_JSON_BYTES:
        raise ContractError("native route struct string length mismatch")
    if not string_bytes.startswith(b"\xef\xbb\xbf") or not string_bytes.endswith(b"\0"):
        raise ContractError("expected stock UTF-8 BOM and NUL terminator")
    json_bytes = string_bytes[3:-1]
    envelope = json.loads(json_bytes.decode("utf-8"))
    if not isinstance(envelope, dict) or not isinstance(envelope.get("payload"), str):
        raise ContractError("invalid NaviDataDetail wrapper")
    # Only complete single chunks may be parsed as an inner MessageSend here.
    payload = json.loads(envelope["payload"]) if envelope.get("length") == 1 else None
    return {"header": {"service": service, "method": method, "client": client,
                       "session": session, "protocol": protocol, "interface": interface,
                       "messageType": msgtype, "returnCode": returncode},
            "checksCRC32": checksum, "counter": counter, "navigationSDLink2Num": count,
            "wireJson": json_bytes.decode("utf-8"), "envelope": envelope, "payload": payload}


def encode_someip_route(record):
    """Rebuild fixed framing from a parsed record, preserving original JSON text."""
    header = record["header"]
    string_bytes = b"\xef\xbb\xbf" + record["wireJson"].encode("utf-8") + b"\0"
    if len(string_bytes) > MAX_JSON_BYTES:
        raise ContractError("route too large")
    body = struct.pack("!IBHI", record["checksCRC32"], record["counter"],
                       record["navigationSDLink2Num"], len(string_bytes)) + string_bytes
    return struct.pack("!HHIHHBBBB", header["service"], header["method"], len(body)+8,
                       header["client"], header["session"], header["protocol"],
                       header["interface"], header["messageType"], header["returnCode"]) + body


def chunk_payload(payload_json, *, time_ms, path_count=1):
    """NaviDataDetail chunks exactly as the stock n.a(): Java String (UTF-16) lengths, the count
    from 524264 and the cut at 524288; one chunk while length + 24 < 524288. A cut past the end
    raises IndexError there, as Java's substring throws (and the stock then sends nothing more).
    """
    from . import stock
    return [json.loads(text) for text in stock.chunks(payload_json, path_count, time_ms)]


def reassemble_chunks(chunks):
    """Strict offline reassembly: no mixing times/counts or silently missing chunks."""
    if not isinstance(chunks, list) or not chunks:
        raise ContractError("missing route chunks")
    first = chunks[0]
    signature = (first.get("time"), first.get("length"), first.get("pathCount"))
    if (any(type(value) is not int for value in signature) or signature[1] < 1 or
            signature[2] < 1 or signature[1] != len(chunks)):
        raise ContractError("invalid route chunk metadata/count")
    for index, chunk in enumerate(chunks):
        if (signature != (chunk.get("time"), chunk.get("length"), chunk.get("pathCount")) or
                type(chunk.get("currentIndex")) is not int or chunk["currentIndex"] != index or
                not isinstance(chunk.get("payload"), str)):
            raise ContractError("route chunks must be ordered, complete and share one generation timestamp")
    # Java cuts at UTF-16 units: a surrogate pair split across chunks joins again here.
    text = "".join(chunk["payload"] for chunk in chunks).encode("utf-16-le", "surrogatepass").decode("utf-16-le")
    if len(text.encode()) > MAX_JSON_BYTES:
        raise ContractError("reassembled JSON too large")
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("someip_bin")
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    try:
        data = Path(args.someip_bin).read_bytes()
        record = decode_someip_route(data)
        rebuilt = encode_someip_route(record)
        binder = encode_binder_route(record["wireJson"], route_count=record["navigationSDLink2Num"], checksum=record["checksCRC32"], counter=record["counter"])
        out = Path(args.out_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "route.rebuilt.someip.bin").write_bytes(rebuilt)
        (out / "route.binder.protobuf.bin").write_bytes(binder)
        (out / "route.payload.json").write_text(json.dumps(record["payload"], ensure_ascii=False, indent=2)+"\n")
        report = {"someipBytes": len(data), "someipSHA256": hashlib.sha256(data).hexdigest(),
                  "roundtripBytesEqual": data == rebuilt,
                  "binderProtobufBytes": len(binder),
                  "binderRoundtripEqual": decode_binder_route(binder)["wireJson"] == record["wireJson"],
                  "payloadErrors": None if record["payload"] is None else validate_payload(record["payload"]),
                  "claim": "captured raw framing and JSON preserved; fresh Gson float formatting and receiver acceptance unproved"}
        (out / "report.json").write_text(json.dumps(report, indent=2)+"\n")
        print(json.dumps(report, indent=2))
    except (ValueError, UnicodeError, KeyError, struct.error) as exc:
        parser.exit(2, f"invalid route bytes: {exc}\n")


if __name__ == "__main__":
    main()
