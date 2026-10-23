"""Grounded Binder protobuf field encodings for local mock traffic only.

Field numbers/types: SomeipSdMapInformService.sdVehicleLocation,
SomeipNavigationStatusLinkInfoService.TrafficInfo/NavigationStatus_LinkInfoNotifyStruct,
SomeipNaviPathUserSelectStsService.NaviPathUserSelectStsStruct in the retained
BydLaunchermap corpus. No claim about native SOME/IP binary struct serialization.
"""
import hashlib
import math
import struct

from .bridge import ContractError, signed64, transform
from .wire import protobuf_fields, varint

POSITION = {"latitude": (1, "double"), "longitude": (2, "double"),
            "matchedLatitude": (3, "double"), "matchedLongitude": (4, "double"),
            "vehicleSpeed": (5, "int32"), "GPSspeed": (6, "int32"),
            "curSDRouteID": (7, "double"), "course": (8, "int32"),
            "curStepId": (9, "int32"), "curLinkId": (10, "int32"),
            "linkOffset": (11, "int32"), "distanceToNextStep": (12, "int32"),
            "currentRoadOwnership": (13, "int32")}
TRAFFIC = {"SpdLmtSpeedValue": (8, "int32"), "distToDsttn": (17, "int32"),
           "timeToDsttn": (18, "int32"), "roadClass": (19, "int32")}
STATUS = {"navigationStatus": (4, "int32")}
SELECTION = {"pathIDReq": (3, "int64")}


def encode_struct(values, schema):
    inner = b""
    for name, (number, kind) in schema.items():
        value = values.get(name)
        if value is None or value == 0:
            continue  # Proto3 primitive defaults omitted, unknowns separately reported.
        if kind == "double":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise ContractError(f"{name}: invalid double")
            inner += varint((number << 3) | 1) + struct.pack("<d", value)
        else:
            if type(value) is not int:
                raise ContractError(f"{name}: expected integer")
            bits = 32 if kind == "int32" else 64
            if not -(1 << (bits-1)) <= value < (1 << (bits-1)):
                raise ContractError(f"{name}: signed {bits}-bit overflow")
            inner += varint(number << 3) + varint(value & ((1 << 64)-1))
    return b"\x0a" + varint(len(inner)) + inner


def decode_struct(data, schema):
    outer = protobuf_fields(data)
    if set(outer) != {1} or outer[1][0] != 2:
        raise ContractError("expected one nested notification struct")
    fields = protobuf_fields(outer[1][1])
    allowed = {number for number, _ in schema.values()}
    if set(fields)-allowed:
        raise ContractError("unexpected dynamic notification fields")
    result = {}
    for name, (number, kind) in schema.items():
        if number not in fields:
            result[name] = 0.0 if kind == "double" else 0
            continue
        wire_kind, value = fields[number]
        if kind == "double":
            if wire_kind != 1:
                raise ContractError(f"{name}: expected protobuf fixed64 double")
            value = struct.unpack("<d", value)[0]
            if not math.isfinite(value):
                raise ContractError(f"{name}: nonfinite protobuf double")
        else:
            if wire_kind != 0:
                raise ContractError(f"{name}: expected protobuf varint")
            bits = 32 if kind == "int32" else 64
            value &= (1 << bits)-1
            if value >= 1 << (bits-1):
                value -= 1 << bits
        result[name] = value
    return result


def selection_bytes(path_id):
    return encode_struct({"pathIDReq": signed64(path_id)}, SELECTION)


def status_bytes(status):
    if status not in (100, 101, 110):
        raise ContractError("unexpected bench navigation status")
    return encode_struct({"navigationStatus": status}, STATUS)


def integer_maybe(value):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or value < 0:
        raise ContractError("invalid dynamic nonnegative metric")
    return int(value)


def make_dynamic_packets(converted, sample, mapped):
    if not mapped.get("accepted"):
        raise ContractError("cannot encode rejected progress")
    path = mapped["curSDRouteID"]
    if type(path) is not int or not 0 < path <= (1 << 53)-1 or int(float(path)) != path:
        raise ContractError("pathID not exactly representable by position's protobuf DOUBLE")
    location = sample.get("location") or {}
    gps = transform([location["longitude"], location["latitude"]], converted["coordinateContract"]["target"])
    matched = mapped["matchedTarget"]
    values = {"latitude": gps[1], "longitude": gps[0],
              "matchedLatitude": matched[1], "matchedLongitude": matched[0],
              "curSDRouteID": path, "curStepId": mapped["curStepId"],
              "curLinkId": mapped["curLinkId"], "linkOffset": integer_maybe(mapped["linkOffset"]),
              "distanceToNextStep": integer_maybe(mapped["distanceToNextStep"])}
    heading = location.get("heading")
    if heading is not None:
        if isinstance(heading, bool) or not isinstance(heading, (int, float)) or not math.isfinite(heading) or not 0 <= heading <= 360:
            raise ContractError("invalid heading")
        values["course"] = int(heading) % 360
    unknown_position = ["vehicleSpeed", "GPSspeed", "currentRoadOwnership"]
    if heading is None:
        unknown_position.append("course")
    traffic = {"SpdLmtSpeedValue": 0, "roadClass": -1,
               "distToDsttn": integer_maybe(sample.get("distanceToFinishM")),
               "timeToDsttn": integer_maybe(sample.get("timeToFinishS"))}
    packets = []
    for name, service, event, values, schema, unknown in (
            ("sdVehicleLocation", 0x8202, 0x8008, values, POSITION, unknown_position),
            ("TrafficInfo", 7, 0x8003, traffic, TRAFFIC,
             [key for key in ("distToDsttn", "timeToDsttn") if traffic[key] is None] + ["jam", "construction", "cameras", "offCourse"]),
            ("NavigationStatus", 7, 0x8001, {"navigationStatus": 101}, STATUS, [])):
        data = encode_struct(values, schema)
        decoded = decode_struct(data, schema)
        for key, value in values.items():
            if value is not None and decoded[key] != value:
                raise ContractError(f"{name}.{key}: protobuf roundtrip changed value")
        packets.append({"name": name, "service": service, "event": event,
                        "binderProtobufHex": data.hex(), "bytes": len(data),
                        "sha256": hashlib.sha256(data).hexdigest(), "decodedProto3Values": decoded,
                        "missingAttributes": unknown, "incompleteExperimentalProfile": bool(unknown),
                        "publicationReady": False})
    return {"packets": packets, "sourceYandexSpeedMps": location.get("speed"),
            "speedEncodingPolicy": "GPSspeed omitted: native unit/scaling not yet traced; vehicleSpeed unavailable",
            "coordinatePolicy": "GPS and route-matched coordinates transformed separately, once each",
            "unknownPrimitivePolicy": "omitted; proto3 decoder defaults are not evidence of known zero values"}
