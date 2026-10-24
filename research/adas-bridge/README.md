# Map → ADAS feed from Yandex (local prototype, 2026-10-23)

Can our app publish the navigation route and the rest of the map's feed to the
car's ADAS (Momenta, `getAdasType` 1) in place of the stock map
(`com.byd.launchermap`, Amap AutoSDK), with the route coming from Yandex
Navigator and the stock map left installed and unmodified? The initial firmware
feasibility study is now followed by an instrumented Navigator build, a host
converter and a local mock receiver. The prototype runs on a disposable emulator;
no new ADAS sender has been installed or used on the car.

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
| `libraries/adas-link` mirrors the stock map's feed code (serializer, chunking, every sender, DriveRouteManager, MmtSomeIpDataManager, status loop, ACK resend, gates); the car's own route capture is rebuilt byte for byte, real Gson and protobuf-java agree with it, Python `tools/adas_bridge/stock.py` gives the same bytes | code + golden + oracles | [Stock-equivalent bridge](#stock-equivalent-bridge-adas-link-2026-10-23) |
| Inside Navigator the bridge runs as a dry run: everything the stock map would fire is recorded to a JSONL trace; the SOME/IP publisher is compiled hard-off | code; emulator run | [Stock-equivalent bridge](#stock-equivalent-bridge-adas-link-2026-10-23) |
| A local SOME/IP client can subscribe to the stock map's offered events (HAL client classes, no security section); the overlay subscribes read-only | firmware; car receipt open | [Transport](#transport) |
| ADAS availability is explicit DNP/ICC/ACC status (0/1/2); NOA family and route ACK are separate. Passive observer receives real car packets and shows an overlay | firmware + car acceptance, 2026-10-23 | [Status observer](#adas-status-observer) |
| With our OSM road store (`tools/adas_bridge/road_store.py`, from a `build_map.py` build) the bridge matches the Yandex route onto our network (adas-link `RoadMatcher`, HMM): links end at OSM junctions and attribute changes and carry the producers' class, formway, link type, LaneNum, direction, ownership, name, link speed and legal limit; 99.4–100 % of four recorded Moscow routes matched, 28–48 → 119–176 links per route; Java and the Python oracle agree to the byte | code + bench, 2026-10-24; car not run | [OSM roads](#osm-roads-under-the-yandex-route-2026-10-24) |
| Inside Navigator on an emulator, with the store placed by `run-as`, both a home-region and a central Moscow route are 100 % matched (53 and 119 links), the dry-run traces pass `trace_check` and the overlay shows a fresh feed, a waiting route and no stock-map alarm; the bridge costs about 0.2 % of a core and 1 ms per second of the main thread, the overlay 6–7 ms; a store for Moscow plus about 50 km is 60 MB | emulator run, 2026-10-24; car not run | [Integrated run](#integrated-emulator-run-2026-10-24) |
| Verdict: feasible with conditions — the transport is open; the blockers are the idle stock map's competing 1 Hz feed, the unknown receiver semantics (coordinate frame, link IDs, path-match) and safety validation | assessment | [Verdict](#verdict-and-plan) |

Contents: [Transport](#transport) · [One writer](#one-writer) ·
[Idle stock map](#what-the-idle-stock-map-still-sends) ·
[Provider path](#no-official-provider-path) · [Contract](#contract) ·
[Yandex data](#what-yandex-gives-us) · [Converter](#the-converter) ·
[Local prototype](#local-prototype-2026-10-23) ·
[Stock-equivalent bridge](#stock-equivalent-bridge-adas-link-2026-10-23) · [OSM roads](#osm-roads-under-the-yandex-route-2026-10-24) · [Native archive](#native-route-archive) ·
[ADAS status observer](#adas-status-observer) ·
[Safety](#safety) · [Verdict and plan](#verdict-and-plan) ·
[Evidence](#evidence-and-reproduction)

Paths below: `LM` = `captures/hud-firmware-20260923/jadx/BydLaunchermap/sources`,
`SIP` = `captures/hud-firmware-20260923/jadx/SomeIpService/sources`,
`YN` = `captures/firmware/yandexnavi-29.8.1-3b0dec32` (all git-ignored).

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
car drives, and the stock consumer is empty (`k.h.e0.d.d.k()`).

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
must be stable or globally meaningful (PathMatch would tell, but is silent);
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
or ferry; a Yandex highway link without a light is
class 0 at a posted 90 km/h or more, else 6, with formway 1, direction 1, ownership
0 and the junction lane sign's width as LaneNum (one-way carriageway); a roundabout
section is formway 4, direction 1; curLinkSpeed (`historySpeed`) is the section's
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
Navigator's location; DNP service, switch and no-sleep are assumed on (Navigator
cannot read BYDAuto); region codes stay at the stock defaults (no Amap adcodes);
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

## OSM roads under the Yandex route (2026-10-24)

Yandex's route has no road graph: the bridge could cut links only where Yandex
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

## ADAS status observer

The car does report availability separately from route acknowledgement:

| Source | Observed meaning | Limitation |
| --- | --- | --- |
| `13/0x8002 PilotStatus`, DNP/ICC/ACC | `0` off/not offered, `1` active, `2` available | Unknown enum values stay unknown; a value is not a reason for refusal |
| `13/0x8005 NewBroadcastInfo.noaMode` | `1` highway NOA, `2` city NOA | Family only, never proof of availability |
| `44/0x8002 MMTPathSelected` | Stock callback fires on status 200, matching path confirms selection | Does not validate lanes, map completeness or NOA eligibility |
| `44/0x8001 PathMatch` | Route ID, reported NOA distance/range and status code | Not observed in retained drives; exact receiver semantics unresolved |
| `13/0x8003` and stock TOR notices | Raw alarm/notice codes and known notification text | Display only; do not synthesize pilot state from a notice |

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

Next validation must compare the same corridor in Yandex and the stock map,
resolve every missing field from an observed source, then test a single writer
on a stationary car. An `MMTPathSelected` response proves selection only, not
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
