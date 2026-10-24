"""Dynamic Binder payloads for one accepted guidance sample, through the stock oracle encoders.

sdVehicleLocation, TrafficInfo and NavigationStatus as SomeIPMatrixManager (f0.s, f0.q, f0.e) fill
them, written as protobuf-java lite writes them (tools/adas_bridge/stock.py): uint32 fields carry
the value's 32 bits (-1 is five varint bytes), the stock's unknown ownership is -1 and is sent,
GPSspeed is km/h as Amap's LocInfo.speed, every distance comes from the one link table. Local mock
traffic only; no transport.
"""
import hashlib
import math

from . import stock
from .bridge import ContractError

POSITION = stock.SD_VEHICLE_LOCATION
TRAFFIC = stock.TRAFFIC_INFO
STATUS = stock.NAVIGATION_STATUS
SELECTION = stock.PATH_SELECT
_NOTIFY = {id(POSITION): stock.SD_VEHICLE_LOCATION_NOTIFY, id(TRAFFIC): stock.TRAFFIC_INFO_NOTIFY,
           id(STATUS): stock.NAVIGATION_STATUS_NOTIFY, id(SELECTION): stock.PATH_SELECT_NOTIFY}
_DEFAULTS = {stock.DOUBLE: 0.0, stock.FLOAT: 0.0, stock.INT32: 0, stock.UINT32: 0, stock.UINT64: 0,
             stock.BOOL: False, stock.STRING: ""}


def encode_struct(values, schema):
    """The notify message of an inner struct from a {fieldName: value} dict (missing: unset)."""
    inner = schema.encode(*[values.get(name) for _, _, name, _ in schema.fields])
    return _NOTIFY[id(schema)].encode(inner)


def decode_struct(data, schema):
    """Inner struct of a notify payload; absent fields at their proto3 default; uint32 unsigned."""
    try:
        outer = _NOTIFY[id(schema)].decode(data)
    except stock.StockError as exc:
        raise ContractError(str(exc)) from exc
    if len(outer) != 1:
        raise ContractError("expected one nested notification struct")
    inner = next(iter(outer.values()))
    return {name: inner.get(name, _DEFAULTS.get(kind)) for _, kind, name, _ in schema.fields if kind in _DEFAULTS}


def as_int32(value):
    """A decoded uint32 read back as the Java int the sender passed."""
    return stock._int32(value)


def selection_bytes(path_id):
    return stock.path_selected(path_id)


def status_bytes(status):
    if status not in (100, 101, 102, 104, 105, 106, 110):
        raise ContractError("unexpected navigation status")
    return stock.navigation_status(status)


def make_dynamic_packets(converted, sample, mapped, *, road_speed_limit=False):
    """f0.s / f0.q / f0.e for an accepted sample: the stock's own values where Yandex has none.

    TrafficInfo.SpdLmtSpeedValue follows StockFeed.Config.roadSpeedLimit: 0 by default (the stock
    reports no limit on our roads), Yandex's posted limit only with road_speed_limit=True."""
    if not mapped.get("accepted"):
        raise ContractError("cannot encode rejected progress")
    path = mapped["curSDRouteID"]
    if type(path) is not int or not 0 < path <= (1 << 53) - 1:
        raise ContractError("pathID not exactly representable by position's protobuf DOUBLE")
    location = sample.get("location") or {}
    frame = converted["coordinateContract"]["target"]
    # As StockFeed.location(): the road slots are Yandex's (route-matched) classified location, the
    # GPS slots the raw platform fix when the sample carries one, else the same location (F6).
    raw = sample.get("rawLocation") if isinstance(sample.get("rawLocation"), dict) else location
    try:
        road = stock.to_sdk(location["longitude"], location["latitude"], frame)
        gps = stock.to_sdk(raw["longitude"], raw["latitude"], frame)
    except stock.StockError as exc:
        raise ContractError(str(exc)) from exc
    matched = (stock.grid(road[0]), stock.grid(road[1]))
    speed = location.get("speed")
    gps_speed = int(speed * 3.6) if isinstance(speed, (int, float)) and not isinstance(speed, bool) and math.isfinite(speed) else 0
    heading = location.get("heading")
    course = int(heading) if isinstance(heading, (int, float)) and not isinstance(heading, bool) and math.isfinite(heading) else 0
    position = stock.vehicle_location(stock.grid(gps[0]), stock.grid(gps[1]), matched[0], matched[1],
                                      gps_speed, gps_speed, course, path, mapped["curStepId"], mapped["curLinkId"],
                                      mapped["distanceToNextStep"], mapped["linkOffset"], stock.OWNERSHIP_UNKNOWN)
    limit = sample.get("speedLimitMps")
    road_speed = stock.half_up(limit * 3.6) if (road_speed_limit and isinstance(limit, (int, float))
                                                 and not isinstance(limit, bool) and limit > 0) else 0
    time = sample.get("timeToFinishS")
    time_to = max(0, stock.half_up(time)) if isinstance(time, (int, float)) and not isinstance(time, bool) else 0
    traffic = stock.traffic_info(0, -1, 0, 0, 0, 0.0, 0.0, road_speed, 0, 0, 0.0, 0.0, 0.0, 0.0, 0, False,
                                 mapped["distanceToDestinationM"], time_to, stock.ROAD_CLASS_NULL)
    status = stock.navigation_status(101)
    packets = []
    for name, service, event, data, schema in (("sdVehicleLocation", 0x8202, 0x8008, position, POSITION),
                                               ("TrafficInfo", 7, 0x8003, traffic, TRAFFIC),
                                               ("NavigationStatus", 7, 0x8001, status, STATUS)):
        packets.append({"name": name, "service": service, "event": event, "binderProtobufHex": data.hex(),
                        "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                        "decodedProto3Values": decode_struct(data, schema), "publicationReady": False})
    return {"packets": packets, "sourceYandexSpeedMps": speed,
            "substitutions": {"vehicleSpeed": "GNSS speed (km/h); the car's own speed is not available to Navigator",
                              "gpsSlot": "raw platform fix" if raw is not location else "Yandex classified location; raw GNSS not in the sample",
                              "currentRoadOwnership": "-1, the stock's unknown", "roadClass": "-1, RoadClassNULL"},
            "distancePolicy": "linkOffset, distanceToNextStep and distToDsttn from the one published link table"}
