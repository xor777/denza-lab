# Yandex → ADAS offline prototype

Host Python 3 standard library only. Conversion/replay is offline; `watch.py`
adds bounded, read-only ADB access to an explicitly guarded emulator. There are
no Binder calls, vehicle setters or publisher. `publicationReady` is always
`false`. The durable research owner is
[`research/adas-bridge/README.md`](../../research/adas-bridge/README.md).

The input is `denza-yandex-driving-route-v1` from `HudRouteSnapshot`: WGS84
`points` in longitude/latitude order; sections and `Subpolyline` positions use
`{segmentIndex,segmentPosition}`. Root lists distinguish absent/`null`/reported
unknown from a known empty array. The historical `routeId` + `points` fixture is
accepted with explicit missing-metadata and assumed-frame blockers. Section
metadata (weights, manoeuvres, HD annotation, descriptors) and rich root lists
are retained in the intermediate representation.

```sh
python3 -m tools.adas_bridge.bridge convert \
  captures/yandex-hud-ar-20261015/acceptance-01/native-route.json \
  --target-frame native-gcj-unrestricted-candidate \
  --out captures/adas-bridge-offline-prototype-20261023/yandex.candidate.json

python3 -m tools.adas_bridge.bridge validate \
  captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.payload.json

python3 -m tools.adas_bridge.wire \
  captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.someip.bin \
  --out-dir captures/adas-bridge-offline-prototype-20261023/golden-roundtrip

python3 -m unittest discover -s tools/adas_bridge/tests -v
```

`convert` cuts geometry at fractional manoeuvre/range boundaries and speed-limit
changes; retains source position → section/link mapping; duplicates boundary
endpoints as stock does. `pntBegIdx`/`pntCnt` consume each link's separate slice.
Link IDs use SHA-256-derived positive signed64 JSON integers. Synthetic path IDs
are limited to **53 bits**: the inspected position protobuf stores curSDRouteID
as DOUBLE although route JSON and selection use int64. This preserves the exact
path ID in all three encodings. There are no Python `hash()`, float link IDs or
JS-number roundtrips. Synthetic IDs have unverified ADAS semantics.

`--target-frame` is mandatory. `wgs84` is geometry inspection; the alternative
reuses the existing Moscow-only `routing_coordinates.py` candidate. Input must
remain WGS84 and transformation is applied once to geometry and matched position.
Neither choice proves receiver datum. Fresh Gson float formatting and the final
SDK quantization are not covered by the captured-byte roundtrip.

`candidateNaviToEHPData` keeps unknown SDK fields as `null`, with blockers.
There is no fallback `laneNum=1`, road-class/NOA guarantee, lane-sign-width to
road-lane conversion, highway-range to class upgrade, or assumed direction/
ownership/adminCode. Known tunnel ranges map to verified `LinkTypeTunnel=2`;
other link types remain unknown. Link `speedLimit=0` reproduces the inspected stock policy;
Yandex nullable per-edge limits remain separately available. Yandex route
`Action` is mapped only where its meaning matches the inspected stock
`MainAction` enum; HUD field-28 icon IDs are a different namespace. Unproved
exit/assistant semantics remain unknown. Yandex annotations describe a section's
**beginning**; the candidate stock segment instruction describes its **end**, so
the next section's entry annotation is attached to the preceding segment at
their shared geometry boundary. The final `MainAction=0, AssistantAction=36`
arrival is emitted only when known `wayPoints` begin at route start and end at
the last geometry point. Global lights keep their coordinates; signal ownership
is assigned to the incoming link (`begin < position <= end`) as in the stock
exporter. A light exactly at route start has unknown incoming ownership.
Grouping is one provisional
group over source sections; it does not claim stock road grouping equivalence.

`bridge progress converted.json sample.json --now-ms N` maps the original
source edge/fraction to the cut link, local link index, **remaining** link offset,
remaining section distance and destination distance. Samples require matching
`routeId`, integer `generation`, `capturedAtMs` and `expiresAtMs` in the **same
clock domain** as `--now-ms`, `status:"guidance"`, and `matched:true`. Bind an
observed generation with `convert --generation N`; bind a source process session
with `--source-session ID`. Native `denza-yandex-guidance-v1` samples instead use
`mode`, `freshLocation`, `sourcePolylinePosition` and `fixAgeMs`; host expiry is
2000 ms and aggregate fix age is limited to 2500 ms. `--clock wall|uptime` is
explicit; the source field named capturedUptimeMs actually uses Android
`elapsedRealtime()`. Native raw location and route geometry must agree within
the explicit diagnostic tolerance `min(100m,max(25m,accuracy+15m))`, accuracy must
be known and at most 100m, and native absolute/relative timestamps must remain
fresh (2500ms, at most 250ms ahead). The input location comes from Yandex
`Guidance.getLocation().getLocation()` (a classified location), so it is not
independently proved raw GNSS. These are prototype guards, not established
ADAS localization thresholds. New source sessions require matching
`positionRouteId`, `positionMatchesRoute=true`, and the route snapshot's own
session ID. Old captures without session/ownership fields remain labelled
diagnostic compatibility data.

The fake receiver checks order and invalidates old confirmation on reroute. It emits
offline status 110 for 3000 ms after end then 100; this is the proposed lifecycle,
not a claim about receiver response. Stale-to-fresh recovery retransmits cached
route bytes and selection, requires a new mock confirmation, and advances a
local publication epoch while retaining the source generation. Process sessions
can restart generation/sequence; retired sessions, old generations, old queued
progress and old tagged mock ACKs are rejected. Real ADAS ACKs contain no epoch
token, so this does not prove rejection of delayed real network ACKs.

`python3 -m tools.adas_bridge.replay events.json --out trace.json` accepts a JSON
list of `route` (`convertedFile`, relative to events.json), `selection` (`pathID`),
`fake_confirmation` (`pathID`), `progress` (`sample`, `nowMs`), `end` (`nowMs`) and
`status` (`nowMs`) events. Fake confirmations are explicitly labelled and never
car acceptance evidence. No rejected or expired sample keeps an active fake feed.

`python3 -m tools.adas_bridge.bench converted.json golden.someip.bin --time-ms N
--out-dir DIR` creates a **separate incomplete experimental profile** using the
explicit stock golden envelope metadata. It substitutes only verified native
enum null sentinels `roadclass=-1` and `linktype=-1`; all other unknown primitive
fields remain JSON null. It emits actual route Binder protobuf/SOME-IP bytes,
selection Binder bytes, and a manifest. The fake receiver parses those bytes
before route → selection → labelled mock confirmation. JSON null primitives
are **not** claimed acceptable to real ADAS.

`capture_replay` takes a rich snapshot, native `sample-*.json` ring and stock
golden, plus `--target-frame`/`--out-dir`. It orders by recorded elapsed realtime
and tracks `(sourceSessionId,sequence)` separately. It runs at recorded time with
zero simulated delivery delay, so archival replay does not assert current data
freshness. Every accepted native sample encodes and locally parses three actual
Binder protobufs: `sdVehicleLocation`, `TrafficInfo`, and NavigationStatus 101.
`dynamicBinderPackets` in the replay/receipts retains raw hex, SHA-256, decoded
fields and explicit missing attributes. The protocol's GPS coordinate slots use
the source classified location and its matched slots use route interpolation;
they are computed separately and transformed once each. This does not prove an
independent raw GNSS feed. Path IDs remain exact; offsets/distances are integer
metres. TrafficInfo uses verified `roadClass=-1` and stock legal-limit zero.
Vehicle speed, ownership, cameras/jam/off-course details remain unavailable;
GPSspeed is omitted until native unit/scaling is traced, while Yandex m/s is
retained. Omitted proto3 primitives decode to defaults; the missing-attribute
list prevents treating those defaults as known values. These are Binder
encodings, not fabricated native SOME/IP dynamic structs.

```sh
python3 -m tools.adas_bridge.watch \
  --serial emulator-5592 --duration-s 60 \
  --golden-someip captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.someip.bin \
  --target-frame native-gcj-unrestricted-candidate \
  --out-dir captures/yandex-adas-prototype-20261023/converted-01/live-watch
```

The watcher accepts only `emulator-N` serials and checks `ro.kernel.qemu=1`
before any app-file access. Duration is bounded to 1–300 seconds and every ADB
command has a deadline. It reads only the two atomic evidence files and device
uptime, with `run-as` or an already-root emulator's `head`; it never invokes
`adb root`, `su`, installs, activities or services. A new route/session must
match progress identity before conversion. JSONL receipts contain the decoded
dynamic packets, expiry/cancellation and mock recovery; run/session/generation/
epoch filenames retain route and selection bytes without cross-session reuse.

The `wire` tool separates two real formats:

* Captured SOME/IP: 16-byte header, native big-endian CRC32/counter/uint16
  route-count/uint32 UTF-8 length, BOM + original JSON + NUL. The retained car
  golden is reconstructed byte for byte from parsed framing and untouched JSON.
* Binder input: protobuf `naviSDRouteNotify` field 1 containing CRC32 field 1,
  counter field 2, route count field 3 and JSON string field 4. This is encoded
  and decoded separately; it is **not** the bytes on SOME/IP TCP.

Candidate UTF-8 chunking/reassembly is bounded and rejects missing, mixed or
reordered chunks. It is not claimed identical to stock Java substring chunking
for non-ASCII data. `diff` reports exact JSON differences including missing vs
null and integer vs float. The current Yandex and stock captures describe
different routes; their comparison cannot establish corridor equivalence.

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
