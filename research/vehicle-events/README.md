# Vehicle Event Probe Archive

This archived probe explored BYD vehicle events from a normal app. It stays
outside the Android source tree and the frozen `denza-mirrors.apk` build.

The current consolidated availability matrix, including GNSS/IMU frequencies,
the exported car-status provider, and the high-level DiCar permission probe,
lives in
[`docs/vehicle-data-findings.md`](../../docs/vehicle-data-findings.md).

## Last Findings

- Tested on the car on 2026-06-27.
- `BYDAUTO_*_COMMON` permissions were granted to the debug app.
- `BYDAUTO_*_GET` permissions were not granted.
- Legacy BYD listeners could be registered with `registerListener(IBYDAutoListener, int[])`, but no app-level callbacks arrived for turn-signal tests.
- Direct getters for `setting`, `bodywork`, `light`, `gearbox`, `instrument`, and others failed with `SecurityException`.
- System `logcat` did show vehicle/camera activity during turn-signal tests, including:
  - `BYDAutoSettingDevice postEvent event_type=28600009 value=0/1`
  - `BYDAutoBodyworkDevice postEvent ...`
  - active camera frames for camera ids `2`, `3`, and `10`
  - `com.byd.sr/.cluster.ClusterActivity` rendering on the dashboard
- The SR/camera-window monitor remains the working product trigger. These
  app-level listeners produced no usable turn-signal callbacks.
- 2026-07-24 logcat capture falsified `28600009` as a stalk-edge trigger: it is
  a continuous broadcast (~25–45 events/s, value toggling 0/1) that keeps
  firing while parked with both turn signals off.
- 2026-07-24 wide capture (all `postEvent` device types) mined around six
  live signal edges found no stalk-correlated event at all. Every near-edge
  type was periodic telemetry (bodywork accelerometer flood, PM2.5 device
  `1008/4f60001x` every 2 s) or uncorrelated toggles (`1023/2ec00020`).
  Turn-signal state does not transit any logcat-visible `postEvent` channel;
  the early-vehicle-event trigger idea is falsified end to end. The only
  remaining in-architecture fast-switch candidate is an accessibility
  window-push trigger (~10–20 ms) against the measured 95–174 ms crash budget.

> **Superseded 2026-09-23:** this holds for app-level listeners and logcat
> `postEvent`, not for the car's signals. Received under the shell UID
> (`TargetedBydLightEventProxyMain`, run by `app_process` over local ADB), the
> lamps FID `0x38A0002C` and the raw lever FID `0x1330002C` now drive Mirrors:
> show with AVC's card and the lamps, teardown on a lever onset toward the other
> side. The window monitor is no longer the trigger — see
> [`docs/instrument-display-findings.md`](../../docs/instrument-display-findings.md#the-firmware-model-contract-2026-09-23).

## If Research Resumes

Copy `VehicleEventProbeService.java` back into:

```text
legacy/denza-mirrors/src/main/java/dev/denza/mirrors/probe/VehicleEventProbeService.java
```

Then add the service and experimental permissions back to
`legacy/denza-mirrors/src/main/AndroidManifest.xml`.

The probe action names were:

```text
dev.denza.mirrors.START_VEHICLE_EVENT_PROBE
dev.denza.mirrors.STOP_VEHICLE_EVENT_PROBE
```

The app data files were:

```text
vehicle_event_probe_status.txt
vehicle_event_probe.log
```
