# Yandex → ADAS host oracle and offline prototype

Host Python 3 standard library only. Conversion/replay is offline; `watch.py`
adds bounded, read-only ADB access to an explicitly guarded emulator. There are
no Binder calls, vehicle setters or publisher. `publicationReady` is always
`false`. The device side is [`libraries/adas-link`](../../libraries/adas-link),
compiled into Navigator by `tools/yandex_hud_build`; the durable research owner
is [`research/adas-bridge/README.md`](../../research/adas-bridge/README.md).

## The oracle: `stock.py`

An independent Python implementation of the Java library, checked byte for byte
against it by `tests/test_stock_equivalence.py` (which compiles the Java with the
local JDK, or skips when there is none):

* `route_from_snapshot` + `convert` — Yandex `denza-yandex-driving-route-v1` →
  the Amap-style `PathInfo` the stock serializer reads: one segment per Yandex
  section, links cut at every known traffic light (the light ends its link),
  every per-edge legal-limit change and every tunnel/ferry boundary, **never at
  jams**; cuts closer than 1 m merge (a light keeps its place); whole-metre link
  lengths whose sum is the segment's (largest remainder, half-up); points on the
  stock `Coord2DInt32` grid (1/3 600 000°); link IDs are SHA-256 of the link's own
  grid points, so a piece of road keeps its ID across routes; attributes Yandex
  does not give carry the stock's own NULL values (roadclass/formway/linktype −1,
  ownership −1, actions 0, laneNum 0, booleans false), never JSON null.
* `PathIds` — Amap-style per-process path IDs (100002, 100003, …); the same link
  table keeps its ID, a different table gets a new one.
* `message_send` / `chunks` — `k.g()` / `l.*` / `n.a()`: the MessageSend JSON as
  BydLaunchermap's Gson writes it (key order, `Double.toString`, `Float.toString`
  for `len`, the description text in the EN or default locale), NaviDataDetail
  chunks in Java UTF-16 units with the stock's 524264/524288 arithmetic.
* The protobuf messages of every stock sender (`f0.s q e h r k l p f o c i`,
  `e0.k l`, `f.h`), written as protobuf-java lite writes them (uint32 is the
  value's 32 bits, a set sub-message always goes out), and their decoders.
* `path_from_payload`, `someip_frame` — the car's captured stock route is
  reproduced from the PathInfo it describes, JSON and native SOME/IP bytes.

`java_double` refuses |x| ≥ 2⁵³ and subnormals, where JDK's `FloatingDecimal` is
not shortest-digit; no stock payload double lives there.

```sh
python3 -m tools.adas_bridge.stock convert route.json --out payload.json
python3 -m unittest discover -s tools/adas_bridge/tests -v
```

## The prototype, fixed in step

* `bridge.py convert` validates the snapshot and delegates to the oracle; the
  candidate is the stock NaviToEHPData (no null), plus an intermediate placement
  table. `progress` places a sample on the published link table: integer link
  offset and segment/route remainders from that one table (TrafficInfo
  `distToDsttn` too), FINE-only freshness when the sample carries
  `locationClass`, fix age from `lastFineFixAgeMs` when present, at most 1 s.
  The location/route consistency check compares two Yandex outputs (the
  classified location is already route-snapped) and is not an independence
  guard.
* `dynamic.py` encodes position/TrafficInfo/status through the oracle: GPS speed
  km/h, ownership −1 sent explicitly, roadClass −1, uint32 encoding; vehicle
  speed is the GNSS speed (labelled substitution).
* `receiver.py`, `watch.py`, `replay.py` follow the stock lifecycle: positions
  flow before the ACK; a stale, duplicate or out-of-order sample or a reroute
  holds the last position and keeps status 101 (no end, no resend); progress
  never moves back on one path; 110 for 3 s only after an arrival; a route
  re-read with the same table keeps its path and is not resent, a changed table is
  a new path; a finished route is retired; replay rejections continue.
* `bench.py` publishes the oracle's payload in stock chunks; the golden only lends
  its SOME/IP header.

## Dry-run traces: `trace_check.py`

```sh
python3 -m tools.adas_bridge.trace_check trace.jsonl --interleave \
  --stock-log captures/adas-bridge-review/dynamics/stock-drive01-positions.json --out report.json
```

Reads a `denza-adas-dry-run-v1` JSONL trace from the module's DryRunRecorder,
re-decodes every fire with the oracle and compares with the recorded `decoded`,
and reports per-topic counts, NavigationStatus/position cadence, status runs,
path changes, route publications, any 110 without a preceding `naviArrive`, and
progress moving back on a path. `--interleave` counts the seconds in which the
idle stock map's 1 Hz NavigationStatus 100 / pathId −1 stream (phase from the
stock log or `--stock-phase-ms`) would reach ADAS together with ours (DYN-05).
`--out` writes one row per fire (time, service, event, oracle-decoded values),
the form a stock-versus-bridge comparator reads.

## Emulator watcher

The watcher accepts only `emulator-N` serials and checks `ro.kernel.qemu=1`
before any app-file access. Duration is bounded to 1–300 seconds and every ADB
command has a deadline. It reads only the two atomic evidence files and device
uptime, with `run-as` or an already-root emulator's `head`; it never invokes
`adb root`, `su`, installs, activities or services.

```sh
python3 -m tools.adas_bridge.watch \
  --serial emulator-5592 --duration-s 60 \
  --golden-someip captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.someip.bin \
  --target-frame native-gcj-unrestricted-candidate \
  --out-dir captures/yandex-adas-prototype-20261023/converted-01/live-watch
```

The `wire` tool separates captured SOME/IP (16-byte header, native big-endian
CRC32/counter/uint16 count/uint32 length, BOM + JSON + NUL) from the Binder
protobuf input (`naviSDRouteNotify`); `status.py` decodes ADAS status from saved
or live read-only logcat.

## Differential bench (stock map ↔ bridge)

The acceptance bench records what the original map fires toward ADAS and what
the bridge would fire for the same route and position trace, in one trace format
(`feed.py`, decoded with `someip_schema.json`, regenerated from the stock classes
by `schema_gen.py`), and compares them (`compare.py`). Outputs go under
`captures/adas-bench/` (git-ignored; Beijing and car coordinates never leave it).

* `stock_feed_observer.js` + `stock_trace.py`: the original app on an owned
  emulator, a recorded drive (`build_drive_replay_case.py`) or a synthetic trace
  along a factory route, the car's guidance order (Navi_onCreate → startNavi →
  route data update; reroute data before setNaviPath; arrival → stopNaviOnArrive →
  stopNavi → Navi_onDestroy), the car's logged BYDAuto NOA values, and a recorder
  at `SomeIpServerProxyImpl.fireEvent` playing the bound IVI Binder. `--no-answers`
  or a scripted MMTPathSelected (status 200, the requested pathId, 80 ms) through
  `SomeIpClient.onSomeIpEvent`. Nothing leaves the emulator process.
* `beijing.py`: factory Beijing package (`a1`) on the stock global.db; `survey`
  plans many O/D pairs and keeps each route's original NOA export
  (`stock_plan_observer.js`), `merge`/`select` pick the routes that cover the
  most transition kinds (`coverage.py`, Amap enums), `cases` builds a 1 Hz trace
  along each (class speeds, slowing into turns, stops at lights, arrival dwell) and
  a missed-turn splice onto another factory route.
* `bridge_trace.py synth`: SYNTHETIC Yandex input from each stock route (only what
  Yandex also has: polyline, sections with actions/next road, light positions,
  tunnel ranges, way points; identity frame) and the stock run's GNSS ticks
  projected on it, through `LiveBridge`. `bridge_trace.py yandex`: a REAL Navigator
  snapshot + guidance ring.
* `compare.py scenario RUN [--content-reference]` (one command per scenario):
  message inventory and cadence, route → selection → answer handshake, pathId and
  NavigationStatus lifecycle, reroutes, after-arrival tail, per-tick position and
  TrafficInfo semantics, route content field by field with links aligned by
  along-route distance, Amap-semantics checks on both sides, and the factory
  route's transitions with what each side sends around them. Classes:
  `bridge-defect`, `source-data-gap`, `stock-not-modelled`,
  `reference-not-authoritative` (content vs a stock on OSM-derived data, never a
  defect by itself), `stock-semantics`, `info`. `compare.py profile` tabulates per
  road type what goes to ADAS per link and per tick, for any traces.

```sh
python3 -m tools.adas_bridge.stock_trace run --build captures/stock-map-review-20261023/car/package-e \
  --case captures/adas-bench/cases/arrival-d02.json --out captures/adas-bench/moscow/arrival-d02-answers
python3 -m tools.adas_bridge.compare scenario captures/adas-bench/moscow/arrival-d02-answers
python3 -m tools.adas_bridge.beijing survey --out captures/adas-bench/beijing/survey-01
python3 -m tools.adas_bridge.stock_trace batch --plan captures/adas-bench/beijing/all/plan.json \
  --serial emulator-5602 --frida-port 27071
python3 -m tools.adas_bridge.compare scenario captures/adas-bench/beijing/run-<id> --content-reference
```

## OSM roads under the Yandex route (`road_store.py`, `roads.py`)

Yandex names no junctions and no road attributes; our OSM map has both. The
bridge places the Yandex route on it and, where it matches, cuts links at the
network's junction nodes and attribute changes and takes the attributes from it
(`libraries/adas-link` RoadStore + RoadMatcher, mirrored byte for byte here).
The geometry stays Yandex's.

* `road_store.py build --package BUILD_DIR --out roads.bin` — the store, from a
  `build_map.py` output (network, tiles and the routing chain through its final
  `factory` stage): every value is what the producers delivered to m2, decoded
  with the field-coverage gate's readers (`routing_tiles`, `decode_tile`, the
  factory speed-limit signs), mapped as the stock map exports it (direction
  raw≠3 → 1, ownership 1..4 → 1); names from the network's ways. Fragments that
  meet only each other with equal attributes are joined. Format in the module
  docstring; the report has counts and sizes only.
* `roads.py` — the reader and the matcher (HMM over samples every 12 m: fragments
  within 30 m in an allowed direction, a network path for every step, a no-road
  state; runs under 30 m dropped; past a dead end only 5 m). `stock.convert(route,
  frame, roads)` uses it; `route_convert(..., roads=)`, `LiveBridge(..., roads)`,
  `bridge_trace.py yandex --roads S [--engine java]` and `compare.py profile
  --roads S` (links grouped by the OSM road under them) pass it on.
* `road_bench.py --roads S --out OUT SNAPSHOT...` — links per route, link length
  and attribute coverage without and with the store, per road group next to the
  factory profile.

```sh
python3 tools/stock-map/build_map.py --region captures/adas-bridge-osm/region-yandex-routes.json \
  --out captures/adas-bridge-osm/build-01 --stages factory --network-margin-m 1000
python3 -m tools.adas_bridge.road_store build --package captures/adas-bridge-osm/build-01 \
  --out captures/adas-bridge-osm/roads.bin --report captures/adas-bridge-osm/roads.report.json
python3 -m tools.adas_bridge.bridge_trace yandex --engine java --roads captures/adas-bridge-osm/roads.bin \
  --snapshot captures/yandex-adas-prototype-20261023/route-01/denza-hud-native-route.json \
  --progress captures/yandex-adas-prototype-20261023/route-01/progress-later --out OUT/bridge-trace.json
```

Inside Navigator the module maps `denza-adas-roads.bin` from its files
directory (internal, else external) at bridge start; without it the bridge uses
Yandex data only.
