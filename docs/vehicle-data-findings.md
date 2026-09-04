# Vehicle Data Availability Findings

Status: live-car availability investigation on 2026-07-24, with the earlier
vehicle-event probe results from 2026-06-27 retained where relevant, a shell-UID
`autoservice` FID read on 2026-08-22, and the former head-unit vehicle panels
wired to that allowlist the same day (built and unit-tested then, reviewed on
the car on 2026-08-23). The cluster dashboard first ran on the car on
2026-08-25. Product wiring was checked again on 2026-08-27: the head-unit panels
are deleted, and the cluster dashboard is the only active UI consumer of the
telemetry backend. A second car, a Denza N9, was attached on 2026-08-30, and the
vehicle-id check that tells it apart from the Z9GT is recorded here. On
2026-09-03, passive raw CAN-FD callbacks were also confirmed from an
owner-controlled local ADB shell on the Denza Z9. Targeted shell-UID turn-signal
events were live-proven on 2026-09-04. They feed bounded diagnostics and an
early-teardown guard for an active Mirrors camera; the stock AVC window is the
only camera-eligibility authority, and the listener can never open a camera.

This page records which vehicle and journey signals a normal Denza Apps APK can
actually use. It distinguishes product-usable sources from values that are
visible only to system processes, shell diagnostics, or vendor API surfaces
identified through static inspection.

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

The same local shell identity can also receive the raw CAN-FD frames already
selected by the stock `CanDataCollect` service. The confirmed callback is
`BYDAutoBigDataDevice` FID `0x99000020`; a 15-second passive observation
received 2,054 callbacks. This is a diagnostic stream, not evidence that a
normal Denza Apps process can register for it. Details:
[raw CAN-FD callback](#raw-can-fd-callback-2026-09-03).

## Test environment

| Item | Value |
| --- | --- |
| Vehicle | Denza Z9GT (everything before 2026-08-30); Denza N9 from 2026-08-30 |
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

## Which car is this (2026-08-30)

Every measurement on this page before 2026-08-30 was taken on a Denza Z9GT. A
Denza N9 was attached over local ADB on 2026-08-30. The two cars run the same
DiLink 5.1 build and are not distinguishable by any standard Android identity,
so a reliable check has to use the vendor's own vehicle id.

That id is `AutoType` in BYD code, carried on the wire as FID
`BODYWORK_AUTO_TYPE` (`0x40D00010`, hence the `40d` in one property name):

| Car | vehicle id |
| --- | --- |
| Denza N9 | `168` |
| Denza Z9 / Z9GT | `170`, `171`, `211`, `212` |

`DiCarServer` maps the id to a model name through its own asset table,
`/system/priv-app/DiCarServer/DiCarServer.apk!assets/VehicleCarType.json` —
100 ids over 21 names, the same names that `com.byd.car.VehicleCarType` holds as
constants. A second table in the same APK, `assets/vehicleType.json`, derives the
body class from the same id: `168` is `SUV`, all four Z9 ids are `CAR`. Neither
table separates Z9 from Z9GT — our Z9GT reports `170`, and `171` / `211` / `212`
have never been observed.

### Properties measured on the N9

All six are `u:object_r:system_prop:s0` and read from an app domain (checked
through `run-as dev.denza.apps`):

| Property | N9 value |
| --- | --- |
| `persist.sys.AutoType` | `168` |
| `persist.sys.car.type` | `168` |
| `persist.sys.vehicle_40d_code` | `168` |
| `persist.sys.model_variant.model` | `n9` |
| `persist.sys.byd.bluetooth_name` | `腾势N9` |
| `persist.sys.byd.default_name` | `腾势N9` |

`CarInfoServiceImpl.updateCarModel()` is what produces `model_variant.model`: it
reads the id, looks it up in `VehicleCarType.json`, and stores the name
lower-cased.

### What does not discriminate

Identical on both cars, so `Build.MODEL` and the rest of the standard Android
identity say nothing about which car the code is running in:

| Property | Value on both |
| --- | --- |
| `ro.product.model` | `DiLink5.1` |
| `ro.product.device`, `ro.product.name` | `IVI` |
| `ro.product.brand` | `BYD-AUTO` |
| `ro.vehicle.type` | `DiLink150_7.0UI` |
| `ro.build.car.series` | `denza` |

### Evidence, and the gap in it

The N9 values above are a direct read on 2026-08-30. For the Z9GT the only
archived evidence is the live FID value, logged three times across two months:

- `captures/drag-after-copycurrent.log:6659` — `getAutoType type is: 170` (06-28)
- `captures/turn-signal/turnsignal-20260626-154308.log:1702` — `获取车型ID:170` (06-26)
- `captures/split-live-acceptance/evidence/diag-v16/full-logcat.txt:20734` —
  `getAutoType type is: 170` (08-23)

The Z9GT *properties* were never captured. That `persist.sys.model_variant.model`
reads `z9` there follows from the vendor code path, not from a measurement.
Code that tells the cars apart should therefore key on the numeric id against the
table above, and treat an unrecognised id as unknown rather than as "the other
car". A full `getprop` dump from a Z9GT is the missing artefact.

One reliability caveat: `CarInfoServiceImpl.updateVehicleId()` compares the
stored property against the live FID and, on a mismatch, writes the **old** value
back instead of the new one. The property is `persist.` and survives a data wipe,
so a head unit moved between cars could keep a stale id. The live FID is
authoritative but reachable only from shell — see
[autoservice FID protocol](#autoservice-fid-protocol).

### Reading it

From shell or a host script:

```bash
adb shell "getprop persist.sys.AutoType; getprop persist.sys.model_variant.model; getprop persist.sys.byd.default_name"
```

From a normal APK, the way `RescueRunner.systemProperties()` already does it: run
`/system/bin/getprop` through `ProcessBuilder` and parse the `[name]: [value]`
lines. Reflection into `android.os.SystemProperties` is neither needed nor
allowed under hidden-API restrictions.

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
| Vehicle-id system properties (`persist.sys.AutoType` and mirrors) | which car the head unit is in | static; written at boot | none | Confirmed normal app | Usable; see [Which car is this](#which-car-is-this-2026-08-30) |
| Raw CAN-FD callback | frames selected by stock `CanDataCollect`; observed on channels 0, 1, and 2 | 2,054 callbacks in 15 s (about 136.9/s) | local ADB shell or system identity; normal-app access unproven | Shell/system only | Confirmed passive diagnostic stream through `0x99000020` |
| BYDAuto events/system logs | speed logs, bodywork/settings/safety-belt/PM2.5 events, other CAN-derived events | speed log about 1 Hz; other events vary; some logs are high-rate | system log access / protected BYD permissions | Shell/system only | Diagnostics only |

The current Denza Apps trip/spectrum panel reads standard GNSS at approximately
`1 Hz` and reuses the validated Yandex guidance runtime. Its earlier `30 Hz`
gravity/gyroscope/accelerometer pipeline fed only a permanently hidden
presentation and was removed from product code on 2026-08-26. The sensor
findings below remain useful research evidence, not current product wiring.

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

On 2026-08-28 a stationary, brake-held `P -> D -> P` check read each state five times. The park
switch was `1 -> 0 -> 1`, EPB was `3 -> 1 -> 3`, and vehicle speed remained `0` throughout. Denza
Apps uses only the proven park-switch fact to end and suppress its GNSS-derived trip while in P;
the readings do not claim values for N or R.

Open: no FID on this firmware reproduced the third-party “rear motor 40 °C”
card; `STATISTIC_INSTANTANEOUS_CURRENT` scale unknown. Next action: one
moving-drive capture of current, pack power, and rear-motor FIDs, then stop
scanning the catalog.

## Vehicle telemetry wiring (2026-08-22 to 2026-08-27)

The `feature.vehicle` backend originally fed the second and third pages of the
head-unit's swipeable bottom panel. `BottomPanelPager`, `VehiclePanelView`, and
`EnginePanelView` were retired and deleted on 2026-08-27. The table below
preserves the decisions behind that live-measured implementation; names of
deleted views and page-specific gates are historical, not current entry points.

| Historical decision | Former location | Why |
| --- | --- | --- |
| Read transacts only (`5`, `7`) | `VehicleSignals.kt` | `setInt` (`6`) must never appear in a product package; a unit test asserts the built command never contains it |
| Shell identity, PASSIVE policy | `VehicleTelemetryHub` via `DenzaLocalAdb` | Proves existing trust, never enqueues an authorization prompt; an untrusted key leaves the page empty with the reason |
| One batched command per sweep | `AutoserviceShell.command` | Every id would otherwise be a separate ADB round trip; the current cluster retains the same batching rule |
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
| Combustion signals poll only on the engine page | `VehicleSignal.engineOnly`, `VehicleTelemetryHub.setEngineActive` | The engine set is 21 of 44 signals and appears on no other page. Measured on the car: a full sweep costs 266–315 ms without it and 468–587 ms with it, so the electrical page would have paid double for lamps nobody is looking at |
| One lamp folded from several feature ids | `EngineLamp` | Four ids report low oil pressure and four report low coolant level; they are generation variants, and reading all of them is cheaper than betting on one |
| A lamp that never answered is not "healthy" | `LampState.UNKNOWN` | Every lamp read `0` on a healthy car, which proves they are readable, not that they light. A hollow dot makes a weaker claim than a green one |

The current product consumer is `feature.cluster.dashboard`. Its view owns the
only polling activity claim: attaching/showing the cluster dashboard starts the
hub, and hiding/detaching it stops the hub. There are no vehicle-page or
engine-page lifecycle flags and no page-dependent signal filter. While visible,
the cluster reads the six hot and thirty cold signals it needs, including the
combustion readings and lamps. Its consumption history is always rendered over
the latest **3 km**; no saved head-unit selector is consulted.

Unit tests cover the command shape, the marker alignment, the proven scales, the
sentinel and plausibility rules, and the consumption accumulator including the
standstill rule.

### What the historical head-unit panel did not show, and why

Removed after the owner reviewed it on the car on 2026-08-23:

- **tyre pressures and temperatures.** The car has a native tyre-pressure
  display; the panel was repeating it. Eight feature ids left the allowlist, and
  with them the only ones past `0x7fffffff` — the signed-decimal rule still has
  a test, now over the whole allowlist rather than one tyre id.
- **cabin and outside temperature.** Both are already on the instrument cluster.
- **remaining range, BMS state of charge, and pack health in the narrow pane.**
  At that time they stayed in the full-width page. Range and duplicate BMS state
  later left the allowlist with the retired pages; pack health remains on the
  cluster because the stock display does not show it.

The final head-unit panel reported the three drive motors separately (front,
rear left, rear right) rather than as a front/rear pair, with the inverter on
its own row: this car has three motors, and one of them running away from the
others is what the row exists to show.

`CHARGE_KW` has its own plausibility gate (`-1..160 kW`) rather than sharing the
pack-power one (`±600 kW`). The wide gate let a spike through and the panel
showed a three-hundred-kilowatt charge on a car parked on a household socket.
Pack power keeps the wide gate: this car really can pull hundreds of kilowatts.

### Measured on the car (2026-08-22, second session, parked on AC charge)

All 33 allowlist signals answered, none returned a sentinel, and none was
dropped by the plausibility gate — the catalog is correct for this firmware. The
head-unit allowlist then held 23 signals: tyres and cabin/outside climate were
removed from the panel, and polling them was the only reason to read them.

| Batch | Calls | Wall time on the head unit |
| --- | --- | --- |
| Shell baseline (two `date` calls, no reads) | 0 | 7–11 ms |
| One `service call` | 1 | 14–22 ms |
| Hot batch | 5 | 129–195 ms |
| Hot + cold batch | 33 | 270–287 ms |
| Hot batch, electrical page (2026-08-23) | 5 | 56–81 ms |
| Hot batch, engine page | 8 | 86–104 ms |
| Hot + cold, electrical page | 23 | 266–315 ms |
| Hot + cold, engine page | 44 | 468–587 ms |

The cost is almost all fixed: about 4–5 ms per additional call against roughly
130 ms of shell and first-process overhead. Batching is therefore what makes the
panel affordable, and widening the hot set is nearly free, while shortening the
interval is what actually costs the car.

That measurement set the `300 ms` hot and `10 s` cold cadence retained by the
cluster dashboard. A fresh power figure lands about twice a second and the shell
is busy about a third of the time, but now only while the cluster dashboard is
visible. Every due sweep includes the full hot or cold set the cluster needs;
there is no longer an electrical-page/engine-page split. Splitting the hot set
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
| Engine speed | `0x14400012` | 1012 | 5 | `0` / `0x1FFF` | rpm; `_20D` `0x20D00008` and `_GB` `0x10D00008` agree. **Two different answers on a stopped engine** — see below |
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

### A resting reading is not the same as a resting ECU

Both sweeps above were taken with the engine stopped, and engine speed answered
`0` in both. On 2026-08-25, with the dashboard live on the cluster and the car
parked longer, the same id answered **`0x1FFF`** — thirteen bits, all ones — and
the panel printed **8191 об/мин** under a stopped engine. `ENGINE_RUNNING` read
`0` throughout.

That is the CAN convention for "signal not available": a signal says it has
nothing by setting every bit of its own field. So the difference between the two
sweeps is not the engine, it is the engine ECU — awake it reports a real zero,
asleep the gateway hands back the invalid pattern.

The consequence for the allowlist is a general one. `8191` is inside any
plausible rpm range, so `VehicleKind.accepts` cannot catch it: a range gate
checks whether a number is *possible*, and this number is possible. Only the bit
pattern says otherwise, and the pattern is the width of that signal's field —
`0x1FFF` is nothing at all in thirteen bits and an ordinary number in sixteen.
`VehicleSignal` therefore carries an optional `invalid` word, checked in
`AutoserviceShell.decode` against the raw parcel word before any scale or offset.
`ENGINE_RPM` is the only entry that declares one so far. **Any id whose sweep
value was a resting `0` is owed the same suspicion**: a resting zero may only
mean the ECU was awake that day.

### Warning flags — all sixteen answer, all read `0` on a healthy car

These are the "lamp" signals: no number, just a state the cluster would light.
Every one of them answered on device `1007` unless noted, and every one read
`0`. That proves they are *readable*, not that they *change* — until one of
them trips, `0` = healthy is the constant names' claim, not a measurement.

| Lamp | FID |
| --- | --- |
| Coolant level low | `0x3D911028`, `0x3D901030`, `0x3D95D015`, and `0x05500031` on dev 1012 / 1001 |
| Coolant temperature high | `0x3D901016` |
| Motor coolant temperature over high | `0x3D91102A` |
| Oil level lamp, and its colour | `0x4A508040` / `0x4A508043` |
| Oil level low / high | `0x3D901032` / `0x3D901033` |
| Oil pressure low | `0x3D911011`, `0x3D901017`, `0x29600008`, `0x3D95D017` |
| Oil monitoring system | `0x3D911029` |
| Oil life indicator | `0x24800014` |
| Transmission oil temperature high | `0x3D90102A` |

Four separate ids for oil pressure and three for coolant level are generation
variants of the same lamp, the way the motor temperatures were. Reading all of
them and OR-ing the result is cheaper than deciding which one this car uses.

### Generation and charge power

| Reading | FID | dev | tx | Value | Note |
| --- | --- | --- | --- | --- | --- |
| Power generation value | `0x2610001F` | 1006 | 5 | `0` | engine stopped; the figure the series hybrid generates into the pack |
| Power generation state | `0x34F0000A` | 1006 | 5 | `0` | |
| AC charge power | `0x32300018` | 1009 | 7 | `2.5` | already shipped as `CHARGE_KW` |
| AC charge power, DM twin | `0x2ED0001C` | 1009 | 7 | `2.5` | agrees with the above |
| Pack power | `0x14400020` | 1012 | 5 | `-2` | negative while charging — confirms `POWER_POSITIVE_IS_DISCHARGE` |
| `ENGINE_CHARGE_POWER` | `0x2ED00010` | 1009 / 1012 | 5 | `-2` | **not** engine generation: it tracks pack power, and is the int twin of the float above |
| DC-DC work mode | `0x36D00030` | 1006 | 5 | `2` | |

Two figures that answer but do not mean what their names suggest:
`INSTRUMENT_DD_EXTERNAL_CHARGE_POWER` (`0x4A508010`) reads `1522.6` and
`INSTRUMENT_DISCHARGE_POWER` (`0x15100012`) reads `51.1` — lifetime totals or
percentages, not live kilowatts. Transmission oil temperature
(`0x34F00030`) is silent on devices 1005 and 1011.

The `155` seen on `BODY_OIL_LIFE` and its neighbours also appears on
`SETTING_POWER_GENERATION_TRANSMISSION_OIL_LIFE` (`0x38D00060`, dev 1005). Four
different service-life ids reading the same number is a shared default, not
four measurements.

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
while `ENGINE_SPEED` on `0x144` answers.

**Settled 2026-08-23 with the engine running.** Two sampling runs across full
start/stop cycles re-read every silent id at about 1.4 Hz: not one of them ever
answered. Coolant temperature, thermostat water temperature, indicated torque,
boost, per-cylinder knock, instantaneous fuel consumption and water-pump speed
stay sentinel with the engine turning. This firmware does not carry them — it is
the feature-id generation, not the engine state, and there is nothing further to
try short of a different id family surfacing.

`INSTRUMENT_WATER_TEMP_METER_PERCENT` (`0x4A509018`) held `409.5` through both
runs, engine hot or cold, which confirms it as a placeholder rather than a gauge.

### Measured start/stop cycle (2026-08-23, parked, gun disconnected)

| t | rpm | state | generation | pack power | inverter |
| --- | --- | --- | --- | --- | --- |
| 0.0 s | 0 | 0 | 0, state 0 | +1 kW | 26 °C |
| 6.7 s | 1619 | 3 | 0, state 0 | −6 kW | 26 °C |
| 7.4 s | 1572 | 3 | 8 kW, state 1 | −8 kW | 26 °C |
| 9–60 s | 1321 | 3 | 8–10 kW, state 1 | −8…−10 kW | 27 → 32 °C |
| 62.7 s | 1321 | 3 | 0, **state 2** | −8 kW | 32 °C |
| 64.8 s | 242 | 3 | 0, state 2 | +1 kW | 32 °C |
| 65.5 s | 0 | 0 | 0, state 2 | +1 kW | 32 °C |
| 68 s + | 0 | 0 | 0, state 0 | +1 kW | 32 → 28 °C |

What that pins down:

- **Revolutions are real rpm.** A 1619 start peak, a 1321 generation set-point
  held for a minute, 242 spinning down. `ENGINE_SPEED_20D` and `_GB` track it
  within about 40 rpm, so any of the three will do.
- **`ENGINE_STATE` is `0` stopped and `3` running**, including through the
  spin-down. No other value appeared.
- **Generation is in kilowatts.** It mirrored pack power exactly at every
  sample — `8` against `-8`, `10` against `-10` — which is the cross-check that
  settles the unit, and it independently re-confirms the discharge-positive sign
  convention.
- **`GENERATION_STATE` is `0` idle, `1` generating, `2` shutting down.** State
  `2` arrives a second and a half before the engine stops, with the kilowatt
  figure already at zero, so only `1` may count as generating.
- **The inverter is the one thermal reading the generation path has.** It rose
  26 → 32 °C over a minute of generation and fell back afterwards. With no
  engine coolant temperature on this firmware, the retired engine page showed
  it and the cluster dashboard still does — real feedback, not a stand-in.
- Fuel level held `53 %` and the coolant and oil-pressure lamps held `0` across
  the whole cycle.



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

This catalog listing turned up no plain "tank level, percent" constant, and an
earlier version of this paragraph concluded a fuel gauge would have to be built
out of range. **That conclusion is withdrawn.** The read-only sweep above found
`STATISTIC_FUEL_PERCENTAGE` at `0x4A507040` on device `1014`, and it answered
`53` — consistent with the `491` km the range id reported in the same sweep, and
steady across a full engine start/stop cycle. The level is a real reading; the
catalog listing was simply incomplete.

Two of them entered the historical panel allowlist as `FUEL_PERCENT` and
`FUEL_RANGE_KM`, on the cold poll and gated to the engine set. Both left the
product allowlist with the retired pages on 2026-08-27: the current cluster does
not render them, and the stock cluster already owns both facts.

**`INSTRUMENT_FUEL_LOW_ALARM` was the third and is now out.** It was read here as
the vehicle's own line for "low" — better than a threshold of ours, because a
second opinion beside the car's would put two answers on one cluster. The live
run of 2026-08-25 killed that: it answered `0` on 2026-08-23 and `1` two days
later against an unchanged `53 %` tank (`488` km of range), so whatever the
constant names, it is not this tank's alarm. On the cluster it was painting a
half-full tank in the alert colour. Nothing of ours replaces it; the stock
cluster keeps the fuel level, range and low-fuel lamp a few centimetres away.

## Consumption journal

Added 2026-08-25, on the owner's decision. Not yet run on the car.

The consumption chart used to hold twenty-four bars of 200 m - one window of
4.8 km - and it lived in the app's process, so it started empty after every
restart. The journal now keeps **300 bars of 100 m**, while the active cluster
dashboard always displays the latest **3 km**. The retired head-unit panel had
offered **3, 10 or 30 km**; its selector and saved setting were deleted on
2026-08-27, so an old installation cannot silently choose the cluster window.

**One hundred metres is one odometer tick**, which is as fine as this vehicle can
be asked; the odometer arrives in tenths of a kilometre. The log stores that one
resolution. The 30 km span is only the journal's retention horizon; the active
3 km view takes the newest thirty raw buckets, one per bar. The historical
10/30 km folding code was removed with the selector rather than preserved as a
test-only implementation.

**The journal is append-only lines, not JSON**, and that is a durability
decision rather than a taste one. A bar closes every hundred metres - three or
four seconds at highway speed - and the power goes away without warning when the
car is switched off. A JSON document must be rewritten whole to add one entry, so
an interrupted write costs the entire history; a line appended to the end costs
the tail. Format is `odometer,value`, `%.1f` and `%.3f`, in
`filesDir/consumption.log`.

**The odometer is stored with each bar**, and that is what makes a restart safe.
Time cannot say whether a journal describes the last thirty kilometres - the
entries could be five minutes old and cover a different road - but an odometer
can. On the first sweep that reports one, anything further back than the 30 km
retention horizon is dropped, and a journal whose entries sit *ahead* of the car is refused
outright: the reading went backwards, so it is not this car's journal.

**Any other kind of wrong wipes the file.** Unreadable, unparseable anywhere but
the last line, larger than the format allows, a write that throws - all end in an
empty journal, which costs at most the drive so far. A single bad *last* line is
a write the ignition interrupted and is dropped in silence. `ConsumptionJournal`
takes no Android dependency so all of this is covered by ordinary unit tests.

Writes are batched ten bars to a flush and each flush is `fsync`ed, so a sudden
power cut loses at most a kilometre. The file is bounded at 500 lines and trimmed
back to 300 through a temporary file and a rename; the slack is there so the trim
runs about once every fifty kilometres instead of on every append.

The active window is deliberately not a setting. The cluster has no touchscreen
and is now the only consumer, so it uses the fixed 3 km default every time. The
removed preference is not read and no hidden selector state survives the
head-unit panel's retirement.

### Two corrections this reading forced

- **`0x14400020` is `ENGINE_POWER` in the catalog**, and it is the id the cluster
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

## Raw CAN-FD callback (2026-09-03)

An owner-controlled local ADB shell can passively receive the raw frames already
selected by the stock `CanDataCollect` service. This is a confirmed diagnostic
path. It is not a normal-APK product API and it does not expand the product
availability claims elsewhere on this page.

### Live result

The observation ran on the Denza Z9 (`AutoType=170`) with DiLink 5.1 and this
firmware fingerprint:

```text
BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys
```

The vehicle reports `sys.car.protocol=CANFD`. There is no Linux SocketCAN
interface such as `can0` and no `candump` or `cansniffer` binary; the data path
is the vendor vehicle service rather than a SocketCAN device.

The passive probe ran as shell UID 2000, registered only for
`BIGDATA_DYNAMIC_DATA_CALLBACK` (`0x99000020`, signed `-1728053216`), and kept
the main Android `Looper` running. During 15 seconds it received 2,054 callbacks,
about 136.9 callbacks per second. It printed only the first 200 frames while
continuing to count the rest, then unregistered the listener and exited. The
temporary device-side files were removed after the run. The power-state getters
reported `ACC=1` and `MCU=1` during this observation.

The probe did not call `sendRegisterTable`, any setter, MCU wake, or service
restart. It observed the table already maintained by the active stock
`com.byd.CanDataCollect` system process.

### Callback and frame envelope

The confirmed device and event are:

| Field | Value |
| --- | --- |
| BYDAuto device | `BYDAutoBigDataDevice` (`1061`) |
| Event FID | `BIGDATA_DYNAMIC_DATA_CALLBACK` (`0x99000020`) |
| Stock receiver | `CanDataCollectService.recv_can(byte[])` |
| Delivery model | streaming callback; not a retained latest-value getter |

The observed byte envelope is:

| Bytes | Meaning |
| --- | --- |
| `0..3` | CAN identifier, big-endian |
| `4` | sub-ID |
| `5` | channel |
| `6..9` | rolling counter or timestamp, big-endian |
| `10..` | payload |

Observed payload sizes were 8, 16, 32, and 64 bytes, on channels 0, 1, and 2.
Recurring identifiers included `0x08C`, `0x223`, `0x343`, `0x12D`, `0x302`,
and `0x495`. Raw payloads are intentionally not stored in this document.
Static inspection also found that the stock service always includes `0x08C`
on channel 0, `0x223` on channel 1, and `0x343` on channel 2 at a nominal
100 ms interval. The observed callback stream includes more identifiers because
the service's active table contains its full current configuration.

### Important corrections and boundaries

- An initial run reported zero callbacks because its helper slept on Android's
  main thread. `BYDAutoDeviceManager` posts listener delivery through a
  `Handler` bound to the main `Looper`; the corrected helper called
  `Looper.loop()` and received the stream. The zero-frame result is withdrawn.
- `getBuffer(1061, 0x99000020)` returned an empty byte array. This is consistent
  with a streaming event and rules out treating the FID as a latest-frame
  getter.
- The shell process's Android `Context.checkPermission` result was denied for
  `BYDAUTO_BIGDATA_GET` and `BYDAUTO_POWER_GET`, while the native vehicle service
  accepted the listener because its own read check permits UIDs up to 9999.
  A normal installed app has a different UID range, so this result does not
  prove direct registration from Denza Apps.
- The current Denza Apps debug certificate is different from the stock
  `CanDataCollect` certificate, and Denza Apps is not system UID 1000. Direct
  app-process delivery therefore remains unproven and must not be assumed.
- The alternative BYDCross transport did not expose a general raw-CAN mirror:
  a 12-second passive observation of its vehicle-transport events received no
  frames.

`com.byd.eventcenter` is a static integration candidate, not a live-confirmed
path. The persistent system app scans component metadata named `can_msg_event`;
the value `Bigdata/BIGDATA_DYNAMIC_DATA_CALLBACK` asks it to register the
big-data listener and deliver `byd.intent.action.GET_EVENT_CENTER_MESSAGE` with
`event_center_data` containing `event_center_type=1`, `eventType`, and
`bufferDataValue`. No package-change observer was found, so a newly installed
manifest may not be noticed until EventCenter next initializes. It also invokes
the target component for every event, which needs performance validation at the
measured callback rate before any product use is considered.

### Minimal passive API shape

The working diagnostic harness followed this lifecycle:

```java
BYDAutoBigDataDevice device = BYDAutoBigDataDevice.getInstance(context);
device.registerListener(listener, new int[] { 0x99000020 });
new Handler(Looper.getMainLooper()).postDelayed(() -> {
    device.unregisterListener(listener);
    System.exit(0);
}, durationMillis);
Looper.loop();
```

The essential detail is processing the main `Looper`; sleeping on that thread
prevents callback delivery. The harness does not replace or extend the stock
collection table.

## Targeted turn-signal events (2026-09-04)

An owner-controlled, read-only `app_process` probe registered a vendor light
listener for exactly two device-`1004` FIDs:

| Semantic event | FID | Live values |
| --- | --- | --- |
| Lever/switch phase | `LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE` (`0x1330002C`) | Raw values `1..5` were observed; they are deliberately not named as intent |
| Confirmed flash mode | `LIGHT_TURN_SIGNAL_LIGHT` (`0x38A0002C`) | Live-confirmed: `1` off, `2` left, `4` right, `6` hazard; any other value remains vendor-defined raw data |

The controlled run covered neutral, left, right, manual cancellation, and
hazard. It delivered 16 events with zero callback errors. The confirmed flash
mode followed the initial switch event by 63 ms for both left and right. This is
the interval between the two vendor events, not an estimate of physical
lever-to-Binder latency.

The switch FID is retained as a raw typed phase only. Cancelling left briefly
produced `4 -> 1`, and cancelling right produced `2 -> 1`: the spring-loaded
lever crosses the opposite short position. Acting on that event could therefore
show the wrong camera. The confirmed flash event returned cleanly to `1` in
both cases.
It is authoritative for the lamps, but not for camera eligibility: the stock
`com.byd.avc` window still carries the vehicle's P/D/speed and camera policy.

A separate labelled raw-CAN capture found the same state in channel `0`, CAN ID
`0x38A`, sub-ID `0`, eight-byte payload, byte `4`: `0x15` neutral, `0x25` left,
`0x45` right, and `0x65` hazard. That capture received 21,146 callbacks with no
reported drop or sequence gap, about 137 callbacks/s; the selected raw stream
carried this particular frame only every one to two seconds. It is both noisier
and slower for this feature than the targeted semantic listener and is not a
product source.

### Product adapter and safety boundary

The product uses a process-local typed `VehicleSignalHub` with
demand leases and a dedicated `MIRROR_EVENTS`-style source lane. It starts only
while at least one module owns a lease and registers only the union of requested
signals from the closed two-FID allowlist through the already trusted passive
local-ADB identity. Mirrors requests the confirmed flash mode and transient raw
lever phase while its foreground service is active. The source
publishes typed state with monotonic timestamps, source epochs, sequence
numbers, TTL, explicit missing reasons, and bounded per-subscriber event
mailboxes. A channel heartbeat never refreshes
data TTL: after five quiet seconds the helper explicitly re-reads only the
demanded getter(s) and publishes fresh
snapshots. There is no raw-frame API, arbitrary FID API, setter, new
`BYDAUTO_*` manifest permission, or exported EventCenter component.

Passive ADB authorization failure is terminal for the current lease activation;
it cannot create a prompt or a retry loop. Transient transport failures back off
from 500 ms to 60 s. A quick initial snapshot cannot reset that history; reset
requires a healthy sampled connection lasting at least 15 seconds.

The listener is packed as a dedicated thin jar, addressed and verified by its
SHA-256 rather than by APK version or file size. The earlier 6,181-byte revision
was run passively from its exact built asset: it returned two initial `OFF`
snapshots, measured `0.0%` idle CPU and `54,738 KB` total PSS, and had accumulated
`0.49 s` of process CPU after startup. Closing its ADB stream removed the helper
immediately, and no orphan remained. The current asset is 7,449 bytes with
SHA-256
`1ca3d39b4f16f68f92404a31dfb5832ada8f3a41e6c3be94f34992a279d1195c`.
That exact asset was then live-smoked with the mode-only demand mask: it returned
an initial mode snapshot and a second getter snapshot 5.504 s later, both value
`1`, and exited without an orphan. One event lane may serve future approved
event signals; modules must not spawn one helper per signal.

The raw phase never selects a camera side. Known onset phases `2` and `4` can
only close an active Denza camera of the other side, immediately and before
stock AVC rebuilds its own surface. An onset for the side already starting or
showing is ignored, whether or not the lever passed neutral in between: the FID
emits several pulses per lever movement, and a same-side pulse can never be a
side switch. Follow-through `3`/`5`, neutral `1`, and unknown values are not
onsets. The 10 Hz stock-window observer is the only Show authority and
`MirrorTransitionReducer` the only producer of `Show`; nothing the listener
does or fails to do can open a camera, and an unavailable, reconnecting, or
overflowing source only shows up in the bounded diagnostics. Every quarantine the stock caused (a lever preempt, a closed window, a direct side
change, an ambiguous window set) ends on a clean stock window: two consecutive
polls of one unambiguous side with the Denza runtime idle, the old local surface
detached, vendor `freeDisplay` complete, and no preempt in flight. The side that
was torn down needs five such polls, because its old window outlives the switch
by 100 to 300 ms; a lever knocked toward the other side and returned therefore
gets its camera back after about half a second instead of losing the turn.
Quarantines caused by our own failures (an AVC failure, a start or session
timeout, a lost or side-changed runtime, a failed dispatch) still wait for three
neutral polls, so a broken Show cannot loop.
Ambiguous stock windows while the reducer is idle are waited through only while
the retained raw phase still reports an engaged lever (`2`..`5`). The two
earlier contracts that gated Show on the listener are kept below as history;
their live acceptance does not transfer to this contract.

### Installed adapter acceptance

The initial pre-sync debug APK with SHA-256
`0329c08b786556fa905060dc86ee1ce1fda0fb1889a904341e8ef6d3edec88df`
was installed on the same car on 2026-09-04. The staged jar matched the asset
hash above, Mirrors requested the mode-only mask, and exactly one
`denza_vehicle_signals` process remained resident.

The first controlled run held the car in P and applied left for about three
seconds, right for about three seconds, and hazard for about three seconds. The
diagnostic delta contained 60 directional-signal samples without an AVC window,
zero side mismatches, and no new unavailable samples. Hazard was not classified
as a directional request, the final state was `off`, and the camera runtime
remained idle.

The second run used stationary D with the brake held, then left and right for
about three seconds each before returning to P. Relative to the P-run snapshot,
the bounded counters advanced by 989 observations: 955 matches, two
signal-without-window samples, 32 window-without-directional-signal samples,
zero side mismatches, and zero unavailable samples. At the 10 Hz Mirrors
observation rate this is about 0.2 seconds of combined signal lead and 3.2
seconds of combined stock-window tail. The camera runtime reached generation 8
and returned to `IDLE`, `side=NONE`, `camera hidden`.

The Denza Apps main process, the one helper, and `com.byd.avc` remained alive;
the crash buffer stayed empty. The helper measured `0.0%` CPU while idle,
`62,371 KB` total PSS after the first run, and only `0.69 s` cumulative process
CPU after both runs and the diagnostic capture. This accepts installed staging,
passive event delivery, P gating, stationary-D agreement, and teardown of both
camera cycles.

After synchronizing the later chooser and spectrum work through commit
`fdb9b8272e348059c0185581b3985343cabb7626`, the combined debug APK was rebuilt
and installed with SHA-256
`ef168a36aa00a9dab4db26b52b683b2d3456a0d92b6dcfcbcaef4e64cb4ab94c`.
The installed `base.apk` matched that hash byte for byte, the staged helper
still matched the 7,449-byte asset above, and one helper remained resident. A
short final run applied left for about two seconds in P, followed by left and
right for about two seconds each in stationary D with the brake held. The final
bounded snapshot was
`samples=2107/2050/20/33/0/4`: observations, matches,
signal-without-window, window-without-directional-signal, side mismatch, and
unavailable respectively. Thus the P signal was observed without opening the
stock window, the stationary-D sides agreed with zero mismatch, and the stock
window retained its own trailing policy. AVC returned to `IDLE`, `side=NONE`,
generation 8, `camera hidden`.

No new crash-buffer entry appeared during that final ordinary shadow run. Across the
run and diagnostic capture the helper's cumulative process CPU advanced by
about `0.05 s`; its final total PSS was `53,989 KB`. This does not qualify
sleep/wake, lane-change, automatic cancellation, a safe rapid-side
implementation, R, moving speed thresholds, or another vehicle/firmware
profile. That candidate therefore remained shadow-only; the later guarded rapid
transition is qualified separately below.

### Rapid left-to-right failure capture

One later stationary-D run with the brake held deliberately switched directly
from left to right. The same installed APK produced this sequence:

- `20:04:12.024`: the 10 Hz shadow observer first saw CAN mode `left` with no
  AVC window;
- `20:04:12.149`: stock AVC started `PIP2MeterActivity`;
- `20:04:12.317`: Mirrors commanded its own left view, and at `20:04:12.505`
  CAN and the observed stock window agreed on left;
- `20:04:14.215`: stock AVC hid and destroyed its PIP surface during the side
  change;
- `20:04:14.335`: the existing `PIP2MeterActivity` received another intent;
- `20:04:14.345`: the shadow observer saw CAN mode `right` while the stock
  window still classified as left;
- `20:04:14.364`: `com.byd.avc` received `SIGSEGV`, only 29 ms after the new
  intent. The tombstone reports a null-pointer dereference at address `0x90` in
  `ANativeWindow_getWidth`, called by
  `libvideocatsdk_jni.so` `native_setSurface`, then
  `VideocatManagerImpl.setSurface`, `AvmController.setDisplaySurface`,
  `TSAPI.createDisplay`, and `PIPViewAlertController.modeChange`;
- `20:04:14.908`: ActivityManager observed the stock process death and removed
  its window; the persistent AVC process restarted as a new PID at
  `20:04:14.976`;
- `20:04:15.038`: Denza Apps, which never crashed, observed its camera runtime
  failure, entered `QUARANTINED`, hid its view, and later returned to idle. The
  shared CAN helper also remained alive.

The native fault occurred in stock AVC, not in the CAN adapter or the Denza
Apps process. The capture alone did not prove what made stock AVC enter that
surface race, so a control run changed only one condition: Mirrors was stopped
through its own service action while the rest of Denza Apps remained running.
That also stopped the demand-driven CAN helper. The same stationary-D,
brake-held, direct left-to-right manoeuvre then showed both stock camera sides
correctly. Stock AVC kept PID `17977`, produced no new `SIGSEGV` or exit-info
record, and completed normal PIP teardown. Mirrors was then restarted; its
service returned and exactly one new helper process became resident.

This paired result is strong evidence that the concurrently active Denza
Mirrors AVC display surface is a necessary condition for the reproduced stock
native surface race. One successful control is not enough to identify the
precise ordering bug or to prove that it can never occur without Mirrors. CAN
cannot repair the native AVC code or prevent stock AVC from receiving the
right-turn event. It may nevertheless let Mirrors release its own surface
*before* stock AVC rebuilds the opposite-side surface. Such a guard must react
to an actual switch edge rather than a 10 Hz snapshot, remain side-agnostic for
the raw switch phases, and keep stock AVC as the eligibility authority.
Directional mismatch remains useful as a fail-safe veto for a stale old-side
view, but the captured 29 ms post-intent crash window makes that late check
unlikely to prevent the native failure by itself.

Showing or retrying a camera without a stock window would additionally require
a separately proven eligibility policy (at minimum gear and speed, or a short
lease on recently confirmed AVC eligibility), so these runs do not justify
making CAN the camera owner.

### Guarded rapid-transition acceptance

The first active-guard canary APK, SHA-256
`00707767053650eace85b786220e66fc2ac6c0cc21bdcca13092d45e8d659197`,
was installed byte-for-byte on the same car. In stationary D with the brake
held, direct left-to-right showed the Denza left camera, then deliberately left
the right side to stock AVC for the rest of that turn. Stock AVC retained PID
`17977` and produced no new crash or exit-info record. This accepted early
preemption but also exposed that the general quarantine policy prevented a safe
same-cycle Denza reopen.

The narrowed recovery candidate, SHA-256
`0588de7d41f4059c25a6eb17e12c38e7ceffceff7ccf8a2e85c76826113e7864`,
was built from local `main` `75ded26f53195f111d41e17731cb04feffaa48db`,
passed 1,084 unit tests and Android lint, and was installed byte-for-byte. Its
single stationary-D, brake-held left-to-right canary produced this ordering:

- `20:51:44.108`: raw onset was observed; preemption was accepted 2 ms later;
- `20:51:44.112`: the old Denza local surface was detached, 4 ms after the edge;
- `20:51:44.224`: vendor release completed in 103 ms, and the preemption callback
  completed at `20:51:44.229`, 121 ms after the edge;
- `20:51:44.485`: stock AVC began creating the right display;
- `20:51:44.810`: confirmed right mode and the observed right stock window
  matched;
- `20:51:45.078`: after the stable-match gate, Denza issued exactly one
  `show RIGHT`; it reached `READY` at `20:51:45.371`.

The Denza right command was therefore issued 970 ms after the raw edge, 849 ms
after vendor teardown completion, and 268 ms after the first logged matching
right window. The user saw both Denza sides correctly and noticed the intended
short handoff pause. Turning the signal off detached the right surface in 3 ms,
completed vendor teardown in 120 ms, and returned through neutral to idle.

Stock AVC kept PID `17977`; Denza Apps and exactly one
`denza_vehicle_signals` helper also remained alive. No new AVC `SIGSEGV` or
exit-info entry appeared; the newest recorded AVC failure remained the earlier
`20:04:14` reproduction. This accepts one instrumented stationary left-to-right
guarded transition on this car/firmware. It does not yet qualify right-to-left,
lane-change or automatic cancellation, hazard during a session, sleep/wake,
moving speed thresholds, repeated stress, or another vehicle/firmware profile.

### Separated-cycle ambiguity regression

The later byte-matched APK
`e4b26fadc28082baa97c306ce429ee8ffdd640f455a6086df29fbb004fc1fae6`
exposed a separate fail-closed false negative during an ordinary
left-off-pause-right sequence. The left Denza view closed normally and the
reducer returned to `IDLE` at `20:58:24.837`. Stock AVC began its right display
at `20:58:24.992`; 40 ms later the monitor saw confirmed mode `right` but a
short-lived partial/overlapping stock-window set, classified it as ambiguous,
and moved `IDLE` to neutral-only quarantine. The stock window was unambiguously
right by `20:58:25.486`, but the quarantine correctly refused a late Show. The
user therefore saw only the stock right camera. No process crashed.

That transition is not itself authority to open a camera. The corrected reducer
only keeps waiting when all of these are true: a live raw switch edge is pending,
no side is yet eligible, the Denza camera runtime is idle, and the ambiguity is
in the stock-window observation. It issues no command in that state. Ambiguity
without a pending edge, or while a Denza runtime is active, still quarantines;
the eventual Show still requires the same-epoch higher-sequence confirmed mode
and the first exact matching window observation.

The corrected APK
`baa2635f32e030cddf4b8310a98559aeeaf6ae9abbca1af84e2b58675140b8e9`
passed 1,087 unit tests and Android lint and was installed byte-for-byte. One
stationary-D, brake-held left-off-two-second-pause-right run showed both Denza
cameras correctly. The left vendor release took 121 ms and the reducer returned
to neutral `IDLE` before the next turn. On right, signal and stock window first
matched at `21:03:01.767`; Denza issued exactly one `show RIGHT` 269 ms later and
reached `READY` at `21:03:02.385`. Stock AVC retained PID `17977`, and Denza Apps
and its one helper remained alive with no new crash or exit-info entry.

The transient ambiguous-window state did not recur in that successful final
run. Thus the separated-cycle behavior is live-accepted, while the exact new
ambiguity-wait branch has deterministic unit coverage but still lacks a second
live occurrence. This distinction must be preserved in future acceptance claims.

The post-acceptance hardening also reads the adapter's already-retained raw phase
inside the poll transition, so a short executor delay between hub publication and
the event callback cannot turn an active phase `2`..`5` into a false neutral-only
quarantine. This is an in-memory snapshot read and adds no vehicle request. A
pending onset is discarded fail-closed after 2 seconds (or an elapsed-clock
rollback), preventing a lost follow-up event from leaving recovery pending
indefinitely. These two paths are deterministically unit-tested; they were added
after the live run above and therefore are not claimed as separately live-proven.

The resulting hardening APK, SHA-256
`97a56057989788eb2a53a95e23e50f6cf1ddbf713d5539b64a8affa3691d6e04`,
passed 1,090 unit tests with zero failures/errors and Android lint. It was
installed byte-for-byte after the user rebooted the car; package-replacement
recovery restored Denza Apps, Mirrors, and exactly one signal helper. Stock AVC
remained alive as PID `5050`, and exit-info still ended at the deliberate
`20:04:14` failure. No physical turn cycle was run after this last hardening
build, so the exact hash is install-verified but not separately manoeuvre-tested.

### Same-side onset regression and final live acceptance

The first ordinary right turn with the hardening build exposed a deterministic
regression rather than an AVC or window race. Denza issued `show RIGHT` at
`21:30:51.969`; while the camera was still `STARTING`, a trailing raw phase `4`
arrived at `21:30:52.262`. The preemption path treated it as a new switch,
detached the just-created surface, and ignored the now-stale AVC-ready callback.
The user therefore saw only a momentary canvas. This proves that phase `4` can
also be part of the ongoing right-lever movement; phase `2` has the symmetric
meaning for an already active left side.

The corrected policy compares an onset with the side already `STARTING` or
`READY` and with the gesture that started that camera. A same-side onset keeps
the camera only until raw neutral or confirmed mode `OFF`; after either boundary
it is a new gesture and preempts the old surface. An opposite-side onset always
preempts immediately. It does not use the raw phase to choose a camera and does
not weaken the later confirmed-mode and stock-window Show gate. The
three-sample/200 ms window delay was also removed: once a post-onset confirmed
mode exists, the first exact matching stock-window observation is sufficient.
The old-surface detach and completed vendor `freeDisplay` barriers remain.

The live-tested intermediate APK, SHA-256
`4811ec32c8a8cf1ad5fa66b96482951ef03a7b278fa443e360c71dfc6b5e5268`,
passed 1,092 unit tests with zero failures/errors and Android lint and was
installed byte-for-byte. The user visually accepted three ordinary right turns.
The latter two reproduced a same-side phase `4` 206 ms and 262 ms after their
Show commands; both were ignored as intended, and AVC became ready after 257 ms
and 273 ms. The first cycle reached AVC ready in 281 ms.

One final stationary, brake-held direct left-to-right canary retained opposite-
side preemption: the left local surface detached 3 ms after right onset, vendor
release completed in 105 ms, and the right camera subsequently reached AVC ready
in 244 ms. The user saw both Denza cameras correctly. Stock AVC retained PID
`5050`, while Denza Apps and exactly one `denza_vehicle_signals` helper remained
alive. This accepts the corrected same-side handling and one direct
left-to-right handoff on this car/firmware; it does not broaden acceptance to
the other untested matrix entries listed above.

Post-acceptance hardening records the gesture boundary explicitly. Raw neutral
or a confirmed `OFF` observed after the camera Show prevents a subsequent
same-side onset from being mistaken for the earlier trailing duplicate; a
delayed neutral event timestamped before that Show cannot close the new gesture.
The resulting APK, SHA-256
`7462350e1a5588c1ffe6a36685a03f869ec947ec1e5ac718c498769424e98aea`,
passed 1,095 unit tests with zero failures/errors and Android lint. This final
boundary logic is deterministic-test accepted but was not reinstalled or
physically exercised before commit; the live acceptance above belongs exactly
to the preceding byte-matched APK.

### Window-only Show, onset-only teardown (2026-09-04, late)

An independent review of the gated contracts against a passive filtered
`logcat -s` capture of two ordinary right cycles found that the randomness the
owner reported was designed in. (The main ring buffer holds under a second of
history on this car because of camera-HAL spam, so a filtered live capture is
the only way to keep a run.) The raw lever FID emits several pulses per
movement: `4` at 0 ms, `5` at about +100 ms, and a second `4 -> 1` pair at
about +300 ms; the stock right window was observed 186 ms and 194 ms after the
first pulse, and in the 20:04 run above the left view was commanded about
290 ms after the mode was first observed. With Show gated on a confirmed mode
event that had to be newer than the latest onset, a second same-side pulse that
landed before the window re-armed the pending switch after the only mode event
had already passed; the 2 s timeout then failed closed, and the turn stayed
dark until the next `OFF` event. Left turns were close to a coin toss on those
timings. The same fail-closed reset ran on every hub `Unavailable` notice (each
helper reconnect, sequence gap, or mailbox overflow, with reconnect backoff up
to 60 s), so a transport hiccup could hide an active camera and keep it hidden
for the rest of the turn. The gesture-boundary rule of the last gated build
re-created the same dark turn for a same-side pulse after the lever had passed
neutral.

The replacement contract is the one described under "Product adapter and safety
boundary" above: the window observer alone opens cameras, the onset alone
closes them, and reopening after a preempt waits for the other side's window.
`MirrorSignalSafety` and the gesture-boundary state were deleted. The reducer
and the preemption policy are unit-tested with a mutation ledger. The live
acceptance in the sections above belongs to the retired gated builds; the
early-teardown timings (3 to 4 ms detach, 105 to 121 ms vendor release) were
the only part carried over unchanged, and the first drive below confirmed them
on this contract.

#### First drive of the window-only contract (2026-09-04, 22:13 to 22:20)

Build 42 (`bf5f06cb…`) went on the car at 22:13:27 with the owner watching and a
filtered `logcat -s` capture running. Between 22:17:05 and 22:17:14 the owner
switched right-to-left, left-to-right and right-to-left again in quick
succession: each opposite onset detached the local surface 3 to 4 ms after the
pulse, vendor `freeDisplay` completed in 109 to 118 ms, the new side opened at
the second clean poll of its window (about 125 ms after the window appeared),
two same-side pulses were ignored while that side was showing, `com.byd.avc`
kept its PID and the crash buffer stayed empty. That is the left-to-right AVC
canary the earlier sections had left open, and it passed on this contract. A
three-blink comfort tap at 22:14 showed both cameras for the three blinks and
closed them 0.6 s after the stock closed its window, which is the stock's own
behavior.

One dark signal remained. At 22:17:16 the mode went off, at 22:17:19.2 the
stock closed the left window and the reducer hid the camera, and 125 ms later
the owner engaged left again; the stock left window was back after two empty
polls. The quarantine that follows a closed window only knew how to wait for
three neutral polls, so the Denza camera stayed dark for that whole 2.3 s
signal while the stock camera showed. The recovery rule above is the fix: a
stock-caused quarantine now ends on a clean stock window, and only our own
failures still wait for neutral. The adversarial review of the same build found
two more holes that the same change closes: the preempted side was blocked
permanently rather than for the stale window's lifetime (a lever knocked toward
the other side and returned lost the turn), and the status publication that
refreshes the whole app repository ran under the gate that the onset needs to
detach a surface, so it now runs after the gate is released. Two review items
stay open for lack of evidence: the ambiguity rule never fired tonight (none of
about thirty transitions), and the reopen counter still demands adjacent clean
polls. The revised build has not been driven yet.

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
| Technical BMS / HV cluster dashboard | `autoservice` allowlist via `DenzaLocalAdb` | Cluster-only subset in `feature.vehicle`; shell-only, short allowlist, no `BYDAUTO_*` in the manifest. The old head-unit page and its 12V reading were retired 2026-08-27 |
| Tyre / climate / PM2.5 | same Binder, different `dev` | Same privilege path; still blocked from app UID |
| Raw CAN diagnostics | `BYDAutoBigDataDevice` callback `0x99000020` from local ADB shell | Confirmed passive stream; not proven from the Denza Apps UID and not yet a product input |
| Turn-signal guard | targeted shell-UID BYDAutoLight listener | Event-driven and low-CPU; a raw onset only tears down an active opposite-side Denza surface; the stock AVC window alone opens and reopens |

The current road-thread/body-field prototypes use simulated values. They show a
candidate visual mapping only; they are not live-car evidence.

## Commands and inspected inputs

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

Vendor inputs inspected locally and intentionally not committed:

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

GNSS/IMU research (unchanged from 2026-07-24; use an isolated probe rather than
restoring this pipeline to the product):

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
   is the only thing that moves it. The cluster dashboard shows pack voltage as context, not as a
   gauge, until that sag figure exists.
5. Cross-check the odometer against the trip panel's GNSS distance over the same
   stretch; the consumption histogram's axis rests on the ×10 scale, and the
   stock home widget's 23.2 kWh/100 km over 50 km is the number it should land
   near.
6. ~~Record `sweepMillis`~~ **done**: the hot batch costs 129–195 ms and the full
   sweep 270–287 ms. The hot interval was set to 300 ms on that evidence; what
   remains is to confirm on a drive that the car keeps up with it under load.

Raw CAN-FD diagnostics (2026-09-03):

1. Keep using the stock `CanDataCollect` selection table; do not replace or
   extend it for observation-only work.
2. Add host-side capture timestamps, CAN-ID/channel filters, and loss counters
   before attempting a longer moving observation.
3. Correlate one known vehicle action at a time with one filtered identifier;
   keep unfiltered payloads out of repository documents.
4. If product integration is pursued, measure EventCenter delivery overhead at
   the observed callback rate before deciding whether that static candidate is
   viable.

Until the drive capture, the honest **app-UID** boundary remains GNSS plus
standard IMU plus fail-closed Yandex guidance. The honest **shell-UID**
boundary is the widget allowlist plus the passive raw CAN-FD callback above.
