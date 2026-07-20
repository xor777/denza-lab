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

> **Archived on 2026-06-28.** The alias APK, `SourceKeeperService`,
> `FLAG_NOT_TOUCHABLE`, and native-metadata experiments below explain how the
> current design was reached. Denza Apps now reads the live
> `ShareDialogActivity` bounds with `SimulcastAccessibilityService`, redraws the
> App Change row and central preview, and casts through
> `DiShareProjectionBridge` at `2560x1440`. BYD forces non-touchable overlays to
> alpha 0.8, so the opaque cover is a touchable window. DiShare still supplies
> the underlying native row from its cloud metadata.

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

The native App Change row can expose whitelisted slots while still showing their
stock Chinese cloud name and icon. The shipped UI covers that row at the
accessibility layer and starts the chosen app through `DiShareProjectionBridge`.

Historical implementation:

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

- A normal `/data/app` package can call DiShare's exported control/API services
  and launch Russian targets on DiShare virtual displays; system uid is not
  required for that part.
- The native visual list comes from a separate data path. Decompiled DiShare
  code shows
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
- The inspected DiShare client setup contains no explicit OkHttp certificate
  pinning. Target SDK 31 still rejects a normal user CA by default. A no-root
  native-list path may still be possible through a controlled cloud-response or
  proxy experiment, but it must prove TLS trust/routing on the car before it can
  replace the overlay path.

## No-root native Simulcast row workaround

This path makes Russian apps look native in the Simulcast App Change flow while
leaving `/system` and `com.byd.dishare` private data untouched.

DiShare ignores the installed launcher label and icon for this row. Its adapter
renders `ShareApp.appIconStr` from cloud/cache metadata, and a normal debug APK
has no write access to that cache:

- `com.byd.dishare` data is not readable/writable by `shell`
- `run-as com.byd.dishare` is blocked
- `DynaConfigContentProvider` query works, but update/insert paths are no-op
- `adb backup` confirmation did not appear on the car, so backup/restore is not a
  reliable write vector at the moment

Current no-root custom drag approach:

1. `denza-apps` opens the native Simulcast screen; its accessibility service
   observes the live dialog geometry.
2. The accessibility overlay draws the Russian app row over the stock App
   Change row, using installed target icons from
   `PackageManager.getApplicationIcon()`.
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
6. `SimulcastAccessibilityService` queries DiShare `getScreens` through
   `DiShareScreens` and intersects runtime-available receivers with receiver
   nodes visible in the current accessibility tree. On the
   current car the available list is `screen_hud`, `screen_fse`, and
   `screen_ivi`; rear/overhead zones are therefore not accepted even though their
   layout coordinates are known for other models.
7. On the wide IVI layout the overlay is anchored to the left DiShare panel
   width (`839dp`) instead of centering on the full physical display. This keeps
   the custom row aligned with the native App Change row when the right side is
   occupied by navigation or another app.
8. `SimulcastOverlayService` exposes two debug actions for repeatable ADB checks:
   - `dev.denza.apps.START_SIMULCAST_TARGET` with extras `targetPackage` and
     `receiver`
   - `dev.denza.apps.STOP_SIMULCAST_TARGET`
   The user still opens Simulcast and drags a visible app icon; Denza Apps has no
   global Start/Stop control.

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

The older `FLAG_NOT_TOUCHABLE` and alias-launcher path stays in `research/` as a
fallback and record of the investigation. The normal Denza Apps build does not
use it, and DiShare's own stock icon prevents it from producing a convincing
drag preview.

Known caveats:

- Drop-zone coordinates are still a product calibration point. If BYD changes
  the Simulcast layout or another model uses a different layout family, the
  custom drag layer may need per-layout receiver bounds.
- Native DiShare metadata injection is still unresolved. The native row uses
  `ShareApp.appIconStr`/`appName` from DiShare's private cloud/local metadata,
  not the installed APK launcher label/icon.
- Direct Simulcast control from a normal APK is proven. Native visual metadata
  remains the open part of the no-root route.

### Multi-screen receiver contract (2026-07-18)

Denza Apps maps the runtime receivers as follows:

| DiShare receiver | Accessibility node |
| --- | --- |
| `screen_hud` | `ar_hud_screen` |
| `screen_fse` | `fse_screen` |
| `screen_rse_l` | `left_rse_screen` |
| `screen_rse_r` | `right_rse_screen` |
| `screen_overhead` | `overhead_screen` |
| `screen_tv` | `overhead_screen` (single rear-screen candidate) |

`screen_ivi` is the source, so it is excluded from drop targets. A temporary
`getScreens` failure leaves the Simulcast setting intact, shows a neutral screen
check, retries, and keeps unconfirmed receivers disabled. HUD and FSE are the
only receivers verified on the test car. The DiShare implementation also
exposes a distinct `screen_tv` / `deviceId=tv` contract for configurations with a
single rear display; Denza Apps maps it to the same visible rear card as
`screen_overhead`. N9 rear and overhead support is implemented from these
contracts. N9 verification still needs `getScreens`, an accessibility-tree
capture, and one isolated launch per receiver. OpenBYD enumerates Android
`Display` objects rather than DiShare receivers; seeing a rear display there is
useful hardware evidence, but availability still comes from `getScreens`.

The hidden Denza Apps diagnostic view now captures those three discovery layers
in one place. Open **Help**, tap **Как пользоваться** seven times, and read:

- `DiShare getScreens` plus one row per raw receiver, including `deviceId`,
  `screenId`, and `available`;
- one `Target` row for every supported receiver, showing the expected stock view
  id, its last observed bounds, DiShare availability, and whether the
  intersection is usable;
- every public Android `Display`, including id, name, real size, dpi, reflected
  type, and flags.

Opening the diagnostic view triggers a fresh `getScreens` query even when the
stock dialog is closed. Receiver-card rows are the last observed accessibility
snapshot, so on an N9 first open the stock Simulcast/App Change window once, then
return to Denza Apps diagnostics. This distinguishes a receiver omitted by
DiShare from a missing stock card or an Android-only rear display without
guessing a target id.

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

What this means:

- The migrated AVC AIDL renderer is still the compatibility path for the
  selected driver display, but it is not safe during a stock AVC surface
  transition. The standalone `AvcAidlDashActivity` remains only as the
  transition reference.
- Treat HUD camera output as experimental unless the APK can run with
  system/platform privileges or another non-protected frame source is found.

### Live-car side-switch safety finding (2026-07-18)

The migrated renderer reproduced the signature of the unresolved fast
side-switch failure from Denza Mirrors. After the diagnostic presentation
succeeded, the requested left-then-right test recorded `showing left`; before a
right frame appeared, the stock `com.byd.avc` process crashed twice: first with
`SIGSEGV` in `ANativeWindow_getWidth` from
`VideocatManagerImpl.native_setSurface`, then with `SIGABRT` in
`libvc_sdk_ui.so`. Mirrors were disabled and Denza Apps was force-stopped; the
next AVC process remained stable.

The first implementation tore down the AVC display, released its `Surface`, and
called `initDisplay` again for every side change. A follow-up candidate kept one
bound AVC display and one `Surface`, switched only the viewpoint, waited 350 ms
for a stable stock window, and bridged a 1,000 ms no-window gap. That candidate
failed live at 21:10 and again at 21:18 with the same `SIGSEGV` stack. Keeping a
Surface is therefore not a fix.

Decompiled stock behavior explains the repeatable failure. The stock
`PIPViewAlertController.modeChange()` compares its current `SurfaceHolder`
surface with `IPanoAPI.getSurface()` and calls its own `initDisplay(stockSurface)`
when they differ. Any successful Denza Apps AIDL `initDisplay(appSurface)` makes
them differ. The next stock mode transition then enters the vendor native
`setSurface` path and can dereference a null `ANativeWindow`. The failure is
competition for a single vendor display surface, not only timing around
left-to-right teardown.

Later live work also exposed a single-side teardown regression in the migrated
scene. At 22:06:23 the stock left window disappeared, but Denza Apps called
`freeDisplay()` while its `TextureView` surface was still attached. The enlarged
frame froze, and at 22:06:26 `com.byd.avc` aborted in `libvc_sdk_ui.so`; the frame
disappeared only after the persistent process restarted. The standalone Denza
Mirrors order was the reverse: dismiss the presentation and destroy the surface,
then free the AVC display. Restoring that order and putting cameras back on the
named `shared_fission_bg_XDJAScreenProjection_1` overlay display fixed isolated
left and right open/close cycles. The post-fix AVC PID stayed `14737` and the
crash buffer remained empty.

This restores the pause-based Denza Mirrors compatibility behavior. Rapid
left-to-right switching remains unverified after the fix and must still be
treated as the known unsafe case.

### Stock-owned non-AIDL candidate (2026-07-18)

The stock cluster projection Binder resolves the real calling package and
accepts only configured system
packages, and permits `com.byd.avc` only for
`CLUSTER_LEFT + PICTURE_IN_PICTURE_CARD`. It rejects Denza Apps and has no right
PIP contract.

A more promising path is to display an already-rendered stock window without
calling AVC AIDL. Shell UID on this firmware has `ACCESS_SURFACE_FLINGER`,
`READ_FRAME_BUFFER`, and `INTERNAL_SYSTEM_WINDOW`. The host-side
`tools/SurfaceControlMirrorProbe.java` uses
`IWindowManager.mirrorDisplay(displayId, SurfaceControl)` and
`SurfaceControl.captureLayers(...)`. Controlled live tests captured:

- display `0` at 640x400, including the Denza Apps UI;
- display `3`, including the stock cluster scene;
- a live stock left camera on display `4`;
- the live right-camera window from display `0`.

The captured camera layers had neither the secure nor protected flag. Even so,
the path was unusable in the product: the live left copy was drawn below the
car's physically composited stock display-4 card, producing a duplicate. The
right source required the stock camera window to remain on the main screen and
copied its text and controls. A compositor color transform intended to reproduce
the image enhancement yielded black output, and a later copy was not stable.
All SurfaceControl integration was removed from Denza Apps; the two host tools
remain research-only evidence.

Parked return point for a future non-AVC attempt:

1. keep both Denza Apps and standalone Denza Mirrors monitors stopped;
2. use `tools/surface_control_mirror_probe.sh` to re-confirm a single live stock
   source (`4` for the left-camera display, `0` for the right IVI window);
3. use `tools/surface_control_display_overlay_probe.sh` only for a short,
   unprocessed crop and remove it before changing sides;
4. investigate a shell-owned buffer/crop pipeline that can exclude stock text
   and controls and place its output above the stock display-4 card without
   recursively capturing its own output;
5. do not restore product integration until one isolated left and one isolated
   right cycle close cleanly, image processing works, and no AVC call or crash
   appears in the trace.
