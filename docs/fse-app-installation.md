# Passenger-screen (FSE) app installation

Status: **working live-car path**, verified on 2026-07-20 on a Denza Z9GT and
available as an explicit user action in Denza Apps 0.3.0. The host-side probes
remain useful for protocol research and recovery.

## What the passenger screen is

On the tested Z9GT the passenger screen is not Android user `999` on the main
head unit. It is a separate Android 12 system called FSE, connected to the
DiLink 5.1 / Android 13 IVI over an internal network:

The read-only `upgrade_server` probe reported FSE MCU `42.2.3.2511110.2`, SoC
`42.1.8.2511241.1`, and fingerprint
`BYD-AUTO/FSE/FSE:12/SQ3A.220605.009.B1/eng.build.20251201.033803:user/release-keys_denza`.

| System | Address | Evidence |
| --- | --- | --- |
| Main IVI | `192.168.195.2` | IVI interface and live cross-service traffic |
| Passenger FSE | `192.168.195.17` | FSE heartbeat, SMB mount, and `BYDCrossDevice` query |

The Spanish `adb install --user 999 ...` instructions seen for some BYD models
therefore do not describe this vehicle. `pm list users` on the IVI has only user
`0`; FSE ADB ports `5037` and `5555` were closed.

## Verified transport

Two stock channels form the working path:

1. **SMB carries the APK.** FSE exports `/storage/emulated/0` as `fse-insd`.
   The IVI already mounts it read/write at `/storage/FFFF-FFFC` (and equivalent
   pass-through paths).
2. **The BYD cross-device bus triggers installation.** FSE port `6666` begins
   with the ZMTP greeting `ff00000000000000017f`. Netcat relays were useful to
   identify that ZeroMQ transport, but the working probe does not construct raw
   ZMTP packets. It calls the stock `android.cross.device.BYDCrossDevice` API,
   which publishes the message on cross feature `-13631467` (`0xff300015`).

An ordinary `/data/app` probe with only `android.permission.INTERNET` could read
FSE state and publish this feature through reflection. The privileged package
installation itself runs inside the stock FSE `WallpaperHomeFse` package.

## Why the wallpaper service installs APKs

The stock FSE wallpaper provider supports wallpaper type `14`. A live
`get_wallpapercenter_version` request returned support ids
`[1, 9, 15, 2, 3, 12, 14, 4, 13]`.

For type `14`, `WallpaperHomeFse`:

1. reads `config.json` from the supplied resource root;
2. finds the first `.apk` under `wallpaper/` (or an orientation-specific
   wallpaper subdirectory);
3. creates and commits a local `PackageInstaller` session on FSE;
4. reports the result to IVI over the same cross-device feature.

The exact resource layout that worked on this firmware is:

```text
/storage/emulated/0/denza-install-<name>/
├── config.json
└── wallpaper/
    └── Application.apk
```

From the IVI, the same files are visible at:

```text
/storage/FFFF-FFFC/denza-install-<name>/
```

Important live finding: placing the files only under an extra `fse/`
subdirectory returned `result=0` in about 0.6 seconds and did not install the
app. Putting `config.json` and `wallpaper/` directly in the resource root
returned `result=1`.

## Message format

After staging the files, publish this JSON through
`tools/fse_cross_message_probe.sh`:

```json
{
  "fromDevice": 1,
  "toDevice": 2,
  "function": "wallpaper",
  "provider_method": "set_wallpaper_path",
  "wallpaper_path": "/storage/emulated/0/denza-install-example",
  "wallpaper_type": 14,
  "theme_id": 909016,
  "wallpaper_service": "dev.denza.fse.install.example/.NoSuchWallpaperService",
  "app_version_name": "1.0",
  "app_version_code": 1
}
```

The working probe metadata for the two accepted tests is kept under
`tools/fse-apk-wallpaper/`. The APK payloads are intentionally ignored and must
never be committed.

Run the cross-device probe against a tunnel-selected IVI like this:

```bash
export ADB_SERIAL=127.0.0.1:15552

tools/fse_cross_device_probe.sh

tools/fse_cross_message_probe.sh \
  '{"fromDevice":1,"toDevice":2,"function":"wallpaper","provider_method":"get_wallpapercenter_version"}'
```

For an installation, use a fresh resource directory and `theme_id`, inspect the
JSON carefully, then pass the complete `set_wallpaper_path` message. A stock
success response looks like:

```json
{
  "fromDevice": 2,
  "toDevice": 1,
  "function": "wallpaper",
  "action": "android.intent.action.using_wallpaper_result",
  "result": 1,
  "res_id": 909015
}
```

`result=0` is a failure even when the sender-side `BYDCrossDevice.set()` returned
`0`; the latter only means the IVI cross service accepted the outgoing event.
Set `FSE_CROSS_WAIT_SECONDS` for large APKs; Yandex Navigator needed about 18
seconds before the FSE response arrived:

```bash
FSE_CROSS_WAIT_SECONDS=60 tools/fse_cross_message_probe.sh "$INSTALL_JSON"
```

## Live verification

Both packages were copied through the existing SMB mount, installed by the FSE
wallpaper provider, appeared in the passenger launcher, and were opened manually
by the user:

| App | Package / version | APK size | SHA-256 | Result |
| --- | --- | ---: | --- | --- |
| AIMP | `com.aimp.player`, `v4.31.1740` | 20,497,869 bytes | `e16c00a15ab86346a959654107b5d97b2ae9b4c40801713bfa2851232c55e6dd` | installed; user launched it on FSE |
| Yandex Navigator | `ru.yandex.yandexnavi`, `29.8.1` (`739494300`) | about 352 MiB | `3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3` | `result=1` after about 18 seconds; user launched it on FSE |

Yandex Navigator was a monolithic `base.apk` on IVI. A package delivered as
multiple split APKs is not yet supported by this probe path because the stock
wallpaper installer selects one APK file.

## Denza Apps flow

Denza Apps 0.3.0 adds the **Установить приложение** card in the second row. It:

1. lists non-system launcher applications installed on the main IVI, including
   their real icon and version; BYD service packages and Chinese-labelled apps
   are omitted from this user-facing list;
2. checks whether the selected package has a single readable base APK;
3. copies that APK through the existing FSE SMB mount in synchronous 4 MiB
   blocks, reports real progress, and verifies the exact final byte size;
4. sends the stock `set_wallpaper_path` request and waits for the matching
   `res_id` result;
5. removes the staged APK after an explicit success or failure response.

Every installation requires a tap on a particular application. There is no
background batch installation. Apps delivered as split APKs remain visible in
the picker but are marked **Split APK пока не поддерживается** and are not sent
to FSE. If confirmation times out, the UI reports an uncertain result and keeps
the staging details in diagnostics instead of claiming success.

## Known limitations and cleanup

- Installation and launching are separate. The exported AutoVoice automation
  input is not a reliable arbitrary FSE launcher: `打开AIMP` opened the IVI app
  list. The accepted verification used the passenger launcher manually.
- This reuses an OEM wallpaper installation path. It may update internal FSE
  wallpaper metadata even when the installed package is not a wallpaper. No
  visible wallpaper regression occurred in the accepted tests, but this side
  effect has not been exhaustively characterized.
- There is no verified remote FSE uninstall command yet. Use the passenger
  screen's normal application management UI for rollback.
- Delete staged resource directories from `/storage/FFFF-FFFC` after they are no
  longer needed. The installed package is independent of the staged APK after a
  successful `PackageInstaller` commit.
- Do not turn the explicit Denza Apps action into background, batch, or
  unattended installation without a separate product and security decision.
