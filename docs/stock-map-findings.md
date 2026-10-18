# Stock map findings

What the car's own map can display, and where Yandex can replace it in the
factory navigation experience. Coverage was established on 2026-09-22 from
the corpus and the car; the integration study below is firmware/code evidence.

## Current state

Updated 2026-10-15. Separate replacing the stock map's data from replacing its
launch, widget and instrument-display surfaces.

| Claim | Status | Since | Section |
| --- | --- | --- | --- |
| The installed AutoSDK streams Beijing but leaves Moscow blank; the offline catalog and USB format offer no Russia pack | live | 2026-09-22 | [Coverage](#the-pipeline-works-only-the-data-is-missing) |
| `DEFAULT_MAP_SWITCH` redirects generic navigation opening, not all route commands or stock UI surfaces | firmware | 2026-10-15 | [Launch and destinations](#launch-and-destinations-are-different-contracts) |
| “`byd_map_package` has only a CustomKey consumer” is false: `BydProjectionService` selects Gaode/Baidu with it, then checks actual caller identity | refuted | 2026-10-15 | [Native cluster](#native-cluster-provider-contract) |
| The custom Yandex branch has real guidance data and an independent offscreen map; a separate cluster renderer and IPC to Denza Apps are not implemented there | code | 2026-10-15 | [Source and evidence](#source-and-evidence) |
| Shell declares `BIND_APPWIDGET`; the IVI framework admits that caller to update another provider's existing widget in the same user. This is a candidate for replacing the stock map card's contents | firmware | 2026-10-15 | [Native widget](#replacing-the-existing-stock-widget-through-shell) |
| A shell `IActivityController` can be registered only with the Monkey flag in this firmware; selective map-launch redirection is an accepted owner-car research candidate, with concrete bootstrap and system-behavior effects to test | open | 2026-10-15 | [Monkey controller](#monkey-controller-as-an-owner-car-experiment) |
| The permanent service-card chooser has fixed provider components; dynamic cards have protected access and a 20-minute expiry | firmware | 2026-10-15 | [Widget catalog](#widget-catalog-and-dynamic-cards) |
| A stock Map-mode follower using Denza Apps' existing cluster surface and Yandex's independent renderer is feasible in principle; native scene priority, small-card gates and lifecycle need a car test | open | 2026-10-15 | [Factory scene](#following-the-factory-map-scene) |
| `pm grant` accepts requested runtime/development permissions, not arbitrary signature permissions. BYD Setting/Instrument GET/SET are signature permissions in the IVI AutoPermission APK | firmware | 2026-10-15 | [ADB privileges](#denza-apps-as-the-privileged-companion) |
| A `com.byd.naviauto` adapter has a boot-scanning blocker in `InstallPackageHelper`; changing only `byd_map_package` or the APK name is insufficient | firmware | 2026-10-15 | [Provider adapter](#why-a-baidu-named-adapter-is-not-yet-a-durable-solution) |

**Open questions**

- Does shell hold the declared widget permission on the current car, and can a
  bounded update replace the existing map widget, including clicks, without a
  competing stock update? Read permissions and `dumpsys appwidget`, then run one
  reversible widget test.
- Can a Yandex renderer follow the factory Map scene without obscuring another
  scene? Observe theme, tasks and display layers together before a renderer test.
- Which native small-map/TBT slots are enabled on the current platform? Read
  `ro.byd.ui.platformized`, projection-service state and active cluster tasks.
- Can a custom HOME component survive BYD role/preferred-home reconciliation?
  A direct Android HOME role is separate from membership in the stock chooser.
- Full native provider substitution, voice route commands and phone destination
  handoff still lack a complete, persistent adapter.

## Contents

- [What it is](#what-it-is)
- [What it draws in Russia](#what-it-draws-in-russia)
- [Why there is nothing to load](#why-there-is-nothing-to-load)
- [Amap does have a world map](#amap-does-have-a-world-map--this-build-does-not-reach-it)
- [The pipeline works](#the-pipeline-works-only-the-data-is-missing)
- [The native levers](#the-native-levers-and-where-they-stop)
- [Verdict](#verdict)
- [Not checked](#not-checked)
- [Deeper Yandex integration (2026-10-15)](#deeper-yandex-integration-2026-10-15)

## What it is

The stock map is AutoNavi's (高德 / Amap) AutoSDK, packaged by BYD.

| Fact | Value |
| --- | --- |
| Package | `com.byd.launchermap`, `/system/app/BydLaunchermap/BydLaunchermap.apk` |
| Size | 569 603 317 bytes, 26 dex files, `targetSdk` 30 |
| Version | `V3.12.837.10.810.1.202606231740.6`, `versionCode` 837 |
| Engine | `assets/GblBranchCommitInfo.txt`: `BLVersion 9.810.33727.0.2269`, every GBL component on `release/810` |
| Engine binaries | `libGbl.so` (62 MB), `libAutoDice.so` (62 MB), `libAmapFreeType.so`, `libGNet.so`, `libGEhp.so` |
| Render assets | `assets/blRes/{MapAsset,3DMapAsset,ExtraMapAsset,LayerAsset}` — Amap's own style/data format |
| Activity | `com.byd.automap.activity.MainActivity`, plus `com.byd.automap.meter.MeterTbtActivity` for the cluster card |
| Scheme | `bydautomap://` with ~45 authorities (`navi`, `route`, `offline_data`, `citySelect`, `openUsbguide`, …) |

Around it: `com.example.amapservice` (`/system/priv-app/AmapService`, system UID,
`1.5.13.3.2605201100`), `com.byd.maphelper`, and `com.baidu.mapauto.pass`
(`BydMapAccount`, account plumbing only). `settings get global byd_map_package`
is `com.byd.launchermap`; that key selects which package the firmware treats as
"the map" and is not a data path — see
[shortcuts-automation-findings.md](shortcuts-automation-findings.md).

## What it draws in Russia

Opened on the car on 2026-09-22 (parked, charging, Moscow):

- The GNSS fix is fine. The car arrow, its heading and the north rose are drawn,
  the 高德地图 watermark and the 100 m scale bar are drawn.
- Everything else is empty. No road, no label, no water, no landuse — the base
  canvas at 100 m scale is blank.

So the engine runs and positions correctly; it simply has no map of this place.

## Why there is nothing to load

**The offline catalog is China.** Settings → Personal → Offline map lists
`All cities` as Chinese provinces (`直辖市`, `安徽省`, `福建省`, `甘肃省`, …),
its search box says *"Please enter simplified Chinese, Pinyin or initials"*, and
the one global item is `Base package`, 55.53 MB, *"Necessary for cross-city
navigation"*. `Current city` is `To be updated`, `Nearby cities` is
`0 cities / 0.00 KB`. There is no region in that list that is not in China.

**The USB import is Amap's own pack, version-locked.** The screen has a U-disk
button, and the app's own help text is explicit: download the pack for *your
version* from `https://auto.amap.com/download/map_data/v3`, unpack `amapauto9`
to the root of a USB stick, insert it. A neighbouring string is
`U盘数据与导航版本不兼容，无法进行更新` — "U-disk data is incompatible with the
navigation version, cannot update". The import accepts Amap's proprietary,
version-matched data and nothing else; there is no OSM, GPX, MBTiles, tile-URL
or style hook anywhere in the app.

**The data model is keyed on Chinese administrative codes.** `libGbl.so` is full
of `GetAdcodeByLonLat`, `DriveLinkAccessorGetAdcode`,
`DrivePathEngineGetCityAdcodeList`, `WHERE adcode = %d`. Failure strings match:
`路线规划失败，所在城市未下载离线数据` and `…终点未下载离线数据` — routing is
refused per city when that city's pack is missing.

**There is no overseas mode.** `海外`, `境外`, `oversea`, `Oversea` and `OVERSEA`
occur zero times in `resources.arsc` and zero times in `libGbl.so` (control:
`amap` occurs 201 times in the same binary).

**The tile services this product family uses stop at Greater China.** Host
probe at z15 of `webrd01.is.autonavi.com` (`style=8`, roads),
`webrd02` (`style=7`, base) and `webst01` (`style=6`, satellite), bytes per
tile:

| | Beijing | Hong Kong | Taipei | Tokyo | Bangkok | Paris | Moscow | Sochi |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| roads | 12 379 | 18 939 | 9 337 | 179 | 179 | 179 | 179 | 179 |
| base | 13 538 | 20 168 | 12 765 | 124 | 124 | 124 | 124 | 124 |
| satellite | 16 299 | 13 270 | 16 575 | 4 235 | 4 235 | 4 235 | 4 235 | 4 235 |

The overseas numbers are identical placeholder images. At z8 Moscow does return
8 335 bytes — a coarse world overview with `莫斯科` and trunk roads — so a world
layer exists at overview scale and nothing below it. The car reaches the servers
(`ping auto.amap.com` ≈ 290 ms, `autoapi.amap.com` ≈ 187 ms from the head unit),
so this is coverage, not connectivity.

## Amap does have a world map — this build does not reach it

Corrected on 2026-09-22 after the owner opened Amap on a phone and saw Moscow
and their own position. Amap's consumer product does carry a world map: the
company announced coverage of 200+ countries and regions with driving, cycling,
walking and transit routing in 78 languages, and the open platform sells a world
map service. So "Amap has no data here" is wrong as a statement about Amap.

It is right as a statement about **this head unit**. Searching `Moscow` in the
car's own map (2026-09-22, live) returns Chinese restaurants: tabs `北京`,
`宁波`, `大连`, `其他城市`, first hit `莫斯科餐厅` in Beijing's 西城区, 5 834 km
away, cuisine `俄国菜`; the `其他城市` tab offers `莫斯科西餐厅` in 满洲里市,
5 171 km. The backend answered a Latin query by scanning Chinese cities. Nothing
in the result space is outside China, and the map's canvas stays empty at the
car's own position while the same servers answer within 190 ms.

The owner then opened `amap.com/@37.6173,55.7558,14z` in a browser: Moscow is
drawn in full detail — the ring roads, `M11`, `M12`, `E105`, the airports, all
labelled in Chinese. So the data exists and Amap serves it to a browser from
here. The head unit's client asks a different backend and gets China.

So the world map lives in the phone app, the web map and the open platform, not
in the AutoSDK build BYD ships here.

## The pipeline works; only the data is missing

The strongest test, run last (2026-09-22). In the same session, with `Current
city` still `To be updated` and `Nearby cities` still `0 cities / 0.00 KB` —
that is, with **no offline pack downloaded at all** — a Beijing search result was
opened. The map drew Beijing in full: streets and their names, buildings, water,
the zoo and the planetarium, metro entrances, traffic-coloured roads, POI icons,
a photo card with opening hours and two phone numbers.

So the client streams vector map data online and renders it correctly, from this
car, on this network, with an empty offline store. Nothing about the engine, the
build, the connection or the missing packs explains the empty canvas at home.
The one thing that differs between the two places is whether the server has data
for them.

That also retires the last workaround worth naming: downloading the 55.53 MB
`Base package` cannot help. Rendering never needed it, and its content is China.

## The native levers, and where they stop

What the firmware itself offers, and what it does not:

| Lever | State | Reach |
| --- | --- | --- |
| `PersonBean.DEFAULT_MAP_SWITCH` | already `ru.yandex.yandexnavi` (read 2026-09-22) | generic map opening from voice and Shortcuts `102000`; not the complete destination protocol |
| `Settings.Global.byd_map_package` | `com.byd.launchermap` (2026-09-22 read) | CustomKey action 7 **and** the native cluster's Gaode/Baidu provider selection; arbitrary Yandex package values do not register a cluster provider |
| Map Settings → Navigate / Acoustic / Interconnect / Personal | inspected live | route preferences, voice, phone→car destination handoff (BYD app, WeChat, Amap phone, Dianping, Meituan), account, offline map. **No region, country or data-source control anywhere** |

> **Superseded 2026-10-15:** the former “CustomKey action 7 only / inert here”
> description missed `BydProjectionService`. Two role writes do not replace
> navigation everywhere — see [native cluster](#native-cluster-provider-contract).

The one switch in that table never pulled is the Amap account: the map reports
`Not logged in yet`, and signing in is the owner's to do, not this session's.
Nothing in the app ties an account to a region — the account page offers
favourites, offline map and Team — so the expectation is that it changes
nothing, but it is the last untried control that exists in the UI.

Beyond it there is no property, setting or account switch that points the stock
client at the world map. Reaching it would mean patching a signed `/system` app and then
persuading a backend that answers only about China — not a native path, and not
a reachable one.

## Verdict

This verdict concerns the **stock engine and its map coverage**. The factory
UI can potentially carry another renderer without replacing that engine's
data; see [the integration study](#deeper-yandex-integration-2026-10-15).

The stock map cannot be made to show Russia. Not by a setting, not by a
downloaded pack, not by a USB import, not by pointing it at another tile source:
the renderer consumes Amap's own vector data, the only importable data is Amap's
own China packs — Amap's install page offers exactly `全量地图数据` (full) or
`分省地图数据` (per-province), and nothing else — and the online services this
build talks to answer only about China. Amap's world map does exist, but it is
not sold as an `amapauto9` pack and there is no build here that subscribes to
it. Anything short of replacing the engine — a 569 MB closed `/system` app that
also feeds the cluster map card and the AR HUD — does not reach this.

The established baseline is what this repository already does: keep the stock surfaces and
put a different navigator behind them. The navigation role (`PersonBean`
`DEFAULT_MAP_SWITCH`) points voice and Shortcuts at an installed navigator, the
Navigation feature projects that navigator onto the cluster, and AR HUD guidance
is supplied outside Amap. The embedded Yandex build now owns its HUD output;
the next integration should preserve one writer per channel. See
[shortcuts-automation-findings.md](shortcuts-automation-findings.md),
[instrument-display-findings.md](instrument-display-findings.md) and
[dishare-api-notes.md](dishare-api-notes.md).

One untested option is worth naming, because it is ordinary: Amap's **phone**
app (`com.autonavi.minimap`) is an ordinary Android app and is not installed on
this head unit. If it runs here it would carry the world map the owner just saw
on their phone, and it would be projectable to the cluster the same way Yandex
Navigator is. That is not the stock map — it is another navigator behind the
same surfaces.

## Not checked

- Whether the `Base package` and `Current city` downloads still succeed from
  here; both showed `To be updated` and were not pressed.
- Whether the engine would draw the coarse world overview layer (the one behind
  the z8 tile) if the map were zoomed out to country scale. It could not be
  tested from a shell: the map exposes no zoom control in its view tree,
  `KEYCODE_ZOOM_OUT` is ignored, and `sendevent` to `/dev/input/event4`
  (`himax-touchscreen`) is refused for the shell UID, so no pinch can be
  injected. It would not change the answer — an overview layer carries no
  routing and no search.

## Deeper Yandex integration (2026-10-15)

The owner's target is factory navigation behavior with Yandex behind it:
factory map buttons, HOME, launcher cards, cluster Map/TBT scenes and HUD.
This is an owner-operated research car. A reversible shell-controller approach
is a valid candidate even if it would be unsuitable for a general distribution.
This pass changed no car state and installed nothing; all new paths below are
static findings or proposals, not vehicle acceptance.

### Source and evidence

- Main snapshot: `9f61081458a9a34d8e6522ff3ac53589dd6dce03`.
- Yandex snapshot: `experiment/yandex-hud-renderer`,
  `53dd3d7f7767e08f6731380e42322a2f38ab2715`, worktree
  `/Users/dmitry/.codex/worktrees/yandex-hud-street/denza-gateway`.
- IVI OTA: `34.1.33.2605218.1`. Existing decompiles under
  `captures/split-firmware-20260923/jadx/` (launcher3, systemui, framework,
  services, dilink-services) and `captures/hud-firmware-20260923/jadx/`
  (AmapService, BydLaunchermap, MapHelper, BydClusterApp).
- Focused manifest/decompile evidence:
  `captures/stock-map-integration-20261015/reports/`. The recovered
  `BydProjectionService.java` has imperfect control-flow reconstruction;
  use its concrete package/action assignments and independently traced methods.
  `InstallPackageHelper-fallback.java` preserves the instruction branches that
  the normal decompile lost. Do not infer behavior from its empty Java `if`s.
- Newly copied IVI permission owners, after corpus lookup:
  `captures/firmware/ivi-34.1.33.2605218.1/system/system/framework/framework-res.apk`
  and `AutoPermission/AutoPermission.apk`; source/archive hashes are in
  `extraction.json`. The firmware index was refreshed.
- Older AutoVoice protocol evidence is explicitly older:
  `reverse/av9-jadx/` and `reverse/autovoice-jadx/`; check the installed
  AutoVoice version before a destination-protocol experiment.

The Yandex module's `NavigatorSource` has the selected/resumed route, classified
position, maneuvers, lanes, traffic, ETA and speed limit. `HudMap` already creates
an independent `createOffscreenMapWindow(300, 180, 1f)`. It does not move the
main map, capture the screen or calculate another route. That makes a second,
appropriately sized cluster renderer a much better source than transferring
the main Activity away from IVI. It still needs implementation and performance
measurement; enlarging the HUD bitmap is not a cluster rendering design.

Proposed division: Yandex owns route state and rendering; Denza Apps owns car
scene integration, the existing ADB connection and fixed privileged operations.
Exchange fresh semantic snapshots and render surfaces over explicit IPC. Use
route/generation identity and expiry, so cancellation, process death or stale
position cannot leave a believable old instruction on another surface.

An immediate cross-branch issue: `tools/yandex_hud_build/identity.py` at the
Yandex snapshot emits `29.8.1-YandexHUD.1`, while main's `HudNavigatorBuild.kt`
recognizes only `-denza-hud`. This combination can fail the one-HUD-writer gate.
The installed APK was not read here; this is a source compatibility finding,
not a claim that the current car is flickering. Reconcile recognition before
combining these snapshots. Keep the embedded module as the HUD writer.

### Replacing the existing stock widget through shell

**The strongest native-card candidate found in this pass.** The permanent
BYD card can remain in its current launcher slot while a Denza Apps shell
helper replaces its `RemoteViews` with Yandex information and click actions.

The chain in this IVI firmware is complete through the host update:

1. `Shell.apk` requests `android.permission.BIND_APPWIDGET` (manifest line 598).
2. `AppWidgetServiceImpl.SecurityPolicy.canAccessAppWidget()` accepts an owner
   **or** a caller holding that permission for a widget in the same user.
3. `updateAppWidgetIds(callingPackage, ids, views)` checks that the calling
   package belongs to the caller, then uses that same access policy. A shell
   helper must identify itself as `com.android.shell`, not impersonate Yandex.
4. `updateAppWidgetInstanceLocked()` replaces the views and notifies the host.
   It enforces bitmap-memory limits, but the inspected path does not require
   the RemoteViews resource package to equal the widget provider package.
5. BYD `CardAppWidgetHostView.updateAppWidget()` stores the received views and
   calls Android's implementation; no additional provider/resource-package
   equality check was found in that override.

Candidate contents: Yandex label/icon, current maneuver, street, distance, ETA,
small map snapshot, and real Yandex PendingIntents for opening navigation,
search, home/work or route continuation. Low-rate RemoteViews updates suit a
card; continuous high-frame-rate map video should use a Surface instead.

This updates an **existing widget instance**, not the permanent provider list.
Discover the exact host/provider/id from `dumpsys appwidget`, confirm shell's
actual permission, and read the old views through `getAppWidgetViews()` before
one bounded update. IDs must be rediscovered after host rebuilds. The stock
`MapWidgetUtil` also updates this widget on its own events and can overwrite
ours. Stable ownership, theme/size changes, clicks, sleep and restoration are
unproved. Do not disable the provider as a shortcut: widget reconciliation may
remove the slot itself. Do not label a repeated overwrite loop a finished fix.

This path does **not** require granting `BIND_APPWIDGET` to Yandex. Its protection
level is signature/privileged; a shell-side operation retains shell identity,
whereas a call relayed back into Yandex runs with Yandex's ordinary UID.

### Following the factory Map scene

`BydProjectionService` reads `INSTRUMENT_THEME` and selects the stock map when
the theme is `2`. The resolved CAN-FD table names that signal as `1086369808`
(`0x40C0B010`); it is an observation source, not a proposed setter. The native
map Activity's lifecycle gives a second observation of what is actually shown.

Denza Apps already owns a `ClusterSceneService`/`ClusterPresentation` map
surface and separate camera layer. A candidate can show a dedicated Yandex
render **when the driver selects the factory Map scene**, and release it on
leaving that scene, rather than require a separate projection action or move
the full Navigator task. This could leave the interactive Navigator on IVI,
the map on the cluster and HUD guidance active concurrently.

A shell helper can observe task-stack changes:
`ActivityTaskManagerService.registerTaskStackListener()` requires task-management
permission, which Shell requests. This is separate from the global Monkey
controller. Observe theme plus actual task/display ownership; never infer the
scene from a cached display number or package preference. The former automatic
Map-mode detector in Denza Apps is historical evidence, not current product
functionality (see instrument findings, Navigation projection).

The factory controller arbitrates ADAS, night vision, camera and map scenes.
The overlay/render must follow that arbitration, including startup and loss
of the observer. Existing projection success does not prove this new automatic
behavior. Native small cards have additional readiness gates; a full-map
scene follower does not automatically solve TBT.

### Native cluster provider contract

`com.example.amapservice` exports `BydProjectionService` without a manifest
permission, but Binder methods derive the caller from UID/PID and compare its
real package to configured providers. The map role is not freely registrable.
`byd_map_package=com.byd.naviauto` selects Baidu; other ordinary values select
Gaode (`com.byd.launchermap`), including an arbitrary Yandex package value.

| Surface | Gaode Activity / action suffix | Baidu Activity / action suffix |
| --- | --- | --- |
| Large map | `com.byd.automap.meter.MeterActivity` / `com.byd.automap.START_MAP_VIEW` | same class / `com.byd.naviauto.START_MAP_VIEW` |
| Turn guidance | `com.byd.automap.meter.MeterTbtActivity` / `com.byd.automap.START_MINI_MAP_CARD` | same class / `com.byd.naviauto.START_MINI_MAP_CARD` |
| Small map | `com.byd.automap.meter.MeterSmallScreenActivity` / `com.byd.automap.START_HALF_MAP_CARD` | `com.byd.automap.meter.MeterMiniActivity` / `com.byd.naviauto.START_MINI_MAP_VIEW` |

The protocol includes callback registration, `readyForProjection`,
`startContentProjection`, `stopContentProjection` and `syncViewState` with an
`actionId`. Firmware starts/stops Activities on the base and top fission
displays. There are separate `MAP_VIEW`, `MINI_MAP_CARD` and `TBT_CARD` types;
which small cards are supported and their left/right placement depend on
`ro.byd.ui.platformized`. Startup also depends on product/cluster/ADAS platform
and the stored/current SR flag in `AmapServiceApplication`.

Retaining stock as a scene owner is more promising than merely catching its
launch intent. Although non-Google projection launches use implicit actions,
the ready/view-state callbacks still require an accepted caller. Registering
the same action in Yandex alone does not complete the protocol. Stock
`MeterActivity` reports visible at `onResume`, hidden at `onStop`.
`DashBroadService.mapViewReadyState()` returns true, whereas non-platformized
TBT readiness requires the **stock engine's** `getIsNav()`. A Yandex route does
not satisfy that predicate by itself.

The service also contains Google/CarPlay client definitions, but the inspected
initialization marks those map clients unsupported; a package name in the
source is not an enabled generic slot. The debug projection switch substitutes
demo identities for map, ADAS, camera and other providers together. It is not
a map-only registration API.

### Why a Baidu-named adapter is not yet a durable solution

The expected Baidu Activities and widget provider could theoretically be
implemented in a separate compatibility APK without renaming working Yandex.
No `naviauto` APK was found in the indexed corpus. That does not establish its
installed-package/signature state on the car.

More decisively, `InstallPackageHelper.installPackagesFromDir()` filters these
map package names at boot. Its constructor reads vehicle configuration
`285409796`. In the fallback instruction dump, config `0`/`1` sends
`com.byd.naviauto` directly to the loop continuation, before `addForInitLI`.
The other branch also skips `naviauto` and skips `launchermap`. The normal
decompile had empty conditions here and hid this result. A successful runtime
install would therefore not establish persistence across boot. Changing the
global map selector is a different mechanism and does not remove this filter.

Installing an ordinary differently signed APK over the existing Gaode system
package is also not an ordinary update. A fully substituted native provider
needs a separately established installation/signature/boot strategy. Shell
identity alone cannot satisfy the projection service's real-package check.

### Widget catalog and dynamic cards

The IVI51 launcher service-card chooser is built from a platform policy, not
from every installed AppWidgetProvider. Its known map providers include
`com.byd.launchermap/com.autosdk.drive.widget.MapWidget` and
`com.byd.naviauto/com.byd.automap.widget.MapWidget`. Adding a normal Yandex
widget does not automatically add it to this chooser.

`CardProvider.call("dynamic_card_set_status", ..., extras)` accepts a component
and `show_status`; on supported platforms the repository can bind that widget
and insert a dynamic card. But both provider permissions (`READ_CARD`,
`WRITE_CARD`) are signatureOrSystem. `ContentProviderHelper` checks acquisition
before `call`; the absence of a per-method check is not open access. Shell's
inspected manifest does not request either permission. The card also expires
after `1200000` ms, and a second show request for an already displayed card does
not reset that timer. This is not a permanent-card API.

The app/shortcut chooser is broader: `CardAppRepository` enumerates launchable
apps and shortcuts. Yandex quick actions are a separate useful route, but are
not a replacement for a live map card. The shell RemoteViews path above is the
more direct candidate for the existing factory map slot.

### Denza Apps as the privileged companion

The car's `PermissionManagerServiceImpl.grantRuntimePermissionInternal()`
requires runtime/development (or specifically managed role) permission and a
manifest request. Otherwise it reports “not a changeable permission type” or
“has not requested permission”. These are IVI facts, not assumptions based on
generic Android behavior.

| Capability | What ADB can provide | Contribution and limit |
| --- | --- | --- |
| Location/microphone and relevant runtime grants | `pm grant`, subject to Android restriction flags | Background guidance/Alice readiness; no native map identity |
| `WRITE_SECURE_SETTINGS` | Grantable development permission when requested | Settings access; already bootstrapped by Denza Apps in `AdbPortRestore.kt`; cannot rewrite compiled allowlists |
| `SYSTEM_ALERT_WINDOW` | App-op authorization; declare the intended capability in the mod | An app-owned window, not ownership of a factory widget/provider |
| `DUMP`, `READ_LOGS`, `CHANGE_CONFIGURATION` | Development-grant candidates when requested | Diagnostics/configuration, not a universal privileged UID; individual APIs can add gates |
| Existing native widget updates | Shell helper with its own `BIND_APPWIDGET` | The concrete RemoteViews candidate above; cannot transfer shell identity to Yandex |
| Task observation and fixed task operations | Shell helper using its requested task permissions | Scene observation, launch/move/resize; Denza Apps already has narrow task helpers |
| BYD Setting/Instrument GET/SET | Not grantable through ordinary `pm grant`: IVI AutoPermission declares signature protection | Keep established car operations in the existing shell path |
| `READ_CARD`/`WRITE_CARD`, `INJECT_EVENTS`, trusted/system display permissions | Not ordinary runtime/development grants | Adding them to the mod manifest does not make the native catalog or cluster provider accept it |

`BYDAUTO_SETTING_COMMON` and `BYDAUTO_INSTRUMENT_COMMON` are normal permissions
in framework-res; the corresponding GET/SET declarations are signature
permissions in **AutoPermission**, a different APK. COMMON alone is not the
missing capability. Current app-UID failures and working shell reads are
recorded in vehicle-data findings. The existing `HudNativeSpeedLimit.kt`
demonstrates the useful split: route limit from Yandex, established shell writes
and readback in Denza Apps. Native IPC could remove its dependence on a visible
accessibility speed-sign view, without changing the vehicle-write contract.

The current Yandex builder preserves permission uses; it does not add
`WRITE_SECURE_SETTINGS` or `SYSTEM_ALERT_WINDOW`. A future grant needs the
corresponding deliberate manifest/build change. Broadly granting everything is
not necessary for either the shell widget path or semantic route IPC.

### Monkey controller as an owner-car experiment

**Keep this candidate.** The owner explicitly accepts experimental integration
on one research car. The reason to measure Monkey's effects is engineering
predictability, not a blanket requirement to meet mass-market product rules.

Shell requests `SET_ACTIVITY_WATCHER`. This firmware's
`ActivityTaskManagerService.setActivityController(controller, imAMonkey)`
throws `UnsupportedOperationException` when `imAMonkey` is false. With true it
stores an `IActivityController`. This is a controller registration, not starting
the `monkey` random-input executable: no random gestures are generated by it.

`activityStarting()` can permit or reject an attempted Activity start. A narrow
shell controller could recognize factory map opening and asynchronously launch
Yandex instead. This can reach explicit stock launches that an Android default
map preference cannot redirect. Keep stock meter/cluster/service paths separate
from main-screen map entrypoints. Return promptly; do not synchronously launch
an Activity from the callback while the system waits on it. Observe before
redirecting, and preserve normal crash/ANR handling rather than copying the
interactive `am monitor` wait loop into a permanent helper.

Limits established in the corpus:

- The controller is global and singular. `ActivityManager.isUserAMonkey()` is
  true while that controller is present. Clearing it must respect the same
  BYD gate: `setActivityController(null, true)`, not a false-flag call.
- `ActivityStarter` passes `intent.cloneFilter()`: action, data and component
  information can survive, but `extras` do not. This can redirect an opening;
  it cannot reconstruct every stock destination carried only in extras.
- `DashBroadService.startService(context, false)` suppresses a cold start in
  Monkey mode. Its ordinary `MainActivity` calls the same method with **true**,
  which does not take that suppression branch. Thus this is a specific bootstrap
  dependency, not evidence that all cluster rendering stops in Monkey mode.
- `PhoneWindowManager` ignores vendor key `380` (the fission reboot shortcut)
  in Monkey mode. This is not the navigation wheel key `320`/`321`.
- BYD `AnrHelper.isSend2Pms()` skips its extra PMS ANR-reporting path. SystemUI
  also guards bugreport actions, user switching and the flashlight tile. These
  are static branches; their relevance on this car needs observation.

No direct `isUserAMonkey` use was found in the inspected launcher source or
AmapService tree. That is bounded source evidence, not a claim that every
vehicle app/library has been audited. Native map initialization, cold start,
sleep/wake and clean controller removal are the first acceptance scenarios.

### HOME and the factory map desktop

BYD's HOME chooser has a fixed set: launcher, my-car, Gaode, Baidu and
platform-gated DriveFlow. `HomeHelper.setHome()` accepts only its generated
list. Granting secure-settings access does not add Yandex to that list.

There is a separate Android path: add a real HOME component to the mod, then
use the shell's `set-home-activity` role operation. `HomeHelper.syncRoleAndPrefer`
does not unconditionally reject a matching non-BYD role/preferred-home pair;
an aligned custom HOME is therefore a candidate, not disproved by the chooser's
allowlist. It still would not become the factory chooser's Map item, and role
reconciliation, BOS split mode, boot and the home-cycle button need testing.
For retaining the existing factory Map selection, the narrow Monkey launch
adapter is another candidate; launch loops and preserving cluster bootstrap
must be measured before treating it as a desktop replacement.

### Launch and destinations are different contracts

`PersonBean.DEFAULT_MAP_SWITCH` already supports the real Yandex package for
generic opening. Older AutoVoice code's non-stock launch path only calls
`getLaunchIntentForPackage`; its `ProtocolAidlControl` instead binds explicitly
to `com.byd.launchermap/com.autosdk.protocol.service.ProtocolService`, with
Baidu fallback if stock is missing. A default-app write cannot translate that
Binder protocol, search results, favorites or BYD-phone destination handoff.

Our replacement widget can send purpose-built Yandex actions. The official
[Navigator URL parameters](https://yandex.ru/dev/navigator/doc/ru/concepts/navigator-url-params)
document route/search URLs, including `yandexnavi://build_route_on_map` with
destination coordinates; caller registration/usage requirements must be checked
for the chosen integration. With an embedded module, explicit internal IPC is
another candidate. Neither establishes a receiver for every stock voice/cloud
command. Alice's visible-window steering-wheel integration already has its own
live evidence in shortcuts findings; hidden/cold invocation is separate work.

Other inspected branches do not supply a ready universal bridge:

- `AUTONAVI_STANDARD_BROADCAST_SEND` is a legacy TBT path; this AmapService
  disables navigation broadcast handling for the relevant DiLink product
  families. Sending common Gaode broadcasts is not a proven cluster solution.
- MapHelper's `CUSTOM_NAVI_STANDARD_BROADCAST_RECV` path logs a destination
  type; that alone is not a Yandex route handoff or a map renderer.
- Stock map code emits island-notification payload keys, but no matching
  consumer for those exact keys was found in the inspected IVI SystemUI,
  framework and launcher. Native-island availability remains unproved.
- Replacing map tile URLs or downloading another offline pack does not replace
  this AutoSDK's data engine, voice protocol or factory UI contracts.

### Proposed order of investigation

1. Establish a versioned Yandex↔Denza Apps route/render interface and reconcile
   the HUD-build marker. Reuse the current route; retain existing HUD ownership.
2. Read actual shell grants and stock widget identity; test one restored-on-exit
   RemoteViews replacement in the **existing** factory map card. Include clicks
   and a stock-update collision, not just a screenshot of our text.
3. Run a bounded, observation-only Monkey controller, then redirect exactly one
   main-screen map entrypoint. Test permitted stock MainActivity bootstrap,
   removal and ordinary application starts before extending the filter.
4. Observe factory Map/theme/display changes, then connect a dedicated Yandex
   renderer to that scene. Validate camera/ADAS precedence, exit, lost route,
   service death and sleep. Test native TBT separately against its readiness gate.
5. Explore HOME and destination-protocol adapters from the measured entrypoints.
   Treat complete native provider replacement as a deeper installation/firmware
   project if the ordinary shell paths do not cover the desired experience.

No APK builds, permission grants, role writes, controller registrations,
widget updates, restarts or vehicle writes were performed in this study.
