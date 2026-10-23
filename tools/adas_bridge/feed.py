"""Map->ADAS feed messages: topic table, schema-driven protobuf decoding, one trace format.

A *feed trace* is the common currency of the ADAS bench (stock and bridge alike):

    {"schema": "denza-adas-feed-trace-v1", "side": "stock"|"bridge", "label": ..., "provenance": {...},
     "events": [{"t_ms": float, "kind": "fire"|"client_send"|"answer"|"context",
                 "topic": int, "service": int, "event": int, "name": str,
                 "fields": {...decoded...}, "bytes": int, "thread": str|None, ...}]}

`t_ms` is monotonic milliseconds from the first event of the trace. Route events
(43/0x8002) carry `route`: the reassembled NaviToEHPData list once the last chunk
arrived. Decoding uses someip_schema.json (generated from the stock classes by
schema_gen.py); unknown fields are kept by number, never dropped.
"""
from __future__ import annotations

import base64
import json
import math
from pathlib import Path
import struct

SCHEMA = json.loads(Path(__file__).with_name('someip_schema.json').read_text())['messages']

# topic -> (short name, top-level notify message). Senders: k.h.a0.c.c.a.f0, k.h.e0.c.j.e0.
TOPICS = {
    1125929972105217: ('NavigationStatus', 'NavigationStatus_LinkInfoNotify'),
    1125929972105218: ('ADASISv2', 'ADASISV2Notify'),
    1125929972105219: ('TrafficInfo', 'TrafficInfoNotify'),
    1125929972105220: ('AdvAutoFunInfo', 'AdvAutoFunInfoNotify'),
    1125929972105221: ('NavigationDataInfo', 'NavigationDataInfoNotifiy'),
    1125929972105222: ('GeneralNavigation', 'genernalNavigationNotify'),
    1126084593287169: ('HDLink2', 'NavigationHDLink2Notify'),
    1126084593287170: ('Route', 'naviSDRouteNotify'),
    1126093183352833: ('PathSelected', 'NaviPathUserSelectStsNotify'),
    1268847189590017: ('TrafficLight', 'sdTrafficLightNotify'),
    1268847189590018: ('TrafficIncident', 'sdTraffiIncidentNotify'),
    1268847189590019: ('ServiceAreaToll', 'serviceAreaAndTollStationNotify'),
    1268847189590020: ('Tunnel', 'tunnelNotify'),
    1268847189590021: ('RoadFacilities', 'roadFacilitiesNotify'),
    1268847189590022: ('Intersection', 'intersectionNotify'),
    1268847189590023: ('TidalLane', 'tidalLaneNotify'),
    1268847189590024: ('VehicleLocation', 'sdVehicleLocationNotify'),
    1268847189590025: ('Region', 'regionalAndWeatherNotify'),
    1268847189590026: ('SpeedIntersection', 'speedIntersectionInformationNotify'),
    1268847189590027: ('NextAction', 'naviActionAndCameraNotify'),
    1268847189590028: ('NextIntersectionLanes', 'nextIntersectionLanesInfoNotify'),
    1268847189590029: ('MixFork', 'mixForkInfoListNotify'),
    1268847189590030: ('Lanes3km', 'aheadIntersectionsLanesInfoNotify'),
    # client side (IVI -> ADAS requests) and ADAS's answers
    563005788848129: ('DnpSwitchRequest', 'SwitchNotifyReq'),
    563005788848130: ('OddRegionRequest', 'ODDRegionCodeReq'),
    1126088888320001: ('PathMatch', 'NavigationPathMatchStatusNotify'),
    1126088888320002: ('MMTPathSelected', 'NaviPathUserSelectStsConfirmNotify'),
}
NAMES = {name: topic for topic, (name, _) in TOPICS.items()}


def topic_parts(topic):
    return {'service': (topic >> 32) & 0xFFFF, 'event': topic & 0xFFFF}


def _varint(data, offset):
    value = shift = 0
    while True:
        if offset >= len(data):
            raise ValueError('truncated varint')
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7
        if shift > 70:
            raise ValueError('oversized varint')


def _signed(value, bits):
    value &= (1 << 64) - 1
    if value >= 1 << 63:
        value -= 1 << 64
    if bits == 32:
        value = ((value + (1 << 31)) % (1 << 32)) - (1 << 31)
    return value


def _scalar(kind, wire, raw):
    if kind in ('int32', 'int64'):
        if wire != 0:
            raise ValueError(f'{kind}: wire {wire}')
        return _signed(raw, 32 if kind == 'int32' else 64)
    if kind == 'bool':
        return bool(raw)
    if kind == 'double':
        return struct.unpack('<d', raw)[0]
    if kind == 'float':
        return struct.unpack('<f', raw)[0]
    if kind == 'string':
        return raw.decode('utf-8')
    if kind == 'bytes':
        return list(raw)
    raise ValueError(kind)


def decode(message, data):
    """Schema-driven decode. Absent proto3 scalars are absent (not defaulted)."""
    fields = SCHEMA.get(message, {})
    out, offset = {}, 0
    while offset < len(data):
        tag, offset = _varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if wire == 0:
            raw, offset = _varint(data, offset)
        elif wire == 1:
            raw, offset = data[offset:offset + 8], offset + 8
        elif wire == 5:
            raw, offset = data[offset:offset + 4], offset + 4
        elif wire == 2:
            size, offset = _varint(data, offset)
            raw, offset = data[offset:offset + size], offset + size
        else:
            raise ValueError(f'{message}: wire type {wire}')
        name, kind = fields.get(str(number), ('#' + str(number), None))
        if kind is None:
            out[name] = raw.hex() if isinstance(raw, (bytes, bytearray)) else raw
            continue
        repeated = kind.startswith('repeated ')
        base = kind[9:] if repeated else kind
        if base.startswith('message:'):
            value = decode(base[8:], raw)
        elif repeated and wire == 2 and base not in ('string', 'bytes'):
            value, inner = [], 0
            while inner < len(raw):
                if base in ('double', 'float'):
                    width = 8 if base == 'double' else 4
                    value.append(_scalar(base, 1, raw[inner:inner + width]))
                    inner += width
                else:
                    item, inner = _varint(raw, inner)
                    value.append(_scalar(base, 0, item))
            out.setdefault(name, []).extend(value)
            continue
        else:
            value = _scalar(base, wire, raw)
        if repeated:
            out.setdefault(name, []).append(value)
        else:
            out[name] = value
    return out


def flatten(message, data):
    """Decode a notify wrapper and return the inner struct (field 1) when it is one."""
    top = decode(message, data)
    inner = [v for v in top.values() if isinstance(v, dict)]
    if len(top) == 1 and len(inner) == 1:
        return inner[0]
    return top


class RouteAssembler:
    """43/0x8002 chunks -> NaviToEHPData list (stock n.java chunking: `length` chunks)."""

    def __init__(self):
        self.pending = {}

    def add(self, struct_fields):
        text = struct_fields.get('navigationSDLink2')
        if text is None:
            return None, 'missing navigationSDLink2'
        envelope = json.loads(text.lstrip('﻿'))
        key = (envelope.get('time'), envelope.get('length'), envelope.get('pathCount'))
        chunks = self.pending.setdefault(key, {})
        chunks[envelope.get('currentIndex')] = envelope.get('payload') or ''
        meta = {'time': envelope.get('time'), 'chunk': envelope.get('currentIndex'), 'length': envelope.get('length'),
                'pathCount': envelope.get('pathCount')}
        if len(chunks) < (envelope.get('length') or 1):
            return None, meta
        payload = json.loads(''.join(chunks[i] for i in sorted(chunks)))
        del self.pending[key]
        return payload, meta


def decode_event(topic, payload_bytes, assembler=None):
    name, message = TOPICS.get(topic, ('topic-' + str(topic), None))
    record = dict(topic=topic, name=name, **topic_parts(topic), bytes=len(payload_bytes))
    if message is None:
        record['fields'] = {'raw': payload_bytes.hex()}
        return record
    try:
        record['fields'] = flatten(message, payload_bytes)
    except (ValueError, struct.error, UnicodeDecodeError) as error:
        record['fields'] = {'raw': payload_bytes.hex()}
        record['decodeError'] = str(error)
        return record
    if name == 'Route' and assembler is not None:
        route, meta = assembler.add(record['fields'])
        record['chunk'] = meta
        text = record['fields'].pop('navigationSDLink2', None)
        record['jsonBytes'] = None if text is None else len(text.encode())
        if route is not None:
            record['route'] = route
    for key in ('aheadIntersectionsLanesInfo', 'mixForkInfoLins'):
        if isinstance(record['fields'].get(key), str):
            try:
                record['fields'][key] = json.loads(record['fields'][key])
            except ValueError:
                pass
    return record


def b64(text):
    return base64.b64decode(text) if text else b''


def finite_or_none(value):
    return value if isinstance(value, (int, float)) and math.isfinite(value) else None


def write_trace(path, side, label, events, provenance, *, rebase=True):
    events = sorted(events, key=lambda e: (e['t_ms'], e.get('order', 0)))
    if events and rebase:
        origin = events[0]['t_ms']
        for event in events:
            event['t_ms'] = round(event['t_ms'] - origin, 3)
    trace = {'schema': 'denza-adas-feed-trace-v1', 'side': side, 'label': label,
             'provenance': provenance, 'events': events}
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(trace, ensure_ascii=False) + '\n')
    return trace


def read_trace(path):
    trace = json.loads(Path(path).read_text())
    if trace.get('schema') != 'denza-adas-feed-trace-v1':
        raise ValueError('not a feed trace: ' + str(path))
    return trace
