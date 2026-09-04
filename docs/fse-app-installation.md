# Passenger-screen (FSE) app installation

Status: **working on the test car**. The path was verified on 2026-07-20 and
requalified after the 2026-07 firmware update on 2026-08-14. It is available
from Denza Apps 0.3.0. Host-side probes are kept for protocol work and recovery.

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

For type `14` (`WALLPAPER_TYPE_APK`), `WallpaperHomeFse` on FSE:

1. reads `config.json` from the supplied resource root (it can override
   `wallpaper_type`, `theme_id`, `wallpaper_service`, and APK version fields
   from the JSON);
2. `ApkWallpaperManager.findApkPath` takes the **first** `*.apk` under the
   policy subdirectory list (live-proven: `wallpaper/`);
3. opens one `PackageInstaller` session (`SessionParams(1)` = full install)
   and writes that single file as `"SilentInstaller"` — splits are not
   assembled;
4. after a successful commit it tries to bind `wallpaper_service`. A dummy
   component such as `.NoSuchWallpaperService` yields result **`-7`**
   (`FAIL_APK_SERVICE_INVALID`) **after the package is already installed**.
   That is the 2026-07 firmware `-7` that Denza Apps already treats as
   present, not an installer failure.
5. reports the result to IVI over the same cross-device feature.

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

### 2026-07 firmware compatibility

The 2026-08-14 requalification used IVI build
`eng.build20260705.011226` and FSE SoC `42.1.8.2605219.1` / build
`eng.build.20260708.175801`. Two compatibility changes were required:

- the legacy interactive ADB shell started echoing the command and a PTY prompt;
  `LocalAdbClient` now brackets command output with explicit control-character
  markers and excludes the prompt and echoed command;
- FSE responses still arrive on cross feature `0xff300015`, but the IVI no
  longer mirrors their JSON to `Launcher.CrossUtil`. Denza Apps registers an
  `IBYDCrossListener` before sending and waits for the matching `res_id`
  callback directly.

This firmware returned `result=-7` after a fresh RUTUBE installation, while the
package was visibly present on the passenger screen. The integrated flow treats
both the stock `result=1` and this firmware-qualified `result=-7` as an installed
outcome. Other numeric results remain failures, and a missing callback remains a
timeout. The classification is based on live package visibility rather than the
same numeric value's meaning in the public Android package-manager constants.

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
5. removes the staged APK after an explicit success or failure response;
6. before a later installation, removes only abandoned
   `/storage/FFFF-FFFC/denza-apps-install-*` resources left by a timeout or
   interrupted process.

The user starts each installation by tapping an app; Denza Apps has no batch or
background installer. Split APK packages are left out of the chooser, whose foot says
in the driver's words that only installable applications are listed; the reason
**Split APK пока не поддерживается** stays on the application record for the
support screen. If FSE does not answer before the
timeout, the UI reports the missing confirmation and leaves the current staging
path and request ID in diagnostics. The next installation removes that abandoned
directory before creating its own, so repeated failures cannot accumulate APKs.

### Passive split-package diagnostics

The hidden support screen reports the installed APK layout without using ADB or
contacting the passenger screen. Open it with seven quick taps on the
**Трансляция** card header. Its `FSE APK layouts` row gives the candidate/split/
monolithic totals. Each split package then has three rows containing:

- package, label, version, launcher split, base filename and base file state;
- `PackageInfo.splitNames` in the order Android reported them;
- the corresponding split filenames, byte sizes, and `missing` or
  `not-readable` states.

This is intended for remote vehicle reports where shell access is unavailable.
A screenshot is enough to distinguish real configuration/ABI splits from an OEM
PackageManager mismatch. The report is passive: it does not relax the installer
gate or attempt a base-only installation.

## Known limitations and cleanup

- Installation stops at adding the app to FSE. The AutoVoice command `打开AIMP`
  opened the IVI app list during testing, so launches were checked manually from
  the passenger screen.
- This reuses an OEM wallpaper installation path. It may update internal FSE
  wallpaper metadata even when the installed package is not a wallpaper. No
  visible wallpaper regression occurred in the accepted tests, but this side
  effect has not been exhaustively characterized.
- Remote uninstall is present in the FSE wallpaper provider as
  `provider_method: uninstall_wallpaper` with `package_name`, which calls
  `PackageInstaller.uninstall`. The cross-message switch in this build looks
  like it may invoke `reset_wallpaper` instead of that method (JADX of
  `CrossMessageHandler`). Do not productize uninstall until a live probe
  confirms which method actually runs. Until then, use the passenger screen's
  application management UI for rollback.
- Denza Apps owns and automatically prunes only its
  `/storage/FFFF-FFFC/denza-apps-install-*` directories. Manual probe resources
  such as `denza-install-*` remain an operator cleanup responsibility. The
  installed package is independent of the staged APK after a successful
  `PackageInstaller` commit.
- Background, batch, or unattended installation needs a separate product and
  security decision before it is added.

## Other install channels (FSE firmware `42.1.8.2605219.1`, 2026-08-25)

The Downloads FSE zip is a real A/B OTA (`update.zip` / `payload.bin`). Corpus
is the FSE `system` image from that payload (`priv-app` /
`PackageInstaller_ui_platformized`, `WallpaperHome`, `BydAppStore`,
`RapidUpdateService`, `FileManager_Platform`). IVI copies of the same
families exist and were used only to name packages; they are not FSE
installers.

WallpaperHome on FSE (`com.byd.wallpaperhome`,
`sharedUserId=android.uid.system`) is the type-14 installer. Its
`CrossMessageHandler` still accepts only `function=wallpaper` on
`-13631467`. `openWrite("SilentInstaller", …)` is a **PackageInstaller
session file name**, not a package. FSE `priv-app` and `app` listings have
**no** `com.byd.silentinstaller`. The USB factory APK of that name lives on
the IVI, not on this FSE image.

| Channel | What it is | Usable from Denza Apps? |
| --- | --- | --- |
| Wallpaper type 14 + cross `-13631467` | OEM remote APK install on FSE (system-uid `PackageInstaller` session) | **Yes** — current product path |
| `uninstall_wallpaper` on the same bus | `PackageInstaller.uninstall(package_name)` | **Maybe** — live probe required |
| `content://com.byd.wallpaper` `call()` | Same provider, FSE-local | No — not reachable from IVI without the cross bus |
| FSE `com.byd.appstore` `silenceInstall` | Privileged session via `InnerInstallReceiver` | No — FSE-local, signature `BYDSTORE_INNER_INSTALL` |
| FSE `com.byd.appstore` catalog / AutoVoice `ST_DOWNLOAD_APP` | Opens store detail / download UI for a **listed** app | No FSE push of an arbitrary APK; not an IVI API |
| FSE `com.byd.updated` (RapidUpdate) | SOTA / bundle / APEX with `INSTALL_PACKAGES` | No — cloud SOTA, not a local APK drop |
| `upgrade_server` / `CROSS_ID_OTA_IVI2FSE` | Firmware ZMQ (`PackageInfoCmd` path/size/md5) | Firmware zip only. Do not send an APK as an OTA |
| Cross `function: skin` | Constant only; handler is wallpaper-only | No |
| File Manager `ACTION_VIEW` on an APK | Hits FSE PackageInstaller UI | **Blocked** — market-only referrer gate (below) |
| IVI `com.byd.silentinstaller` | USB `/storage/usbotg/SilentInstaller/*.apk` on **IVI** | No — package is not on this FSE image |

There is no second hidden remote PackageInstaller API for an arbitrary APK.
Type 14 is the stock IVI→FSE app install. The only likely extra on that
same bus is uninstall.

Provider result codes (`StaticValue.ResultCode`):

| Code | Name |
| --- | --- |
| `1` | `SUCCESS` |
| `0` | `FAIL` |
| `-2` | `FAIL_TYPE_NOT_SUPPORT` |
| `-3` | `FAIL_RESOURCE_INVALID` |
| `-4` | `FAIL_TRANSFER_ERROR` |
| `-5` | `FAIL_APK_PATH_EMPTY` |
| `-6` | `FAIL_APK_ITEM_INVALID` (bad `wallpaper_service` / path) |
| `-7` | `FAIL_APK_SERVICE_INVALID` (APK committed; wallpaper service missing) |
| `-8` | `FAIL_APK_INSTALL_FAILED` |

Do not add a second installer in Denza Apps. If uninstall is promoted, it
belongs on this same wallpaper cross channel after a watched FSE probe.

## FSE PackageInstaller is market-only

Copying an APK over SMB and opening it in File Manager does **not**
install it. The FSE PackageInstaller UI (`com.android.packageinstaller`,
`InstallStart`) is patched:

1. File Manager (`com.byd.filemanager`) has
   `REQUEST_INSTALL_PACKAGES` only. Opening an `*.apk` is
   `ACTION_VIEW` + MIME `application/vnd.android.package-archive`, which
   lands on `InstallStart`.
2. `BydUtils.isShowWarningDialog` reads `Activity.mReferrer`. It allows
   the stock install flow only when the referrer is `null` or
   `com.byd.appstore`. Any other caller (File Manager, a browser, Denza
   Apps if it were on FSE) is rejected.
3. The rejected path is `triggerInstallRiskPrompt`: a non-cancelable
   `BydAlertBuilder` with a single OK button that **finishes the
   activity**. It does not offer unknown-sources, Settings, or "install
   anyway".

Default English copy (`byd_install_warning_dialog_msg`):

> In order to ensure system security and good user experience,
> applications other than the application market cannot be installed,
> please download and use applications from the application market.

Chinese: `无法安装应用市场以外的应用，请用应用市场下载使用应用`.

AOSP `no_install_unknown_sources` / AppOps
`OP_REQUEST_INSTALL_PACKAGES` still exist later in
`PackageInstallerActivity`, but this BYD gate runs first. Granting
unknown sources to File Manager would not help: the referrer check never
reaches that code.

Privileged `PackageInstaller.createSession` + `commit` **does not** go
through `InstallStart`. That is why type 14 works: WallpaperHome is
`android.uid.system`. The same bypass is how AppStore `silenceInstall`
and RapidUpdate SOTA install, with their own triggers.

FSE privileged installers (`INSTALL_PACKAGES` in
`privapp-permissions-platform.xml` and/or `sharedUserId=android.uid.system`).
WallpaperHome does **not** declare `INSTALL_PACKAGES`; it installs because
it is `android.uid.system`:

| Package | How it actually installs | Trigger from IVI / Denza Apps |
| --- | --- | --- |
| `com.android.packageinstaller` | Confirm UI for everyone else | Blocked by the referrer gate unless the caller is AppStore |
| `com.byd.appstore` | `PackageControllerImpl.installApp` session (`openWrite("SilentInstaller")`) | `InnerInstallReceiver` action `com.byd.appstore.BYDSTORE_INNER_INSTALL`, extra bundle `appMsg` with `actionType=innerInstall`, `innerInstallPath`, `innerInstallAPK`, `packageName`. Permission `com.byd.appstore.permission.BYDSTORE_INNER_INSTALL` is `protectionLevel=signature`. AutoVoice `ST_DOWNLOAD_APP` only opens catalog download UI. |
| `com.byd.updated` | SOTA `PackageInstallManager.installSingleApp` / bundle / APEX | Cloud RapidUpdate, not a local file |
| `com.byd.wallpaperhome` | Type 14 `ApkWallpaperManager` session | Cross `-13631467` `set_wallpaper_path` — **this is the product path** |
| `com.android.managedprovisioning` | AOSP provisioning | Not an app-sideload channel |
| `com.android.shell` | `pm install` | FSE ADB `5037`/`5555` still closed |

`InnerInstallReceiver` is FSE-local. Denza Apps on the IVI cannot send
that broadcast, and even an APK already on FSE would need the AppStore
signing certificate. Do not treat INNER_INSTALL as a backup product
channel.

## If type 14 is closed

Asked 2026-08-25: find a backup remote install that does **not** depend on
wallpaper type 14, given that FSE forbids installs that are not from the
market. The IVI↔FSE feature catalog
(`android.cross.BYDCrossFeatureIds` in this firmware) has **no install id**.
The bus we already use is officially `CROSS_ID_CHANGE_THEME` (`-13631467`).
FSE `CrossMessageHandler` accepts only `function=wallpaper` on that id.
`ApkWallpaperManager` is the only wallpaper path that opens a
`PackageInstaller` session; it runs solely for type `14`.

| Fallback | How | If type 14 dies |
| --- | --- | --- |
| Same theme bus, other wallpaper types | Pictures/video/GIF/sunset | Does **not** install APKs |
| `upgrade_server` / `CROSS_ID_OTA_IVI2FSE` (`305135628`) | ZMQ `PackageInfoCmd{path,size,md5}` then FSE `UpdateEngine` | Firmware zip only, not APKs. Do not send an APK as an OTA. |
| `IDiLinkPackageManager.getPackageInstaller(user)` | Same-SoC extra user (`999`) | This car is a separate FSE Android, not user 999 |
| FSE `com.byd.appstore` INNER_INSTALL / `silenceInstall` | Signature broadcast + local path | FSE-local, same-cert only. Not reachable from Denza Apps |
| FSE App Store catalog / `ST_DOWNLOAD_APP` | User (or AutoVoice) installs a **store-listed** app | Only apps BYD publishes. Not an arbitrary APK, not IVI-driven |
| RapidUpdate / `com.byd.updated` | Cloud SOTA | Not a local APK drop |
| File Manager + SMB | `ACTION_VIEW` on the APK | **Does not install.** Market-only PackageInstaller dialog; OK dismisses. |
| IVI `com.byd.silentinstaller` | USB `…/SilentInstaller/*.apk` on IVI | Not present on this FSE image; not an IVI→FSE API |
| FSE ADB `pm install` | Ports `5037`/`5555` | Still closed on this car |

There is no second silent IVI→FSE installer for an arbitrary APK in this
firmware. If BYD drops type 14 (support-id list or
`ApkWallpaperManager.install`), automatic passenger-screen install from
Denza Apps stops. The remaining operator path is the FSE application
market for apps that are actually in the catalog. Do not tell anyone to
copy an APK over SMB and tap it, and do not invent a third installer
against `upgrade_server`.
