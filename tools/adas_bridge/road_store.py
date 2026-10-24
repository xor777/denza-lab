#!/usr/bin/env python3
"""The bridge's road attribute store: our OSM network as the stock map would export it, HOST ONLY.

Built from a `build_map.py` output directory (stages tiles + the routing chain through signals),
never by re-deriving a rule: every attribute is the value the producers delivered to the stock
map's m2 (decoded with the field-coverage gate's own readers, `audit_field_coverage.routing_tiles`
and `decode_tile`), mapped the way the stock map exports it to ADAS:

    roadClass   DRT slot 0                    (build_routing_tiles 9 + enrich_noa_road_classes)
    formway     DRT formway                   (enrich_noa_road_classes)
    linkType    DRT link_type                 (enrich_noa_road_classes: 3 bridge, 2 tunnel, else 0)
    laneNum     ERAT LaneNum                  (build_routing_tiles / lane enrichment)
    direction   DRT direction raw != 3 -> 1   (DriveOfflinePathAdapter 0x22fa660, getRoadDirection)
    ownership   DRT ownership 1..4 -> 1       (the NOA export)
    speed       ERAT 0x4269 byte, km/h        (what the stock sends as historySpeed)
    legal       km/h per direction of the fragment: the factory speed-limit sign pair (ERAT 0x426f,
                types 10 and 54) the factory stage writes; 0 where it writes none
    name        the way's OSM name from the network stage ("" when none; the factory sends "")

Geometry is each staging fragment (one DRT road: an OSM edge cut at Z14 tiles) in the stock map's
own frame (`native-gcj-unrestricted-candidate`, the frame `build_routing_tiles` encodes and the
bridge publishes) on the stock Coord2DInt32 grid (1/3 600 000 degree). Nodes are the fragments'
end keys (OSM node or tile-boundary point); a node's degree is the number of fragment ends there.

File (big-endian, read by `libraries/adas-link` RoadStore and `tools/adas_bridge/roads.py`):

    header   'DZRN', u16 version 1, u16 frame (1 = native GCJ candidate), i32 originX, originY,
             f64 metres per unit X, Y, i32 cellX, cellY (units), i32 cols, rows,
             i32 nodes, fragments, names, i32 offsets of: nodes, adjacency, fragments, geometry,
             cells, cell items, names, i32 file length
    nodes    i32 x, i32 y (units)
    adjacency i32 start[nodes + 1], then i32 (fragment << 1 | end) per fragment end (end 1 = its last point)
    fragments 25 bytes: i32 from, i32 to, i32 geometry offset, u16 points, u8 roadClass, formway,
             linkType, laneNum, flags (bit0 forward allowed, bit1 backward allowed, bit2 direction
             export, bit3 ownership export), speed, legal forward, legal backward, u24 name index
             (0xFFFFFF none)
    geometry the points after the first (= from node), zigzag varint deltas, x then y
    cells    i32 start[cols * rows + 1], items i32 fragment index (ascending per cell)
    names    i32 start[names + 1], UTF-8 bytes

    python3 -m tools.adas_bridge.road_store build --package BUILD_DIR --out roads.bin [--report r.json]
    python3 -m tools.adas_bridge.road_store info roads.bin

No coordinates leave captures/: the report holds counts and sizes only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]


def _stock_map_tools():
    """The factory readers and the field-coverage gate, from the amap-injector checkout."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import amap_injector
    amap_injector.importable('tools/stock-map', 'research/stock-map-native')

MAGIC = b'DZRN'
VERSION = 1
FRAME_CODES = {'wgs84': 0, 'native-gcj-unrestricted-candidate': 1}
SCALE = 3600000
HEADER = struct.Struct('>4sHHiiddiiiiiii7ii')
FRAGMENT = struct.Struct('>iiiHBBBBBBBB3s')
CELL_M = 200.0
NO_NAME = 0xFFFFFF


def zigzag(v):
    return (v << 1) ^ (v >> 63)


def varint(out, v):
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


def metres_per_unit(lat):
    """Planar metres per grid unit at the store's middle latitude (equirectangular, R = 6 371 008.8 m)."""
    k = 6371008.8 * math.pi / 180.0 / SCALE
    return k * math.cos(math.radians(lat)), k


def exported(road, legal=(0, 0)):
    """The decoded m2 record as the stock map exports it to ADAS (see the module docstring);
    legal: (forward, backward) km/h of its speed-limit signs."""
    raw = road['direction']
    forward, backward = {1: (1, 0), 2: (0, 1)}.get(raw, (1, 1))
    flags = forward | backward << 1 | (0 if raw == 3 else 1) << 2 | (0 if road['ownership'] == 0 else 1) << 3
    return dict(roadClass=road['road_class'], formway=road['formway'], linkType=road['link_type'],
                laneNum=road['lanes'], flags=flags, speed=road['speed'], legalForward=legal[0], legalBackward=legal[1])


def speed_limit_signs(erat):
    """{(road, forward 1 / backward 0): km/h} of a tile's factory speed-limit sign pairs (ERAT 0x426f)."""
    _stock_map_tools()
    import factory_map_readers as F
    out = {}
    for g in F.decode_erat_full(erat)['generic']:
        if g['tags'] != (0x426f,) or 'objects' not in g:
            continue
        for (direction, road), (records,) in g['objects']:
            values = {r['value'] for r in records if r['type'] in F.SPEED_LIMIT_SIGN_TYPES}
            if len(values) == 1:
                out[road, direction] = values.pop()
    return out


def read_package(package, tiles_db=None):
    """The fragments of a build_map.py output: [(from key, to key, points (degrees, store frame), exported, name)].

    tiles_db: the routing tiles to read; default the field-coverage gate's choice (the signals stage, the
    records the routing stage packs into m2).
    """
    _stock_map_tools()
    from audit_field_coverage import routing_tiles, decode_tile
    package = Path(package)
    tiles, tiles_source = routing_tiles(package, tiles_db)
    records, limits = {}, {}
    for tile, (erat, drt) in tiles.items():
        roads, _, _ = decode_tile(erat, drt)
        for local, road in enumerate(roads):
            records[(tile, local)] = road
        for (local, direction), kmh in speed_limit_signs(erat).items():
            limits[tile, local, direction] = kmh
    staging = sqlite3.connect(f'file:{package / "stages/tiles/tiles/routing-staging.sqlite"}?mode=ro', uri=True)
    network = sqlite3.connect(f'file:{package / "stages/network/network/network.sqlite"}?mode=ro', uri=True)
    names = {}
    for way, tags in network.execute('SELECT osm_way_id,tags_json FROM ways'):
        name = json.loads(tags).get('name')
        if name:
            names[way] = name
    out, missing = [], 0
    for tile, local, way, from_key, to_key, points_json in staging.execute(
            'SELECT tile,local_id,way_id,from_key,to_key,points_json FROM fragments ORDER BY fragment_id'):
        road = records.get((tile, local))
        if road is None:
            missing += 1
            continue
        points = [(p[0], p[1]) for p in json.loads(points_json)]
        legal = (limits.get((tile, local, 1), 0), limits.get((tile, local, 0), 0))
        out.append((from_key, to_key, points, exported(road, legal), names.get(way)))
    staging.close()
    network.close()
    return out, dict(tilesSource=str(tiles_source), fragmentsWithoutRecord=missing)


def _reversed(fragment):
    from_key, to_key, points, e, name = fragment
    flags = e['flags']
    e = dict(e, flags=(flags & ~3) | (flags & 1) << 1 | (flags & 2) >> 1,
             legalForward=e['legalBackward'], legalBackward=e['legalForward'])
    return to_key, from_key, list(reversed(points)), e, name


def merge_continuations(fragments):
    """Join fragments that meet at a node only each other (an OSM node where one way continues
    into the next, a Z14 tile border) when everything the store keeps of them is equal: the stock
    map's link breaks at a junction or at a change of attributes, and so does the bridge, so such a
    node carries nothing. Fragment order: the first of each chain's place; deterministic."""
    alive = {i: f for i, f in enumerate(fragments)}
    ends = {}
    for i, f in alive.items():
        ends.setdefault(f[0], []).append(i)
        ends.setdefault(f[1], []).append(i)
    merged = 0
    for node in list(ends):
        refs = ends.get(node)
        if refs is None or len(refs) != 2 or refs[0] == refs[1]:
            continue
        i, j = sorted(refs)
        a, b = alive[i], alive[j]
        if a[1] != node:
            a = _reversed(a)
        if b[0] != node:
            b = _reversed(b)
        if a[1] != node or b[0] != node or a[0] == node or b[1] == node:
            continue
        if a[3] != b[3] or a[4] != b[4] or len(a[2]) + len(b[2]) - 1 > 0xFFFF:
            continue
        joined = (a[0], b[1], a[2] + b[2][1:], a[3], a[4])
        alive[i] = joined
        del alive[j]
        del ends[node]
        far = ends[b[1]]
        far[far.index(j)] = i
        merged += 1
    return [alive[i] for i in sorted(alive)], merged


def encode(fragments, frame='native-gcj-unrestricted-candidate'):
    """The store bytes of [(from key, to key, points in degrees, exported attributes, name or None)]."""
    node_index, node_xy = {}, []
    records, geometry = [], bytearray()
    name_index, name_list = {}, []

    def node(key, xy):
        if key not in node_index:
            node_index[key] = len(node_xy)
            node_xy.append(xy)
        elif node_xy[node_index[key]] != xy:
            raise ValueError(f'node {key} has two positions')
        return node_index[key]

    for from_key, to_key, points, e, name in fragments:
        if len(points) < 2 or len(points) > 0xFFFF:
            raise ValueError('fragment point count out of range')
        grid = [(round(p[0] * SCALE), round(p[1] * SCALE)) for p in points]
        a, b = node(from_key, grid[0]), node(to_key, grid[-1])
        offset = len(geometry)
        for (px, py), (x, y) in zip(grid, grid[1:]):
            varint(geometry, zigzag(x - px))
            varint(geometry, zigzag(y - py))
        if name is None:
            ni = NO_NAME
        else:
            if name not in name_index:
                name_index[name] = len(name_list)
                name_list.append(name)
            ni = name_index[name]
        records.append((a, b, offset, len(points), e, ni, grid))
    if not records:
        raise ValueError('no fragments')

    xs = [x for f in records for x, _ in f[6]]
    ys = [y for f in records for _, y in f[6]]
    origin_x, origin_y = min(xs), min(ys)
    mx, my = metres_per_unit((min(ys) + max(ys)) / 2 / SCALE)
    cell_x, cell_y = max(1, round(CELL_M / mx)), max(1, round(CELL_M / my))
    cols = (max(xs) - origin_x) // cell_x + 1
    rows = (max(ys) - origin_y) // cell_y + 1
    cells = [[] for _ in range(cols * rows)]
    for index, f in enumerate(records):
        touched = set()
        for (x1, y1), (x2, y2) in zip(f[6], f[6][1:]):
            for cx in range((min(x1, x2) - origin_x) // cell_x, (max(x1, x2) - origin_x) // cell_x + 1):
                for cy in range((min(y1, y2) - origin_y) // cell_y, (max(y1, y2) - origin_y) // cell_y + 1):
                    touched.add(cy * cols + cx)
        for c in sorted(touched):
            cells[c].append(index)

    adjacency = [[] for _ in node_xy]
    for index, f in enumerate(records):
        adjacency[f[0]].append(index << 1)
        adjacency[f[1]].append(index << 1 | 1)

    body = bytearray()
    offsets = {}

    def section(name, data):
        while len(body) % 4:
            body.append(0)
        offsets[name] = HEADER.size + len(body)
        body.extend(data)

    section('nodes', b''.join(struct.pack('>ii', x, y) for x, y in node_xy))
    starts, flat = [0], []
    for refs in adjacency:
        flat.extend(refs)
        starts.append(len(flat))
    section('adjacency', struct.pack('>%di' % len(starts), *starts) + struct.pack('>%di' % len(flat), *flat))
    section('fragments', b''.join(FRAGMENT.pack(a, b, offset, count, e['roadClass'], e['formway'], e['linkType'],
                                                e['laneNum'], e['flags'], e['speed'], e['legalForward'], e['legalBackward'],
                                                ni.to_bytes(3, 'big'))
                                  for a, b, offset, count, e, ni, _ in records))
    section('geometry', bytes(geometry))
    starts, flat = [0], []
    for items in cells:
        flat.extend(items)
        starts.append(len(flat))
    section('cells', struct.pack('>%di' % len(starts), *starts))
    section('items', struct.pack('>%di' % len(flat), *flat))
    encoded = [n.encode('utf-8') for n in name_list]
    starts = [0]
    for n in encoded:
        starts.append(starts[-1] + len(n))
    section('names', struct.pack('>%di' % len(starts), *starts) + b''.join(encoded))
    total = HEADER.size + len(body)
    header = HEADER.pack(MAGIC, VERSION, FRAME_CODES[frame], origin_x, origin_y, mx, my,
                         cell_x, cell_y, cols, rows, len(node_xy), len(records), len(name_list),
                         *(offsets[k] for k in ('nodes', 'adjacency', 'fragments', 'geometry', 'cells', 'items', 'names')),
                         total)
    sizes = dict(header=HEADER.size, nodes=8 * len(node_xy), adjacency=4 * (len(node_xy) + 1 + 2 * len(records)),
                 fragments=FRAGMENT.size * len(records), geometry=len(geometry), cells=4 * (cols * rows + 1),
                 items=4 * sum(len(c) for c in cells), names=4 * (len(name_list) + 1) + sum(map(len, encoded)))
    summary = dict(nodes=len(node_xy), fragments=len(records), names=len(name_list), cells=cols * rows,
                   cellMetres=CELL_M, sectionBytes=sizes, junctionNodes=sum(len(a) >= 3 for a in adjacency),
                   points=sum(f[3] for f in records),
                   fragmentsWithLegalLimit=sum(bool(f[4]['legalForward'] or f[4]['legalBackward']) for f in records))
    return header + bytes(body), summary


def build(package, out, report=None, tiles_db=None):
    fragments, source = read_package(package, tiles_db)
    source['packageFragments'] = len(fragments)
    fragments, source['mergedContinuations'] = merge_continuations(fragments)
    data, summary = encode(fragments)
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_bytes(data)
    summary = dict(schema='adas-bridge-road-store-report-v1', package=str(package), **source,
                   bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), **summary)
    if report:
        Path(report).write_text(json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('build')
    p.add_argument('--package', type=Path, required=True, help='a build_map.py --out directory')
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--report', type=Path)
    p.add_argument('--tiles', type=Path, help='routing tiles.sqlite of an earlier stage (default: the signals stage)')
    p = sub.add_parser('info')
    p.add_argument('store', type=Path)
    args = parser.parse_args()
    if args.command == 'build':
        print(json.dumps(build(args.package, args.out, args.report, args.tiles), ensure_ascii=False, indent=1))
    else:
        from .roads import RoadStore
        store = RoadStore(args.store.read_bytes())
        print(json.dumps(dict(nodes=store.node_count, fragments=store.fragment_count, names=len(store.names),
                              cells=store.cols * store.rows, bytes=args.store.stat().st_size)))


if __name__ == '__main__':
    main()
