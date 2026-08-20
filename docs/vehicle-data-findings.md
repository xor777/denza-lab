# Vehicle Data Availability Findings

Status: live-car availability investigation on 2026-07-24, with the earlier
vehicle-event probe results from 2026-06-27 retained where relevant and current
product wiring checked against the code on 2026-08-20.

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

The following must not be treated as product-available today:

- battery state of charge, energy flow, charging state, or electric range;
- accelerator/brake position, steering angle, or gear;
- tire pressure or tire temperature;
- cabin PM2.5, CO2, or climate values;
- raw BYD event traffic seen in system `logcat`;
- regeneration power inferred from the unsigned/raw trip arrays.

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

The temporary probe was uninstalled after the run. Denza Apps was not modified.
No `com.byd.avc` crash was observed during these read-only checks.

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
| High-level DiCar Binder APIs | battery, energy flow, range, charging, pedals, steering, tires, air quality | getter surface exists; useful calls blocked | signature/privileged BYD permissions | Blocked | Not a product source |
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

The conclusion is fail-closed: none of these blocked values should appear in a
Denza Apps design unless a later normal-product APK test proves a supported
permission path.

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
| Regeneration/energy display | no qualified source | Do not implement |
| Tire/cabin/charging display | protected DiCar getters | Do not implement |

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
```

Reverse inputs inspected locally and intentionally not committed:

```text
/system/priv-app/DiCarServer/DiCarServer.apk
/system/priv-app/BydClusterApp/BydClusterApp.apk
/system/priv-app/CarStatusProvider/CarStatusProvider.apk
```

The first shell-only `app_process` harness was killed and was abandoned. An
initial no-display Activity harness then failed because Android requires a
`Theme.NoDisplay` Activity to finish before resume. A transparent temporary
Activity corrected the harness and produced the normal-UID permission matrix
above. These were probe-process failures, not vehicle-process failures.

## Next useful validation

Before product code is added:

1. Build an isolated normal-APK recorder for standard accelerometer, gyroscope,
   gravity/linear acceleration, and GNSS.
2. Record a short normal drive with the head unit untouched during motion.
3. Calibrate longitudinal/lateral/vertical axes and quantify stationary noise.
4. Correlate turns, braking, elevation changes, and road impulses with the
   recorded trace.
5. Profile a 30 Hz sensor loop and 30 FPS renderer for CPU, frame time, and
   thermal impact.
6. Separately register the vendor SCP sensors and document whether a normal APK
   receives and can interpret their payload.
7. Observe `car_status` changes during the same drive without writing to the
   provider; identify the producer and units before using any trip array.

Until those checks pass, the honest product boundary is GNSS plus standard
Android IMU data, with existing fail-closed navigation guidance where
applicable.
