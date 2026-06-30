# DiShare API notes

Context: reverse pass against `com.byd.dishare` from the car image, package version
`1.5.1.1.23102ef`. Dynamic test showed DiShare can create a virtual display named
`BYD-Mirror` and move Bilibili to it.

## Exported components

- `com.byd.dishare.api.DiShareApiService`
  - action: `com.byd.dishare.api.DiShareApiService`
  - binder descriptor: `com.byd.dishare.api.IDiShareApiService`
  - looks like the mirror/source client API, not the direct HUD start path.
- `com.byd.dishare.control.DiShareControlApiService`
  - action: `com.byd.dishare.control.DiShareControlApiService`
  - binder descriptor: `com.byd.dishare.control.IDiShareControlApiService`
  - public wrapper for open/close UI, closeShare, capability checks.
- `com.byd.dishare.control.DiShareControlService`
  - action: `com.byd.dishare.control.DiShareControlService`
  - binder descriptor: `com.byd.dishare.control.IDiShareControl`
  - direct control API. This is the interesting path for HUD projection.
  - Binding must use the action. A component-only bind reaches the service record but
    `onBind()` returns null because the action check fails.
- `com.byd.dishare.DynaConfigContentProvider`
  - authority: `com.byd.dishare.DynaConfigContentProvider`
  - query on the car returned `ShowInAppList=true`.

## Direct control service transaction map

Descriptor: `com.byd.dishare.control.IDiShareControl`.

| tx | method shape | meaning |
| --- | --- | --- |
| `0x2` | `K(IDiShareListener, packageName)` | register client |
| `0x3` | `y(IDiShareListener, packageName)` | unregister client |
| `0x4` | `t(packageName)` | get screens |
| `0x5` | `f0(packageName)` | get share state |
| `0x6` | `a(provider, receivers, appName, packageName)` | start share |
| `0x7` | `x(sessionId, packageName)` | stop share |
| `0x8` | `X(sessionId, receivers, packageName)` | add receivers |
| `0x9` | `o0(sessionId, receivers, packageName)` | remove receivers |
| `0xb` | `e(screenId, packageName)` | close DiShare UI for screen |
| `0xf` | `v(screenId, appName, packageName)` | switch mirror app |
| `0x12` | `r(screenId, packageName)` | isShareCanStart |
| `0x13` | `C(screenId, packageName)` | isShareCanReceive |
| `0x16` | `o(event, bundle, packageName)` | event callback path |

Return parcelables are simple enough to parse manually:

- `DiShareScreen`: `deviceId`, `screenId`, `isAvailable`
- `DiShareState`: `sessionId`, `provider`, `receivers[]`, `sharedApp`
- `DiShareControlResult`: `Bundle` mapping screen/provider names to integer result codes

## Direct start path

`DiShareControlService.a(provider, receivers, appName, packageName)`:

1. checks API support for `packageName`;
2. looks up a registered record keyed by `packageName`;
3. maps provider screen name to source screen id;
4. resolves `ShareSourceInfo` for `(source screen id, appName)`;
5. checks `isShareCanStartWithPackage(provider, appName)`;
6. calls internal `IDiShareService.startShare(ShareRequest)`.

Internal `ShareRequest(provider, receivers, sourceId, app)` is serializable and requires
non-null provider, non-empty receivers, non-null source id, and non-null app.

Likely useful call for HUD:

```text
provider = screen_ivi
receivers = [screen_hud]
appName = com.bilibili.bilithings
packageName = com.byd.dishare
```

`packageName = com.byd.dishare` is a deliberate probe hypothesis. Registration validates
the named package signature/uid, not just the binder caller. Because `com.byd.dishare`
is system uid 1000 on the car, this may allow our callback binder to create/use the
control record.

Dynamic result from the car:

- registration with `packageName=com.byd.dishare` succeeds from our debug APK;
- `getScreens(com.byd.dishare)` returns:
  - `DiShareScreen{deviceId='fse', screenId='screen_fse', available=true}`
  - `DiShareScreen{deviceId='fse', screenId='screen_hud', available=true}`
  - `DiShareScreen{deviceId='ivi', screenId='screen_ivi', available=true}`
- `isShareCanStart(screen_ivi, com.byd.dishare)` returns `true`;
- `start(screen_ivi, [screen_hud], com.bilibili.bilithings, com.byd.dishare)`
  returned `{screen_hud=0, screen_ivi=0}`;
- the user visually confirmed that the image appeared on HUD;
- `dumpsys display` showed `BYD-Mirror`, `virtual:com.byd.dishare,1000,BYD-Mirror,0`,
  2560x1440;
- `stop(sessionId=1, com.byd.dishare)` returned `{1=0}` and removed `BYD-Mirror`;
- an immediate `getState` right after `stop` can still show stale state, but a repeat
  `probe` shortly after stop returned `state=null`.

## Probe commands

The repo probe APK now has a raw Binder activity for this service. Use the activity
entrypoint because this firmware blocks self-started manifest receivers for ordinary
app uids.

```bash
adb shell am start -W \
  -n dev.denza.mirrors/.probe.DiShareProbeActivity \
  --es command probe

adb shell am start -W \
  -n dev.denza.mirrors/.probe.DiShareProbeActivity \
  --es command start_hud

adb shell am start -W \
  -n dev.denza.mirrors/.probe.DiShareProbeActivity \
  --es command stop
```

Useful extras:

- `package`: defaults to `com.byd.dishare`
- `provider`: defaults to `screen_ivi`
- `receiver`: defaults to `screen_hud`
- `app`: defaults to `com.bilibili.bilithings`
- `session_id`: optional for `stop`; if omitted, the probe reads current state first

Watch logs with:

```bash
adb logcat -v time -s DenzaDiShareProbe DiShareControlImpl PackageValidator
```

## Historical Simulcast App Change alias path

> SUPERSEDED (2026-06-28). The sections below (alias APKs, `SourceKeeperService`,
> FLAG_NOT_TOUCHABLE overlay, native-metadata injection) are kept as history. The
> shipping path is now an **AccessibilityService** (`SimulcastAccessibilityService`)
> that reads the live DiShare `ShareDialogActivity` node bounds and erases+redraws
> the App Change row + central preview with the user's chosen apps; casting goes
> straight through `DiShareProjectionBridge` at `2560x1440`. No alias APKs, no
> `SourceKeeperService`, no metadata injection. Key gotcha: BYD firmware force-dims
> `FLAG_NOT_TOUCHABLE` overlays to alpha 0.8 (ignoring explicit alpha), so the
> opaque cover plate must be a **touchable** window. The native row comes from
> DiShare's own cloud metadata, so it appears even without our registrations.

Date/context: 2026-06-28, car package `com.byd.dishare`
`1.5.1.1.23102ef`.

The native App Change list is not built from normal launcher labels/icons alone.
Reverse and live tests showed that DiShare uses its own `ShareApp` metadata from:

- cloud endpoint: `https://video-cn.denzacloud.com/apiService/video/manager/videoList`
- local cache: `com.byd.dishare` device-protected shared preferences `config`,
  key `cloud_request_result`
- read-only config provider: `content://com.byd.dishare.DynaConfigContentProvider`

Normal shell access cannot write the DiShare cache:

- `/data/user_de/0/com.byd.dishare` is permission denied for shell
- `run-as com.byd.dishare` fails because the package is not a debug app
- the DynaConfig provider can be queried, but insert/update paths are no-op

Practical consequence found during that investigation: the native App Change row
can expose whitelisted slots, but their visual name/icon may remain the stock
Chinese/cloud entry. This is no longer the product solution; the current product
path covers the native row at the accessibility/UI layer and starts selected
apps directly through `DiShareProjectionBridge`.

Historical implementation (not the current product path):

- The old `denza-apps` implementation started `SourceKeeperService`, which
  registered source-only DiShare clients for known whitelisted package names.
- `research/simulcast-aliases/launcher` builds tiny APKs that occupy those package names.
- When native Simulcast starts an alias inside `BYD-Mirror`, the alias calls
  `DiShareProjectionBridge` to start the real Russian target package through the
  DiShare control service, then closes the alias activity.
- Source-only registrations use `2560x1440`; direct target shares use the proven
  `1024x576` path. A previous attempt to force direct targets to `2560x1440`
  caused the initial native share to open and close without moving the target app.
- Fallback `startActivity()` is disabled inside `BYD-Mirror`; the car's
  `MirrorContext` rejects non-whitelisted app starts there.

Live verification:

- Native App Change row, 2026-06-28:
  - normal `/data/app` alias packages are installed for DiShare-whitelisted
    package names such as `com.tencent.qqlive`, `com.mgtv.auto`,
    `cn.cmvideo.car.play`, `com.youku.car`, `com.qiyi.video.pad`, and
    `com.tencent.qqlive.audiobox`
  - opening stock `com.byd.dishare/.app.ui.ShareDialogActivity` and pressing
    App Change shows these slots in the native Simulcast row
  - screenshot captured at `captures/simulcast-native-app-change-row.png`
  - visible icons/text are still DiShare stock/cloud metadata, not the alias
    APK launcher icon or label
- VK slot:
  - selected native slot `com.tencent.qqlive.audiobox`
  - final `dumpsys display`: `BYD-Mirror`,
    `virtual:com.byd.dishare,1000,BYD-Mirror,0`, `1024 x 576`
  - final `dumpsys activity`: display `#23` top task
    `com.vk.vkvideo/com.vk.video.screens.main.MainActivity`
- Rutube slot:
  - selected native slot `com.mgtv.auto`
  - final `dumpsys display`: `BYD-Mirror`,
    `virtual:com.byd.dishare,1000,BYD-Mirror,0`, `1024 x 576`
  - final `dumpsys activity`: display `#25` top task
    `ru.rutube.app/.ui.activity.tabs.RootActivity`

Known limitation:

- The row may still show the stock Simulcast icons/text. Making the native list
  visually say VK/Rutube through the native adapter requires controlling
  DiShare's `ShareApp` metadata cache/cloud response or finding another
  writable normal-APK metadata entry. Installed launcher icon/label alone is
  not enough.

2026-06-28 native metadata follow-up:

- A normal third-party APK does not need system uid to participate in Simulcast.
  Live tests already prove a normal `/data/app` package can call DiShare's
  exported control/API services and launch Russian targets on DiShare virtual
  displays.
- The native visual list is a separate problem. Decompiled DiShare code shows
  `ShareAppLocalDataSource` reads `SharedPreferences("config")` key
  `cloud_request_result` and deserializes it as Base64 Java serialization of
  `List<ShareApp>`.
- `CloudRequestService` is exported and triggers `UpdateShareAppUseCase`, but
  it does not accept extras containing a custom app list. It refreshes from
  `https://video-cn.denzacloud.com/apiService/video/manager/videoList` when
  network is connected and the cached timestamp is older than one day.
- The network response schema used by DiShare is `resultCode=0` with
  `resultData[]` entries containing `packageName`, `appName`, `appIconUrl`, and
  `backgroundImgUrl`. DiShare downloads those images and stores them inside
  serialized `ShareApp` objects.
- DiShare has `targetSdkVersion=31` and `usesCleartextTraffic=true`. The icon
  URLs from `appIconUrl`/`backgroundImgUrl` can therefore be plain HTTP, but the
  hard-coded `videoList` request itself is HTTPS.
- No explicit OkHttp certificate pinning was found in the DiShare client setup,
  but target SDK 31 means a normal user CA is not enough by default. A no-root
  native-list path may still be possible through a controlled cloud-response or
  proxy experiment, but it must prove TLS trust/routing on the car before it can
  replace the overlay path.

## No-root native Simulcast row workaround

Goal: make the native Simulcast App Change flow look and behave like it has
Russian apps without patching `/system` or writing `com.byd.dishare` private data.

Confirmed blocker: DiShare does not use the installed launcher label/icon for the
native row. The adapter renders `ShareApp.appIconStr` from the cloud/cache metadata
instead. On a normal debug APK we cannot write that cache:

- `com.byd.dishare` data is not readable/writable by `shell`
- `run-as com.byd.dishare` is blocked
- `DynaConfigContentProvider` query works, but update/insert paths are no-op
- `adb backup` confirmation did not appear on the car, so backup/restore is not a
  reliable write vector at the moment

Current no-root custom drag approach:

1. `denza-apps` opens the native Simulcast screen and shows
   `SimulcastOverlayService`.
2. The overlay draws the Russian app row over the stock App Change row, using
   real installed target app icons via `PackageManager.getApplicationIcon()`.
3. The overlay also draws a large selected-app preview over the native stock
   preview, so the visible Simulcast UI presents Russian app icons even though
   DiShare's underlying `ShareApp` metadata is still stock/cloud data.
4. The overlay is touchable. It handles drag itself, draws the real target app
   icon under the finger, maps the drop point to a DiShare receiver, then calls
   `DiShareProjectionBridge.startToReceiver(...)`.
5. Receiver hit zones are based on DiShare's decoded screen coordinates from
   `window_share_layout_ivi_r`: `screen_hud`, `screen_fse`, `screen_overhead`,
   `screen_rse_l`, `screen_rse_r`, with `screen_ivi` treated as the local/source
   screen.
6. `SimulcastOverlayService` queries DiShare `getScreens` through
   `DiShareScreens` and filters drop zones to runtime-available receivers. On the
   current car the available list is `screen_hud`, `screen_fse`, and
   `screen_ivi`; rear/overhead zones are therefore not accepted even though their
   layout coordinates are known for other models.
7. On the wide IVI layout the overlay is anchored to the left DiShare panel
   width (`839dp`) instead of centering on the full physical display. This keeps
   the custom row aligned with the native App Change row when the right side is
   occupied by navigation or another app.
8. `SimulcastOverlayService` also exposes explicit debug actions for the same
   code path:
   - `dev.denza.apps.START_SIMULCAST_TARGET` with extras `targetPackage` and
     `receiver`
   - `dev.denza.apps.STOP_SIMULCAST_TARGET`
   These are for repeatable ADB verification and for the Denza Apps stop button;
   the user workflow remains opening Simulcast and dragging the visible Russian
   app icon.

Live verification:

- 2026-06-30 clean-car provisioning follow-up:
  - the failure mode was `dev.denza.apps` missing from
    `enabled_accessibility_services`; overlay app-op was already `allow`, but the
    custom Russian row cannot render until `SimulcastAccessibilityService` is
    enabled;
  - `denza-apps` self-repair now uses its generated ADB key to grant
    `SYSTEM_ALERT_WINDOW` and add
    `dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService` to secure
    accessibility settings;
  - on this car `127.0.0.1:5555` refused local ADB, while the WLAN address
    `192.168.88.204:5555` worked, so the shared `LocalAdbClient` tries loopback
    first and then local non-loopback IPv4 addresses;
  - after installing the APK and pressing Start, the app enabled its own
    accessibility service, the service was bound by system, and pressing native
    App Change produced `dev.denza.apps` `SYSTEM_ALERT_WINDOW` overlay windows
    with the VK/Rutube/Yandex Navi/VLC row visible over DiShare.
- 2026-06-30 follow-up: the accessibility service now detects the visible native
  App Change button and draws the Russian row over that area immediately, without
  clicking the native button. The central source preview overlay also extends
  lower to cover the native selected-app highlight that was visible as a pink
  Bilibili edge.
- 2026-06-28: `captures/simulcast-russian-overlay-preview-wide.png` shows the
  custom Russian row and VK Video selected preview covering the native stock
  App Change row/preview.
- 2026-06-28: dragging the first custom icon to the HUD target launched
  `com.vk.vkvideo/com.vk.video.screens.main.MainActivity` on DiShare virtual
  display `#38`, `1024x576`, with `launchedFromPackage=com.byd.dishare`.
- 2026-06-28 after dynamic screen query:
  - `getScreens(com.byd.dishare)` returned
    `[screen_hud available, screen_fse available, screen_ivi available]`
  - VK Video -> HUD launched on display `#43`, `1024x576`, from
    `com.byd.dishare`
  - Rutube -> FSE launched on display `#44`, `1024x576`, from
    `com.byd.dishare`

Repeatable debug commands after installing the current APK:

```bash
./gradlew :denza-apps:assembleDebug
tools/install_denza_apps_simulcast.sh

adb shell am startservice \
  -a dev.denza.apps.START_SIMULCAST_TARGET \
  -p dev.denza.apps \
  --es targetPackage com.vk.vkvideo \
  --es receiver screen_hud

adb shell am startservice \
  -a dev.denza.apps.STOP_SIMULCAST_TARGET \
  -p dev.denza.apps
```

Use `screen_fse`, `screen_overhead`, `screen_rse_l`, or `screen_rse_r` for the
receiver when `DiShareScreens.getScreens` reports that receiver as available.
Do not treat a failed command as product evidence if the ADB tunnel is offline
or the host cannot reach `192.168.88.204:2222`.

The one-shot receiver verifier is:

```bash
tools/dishare_overlay_receiver_test.sh com.vk.vkvideo screen_hud
tools/dishare_overlay_receiver_test.sh ru.rutube.app screen_fse
```

The older `FLAG_NOT_TOUCHABLE` overlay plus `research/simulcast-aliases/launcher`
path is parked as fallback/research history. It is not part of the normal
`denza-apps` install/build flow and cannot produce a native-looking drag preview
because the native Simulcast UI draws its own stock/Chinese icon.

Known caveats:

- Drop-zone coordinates are still a product calibration point. If BYD changes
  the Simulcast layout or another model uses a different layout family, the
  custom drag layer may need per-layout receiver bounds.
- Native DiShare metadata injection is still unresolved. The native row uses
  `ShareApp.appIconStr`/`appName` from DiShare's private cloud/local metadata,
  not the installed APK launcher label/icon.
- The no-root path is still valid. The open question is native visual metadata,
  not whether a normal APK can participate in Simulcast.

## HUD camera streaming findings

Working:

- DiShare media stream can render generated frames to HUD.
- DiShare media stream can render app-accessible Camera2 sources `0` and `1` to HUD.
- AVC AIDL `initDisplay(surface)` succeeds and reports `buffer=1`, but when the target
  surface is the DiShare encoder input surface the HUD output is black.

Not working as an ordinary `/data/app` debug APK:

- Camera2 ids `2` and `10` fail with `RuntimeException cameraId was 2/10`, even though
  AVC logs show those cameras are active in the system process.
- `com.byd.avc` runs as `android.uid.system` and has `BYDAUTO_VIDEO_*` and
  `BYDAUTO_PANORAMA_*` permissions. Our package runs as a normal app uid and does not.
- `com.byd.avc.aidl.IAVCAidlInterface` has `getCameraSurface()`, `addCamTexture()`, and
  `rmCamTexture()`, but on this firmware `getSupportPushBufferType()` returns `1`,
  `BYDAPI.getCameraSurface()` returns null, and `addCamTexture/rmCamTexture` are no-op
  stubs.
- `PIP2MeterActivity`/`PIP2MeterAlert` create a `SurfaceView` and on first frame call
  `AVCBYDAutoPanoramaDevice.startPanoramaProjection2Ins()`, which sets
  `PANORAMA_SCREEN_PROJECTION_STATUS_IVI_TO_INS_SET=1`. That is the stock projection path
  for the instrument display, not a reusable camera frame stream for DiShare.

Practical conclusion:

- Keep the default turn-signal prototype on the driver display via `AvcAidlDashActivity`.
- Treat HUD camera output as experimental unless the APK can run with system/platform
  privileges or another non-protected frame source is found.
