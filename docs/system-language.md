# System Language

This page owns the car's language: what the firmware ships, which screen can
reach it, how a choice is applied, and what Denza Apps does about it. It also owns
the language of the app's own words, which is a separate setting.

It replaces `stock-russian-locale.md`, which described a per-application locale
override. That override is gone from the product — see
[What this replaced](#what-this-replaced).

## Current state

Updated 2026-11-07. Which languages the car can speak, how a choice is applied, and
how Denza Apps picks the language of its own words.

| Claim | Status | Since | Section |
| --- | --- | --- | --- |
| `com.byd.carsettings` has two language screens. On this car the visible one offers 2 languages and the unlisted one (`android.settings.LOCALE_SETTINGS1`) offers 40 | firmware | 2026-09-18 | [Two language screens](#two-language-screens-and-why-only-one-of-them-is-short) |
| On a Denza N9, choosing Russian in the unlisted picker sets `persist.sys.locale=ru-RU` through the vendor HAL, with no reboot and no per-application override | live | 2026-09-18 | [Live result](#live-result-2026-09-18) |
| The other 39 languages are read out of the firmware; none has been applied | firmware | 2026-09-18 | [Not verified](#not-verified) |
| A switch translates settings, the voice assistant UI and DiShare; the other captured stock applications ship no Russian | firmware | 2026-09-18 | [How much of the car follows](#how-much-of-the-car-follows) |
| The «Язык системы» tile reads `Locale.getDefault()` and opens the picker; it sets nothing (`feature/locale/SystemLanguage.kt`) | code | 2026-09-18 | [Denza Apps](#denza-apps) |
| The app's own words resolve through one holder per process, installed in `Application.attachBaseContext` in the stored language, Russian when nothing is stored (`core/AppText.kt`) | code | 2026-11-06 | [The holder](#the-holder) |
| A row in «Сервис» chooses Russian or English. `AppLanguageSwitch` writes with `commit()`, ends `:picker`, relaunches `MainActivity` and ends the main process; a failed write or the language already running does nothing. It checks nothing else, by the owner's decision ([Decisions for the owner](#decisions-for-the-owner)). No car or emulator has run it | code | 2026-11-07 | [The language switch](#the-language-switch) |
| What a restart costs each surface, traced from the code: the Contour and a share do not come back by themselves, Mirrors has a gap, and the picker bases go with `:picker` while the remembered slots stay | code | 2026-11-06 | [What a restart costs](#what-a-restart-costs) |
| `CyrillicLiteralGuardTest` holds every file's Cyrillic literals to a list, forbids reading a string resource directly outside `AppText`, and bars the files of external text from `AppText` and `R.string` | code | 2026-11-06 | [The guard](#the-guard) |
| Extracted to `strings.xml` so far: `core/FeatureWords`, `feature/network`, `fse`, `mirrors`, `navigation`, `cloud`, `simulcast`, `split`, `speaker`, `weather`, `defaultapps`, `adb` and `warning`, the root package, the status and notification words of `feature/hud`, the Compose text of `ui/` with the speakers' tile and panel, and the drawn text of the Contour, the strip, `HudGlassPicture.kt`, `HudRadarBadge.java` and `SimulcastDialogOverlay.java`, the words of `feature/cluster`, and the route summary the capture sends to the stock HUD. Drivers' words still in the code: `feature/diagnostics`, which is parked, and the YandexHUD module's default words, `HudProtocol.SummaryWords.RUSSIAN` | code | 2026-11-06 | [The app's own language](#the-apps-own-language) |
| `res/values-en/strings.xml` holds an English copy of every string, a draft awaiting the owner's approval. The app shows it once a driver chooses English in «Сервис». `StringParityTest` holds the keys, placeholders, plural forms and firmware marks to `values/` | code | 2026-11-06 | [The English text](#the-english-text) |
| A word read on every frame reaches `Resources` once per process, and a steady frame of the Contour or the car page formats nothing (`DrawnWordsCostTest`, on the JVM). What that costs on the head unit has not been timed | code | 2026-11-06 | [Words on a draw loop](#words-on-a-draw-loop) |
| The contract tests run in Russian and in English, and `DrawnWordsFitTest` measures every word drawn in a fixed cell in the font files, in both | code | 2026-11-06 | [What is tested in English](#what-is-tested-in-english) |
| `hud_glass_cameras` stays inside the HUD panel's glass picture at both widths in both languages. The owner shortened the Russian to «Камеры»; the panel's row keeps «Камеры и опасности» | code | 2026-11-07 | [What is tested in English](#what-is-tested-in-english) |
| In English the capture sends the HUD's `SUMMARY_DISTANCE` and `SUMMARY_TIME` in English words (`HudSummaryWords.SEND_ENGLISH_TO_GLASS`). Russian bytes are frozen by `HudWireGoldenTest`. The YandexHUD module stays Russian and was not rebuilt. No car has drawn the English | code | 2026-11-06 | [Words that leave the app or get stored](#words-that-leave-the-app-or-get-stored) |
| `AppText`'s `Resources` path has been compiled and has never run, on a car or on an emulator | open | 2026-11-06 | [The holder](#the-holder) |

**Open questions**
- Does the car's Roboto measure like the repo's, and which font draws the ★ in `sheet_cluster_wheel_button`?
  One screenshot of `sheet-cluster` and one of `main-car` settle it. Do the English screens match their boards?
  `compare.py --lang en` on the fixture activity settles it.
- Does `AppText`'s locale override work on a device? One debug build on an emulator set to en-US
  settles it: the speakers' notification, the split's wait and the navigation transfer card have
  English in `values-en/` now, and all three must still show Russian.
- Does this firmware create content providers after `attachBaseContext`? Reading
  `ActivityThread.handleBindApplication` in the firmware corpus settles it, or a line logged from
  `SplitCommandProvider.onCreate` on the emulator run above.
- Does the runtime come back cleanly when a language change restarts the process with foreground
  services, Mirrors and the cluster scene running? The run in [The first car test](#the-first-car-test)
  settles it.
- Does the stock weather widget draw English `weathertext` and `desc`, and does anything in it read
  those words? One run with an English row on the car, or the widget's code in the firmware corpus.
- Does the stock HUD draw Latin in `SUMMARY_DISTANCE` and `SUMMARY_TIME`, and at what width? One
  route with English words sent to the glass settles it (step 10 of [The first car test](#the-first-car-test)).
- Does Yandex Navigator speak English on an en-US system, and do the parsers in
  `HudGuidanceModels.kt` cover it? One route with the car on English, with the guidance logged.
- Do the firmware's labels match the English text? `defaultapps_sheet_help`, `adb_gate_dialog_message`
  and `warning_export_saved` quote them; one look at each screen with the car on English settles it.
- Which locale does each of the other 39 codes apply? Applying each one, or the framework service
  that maps them, which is not in the corpus.

## Contents
- [Two language screens](#two-language-screens-and-why-only-one-of-them-is-short): the visible one, the unlisted one, and how both apply a choice.
- [The forty languages](#the-forty-languages): codes, tags, names and how many strings each has.
- [Live result (2026-09-18)](#live-result-2026-09-18): Russian applied on the N9.
- [How much of the car follows](#how-much-of-the-car-follows): which stock applications ship which languages.
- [Denza Apps](#denza-apps): the «Язык системы» tile, and the override it replaced.
- [The app's own language](#the-apps-own-language): `AppText`, the language switch and what a restart costs, words on a draw loop, text the app reads back, why not `LocaleManager`, unit tests, the guard, adding a string, the English text and its glossary.
- [Curiosities in BYD's own table](#curiosities-in-byds-own-table): what reads as our bug and is the firmware's.
- [Reset](#reset): putting a car back on English.

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
| `MapHelper`, `CustomKey`, `AutoVideo`, `bilithings` | 12, all `en`/`es`/`fr`/`pt`/`zh` variants | no |

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

## The app's own language

The app's words are Russian and live in `res/values/strings.xml`, apart from the few
[Current state](#current-state) lists as still in the code. The car's language does not change them: on an en-US car the app stays in Russian. The two
`@string` accessibility descriptions in `res/xml` are the exception: the system resolves them, so they
follow the car's language, so on an English car they now read English. The «Язык системы» tile is about the car and has nothing to do with this. A row in «Сервис» chooses
the app's language, with Russian as the default, and `values-en/` is a [draft](#the-english-text) that
the row makes reachable.

### The holder

`core/AppText` is one object per process: `AppText.s(R.string.x)`, `AppText.s(R.string.x, args)` and
`AppText.p(R.plurals.x, count, args)`. Most of a driver's words are made in pure functions, constants
and enums with no Context in scope, and threading one through would touch their callers
transitively. With a holder the call sites stay: `const val X = "…"` becomes
`val X: String get() = AppText.s(R.string.x)`.

`DenzaAppsApplication.attachBaseContext` installs it. The manifest sets that class on
`<application>`, and its one `android:process` is `:picker` on `SplitPickerActivity`, so both
processes run it. The place was chosen because Android creates content providers after
`attachBaseContext` and before `Application.onCreate`, and `SplitCommandProvider` runs in the main
process. That order is standard Android and has not been read from this firmware. A call before the
holder is installed throws, so a wrong order would show as a crash and never as a wrong word.

It resolves through `createConfigurationContext` with the locale of `AppLanguage`: `ru-RU`, or `en`
when English is stored. Reading `values/` through the car's locale would pick English plural rules and
English words on an en-US car. With the locale set here, plurals and `%.1f` follow the app's language
on any car, and `Locale.getDefault()` stays the car's. The override holds the locale alone, so density,
`uiMode` and screen size keep following the system.

Nothing here has run on a car or an emulator; see [Current state](#current-state).

### The language switch

**Storage.** `AppLanguageStore` keeps one key in a preferences file of its own, `app_language`. Only
the main process writes it, with `commit()`. Both processes read it in `attachBaseContext`, through
`AppText.install`, from the base context they are handed; that works this early because a base context
already has its package and data directory (reasoned, not run). Nothing is written on first run, so an
untouched install has no file and speaks Russian on any system locale. A value this build does not
know, or a read that fails, means Russian. SharedPreferences keeps a copy per process, which is safe
here because `:picker` is ended on every change. If `:picker` starts during main's `commit()`,
SharedPreferences' backup handling could in theory lose the new value (AOSP from memory, not run): the app
then comes back in the old language after the restart, and tapping again cures it.

**The row.** «Сервис» shows a section titled «Язык / Language» with the stock tab row, «Русский» and
«English», and one line saying the app will restart. The title and the names are `translatable="false"`,
so they read the same in either language. A switch could not say which language «on» means. The row
stands above the pages, on the first screen, because someone who cannot read Russian will not scroll a
list they cannot read. It pushes «Приборный экран» below the fold, which the owner accepted on 2026-11-07. Its selection is `AppText.language`, what the process speaks, so a write that
failed leaves it as it was.

**The restart.** `AppLanguageSwitch.choose` is the order, with no Android in it: the same language does
nothing; a failed write does nothing further; otherwise write, end `:picker`, relaunch, end self.
`AppLanguageRestart` supplies the steps, and its write step logs the outcome, because `endSelf` leaves
nothing after `choose` to log it. There is no separate way to restart features to reuse, because
the code has none: «Короткий перезапуск включённых функций» in the glossary is the notification channel
of `RuntimeRecoveryService`, which runs the recovery contour after `BOOT_COMPLETED` or an update. The
contour itself starts from `DenzaAppsApplication.onCreate` in any new main process, with
`RuntimeStartCause.PROCESS_START`. So the restart ends the processes and leaves the rest to the path
every wake takes.

The steps run on the main thread inside the tap. `FLAG_ACTIVITY_NEW_TASK | CLEAR_TASK` on the `singleTask`
`MainActivity` clears the task of its own affinity, `dev.denza.apps.control`. The pickers, the capture
request and the launcher entries have other affinities and are not touched. If `MainActivity` is resumed,
the system first waits for a pause that this thread never gets to answer, so the new `MainActivity` starts
in a new process. If it is paused, no pause is owed and the launch is scheduled to the dying process, which
should still end in a relaunch once the system sees that process die. Both are AOSP from memory, since the
corpus is not on this Mac, and neither has run. It opens at the dashboard, not at «Сервис».

**Channels.** A channel's name changes when its service next creates it, and each service creates its
own, with none created at process start. `ClusterSceneService`, `SideCameraMonitorService`,
`SpeakerCoverService`, `CloudLinkService` and `WeatherAdapterService` do it in `onCreate`, and
`HudMapProjectionService` in `onStartCommand`. Whichever of them run come back with the new name. Weather's
waits for its next alarm, and `RuntimeRecoveryService`'s for the next wake or update. A channel whose
service has not run yet keeps its old name in the car's settings and has no notification on screen.

### What a restart costs

Read means traced in this tree. Assumed means standard Android from memory or a firmware behaviour not
read here, since the corpus is not on this Mac. Nothing in this table has run.

| Surface | Across the restart | Basis |
| --- | --- | --- |
| Recovery | `Application.onCreate` enters the same contour as every wake, with no foreground bootstrap. The wake's foreground service took 2.5 s on 2026-09-23 (`adb-authorization-recovery.md`), a different path | read |
| `START_STICKY` services: Mirrors, cluster scene, speakers, cloud, Simulcast overlay | A kill, unlike a force-stop, makes the system restart them with a null intent, which each handles: Mirrors starts its monitor, the scene prepares a transparent base, speakers and cloud check their settings. `SimulcastOverlayService` is sticky too and does nothing on a null intent. This branch is rarely taken on the car, since sleep force-stops | code read; the restart assumed |
| The Contour | Its view dies with the process. `dashboardOnCluster` starts false and `NavigationCoordinator.initialize` shows nothing, so the stock instruments are what remains until the tile is pressed, as after a sleep. It does not come back by itself | read |
| A navigator on the cluster | Another package's task, untouched by the kill. `discoverTask` finds its id. What the tile says about it afterwards was not traced | read, tile not |
| Mirrors | A camera on screen dies mid-session. `AvcDisplayClaim` persists and `releaseStaleClaimWhenIdle` frees it once AVC reports idle; until then a value of ours in AVC's owner field can crash its next window-creating PIP. No side camera between the kill and the monitor's return | read; the gap's length unknown |
| HUD with YandexHUD | The host's binder dies, the module writes by itself and looks for the host every 5 s, then hands over (`hud-output-architecture.md`, 5, not run on the car) | read in the doc and code |
| HUD with ordinary Yandex | The glass keeps each topic's last packet until the compositor returns. The capture token dies; `MainActivity`'s resume asks again through `HudMapGrant.attach`, with no dialog while op 46 is allowed. The notification listener's rebind is the system's | read; rebind assumed |
| ADB gate and client | Phase starts `UNKNOWN`, so the gate covers the dashboard until a passive check says `TRUSTED`. The key is on disk and no prompt is sent. Persistent shells are opened anew | read |
| Shell helpers | `denza_split_serve`, `denza_vehicle_signals` and `denza_vehicle_read` read stdin to EOF and exit. The process's socket to adbd closes with it, so they end and leave nothing to reap. Nothing reattaches: the next start opens new ones. A one-shot `denza_nav_cmd` finishes alone | code read; adbd's hangup assumed |
| Overlay and Simulcast | Windows go with the process. DiShare ends a share when its API client dies (`dishare-api-notes.md`), `lastTargetPackage` is in memory only, so no exit control comes back and the share does not resume | read |
| Accessibility services | The system rebinds a bound service after a plain death; if it lists one as crashed instead, `AccessibilityRepair` rewrites the setting at once, as at every wake | repair read; rebind assumed |
| Trip figures | `TripEnergyLedger.restore` reads the journal back, as after a sleep | read |
| An operation in flight | An open, a selection or a transfer dies unfinished, like one a sleep interrupts (`SplitWorkJournal`) | read |

**The split.** The remembered slots survive a restart by this trace, with one narrow exception:

- `SplitCoordinatorCore.ready` loads the slots from the store with `scene = null`. `settleByExistence`
  returns `NOTHING` while `scene` is null, so a vanished task cannot be read as «Очистить всё» in a process
  that has adopted nothing.
- `SplitFact.SceneEndedSettled`, the fact that turns `App` slots into `Picker`, needs the scene's apps dead
  under cover on two reads with a pause between them. In the old process `:picker` dies moments before the
  main process, and that process's reconcile cannot finish in the gap. A pair with live apps reads as
  «база утрачена», which ends nothing.
- `adoptOwnedScene` needs our picker base in both roots and refuses otherwise, which leaves the slots as
  stored. With both bases gone, every reconcile of the new process stops there. The gate duty of К 1.11
  runs first, as after any start.
- The exception is an adoption that succeeds. `BuildSceneSucceeded` writes what it read into the slots, so
  a pane whose top task reads as our picker turns its `App` into `Picker` on disk, and a hint with a pair
  remembered may ask for that (`adoptable`). On a covered world it takes both roots topped by our bases,
  and on a visible one a base on show. A restart reaches this only if the bases outlive `:picker`, and only
  in a pane whose app has gone, or in the pane of a relaunched `MainActivity` that lost its place. The language
  switch adds nothing here, since any process start has this path. Whether it explains the «two pickers» the
  owner reported is not known.

The bases are the cost. `split-screen-findings.md`, "A caption swipe closes the whole pane once our picker
is gone", records one on the car: an install killed `:picker` with «proc died without state saved», both
bases went, and the other packages' apps stayed. A plain kill should be the same event, so with a live pair
the panes have no picker under the apps until the next «Разделить экран», and a caption swipe in that time
closes the pane. A pane showing only a picker loses it at once, and what the firmware does with the empty
root is not known. AOSP from memory says a stopped Activity with saved state stays and is started again when
revealed, reading the new language in `attachBaseContext`, which the record above does not show happening.
The first car test tells which it is.

Nothing launches into a pane. The relaunch starts `MainActivity` and no other app, the new process's
`SplitScreenCoordinator.initialize` is read-only (K7), and if `MainActivity` stood in a pane, `CLEAR_TASK`
reuses that task (assumed). A gate still open under a covered area would pull the relaunch into a split
next to `com.byd.sr`, the shape of 2026-09-18; the new process then closes it by its first area read,
which is too late for this one launch.

### Decisions for the owner

The owner chose the plain restart on 2026-11-07: tap, then restart, with no check of what is running.
The row works at any time and the costs above stand. Every sleep force-stops this package, so the app
already meets that event daily, and a language is switched once or twice in an install's life, which
pays for no guard.

A real force-stop is not what runs. An app needs the shell to do one to itself, which would tie the
switch to ADB access, and nothing would open the app again. `Process.killProcess` runs instead, and the
difference that shows is the sticky services in the table above.

Warning names stay Russian in either language, on the page and in the export, by the owner's decision on
2026-11-07. The file the app reads gives no English: `popup_nads.json` holds Chinese whatever the car's
language, and the cluster translates after reading it (`vehicle-data-findings.md`, "OEM warning IDs and
text"). An English table of the firmware's own would be the source to use, if the corpus shows one; it is
not on this Mac.

One decision is left:

1. **The English draft.** The row makes the unapproved text reachable by any driver. Options: approve the
   text first; ship the row only in debug builds until then.

### The first car test

One session owns the car. Write down the expected action at each step first
(`governance.md`, "Firmware Behavior Method"). Any `com.byd.avc` line in `logcat -b crash` stops the run.

1. Reset: split acceptance baseline (`split-screen-findings.md`, "Acceptance reset"), the app's
   `app_language` file absent, Disable self-start off after the install, record `am stack list`,
   `service call activity_task 30`, `split_state_v2`, the crash buffer.
2. With nothing stored, check the words are Russian with the car's locale on `en-US`, if the car stands there.
3. Tap «Русский». Expected: no line changes, same pids.
4. Parked, split off, Mirrors off: tap «English». Expected: the `DenzaLanguage` line saying the write succeeded, `:picker` and main
   gone, a new pid, `MainActivity` in English, the «Язык системы» tile naming the car's language, and
   `persist.sys.locale` unchanged. Back to Russian the same way.
5. Parked, Mirrors on, no camera up: switch. Read `dumpsys activity services` for the sticky restarts and
   time the monitor's return. Then a turn signal, expecting the stock card and our camera.
6. Parked, Contour on the cluster: switch. Expected: stock instruments, tile off.
7. Parked, HUD hints on with a route: switch. Read the glass and `dumpsys media_projection`.
8. Split with a live pair, covered by the hub: switch. Expected: the same tasks alive with sound
   uninterrupted, `split_state_v2` slots unchanged, nothing launched. Then «Разделить экран» restores the
   same pair without restarting them.
9. Hub in one pane, a picker in the other: switch, and record what the firmware does with the empty root.
10. Parked, HUD hints on, ordinary Yandex with a route, app in English. Expected: «5.6 km» or «12 km» and «21 min»
    or «1 h 5 min» in the two summary lines, whole and uncut, with «350 m» near the end of a route. If Latin
    does not draw or is cut, set `HudSummaryWords.SEND_ENGLISH_TO_GLASS` to false and rebuild: English mode then
    puts the Russian bytes in those two lines. The constant governs only the text fields the stock HUD draws. The
    radar is a PNG we draw with our own fonts, so its units stay English either way and tell nothing about this.
11. Last of the switches, since a crash here stops the run: parked, a side camera on screen, switch. Expected:
    the camera goes, `AvcDisplayClaim` is freed once AVC reports idle, the next turn signal draws the stock card
    and our camera, and `logcat -b crash` has no `com.byd.avc` line.
12. `dumpsys notification` for the running services' channel names. Leave the car on Russian.

### Words on a draw loop

The Contour builds a frame and draws it up to thirty times a second, the strip's car page once per
drawn frame, and both read their words through getters. `AppText.s(id)` with no arguments keeps what it
read in `WordMemo`, a table indexed by the low bits of the id with the id stored beside its text. The
first read goes to `Resources` and each later one is an array read, with no lock and no allocation. A
language change restarts the process, so the memo cannot go stale. Two ids sharing a slot cost a second
read and never answer for each other. The no-argument form has to be its own overload, since calling
the `vararg` form with nothing allocates an empty array.

`AppText.s(id, args)` and `AppText.p` format on every call. A sentence with a figure in it is built
where the figure changes: `ContourFigures`, the identity tests in `ContourFrameBuilder` and
`EnergyReadouts`, `StripReadings.Printed`. `DrawnWordsCostTest` runs steady frames of both screens on
the JVM and holds them to no lookup and to the bytes allocated per frame. This has not been timed on
the car.

| Surface | How often its text is read |
| --- | --- |
| `ContourFrameBuilder`, `ClusterDashboardRenderer` | Every frame, through the memo. Sentences when a figure changes |
| `StripReadings`, `VehiclePageRenderer` | Every drawn frame of the car page, through the memo. Sentences when a figure changes |
| `TripPanelRenderer`, `StripInk` | Draw what the model holds. `StripInk` has one literal of its own, the ellipsis |
| `TileFacePainter` | Draws a plate and a glyph, no text |
| `HudGlassPicture` | Each time Compose redraws the panel's picture |
| `HudRadarBadge` | Each time `HudRadarPainter` renders a warning to a PNG |
| `SimulcastDialogOverlay` | Each draw of the overlay while the screens are checked |
| `SplitCrewView` | Once: the scene keeps its caption from the constructor |
| `VehicleTelemetryHub` | Once per closed snapshot; the views keep the string |

`LightPen` and `WideDigits` hold no strings, and `StripInk.Fitted`, `StripInk.Wrapped` and
`ContourScene` compare text by value. The identity tests are on the printed figures, which a slot hands
back as the same object, so a word's source does not affect them.

A word read while a class loads would come before `attachBaseContext` and throw. A drawn word is
therefore a getter or a resource id, never a value in an initialiser of an `object` or a companion.
`NoWordAtClassLoadTest` loads the drawn surfaces in a class loader of their own and fails if one reads
a word.

Numbers follow the app's language as well. `core/AppDecimal` writes a figure printed with a point or
counted in tenths with the mark of `AppText.locale`, so a comma in Russian and a point in English,
without reading `Locale.getDefault()`. The Luminofor figures have a glyph for both marks at one advance.

### Text the app reads back

Nothing outside the guard's `external` files decides anything by a word it wrote, on the surfaces
extracted so far. Each of these used to:

- `EnergyReadouts` chooses an `EnergyReadouts.Direction`, and the words and the blue mark follow from it.
- A report section the page has to find again has an id, written `[id|Название]`, and the technical
  page opens the journal under `SupportDiagnostics.SPLIT_SECTION`. The board's fixtures tell sections
  apart by title, so the debug build's `SheetFixtures` adds the id when it loads them.
- `SimulcastScreenDiagnostics` holds a `ScreenQuery`, and the report's `key=value` lines print it.
- `VehicleTelemetryHub` remembers the `VehicleReadFailure`. `ContourScene` still compares its `message`,
  but only to know whether the text to draw has changed.

`feature/diagnostics` keeps its own comparisons on its words until it is unparked.

### Words that leave the app or get stored

Weather text follows the app's language, by the owner's decision. `AndroidWeatherGeocoder` asks for
`AppText.locale`, so the language is still named in one place. The
condition names and `weatherDesc` and `desc` are strings like any other, although the stock widget
draws them, and its own frame follows the car's language around them. Our code compares none of these
words; it sends the condition as the numbers in `weatherid`, `cnweatherid` and `zmweatherid`. So no
weather file is on the guard's `external` list. The row already on the car keeps the old language until
the next run replaces it.

`DefaultAppsSettings` stores a role's confirmed package and no word. Until the first provider read the
panel names that package from preferences alone: a known app's catalog name, the «Штатная…» word or the
package name. `MainActivity` makes the first `refresh` on the main thread, so this read asks
`PackageManager` nothing, and it sits inside the state update, so it cannot land on a provider read that
has already finished. An unknown third-party app shows its package name until that read replaces it with
what `discover` found.

Installs from before this change also hold `confirmed_label_<role>`. Nothing reads it, and the next
confirmation removes it in the edit that writes the package, so there is no migration to interrupt. An
unknown app used to show that stored label during the wait, and this is the one thing the change gives up.

A provider read runs on the default-apps thread and can finish before the launcher catalog. There
`DefaultAppsCatalog.labelNow` gives the word `label` will give once the catalog is in: the application's
own label for a launchable package, and `fallbackLabel` for any other, so a disabled stock map says
«Штатная навигация» on both sides of that moment.

`AdbRescueSnapshot` holds its `message` and `details` as text in memory, resolved when the phase
changes, so a language chosen mid-run shows there only after a restart. Nothing about the phase is
persisted as words: `adb_rescue` keeps a flag, a count and a time, and the restore's stored outcome
and dialog result are machine words such as `needs-dialog`. The restore's rows in «Технические
сведения» and the headers of the warnings export stay Russian in the code as report text. The export's
title and section names are Russian too, by the owner's decision on 2026-11-07 that reports stay Russian:
`WarningExport` spells them itself, and `WarningTextTest` holds them equal to `warning_title`,
`warning_codes` and `warning_events` in `values/`. The page on screen keeps following the language. The
event text in the export follows it as well, so a file written in English has English events under
Russian headers.

The capture's route summary follows the app's language too, and `HudSummaryWords` decides it. The two
lines were never built alike. `SUMMARY_DISTANCE` is printed by `HudProtocol.routeDistance`, with the
`hud_summary_*` words and `AppDecimal.mark()`. `SUMMARY_TIME` has always been Yandex's own text passed on, so
Russian keeps passing it on. English reprints it only when the whole of the current text is hours, minutes,
or hours with minutes, and prints what that match read, never the merged seconds. Anything else, days
included, stays Yandex's text. Words are looked up once per poll, when the fields are made;
the compositor's frames reuse the fields.

`SEND_ENGLISH_TO_GLASS` is one constant and no setting, because the only reason to flip it is the car test
above: set to false, English mode sends `SummaryWords.RUSSIAN` and Yandex's time.

Everything else on the glass is covered or is someone else's text. The radar picture's units are
`hud_radar_unit_*`, drawn in `HudRadarBadge`. `ROAD_NAME` is Yandex's street name, passed on. The AR
fields are numbers, and the manoeuvre picture is Yandex's artwork or `HudManeuverIcon`, which draws no text.
Across the binder the app sends the module nothing but `TX_ATTACH`'s answer, an integer, so no word of ours
reaches it. The module's own Russian comes back through the host as its fields and reaches the glass as it is,
in either language, since the owner decided the module stays Russian. `HudProtocol.routeDistance(int)` and
`duration(int)` still produce those bytes, so its source compiles unchanged and the build on the car stays
valid.

### Why not `LocaleManager`

`LocaleManager.setApplicationLocales` for the app's own package would change `Locale.getDefault()`,
which `SystemLanguage` reads as the car's language, so the tile would name the wrong one. A Russian
default would also need a write on first run, before anything draws, in a process BYD autoload can
start with no Activity. This is Android's documented behaviour and was not tried on this car. No
Activity here uses AppCompat, so `AppCompatDelegate.setApplicationLocales` is not available.

### Unit tests

The suite runs on the JVM with no `Resources`. When nothing is installed, `AppText` loads
`StringsXmlSource` by name. That class exists only on the test classpath: it parses
`res/values/strings.xml`, maps an `R.string` id to its name by reflection, and reads the text the way
aapt2 compiles it (ASCII whitespace, quotes, escapes). A value starting with `@` or `?` fails the test
run, because the seam cannot resolve a reference. `values/` is Russian, so a test or board sees the
bytes it saw when the word was a literal, and neither moves when a string is extracted. The APK does
not contain the class, so on a device that path ends in the exception above.

`inEnglish { … }` (`core/InLanguage.kt`, test sources) runs a block as a process started with English
stored would run: `StringsXmlSource` reads `values-en/` and falls back to `values/` for a name that file
leaves out, English plural rules pick the form, and `AppDecimal` writes a point. It then goes back to
Russian, and `Locale.getDefault()` never moves. A surface that kept a word from an earlier call keeps it
inside the block too, which cannot happen on a car. `AppLanguageTest` holds all of this and the order of
`AppLanguageSwitch`; `LanguageRowBoardTest` holds the row on the boards to the strings.

`EnglishWords` does the same for a whole test class, and [What is tested in English](#what-is-tested-in-english)
lists the classes that use it.

### The guard

`CyrillicLiteralGuardTest` lexes the main sources of `:denza-apps`, `:hud-link` and `:dishare-bridge`.
Each file holding Cyrillic literals has an entry with its exact count and one of these reasons:

| Reason | Means |
| --- | --- |
| `external` | Text another program or the research data wrote, matched by our code. It stays Russian whatever the app speaks. Do not extract it. |
| `report` | Read by the people supporting a car. |
| `log` | A log line or a developer's message. |
| `table` | Data, such as the languages' own names. |
| `pending` | A driver's word still to extract. A file holding both counts them together. |

A new file or a higher count fails. A lower count fails too until the entry comes down, because the
room left behind would otherwise take the next literal. `feature/diagnostics` is one parked directory
with no counts, except `scan/BlockRecords.kt`, which is listed as external with its one literal. Comments
never count.

The code of the four external files (`HudCaptureSource.java`, `HudGuidanceModels.kt`,
`YandexNotificationGuidance.kt`, `BlockRecords.kt`) may not name `AppText`, `R.string` or `R.plurals`, and each carries a comment saying
why. The unit-test seam returns Russian, so `YandexGuidanceParserTest` would stay green after a wrong
extraction, and the failure would appear only in an English build. An own word in one of these files
that has to be translated moves to another file first.

Outside `AppText` the same test forbids `stringResource(` and `pluralStringResource(`, and `getString`,
`getText`, `setText`, `getQuantityString` and `getQuantityText` called with an `R.string` or `R.plurals`
id, qualified or not, because all of them read the car's locale. Two forms are left to review. An id
held in a variable (`getString(id)`, `setText(id)`) looks the same to it as
`preferences.getString(key)`, and it does not know other calls that take an id, such as
`setTitle(R.string.x)` or `Toast.makeText(context, R.string.x, …)`.

### Adding a string

1. Add it to `res/values/strings.xml`. When moving a literal, copy it word for word, and change no
   wording in the same commit, so that the tests and boards catch a mistake.
2. Read it with `AppText.s`. A constant becomes a getter, an enum's `label: String` becomes a
   `@StringRes` parameter with a `label` getter.
3. Lower the file's entry in the guard, and delete it at zero.

A word drawn in a fixed place gets a comment in `strings.xml` with its budget and its width today,
measured with `JuraMeasure` or `RobotoEstimate`, so that the English copy can be checked against them.
A sentence with a figure in it is one format string taking the printed figure as `%1$s`. A member that
only a contract test reads is cut from that sentence in the test sources, as `CAPTION_TRIP` is in
`ContourReadoutCase.kt`.

A count is a `<plurals>` with `one`, `few`, `many` and `other` filled, holding the whole phrase, read
with `AppText.p(R.plurals.x, count, count)`. `DashboardPluralsTest` holds the dashboard's against the
hand-written helpers they replaced, from 0 to 200, and `RussianPluralCategoriesTest` holds the seam to
CLDR's rules, which Android reads through ICU. `configuredApps` differs from its old helper at 21, 31
and so on, because Russian's `one` includes them. It counts three roles, so a car never gets there.

A string that quotes a label on a stock screen has a comment opening `Quotes the firmware` in both
files; `defaultapps_sheet_help` is the example. Its English comes from the firmware's labels. An
English label the docs do not record gets a comment opening `FIRMWARE:` instead, with the best guess
in the string. `StringParityTest` fails if either file lacks the mark or the key.

A new string needs its English in `values-en/strings.xml` in the same change, since that test compares
the two.

The shell helpers (`PackShellProxy` compiles them against `android.jar` alone) and `:hud-link`, which
is compiled into Navigator's dex, cannot reach `R`. Words stay out of the helpers, and `HudProtocol` takes
them as `SummaryWords`. Its four Russian defaults stay on the guard's `pending` list because the module uses them.

### The English text

`res/values-en/strings.xml` was written against the Russian and its uses in the code. The owner has not
approved it. `build.gradle.kts` has no `resourceConfigurations`, so `en` reaches the APK.

The firmware labels come from `docs/shortcuts-automation-findings.md` (the Shortcuts actions: `Navi →
Access map`, `Video → Open video`, `Music → Continue playing`, `Music → Open music`) and
`docs/adb-authorization-recovery.md` (the Wi-Fi debugging dialog). The folder name in
`warning_export_saved` is not recorded, so that string carries `FIRMWARE:`.

The drawn words are measured in [What is tested in English](#what-is-tested-in-english), which has the
numbers.

| Russian | English | Why |
| --- | --- | --- |
| штатный | stock | The docs' word, short and unambiguous. «Factory» and «built-in» are the alternatives. |
| батарея | battery | «Pack» is the docs' word; a driver reads «battery». |
| ДВС | engine | «ICE» is jargon. On the Contour in capitals: ENGINE. |
| рекуперация | regen | REGEN on the Contour. The full word costs no budget there, but «regen» is what a driver says. |
| расход | usage | On the car page. «Consumption» is 98 dp where the Russian is 55. |
| за поездку | this trip | «For the trip» is longer and adds nothing. |
| машина | car |  |
| приборка, приборная панель | cluster | The screen behind the wheel, as a place: «To cluster». |
| приборы | instruments | What is drawn there, the Contour among them. |
| Экран водителя | Driver screen | The tile, 14 characters at most, so no «Driver's». |
| Экран справа | Right screen | The tile, and the same words in its chooser's help. |
| Контур | the Contour | The design's name for our instruments. No string a driver reads uses it yet. |
| Зеркала | Mirrors | The feature's name, capitalised in a sentence. |
| Трансляция | Simulcast | The feature's name; «cast» appears nowhere. As a verb: «What to simulcast». |
| Разделение | Split | The tile. Elsewhere «split screen», as the launcher icon already says. |
| плитка | tile |  |
| выбор приложений | picker in the docs, «Choose an app» on screen | No string calls it a picker. |
| панель поездки, полоса | trip panel, the strip | «Trip panel» is the description TalkBack would read; «the strip» stays the docs' word. |
| Сервис (плитка) | Service | The tile and its panel. |
| сервис (мастерская) | service centre | In the ADB strings, so the two cannot be confused. |
| функция | feature |  |
| приложение | app |  |
| связь | link | Link with the car, cloud link. |
| доступ к машине | car access, access | The app's ADB access. |
| запрос ADB | ADB request |  |
| отладка по ADB | ADB debugging | The Russian does not quote a stock label; Android's own is USB debugging. |
| беспроводная отладка, отладка по Wi-Fi | wireless debugging | Android's name for it (docs/adb-authorization-recovery.md). |
| восстановление | recovery | «Restoring» only for a running process: «Restoring access». |
| перезагрузка | reboot | «Restart» is the short restart of features, a different thing. |
| проекция | the HUD | The windscreen projection. «Projection» reads as a video projector. |
| подсказки | hints | HUD hints. |
| манёвр | turn | Plain English, and the code's word. «Manoeuvre» also fits the glass (71 against 29). |
| ограничение (на стекле) | limit | Speed limit in the panel. |
| сценарии | Shortcuts | The firmware's own name for the engine (docs/shortcuts-automation-findings.md). |
| головное устройство | head unit |  |
| журнал работы | work log |  |
| блок | module | In «Auto-scan of modules». Plainer than ECU. |

Spelling is British, as in these docs. A figure's decimal mark comes from `AppDecimal`, so no string
writes one.

### What is tested in English

Every contract test that can mean something in English runs twice. Its English half is a small subclass
holding the `EnglishWords` rule and nothing else (`core/InLanguage.kt`). Where a test compares against a
literal it writes both with `say("…", "…")` or `figure("19,8")`, so the Russian half compares against the
same literal it always did.

| Test | In English |
| --- | --- |
| `ContourGeometryTest`, `ContourFrameBuilderTest`, `ContourFixturesContractTest` | The whole class. |
| `EnergyReadoutsTest`, `EnergyReadoutsCase` | The whole class, one-answer property included. `headingWord` pairs ENGINE on the cluster with Engine on the car page and leaves «ДВС» alone. |
| `StripGeometryTest` | The whole class, on `RobotoEstimate` and again on the font files: `StripGeometryEnglishTest`, `StripGeometryMeasuredTest`, `StripGeometryMeasuredEnglishTest`. |
| `StripBoardContractTest`, `SheetBoardContractTest`, `HudPanelBoardContractTest`, `LanguageRowBoardTest` | The whole class, against `fixtures.en.json`. |
| `TileCaptionContractTest` | One more test with the same 17 and 14 characters. The no-Latin rule becomes two: no Cyrillic, and every caption has to be a string of `values-en/`, as it stands or with its figure in, which an exception's message or a class's name is not. |
| `DashboardTilesTest`, `ContourReadoutTest`, `ContourSceneTest` | Russian only. They hold about a hundred Russian sentences one by one, and what they add for English is widths, which the rows above and below cover. |

`SpecJson.fixtures` reads `fixtures.en.json` in English, so a correction to the text fails these tests until
`shot.py --lang en --fixtures` has run.

`DrawnWordsFitTest` and `DrawnWordsFitEnglishTest` measure each word drawn in a fixed cell against that cell's
room from the code or the board: the Contour, the cluster's closed messages, the strip, the car page, the
glass picture at 432 and 384 dp, the radar, the simulcast line, the split caption, the tile faces, and the
single lines of the panels, walked from the fixtures and scanned out of the code. Compose paragraphs that wrap
are left out. Each run writes `build/reports/drawn-words/<language>.tsv`, tightest cell first.

Widths come from `FontMeasure`, which is `java.awt.Font` over `res/font/jura_medium.ttf` and Roboto 400 and 500
in `tools/design-canvas/luminofor/fonts/`. It takes the wider of the kerned and unkerned run, and a glyph the
face lacks counts as a full em. The app measures with `Paint`, which cannot run on the JVM here. On 2026-11-06
801 strings were measured in those three faces with `FontMeasure`'s arithmetic and with Chrome's
`measureText`, which is Skia and HarfBuzz like Android: AWT was never narrower, and at most 3.9 % wider in
Roboto and 5.2 % in Jura. What that leaves open:

- The system sans on the car is the firmware's Roboto, which the test has not read. It is an older cut.
- The radar's `sans-serif-condensed` and the simulcast line's bold have no file. Roboto 500 stands in for the
  first as an assumed upper bound, Roboto 500 and five per cent for the second.
- `HudRadarPainter` and `SimulcastDialogOverlay` paint without `LINEAR_TEXT_FLAG`, so hinting can round an
  advance. `LightPen`, `HudGlassPicture` and `SplitCrewView` set it, and Compose text is laid out linearly.

| Tightest cells | Russian | English |
| --- | --- | --- |
| Tile name, of 147.3 | «Экран водителя» 144.7 | Driver screen 112.3 |
| Tile state, of 147.3 | «Нет свежих данных» 138.9 | No screen chosen 120.2 |
| Panel row title, of 288 in a pane | «Восстанавливать автоматически» 283.1 | Automatic speaker control 211.8 |
| Speed unit on the glass at 384, of 24.2 | км/ч 19.2 | km/h 20.1 |
| The trace's unit behind 123,4, of 344.8 | 334.7 | 343.7 |
| Radar, tiles, digits and unit, of 246 | Ø 197 225.5 | 99.9 km 229.0 |

The digits on the radar stay at 46 in both languages, so `HudRadarPainter`'s shrinking loop never runs for these
words. `contour_per_100km` says `last`, since `in 10 km` could be taken for a distance ahead. At distance 0.0
the unit measures 198.6 of the 199.7 left behind 123,4, which makes it the tightest cell in English, and it
fits with no allowance in the test. `over` measures 202.8 and would not fit. On the car page the widest line,
Usage -188.8 kWh/100 km · last 0.0 km, is 226.4 of 392 in a one-third pane and 261.2 of 788 and 1184 in the
wider two.
`contour_unit_millivolt`, `strip_unit_*`, `strip_road_*`, `hud_glass_unit_*`, `hud_glass_distance` and
`hud_radar_unit_*` are a few dp wider than the Russian and all fit, so none of them changed.

`hud_glass_cameras` did change, in both languages. The label is in Roboto 13/500, centred on the lane window at
52, or 46 in a pane, so it has 104 dp and 92 dp inside the glass picture. «Камеры и опасности» is 127.8 dp and
ran 11.9 dp past the left edge, 17.7 dp in a pane, where the window's margin is 16. «Cameras and hazards» at
126.5 did the same, so the English became Speed cameras, 90.1. The owner shortened the Russian to «Камеры»,
49.9, on 2026-11-07. `theCamerasLabelAgainstTheGlasssLeftEdge` holds both languages inside the picture at both
widths. The panel's own row keeps the full wording, «Камеры и опасности» and Cameras and hazards.

`sheet_cluster_wheel_button` has ★, which Jura and Roboto lack in both languages. It is a Compose row title in
the system sans, so Android's fallback chain draws it, and which font that is on this firmware is unknown. On
the board Chrome borrows it from macOS. `everyLetterIsInTheFaceThatDrawsIt` holds it as the one such key and
counts it a full em. «Яндекс Навигатор», «Яндекс Музыка» and Yandex Navigator are wider than the 84 dp of an app
tile and end in an ellipsis, on the boards too; they are products' names, so the test lists them.

Looking at every English board on 2026-11-06 changed two more strings. `adb_restore_status_waiting_wifi` broke
as «Wi-» and «Fi» on `sheet-service-restore-wifi` and is now shorter, 330 dp. `sheet_cluster_map_card_summary` lost
its end to the ellipsis on `one-sheet-cluster` and is now shorter too. `aRowsSummaryEndsInsideItsTwoLines` and
`noEnglishLineEndsInsideWiFi` hold both. What stays wrong on the boards has no wording that could fix it: the
pages listed in the README keep Russian words, and the car's long warning name is cut on
`one-sheet-service-warnings`, as it is in Russian.

What only a device can settle:

- Whether the car's Roboto measures like the repo's, and which font draws ★. One screenshot of `sheet-cluster`
  and one of `main-car` settle both.
- Whether Paint agrees with these numbers. `LuminoforFixtureActivity` reads `fixtures.en.json` when the app runs
  in English, and `compare.py <id> app.png --lang en` lays the screen over its English board. The Contour's unit
  with `last` is the tightest case of this check, with 1.1 dp to spare.
- Whether the HUD panel opens in a one-third pane at all, where the Russian label is cut.

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
