# Stock Shortcuts automation and the map-role bridge

What the BYD "Shortcuts" automation engine can and cannot do for a third-party
app, and which switch actually selects the car's map application.

Observed live on 2026-08-16 over ADB (`shell` UID, no root), plus static reading
of `BydAutoVoice.apk` (`v15.3.149.11.260512.7`).

## Where the feature lives

"Shortcuts" is `com.byd.autovoice/.DiyCommandActivity`, a second launcher entry
of the voice assistant package (`/system/byd/devices/users/app/BydAutoVoice`,
`android.uid.system`). It is not a separate app. `com.byd.scenemodes` is the
unrelated scene/privacy platform.

The editor model is `If <trigger> → Then execute <command>`, plus `Cycle Mode`
and `Repeat` settings, with `Save` and `Test Run`.

## Trigger catalog (rich)

| Category | Options |
| --- | --- |
| Voice Trigger | custom spoken phrase ("Speak to the voice") |
| Doors and Windows | 4 doors, 4 windows, sunshade |
| Child lock | — |
| Seat Belt | — |
| Wiper | — |
| Environment | outside temperature, UV, charger/discharger |
| Vehicle status | battery SOC, fuel/electric/total range, power-on state, vehicle speed (above/equal/below), gear |
| Timer | timer, time range, location arrive/leave (geofence) |

## Execution catalog (closed)

Delay, Voice Reply, Doors and Windows, Seats, Steering wheel, Fragrance, Light,
Drive, A/C, Screen Display, Volume, Bluetooth, Network Connection, and **Apps**:
`Desktop`, `Music`, `Navi`, `Video`, `Navigation Customization`,
`Mic-free Karaoke`.

There is no "pick an installed app" entry. The list is fixed; an arbitrary
package cannot be added through the UI.

`Navi` expands to `Access map`, `Close Map`, `Navigate to Company`,
`Navigate Home`. `Navigation Customization` is a preset destination address plus
route preferences, not an app chooser.

## No supported third-party registration

Every BYD automation hook is signature-gated, so a normally-signed APK cannot
join the system:

| Hook | Protection |
| --- | --- |
| `com.byd.autovoice.permission.thirdapp` | `signature\|privileged` |
| `com.byd.autoiot.service.BROADCAST_PERMISSION` | `signature` |
| `android.permission.BYDAUTO_*` (from `com.byd.auto.permission`) | `signature` |

`dumpsys car_service` does not exist — this is not Android Automotive, so the
`android.car` property API is not an alternative source of vehicle signals.

`com.byd.maphelper` (6 MB, `/system/byd/devices/users/app/MapHelper`) is **not**
an integration point. It only toggles `com.byd.rsemap`/`com.byd.navigatormap`
and `com.byd.fsemap`/`com.byd.deputymap` via `setApplicationEnabledSetting`,
switching Baidu vs Gaode on the rear and passenger screens according to the
`VEHICLE_CONFIG_ITEM_MAP_TYPE` config word.

## Two independent map-role switches

There is no single "default navigation app". Two switches exist and different
consumers read different ones.

| Switch | Storage | Read by |
| --- | --- | --- |
| `DEFAULT_MAP_SWITCH` | `PersonBean` row in `com.byd.autovoice` | voice assistant, Shortcuts `Navi` commands |
| `byd_map_package` | `Settings.Global` | `CustomKey`, for the steering-wheel navigation key |

Replacing navigation everywhere therefore means setting both.

### `DEFAULT_MAP_SWITCH` — the voice and Shortcuts path

`MapUtils.getBydMap()` does **not** read `Settings.Global`. It returns a fixed
choice by device type, falling back to the literal `"com.byd.launchermap"`.

The selectable value lives in `MapUtils.getCurDefaultSelectMap()`, backed by
`PersonDaoManger.z0 = "DEFAULT_MAP_SWITCH"` in the GreenDAO `PersonBean` table:

```java
String strG = PersonDaoManger.getInstance().g(PersonDaoManger.z0, getBydMap());
if (!TextUtils.isEmpty(strG) && AppMangerUtils.getInstance().f(strG)) {
    return strG;          // only validation is "is this package installed"
}
```

The stored value is **not** checked against the candidate list. That list
(`MapUtils.b` — BYD maps plus `com.autonavi.*` and `com.baidu.*`) is only the
fallback scan used when nothing is stored, and contains no Yandex, Waze or 2GIS.

That table is reachable from `shell` through an exported provider, which ignores
the path segment:

```bash
adb shell content query --uri content://com.byd.autovoice/PersonBean --where "SETTING='DEFAULT_MAP_SWITCH'"
# Row: 0 _id=43, SETTING=DEFAULT_MAP_SWITCH, VALUE=com.byd.launchermap
```

Reading was proven during the initial inspection. The later bounded spike below
also proved that shell UID can write this row through `content update` and
restore the original value; third-party destination handoff remains a separate
unproven question.

### The launch target is resolved, not hard-coded

A `Test Run` of `Navi → Access map` produced:

```
MapToFunction_sunny: updateData ... commandId=102000, curMapPackage=com.byd.launchermap
ActivityTaskManager: START u0 {flg=0x10000000
  cmp=com.byd.launchermap/com.byd.automap.activity.EmptyJumpActivity (has extras)}
```

`com.byd.automap.activity.EmptyJumpActivity` is the `LAUNCHER` activity of
`com.byd.launchermap`, and `NaviManager.y()` builds the intent generically:

```java
Intent launchIntentForPackage = context.getPackageManager().getLaunchIntentForPackage(str);
if (launchIntentForPackage == null) return false;
...
launchIntentForPackage.addFlags(268435456);   // 0x10000000, matches the live log
```

Neither `EmptyJump` nor `automap.activity` appears as a string in any of the 11
dex files of `BydAutoVoice.apk`, while `getLaunchIntentForPackage` does. The
component is therefore resolved from the target package's own launcher entry — a
third-party navigator does not need to impersonate a BYD class name.

A map that is not the BYD map takes a different branch in `NaviManager.x()`:

```java
if (!str.equalsIgnoreCase(MapUtils.getBydMap())) {
    AppMangerUtils.getInstance().Y(this.a, str);   // third-party launch path
    return;
}
```

`AppMangerUtils.Y` lives in a dex that has not been decompiled yet, so whether a
destination is carried into a third-party navigator or the app is merely opened
is **unproven**.

## Spike before integrating

[tools/navi_role_probe.sh](../tools/navi_role_probe.sh) sets both switches to a
target package, waits for a manual navigation trigger, reports the component the
system actually started, and restores both originals on exit — including on
error or Ctrl-C.

```bash
ADB_SERIAL=127.0.0.1:15555 tools/navi_role_probe.sh ru.yandex.yandexnavi
```

It answers the three open questions in one run: whether `content update` is
permitted, whether the third-party branch launches the target, and whether a
destination arrives with it.

`content update` on `DEFAULT_MAP_SWITCH` and `settings put global byd_map_package`
are both **proven writable from `shell`** (2026-08-16); the restore path was
verified to put both values back.

Nothing may be added to `:denza-apps` until the launch behaviour is read.

### Never inject key code 321 as a navigation trigger

Key code `321` is the configurable steering-wheel custom key, not a navigation
key. On this car `CustomKeyHandler` logs
`key config(Double key: 3; Single key: 1,2): 1` and takes action `1`:

```
CustomKeyHandler: apaValue: 1 (1: APA_SUPPORT), hasParking: true
CustomKeyHandler: sendParkingBroadcast
ActivityTaskManager: START u0 {cmp=com.byd.avc/.AutoVideoActivity}
```

That opens `com.byd.avc` and starts an APA (auto parking assist) scan — a
physical vehicle function. Injected once on 2026-08-16; the parking module
reported `Obj=0, Slot=0, Track=0` (scan only, no slot, no maneuver) and
`com.byd.avc` did not crash. Do not repeat it.

The wheel-key action is per-car configuration, so action `7` (the map action
recorded in [instrument-display-findings.md](instrument-display-findings.md)) is
not what this car does. `byd_map_package` therefore has no wheel-key consumer
here, and `DEFAULT_MAP_SWITCH` is the switch that matters.

## Cost to weigh before taking the role

The map role is shared by the steering-wheel navigation key, voice "navigate
to", the Shortcuts `Navi` commands, and the cluster Map mode. The stock map is
China-only and this car stays in RF, so the role has little to lose — but
`StockClusterModeDetector` detects stock Map mode by a visible
`com.byd.launchermap/com.byd.automap.meter.MeterActivity` task, and that
detection needs re-checking if the role moves.

See also [instrument-display-findings.md](instrument-display-findings.md), which
records `byd_map_package` and the package-scoped
`CUSTOM_NAVI_STANDARD_BROADCAST_RECV` broadcast for the wheel key.
