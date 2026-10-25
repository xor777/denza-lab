# Yandex → ADAS host tools and retained research

On `codex/stock-sender-parity`, candidate 15 replaces the product sender with
`stocknative` and the unified `yandexnative` adapter. The previous feed and its
variant/golden tests remain historical research, not product acceptance.
The original-APK laboratory is frozen; current work is product integration,
stationary acceptance and a first trip. Signed candidate 15 is built locally,
not installed; `captures/stock-sender-parity-20261025/product/candidate15.json`
pins its APK, gate reader and validation results.
See the [current milestone and field policy](../../research/adas-bridge/README.md#product-milestone-and-bench-freeze).


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

The historical build-13 follow-up added embedded vector decoding, explicit functional
road-class approximation and a separate full-route downloaded-map attribute
provider. Automatic offscreen-camera requests have been retired from the active
bridge. The provider uses standard pinned Zstd and a read-only catalog; route
choice and guidance remain Yandex SDK data. The owner's
minimal offline prerequisite is a fresh inventory containing at least one usable
installed region; it does not assert route coverage or require a second offline
route. The latest changes are now compiled into the local signed ARM64 build 13
(SHA-256 `acc7054011849cbdd3c743133638c9776f96841af3bf39c991308dd40f263b86`),
with the separate parked-player APK and build receipts under
`captures/yandex-feed13-fullroute-20261024/`. Host Java replay and builder checks
pass; neither APK was installed, and Android provider/JNI execution remains
unverified. See
[runtime and prerequisite status](../../research/adas-bridge/README.md#generic-vector-runtime-and-offline-prerequisite-2026-10-24).

## Preparing the real channel

`live_session.py` is separate from the offline converter. It prepares one
explicitly armed factory-sender session; it is not an installer. Candidate 15
must report `sender: stocknative` before the host accepts an arm. Its
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
was restored before installing build 12. The current two-hour trip ledger is
`captures/yandex-feed12-20261024/session1.rollback.json`. Timeout closes the
publisher but does not restore the stock package; run `restore` after the test.

New `plan` and `arm` commands accept no `--variant` or sender switch. A legacy
variant arm is rejected; the reader performs no new MPC/instrument side-channel
writes. The adapter's missing-boolean test policy is explicit in source and logged
per route. The reader also reads device 1038 FID `230686742`, the actual factory
tip-support input. Its presence is required by the product environment.

`restore` still understands historical rollback ledgers and restores their exact
recorded MPC/instrument originals when necessary. This compatibility is cleanup
for existing sessions, not an alternate sender path. The historical variant
protocol below applies only to earlier builds and parked research tools.

<details>
<summary>Historical variant and side-channel protocol (not accepted by candidate 15)</summary>

`arm --variant FILE.json` puts one experiment variant into the arm (examples in
`variants/`: baseline, H1, H2a–H2g, H3, H6, H7, the historySpeed A/B). It is checked
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

</details>

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

The reader's own test (actual gate reads plus historical restoration behavior,
including no write on a failed read) compiles against `android.jar` and runs on the host:

```sh
A=$ANDROID_HOME/platforms/android-35/android.jar L=libraries/adas-link/src/main/java/dev/denza/adaslink
javac --release 8 -cp $A -d /tmp/denza-reader-test tools/adas_bridge/AdasGateReader*.java $L/Json.java $L/AdasGateSnapshot.java $L/BootIdentity.java
java -cp /tmp/denza-reader-test:$A dev.denza.adasprobe.AdasGateReaderTest
```

The client harness executes the actual `AdasPassiveClient` against a fake Binder
boundary; it exercises fresh/already-started service setup, availability replay,
readiness loss and owned cleanup. Neither harness proves delivery on the car.
The historical build-12 APK, source archive and pinned plan are recorded in
`captures/yandex-feed12-20261024/candidate12.json`. Build 12 retained
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

**Frozen on 2026-10-25.** Preserve recorded original-APK start/arrival and
reroute outputs as the immediate comparison corpus. The next milestone is the
product adapter and opt-in factory sender, then stationary receipt and a first
drive. Phase 2 is reopened only for a specific unexplained output discrepancy;
the commands below document the retained laboratory, not a continuing coverage
campaign. See the [owner decision](../../research/adas-bridge/README.md#product-milestone-and-bench-freeze).

For an independent **original-input sender** comparison, use
[`sender_parity.capture`](sender_parity/capture.py) and
[`sender_parity.bench`](sender_parity/bench.py). That bench records native
PathInfo getters and processed SDK callbacks before original conversion, then
replays the retained inputs directly into StockFeed without a Yandex adapter.
It retains unsupported callbacks, service lifecycle, startup/timer phase and
strict byte/order/timing differences. The first factory start/arrival case
confirms the exact native route JSON payload, not complete sender equivalence.
Commands, measured results and limitations are in
[Original sender parity](../../research/adas-bridge/README.md#original-sender-parity-2026-10-25).

The second-stage literal core is `stocknative`, with raw native SDK getter seams
instead of Yandex inputs. `sender_parity.phase2_run` captures original method
entries/exits, getter and clock reads, mutable callback inputs, and individual
Handler post/cancel/dispatch occurrences. `sender_parity.phase2_bench` replays
that ledger and compares every output and queue effect without startup clipping
or packet alignment. `CAPTURED` establishes transcript integrity only; a passing
comparison is a separate result. A missing mandatory original callback makes
the capture incomplete. Finite captures use an explicit original SDK position
observer unsubscribe after all declared inputs return and guidance stops, then
require all due sender work to drain; only strictly future timers may remain.
The opt-in JVMTI recorder and calibration are described
in [the runtime notes](sender_parity/runtime/README.md). These sender benches do
not validate the Yandex-to-AutoSDK field policies or establish vehicle behavior.

For this branch only original factory-APK execution supplies sender reference
values. Legacy `StockFeed`/converter/driver goldens and drive logs are not new-core
targets; their tests must be retired for a migrated path or rewritten against
new factory evidence. Adapter fields require fresh source/meaning/absence
evidence; experiments stay outside the factory core. The complete owner rules
are [recorded with the sender boundary](../../research/adas-bridge/README.md#oracle-and-migration-rules).

The accepted runtime path is explicit: `--observer-backend jvmti
--vehicle-provider virtual` with the pinned Frida-16 runtime configuration. For
an already prepared, exclusively owned emulator, use fresh output directories:

```sh
python3 -m tools.adas_bridge.sender_parity.phase2_run \
  --build captures/stock-map-beijing-pack/car-01 \
  --case captures/adas-bench/beijing/catalog-sel/cases/catalog-01-c019.json \
  --out captures/sender-parity-new-capture \
  --serial emulator-5614 --frida-port 27094 \
  --runtime-config captures/stock-sender-parity-20261025/phase2/runtime16-debug/runtime-config.json \
  --observer-backend jvmti --vehicle-provider virtual --callback-case arrival-idle
python3 -m tools.adas_bridge.sender_parity.phase2_bench run \
  --capture-manifest captures/sender-parity-new-capture/capture-manifest.json \
  --port captures/sender-parity-new-port/port.jsonl \
  --report captures/sender-parity-new-port/report.json \
  --build captures/sender-parity-new-port/classes
```

Use the configured runtime's Python environment for capture. Callback profiles
are immutable input fixtures: `rare-active`, `reset-offcourse`, `lane-query`,
`city-odd`, `route-change` and `arrival-idle`. Their actual original processing
receipts determine coverage. `route-change` needs the retained 160-sample reroute
case and two distinct actual SDK routes; an ordinary route or a same-ID reset
does not satisfy it. Defaults leave a drain tail after GNSS delivery (at least
540 seconds total for route changes, 240 for lane queries). Route-change uses
`--no-arrive`, with SDK stop gated on actual fixture processing; arrival-idle
allows 120 seconds for setup. An expired fixture or capture deadline
remains incomplete. Keep failed raw evidence and use a fresh directory for a
retry; never edit the reference to make comparison pass.

The existing integration bench below has a different input boundary:

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
