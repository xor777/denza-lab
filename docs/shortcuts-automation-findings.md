# Stock Shortcuts automation and the map-role bridge

What the BYD "Shortcuts" automation engine can and cannot do for a third-party
app, how Then-actions actually start activities, and which switches can point
those actions at Denza Apps.

Observed live on 2026-08-16 and 2026-08-22 over ADB (`shell` UID, no root), plus
static reading of `BydAutoVoice.apk` (`v15.3.149.11.260512.7`, 11 dex files).
The 2026-08-22 pass decompiled the remaining automation dexes
(`AppMangerUtils`, `NaviManager`, `MediaApi`, `DiyChoiceScence5_1`,
`IOTProvider`, `HandleDefaultAppUtils`) and took read-only dumps of PersonBean
and installed role packages. A later bounded write the same day pointed
`DEFAULT_MAP_SWITCH` at `ru.yandex.yandexnavi`; a live Shortcuts Test Run of
`102000` opened Yandex Navigator. The row was restored to
`com.byd.launchermap` and the restore was read back. `byd_map_package` was
not changed.

## Where the feature lives

"Shortcuts" is `com.byd.autovoice/.DiyCommandActivity`, a second launcher entry
of the voice assistant package (`/system/byd/devices/users/app/BydAutoVoice`,
`android.uid.system`). It is not a separate app. `com.byd.scenemodes` is the
unrelated scene/privacy platform.

The editor model is `If <trigger> → Then execute <command>`, plus `Cycle Mode`
and `Repeat` settings, with `Save` and `Test Run`. Persistence is a GreenDAO
`DiyCommandBean` (`conditions` / `actions` as `QicAtom` lists). The exported
`content://com.byd.autovoice` provider does **not** expose that table: every
path segment still reads `PERSON_BEAN`.

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

## Execution catalog (closed in the UI)

The Then picker is compiled into `DiyChoiceScence5_1.initLevel1()`, not a
query of installed packages. An arbitrary package cannot be added through the
UI.

DiLink 5.1 Then groups and the command IDs that matter for launching something:

| UI group | Then item | Command ID | What it actually starts |
| --- | --- | --- | --- |
| 应用管理 | 打开应用 | `101000` | Label match among installed LAUNCHER apps. Catalog is five BYD names only: 智能语音助理, 百变主题, 文件管理, 用户手册, 应用市场 |
| 应用管理 | 关闭应用 | `101001` | Same closed name list |
| 媒体 | 音乐 → 打开 | `104451` | `MediaApi.getDefaultMusicPkg()`, then generic launch unless the package is `com.byd.mediacenter` |
| 媒体 | 视频 → 打开 | `131501` | `MediaApi.getDefaultVideoPkg()` |
| 导航 | 地图 → 打开 | `102000` | `MapUtils.getCurDefaultSelectMap()`. BYD map uses the automap API; any other installed package uses `AppMangerUtils.Y` |
| 导航 | 地图 → 关闭 | `102001` | Close map |
| 导航 | 导航 → 家 / 公司 | `102003` / `102004` | Destination handoff into the current map role |

The 2026-08-16 English UI names (`Desktop`, `Music`, `Navi`, `Video`,
`Navigation Customization`, `Mic-free Karaoke`) are the same engine with
localized labels. On this 5.1 catalog there is no Home/Desktop item and no
Karaoke Then-action; 全民K歌 appears only in the older `DiyChoiceScence_R123`
list.

`101000` is not an app picker. The names are literals in
`DiyChoiceScence5_1.CarNav()`. The executor, however, resolves the name with
a contains-match over every installed app's launcher label
(`AppMangerBaseUtils.j` / `y`). Only the editor is closed.

## How Then-actions start an activity

All three useful launch paths end in `PackageManager.getLaunchIntentForPackage`
plus `FLAG_ACTIVITY_NEW_TASK` (`0x10000000`). The target does not need a BYD
class name. On this car Denza Apps' launcher is
`dev.denza.apps/.DenzaLauncherActivity` (label `Denza Apps`, version `0.5.4`
as installed). Extra `FROM=com.byd.autovoice` is added on the music path only
and can be ignored.

### `102000` — open map (best Shortcuts hook)

`NaviManager.x(package)`:

```java
if (!str.equalsIgnoreCase(MapUtils.getBydMap())) {
    AppMangerUtils.getInstance().Y(this.a, str);   // any other installed package
    return;
}
// else BydAutoMapImpl / EmptyJumpActivity
```

`AppMangerBaseUtils.Y` (now decompiled from classes8) is only:

```java
Intent launchIntentForPackage = context.getPackageManager().getLaunchIntentForPackage(str);
if (launchIntentForPackage != null) {
    launchIntentForPackage.addFlags(268435456);
    context.startActivity(launchIntentForPackage);
}
```

No destination extra, no Gaode/Baidu protocol. A third-party map role **opens
the app**. Home/Company (`102003`/`102004`) remaining a destination-handoff
question is unchanged and is not required to launch a third-party package.

A `Test Run` of `Navi → Access map` while the role was still BYD (2026-08-16):

```
MapToFunction_sunny: updateData ... commandId=102000, curMapPackage=com.byd.launchermap
ActivityTaskManager: START u0 {flg=0x10000000
  cmp=com.byd.launchermap/com.byd.automap.activity.EmptyJumpActivity (has extras)}
```

On 2026-08-22 the same Then, after

```bash
adb shell content update --uri content://com.byd.autovoice/PersonBean \
  --bind VALUE:s:ru.yandex.yandexnavi --where "SETTING='DEFAULT_MAP_SWITCH'"
```

opened Yandex Navigator (`ru.yandex.yandexnavi` is installed; operator
confirmed the live start). `byd_map_package` stayed `com.byd.launchermap`.
The PersonBean row was restored to `com.byd.launchermap` and read back.
That is live proof of `NaviManager.x` → `AppMangerUtils.Y` →
`getLaunchIntentForPackage` for a non-BYD package. `dev.denza.apps` was
not the target of that run; it uses the same branch.

### `104451` — open music

`MediaStart.i()`:

```java
String defaultMusicPkg = MediaApi.getDefaultMusicPkg();
if ("com.byd.mediacenter".equals(defaultMusicPkg)) {
    MediaFactory.d().b().E0(false, 1001, -1);     // mix-module, not a generic launch
} else {
    AppMangerUtils.getInstance().U(defaultMusicPkg); // same getLaunchIntentForPackage
}
```

`MediaApi.getDefaultMusicPkg()` is `PersonBean.MUSIC_SWITCH` with fallback
`com.byd.mediacenter`. Validation is only "is this package installed"
(`MediaApi.e` → `AppMangerUtils.f`). No Chinese-app whitelist on the stored
value.

### `101000` — open named app

`APPToFunction` special-cases Chinese media titles (QQ音乐, 酷狗, 网易云, …)
then falls through to `AppMangerUtils.y(name)`, which keeps every installed
package whose launcher label contains the requested string
(case-insensitive, spaces stripped) and that has a LAUNCHER intent. The
blacklist is only `com.byd.bydcamera`. Voice "打开 Denza Apps" uses this
path; the Shortcuts Then-list simply never offers that name.

## Role switches (live 2026-08-22)

| Key | PersonBean | Live value | Consumer |
| --- | --- | --- | --- |
| `DEFAULT_MAP_SWITCH` | `_id=43` | `com.byd.launchermap` | voice navi, Shortcuts `102000` |
| `MUSIC_SWITCH` | `_id=45` | `com.byd.mediacenter` | Shortcuts `104451`, voice "open music" |
| `FM_SWITCH` | `_id=44` | `com.byd.mediacenter` | radio |
| `NEWS_SWITCH` | `_id=46` | `com.byd.mediacenter` | news |
| `VIDEO_SWITCH` | `_id=186` | `com.byd.videoplay` | Shortcuts `131501` |
| `KARAOKE_SWITCH` | (no row) | empty / third-music table | KTV |
| `byd_map_package` | Settings.Global | `com.byd.launchermap` | CustomKey action 7 only; this car uses action 1 (APA) |

Read:

```bash
adb shell content query --uri content://com.byd.autovoice/PersonBean --where "SETTING='DEFAULT_MAP_SWITCH'"
adb shell content query --uri content://com.byd.autovoice/PersonBean --where "SETTING='MUSIC_SWITCH'"
adb shell settings get global byd_map_package
```

`content update` on `DEFAULT_MAP_SWITCH` is **proven writable from `shell`**
(2026-08-16 write/restore; 2026-08-22 write to `ru.yandex.yandexnavi`, live
`102000` launch, restore to `com.byd.launchermap`). `settings put global byd_map_package`
is writable (2026-08-16) but was not used in the Yandex run. `MUSIC_SWITCH` /
`VIDEO_SWITCH` use the same PersonBean provider; write is the same command
with a different `SETTING=` and is **not yet live-proven**.

The official voice-settings picker is narrower than the stored-value check:

- Map picker (`DefaultAppFragment.G0` / `HandleDefaultAppUtils` type `map`)
  only commits BYD / Gaode / Baidu / Huawei package names, matched by
  **display name**. `setDefaultApp` with `type=map` cannot point at Denza Apps.
- Music picker lists `MediaApi.getSupportMusicList()`, which is
  `THIRD_MUSIC_CONTROL_BEAN` rows of type `MUSIC`. Writing `MUSIC_SWITCH`
  directly, or registering via `FUNCTION_UPDATE` then picking in the UI,
  bypasses that.

## Signature-gated hooks (still closed)

A normally-signed APK still cannot join the voice/third-app SDK:

| Hook | Protection | Result |
| --- | --- | --- |
| `com.byd.autovoice.permission.thirdapp` | `signatureOrSystem` (`protectionLevel=0x3`) | cannot `pm grant` |
| `com.byd.autoiot.service.BROADCAST_PERMISSION` | `signature` | IoT DIY insert from a third-party app is rejected |
| `android.permission.BYDAUTO_*` | `signature` | unchanged |

`IOTProvider` (`content://com.byd.autovoice.iot_provider/IOT`) **is**
exported and can insert a full `DiyCommandBean` (JSON `QicAtom` conditions
and actions), but `checkCallingPackage()` allows only
`com.byd.iotmanager` and `com.byd.mediacenter`. An empty caller is rewritten
to `iotmanager`; `com.android.shell` would throw `SecurityException`. Query
does not check the caller (read 2026-08-22: empty `id`/`code` cursor, no
exception). Do not treat IOT insert as a Denza Apps API.

`dumpsys car_service` does not exist — this is not Android Automotive.

`com.byd.maphelper` is **not** an integration point. It only toggles
`com.byd.rsemap`/`com.byd.navigatormap` and `com.byd.fsemap`/`com.byd.deputymap`
via `setApplicationEnabledSetting`.

## Masquerading as a Chinese app does not win the role

Live `pm list packages` on 2026-08-22:

| Package | Role it would impersonate | On this car |
| --- | --- | --- |
| `com.byd.launchermap` | default / BYD map | **installed** (current `DEFAULT_MAP_SWITCH`) |
| `com.autonavi.minimap` / `com.autonavi.amapauto` | Gaode | missing |
| `com.baidu.BaiduMap` / `com.baidu.mapauto` | Baidu | missing |
| `com.byd.mediacenter` | default music / radio / news | **installed** (current `MUSIC_SWITCH`) |
| `com.netease.cloudmusic` / `com.kugou.android.auto` / `com.tencent.qqmusic` | music fallback names | missing |
| `com.byd.videoplay` | default video | **installed** (`VIDEO_SWITCH`) |
| `com.byd.minikaraoke` | KTV | installed |
| `dev.denza.apps` | — | installed |
| `ru.yandex.yandexnavi` / `ru.yandex.music` | — | installed |

`MapUtils.getCurDefaultSelectMap()` returns the stored PersonBean value if
that package is installed. The Gaode/Baidu list is only scanned when the
stored value is empty **or** that package is missing. `com.byd.launchermap`
is present, so installing a fake `com.autonavi.minimap` does nothing to
Shortcuts `102000`. The same is true of NetEase vs `com.byd.mediacenter` for
music.

A sidecar APK that steals a Chinese package name would also collide with a
later real install, cannot share `dev.denza.apps` updates, and is not needed:
the stored role already accepts any installed package.

Do not disable `com.byd.launchermap` to force the fallback scan.

## Hidden registration APIs (corpus; not live-proven)

These are how a third-party app can *look* registered without the
`thirdapp` permission. None of them were fired on 2026-08-22.

1. **PersonBean write (proven for map, expected for music/video)** from
   `shell` through `content://com.byd.autovoice`. Points Shortcuts Then-actions
   at any installed package. Shared with voice "open map/music". Restore the
   original row.
2. **`VoiceSettingDatabaseProvider.call("setDefaultApp")`** — exported, no
   caller check. Bundle keys `type`, `appName`, `pkgName`. `type=music|video|ktv|news|radio`
   writes `pkgName` with no whitelist. `type=map` still requires a known map
   display name, so it cannot elect Denza Apps. Callable from Denza Apps
   itself without ADB, or from `adb shell content call --uri content://com.byd.autovoice --method setDefaultApp …`.
3. **Broadcast `com.byd.action.FUNCTION_UPDATE`** — `MusicAppReceiver` is
   registered without a broadcast permission (live
   `dumpsys activity broadcasts`: `com.byd.autovoice/1000`). Extras:
   `EXTRA_PACKAGE_NAME`, `EXTRA_APPLICATION_TYPE` (`MUSIC` / video / …),
   `ISCONTROL`, `EXTRA_SERVICE_NAME` (required, non-empty). Inserts
   `THIRD_MUSIC_CONTROL_BEAN` and makes the package appear in the official
   default-music picker. Still need the user, or a PersonBean write, to
   *select* it.
4. **Voice by label** — no registration. "打开 Denza Apps" already matches
   `DenzaLauncherActivity`'s label. This is not a Shortcuts Then-item.

## Two independent map-role switches

There is no single "default navigation app". Two switches exist and different
consumers read different ones.

| Switch | Storage | Read by |
| --- | --- | --- |
| `DEFAULT_MAP_SWITCH` | `PersonBean` row in `com.byd.autovoice` | voice assistant, Shortcuts `102000` |
| `byd_map_package` | `Settings.Global` | `CustomKey` action 7 only |

Replacing navigation *everywhere* means setting both. On this car the wheel
key is action 1 (APA), so `byd_map_package` has no consumer and
`DEFAULT_MAP_SWITCH` is the switch that matters for Shortcuts and voice.

### `DEFAULT_MAP_SWITCH` — the voice and Shortcuts path

`MapUtils.getBydMap()` does **not** read `Settings.Global`. It returns a fixed
choice by device type, falling back to the literal `"com.byd.launchermap"`.

The selectable value lives in `MapUtils.getCurDefaultSelectMap()`, backed by
`PersonDaoManger.z0 = "DEFAULT_MAP_SWITCH"`:

```java
String strG = PersonDaoManger.getInstance().g(PersonDaoManger.z0, getBydMap());
if (!TextUtils.isEmpty(strG) && AppMangerUtils.getInstance().f(strG)) {
    return strG;          // only validation is "is this package installed"
}
```

The stored value is **not** checked against the candidate list. That list
(`MapUtils.b` — BYD maps plus `com.autonavi.*` and `com.baidu.*`) is only the
fallback scan used when nothing is stored, and contains no Yandex, Waze, 2GIS
or `dev.denza.apps`.

## Product shape: inject into AutoVoice, do not daemonize Denza Apps

`com.byd.autovoice` is `SYSTEM|PERSISTENT` (`android.uid.system`). It is
already running at boot and is the process that observes seatbelt
(`CAD01` / `INSTRUMENT_DD_MAIN_SAFETYBELT_STATE` = `0x29400018`) and
executes Then. Denza Apps is a normal `/data/app` APK: it is not
persistent, and a background listener would have to be running first.
That is the wrong runtime.

Denza Apps may only be:

- a **one-shot editor** that writes AutoVoice state, then exits; or
- a **trampoline activity** that AutoVoice cold-starts, which immediately
  starts the target and finishes. AutoVoice, not Denza Apps, saw the
  seatbelt.

The stock Then picker will never list an arbitrary installed app.
`101000` *execution* already does a contains-match on every LAUNCHER
label; the editor just will not offer that label. A persisted
`DiyCommandBean` with condition `CAD01` and action `101000` /
value=`Яндекс Музыка` would be the real «any app per rule» inject.
Storage is GreenDAO `DIY_FUSION_BEAN` under AutoVoice's private data
dir. Shell cannot list that dir (`Permission denied`); `run-as` refuses
a non-debuggable system package.

Write APIs that could persist such a row:

| API | Caller gate | Status |
| --- | --- | --- |
| Official Shortcuts UI | user | Then catalog is closed; cannot pick Yandex Music |
| `IOTProvider.insert` (`content://com.byd.autovoice.iot_provider/IOT`) | only `com.byd.iotmanager`, `com.byd.mediacenter`, or an *empty* `getCallingPackage()` (rewritten to iotmanager) | Denza Apps UID is rejected. `com.android.shell` is expected to throw `SecurityException`. Empty-caller hole is unproven. Invalid JSON must be used if this is ever probed, so no row is created. |
| sqlite on `DIY_FUSION_BEAN` | filesystem | blocked without root |
| `VoiceSettingDatabaseProvider.call("setDefaultApp")` | none | writes a **role**, not a per-rule action |

Until a persist path for `101000`+label is proven, the only AutoVoice-native
Then that can start a third-party package is a **role**:

| Stock Then | Role key | Live occupant | Independent rules |
| --- | --- | --- | --- |
| 导航 → 地图 → 打开 (`102000`) | `DEFAULT_MAP_SWITCH` | `com.byd.launchermap` | one package for every open-map command |
| 媒体 → 音乐 → 打开 (`104451`) | `MUSIC_SWITCH` | `com.byd.mediacenter` | one package for every open-music command |
| 媒体 → 视频 → 打开 (`131501`) | `VIDEO_SWITCH` | `com.byd.videoplay` | one package for every open-video command |

A user-facing editor in Denza Apps that writes those three PersonBean
rows (map write already proven from shell) plus a short instruction
«в Shortcuts: ремень → открыть карту» is configuration, not a runtime.
AutoVoice remains the daemon. That is at most three always-on targets,
not an arbitrary app per rule.

A trampoline (map role = Denza Apps, `DenzaLauncherActivity` starts the
configured package and finishes) is also AutoVoice-driven: Denza Apps
does **not** need to be running before the seatbelt. `Y()` carries no
If identity, so the trampoline still has a single target.

Do not poll `autoservice` from a Denza Apps service as the product
Shortcuts replacement. That reintroduces the "must already be running"
constraint the stock engine does not have.

## Russia-oriented launch strategy

The car stays in RF. The stock map is China-only. Taking the map role therefore
costs little map utility and is the cleanest way to give Shortcuts a Then-action
that opens Denza Apps:

1. **Preferred, reversible, live-proven for a third-party package.** Point
   `DEFAULT_MAP_SWITCH` at the target (`ru.yandex.yandexnavi` opened from
   Shortcuts `102000` on 2026-08-22). Create a Shortcuts rule whose Then
   is 导航 → 地图 → 打开. Restore `com.byd.launchermap` afterwards until the
   behaviour is accepted as a product default. Use
   [tools/navi_role_probe.sh](../tools/navi_role_probe.sh); do not inject key
   `321`. `dev.denza.apps` is the same `Y()` branch and is not yet the
   target of a live Test Run.
2. **If the map role must stay BYD** (cluster Map-mode detector still keys off
   `com.byd.launchermap/com.byd.automap.meter.MeterActivity`). Point
   `MUSIC_SWITCH` at `dev.denza.apps` instead and use 媒体 → 音乐 → 打开
   (`104451`). Costs the stock media-center "open music" command. Restore
   `com.byd.mediacenter`. Not live-proven.
3. **Voice without taking a role.** "打开 Denza Apps" uses `101000` label
   match. Useful as a fallback, not as a vehicle-status If/Then.
4. **Do not** ship a second APK as `com.autonavi.*` / `com.netease.*` /
   `com.byd.mediacenter`. Those names either already belong to system apps or
   do not beat the stored PersonBean value.
5. **Do not** declare `BYDAUTO_*` or `thirdapp` in the product manifest.
   Privilege path remains `DenzaLocalAdb` shell writes to PersonBean, same as
   the locale grant.

The former hidden automatic Map-mode follower and its
`StockClusterModeDetector` were removed on 2026-08-26. Moving
`DEFAULT_MAP_SWITCH` therefore no longer changes Denza Apps projection state.

Related RF work: the per-app `ru-RU` override for `com.byd.carsettings` in
[stock-russian-locale.md](stock-russian-locale.md). Shortcuts UI strings stay
Chinese; that is BYD's catalog, not a Denza Apps translation job.

## Spike before integrating

[tools/navi_role_probe.sh](../tools/navi_role_probe.sh) sets both map switches
to a target package, waits for a manual navigation trigger, reports the
component the system actually started, and restores both originals on exit —
including on error or Ctrl-C.

```bash
ADB_SERIAL=127.0.0.1:15555 tools/navi_role_probe.sh dev.denza.apps
```

Trigger with Shortcuts → 导航 → 地图 → 打开 → Test Run, or the wake word plus
a navigation request. The `102000` third-party branch is **live** for
`ru.yandex.yandexnavi` (2026-08-22). Expected START for Denza Apps (same
branch, not yet that target):

```
cmp=dev.denza.apps/.DenzaLauncherActivity
```

Nothing may be added to `:denza-apps` as a *product* map-role writer until
the editor UX and the cluster Map-mode detector trade-off are decided.
The launch mechanism itself is no longer the blocker. Exactly one session
may mutate PersonBean at a time.

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
not what this car does.

## Cost to weigh before taking the role

The map role is shared by voice "navigate to", the Shortcuts `102000` family,
and the cluster Map mode. The stock map is China-only and this car stays in RF,
so the role has little to lose. Denza Apps no longer follows the stock cluster
Map task automatically.

The music role is shared by voice "open/play music" and Shortcuts `104451`.
Taking it from `com.byd.mediacenter` is more user-visible than taking the
China map.

See also [instrument-display-findings.md](instrument-display-findings.md), which
records `byd_map_package` and the package-scoped
`CUSTOM_NAVI_STANDARD_BROADCAST_RECV` broadcast for the wheel key.

## Next validation

- Optional: the same restore-wrapped `102000` Test Run against
  `dev.denza.apps` if the product default should be Denza Apps rather than
  a chosen third-party launcher. Mechanism is already live for Yandex Navigator.
- Only if the map role must be kept: restore-wrapped write of
  `MUSIC_SWITCH` and Test Run of `104451`.
- Per-rule any-app still needs a persist path for `101000`+label
  (`IOTProvider` caller gate is the next bounded probe, invalid JSON only).
- Do not spawn `app_process` while another session owns the car.
