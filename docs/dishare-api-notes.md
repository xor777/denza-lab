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
  -n com.byd.cluster.projection.mapdemo/.DiShareProbeActivity \
  --es command probe

adb shell am start -W \
  -n com.byd.cluster.projection.mapdemo/.DiShareProbeActivity \
  --es command start_hud

adb shell am start -W \
  -n com.byd.cluster.projection.mapdemo/.DiShareProbeActivity \
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
