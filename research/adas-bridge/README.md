# Map → ADAS feed from Yandex (local prototype, 2026-10-23)

Can our app publish the navigation route and the rest of the map's feed to the
car's ADAS (Momenta, `getAdasType` 1) in place of the stock map
(`com.byd.launchermap`, Amap AutoSDK), with the route coming from Yandex
Navigator and the stock map left installed and unmodified? The initial firmware
feasibility study is now followed by an instrumented Navigator build, a host
converter and a local mock receiver. Conversion benches run on a disposable
emulator; car builds also collect passive evidence. Build 17 is built and signed
locally, with recorder lifetime, optional service-10 control and reroute handoff
corrections. Build 17 is installed by hash; the A1 OFF drive is complete and its
session is closed. It retained nine 489–607 ms NOA-active runs, all ending in ICC;
availability did not recover. Moving reroute gaps fell to 1.745–5.504 s, and both
status recorders covered the drive and subsequent map hiding.
See [build 17](#build-17-control-candidate-2026-10-25).
The preceding verified installed APK was build 16
and verified by APK SHA-256 (`cb97cacb…`), preserving build 15's offline-package
fix and adding the passive position observer described below. Build 15 prepared downloaded-vector
attributes for one selected route, decoding 67/67 tiles. This establishes that
route's Android acquisition path, with unresolved attributes still explicit. See
[the build-15 correction](#generic-vector-runtime-and-offline-prerequisite-2026-10-24).
On build 12 the parked car acknowledged two distinct Yandex routes
with explicit status 200, including cancellation and replacement within the same
armed session, then received dynamic packets. Build 15 also received a real
status-200 acknowledgement for the enriched route with `startProtocol: stock`,
after its shortened-start comparison received none. A subsequent same-arm parked
check accepted three paths across two manual replacements without stale path IDs
in the retained position packets. Sustained NOA operation is not established; the subsequent build-15 drive
retained brief active CNOA intervals while the feed continued, as described below.
See [route replacement acceptance](#route-replacement-correction-build-12-2026-10-24).

## Current state

Updated 2026-10-25. Local parser acceptance is separate from vehicle acceptance.

| Claim | Status | Section |
| --- | --- | --- |
| Default Region now reuses the successful stock/OSM drive's `990001/1/990001/990001` (region/country/province/city), without an arm override. Weather remains 0. Route-link adminCode remains independent | local source change; vehicle transmission and NOA effect unverified | [Region default](#region-default-from-the-successful-stock-drive-2026-10-25) |
| URID 372 binds the stock SDK's map package and feeds its local ODD calculations; it is not a Region field in the active ADAS-type-1 wire. The production Java calculator matches the original Amap on 1,332/1,332 Beijing samples, including protobuf bytes. One Yandex route now has conservative route-specific annotation: 14/26 links (3,273/4,772 m) mapped to 372; 12 divergent links remain unknown, so its full feed retains MAX. Java and Python agree on 144/144 replay samples, including a controlled finite boundary on a fully mapped excerpt | local source + original-APK emulator differential + offline geometric annotation; generic Yandex annotation, receiver eligibility and Russia-wide coverage remain unresolved | [Region default](#region-default-from-the-successful-stock-drive-2026-10-25) |
| Build 17 (`dd8dbce9…`, versionCode 739494317) A1 OFF retained nine NOA-active runs of 489–607 ms, each ending in ICC. Pure availability occupies 0.80% of observed moving time; service 10 is actually OFF. Five paths received SELECTED 200; 743 retained positions have no send errors. Reroute gaps are 1.745/1.828/2.418/5.504 s. Both recorders cover movement and another 151 s. Session closed after the owner's manual reboot, stock remains disabled | current-arm raw evidence; no sustained NOA; subscription-only explanation weakened, cause unresolved | [A1 drive](#build-17-a1-off-drive-review-2026-10-25) |
| The completed build-16 `loop-wire` drive has five explicit DNP-active intervals of 495–589 ms, all ending in ICC. Five paths received SELECTED 200; 7,512 preclosure sends and 799 positions have no transport failures or wrong-path sends. First three dropouts precede the first reroute; the first four occur with ordinary matched ownership 0. Session closed, stock left disabled by owner request | current-arm raw evidence; no sustained CNOA, receiver cause unresolved | [Loop-wire drive](#loop-wire-drive-review-2026-10-25) |
| Build 16 records service-13 states only through the overlay observer, which closes 30 s after the map Activity stops. AVC foregrounding ended that observer after its drive; guidance/live continued. All recorded movement precedes that cutoff. Installed build 17 retains both observers through its drive and later map hiding | build-16 source/raw lifecycle proof + build-17 moving recording receipt | [A1 drive](#build-17-a1-off-drive-review-2026-10-25) |
| Build 16 records optional service-10 VehiclePositionInfo without requiring an arm. Its completed loop-frame drive received four explicit same-path SELECTED 200 replies and sent 562 current-path positions without transport failures; no explicit DNP available/active status was retained. Two moving reroutes interrupted positions for 8.264/7.469 s. The session is RESTORED, publisher closed, stock map enabled state 0. Output proximity does not establish the required route datum | installed SHA verified + bounded moving capture + restored state | [Build-16 position observer](#build-16-position-observer) |
| Build 16 can tear down a ready replacement before its complete observation, adding about 2 s end plus 5 s fresh start. Installed build 17 retains guidance during bounded replacement while blocking old egress and requiring a fresh-path ACK. A1's four replacements commit without the old stop/start lifecycle, with 1.745–5.504 s position gaps; NOA dropouts remain | host regression + moving build-17 evidence | [Local handoff correction](#local-reroute-handoff-correction-2026-10-25) |
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
| Local vector runtime prepares the entire chosen route through a separate downloaded-map attribute source; SDK inventory/path, a read-only vmap3fb reader and standard Zstd replace automatic offscreen camera acquisition. The converter consumes a source-independent interval contract | installed build 15; actual Android app prepared one route, 67/67 tiles decoded; sampled attributes with explicit gaps/conflicts | [Generic runtime and offline prerequisite](#generic-vector-runtime-and-offline-prerequisite-2026-10-24) |
| Build 15's bounded parked source-only comparison sent an enriched 100-link route and selection through the real transport, both result 0; no same-path SELECTED ACK arrived in the retained 62.6-second window, so the guard withheld dynamics. The session was restored and the stock package returned to its recorded state 0 | physical parked observation; drive variants and NOA not accepted | [Build-15 parked comparison](#build-15-parked-comparison-2026-10-24) |
| A subsequent build-15 comparison kept the same Yandex route and both optional vehicle side channels off, changing `startProtocol` to `stock`. ADAS acknowledged the new path with status 200 and the guarded feed sent dynamics; 104 successful packet sends are retained. Start messages, ODD request and the added start delay have not been separated causally | physical parked acceptance of this route and configuration; NOA not established | [Build-15 parked comparison](#build-15-parked-comparison-2026-10-24) |
| The build-15 drive retained 126 explicit DNP-active intervals (median 786 ms, maximum 5415 ms), 113 immediate ICC-active exits and only city-family mode 2. All exits were bracketed by successful position sends within 2028 ms. Its 844 link instances contain no class 0/6, and 147/1799 positions disagree with dynamic road class; the receiver exit predicate remains unknown | actual car packets + installed-source audit; short activation, no sustained NOA acceptance | [Build-15 drive](#build-15-drive-review-2026-10-24) |
| The owner confirms autonomous NOA↔ICC cycling after a stalk request. Three active intervals end before any new bridge packet; 124/126 exits have coherent route-position geometry. A retained stock control holds CNOA for 24.902 s with dynamic class -1/9, so missing class 6 does not explain every city dropout. Coordinate family, ownership and vehicle-speed provenance remain persistent differences, not proved receiver causes | raw packet replay + original stock log/source; host-only follow-up | [Immediate dropout](#immediate-dropout-follow-up) |
| Original SDK native DR mode1 emits shifted Moscow coordinates correlated by inputTickTime; the GNSS path and public guard preserve WGS84. The pinned build-15 candidate matches the original inner function on 408 trip points, and 889 host field checks find no active coordinate bypass or double transform. Receiver datum and dropout causality remain unproved | original APK/ELF runtime + pinned-source host checks + parked selection; dropout unresolved | [Coordinate parity](#coordinate-parity-follow-up) |
| On the same settlement streets where the stock held CNOA, build 15 sends values the stock never sends while guiding: position ownership -1 (stock 0 in 1,747/1,747 guided positions), and route links with laneNum 0, formway -1, ownership -1, adminCode 0, hasMultiOut false (stock 1–2, 15, 0, 990001, true in a later parked capture with another package; the holds' route bodies are not retained). Region 0, a non-zero speed limit and a located traffic light also persist; dynamic class -1, cadence and route progress are ruled out by the control. Receiver cause not proved | original stock logs/source + build-15 wire, joined by street; host-only | [Stock CNOA hold against build 15](#stock-cnoa-hold-against-build-15-field-by-field) |
| The stock's longest hold, HNOA on Kievskoye for 54.2 s, sends class 6 and historySpeed 85; its CNOA holds in the settlement send 9/-1 and 25. Ownership 0, Region 990001, speed limit 0 and an open ODD are the same in both | original stock send log, host-only | [Stock CNOA hold against build 15](#stock-cnoa-hold-against-build-15-field-by-field) |
| Build 15 accepted three paths across two manual replacements within one parked arm; all 160 decoded positions referenced their corresponding current path, and both stops sent real end statuses. A separate two-hour session subsequently obtained its own real ACK and dynamic sends, with autonomous logs growing | physical parked transport and recording checks; subsequent moving-trip evidence below; full-route attribute coverage and sustained NOA remain unverified | [Build-15 parked comparison](#build-15-parked-comparison-2026-10-24) |
| Offline maps remain mandatory, with the owner-requested minimal check: at least one usable installed region. Neither full-route coverage nor equality with a separate offline route is required for this iteration | source implementation; inventory presence is not a claim about route coverage | [Generic runtime and offline prerequisite](#generic-vector-runtime-and-offline-prerequisite-2026-10-24) |
| A downloaded Yandex Kazan `driving.fb` exposes 298,104 directed indices, 297,181 admitted base-edge shapes and 156,842 raw attribute entries. A bounded Java reader and whole-route association pass host tests on the actual package. Raw road/access enums remain unconverted; active class/direction acquisition uses vmap3fb literals | native bytes + host Java checks; regional reader not integrated into feed | [Regional offline graph](#regional-offline-graph-2026-10-24) |
| Post-drive read-only pull verified build 8 by APK SHA. The latest session has 1,362/1,362 FINE guidance samples at 2–364 ms; retained ADAS shows two ICC-active episodes and no service-44 packets. Raw status packets occupied 77.44% of the shared log, and the overlay listener closed during 26 active-guidance samples | physical retained logs + presence-aware offline replay; build 8 sent no Yandex route | [Post-drive review](#post-drive-read-only-review-2026-10-24) |
| Build 11 fixes dynamic legal-limit suppression and lane-sign loss, adds intersection/next/ahead-lane senders and preserves captured traffic context. Static route `speedLimit=0` remains factory-compatible | host tests + historical build-11 installation; retained in build 12 with parked dynamic sends | [Build 11](#build-11-completeness-and-car-test-2026-10-24) |
| Completeness inventory covers 73 snapshot fields, 67 values of five enum families and 20 factory message families; 60 decisions have source evidence and a next action. Unproved mappings and source limitations remain explicit | bounded source/capture audit; not a claim that all data is converted | [Completeness inventory](#build-11-completeness-and-car-test-2026-10-24) |
| Build 11 route and selection fires returned 0 with fresh hardware snapshots and actual remote-service availability; no matching service-44 ACK was received in the retained pre-departure window | historical build-11 parked car; superseded by build-12 ACKs below | [Build 11](#build-11-completeness-and-car-test-2026-10-24) |
| Normal route cancellation closed the whole build-11 arm; build 12 keeps a healthy armed session idle between routes and distinguishes an ACK wait from send silence | host regression + two physical same-arm route ACKs and dynamics | [Route replacement](#route-replacement-correction-build-12-2026-10-24) |
| Local candidate 13 refreshes same-route conditions, adds typed road facilities, preserves partial-source errors, latches terminal sender failures and clarifies the idle overlay | local host validation; not installed | [Local follow-up](#local-follow-up-candidate-13-2026-10-24) |
| Every value the bridge can send to ADAS has a contract row: provenance, mark against the stock, decision with evidence or the experiment that decides it, NOA risk (252 rows, 87 open after the local enrichment source audit; code anchors describe main with the experiment variant merged in; build 15 has bounded parked acceptance of manual replacement and the source-valued stock-start configuration; other experiment variants and moving behavior remain unverified) | Data, gated by tests | [Field contract](#field-contract-2026-10-24) |
| Historical first parked H0 obtained ACK 200 and PathMatch but its ODD request failed with NullPointerException. The owner confirms this car never activates or displays NOA while stationary: the proposed cluster-message criterion is inapplicable. The revised control compares internal ADAS replies against the actual stock map | physical car + owner-directed revised method, 2026-10-24 | [Parked experiment kit](#parked-experiment-kit-2026-10-24) |
| Corrected H0 ODD returned 0 and retained its 1,743-byte response as `odd_response` hex. Actual LISTEN recorded 1,800 pilot samples with zero publisher calls; no stock-route ACK/PathMatch was received, so the paired guidance control is not established. Stock was disabled again and the probe removed | physical H0/LISTEN + 34 Python tests / 127 core checks, 2026-10-24 | [Parked experiment kit](#parked-experiment-kit-2026-10-24) |
| The live engine no longer dies on a reroute, pauses instead of going silent on a prerequisite gap, keeps one path per Yandex route while its offline admission remains valid (proof withdrawal requires a fresh path), sends positions at 1 Hz with their own indices, ends every session with the stock's statuses, resumes after a Navigator restart and keeps bounded rotating evidence (the build-16 position stream reduces measured retention below two hours). Build 15 has parked manual-replacement, stop and recorder-growth evidence; build-16 moving reroutes expose 8.264/7.469 s position gaps, and restart recovery remains unverified | code + host harnesses + bounded parked checks, 2026-10-24 | [Engine follow-up](#engine-follow-up-2026-10-24) |
| Lane category 0/1/2 is supported by the recovered SDK enum and the stock's forwarding code; the Beijing sample's 255 is NULL, not a universal constant. TRAM/TAXI/BIKE → Other remains a declared coarse projection | corpus + source-oracle regression, 2026-10-24; car not run | [Enrichment follow-up](#enrichment-follow-up-2026-10-24) |
| Strict per-arm field switches and the separate MPC/instrument state switches are implemented for local controlled comparisons; instrument state is ON by default for new host sessions (explicit false disables it), MPC remains opt-in; their presence does not decide OPEN field rows | code + host harnesses; car not run | [Enrichment follow-up](#enrichment-follow-up-2026-10-24) |
| Instrument `0x43E0003A` is registered for both SET and cached GET on device 1007 and shell UID is allowed; its initial 65535 is unknown. Cache readback does not prove ECU receipt or an ADAS gate; the inspected MPC `0x4CA0004B` registration is request-only | original native corpus + bounded offline replay, 2026-10-24 | [Enrichment follow-up](#enrichment-follow-up-2026-10-24) |
| The experiment variant (strict per-arm switches: frame, link and position fill-ins, region, historySpeed, speedLimit, startProtocol, naviSt, instrumentNaviStatus) is now in main together with the offline attributes. A fill-in never overwrites a value Yandex knows (graph road, offline class and one-way, roundabout), per field, and the highway rule stays removed. One ODD request mechanism remains: the fixed parcel, sent only through the guard under `startProtocol: stock`, once per fresh guidance start. The completed build-16 drive exposes a reroute exception: route-subject reconciliation recreates the driver and repeats the fresh start and ODD. The default (bridge) start sends none, and a recreated driver keeps the session's frame. Included in installed build 15; only the source-valued bridge/stock-start comparison with both side channels off has been exercised here | code + host harnesses + bounded parked checks, 2026-10-24 | [Parked experiment kit](#parked-experiment-kit-2026-10-24) |
| Verdict: feasible with conditions — the transport is open; the blockers are the idle stock map's competing 1 Hz feed, the unknown receiver semantics (coordinate frame, link IDs, path-match) and safety validation | assessment | [Verdict](#verdict-and-plan) |

Contents: [Build 17 control](#build-17-control-candidate-2026-10-25) · [Build-12 NOA gap audit](#noa-gap-audit-of-the-build-12-trip-2026-10-24) · [Local follow-up](#local-follow-up-candidate-13-2026-10-24) · [Transport](#transport) · [One writer](#one-writer) ·
[Idle stock map](#what-the-idle-stock-map-still-sends) ·
[Provider path](#no-official-provider-path) · [Contract](#contract) ·
[Yandex data](#what-yandex-gives-us) · [Converter](#the-converter) ·
[Local prototype](#local-prototype-2026-10-23) ·
[Stock-equivalent bridge](#stock-equivalent-bridge-adas-link-2026-10-23) · [Guarded live candidate](#guarded-live-candidate-2026-10-24) · [Live gate sources](#live-publisher-gate-sources-2026-10-24) · [OSM roads](#osm-roads-under-the-yandex-route-2026-10-24) · [Removing OSM](#removing-osm-review-2026-10-24) · [Yandex-only extraction](#yandex-only-graph-extraction-2026-10-24) · [Regional offline graph](#regional-offline-graph-2026-10-24) · [Location clock](#location-clock-domain-2026-10-24) · [Native archive](#native-route-archive) ·
[ADAS status observer](#adas-status-observer) ·
[Car trip recorder](#car-trip-recorder-2026-10-24) ·
[Parked selection](#parked-route-selection-with-a-separate-probe-2026-10-24) ·
[Post-drive review](#post-drive-read-only-review-2026-10-24) · [Loop-wire drive](#loop-wire-drive-review-2026-10-25) ·
[Pre-test review](#pre-test-firmware-review-2026-10-24) ·
[Build 11 and completeness](#build-11-completeness-and-car-test-2026-10-24) ·
[Route replacement](#route-replacement-correction-build-12-2026-10-24) ·
[Field contract](#field-contract-2026-10-24) · [Parked experiments](#parked-experiment-kit-2026-10-24) · [Engine follow-up](#engine-follow-up-2026-10-24) · [Enrichment follow-up](#enrichment-follow-up-2026-10-24) ·
[Safety](#safety) · [Verdict and plan](#verdict-and-plan) ·
[Evidence](#evidence-and-reproduction)

Paths below: `LM` = `captures/hud-firmware-20260923/jadx/BydLaunchermap/sources`,
`SIP` = `captures/hud-firmware-20260923/jadx/SomeIpService/sources`,
`YN` = `captures/firmware/yandexnavi-29.8.1-3b0dec32` (all git-ignored).

## Region default from the successful stock drive (2026-10-25)

The owner requested the region sent during the successful NOA run. The original
`captures/stock-map-review-20261023/car/drive-02/drive.log` records successful
Region sends on topic `1268847189590025` (`0x8202/0x8009`), including lines
134, 399 and 808: `regionId:990001 countryCode:1 provinceCode:990001
cityCode:990001 weatherid:0`. This is the stock/OSM drive with the retained
CNOA holds, not a region inferred from Navigator's offline-map inventory.

`FieldDefaults` now uses those three `990001` ids for both Navigator hosts,
dry run and live, even with no `region` variant. Previously they were 0;
build 17's A1 source-valued variant used that older default: its retained
`postdrive/reports/transmission-a1/arm-review.json` has 774 Region packets with
country 1 and region/province/city absent (proto3 zero). Explicit experiment
variants remain compatible. The trace header records the new default.
`link.adminCode` is a separate route attribute and is unchanged.
This synthetic map code has no established general administrative or ODD meaning;
reusing it is not evidence that NOA will hold. No vehicle install or arm is part
of this source change. Historical zero-region measurements below describe the
older APKs.

Validation: `:adas-link:testDebugUnitTest` passes, including the existing
`VariantWireTest` now decoding default Region output and checking all five
values without an arm override, plus the field-contract and serializer oracles.

**URID 372 is a separate internal namespace.** The stock-data builder
`amap-injector/research/stock-map-native/global_db_moscow_bound372.py` binds
adcode 990001 to URID 372 in `global.db`, selecting `chn/a372`. The original
SDK exposes the road's URID through `LinkInfo.getURID()`.
`LM/k/h/e0/d/f/m.java` compares these URIDs (and `BydOddInfos.cityURID`) with
the open-city set; `LM/k/h/e0/c/f.java` computes the distance to a non-ODD city
from them. These internal structures are distinct from the active
ADAS-type-1 `NaviToEHPData` payload, which has link `adminCode`, not `URID`.
`OddDistance` now ports the stock controller's URID-transition algorithm,
including its empty-set MAX result, transition-before-length rule, 2 km
look-ahead and Java int arithmetic. `StockFeed` consumes the actual ODD response
retained by `AdasPassiveClient`; with complete internal URID annotation it emits
the calculated distance, otherwise it deliberately retains MAX. URID is present
only in `StockPath`, never added to the ADAS route wire. `OddCoverage.Catalog`
separates the generic adcode-to-URID catalog from geographic adcode assignment;
the confirmed `990001 -> 372` pair remains a Moscow stock-map bench input, not a
Russia-wide default or a coverage override.

The original-APK differential used factory route `survey-01-p006` (74 links,
7,487 m, native URID 1) on the Beijing package. The six controlled cases each
have 222 samples: retained 374-city ADAS list, native cities, empty list,
excluded list and two labelled synthetic URID boundaries. All 1,332 production
Java results match Amap's decoded distances and complete protobuf payloads;
the independent Python oracle also passes 1,332/1,332. The synthetic
covered-to-excluded case includes finite 81/40 m boundaries and zero outputs.
The original run is emulator-only with `vehicle_operations=0`, status `observed`
and baseline restored. Receipts: `captures/adas-odd-bench-20261025/java-check-v3.json`,
`python-check-v3.json` and `prepared-v3/run/receipt.json`.

This proves the universal calculation against one real factory route and
controlled boundaries. It does not supply geographic URID annotation for Yandex
routes in Russia, establish actual NOA coverage or prove sustained NOA. Merely
putting 372 into Region would still not reproduce package selection or coverage.

Validation after integration: `:adas-link:testDebugUnitTest` passes; Python ODD
and live-session tests pass 54/54; the passive-client harness passes 2,631
checks; runtime handoff/coordinator harnesses pass 82 + 337 checks.

**One-route geometric URID annotation.** A second emulator-only reference run
used the Moscow stock-map package and the endpoints of retained Yandex route
`01a16d87-80aa-71cf-ba4e-cb002076aaa0`. Its receipt is observed, restored and
has zero vehicle operations. Amap returned one local 6,000 m route with 84
links; every native `LinkInfo` and exported link agrees on length and adcode,
and every native URID is 372. The independent six-case calculator check passes
1,512/1,512 samples. This Amap self-differential is separate from the Yandex
annotation below; it is not an original-Amap calculation on Yandex geometry.

`tools/adas_bridge/urid_annotation.py` verifies the native/exported link tables,
converts the actual 380-point Yandex snapshot into the stock SDK frame and
compares geometry. Samples are at most 12 m apart, candidates must lie within
30 m and 60 degrees, and admission requires at least 90% matched samples plus
exactly one candidate adcode and URID. The threshold is fixed in the receipt;
it is not relaxed to obtain coverage. Unknown and ambiguous candidates remain
zero. This route-specific tool does not alter `RouteConverter` or claim a map
catalog for Russia.

The Yandex route is 4,782.800 m before stock-link rounding and has 26 links.
Fourteen links receive adcode 990001 and URID 372 from their geometric Amap
candidates: 3,273 of 4,772 integer link metres (68.59%). Twelve remain
unmapped, including one only 2/6 matched; none is ambiguous. Amap and Yandex do
not follow identical geometry everywhere, so their different route lengths are
not an error to hide by copying 372 onto every link. Per-link sample counts,
mean/max nearest distances and all candidate keys are retained in
`captures/urid-route01-20261025/annotation.json`; its `stockPath` is rebound by
the production `OddCoverage.stockMapCatalog()` during replay.

The retained real 374-city ODD response contains 372. Production Java and the
independent Python oracle agree on 144/144 route-annotation replay samples. On
the incomplete full route, all 78 samples deliberately return MAX through the
production completeness guard. The longest fully mapped excerpt has 11 links:
all 33 actual-list samples return MAX. A labelled synthetic exclusion of its
last link returns finite 240–1,945 m distances on 16 samples, zero on four and
MAX on 13 (including the 2 km horizon). This demonstrates finite arithmetic on
a controlled complete subset, not a finite result for the whole Yandex route.
Receipts: `captures/urid-route01-20261025/replay-expectations.json` and
`java-replay-check.json`; seven focused Python tests and
`:adas-link:testDebugUnitTest` pass. Generic geographic annotation, actual
Russia-wide ODD coverage and NOA acceptance remain unresolved.

## Build 17 control candidate (2026-10-25)

Local signed ARM64 APK: `captures/yandex-feed17-20261025/build/YandexHUD-17-arm64-debug.apk`,
version `29.8.1-YandexHUD.17`, code `739494317`, SHA-256
`dd8dbce93c2d8b1c3a318ae88822ce8a9cb16c898fc739ed17282807dc886728`.
Signature verification passes v2/v3 with the existing build-16 certificate
`df4100bda2c4f581c6bb5251746f1b2bdee2f7662ead31a46ef01ae25f9d39c6`.
No installation, ADB, emulator or live session was performed in this preparation.
The working-copy offline-package corrections are preserved; OSM is absent.

`AdasPassiveClient.ObserverConfig` latches the regular-file marker
`files/denza-adas-vehicle-position.enabled` once per app process. Missing marker,
directory or access error means OFF: no service-10 start/subscribe, including on
reconnect. ON applies only to the existing passive observer; the live bridge's
required answer receiver never acquires service 10. Each connection records
`vehicle_position_config` with configured/effective values and marker origin.
Changing this marker requires a full process restart, not reopening an Activity.

The separate capture marker `files/denza-drive-capture.enabled` now latches
recorder enablement and passive-observer retention together. During capture,
hiding the map keeps its observer in both service-10 modes; UI windows still
follow Activity lifetime. The existing live receiver also records its service-13
callbacks, tagged with its arm and receiver. Thus camera foregrounding no longer
removes the only status recorder. Closing a live session alone does not stop the
capture observer: switching modes requires session closure, evidence collection
and process restart. Observer retention is not conditional on service 10.

The [bounded reroute handoff](#local-reroute-handoff-correction-2026-10-25) is
included. It blocks old-path output during preparation, preserves the guidance
lifecycle, and still requires valid offline admission, fresh-path ACK and FINE
position. Timeout/cancellation and real transport errors still close the path.
This corrects the reproduced 7–8 s lifecycle pause; the subsequent A1 moving
measurement below retained four gaps of 1.745/1.828/2.418/5.504 s.

Validation retained under `captures/yandex-feed17-20261025/`:

- Passive client: independent JVM OFF/ON/OFF/directory/error runs,
  483/699/483/483/483 assertions, all pass (2,631 total).
- Runtime: 82 handoff and 337 coordinator checks pass, including service-13
  recording for 35 s with no overlay, original receipt/bytes and late-callback
  rejection. `recording-red.log` preserves the old filter's failure.
- Archived build-15 versus current steady-route replay with the exact
  `drive15-source-stockstart` variant: 68 sends each, matching topics and timing;
  67 payloads identical. Only the route wrapper's wall-clock `time` differs;
  normalizing that metadata makes all 68 equal. This does not cover ECU behavior,
  Android scheduling or reroutes.
- Full builder checks, D8/native compilation, APK source hashes, manifest/resource
  checks, signature and ARM64/OSM-absence checks pass. `source.zip` preserves the
  dirty and untracked sources with its manifest; binary inputs remain in the
  existing private corpus. The independent source review found no blockers.

The first control is OFF with the build-15 variant (WGS84, source-valued road
fields, stock start, both optional navigation side channels false), then ON,
then OFF again on this same APK and route. Compare availability separately from
active intervals; engagement is not required to measure offers. This tests the
subscription hypothesis, not a claim that service 10 caused the loss of offers.
Old 69% summaries combined available OR active and used sampled seconds: do not
compare them directly with a pure-availability time fraction.

Collect each short leg separately. Projection from the build-16 raw packet
cadence gives build-17 retention about 37–44 min with service 10 ON and 55–64 min
OFF; these are estimates, not guaranteed durations. Return/pull within 20 min
per leg. Use the current arm's `receiver=live` as the primary service-13 stream
and overlay as an independent check; do not sum duplicate receivers.
`drive_review._pilot` still prefers overlay when present, so its generic mixed
folder totals are not sufficient for this control. Exact kit/plan receipts and
the current procedure are in `captures/yandex-feed17-20261025/trip-kit.json`
and `captures/drive-kit-20261025/LOOP.md`. Stock remains disabled by owner request;
the previously closed `loop-wire` ledger must not be restored again.

**Subsequent parked deployment, 2026-10-25 11:10-11:13 MSK.** Installed APK SHA
matches the pinned build 17. The new A1 ledger records original stock state 3;
arm `1fea7c6c-c2e4-4c28-8239-31bf256a0484` expires approximately 12:10:39 MSK.
Both clients report `enabled=false effective=false origin=marker-absent`; no
service-10 packets appear in the new source session. The previous suggested
destination (Lugovaya Street 20) was selected and guidance started in P.
ADAS returned real SELECTED 200 for path `1792915887`. At handoff, 1,024 actual
packet sends include 101 positions, all successful and referencing that path.

Opening stock `AutoVideoActivity` kept both service-13 streams alive: each
receiver retained 519 callbacks on each of the three status topics in the
53.52-second observation window, including 169 each after 35 seconds.
`capture_observer_retained` was recorded; Navigator and AVC PIDs stayed unchanged
and the crash buffer stayed empty. The recorder reported one raw queue drop,
zero critical drops and zero post/write errors; this is not zero-loss acceptance.
Yandex was returned to the foreground with a fresh matched FINE position and
the route receipt visible. The owner was told A1 is ready for a short manual
control drive and to return/collect within 20 minutes. No device operations
followed that handoff until the owner returned. Moving results and closure are
recorded below. Local receipts and closure ownership:
`captures/yandex-feed17-20261025/deployment.json`, `current-A1-local.json`, and
`sessions/20261025-110730-A1-off/{ready.json,camera-check-report.json,rollback.json}`.

### Build-17 A1 OFF drive review (2026-10-25)

Private evidence and reproducible analyzers:
`captures/yandex-feed17-20261025/sessions/20261025-110730-A1-off/postdrive/`.
`SUMMARY.md` links the three independent reviews under `reports/`: ADAS states,
transmission and recording integrity. Installed APK SHA still matches the pinned
build. The capture preserves all generations; 44/44 file hashes match the car.
Only the current source/process and arm are analyzed; historical packets in the
rings are excluded. The live service-13 receiver is primary. All 8,101 paired
PilotStatus callbacks within 25 ms agree with the independent overlay receiver;
one live callback is unpaired.

**NOA did not become usable.** Nine explicit active-state runs last 489–607 ms
(median 499 ms), each followed by explicit ICC-active. These are state runs, not
a count of driver lever actions. Availability appears in 28 blips of 72–125 ms.
Using receipt-to-next-receipt time, fresh FINE guidance and speed above 5 km/h,
available occupies 2.689/335.593 s (0.8013%), active 4.575/335.593 s (1.3633%).
On the same definitions the preceding loop-wire drive has 0.2017% available and
0.8186% active. Routes differ; a separate shared-footprint comparison against
build 15 also finds no practical return of its frequent offers. Do not compare
these pure-availability time fractions directly with the old 69% sampled
available-or-active number. NoaFamily remains explicitly 2. Reason 19 first
appears 110.124 s after the last active-to-ICC exit; no retained reason identifies
the cause of those nine exits.

**Service 10 is actually OFF**, with no acquisition or packet in this source.
This weakens the simple explanation that its subscription alone removed offers;
it does not isolate a causal effect. A1 also differs from loop-wire in variant
fields, the handoff and recorder changes, and external vehicle conditions. The
planned ON/OFF pair has not been run; A1 supplies no recovered-offer baseline.

**The two implemented fixes have moving evidence.** Five complete routes have
five explicit SELECTED 200 replies. Retained logs contain 7,191 successful sends,
including 743 positions. Four replacement handoffs commit without an intervening
navigation stop, end or fresh-start lifecycle. Their position gaps are
1.745/1.828/2.418/5.504 s versus loop-wire's 7.854/7.578/7.795/7.925 s. The longest
new gap includes rejected old/unknown-path observations while Yandex still
reports rerouting; it is not the old stop/start regression. Both observers cover
all retained motion and another 151 s, including 128.5 s after later map hiding.
The single raw drop was already present at parked handoff; no new observed drop,
critical drop or post/write error occurs during the drive.

All nine active-to-ICC exits are bracketed by successful positions of their
acknowledged current path, with intervals of 999–1,015 ms. There is no local
veto or lifecycle change within ±5 s of any exit. The first seven precede the
first handoff. The first exit is 292.803 s after ACK (including parked warm-up)
and 46.488 s after first recorded motion above 0.5 m/s. Thus these exits are not
explained by the recorded reroute gaps or a local publication stop. All 743 A1
positions explicitly carry source-valued ownership -1; preceding loop-wire
ownership 0 also failed to remove dropouts, so neither observation isolates a
single-field cause.

**Termination and closure.** The owner manually rebooted the head unit because
the network failed. Four source JSONL files have terminal NUL-only tails totaling
50,856 bytes; complete preceding rows are retained. These are present in the
hash-matched source, not transfer corruption. Counts above are retained lower
bounds, not exact whole-session totals. The current crash buffer is empty but
cannot establish pre-reboot crash absence or the network failure's cause.
Persisted LIVE/heartbeat files refer to the old boot and are not current activity.
After saving evidence, an owned closure removed only the stale arm and its tmp
file. Changed boot/uptime, absent app/reader processes, unchanged APKs and stock
state 3 were independently checked. The ledger is `CLOSED_AFTER_BOOT_CHANGE`,
not ordinary `RESTORED`; normal end-status delivery and ECU route purge are not
claimed. `closure-receipt.json` records the proof. Stock stays disabled; no
automatic next leg was armed.

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
(`e0.java:1128-1143`). ~~Unmatched: freeze at the link end~~ (unsupported earlier
generalization, corrected by the source audit: `e0.b0`, `:600-613`, does not clamp or
overwrite the current NaviInfo placement; `e0.P/H0`, `:432-484`, `:1127-1143`, sends
fresh GPS/matched coordinates with the current bean's indices and remaining distances).
Ownership −1 is the mapping of SDK `Integer.MIN_VALUE`, not proof of an off-route state.
`offCourse` is set by a recalculation callback and reset after the location bundle,
rather than a persistent indication on every off-route packet.

**TrafficInfo (`7/0x8003`).** jam length/distance/status, pass time,
construction, `SpdLmtSpeedValue` (legal limit; 0 on this car), camera limit and
distance, interval camera, `offCourse`, distance and time to destination,
`roadClass` of the current link (`f0.java:208-212`).

**Guidance extras (while matched).** `…0A` `historySpeed`, `trafficFlowSpeed`,
`hasParallelRoad`, `hasMixFork`, `hasLongSolidLane`, voice command;
`…0B` next icon/main/assistant action and distance, camera; `…07` ramp
distance and tidal lane; `…0C` next-intersection lanes (back/front/extend/
recommended/types as bytes); `…0E` lanes 3 km ahead (JSON); `…01` light
(only when `dstLinkDistance < 500` or with a countdown: this is distance through the
lane-target link, **not** distance to the next light; see the source audit below);
`…09` region; `7/0x8001` status 101.

**Not to send.** `13/method 0x0001` (DNP/ICC switch request,
`e0.java:110`) is a control of the driving function; memory-NOA requests are
stubs in this build (`LM/k/h/e0/c/h.java`). `13/method 0x0002` (ODD region
list) is a read and harmless.

**Unknown.** What ADAS requires before offering NOA (drives show class 0/6
for highway NOA, about 3–4 km of unbroken class 6 ahead, not ending at the
destination; city NOA unexplained, maybe `historySpeed`); whether `linkID`s
must be stable or globally meaningful (the parked PathMatch reply does not settle this);
the receiver's coordinate datum; how ADAS treats two interleaved writers.

**Coordinates.** The stock application forwards SDK-domain coordinates. The
SDK output is mode-dependent: the controlled original-APK GNSS mode0 callback
retains WGS84, whereas native DR mode1 produces a roughly 538 m shift on the
same Moscow source track. Fresh `inputTickTime` joins, rather than packet-send
timestamps, establish the DR result. The old 40 timestamp pairs collapse to
only two stationary SDK positions and do not independently establish the
conversion path. See [Coordinate parity](#coordinate-parity-follow-up).
Our historical OSM packages use the candidate SDK frame, and the 2026-10-23
car run obtained route confirmation and HNOA. Neither that compound experiment
nor original-producer parity determines the external receiver's datum or
proves that a frame change fixes the immediate dropout. The existing candidate
is limited to 35–40 E / 54–58 N; it is not a global coordinate implementation.

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
   ~~lights within 500 m~~ (corrected: apply the stock's lane-target distance gate,
   independent of the next light's location), ~~region 372~~ (corrected 2026-10-25:
   372 is the stock SDK's internal URID; the successful drive's outbound Region
   ids are 990001, see [region default](#region-default-from-the-successful-stock-drive-2026-10-25)). Off-route or lost: retain the
   latest guidance placement while fresh location coordinates continue; route end or
   Yandex gone: status 110 for 3 s, then 100
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
Region codes initially stayed at zero (no Amap adcodes); superseded by the
[owner-requested successful-drive default](#region-default-from-the-successful-stock-drive-2026-10-25).
jams and incidents are not mapped; ~~lane info (`0x800C`) and 3 km lanes (`0x800E`,
sent empty as the car did) are not mapped~~ (superseded: junction signs now feed both
lane surfaces, with per-lane losses recorded by `LaneGuidance`; continuous lane counts
remain a separate decision); ~~link granularity is Yandex's~~ (with the road store links
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
At the initial build-13 host acceptance, JNI decompression on Android had not
been executed. The build-15 correction below now establishes packaged Zstd
execution in the actual app for one selected route.

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
Android execution remained unverified at that historical build-13 receipt. The
build-15 correction below supersedes that runtime limitation for one actual route.

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

**Earlier first-publication gap; build 15 now runtime-checks acquisition on one route.** Later
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
car acceptance. Installation was not performed at those historical receipts;
build 15 was subsequently installed and checked as recorded below.

**Build-15 correction: installed Android acquisition (2026-10-24).** Build 14
was subsequently installed, but the actual app settled with
`reason=no_supported_package` (retained in
`captures/drive-kit-20261025/install-check/before15-denza-yandex-route-vector-status.json`)
despite a usable SDK-inventory Moscow region 1.
Its `vmap3fb.fb` is 826,573,656 bytes, exceeding the old 512 MiB cap, and has no
`region.pb` sidecar. Build 15 makes that sidecar optional for the SDK-bound
package: absence has its own identity sentinel and is rechecked, while a present
sidecar keeps its stat/SHA fences. The package header/version identity is also
rechecked around the scoped read. The read-only mmap cap is now
`Integer.MAX_VALUE`, the ByteBuffer indexing limit; this does not allocate a
file-sized heap array. The separate 2 MiB compressed-tile, dictionary and decoded-tile
bounds, 4096-tile acquisition cap, aggregate byte and matcher budgets remain.
Host mutation tests are retained in
`captures/drive-kit-20261025/install-check/offline-discovery-fix/receipt.json`.

The installed identity receipt
`captures/drive-kit-20261025/install-check/installed15-identity.json` verifies
`29.8.1-YandexHUD.15`, version code `739494315`, APK SHA-256
`dcbb3f8242aff7bffa925ba0a76e32abf72f71e68d2a75ec22d7d10fd7a98416`.
The actual app status
`captures/drive-kit-20261025/install-check/after15-denza-yandex-route-vector-status.json`
settled with `reason=prepared`: 67 requested/found/decoded tiles, zero missing,
4,129,844 decoded bytes, 4945 ms. This checks the typed SDK callback, app-UID
package-file access and packaged Android Zstd on this route. It does not establish
the same result for every route or installed region.

Of 511 route edges, that receipt reports `knownEdges=393`, `unmatchedEdges=34`
and `conflictEdges=90`; these counters can overlap and must not be summed as a
partition. Quality is `sampled_renderer_field_consensus`, with
`exactNativeId=false`, `continuousCoverageProved=false` and
`continuousLaneCountKnown=false`. Sample consensus is neither a native edge-ID
join nor continuous coverage or a continuous lane-count source. The source is
prepared with static attributes frozen and `sourceChangedSincePreparation=false`.
The scope of this receipt is attribute acquisition for that specific route.

The build receipt is
`captures/yandex-feed15-20261025/build/build-report.json`; the matching source
archive, including the native collector/relay sources, is
`captures/yandex-feed15-20261025/source.zip`, SHA-256
`684744d5de68f45e4ec6eb1df5dc832d2ac03912caca313254975fde07554762`.
Earlier build-13 host/source-only limitations above remain historical, with this
specific Android-route correction; full attribute coverage and factory parity
remain unproved.

### Build-15 parked comparison (2026-10-24)

After acquisition, the bounded `parked15-source` session retained Yandex's
coordinates and speed values, with both MPC/instrument writes disabled. The
real route and selection sends returned 0 for pathId `1792860804`; the serialized
route has 100 links. Road classes are 9 on 14 links, 8 on 26, 7 on 17 and -1 on
43; direction is 1 on 22 links and 0 on 78. All laneNum values remain 0 and
formway -1. These are actual outgoing values, not proof that all fields are known.

No same-path SELECTED acknowledgement was retained during 62.6 seconds after
selection. `confirmedPathId` stayed -1 and `real selection ACK required` blocked
dynamic sends. A zero transport result and state `LIVE` do not prove receiver
acceptance. This was not the runbook's `drive-1` or `drive-2` experiment, and does
not establish a cause for the absent acknowledgement or readiness for NOA driving.

The session was explicitly restored; its ledger records `RESTORED`, original
and restored stock enabled state 0. The arm file and reader process are absent,
both endpoints are closed and the last actual send time is unchanged. Progress
and passive packet files grew autonomously; the crash buffer was unchanged.
Evidence and byte hashes are in
`captures/drive-kit-20261025/install-check/receipt15.json`, with the rollback
ledger at `captures/drive-kit-20261025/ledgers/parked15-source.json`.

**Subsequent same-route stock-start comparison.** With the same installed build,
Yandex route and prepared attributes, `parked15-stockstart` changed only
`startProtocol` to `stock`; both MPC/instrument side channels remained off.
The retained `stockStart` reports a successful ODD region request, statuses
100/100/106/106/106 and a 5000 ms pre-route phase. The real service-44 selection
callback explicitly contains status 200 and the new pathId `1792861159`.
The unchanged real-ACK guard accepted it and admitted dynamic packets;
104 successful outgoing packets are retained, including the route, selection,
position and dynamic data. The session was then restored to stock state 0.

The acknowledged protobuf is 70,548 bytes and selection follows the route by
3 ms. This rules out a universal 64 KiB receive limit or a mandatory 61–87 ms
route-to-selection hold. Host comparison finds identical geometry, all 100 link
records, IDs, attributes, cuts and schema after excluding the new pathId and
five refreshed groupTime estimates; it does not treat those timing values as fixed.

This is a successful parked configuration, not proof that one particular start
message caused the earlier silence: the phase changes messages and timing
together. Nor does route acknowledgement establish NOA eligibility or complete
map semantics. The first failed comparison above remains valid historical
evidence but does not describe this subsequent result. Exact bytes, hashes,
decoded acknowledgement and restoration are retained at
`captures/drive-kit-20261025/install-check/ack-analysis/stockstart-receipt.json`;
the ledger is `captures/drive-kit-20261025/ledgers/parked15-stockstart.json`.

**Manual replacement and departure recording check.** In a new parked arm,
three paths (`1792861366`, `1792861464`, `1792861477`) each received an explicit
raw service-44 status-200 callback. Both replacements included `navStop` and
three actual NavigationStatus=100 sends before the next route. All 160 decoded
position payloads referenced the path belonging to their attempt; none used an
old path after the replacement route was published. The final 103-link route
prepared 45/45 vector tiles with zero missing tiles. This is tile acquisition,
not proof of complete or unambiguous attributes across the route. The session
was restored, with its endpoints closed, reader stopped and stock state 0.
Evidence: `captures/drive-kit-20261025/departure15/same-arm-acceptance.json`.

The following two-hour `drive15-source-stockstart` arm kept those source-valued
settings. At the 20:08 MSK parked check, path `1792861607` had an actual raw
status-200 ACK; 952 successful dynamic/event sends after it were retained,
including 97 correctly identified positions. Installed APK SHA was rechecked;
the detached reader was alive, stock processes absent and package disabled by
this arm, progress/packet files were growing, and the crash buffer was unchanged.
The local `ACTIVE_SESSION.json` records the active ledger and expiry (24 October
22:06:45 MSK). Its original stock state is 0 and requires explicit restore;
expiry does not reenable the stock package. Receipts are `departure15/drive-acceptance.json`
and `drive-wire-acceptance.json`. These establish parked route transmission and
recorder readiness only: missed-turn rerouting, two-hour retention and NOA in
motion still require evidence from the drive.

### Build-15 drive review (2026-10-24)

The owner reported frequent NOA offers followed by immediate degradation, and
city NOA on the factory cluster on Kievskoye Highway. Raw packets confirm the
pattern. The retained build-15 arm has 126 explicit DNP=1 intervals (status
intervals, **not driver button presses**), median 786 ms and maximum 5415 ms to
the next sample. 113 exits immediately carry explicit ICC=1. All 19,493 retained
mode observations, including reconstructed compact repeats, are mode 2; none
are highway mode 1. Absent fields remain absent, not observed zero.

All 126 exits are bracketed by successful actual position sends with intervals
at most 2028 ms. Seven real route sends receive explicit selection status 200;
five distinct PathMatch payloads contain pathId/endIdx but no status or NOA
distance. This separates short activation with a running, selected feed from
the earlier build-12 episode after publisher closure. It does not establish
sustained NOA or a receiver-level root cause.

The actual 844 link instances in seven route bodies have class 7/8/9/-1 counts
21/513/89/221, **no class 0/6**, and all carry formway=-1, laneNum=0 and
ownership=-1. On a long first Kievskoye section, class7/direction1 accompanies
a 5.415-second CNOA interval; a 2035-m return link carries class-1/direction0
and a 618-ms interval. Yandex labels the first 2748.863-m section with display
scheme HIGHWAY, while its route metadata has hasHighways=false; neither field
alone establishes the legal or Amap class. The source crosswalk never produces
0/6 and leaves category1 unresolved. However, the final vector status was
replaced after route completion, so raw class/fc for these particular links
were not retained: attributing their unknown class to category1 is unproved.

Static versus dynamic class differs at 147/1799 positions, including 22 on
Kievskoye-named links. In the installed source, NavigatorDriver retains the
published static table but uses latest.roadMatch for dynamic lookup, then
combines that match with the old link interval. This explains a producer-side
consistency defect, not the ECU's precise degradation decision. The prior
Amap+OSM positive control remains 54.103 s HNOA with 27 dynamic class6 samples,
historySpeed85 and legal limit0. Its successful static route body is missing;
source-tile lane/form values are not proven successful wire values.

The main position cadence is now about 1 Hz (median1005.5 ms, p95=1087 ms).
Two same-path gaps of2986/3259 ms occur despite fresh FINE guidance; both dry
and LIVE location processing stall and then catch up. A separate stale offline
inventory withdraws and republishes an unchanged route, producing a7830-ms
gap. These defects need separate profiling; none of the126 NOA exits occurs
inside a position gap over2.5 s. Final PAUSED follows a near-arrival sample,
routeId:null and actual100/100/100 end statuses, not an established moving failure.

The existing stock corpus maps raw alarm reason3 to the normal-DNP-exit UI
string, without identifying the underlying cause. Reason19 remains unresolved.
The notice dispatcher `f0.j(int,int)`, which the corpus jadx tree did not
decompile, reads with `--single-class k.h.e0.c.j.f0 --show-bad-code`: notice 4 →
«NOA/CNOA activated», 22 → «Downgraded to ICC» (已降级至车道保持巡航), 23 → «CNOA
activated» again, 12 → «NOA exited». The later targeted DEX fallback and resource
audit resolves 28 as «Avoid passing traffic» / 智慧避让中; 49 has no branch in
this APK, as does reason19. These are what the driver is told, not why. So the trip's ADAS itself
announced 22/23 in a loop: NOA held median 789 ms and the downgrade recurred every
~1.4 s. Downgrade instants show no lag to any outbound topic that differs from
random instants, so no single message of ours triggers them. The stock map's
own family fallback is `x6.j()`: matched class 0 or 6 → HNOA, anything else → CNOA;
`j0/o.java` otherwise copies ADAS `noaMode`. The owner adds that on the Amap+OSM
drives CNOA came only inside the settlement and held for tens of seconds
(25 s and 7 s), while this feed got CNOA offered on every road, Kievskoye included,
and could not hold it. Decode, timing script and output:
`…/postdrive-20261024-204337/reports/notice-decode/`. The next offline
work on road completeness remains immutable per-route attribute provenance, a
consistent published static/dynamic road view, evidence-backed expressway
classification and profiling the processing/inventory gaps. Immediate dropout
has priority over those improvements; the follow-up below separates it from
highway-family selection. No eligibility-seeking field constant or
vehicle-control write is justified by this review.

APK SHA was verified before and after capture. The owned session was restored
after evidence collection: arm absent, endpoints closed, reader gone, original
stock enabled state0 restored, crash buffer unchanged. Private raw bytes,
file:line receipts and rerunnable analyses are under
`captures/drive-kit-20261025/postdrive-20261024-204337/reports/`, with
`SUMMARY.md` and `summary.json` as the index. The standard aggregate includes
older rings; the three targeted reports filter the actual current arm.

#### Immediate dropout follow-up

The owner clarified that one stalk request started repeated NOA→ICC→NOA
cycling; he then cancelled with the stalk and tried again. The 126 active
intervals must not be described as 126 manual attempts. In 100 of the 125
adjacent pairs, the next DNP=1 arrives 497–892 ms after the exit (median603 ms).
This is an observed recurrence, not a recovered ECU timer or permission rule.

The current-arm phase audit reconstructs the same 126 intervals independently
from raw packets. In27 intervals, no new position/TrafficInfo arrives between
activation and exit; in3, no bridge packet of any topic arrives. Both transport
attempt and successful-return timestamps were checked. Exits within100 ms of
position account for12/126 (9.52%), versus551/5718 (9.64%) ordinary DNP=2
control observations. All own navigation-status packets within±2 s of the
boundaries are101; no own100/106/110 occurs there. A newly sent bridge packet
is therefore not required for every dropout. A persistent invalid input,
receiver state, or another publisher is not excluded by that result.

Position/route auditing finds valid step/link indices in all1799 packets and
coherent geometry and remaining distance at124/126 exits. A real but bounded
defect occurs during rerouting: the saved source progress scalar stays fixed
while the coordinate moves, producing up to170.84 m error in the sent link
remaining distance. It affects exits70/71, not the general cycle. The known
static/dynamic class mismatches and worker gaps likewise do not explain every
immediate exit.

The original stock log provides a closer positive control than HNOA alone:
city mode2 holds24.902 s and7.205 s. The longer interval's12 retained road
samples contain class-1 seven times and class9 five times, historySpeed25 and
navigation101. All12 direct position log lines have ownership0; vehicle speed
and GPS speed differ in8. The shorter interval has ownership0 in3/3 positions
and different speeds in2/3. The highway interval has27/27 ownership0 and
different speeds in13/27. Its PilotStatus duration is54.203 s; the older54.103 s
report uses separate cluster-notice boundaries. Stock logcat is sparse and
prints decoded scalar defaults; it is not a substitute for protobuf presence
or a complete successful static route body. Unknown dynamic road class was
compatible with sustained city activation in this control; this does not
prove other road attributes are irrelevant.

Three persistent producer differences now deserve specific contract checks:

- The installed feed sends WGS84 route and position coordinates. The original
  stock forwards SDK route and localization coordinates unchanged. On the
  current drive, the installed candidate SDK-frame formula differs from WGS
  by527.54–540.41 m (median537.51 m). This is a producer-frame difference,
  not proof of the external receiver's expected datum. Internally consistent
  route/position geometry alone cannot settle that question.
- Current static and dynamic ownership are consistently-1, versus observed0
  in the successful stock position samples. The native road/access source
  must establish public/private semantics before a conversion is chosen;
  neither a successful route ACK nor a road name establishes ownership.
- The bridge uses GNSS speed for both speed fields in1799/1799 positions.
  Original stock obtains vehicle speed independently. The missing provenance
  is confirmed; an ECU speed-rejection condition is not.

The drive also explicitly disabled MPC and instrument navigation-state side
channels. The older stock log contains CAN navigation output, but does not pin
the MPC value. These states remain part of the comparison, not a demonstrated
cause or a reason to change several switches together.

Next diagnostic work should recover an independent exit-reason signal and
compare these persistent contracts against original stock output on the same
saved inputs. A replacement build should be justified by that comparison;
road enrichment alone must not be presented as a fix for the automatic cycle.
The existing OTA framework admits `ADAS_NOA_QUIT_PROMPT` (`0x1FF0201C`) to
generic GET/listener APIs on device1038. Its transport registration, live
publication, value decoder and freshness semantics remain unproved; it was
not recorded under its identifiers in this drive. It is a candidate for
passive event capture with monotonic receive timestamps, not an already
decoded exit reason. `ADAS_PREVIEW_ABNORMAL_REASON` has weaker app-name-only
evidence. See `reports/dropout-notices/noa-quit-signal.md` for exact sources.
No production code, installed APK, vehicle state or remote repository changed
during this follow-up. Local evidence and reproducible scripts are
`reports/dropout-phase/`, `reports/dropout-position/`,
`reports/dropout-notices/`, and `reports/dropout-stock-control.{py,json}`
under the post-drive capture above.

#### Build-16 position observer

Installed APK: `29.8.1-YandexHUD.16`, versionCode `739494316`, SHA-256
`cb97cacb5dbb25d6fe186a91a30722eb2c7b3754c2cc17047a3839b8e6c44e1b`.
The build uses the working copy, not clean main. `YandexOfflineGraphFiles.java`
and `OfflineVectorPackage.java` are byte-identical to the pinned build-15 source
archive. Only `AdasPassiveClient`, `AdasWire` and `AdasObservations` differ among
the production module sources. Build/source/install receipts and the rollback APK
are retained locally; nothing was pushed. The build-16 source archive SHA-256 is
`8a7885fdc97556d16cfa265242b8f694e69c98136a0a621a1197d5e6e4413deb`.

The overlay's passive receiver optionally starts service/instance 10/10 and
subscribes to `0x0004000A000A8001`. Setup failure is isolated from the required
ADAS answer subscriptions. Raw packets enter the existing bounded recorder;
the observation does not change readiness, stock-writer detection, or publishing.
The bridge receiver's required services and subscriptions are unchanged. Host
decoding preserves all 37 descriptor fields and field presence. Vehicle speed,
fusion status, position type and localization output offset are absent in this
capture, not observed zero. The passive harness passed 557 checks and the full
Python suite passed 320 tests; build pipeline checks and APK signature passed.

The first installed capture spans 121.443 seconds with 2,025 service-10 records
and no decoding errors. There are 1,977 fresh same-process guidance pairs within
one second: median distance to Yandex's classified/matched WGS84 point is
10.3425 m, versus 528.0192 m to the shifted candidate. No independent raw GNSS
was recorded. Coordinates are effectively stationary. All packets contain
`hdStatus=1`; constant lane/offset values have no established physical meaning.
These are ADAS output coordinates; they do not prove its internal datum or the
datum required for an incoming route, nor resolve the NOA dropout cause.

Evidence: `captures/yandex-feed16-vehicle-position-20261024/`, especially
`install-check/reports/local-recording-review/{review.json,review.md,rates.json}`
and `frame-check/before.json`. The latter independently verifies the installed
hash, park predicate, measured zero speed, absent arm, closed publisher and
usable offline inventory. That initial passive check created no arm; the later owner-authorized loop-frame session is described below.

Recorder counters show no drops, failed posts or write errors in this capture.
Service 10 arrives at approximately 16.67 Hz and raises the measured packet log
rate to 0.830 MiB/min. The existing seven 8-MiB generations retain roughly
58 minutes in completed generations, at most 67.5 minutes total at that rate.
The earlier two-hour estimate from build 15 does not apply to this new stream;
moving-trip rates and retention remain unmeasured.

**Follow-up: output proximity does not reject the shifted candidate.** An earlier
chat conclusion that the 528 m separation made the candidate inappropriate for
testing was premature. The observation does not determine the input contract.
Local replay of the exact first 72-byte notification now covers the original
`a0.d` parser, platform-210 branch, admission/buffer, `a0.b` and
`PosService.setSignInfo` (`0x40000003`). Each successful run submitted the
original packet once and 300 further copies with only the epoch timestamp
rebased. All coordinates and other decoded fields were preserved; the fixture
selects the platform branch and an original factory localization configuration.
These are emulator fixtures, not modifications installed on the car.

The fused-only run has 301 distinct submission ticks and no LocInfo callbacks.
Separate controls supply three GNSS bootstrap samples at the same point, then
the same fused replay: factory DR configuration yields one GNSS-linked output
537.495 m from WGS84 and 0.034 m from the original inner transform; factory GNSS
configuration yields one GNSS-linked output 0.041 m from WGS84. Neither output
has an inputTickTime belonging to a fused submission. Thus the test reproduces
configuration-dependent producer coordinates but does not establish a fused
position output or ECU acceptance. Empty output is not proof of rejected data.
The first spawn timeout and the subsequent fixture-field-name failure are
retained separately and provide no acceptance evidence. Reproduction and exact
event/tick joins: `captures/yandex-feed16-vehicle-position-20261024/fusion-replay/`
(`analyze.py`, `analysis.json`, runs 03–05). No car command changed state during
this follow-up, and the publisher was not armed.

**Subsequent owner-authorized loop-frame session, 21:53 Moscow.** After fresh
human P/parking-brake confirmation and measured zero speed, build 16 was armed
for 30 minutes with `loop-frame`; both optional navigation side channels remain
false and road/lane/speed fields use the Yandex source. Installed APK hash was
verified before and after. Arm `21ed02f8-ebf8-4c03-b965-05f7077bc315` received an
explicit same-path SELECTED 200 for `1792868010`. The retained pre-departure
window contains 498 successful packet sends, 49 positions spanning 48.171 s,
maximum interval 1059 ms, no transport failures and no rejection after ACK.
All 49 position path IDs equal the confirmed route ID; this wire field is a
double and must be compared numerically, not by integer-versus-float strings.
There are 56 fresh guidance records and 931 service-10 packets, no recorder
drop/write-error counters, and an unchanged crash buffer. The latest state is
LIVE with fresh sends and no active prerequisite, failure or suspension. This
establishes transmission and recording, not sustained NOA operation.

The session was initially left armed with expiry about 22:23:29 Moscow and
stock map state 3 (original state 0). It has now been restored after the drive,
as recorded below. Ledger:
`captures/yandex-feed16-vehicle-position-20261024/frame-check/loop-frame.rollback.json`.
Pre-departure receipt, raw tails, screenshot and verifier are in `frame-check/live/`.

**Completed loop-frame drive and restoration.** The owner reports that NOA was
never offered. The current-arm capture covers 21:53:28.928–22:04:06.096 Moscow;
movement in Yandex guidance is retained from 21:57:33.591 to 22:03:28.955.
Analysis must be restricted to arm `21ed02f8-ebf8-4c03-b965-05f7077bc315` and
recorder source session `8c1bb109-a4c7-48fa-b9ee-074197dcba99`: older ring
entries contain earlier builds and their NOA episodes. The generic whole-folder
review is not a current-arm summary.

Four routes were fully reassembled and acknowledged with explicit same-path
SELECTED status 200. There are 5,779 successful packet sends and 562 positions,
no failed send results, no numeric current-path mismatch and no positions sent
before the corresponding ACK. Maximum within-path position intervals are
2,030/1,149/1,102/1,104 ms. Between paths, the first replacement interrupts
positions for 13.600 s while sampled stationary. Two subsequent moving yaw
reroutes interrupt positions for 8.264 and 7.469 s: route-specific offline
admission is withdrawn, the old guidance end sequence runs, a fresh start and
selection follow. The generic reason “Офлайн-карта загружена” is not evidence
that the regional download disappeared. These gaps are a separate continuity
finding; they do not explain the absence of NOA before those reroutes.

The passive stream has no significant coverage gap during guidance. Raw
PilotStatus packets contain no explicit `dNPStatus` available (2) or active (1)
values; `dNPStatus` and `iCCStatus` are absent, not transmitted zero. Journal
getter zeros must not be treated as raw zero evidence. Mode replies contain
mode 2, which alone is not a NOA offer. Four selected-path acknowledgements do
not establish NOA eligibility. Alarm reason 19 appears only after the final
navStop; it does not explain the earlier lack of offers.

The previous build-15 drive has explicit DNP availability/activity on the shared
village footprint, not just on its separate highway section. Joining previous
PilotStatus to preceding FINE guidance within 1.5 s, then to current moving
WGS84 guidance within 30 m and the current speed range, retains 1,714 available
and 263 active samples. Even with heading difference at most 30 degrees and
speed difference at most 2 m/s, 501 available and 87 active samples remain.
These are correlated samples, not independent stalk attempts. This reduces the
road/speed-exposure explanation but does not control the route, surrounding
traffic, driver request or ECU state; it is not a causal datum experiment.

There are 10,620 service-10 positions in this arm and 9,877 fresh same-process
pairs with classified/matched Yandex guidance. For the 5,060 pairs with guidance
speed above 1 m/s, median distance to WGS84 is 4.411 m (p95 9.985 m), versus
535.410 m to the shifted candidate (p95 543.188 m). All retain `hdStatus=1`.
This is moving output-coordinate evidence, still not proof of the ECU's
incoming-route or internal datum. The shifted variant did not achieve NOA
availability in this drive; a coordinate root cause is not established.
Actual reassembled wire routes verify all 640 retained source vertices against
one transform plus the stock grid, with no identity or double-transform matches.
557 fresh same-route outgoing-position/guidance joins within 600 ms have median
error 0.185 m against one transform; this temporal comparison does not assert
same-tick identity. The candidate was applied coherently, rather than accidentally
twice.

**Same streets, frame switch changed.** The switch moves coordinates and also the
link IDs derived from them (all 142 checked IDs of both drives reproduce from the
transformed geometry), so its effect cannot be split between the two; the
other fields of the feed were equal. All 299 moving guidance samples of this
arm (above 5 km/h, median 13.8 km/h) lie within 30 m of a point where build 15
received a DNP offer on 2026-10-24 (median 2 m). On those same streets build 15,
WGS-84 and otherwise the same feed, had DNP offered (2) or active (1) at 195 of
284 moving positions (69 %); loop-frame had none. ICC was rarely available there
in either drive (17/284 in build 15), so its absence today is not a new
condition. With one arm and other differences (build 16's observer only, a later
hour, a different route), this is an association: the shifted run had no offers
where the WGS-84 run had them. It does not establish that the frame caused the
change. **Superseded 2026-10-25:** the WGS-84 arms on the same streets the next
day (loop-wire, build-17 A1 with the 2026-10-24 variant) also lost the offers
(≤6 % on the first route), so this arm's missing offers cannot be attributed to
the frame at all; the frame remains untested on the car. The stock's own shifted feed got
5–6 DNP=2 onsets per 2026-10-23 drive against build 15's 1,055. The next arm keeps
WGS-84 in the proposed comparison. The later `loop-wire` proposal removes link
fill-ins from that comparison; it has not been armed in the 2026-10-25 local
follow-up. Its ownership/region/limit values remain experimental, not established
receiver requirements or a validated road description.

**loop-wire, 2026-10-25 10:25–10:36 (arm `46b7e099`, build 16, WGS-84).** Five
routes got SELECTED 200. Raw PilotStatus shows DNP available (2) only in seven
blips of about 0.1 s and five activations, each DNP=1 for 495–589 ms, then ICC
active for 4.3–29.6 s with no return to DNP; the driver saw «Degraded to ICC» at
once. On the same streets (313 moving samples, median 16.8 km/h) DNP was offered
or active at 2 of 313, against build 15's 210 of 303 (69 %) there. Both build-16
arms, with different switches, lost the offers build 15 had; the one change
common to them is build 16's overlay subscription to `10/0x8001
VehiclePositionInfo` (a client of service 10 that build 15 never started; the
stock map subscribes to it too and got 5–6 onsets per drive). This is a
hypothesis, not a cause: the time of day and the car state also differ. The
control that separates them is build 15 with its 2026-10-24 variant on the same
streets.

**Build 17 A1, 2026-10-25 11:10–11:22 (arm `1fea7c6c`, service 10 off, the
2026-10-24 variant).** The subscription hypothesis does not hold: with service 10
off and the exact build-15 variant, DNP was offered or active at 9 of 331 moving
samples (3 %); nine activations of 498–607 ms each went straight to ICC. On the
first route, before any reroute, builds 15 and 17 run the same feed code (17
adds only the reroute replacement, live service-13 recording and the observer
switch); there build 15 had DNP offered or active at 357 of 439 moving positions
(81 %), A1 at 10 of 171 (6 %), on the same streets with the same field values
(ownership -1, class 9, limits 20/60, historySpeed 18/7, Region 0). Lane-guidance
messages (`Intersection`, `NextIntersectionLanes`) were sent only on 2026-10-24,
but within that drive offers ran at 67 % with them and 70 % without, so they do
not explain it either. The fall in offers since 2026-10-24 evening is therefore
outside the feed: car state (a new boot on 2026-10-25), light or road conditions.
The 0.5-s NOA→ICC drop itself appears in every configuration tried.

The final persistent PAUSED state follows navStop/route absence, before arm
expiry. Last sampled movement precedes that stop by 840 ms, so it cannot be
labelled a proven stationary post-drive pause. After the owner's fresh P and
parking-brake confirmation, evidence was copied before restoring the exact
ledger. Restoration reached `RESTORED`; arm absent, publisher and answer
endpoints closed, reader process absent, original stock enabled state 0, and
installed build-16 APK hash unchanged. Before/after crash buffers are identical
and contain only earlier morning entries. No new drive or variant was armed.

Evidence and reproducible analyses: `frame-check/postdrive/` under the build-16
capture, especially `reports/transmission/arm-review.json`,
`reports/recording/focused-recording.json`, `reports/position/summary.json`,
`reports/adas/REPORT.md` with its matched-footprint CSV,
`capture-receipt.json`, and `after-restore.json`. All listed rotating generations
were recovered. The initial bounded tar transfer stopped in an old generation;
its complete files and a separate remainder transfer form the verified extracted
capture. No input file or road-control setting was altered for analysis.

#### Local diagnosis after the loop-frame drive

The two moving reroute gaps now have a pinned-source explanation and a host-only
reproduction. `LiveMapBridge.reconcileOffline` (`:131–147`) requires both a READY
offline decision and equality of the current and published route subject. The
route identity changes before its full converted observation reaches the sender.
If reconciliation wins that ordering, it calls `endGuidance`, withdraws the route
and creates a new `NavigatorDriver` even though the installed offline inventory
is READY. The new driver loses the yaw lifecycle: the dry driver emits route type
2, while LIVE treats the same replacement as type 1 and repeats a fresh start.
If the complete observation arrives first, `verifiedReplacement` (`:592–596`)
keeps the reroute lifecycle instead. Both orders are reproduced with unchanged
build-16 production sources, a fake clock and a host-only Binder boundary.

The actual 8.264/7.469 s gaps are dominated by 2.005/2.008 s for three end statuses
and 5.006/5.007 s for the fresh-start hold. Real selection ACK latency is only
103/32 ms. New-route dry conversion finished 1.209/1.406 s before the LIVE end
sequence completed, so it cannot account for the remaining serial wait. The
five-second start hold was a chosen experiment timing, not a recovered mandatory
factory delay. Original Java keeps yaw replacement separate from navStop; an
existing original-APK replay has nine yaw replacements with no intermediate
100/106 phase (that emulator replay is lifecycle evidence, not ECU acceptance).
The exact first reconciliation caller on the Android run is not logged.

This establishes the cause of these two transmission gaps, **not** the cause of
missing NOA offers or the older immediate dropouts: offers were absent before
the first gap. The diagnosis itself changed neither control code nor gates;
the subsequent local correction is recorded below. Reproducer,
source/archive hashes and additive timelines are in
`frame-check/postdrive/reports/reroute-cause/` under the build-16 capture.

A second comparison uses 71 valid pairs of actual sent positions on shared
village streets, with similar direction/speed and both paths selected and not
ended. Road class 9, formway −1, lane count 0, direction 0, ownership −1, admin
code 0 and static limit 0 are identical across all pairs. Dynamic class 9,
legal limit, position ownership −1, navigation status 101 and region values are
also unchanged. The four groups proposed by `loop-rest` therefore do not explain
why offers disappeared between these two drives; this does not settle their
possible contribution to the older short active intervals.

All compared link IDs change. They are hashes of link grid coordinates after
`Coordinates.toSdk`, not stable independent map identifiers: changing frame also
changes derived link IDs. Twenty paired link geometries have different length
or point-count segmentation, and dynamic progress/speed naturally differs.
Consequently the effective variant changes one configured frame choice, but the
wire comparison is not a byte-identical feed with only coordinate scalars changed.
`reports/field-delta/` retains raw pairs, joins, link/coordinate checks and limits.

Exact build15/16 source and APK comparison excludes a hidden converter update:
all 46 archived adas-link files and all native libraries are identical. Only
three production passive-observer Java files and version identity change;
20,248 APK entries match, with manifest differences limited to versionCode/name.
The additional service-10 subscription remains an uncontrolled build difference;
there is no observed callback/drop/send failure attributable to it. Details:
`reports/build-delta/`. All work in this follow-up is local; no build, installation
or new arm was performed.

#### Loop-wire drive review (2026-10-25)

The owner reports no visible CNOA/ICC offers and immediate “Degraded to ICC”
after activation. Raw ADAS status shows that a brief active CNOA state did
occur: five explicit `dNPStatus=1` intervals, each followed by explicit
`iCCStatus=1`, with city-family mode 2. These are five state intervals, not a
count of independent stalk requests. Device wall times converted to Moscow:

| First active response | First subsequent ICC response | Observed interval |
| --- | --- | ---: |
| 10:30:43.212 | 10:30:43.707 | 495 ms |
| 10:31:05.724 | 10:31:06.232 | 508 ms |
| 10:31:44.431 | 10:31:45.020 | 589 ms |
| 10:34:17.817 | 10:34:18.321 | 504 ms |
| 10:35:03.510 | 10:35:04.015 | 505 ms |

These durations are between received status transitions at approximately 10 Hz,
not sub-cycle ECU execution timing. Among 6,608 current-arm PilotStatus samples,
DNP is explicitly active in 26, available in only seven isolated samples
(68–109 ms intervals), and absent in 6,575. ICC is explicitly active in 654;
no explicit ICC-available value is retained. This explains why the raw record
can contain activation without the driver seeing a sustained offer. Absent
fields are not observed zero. No `pilotNotice` is transmitted in the retained
alarm stream; reason 19 appears only later at 10:35:56.431. It does not identify
the cause of these five exits, and the cluster wording is the owner's observation.

The original accepted path covers the first three exits, before any reroute
teardown. Successful position sends bracket all five exits with intervals
1,004–1,058 ms. The first four have normal matched ownership 0; no lifecycle
change occurs within five seconds of those exits. The fifth is already in
Yandex rerouting: unmatched ownership -1 begins before the exit, whereas the
actual route teardown follows 3.961 s afterwards. Therefore neither the known
handoff pause nor the unmatched-ownership interval explains every dropout.

Scope is arm `46b7e099-b729-4447-a073-d628dd46f5b2`, recorder source
`f232ba3b-1c6f-4478-a82e-a3f714de2237`, pinned build-16 APK `cb97cacb…`.
Older ring generations and old-boot elapsed values are explicitly excluded.
The preclosure capture has five complete routes with explicit same-path SELECTED
200, 7,512 successful sends and 799 positions: no transport failure, wrong
current-path ID or position before its path's ACK. Four replacement gaps are
7.854/7.578/7.795/7.925 s. The local handoff correction below was not installed.
All retained source vertices in those wire routes match the WGS84 stock grid.
The variant sends Region 990001 throughout; legal-limit fields are absent with
protobuf default 0. Matched positions (781) omit ownership with protobuf default
0; 18 rerouting/unmatched positions explicitly retain -1. The variant is a
matched-position fill-in, not an unconditional overwrite of the unmatched state.

Sparse service-10/outbound joins at the five exits show location separation
1.22–5.69 m and close headings, rather than a 537 m discrepancy. Different
sample times and undeclared service-10 speed units prevent interpreting these
pairs as a same-tick equality test or proof of the receiver's required datum.
Route selection and successful transport still do not establish road matching,
eligibility or the predicate that caused a downgrade. This compound variant
failed to sustain CNOA; it does not eliminate interactions among its changed
fields or establish a permanent replacement value for any field.

**Recording boundary.** Last observed movement is 10:35:52.188. Raw ADAS
continues to 10:36:11.574, then the overlay emits `closed` at 10:36:11.612.
AVC camera foregrounding stopped Navigator's Activity at 10:35:41.613. The
installed source confirms the overlay's 30-second linger and closure; the live
receiver intentionally excludes service 13 from raw recording. Its continuing
callback counter therefore does not replace the missing status record. The
261 later FINE guidance samples all have speed 0: movement is covered, later
stationary ADAS state is not. No malformed JSON, recorder drop/write-error
counter, target crash or process restart was observed. Recording should be
independent of overlay visibility before claiming full autonomous coverage.

**Closure.** The arm was removed, stable publisher/answer closure proved and
the owned reader stopped. Both navigation side channels were disabled in this
variant and required no restoration. The owner explicitly requested that the
stock map remain disabled: final state 3, no stock processes, original state 0
retained in the ledger, final phase `CLOSED_STOCK_DISABLED`. A local wrapper
reused all normal closure and identity checks but replaced the final package
enable with disabled/stopped readback. Do not rerun plain `restore` against
that closed ledger. No APK was installed, no new session armed and no push made.

Evidence: `captures/drive-kit-20261025/loop-wire-20261025-102413/postdrive/`:
`SUMMARY.md`, `capture-receipt.json`, hashes, before/after archives, closure
script and three independent reports under `reports/adas`, `reports/transmission`,
`reports/recording`. Times in the episode table use the recorder's device clock;
host capture timestamps are not treated as synchronized clock pairs.

#### Local reroute handoff correction (2026-10-25)

The working copy now distinguishes a different route awaiting preparation from
actual offline-data loss. `LiveMapBridge` blocks all old-route egress immediately,
retains its driver/guidance lifecycle, and accepts only an observation with the
current route ID, epoch, geometry hash and fresh offline admission. The new path
still needs its own selection ACK and fresh FINE fix. A ready yaw replacement
therefore avoids the old three-status end and five-second fresh-start phase;
route preparation and actual ACK latency still remain.

Pending lasts at most 5,000 ms from the first sender reconciliation. Further
route changes and inventory refreshes do not extend it. Timeout, cancellation,
arrival, unavailable/stale inventory and same-ID geometry/epoch changes retain
the withdrawal path. Checks after potentially blocking refresh/conversion prevent
late preparation from extending this window. Normal synchronous ADV_AUTO_FUN_INFO
and asynchronous positions are covered separately from replacement behavior.
`LiveEgressGuard.revokeCachedReadiness` only revokes cached admission at the final
transaction boundary; genuine transport errors remain terminal.

The same runtime regression fails on archived build-16 production sources at
the reconcile-first case and passes on the candidate: 82 handoff checks and 261
existing runtime checks. Separate compiled plain-main suites pass 411 guard,
76 lifecycle and 19 offline-gate checks. Independent source review found and
helped correct an initial overbroad veto of ordinary dynamic messages.
Reproducer, before/after output and source hashes:
`captures/reroute-fix-20261025/`. Initially source-only, this correction is now
included in [local signed build 17](#build-17-control-candidate-2026-10-25), with
337 runtime checks after the recorder regression was added. The APK is not
installed and no vehicle session was armed in this preparation. It does not
establish a cause or fix for NOA availability or immediate degradation.

#### Coordinate parity follow-up

The source for this comparison is the exact build-15 archive
`684744d5de68f45e4ec6eb1df5dc832d2ac03912caca313254975fde07554762`,
original stock APK
`3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793`,
and original `libAutoDice.so`
`a3a0279dec67e158babb0cd84f8501596c4750b00fb04d74136e699aaa02fe5a`.
Evidence lives in `reports/coordinate-parity/` under the post-drive capture.

The native public guard at ELF VA `0x2092a4c` preserves Moscow coordinates;
its China bounds differ from the inner transform. The pinned Java candidate
and original inner wrapper at `0x20929dc` agree bit for bit on 408 retained trip
points, including stock-grid rounding. Original machine-code host execution
and Android native calls are separate checks. REA historical addresses are
ELF VA + `0x100000`; Android hooks use loaded module base + ELF VA. Byte spans and
hashes are recorded in `native/` and `batch/`.

The original APK ran on a session-owned, read-only emulator. GNSS input used
retained moving positions/speed/course, `isEncrypted=0` and fresh SDK ticks.
The moving control retains GNSS mode0 and produced 340 callbacks for all 35
input ticks. Across the seven shared inputs after initialization, its first
callbacks are 0.262–5.927 m from WGS84; the DR callbacks are 0.382–5.822 m from
the original inner-formula output. The two modes differ by 536.387–538.268 m
on those same inputs. The DR run changes only the native
`PosService.init` configuration through the original Java factory's
`d(1,false)` result. No coordinate return value is substituted. Original
location observers show 62 DR callbacks tied to 8 of 35 submitted input ticks.
The first callback is 20.27 m from the inner-formula output; the next seven
first callbacks are 0.38–5.82 m away, and approximately 536–542 m from WGS84.
Input-to-first-callback delays are 55–97 ms. Both configurations still call
the public GNSS guard, which returns unchanged coordinates: the DR shift
occurs later in the position-processing path. Its exact later native call
site has not been recovered.

This is a native localization comparison, not a complete car-sensor replay:
no wheel/IMU stream was supplied, and only 8 DR input ticks produced retained
callbacks. The old real-car corpus records `locType:1`, making DR relevant,
but neither that fact nor this fixture establishes the ECU's interpretation.
No SOME/IP output or ADAS decision is observed in the emulator. The earlier
stationary 40-pair fit reproduces numerically but contains only two unique SDK
positions; the original zero-speed resend runnable can reuse a point. Its
send timestamp must not be treated as the GNSS measurement timestamp.

The field inventory covers 26 scalar fields / 13 coordinate-pair types. All
nine active families use the selected frame exactly once; three reset-only
families preserve zero and incidents remain an empty list. Pinned-source
checks passed 612 existing and 277 added assertions. This is field plumbing,
not independent numerical proof. Stock dynamic wrappers forward SDK doubles;
build15 rounds most dynamic points to the route grid, while facilities retain
doubles. The candidate has a Moscow envelope and the copied light-existence
predicate assumes positive longitude/latitude, so global support remains open.
Future nonempty incident/construction/interval-camera producers need explicit
source-frame conversion; current zero output is not proof of their support.

`candidate/variant.json` was prepared from the completed trip's exact variant
and changes only `frame=STOCK_GCJ` plus the identifier. It was subsequently used
in the owner-authorized parked comparison below and that session is closed.
Stock start and both disabled navigation side channels are preserved. Host
validation and the archived Java parser agree. All retained route/position
points fit the candidate's envelope; this says nothing about a future route.
No product default or APK changed. During the owner-confirmed P/parking-brake
check, the installed APK SHA matched build15 and measured speed was zero.
Session `f816346f-57db-4124-8959-3e462af7c0c4` sent path `1792866243`; ADAS explicitly
confirmed the same path with SELECTED 200. PathMatch contained pathID and
endIdx 664, with no match status or NOA distance. Over 66.856 s the retained
egress has 497 successful packets and 60 positions, no transport failure, and a
maximum position interval of 1098 ms. Both additional navigation channels
remained disabled.

Yandex recalculated the selected route before publication. The actual source
snapshot has 550 points; the earlier 533-point selection is retained separately.
Every one of 536 unique transformed source vertices occurs in the 665-point
published route; 619 point instances match exactly on the stock grid. The
remaining 46 interpolated instances lie within 0.0311 m of the transformed
source polyline. For 20 position samples in the earlier capture, matched
positions are 0.0075–0.0589 m from the transformed source guidance and roughly
537.5 m from WGS84. Raw GNSS input pairing is not established by this guidance
record. This verifies actual coordinate transmission and selection, not a
NOA fix or the receiver's internal datum.

The session restored successfully: arm absent, publisher/answer endpoints
closed, reader process absent, original stock enabledState 0 restored, installed
APK unchanged and crash buffer unchanged. Its temporary reader JAR/log were
removed after local capture. The read-only quit-prompt getter returned raw 0
before/after; publication, freshness and the meaning of 0 are still unknown.
Evidence: `parked/{rollback,postflight,receiver-check,coordinate-wire-check}.json`.
The car was released to the owner's other agent; this session retains no
vehicle lease. The owned emulator and its forward were also stopped/removed.

#### Stock CNOA hold against build 15, field by field

A second host-only pass parsed every stock send template of
`k/h/a0/c/c/a/f0.java` in both 2026-10-23 drive logs and joined it to the
build-15 wire by street: positions within 25 m of the stock's own positions
inside its 24.9-s and 7.2-s CNOA holds (stock coordinates inverted to WGS-84).
Build 15 drove those streets at 20:18:15–20:18:25 and 20:37:34–20:37:36 and
went active eight times there for 0.5–1.7 s. The stock log is decimated
(`LogIntervalUtil.downConversion`, every 2nd–5th send), so values compare and
rates do not; an absent proto3 field of ours counts as its wire default.

What the stock never sends while guiding, and build 15 always does:
position ownership is 0 in all 1,747 stock positions that carry a path across
both drives (-1 only with no path); ours is -1 in all of them. The stock's
parked route over the same streets (`first-full`, 55 links) carries laneNum
1 (52) / 2 (3), formway 15, link ownership 0, adminCode 990001 and hasMultiOut
true (54/55); every build-15 link carries laneNum 0, formway -1, ownership -1,
adminCode 0 and hasMultiOut false. **Corrected 2026-10-25:** `first-full` was
captured at 20:48 with package-d, after the hold drive; drive-02 ran package-b,
which estimated LaneNum 1 where OSM had none and predates package-d's formway and
ownership fields. No route body of the holds is retained, so these link values
are not what ADAS received while CNOA held. The dynamic values in this paragraph
(position ownership, Region, speed limit, light) do come from the hold drives'
own send log. Persistent elsewhere: Region ids 990001
against our 0; TrafficInfo speed limit 0 in all 2,413 stock records against
the Yandex limit (20 on these streets, 60–90 on Kievskoye) plus camera speed
and distance; traffic light exist 1 without coordinates against our 2 with the
coordinates of a light ahead. Minor: our links are finer (median 23 m, a
quarter under 10 m, minimum 1 m, against 47 m and 5 %); historySpeed 18 against
25 on these streets; empty voice command and link mainAction 0; the stock sent
no NextIntersectionLanes or Intersection in either drive.

Equal, or shown harmless by the control: 1 Hz cadence of every periodic topic
during our active intervals (the light at 0.6/s), NavigationStatus 101, tidal,
tunnel, AdvAutoFunInfo, empty Lanes3km, parallel/mix-fork 2. **Corrected by the active-window recheck below:** the stock held CNOA while
route indices/offsets froze, but both output coordinate pairs continued moving.
This shows incomplete progress in those observed holds; it does not establish a
universal rule about which progress fields the ECU needs.
`ExperimentVariant` already switches frame, position ownership, link laneNum,
formway, ownership and adminCode, region and a zero speed limit; it has no
switch for the light, hasMultiOut or link granularity. Ranked list, script and
output: `…/postdrive-20261024-204337/reports/stock-diff/`.

**The stock's HNOA hold on Kievskoye, same send log (added 2026-10-26).** The
longest stock hold of 2026-10-23 is HNOA at 19:14:43–19:15:38 (54.2 s), used above
only as a reference. Inside it the stock sends TrafficInfo roadClass 6 in all 19
records (the CNOA holds: 9 and -1) and historySpeed 85 in all 18 SpeedIntersection
records (the CNOA holds: 25). The rest matches the CNOA holds: position ownership
0 (28/28), Region 990001, noODDRegionDist 2147483647, speed limit 0, traffic light
exist 1 without coordinates. NextAction carries iconType 5 with mainAction 8.
This agrees with the earlier physical HNOA control in
`captures/noa-bridge-audit-20261024/reports/stock-semantics.json` (27 samples:
class 6, history speed 85, legal limit 0). historySpeed and class therefore follow
the road type; one constant for every link does not reproduce either hold.
Script: `stock_holds.py` in the same folder.

**The frame, rechecked on car data.** An emulator run found that plain GNSS fed
into the stock SDK stays WGS-84 in Moscow (the public conversion branch). That
does not describe what reached ADAS on the car. Every moving stock position of
2026-10-23 lies on build 15's Yandex track only after the candidate inverse:
636/636 in drive-01 within 60 m, median 3.6 m (p90 10.0 m), and 675/1,027 in
drive-02 (the rest are roads build 15 did not drive), median 2.9 m. Without the
inverse, none comes within about 65 m; the candidate moves a point here by
537.5 m. The stock's parked route over the same streets starts at its own
shifted home position. These observations support shifted stock output positions and the separately
captured parked route, including non-active stretches. **The complete route
bodies during active holds are absent**; the parked route is from a different
package and must not be presented as their byte proof. The car's input is not
fully reproduced by the emulator: for the cached `autoType==210` branch,
`a0.java` accepts
`10/0x8001 VehiclePositionInfo` into buffered/interpolated `LocAllFusionPos`
(signal type `0x40000003`) when HD status is1 or cached DNP is1/2. If that gate
fails, this handler does not enqueue the packet; it does not establish a plain
GNSS fallback. The non210 branch forwards raw longitude/latitude/timestamp to
`b0.v`. The DNP cache itself is stabilized by repeated status triples.
The actual CNOA-hold PID records `isLaneSrUi:false` before both windows, so the
forced-210 emulator replay does not establish its platform branch. `locType`
is selected separately and absent from that drive log. Where the car's shift
arises stays open, and so does ADAS's own frame.
`VehiclePositionInfo` is ADAS → IVI and was not captured by build15. Recording
it can establish the frame of that output message, but cannot by itself settle
the ECU's internal localization frame or its incoming-route contract. A WGS84
output therefore does not rule out an incoming-route frame mismatch; a shifted
output strengthens producer-parity evidence without proving dropout causality.
The newer GNSS0/DR1 comparison above directly establishes mode-dependent SDK
output on the same fresh inputs. The settlement loop remains a compound
vehicle experiment, not an independent coordinate-contract oracle.

**The candidate against the stock's own output.** Build 16's parked capture
(400 `VehiclePositionInfo` samples, HD status 1, one point) puts ADAS's home
pose 537.6 m from the stock's 2026-10-23 parked wire position (`first-full`) and
0.4 m from it after the candidate transform (3.5 m from the stock's matched
road point). The same transform puts build 15's Yandex route points on the
stock's parked route over the same streets at median 3.0 m (p90 4.9 m, 137
points); untransformed, the same points lie at median 524 m. Java
`Coordinates.toSdk` and Python `stock.to_sdk` return identical values on these
points. So `STOCK_GCJ` reproduces what the stock put on the wire from ADAS's own
WGS-84 pose and reproduces that parked route. It is not a complete capture of
the route/position input during active CNOA or HNOA, and is not a demonstrated fix.

**Active-hold audit, correcting the earlier premise.** The 24.902 s and 7.205 s
CNOA windows retain 12 and 3 distinct SDK `gpsPos`/first-match `stPos` pairs,
all successful sends with positive vehicle speed. Original `e0.P` forwards
`LocInfo.gpsPos` and `matchInfo[0].stPos`; neither is a raw Android GNSS oracle.
During two frozen route-index/offset groups, GPS output moves 43.244 and
36.809 m. Both coordinate pairs fit the retained shifted package-b geometry,
but the association selects a segment by SDK proximity and is not input/output
tick lineage. Full route bodies for active paths 100005/100006/100009 are absent;
parked first-full path100002 belongs to later package-d. Raw GNSS, inputTickTime,
locType and VehiclePositionInfo are also absent from the active drive log.
The observed platform predicate is false, so the forced-210 replay remains a
separate fixture. `mapProvider=1` alone is not an explicit datum declaration.
Exact raw values, source getter/argument order, geometry joins and hashes:
`frame-check/postdrive/reports/stock-frame-recheck/` under the build-16 capture.

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

**Current build-16 limitation, found 2026-10-25:** service-13 raw recording stops
when the overlay observer closes after the map Activity is hidden. A live
publisher and continuing guidance do not prove continuous status recording.
The [loop-wire audit](#loop-wire-drive-review-2026-10-25) covers the entire observed
moving portion but exposes this stationary tail gap. The build-7/8 receipts
below remain historical bounded tests.

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
display-owned listener still had its original visibility-dependent lifecycle
in that candidate. Build 16 later filtered service-13 from the live recorder;
[build 17](#build-17-control-candidate-2026-10-25) restores it and retains the
passive observer while capture is enabled, independently of Activity visibility.

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

Code anchors describe the local working tree based on `810a2fe9`; the anchor is the
authority. The preceding engine rewrite (`134caa8e`..`6f97e3bb`) and this follow-up have
not been run on the car. Historical `today` values remain build 12's, recorded before
the rewrite. Source-audit decisions carry their retained evidence instead of a new
commit identifier.

| | Rows | Decided | Open |
| --- | --- | --- | --- |
| Matches the stock | 160 (61 are fields the stock builder never sets) | 158 | 2 |
| Differs from the stock | 70 | 7 | 63 |
| Stock unknown | 22 | 0 | 22 |

The engine rewrite moved seven rows to *matches the stock*:
- the route goes out once per Yandex route ID (`naviSDRoute`), and is resent 300 ms after
  DNP comes back on;
- RouteType 99 marks a real recalculation by Yandex, not a graph refresh;
- position, TrafficInfo, tunnel and region go out at the stock's once a second in LIVE;
- the remaining distance in TrafficInfo comes from the fix it travels with.

Three decided differences are guard policy:
- no idle stream;
- no stale or foreign-path position;
- no idle status outside the stock's end sequence. Every end that may still send now
  sends that sequence: 110 ×3 after an arrival, then 100 ×3.

Four more decided differences are lane categories against the unknown-only Beijing
sample: the recovered SDK enum supports Normal 0 / Bus 1, with special subtypes explicitly
projected to Other 2. The light gate now mirrors the recovered independent
`dstLinkDistance` accumulator. Four broad off-route position rows moved to *stock
unknown*: current-bean retention is supported, while equivalence of upstream Amap and
Yandex off-route inputs remains open; the universal endpoint-freeze premise is removed.

The open rows carry 19 high, 21 medium and 47 low NOA risks. These come first:
- **Frame (H1).** Route points, positions, lights and `mapProvider=1` claim the Amap SDK
  frame, but both hosts send WGS-84 (`FieldDefaults.FRAME`).
- **Road semantics (H2, D-HWY).**
  - On links, roadclass, formway and ownership are −1, adminCode is 0, and direction is
    0, which the export reads as two-way (DRT raw 3 → 0).
  - Region was 0; the [2026-10-25 default change](#region-default-from-the-successful-stock-drive-2026-10-25)
    reuses the successful stock/OSM drive's 990001. Its geographic/ODD meaning remains open.
  - TrafficInfo class and position ownership are unknown −1 on a path that the same
    packets declare matched. Unknown is not proof of a universal off-route state.
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
  the route, the position indices hold their last placement while fresh location
  coordinates continue. ~~The stock freezes them at the link end~~ (the source does
  not establish this universal rule; see the enrichment follow-up).

The file records fifteen contradictions between docs, code and the oracle as entries of
their own:
- linktype is −1 in the RouteConverter javadoc and in this README, but 0 in the code;
- the oracle calls direction 0 "unknown";
- `mapProvider=1` is sent with WGS-84 points;
- Safety says SpdLmtSpeedValue 0, but `FieldDefaults` enables the Yandex limit for both
  hosts;
- ~~lane types 0/1/2 break StockMessages' promise and the factory's 255~~ (resolved
  corpus-first: the SDK declares Normal 0, Bus 1, Other 2 and NULL 255, and the stock
  forwards supplied types; [source oracle](lane-category-source-20261024.json));
- `stock.py` lacks the `completeAttributes` gate, the 64-lane cap and the graph-name rule;
- `naviVoiceCommand ""` is undocumented;
- the "unknown, not silently false" flags are sent as false;
- pathID is described as a "hash of routeId";
- "Position, 1 Hz" (resolved by the engine rewrite, `646add68`);
- unknown class/ownership on a matched path (earlier universal-unmatched wording
  corrected against the source);
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
values (H5), H0 plus the stock `SETTING_MPC_IFC_NAVI_ST_SET` side channel (H6), and
independently H0 plus `INSTRUMENT_SEND_NAVI_STATUS_SET` (H7). H0's
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
H7 runner applies the same read/write-2/restore/readback lifecycle to the separate
instrument device 1007, FID 1138753594, accepting originals 0–4 and rejecting 65535.
H0 remains the unmodified parked control; H7 adds only the instrument side channel. The
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

The real bridge now also has the fixed read-only ODD-list query ~~before its armed
feed starts~~ (superseded by the variant merge: only under `startProtocol: stock`,
through the guard, once per fresh guidance start; see
[Enrichment follow-up](#enrichment-follow-up-2026-10-24)), with raw response/status recording. The passive overlay cannot use
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

- A missing prerequisite normally pauses LIVE egress without dropping the path, its real ACK or the
  FINE anchor. **Installed build-16 exception:** changing a ready route subject can trigger full route
  teardown and a fresh guidance start before its new observation is admitted; the pinned
  [post-drive reproduction](#local-diagnosis-after-the-loop-frame-drive) establishes the race.
  The later [local handoff correction](#local-reroute-handoff-correction-2026-10-25)
  keeps the guidance lifecycle for a bounded ready replacement while withholding old egress;
  it has not been installed.
  The arm itself ends only on a terminal cause (an actual send or audit failure,
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
- Evidence was sized for the build-15 two-hour objective, with service 13 recorded once
  and state transitions in `adas-journal.jsonl`. The added build-16 service-10 stream
  reduces measured packet retention below two hours; see the position-observer measurements.

Each defect has a regression test that failed on the old engine
(`LiveDriveRegressionTest`, the runtime and passive-client harnesses). Not yet established
on the car: whether ADAS releases the route on the end statuses, accepts the 1 Hz cadence
and the DNP-on resend, how the 250 ms input cache behaves under real load, the resumed
arm, and the ring sizes on the car's disk.

## Enrichment follow-up (2026-10-24)

Local working-tree follow-up to `810a2fe9`; parser and host-test results do not establish
an ADAS consumer, a receiver interpretation or acceptance on the car.

The field switches are explicit in the arm's `variant`, with the same strict schema in
`ExperimentVariant` and `live_session.py`. New host sessions normalize an omitted
`instrumentNaviStatus` to `true` and persist that value in the arm and rollback ledger;
explicit `false` disables it. Other omitted fields keep their baseline values. Java
retains missing-key `false` for legacy arms, so restarting an already issued arm does
not silently change its behavior. MPC `naviSt` remains opt-in. Unknown keys,
wrong types and unsupported values are rejected; the effective values and the keys
actually overridden are recorded with the arm and the trace. The supplied files in
`tools/adas_bridge/variants/` isolate these choices:

| Variant | Choice |
| --- | --- |
| H1 | Route and position frame, `STOCK_GCJ` versus the WGS84 baseline |
| H2a–H2g | Link class/formway, ownership, direction, lane count, adminCode+region, position ownership+TrafficInfo class, and legal-limit zero, one step at a time |
| H3 | ODD region read, then the stock's 100/106 start phase before the route; the chosen holds are experimental, not measured stock timings |
| historySpeed A/B | Yandex's section free-flow value, a fixed value, or alternate values per Yandex route ID |
| H6 / `naviSt` | Independent MPC navigation-state property `0x4CA0004B`, device 1023: only after reading an original, write 2 for confirmed guidance, restore and read back; original-read can refuse this channel |
| H7 / `instrumentNaviStatus` | Independent instrument-state property `0x43E0003A`, device 1007: read original 0–4, write 2 for confirmed guidance, restore and read back; new host arm default ON, explicit false OFF |

These switches leave undecided field rows OPEN. A switch creates a controlled
comparison, not evidence that the selected value makes NOA available.

**Merged with the offline attributes.** The switches now sit in main on top of build 13's
offline class and one-way. A link or position fill-in applies per field, only where Yandex
left the value unknown: a class from a graph road or the offline maps, an offline one-way
and a roundabout's form and direction stay, while for example an unknown ownership on the
same road is still filled; `variantApplied` counts only what a switch put on the wire
(`VariantWireTest.linkOverrides`, `partialRoad`). The highway rule (class from posted
speed, lane count from sign width) stays removed. One ODD request implementation remains,
build 13's fixed parcel (both inout objects, explicit length plus `writeByteArray`, the
raw answer kept as base64 within 64 KiB): only H3's `startProtocol: stock` sends it,
through the guard, once per fresh guidance start. **Corrected by the build-16
reproduction:** a direct reroute does not request ODD, but route-subject teardown
recreates the driver, turns a yaw replacement into a fresh start, and requests ODD
again. The local handoff correction above removes that extra fresh start for a
ready replacement; the installed build 16 still has the recorded behavior.
Build 13's unconditional request before the armed feed is gone, so every other variant leaves H3 a
clean variable. A driver recreated after an offline withdrawal keeps the session's frame
and variant (`LiveRuntimeTest.variantFrameAfterOfflineWithdrawal`). None of this has been
built into a car build or run on the car.

**Instrument channel native proof.** The separate instrument switch uses
`0x43E0003A` on device 1007 (guidance 2; not-guiding 1 in the stock API). Original IVI
native code registers the same Parameter in the request and query maps at
`0x178a24/0x178a34`; `getIntValue` reads its integer cache and `setIntValue` updates that
cache. Autoservice permits both calls from shell UID 2000. The initial HAL value is
65535, an unknown original that must refuse a write. This establishes a cached getter,
not an ECU acknowledgement or an ADAS consumer.

The source hashes, disassembly and reproducible bounded constructor/registration replay
are retained in `captures/adas-stock-followup-20261024/native-source-proof.json` and
`inspect_ivi_registration.py`. The inspected MPC property `0x4CA0004B` is registered only
in the request map (`0x194b70`), so H6's existing original-read requirement can refuse
it; the second channel does not inherit proof of a getter from the first. Both side
channel rows remain OPEN for their unknown receiver role and vehicle acceptance.

H7 is H0 plus only `instrumentNaviStatus: {"value": 2}` in the parked scenario, and an
independent strict `instrumentNaviStatus: true` arm switch in the live candidate. It
uses its own ledger (`files/denza-adas-instrument-navi-status.json`, schema
`denza-adas-instrument-navi-status-v1`). A valid original is required before any guidance
write; each write gets a cache readback. Guidance end, expiry, stale consumer and reader
exit restore the exact original. Host rollback independently verifies/restores that
original before re-enabling the stock map; a failure leaves rollback pending. The
implemented lifecycle is enabled by default in new host sessions and remains unrun on
the car; the default is not acceptance evidence.

**Lane category source audit.** The earlier review treated the Beijing fixture's 255 on
3,172/3,172 next-lane packets as a universal stock constant. That premise is false.
`LM/com/autonavi/gbl/guide/model/LaneCategoryType.java:7-14` declares Normal 0, Bus 1,
Other 2, Tidal 3, Variable 4 and NULL 255. `e0.Z` (`:550-597`) forwards SDK
`LaneInfo.backLaneType/frontLaneType` to the 3 km beans, and `f0.g` (`:91-98`) serializes
the supplied next-lane types without coercing them to 255.

The existing Normal 0 / Bus 1 mapping therefore stays. TRAM/TAXI/BIKE → Other 2 is a
declared coarse projection: the original subtype and its loss remain in each lane's
diagnostics, with no access inference. Unknown kinds and foregrounds without a supported
explicit highlight stay NULL 255. No direction payload or lane count changes. The
[retained source oracle](lane-category-source-20261024.json) records the enum's source
and SHA-256; `LaneGuidanceTest.categorySourceOracle` checks both lane wire surfaces,
foreground unknowns, unchanged directions/width and the special-subtype loss. The four
category rows are decided on this evidence, with receiver acceptance still untested.

**Light gate source audit.** The earlier instruction to pass the next light's real
distance into the 500 m gate would have changed the wrong field. In `k.h.a0.d.c.c`, the
`FontRoundBean` walk has two independent accumulators: `lightDist` for the first flagged
light and `dstLinkDistance` through the link identified by the next-lane target's
segment/link indices. The latter defaults to 0 when no target is found. `e0.v0` (`:954`)
puts `dstLinkDistance` in `LightParam`; `e0.H0` (`:1139-1140`) checks that value against
500, or admits an active countdown. It does not check `lightDist`.

`StockFeed.fontRound` mirrors that walk on the converted route: remaining metres of the
current link, whole subsequent links, the 3 km bound and tunnel-exit boundary. The
lane-target distance feeds the gate; the separately found light feeds the location.
Consequently a light more than 500 m away is not by itself a stock mismatch: with no
lane target the stock's distance is 0. `TrafficLightDynamicsTest` checks target-link
boundaries, the independent light location, default/reset and wire emissions. The
original normal/fallback DEX extractions and hashes are retained in
`captures/adas-stock-followup-20261024/stock-dynamics-source.json`.

**Off-route source audit.** The earlier proposed endpoint clamp was also unsupported.
`e0.b0` resets transient guidance extras on recalculation and sets `offCourse`, but
leaves `NaviInfoParam` placement untouched. The moving and stationary location paths
read its current segment/link and remaining distances. We therefore keep the existing
placement when Navigator provides no guided scalar and continue sending fresh road/GPS
coordinates; we do not invent an endpoint with zero remaining distance. The regressions
hold positive remaining distances through a reroute in LIVE and dry run. This describes
the sender's retention rule, not every possible value supplied by an upstream Amap
callback during loss of guidance.

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
