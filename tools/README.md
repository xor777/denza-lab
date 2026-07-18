# Tools

Host-side scripts for live experiments against the car.

These scripts are probes, not product code. A script can only become part of an Android app after the promotion checklist in `docs/governance.md` is satisfied.

Current scripts:

- `side_camera_overlay_monitor.sh`: older host-side monitor for side-camera windows and turn-light logcat events.
- `turn_signal_overlay_monitor.sh`: older PIP/turn-signal overlay experiment.
- `avc_alert_overlay_monitor.sh`: older AVC alert/window monitor experiment.
- `dishare_native_metadata_probe.py`: controlled host-side probe for the native
  DiShare App Change metadata path. It can export real installed Russian app
  icons, generate a `mitmproxy` addon for `videoList`, collect DiShare state, and
  explicitly set/revert the Android global proxy. Default commands are read-only.
- `dishare_overlay_receiver_test.sh`: repeatable live verifier for the current
  `denza-apps` overlay path. It starts a selected Russian target package on a
  selected DiShare receiver through `SimulcastOverlayService`, then prints
  display/window/log evidence.
- `install_denza_apps_simulcast.sh`: installs the current `denza-apps` APK,
  enables the overlay app-op, and opens the app. Requires the APK to be built
  first. (The old Simulcast alias APKs are no longer installed; the accessibility
  overlay replaced them — see `research/simulcast-aliases/`.)
- `surface_control_mirror_probe.sh`: compiles and runs the shell-UID
  `SurfaceControlMirrorProbe` against one logical display, then pulls a PNG.
  Set `ADB_SERIAL` when using a tunnel. It is read-only apart from temporary
  files under `/data/local/tmp` and is the return point for non-AVC camera
  capture research.
- `surface_control_display_overlay_probe.sh`: compiles and runs the shell-UID
  `SurfaceControlDisplayOverlayProbe` to place a cropped logical-display mirror
  on another display for at most 30 seconds. It does not call AVC AIDL, removes
  the layer on exit, and must remain a live-car probe until its z-order, copied
  controls, and black-output limitations are resolved.

When adding a tool, include:

- expected ADB serial or tunnel;
- exact scenario it tests;
- known side effects;
- whether the result should update `docs/instrument-display-findings.md` or
  `docs/dishare-api-notes.md`.
