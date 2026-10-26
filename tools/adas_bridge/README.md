# Yandex → ADAS host oracle and offline prototype
Local signed build 17 is prepared with service-10 control (default OFF), recording
independent of overlay visibility and bounded reroute handoff. It is installed by
hash; A1 OFF is complete and closed. Nine 489–607 ms NOA-active runs all end in
ICC; availability remains below 1% of moving time. Reroute gaps fell to
1.745–5.504 s and status recording covers the drive and subsequent map hiding.
Stock remains disabled after owned closure following the owner's reboot.
[A1 evidence](../../research/adas-bridge/README.md#build-17-a1-off-drive-review-2026-10-25).
The current local control kit is `captures/yandex-feed17-20261025/trip-kit.json`;
use the build-17 section at the top of `captures/drive-kit-20261025/LOOP.md`.


The Python conversion/replay tools use the standard library and run offline;
`watch.py` adds bounded, read-only ADB access to an explicitly guarded emulator.
These conversion/replay tools have no Binder publisher and keep `publicationReady=false`.
The separate `ParkedRouteProbe.java` and `parked_probe/` APK sent one route and
selection on the parked car, then were removed; its source allowlist and
[physical receipt](../../research/adas-bridge/README.md#parked-route-selection-with-a-separate-probe-2026-10-24)
are separate from the offline tools. The device-side library is
[`libraries/adas-link`](../../libraries/adas-link),
compiled into Navigator by `tools/yandex_hud_build`; the durable research owner
is [`research/adas-bridge/README.md`](../../research/adas-bridge/README.md).

The build-13 follow-up added embedded vector decoding, explicit functional
road-class approximation and a separate full-route downloaded-map attribute
provider. Automatic offscreen-camera requests have been retired from the active
bridge. The provider uses standard pinned Zstd and a read-only catalog; route
choice and guidance remain Yandex SDK data. The owner's
minimal offline prerequisite is a fresh inventory containing at least one usable
installed region; it does not assert route coverage or require a second offline
route. That stage was compiled into the local signed ARM64 build 13
(SHA-256 `acc7054011849cbdd3c743133638c9776f96841af3bf39c991308dd40f263b86`),
with the separate parked-player APK and build receipts under
`captures/yandex-feed13-fullroute-20261024/`. Host Java replay and builder checks
passed; neither APK was installed at that stage. Subsequent build-15 Android
provider evidence and the remaining limitations are recorded in
[runtime and prerequisite status](../../research/adas-bridge/README.md#generic-vector-runtime-and-offline-prerequisite-2026-10-24).

## ODD-distance differential bench

`odd_bench.py` and `odd_bench_observer.js` run the original Amap
`DnpOddDistanceController` on a retained factory Beijing route. They replace the
IVI endpoint inside the emulator, record the original ODD protobuf packets and
perform no vehicle operations. `odd_bench.py check` independently replays the
native link URIDs/lengths through Python. `OddDistanceReplay.java` replays the
same events through the production `OddDistance` and `StockMessages` classes and
checks both the decoded distance and every payload byte.

The 2026-10-25 reference uses `survey-01-p006`: 74 native Beijing links, native
URID 1 and a 7,487 m route. Six cases each contain 222 samples (every link at its
full, half and zero remaining length): the retained 374-city ADAS response,
native cities, an empty set, an excluded set, and labelled synthetic
covered-to-excluded and excluded-to-covered transitions. The retained city list
is a controlled input from the car, not a claimed Beijing cloud snapshot.
Evidence is under `captures/adas-odd-bench-20261025/`; `prepared-v3/run/receipt.json`
has `status=observed` and `baseline_restored=true`. Both
`python-check-v3.json` and `java-check-v3.json` pass 1,332/1,332 samples. The
finite synthetic boundary outputs include 81 m and 40 m; excluded-route cases
also exercise zero and the 2 km look-ahead's MAX result.

This is calculator parity, not NOA acceptance and not a Russia-wide geographic
ODD catalog. `OddCoverage.Catalog` is generic, but `990001 -> 372` is only the
confirmed Moscow stock-map bench binding. Production conversion does not assign
it to Russian routes. Until every route link has a real adcode/URID binding,
`StockFeed` deliberately sends MAX instead of inventing a finite exit distance.

### Route-specific URID annotation

`urid_annotation.py` joins one retained Yandex route to the original Amap's
`LinkInfo.getURID()` observations and exported route geometry. It verifies the
84 native links against the 84 exported links, then samples each Yandex link at
no more than 12 m spacing in the stock SDK frame. A candidate must be within
30 m and 60 degrees of travel. A link is mapped only when at least 90% of its
samples have a candidate and all candidates carry one adcode and one URID.
Otherwise it remains adcode/URID zero. This is deliberately not a blanket `372`
assignment and not a generic road-network matcher.

For `captures/adas-live-drive-20261024/evidence/route-1.json`, Amap returned a
local 6,000 m route whose 84 links all have adcode 990001 and URID 372. The
Yandex route is 4,782.800 m before stock-link rounding and has 26 converted
links. Fourteen links (3,273/4,772 integer link metres, 68.59%) pass; twelve
remain unmapped because the routes diverge or the sample fraction is below the
threshold. No link is ambiguous. The result and every link's distances, sample
counts and candidates are in `captures/urid-route01-20261025/annotation.json`.

`OddAnnotationReplay.java` and the independent Python expectations agree on
144/144 samples. The incomplete full route returns MAX for all 78 actual-ODD
samples. A fully mapped 11-link excerpt returns MAX for all 33 actual-ODD
samples; replacing its final URID with a labelled excluded value produces
finite 240–1,945 m boundary distances, zeros and the stock 2 km MAX behavior.
This excerpt is a calculator control, not a shortened route sent to a vehicle.
See `replay-expectations.json` and `java-replay-check.json` in the same capture.
Neither result establishes generic Russia annotation, actual ODD coverage or
NOA acceptance. Production conversion is unchanged by this route fixture.

```sh
python3 -m tools.adas_bridge.urid_annotation annotate \
  --snapshot captures/adas-live-drive-20261024/evidence/route-1.json \
  --events captures/urid-route01-20261025/home-prepared/run/app/events.jsonl \
  --odd-response captures/adas-h0-listen-20261024/H0-odd-response.bin \
  --out captures/urid-route01-20261025/annotation.json \
  --replay-out captures/urid-route01-20261025/replay-expectations.json
python3 -m unittest tools.adas_bridge.tests.test_urid_annotation
./gradlew :adas-link:testDebugUnitTest
```

## Preparing the real channel

`live_session.py` is separate from the offline converter. It prepares one
explicitly armed session of the exact supplied APK; it is not an installer.
Build 17's three control plans pass locally with zero device calls. Its
`plan` and `arm --dry-run` modes make **zero device calls**. `build-reader`
compiles the fixed shell-UID getter helper into a DEX JAR, with source and
artifact hashes. Use `--help` for the required APK version/hash, reader hash,
serial and rollback-ledger arguments; the script never chooses a device or
assumes an installed version.

The actual arm operation requires human stationary confirmation, the narrowly
tested park predicate and measured speed zero. It preserves the stock map's
enabled state before disabling it, proves process absence, validates the
reader and waits for the matching runtime to accept the arm. `restore` disarms
first, asks the owned reader to stop, proves runtime quiescence, then restores
the exact original package state. Failure to prove closure is reported as
pending restoration. It does not reset ADB keys, tunnels or global SOME/IP
clients. Sessions 1–3 were restored with exact original package state; build-11 session 4
was restored before installing build 12. The historical build-12 trip ledger is
`captures/yandex-feed12-20261024/session1.rollback.json`. Timeout closes the
publisher but does not restore the stock package; run `restore` after the test.

`arm --variant FILE.json` puts one experiment variant into the arm (examples in
`variants/`: baseline, H1, H2a–H2g, H3, H6, H7, the historySpeed A/B, drive-1/drive-2
and the settlement loop's `loop-*`, below). It is checked
against ExperimentVariant's strict schema before any device call: an unknown key
or a value outside the stock's set refuses the arm. The effective variant is
printed, written to the rollback ledger and carried by the arm, so a restarted
Navigator keeps it. Link and position fill-ins only set a field Yandex left unknown
(a graph road's or the offline maps' class and one-way stay). Only `startProtocol:
stock` (H3) sends the ODD region read, with the fixed parcel, once per fresh guidance
start; without it the live bridge makes no ODD request. With `naviSt: true` the reader is started with the arm's id
and drives SETTING_MPC_IFC_NAVI_ST_SET (`AdasGateReader.NaviSt`): it reads the
original at arm time (the arm is refused without it), writes 2 at guidance on a
confirmed path, restores on guidance end, arm end or expiry and its own exit, and
reads back every write. `restore` then reads NaviSt itself and writes the
recorded original back only if the reader did not. Every read and write is in the
reader's ledger (`files/denza-adas-navi-st.json`) and Navigator's journals.

`instrumentNaviStatus: true` independently enables the stock instrument side channel
(device 1007, `0x43E0003A` / 1138753594); new host `arm`/`plan`/`arm --dry-run` sessions default the instrument switch ON;
set `instrumentNaviStatus: false` explicitly to disable it. MPC `naviSt` stays OFF.
The host normalizes and records this default in the variant before starting a reader.
Legacy arm files and direct Java schema parsing keep their old absent-key OFF behavior. `H7.json`
changes only that switch, and parked scenario H7 is H0 plus the instrument signal.
The stock map's `NaviService.sendNavigateInfoToCAN` writes 2 for GPS navigation,
1 when guidance stops, via `PlatformApiImpl.setNaviState` and the instrument proxy.
The IVI HAL registers this exact INT event in both get/set maps with the same
parameter cache; shell UID passes the native permission gate. Its initial 65535
is unknown: only an original and every readback in 0..4 is accepted. Cache readback
does not prove ECU receipt or an ADAS consumer.

The reader stores this channel's original at arm time in its own
`files/denza-adas-instrument-navi-status.json` ledger, and H7 refuses to arm without
it. It writes 2 only while the same unexpired arm owns live guidance on a confirmed
path; it restores the exact original at guidance end, arm expiry, stale live state,
stop or exit. Failed reads write nothing; uncertain writes attempt restoration and
disable only that channel. Navigator journals instrument events separately from
MPC NaviSt. Host `restore` disarms and proves egress quiescent, restores each side
channel independently (including fallback from the rollback ledger when the reader
is gone), then restores the stock package. Any pending side restoration keeps the
stock map disabled and records `ROLLBACK_PENDING`. The parked runner uses the same
fixed read/write commands, verifies writes and restores after the player's end or
on error, timeout and Ctrl-C. No generic device/FID setter or `fire13/0x0001` is added.

Private JSON writes use an atomic, bounded base64 command, avoiding an exec-out
stdin EOF dependency. Getter subprocesses retain their one-second deadline but
poll completion every 5 ms; a car batch measured 145 ms after this correction.
Freshness limits are unchanged. The consumer heartbeat clock is read after the
file. These fixes and failed attempts are retained in the build-11 evidence.

The [live-channel findings](../../research/adas-bridge/README.md#guarded-live-candidate-2026-10-24)
describe the real gates, callbacks, ACK and timing checks. Run the production
coordinator's local harness without Android or ADB using:

```sh
python3 tools/adas_bridge/runtime_harness/run.py --out /tmp/denza-live-runtime-check
python3 tools/adas_bridge/passive_client_harness/run.py --out /tmp/denza-passive-client-check
python3 -m unittest tools.adas_bridge.tests.test_live_session
```

The reader's own test (NaviSt read → write 2 → restore, no write on a failed
read) compiles against `android.jar` and runs on the host:

```sh
A=$ANDROID_HOME/platforms/android-35/android.jar L=libraries/adas-link/src/main/java/dev/denza/adaslink
javac --release 8 -cp $A -d /tmp/denza-reader-test tools/adas_bridge/AdasGateReader*.java $L/Json.java $L/AdasGateSnapshot.java $L/BootIdentity.java
java -cp /tmp/denza-reader-test:$A dev.denza.adasprobe.AdasGateReaderTest
```

The client harness executes the actual `AdasPassiveClient` against a fake Binder
boundary; it exercises fresh/already-started service setup, availability replay,
readiness loss and owned cleanup. Neither harness proves delivery on the car.
The runtime harness also runs `RerouteHandoffTest`: both replacement queue orders,
new-path ACK, bounded preparation, real offline loss and ordinary dynamic egress.
The 2026-10-25 local correction removes the reproduced end/fresh-start race;
it is not in installed build 16. Before/after evidence and source hashes are in
`captures/reroute-fix-20261025/`.
The final local APK, source archive and pinned plan are recorded in
`captures/yandex-feed12-20261024/candidate12.json`. Build 12 retains
separate bounded packet/history recording and captures the existing live
client's actual responses independently of overlay visibility; the send and
arming protocol from candidate 9 is unchanged. The APK explicitly
queries the stock package so its enabled-state check works under Android's
package-visibility rules; no extra permission is added.

After a drive, `drive_review.py` reads a directory of pulled evidence (Navigator's
`files/denza-*` and `denza-drive-evidence/`, tars of them, the host ledgers and
logcat) and answers, with the file and line of every number: sessions and their end,
paths per Yandex route and why each new one appeared, topic cadence, pauses and
position gaps, matched positions carrying unmatched markers, ADAS episodes against
what was being sent, the side channels and their restore, and process health:

```sh
python3 tools/adas_bridge/drive_review.py <pulled-dir> --out <dir>   # review.json + review.md
```

Build 16 also records optional `VehiclePositionInfo` from service/instance 10/10
while disarmed. `drive_review` adds `adasVehiclePosition` with raw payloads,
field presence, freshness and same-process joins to Yandex guidance. Its output
frame is not proof of the datum required for incoming ADAS routes. The first
parked build-16 capture measured 0.830 MiB/min of packet logs: the current ring
retains approximately 58–67.5 minutes at that rate, replacing the older
two-hour estimate. [Installed receipt and limits](../../research/adas-bridge/README.md#build-16-position-observer).

### Settlement loop: the stock-like arm and its halving

The loop files describe experimental comparisons with build 15's NOA→ICC cycle;
one drive cannot identify or exclude its cause. Some values come from stock
positions on the same streets; others came from a separate parked route
([research README](../../research/adas-bridge/README.md#stock-cnoa-hold-against-build-15-field-by-field)).
Builds 15 and 16 both carry every switch below (their `ExperimentVariant`,
`FieldDefaults`, `StockFeed`, `RouteConverter` and `Coordinates` equal main's;
build 16 changes only the passive observer), so the host reads the file at `arm`.

| Group | Switches (the stock's value) |
| --- | --- |
| F frame | `frame: STOCK_GCJ` — position, matched point, route, lights and facilities in the stock's frame. It reproduces the stock's 2026-10-23 wire from ADAS's own WGS-84 pose within 0.4 m; ADAS's output pose is WGS-84, so this is an experiment, not a fix |
| O ownership | `position.ownership: 0`, `link.ownership: 0` |
| L lanes | `link.laneNum: 1`, `link.formway: 15` |
| R region | `region: 990001`, `link.adminCode: 990001` |
| S limit | `speedLimit: zero` |

Every `loop-*` file is a slice of `loop-all` and carries build 15's drive start:
`startProtocol: stock`, `naviSt: false`, `instrumentNaviStatus: false`
(`test_live_session` holds the slices to `loop-all`). Fill-ins only set what
Yandex left unknown. LaneNum 1 and formway 15 were observed in a separate parked
route, not in retained route bodies of the successful CNOA intervals; they are
not established attributes of every settlement street. S zeroes the posted
limit, not speed cameras. These files are experimental inputs, not validated
road descriptions or approval to use them for road-going NOA.

**The loop** is the settlement streets where the stock's CNOA held on 2026-10-23
(drive-02, 24.9 s and 7.2 s) and build 15 cycled on 2026-10-24 (20:18:15–20:18:25
and 20:37:34–20:37:36); times and positions are in the private post-drive capture.
The earlier proposed drive procedure asked for two requests per arm. For retained
logs, classify the cluster observations and `drive_review.py` intervals per arm id:

- **HOLDS:** one CNOA interval of at least 10 s without the «Downgraded to ICC» /
  «CNOA activated» cycle (build 15's longest there: 1.7 s; anywhere: 5.4 s).
- **CYCLES:** every interval under 3 s, with «Downgraded to ICC».
- No available interval: NEVER OFFERED; other results: INCONCLUSIVE.

The earlier proposed order allowed changes only while stopped in P, with a
restore between separate ledgers. It is retained in the private runbook as a
proposal, not vehicle acceptance of the variants.

`loop-frame` ran first on 2026-10-24 (21:53): on the same streets NOA was never
offered, where build 15 on WGS-84 had it offered at 69 % of moving positions
([research README](../../research/adas-bridge/README.md#build-16-position-observer)).
The frame group therefore stays WGS-84.

The link values of the O, L and R groups come from `first-full`, a parked capture
with a later package than the hold drive; the holds' route bodies are not
retained (research README, the correction of 2026-10-25). The revised proposal is
`loop-wire`: only the values the stock's own send log carries
during its CNOA holds — `position.ownership: 0`, `region: 990001`, `speedLimit:
zero` — with no link fill-in. Those scalars occur in the retained stock send
logs. Their occurrence does not establish the receiver semantics of zero, the
correct ownership/region for a new route, or safety outside the settlement.
The proposed `loop-wire` road test has not been prepared or armed by this
follow-up. Local protocol comparison can proceed without sending these
overrides to the vehicle.

For interpretation of any retained comparison, distinguish HOLDS, CYCLES,
NEVER OFFERED and INCONCLUSIVE. A positive combined result only makes that
combination a candidate; it does not identify one field. A negative result
does not exclude the fields, because interaction, route, traffic and ECU state
were not controlled. The single-field files can be compared in local replay;
no value becomes a permanent bridge default from one successful activation.

`loop-all` (with the shift), `loop-rest` and their link-filling halves are kept
as files but are out of the order. A contradictory result is inconclusive;
interaction is one hypothesis, not a demonstrated explanation. Preserve the
evidence and investigate locally. Pull the evidence after every arm, before its
`restore`: build 16's packet ring keeps about an hour, less than five arms.

## Yandex-only data source

The Navigator build now uses its own online graph and route annotations. It
contains no OSM loader, database path or regional store. The historical
`RoadStore` Java reader remains under test sources only, for the old matcher
oracle. `GraphTile`, `GraphTileCache` and `GraphRoadNetwork` are the runtime
source; `YandexGraphSource` owns the background decoder and bounded cache.

* `yandex_graph_observer.js` observes original online graph protobuf messages
  inside the exact ARM64 Navigator 29.8.1 library. Tested with Frida 16.7.19 on
  an owned emulator. Verify the library SHA in the script before attachment;
  its instruction-prefix check is not a complete library hash check. Save the
  binary `data` argument from Frida's message callback as a `.bin` file.
* `yandex_graph_tile.py` decodes those saved tiles into directed road fragments,
  source IDs, adjacency, geometry, names, limits and endpoint levels. It retains
  tile-boundary gaps and unknown fields instead of joining roads by proximity.
* `yandex_graph_capture.py` independently decodes saved `lookupEdge` memory
  observations. Graph pointers and vertex indices stay scoped to their capture
  frame. Bytes beyond the confirmed 96-byte object are not road attributes.
* `yandex_vector_observer.js` captures bounded successful online `vmap3.Tile`
  decodes at a different native entry, with wrapper/render/source TileId context.
  `yandex_vector_tile.py` exports raw geometry buffers and dictionary-backed
  feature attributes, retaining root field 7 as opaque bytes even when it is
  not UTF-8. Runtime samples contain literal `class`, `fc`, `oneway`,
  structure/toll/level fields and HD markings.
* `yandex_vector_geometry.py` reconstructs native local paths, the 30-bit world
  affine and ellipsoidal WGS84 geometry. It compares saved road features with
  route/graph geometry and exports a host review table. Renderer feature IDs
  and graph edge IDs remain separate namespaces; class/fc/oneway are not recoded
  or consumed by build 5's ADAS bridge.
* `yandex_inference.py` extracts route evidence and separate estimates. Optional
  lane extrapolation is capped at 100 m upstream from a sign and stops at known
  boundaries. It does not turn junction sign width into a measured count over
  an entire road.

```sh
python3 -m tools.adas_bridge.yandex_graph_tile tile-01.bin tile-02.bin --out graph.json
python3 -m tools.adas_bridge.yandex_graph_capture observations.jsonl --out observed.json --route route.json
python3 -m tools.adas_bridge.yandex_inference --out-dir inference --approach-lanes-m 100 route.json
python3 -m unittest tools.adas_bridge.tests.test_yandex_graph_tile tools.adas_bridge.tests.test_yandex_graph_capture tools.adas_bridge.tests.test_yandex_inference
```

The parsers use only the Python standard library. The embedded ARM64 collector
is built by `tools/yandex_hud_build/native_graph.py`; it needs neither root nor
Frida. It pins the original library SHA, loaded build ID and instruction bytes,
checks register preservation before installation, and copies only naturally
received graph tiles into an eight-slot queue. Parsing runs on a worker. The
Frida observer remains an independent research tool; do not attach it at the
same decoder entry while the embedded collector is installed.

`files/denza-yandex-graph/status.json` records queue/cache/decode metrics.
`files/denza-yandex-graph-route.json` records accepted fragments and actual
matched route metres/cuts. Raw tile evidence is written on emulators or when
the car's `files/denza-drive-capture.enabled` marker enables bounded recording.
The dry-run host declares WGS84 for all regions; the historical Moscow-only
native-frame candidate remains an explicit bench option. Installed build 8 has
publication compiled off; candidate 10 uses the separately armed channel above.
Evidence, coverage and remaining attribute gaps are in the research
owner's [Yandex-only extraction section](../../research/adas-bridge/README.md#yandex-only-graph-extraction-2026-10-24).

### Vector world geometry: capture and replay

The vector observer has three instruction-prefix guards: decoder `0x17bbaa0`,
wrapper `0x17bb6c8` and layer context `0x17c1bdc`. Verify the pinned complete
APK/library identity separately before attaching to an already running owned
emulator process. Prefix guards alone do not establish that identity. It
records per-thread nested wrapper, render and layer-source TileIds and emits
bytes only after the original parser reports success. Save the message
payload plus `binaryFile`, `binarySize` and SHA-256 in `events.jsonl`, alongside
the `.bin` files; `decode_capture` checks all three identities and their raw
12-byte representations, native success, thread ID and binary size/hash.
Missing or differing TileId contexts are rejected rather than inferred from
geography. The capture proves equality for these inputs, not all overzoom cases.

Observer bounds are **eight copied inputs, 8 MiB combined copied bytes and
60 seconds**; failed native parses still consume the copy budget. Inputs below
zoom 14 or 32 KiB are skipped. An optional
`globalThis.DENZA_VECTOR_CAPTURE_WINDOW = {z, minX, maxX, minY, maxY}` supplied
before the script restricts capture to one validated tile rectangle. It is a
caller-selected window, not a regional road store. The observer detaches on its
budget, duration or `stop()` RPC. It performs no application setters or explicit
native data-source calls. One later Frida spawn attempt failed in Android's
zygote before observer installation; normal launch recovered, and that trigger
was not retried. This is not a reason to repeat a spawn for the preserved replay.

Host geometry capture bounds are also eight tiles/8 MiB combined, with the raw
parser's separate **2 MiB per-input** default. Geometry limits additionally cap
the whole capture at 100,000 features and 500,000 stored coordinate pairs, each
tile at 200,000 paths, and each path at 100,000 pairs. A path starts with an
absolute pair, then deltas, resetting at each component. The affine denominator
is `2^b-1`, with inclusive local-grid bounds; projection is the native
ellipsoidal WGS84 implementation. Rings are not automatically closed, polygon
auxiliary roles stay unknown, and host verification rejects native int32
overflow or an empty Point's cross-path peek.

Exact CLI for the retained eight vector inputs, positive route and combined
fourteen-file graph corpus (run from the repository root; the existing graph
glob expands in the shell):

```sh
python3 -m tools.adas_bridge.yandex_vector_geometry \
  captures/yandex-vector-world-20261024/runtime \
  --out /tmp/yandex-vector-world.json \
  --route captures/yandex-vector-world-20261024/route.json \
  --graph-tiles captures/yandex-vector-world-20261024/graph-current/tile-*.bin \
  --verification-out /tmp/yandex-vector-verification.json \
  --enrichment-out /tmp/yandex-vector-enrichment.json

python3 -m unittest tools.adas_bridge.tests.test_yandex_vector_tile tools.adas_bridge.tests.test_yandex_vector_geometry -v
node tools/adas_bridge/tests/test_yandex_vector_observer.cjs
```

`--out` is required. `--route`, `--graph-tiles`, `--verification-out` and
`--enrichment-out` are optional; enrichment needs graph inputs and also writes
a sibling `.csv`. The saved corpus is input only in the command above. The
frozen outputs and hashes are indexed by
[`yandex-vector-world-20261024.json`](../../research/adas-bridge/yandex-vector-world-20261024.json).

For this positive control, **98.73% is sampled 2D coverage including ambiguity**:
94.08% unique and 4.65% ambiguous, with a 3 m distance/15° undirected-heading
gate and 10 m sampling step. The table's separate 5 m sampling gate supports
582 canonical graph fragments (975 directed-edge IDs, 237 renderer feature IDs),
not 582 globally identified roads. It requires 95% single-candidate coverage,
at most 1% ambiguity, 15 m extent and three samples. Unmatched, ambiguous,
partial and short fragments remain explicit. Literal `class`, `fc`, `oneway`
and level/structure fields accompany candidates; they are not Amap categories,
legal traffic-direction assertions or per-road lane counts. This is an offline
host table, **not runtime ADAS enrichment**. Coverage between samples, shared
vertical-level semantics and unclipped edge extents remain unverified.

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
  The producer clock must be established: Yandex `relativeTimestamp` is
  `CLOCK_MONOTONIC` milliseconds, matching Android `uptimeMillis`, while
  `capturedUptimeMs` in these snapshots is `elapsedRealtime`. Direct subtraction
  mixes clock domains. Build 7's retained car age/freshness fields are invalid
  for that reason; see the [native clock proof](../../research/adas-bridge/README.md#location-clock-domain-2026-10-24).
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
python3 "$DENZA_AMAP_INJECTOR"/tools/stock-map/build_map.py --region captures/adas-bridge-osm/region-yandex-routes.json \
  --out captures/adas-bridge-osm/build-01 --stages factory --network-margin-m 1000
python3 -m tools.adas_bridge.road_store build --package captures/adas-bridge-osm/build-01 \
  --out captures/adas-bridge-osm/roads.bin --report captures/adas-bridge-osm/roads.report.json
python3 -m tools.adas_bridge.bridge_trace yandex --engine java --roads captures/adas-bridge-osm/roads.bin \
  --snapshot captures/yandex-adas-prototype-20261023/route-01/denza-hud-native-route.json \
  --progress captures/yandex-adas-prototype-20261023/route-01/progress-later --out OUT/bridge-trace.json
```

Inside Navigator the module maps `denza-adas-roads.bin` from its files
directory (internal, else external) at bridge start; without it the bridge uses
Yandex data only. One file, one region: build the store from one `build_map.py`
build whose region covers everywhere the car drives (the stores of two builds
are not merged). The YandexHUD build is debuggable, so the file goes into the
internal files directory through `run-as`, then Navigator is restarted (the store
is mapped once, when the bridge starts):

```sh
adb exec-in "run-as ru.yandex.yandexnavi sh -c 'cat > files/denza-adas-roads.bin.part'" < roads.bin
adb shell "run-as ru.yandex.yandexnavi sh -c 'chmod 600 files/denza-adas-roads.bin.part && mv files/denza-adas-roads.bin.part files/denza-adas-roads.bin'"
adb shell "run-as ru.yandex.yandexnavi sha256sum files/denza-adas-roads.bin"
adb shell am force-stop ru.yandex.yandexnavi
adb logcat -s DenzaAdasBridge   # "OSM roads …: N roads, B bytes"; the dry-run trace's state line says the same
```

Removing the file (`run-as … rm files/denza-adas-roads.bin`, then a restart)
returns the bridge to Yandex data only. The file survives `adb install -r`.
