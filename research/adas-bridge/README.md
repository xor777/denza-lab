# Map → ADAS feed from Yandex (local prototype, 2026-10-23)

Can our app publish the navigation route and the rest of the map's feed to the
car's ADAS (Momenta, `getAdasType` 1) in place of the stock map
(`com.byd.launchermap`, Amap AutoSDK), with the route coming from Yandex
Navigator and the stock map left installed and unmodified? The initial firmware
feasibility study is now followed by an instrumented Navigator build, a host
converter and a local mock receiver. Conversion benches run on a disposable
emulator; car builds also collect passive evidence. Build 12 is installed. The parked car acknowledged two distinct Yandex routes
with explicit status 200, including cancellation and replacement within the same
armed session, then received dynamic packets. NOA operation is not established.
See [route replacement acceptance](#route-replacement-correction-build-12-2026-10-24).

## Current state

Updated 2026-10-24. Local parser acceptance is separate from vehicle acceptance.

| Claim | Status | Section |
| --- | --- | --- |
| The stock map publishes to ADAS through the same exported `SomeIpServerService` Binder that `:hud-link` already uses from an app UID; the IVI offers the services (vsomeip, `someip_stack.json`), and no layer checks the sender, the topic or the payload | firmware | [Transport](#transport) |
| An app UID can therefore fire every map→ADAS event (services 7, 43, 45 and `0x8202`) and receive ADAS's answers (services 13, 44, 10) through `SomeIpClientService`, which fans events out to every subscribed session | firmware | [Transport](#transport) |
| There is no exclusive IVI writer: offers are reference-counted per session and fires are unconditional; the ECU's arbitration between two publishers remains unknown | firmware; receiver policy open | [One writer](#one-writer) |
| The stock map, alive but not navigating, still fires position (pathId −1), TrafficInfo (roadClass −1) and NavigationStatus 100 about once a second; this is the main conflict with our feed | firmware + car logs | [Idle stock map](#what-the-idle-stock-map-still-sends) |
| While the stock map navigates, ADAS's path-selected answer for any other pathId makes it resend its own route and selection: no coexistence during stock guidance | firmware | [One writer](#one-writer) |
| No official third-party navigation provider path to ADAS exists: the ADAS feed lives inside the stock map; `byd_map_package` and `BydProjectionService` concern the cluster projection only | firmware | [Provider path](#no-official-provider-path) |
| The wire format is recoverable completely from the stock map (protobuf field numbers, the route JSON, chunking, the 1 Hz cadence, the handshake); what ADAS does with it is only partly known | firmware + car logs | [Contract](#contract) |
| Navigator 29.8.1 exports complete route geometry, nullable per-edge speed limits, manoeuvres, junction lane signs, traffic signals and live progress; inspected getters have no per-road class/count/access/direction contract | code; actual emulator captures | [Local prototype](#local-prototype-2026-10-23) |
| Native route archive expands SD geometry into the same public geometry; no additional road-class or lane-count field has been identified | code; byte evidence | [Native archive](#native-route-archive) |
| ~~Route conversion, byte roundtrip and a mock selection/progress lifecycle are implemented locally~~ superseded by the stock-equivalent bridge below | code | [Local prototype](#local-prototype-2026-10-23) |
| The stock route serializer roundtrips the car golden byte for byte, with Gson/protobuf and Python oracles. This does not establish LIVE equivalence: the build-12 drive has 5 Hz location bundles, graph-driven route replacement and WGS84 geometry rather than the measured stock SDK frame | code + golden + actual car egress; receiver requirements open | [NOA gap audit](#noa-gap-audit-of-the-build-12-trip-2026-10-24) |
| Installed build 12 obtained parked ACKs and 16 more retained route ACKs during the trip. Its only retained active CNOA pulse occurs about 77.5 s after its publisher closed; reliable Yandex-fed NOA is not established | live; bounded retained rings | [NOA gap audit](#noa-gap-audit-of-the-build-12-trip-2026-10-24) |
| The retained CNOA pulse lies on the last actually published route, not on a conflicting new Yandex polyline. At Kievskoye Highway the route nevertheless describes a divided multi-lane road as class/form/ownership unknown, two-way and zero-lane; matched positions coexist with unmatched dynamic markers. Raw GNSS is 7.094 m from the route centreline, while the associated-road position is on it; no camera/perception evidence establishes a vision rejection | offline egress/guidance join + current Yandex map/satellite; receiver cause open | [Episode geometry and road reality](#episode-geometry-and-road-reality-2026-10-24) |
| The stock's DNP configuration, switch and sleep predicates have exact device-1038 integer getter sources. A new bounded snapshot decoder validates their provenance and freshness; build 12 reads these values on the car and completed two stationary route selections. Remote SOME/IP availability is a separate kind-1 topic callback, not the local HAL flag | firmware bytes + host checks + build-12 parked acceptance | [Live publisher gate sources](#live-publisher-gate-sources-2026-10-24) |
| Yandex `Location.relativeTimestamp` uses native `CLOCK_MONOTONIC(1)` milliseconds, matching Android `uptimeMillis`, not `elapsedRealtime`. Build 7 mixed these clocks and rejected all 56 captured FINE fixes. Build 8 introduced the correct clock (retained in build 12) and preserves the 1 s / 2.5 s gates; its initial 43/43 and later drive's 1,362/1,362 FINE samples are fresh | native bytes + host tests + retained car guidance; NOA not established | [Location clock](#location-clock-domain-2026-10-24) |
| Build 6 was withdrawn after a CheckJNI SIGABRT in its offline-cache path callback. Build 7 removes it and passed a 115.5 s parked-guidance recording check with the same PID, 104 guidance samples and 3,468 passive packets; moving-trip behavior remains unverified and the separate probe below proves selection only | car failure + bounded car recorder check, 2026-10-24 | [Car trip recorder](#car-trip-recorder-2026-10-24) |
| A separate temporary APK sent one real Yandex route and path selection while parked: both fires returned 0; ADAS replied on `44/0x8002` with the same fresh pathId and an explicitly transmitted status 200, then `44/0x8001` with the same pathId and `routeEndIdx=858`. No PathMatch status or NOA distance was transmitted | physical car one-shot + independent presence-aware decode, 2026-10-24; selection protocol only | [Parked selection](#parked-route-selection-with-a-separate-probe-2026-10-24) |
| The parked probe's local read-only client successfully subscribed to all five required ADAS answer topics on services 13/44; the three optional IVI loopbacks on 7/`0x8202` returned unsupported in this session | physical car subscription receipt, 2026-10-24 | [Parked selection](#parked-route-selection-with-a-separate-probe-2026-10-24) |
| ADAS availability, NOA family and route ACK are separate. Candidate 9 requires an explicit same-path SELECTED status 200 from the current session; PATH_MATCH path presence no longer produces a matched indicator | firmware + passive car capture + host replay; installed build 12 has two explicit current-path ACKs | [Status observer](#adas-status-observer) |
| Historical, removed from the APK in build 5: with our OSM road store (`tools/adas_bridge/road_store.py`, from a `build_map.py` build) the bridge matches the Yandex route onto our network (adas-link `RoadMatcher`, HMM): links end at OSM junctions and attribute changes and carry the producers' class, formway, link type, LaneNum, direction, ownership, name, link speed and legal limit; 99.4–100 % of four recorded Moscow routes matched, 28–48 → 119–176 links per route; Java and the Python oracle agree to the byte | code + bench, 2026-10-24; car not run | [OSM roads](#osm-roads-under-the-yandex-route-2026-10-24) |
| Historical OSM build, superseded by build 5: inside Navigator on an emulator, with the store placed by `run-as`, both a home-region and a central Moscow route are 100 % matched (53 and 119 links), the dry-run traces pass `trace_check` and the overlay shows a fresh feed, a waiting route and no stock-map alarm; the bridge costs about 0.2 % of a core and 1 ms per second of the main thread, the overlay 6–7 ms; a store for Moscow plus about 50 km is 60 MB | emulator run, 2026-10-24; car not run | [Integrated run](#integrated-emulator-run-2026-10-24) |
| Build 5 removes the OSM runtime loader and regional store entirely. The embedded Yandex graph supplies observed topology; unknown class and continuous lane count remain unknown. The older OSM replay used category-based lane counts on 82 % of its ways | code + rootless emulator, 2026-10-24 | [Removing OSM](#removing-osm-review-2026-10-24) |
| Yandex's own online graph is accessible before and after decoding; routes and graph loads were observed in SPb, Kazan and Novosibirsk with no OSM store. Directed edges, geometry, names, limits and endpoint levels are recovered; class and continuous lane count are not | native byte evidence + embedded dry-run integration, 2026-10-24 | [Yandex-only extraction](#yandex-only-graph-extraction-2026-10-24) |
| Online vector tiles contain literal road `class`, `fc`, `oneway`, structures and HD markings. Native local geometry, three TileId contexts and the ellipsoidal WGS84 projection are recovered; eight further native-success tiles decode completely (17,895 features) | native bytes + captures + independent host review, 2026-10-24 | [Yandex-only extraction](#yandex-only-graph-extraction-2026-10-24) |
| A captured Kazan route has 98.73% sampled 2D vector coverage, including ambiguity (94.08% unique + 4.65% ambiguous). A host review table supports 582 canonical graph fragments; raw class/fc/oneway are not recoded, and build 5 does not consume this table | host evidence only; no runtime ADAS enrichment, 2026-10-24 | [Yandex-only extraction](#yandex-only-graph-extraction-2026-10-24) |
| Local vector runtime prepares the entire chosen route through a separate downloaded-map attribute source; SDK inventory/path, a read-only vmap3fb reader and standard Zstd replace automatic offscreen camera acquisition. The converter consumes a source-independent interval contract | compiled into signed build 13; host Java route replay passes; Android runtime not exercised | [Generic runtime and offline prerequisite](#generic-vector-runtime-and-offline-prerequisite-2026-10-24) |
| Offline maps remain mandatory, with the owner-requested minimal check: at least one usable installed region. Neither full-route coverage nor equality with a separate offline route is required for this iteration | source implementation; inventory presence is not a claim about route coverage | [Generic runtime and offline prerequisite](#generic-vector-runtime-and-offline-prerequisite-2026-10-24) |
| A downloaded Yandex Kazan `driving.fb` exposes 298,104 directed indices, 297,181 admitted base-edge shapes and 156,842 raw attribute entries. A bounded Java reader and whole-route association pass host tests on the actual package. Raw road/access enums remain unconverted; active class/direction acquisition uses vmap3fb literals | native bytes + host Java checks; regional reader not integrated into feed | [Regional offline graph](#regional-offline-graph-2026-10-24) |
| Post-drive read-only pull verified build 8 by APK SHA. The latest session has 1,362/1,362 FINE guidance samples at 2–364 ms; retained ADAS shows two ICC-active episodes and no service-44 packets. Raw status packets occupied 77.44% of the shared log, and the overlay listener closed during 26 active-guidance samples | physical retained logs + presence-aware offline replay; build 8 sent no Yandex route | [Post-drive review](#post-drive-read-only-review-2026-10-24) |
| Build 11 fixes dynamic legal-limit suppression and lane-sign loss, adds intersection/next/ahead-lane senders and preserves captured traffic context. Static route `speedLimit=0` remains factory-compatible | host tests + historical build-11 installation; retained in build 12 with parked dynamic sends | [Build 11](#build-11-completeness-and-car-test-2026-10-24) |
| Completeness inventory covers 73 snapshot fields, 67 values of five enum families and 20 factory message families; 60 decisions have source evidence and a next action. Unproved mappings and source limitations remain explicit | bounded source/capture audit; not a claim that all data is converted | [Completeness inventory](#build-11-completeness-and-car-test-2026-10-24) |
| Build 11 route and selection fires returned 0 with fresh hardware snapshots and actual remote-service availability; no matching service-44 ACK was received in the retained pre-departure window | historical build-11 parked car; superseded by build-12 ACKs below | [Build 11](#build-11-completeness-and-car-test-2026-10-24) |
| Normal route cancellation closed the whole build-11 arm; build 12 keeps a healthy armed session idle between routes and distinguishes an ACK wait from send silence | host regression + two physical same-arm route ACKs and dynamics | [Route replacement](#route-replacement-correction-build-12-2026-10-24) |
| Local candidate 13 refreshes same-route conditions, adds typed road facilities, preserves partial-source errors, latches terminal sender failures and clarifies the idle overlay | local host validation; not installed | [Local follow-up](#local-follow-up-candidate-13-2026-10-24) |
| Every value the bridge can send to ADAS has a contract row: provenance, mark against the stock, decision with evidence or the experiment that decides it, NOA risk (252 rows, 92 open; brought in line with the engine rewrite at `6f97e3bb`, which has not been run on the car) | Data, gated by tests | [Field contract](#field-contract-2026-10-24) |
| Historical first parked H0 obtained ACK 200 and PathMatch but its ODD request failed with NullPointerException. The owner confirms this car never activates or displays NOA while stationary: the proposed cluster-message criterion is inapplicable. The revised control compares internal ADAS replies against the actual stock map | physical car + owner-directed revised method, 2026-10-24 | [Parked experiment kit](#parked-experiment-kit-2026-10-24) |
| Corrected H0 ODD returned 0 and retained its 1,743-byte response as `odd_response` hex. Actual LISTEN recorded 1,800 pilot samples with zero publisher calls; no stock-route ACK/PathMatch was received, so the paired guidance control is not established. Stock was disabled again and the probe removed | physical H0/LISTEN + 34 Python tests / 127 core checks, 2026-10-24 | [Parked experiment kit](#parked-experiment-kit-2026-10-24) |
| The live engine no longer dies on a reroute, pauses instead of going silent on a prerequisite gap, keeps one path per Yandex route while its offline admission remains valid (proof withdrawal requires a fresh path), sends positions at 1 Hz with their own indices, ends every session with the stock's statuses, resumes after a Navigator restart and keeps evidence for a two-hour drive; not yet run on the car | code + host harnesses, 2026-10-24 | [Engine follow-up](#engine-follow-up-2026-10-24) |
| Verdict: feasible with conditions — the transport is open; the blockers are the idle stock map's competing 1 Hz feed, the unknown receiver semantics (coordinate frame, link IDs, path-match) and safety validation | assessment | [Verdict](#verdict-and-plan) |

Contents: [Build-12 NOA gap audit](#noa-gap-audit-of-the-build-12-trip-2026-10-24) · [Local follow-up](#local-follow-up-candidate-13-2026-10-24) · [Transport](#transport) · [One writer](#one-writer) ·
[Idle stock map](#what-the-idle-stock-map-still-sends) ·
[Provider path](#no-official-provider-path) · [Contract](#contract) ·
[Yandex data](#what-yandex-gives-us) · [Converter](#the-converter) ·
[Local prototype](#local-prototype-2026-10-23) ·
[Stock-equivalent bridge](#stock-equivalent-bridge-adas-link-2026-10-23) · [Guarded live candidate](#guarded-live-candidate-2026-10-24) · [Live gate sources](#live-publisher-gate-sources-2026-10-24) · [OSM roads](#osm-roads-under-the-yandex-route-2026-10-24) · [Removing OSM](#removing-osm-review-2026-10-24) · [Yandex-only extraction](#yandex-only-graph-extraction-2026-10-24) · [Regional offline graph](#regional-offline-graph-2026-10-24) · [Location clock](#location-clock-domain-2026-10-24) · [Native archive](#native-route-archive) ·
[ADAS status observer](#adas-status-observer) ·
[Car trip recorder](#car-trip-recorder-2026-10-24) ·
[Parked selection](#parked-route-selection-with-a-separate-probe-2026-10-24) ·
[Post-drive review](#post-drive-read-only-review-2026-10-24) ·
[Pre-test review](#pre-test-firmware-review-2026-10-24) ·
[Build 11 and completeness](#build-11-completeness-and-car-test-2026-10-24) ·
[Route replacement](#route-replacement-correction-build-12-2026-10-24) ·
[Safety](#safety) · [Verdict and plan](#verdict-and-plan) ·
[Evidence](#evidence-and-reproduction)

Paths below: `LM` = `captures/hud-firmware-20260923/jadx/BydLaunchermap/sources`,
`SIP` = `captures/hud-firmware-20260923/jadx/SomeIpService/sources`,
`YN` = `captures/firmware/yandexnavi-29.8.1-3b0dec32` (all git-ignored).

## NOA gap audit of the build-12 trip (2026-10-24)

The owner reported immediate degradation, probably from CNOA, on the last trip.
A read-only pull through the existing ADB connection verified the installed APK
SHA-256 `b0dbe08bee205de965c77b38faaac0e719013ae56166265c76dee3cfe5a284eb`
before and after copying its retained rings. The installed source archive, not
the newer working tree, defines build-12 behaviour. No app, vehicle setting,
publisher arm, package state or tunnel was changed by this audit.

The compact [audit index](noa-gap-audit-20261024.json) links the source pins,
measurements and limitations. Full private evidence and rerunnable analysis
are in `captures/noa-bridge-audit-20261024/`.

**The retained activation does not overlap a running publisher.** The last
successful map send was at elapsed `78706036`; the owned publisher closed at
`78706341`, with a stopped record at `78706490`. The only retained active DNP
pulse is `78784035–78784509` (about half a second, city mode), followed by ICC.
It starts about 77.5 s after the stopped record. Earlier raw status packets
have rotated out, so this does not exclude an earlier unrecorded episode.
The pulse cannot validate continuous Yandex feeding; cached ECU route state is
also not ruled out. All 16 full routes retained in the live egress journal
received real status-200 selection ACKs. Delivery, selection, map matching and
NOA eligibility remain distinct claims.

**Confirmed differences from the stock producer:**

| Boundary | Actual build-12 evidence | What remains unproved |
| --- | --- | --- |
| Coordinate frame | LIVE declares WGS84 at the same `mapProvider=1` used by Amap. Across 3,961 positions, the measured stock SDK transform differs by 537–542 m. All 526 points of the last exported route equal its source WGS84 points on the stock grid; none equals the transformed source | The external receiver's datum/inverse. This is a producer-contract difference, not proof that changing frames alone enables NOA |
| Road semantics | All 929 link instances in 16 retained route publications have `roadclass=-1`, `formway=-1`, `laneNum=0`, `ownership=-1`, `adminCode=0`, `direction=0`, and all three fork/parallel flags false | Which attributes the ECU requires. In particular, stock direction 0 represents two-way, so this default does not communicate an unknown one-way status |
| Route continuity | One unchanged Yandex route ID became 15 different ADAS paths over 601 s as graph coverage changed: 31→37→38→53→58→65→67→69→72→70→66→68→67→57→51 links. Each replacement waited 1,064–1,399 ms for its first new-path position, despite ACKs after 162–262 ms | Whether any particular pause caused a downgrade. These are graph-driven replacements, not 15 observed Yandex reroutes |
| Dynamic coherence | Position, TrafficInfo and Region run at median 200 ms; NaviInfo and status run near 1,000 ms. The code bypasses the stock throttle in LIVE. Geometry versus transmitted remaining link distance differs by up to 36.57 m; 16 positions are over 10 m outside their declared link, up to 22.98 m | The receiver's permitted skew and cadence. The dry-run bench exercises the throttled branch |
| Independent inputs | All 3,961 `vehicleSpeed` values equal `GPSspeed`: GNSS fills both, whereas stock reads vehicle and GNSS speed independently. Region is 0/1/0/0; the old successful OSM run used 990001/1/990001/990001 | ECU checks for these inputs and the administrative namespace. Country value 1 must not be reinterpreted as an ISO country code |

There is also a separate **43.972 s position gap** at elapsed
`78019984–78063956`. The journal records prerequisite invalidation and later
publication recovery in the same session/epoch. All 43 retained guidance
samples inside the gap remain FINE on the same Yandex route. This is not the
ordinary one-second new-path delay and is not evidence of GNSS loss. The
installed build 12 can invalidate a path without latching the session failure;
the local candidate-13 latch addresses that recovery behaviour, but does not
identify or eliminate the initial lost prerequisite.

**Why Beijing did not close these gaps.** `bridge_trace.py synth` starts with
already-SDK geometry and labels the identity path WGS84; its own header excludes
the WGS84→SDK conversion. The stock observer injects already-encrypted GNSS.
The bench supplies a scripted selection response, contains no Momenta receiver,
uses a fixed graph and runs the dry-run branch. It establishes useful stock
producer behaviour, but cannot establish receiver eligibility or the LIVE
differences above.

**The terminal reason is also lost.** In the installed build-12 source,
`LiveMapBridge.stop` sets `finished=true` before `feed.close`. The close path
can call readiness again, which then overwrites the shared reason with
«Связь сеанса потеряна». That text does not identify the initial failed
prerequisite and does not prove an ECU refusal or lost Binder. Fresh FINE
guidance continues through closure. The final retained reader snapshot is later
than closure and cannot identify the triggering prerequisite either.

**Controls against attractive single-field explanations.** The old stock/OSM
HNOA episode really lasts 54.103 s; its 27 retained positions carry class 6,
history speed 85, legal limit 0 and public ownership. The package-a→b change
was not an isolated class experiment: other classes, lane defaults and several
lane experiments changed together. Formway 1 already existed on seven of eight
fragments of the active road before the successful package. No full static
route body from that successful episode was retained. Neither a nonzero legal
limit nor a newly added divider is established as the missing universal gate.
The stock ODD request retrieves a region list; the successful OSM drive also
sent maximum no-ODD distance. The DNP switch request belongs to a user action,
not a missing periodic handshake to replay blindly.

**A second stock channel needs tracing.** Stock navigation also writes
`SETTING_MPC_IFC_NAVI_ST_SET` (`0x4CA0004B`) through `setNaviSt`, and writes
instrument navigation state (`0x43E0003A`) through its CAN guidance path.
These are outside the map SOME/IP feed and are not reproduced by the bridge.
The MPC write is source-proved (GPS navigation maps to state 2, Cruise to 1,
stop/destroy to 0); its setter log is absent from the retained successful
drives, so the actual historical MPC value is not established.
The real successful stock drive logs its own `sendNavigateInfoToCAN` fallback;
the alternative service-presence test checks `com.byd.amapservice`, not the
separately installed `com.example.amapservice`. Do not attribute that drive's
writes to the latter service. The instrument names and “independent CAN” log
support an instrument role; an ADAS consumer or gate has not been found.
This is a concrete full-lifecycle difference and a property hypothesis to
trace, not authorization or evidence for setting either value to force NOA.

The next investigation should first retain the *initial* terminal reason and
an event timeline that outlives routine packets; keep a published route table
stable until a genuine route change; and replay the actual LIVE branch with
stock cadence, independent speeds and coherent indices. Then compare the same
source route/poses through the original stock exporter to settle the producer
frame, and recover source-supported class/form, direction, continuous lanes
and fork semantics. Coordinate, runtime and semantic changes need separate
controls; filling every unknown with class 6 or a lane count would prevent a
causal conclusion. Candidate 13's facilities/conditions work does not establish
these conditions or a NOA fix.

### Episode geometry and road reality (2026-10-24)

A separate read-only join checks the retained CNOA pulse against the route that
was actually published, the parallel dry reconstruction and Yandex guidance.
Reproduce with `python captures/noa-bridge-audit-20261024/reports/noa_episode_geometry.py`;
its output is `noa-episode-geometry.json` beside the script. DRY packets below
were not sent to the car.

- At elapsed `78784035`, guidance `(37.14713263857925,55.5558947378885)` is
  0.0011 m from the polyline of the last actual LIVE path `1792838197`
  (526 points, published at `78701299`). This is source-grid equality, not a
  receiver localization measurement, but it rules out a conflicting new Yandex
  polyline as the explanation of this pulse. The parallel DRY path `100022`
  also fits the point and was never published to ADAS.
- The nearest DRY position separates raw GNSS from its associated-road point by
  18.746 m in 2D. Raw GNSS is 7.094 m from the LIVE route centreline; the
  associated-road point is 0.006 m from it. On a multi-lane carriageway the raw
  lateral offset is not by itself a map/perception contradiction. No camera
  frames or lane/perception state were retained, so a vision rejection remains
  untested rather than established.
- The current Yandex map and satellite view identify this as Kievskoye Highway
  E101, with separated directional carriageways, several lanes and adjacent
  merge/exit ramps. The actual LIVE link at the point instead carries
  `roadclass=-1`, `formway=-1`, `ownership=-1`, `direction=0` (stock two-way),
  `laneNum=0` and false fork/parallel fields. This is a concrete road-model
  mismatch, not proof of the ECU's exact eligibility predicate. Throughout the
  actual LIVE feed, path IDs and route progress also coexist with dynamic
  `currentRoadOwnership=-1` and `TrafficInfo.roadClass=-1`, the stock producer's
  unmatched markers; that internal semantic contradiction remains a leading
  producer defect.
- In every one of the four unique retained PathMatch responses,
  `routeEndIdx` equals the last serialized point index (646/646, 637/637,
  635/635 and 525/525). This is numerical consistency, not proof of full
  geographic matching. It does not support a route-truncation theory. Absent
  `totalNOADistance`, `routeBeginIdx`, item `statusCode` and outer `code` still
  have no established zero/success/failure mapping and no positive stock control.

The retained episode therefore does not justify another build-12 field-swap
drive. Establish a stable, journalled LIVE control first; then isolate frame,
dynamic matched semantics and static road attributes as separate variables.
The existing parked H0/H1/H2/H6 harness can screen those variables, but its
parked failure wording is not proof of moving NOA eligibility.

## Local follow-up candidate 13 (2026-10-24)

This increment was developed locally after the build-12 trip. No vehicle or
emulator was accessed during the build below, and no repository content was pushed.
The early-candidate changes listed here precede the [engine follow-up](#engine-follow-up-2026-10-24),
which supersedes their pause/terminal-failure and end-status descriptions.

The latest owner-requested build includes that engine follow-up and the whole-route
offline attribute provider: `29.8.1-YandexHUD.13`, ARM64, 220947134 bytes, SHA-256
`acc7054011849cbdd3c743133638c9776f96841af3bf39c991308dd40f263b86`.
The separate parked-player APK is also built and signed, SHA-256
`222acb0567923d0c4f60a4fc1b1301d05d7431a38066564f0bccc3be98320d8a`.
Both are local and uninstalled. Receipts, the exact source snapshot and successful
builder log are in `captures/yandex-feed13-fullroute-20261024/`; identify this
candidate by hash because the earlier local build below has the same version number.

- **C01/C44 conditions:** route copies now continue after the initial copy and
  10-second follow-up, bounded to one subsequent attempt per 30 seconds. Capture
  generation/revision/time and the SDK outdated flag are recorded separately;
  polling time is not presented as server-update time. Superseded queued copies
  cannot resurrect a replaced or stopped route. Dynamic-only conditions replace
  the current view while the published link table, path ID and ACK stay fixed.
  A changed link boundary or static attribute still requires a new publication.
  In particular, adding a limit boundary or lane-sign position can change that table.
- **C60 lane timestamp:** a NextLanes timestamp is latched for the displayed sign
  content and position; repeated queries and equal replacement wrappers keep it.
- **C12/C13 road facilities:** `0x8202/0x8005` now has the exact factory protobuf
  schema and sender. Pedestrian crossings map to 31, railway crossings to generic
  8, checkpoints to 91, SCHOOL to 17 and DANGER to 30. Railway UNKNOWN/REGULAR
  identifies a crossing family, not the presence of gates. No 51/52 subtype is
  invented. Multi-tag events retain every tag. Speed bumps, fords, rugged surfaces
  and restrictions retain raw records and an explicit unresolved mapping decision;
  the two RuggedRoad surface flags are now captured too.
- Facilities are selected by route progress within an explicit **600 m adapter
  window**, with stable source order for coincident objects. This window is not a
  recovered AutoSDK threshold. Coordinates are estimates interpolated from the
  source route; original event coordinates remain in the raw record. Distance on
  the wire is integer straight-line metres, matching the factory producer's
  last-location branch. Changed messages are sent at most once per second and
  retried after a failed attempt. Disappearance sends factory clear `0a00`.
  Leaving the route clears the shown object; held off-route fixes cannot resurrect
  it, and a queued pre-reroute progress update cannot grant fresh placement.
  A guarded LIVE stop still permits only status 100; the next accepted route
  overwrites/clears facilities on its first fresh fix rather than bypassing the guard.
- **C45/C55 partial data:** malformed optional camera/restriction rows no longer
  reject the entire route. Their raw rows, source availability/getter errors and
  local conversion decisions remain distinguishable. This is a targeted repair,
  not a claim that every optional array is now tolerant.
- The sender now latches observed prerequisite/actual-send failures for the owned
  live session. A quick recovery cannot silently resume that arm. Old callbacks
  cannot close a replacement session. Initial waiting, a stale FINE fix and a
  foreign ACK are separate from terminal send failures.
- An idle, ready overlay says “Ожидаем запуск маршрута / Нажмите «Поехали» в Яндексе”.
  Transport preparation still displays its actual waiting reason.

Evidence and focused test receipts: `captures/yandex-followup-20261024/`.
`facilities-proof.json` pins the source code and retains 50 original factory
payloads; `facilities-fixtures.json` clearly separates real observations from
synthetic serializer tests. The earlier build13-stable passed all builder checks: 162 facilities, 52 conditions,
86 snapshot, 391 guard, 130 production runtime and 398 overlay assertions; the
50 original facility payloads matched byte for byte. The independent Python
bridge suite passed 47 tests. APK `29.8.1-YandexHUD.13` SHA256
`9cdd001a66ef891dcd0f711502b874f8894715c99c446e18b20490481a219c7a`.
Signed APK, source archive and manifest are pinned by
`captures/yandex-followup-20261024/candidate13.json`. The first build attempt was
rejected by the source-hash guard while review fixes were still changing files;
only the subsequent stable-source build is retained as a candidate. This increment does not establish any sufficient condition for NOA.

Remaining work includes the complete camera/event subtype mapping, traffic-light
identity and distance, road class/access/direction, continuous lane count and
vertical matching. Their original inventory decisions remain open.

## Route replacement correction (build 12, 2026-10-24)

The user's pre-departure test exposed a build-11 lifecycle defect: cancelling the
prepared route produced `navStop` at elapsed 77015459 and closed the entire live
session at 77015863. The same arm could no longer transmit a subsequent route.
The initial overlay also called the route-ACK wait “Яндекс молчит” solely because
no dynamic packets were sent. Those are two separate defects; neither proves an
ADAS eligibility or map-attribute rejection. Raw evidence and diagnosis:
`captures/yandex-feed11-20261024/user-route-issue/` and
`captures/yandex-feed12-20261024/diagnosis.json`.

Build 12 separates navigation lifecycle from the armed session. A healthy normal
stop clears route ownership, ACK and FINE eligibility, allows one bounded stop
status only if a route was actually sent, then waits for the next route. Cancel
before the first chunk goes idle without sending a fabricated stop. A replacement
gets a fresh persisted path ID, full route/selection publication and its own ACK;
old ACKs/fixes/queued stop callbacks cannot authorize or close it. Session expiry,
explicit disarm and coordinator-observed transport/gate failure close the session.
A later review found a transient failure could recover before the coordinator tick;
the local build-13 correction below closes that gap. This correction is not yet installed.
The same live receiver therefore records responses across ordinary route changes.

The overlay now receives explicit producer state and the guard's confirmed path:
waiting for ACK, a stopped session and a runtime error have different messages.
It judges send silence only after actual confirmation of the current route.
The timestamp-free host harness now injects a coherent wall clock alongside its
simulated monotonic clock, removing real-host timing noise from freshness checks;
production continues to use the system clocks.

As a separate compatibility experiment, live wire IDs use fresh positive signed
32-bit values in persisted `lastCompatible32`, seeded by epoch seconds. The old
wide-ID history is retained separately. Every new ID is committed before sending;
clock/storage/range failures reject publication. The prior parked probe accepted
an ID in this range, while build 11's wide-ID routes received no matching callback.
This does not yet prove an ADS integer-width limit; selection requires the actual
same-path status-200 response regardless of ID range.

Build-12 local checks: 281 guard assertions, 44 production-runtime harness checks,
396 overlay assertions, plus the existing builder suites. Installed APK SHA-256:
`b0dbe08bee205de965c77b38faaac0e719013ae56166265c76dee3cfe5a284eb`.

**Physical acceptance:** path `1792837088` received actual same-path status 200.
The app cancelled guidance (`navStop` 77620646), became idle without closing the
arm (77620666), then started another route. Path `1792837172` also received actual
same-path status 200 in the same consumer/arm. The retained window contains 1,576
successful actual packet sends, including position and dynamic data. This proves
parked route publication/cancellation/replacement, not missed-turn rerouting while
driving, complete lane semantics or NOA activation. Positive 31-bit IDs worked;
the exact cause of build-11 silence still needs an isolated payload experiment.

The current two-hour trip ledger is
`captures/yandex-feed12-20261024/session1.rollback.json`. Build-11 session 4 was
restored before installation. Autonomous recording does not require ADB.
After the trip retain the rings, then run `live_session.py restore` with this
ledger; timeout alone does not re-enable the stock map. Receipt:
`captures/yandex-feed12-20261024/physical-acceptance.json`.
The idle-preview overlay still pairs waiting for transmission with transmission
ready. This is misleading wording, not a send failure. The pending correction is
waiting for navigation start. No further APK was installed before departure.

## Build 11: completeness and car test (2026-10-24)

Historical installation, replaced by build 12: `29.8.1-YandexHUD.11`, code `739494311`, APK SHA-256
`78451b6dd27725a6bf30d27470c928bcbaf59aeb478b90fb552f4bebd47ccf91`.
The installed bytes match the signed artifact. No OSM data or runtime loader
is included. Evidence, source archive, tests and rollback ledgers live under
`captures/yandex-feed11-20261024/`; `candidate11.json` pins the retained files.

**Implemented:** dynamic legal limits use valid guidance, then the aligned route
edge, then the observed graph, retaining provenance and clearing disappeared
values. Static route speedLimit stays zero. Lane signs preserve all 14 direction
and six kind values, positions, order and highlights. Intersection,
NextIntersectionLanes and up to six signs within 3 km now have senders, expiry,
clear and same-path update behavior. Generic turns/other lane kinds have explicit
projection losses; a sign is not proof of continuous physical lane count. Two
retained car routes preserve 12/12 signs, 30 lanes and 13 highlighted directions.
Traffic context preserves all 1,307 captured jam segments, raw types, speeds,
units and offsets. Its source age stays unproved when no source timestamp exists;
receipt time or a graph refresh cannot make traffic fresh. Jam/flow wire conversion
is still an explicit open decision, not silently filled with invented values.

The final combined builder passed its checks, including 33,025 lane, 103 legal
limit, 2,650 traffic-context, 1,129 base ADAS, runtime/client Binder harness and
independent Python wire-equivalence checks. These establish local behavior and
factory-byte correspondence, not receiver acceptance.

**Completeness inventory:** [the owned JSON](data-completeness-20261024.json)
contains 60 decisions: 30 observed losses, 21 unproved mappings, four factory
values, two missing proven sources and three integration policies. The original
59-item audit pins historical source hashes; the final-build delta records what
build 11 closed, and C60 records the remaining lane timestamp cadence mismatch.
Coverage is 38 RouteSnapshot + 26 GuidanceSnapshot + 9 TrafficSignalSnapshot
fields, 67 enum values and 16 emitted topics against 20 factory message families.
There are zero unclassified entries inside that inspected contract; this does
not claim every private Yandex interface has been recovered. Each record gives
source availability, current treatment, decision and next verification.

The next implementation order is conditions revisions/partial-input handling;
typed events, cameras and road facilities; signal identity/distance and timestamp
semantics; then native road class, access, direction, continuous lanes and
vertical graph matching. Raw source facts remain retained when the receiving
namespace is unproved. No missing fact is turned into an eligibility claim.

**Car test:** the original stock package state 0 is saved in each rollback ledger.
Only this session disables that package (state 3) and proves its processes absent.
The first attempted arm failed before publication because an exec-out stdin pipe
did not close; the atomic writer now uses a bounded base64 command and has a
regression test. A second session sent route/selection but stopped on snapshot
expiry. The shell reader's ten timed process waits added about one second per
batch; bounded 5 ms completion polling reduced a measured batch to 145 ms.
The same 1 s command timeout and 2.5 s freshness limit remain. The reader also
samples its clock after reading a consumer heartbeat to avoid a false future
heartbeat. Sessions 1–3 were restored through quiescence and exact package-state
restoration before the next arm.

Historical session 4 (restored before build 12) was armed while the car still reported parked and speed zero, for
7,200,000 ms. Its ledger is `session4.rollback.json`; the pre-departure state is
`departure-state.json`. Route and selection reached the actual Binder transport
with result 0; remote-service readiness and periodic service-13 packets are
observed. **No matching service-44 selection ACK has been received in that
window, so dynamic packets are blocked and NOA is unverified.** This is a real
route-delivery experiment, not proof that lane/limit messages reached ADAS.

The user was released for an ordinary trip with their own Yandex route. No
installation, app restart or re-arm is to be performed while moving. The
on-device arm does not depend on the host ADB connection, but expires on timeout,
process restart or loss of prerequisites. Expiry stops publication; it does not
restore the stock package. After the test, use `live_session.py restore` with the
session-4 ledger: stop the owned publisher/reader, prove quiescence, restore state
0. Never manually enable the stock writer while the custom writer is active.
The live audit is capped at two 8 MiB files; callback/progress rings have separate
budgets. After the trip, pull all retained rings before changing the session.

## Guarded live candidate (2026-10-24)

**Build 12 is installed and uses this guarded channel. Candidates 9 and 10
were local predecessors; build 8 is retained as a rollback APK.** The
`LiveMapBridge` consumes the same Yandex route and observed graph as the dry run,
with no OSM source. It owns separate SOME/IP client/server sessions. It offers
only the four map services and sends only map-event topics; it has no vehicle
switch setter or pilot-control request. Unresolved native graph class, continuous
lane count and access-mask enums are not promoted into mapped attributes.
The installed historical build 12 used a highway/posted-limit class-0/6 rule,
divided/one-way/public assumptions and an endpoint sign's width as lane count.
**Removed from local source:** those inputs do not establish these continuous
road attributes. Highway ranges and lane signs still supply cuts, and actual
junction signs remain point-scoped guidance. The roundabout form/direction rule
and independently supplied attributes remain; a known class 0/6 is allowed when
its source actually supplies it. This source correction has not been built or
run on the car.

The real channel is opened by an explicit host arm for one consumer-process
UUID, reader UUID and boot identity. All three participants derive the boot
identity from the public Android `Settings.Global.BOOT_COUNT`, using Java
`UUID.nameUUIDFromBytes(UTF8("denza-adas-boot-count-v1:" + canonicalDecimal))`.
It is a per-device boot counter, not the kernel's `boot_id` or a globally unique
vehicle identity; the random consumer UUID and explicit ADB serial provide the
other scopes. Missing values have no default. The arm expires within four hours
and cannot survive a Navigator process restart.

`tools/adas_bridge/AdasGateReader.java` runs under shell UID and reads only the
fixed integer property list, once per second. It stops on its bounded lifetime,
lost consumer heartbeat or matching reader-stop marker. `AdasGateSnapshot`
validates the exact reader, boot, sequence, oldest read time and getter type;
all required values must be known. `LiveMapBridge` also requires the stock
package to remain disabled for the user and a fresh independent process-absence
snapshot. A full Navigator name in `ps -A -w -o NAME` is required as evidence
that the process-name column was not truncated.

Every actual send rechecks these inputs, local HAL readiness and both remote
ADAS service-availability callbacks. Unavailable→available transitions change
the transport epoch even when they occur between worker checks. A new epoch
invalidates the old path and ACK. The transport repeats the nonblocking lease
check after parcel construction and immediately before its event transaction.
The native initial-availability path is byte-verified: subscribe replays an
already-available service. A fresh handle must start its client before it can
subscribe, so setup starts clients before the availability subscriptions.
Build 11 received actual service-availability callbacks and periodic service-13
packets while parked. Service-44 route acceptance is still pending; an absent
callback is not assumed available or acknowledged.

`StockFeed` keeps its historical dry-run behavior for the byte oracle, but its
LIVE mode requires `LiveEgressGuard` and a persisted fresh wire ID. It does not
select a route until every chunk actually returned success. `RealPathAck`
requires the current session, the same path ID, explicitly present status 200,
and valid request/callback timing. It never treats the periodic service-13
pilot status or a successful Binder call as a route ACK. Only then may dynamic
packets pass, with the original FINE timestamp no older than one second.
Stationary callbacks cannot indefinitely repeat a held position. The overlay
also preserves field presence: a missing SELECTED status stays waiting; an
explicit non-200 response is a refusal. Old-session receipts cannot light the
current route. PATH_MATCH retains its raw outer/item codes, range and distance,
but currently never lights a matched indicator: no success-code interpretation
has been proved for that separate message. Its path ID alone is insufficient.
Navigation stop admits at most one bounded status-100 packet, then closes the
owned server.
Gate loss, source watchdog, transport loss or disarm closes the session; the
same arm cannot automatically reopen it.

The host tool [live_session.py](../../tools/adas_bridge/live_session.py) records
the original stock package state before changing it, verifies stationary
confirmation plus the tested park predicate and zero measured speed, disables
and force-stops the stock map, starts the bounded reader, then writes the arm.
Restore removes the arm first and waits for two fresh quiescent runtime states
before restoring the original package state. Missing closure proof leaves an
explicit pending restore, never a success report. Disabling the stock package
also removes its own map tile, map scene and voice navigation for the session.
The candidate has not proved that a vendor controller cannot restart a disabled
package; the process witness is checked continuously for that reason.

Validation is layered: original serializer/graph goldens, adversarial pure-Java
ACK/producer/egress/gate tests, and a 34-check harness executing the production
`LiveMapBridge` against a fake Android clock, filesystem and Binder boundary.
The latter covers route→selection→ACK→position, no held-fix repeat, missing gate
data, remote epoch loss, disarm before a queued send, failed sends and failed
ID persistence, plus recording actual match/pilot/availability callbacks before
any selection ACK. A separate 249-check harness executes the production
`AdasPassiveClient` with typed fake Binder/Parcel boundaries, including fresh
start ordering, availability replay, failed setup and owned cleanup. The
overlay has 383 checks, including the real parked reply and session changes.
Actual Android/HIDL delivery is source- and byte-reviewed, not physically
proved by either harness. The earlier parked one-shot remains the only car route-send
proof; continuous feeding, guidance and NOA acceptance require the next parked
check and subsequent observed trip.

The final APK and source archive are pinned by
`captures/yandex-drive-review-20261024/candidate10.json`; its plan pins the
candidate APK, shell-reader JAR and two-hour session lifetime. The new manifest
query for `com.byd.launchermap` makes its enabled state visible to the APK;
this adds no permission.

Evidence owners: `captures/yandex-drive-review-20261024/`,
`captures/yandex-live-runtime-20261024/`,
`captures/yandex-real-feedback-20261024/producer/`,
`captures/yandex-live-readiness-20261024/{guard,independent-audit}/`,
`captures/yandex-live-gates-20261024/`, and
`captures/yandex-live-session-20261024/`. These are local, untracked artifacts.

## Transport

**Java → Binder → HAL → vsomeip.** The stock map's
`com.autosdk.bussiness.pilot.someip.SomeIpServer` wraps
`ts.car.someip.sdk.SomeIpServerProxy`, which binds
`com.ts.car.someip.service/.manager.SomeIpServerService` with the caller's own
package as the intent type (`LM/ts/car/someip/sdk/SomeIpServerProxyImpl.java:241-247`).
The service forwards `startSomeIpService` / `fireEvent` to one HIDL client of
`vendor.ts.someip@1.0` (`SIP/.../SomeIpServerService.java:463`, `:551`), and
the routing manager `someipd-routing` offers the services on `192.168.195.2`
with SOME/IP-SD on `224.0.2.4:30490`
(`captures/hud-firmware-20260923/system/system/etc/someip/someip_stack.json`).

**Topic layout.** A 64-bit topic is `kind(4 bits) | service(16) | instance(16) | id(16)`:
`0x4…` an event, `0xB…` a service, `0x1…` service available, `0x2…` a method.
`0x4002B002B8002` = service 43, instance 43, event `0x8002`.

**Who offers what.** The IVI is the server of every map→ADAS service; ADAS
(Momenta) is the server of the replies. All map events are plain events
(`is_field=false`), so a subscriber gets no cached last value.

| Direction | Service (app in config) | Events used by the stock map |
| --- | --- | --- |
| IVI → ADAS | 7 `NavigationStatus_LinkInfoService_server` (UDP 51221) | `0x8001` NavigationStatus (100/101/104/105/106/110), `0x8003` TrafficInfo, `0x8006` generalNavigation (interval camera, `newNoODDRegionDist`) |
| IVI → ADAS | 43 `NavigationSDLink2Service_server` (TCP 51224) | `0x8002` naviSDRoute — the route JSON |
| IVI → ADAS | 45 `NaviPathUserSelectStsService_server` (UDP 52105) | `0x8001` sendPathSelected (`pathIDReq`) |
| IVI → ADAS | `0x8202` `SDMapInformService_Server` (UDP 52103) | `0x8001` light, `…02` incidents, `…03` service area/toll, `…04` tunnel, `…05` road facilities, `…06` intersection, `…07` tidal lane/ramp, `…08` vehicle location, `…09` region, `…0A` speed/intersection (`historySpeed`), `…0B` next action + camera, `…0C` next-intersection lanes, `…0D` mixed fork, `…0E` lanes 3 km ahead |
| ADAS → IVI | 44 PathMatchStatus | `0x8001` PathMatch, `0x8002` user-select confirm (`MMTPathSelected`), P2P match |
| ADAS → IVI | 13 PilotStatus_AlarmInfo | `0x8002` pilot status (DNP/ICC/ACC), `0x8003` alarms, `0x8005` NOA family; methods `0x0001` DNP/ICC switch request, `0x0002` ODD region codes |
| ADAS → IVI | 10 VehiclePositionInfo, 14 PlanningLine, 12 Obstacle/LaneLine | not needed for the feed |

Topic constants: `LM/com/autosdk/bussiness/pilot/someip/SomeIpId.java`;
senders: `LM/k/h/a0/c/c/a/f0.java` (`SomeIPMatrixManager`), route and
selection `LM/k/h/e0/c/j/e0.java:140-148` (`SendSomeIpDataManager`). The full
table is pinned in [`someip-map-adas-topics.json`](someip-map-adas-topics.json).

**No whitelist anywhere on the IVI.** `SomeIpServerService` is exported
without a permission and its `startSomeIpService` / `fireEvent` check neither
the caller, the topic nor the size (also recorded in the HUD findings); the
session key is the caller-supplied intent type (`onBind`, `:135-137`).
`someip_stack.json` has no vsomeip `security` / `policies` section, so the
routing manager accepts any local application. SELinux is no obstacle in
practice: `:hud-link` already starts service 266 and fires on it from the
Denza Apps and Navigator UIDs on this car. The ADAS ECU cannot tell who on the
IVI sent an event: the offer is the IVI's.

**Our path is the same daemon.** `HudSomeIp` (`TX_START` 4, `TX_FIRE` 6) is
the same interface the stock map uses; publishing to ADAS is the same calls
with services `0xB000700070000`, `0xB002B002B0000`, `0xB002D002D0000`,
`0xB820282020000`. A shell-UID helper is not needed.

**Receiving ADAS's answers.** `SomeIpClientService` (`SIP/.../SomeIpClientService.java`)
reference-counts `startClient` and `subscribe` per session and delivers every
event to each subscribed session (`notifySomeIpEvent`, `:235`), so we can read
PathMatch, MMTPathSelected and pilot status alongside the stock map without
taking them from it. A third-party HUD app ships a read-only diagnostic that
subscribes as a client to the IVI's own map services (7, 43, 45, `0x8202`,
266). The HAL's own `libsomeipimpl.so` carries the client classes for the map's
services (`CNavigationStatus_LinkInfoServiceClient`, `CSDMapInformServiceClient`)
and the stack config has no security section, so a local client can subscribe to
the events the stock map offers: a passive probe can record the idle stock map's
feed, and the ADAS overlay (`experiments/yandex-hud-module`, `AdasPassiveClient`)
does so read-only for services 7 and `0x8202`. Live receipt of both writers'
bytes on the car is still to be shown.

## One writer

- **No exclusive ownership.** Offers are counted per session; the HAL offer
  ends only when the last session stops (`SomeIpServerService` `stopSomeIpService`,
  `onUnbind`). Neither app can withdraw the other's offer; fires from either
  go out through the one HAL client.
- **Idle stock map vs. our route:** both streams reach ADAS interleaved — see
  the next section. Whether ADAS ignores `pathId −1` positions while a route is
  selected is unknown and is the first question for a live probe.
- **Stock map navigating:** coexistence is impossible. `DriveRouteManager.g()`
  (`LM/k/h/e0/c/g.java:135`) treats an `MMTPathSelected` for any pathId other
  than its own as a failure and calls `onSelectMainPathStatus(..,1)` → resend
  of its route and `sendPathSelected` (`:211-219`). Two navigating writers
  would fight on every confirm.
- **Levers that silence the stock map without modifying it**, best first:
  1. *None inside the protocol.* The only internal gate on the 1 Hz senders is
     `SomeIPMatrixManager.t()` (`f0.java:276`), driven by the car's NOA
     "no-sleep" state from BYDAuto, not a setting we own. The DNP switch only
     gates the route/handshake and the ADAS client subscriptions
     (`LM/k/h/e0/b/d.java:131-145`), not the position/TrafficInfo/status loop.
  2. *Stop the process* is not reliable isolation. In the retained deployment
     receipt (`captures/stock-map-route-graph-20261022/car-graph-deployment-03/receipt.json`),
     `force-stop` completed at 14:01:36.204 UTC and a new PID was already recorded
     at .382, about 178 ms later. The stock map card, cluster Map scene and
     voice navigation also depend on the package.
  3. *Disable the whole package for user 0* (`pm disable-user`) is a reversible
     candidate, not tested here. The exact firmware PackageManagerService permits
     shell whole-package enabled states 0/1/3, subject to protected-package
     checks; shell component disabling for a non-testOnly APK is rejected.
     Record the original enabled state before any separate car trial. Blocking
     the map UID's network cannot silence Binder calls through the shared SOME/IP
     daemon; blocking that daemon would affect our sender and HUD too.
  4. *Do not touch* the caller-supplied session key: binding with the stock
     map's package as the intent type would merge into its session and can tear
     down its offer on unbind. That is impersonation, not a lever.
- **ADAS type and nav-provider selection** do not switch the feed off:
  `getAdasType` only chooses the serializer (2 SDRoute, 6 RouteInfo, else
  NaviToEHPData — this car), `byd_map_package` / `DEFAULT_MAP_SWITCH` steer
  launch and cluster projection.

### What the idle stock map still sends

From the code (`MmtSomeIpDataManager`, `LM/k/h/a0/c/c/a/e0.java`) and a
2026-09-23 car log (`captures/split-signals-live-20260923/logcat.txt`, stock
map pid 27260, not navigating; logging is rate-limited, sends are ~1 Hz):

| Topic | Idle content | Cadence |
| --- | --- | --- |
| `0x8202/0x8008` vehicle location | GPS = matched point, `pathId −1`, segment/link/offsets −1, `ownerShip −1`, course | 1 s loop while stopped (`e0.java:109-150`), else per location fix |
| `7/0x8003` TrafficInfo | `roadClass −1`, `roadSpeed 0`, `toDist −1`, `offCourse false` | with each location send |
| `7/0x8001` NavigationStatus | `100`; `101` while navigating; `110` for 3 s after guidance ends (`e0.java:237-258`) | 1 s loop |
| `0x8202/0x8009` region | adcode/province/city from its own `global.db` (`990001` with our bound package) | on region change |
| `0x8202/0x8004` tunnel, `…02` incidents | empty/zero | with location |

Across all retained captures NavigationStatus was 100 in 10 053 logged sends,
101 in 742, 105 in 753, 104 in 195, 106 in 42, 110 in 21. Our feed would assert
101 and a pathId every second while the stock map asserts 100 and −1. ODD
region requests (`ODDRegionCodeReq`) and the route are sent only around
routes, never idle.

## No official provider path

- The ADAS feed is a part of the stock map's own process (`com.byd.noa.*`,
  `k.h.e0.*`), not a system service another navigator could register with.
  Nothing in the IVI framework, AmapService or SomeIpService accepts a route
  from a different provider.
- `BydProjectionService` (`com.example.amapservice`) selects Gaode or Baidu by
  `byd_map_package` and checks the caller's real package, but only for cluster
  projection Activities; it never touches SOME/IP. The Baidu identity is also
  filtered at boot (`InstallPackageHelper`). See `docs/stock-map-findings.md`,
  "Native cluster provider contract".
- The "official" route to ADAS is therefore: be the process that fires on
  services 7/43/45/`0x8202`. The IVI does not care which process that is.
- A second, separate map→ADAS channel exists and is already ours: the speed
  sign through BYDAuto Setting device 1023 (`SETTING_RODE_TYPE_SET`,
  `SETTING_SPEED_LIMIT_SET`; `HudNativeSpeedLimit.kt`). The stock map writes
  the road type there on every road-class change.

## Contract

What ADAS receives from "the map", reduced to what an emulating publisher
must produce. Protobuf schemas are the generated lite classes in
`LM/someip/...` (field numbers in `*_FIELD_NUMBER`); the route payload is Gson.

**Session.** `startSomeIpService` on the four IVI services as soon as the
binder is ready (`LM/k/h/e0/b/d.java:224-228`), not per route.

**Route (`43/0x8002`, reliable).** Built by `k.d().f()/g()`
(`LM/k/h/e0/d/f/k.java`) on every route result, reroute, path change and
300 ms after the DNP switch turns on (`g.java:175-185`); then `sendPathSelected`.

- protobuf `naviSDRouteNotify{ naviSDRouteStruct{ navigationSDLink2: string, navigationSDLink2Num: int } }`
- string = Gson `NaviDataDetail{ time, pathCount, length, currentIndex, payload }`;
  payloads over 512 KiB are split into chunks, `length` = chunk count
  (`LM/k/h/e0/d/f/n.java`)
- payload = `MessageSend{ msg:"response", code:200, data:[NaviToEHPData…], endPOIInfo{AVPMapIdList, PTPStateFlag}, RouteType, mapProvider }`
  (`RouteType` from the route type, `k.a()`; `endPOIInfo` from AVP map ids, empty here)
- `NaviToEHPData{ pathID, linkCnt, links[], pntCnt, pnts[{x,y}], guideGroups[], trafficLights[{x,y}], viaRoadInfo[] }`
  (`l.f()` `LM/k/h/e0/d/f/l.java:219`, `l.y()` `:749`, `l.o()` `:676`)
- `LinkItem{ linkID (64-bit topo id), roadclass, roadname, len, linktype, formway, hasTrafficLight, laneNum, hasMultiOut, mainAction, assistantAction, adminCode, ownership (1–4 → 1), hasParallel, direction (raw≠3), speedLimit (always 0), hasMixFork, pntBegIdx, pntCnt }` (`l.b()`, `l.java:111`)
- `GuideGroup{ groupName, groupIconType, groupLen, groupTime, groupTrafficLightsCount, groupEnterCoord, segments[SegmentItem{ crntSegmLinkCnt, linkBegIdx, navigationLen, navigationMainAction, navigationAssitAction, trafficLightNum, isArriveWayPoint, description, navigationNextRoadName }] }`
- points are SDK integers / 3 600 000, i.e. the SDK frame, with no second datum
  conversion (see "Coordinates" below).

**Selection handshake.** `45/0x8001 pathIDReq=pathId` after the route;
ADAS answers `44/0x8002` (MMTPathSelected, same pathId: seen for all 35
routes of drive-01). A different pathId or an error makes the stock map resend
(`g.java:135-140, 242`). `PathMatchProxy` (`44/0x8001`) decodes `NavigationPathMatchStatusStruct`
with `checksCRC32`, `counter`, `code`, `routeInfoArray` and `msg`; each
`RouteInfo` has `pathID`, `totalNOADistance`, `routeBeginIdx`, `routeEndIdx`,
`statusCode`. The earlier EHPToNaviData field list was not this build's wire
schema. Neither this reply nor `PathP2PMatchProxy` was observed in the retained
2026-10-23 drives. The later [parked probe](#parked-route-selection-with-a-separate-probe-2026-10-24)
did receive PathMatch with a matching pathId and end index, without a transmitted
status code or NOA distance. The stock consumer is empty (`k.h.e0.d.d.k()`).

**Position, 1 Hz (`0x8202/0x8008`).** `sdVehicleLocation{ 1 lat, 2 lon, 3/4 matched lat/lon, 5 vehicleSpeed, 6 GPSspeed, 7 curSDRouteID=pathId, 8 reserve4=course, 9 curStepId=segment, 10 curLinkId=link in segment, 11 linkOffset (remaining in link), 12 distanceToNextStep (segment distance), 13 currentRoadOwnership }`
(`f0.java:230-239`). Sent with TrafficInfo, tunnel and incidents
(`e0.java:1128-1143`). Unmatched: freeze at the link end, matched point = GPS,
ownership −1, class −1; there is no explicit off-route flag in practice
(`offCourse` exists in TrafficInfo but stays false).

**TrafficInfo (`7/0x8003`).** jam length/distance/status, pass time,
construction, `SpdLmtSpeedValue` (legal limit; 0 on this car), camera limit and
distance, interval camera, `offCourse`, distance and time to destination,
`roadClass` of the current link (`f0.java:208-212`).

**Guidance extras (while matched).** `…0A` `historySpeed`, `trafficFlowSpeed`,
`hasParallelRoad`, `hasMixFork`, `hasLongSolidLane`, voice command;
`…0B` next icon/main/assistant action and distance, camera; `…07` ramp
distance and tidal lane; `…0C` next-intersection lanes (back/front/extend/
recommended/types as bytes); `…0E` lanes 3 km ahead (JSON); `…01` light
(only when < 500 m or with a countdown); `…09` region; `7/0x8001` status 101.

**Not to send.** `13/method 0x0001` (DNP/ICC switch request,
`e0.java:110`) is a control of the driving function; memory-NOA requests are
stubs in this build (`LM/k/h/e0/c/h.java`). `13/method 0x0002` (ODD region
list) is a read and harmless.

**Unknown.** What ADAS requires before offering NOA (drives show class 0/6
for highway NOA, about 3–4 km of unbroken class 6 ahead, not ending at the
destination; city NOA unexplained, maybe `historySpeed`); whether `linkID`s
must be stable or globally meaningful (the parked PathMatch reply does not settle this);
the receiver's coordinate datum; how ADAS treats two interleaved writers.

**Coordinates.** The stock map sends its SDK frame (the GCJ-style inner
transform, applied in Moscow too: ~531–540 m from raw GNSS). Our OSM packages
are encoded in the same frame (`native-gcj-unrestricted-candidate`), and on
2026-10-23 ADAS confirmed those routes and drove highway NOA on them. The best
current reading is that ADAS consumes that frame; a Yandex converter must
apply the same transform to WGS-84 route points and positions. This is
evidence, not proof (`noa-coordinate-contract-evidence-20261022.json`).

## What Yandex gives us

Through our module inside Navigator (it already holds `GuidanceBinding`,
`DrivingRoute`, `Guidance`, `Windshield`; `experiments/yandex-hud-module/`).
API from `YN/jadx/.../directions/driving/DrivingRoute.java` and
`navigation/automotive/{Guidance,Windshield}.java`, member lists from
`dexdump` of `YN/dex/classes10.dex`.

| Yandex source | Content |
| --- | --- |
| `DrivingRoute.getGeometry()`, `getRouteId()`, `getSections()` (`DrivingSectionMetadata`: annotation, weight, leg) | polyline (WGS-84), route identity, manoeuvre sections |
| `getSpeedLimits()` | legal limit per polyline segment (m/s, nullable) |
| `getHighways()`, `getTunnels()`, `getTollRoads()`, `getHdSections()`, `getStandingSegments()` | `Subpolyline` ranges only, no attributes |
| `getTrafficLights()` (id, position); `Windshield.getTrafficLightsWithSignal()` | lights on the route; signal state ahead |
| `getLaneSigns()` / `Windshield.getLaneSigns()`, `getNearestLanes()` | lanes at junctions (kind, directions, highlighted); not a per-road count |
| `getJamSegments()` | jam type and speed per segment |
| `getEvents()`, `getSpeedBumps()`, `getRailwayCrossings()`, `getPedestrianCrossings()`, `getDirectionSigns()`, `getZlevels()` | cameras (with limit), incidents, crossings, signposts, level changes |
| `Guidance.getRoutePosition()` / `RoutePosition`, `getLocation()` + class, `getRoadName()`, `getSpeedLimit()`, `isIsStanding()`, `getRouteStatus()` | progress on the route, matched position, current street |

Accessibility reads in Denza Apps add nothing structural (limit, street,
manoeuvre text). Public Navigator intents (`yandexnavi://build_route_on_map`)
only start routes; there is no route export.

**NaviToEHPData field by field.**

| Field | Source |
| --- | --- |
| `pathID` | hash of Yandex `routeId` (new id → new route + selection) |
| `pnts`, `len`, `pntBegIdx/pntCnt` | polyline cut into links; GCJ transform |
| `links[].linkID` | synthetic, stable per OSM way fragment or polyline cut (receiver semantics unknown) |
| `roadclass` | Not exposed by inspected route getters; opaque native route data remains under investigation. Existing OSM enrichment starts with known OSM IDs and is not a Yandex-to-OSM matcher. Unknown class remains unknown |
| `formway` | No proven getter or archive mapping. Remains unknown; no default 15 or inferred ramp classification |
| `linktype` | Explicit tunnel ranges map to the inspected tunnel enum. Z-level alone does not prove a bridge; other types remain unknown |
| `laneNum` | No confirmed per-road source yet. Junction lane signs include special lanes and do not establish counts along the entire route. Missing counts must not become fabricated laneNum1 |
| `direction` | No proven source for road-level one-way semantics; the direction of the selected route alone is insufficient |
| `ownership` | No proven source or cross-provider access-to-ownership mapping; remains unknown |
| `adminCode` | Unknown; do not turn the Moscow package's synthetic region into an eligibility claim for Yandex |
| `hasTrafficLight`, `trafficLights[]` | `getTrafficLights()` positions |
| `mainAction`, `assistantAction`, guide groups, segments | Translate semantic annotations with the route MainAction/AssistantAction enums. HUD field-28 IDs are different; per-link and guide-segment actions must also stay distinct |
| `roadname`, `navigationNextRoadName` | annotation toponyms, `getRoadName()` |
| `speedLimit` | `getSpeedLimits()` — stock always sends 0; keep 0 until tested |
| `hasParallel`, `hasMixFork`, `hasMultiOut` | Unknown, not silently false |
| `viaRoadInfo` | derivable (class, lanes, limits per named road) |

## The converter

The original product architecture below is a proposal. The implemented boundary
is emulator private evidence files → host Python → a local mock receiver,
described under [Local prototype](#local-prototype-2026-10-23). ~~There is no
Yandex-to-OSM matcher~~ (the matcher exists since 2026-10-24, inside Navigator:
[OSM roads](#osm-roads-under-the-yandex-route-2026-10-24)); there is no Denza Apps
ADAS publisher yet.

Proposed product architecture, in two places:

1. **In Navigator (module):** on each new `routeId`, a route snapshot
   (polyline, ranges, limits, lights, lane signs, manoeuvres, toponyms); at
   1 Hz a progress sample (segment index + fraction, classified location,
   speed, heading, standing, jam speed ahead, next manoeuvre). Sent over the
   existing Navigator → Denza Apps Binder (`HudHostProtocol` style, with
   generation and expiry).
2. **In Denza Apps:** map-match the polyline to the OSM graph we already build
   for the region (amap-injector `tools/stock-map`, `research/stock-map-native`), take class,
   formway, lanes, direction, ownership, bridges and signals from it, cut links
   at attribute changes and manoeuvres, transform to the SDK frame, serialize
   `NaviToEHPData` byte-compatible with the stock Gson output, and publish
   route + selection once per routeId. At 1 Hz convert `RoutePosition` to
   segment/link/offsets of our cut, publish position, TrafficInfo
   (class of the current link, distance/time to destination, `SpdLmtSpeedValue`
   0 at first), status 101, `historySpeed`/`trafficFlowSpeed` from jams,
   lights within 500 m, region 372. Off-route or lost: emulate the stock's
   unmatched state; route end or Yandex gone: status 110 for 3 s, then 100
   and pathId −1, then stop.

Unknown attributes stay explicit in the offline draft and block publication
acceptance. Class values are enums, not a numeric safety ranking. No fallback
combination has been shown to guarantee ICC-only behavior.

## Local prototype (2026-10-23)

Evidence index: [`prototype-evidence-20261023.json`](prototype-evidence-20261023.json).

Implementation: [`tools/adas_bridge`](../../tools/adas_bridge/README.md),
[`converter-contract.json`](converter-contract.json), and the diagnostic readers
inside [`yandex-hud-module`](../../experiments/yandex-hud-module/README.md).
The APK keeps the existing two hooks and HUD transport; new route evidence is
emulator-only (`ranchu` / `goldfish`). It does not send ADAS events.

`RouteSnapshot` copies the selected route independently of AR and manoeuvre
visibility: complete WGS84 geometry, sections, nullable edge speed limits, lane
signs with special lanes and recommendations, traffic lights, event/camera data,
range annotations, signposts with styles, metadata and opaque descriptors.
`GuidanceSnapshot` copies the original guidance before visual HUD holding, with
source route ownership, source edge/fraction, native timestamps, actual fix age,
location, ETA, next manoeuvre and upcoming traffic-light signals. The recorder
writes at most once a second into an atomic latest file and a 120-file ring.
Every process has a common UUID across route, progress and native archive files.

Actual fixtures, not just getter signatures:

| Fixture | Geometry points | Non-null speed limits / edges | Lane signs | Traffic lights | Highway ranges |
| --- | ---: | ---: | ---: | ---: | ---: |
| Initial central Moscow route | 402 | 401 / 401 | 12 | 15 | 0 |
| Southwest Moscow → Vnukovo | 637 | 636 / 636 | 21 | 17 | 0 |
| M4 corridor request | 975 | 884 / 974 | 4 | 6 | 1 |

The southwest fixture also contains a runtime `GREEN`, `LEFT`, `MAIN` signal
with seven seconds left. That source API supplies no independent signal age,
so its current value is not a validated traffic-control input. Null speed
limits on the M4 fixture remain null. Its explicit highway annotation is
preserved; it does not by itself assign stock road class 0/6. Final rich copies
reported no extraction issues; observed copy times were 20 ms for the final
city route and 38 ms for M4. `complete` means the queried API fields were copied
without errors, not that all ADAS-required map attributes exist.

The host cuts geometry at fractional section/range/limit boundaries, maps
source progress back to those cuts, and applies the selected coordinate
transform once. Yandex annotations describe section entry; stock guide-segment
actions describe the end, so the action is shifted to the preceding segment.
Only known semantic enum correspondences are used. Traffic lights on a boundary
belong to the incoming link. Junction lane-sign width is never extrapolated to
the whole road. (Superseded: the bridge numbers paths as Amap does, 100002 up per
process, one id per link table, and cuts no link at a jam.) Synthetic path IDs fit 53 bits because `curSDRouteID` is a
protobuf **double**, whereas route JSON and selection use integers; link IDs
retain exact signed64 serialization.

Local transport exercise: route bytes → decode → selection bytes → local mock
confirmation → position/TrafficInfo/status protobuf bytes → decode. A 180-second
watch of the actual emulator processed **two Navigator process sessions and two
routes, 64 fresh position samples and 192 dynamic packets with no read errors**.
Cancellation stops the mock feed; restarting Navigator requires a new session,
route and mock selection even when counters restart. Tests also reject stale,
misowned and out-of-order positions and verify recovery after an input gap.
The original 32,608-byte stock SOME/IP message roundtrips byte for byte. The
host's test suite has 32 tests; these are structural/lifecycle checks, not an
ADAS ECU simulation.

```sh
python3 -m tools.adas_bridge.watch --serial emulator-5592 --duration-s 60 \
  --golden-someip captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.someip.bin \
  --target-frame native-gcj-unrestricted-candidate \
  --out-dir captures/yandex-adas-prototype-20261023/new-watch
python3 -m unittest discover -s tools/adas_bridge/tests -v
```

The watcher requires an explicit emulator serial and verifies `ro.kernel.qemu`.
Its device commands only read allowlisted evidence. No real publisher is
implemented. Unknown attributes remain visible, and every draft is marked
`publicationReady=false`. The byte exercise uses the retained stock envelope as
an explicit template and native unknown sentinels where verified; remaining
JSON nulls are **not proved acceptable to the ECU** (superseded: the stock's own
model coerces a JSON null in a primitive to 0, so the bridge sends the stock's NULL
values and never null; see below). Current-road ownership,
vehicle speed, GPS-speed unit conversion and several extras remain unencoded.
The Yandex classified location is not established as independent raw GNSS.
Selection acknowledgement alone would not validate these semantics.

**Yandex versus OSM.** The actual snapshots contain rich route-specific
information: selected lane arrows, per-edge limits, traffic and live progress.
The existing OSM graph independently contains road category, one-way, explicit
per-way lane counts, access and structure tags in the same covered areas. For
example, the central route bbox contains 1,588 non-service ways, 1,562 with
explicit lanes. These are candidate coverage counts, not an exact route match
or a percentage comparable to Yandex lane signs. There is no proven universal
winner and no Yandex-to-OSM matcher in this prototype. See the pinned
[`osmComparison`](yandex-native-route-evidence-20261023.json) for input hashes
and the limitations of the bbox count.

## Stock-equivalent bridge (adas-link, 2026-10-23)

> **Qualified 2026-10-24:** serializer and dry-run oracle correspondence is not
> LIVE producer equivalence. The [build-12 trip audit](#noa-gap-audit-of-the-build-12-trip-2026-10-24)
> measures different frames, cadence and graph-driven path replacement.

`libraries/adas-link` (plain Java 8, compiled into Navigator's dex like
`:hud-link`, and a Gradle library) is the stock map's feed code, ported class by
class from `LM`, driven by Navigator instead of Amap. `AdasBridge` in the YandexHUD
module hosts it; the owner's rule is mirrored code, never our own semantics.

| Stock code (`LM`) | adas-link |
| --- | --- |
| `l.f/y/r/a/d/o/b`, `l.k/A`, `k.g`, `n.a` | `StockRouteJson`: NaviToEHPData, one group named 无名道路, segment descriptions from the app strings (English or default), `endPOIInfo` PTP 1, NaviDataDetail chunks (count from 524264, cuts at 524288, the stock's own failure past the end) |
| `f0` senders, `e0.k/l`, `f.h` | `StockMessages` + `Pb`: every message as the generated protobuf-lite classes write it (uint32 as 32 unsigned bits, set sub-messages always present) |
| `DriveRouteManager`, `k.i` | `StockFeed`: route + selection when the focus path changes and DNP service and switch are on; a foreign or failed MMTPathSelected resends with RouteType 14; DNP switched on resends 300 ms later; no ACK timeout (the stock has none) |
| `MmtSomeIpDataManager`, `f0.t`, `f` | `StockFeed`: location at most once a second (`c.d()`), region then position, TrafficInfo, tunnel, light; the 1 s stationary loop; NaviInfo bundle (tidal lane, action and camera, speed/intersection) once a second; NavigationStatus every second (101 navigating, 110 three times only after arrival, else the map state); resets on start, stop and yaw; nothing dynamic while ADAS sleeps; noODDRegionDist MAX |
| Amap PathInfo | `RouteConverter`: one segment per Yandex section; links cut at lights (a light ends its link), lane, direction and restriction signs (junctions), limit changes, tunnel, ferry and highway boundaries, never at jams; link length cut down to whole metres and the segment their sum (as Amap: stock `len` = haversine − 0.47 m median over 1 177 factory links); group segments by road name; grid 1/3 600 000; link id = SHA-256 of the link's own grid points |
| Amap callbacks | `NavigatorDriver`: start/stop/arrive/yaw from Navigator's own state; a fix only when FINE, newer and at most 1 s old; progress never backwards on one path; a finished route is retired |

**Content from Yandex, by field.** Unknowns are the stock's own NULL values
(`roadclass`, `formway`, `linktype` -1, ownership -1, actions 0), never JSON null.
Facts taken from the factory Beijing profile
(amap-injector `research/stock-map-native/factory-beijing-profile-20261023.json`): ~~the manoeuvre
link carries the segment's MainAction~~ (overturned by the stock's own route
payloads on the bench: every segment-end link carries 0, only inner links 8/9/10,
so link MainAction stays 0); linktype is common (0) unless Yandex lists a tunnel
or ferry; a roundabout section is formway 4, direction 1. The previous highway
rule (class 0/6 from a 90 km/h threshold, formway 1, direction 1, ownership 0 and
sign-width LaneNum) is removed from local Java and Python source: factory
correlation does not establish a Yandex-to-Amap crosswalk or continuous lanes.
Highway and sign boundaries remain cuts. curLinkSpeed (`historySpeed`) is the section's
free-flow speed, the closest Yandex value to the factory's typical link speed.
Not taken: z-levels as bridges (most of the M4 fixture sits at level 1), toll (no
LinkItem field), ~~class, direction, ownership or lanes on ordinary city roads (no
OSM match yet)~~ (with the road store they come from OSM wherever the route matches:
[OSM roads](#osm-roads-under-the-yandex-route-2026-10-24); the rules above remain for
unmatched stretches). FORK/EXIT map to SlightLeft/Right (3/4), ferries and waypoints to
the AssistantActions 19/20/35, arrival to 0/36. Speed cameras (SPEED_CONTROL with a
limit) within 2 km are SubCameraExtType 7 in TrafficInfo and `naviActionAndCamera`.

**Still not the stock's.** Vehicle speed (BYDAuto) is GNSS speed until the car
supplies it; the GPS slot is the platform's passive GNSS fix when fresh, else
Navigator's location; DNP service, switch and no-sleep are assumed on in the
installed dry run. Their exact BYDAuto getter sources and a separate shell-UID
read path are now identified; normal Navigator UID access and the three actual
car readings are not established ([Live gate sources](#live-publisher-gate-sources-2026-10-24)).
Region codes stay at the stock defaults (no Amap adcodes);
jams, incidents, lane info (`0x800C`) and 3 km lanes (`0x800E`, sent empty as the
car did) are not mapped; ~~link granularity is Yandex's~~ (with the road store links
also end at OSM junctions: [OSM roads](#osm-roads-under-the-yandex-route-2026-10-24)) (fewer, longer links than
the factory's); path ids restart per Navigator process; positions come per FINE
fix, so a tunnel without fixes is silent where Amap would dead-reckon.

**Bench (Beijing factory runs, `captures/adas-bench/`).** `bridge_trace.py synth
--engine java` runs the real feed (`ReplayTool`) on synthetic Yandex input made
from each stock route; `compare.py` against the stock map's own fires. Message
inventory and cadence now match (1 Hz status, position, TrafficInfo, tunnel,
light, region, NaviInfo bundle, AdvAutoFunInfo; 100 before any route, 101 through
reroutes, 110 ×3 then 100 and pathId −1 after arrival; one route and selection per
pathId, the position on the new path one NaviInfo later). What the comparator
still lists is the input, not the feed: synthetic geometry is Amap's simplified
shape (segment lengths 0.6–6 % off), Yandex names no junctions (5–14 links where
the factory has 16–60, so `curLinkId`/`linkOffset` differ), no merge actions
(9/10), no lane arrays (`0x800C`, `0x8006` not sent), no limits in the synthetic
input; the stock sends 3 km lanes on 7 of 18 runs (the bridge always, as the car
logged), and one extra old-path position after arrival.

**Evidence.** `AdasLinkTest` (build.py and Gradle): the car's first-full and resend
route captures rebuilt to the byte (JSON, NaviDataDetail, native SOME/IP framing);
converter invariants on four recorded Navigator routes; the review's dynamic
sequences; Gradle oracles run real Gson 2.10.1 and protobuf-java 3.25.5 on the
output. Ten mutants of critical lines are all killed. The Python oracle is equal
byte for byte on the recorded routes and about 650 encodings
(`tools/adas_bridge/tests/test_stock_equivalence.py`). The emulator dry run and
its trace check are in `captures/adas-bridge-device/`.

### Live publisher gate sources (2026-10-24)

`feed.start(true, true, true)` is an assumption in the installed dry run.
The stock's `DnpService` flag means NOA configuration/support, not a SOME/IP
connection. `LM/k/h/e0/a/r.java` (`BydAutoNoaProxy`) reads the following integer
properties through `BYDAutoADASDevice`, device **1038**, and registers filtered
listeners for configuration, switches, sleep and optional disconnection changes.
These are getter sources, not commands to activate assistance.

| Getter input | FID, signed decimal | CAN-FD hex |
| --- | --- | --- |
| HNP configuration | 1126170651 | `0x4320001B` |
| UNP configuration | 1126170658 | `0x43200022` |
| E2E configuration | 1126170664 | `0x43200028` |
| HNP switch | 700448824 | `0x29C00038` |
| UNP switch | 700448826 | `0x29C0003A` |
| E2E switch | 481296416 | `0x1CB00020` |
| Assist drive mode | 535830576 | `0x1FF02030` |
| NOA UI type | 535838789 | `0x1FF04045` |
| Sleep status | 535842878 | `0x1FF0503E` |
| Optional disconnection status | -1728052359 | `0x99000379` |

The FIDs come from this firmware's framework initializer, not the zero-valued SDK
declarations or similarly named CarSetting string keys. Let `H/U/E` mean the raw
configuration values, `HS/US/ES` the raw switch values, `M` assist mode and `UI`
the UI type. The factory predicates are:

```text
hConfig = (UI != 2 ? M == 3 : H == 3 || H == 4) || H == 0
hSwitch = HS == 1 || M == 3 || M == 4
citySwitch = (UI != 2 ? M == 3 : M == 4) || US == 1
dnpService = U == 0 || E == 0 || hConfig
startupSwitchRaw = US == 1 || ES == 1 || hSwitch
laterSwitchRaw = (hSwitch && hConfig) || ES == 1 || (citySwitch && U == 0)
stockNoSleep = sleepRaw != 1
downtime = disconnectionRaw == 2
```

Startup `H()` and later switch recomputation `f0()` are separate paths. The
configuration callback has another explicit recomputation in `NoaStateManager`;
a periodic snapshot does not reproduce callback order. `AdasGateSnapshot` exposes
both startup and later raw predicates and uses the later one for its hardware
predicate. The manager combines the switch with no-sleep. Its catch-true and
getter default values are permissive factory fallbacks, not successful readings,
and are not copied into the decoder.

Sleep 2/3 pass the factory `!=1` predicate but CarSetting disables the control for
these states. The decoder retains `stockNoSleep` and reports operational
`noSleep=null` for 2/3; only explicit 0 permits that operational gate. Accepted raw
sets are a conservative decoder policy, not proved exhaustive enums:
configuration/assist 0–4, switches 0/1, UI 0–2, sleep 0–3. Unknown or failed
required reads close the gate. Optional disconnection missing/error remains
unknown without failing the nine required readings; known 2 explicitly blocks.

**Access and the bounded reader.** `BYDAutoADASDevice` declares
`android.permission.BYDAUTO_ADAS_GET`; Java `get` and listener registration do not
bypass native enforcement. This IVI's `libbydautoservice.so`
`checkGetPermission(int,int)` compares the calling UID against 9999 at ELF VA
`0x299e4..0x299ec`. Shell UID 2000 skips the GET permission check; an ordinary
Navigator UID does not. The minimal read path is a standalone shell-UID
`app_process` reader with this fixed whitelist, not an exported arbitrary-command
or privileged app API. A direct Binder call from Navigator would retain its UID.

Only `autoservice` transaction **5** is allowed for these inputs. The exact IVI
`libbydauto.so` Binder dispatcher maps 5 to `0x157dc` (integer payload,
`Parcel::writeInt32`) and 7 to `0x15d88` (float payload, `ldr s0` then
`Parcel::writeFloat`). An older HUD getter's 5→7 fallback is not an integer gate
fallback. No setters, arrays or guessed transactions belong in this reader.

The parent-owned reader design takes a bounded 1 Hz batch, records start/end
`elapsedRealtime` and `uptimeMillis` plus per-FID errors, and atomically writes a
private debuggable-app file through fixed `run-as` input. A random consumer UUID
is new for every app process; the reader pins it and stops on heartbeat loss or
nonce change, with a four-hour lifetime cap. `AdasGateSnapshot.Tracker` requires
the app's independently verified boot identity and this consumer UUID. The APK 9
candidate uses a shared UUID derivation from Android `Settings.Global.BOOT_COUNT`
so shell and app can verify the same boot generation without app access to
`/proc` boot ID. An unknown boot count fails closed; a UUID is not fabricated
from a wall clock or a caller assertion. Snapshots expire within at
most 5 s of the oldest batch read, including suspend time; the values are
sequential observations, not an atomic hardware state. A held file cannot renew
its age; sequence rollback/mutation or a new reader UUID cannot silently rearm
the same tracker. A future or failed batch reserves its source sequence too, so a
held future file cannot mature into a valid observation or restore older gates.
JSON is bounded to 16 KiB and integer reads never default to 0.
This host implementation has not read the new FIDs on the car.

**Remote availability is separate.** In `SomeIpService`, the local HAL callback
and `isServiceReady()` mean a local HIDL proxy exists. `startClient` returning
0/13 is local client/refcount success. Neither proves the remote service is
available. The stock subscribes to ordinary `onSomeIpEvent(SomeIpData)` topics:

| Remote service | AVAILABLE | NOT_AVAILABLE |
| --- | --- | --- |
| 13 / instance 13 | `0x0001000D000D0001` | `0x0001000D000D0002` |
| 44 / instance 44 | `0x0001002C002C0001` | `0x0001002C002C0002` |

These are kind 1, low ID 1/2. `DataUtil` constructs them; the stock recognizes
the exact topic before parsing any payload. AVAILABLE subscribes to that
service's data and NOT_AVAILABLE unsubscribes. Exact topic subscriptions are
required for delivery. Remote states start unknown and must be cleared on
disconnect/rebind/HAL loss. `getAvailableEvents()` is a stub returning null in
this firmware, so it cannot bootstrap readiness. Current data-only passive
subscriptions and `HAL=true` do not supply these remote state callbacks.

The native bootstrap path is now byte-pinned. For an already-started service,
subscribing to its exact AVAILABLE topic replays its current availability if the
native flag is true, without restarting a client or service. Service 44's
`libsomeipimpl.so` subscriber at `0xa27120` and service 13's at `0xa6c780` test
kind 1 / low ID 1 and the atomic current-available flag, then emit the same topic
with an empty payload. The HIDL service forwards this by started-service handle;
its callback path does not filter against the topic list added after the call.
Java queues the callback, and its `subscribeMap` monitor prevents delivery to a
new session before that session's subscription is stored.

For a fresh handle the HIDL subscriber first requires that the service has been
started; subscribing before `startClient` can return 11. The candidate therefore
starts clients before subscribing to availability. Repeated AVAILABLE
subscriptions are idempotent in both the native exact-topic list and Java's UUID
set. A false native flag does not synthesize a NOT_AVAILABLE callback on
subscription, so absence still means unknown. This is static firmware proof,
not a new car acceptance: the HIDL service is pinned to the owner's IVI OTA and
the implementation library to the retained car pull. Exact bytes, ELF mappings,
callback chain and replay/ordering limits are in
`captures/yandex-live-gates-20261024/availability/receipt.json`;
`availability_proof.py` beside the main receipt reproduces them locally.

The retained parked session does not replace these gate reads: all 81
`13/0x8002` packets omit ACC/ICC/DNP/takeover fields, so their generated getter
zeros are not transmitted OFF states. All 81 `13/0x8005` packets explicitly
carry `noaMode=2`, which identifies a family, not configuration/switch/sleep.
The explicit `44/0x8002` selected status 200 and matching path ID prove selection
protocol only. Optional IVI loopbacks returned unsupported, and cannot supply an
assumed DNP switch. A stationary publisher still needs explicit owner approval,
one-writer ownership, fresh verified hardware readings and independent transport
availability; the decoder's `hardwareGateOpen()` is only the hardware predicate.

Evidence: `captures/yandex-live-gates-20261024/receipt.json` pins source excerpts,
both native library hashes, ELF VA↔file offsets, raw dispatcher/permission slices
and PLT relocations. Reproduce locally with
`.venv-firmware/bin/python captures/yandex-live-gates-20261024/analyze.py`.
`AdasGateSnapshotTest` passes 65 host checks for the formulas, unknown states,
boot/process identity, replay, batch freshness, typed getters and bounded input.
No device calls or new REA sessions were used in this phase.

## OSM roads under the Yandex route (2026-10-24)

Historical implementation: build 5 removes this source from the APK. The reader
and fixtures are retained only as host/test oracles. See [Yandex-only extraction](#yandex-only-graph-extraction-2026-10-24) for the current runtime.

The inspected Yandex route interface exposes no road graph: the bridge could cut links only where Yandex
marks something (lights, lane and direction signs, limits, ranges) and knew no class,
form, lanes, direction or ownership of ordinary roads. Our OSM map has both, encoded
in factory conventions by the `build_map.py` producers; the bridge now places the
Yandex route on it inside Navigator. The geometry stays Yandex's; only cuts and
attributes come from OSM.

**Store.** `tools/adas_bridge/road_store.py` reads a `build_map.py` output — the staging
fragments (one DRT road each, in the stock frame), the delivered m2 records of the
final routing stage (decoded with the field-coverage gate's readers) and the
factory speed-limit signs — and writes a flat big-endian file: nodes, adjacency,
25-byte fragment records (class, formway, link type, LaneNum, direction as exported
(raw≠3 → 1), ownership as exported (1..4 → 1), ERAT 0x4269 speed, legal limit per
direction, name), varint geometry on the stock grid, a 200 m cell index, names.
Fragments meeting only each other with equal attributes are joined. For the region
of the four recorded routes (about 45 × 34 km including central Moscow): 318,977
records → 257,782 fragments, 205,038 nodes (138,682 junctions), 15.7 MB (9.0 MB
gzip), 99,109 fragments with a legal limit. `RoadStore` (adas-link, plain Java) reads
it from a `ByteBuffer`; the module maps `denza-adas-roads.bin` from Navigator's files.

**Matcher.** `RoadMatcher` (mirrored by `tools/adas_bridge/roads.py`, same doubles and
choices): samples every 12 m; states are fragments within 30 m in an allowed
direction heading within 60° of the route, cost (d/7 m)² + 10(1 − cos); a step costs
|network − route distance| / 3 m with the network distance from a bounded Dijkstra
over allowed directions; a no-road state (9 per sample, 15 to enter or leave) carries
the route over roads OSM lacks; Viterbi; runs under 30 m or farther than 12 m on
average are dropped; past a dead end only 5 m. Where matched, `RouteConverter` adds a
cut at every junction node (degree ≥ 3) and every attribute change the route
passes, and a link whose middle lies on a matched road takes that road's values;
elsewhere the Yandex-only rules above stand. Per tick, `NavigatorDriver` hands the
road under the car to `StockFeed` as Amap's LocMatchInfo (TrafficInfo `roadClass`,
position ownership); `historySpeed` is the road's ERAT speed; where Yandex names no
legal limit, the road's sign in the direction travelled.

**Bench** (`road_bench.py`, `bridge_trace.py yandex --engine java --roads`,
`compare.py profile --roads`; outputs in `captures/adas-bridge-osm/`):

| Route | Length | Matched | Links before → after | Median link |
| --- | --- | --- | --- | --- |
| `route-01` (city) | 7.1 km | 100 % | 28 → 119 | 180 → 38 m |
| `route-final-city` | 7.4 km | 99.4 % | 30 → 134 | 108 → 34 m |
| `route-final-m4` (highway) | 29.9 km | 99.5 % | 48 → 176 | 189 → 73 m |
| `highway-02` | 15.9 km | 100 % | 48 → 149 | 228 → 70 m |

Links carrying class, formway, LaneNum, direction and ownership: 0–19 % before,
99.3–100 % after; names 40–93 % → 64–94 % (OSM leaves service roads unnamed, as the
factory sends ""). Median link by road group against the factory's road length:
expressway 140 m (freeway 141), arterial 43–71 m (main road 50, provincial 68),
secondary 31–102 m (44), ordinary 20–49 m (52). Per tick on the recorded progress:
TrafficInfo `roadClass` 7 on arterials and 9 on ordinary roads (−1 before; the factory
sends its class on 100 %), ownership 0/1 (−1 before), `historySpeed` 46–60 on
arterials and 10–29 on ordinary roads (factory 46–64 and 10–21). Sections Yandex
names land on the same streets, Yandex tunnels on OSM tunnels (98 %), Yandex highway
ranges on classes 0/1. Not matched: short stretches where OSM has no connection
Yandex drives (19–70 m on M4, a turn on the city route) and parking-lot starts.

Not done: no car run. ~~The store must be pushed next to Navigator; the region build
needs the producers' `lanes` stage, which fails on this region ("Duplicate
directed-road lane record"), so the bench store took the `factory` stage run by hand
on the `toll` output (lanes and signals change no field the store reads).~~ (2026-10-24:
the lanes failure was a oneway way tagged both `turn:lanes` and `turn:lanes:forward`,
two records on one approach; the producer now keeps one record per approach and drops
conflicting arrows. Every stage of `build_map.py` now runs on that region with no
stage done by hand; three of its 22 gates still fail on region coverage —
`routing:graph_audit`, `classification:routing_values_handled` (unclassified tag
values such as `barrier=sally_port`, `motor_vehicle=service`) and
`signals:approaches_flagged` (6 of 8 617 approaches have no transition) — and the
home region's build passes all of them. The store is placed with `run-as`:
[Integrated run](#integrated-emulator-run-2026-10-24).)

### Integrated emulator run (2026-10-24)

The YandexHUD arm64 stock-prototype build with the bridge, the overlay and a store in
Navigator's files directory, put there with `run-as` (`tools/adas_bridge/README.md`), ran
Navigator's own route simulation on an emulator: a route in the owner's home region
(8.5 km) and a central Moscow route (7.1 km, the recorded `route-01`). Stores: home
region 1.6 MB (25 009 fragments), Moscow-routes region 16.8 MB (277 401), Moscow plus
about 50 km (the car's loaded map region, 1.26° × 2.11°) 60.2 MB (946 937; 34.4 MB
gzip; routing chain about 1 h 40 min on the host). On the device the route took 53
and 119 links, every link with a known class (100 % matched, the same as the host
bench, with either store). The traces pass `trace_check` (1 Hz status and position,
one route and selection per path, 110 ×3 only after arrival, no backward progress);
the overlay shows the feed fresh («Яндекс 0,x с»), the route waiting (no ADAS answer
on an emulator) and no stock-map alarm. Against the stock map's trace of the same
home-region drive (`captures/stock-map-factory-verify/`), `compare` separates a
different drive, not a defect: Yandex picks 3 guide segments where the stock has 7, and
the two runs differ in speed and time, so per-tick values cannot agree (the position
reaches the new path about 5 s after the route because the simulation was started 6 s
after Go). Open before any live step: TrafficInfo `spdLmtSpeedValue` carries the limit
(20–90 in these traces) while [Safety](#safety) asks for 0 until a separate test; link
`speedLimit` is 0. Cost in
Navigator's process (simpleperf on the main thread, /proc per thread): the bridge thread
about 0.2 % of one core; on the main thread `AdasBridge.sample` about 1 ms per second, the
overlay 6–7 ms and the HUD tick 36–39 ms; of the 60 MB store only the pages read are
resident (12 MB on the home route), clean file pages. A 1 ms `am profile` sampling run
made Navigator's main thread stall into an ANR; the profiler, not the module (rerun
without it). Evidence: `captures/yandex-adas-deliverable-20261024/`.

## Removing OSM: review (2026-10-24)

Independent review of Claude session `e67709d3-89ef-4074-b703-2b67ee9c998b`, the
current code and retained inputs. Replayed the current Python oracle with and
without the delivered 60,174,884-byte store (SHA-256 `e57e7e2325c511007bcb08a0863593c87ab2d533aca133458c95a2c056bc7fbb`).
No device actions. Receipt, input hashes and exact Git HEAD:
`captures/adas-yandex-only-review-20261024/comparison.json`.

| Route | Links, Yandex only → with store | Class coverage | Lane-count coverage |
| --- | --- | --- | --- |
| Home region, 8.5 km | 9 → 53 | 0 → 100 % | 0 → 100 % |
| Central Moscow, 7.1 km | 28 → 119 | 0 → 100 % | 0 → 100 % |
| M4, 29.9 km | 48 → 177 | 18.8 → 99.4 % | 2.1 → 99.4 % |

Coverage counts non-unknown values, not independently verified road facts.
The delivered build's `stages/tiles/tiles/manifest.json` records 513,908 source
ways: 92,329 explicit lane counts (17.97 %), 13 derived from directional lane
tags, and 421,566 category defaults (82.03 %). These are whole-region counts,
not counts weighted by distance or restricted to the tested routes. The binary
store retains the resulting count but not its provenance. Its construction
reuses lane defaults needed by the stock junction renderer; that does not
establish their accuracy for ADAS. Matched coverage likewise measures how much
of the route received a match, not how often that match is correct.

Before build 5, the Yandex-only branch ran with a null store. Geometry, manoeuvres,
lights, limits and live progress remain; junction cuts and ordinary-road class,
form, lane count, direction and ownership do not. Junction lane signs identify
lanes at one position and cannot establish the count along every road. A route
polyline alone cannot determine unused connecting roads or one-way restrictions.
That removal reduced input completeness in the reviewed build; the effect on
NOA availability is not measured. Conversely, the inspected getters and partial
archive decode do not prove that Yandex's native graph lacks those attributes.
The graph source has since been recovered and integrated in build 5 below;
class and continuous lane count remain open. The current APK no longer uses
the earlier store-or-route-only implementation.

## Yandex-only graph extraction (2026-10-24)

The owner's requirement is geographic independence: obtain data from Navigator
wherever it routes, without maintaining a Moscow or other regional OSM package.
This work changes the proposed data source, not the live publication policy.

**A callable native source, not just a route polyline.** In the exact ARM64
Navigator 29.8.1 library, `RoadGraph::lookupEdge` at ELF VA `0x2996720` returns
graph-local endpoint indices and a `RoadGraphEdgeData` pointer. The online
graph-tile decoder at `0x298bba0` receives the original protobuf bytes before
building this graph. Runtime addresses are `module.base + ELF VA`; the relevant
ELF load segment has virtual address zero. Hooks check the original instruction
prefix and only observe calls. Source SHA, original bytes, address mapping and
four focused REA decompilations are recorded in
[`yandex-native-extraction-20261024.json`](yandex-native-extraction-20261024.json).

`RoadGraphEdgeData` is **96 bytes**. It contains ID, SD geometry offset, shared
geometry, optional name, optional speed limit, length and optional endpoint
levels. Early 128-byte observations also captured neighbouring memory: bytes
at offsets `+0x60` and above are explicitly excluded from attribute decoding.
The last 12 meaningful bytes are `GraphLevel`, not road class or lane count.
The separate offline routing graph has richer type/category accessors. The
later [Kazan regional-file study](#regional-offline-graph-2026-10-24) recovered
its full saved graph and matched one short online route geometrically. Automatic
availability and exact edge identity for every online route remain unestablished.

An owned disposable emulator ran real Navigator routes in **SPb, Kazan and
Novosibirsk**. Both supported `denza-adas-roads.bin` locations were absent.
Observed lookups yielded 241 distinct stored edges in the SPb capture and 274
across three Kazan graph frames; the latter count is frame-local, not a global
deduplicated road count. Their shared endpoint coordinates agree exactly.
Capturing earlier in the pipeline also yielded two Kazan and five Novosibirsk
online graph tiles. These contain directed edge records, their geometry,
vertex adjacency, road names, speed limits and vertical levels. They load as
Navigator moves, without our regional road store. This proves a dynamic source
in these runs; it does not prove complete graph coverage of an entire route at
route-start time, or availability during a network outage.

Complete inspection of all 16 original APK dex files found no named Java
road-class or continuous lane-count getter. Renderer introspection returned
building-centre objects, and `Guidance.state()` contained exactly the same
route protobuf as the route archive. These negative results apply to the
inspected interfaces, not all Yandex internals. The upstream tile has an
optional field 11 which the automotive converter ignores; no road-class or
lane-count meaning is assigned to it. Native evidence identifies its use as a
bitmask by a separate transport-graph converter. It was absent from all 5,077
`EdgeData` records in the seven captured tiles. Those tiles contain 9,160
directed edge records (tile records, not globally deduplicated roads); 193
endpoint gaps remain explicit rather than becoming invented junctions.

**Prototype components:**

- `tools/adas_bridge/yandex_graph_observer.js` captures bounded original graph
  tile messages from the exact library. The tested attachment uses Frida on an
  owned emulator. Build 5 supersedes that capture dependency with an embedded
  collector; the Frida script remains an independent research tool.
- `yandex_graph_tile.py` decodes those messages into directed fragments with
  source IDs and attributes, preserving unknown fields and tile boundaries.
- `yandex_graph_capture.py` independently decodes saved post-conversion memory
  observations. Its optional geometric association retains ambiguous matches
  and gaps; graph pointers and vertex indices are scoped to their capture frame.
- `yandex_inference.py` handles route annotations without OSM. Every value keeps
  its source and scope; observed values and estimates are separate. The opt-in
  lane estimate extends a sign at most 100 m upstream, stopping at known
  section, sign, range, light or restriction boundaries. Through, turn-only and
  special lanes remain distinct. This is an approach estimate, not a measured
  lane count over the whole road.

The route-only replay covered seven snapshots including SPb and Kazan:
99.759 km summed route length, 72 lane signs and posted limits over 98.759 % of
that distance. Bounded lane approaches cover 4.719 km (4.731 %), not the whole
network. The same street may occur in more than one snapshot. Explicit SDK
highway ranges support separate class hypotheses; unknown ordinary-road class,
ownership and access are not promoted into measured attributes. Yandex itself
defines functional class by network role, so neither a speed limit nor a road
number establishes a class by itself
([Yandex mapping rules](https://yandex.ru/support/nmaps/ru/cat_road_rul2)).

**Build 5: integrated without OSM.** `NativeGraphCollector` and the ARM64
`libdenza_yandex_graph.so` observe the pinned decoder in process. Installation
checks the complete library SHA, loaded build ID, exact instruction prefix and
a synthetic register-preservation test. A single aligned four-byte branch
enters a relay in a verified free address gap; allocation never replaces a live
mapping. The callback preserves x0–x30, q0–q31, NZCV, FPCR and FPSR and only
copies to eight preallocated slots, each capped at 2 MiB. No root, Frida,
external file loader or vehicle API is involved in this runtime source.

The background worker decodes into a 16-tile/16-MiB-accounted cache. Java output
matches the independent Python decoder for every field of seven historical
tiles (587,721 checks) and a newly captured runtime tile. Decoder/cache tests
include malformed input, byte/entity budgets, eviction and clear/release races.
`GraphRoadNetwork` collapses opposite directed records into physical fragments,
joins consistent source vertex IDs, and leaves clipped/conflicting endpoints
separate. Matching supplies junction cuts, names and legal limits; it does not
erase route annotations or turn a legal limit into historical speed. The native graph’s missing class and continuous lane count do not populate
those fields. The later follow-up below removes the highway/speed and continuous
lane-count heuristics; the roundabout annotation rule remains separate from native
facts. Graph refreshes are coalesced at
10 seconds; an unchanged serialized path refreshes local matching without a
resend or new path selection.

`RoadStore` moved out of main sources into the historical test oracle, and the
APK builder rejects the OSM class/path/store if any leaks into packaging. The
dry-run host uses declared WGS84 in every region; the older coordinate
candidate still has its Moscow research envelope as a separate bench option.
The vehicle coordinate contract is not established by this emulator run.
The SOME/IP publisher remains compiled hard-off.

Rootless runtime acceptance used the final signed build 5 (SHA-256
`9b10c56f3e1e63f5ba5475a0edfcc7d253481d5f9908053af35afe73f897ba98`):

| Test route | Native matched distance / full route | Links after graph | Graph cuts | Conversion time |
| --- | --- | --- | --- | --- |
| SPb | 1,560.7 / 4,920.5 m | 57 (route-only: 30) | 31 | 42.8 ms |
| Kazan | 1,672.4 / 25,904.1 m | 63 | 26 | 29.2 ms |

The same process received ten graph tiles across the two regions: 9,993 directed
edge records and 5,399 vertex records in its cache (tile counts, not global road
counts). Captured/polled counts agree; drops, invalid/oversize inputs, decoder
rejections and synthetic ABI failures are all zero. The 1,232-message cumulative
dry-run trace passes the independent wire decoder and lifecycle checks. The
crash log is empty. Initial runtime Java tile JSON also matches Python exactly.
Full topology is still loaded near the current position rather than prefetched
along the entire route. The table measures matched coverage, not independent
map accuracy. Receipt and hashes:
[`yandex-embedded-graph-20261024.json`](yandex-embedded-graph-20261024.json).

**Further extraction: vector road attributes.** A separate, later Frida session
observed four successful native `vmap3.Tile` decodes (1,143,860 bytes). All decode
on the host without dictionary-reference errors: 9,609 features, 77 literal
attribute keys and 1,238 road rows. Actual online road attributes include
`class`, `fc`, `oneway`, `struct`, `struct:toll`, `toll`, `zlev` and `hd_covered`.
There are also road-surface/border and marking layers; a `width` on a marking is
not established as the width or lane count of the road. `class` differs from
`fc`: pedestrian and construction features can have `fc=7`. Public
[Yandex classification rules](https://www.yandex.ru/support/nmaps/ru/cat_road_rul2)
are a comparison source, not proof of the internal wire mapping.

The initial four captures' feature IDs have no exact overlap with the Kazan
graph's EdgeData or directed-edge IDs. These remain separate namespaces: the
later association uses geometry, never equality of numeric IDs. The initial
dictionary decoding is recorded in
[`yandex-vector-tile-protobuf-evidence-20261024.json`](yandex-vector-tile-protobuf-evidence-20261024.json)
and `captures/yandex-only-richer-20261024/runtime-vector/`; two independent
attribute joins agree on all 9,609 features. The later world-coordinate result
supersedes the former missing-TileId/local-geometry limitation below.

**World geometry and graph comparison.** The caller at ELF VA `0x17bb6c8`
receives `TileId` in `x0`, before the decoder at `0x17bbaa0`. The observer now
keeps per-thread nested contexts and records three identities: that wrapper
TileId, the renderer TileId at layer parameters `+0x18`, and the source TileId
at `+0x38` (`0x17c1bdc`). They agree in all eight retained captures. This is a
capture result, not a universal equality assumption for overzoom: the host
rejects missing or different contexts. Field order `x@0, y@4, z@8` is pinned
by JNI and DEX constructor evidence. `0x1b90e78` places the tile on the 30-bit
world grid: tile minimum is `(x,y) << (30-z)` and size is `2^(30-z)`.

The local path decoder consumes shared path lengths and coordinate pairs in
feature order. Each component starts with an absolute pair, followed by deltas;
zero deltas are consumed without appending duplicate vertices. Components reset
independently. Polygon groups retain their outer ring and holes, without an
invented closing vertex; additional polygon paths keep an unknown role. The
centered grid for precision `b` has inclusive bounds `[-2^(b-1), 2^(b-1)-1]`.
Native normalization checks those bounds and divides by **`2^b-1`**, so precision
14 uses extent 16383. The affine is `tileMin + tileSize * normalizedPoint`.
The selected projection is ellipsoidal WGS84, established by the native
tile-selection call and reciprocal inverse; it is not spherical Web Mercator.
Root protobuf field 7 is retained as opaque bytes, not required to be UTF-8:
three of these tiles contain binary GLB headers there.

`tools/adas_bridge/yandex_vector_geometry.py` implements this as a host replay.
All eight native-success inputs (2,164,413 bytes) consume their buffers exactly:
17,895 features, 38,030 components and 214,456 stored coordinate pairs. Hashes,
native instruction evidence, formulas, 29 passing parser/geometry tests and an
independent review with no active findings are pinned in
[`yandex-vector-world-20261024.json`](yandex-vector-world-20261024.json). Full local
receipts live under `captures/yandex-vector-world-20261024/`.

The positive control is a 266-point, 3,299.16 m Kazan route inside the captured
tile bounds. At length-weighted segment-midpoint samples, with a 3 m distance
and 15° **undirected** heading threshold, route→vector coverage is **98.73%**:
3,103.71 m unique (94.08%), 153.56 m ambiguous (4.65%) and 41.89 m unmatched
(1.27%). Median eligible-sample distance is 6.5 cm; p95 is 1.94 m. This is
sampled 2D agreement including ambiguity, not continuous identity or map
accuracy. The 225-point northern route lies outside these tiles and matches
0%; deliberately using the wrong spherical inverse also gives 0%.

The graph comparison uses a **combined corpus of 14 saved files**, containing
10,126 directed records, not a claim about the application's current cache.
`enrichment-review.json` and `.csv` contain literal attributes beside candidate
graph fragments. The review gate requires at least 95% single-candidate sampled
coverage, at most 1% ambiguity, at least 15 m and three samples (5 m step).
It supports **582 canonical graph fragments**, keyed by EdgeData ID plus exact
canonical geometry: in this corpus, 582 unique EdgeData ID bit patterns, 975
directed-edge IDs and 237 renderer feature IDs. Different clipped geometries
stay separate. Another 486 fragments are ambiguous, 25 partial, 492 too short
and 3,973 unmatched. These counts are fragments, not global road counts.

This table is **host research only**, not runtime ADAS enrichment. Build 5 does
not read it. Raw `class`, `fc` and `oneway` retain their original types and
values; no Amap enum or legal direction is inferred. Continuous lane count,
road/lane width, a shared vertical-level namespace, original unclipped extents,
dynamic validity and unsampled gaps remain unknown. Host replay took 5.73 s:
3.07 s for eight vector decodes, 0.80 s for fourteen graph decodes, 0.23 s for
vector→graph comparison and 0.54 s for the review table. These are host timings,
not runtime performance measurements.

The same unchanged build 5 supplied the later route and embedded graph during
normal guidance. Its final runtime cache had nine tiles, 7,650 directed records
and 4,075 vertices; captured/polled counts were both nine, with zero drops,
invalid/oversize inputs, ABI failures or decoder rejections. The final dry-run
trace has **3,442 fires and zero `trace_check` problems**. One Frida spawn
attempt aborted Android's zygote before observer installation; it was not
retried, and normal application launch recovered. The crash record therefore
is not described as empty. The owned emulator for this stage was stopped and
its forwarding removed; other devices and the shared REA installation were
left alone. This result does not establish offline-package availability.

In the earlier richer-interface 30-second observation, the separate offline
graph getters were never called; this is a bounded negative result, not proof they can
never run. Both research scripts detached after their budgets. The owned
emulator 5594 and its forwarding were stopped; 1.88 GB of this task's APK/build
intermediates were removed. The final APK, source pins, raw evidence and reports
remain local; other devices and the shared REA installation were left alone.

Evidence and hashes: `captures/yandex-only-runtime-20261024/receipt.json`,
`no-osm-store.json`, `cleanup.json`,
`captures/yandex-only-interfaces-20261024/interface-report.json`, and
`captures/yandex-only-inference-20261024/{receipt.json,native-graph/receipt.json}`.
Only the owned emulator and its Frida forwarding were removed; the shared REA
installation and other sessions were left alone. All work remains local.

### Generic vector runtime and offline prerequisite (2026-10-24)

The passive online vector source reads native bytes inside Navigator. Its observer pins
exact native instruction sites, preserves the original return addresses and
exception unwinding, and queues bounded packets with three observed TileIds.
The Java decoder/cache retains typed attributes and rejects stale same-tile
sequences. There are no fixed-region coordinates or OSM assets in this path.

**Factory parity is a feed contract, not an all-tiles requirement.** The stock
ADAS channel sends the calculated route's links/points and attributes, then
position/road information around once a second and guidance extras, including
lanes within 3 km. It does not send renderer tiles. These facts do not establish
how far Amap itself preloads its private map data. The complete Yandex route
continues to come from its internal route object, including traffic-dependent
route choice. Neither screenshots nor a second offline calculation supply it.

**Acceptance target requested by the owner: factory-feed parity.** This means
complete supported attributes for the entire selected route before first
publication, consistent path/link identities and progress, factory-equivalent
topic presence/cadence/lifecycle, and correctly scoped dynamic guidance. A
moving 3 km acquisition window and functional class approximation are partial
implementation steps; neither establishes parity. The 3 km lane message does
not bound the whole route, the whole feed, or Amap's internal map acquisition.

**Earlier moving-window implementation, superseded in the active bridge:**
`YandexRouteVectorSource` owns one offscreen map window and requests a bounded
moving corridor ahead of the chosen route. The plan uses a 3 km lookahead and
at most 24 tiles, leaving room in the 32-tile cache for ordinary map activity;
a budget-limited window is explicitly marked truncated. Route/geometry changes,
cancellation and unavailable offline inventory stop the old request sequence.
No visible map camera is changed. The loader registers no JNI callback/Proxy;
only actual captured tile bytes count as arrivals. Pending SDK work cannot be
synchronously canceled through the inspected offscreen interface. Offscreen
acquisition is **implemented but has not been run on Android**; moving its camera
is not itself evidence that the required tile bytes were received.

**Current source architecture: selected route plus an independent attribute provider.**
`AdasBridge` no longer starts the offscreen requester. `DrivingRoute` remains the
source of the selected geometry, route choice, annotations and guidance.
`YandexOfflineCachePath` uses the actual typed SDK listener (compile-only SDK
signatures must not enter the APK); `YandexOfflineGraphFiles` discovers installed
nonlegacy packages from SDK inventory, with inode, metadata and release-observation
fences. No region ID or city is hardcoded. `YandexOfflineAttributes` owns a separate
background worker, coalesces replacements and rejects results from an old route,
geometry, cache path or inventory. It reads the whole chosen route at z15, without
recalculating an alternative offline route. Resource caps are explicit: 4096 tiles,
64 packages, 256 MiB total decompressed input and a cooperative 20-second deadline.

The downloaded `vmap3fb.fb` is a FlatBuffer catalog of ordinary compressed vmap3
protobuf tiles, with a package dictionary. Native byte evidence confirms root
field 4, tile-key packing `(z << 58) | (x << 29) | y`, payload field 2, codec field
10 and dictionary field 11. The retained Kazan package has 1596 keys, including
1414 z15 tiles and no z14. Its SHA-256 is
`053bb300f26fc699b8f6e5d8d972480179ef78872426946caad2f43e8ec35a1e`.
`OfflineVectorPackage` is a bounded read-only reader; decoding uses the separately
pinned standard `com.github.luben:zstd-jni:1.5.7-12` Android dependency, not private
function addresses in the Navigator library. The earlier native-Zstd ABI study
is retained as research; its direct-call JNI was removed from the observer.
Both the independent Python translation and the actual Java frame parser accept
all 1596 stored frames (maximum declared content/window 852196 bytes); the Java
parser also passes 11 boundary cases. The AAR's classes and four Android ABIs
were inspected and pinned; its ARM64 library is packaged in the signed APK.
JNI decompression on Android has not been executed.

`RouteAttributes` separates acquisition from conversion. `RouteVectorAttributes`
streams tiles into whole-route samples and preserves independent class/direction
consensus, conflicts and raw evidence. Geometry association is not a native edge-ID
join. Missing continuous lanes, ownership and access are not filled from renderer
structure fields. Attribute changes add link cuts; merged sub-metre cuts require
consensus over the entire resulting link. The same prepared class also feeds current
road information while guidance is on that route, even without a nearby online graph.

Initial publication waits for this bounded acquisition attempt. A completed partial
or empty attempt does not introduce a full-coverage admission gate: its gaps/reason
remain explicit, and existing route annotations remain available. Any rejected file,
source mutation or processing error discards that attempt's accumulated attributes;
it never publishes the early matches while hiding possible later competitors.
Waiting for the SDK path is bounded to 10 seconds; a terminal path error settles
with an explicit empty result. Prepared static attributes remain frozen for the
route, even if packages update later; `sourceChangedSincePreparation` exposes that
lease, and the next route acquires new data. Fresh installed-map admission remains
independent of this static lease. Runtime status is overwritten
at `files/denza-yandex-route-vector-status.json`, with route/geometry identity, package
versions, byte hashes, tile counts, bounded field evidence and failure/quality reasons.

An independent Python replay of the saved 3.460 km online route reads all **21/21**
planned z15/neighbor tiles from the package. At 791 midpoint samples, candidate coverage
is 100%, with 95.896% unique and 4.104% ambiguous distance. Typed-literal consensus is
98.335% class, 99.783% fc and 99.689% oneway. This includes 98 samples after 3 km.
These are host-oracle measurements, **not execution or acceptance of the new Java
provider**, not continuous coverage and not an Amap crosswalk. Evidence is under
`captures/yandex-fullroute-attributes-20261024/offline-replay/` and
`captures/yandex-vector-enrichment-20261024/route-attributes/`.

The earlier source-only receipt remains at
`captures/yandex-fullroute-attributes-20261024/integrated/receipt.json`. The owner
subsequently requested a build: the complete builder passes Java, native and host
harness checks and produces the signed ARM64 APK pinned in the candidate-13 section.
Compilation exposed three outdated test fixtures (nullable numeric ternary, RTree
padding and the added SDK layout getter); those fixtures were corrected without
weakening production validation. SDK callback linkage, packaged Zstd loading and
Android execution remain unverified; neither APK was installed.

A subsequent actual Java replay uses the exact saved route geometry and the 21
already decompressed tiles, then runs the production tile planner, decoder,
attribute matcher and converter. It completes in 281 ms on this host, with 1010
samples, no unmatched samples and explicit ambiguity/conflicts. The converted
route has eight links: five class 7, one class 9, two unresolved; all retain
laneNum 0 because this source proves no continuous lane count. Optional SDK route
annotations were not synthesized for this geometry-only replay. This exercises
Java conversion, not the Android background provider or native decompression.
Reports and hashes: `captures/yandex-feed13-fullroute-20261024/fullroute-java-check/`
and `decoder-frame-check/` beside it.

`VectorRoadEnrichment` matches whole captured routing fragments with explicit
geometric ambiguity and level checks. Supported `oneway` literals are checked
against the directed graph; renderer vertex order is not legal direction.
Individual attributes are independent: missing lane count or an undecoded
one-way literal does not discard a usable road-class decision. Original class,
fc and feature/tile identities stay in the diagnostic evidence.

`YandexRoadClass` defines the following **functional approximations**, never
copies enum numbers and never infers administrative status from road importance:

| Literal driving class (also `_link`) with agreeing integer fc | Amap result | Decision |
| --- | --- | --- |
| 1 | no new value | Motorway versus urban expressway remains unresolved |
| 2, 3, 4 | 7 MainRoad | Reduce to main-road function |
| 5, 6 | 8 SecondaryRoad | Reduce to district/local-road function |
| 7, 8, 9 | 9 CommonRoad | Ordinary/access/field-road function; access is separate |
| Pedestrian, bicycle, construction or unknown category | no new value | Numeric fc alone does not establish a driving road |
| Missing, differently typed or conflicting fc | no new value | Preserve the source conflict explicitly |

This is a converter decision based on [Yandex's functional classification](https://yandex.ru/support/nmaps/ru/cat_road_rul2)
and [Amap's named categories](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/enums/RoadClass.html),
not a recovered factory crosswalk or proof of NOA eligibility. The actual retained
vector corpus covers numeric classes 4–8 and the `4_link`/`5_link` variants;
the other numeric cases have source tests, not captured-tile acceptance. No
new rule emits Amap 0/6, lanes, ownership, formway or access. The earlier
highway/speed estimate and junction-sign-width to continuous-lane-count rule are
removed in this follow-up; unresolved vector fields must not silently fall back
to those estimates. Actual junction lane guidance and known graph fields remain.

At the owner's request, the offline prerequisite is deliberately minimal for
this iteration: fresh SDK inventory with **at least one usable downloaded
region**, independent of other recognised but unusable regions. It does not
prove that this region covers the route. The production state is `AVAILABLE`,
`mode=inventory_only`, `completeRouteCoverageChecked=false`, with no fabricated
coverage fingerprint. Missing/stale inventory still suspends transmission;
route ID, generation and geometry identity still reject queued old observations.
Hardware, genuine ACK and sender ownership checks are unchanged. The earlier
mandatory full-route coverage gate is superseded; it must not leave this build
permanently waiting for an unimplemented coverage provider.

The full-route coverage investigation is parked. An alternate OFFLINE route may
legitimately differ because of traffic and is not an admission condition.
Its diagnostic prototype remains outside APK sources in
`offline-route-diagnostic/`. The pinned SDK's OFFLINE `matchRoute` implementation
at `0x20f7294` is an empty result (`1f7d00a9c0035fd6`), and regional `driving.fb`
borders are graph connections rather than coverage polygons. These limitations
are recorded, not prerequisites for continuing attribute extraction.

**Earlier first-publication gap; source fix is not yet runtime-accepted.** Later
tiles can improve current-road dynamic information, but the published static
link table remains unchanged. This is not repaired by repeatedly replacing the
path as tiles arrive. The factory `DriveRouteManager.G()` (`g.java:93–104`)
skips its normal route update when `PathInfo.pathID` is unchanged; explicit
same-ID resends on switch/selection/error are a different branch and do not
prove metadata-update semantics. Keep stable path identities and obtain the
full selected route's attributes before its first publication. The provider above
now attempts that acquisition before first publication; it does not prove full
attribute coverage or remove unresolved source semantics. Complete static
attributes, the motorway/urban expressway distinction and continuous lane
counts remain unfinished; the current source implementation is **not parity**.

Earlier decoder/lifecycle host receipts are under ignored
`captures/yandex-vector-enrichment-20261024/` and
`captures/yandex-vector-runtime-20261024/`. They predate the minimal inventory
policy, class approximation and the subsequently retired automatic requester.
The owner first requested source-only changes, then authorized the complete
build pinned above. Current source tests and host replays now run successfully;
earlier receipts remain historical, and no host result establishes Android or
car acceptance. Neither installation nor push was performed for this build.

### Regional offline graph (2026-10-24)

An owner-approved download of Yandex region **43 (Kazan)** supplied the actual
12,311,144-byte `driving.fb`, version `26.10.06-1`, SHA-256
`58747160de74c00d508231a49f20b8bb94144e267fe926653d5fbe853cc5a2b4`.
Native reader code and the saved FlatBuffer expose the graph throughout this
file, independently of the receiver's current position. Its RTree supports
arbitrary host bounding-box queries returning directed-index candidates;
exact shapes must refine those candidates. The stored extent is approximately
48.816245–49.380242° E, 55.602176–55.930512° N, not an administrative-region
polygon or proof of routing eligibility everywhere inside that rectangle.

The file contains **298,104 directed indices**, **138,930 vertices** and
**156,842 attribute entries**. The `isBase`/`hasEdgeData`/rank/reverse-index path
admits 297,181 base edges: 156,842 canonical forms and 140,339 reversed forms.
The remaining **923 indices are rejected** by the host attribute reader; their
meaning is not guessed. All admitted full shapes decode, totalling 944,705
points including duplicates across directed edges. Native vertex coordinates
are bbox minima plus packed integer offsets at 10⁻⁶ degree precision;
intermediate shape points use an Elias–Fano index and Rice-coded signed deltas.
Geometry reversal does not establish legal driving direction.

The constructor and getters identify raw arrays named `types`, `structTypes`,
`categories` and `accessIdMaskIndices`/`accessIdMasks`. Their **numeric enum
labels and access-bit meanings remain unknown**. There are 23 raw access-mask
bytes. Raw type 9 occurs in five attribute entries, while the annotation
wrapper clamps types ≥8 to zero; raw values must therefore be retained
separately. No continuous physical lane count, lane width or stock-ADAS enum
conversion has been established. The named OSRM `GetLaneData`,
`GetTurnDescription` and `GetEdgesInBox` implementations inspected here are
error stubs, not usable lookup APIs.

Independent host re-encoders reproduce the type, structure and access-index
Elias–Fano/payload bits and all **5,932,608 shape-payload bits** exactly. All
140,339 reversed shapes equal their reversed canonical geometry. The captured
short OFFLINE and ONLINE routes have the same 220 coordinate pairs, four lane
sign objects and 219 speed-limit entries; reported distances differ by 5.4 cm.
Comparing the ONLINE route with full regional shapes at 791 length-weighted
segment samples gives **100% sampled 2D coverage**, comprising **83.54% unique
and 16.46% ambiguous** at 3 m distance, 15° undirected heading and a 5 m step.
Maximum eligible-sample distance is 1.54 cm; the two route endpoints are within
1.58 cm and 0.89 cm of a full shape. This is geometric agreement for one route,
not exact route-edge IDs, continuous coverage or an access/direction proof.

A further bounded native pass identifies the root's field 5 as `VehicleTypes`:
`DataFacade` at `0x23e3398` calls its constructor at `0x315d148`. The saved
regional table at file offset `0x4b8a68` contains one raw ID, `[0]`; the
constructor accepts IDs below 5 and maps IDs to profile indices. The turn-weight
consumer at `0x3157a74` bounds its profile index by the reader's profile count.
This does **not** identify access-mask bits with those indices.

Separately, the public driving `VehicleType` formatter at `0x245bd28` maps
0/1/2/3 to Default/Taxi/Truck/Moto through relocation table `0x39f6fa8`;
its validator at `0x2955540` requires an ordinal below 4. The still-missing
link is the conversion from this public enum to the regional raw vehicle ID.
The next bounded targets are JNI resolvers `0x242e188` and `0x242eb48`, then
that conversion's callers into the regional reader. Equal numeric zeroes do
not prove regional ID 0 is Default. No class, lane or access attribute was
promoted from this finding. Three selected REA functions, raw code/table bytes,
address mappings and cleanup are retained in
`native-reader/semantics/receipt.json` under the evidence directory below.

The Python decoder is **host research**. A bounded Java regional reader and
whole-route raw association were subsequently added as unexecuted source; they
do not populate ADAS enum fields and are not the active vector attribute provider.
Automatic package availability wherever Navigator routes, lifecycle/update
handling and exact archive-edge association remain open. Java-only OFFLINE
routing succeeded on the owned emulator. An earlier native observer attempt
crashed in the Frida agent before its first event; its cause is unisolated and
the prepared observer is not runtime-accepted. No retry or new ADAS publisher
is implied by the host result.

Local evidence is under `captures/yandex-offline-region-20261024/`.
`native-reader/manifest.json` pins the package, native source, original code
slices, VA↔file mappings, ABI/range gates, verification and cleanup receipts.
`decode_package_arrays.py` and `verify_package.py` reproduce the array, shape
and short-route checks; `host-package/package_spatial.py` owns the separate
bounded RTree transcription. The focused REA session is closed, its temporary
project is gone, and the shared tool installation is preserved. Everything
remains local.

## Location clock domain (2026-10-24)

The earlier module assumption that Yandex `Location.relativeTimestamp` was
Android `elapsedRealtime` was wrong. The pinned native library's wrapper at
ELF VA **`0x2bb0230`** passes clock ID **1 (`CLOCK_MONOTONIC`)** to imported
`clock_gettime` at `0x2bb0244` and returns `seconds * 10⁹ + nanoseconds`.
The exported sensor callback `NativeLocationSubscriptionWrapper.onLocationReceived`
calls it at `0x19a7144`, divides by 10⁶ and stores a receipt-relative timestamp.
A separate sensor-relative timestamp subtracts the passed age; these are
distinct fields, not a claim about the original GNSS measurement time.
An independent native MapKit `Location` producer calls the same wrapper at
`0x1cb4698`, divides by 10⁶ and stores `Location+0x98` at `0x1cb46a8`.

The native-to-Java converter loads the absolute/relative pair at `0x1bdbfc4`
from `Location+0x90/+0x98` and passes the relative long unchanged to the Java
constructor at `0x1bdc014`. `Location.getRelativeTimestamp()` returns that field.
The SDK's `DefaultClocks` and `ClockImpl` relative-now methods independently
resolve to `0x193d394`, the same monotonic nanoseconds divided by 10⁶, without
an SDK relative offset. The inspected `CLOCK_BOOTTIME(7)` helper at
`0x1b3c4a0` belongs to a separate `TestClockImpl` path; the presence of both
clocks in the library does not make them interchangeable.

The car's retained build 7 window contains **56 FINE fixes, all matching the
selected route, and zero reported fresh fixes**. Subtracting their relative
timestamps from `elapsedRealtime` produces ages of 36,757,981–36,758,092 ms,
while wall time minus their absolute timestamps is 1–113 ms (median 33 ms).
Removing that wall age leaves a nearly constant 36,757,977–36,757,980 ms
domain difference. This is consistent with accumulated suspend time; the
original capture did not record `uptimeMillis` directly. It is not evidence
that the GNSS fix was ten hours old. These build 7 age/freshness fields are
invalid for this car window and must not be used as acceptance evidence.

The matching Android source clock is **`SystemClock.uptimeMillis()`**, or
`System.nanoTime()/1_000_000`. Correct freshness first compares source time
with that clock and retains the driver's **1 s** and snapshot's **2.5 s**
limits, including rejection of invalid/future stamps. A source timestamp
identity mapped into the receiver's `elapsedRealtime` domain must be anchored
once; rereading a held fix cannot refresh it. That anchor then ages through
suspend. An unseen pre-sleep fix cannot be validated from the current
clock difference alone; absolute timestamp age can provide an additional
conservative veto, never a replacement for the monotonic check. The observed
36,757,978 ms must not become a guessed calibration constant.

Exact source SHA, original native slices, instruction bytes, VA↔file mappings,
RTTI/vtable identities, focused REA results and limitations are retained in
`captures/yandex-location-clock-20261024/native-clock/manifest.json`.
The independent car arithmetic receipt is
`captures/yandex-car-trip-20261024/clock-host/receipt.json`. All three owned
REA sessions are closed and the shared installation is preserved. This host
finding alone does not establish car acceptance. The later build 8 installation
does: its APK hash is `a66ed9676188303ac49744626fd5b9cc4f5fc2fc0f5ad76abb8db0cf5f63f6b2`,
verified against the device. `installed-build8.json` and
`build8-progress-initial.jsonl` in the car-trip directory retain an initial
49.0 s window with 43 FINE fixes, all fresh, aged 2–54 ms, plus 1,472 real
passive packets. The fix is confirmed only for this window; it does not
establish moving guidance, continuous route publication or a cause of the
car's earlier NOA behavior. The full publisher remains compiled off.

## Native route archive

`DrivingRouter.routeSerializer().save(route)` yielded an 11,847-byte native
archive for the initial city fixture. The envelope contains version 20, the
route UUID, an 11,496-byte protobuf body and a 303-byte unclassified tail.
Descriptor and traits bytes occur verbatim inside it. All 22 decoded HD lines
are exact contiguous spans of public geometry; the archive's 286 SD edges
expand to the public 401 edges. Thus these HD lines do not establish separate
lane geometry. The 1,400-byte opaque descriptor block remains unidentified.
No road-class, per-road lane-count or access semantics were assigned by guessing
a numeric tag. Exact hashes, offsets and reproduction are retained in
[`yandex-native-route-evidence-20261023.json`](yandex-native-route-evidence-20261023.json).

## Car trip recorder (2026-10-24)

Build 8 has replaced build 7 on the car; its installed identity and bounded
clock check are recorded [above](#location-clock-domain-2026-10-24).
Build 7 (`29.8.1-YandexHUD.7`, code `739494307`, SHA-256
`95ee1036af3b1142a2fcf5e5b3ce6b2becdb85bd4a866f7da9a105462ffa2b67`)
was installed on the owner's car. A bounded parked-guidance check kept the same
PID for 115.5 s, with 104 guidance samples, two offline-region metadata samples,
3,468 real passive packets and an active selected route. No new Navigator crash
was observed in that window. It verifies recording during parked guidance,
not moving-trip behavior. A separate temporary probe's parked selection acknowledgement
is recorded below; it does not enable the full APK's publisher.
The check also exposed a [location clock-domain bug](#location-clock-domain-2026-10-24):
build 7 rejected all 56 FINE fixes in that window. Recording acceptance therefore
does not establish correct freshness or dry-run progress.

Build 6 (`29.8.1-YandexHUD.6`, code `739494306`, SHA-256
`4debfd9e70fa84aaaef55ec109b062434d9f724797161d3c622c105b706c8ae6`)
is **withdrawn** after a car SIGABRT about eight seconds after launch. The
retained CheckJNI trace names `CallVoidMethodV` and
`OfflineCacheManager.PathGetterListener.onPathReceived(String)`; the recorder
used a Java Proxy for that callback. Build 7 removes the Proxy and path request;
the periodic offline evidence retains region state/release metadata.

The shared opt-in marker `files/denza-drive-capture.enabled` is checked at
construction; emulators always record. `DriveEvidence` retains eight selected
route snapshots of at most 2 MiB, plus two 8 MiB JSONL files for 1 Hz guidance,
existing passive ADAS packets/status and offline-region state/release metadata
every 60 s. Raw graph retention is capped at 128 files / 64 MiB. Details and
paths are in the [module README](../../experiments/yandex-hud-module/README.md#car-trip-recorder-2026-10-24).

The short pre-abort receipt contains two graph tiles / 364 directed edges,
24 unresolved boundary endpoints, zero collector rejection/self-test failure,
and real passive `13/0x8002`, `13/0x8003`, `13/0x8005` packets. It proves those
observations only. The full APK's publisher stays **HARD_OFF**
(`SomeIpPublisher.LIVE_ALLOWED=false`); this build 6 receipt contains no route publication
or acceptance. The separate parked probe below establishes a narrower later result.
Evidence: `captures/yandex-car-trip-20261024/{installed-build6.json,car-status.json,car-progress.jsonl,after-crash.txt}`.
The replacement check is retained in the same directory as
`installed-build7.json`, `build7-progress.jsonl`, `build7-crash-buffer.txt` and
`car-build7-guidance.png`. Progress files append across process restarts;
filter by `sourceSessionId` before counting the build 7 window.

### Post-drive read-only review (2026-10-24)

The owner reconnected after the drive. The existing `127.0.0.1:5555` transport
was used for read-only pulls; there was no install, restart, buffer clearing,
new subscriber or publication. The installed base APK still hashes to build 8's
`a66ed9676188303ac49744626fd5b9cc4f5fc2fc0f5ad76abb8db0cf5f63f6b2`.
The pull retained a 26,684,416-byte tar, crash buffer, package identity and
Android process-exit history in `captures/yandex-drive-review-20261024/`.
Files were copied while the recorder ran; all retained JSONL rows parse, but
this is a retained-window snapshot, not a complete trip archive.

All times here are Europe/Moscow. The shared progress ring has 47,530 rows:
45,605 raw ADAS packets, 1,842 guidance samples, 33 offline-cache snapshots
and 50 listener lifecycle records. It spans the tail of one process
(10:42:19–10:43:59) and the latest process (11:16:24–11:47:26). The exit history
explicitly records the previous main process being force-stopped by quickboot
at 10:43:59.908. The recorded native crashes at 09:51/09:52 belong to the
withdrawn offline-cache callback build; there is no newer Navigator crash in
these retained crash/exit records.

The latest session contains **1,362 active guidance samples, all FINE and
fresh**, with age 2–364 ms (median 23 ms, 95th percentile 52 ms), no route-ID
mismatch and no guidance gap above five seconds. Every active sample has a
speed limit, ranging from 20 to 90 km/h. Lane hints have two, three or four
lanes where a nearby sign exists; these do not prove continuous lane count.
One sampled reroute spans 11:28:51.510–11:28:58.717, about 7.2 seconds between
first rerouting observation and restored guidance, not an exact request latency.

Passive packets are exclusively service 13: 15,203 PilotStatus, 15,201 alarm
and 15,201 mode messages. The presence-aware decode finds explicit ICC-active
field 4 = 1 in 124 packets, in two observed episodes:
11:26:20.079–11:26:24.162 and 11:26:29.858–11:26:38.165, about 12.39 seconds
together. DNP field 5 is absent in every PilotStatus packet; this is not an
explicitly transmitted zero. Mode 2 is present in every mode packet and says
family only. No service-44 ACK or PathMatch is retained despite successful
subscriptions. Build 8's publication is HARD_OFF, so these packets cannot be
interpreted as answers to our Yandex route or a negative route-acceptance test.

Two recording limitations are now physical findings. Raw packets occupy
**77.44% of the shared ring's bytes** and evicted the earlier guidance while
route snapshots from 10:11/10:27 remain. Also, the display-owned listener closed
at 11:21:29.681 and reopened through 11:21:55.761: 26 guidance samples remain
in that gap, with no packets. Its final closure at 11:40:58 is after guidance
ended; that later silent tail must not be called an ECU outage. The code ties
this listener to Activity visibility. The separate live client in candidate 9
already had an independent lifetime, but recorded only SELECTED replies.
Local candidate 10 records every actual callback from that client before ACK
filtering, including PathMatch, pilot status and null-payload availability.
Its raw packet ring is separate from guidance; rare route replies and service
availability are copied to both streams. Each ring is two 8 MiB files.
Independent raw/critical queue budgets and drop/post/write counters expose
recording loss. Receiver name, arm ID, process UUID and original elapsed
callback time keep duplicate observations and sessions distinguishable.
The 49-check stream regression floods the raw ring, verifies rare-reply
retention even when raw writes fail, and distinguishes null from empty.
No extra subscriber or new sending behavior is introduced. The unarmed
display-owned listener still has its original visibility-dependent lifecycle.

The latest two route snapshots have all 1,307 speed-limit entries and 12 lane
signs, but no proved continuous lane count, road class or per-link access.
All 50 saved graph tiles decode; field 11 is absent from all 4,245 EdgeData
records. Eight files were first written in the current process and 42 earlier;
deduplication means an older file may also have been observed this time.
The final runtime cache has 16 tiles after 30 evictions. Its active hashes were
not recorded, so the mixed disk corpus's 98.51%/96.90% sampled geometric
coverage cannot establish runtime route coverage. A bounded cache-membership
timeline is a useful next diagnostic addition; candidate 10 does not add it.
Downloaded Moscow data is `OUTDATED`, and both current routes are online.
**Corrected by the pre-test source review:** static route link `speedLimit=0`
is written literally by `StockRouteJson.link`, independently of
`roadSpeedLimit`. The disabled flag instead suppresses the dynamic
`TrafficInfo.SpdLmtSpeedValue`, despite complete SDK speed arrays. The original
graph-analysis summary conflated these two fields; use this correction when
interpreting that retained report. Route 2 metadata distance is 5,435.728 m
versus its polyline's 5,930.485 m; the cause is still unproved. None of these
findings can explain rejection of a Yandex feed in this trip: it was not sent.

Evidence and reproducible summaries: `pull-receipt.json`, `additional-pull.json`,
`guidance-analysis/{analyze.py,report.json}`, `adas-analysis/` and
`graph-analysis/` in the capture directory. Graph cache counts and mixed-process
disk tiles must be kept separate; a disk-union replay is not the runtime cache.

### Pre-test firmware review (2026-10-24)

The owner's firmware-review summary was compared with current sources and the
factory Beijing bench. SHA-256 checks bind `LiveMapBridge`, `StockFeed`,
`RouteConverter`, `NavigatorDriver` and `Pb` to the compiled build-10 report.
This is the historical build-10 review. Build 11 subsequently corrected the
limit and lane gaps below and was installed; [its receipt](#build-11-completeness-and-car-test-2026-10-24)
separates those fixes from remaining gaps and car acceptance.

The strongest actionable gap is the dynamic legal limit. `NavigatorDriver`
already reads the current Yandex limit, with route/observed-graph fallbacks.
`StockFeed.onCurrentRoadSpeed` overwrites it with zero because both integration
wrappers leave `Config.roadSpeedLimit=false`. That configuration comment relies
on an older incomplete Russian stock-map package. The factory Beijing trace
actually carries legal limits in `TrafficInfo.SpdLmtSpeedValue`; static route
links still carry zero. Enabling real dynamic limits and testing their change
and disappearance is a separate correction from route serialization.

Lane signs also lose information inside our pipeline: `RouteSnapshot.lane`
exports each lane's directions and highlighted direction, but
`YandexRoute.from` retains only sign position and lane count. `StockFeed` never
sends `NextIntersectionLanes` or `Intersection`; their topic constants have no
message schemas in `Topics.schema`. `scheduleAheadLanes` always sends `[]`.
Actual sign directions should be carried to the matching factory messages at
the correct route offsets, without claiming continuous physical lane counts.
Traffic incidents remain empty and RoadFacilities has no current sender.
These are content/implementation gaps, not evidence of missing Yandex coverage.

The class-0/6 highway/limit rule, highway ownership/direction and spreading an
endpoint sign's width over its link were conversion assumptions in historical
builds. They are removed from local Java and Python source. Factory correlation
does not establish equivalence of Yandex's annotations or ADAS eligibility.
`historySpeed` currently derives from available typical/free-flow speed; it
must not be replaced with a fixed value merely correlated with a past offer.

Field presence must remain explicit in received-status interpretation: absent
DNP is unknown, not a measured off state; missing selection status is not an
ACK. Outbound encoding still follows the original proto3 serializer, which
omits ordinary scalar defaults; manufacturing explicit zeros would not improve
fidelity. Real selection confirmation is already required in the live path.

The live lifecycle is intentionally narrower than the factory bench: it admits
dynamic packets only after a real same-path ACK and closes after the bounded
navigation-stop message. It does not reproduce the factory's continuous idle
feed or complete arrival sequence. Do not remove those controls to make an
inventory comparison pass; document the difference in the comparison.

Before a NOA-oriented drive, prioritize the dynamic legal-limit path, retained
lane-sign content, and a byte/timing comparison covering start, guidance,
reroute and stop. A stationary route/ACK/position test can establish transport
separately, but does not establish a complete factory-equivalent feed or NOA.
The ADS application image would help inspect receiver decisions; its absence
does not prevent correcting these known producer-side gaps. No necessary and
sufficient NOA field set has been established.

### Parked route selection with a separate probe (2026-10-24)

With the owner confirming P, a separate temporary registered APK
(`dev.denza.adasparkedprobe`) published one route notification on service 43,
event `0x8002`, followed by one path-selection notification on service 45,
event `0x8001`. Each Binder fire returned 0. Its input was the real current
Yandex route converted explicitly to WGS84: 25,357.68 m, 11 segments, 37 links,
859 serialized points, one 58,526-byte route protobuf and an 8-byte selection.
The fresh pathId was `1118166796`. Road class, formway, ownership and continuous
lane facts were not inferred for the probe. Synthetic link IDs and serializer
compatibility defaults remain subject to the receiver's interpretation.

The physical callback trace contains an explicit `44/0x8002 MMTPathSelected`
notification with `pathIDResp=1118166796` and `statusCode=200`. Twenty-seven
milliseconds later it contains `44/0x8001 PathMatch` with one RouteInfo for the
same pathId and `routeEndIdx=858`. The generated firmware schema proves that
RouteInfo field 4 is **routeEndIdx**, while field 5 is **statusCode**. In this
packet, outer `code`, item `statusCode`, `totalNOADistance` and `routeBeginIdx`
are absent. Generated proto3 getters return zero for those absent scalars;
the first host summary printed those defaults. The presence-aware replay
supersedes that presentation: no PathMatch success code or eligible NOA
distance was observed. End index 858 equals the last index of the 859-point
payload, which is numerical consistency only.

The stock `PathMatchProxy` logs outer `getCode()` and copies each RouteInfo's
getter values into `EHPtoNav` (`LM/k/h/e0/c/j/j0/q.java:26-28`). Its registered
`RouteCityOddController` callback only reads a flag and otherwise ignores that
list (`LM/k/h/e0/d/d.java:128-130`). Other inspected `RouteOddSegUtil` uses map
begin/end indices onto the route; no branch interprets `code=0`, `statusCode=0`
or `totalNOADistance=0` as success, failure or eligible kilometres. The effective
getter values are zero, but that does not establish their receiver semantics.
In contrast, the selection proxy explicitly treats status 200 as its success
case (`LM/k/h/e0/c/j/j0/t.java:13`).

This proves a protocol-level selection acknowledgement for the supplied path
and a subsequent matching-ID response. It does not prove continuous navigation,
map matching accuracy, NOA availability or activation, moving-trip acceptance,
or acceptance of lane/class attributes. No position, navigation-status, driving
switch, resend or route-clear feed was published. The full Navigator build 7
publisher remains **HARD_OFF**; the successful sender was this separate probe.

The first two attempts are retained explicitly as **no server start or fire**:
the `app_process` attempt failed because ContextImpl could not associate its
IApplicationThread with a registered application; the first APK attempt bound
successfully but rejected its 5/8 subscription result before publication. The
three unsupported subscriptions were optional IVI loopbacks (services 7 and
`0x8202`). Version 4 requires successful subscriptions to the five exact ADAS
answer topics (`13/0x8002`, `13/0x8003`, `13/0x8005`, `44/0x8002`, `44/0x8001`),
with an individual-topic mask; duplicates cannot satisfy the gate.

The probe used unique own server and client Binder sessions, listened for eight
seconds, stopped its own services 45/43 with result 0, closed its own client and
unbound. It was then uninstalled and the car released by the parent session.
The stock map was not stopped or modified. Source is
[`ParkedRouteProbe.java`](../../tools/adas_bridge/ParkedRouteProbe.java) and
[`ProbeActivity.java`](../../tools/adas_bridge/parked_probe/ProbeActivity.java).
Retained evidence: `captures/yandex-car-trip-20261024/parked-probe{,-apk,-v4}.jsonl`,
`parked-probe-v4-presence.json` and `parked-physical-presence-receipt.json`.
The independent host replay preserves raw protobuf bytes and field presence;
it agrees with the existing schema-driven decoder on both service-44 packets.
The compact [evidence manifest](yandex-parked-selection-20261024.json) pins the
raw trace, protobuf packets, consumer sources, prior no-fire attempts and four
passing presence/boundary tests.

## ADAS status observer

The car does report availability separately from route acknowledgement:

| Source | Observed meaning | Limitation |
| --- | --- | --- |
| `13/0x8002 PilotStatus`, DNP/ICC/ACC | `0` off/not offered, `1` active, `2` available | Unknown enum values stay unknown; a value is not a reason for refusal |
| `13/0x8005 NewBroadcastInfo.noaMode` | `1` highway NOA, `2` city NOA | Family only, never proof of availability |
| `44/0x8002 MMTPathSelected` | Stock callback fires on status 200, matching path confirms selection | Does not validate lanes, map completeness or NOA eligibility |
| `44/0x8001 PathMatch` | Schema includes route ID, NOA distance/range and status code; the parked probe transmitted only pathId and end index | First parked observation retained above; absent status/distance are not measured zero or success, and exact receiver semantics remain unresolved |
| `13/0x8003` and stock TOR notices | Raw alarm/notice codes and known notification text | Display only; do not synthesize pilot state from a notice |

**Candidate 9 correction:** the earlier overlay counted membership in
`PathMatch.routeInfoList` as a successful match. The parked reply contains a
path ID and end index but no outer/item status or NOA distance, so that rule
could show an unsupported double tick. The corrected decoder retains nullable
field presence and the raw uint64 values. Only explicit SELECTED status 200
has a proved success contract; PATH_MATCH remains unclassified, including an
explicit 200, until its own result semantics are established. The overlay
therefore stays at selection for that captured reply and ignores receipts
from an earlier connection generation. Host regression includes the exact
parked packet bytes, missing/failed statuses and equal-time reconnects.

`tools/adas_bridge/status.py` decodes saved stock logs or an explicitly selected
ADB logcat stream. The live path only executes `adb -s SERIAL logcat`, without
clearing buffers or modifying the car. It prints an initial unknown state,
keeps source PIDs separate, preserves raw values and reports the age of the last
observation. A silence timeout is the observer's display policy, not a proven
ECU heartbeat interval. FID values are a different enum: the recorded
`ADAS_DNP_STATE` 3/6 accompanies SOME/IP DNP 2/1; they are not interchangeable.

```sh
python3 -m tools.adas_bridge.status --adb-serial 127.0.0.1:5555 --seconds 60
python3 -m tools.adas_bridge.status --input path/to/saved.log --jsonl
```

The saved drive-02 decoder found ten availability observations, ten active
observations and fifteen route confirmations. The live read-only 25-second
logcat check on 2026-10-23 produced no new matching lines; this establishes no
current pilot state. The standalone [`adas-status-probe`](../../experiments/adas-status-probe/README.md)
subscribes directly instead of relying on the stock application's logging. It
uses a unique own client session, events from services 13/44 only, and no server
or driving-control interface. Binder callbacks enqueue bounded work and return
immediately; receipt time is captured before queueing. Main-thread rendering
ages an immutable snapshot even if decode/journal work is delayed. Reconnects
invalidate the previous connection's observation. A compact Russian overlay
shows fresh NOA state, last family, ICC, last route ACK and response age. The
detailed Activity retains raw values. Missing/stale data never becomes a negative
availability claim. Demo injection is accepted only on emulator hardware and
visibly marked. The Stop button removes the overlay and releases only its own
subscriptions. Raw logs rotate at two 4-MiB files; a separate two-file 512-KiB
change journal records semantic transitions without counter/timestamp noise.

Direct car acceptance then succeeded: build-05 decoded 1371 real packets over
45.6 seconds (457 each of pilot, alarm, NOA mode), zero decoding errors and zero
queue drops. DNP/ICC/ACC were 0, mode 2; no service-44 response arrived in that
window. The overlay remained visible over the normal launcher. Installed APK
hash matched the build, and the crash buffer was unchanged. Initial build-04
alarm failures were traced to fields 6/9 being doubles; all 384 retained native
packets now pass replay. Build-06 enlarges the overlay to 40-pixel text and
720-pixel width with five short Russian lines. Receipt and final installation
identity: [`observer-evidence-20261023.json`](observer-evidence-20261023.json).

## Field contract (2026-10-24)

[`field-contract-20261024.json`](field-contract-20261024.json) turns the owner's rule
into data: every value the bridge can send to ADAS is a decision, and a missing or
incompatible Yandex value is never quietly sent as an unknown, 0 or −1. There is one row
per field of every outbound topic, per key of the route JSON and of the 3 km lane beans,
per emission (cadence and gating), for the two vehicle-property side channels the stock
writes (`0x4CA0004B`, `0x43E0003A`), for the two ADAS requests (13/0x0001, 13/0x0002),
and for the lifecycle and input rules behind the values. Each row gives:
- the stock's meaning of the field and where that is proved;
- what the factory Beijing bench and the car's stock map on OSM sent;
- what we send, from which code and on which trigger;
- the provenance: YANDEX, PLATFORM, DERIVED, DEFAULT, CONSTANT or NOT_SENT, with one
  rule id per branch;
- the mark against the stock;
- DECIDED with a reason and evidence, or OPEN with the experiment that decides it: H0–H6
  from the build-12 analysis, or a named test (D-HWY, D-CITY, BENCH-FB, RE-LM,
  ALIGN-STOCK, TRACE-SIDE, P-CLOSE, EMU);
- the NOA risk.

Code is cited at `6f97e3bb`, the engine rewrite (`134caa8e`..`6f97e3bb`), with a text
anchor, and the anchor is the authority. The rewrite has not been run on the car. Rows
whose behaviour it brought to the stock's carry the engine commit as evidence. Their
`today` values are build 12's, recorded before the rewrite.

| | Rows | Decided | Open |
| --- | --- | --- | --- |
| Matches the stock | 159 (61 are fields the stock builder never sets) | 157 | 2 |
| Differs from the stock | 75 | 3 (policy) | 72 |
| Stock unknown | 18 | 0 | 18 |

The engine rewrite moved seven rows to *matches the stock*:
- the route goes out once per Yandex route ID (`naviSDRoute`), and is resent 300 ms after
  DNP comes back on;
- RouteType 99 marks a real recalculation by Yandex, not a graph refresh;
- position, TrafficInfo, tunnel and region go out at the stock's once a second in LIVE;
- the remaining distance in TrafficInfo comes from the fix it travels with.

The three decided differences are guard policy:
- no idle stream;
- no stale or foreign-path position;
- no idle status outside the stock's end sequence. Every end that may still send now
  sends that sequence: 110 ×3 after an arrival, then 100 ×3.

The open rows carry 19 high, 26 medium and 47 low NOA risks. These come first:
- **Frame (H1).** Route points, positions, lights and `mapProvider=1` claim the Amap SDK
  frame, but both hosts send WGS-84 (`FieldDefaults.FRAME`).
- **Road semantics (H2, D-HWY).**
  - On links, roadclass, formway and ownership are −1, adminCode is 0, and direction is
    0, which the export reads as two-way (DRT raw 3 → 0).
  - Region is 0.
  - TrafficInfo class and position ownership are −1, the stock's *unmatched* markers,
    on a path that the same packets declare matched.
  - historySpeed is the section's free-flow speed.
  - SpdLmtSpeedValue comes from Yandex, against [Safety](#safety).
  - The ramp distance is −1.

  The sourceless values among these (frame, legal limit, facilities, vehicle-speed
  source, region, unmatched class and ownership) are set in `FieldDefaults`, and every
  trace header names them.
- **Continuity (H3, P-CLOSE, H4).**
  - A prerequisite gap now pauses on the same path instead of dropping it. Build 12 was
    silent for 42 s at P→D; the gap itself is still unmeasured.
  - A guarded close now sends the stock's end statuses but not its pathId −1
    positions. An end that may not send, such as a removed arm, still leaves the route.
    P-CLOSE asks whether ADAS releases the route on those statuses.
  - pathID stays a wall-clock id, fresh for each attempt.
- **Cadence (H5).** vehicleSpeed is the GNSS speed (`FieldDefaults.VEHICLE_SPEED`). Off
  the route, the position indices hold their last placement. The stock freezes them at
  the link end (ALIGN-STOCK).

The file records fifteen contradictions between docs, code and the oracle as entries of
their own:
- linktype is −1 in the RouteConverter javadoc and in this README, but 0 in the code;
- the oracle calls direction 0 "unknown";
- `mapProvider=1` is sent with WGS-84 points;
- Safety says SpdLmtSpeedValue 0, but `FieldDefaults` enables the Yandex limit for both
  hosts;
- lane types 0/1/2 break StockMessages' promise and the factory's 255;
- `stock.py` lacks the `completeAttributes` gate, the 64-lane cap and the graph-name rule;
- `naviVoiceCommand ""` is undocumented;
- the "unknown, not silently false" flags are sent as false;
- pathID is described as a "hash of routeId";
- "Position, 1 Hz" (resolved by the engine rewrite, `646add68`);
- the unmatched markers on a matched path;
- FacilityType versus `naviFacilityType`;
- laneNum was taken from lane signs (resolved in local source: signs remain
  point-scoped guidance and cuts, with no continuous lane-count promotion);
- the historySpeed comment;
- the stale route left on close (end statuses sent since `ea0f824d`; release unproved).

`oracle.FieldContractTest` (Gradle, via `FieldContractChecks`) and
`tools/adas_bridge/tests/test_field_contract.py` fail when:
- an encoder field has no row;
- a row names a field or a code anchor that no longer exists;
- a row that differs from the stock is decided without evidence;
- an open row names no known experiment.

Change a field and its row in the same commit.

## Parked experiment kit (2026-10-24)

To find the NOA gap by controlled experiment rather than more drives, a host generator
(`tools/adas_bridge/scenario.py`), a disposable on-device player
(`experiments/adas-parked-player-probe`, app id `dev.denza.adasplayer.probe`,
`-Pexperiments`) and a host runner (`tools/adas_bridge/parked_session.py`) play, at the
start position with the car parked, the stock map's own recorded start feed (H0, the
positive control) and then a chain that changes one variable at a time toward the live
bridge: frame GCJ→WGS-84 (H1), link class/formway, ownership, direction, laneNum,
adminCode+region (H2a–H2e), position ownership + TrafficInfo roadClass (H2f), legal limit
(H2g), no ODD request and no 106 phase (H3), 5 Hz rates (H4), the remaining bridge field
values (H5), and H0 plus the stock `SETTING_MPC_IFC_NAVI_ST_SET` side channel (H6). H0's
route is rebuilt through the byte-exact `stock.py` oracle, so it equals the stock wire bar
pathId and time; H1 is an inverse-GCJ flip on identical geometry. A parked car offers no
NOA even to the stock map. The original plan proposed the cluster's answer to P→D and
the stalk («NOA activating failed» against «ICC activating failed», TOR_738 and TOR_135)
as its distinguishing observable, next to service-13 and service-44 answers. **That
cluster-message criterion is retired for this vehicle:** following the first supervised H0,
the owner explicitly confirmed that NOA never activates or appears while stationary
on this car. The earlier drive analysis explicitly says neither proposed TOR was
retained in logs. Parked publication can verify transport, route selection, recording
and session closure; an absent stationary message cannot classify the changed field
or prove NOA support or rejection. Fixing ODD does not make it a parked activation
control. A comparison of internal replies to H0 versus the real stock map remains
testable while stationary, as explicitly requested by the owner below.

The player always ends with the stock stop sequence (status 110 for 3 s, then 100) and
releases its session on a clean finish, an exception or a watchdog kill, with a fresh
pathId per run; it refuses unless `com.byd.launchermap` is absent and disabled and no
valid live arm is held, records every service-13 and service-44 answer, and never fires
13/0x0001. H6's side channel (device 1023, FID 1285554251) is written by the host runner
over `adb shell`: device 1023 needs the shell UID, while the SOME/IP feed must stay in the
player's app UID (a shell `app_process` cannot bind the SOME/IP service). The runner
follows the stock order (`setNaviSt(2)` from `NaviService.setNaviTypeAndInit(0)` at
guidance start): read and record, write 2 just before the route, and after the player's
110 → 100 end restore the read value and read it back, also on an error, a timeout or
Ctrl-C; a failed or unexpected read means no write and the scenario is not run. The
generator, engine, precondition and runner logic is covered off-device
(`tools/adas_bridge/parked_player_harness/run.py`, `tests/test_scenario.py`,
`tests/test_parked_session.py`); those host results predate the first physical H0 below. H2c (link direction → 0)
is a no-op at this position: the stock feed already sends direction 0 there. Commits
`236b48d5`..`22369074`.

Preflight for the first physical experiment found that `PlayerActivity.onCreate`
called the blocking two-minute player directly. The Activity now runs it on an
owned worker and finishes on the main thread after completion, allowing Android
launch and input dispatch to proceed during the run. The pure-core harness does
not exercise Activity lifecycle; its 116 passing checks are separate from this
Android correction. Preparation evidence and the rebuilt probe are retained in
`captures/adas-parked-experiment-20261024-codex/`. Run one scenario at a time with a fresh host command budget
and prompt for the driver's action after the feed has started: the original
runner's 600-second total budget cannot cover all thirteen two-minute scenarios.

The first Android launch rejected a missing `scenario.json` before opening ADAS
transport. The host's `exec-out run-as ... sh -c` builder had not quoted the remote
script as one argument. The builder now quotes the complete command, verifies each
private write by readback, and fails closed on an unreadable or malformed Navigator
arm. Two regressions cover command tokenization/readback and unknown arm state;
all 32 scenario/runner tests pass. The failed start is retained separately under
`failed-start-01/`; it is not a second route publication.

The subsequent supervised **H0** ran with the owner present, brake held and initial
park predicate 1 / measured vehicle speed 0. Probe SHA-256 was
`bfb45bd5ac4e3b4778d82916b7b5bf064ec8d50967d4754a229bfe5a6c0eaec2`.
The installed Navigator remained build 12, SHA-256
`b0dbe08bee205de965c77b38faaac0e719013ae56166265c76dee3cfe5a284eb`;
its live publisher was quiescent with an expired arm and the stock map was disabled
and absent. ADAS explicitly confirmed path `1792856036` with status 200 and sent
PathMatch with that path and `routeEndIdx=166`; no PathMatch status or NOA distance
was transmitted. Across 1,277 decoded PilotStatus samples DNP/ICC/ACC stayed
0/0/0; a transmitted `noaMode=2` names a mode, not active NOA. The owner reported
no cluster message after the requested attempt. Additionally, the requested ODD
call returned -200 with a recorded `NullPointerException`, so this H0 did not
reproduce the complete stock start sequence. It cannot decide why NOA is missing.
H1–H6 were not run. The player recorded its end sequence, released services and
closed its answer listener. Raw answers, logcat, owner observation and receipts
are under the capture directory above. These are historical failed-control results,
superseded for the ODD transaction by the correction below. Acceptance of NOA itself
still needs a moving reference on a road where the function is observed to work;
replaying a fixed parked position while driving is not a valid moving comparison.

**Owner-directed ODD correction and LISTEN control.** The original parcel omitted
`writeByteArray(new byte[0])` after the explicit payload length and passed a null
second `SomeIpData`. The firmware's transaction 10 reads two inout objects and its
request callback unconditionally fills the second. The correction supplies both
objects, consumes both reply objects, bounds payloads to 64 KiB and records the
second payload without interpretation as `odd_response` in hex. The repeated H0
returned SDK result 0, ACK 200 for path `1792857154` and an actual 1,743-byte ODD
response, SHA-256 `ddd50ca7b778d470f6d8255babe28a3ba1c592861915db248d79224fe18f8e96`.
The raw response and the complete H0 journal are in
`captures/adas-h0-listen-20261024/`; region meanings are left to the owner.

`scenario.py` also generates LISTEN (`listenOnly=true`, default 120 s, no route).
Its engine runs only its duration, without any transport calls, including cleanup.
The Android wiring does not construct or bind `AndroidTransport` in this mode;
only the owned `AdasAnswerListener` is started and closed. The host and app allow
the stock map to be enabled/running but retain the no-live-Navigator-arm gate.
The control starts LISTEN before enabling and opening the actual stock map so
its initial ACK/PathMatch cannot be lost. This run uses a 180-second LISTEN window
to include route setup. Journals have the same row format as H0. Compare DNP/ICC/ACC,
NOA mode, warnings and the actual presence of PathMatch status/distance fields;
missing fields stay missing. If the stock map supplies useful distinguishing
replies, investigate the remaining start differences. If both sides remain
uninformative, move the remaining H1–H6 eligibility comparisons to driving.
No cluster display is required for this protocol comparison.

The completed 180-second LISTEN window contains 1,800 decoded PilotStatus samples
at DNP/ICC/ACC 0/0/0, transmitted `noaMode=2`, no populated warning values, and no
stock-route selection ACK or PathMatch. It contains **zero** publisher service
start/stop calls, fires or ODD requests. The stock MainActivity was foreground,
but a stock guidance route was not established by the retained answers. This is
a background-listener acceptance, **not a completed H0-versus-stock guidance
comparison** and not evidence that both navigation feeds are equivalent or that
stationary replies can never differ. At the owner's clarification the trial was
not extended: the reader finished, the stock package was returned to disabled
state 3 with no stock processes, the probe was uninstalled and Navigator remained
unarmed. `comparison.json`, both raw journals, the rollback ledger and cleanup
proof are retained under `captures/adas-h0-listen-20261024/`.

The real bridge now also has the fixed read-only ODD-list query before its armed
feed starts, with raw response/status recording. The passive overlay cannot use
it. Its Binder harness has 327 passing checks and the production coordinator has
180. Signed build 13 with this follow-up is local at
`captures/yandex-feed13-car-trip-20261024/release-odd-fix/`, SHA-256
`1d26bf0b05361d3cbaa440694eb603ea7a401dc312e109f44da250b05b2bcc76`;
it is not the earlier build-13 APK currently installed. No new trip arm was issued.

## Engine follow-up (2026-10-24)

The build-12 drive (`captures/adas-live-drive-20261024/ANALYSIS.md`) showed the live
session dying on a reroute (a 32-callback latch on a background worker that also
converted routes and did file and Binder I/O per fire), 42 s of silence at P→D, 15 paths
for one Yandex route, positions at about 5 Hz with indices of an older fix, a guarded close
that left ADAS holding the route, an arm lost on a Navigator restart and evidence rings
that rotated out the start of the drive. The host-only rewrite (`134caa8e`..`6f97e3bb`)
changes the dynamics, not the field values:

- A missing prerequisite pauses LIVE egress without dropping the path, its real ACK or the
  FINE anchor; the session ends only on a terminal cause (an actual send or audit failure,
  a transport epoch change, an arm or stock-package change) and keeps its first cause.
- Sends run on their own normal-priority thread. Callbacks go through a queue that never
  refuses: service-44 answers are kept, service-13 status is shed oldest-first. Readiness
  inputs are read every 250 ms and before a possible route publication, never per fire.
- One Yandex route ID is one path for its life, its link table frozen with the graph of
  that moment; DNP back on resends the route after 300 ms, as the stock does.
- LIVE keeps the stock's one-a-second location bundle, with the indices of the fix it reports.
- Every end sends the stock's status-loop end (`e0` `d.run`: 110 for three ticks after
  arrival, then 100): 110 ×3 then 100 ×3 after arrival, 100 ×3 otherwise, including 5 s
  before arm expiry and after a Navigator restart, which resumes the same unexpired arm
  under a per-boot consumer identity.
- `FieldDefaults` names every sourceless value in one place (frame, vehicle-speed source,
  legal limit, facilities, region, unmatched class and ownership); every trace header
  records it. Vehicle speed stays GNSS: Navigator's process has no BYDAuto permission.
- Evidence sized for a two-hour drive, service 13 recorded once, and `adas-journal.jsonl`
  with every ADAS state transition, selection and PathMatch, never rotated within a drive.

Each defect has a regression test that failed on the old engine
(`LiveDriveRegressionTest`, the runtime and passive-client harnesses). Not yet established
on the car: whether ADAS releases the route on the end statuses, accepts the 1 Hz cadence
and the DNP-on resend, how the 250 ms input cache behaves under real load, the resumed
arm, and the ring sizes on the car's disk.

## Safety

- NOA plans lane changes and exits from the route. A misaligned route (frame,
  link order, cut errors) can make the car change lane or take a ramp wrongly.
  Every live step is supervised, stationary first, then a known road.
- Class 0/6 correlates with highway NOA in the retained drives; it is not
  established as a sufficient activation condition. Road class must describe
  the actual road. Yandex highway ranges alone do not establish an Amap class.
- `SpdLmtSpeedValue` and link `speedLimit` may feed speed assistance; send 0
  (stock behaviour) until a separate test.
- Stale data: process death, route cancel, GNSS loss must end the feed within
  one cycle (expiry on every sample), never leave a believable old route.
- Never fire `13/0x0001` (DNP/ICC switch), never impersonate the stock map's
  session, never publish while the stock map navigates.
- Treat a `com.byd.avc` crash or an ADAS fault TOR during a test as a stop.

## Verdict and plan

**A local converter prototype is feasible and implemented.** Rich Yandex data
is now captured directly instead of inferred from public documentation. It does
not yet establish a complete ADAS map or NOA availability. Per-road attributes,
ID semantics, receiver datum and exclusive writer ownership remain material
unknowns.

Further receiver validation must compare the same corridor in Yandex and the stock map
and resolve every missing field from an observed source. The separate stationary
one-shot confirmed a selection handshake; continuous single-writer behavior remains
unverified. An `MMTPathSelected` response proves selection only, not
correct interpretation of lanes, class or driving-function eligibility. No
calendar estimate or assumed ICC-only fallback follows from the current data.

## Evidence and reproduction

- [`someip-map-adas-topics.json`](someip-map-adas-topics.json): services,
  events, topics, protobuf messages and senders.
- Yandex member lists: `dexdump -l plain YN/dex/classes10.dex`, class
  descriptors `Lcom/yandex/mapkit/directions/driving/*` (local output under
  `captures/adas-bridge-scout/`).
- Stock manifest: `aapt2 dump xmltree --file AndroidManifest.xml` of
  `system/app/BydLaunchermap/BydLaunchermap.apk` (boot receiver, no
  `persistent`).
- Related findings: `docs/stock-map-findings.md` ("NOA route data audit",
  "Pipeline review and car defects"), `docs/hud-projection-findings.md`
  (service 266, sender checks), `docs/instrument-display-findings.md`
  ("The car's speed sign from Yandex").
