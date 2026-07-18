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

When adding a tool, include:

- expected ADB serial or tunnel;
- exact scenario it tests;
- known side effects;
- whether the result should update `docs/instrument-display-findings.md` or
  `docs/dishare-api-notes.md`.
