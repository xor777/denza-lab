# Instrument Display Findings

This is the durable status page for the instrument-display scene shared by
Mirrors and navigation. Current implementation status is dated 2026-07-18.

## Product architecture

`denza-apps` owns one transparent `ClusterSceneService` on the selected
instrument display:

- a full-size `SurfaceView` is the base layer for the Yandex Navigator virtual
  display;
- a `TextureView` is the camera layer and can cover the selected third without
  resizing or restarting the map;
- diagnostics are a temporary top layer and are visible only after the user
  presses **Check** in Denza Apps or chooses a display in hidden Support.

`ClusterDisplayResolver` deliberately fails closed. It uses a saved manual
override, the exact known Denza display name
`shared_fission_bg_XDJAScreenProjection_0`, `cluster`/`fission` name evidence,
real dimensions, and display characteristics. It excludes IVI, rear/RSE,
overhead, DiShare, and Denza Apps' own virtual displays. It does not fall back to
display `2` or `4`; ambiguous candidates require the hidden display check.

## Mirrors behavior preserved in Denza Apps

The migrated product path preserves the standalone Denza Mirrors renderer as
the reference behavior:

- frame width is one third of the real display plus 20 percent;
- camera position is left/right in **Sides** mode or centered in **Center** mode;
- the left camera keeps its wider left crop while the right camera remains
  uncropped;
- processing off is the normal image, while processing on uses the verified
  contrast `1.62`, brightness `28`, and saturation `0.80` matrix;
- independent top and bottom black gradients cover 20 percent of the frame and
  peak at alpha `179`;
- camera shutdown waits up to 250 ms and a failed start is retried no sooner
  than 1,500 ms;
- the invisible startup display check lasts 1 second; the colored manual check
  lasts 2.2 seconds.

The monitor now compares the stock left-camera window with the display ID
chosen by `ClusterDisplayResolver`; the old unconditional `mDisplayId=4` match
is gone. It uses the shared `dishare-bridge` local ADB client and does not import
probe code or the abandoned HUD camera path.

## Navigation projection

Denza Apps launches a minimal `app_process` under shell UID through the shared
local ADB client. A random one-time token protects a narrow Binder interface.
The interface can only create/release the Denza navigation virtual display,
find an allowlisted `ru.yandex.yandexnavi` task, move it, set its bounds/focus,
and verify its current display. It exposes no general shell command execution.

The UI state is contextual: **Open Yandex**, **To cluster**, then **Return**.
The active session is memory-only and never starts after boot. Proxy death or a
missing task releases the map surface and enters recovery; releasing the virtual
display is the final fallback that lets Android return its task to the default
display.

## OpenBYD research boundary

The locally inspected APK is `com.sr.openbyd`, version `1.0` (version code `1`),
SHA-256
`6eac698da9be9009ae14b9c53acaef070fad160b53286350e27ede08c2fc9669`.
It moves application tasks to a virtual display from a shell process. Its
display selection looks for the first `fission`/`cluster`-like display and does
not coordinate a map layer with side-camera overlays. No project license was
present in the inspected APK, so OpenBYD is research input only: no decompiled
code was copied into Denza Apps.

Denza Mirrors remains the stronger reference for camera geometry and central
placement. OpenBYD is not treated as ground truth.

## Verification status and hard stops

Local unit tests and both `:denza-apps:assembleDebug` and
`:denza-mirrors:assembleDebug` pass. Hardware-dependent behavior is not yet
accepted:

- N9 rear/overhead Simulcast receivers are implemented by contract but need
  `getScreens`, accessibility-tree, and one-receiver-at-a-time captures;
- left/right, Sides/Center, processing, preview, and camera-over-map behavior
  must be repeated on the car;
- Yandex task movement, bounds/focus restoration, proxy death, lost ADB, and APK
  restart require live testing;
- fast left-to-right turn-signal switching remains unresolved until a clean
  hardware run proves otherwise.

Any crash in `com.byd.avc` is a hard stop. Stop the monitor and collect:

```bash
adb logcat -b crash -d -v time
adb logcat -d -v time | rg "Denza|PIP2MeterActivity|CompactAlertActivity|Fatal signal"
```

Do not run standalone Denza Mirrors and the Denza Apps monitor at the same time.
The standalone module stays under `apps/` and in the default Gradle build until
the migrated path passes real-car acceptance. Only then may a separate commit
move it to `legacy/denza-mirrors`.

## Failed or research-only paths

- Direct BYD vehicle/light getters are permission-blocked for an ordinary debug
  APK or did not deliver useful callbacks.
- HUD camera streaming through DiShare can render generated or app-accessible
  Camera2 frames, but protected AVC/side-camera frames were black or unavailable.
- The old `HudDiShareActivity`, map demos, and `.probe` camera paths are not part
  of the Denza Apps product implementation.
