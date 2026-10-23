# Map → ADAS feed from Yandex (feasibility, 2026-10-23)

Can our app publish the navigation route and the rest of the map's feed to the
car's ADAS (Momenta, `getAdasType` 1) in place of the stock map
(`com.byd.launchermap`, Amap AutoSDK), with the route coming from Yandex
Navigator and the stock map left installed and unmodified? Firmware and code
research only: nothing was built, installed or sent to the car or an emulator.

## Current state

| Claim | Status | Section |
| --- | --- | --- |
| The stock map publishes to ADAS through the same exported `SomeIpServerService` Binder that `:hud-link` already uses from an app UID; the IVI offers the services (vsomeip, `someip_stack.json`), and no layer checks the sender, the topic or the payload | firmware | [Transport](#transport) |
| An app UID can therefore fire every map→ADAS event (services 7, 43, 45 and `0x8202`) and receive ADAS's answers (services 13, 44, 10) through `SomeIpClientService`, which fans events out to every subscribed session | firmware | [Transport](#transport) |
| There is no owner or exclusive writer: offers are reference-counted per session, fires are unconditional, the last write wins and ADAS sees both writers' events interleaved | firmware | [One writer](#one-writer) |
| The stock map, alive but not navigating, still fires position (pathId −1), TrafficInfo (roadClass −1) and NavigationStatus 100 about once a second; this is the main conflict with our feed | firmware + car logs | [Idle stock map](#what-the-idle-stock-map-still-sends) |
| While the stock map navigates, ADAS's path-selected answer for any other pathId makes it resend its own route and selection: no coexistence during stock guidance | firmware | [One writer](#one-writer) |
| No official third-party navigation provider path to ADAS exists: the ADAS feed lives inside the stock map; `byd_map_package` and `BydProjectionService` concern the cluster projection only | firmware | [Provider path](#no-official-provider-path) |
| The wire format is recoverable completely from the stock map (protobuf field numbers, the route JSON, chunking, the 1 Hz cadence, the handshake); what ADAS does with it is only partly known | firmware + car logs | [Contract](#contract) |
| Navigator 29.8.1's `DrivingRoute` and `Guidance` give geometry, per-segment speed limits, highway, tunnel, toll and HD ranges, traffic lights (with signal state ahead), lane signs, manoeuvres, jams with speed, road name and route position; functional class, formway, link type, per-link lane count, direction and ownership are not exposed | dex + module code | [Yandex data](#what-yandex-gives-us) |
| Verdict: feasible with conditions — the transport is open; the blockers are the idle stock map's competing 1 Hz feed, the unknown receiver semantics (coordinate frame, link IDs, path-match) and safety validation | assessment | [Verdict](#verdict-and-plan) |

Contents: [Transport](#transport) · [One writer](#one-writer) ·
[Idle stock map](#what-the-idle-stock-map-still-sends) ·
[Provider path](#no-official-provider-path) · [Contract](#contract) ·
[Yandex data](#what-yandex-gives-us) · [Converter](#the-converter) ·
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
| ADAS → IVI | 13 PilotStatus_AlarmInfo | `0x8002` pilot status (DNP/ICC), `0x8003` alarms; methods `0x0001` DNP/ICC switch request, `0x0002` ODD region codes |
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
266); whether vsomeip delivers locally offered events to a local client is not
established here, but it would let a passive probe record both writers' bytes.

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
  2. *Stop the process* (`am force-stop com.byd.launchermap` from our shell):
     its sessions are dropped on unbind and its sends stop. It starts again on
     boot (`BOOT_COMPLETED` receiver) and whenever a launcher card, the
     cluster projection service, AutoVoice or a map entrypoint binds or opens
     it; the stock map card, cluster Map scene and voice navigation go with
     it. Reversible, but not an always-on design.
  3. *Do not touch* the caller-supplied session key: binding with the stock
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
(`g.java:135-140, 242`). `PathMatchProxy` (`44/0x8001`, `EHPToNaviData`:
`segment_id`, `sd_route_begin/end`, `odd_type`, `match_result[rc_id, offset, length]`)
and `PathP2PMatchProxy` were never received on this car, and the stock map
ignores them (`k.h.e0.d.d.k()` empty).

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
| `roadclass` | **not in Yandex.** OSM lookup (our `enrich_noa_road_classes.py` policy); fallback highway range + limit, never above class 1 without OSM access-control proof |
| `formway` | **not in Yandex.** OSM (ramps, roundabouts, side roads); fallback roundabout from manoeuvre `ENTER_ROUNDABOUT`, else 15 |
| `linktype` | tunnel ranges → tunnel; `getZlevels`/OSM bridge → bridge; else 0 |
| `laneNum` | **not in Yandex** per road; OSM `lanes` per direction, else lane sign width near junctions, else 1 (never 0) |
| `direction` | **not in Yandex.** OSM `oneway` (the stock exporter writes raw≠3) |
| `ownership` | **not in Yandex.** OSM access → 1, else 0 |
| `adminCode` | synthetic URID 372 / adcode `990001`, as in our package and inside the car's ODD list |
| `hasTrafficLight`, `trafficLights[]` | `getTrafficLights()` positions |
| `mainAction`, `assistantAction`, guide groups, segments | manoeuvre annotations → Amap actions (our field-28 table), section lengths and times |
| `roadname`, `navigationNextRoadName` | annotation toponyms, `getRoadName()` |
| `speedLimit` | `getSpeedLimits()` — stock always sends 0; keep 0 until tested |
| `hasParallel`, `hasMixFork`, `hasMultiOut` | not available → false |
| `viaRoadInfo` | derivable (class, lanes, limits per named road) |

## The converter

On the fly, in two places:

1. **In Navigator (module):** on each new `routeId`, a route snapshot
   (polyline, ranges, limits, lights, lane signs, manoeuvres, toponyms); at
   1 Hz a progress sample (segment index + fraction, classified location,
   speed, heading, standing, jam speed ahead, next manoeuvre). Sent over the
   existing Navigator → Denza Apps Binder (`HudHostProtocol` style, with
   generation and expiry).
2. **In Denza Apps:** map-match the polyline to the OSM graph we already build
   for the region (`tools/stock-map`, `research/stock-map-native`), take class,
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

Outside the OSM-covered region the converter must degrade to class ≤ 1/9,
formway 15, laneNum 1 — the values that give ICC and nothing more.

## Safety

- NOA plans lane changes and exits from the route. A misaligned route (frame,
  link order, cut errors) can make the car change lane or take a ramp wrongly.
  Every live step is supervised, stationary first, then a known road.
- Class 0/6 enables highway NOA. Only OSM-proven access-controlled roads may
  carry it; Yandex highway ranges alone must never upgrade a road.
- `SpdLmtSpeedValue` and link `speedLimit` may feed speed assistance; send 0
  (stock behaviour) until a separate test.
- Stale data: process death, route cancel, GNSS loss must end the feed within
  one cycle (expiry on every sample), never leave a believable old route.
- Never fire `13/0x0001` (DNP/ICC switch), never impersonate the stock map's
  session, never publish while the stock map navigates.
- Treat a `com.byd.avc` crash or an ADAS fault TOR during a test as a stop.

## Verdict and plan

**Feasible with conditions.** The transport is open and already proven from
our UIDs; the format is fully recoverable; Yandex has more than enough data
for the route if OSM fills the classes and lanes. Conditions: (1) the idle
stock map's 1 Hz `100 / pathId −1` stream must be tolerated by ADAS or the
stock process stopped for the session; (2) the coordinate frame and link-ID
semantics must hold as on 2026-10-23; (3) no publication while the stock map
navigates.

1. **Passive, stationary (1–2 days).** P, no route. Record the stock map's
   `SomeIPMatrixManager`/`SendSomeIpDataManager`/`DriveRouteManager` logs
   (`tools/stock-map/noa_car_log_capture.py`) and, as a candidate, a
   read-only client subscription to services 7/43/45/`0x8202` and 13/44, to get
   the exact idle bytes and rates. Read ADAS FIDs (DNP/ICC availability).
2. **Offline converter (1 week).** Host/JUnit: Yandex route snapshots
   recorded by the module → `NaviToEHPData`; diff against the stock JSON of
   the same corridor (our OSM package on the emulator) field by field;
   protobuf bytes against the stock builders.
3. **Stationary publish (1–2 days).** P, stock map idle, our route of a short
   known corridor: one route + selection, then position at 1 Hz with the car
   standing on the route start. Observe `MMTPathSelected`, any PathMatch,
   DNP/ICC FIDs, TORs, and whether the stock map's −1 stream disturbs the
   selection. A/B with the stock process stopped. End with status 110 → 100.
4. **Supervised drive (days).** Known road, ICC-level classes first (no 0/6),
   then the М-1/ЦКАД stretches with OSM class 6/0, compared with the
   stock-map drives of 2026-10-23.
5. **Product (2–3 weeks).** Converter in Denza Apps, IPC from the module,
   expiry, one-writer gate with the stock map's guidance state.

Rough total: 4–6 weeks of work, most of it validation.

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
