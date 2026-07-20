# Vehicle Event Probe Archive

This archived probe explored BYD vehicle events from a normal app. It stays
outside the Android source tree and the frozen `denza-mirrors.apk` build.

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
