# DiShare API notes

Context: the IVI's current firmware
(`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226`) ships `com.byd.dishare`
`1.5.1.1.1b1f648` (see [Target-screen centered aspect-fit policy](#target-screen-centered-aspect-fit-policy)).
The first reverse pass and the June alias work below were made against the June 2026 car pull,
package version `1.5.1.1.23102ef`; the HUD video path is the same in both
([hud-projection-findings.md](hud-projection-findings.md)). Dynamic test showed DiShare can
create a virtual display named `BYD-Mirror` and move Bilibili to it.

## Current state

Updated 2026-10-09. How a normal APK drives DiShare (Simulcast) on this car, how Denza Apps
draws and casts over the stock Simulcast screen, and which camera/HUD streaming routes are dead
ends.

| Claim | Status | Since | Section |
|---|---|---|---|
| The IVI firmware ships `com.byd.dishare` `1.5.1.1.1b1f648`; this doc's first reverse pass used the June pull `1.5.1.1.23102ef`; the FSE runs its own `1.5.1.1.afb8f06` (live read 2026-09-24, hud-projection-findings.md) | firmware | 2026-08-14 | [Target-screen centered aspect-fit policy](#target-screen-centered-aspect-fit-policy) |
| A normal APK binds `DiShareControlService` by its action (a component-only bind gets null from `onBind()`, [Exported components](#exported-components)), registers as `packageName=com.byd.dishare` (tx `0x2`), and `start(screen_ivi, [screen_hud], app, com.byd.dishare)` (tx `0x6`) returns `{screen_hud=0, screen_ivi=0}` with the app on the HUD via a `BYD-Mirror` display | live | 2026-06-26 | [Direct start path](#direct-start-path) |
| The product does the same: `DiShareProjectionBridge.java` registers, starts, reads state and stops as `com.byd.dishare` (tx `0x2`/`0x6`/`0x5`/`0x7`); `DiShareScreens.java` asks tx `0x4`. The bridge's tx `0xb` "close UI" had no caller and was removed on 2026-10-09: the product closes the dialog with the package-scoped `DIALOG_CLOSE` broadcast | code | 2026-10-09 | [Direct control service transaction map](#direct-control-service-transaction-map) |
| DiShare tells the API client that started a share when it stops being the mirror client: `IDiShareApiClient` tx 1 `true` during the start, `false` 0.1 s after the `605` on P→D (`captures/hud-pd-20260924T132255Z/dishare.log`) | live | 2026-09-24 | [End of a share](#end-of-a-share) |
| The product's session (`DiShareShareSession.java`) ends on that `false` once it has stood 1.5 s with no `true`, or when DiShare's process dies; the bridge then removes its client and `SimulcastOverlayService.java` clears the target, hides the exit control and refreshes the tile. The target lives in memory only (`SimulcastIntegration.java`). Every bind goes through `DiShareBinding.java`: unbound exactly once, also after `onServiceDisconnected`, and a reconnection when DiShare comes back is never delivered, so it cannot start a share again | code | 2026-10-08 | [Share session lifecycle](#share-session-lifecycle-2026-10-08) |
| A start or exit is answered on screen only by the stock dialog closing and the exit control appearing or going; a refused start keeps the dialog and our row, and DiShare's reply is the «Последний исход» row of «Технические сведения», never a toast (`SimulcastOverlayService.java`, `SimulcastScreenDiagnostics.recordCastOutcome`) | code | 2026-10-09 | [End of a share](#end-of-a-share) |
| On the Z9GT `getScreens` reports `screen_hud`, `screen_fse` and `screen_ivi` (the source); rear, overhead and `screen_tv` receivers are implemented from the contract only | live | 2026-06-28 | [Multi-screen receiver contract](#multi-screen-receiver-contract-2026-07-18) |
| A drop target is a receiver DiShare reports available whose stock card is in the accessibility tree; `ScreenTarget.java` maps `screen_hud`→`ar_hud_screen`, `screen_fse`→`fse_screen`, `screen_rse_l`/`_r`→`left_rse_screen`/`right_rse_screen`, `screen_overhead` and `screen_tv`→`overhead_screen` | code | 2026-07-18 | [Multi-screen receiver contract](#multi-screen-receiver-contract-2026-07-18) |
| "Drop zones come from the decoded `window_share_layout_ivi_r` coordinates and the row is anchored to an 839 dp panel": both are the live node bounds of the receiver cards, `app_list` and `switch_share_app` (`SimulcastDialogGeometry.java`) | refuted | 2026-06-29 | [No-root native Simulcast row workaround](#no-root-native-simulcast-row-workaround) |
| The native App Change row draws DiShare's private `ShareApp` metadata (`cloud_request_result`, refreshed from `videoList`), not launcher labels or icons; shell cannot write it (`/data/user_de` denied, `run-as` refused, DynaConfig writes are no-ops), so Denza Apps draws its own row over it | live | 2026-06-28 | [Historical Simulcast App Change alias path](#historical-simulcast-app-change-alias-path) |
| Share size: `SimulcastVideoSizeResolver.kt` copies the matched Android display's aspect onto a 2560 side, else `2560x1440`; the bridge degrades a dimension outside `180..4096` to the legacy `1024x576` | code | 2026-07-24 | [Share video size vs receiver aspect](#share-video-size-vs-receiver-aspect-2026-07-24) |
| `1b1f648` clamps the mirror to at least 16:9; centered `videoViewBounds` (`SimulcastVideoBoundsResolver.kt`) put the `2560x1440` frame at `[0,80][2560,1520]` on the IVI's `2560x1600` panel, uncropped and undistorted | live | 2026-08-14 | [Target-screen centered aspect-fit policy](#target-screen-centered-aspect-fit-policy) |
| "An aspect-matched share size removes a 16:10 receiver's black bar": DiShare clamps the request back to 16:9 (`2560x1600` asked, `2560x1440` `BYD-Mirror` made); the product centres the frame instead | refuted | 2026-08-14 | [Target-screen centered aspect-fit policy](#target-screen-centered-aspect-fit-policy) |
| Debug builds only: `SimulcastDebugReceiver`, guarded by `android.permission.DUMP`, takes `dev.denza.apps.START_SIMULCAST_TARGET` (`targetPackage`, `receiver`) and `STOP_SIMULCAST_TARGET` (`apps/denza-apps/src/debug/AndroidManifest.xml`) | code | 2026-08-20 | [No-root native Simulcast row workaround](#no-root-native-simulcast-row-workaround) |
| Dialog lifecycle comes from the accessibility service with a 320 ms disappearance grace, never from `action.byd.dishare.DIALOG_HOME`/`DIALOG_LAUNCHER`/`DIALOG_CLOSE`; the outgoing `DIALOG_CLOSE` is package-scoped to `com.byd.dishare` (`SimulcastDialogOverlay.java`, `SimulcastOverlayService.java`) | code | 2026-08-27 | [Dialog lifecycle trust boundary](#dialog-lifecycle-trust-boundary) |
| The overlay rides on the app's shared accessibility service (`SimulcastOverlayRider.kt`). With «Трансляция» switched off and nothing of the overlay's on screen or to finish, a window event starts no walk of the windows, so the «Счётчики окон» row stops counting; switching it on or off starts one at once (`SimulcastIntegration.setEnabled`), so a dialog already open is drawn without waiting for an event | code | 2026-10-09 | [No-root native Simulcast row workaround](#no-root-native-simulcast-row-workaround) |
| Self-repair over local ADB grants `SYSTEM_ALERT_WINDOW` and enables `SimulcastAccessibilityService` (`AccessibilityRepair.kt`, `SimulcastCoordinator.kt` until 2026-10-09); `LocalAdbClient.java` tries `127.0.0.1:5555`, then the car's non-loopback IPv4 addresses | code | 2026-06-30 | [Target-screen centered aspect-fit policy](#target-screen-centered-aspect-fit-policy) |
| DiShare video to `screen_hud` ends with exit `605` when HUD availability `0x38B00036` falls 2→1 on P→D at 0 km/h, and does not come back in P (hud-projection-findings.md) | live | 2026-09-24 | [hud-projection-findings.md](hud-projection-findings.md) |
| Generated frames and Camera2 ids `0`/`1` stream to the HUD through DiShare; ids `2` and `10`, which AVC uses, throw for a normal app UID, and AVC `initDisplay` into the DiShare encoder surface gives a black HUD | live | 2026-06-26 | [HUD camera streaming findings](#hud-camera-streaming-findings) |
| Never call AVC AIDL tx 8 (`getCameraSurface()`): it returns the renderer's input and releases AVC's own copy of that Surface | firmware | 2026-09-23 | [HUD camera streaming findings](#hud-camera-streaming-findings) |
| An app `initDisplay(appSurface)` arms the AVC crash: the stock `PIPViewAlertController.modeChange()` re-binds and dies in `native_setSurface` (`SIGSEGV`, `ANativeWindow_getWidth`) or `libvc_sdk_ui.so` (`SIGABRT`); removing our window before `freeDisplay()` fixed isolated cycles | live | 2026-07-18 | [Live-car side-switch safety finding](#live-car-side-switch-safety-finding-2026-07-18) |
| "One bound AVC display and one Surface, switching only the viewpoint, survives a side change": `SIGSEGV` on the car at 21:10 and again at 21:18 | refuted | 2026-07-18 | [Live-car side-switch safety finding](#live-car-side-switch-safety-finding-2026-07-18) |
| "An accessibility window-push guard can tear down before the AVC crash": it never fired in 3 of 3 fast flips, `TYPE_WINDOWS_CHANGED` arrived 5.3 s late, and it was reverted | refuted | 2026-07-25 | [Fast-switch guard trial result](#fast-switch-guard-trial-result-reverted-2026-07-25) |
| "Fast left-to-right cannot be fixed from the app and the quarantine is the accepted behaviour": tearing down on the raw turn-lever onset, 3–4 ms after it, kept AVC's PID through instrumented canaries and three fast switches each way (instrument-display-findings.md) | refuted | 2026-09-04 | [Fast-switch guard trial result](#fast-switch-guard-trial-result-reverted-2026-07-25) |
| Mirrors now: a lever onset only tears down (`MirrorSwitchPreemption.kt`), a reopen after our teardown waits `REOPEN_SAMPLES` = 2 clean polls (`MirrorTransitionReducer.kt`), and vendor `freeDisplay` runs on the `denza-avc-teardown` thread (posted by `SceneLayer.kt`, the thread started in `ClusterSceneService.kt`) | code | 2026-09-04 | [Fast-switch guard trial result](#fast-switch-guard-trial-result-reverted-2026-07-25) |
| The `TYPE_APPLICATION_OVERLAY` camera presentation threw `Window type mismatch` on every show; the attempt is gone and the presentation sets flags only (`ClusterPresentation.kt`) | code | 2026-09-04 | [Accessibility push-timing probe](#accessibility-push-timing-probe-2026-07-24-toolsa11y-window-timing-probesh) |
| SurfaceControl copies of the stock camera windows (shell `mirrorDisplay`/`captureLayers`) as a camera source: drawn under the composited display-4 card, copying stock text and controls, black under a colour transform; removed from the product | refuted | 2026-07-18 | [Stock-owned non-AIDL candidate](#stock-owned-non-aidl-candidate-2026-07-18) |

**Open questions**
- N9 rear, overhead and `screen_tv` receivers: settled by `getScreens`, an accessibility-tree
  capture and one isolated launch per receiver on that car.
- Whether any rear receiver honours a non-16:9 share size: compare the `Последний запуск` line in
  «Сервис» with the `BYD-Mirror` size in `dumpsys display` after a rear cast.
- Dialog lifecycle on the car: one real Simulcast open/close cycle, and a negative check that
  broadcasting `DIALOG_HOME`, `DIALOG_LAUNCHER` and `DIALOG_CLOSE` leaves the exit control unchanged.
- Native App Change metadata without root: a controlled `videoList` response that proves TLS trust
  and routing on the car (`tools/dishare_native_metadata_probe.py`).
- HUD camera output: needs platform privileges or a non-protected frame source for cameras `2`
  and `10`.
- A non-AVC copy of the stock camera window: the parked shell buffer/crop pipeline in
  [Stock-owned non-AIDL candidate](#stock-owned-non-aidl-candidate-2026-07-18).
- HUD video while driving: an input that survives P→D, which DiShare's does not
  (hud-projection-findings.md).
- The product's end of a share on the car: cast to the HUD, shift P→D, and the exit control
  and the tile's running state go within about two seconds; the stock exit control and a
  same-app re-cast to another screen must give `ended` and no `ended` respectively in the
  `DenzaDiShareBridge` log.

## Contents
- [Exported components](#exported-components) — DiShare's services, binder descriptors, the action-only bind.
- [Direct control service transaction map](#direct-control-service-transaction-map) — `IDiShareControl` transaction codes and return parcelables.
- [Direct start path](#direct-start-path) — what `start` checks, and the first live HUD share as `com.byd.dishare`.
- [Share session lifecycle (2026-10-08)](#share-session-lifecycle-2026-10-08) — how the product binds to DiShare, learns that its share ended, and lets go.
- [Probe commands](#probe-commands) — the legacy raw-Binder probe activity and its extras.
- [Historical Simulcast App Change alias path](#historical-simulcast-app-change-alias-path) — the archived alias APKs, where the native row's metadata lives, the native-list follow-up.
- [No-root native Simulcast row workaround](#no-root-native-simulcast-row-workaround) — the accessibility row and drop layer, dialog lifecycle, centered aspect-fit, receivers, share size.
- [HUD camera streaming findings](#hud-camera-streaming-findings) — what streams to the HUD, the AVC AIDL limits, the side-switch crash, the fast-switch guard trial, SurfaceControl copies.

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

## Share session lifecycle (2026-10-08)

How the product holds its bindings to DiShare, learns that a share it started is over, and
lets go of both.

### Bindings

Every bind to DiShare goes through `DiShareBinding.java`: the projection bridge's API and
control bindings, the current-share stopper, the UI closer and `DiShareScreens.java`. Its
rules live in `DiShareBindingState.java` and are tested there.

- Android keeps a `BIND_AUTO_CREATE` binding registered after `onServiceDisconnected` and calls
  `onServiceConnected` again once the service runs again. The bridge used to clear its
  "bound" flag on disconnect, so its cleanup never unbound, and DiShare coming back re-ran
  `createApiSource` → register (tx `0x2`) → start (tx `0x6`): the share started again by
  itself, also after the driver had pressed exit while DiShare was down.
- "`bindService` was called" is now kept apart from "connected". Release always unbinds it,
  once; only the first connection reaches the owner, and none after release.
- A disconnect while a start or a one-shot call is in flight fails it at once rather than at the
  timeout. `DiShareScreens` delivers exactly one of `onScreens`/`onFailed`.

### End of a share

Before this change the bridge logged DiShare's callbacks and dropped them, and "a share is
running" was only the product's own `last_target_package`, cleared by its own stop or next start.
A share DiShare ended itself, such as the `605` on P→D in
[hud-projection-findings.md](hud-projection-findings.md) §14.9, left the red exit control on the
driver's screen and the tile showing the cast as running.

From the IVI firmware (`captures/hud-firmware-20260923/jadx/DiShare`):

- `IDiShareApiClient` (`r/d/o/f/d0.java`) has one oneway call, tx 1 `y(boolean)`.
  `DiShareApiServiceImpl.updateMirrorClient` (`r/d/o/f/y.java`, `N0`) calls it with `true` on the
  client whose registered app became the shared app and `false` on the client that stops being
  that, on every "mirror app changed" event: the share ended (mirror app `null`) or another app
  took its place. It clears the client's mirror flag before sending `false`.
- API service calls (tx 1 create client, tx 7 remove client, tx 10 finish) are posted to DiShare's
  main thread, and creating a client reports the current mirror app to it at once
  (`onClientCreated` → `N0`). A re-cast of the same app can therefore deliver the previous
  share's `true`, its teardown's `false`, then the new share's `true`.
- Removing a client, or the client's binder dying, calls `onClientRemoved` → `x.j()`, which runs
  `exitAll` while the client is still the mirror client. Removing a client that already got its
  `false` finishes nothing.
- `IDiShareListener` (`r/d/o/c.java`): tx 2 screens, tx 3 `onShareStateChanged(DiShareState)`
  (`null` when no share runs), tx 4 a boolean. `DiShareControlImpl` (`r/d/o/j/i0.java`) keeps one
  listener per registered package name, so every registration as `com.byd.dishare` (the
  product's stopper and UI closer, the next start) replaces the previous one.

Live, 2026-09-24 (`captures/hud-pd-20260924T132255Z/`): `api active=true` 48 ms before the start
returned, `api active=false` 112 ms after DiShare removed the HUD receiver with `605`; the stock
log shows `updateMirrorClient: null` and then `DiShareControlImpl: onShareStateChanged= null`.

What the product does (`DiShareShareSession.java`, tested in `DiShareShareSessionTest`):

- Tx 1 hops to the main thread. A `false` after a `true`, with the session started, arms a
  1.5 s settle; a `true` calls it off. When it runs out the session has ended. The settle is
  what keeps a same-app re-cast from ending, and from removing a client DiShare again counts as
  its mirror client, which would stop the new share.
- DiShare's process dying (the API binding disconnects) ends a started share at once: the
  `BYD-Mirror` display lived in that process. While starting it is a failed start.
- On the end the bridge removes its client (API tx 7, the call its stop already makes, and which
  the 2026-09-24 run made 24 s after this kind of end), releases its bindings and reports
  `onEnded`. `SimulcastOverlayService.java` then does what its own stop does: clears the
  target, hides the exit control and refreshes the tile.
- Nothing toasts (since 2026-10-09). A start DiShare refuses or times out leaves the stock dialog
  open with our row rebuilt - the working choice - and DiShare's reply goes to the «Последний
  исход» row of «Технические сведения» (`SimulcastScreenDiagnostics.recordCastOutcome`); a start,
  an exit and a failed exit go there too. The toasts said «Simulcast не запустил <app>: start
  returned {screen_hud=605}» over the dialog, and «Simulcast завершен» after the exit control had
  already gone.
- Tx 3 is decoded and logged as `share state=`, not used to end anything: the registration it
  rides on is replaced by every other `com.byd.dishare` registration, and it can describe the
  share that was there before ours.
- The target being cast lives in memory for the life of the process (`SimulcastIntegration.java`),
  not in preferences. By the firmware above, our process dying removes our client and its
  `exitAll` stops the share, so a target an earlier process recorded named a share that was
  gone, and it brought the exit control and a running tile back after every restart (APK
  update, a killed process). That the share ends with our process has not been watched on the
  car; it follows from `onClientRemoved`.

## Probe commands

The repo probe APK now has a raw Binder activity for this service. Use the activity
entrypoint because this firmware blocks self-started manifest receivers for ordinary
app uids.

> **Superseded 2026-07-19:** that probe activity lives in the frozen `legacy/denza-mirrors/` source, which the root Gradle build no longer includes; the product's path to the same service is `DiShareProjectionBridge` and `DiShareScreens` in `libraries/dishare-bridge/` — see [Direct control service transaction map](#direct-control-service-transaction-map).

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

   > **Superseded 2026-06-29:** hit zones are the live accessibility bounds of the stock receiver cards (`ar_hud_screen`, `fse_screen`, …), read by `SimulcastDialogGeometry.java`; no decoded layout coordinates are used — see [Multi-screen receiver contract (2026-07-18)](#multi-screen-receiver-contract-2026-07-18).

6. The overlay (`SimulcastDialogOverlay`, on the shared accessibility service) queries DiShare `getScreens` through
   `DiShareScreens` and intersects runtime-available receivers with receiver
   nodes visible in the current accessibility tree. On the
   current car the available list is `screen_hud`, `screen_fse`, and
   `screen_ivi`; rear/overhead zones are therefore not accepted even though their
   layout coordinates are known for other models.
7. On the wide IVI layout the overlay is anchored to the left DiShare panel
   width (`839dp`) instead of centering on the full physical display. This keeps
   the custom row aligned with the native App Change row when the right side is
   occupied by navigation or another app.

   > **Superseded 2026-06-29:** the 839 dp layout profile was removed; the row is centred on the live `app_list` node, or on a box derived from the `switch_share_app` button when the list is absent (`SimulcastDialogGeometry.java`, `SimulcastDialogOverlay.java`) — see [Multi-screen receiver contract (2026-07-18)](#multi-screen-receiver-contract-2026-07-18).

8. The debug build exposes a `SimulcastDebugReceiver` bridge for two repeatable
   ADB checks. The receiver requires `android.permission.DUMP` and forwards the
   validated command to the non-exported `SimulcastOverlayService`:
   - `dev.denza.apps.START_SIMULCAST_TARGET` with extras `targetPackage` and
     `receiver`
   - `dev.denza.apps.STOP_SIMULCAST_TARGET`
   The user still opens Simulcast and drags a visible app icon; Denza Apps has no
   global Start/Stop control.

Since 2026-10-09 the overlay is one rider of the app's shared accessibility service
(`SimulcastOverlayRider`, see feature-map.md, "The accessibility service and what rides on it").
A window event of any app starts a refresh - a walk of every window, on the main thread the wheel
keys share - only while «Трансляция» is on or the overlay still has something on screen or to
finish: a row, a gesture, a close grace, an exit control held hidden. Switching it on or off starts
one at once, so a dialog already open is drawn without waiting for the next event, and a switch
turned off takes the row down.

### Dialog lifecycle trust boundary

Denza Apps does not receive the unprotected
`action.byd.dishare.DIALOG_HOME`, `DIALOG_LAUNCHER`, or `DIALOG_CLOSE`
broadcasts. On the Android 13 target a normal receiver cannot reliably recover
the original sender identity, and the firmware corpus does not identify the
legitimate sender of `DIALOG_HOME` or `DIALOG_LAUNCHER`. Depending on those
actions would therefore let an arbitrary installed app hide or restore the
floating exit control.

The already-required `SimulcastAccessibilityService` is the lifecycle source of
truth instead: a confirmed `com.byd.dishare` dialog window hides the exit control,
and a confirmed disappearance restores it. The existing 320 ms disappearance
grace treats short accessibility gaps as unknown rather than closed. Boot and APK
replacement recovery remain in a non-exported receiver with an exact three-action
allowlist. Denza Apps still sends `DIALOG_CLOSE` to close the stock dialog, but
that outgoing intent is package-scoped to `com.byd.dishare`.

> **Superseded 2026-09-04:** the recovery receiver is now `RuntimeRecoveryReceiver` with a two-action allowlist, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`; `LOCKED_BOOT_COMPLETED` was dropped (`RuntimeRecoveryActionPolicy.java`, `AndroidManifest.xml`) — see [adb-authorization-recovery.md](adb-authorization-recovery.md).

This boundary is covered by deterministic transition and boot-action tests. It
still needs live-car acceptance for a real dialog open/close cycle and a negative
check that broadcasts of all three vendor actions leave the exit control unchanged.

### Target-screen centered aspect-fit policy

Firmware fingerprint
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys`
ships DiShare `1.5.1.1.1b1f648`. Its `MirrorDisplayWrapper` clamps the mirror
virtual display to an aspect ratio of at least 16:9. Its receiver `MirrorView`
then preserves that aspect and centers the surface. A 16:9 stream therefore
occupies only `2560x1440` on a `2560x1600` panel and leaves 160 black pixels at
the bottom even though `ProviderActivity` itself is fullscreen.

Denza Apps deliberately uses centered aspect-fit for target screens: it sends a
`videoViewBounds` rectangle in the target panel's pixel coordinate space that
keeps the whole firmware stream visible without distortion. On a `2560x1600`
panel the effective `2560x1440` frame is placed at `[0,80][2560,1520]`, splitting
the unused height into equal 80-pixel fields. A `1920x1200` rear panel similarly
uses `[0,60][1920,1140]`. Wider targets are centered horizontally by the same
rule. A matched target uses its own Android display dimensions; an unmatched
target falls back to the default IVI viewport instead of assuming a rear-screen
size. Bounds dimensions outside the bridge's existing `180..4096` envelope are
rejected.

Live IVI proof on 2026-08-14 created `BYD-Mirror` at `2560x1440`; SurfaceFlinger
reported the secure receiver surface at `[0,80][2560,1520]` inside the unchanged
`2560x1600` physical display. VK Video remained uncropped and undistorted, with
equal 80-pixel fields above and below.

Aspect-fill with symmetric horizontal clipping and a non-uniform stretch were
evaluated on the IVI on 2026-08-14 but are not the product policy. Rear/FSE/
overhead panels remain model-specific live verification work; unit tests cover
their per-target geometry without claiming hardware acceptance.

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

adb shell am broadcast \
  -a dev.denza.apps.START_SIMULCAST_TARGET \
  -p dev.denza.apps \
  --es targetPackage com.vk.vkvideo \
  --es receiver screen_hud

adb shell am broadcast \
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

  > **Superseded 2026-06-29:** there are no calibrated coordinates left: drop zones are the live bounds of the stock receiver cards, so a layout change moves them with it, and a renamed card id would show up as a missing target — see [Multi-screen receiver contract (2026-07-18)](#multi-screen-receiver-contract-2026-07-18).

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
capture, and one isolated launch per receiver. A third-party projection app
enumerates Android `Display` objects rather than DiShare receivers; seeing a rear display there is
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

> **Superseded 2026-08-26:** the way in above is gone: the diagnostics are the «Сервис» tile, these rows sit in its «Трансляция» and «Экраны Android» sections (`SupportDiagnostics.kt`), and seven taps survive only on the title of the ADB explainer sheet (`AdbExplainerSheet.kt`).

Opening the diagnostic view triggers a fresh `getScreens` query even when the
stock dialog is closed. Receiver-card rows are the last observed accessibility
snapshot, so on an N9 first open the stock Simulcast/App Change window once, then
return to Denza Apps diagnostics. This distinguishes a receiver omitted by
DiShare from a missing stock card or an Android-only rear display without
guessing a target id.

### Share video size vs receiver aspect (2026-07-24)

> **Superseded 2026-08-14:** the "known risk" below is the firmware's behaviour, not a hypothesis: the IVI's DiShare `1.5.1.1.1b1f648` clamps the mirror to at least 16:9 (`MirrorDisplayWrapper`), so a 16:10 request still yields a 16:9 `BYD-Mirror`. The bar is answered by centered `videoViewBounds` with equal fields, live on the IVI; `SimulcastVideoSizeResolver.kt` still requests the matched display's aspect, and rear receivers remain unverified — see [Target-screen centered aspect-fit policy](#target-screen-centered-aspect-fit-policy).

A rear-screen user report showed a black bar at the bottom of the receiver
during casting. Working hypothesis: the fixed `2560x1440` (16:9) share size is
letterboxed by the receiver when its panel is not 16:9 (the IVI panel itself is
`2560x1600`, 16:10, so 16:10 rear panels are plausible). Denza Apps now picks
the share size per cast with `SimulcastVideoSizeResolver`: it matches the
receiver to an Android `Display` by name tokens (`fse`, `rse`, `rear`,
`overhead`, `tv`, plus `left`/`right`), copies that display's aspect onto a
2560-long side, and falls back to the proven `2560x1440` when the display is
missing, ambiguous, or produces a dimension outside the bridge's `180..4096`
range. The chosen size and the match reason appear in the cast logcat line and
in the hidden diagnostics (`Последний запуск <receiver>=...`). Not yet live-car
verified: whether DiShare accepts non-16:9 sizes on rear receivers, and whether
rear panels are present in the public display list at cast time.

Known risk from the 2026-06 "small on main" investigation: decompiled DiShare
clamps the mirror aspect to at least 16:9 (`max(1.7777, w/h)`), and a live
request of `2560x1600` produced a `2560x1440` `BYD-Mirror`. If that clamp also
applies on this firmware to rear-receiver casts, a 16:10 share request will be
coerced back to 16:9 and the bottom bar will remain; in that case the letterbox
is firmware-forced and the remaining routes are receiver-side
(`TX_SET_VIDEO_BOUNDS` semantics) or research-lane experiments. The live check
is: start a rear cast, then compare the requested size from the
`Последний запуск` diagnostics line against the `BYD-Mirror` size in
`dumpsys display`.

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
  `rmCamTexture()`, but on this firmware `getSupportPushBufferType()` returns `1`
  and `addCamTexture/rmCamTexture` are no-op stubs. The live renderer is the TS SDK
  (`TSAPI`), not `BYDAPI`: `getCameraSurface()` (tx 8) returns the renderer's camera
  **input**, and the AIDL stub writes it with `PARCELABLE_WRITE_RETURN_VALUE`, which
  releases AVC's own copy of that Surface. **Never call tx 8** - one call can break
  the stock camera feed until AVC restarts (read from the OTA image on 2026-09-23;
  see instrument-display-findings.md, "The stock turn-signal camera, read from the
  firmware").
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

> **Superseded 2026-09-04:** rapid left-to-right is no longer the unverified unsafe case: tearing our surface down on the raw turn-lever onset, which precedes the lamps AVC reacts to, kept AVC alive in instrumented canaries and in three fast switches each way on that day's drive — see [Fast-switch guard trial result: reverted (2026-07-25)](#fast-switch-guard-trial-result-reverted-2026-07-25) and instrument-display-findings.md, "Mirrors behavior preserved in Denza Apps".

The quarantine added on 2026-07-23 prevents a direct side change from issuing a
second `initDisplay()`: it closes the active app-owned presentation once, then
waits for three neutral window samples before accepting another camera session.
That protects the vendor service, but the first implementation published
`IDLE` before `Presentation.dismiss()` and the synchronous `freeDisplay()` had
finished. When the vendor call was slow, the last frame could therefore remain
visible even though the transition had already entered quarantine.

The 2026-07-24 local follow-up makes teardown two-phase. The presentation window
is removed first, `freeDisplay()` runs on the next main-loop turn, and the camera
runtime remains `STOPPING` until that work completes. Quarantine cannot recover
while runtime is `STOPPING`, and a new camera request is rejected during that
phase. This change is locally unit-tested and built but still needs one
live-car close check. It deliberately does not queue the opposite side:
automatically opening it without a confirmed neutral interval would re-enter
the known AVC crash path.

> **Superseded 2026-09-04:** the quarantine and its neutral wait are gone from the code: after one of our teardowns the next start waits for `REOPEN_SAMPLES` = 2 clean polls of one side, and a side whose start of ours failed waits until its stock card or lamps end (`MirrorTransitionReducer.kt`) — see [Fast-switch guard trial result: reverted (2026-07-25)](#fast-switch-guard-trial-result-reverted-2026-07-25).

### Live re-test with quarantine build 0.4.3 (2026-07-24 evening)

Two more live sessions settled what the quarantine can and cannot do.

- Direct left→right still crashes stock `com.byd.avc` even with the quarantine
  active: 19:39:40 `SIGABRT` in `libvc_sdk_ui` (stock main thread stopped
  answering `pause` ~95 ms after the right alert window appeared), and
  19:53:43.988 `SIGSEGV` (`SEGV_MAPERR`, fault addr `0x90`) on the stock main
  thread 174 ms after the right alert window appeared. The 19:19 crash stack
  runs through stock `PIPLeftMode.handleMessage`. The crash is armed by our
  successful `initDisplay()` and fired by the stock's own mode transition;
  no app-side gating that reacts to window appearance can fire early enough.
- The quarantine still does its narrower job: after each crash the reducer
  recovered to `ready` through the neutral gate on its own, and the isolated
  open/close cycles are clean — measured overlay reactions on 0.4.3 were
  164 ms (left open), 111 ms (left close), 452 ms (right open), 119 ms
  (right close) behind the stock window, via `dumpsys` polling.
- During the 19:39 wedge our teardown lagged ~5 s; mirror-path logcat logging
  (`DenzaMirrorMonitor`, scene `showCamera`/`hideCamera` marks) was added to
  attribute such delays.
- Timing budget for any future fast-switch fix: right-window-add to stock
  main-thread danger is ~95–174 ms. `dumpsys` polling detects at 110–600 ms
  (too late). An accessibility `TYPE_WINDOWS_CHANGED` push (~10–20 ms) plus an
  emergency surface release could fit inside the budget, but the safe teardown
  order (window removal before `freeDisplay`) needs at least one frame; this
  path is unproven and needs a dedicated probe before any product change.

### Accessibility push-timing probe (2026-07-24, `tools/a11y-window-timing-probe.sh`)

Measured on live isolated cycles with `uiautomator events` against
WindowManager `addWindow` truth: the first accessibility event arrives
**+30/+35 ms** after the stock right alert window is added and **+71 ms**
after the left PIP activity window. The vehicle-event alternative is falsified
(see `research/vehicle-events/README.md`), so this push is the earliest
app-visible trigger.

> **Superseded 2026-09-04:** only the logcat `postEvent` channels were falsified. A targeted BYDAutoLight listener, run through Denza Apps' passive local-ADB lane, delivers the raw turn-lever onset (`2` left, `4` right) about 63 ms before the lamps AVC reacts to (`MirrorSwitchPreemption.kt`), and that is the trigger in use — see vehicle-data-findings.md, "Targeted turn-signal events (2026-09-04)", and [Fast-switch guard trial result: reverted (2026-07-25)](#fast-switch-guard-trial-result-reverted-2026-07-25).

Race math: +35 ms trigger + current two-phase teardown (105–142 ms measured
via the `DenzaClusterScene` marks) = ~140–177 ms — inside the soft 174 ms
budget but past the hard 95 ms case. An emergency teardown that frees the AVC
display in the same main-loop turn as the surface release (~50–60 ms
estimated) would land at ~85–95 ms, marginally inside even the hard case.
Prerequisites for a product attempt: `notificationTimeout=0` on the
accessibility service (currently 100 ms, which would eat the whole budget),
an in-process bridge from the accessibility service to the mirror runtime,
and an explicit live trial plan, since losing the race still crashes the
stock process (same outcome as today's guaranteed crash).

Unrelated finding from the same session: the `TYPE_APPLICATION_OVERLAY`
presentation path throws `IllegalArgumentException: Window type mismatch` on
every `showCamera` on this firmware and always falls back to the normal
camera window. Functional, but the overlay attempt is dead code here and
logs a full stack per activation.

> **Superseded 2026-09-04:** the overlay attempt is removed; the camera presentation keeps the platform's `TYPE_PRESENTATION` and sets flags only (`ClusterSceneService.kt`, commit `0c6f60c9`).

### Fast-switch guard implementation (2026-07-24, reverted 2026-07-25)

> **Superseded 2026-07-25:** this guard was tried on the car the next day, never fired, and was reverted; the heading said "untested on the car" until 2026-10-03 — see [Fast-switch guard trial result: reverted (2026-07-25)](#fast-switch-guard-trial-result-reverted-2026-07-25).

Built on the probe numbers above, in three gated pieces:

- `ClusterSceneService.emergencyReleaseCamera()` frees the vendor display
  synchronously in one main-loop turn using the original verified
  window-before-`freeDisplay` order; the released runtime snapshot carries an
  `emergency` mark.
- `MirrorGuardAccessibilityService` (separate component, window-state events
  only, `notificationTimeout=0`) triggers that release when a `com.byd.avc`
  window changes while our session is `STARTING`/`READY`. The Simulcast
  service keeps its 100 ms batching. Kill switch without reinstall: remove
  the guard component from `enabled_accessibility_services`; the repair flow
  enables both owned services.
- `MirrorTransitionReducer` gives emergency-released sessions their own
  quarantine kind: after six consecutive stable polls of the newly requested
  stock window (~2–3 s) it starts that side from the queue. Second flip,
  ambiguous windows, or any non-emergency quarantine fall back to the
  confirmed-neutral wait. Everything is gated by the `fast_switch_guard`
  mirrors setting (default on).

**Outcome: the guard was tried on the car on 2026-07-25 and reverted.** See
the closing section below; the design notes here stay as the record of what
was built and why.

Trial protocol (agreed before implementation): baseline isolated cycles must
stay clean, then at most six deliberate fast left-to-right attempts with
`logcat -b crash` streaming and the FSM/guard logs captured, including at
least one run under driving load, since all probe numbers came from a parked
car. Kill criterion: stock `com.byd.avc` crashes in more than half of the
fast-switch attempts, or any new crash appears in isolated cycles — then the
guard commits are reverted and this section gets the closing numbers.
Expected win: a fast flip ends with the stock process alive and the right
mirror appearing from the queue ~2–3 s later. Losing the race keeps today's
behavior (stock crash + quarantine recovery), so the trial cannot regress
the baseline.

### Fast-switch guard trial result: reverted (2026-07-25)

Stationary trial on `0.4.3`. Baseline isolated cycles were clean (left 290 ms
/ right 212 ms overlay reaction, 110 ms teardowns, zero guard triggers, no
crashes). Three fast left-to-right attempts all crashed stock `com.byd.avc`
(`SIGSEGV`, `SEGV_MAPERR`, fault addr `0x90`) 128 ms, 100 ms and 128 ms after
its opposite window was added — and in all three the guard never fired.

The instrumented third attempt explains why, and closes the whole
app-side-trigger idea:

- while the stock app is healthy, window events reach an accessibility client
  in 6–17 ms — the push channel itself is fast, as the probe promised;
- the app-emitted `TYPE_WINDOW_STATE_CHANGED` for a stock window already runs
  98–110 ms late even in healthy cycles;
- in the crash run the system-emitted `TYPE_WINDOWS_CHANGED` events for the
  new right window were delivered **5.3 s late** (`age=4783ms`), i.e. they sat
  queued for the entire time the stock process was dying and dumping.

Accessibility delivery, `dumpsys` polling and WindowManager logcat lines all
sit behind the same WindowManager work that the stock transition itself
blocks, so no app-visible signal about the flip can exist before the stock
process has already entered the crashing path. Combined with the falsified
vehicle-event channel, there is no earlier trigger available to a normal-uid
app on this firmware.

Reverted: guard accessibility service, its evaluator, the emergency release
entry points, the `emergency` runtime mark, the emergency quarantine kind and
its queued opposite side, and the `fast_switch_guard` setting. The retired
component id stays in `SharedAccessibilityAccess`' strip list so an
installed car does not keep a dangling accessibility entry.

Kept, because both were independently proven in the same trials:

- vendor `freeDisplay` now runs on a dedicated teardown thread. The trials
  measured 4–5 s binder calls into the crash-dump zombie `com.byd.avc`, which
  previously froze the app main thread through the quarantine hide ("hide did
  not finish within 250ms" plus a 5 s stall); after the change the same
  situation logs `vendor display freed in 0ms`.
- the mirror-path logging (`DenzaMirrorMonitor` transitions/commands and the
  scene teardown marks) that made all of this diagnosable.

Standing conclusion: fast left-to-right switching cannot be fixed from a
normal-uid app on DiLink 5.1. The quarantine remains the accepted behavior —
the flip costs the right mirror for that cycle, the stock crash is not
preventable by us, and state recovery is automatic. Revisit only with
platform-signature privileges or a non-AVC frame source.

> **Superseded 2026-09-04:** there is an earlier trigger after all. The raw turn-lever onset, read through Denza Apps' passive local-ADB lane, precedes AVC's lamp-driven transition; detaching our surface 3–4 ms after it, with vendor release in 105–121 ms, kept AVC's PID through instrumented canaries and three fast switches each way on the 22:17 drive. The lever only ever tears down (`MirrorSwitchPreemption.kt`) and the quarantine is gone from the code (`MirrorTransitionReducer.kt`) — see instrument-display-findings.md, "Mirrors behavior preserved in Denza Apps".

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
