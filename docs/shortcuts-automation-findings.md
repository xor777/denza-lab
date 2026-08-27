# Stock Shortcuts automation and default-app roles

What the BYD "Shortcuts" automation engine can and cannot do for a third-party
app, how Then-actions actually start activities, and which switches can point
those actions at Denza Apps.

Observed live on 2026-08-16, 2026-08-22, and 2026-08-27 over ADB (`shell` UID,
no root), plus
static reading of `BydAutoVoice.apk` (`v15.3.149.11.260512.7`, 11 dex files).
The 2026-08-22 pass decompiled the remaining automation dexes
(`AppMangerUtils`, `NaviManager`, `MediaApi`, `DiyChoiceScence5_1`,
`IOTProvider`, `HandleDefaultAppUtils`) and took read-only dumps of PersonBean
and installed role packages. A later bounded write the same day pointed
`DEFAULT_MAP_SWITCH` at `ru.yandex.yandexnavi`; a live Shortcuts Test Run of
`102000` opened Yandex Navigator. The row was restored to
`com.byd.launchermap` and the restore was read back. `byd_map_package` was
not changed.

The 2026-08-27 pass also proved the music and video roles with installed RF
apps. With `MUSIC_SWITCH=ru.yandex.music`, the firmware's **Continue playing**
action executed runtime command `129003`, read the Yandex package, and started
`ru.yandex.music/.main.MainScreenActivity`. The apparent **Open music** action
executed `129136` and opened the stock media center instead. With
`VIDEO_SWITCH=com.vk.vkvideo`, **Open video** opened VK Video and was confirmed
by the operator; the corresponding runtime path is `131500`. Both PersonBean
rows were restored to their stock values and read back after the test. On this
DiLink build a successful `content update` prints no row count, so the exact
post-write query is the authoritative success check.

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

DiLink 5.1 Then groups and the IDs that matter for launching something. The
catalog ID comes from the decompiled picker; the runtime ID is what the live
firmware logged after `Test Run`:

| UI group | Then item | Catalog ID | Live runtime ID | What it actually starts |
| --- | --- | --- | --- | --- |
| 应用管理 | 打开应用 | `101000` | — | Label match among installed LAUNCHER apps. Catalog is five BYD names only: 智能语音助理, 百变主题, 文件管理, 用户手册, 应用市场 |
| 应用管理 | 关闭应用 | `101001` | — | Same closed name list |
| 媒体 | 音乐 → 打开 | `129136` on the live R123-style picker | `129136` | Stock `com.byd.mediacenter`; it did **not** honor `MUSIC_SWITCH` on this car |
| 媒体 | 音乐 → 继续播放 | `129003` | `129003` | `MediaApi.getDefaultMusicPkg()`, then generic launch when no usable current music session exists; live-proven with Yandex Music |
| 媒体 | 视频 → 打开 | static picker uses `131501` | `131500` | `MediaApi.getDefaultVideoPkg()`; live-proven visually with VK Video |
| 导航 | 地图 → 打开 | `102000` | `102000` | `MapUtils.getCurDefaultSelectMap()`. BYD map uses the automap API; any other installed package uses `AppMangerUtils.Y` |
| 导航 | 地图 → 关闭 | `102001` | — | Close map |
| 导航 | 导航 → 家 / 公司 | `102003` / `102004` | — | Destination handoff into the current map role |

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

### Music — static open path versus the live launch action

The decompiled `DiyChoiceScence5_1` catalog labels `104451` as open music, and
its static executor contains this default-role path:

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

The live 2026-08-27 picker behaved like the R123 catalog instead. With
`MUSIC_SWITCH=ru.yandex.music`:

```text
commandID = [129136]
MediaStart: whiteLaunch ... mediaApp = [com.byd.mediacenter]

commandID = [129003]
defaultMusicPkg:ru.yandex.music
MediaStart: launchMediaApp ... mediaApp = [ru.yandex.music]
ActivityTaskManager: START ... cmp=ru.yandex.music/.main.MainScreenActivity
```

Therefore the reliable user instruction for this exact firmware is
**Music → Continue playing**, not **Open music**. This remains state-sensitive:
the command may continue an already active supported media session instead of
starting a package. Denza Apps changes the role only; it does not rewrite the
Shortcuts catalog or inject the runtime command.

### Video — Open video

The static picker stores `131501` for **Open video**, while the live executor
logged `131500`. With the stock role, that runtime path read
`defaultVideoPkg=com.byd.videoplay`, reported `isNetworkEnable:true`, and
started the stock player. With `VIDEO_SWITCH=com.vk.vkvideo`, the same visible
**Open video** choice opened VK Video (operator-confirmed on 2026-08-27).
The tunnel dropped before the probe captured the complete VK launch chain, but
after reconnection the role still read `com.vk.vkvideo` and Android retained a
VK Video activity record. The row was then restored to `com.byd.videoplay` and
read back.

Video has two extra constraints: AutoVoice must report its network gate as
enabled, and an already visible supported video app in a split/top scene may be
continued instead of launching the configured role. For a launch check use a
clean fullscreen scene and **Open video**; Play/Pause/Continue are media-control
commands, not package selectors.

### `101000` — open named app

`APPToFunction` special-cases Chinese media titles (QQ音乐, 酷狗, 网易云, …)
then falls through to `AppMangerUtils.y(name)`, which keeps every installed
package whose launcher label contains the requested string
(case-insensitive, spaces stripped) and that has a LAUNCHER intent. The
blacklist is only `com.byd.bydcamera`. Voice "打开 Denza Apps" uses this
path; the Shortcuts Then-list simply never offers that name.

## Role switches (live 2026-08-22, rechecked 2026-08-27)

| Key | PersonBean | Live value | Consumer |
| --- | --- | --- | --- |
| `DEFAULT_MAP_SWITCH` | `_id=43` | `com.byd.launchermap` | voice navi, Shortcuts `102000` |
| `MUSIC_SWITCH` | `_id=45` | `com.byd.mediacenter` | live Shortcuts runtime `129003` (Music → Continue playing), voice/media default |
| `FM_SWITCH` | `_id=44` | `com.byd.mediacenter` | radio |
| `NEWS_SWITCH` | `_id=46` | `com.byd.mediacenter` | news |
| `VIDEO_SWITCH` | `_id=186` | `com.byd.videoplay` | visible Shortcuts Open video; live runtime `131500` |
| `KARAOKE_SWITCH` | (no row) | empty / third-music table | KTV |
| `byd_map_package` | Settings.Global | `com.byd.launchermap` | CustomKey action 7 only; this car uses action 1 (APA) |

Read:

```bash
adb shell content query --uri content://com.byd.autovoice/PersonBean --where "SETTING='DEFAULT_MAP_SWITCH'"
adb shell content query --uri content://com.byd.autovoice/PersonBean --where "SETTING='MUSIC_SWITCH'"
adb shell settings get global byd_map_package
```

All three role rows are now **proven writable from `shell`** with exact
post-write and restore readbacks:

- `DEFAULT_MAP_SWITCH`: Yandex Navigator opened from runtime `102000`
  (2026-08-22).
- `MUSIC_SWITCH`: Yandex Music opened from runtime `129003`
  (2026-08-27).
- `VIDEO_SWITCH`: VK Video opened from the visible Open video action, whose
  runtime path was `131500` (2026-08-27, operator-confirmed).

`settings put global byd_map_package` is also writable (2026-08-16) but is a
different wheel-key setting and was not touched in any of these role tests.
The DiLink 5.1 `content update` command returns exit success with empty stdout;
do not require an `Updated 1 row` string. Require exactly one row before the
write and an exact value readback afterwards.

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

## Registration APIs and adjacent paths

These are how a third-party app can *look* registered without the
`thirdapp` permission. The PersonBean path is now live-proven for all three
product roles; the other registration paths remain corpus findings unless
stated otherwise.

1. **PersonBean write (live-proven for map, music and video)** from `shell`
   through `content://com.byd.autovoice`. Points the matching Shortcuts
   Then-action at any installed launchable package. Shared with voice
   "open map/music". Restore the original row after a probe.
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
| 媒体 → 音乐 → 继续播放 (live runtime `129003`) | `MUSIC_SWITCH` | `com.byd.mediacenter` | one default package, but an existing media session can win |
| 媒体 → 视频 → 打开 (static `131501`, live runtime `131500`) | `VIDEO_SWITCH` | `com.byd.videoplay` | one package for the Open video command |

The Denza Apps `Приложения` panel writes those three live-proven PersonBean
roles and explains which matching Shortcuts action to use. It is configuration,
not a runtime: AutoVoice remains the daemon. That is at most three always-on
targets, not an arbitrary app per rule.

First-run selection is handled once per role after a successful provider read.
An untouched stock value may be replaced with the first installed known app,
but the update predicate also requires the value to still be stock, so a
concurrent external choice cannot be overwritten. Keeping stock or preserving
a pre-existing non-stock value also completes first-run handling; installing a
known app later never causes a surprise role switch. PersonBean is reread on
Activity resume, and an unavailable read is shown only as a last-known value,
not as a confirmed current selection.

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
2. **Music is live-proven but state-sensitive.** Point `MUSIC_SWITCH` directly
   at the chosen installed player and use **Music → Continue playing** on this
   firmware (runtime `129003`). It opened Yandex Music from cold state. The
   visible Open music action instead opened `com.byd.mediacenter` in the live
   run. An already active supported media session can still take precedence.
3. **Video is live-proven for Open video.** Point `VIDEO_SWITCH` directly at
   the chosen installed player and use **Video → Open video** (live runtime
   `131500`). The network gate must be enabled and no supported video app
   should already occupy the active split/top scene.
4. **Voice without taking a role.** "打开 Denza Apps" uses `101000` label
   match. Useful as a fallback, not as a vehicle-status If/Then.
5. **Do not** ship a second APK as `com.autonavi.*` / `com.netease.*` /
   `com.byd.mediacenter`. Those names either already belong to system apps or
   do not beat the stored PersonBean value.
6. **Do not** declare `BYDAUTO_*` or `thirdapp` in the product manifest.
   Privilege path remains `DenzaLocalAdb` shell writes to PersonBean, same as
   the locale grant.

The former hidden automatic Map-mode follower and its
`StockClusterModeDetector` were removed on 2026-08-26. Moving
`DEFAULT_MAP_SWITCH` therefore no longer changes Denza Apps projection state.

Related RF work: the per-app `ru-RU` override for `com.byd.carsettings` in
[stock-russian-locale.md](stock-russian-locale.md). Shortcuts UI strings stay
Chinese; that is BYD's catalog, not a Denza Apps translation job.

## Restore-wrapped live probe

[tools/default_app_role_probe.sh](../tools/default_app_role_probe.sh) changes
exactly one PersonBean role, waits for a manual Shortcuts `Test Run`, records a
focused launch transcript, and restores the original with exact readback. It
never changes `byd_map_package`, injects input, clears logcat, starts an app, or
restarts ADB/AutoVoice.

```bash
tools/default_app_role_probe.sh run --serial 127.0.0.1:5555 \
  --role music --package ru.yandex.music --timeout 300 --require-stopped

tools/default_app_role_probe.sh run --serial 127.0.0.1:5555 \
  --role video --package com.vk.vkvideo --timeout 300 --require-stopped
```

For music on this firmware trigger **Continue playing**; for video trigger
**Open video**. The older [tools/navi_role_probe.sh](../tools/navi_role_probe.sh)
remains navigation-specific and also writes the separate global map key, so do
not use it for the three-role product acceptance. Exactly one session may
mutate PersonBean at a time.

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

The music role is shared by voice/media control and the live `129003` Continue
playing path. Taking it from `com.byd.mediacenter` is more user-visible than
taking the China map, and an already active session can still affect the
result. The video role also has a network gate and existing-scene special case.

See also [instrument-display-findings.md](instrument-display-findings.md), which
records `byd_map_package` and the package-scoped
`CUSTOM_NAVI_STANDARD_BROADCAST_RECV` broadcast for the wheel key.

## Next validation

- Install the Denza Apps build containing the default-app picker and verify its
  UI write/readback path for all three roles. The underlying shell/provider
  paths are already live-proven independently.
- Verify cold-start first-choice initialization on a clean product preference
  state without overwriting a pre-existing non-stock PersonBean choice.
- Per-rule any-app still needs a persist path for `101000`+label
  (`IOTProvider` caller gate is the next bounded probe, invalid JSON only).
- Do not spawn `app_process` while another session owns the car.
