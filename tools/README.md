# Tools

Host-side scripts for live experiments against the car.

These scripts run focused experiments from a development computer. Before any
of this work moves into an Android app, it must pass the promotion checklist in
`docs/governance.md`.

Current scripts:

- `side_camera_overlay_monitor.sh`: older host-side monitor for side-camera windows and turn-light logcat events.
- `turn_signal_overlay_monitor.sh`: older PIP/turn-signal overlay experiment.
- `avc_alert_overlay_monitor.sh`: older AVC alert/window monitor experiment.
- `a11y-window-timing-probe.sh`: captures accessibility window-push timing for
  the side-camera transition investigation. It is a diagnostic observer, not a
  production trigger; avoid running it beside another high-frequency
  accessibility client.
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
  capture research. For a GL navigator, capture the app-owned `Denza
  Navigation` display directly to inspect its real layout; a display-3 capture
  can be black or stale at the nested `SurfaceView` boundary and should be used
  separately only to verify cluster placement.
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
- `navi_role_probe.sh`: hands the map role to a target package and reports the
  component the system actually starts. Expects `ADB_SERIAL` (the local tunnel,
  e.g. `127.0.0.1:15555`). It writes `DEFAULT_MAP_SWITCH` through the exported
  `content://com.byd.autovoice` provider and `Settings.Global.byd_map_package`,
  then restores both on exit, including on error or Ctrl-C. Side effects: while
  it runs, voice navigation and the Shortcuts `Navi` commands point at the target
  package. It needs a manual navigation trigger and **must not** be triggered
  with key code `321` — that key is the configurable steering-wheel custom key
  and on this car starts an APA parking scan. Results update
  `docs/shortcuts-automation-findings.md`.
- `night_vision_probe.sh`: build/install/preflight wrapper for the isolated
  front-camera source evaluator. Its original `start` flow predates the accepted
  stock warm-handoff sequence; use it for bounded research evidence, not as a
  product camera control.
- `single_package_split_probe.sh`: guarded live-car wrapper for the disposable
  one-package split experiment. It snapshots SmartMulti state, operates only on
  exact probe components, and never invokes runtime allowlist transaction 125.
- `speaker_lift_probe.sh`: builds and runs the shell-UID `SpeakerLiftProbe`
  (app_process dex, no APK install) for the Devialet pop-out cover
  investigation: read-only FID/dumpsys `capture`, `focus`/`tone` stream-14
  impersonation, intArray-shaped FID SETs via the framework's own client, and
  `AudioSystem.setParameters`. SETs never hand-craft autoservice parcels.
  Default serial `127.0.0.1:5555`; durable results belong to
  `docs/speaker-lift-findings.md`.
- `audio_fid_sweep.sh` + `audio-fids-dev1002.txt`: read-only sweep of every
  audio FID this firmware defines (762 entries, generated from the car's own
  `com.byd.feature.audio.Audio` catalog), plus the media-state FIDs on devices
  1007/1023. `capture` snapshots a state, `diff` compares two. Only
  `autoservice` transact 5. Use it instead of eyeballing a handful of FIDs: a
  sweep across a real cover raise showed the amp reports nothing at all.
- `speaker_lift_ab.sh`: wraps any action in a before/after sweep and prints the
  delta, e.g. `speaker_lift_ab.sh localpulse tools/speaker_lift_local_pulse.sh
  play-path <path>`.
- `vehicle_speaker_pulse.sh` + `VehicleSpeakerPulse.java`: shell-UID
  `app_process` probe for the stock "IVI uses the vehicle speaker" path —
  `setUseVehicleSpeaker`, `isStreamAllowed`, and a plain `MediaPlayer` on a
  local file. Every run prints the audio-scene and flip FIDs before and after
  and tails the broker's own logcat lines, so a negative result is provably a
  negative and not a call that never fired. `prepare` builds and pushes without
  firing; `run dryrun` resolves every symbol and warns when
  `requestUseVehicleSpeaker` would be a no-op. All three modes were falsified
  as cover motors on 2026-08-25.
- `speaker_lift_local_pulse.sh`: runs the separately verified stock
  MediaCenter LOCAL `playById`/pause path. It does extend the covers, but it is
  superseded: `AUDIO_RLSA_STATE_SET` (`0x16300025`, `1` out / `2` in) moves them
  both ways silently. Keep this one as the stock-shaped positive control. Its
  input is an already indexed local track's canonical IVI path or signed
  MediaCenter music ID.
- `speaker_lift_yandex_probe.sh`: builds and owns the disposable normal-UID
  `speaker-lift-yandex-probe` APK. `install` preserves existing accessibility
  services, prepares the exact proven one-second LOCAL chime, and enables a
  no-Activity observer that pulses MediaCenter when `ru.yandex.music` opens.
  `disable` and `uninstall` remove only this probe's component/package.
- `smartmulti_state.sh`: `snapshot` records the exact SmartMulti persistent
  pair, gate, area, resizeability lease, package support, Denza split
  preferences, and live roots. The explicitly mutating `reset` action restores
  either verified non-Huawei clean tuple (`com.byd.sr + com.byd.launchermap`,
  mode `100`; or the firmware-written Home tuple
  `com.android.launcher3 + com.byd.launchermap`, mode `102`) through
  SmartMulti's package-change fallback for the experimental picker,
  restores Denza's resizeability lease, clears only Denza split preferences,
  and returns Home. Runtime transaction-125 additions still require a
  controlled reboot and are reported as residue.

The small JSON files under `fse-apk-wallpaper/` preserve the FSE resource
metadata used for the AIMP and Yandex Navigator tests. APK payloads stay outside
Git.

When adding a tool, include:

- expected ADB serial or tunnel;
- exact scenario it tests;
- known side effects;
- which closest focused page under `docs/` owns the durable result.
