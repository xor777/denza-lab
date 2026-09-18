# System Language

This page owns the car's language: what the firmware ships, which screen can
reach it, how a choice is applied, and what Denza Apps does about it.

It replaces `stock-russian-locale.md`, which described a per-application locale
override. That override is gone from the product — see
[What this replaced](#what-this-replaced).

## Two language screens, and why only one of them is short

`com.byd.carsettings` carries two independent language implementations.

| | visible | unlisted |
| --- | --- | --- |
| class | `com.byd.systemsettings.language.view.LanguageSettings` | `com.byd.systemsettings.languageadb.view.LanguageSettings` |
| intent action | `android.settings.LOCALE_SETTINGS` | `android.settings.LOCALE_SETTINGS1` |
| `android:exported` | true | true |
| `android:permission` | none, and none on `<application>` | none, and none on `<application>` |
| languages offered | 2, or 17 | **40** |

The visible screen builds its list in `language/model/LanguageDataModel`. Chinese
(1) and English (3) are added unconditionally; the other fifteen sit inside

```java
if (Constants.isDi100VCP() || Constants.isDi150VCP() || Constants.isDi300VCP())
```

which compares `Build.PRODUCT` against `Di100VCP_IVI`, `Di150VCP_IVI` and
`Di300VCP_IVI`. This car reports `ro.product.name=IVI`, and its own log says so
directly — `captures/split-live-acceptance/evidence/ground-v18/A-home-logcat-full.out`,
08-23 21:51:42:

```text
[BydCarUtils][1]isDi150VCP: false
[BydCarUtils][1]isDi100VCP: false
[BydCarUtils][1]isDi300VCP: false
```

So the stock language screen on this vehicle can only ever show 中文（简体）and
English. The unlisted screen applies no such check: its `LanguageDataModel` puts
all forty in the map unconditionally.

Both screens apply the choice the same way, through
`LanguageModel.setLanguage(int)` → `BYDAutoSettingDevice.setLanguage(int)`. That
is the vendor HAL and it changes the **system locale**, not one package's
override.

## The forty languages

Code → tag → the firmware's own name for it → translated strings in
`CarSettingPlatform.apk` (out of 4999). Codes are `LanguageCode` constants; tags
come from `LanguagePresenter.getLanguageTag`; names are the `system_language_*`
resources.

| | | | | | |
| --- | --- | --- | --- | --- | --- |
| 1 `zh-CN` 中文（简体） 4764 | 2 `zh-TW` 中文（繁體） 3725 | 3 `en-US` English (base) | 4 `es-ES` Español 3449 | 5 `es-US` Español (América) 3367 | 6 `pt-PT` Português (Portugal) 3723 |
| 7 `pt-BR` Português (Brasil) 3222 | 8 `ja-JP` 日本語 3640 | 9 `ko-KR` 한국어 3640 | 10 `fr-FR` Français 3633 | 11 `de-DE` Deutsch 3638 | 12 `it-IT` Italiano 3640 |
| 13 `hi-IN` हिंदी 2977 | 14 `ar-IL` العربية 3639 | 15 `nl-NL` Nederlands 3035 | 16 `th-TH` ภาษาไทย 3640 | 17 `sv-SE` Svenska 2977 | 18 `nb-NO` Norsk 2977 |
| 19 `fi-FI` Suomi 2977 | 20 `da-DK` Dansk 3114 | 21 `iw-IL` עִבְרִית 3242 | 22 `ru-RU` Русский язык 3640 | 23 `uz-UZ` O'zbek 3242 | 24 `tr-TR` Türkçe 3640 |
| 25 `hu-HU` Magyar 2977 | 26 `sk-SK` Slovenské 2977 | 27 `cs-CZ` Česky 2977 | 28 `pl-PL` Polski 2980 | 29 `vi-VN` Tiếng Việt 3640 | 30 `in-ID` Bahasa Indonesia 3640 |
| 31 `ms-MY` Bahasa Melayu 3640 | 32 `fr-CA` Français (Canada) 3066 | 33 `en-AU` English (Australia) 3012 | 34 `ca-AD` Català 2871 | 35 `hr-HR` Hrvatski 2976 | 36 `ro-RO` Română 2977 |
| 37 `el-GR` Ελληνικά 2977 | 38 `uk-UA` Українська 2976 | 39 `kk-KZ` Қазақ 3113 | 40 `bg-BG` Български 2943 | | |

Two are hidden on a right-hand-drive car: `LanguagePresenter.getLanguageList`
skips codes 14 and 21 when `RightRudderHelper.isRightDriver()`.

Catalan's resources are filed under bare `ca` rather than `ca-rAD`; Android
resolves `ca-AD` to them.

### Shipped but unreachable

These locales have translated resources in the settings APK and appear in
neither picker. `LanguageCode` even declares constants for the first six
(`CODE_SERBIAN` … `CODE_BOSNIAN`, 41–46); no label map references them.

| locale | strings |
| --- | --- |
| `sl-SI` | 1563 |
| `bs-BA`, `et-EE`, `lt-LT`, `lv-LV` | 1560 each |
| `sr-RS` | 1494 |
| `km-KH` | 501 |

## Live result (2026-09-18)

Read-only state before, on the Denza N9:

| property | value |
| --- | --- |
| `persist.sys.locale` | `en-US` |
| `ro.product.locale` | `zh-Hans-CN` |
| `ro.product.name` | `IVI` |
| `sys.byd.countrycode` | empty |
| `cmd locale get-app-locales com.byd.carsettings` | `[]` |

`am start -a android.settings.LOCALE_SETTINGS1` opened the unlisted picker over
the running desktop; `dumpsys activity activities` confirmed
`com.byd.carsettings/com.byd.systemsettings.languageadb.view.LanguageSettings`
resumed and focused. Choosing **Русский язык** and confirming:

- `persist.sys.locale` became `ru-RU`;
- the head unit **did not reboot** — `uptime` carried straight through at 13:12;
- the per-application override stayed `[]`, so the vendor HAL did all of it.

The change is therefore cheap and immediate: one call, no restart, no ADB, no
permission.

### Not verified

Only Russian has been applied on a live car. The other thirty-nine are read out
of the firmware and are not individually proven. One reason to keep that
distinction: the vendor SDK stub in `reverse/maphelper-jadx/.../BYDAutoSettingDevice.java`
declares only five language constants and disagrees with the settings app about
what they mean (`4 = RUSSIAN` there, `4 = es-ES` in `LanguagePresenter`). That
stub is stale, but the authoritative code→locale mapping lives in the framework
service, which is not in the corpus.

## How much of the car follows

The system locale reconfigures every process, but each application only has the
translations it shipped with. Region-qualified locales in the captured APKs:

| package | locales | Russian |
| --- | --- | --- |
| `com.byd.carsettings` | 46 | yes |
| `BydAutoVoice` | 44 | yes |
| `dishare` | 36 | yes |
| `BydSRDenza` (speech recognition) | 0 | — |
| `openbyd`, `MapHelper`, `CustomKey`, `AutoVideo`, `bilithings` | 12, all `en`/`es`/`fr`/`pt`/`zh` variants | no |

So a language switch translates settings, the voice assistant UI and DiShare,
and leaves the rest of the stock applications in English.

## Denza Apps

One tile, `TileId.LOCALE`, named **«Язык системы»**, subtitled with the language
the car is currently speaking in that language's own word for itself.

- a short press opens the car's own list through
  `SystemLanguage.open` → [`PICKER_ACTION`](#two-language-screens-and-why-only-one-of-them-is-short);
- a long press opens the tile's panel, whose single button **«Выбрать язык»** is
  the same press said in words (`TileAction.LANGUAGE_PICK`);
- the subtitle comes from `Locale.getDefault()` mapped through
  `SystemLanguage.FIRMWARE_NAMES`, which carries BYD's own forty names so the
  tile and the row the driver taps say the same thing. `SystemLanguageTest`
  holds that table.

The app never sets the language itself and holds no permission for it. It reads
a locale and opens a door, so there is no state to coordinate, nothing to
retry, and no way for the tile to be refused.

Two deliberate departures from the firmware's data:

- BYD's Russian string begins with a Latin `P` (`Pусский язык`). Denza Apps
  writes Cyrillic; the glyph is indistinguishable and the data is not.
- Android reports Hebrew as `iw` and Indonesian as `in`, matching BYD's tags,
  but a desktop JVM from 17 onwards reports `he` and `id`. `SystemLanguage`
  normalises both spellings so a unit test and the car agree.

### What this replaced

The previous tile was a switch over an Android 13 **per-application locale
override**: it asked `LocaleManager.setApplicationLocales("com.byd.carsettings", …)`
for `ru-RU`, which required `CHANGE_CONFIGURATION` granted once over the passive
local ADB client, could not be read back (reading another package's locale needs
the signature-only `READ_APP_SPECIFIC_LOCALES`), and translated exactly one
stock application while the rest of the car stayed English. It could be refused,
so the tile carried three ways of saying no — «Нужен доступ», «Не переключился»,
«Не проверено» — and a running state, an ABA-safe conditional update and a
dedicated executor behind them.

All of it is deleted: `StockRussianLocaleCoordinator` and its test, the
repository's snapshot, claim, failure and executor, the switch in the tile's
panel, and the `CHANGE_CONFIGURATION` grant path. Nothing in the product asks
for that permission any more, and since 2026-09-18 the manifest no longer
declares it either.

The third-party switcher that inspired it — `com.wings.translator`, launcher
label `BYD Настройки`, SHA-256
`6b12b934ef644cfb5078093a9adbef6714bd81146d8218da684bd0e042f76d74` — used the
same override, plus an unused 763 KB `assets/translations.txt` accessibility
overlay whose service is not declared in its manifest and therefore cannot run.
It is recorded here only so nobody rediscovers it as a path worth taking.

## Curiosities in BYD's own table

Not defects in our code; they are what the firmware says, and they are the sort
of thing that reads as our bug when it is seen on the car.

- `system_language_estonian` is «Lietuvių» and `system_language_lithuanian` is
  «Eesti» — the native names are swapped. `system_language_latvian` says
  «Lietuviešu», which is Latvian for *Lithuanian*. All six of those locales are
  unreachable from either picker anyway.
- `LanguageDataModel.languageCodeMap` maps dialling code `7` (Russia,
  Kazakhstan) to English, and `998` (Uzbekistan) to Russian. It only chooses
  which row floats to the top of the unlisted picker, via `sys.byd.countrycode`,
  which is empty on this car — so the list opens at index 2 instead.

## Reset

The choice is applied by the vendor controller and persists. To put a car back:
open the same picker and choose **English** (code 3), which restores
`persist.sys.locale=en-US`, the state this car was in before 2026-09-18.
