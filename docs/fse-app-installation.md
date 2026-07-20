# Passenger-screen (FSE) app installation

Status: **working on the test car**. The path was verified on 2026-07-20 on the
author's Denza Z9GT and is available from Denza Apps 0.3.0. Host-side probes are
kept for protocol work and recovery.

## What the passenger screen is

The passenger screen in the tested Z9GT runs its own Android 12 system, called
FSE. It talks to the DiLink 5.1 / Android 13 IVI over an internal network.

The read-only `upgrade_server` probe reported FSE MCU `42.2.3.2511110.2`, SoC
`42.1.8.2511241.1`, and fingerprint
`BYD-AUTO/FSE/FSE:12/SQ3A.220605.009.B1/eng.build.20251201.033803:user/release-keys_denza`.

| System | Address | Evidence |
| --- | --- | --- |
| Main IVI | `192.168.195.2` | IVI interface and live cross-service traffic |
| Passenger FSE | `192.168.195.17` | FSE heartbeat, SMB mount, and `BYDCrossDevice` query |

The IVI itself exposes only Android user `0`, and the FSE ADB ports `5037` and
`5555` were closed. The often-shared `adb install --user 999 ...` recipe belongs
to a different BYD screen architecture.

## Where the investigation started

The first lead was a forum post about Leo 5 / Leo 8. This is the original
Spanish text as it was shared with us:

> instalar aplicaciones en el segundo monitor. Leo 5 rest con lidar, Leo8.
>
> Conectar el equipo y la laptop al punto de acceso telefónico. En la laptop,
> abrir la aplicación ADBAppControl-1.8.6, mirar la dirección IP del equipo en
> el teléfono e introduje esta IP en la aplicación (esquina superior derecha).
> La aplicación identificó el equipo y me pidió instalar algo adicional para
> ver los íconos de las aplicaciones, pero me acobardé y me negué. Luego abrí la
> pestaña de la consola y todo sigue según las instrucciones publicadas aquí.
>
> Comando para mostrar códigos de monitor:
>
> Usuarios de la lista de Adb shell pm
>
> Después de eso, veremos lo siguiente:
>
> UserInfo(0:Owner:c13) en ejecución
>
> UserInfo{999:doubleinstance:1030] en ejecución
>
> Segundo monitor 999
>
> Comando para instalar la aplicación:
>
> Adb install --user 999 E:VAIMP.apk
>
> El apk estaba en la raíz de mi disco E, el disco es una unidad flash conectada
> al portátil.

We tried that route first. It was useful as a clue that BYD has more than one
passenger-screen design, but the Z9GT evidence pointed elsewhere: no user `999`,
no reachable FSE ADB, and a separate Android system on the internal network.
That led to the stock SMB and cross-device services described below.

## Verified transport

Installation uses two stock channels already present in the car:

1. **SMB carries the APK.** FSE exports `/storage/emulated/0` as `fse-insd`.
   The IVI already mounts it read/write at `/storage/FFFF-FFFC` (and equivalent
   pass-through paths).
2. **The BYD cross-device bus triggers installation.** FSE port `6666` begins
   with the ZMTP greeting `ff00000000000000017f`. Netcat helped identify the
   ZeroMQ transport. The installer itself calls the stock
   `android.cross.device.BYDCrossDevice` API and publishes on cross feature
   `-13631467` (`0xff300015`), so it never has to assemble raw ZMTP packets.

A normal `/data/app` probe with `android.permission.INTERNET` could read FSE
state and publish the feature through reflection. The stock FSE package
`WallpaperHomeFse` performs the privileged `PackageInstaller` work.

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

The directory depth matters. An extra `fse/` level produced `result=0` after
about 0.6 seconds and installed nothing. With `config.json` and `wallpaper/`
directly in the resource root, FSE returned `result=1`.

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

Metadata from the AIMP and Yandex Navigator probes lives under
`tools/fse-apk-wallpaper/`. APK payloads stay out of Git.

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

AIMP and Yandex Navigator were copied through the existing SMB mount, installed
by the FSE wallpaper provider, and opened from the passenger launcher:

| App | Package / version | APK size | SHA-256 | Result |
| --- | --- | ---: | --- | --- |
| AIMP | `com.aimp.player`, `v4.31.1740` | 20,497,869 bytes | `e16c00a15ab86346a959654107b5d97b2ae9b4c40801713bfa2851232c55e6dd` | installed; user launched it on FSE |
| Yandex Navigator | `ru.yandex.yandexnavi`, `29.8.1` (`739494300`) | about 352 MiB | `3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3` | `result=1` after about 18 seconds; user launched it on FSE |

Yandex Navigator was a monolithic `base.apk` on IVI. A package delivered as
multiple split APKs is not yet supported by this probe path because the stock
wallpaper installer selects one APK file.

The integrated Denza Apps flow was later checked with Kinopoisk. Its large APK
showed live copy progress, completed the same FSE request, and appeared on the
passenger screen. The exact APK version and hash were not recorded during that
UI test, so they are not added to the evidence table above.

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

The user starts each installation by tapping an app; Denza Apps has no batch or
background installer. Split APK packages remain visible in the picker with the
label **Split APK пока не поддерживается**. If FSE does not answer before the
timeout, the UI reports the missing confirmation and leaves the staging path and
request ID in diagnostics.

## Known limitations and cleanup

- Installation stops at adding the app to FSE. The AutoVoice command `打开AIMP`
  opened the IVI app list during testing, so launches were checked manually from
  the passenger screen.
- This reuses an OEM wallpaper installation path. It may update internal FSE
  wallpaper metadata even when the installed package is not a wallpaper. No
  visible wallpaper regression occurred in the accepted tests, but this side
  effect has not been exhaustively characterized.
- Remote uninstall has not been mapped. Use the passenger screen's application
  management UI for rollback.
- Delete staged resource directories from `/storage/FFFF-FFFC` after they are no
  longer needed. The installed package is independent of the staged APK after a
  successful `PackageInstaller` commit.
- Background, batch, or unattended installation needs a separate product and
  security decision before it is added.
