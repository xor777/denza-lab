# Tools

Host-side scripts for live experiments against the car.

These scripts run focused experiments from a development computer. Before any
of this work moves into an Android app, it must pass the promotion checklist in
`docs/governance.md`.

Current scripts:

- `side_camera_overlay_monitor.sh`: older host-side monitor for side-camera windows and turn-light logcat events.
- `turn_signal_overlay_monitor.sh`: older PIP/turn-signal overlay experiment.
- `avc_alert_overlay_monitor.sh`: older AVC alert/window monitor experiment.
- `dishare_native_metadata_probe.py`: controlled host-side probe for the native
  DiShare App Change metadata path. It can export real installed Russian app
  icons, generate a `mitmproxy` addon for `videoList`, collect DiShare state, and
  set and revert the Android global proxy. Default commands are read-only.
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
- `fse_cross_device_probe.sh`: builds an isolated normal-UID APK and performs a
  read-only query of the stock `BYDCrossDevice` API for the passenger-screen IP
  and IVI/FSE online state. Set `ADB_SERIAL` when using a tunnel.
- `fse_cross_message_probe.sh`: builds an isolated normal-UID APK and publishes
  one caller-supplied JSON message on BYD cross feature `-13631467`. This is a
  mutating research tool: inspect the JSON before running it. The verified APK
  installation message and rollback notes live in
  `docs/fse-app-installation.md`.
- `fse_upgrade_info_probe.sh`: read-only shell-UID probe for the stock
  `upgrade_server` Binder. It requests only connection, version, and platform
  information. It contains no package transfer or upgrade commands.
- `fse_voice_command_probe.sh`: isolated client for the exported AutoVoice test
  input. It can exercise stock voice commands, but it is not a reliable launcher
  for arbitrary passenger-screen apps; the AIMP test opened the IVI app list.

The small JSON files under `fse-apk-wallpaper/` preserve the FSE resource
metadata used for the AIMP and Yandex Navigator tests. APK payloads stay outside
Git.

When adding a tool, include:

- expected ADB serial or tunnel;
- exact scenario it tests;
- known side effects;
- whether the result should update `docs/instrument-display-findings.md`,
  `docs/dishare-api-notes.md`, or `docs/fse-app-installation.md`.
