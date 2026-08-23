# Vehicle Data Availability Findings

Status: live-car availability investigation on 2026-07-24, with the earlier
vehicle-event probe results from 2026-06-27 retained where relevant, product
wiring checked against the code on 2026-08-20, a shell-UID `autoservice`
FID read on 2026-08-22, and the vehicle panel wired to that allowlist the same
day (built and unit-tested; not yet exercised on the car).

This page records which vehicle and journey signals a normal Denza Apps APK can
actually use. It distinguishes product-usable sources from values that are
visible only to system processes, shell diagnostics, or reverse-engineered API
surfaces.

## Executive result

A normal `/data/app` APK cannot read most BYD CAN-backed values. The exported
high-level DiCar service can be reached, but its useful getters enforce
signature/privileged `BYDAUTO_*_GET` permissions at call time.

The practical product inputs are:

- standard Android GNSS: position, speed, bearing, altitude, and accuracy;
- standard Android accelerometer, gyroscope, gravity, linear acceleration, and
  game rotation vector sensors;
- possibly the vendor inertial sensors advertised without a permission, after a
  dedicated normal-APK registration test;
- an exported car-status content provider containing maintenance fields and raw
  stored trip series, whose units and update semantics are not yet understood;
- Denza Apps' existing accessibility-derived Yandex guidance while a valid
  navigation scene is visible.

The following must not be treated as product-available to a normal `/data/app`
identity (no `BYDAUTO_*_GET`, no Binder from the app process):

- accelerator/brake position, steering angle, or gear via DiCar getters;
- tire / climate / PM2.5 / BMS via DiCar getters;
- raw BYD event traffic seen in system `logcat`;
- regeneration power inferred from the unsigned/raw trip arrays.

The same BMS, HV, 12V, motor, tyre, climate, and air values **do** exist on
the native `autoservice` Binder (`android.gui.BYDAutoServer`). A trusted
local-ADB `shell` UID can read them with `service call autoservice`. That is
how third-party dashboards on this head unit get the numbers. Protocol,
scales, and the widget allowlist: [autoservice FID protocol](#autoservice-fid-protocol).

## Test environment

| Item | Value |
| --- | --- |
| Head unit | DiLink 5.1 |
| Android | 13 |
| Build fingerprint | `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20251214.220229:user/release-keys` |
| ADB target | `127.0.0.1:5555` |
| Product package | `dev.denza.apps` |
| Temporary probe package | `dev.denza.tools.vehicledatareadprobe` |
| Probe identity | normal app UID; no BYD/system permissions |

The temporary probe was uninstalled after the 2026-07-24 run. The 2026-08-22
`autoservice` reads used only `service list` and `service call`; Denza Apps was
not modified. No `com.byd.avc` crash was observed.

## Evidence labels

- **Confirmed normal app** — available through a standard Android API to a
  normal app identity, or already used by a normal third-party app on this head
  unit.
- **Advertised; probe next** — the system reports no permission requirement, but
  Denza Apps has not yet registered and interpreted the signal itself.
- **Readable but unqualified** — the transport is open, but meaning, units,
  cadence, or stability are not established.
- **Blocked** — a normal app reached the API and received a permission or
  capability failure.
- **Shell/system only** — useful for diagnosis, not a product data source.

## Availability and frequency matrix

| Source | Data | Frequency observed or advertised | Permission | Evidence | Product status |
| --- | --- | --- | --- | --- | --- |
| Android `TYPE_ACCELEROMETER` (`TDK icm42670`) | 3-axis acceleration including gravity | 15–100 Hz advertised; stock clients observed at 15 and 100 Hz | none | Confirmed normal app ecosystem | Usable after car-axis calibration |
| Android `TYPE_GYROSCOPE` (`TDK icm42670`) | 3-axis angular velocity | 15–100 Hz advertised; stock normal app observed at 100 Hz | none | Confirmed normal app ecosystem | Usable |
| Android virtual sensors | gravity, linear acceleration, game rotation vector | up to 100 Hz advertised | none | Confirmed Android surface | Usable; validate orientation and drift |
| Vendor `icm4n607-a-iner`, type `65613` | vendor inertial acceleration payload | 12.5–400 Hz advertised; stock AutoSDK observed at 12.5–15 Hz | none advertised | Advertised; probe next | Promising, but payload and normal-APK registration are unverified |
| Vendor `icm4n607-g-iner`, type `65614` | vendor inertial gyroscope payload | 12.5–400 Hz advertised; stock AutoSDK observed at 15 Hz | none advertised | Advertised; probe next | Promising, but payload and normal-APK registration are unverified |
| `als-can` light sensor | ambient/CAN light level | on-change, up to 100 Hz advertised | none advertised | Advertised; probe next | Do not use until values and meaning are verified |
| Android GNSS provider | position, speed, bearing, altitude, horizontal/vertical/speed/bearing accuracy | approximately 1 Hz observed on the car | standard runtime location permission | Confirmed normal app | Usable |
| GNSS status/extras | satellite and signal-quality information | event-driven with GNSS fixes/status | standard runtime location permission | Confirmed Android surface | Optional diagnostics, not a primary dashboard metric |
| `com.byd.carStatusProvider` | maintenance fields, issue fields, raw fuel/electric trip arrays | update cadence unknown; provider supports change notifications | provider exported with no read permission | Readable but unqualified | Query/observe only; do not label raw arrays as consumption or regeneration |
| Yandex guidance through Denza Apps accessibility | maneuver, next road, remaining route distance/time, optional road text | event-driven; only while validated guidance is visible and fresh | enabled Denza Apps accessibility service | Existing product path | Usable with the current fail-closed/staleness rules |
| High-level DiCar Binder APIs | battery, energy flow, range, charging, pedals, steering, tires, air quality | getter surface exists; useful calls blocked | signature/privileged BYD permissions | Blocked | Not a product source from app UID |
| `autoservice` (`android.gui.BYDAutoServer`) | SOC, SoH, pack temps, cell mV, 12V, HV, charge, motors, tyres, climate, PM2.5 | on-demand `service call` from shell | shell UID via local ADB; app UID blocked | Shell/system only | Poll a short allowlist through `DenzaLocalAdb`; never from the app process |
| Raw BYDAuto events/system logs | speed logs, bodywork/settings/safety-belt/PM2.5 events, other CAN-derived events | speed log about 1 Hz; other events vary; some logs are high-rate | system log access / protected BYD permissions | Shell/system only | Diagnostics only |

The `30 Hz` standard-IMU sampling and at-most-`30 FPS` rendering design is now
implemented in the compile-time-enabled Denza Apps trip/spectrum panel.
`TripSensorHub` registers gravity, gyroscope, and accelerometer with a
`33,333 us` sampling-period hint, reads standard GNSS at approximately `1 Hz`,
and reuses the validated Yandex guidance runtime. This records current product
wiring, not a fresh performance claim: CPU, thermal, actual delivered sensor
rate, and long-drive behavior still need a retained live profile.

## Android inertial sensors

`dumpsys sensorservice` reported these relevant sensors without a sensor
permission:

| Sensor | Android type | Mode | Reported rate |
| --- | --- | --- | --- |
| `TDK icm42670` | `android.sensor.accelerometer` (`1`) | continuous | 15–100 Hz |
| `TDK icm42670` | `android.sensor.gyroscope` (`4`) | continuous | 15–100 Hz |
| Game Rotation Vector | `android.sensor.game_rotation_vector` (`15`) | continuous | up to 100 Hz |
| Gravity | `android.sensor.gravity` (`9`) | continuous | up to 100 Hz |
| Linear Acceleration | `android.sensor.linear_acceleration` (`10`) | continuous | up to 100 Hz |
| `icm4n607-a-iner` | vendor `android.sensor.accelerometer_scp` (`65613`) | continuous | 12.5–400 Hz |
| `icm4n607-g-iner` | vendor `android.sensor.gyroscope_scp` (`65614`) | continuous | 12.5–400 Hz |
| `als-can` | `android.sensor.light` (`5`) | on-change | up to 100 Hz |

The stock Yandex process, running as a normal app UID, has registered standard
accelerometer, gyroscope, and game-rotation sensors. This is direct evidence
that the standard IMU path is not limited to privileged packages.

Stationary samples showed a fixed head-unit mounting orientation rather than
ready-to-use vehicle axes. Product code must calibrate gravity and map sensor
axes to longitudinal, lateral, and vertical vehicle motion before interpreting
events.

The vendor SCP sensors expose vendor-specific payloads and were active in the
stock AutoSDK process. Their manifest-level sensor permission is `n/a`, but
normal Denza Apps registration and payload interpretation remain untested.
Start with standard sensors; treat the vendor sensors as a later accuracy
experiment.

Temperature sensors were also present, but they appear to be IMU/chip
temperature sensors. They are not evidence of cabin or outside temperature and
must not be shown as such.

## GNSS

The Android GPS provider is active and reports:

- latitude/longitude;
- speed;
- bearing;
- altitude;
- horizontal, vertical, speed, and bearing accuracy;
- satellite/signal metadata.

Stock location consumers request approximately one-second updates. A normal app
can use the same provider after the standard location permission flow.

Useful derived journey values include:

- the travelled trace and distance;
- elevation profile and accumulated climb/descent;
- moving and stopped segments;
- direction and turn geometry;
- local sunrise/sunset calculations from time and position;
- fusion with IMU samples for a road-thread or body-motion visualization.

Current speed is useful internally for segmentation and distance checks, but it
need not be displayed because the cluster, HUD, and navigator already show it.

## Exported car-status provider

The system package `com.byd.providers.carstatus` declares:

```text
content://com.byd.carStatusProvider/car_status
content://com.byd.carStatusProvider/dicare_record
```

Reverse inspection of `CarStatusProvider.apk` showed:

- `android:exported="true"`;
- no provider read or write permission;
- SQLite-backed `query`, `insert`, `update`, and `delete`;
- `notifyChange(...)` after updates.

Only read/query and observation are acceptable for research. Denza Apps must
never write to this stock database.

Rows observed in `car_status` included:

- `travel_points_fuel`;
- `travel_points_elec`;
- the `_one` and `_two` variants of those arrays;
- `car_status_issue` and `car_status_issue_num`;
- maintenance time, total-mileage, and HEV-mileage fields;
- maintenance reminder/switch fields.

The raw electric series contains both positive and negative integers, but that
does not prove units, sign convention, sample interval, power, consumption, or
regeneration. The series names say “travel points,” not “power.” Do not build an
energy or regeneration UI from these values until the producer and units are
identified and a moving capture is correlated with known vehicle behavior.

`dicare_record` returned no rows in this test.

## High-level DiCar service

Reverse inspection of:

```text
/system/priv-app/DiCarServer/DiCarServer.apk
/system/priv-app/BydClusterApp/BydClusterApp.apk
```

found exported Binder-provider infrastructure and high-level service interfaces.
A temporary normal-UID APK successfully obtained the Binder through:

```text
content://com.byd.car.server.provider.CarServiceProvider
```

Transport access did not imply data access. Representative getter results:

| Getter/data family | Result |
| --- | --- |
| battery level | `20004`, missing `android.permission.BYDAUTO_STATISTIC_GET` |
| energy flow | `20004`, missing `android.permission.BYDAUTO_ENERGY_GET` |
| pure-electric range | `20004`, missing `android.permission.BYDAUTO_SETTING_GET` |
| stationary charging power/SOC/state | `20004`, missing `android.permission.BYDAUTO_CHARGING_GET` |
| accelerator/brake position | `20004`, missing `android.permission.BYDAUTO_SPEED_GET` |
| steering angle/motor torque | `20004`, missing `android.permission.BYDAUTO_SETTING_GET` |
| heading/tire values | `20004`, missing `android.permission.BYDAUTO_INSTRUMENT_GET` |
| PM2.5 | `20004`, missing `android.permission.BYDAUTO_PM2P5_GET` |
| CO2/air quality | `20004`, missing `android.permission.BYDAUTO_AC_GET` |
| pitch/roll | `10004`, CAN signal unsupported on this device/API path |
| mileage families | failed to get mileage |
| current trip info | `20001`, invalid property key format |
| energy-consumption API version/type | success with value `1`; capability metadata only |

The conclusion for a **normal app UID** is still fail-closed: do not add
`BYDAUTO_*` permissions to the product manifest, and do not call these
high-level getters from `dev.denza.apps` process identity.

The 2026-07-24 probe also returned `20001` invalid property key format for
trip info. The keys it needed were not names like `battery_level`; they are
the hex CAN feature IDs from the DiLink catalog (`com.byd.feature.*`), for
example `"0x44700038"`. That catalog gap, plus never calling the native
`autoservice` Binder from shell, is why BMS/cell/12V looked unavailable.

## autoservice FID protocol

Live-verified 2026-08-22 on this DiLink 5.1 IVI over `adb -s 127.0.0.1:5555`.
The car was parked and on AC charge (gun=2, ~2.4 kW, SOC rose 42→43 % during
the session). Read-only `service call` only: no APK install, no `app_process`,
no writes, `dumpsys autoservice` returns empty.

**Result: working** for shell UID. **Blocked** for a normal app UID. Same
Binder third-party dashboards use (BYDMate `AutoserviceClient`, EV Pro
local-ADB layer, OpenBYD privileged proxy). OpenBYD's `app_process` +
`BydContextWrapper.checkPermission()==0` is unnecessary for reads: a one-shot
`service call` from shell is enough.

### Binder

```text
autoservice: [android.gui.BYDAutoServer]
```

| Transact | Meaning | Do not use |
| --- | --- | --- |
| `5` | `getInt(dev, fid)` | |
| `7` | `getFloat(dev, fid)` — IEEE-754 bits in the parcel int | |
| `6` | `setInt(dev, fid, value, binder)` — pass a `null` binder | speaker-cover SET is documented in [speaker-lift-findings.md](speaker-lift-findings.md); do not probe other write FIDs |
| `10` / `12` (and similar array/float methods) | | **Do not** call with `i32`-only parcels. Wrong arity SIGSEGV’d `/system/bin/autoservice` on 2026-08-22 (recovered; not `com.byd.avc`). |

Device types from `android.hardware.bydauto.BYDAutoConstants` (MapHelper stub
JAR; live `dev` values match):

| Family | `dev` | Used for |
| --- | --- | --- |
| AC | `1000` | cabin/out temps, CO2 |
| bodywork | `1001` | 12V, cell count, doors, hood |
| audio | `1002` | speaker flip / RLSA / amp config — [speaker-lift-findings.md](speaker-lift-findings.md) |
| power | `1005` | LV / HV state flags |
| energy | `1006` | 12V twin, 50 km split |
| instrument | `1007` | tyre temps, some trip figures |
| PM2.5 | `1008` | in/out µg/m³ |
| charging | `1009` | pack V, current, gun, charge power |
| gearbox | `1011` | EPB, park switch |
| engine | `1012` | pack power kW, motor rpm |
| speed | `1013` | speed / pedal (parked = 0) |
| statistic / BMS | `1014` | SOC, SoH, cells, pack temp, odo |
| tyre | `1016` | pressures |
| OTA | `1032` | 12V alias |
| GB | `1039` | motor temps, bus V, insulation |
| sensor | `1043` | slope, windshield humidity |

Hex feature IDs (`fid`) come from the MapHelper-bundled catalog
`reverse/maphelper-jadx/sources/com/byd/feature/{statistics,energy,gb,charging,…}`.
The catalog is ~8000 constants; most are SET/CONFIG/FAULT. Motor temp IDs are
generation-specific (`GB_FRONT_MOTOR_TEMP` vs `_DM40` vs `_DM40_464`); this
car answers on the `_DM40_464` set.

### How to read

```bash
# getInt — pass fid as a signed 32-bit decimal (high bit set → negative)
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 <dev> i32 <fid>
# getFloat
adb -s 127.0.0.1:5555 shell service call autoservice 7 i32 <dev> i32 <fid>
```

Stdout: `Result: Parcel(00000000 XXXXXXXX   '....')`. The second 32-bit word
is the payload.

```python
import struct
word = int("XXXXXXXX", 16)
sint = word - 2**32 if word >= 2**31 else word
flt  = struct.unpack(">f", bytes.fromhex("XXXXXXXX"))[0]
```

From Denza Apps, the same command goes through the existing local-ADB client
(`DenzaLocalAdb.client(context).shell(...)`), not through
`BYDAutoStatisticDevice.get()` in the app process. Do not add `BYDAUTO_*`
permissions to the product manifest. Do not spawn a long-lived `app_process`
proxy.

If transact 5 returns −10013, retry 7 (and vice versa).

### Sentinels — do not display

| Parcel / value | Meaning |
| --- | --- |
| `0xffffd8e3` (−10013) | wrong transact or direction |
| `0xffffd8e5` (−10011) | no data / not on this generation |
| `0xbf800000` (−1.0f) | invalid float |
| `0xffffffd8` (−40) | often “no sensor” (also a real −40 °C offset-zero) |
| `255`, `4095`, `1023`, `205`, `40.95`, `6153.5` | max-range placeholders, common on V2L/VTOV |

Cell delta has no FID: compute max − min locally.

### Scales proven or likely on this car

| Rule | Evidence |
| --- | --- |
| Pack temp °C = int − 40 | avg raw 68 → 28 °C vs third-party 29.0 °C |
| Motor / IPM / tyre / climate °C = int as-is | front motor 31 matched the screenshot card |
| Cell voltage V = int / 1000 | 3313 → 3.313 V |
| 12V = float volts | `0x415ccccd` → 13.80 |
| SOC % = float (also int twin) | 43.0 |
| Odometer km = int / 10 | 118927 → 11892.7 km (BYDMate convention, plausible) |
| ~~Remaining energy kWh = int / 10~~ **falsified 2026-08-22** | `0x44700028` is the BMS state of charge in tenths of a percent: `432` against a 43 % display, `616` against a 62 % display. The "43.2 / 0.43 ≈ 100 kWh pack" inference was circular — a state of charge divided by itself always lands near 100. The owner puts this pack under 40 kWh, and the stock home widget reports 23.2 kWh/100 km over 50 km, which fits a ~38 kWh plug-in hybrid, not a 100 kWh EV |
| Tyre pressure bar = int / 100 | 287 → 2.87 bar |
| Charge gun 2 = AC connected | BYDMate table; SOC rose during the session |
| Pack V cross-check | 166 cells × 3.315 V ≈ 550 V = `CHARGING_CHARGE_BATTERY_VOLT` |

Unproven — do not label in a UI until a moving capture: `STATISTIC_INSTANTANEOUS_CURRENT`
(raw 35721), available-power 72, LV-side current 28 vs 2.8 A, driving-time 56.3
hours vs minutes, max chg/dchg 759/3641.

## Widget allowlist (2026-08-22)

Filtered scan: 1156 telemetry-named FIDs (skipped SET/CONFIG/FAULT and
ADAS/settings/audio/lights) → 676 non-sentinel parcels. Most are door-actuator
flags or placeholders. A product widget polls only the rows below.

| Group | Signal | FID | dev | tx | Session value | Decode |
| --- | --- | --- | --- | --- | --- | --- |
| BMS | SOC | `0x4A505038` | 1014 | 7 | 43 | % |
| BMS | SoH | `0x44400028` | 1014 | 5 | 99 | % |
| BMS | Pack temp avg/min/max | `0x44700038` / `0x44700010` / `0x44700020` | 1014 | 5 | 68 / 67 / 69 | °C = raw − 40 |
| BMS | Cell min/max | `0x44600010` / `0x44600030` | 1014 | 5 | 3313 / 3317 | mV; Δ local |
| BMS | Series cell count | `0x43A00008` | 1001 | 5 | 166 | cells |
| BMS | BMS state of charge | `0x44700028` | 1014 | 5 | 432 / 616 | percent ×10, **not** energy — see the scales table |
| HV | Pack voltage | `0x44400008` | 1009 | 5 | 550 | V |
| HV | Front/rear bus | `0x46407020` / `0x46407010` | 1039 | 5 | 551 / 551 | V |
| HV | Charge power | `0x32300018` | 1009 | 7 | 2.4 | kW |
| HV | Charge current | `0x44400018` | 1009 | 7 | −4.4 | A; sign not proven |
| HV | Engine/pack power | `0x14400020` | 1012 | 5 | −2 | kW |
| HV | Insulation | `0x43A00018` | 1039 | 5 | 13051 | likely kΩ |
| 12V | Voltage | `0x43400028` | 1001 | 7 | 13.8 | V (energy twin `0x36D00020` / 1006) |
| Range | Remaining EV range | `0x4A50203E` | 1014 | 5 | 67 | km |
| Trip | Odometer | `0x4A502010` | 1014 | 5 | 118927 | km ×10 |
| Trip | Lifetime kWh / kWh/100 | `0x3D906030` / `0x4A501030` | 1014 | 7 | 997.9 / 6.5 | |
| Trip | Last 50 km equivalent | `0x4A507032` | 1014 | 7 | 6.6 | **not kWh/100 km** — the stock home widget showed 23.2 kWh/100 km for the same 50 km window on 2026-08-22 |
| Trip | 50 km split drive/AC/aux | `0x35903831` / `0x35903838` / `0x35903841` | 1006 | 5 | 91 / 4 / 5 | % |
| Charge | Gun | `0x34400032` | 1009 | 5 | 2 | 2 = AC connected |
| Charge | SOC on charger | `0x32300010` | 1009 | 5 | 43 | % |
| Charge | Time remaining h/min | `0x32300028` / `0x32300030` | 1009 | 5 | 9 / 17 | |
| Motor | Front temp / IPM | `0x46406018` / `0x46406010` | 1039 | 5 | 31 / 26 | °C raw |
| Motor | Rear L/R temp | `0x285001A8` / `0x285001B0` | 1039 | 5 | 29 / 31 | °C raw; not the third-party 40 °C card |
| Motor | rpm front/rear | `0x44100008` / `0x25100008` | 1012 | 5 | 0 / 0 | |
| Tyre | Pressure LF/RF/LR/RR | `0x99000124` / `28` / `2c` / `30` | 1016 | 5 | 287 / 287 / 285 / 287 | kPa → bar /100 |
| Tyre | Temp LF/RF/LR/RR | `0x4A50A018` / `24` / `30` / `3c` | 1007 | 5 | 25 / 24 / 23 / 23 | °C raw |
| Climate | Inside / filtered / out | `0x3D800030` / `0x4EB06010` / `0x40400038` | 1000 | 5 | 23 / 26 / 21 | °C |
| Climate | Main / passenger set | `0x40400028` / `0x40400030` | 1000 | 5 | 23 / 24 | °C |
| Air | PM2.5 in / out | `0x4F600010` / `0x4F60001C` | 1008 | 5 | 9 / 19 | µg/m³ |
| Air | CO2 outside | `0x35B00018` | 1000 | 5 | 300 | ppm-ish |
| Cabin | Windshield humidity / surface | `0x3B400008` / `0x3B400010` | 1043 | 7 | 19.5 / 28 | % / °C |
| Cabin | Slope | `0x2230002C` | 1043 | 5 | −4 | deg-ish |
| Body | EPB / park switch | `0x21800011` / `0x05500030` | 1011 | 5 | 3 / 1 | parked |

Open: no FID on this firmware reproduced the third-party “rear motor 40 °C”
card; `STATISTIC_INSTANTANEOUS_CURRENT` scale unknown. Next action: one
moving-drive capture of current, pack power, and rear-motor FIDs, then stop
scanning the catalog.

## Shipped panel wiring (2026-08-22)

`feature.vehicle` in Denza Apps is the second page of the swipeable bottom
panel. It is the only product consumer of this document's allowlist.

| Decision | Where | Why |
| --- | --- | --- |
| Read transacts only (`5`, `7`) | `VehicleSignals.kt` | `setInt` (`6`) must never appear in a product package; a unit test asserts the built command never contains it |
| Shell identity, PASSIVE policy | `VehicleTelemetryHub` via `DenzaLocalAdb` | Proves existing trust, never enqueues an authorization prompt; an untrusted key leaves the page empty with the reason |
| One batched command per sweep | `AutoserviceShell.command` | 6 hot ids at panel cadence and 29 cold ids every 10–30 s would otherwise be one ADB round trip each |
| `echo @@<index>` before each call | `AutoserviceShell.parse` | A feature id that prints nothing on this generation cannot shift the following answers onto the wrong signals |
| Plausibility gate per unit | `VehicleKind.accepts` | Sentinels and max-range placeholders are dropped by what the unit can physically be, not by a blacklist — `255` stays a legal 2.55 bar and an illegal 215 °C |
| Hot values never carried over | `VehicleTelemetryHub` | A stale kilowatt figure is worse than a dash; cold values do carry over between sweeps |
| Odometer, not GNSS, for distance | `ConsumptionLog` | The page needs no location permission and keeps its histogram with the trip page closed |
| Pack-power sign in one constant | `VehicleConvention` | The convention is inferred from the parked charging session, not proven; the drive capture flips one line if it is wrong |
| No shell until the page is opened | `VehiclePanelView.syncHub` | A session that never swipes to the page costs the car nothing |
| One big figure per block | `VehiclePanelRenderer` | Chosen for movement, not abstract importance: charge, traction voltage, the hottest drivetrain reading, consumption. A number that never moves is a supporting line whose colour, not size, raises a hand |
| Live consumption is a rolling window, not the open bar | `ConsumptionLog` | kWh per 100 km has no value at zero speed; folding standstill energy into it made a parked car's reading crawl. The window stops at a standstill, the bars keep the energy |
| Two type scales, never one | `VehiclePanelRenderer` | A virtual unit is about 0.6 dp at full width and exactly 1 dp in the narrow pane, so a shared constant renders at two different sizes |
| No block headings in the narrow pane | `VehiclePanelRenderer.drawNarrow` | The hairlines already separate the blocks, and the four headings were what pushed the consumption chart off the bottom of the pane |

Unit tests cover the command shape, the marker alignment, the proven scales, the
sentinel and plausibility rules, and the consumption accumulator including the
standstill rule.

### What the panel does not show, and why

Removed after the owner reviewed it on the car on 2026-08-23:

- **tyre pressures and temperatures.** The car has a native tyre-pressure
  display; the panel was repeating it. Eight feature ids left the allowlist, and
  with them the only ones past `0x7fffffff` — the signed-decimal rule still has
  a test, now over the whole allowlist rather than one tyre id.
- **cabin and outside temperature.** Both are already on the instrument cluster.
- **remaining range, BMS state of charge, and pack health in the narrow pane.**
  Kept at full width, where there is room for a second opinion on the charge.

The three drive motors are now reported separately (front, rear left, rear
right) rather than as a front/rear pair, with the inverter on its own row: this
car has three motors, and one of them running away from the others is what the
row exists to show.

`CHARGE_KW` has its own plausibility gate (`-1..160 kW`) rather than sharing the
pack-power one (`±600 kW`). The wide gate let a spike through and the panel
showed a three-hundred-kilowatt charge on a car parked on a household socket.
Pack power keeps the wide gate: this car really can pull hundreds of kilowatts.

### Measured on the car (2026-08-22, second session, parked on AC charge)

All 33 allowlist signals answered, none returned a sentinel, and none was
dropped by the plausibility gate — the catalog is correct for this firmware. The
shipped allowlist is now 23 signals: tyres and cabin/outside climate were
removed from the panel, and polling them was the only reason to read them.

| Batch | Calls | Wall time on the head unit |
| --- | --- | --- |
| Shell baseline (two `date` calls, no reads) | 0 | 7–11 ms |
| One `service call` | 1 | 14–22 ms |
| Hot batch | 5 | 129–195 ms |
| Hot + cold batch | 33 | 270–287 ms |

The cost is almost all fixed: about 4–5 ms per additional call against roughly
130 ms of shell and first-process overhead. Batching is therefore what makes the
panel affordable, and widening the hot set is nearly free, while shortening the
interval is what actually costs the car.

That measurement set the shipped cadence. The hot interval is `300 ms`, so a
fresh power figure lands about twice a second and the shell is busy about a third
of the time — but only while the vehicle page is the one on screen. The cold set
still joins every 10 s, a sweep that costs about 280 ms. Splitting the hot set
any finer would buy nothing: a one-call batch costs almost what a five-call batch
costs.

Two readings from the same session are worth keeping:

- the cell window moved from 3313–3317 mV at 43 % to 3352–3358 mV at 72 %, and
  the pack from 550 V to 557 V. The LFP curve is flat, not frozen; the knee
  starts showing above roughly 70 %.
- vendor range read 128 km at 72 % SOC. Against the stock home widget's
  23.2 kWh/100 km over 50 km that implies a pack in the low forties of kWh,
  which agrees with the owner's "under 40" and rules out the falsified 100 kWh
  figure. It is an estimate from two vendor-derived numbers, not a measurement,
  and the panel does not display it.

## Combustion side of the hybrid (2026-08-23)

This car is a PHEV and the catalog carries a full combustion set. Candidates
were taken from `reverse/maphelper-jadx/sources/com/byd/feature/` and then read
on the car in two read-only sweeps, **engine stopped, car parked**. Roughly half
the set answers; the interesting half does not.

### Answered on this car, engine stopped

| Reading | FID | dev | tx | Value | Note |
| --- | --- | --- | --- | --- | --- |
| Fuel level | `0x4A507040` | 1014 | 5 | `53` | percent, `STATISTIC_FUEL_PERCENTAGE` |
| Range on fuel | `0x4A504038` | 1014 / 1007 | 5 | `491` | km; consistent with 53 % of the tank |
| Engine speed | `0x14400012` | 1012 | 5 | `0` | rpm; `_20D` `0x20D00008` and `_GB` `0x10D00008` agree |
| Engine running | `0x10D00038` | 1012 | 5 | `0` | stopped |
| Displacement | `0x40D00008` | 1012 | **7** | `2.0` | litres — matches this car's 2.0 T |
| Engine code | `0x40D00028` | 1012 | 5 | `26` | unknown enum |
| Catalyst heating | `0x2330002D` | 1012 | 5 | `1` | |
| Coolant level flag | `0x05500031` | 1012 | 5 | `0` | |
| Oil level | `0x05500038` | 1012 | 5 | `27` | not a 0/1 flag; scale unknown |
| Oil / transmission / blade-coolant service life | `0x4BB000D0` / `D8` / `E8` | 1001 | 5 | `155` each | identical across all three, so probably not per-item |
| High water temperature warning | `0x3D911018` | 1007 | 5 | `0` | flag only, not a temperature |
| **Vehicle speed** | `0x94400008` | 1013 | **7** | `0.0` | reaches the shell as `-1807745016` |
| Accelerator / brake position | `0x34200008` / `0x34200010` | 1013 | 5 | `0` / `0` | |

### Did not answer — every candidate, at every device tried

Coolant temperature is the notable miss. `ENGINE_ENGINE_COOLANT_TEMPERATURE`
(`0x4EB06040`) returned the sentinel at devices 1000, 1006, 1007, 1012, 1014 and
1039; so did `ENERGY_ENGINE_THERMOSTAT_WATER_TEMPERATURE` (`0x32400048`, and its
`_CAN` twin `0x30D00008`) and `STATISTIC_WATER_TEMPERATURE` (`0x3D956038`).
`INSTRUMENT_WATER_TEMP_METER_PERCENT` (`0x4A509018`) answers as a float but
reads `409.5`, a placeholder rather than a gauge position.

Also silent: indicated torque (`0x32400058`), boost actual and target
(`0x387000C0` / `B8`), charge-air temperature (`0x387000C8`), per-cylinder knock
retard (`0x32400180`…), pre-ignition counters (`0x324001C8`…), instantaneous
fuel consumption (`0x32400098`, and `_ML` `0x30D00010`), and engine water-pump
speed (`0x46C00040`).

The silence clusters by feature-id prefix rather than by subject: the whole
`0x324`, `0x387` and `0x30D` families are quiet, including `ENGINE_SPEED_324`
while `ENGINE_SPEED` on `0x144` answers. That points at generation — the same
lesson the motor temperatures taught with `_DM40_464` — rather than at the
engine being stopped. **One repeat sweep with the engine running settles it**,
and it is the same read-only script.

### Engine itself — `com/byd/feature/engine/Engine.java`, dev `1012`

| Reading | Constant | FID |
| --- | --- | --- |
| Engine speed | `ENGINE_SPEED` | `0x14400012` |
| Engine speed, other generations | `ENGINE_SPEED_20D` / `_324` / `_GB` | `0x20D00008` / `0x32400038` / `0x10D00008` |
| Running or stopped | `ENGINE_STATE` | `0x10D00038` |
| Coolant temperature | `ENGINE_ENGINE_COOLANT_TEMPERATURE` | `0x4EB06040` |
| Thermostat water temperature | `ENERGY_ENGINE_THERMOSTAT_WATER_TEMPERATURE` (+ `_CAN`) | `0x32400048` / `0x30D00008` |
| Indicated torque | `ENGINE_INDICATED_TORQUE_NM` | `0x32400058` |
| Boost, actual and target | `ENGINE_ACTUAL_BOOST_PRESSURE` / `_TARGET_` | `0x387000C0` / `0x387000B8` |
| Charge air temperature | `ENGINE_BOOST_GAS_TEMPERATURE` | `0x387000C8` |
| Power sent to the pack by the engine | `ENGINE_CHARGE_POWER` | `0x2ED00010` |
| Knock retard angle, cylinders 1–4 | `ENGINE_CYLINDER_n_KNOCK_RETARD_ANGLE` | `0x32400180` + `0x10` each |
| Pre-ignition counters A–D | `ENGINE_x_CYLINDER_PRE_IGNITION_COUNTER` | `0x324001C8` + `0x8` each |
| Catalyst heating | `ENGINE_CATALYST_HEATING_FLAG` | `0x2330002D` |
| Coolant / oil level flags | `ENGINE_COOLANT_LEVEL` / `ENGINE_OIL_LEVEL` | `0x05500031` / `0x05500038` |
| Oil, transmission oil, coolant service life | `BODY_OIL_LIFE` and neighbours | `0x4BB000D0` … |

### Fuel

| Reading | Constant | FID | dev |
| --- | --- | --- | --- |
| Instantaneous consumption | `ENERGY_INSTANTANEOUS_FUEL_CONSUMPTION` | `0x32400098` | 1006 |
| Instantaneous consumption, ml | `..._ML` | `0x30D00010` | 1006 |
| Trip average | `ENERGY_CURRENT_ITINERARY_AVERAGE_FUEL_CONSUMPTION` | `0x3590481F` | 1006 |
| Average over the last 200 m | `STATISTICS_AVERAGE_INSTANT_FUEL_LAST_200M` (+ `_HEV`) | `0x3D90601C` / `0x46100030` | 1014 |
| Range on fuel | `STATISTIC_FUEL_DRIVING_RANGE` | `0x4A504038` | 1014 |
| Low fuel alarm | `INSTRUMENT_FUEL_LOW_ALARM` | `0x4A507027` | 1007 |

No plain "tank level, percent" constant turned up — only the alarm and the
range. If the sweep confirms that, a fuel gauge has to come from range, which
is a vendor estimate rather than a measurement.

### Two corrections this reading forced

- **`0x14400020` is `ENGINE_POWER` in the catalog**, and it is the id the panel
  currently labels pack power. Parked on AC charge it read `-2` while the
  charging device reported `+2.4` kW with the engine stopped, so it is not the
  engine's own output — but the label is an assumption, and the drive capture
  that settles the sign should settle the meaning at the same time. If it turns
  out to be combustion power, the whole consumption block is mislabelled.
- **Vehicle speed exists and reads**: `SPEED_AUTO_SPEED` = `0x94400008`
  (dev `1013`), on transact **7** as a float, `0.0` parked. `SPEED_AUTO_SPEED_121`
  = `0x12100008` agrees. An earlier note in this session said speed was absent;
  that was a search of this document, not of the catalog. With speed the live
  consumption figure can be instantaneous (power over speed) instead of a
  rolling odometer window, and a standstill is detectable immediately rather
  than after five seconds of a still odometer. Note `0x94400008` sets the high
  bit, so it reaches the shell as `-1807745016` — the signed-decimal rule the
  tyre ids used to cover.

## Legacy BYDAuto events and system logs

The archived 2026-06-27 probe under
[`research/vehicle-events/`](../research/vehicle-events/) established that:

- `BYDAUTO_*_COMMON` permissions could be granted;
- `BYDAUTO_*_GET` permissions were not granted;
- listener registration succeeded but produced no useful app callbacks;
- direct getters failed with `SecurityException`;
- system logs still showed BYDAuto events and camera activity.

The 2026-07-24 passive shell inspection additionally saw:

- `BYDAutoStatisticDevice getSpeedSignalVDisValue` at roughly 1 Hz;
- high-rate `BydDms CanDriver updateSteeringSpeed` logs with uncertain
  semantics;
- PM2.5 and safety-belt events;
- frequent bodywork, settings, special, and sensor events.

These logs prove that stock system processes receive live vehicle state. They do
not provide a product path: Denza Apps has no `READ_LOGS`, and a normal APK must
not depend on shell log collection.

## Existing navigation-derived data

Denza Apps already validates visible Yandex guidance through its accessibility
service and can derive:

- maneuver;
- next road;
- remaining route distance and time;
- optional current/road text.

This is event-driven UI-derived data, not a stable navigation SDK. It is usable
only while the expected scene is visible and current. The existing staleness,
package allowlist, and fail-closed clearing rules must remain in force.

No generic route geometry, destination weather, or “road ahead” feed was proven
in this vehicle-data investigation.

## Product implications

| Concept | Inputs available now | Important caveat |
| --- | --- | --- |
| Road thread / journey trace | GNSS at about 1 Hz, altitude, bearing, calibrated IMU | Needs a real moving capture and axis calibration |
| Body-motion field | accelerometer + gyroscope at a proposed 30 Hz | Represents head-unit/body motion, not suspension travel or wheel impact |
| Trip event ribbon | GNSS stops/elevation plus IMU turns/vertical impulses | Event labels need deterministic, documented thresholds |
| Local daylight indicator | time + GNSS position | Useful but not vehicle telemetry |
| Route remaining time/distance | existing validated Yandex accessibility guidance | Only while guidance is visible and fresh |
| Road-surface memory | GNSS + calibrated vertical motion stored locally | Deferred; needs repeat-drive validation and false-positive analysis |
| Maintenance summary | exported car-status rows | Units/state semantics need confirmation |
| Regeneration/energy display | no qualified instantaneous current yet | Do not label `35721` as amps |
| Technical BMS / 12V / HV widget | `autoservice` allowlist via `DenzaLocalAdb` | Built as `feature.vehicle`; shell-only, short allowlist, no `BYDAUTO_*` in the manifest |
| Tyre / climate / PM2.5 | same Binder, different `dev` | Same privilege path; still blocked from app UID |

The current road-thread/body-field prototypes use simulated values. They show a
candidate visual mapping only; they are not live-car evidence.

## Commands and reverse-engineering inputs

Core read-only commands:

```bash
adb -s 127.0.0.1:5555 shell dumpsys sensorservice
adb -s 127.0.0.1:5555 shell dumpsys location
adb -s 127.0.0.1:5555 shell service list
adb -s 127.0.0.1:5555 shell dumpsys package providers
adb -s 127.0.0.1:5555 shell content query \
  --uri content://com.byd.carStatusProvider/car_status
adb -s 127.0.0.1:5555 shell content query \
  --uri content://com.byd.carStatusProvider/dicare_record
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 1014 i32 1147142160
adb -s 127.0.0.1:5555 shell service call autoservice 7 i32 1001 i32 1128267816
```

Reverse inputs inspected locally and intentionally not committed:

```text
/system/priv-app/DiCarServer/DiCarServer.apk
/system/priv-app/BydClusterApp/BydClusterApp.apk
/system/priv-app/CarStatusProvider/CarStatusProvider.apk
MapHelper.apk  (bundled com.byd.feature.* FID catalog + BYDAutoConstants)
```

The first shell-only `app_process` harness was killed and was abandoned. An
initial no-display Activity harness then failed because Android requires a
`Theme.NoDisplay` Activity to finish before resume. A transparent temporary
Activity corrected the harness and produced the normal-UID permission matrix
above. These were probe-process failures, not vehicle-process failures.

## Next useful validation

GNSS/IMU (unchanged from 2026-07-24):

1. Isolated normal-APK recorder for standard accelerometer, gyroscope,
   gravity/linear acceleration, and GNSS.
2. Short drive with the head unit untouched; calibrate vehicle axes.
3. Profile the 30 Hz sensor loop for CPU/thermal.

`autoservice` widget (2026-08-22; stop catalog scanning):

1. One moving-drive capture of pack power, `STATISTIC_INSTANTANEOUS_CURRENT`,
   motor rpm/temp, and the rear-motor FID candidates.
2. Establish the real pack size from the owner's paperwork or one full charge
   session. Nothing on this Binder gives it: `0x44700028` turned out to be a
   second state of charge, and the panel no longer pretends otherwise.
3. Confirm the pack-power sign in motion: acceleration must read positive with
   `VehicleConvention.POWER_POSITIVE_IS_DISCHARGE = true`. If it reads negative,
   flip that one constant.
4. Measure traction-voltage sag under load. On this LFP pack the resting voltage
   is flat across the charge window — 550 V at 43 %, 551 V at 62 % — so current
   is the only thing that moves it. The panel shows it as context, not as a
   gauge, until that sag figure exists.
5. Cross-check the odometer against the trip panel's GNSS distance over the same
   stretch; the consumption histogram's axis rests on the ×10 scale, and the
   stock home widget's 23.2 kWh/100 km over 50 km is the number it should land
   near.
6. ~~Record `sweepMillis`~~ **done**: the hot batch costs 129–195 ms and the full
   sweep 270–287 ms. The hot interval was set to 300 ms on that evidence; what
   remains is to confirm on a drive that the car keeps up with it under load.

Until the drive capture, the honest **app-UID** boundary remains GNSS plus
standard IMU plus fail-closed Yandex guidance. The honest **shell-UID**
boundary is the widget allowlist above.
