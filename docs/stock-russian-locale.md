# Stock Russian Locale

This page records what the captured third-party `BYD Настройки` APK actually
does and owns the narrow Denza Apps locale toggle derived from that behavior.

## Investigated inputs

- `captures/apk/byd-settings-2026-08-18/BYD-Settings.apk`
  - SHA-256: `6b12b934ef644cfb5078093a9adbef6714bd81146d8218da684bd0e042f76d74`
  - package: `com.wings.translator`
  - launcher label: `BYD Настройки`
  - file size: about 313 KiB
- `captures/apk/byd-carsettings-2026-08-18/CarSettingPlatform.apk`
  - package: `com.byd.carsettings`
  - file size: about 460 MiB
  - packaged resource configurations include both `ru` and `ru-rRU`

These are different applications. The small APK is a third-party switcher; the
large APK is the stock BYD settings application whose resources it selects.

## What the small APK does

The language button reads a private `locale_ru` preference and chooses either
`ru-RU` or an empty locale list. It first reflects into the package-aware hidden
`LocaleManager.setApplicationLocales(String, LocaleList)` overload. If that
throws, it falls back to `cmd locale set-app-locales`.

On the target DiLink 5.1 Android 13 firmware, that direct setter requires
`android.permission.CHANGE_CONFIGURATION`. The captured switcher declares it
and has it granted. Its UI state comes from the private preference rather than
from a system read-back.

The bytecode contains 78 package names, but its success flag is shared across
the loop. After the first successful locale write, later writes are skipped.
`com.byd.carsettings` is first, so the expected successful path changes only
the stock settings package. The APK then tries to kill background processes for
the full list and records only its own preference; it does not read back and
verify the applied locale.

This is an Android 13 **per-app locale override**, not a global vehicle locale
change and not a replacement set of translated resources. The Russian strings
shown by this path come from the stock `com.byd.carsettings` APK's own `ru` /
`ru-rRU` resources. Coverage is therefore limited to what BYD shipped.

Android 13's locale shell contract defines an omitted `--locales` option as an
empty locale list. These commands remain useful for host-side diagnosis:

```text
cmd locale get-app-locales com.byd.carsettings
cmd locale set-app-locales com.byd.carsettings --locales ru-RU
cmd locale set-app-locales com.byd.carsettings
```

Reference: [AOSP Android 13 LocaleManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/locales/LocaleManagerShellCommand.java)

## The unused overlay translator

The APK also ships a 763,165-byte `assets/translations.txt` dictionary and a
`TranslatorService` class. That class is an `AccessibilityService`: it walks
text nodes in BYD settings/driving-mode windows and draws replacement Russian
text in a system overlay.

However, `AndroidManifest.xml` declares no service at all. It declares only the
main activity, a file provider and an update receiver. Consequently the
`TranslatorService` component cannot be resolved or enabled in this captured
build, even though `MainActivity` tries to add its nonexistent component name
to the secure accessibility setting. The overlay code is present but is not the
working language-switch path.

The VIN-bound license check and GitHub updater are also unrelated to selecting
the stock locale.

## Denza Apps product boundary

The hidden Diagnostics window exposes a single switch for
`com.byd.carsettings`:

- opening Diagnostics reads only Denza Apps' local saved choice and never opens
  an ADB connection;
- the first explicit choice grants only `CHANGE_CONFIGURATION` through the
  already trusted passive local ADB client when that grant is still missing;
- switching on then calls the package-aware `LocaleManager` directly with only
  `ru-RU`;
- switching off calls the same API with an empty `LocaleList`, removing only
  that package's override;
- every explicit choice is applied again even if the saved value already
  matches, so a change made by another app can be repaired;
- Denza Apps does not import the dictionary, accessibility overlay, license,
  updater, broad permission list or 78-package process killing.

The system service on this firmware requires the signature-only
`android.permission.READ_APP_SPECIFIC_LOCALES` to read another package's locale.
Denza Apps cannot legitimately obtain that permission, so it cannot perform a
direct system read-back. Before its first choice the UI therefore shows explicit
**Вкл** and **Выкл** buttons instead of pretending that an unknown state is off.
After a successful direct call it stores that choice and shows the normal
persistent switch. A failed direct call is not saved.

The direct implementation is locally unit-tested and build-verified. Firmware
and package-permission behavior were confirmed read-only on 2026-08-22, but this
version has not yet been installed or toggled on the live car.
