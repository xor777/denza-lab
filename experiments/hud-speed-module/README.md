# HUD Speed → Denza Apps

Owner-only embedded adapter for the reviewed official HUD Speed **79.0**
(`air.StrelkaHUDFREE`). Architecture and product boundaries:
[HUD output architecture](../../docs/hud-output-architecture.md#hud-out-radar-adapter).

## Current state

| Claim | Status | Evidence |
|---|---|---|
| Five entry hooks in the real MainService, no OCR or visible overlay requirement | Built | `tools/hud_speed_build/PatchHooks.java`; SHA-pinned upstream APK |
| Authenticated semantic IPC, host-rendered card, one HUD writer | Implemented | `HudRadarWarning`, `HudHostEndpoint`, Denza Apps `HudRadarSource` |
| Nearest **selected** warning, GPS loss, mph, ongoing average section, stale observation, service destruction | Emulator checks | `test-device/`, `src/test/`; capture directory below |
| Card alongside route; maneuver, lanes and road-limit ownership retained | Emulator/JVM checks | Host instrumentation and `HudRadarSourceTest` |
| Projector on this car, genuine moving detections, background entitlement, account/purchase, database refresh, sleep/wake | Not accepted | Requires vehicle/user scenarios |
| Warning without a route | Not implemented | Native camera fields / temporary navigation mode still need the parked surface probe |

## Data and lifetime

`MainService.onCreate` starts the adapter. At entry to `onRadarUpdateEvent` and
`onRadarGpsStatusUpdateEvent`, it snapshots the original model on the event's
thread. `onDestroy` and `onTaskRemoved` disconnect. All Binder operations run
on a dedicated worker; snapshot objects are immutable.

The upstream engine is `MainService.c`, settings are `MainService.d`. The engine
must have `w == true` and `h() == true` (running and valid GPS). `f()` already
filters active objects and sorts them; use its first element, exactly like
`l.c.f`. Do not choose a nearby POI independently. When that list is empty and
`G` is true, `H` and `J` describe an ongoing average-speed section; `n.a()` is
its average speed in m/s. `i.e.s(settings.S())` gives the selected limit:
**Settings.S is vehicle category**, `i.e.u()` is the limit unit (1 km/h, 2 mph).
Names come from upstream `g.i.y`; distance uses upstream `g.i.x`. Locations and
the database never leave HUD Speed. Auxiliary heat/truck/bump widgets outside
the selected list are not synthesized into warnings.

Wire message `TX_RADAR = 4` has its own format version. The caller must first
attach through the existing host handshake. Denza Apps pins the package and
the dedicated certificate; RADAR cannot submit generic layers/fields, MODULE
cannot submit radar messages. Unknown, malformed, future, out-of-order or
older-than-3-second observations are rejected. Queueing and host reconnect do
not reset the source timestamp (`elapsedRealtime`). Host TTL is the remaining
part of those 3 seconds, so a stopped/frozen adapter cannot retain a badge.
Empty list/GPS loss clears it; process death and explicit detach withdraw the
source. Host lookup retries every 5 seconds, including unanswered bindings.
There is no standalone SOME/IP implementation in the adapted APK.

The host draws a 170×78 card in the upper-right of the 300×180 map surface,
with original warning name, metres, optional limit and explicit unit; a section
also shows average speed. It publishes only RADAR/MAP_BADGE. It does not replace
the navigator's road limit, maneuver, lane or AR fields, and produces no audio.
Stopping radar in HUD Speed stops output. Background service and overlay
entitlement checks, subscription/account code, voice and camera database remain
unchanged. Re-signing compatibility with account/purchase is not yet proved.

## Build

```sh
python3 tools/hud_speed_build/build.py --out captures/hud-speed-20261018/build-final
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :hud-link:testDebugUnitTest :denza-apps:testDebugUnitTest :denza-apps:assembleDebug
```

Input defaults to `captures/hud-speed-20261018/hudspeed-radarbase-release-79000000.apk`,
SHA-256 `393220d5d139adf191e616b60126ecf888e102d4310be1d527a8df5aad967e49`.
No download is performed. Another upstream version fails before modification.
The build requires local JDK17, Android SDK build-tools36, android35 and the
installed JADX1.5.5 jar (DEX rewriter only).

Persistent signing key: gitignored `captures/hud-speed-signing/adapter.p12`.
Certificate SHA-256:
`4e2b390deba4ee0b336f85d916a372ca0dfe3fd70a7379cb22fe778773f92202`.
The builder verifies this against the expected identity and never regenerates
a missing key. Keep it for compatible updates. No original certificate spoofing.

Output is `HUD-Speed-Denza-79.0-1.apk`, version `79.0-DenzaHUD.1`,
code79000001. Independent SDK manifest decoding proves that only app label,
version and the Denza Apps package query changed. Only original `classes.dex`
and the manifest change; MainService moves to classes3, adapter to classes4.
All native libraries, assets, resources and classes2 stay byte-identical.
`build-report.json` records input/output hashes, signer and module source hashes.

## Emulator checks

```sh
python3 tools/hud_speed_build/device_checks.py --out captures/hud-speed-20261018/device-checks-final
python3 tools/hud_speed_build/run_checks.py --serial emulator-5582 \
  --source-apk captures/hud-speed-20261018/delivery/HUD-Speed-Denza-79.0-1.apk \
  --host-apk captures/hud-speed-20261018/delivery/denza-apps.apk \
  --checks captures/hud-speed-20261018/device-checks-final \
  --out captures/hud-speed-20261018/new-emulator-run
```

These two instrumentation APKs target the candidate HUD Speed and Denza Apps,
signed with the adapter key and local debug key respectively. Install them and
both candidates **only on a disposable emulator**. Start
`dev.denza.hud.speed.check.host/dev.denza.hud.speed.check.HostCheck`, wait for
`state=ready`, then run
`dev.denza.hud.speed.check.source/dev.denza.hud.speed.check.SourceCheck`.
Both enforce ranchu/goldfish; the compile-only MainService stub is never packaged.
The source test calls the actual patched service with synthetic upstream camera
objects. The host seeds a synthetic route, checks the real compositor and saves
cards under Denza Apps `files/hud-speed-qa`. These fixtures prove the bridge,
not real camera detection or physical projector rendering. Tests are never
embedded in either candidate APK.

## Vehicle acceptance and diagnosis

First confirm that the car is parked and one session owns installation. Read
`pm path` and installed `base.apk` hashes before and after. The adapted package
uses a different signer from official HUD Speed: it cannot update the official
package in place. Do not automatically uninstall an existing installation or
erase its settings/purchase state. Keep the original and currently installed
Denza Apps APK available for recovery. Install the compatible host together
with the adapter; an older host does not understand/admit this source.

Launch HUD Speed normally, complete its own setup and enable background use
through its normal entitlement/settings. A visible overlay is not required.
While a route is active, verify camera/danger title, distance, unit and limit;
verify passage, GPS loss, source stop, host restart and app/background transitions.
Then validate account/purchase, database refresh and sleep/wake separately.
Do not infer these from synthetic emulator fixtures.

Log tags: `DenzaHudSpeed` (host attachment, snapshot failures), `DenzaHudHost`
(accepted source), `DenzaHudGuidance` (single output session). An older or wrong
signer logs refusal. A snapshot error clears output instead of guessing values.
No raw location or database records are logged by the adapter.

Original inspection, signed candidates, test logs and card PNGs are retained in
gitignored `captures/hud-speed-20261018/`; never commit APKs or signing material.

## Recorded verification, 2026-10-18

`acceptance-final/`: both instrumentation suites passed on a dedicated temporary
API35 emulator. All five original DEX hooks executed, including GPS event,
task removal, service restart and destroy; host saw three distinct cards and
three removals, with maneuver and road-limit ownership intact. Package hashes
were verified on the emulator before the run. These are synthetic engine
observations, not a drive. Earlier fixture failures (missing average-limit rule
and uninitialized upstream trip statistics) are retained in the capture corpus;
neither was fixed by changing the upstream app or bypassing its checks.

JVM: `:hud-link:testDebugUnitTest` — 69 tests; `:denza-apps:testDebugUnitTest` —
2204 tests, 1 existing skip; no failures. Denza Apps was built from an isolated
HEAD snapshot with only HUD source/test/manifest changes overlaid because the
shared checkout was concurrently editing diagnostics. `delivery/qa-source.json`
records that base. The exact source archive, build log and report stay with the
two APKs in `delivery/`.

| Artifact | SHA-256 |
|---|---|
| `delivery/HUD-Speed-Denza-79.0-1.apk` | `cbcbc58a0d982aae9c625013b6842a4082779d39718b41c79e460c97706216b9` |
| `delivery/denza-apps.apk` | `31c2b8630e99c794a0ee2efa438d97fdf7b372b035e73bb41b76a4b163d83bbc` |
