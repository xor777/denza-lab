# Stock Shortcuts automation and default-app roles

What the BYD "Shortcuts" automation engine can and cannot do for a third-party
app, how Then-actions actually start activities, and which switches can point
those actions at Denza Apps.

## Current state

Updated 2026-10-15. What the stock Shortcuts/AutoVoice engine lets a third-party app launch, how
Denza Apps points the three stock default-app roles at chosen packages, and how the steering
wheel's Play/Pause and voice keys are answered.

**Normative section:** [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11)
is the contract for `feature/media`; where any other section disagrees with it, that section wins.
Everything else here is a dated findings journal.

| Claim | Status | Since | Section |
|---|---|---|---|
| Navigator 29.8.1 original and HUD debug APK: English locale selects the ordinary microphone / `Listening…` widget; Russian selects Alice and a button tap starts unsilenced MIC. The owner reports automatic language on the car; explicit Russian and restart still need car confirmation | emulator + code; car pending | 2026-10-15 | [Alice language selection](#alice-language-selection-in-the-hud-build-2026-10-15) |
| Navigator 29.8.1 exposes different microphones for free map (`navi_service_open_voice_search`) and route guidance (`guidance_open_voice_search`); semantic clicks on both start sustained unsilenced MIC. The empty-text deep link, even followed by `alice_oknyx`, did not start listening | live | 2026-10-11 | [Alice in Yandex Navigator](#alice-in-yandex-navigator-2026-10-11), [Route microphone correction](#route-microphone-correction) |
| Physical voice key `320` (scanCode `290`, device `2`): build 73 opens Alice with sustained MIC in visible Navigator, including an unfocused split pane and hidden route controls; fully hidden Navigator leaves stock BYD. Build 69 fixed the route ID, build 70 removed the manual-touch prerequisite, build 73 removed the input-focus prerequisite | live | 2026-10-11 | [Route microphone correction](#route-microphone-correction), [Hidden route controls](#hidden-route-controls-without-a-manual-touch), [Visible split](#visible-navigator-in-split-screen) |
| Build 73 physical-key acceptance: Music focused, Navigator visible/unfocused, Denza Apps hidden; both free map and hidden route controls opened Alice with sustained MIC. With Navigator fully hidden, stock BYD opened | live | 2026-10-11 | [Visible Navigator in split screen](#visible-navigator-in-split-screen) |
| Wheel Play/Pause is keyed by **package**: the last package seen PLAYING is persisted in prefs `media_resume`, `last_played_package`, with no time and no expiry (`last_played_at`, never read, stopped being written on 2026-10-09) (`MediaResumeCore.kt`, `MediaLastPlayedPreferences.kt`) Play resolves PLAYING → pause; live or dormant last-package session → play; otherwise stock. | code | 2026-10-09 | [Resume contract](#resume-contract-the-package-not-the-token-2026-09-11) |
| A session that leaves `getActiveSessions` stays commandable until `onSessionDestroyed`: this vehicle's `MediaSessionRecord` routes `play()` without reading `mIsActive` | firmware | 2026-09-11 | [Resume contract](#resume-contract-the-package-not-the-token-2026-09-11) |
| The key reads sessions through the process's one `MediaSessionHub`, shared with the speaker covers and the strip: one listener and one callback per session, a dormant session kept until destroyed; a key that starts listening adopts only the active list, and a press (`MediaResumeSessions.press`) re-reads the list synchronously without telling the other subscribers. A lost grant, which the firmware answers by disconnecting the listener with an empty list, is noticed and the key reads «нет доступа к сессиям» until the repair has the hub listen again (`MediaResumeSessions.kt`, `MediaSessionHub.kt`) | code | 2026-10-09 | [Resume contract](#resume-contract-the-package-not-the-token-2026-09-11) |
| The firmware's Play fallback (`MediaKeyHandler`) is the audio-focus owner's controller, else `com.byd.mediacenter`, so every Play we refuse opens the stock player | firmware | 2026-09-05 | [Why Pause can work while Play selects stock music](#why-pause-can-work-while-play-selects-stock-music) |
| Z9GT wheel: play/pause arrives as `386`; next/previous arrive twice each, `307`/`308` and then the re-injected `87`/`88`; direct VK Video/Yandex Music pause/resume was operator-confirmed on 2026-09-05 | live | 2026-09-18 | [The press the preparation swallowed](#the-press-the-preparation-swallowed-and-what-the-car-taught-on-2026-09-18) |
| The shared accessibility service offers every key to `MediaKeyRider` first (★, `SteeringWheelKeyRider`, only gets what it leaves); it takes `126`/`127`/`85`/`386` and consumes a whole down/up only when the policy accepts it; `334`/`335` keep the stock route (`MediaResumeCore.kt`, `MediaResumeKeyInterceptor`, `MediaKeyRider.kt` since 2026-10-09) | code | 2026-09-05 | [Minimal built-in slice](#minimal-built-in-slice-requested-by-the-owner) |
| No shell helper edits the audio-focus stack: a pause with paused predecessors is an ordinary pause, and a player paused under a video may resume when the video pauses. The helper, switched off on 2026-09-18 (`FOCUS_SURGERY = false`), was moved out of the product to `research/media-focus/` on 2026-10-09 by the owner's decision, with its flag, the deferred-pause path and the APK's `media-focus-pause-proxy.jar` (`MediaResumeCore.kt`, `MediaKeyExperiment.kt`) | code | 2026-10-09 | [Resume contract](#resume-contract-the-package-not-the-token-2026-09-11) |
| Focus-stack surgery as the answer to VK→Yandex auto-resume: worked on 2026-09-05, then threw on 6 of 7 presses, held each pause ~650 ms and once emptied the stack so the next press reached the stock player | refuted | 2026-09-18 | [Resume contract](#resume-contract-the-package-not-the-token-2026-09-11) |
| BYD's self-start gate (`ActivityManagerService.isEnableFeature()` in `bindServiceLocked`, `startServiceLocked`, `BroadcastQueue`) refused the `MediaBrowser` bind and both `MEDIA_BUTTON` broadcasts to Yandex; nothing played (build 47) | live | 2026-09-18 | [The firmware's self-start gate](#the-firmwares-self-start-gate-blocks-both-reconnect-paths-2026-09-18) |
| Nothing brings back a player the car unloaded: the reconnect (`MediaBrowser` bind, directed `MEDIA_BUTTON`) is deleted, a press with no live session of the last-played package is not consumed and the ring shows `✗ <package> stock-no-live-session`; starting the player's activity was rejected (owner's decision) | code | 2026-10-08 | [No resurrection after sleep](#no-resurrection-after-sleep-2026-10-08) |
| "`ForegroundServiceStartNotAllowedException` inside Yandex loses the reconnect Play": the press never reaches Yandex, the gate drops it first | refuted | 2026-09-18 | [The firmware's self-start gate](#the-firmwares-self-start-gate-blocks-both-reconnect-paths-2026-09-18) |
| "A checked switch in `com.byd.appstartmanagement` is the permissive state": checked is the deny bit (`getAppStartupData(uid) == 1`), new installs get `1`, every APK update re-blocks (split-screen-findings.md) | refuted | 2026-09-23 | [The firmware's self-start gate](#the-firmwares-self-start-gate-blocks-both-reconnect-paths-2026-09-18) |
| Yandex Music 2026.07.2: `MusicBrowserService.onGetRoot` returns `null` to Denza Apps (caller allowlist); the exported `DebugMediaButtonReceiver` forwards `KEYCODE_MEDIA_PLAY` to the player service | firmware | 2026-09-11 | [Resume contract](#resume-contract-the-package-not-the-token-2026-09-11) |
| Support report «Кнопка play/pause на руле»: `Кнопка play/pause=`, `Режим медиакнопки=`, `Запомненная сессия=`, `Последние нажатия=` (last 12 presses); the only trace where `log.tag=M` hides `DenzaMediaResume` (`MediaKeyDiagnostics.kt`) | code | 2026-09-18 | [What the support report says](#what-the-support-report-says-about-the-key-2026-09-11) |
| `content://com.byd.autovoice/PersonBean` is exported with no permission or caller check: app-UID `query` 2.3 ms, `update` 3.1 ms, shell `content query` 1.21 s; a `ContentObserver` saw no change | live | 2026-09-03 | [App-UID ContentResolver access](#app-uid-contentresolver-access-live-proven-2026-09-03) |
| `DefaultAppRoleRepository.kt` reads and writes `DEFAULT_MAP_SWITCH`, `MUSIC_SWITCH`, `VIDEO_SWITCH` through `ContentResolver` (`SETTING=?`; conditional `SETTING=? AND VALUE=?` matching exactly one row); each role holds the chosen app's real package; no ADB, no proxy. This is not global replacement of every stock navigation surface | code | 2026-09-04 | [App-UID ContentResolver access](#app-uid-contentresolver-access-live-proven-2026-09-03), [Map-role consumers](#two-independent-map-role-switches) |
| Single-package navigation proxy (`DEFAULT_MAP_SWITCH=dev.denza.apps` plus a trampoline): AutoVoice "open app" and the map role both arrive as `getLaunchIntentForPackage(dev.denza.apps)` with `MAIN + INFO` and `FROM=com.byd.autovoice`; retired in build 42 | refuted | 2026-09-03 | [Retired single-package navigation proxy](#retired-single-package-navigation-proxy-experiment-2026-09-03) |
| AutoVoice's `AppReceiver` resets `DEFAULT_MAP_SWITCH` to stock on `PACKAGE_REMOVED` without reading `EXTRA_REPLACING`, so a Store update of the chosen navigator drops the role | firmware | 2026-09-03 | [Retired single-package navigation proxy](#retired-single-package-navigation-proxy-experiment-2026-09-03) |
| While Denza Apps is running it puts the navigator back after such an update: armed when the package it last confirmed in the role is removed with `EXTRA_REPLACING`, restored on `PACKAGE_ADDED`/`PACKAGE_REPLACED` by a write conditional on the stock map; a stopped app does not repair it (`NavigationRoleRepair.kt`) | code | 2026-10-08 | [Navigation role repaired after a Store update](#navigation-role-repaired-after-a-store-update-while-denza-apps-runs-2026-10-08) |
| Shortcuts actions that honour the roles: map `102000` (导航 → 地图 → 打开), music **Continue playing** runtime `129003`, video **Open video** runtime `131500` (static `131501`) | live | 2026-08-27 | [Role switches](#role-switches-live-2026-08-22-rechecked-2026-08-27) |
| "Music → Open music follows `MUSIC_SWITCH`": live runtime `129136` opened `com.byd.mediacenter`; use Continue playing | refuted | 2026-08-27 | [Music — static open path versus the live launch action](#music--static-open-path-versus-the-live-launch-action) |
| The Then catalog is compiled in (`DiyChoiceScence5_1.initLevel1()`), `101000` offers five BYD names, and `IOTProvider` insert admits only `com.byd.iotmanager`/`com.byd.mediacenter`: no arbitrary app per rule | firmware | 2026-08-22 | [Product shape](#product-shape-inject-into-autovoice-do-not-daemonize-denza-apps) |
| Key `321` is the wheel custom key: on this car action `1` opens `com.byd.avc` and starts an APA scan; never inject it | live | 2026-08-16 | [Never inject key code 321](#never-inject-key-code-321-as-a-navigation-trigger) |

**Open questions**
- Alice: build 73 passed physical-key acceptance in unfocused split panes with free map and
  hidden route controls, and the stock route when Navigator is completely hidden. An already-open listening panel, other Navigator versions/layouts and
  restart/sleep remain unverified; those need their own physical-key runs. The panel
  deep-link/click fallback did not start listening. The owner chose the stock
  voice key and, subsequently, a visible-window gate including unfocused split panes.
  Launching a completely hidden or cold Navigator remains outside this binding.
- Several sessions PLAYING at once: the policy pauses the first one it finds and records any PLAYING
  package, the stock player included, as last-played (`MediaResumeCore.perform`, `markPlaying`).
  Settled by deciding which session the driver means and a ring capture with a video paused in the
  other pane.
- Acceptance steps 2 (our service restarted), 3 (the player unloaded, as rewritten 2026-10-08) and 5
  (stock or Bluetooth as the last source) have no recorded run. Settled by running them as written.
- Which codes the N9 wheel sends, `386` or `334`/`335`. Settled by an N9 support report taken after a
  few presses.
- Next and previous in the same policy (the "second step"). Whether the focus helper is deleted is
  no longer part of it: the owner had it moved to `research/media-focus/` on 2026-10-09, whose
  README says how to bring it back. Settled by a design of the second step.
- Role recovery after a Store update with Denza Apps stopped: whether it receives `PACKAGE_REMOVED`,
  `PACKAGE_ADDED` and `PACKAGE_REPLACED` with `EXTRA_REPLACING`, under the self-start deny bit
  (split-screen-findings.md). Settled by the isolated package-event probe in Next validation. The
  running-app repair has no live run yet: settled by a Store update of the chosen navigator with
  the app open and a `DEFAULT_MAP_SWITCH` readback.
- Any app per rule: a persist path for a `101000` + label `DiyCommandBean`; the next bounded probe is
  the `IOTProvider` caller gate, invalid JSON only.
- Shortcuts `102000` with Denza Apps force-stopped, and `129003`/`131500` from cold state and with
  active sessions, on a direct-role build. Settled by the Next validation runs.

## Contents
- [Alice in Yandex Navigator (2026-10-11)](#alice-in-yandex-navigator-2026-10-11) — a failed empty-text deep link and a live-proven semantic microphone click.
- [Alice language selection in the HUD build (2026-10-15)](#alice-language-selection-in-the-hud-build-2026-10-15) — original/debug locale comparison; automatic language can select ordinary speech search.
- [Steering-wheel Play/Pause feasibility (2026-09-05)](#steering-wheel-playpause-feasibility-2026-09-05) — corpus key routing, the accessibility key filter, the VK/Yandex focus diagnosis, the normative resume contract, its 2026-09-18 live corrections, and the 2026-10-08 decision to leave a player the car unloaded to the firmware.
- [Evidence base for the role findings (2026-08-16 to 2026-08-27)](#evidence-base-for-the-role-findings-2026-08-16-to-2026-08-27) — what the August live passes and decompiles covered.
- [Where the feature lives](#where-the-feature-lives) — `com.byd.autovoice/.DiyCommandActivity` and its GreenDAO storage.
- [Trigger catalog (rich)](#trigger-catalog-rich) — the If-side conditions.
- [Execution catalog (closed in the UI)](#execution-catalog-closed-in-the-ui) — Then items, catalog IDs against live runtime IDs.
- [How Then-actions start an activity](#how-then-actions-start-an-activity) — the `getLaunchIntentForPackage` paths for map, music, video and named app.
- [Role switches (live 2026-08-22, rechecked 2026-08-27)](#role-switches-live-2026-08-22-rechecked-2026-08-27) — PersonBean keys, stock values, read/write recipe.
- [Signature-gated hooks (still closed)](#signature-gated-hooks-still-closed) — `thirdapp`, IoT broadcast, `BYDAUTO_*`, `IOTProvider`.
- [Masquerading as a Chinese app does not win the role](#masquerading-as-a-chinese-app-does-not-win-the-role) — why a fake Gaode/NetEase package changes nothing.
- [Registration APIs and adjacent paths](#registration-apis-and-adjacent-paths) — PersonBean, `setDefaultApp`, `FUNCTION_UPDATE`, voice by label.
- [Two independent map-role switches](#two-independent-map-role-switches) — `DEFAULT_MAP_SWITCH` against `byd_map_package`.
- [Product shape: inject into AutoVoice, do not daemonize Denza Apps](#product-shape-inject-into-autovoice-do-not-daemonize-denza-apps) — the «Приложения» panel, the retired proxy, the navigation repair after a Store update, app-UID PersonBean access.
- [Russia-oriented launch strategy](#russia-oriented-launch-strategy) — which Shortcuts action to use per role.
- [Restore-wrapped live probe](#restore-wrapped-live-probe) — `tools/default_app_role_probe.sh`, and never key `321`.
- [Cost to weigh before taking the role](#cost-to-weigh-before-taking-the-role) — what each role is shared with.
- [Next validation](#next-validation) — outstanding live checks for the roles.

## Alice in Yandex Navigator (2026-10-11)

**Result:** the visible microphone control can be invoked programmatically by its view ID.
An accessibility `ACTION_CLICK` opened Alice and started a sustained microphone session.
This is integrated into Denza Apps through the existing shared accessibility service.
Build 70 passed the physical-key hidden-route case; build 72 extends selection to visible,
unfocused split panes. See [Visible Navigator in split screen](#visible-navigator-in-split-screen).
This result assumes Alice is enabled for the app's language. The same button IDs can instead
invoke ordinary speech search; see the [2026-10-15 language comparison](#alice-language-selection-in-the-hud-build-2026-10-15).

### Identity and evidence

Installed `ru.yandex.yandexnavi` is **29.8.1**, `versionCode=739494300`.
The installed APK and `captures/hud-lanes-20261011/yandex-installed.apk` both hash to
`3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3`.
The focused decompile, including resources, lives at
`captures/firmware/yandexnavi-29.8.1-3b0dec32/jadx/Navigator/`.
Sources extracted from individual DEX files extend the same tree; the complete APK is reused,
not downloaded or modified. Evidence is in `captures/alice-navigator-20261011/`:

- `identity.json`, `EXPECTATIONS.md`, and the focused JADX logs and reference indexes;
- `live-empty-text/`: launch output, short logcat, screenshots, activity/audio state, crash buffers;
- `live-semantic-button/`: the same observations for the accessibility click;
- `ui-inspect.txt` and `accessibility-after.txt`: discovered controls and still-enabled services.

The owner confirmed that the car was free for the investigation. No APK was installed or
replaced, no vehicle setter or wheel key was injected, and the existing ADB transport was kept.
Screenshots and raw logs remain local because they contain location and other session data.

### Why the deep link is only half an answer

The [public Navigator launch documentation](https://yandex.ru/dev/navigator/doc/ru/)
describes `ACTION_VIEW`, the `yandexnavi` scheme and pinning the package. The extra
`ask_alice` route was found in this APK, not in that public API documentation.

The source chain, relative to the decompile's `sources/`, is:

1. The exported `ru.yandex.yandexmaps.launch.LaunchActivity` accepts `yandexnavi` and forwards
   the URI to `MapActivity`. The old `core.NavigatorActivity` identity is a manifest alias.
2. `ru/yandex/yandexmaps/multiplatform/uri/parser/api/events/l.java` accepts host `ask_alice`
   only when the `text` parameter exists; it creates event `m`. An empty value is accepted.
3. `ru/yandex/yandexmaps/launch/handlers/h.java` invokes `app.x3.v` with source `DEEPLINK`.
   `app/x3.java` opens the Alice controller and then calls its service's `h(text)`.
4. `alice/internal/alicekit/service/l.java:h` calls `r()` to stop recognition, constructs
   `dialog://?text=<text>&session_type=voice`, and dispatches it. The `dialog` branch in
   `g52.a.b` calls `n(text)`, which sends a text request. The recovered branch is also saved
   as `captures/alice-navigator-20261011/reports/DialogRouter.java`.

The one empty-text launch was:

```sh
adb -s 127.0.0.1:5555 shell am start -W \
  -a android.intent.action.VIEW -p ru.yandex.yandexnavi \
  -d 'yandexnavi://ask_alice?text='
```

It returned `Status: ok`, `WaitTime: 114`, and displayed Alice's «Чем я могу помочь?» panel.
But `AudioRecord.start` at car-log time `15:45:35.617` was followed by `stop` at `.662`;
AudioService recorded the microphone's update at `.671` and stop at `.731`. The +0.7, +2 and
+4 s audio snapshots contain no active Navigator recording. A panel appearing is therefore
insufficient evidence of a working voice trigger. The red orb afterwards does not by itself
establish why a network request failed; the local cancellation path is independently visible
in both the source and audio trace.

Other inspected paths do not supply a direct listening command: `VOICE_COMMAND` resolves to
no activity in this package, the three Alice shortcut activities are disabled in the manifest,
and `OverlayActivity` is not exported. `MapActivity`'s own key-event subscription was traced
to volume-up/down zoom (`map.controls.impl.t1.h`, codes 24/25). This is evidence about this
APK's inspected paths, not a claim that every Yandex version has no other entry point.

### The semantic microphone click that worked

The live tree contains exactly one enabled, visible and clickable control:

```text
ru.yandex.yandexnavi:id/navi_service_open_voice_search
  child: ru.yandex.yandexnavi:id/control_voice_search_image
  child contentDescription: Голосовой поиск
```

`tools/YandexAliceUiProbe.java`, driven by `tools/yandex_alice_ui_probe.sh`, reads Navigator's
windows and invokes `AccessibilityNodeInfo.ACTION_CLICK` on that exact control. It checks
package, visibility, enabled state and uniqueness, refreshes before clicking, and does not
fall back to coordinates. It runs as shell through `app_process`, with no APK installation,
and connects with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Unlike `uiautomator dump`,
which failed here with `could not get idle state`, it does not wait for the animated map to idle.

```sh
# Read only; Navigator must already be visible on the default display.
bash tools/yandex_alice_ui_probe.sh inspect

# A live microphone activation, only when the car is free for the experiment.
bash tools/yandex_alice_ui_probe.sh click \
  ru.yandex.yandexnavi:id/navi_service_open_voice_search
```

The live call returned `ACTION_CLICK accepted=true`. Alice appeared; the audio snapshots
at +0.7, +2 and +4 s all show session **257**, UID **10133**, source **MIC**, package
`ru.yandex.yandexnavi`, **active=true**, **silenced=false**. The +2 s screenshot shows the
listening orb. The later settled audio snapshot confirms that the recording ended; the probe
does not leave an ongoing capture behind. Both runs' before/after crash buffers are identical.
No spoken command or server answer was used as acceptance evidence.

### Alice language selection in the HUD build (2026-10-15)

The owner reported an ordinary microphone icon and a speech-recognition widget instead of
Alice in the modified Navigator, with its language set to **system/automatic**. This symptom
was reproduced on a disposable Android emulator. No car was connected during this test;
the car's effective process locale was not read.

The two APKs were installed successively on the same `denza_hu` read-only emulator
(`emulator-5580`). Each had microphone permission, no signed-in account, and the same map
deep link. The original required uninstalling the modified package on this disposable
emulator because their signers differ. Within each APK, only the Android per-app locale was
changed, followed by force-stop/relaunch; no reinstall or data clear separated its two locales.

| APK | English | Russian |
|---|---|---|
| Original 29.8.1, SHA-256 `3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3` | Plain microphone; tap opens `Listening…` / `One second…` | Purple Alice icon; tap opens «Чем я могу помочь?» and unsilenced MIC |
| Brighter-roads HUD debug, SHA-256 `d23c4054c9396ecc8c042f40b432c9667001e7cd590ddb284a1cb735dc1cb57e` | Same plain microphone and recognition widget | Same Alice panel; MIC remains active and unsilenced across three audio snapshots |

The modified APK initially inherited English from the emulator. To change only its locale:

```sh
# Disposable emulator only. Android per-app locale, not a vehicle/global locale change.
adb -s emulator-5580 shell cmd locale set-app-locales ru.yandex.yandexnavi --user 0 --locales ru-RU
adb -s emulator-5580 shell am force-stop ru.yandex.yandexnavi
```

The existing decompile independently explains the selection:

- `ru/yandex/yandexmaps/controls/voicesearch/ControlVoiceState.java` has `ALICE`, `SPEECH_KIT`
  and `DISABLE`; `ControlVoiceSearch.setState` chooses Alice or microphone artwork.
- `ru/yandex/yandexmaps/alice/q.java` supplies RU/TR for the Alice product kind. EN/AR belongs
  to the separate Yasmina kind, not a universal English Alice fallback.
- `ru/yandex/yandexmaps/alice/o.java` selects the Alice service only if the current language
  is in that set; otherwise it selects the fallback service.
- `ru/yandex/yandexmaps/multiplatform/core/utils/q.java` obtains the language from
  `Locale.getDefault().getLanguage()`.

`classes3.dex` (the voice control) and `classes16.dex` (Alice selection/services) are byte
identical in the two APKs. The locale switch fixes the reproduced icon/widget symptom without
changing the HUD module, account type, signing key or server credentials. For the owner,
the next car check is to select **Russian explicitly in Navigator** and fully restart it.
The emulator test used Android's per-app locale; the in-app language selector on this car
has not yet been exercised. Automatic language plus the matching symptom makes locale the
leading car explanation, not a measured car root cause.

Evidence: `captures/yandex-hud-alice-20261015/apk-identity.json`, `modified/` and `original/`
contain UI XML, screenshots and audio snapshots. The emulator ran with `-no-audio`: active
MIC proves the recording session, not recognition of a spoken command. Nonempty `ask_alice`
text probes did not establish a conversational answer: `2+2` ended on a map search result
for a business of that name; the subsequent Russian arithmetic question left that result
visible. Do not claim end-to-end voice recognition, a server answer or audible speech from
this run. The redacted process log's Firebase/RuStore push errors are not Alice failure proof.

### Product boundary

Denza Apps already has `FLAG_REPORT_VIEW_IDS`, interactive windows, window-content access and
key filtering in `simulcast_a11y_service.xml`. Its `SimulcastAccessibilityService` delegates
to accessibility riders. Build 68 uses that service through `AliceVoiceKeyRider` and the same
`performAction(ACTION_CLICK)` public API, with no competing `UiAutomation` connection.

The **initial microphone-only proof was shell-UID and Navigator already visible on IVI**.
It is extended below by the [ordinary app-UID physical-key run](#app-uid-wheel-acceptance).
**Owner's subsequent scope decision (2026-10-11):** the stock voice wheel key must
invoke Alice whenever Navigator is visible on the main screen, including an unfocused split
pane beside any other app. Denza Apps itself may be in the background. This later clarification
replaces the initial input-focus-only gate. A completely hidden or unloaded Navigator remains
outside this binding: leave those presses to the firmware. Different layouts may
expose another microphone ID, so the single observed ID is not a universal fallback. Repeated
presses while Alice is already listening still need live acceptance. The initial research did
not change product code; the owner subsequently requested the [product integration](#denza-apps-integration).

### Voice wheel key, only with Navigator focused

> **Scope superseded later on 2026-10-11:** the following is the original probe contract.
> Build 72 uses [visible Navigator windows](#visible-navigator-in-split-screen), including
> an unfocused split pane; input focus is no longer required.

**Feasibility:** the firmware provides the required interception point, and the later
[disposable probe run](#app-uid-wheel-acceptance) proved it live from an ordinary app UID.
The subsequent [Denza Apps integration](#denza-apps-integration) uses the same interception point.
The existing service already requests key filtering and window content; the live accessibility
dump still shows it bound with capabilities `9`. A second permanent service is unnecessary.

The live `/system/framework/services.jar` SHA-256 is
`23a58a4e3c98c50541785390f1234e8c4d7138b5dd170c4f5843bae15b93c019`, and
`dilink-services.jar` is
`6e709f430c4b972dc1a869253717a999add80b74e484656d74716eabe1ac3f00`.
Both match the corpus under `captures/split-firmware-20260923/jadx/`.
Identity is recorded in `captures/alice-navigator-20261011/wheel-firmware-identity.txt`.

The complete route in that corpus is:

1. `framework/.../android/view/KeyEvent.java` names **320**
   `KEYCODE_AUTO_MEDIA_VOICE`. This differs from Android `VOICE_ASSIST` **231** and
   the custom star key **321**. `328` has a long-press constant, but its physical routing is
   unproven and is not part of this short-press conclusion.
2. `services/.../policy/PhoneWindowManager.isKeyPassToUser()` includes `320`.
   Its queueing method does not launch the voice assistant for this code.
3. `services/.../accessibility/KeyboardInterceptor` delivers `320` straight to
   `AccessibilityManagerService.notifyKeyEvent()`. Only volume `24/25` get an early policy
   dispatch check there. `KeyEventDispatcher` waits up to **500 ms** for service results and
   forwards an unhandled event. Android's
   [onKeyEvent contract](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onKeyEvent(android.view.KeyEvent))
   requires consuming a consistent DOWN/UP stream.
4. Downstream, `PhoneWindowManager.interceptKeyBeforeDispatching()` handles `320` only on
   **UP with repeatCount zero**. Normal JADX output is malformed; the existing
   `reverse/speaker-lift/PhoneWindowManager-simple.java`, labels `L353`/`L367`, shows the
   condition. `PhoneWindowManagerExtImpl.handleMediaKey()` calls
   `MediaKeyHandler.onMediaKey()`, whose `case 320` sends a package-directed broadcast
   `android.intent.action.MEDIA_VOICE` to `com.byd.autovoice`.

Thus a successful accessibility consumption happens before the BYD wake broadcast. Watching
that broadcast and trying to close BYD afterwards would be too late; it is also a normal
package-directed broadcast, not an ordered broadcast for another app to abort.

Binding behavior tested in the probe (the product's missing-control policy is described below):

- On the initial `320` DOWN, require Navigator's `TYPE_APPLICATION` window to have **input
  focus on display 0**, and require the exact visible, enabled microphone control. Use
  `AccessibilityWindowInfo.isFocused()`, not accessibility focus or the last event package.
  A running process, a background task, or a projection on another display is insufficient.
  In split screen this strict definition selects the focused pane, not every visible pane.
- If the initial gate fails, leave the entire press to stock routing, even if focus later
  changes to Navigator. If accepted, own DOWN/repeats/UP as one press and invoke Alice once;
  recheck the Navigator window before the click. Losing focus must cancel the click without
  leaking a lone UP to BYD. Do not launch Navigator or inject a replacement key.
- A small rider in the existing `SimulcastAccessibilityService` can own this distinct code;
  the media rider owns `126/127/85/386` and the navigation star rider owns `321`, so there is
  no code overlap. Keep the callback bounded: a delayed result beyond the framework's
  500 ms timeout can let stock routing proceed. The probe's accepted callbacks took 2/0 ms;
  product lifecycle and failure behavior still need their own acceptance.
- Acceptance must show Navigator's unsilenced recording **and no BYD wake** for a physical
  press, then normal BYD behavior outside Navigator, no click after a focus change, and
  one invocation per press. Long presses and an already-open Alice panel are separate cases.

The existing shell `UiAutomation` microphone probe is not a key-consumption proof:
`AccessibilityManagerService.notifyKeyEvent()` sends keys only to `mBoundServices`, whereas
the automation connection is kept separately in `UiAutomationManager`. Use an ordinary
app-UID accessibility service for the eventual interception run.

A first bounded 120-second passive observation was prepared in
`captures/alice-navigator-20261011/wheel-observation/`. No physical press was confirmed by the
owner during that window and no key trace appeared; this neither confirms nor refutes the
physical mapping. The two temporary log-tag properties were restored exactly, the crash
buffer was unchanged, and both Navigator and Denza Apps APK hashes were unchanged. No key
was injected and no interception was armed.

**Physical mapping confirmed on the second observation.** The owner requested another run
and confirmed pressing the button. `wheel-observation-02/trace.txt` records pairs at car time
**16:04:07.532** and **16:04:13.392**, both `keyCode=320`, DOWN then UP, `repeatCount=0`.
Each UP reached `MediaKeyHandler` as `KEYCODE_AUTO_MEDIA_VOICE`, `scanCode=290`, `deviceId=2`,
`source=0x101`, `displayId=-1`. BYD voice windows appeared after the first pair and were
removed after the second. The trace does not establish which physical gesture produced each
pair beyond the owner's press confirmation; it does establish the actual voice-key code.

The key's `displayId=-1` means the gate must query IVI windows (originally the focused one;
build 72 instead selects a visible Navigator application window); requiring the key itself to carry displayId `0` would reject this real
press. The observation was stopped once the evidence was captured, with cleanup in `finally`:
log properties restored exactly, crash buffer unchanged, both APK hashes unchanged
(`wheel-observation-02/result.json`). This confirms mapping and stock handling, **not** a
successful app-UID filter callback, consumption or Alice invocation from the wheel. Those
were established in the subsequent run below.

### App-UID wheel acceptance

**Both requested cases passed on 2026-10-11**, with the owner physically pressing the key
and reporting which assistant opened. The disposable
[`experiments/alice-wheel-probe`](../experiments/alice-wheel-probe/README.md) installed as
`dev.denza.alicewheel.probe`, UID **10127**, APK SHA-256
`6f4040380d0519ce631816d4a1cf5b7079c34f293be66eaa1e60eabe1d5aa059`.
It used public accessibility APIs, no root or BYD permissions, and was added alongside the
existing services. Denza Apps and Navigator were not rebuilt or replaced.

Evidence directory: `captures/alice-navigator-20261011/live-wheel-03/`, with the prewritten
`wheel-interception-expectations.md` one level above. `trace.txt`, the three `audio-click-*`
dumps, `negative-focused.txt`, `operator-confirmation.json`, and `result.json` hold the proof.

| Case | Observed result |
|---|---|
| Navigator focused on display 0, window 295; voice key at 16:10:01.303 car time | Probe DOWN at .306: `consumed=true`, 2 ms; UP at .307: `consumed=true`, 0 ms; semantic `CLICK accepted=true` at .412. No downstream `interceptKeyTi` for 320, no `MediaKeyHandler` voice call or new AutoVoice window in this phase. Owner: **Alice**. |
| Navigator microphone | AudioService `rec start` at 16:10:01.444, UID 10133, riid 575, session 281. At +0.3/+1.5/+3.5 s after click acknowledgement: `active? true`, source `MIC`, `silenced:false`. Approximately 141 ms from the physical key's queue trace to recording start. |
| Denza Apps focused on display 0, window 303; probe rearmed, `readyWindow=-1`; voice key at 16:10:48.768 | Both events `consumed=false`; stock dispatch and `MediaKeyHandler` UP at .771/.772; new BYD voice windows at .875/.902. Owner: **stock BYD**. A second pair at 16:10:49.865 also passed through; total Alice click count remained one. |

The probe prefetches the exact microphone node only while armed, verifies the current focused
window on DOWN, owns the matching DOWN/repeats/UP, then posts a fresh window/node check and
one click after returning the key-filter result. One attempt disarms it; expiry is at most
180 seconds. It never opens a background Navigator. The host's ten-minute watchdog and explicit
STOP cleanup provide a second boundary.

The first installation (`live-wheel-02/`) did not bind the service within its observation
window and was cleaned up without arming. On reinstall, waiting two seconds after waking
the process showed `installedServiceCount=8` before enable and the probe bound immediately.
This is consistent with a package-registration timing issue, but the first attempt did not
capture enough state to establish that cause. No product or probe APK change was made between
these two installations. The earlier `live-wheel-01/` stopped before installation because
the host treated `pm path`'s missing-package exit status as an error.

Cleanup passed: probe disarmed and uninstalled (`Success` and package absent), original enabled
service set restored (Android canonicalized component spellings/order), all three temporary log
properties restored, crash buffer unchanged, and hashes of Denza Apps, Navigator and the probe
unchanged over the run. Three host unit tests for whole-press ownership and the probe build passed.

This proves the requested basic behavior **on this car and Navigator build** in the probe.
The product rider is described below. Repeated invocation while Alice is already open, long
presses, split-screen focus transitions during a held press, restart/sleep and updated
Navigator layouts remain outside this probe run.

### Denza Apps integration

> **Corrected 2026-10-11:** build 68 searched only the free-map microphone. Build 69 adds the
> route-specific ID; see [Route microphone correction](#route-microphone-correction). The
> initial build 68 acceptance failures below remain evidence, not the current selector.
> Build 72 also replaces the original focus-only gate with visible-window selection below.

Build **68**, requested by the owner on 2026-10-11, adds `feature/alice/` to the existing
shared accessibility service. `AliceVoiceKeyRider` follows the media and star riders;
`AccessibilityKeyEvent` preserves device, down-time, event-time and cancellation while
keeping the older riders' three-argument interface. No new service, permission, setting,
shell command or experimental-package dependency is added to the product.

`NavigatorVoiceWindow` prefetches visible application roots on display 0 and caches Navigator
window identities, with a retry on Navigator content if a root is not ready. On `320` DOWN,
`NavigatorWindowPolicy` selects exactly one known Navigator window with currently exposed
area after subtracting higher windows. Focus and the peer app's identity do not participate.
No root traversal or click occurs in the key callback. Physical events have displayId `-1`,
so that field is deliberately not the main-display test. This is the build 72 policy; builds
68–70 required input focus.

`AliceVoiceKeyController` owns the matching device/down-time DOWN, repeats and UP. On UP it
posts one click to the service looper, rechecks the same visible Navigator window, and finds
exactly one visible, enabled, clickable microphone from `NavigatorVoiceControls`: free map
`navi_service_open_voice_search` or, since build 69, active route `guidance_open_voice_search`. It refreshes
that node and the window's current visibility once more before `ACTION_CLICK`. Semantic
clicks do not require an uncovered center; coordinate reveal gestures do. Hiding/replacing the target window or disconnecting the service cancels queued work; an already-owned UP remains consumed. Keys older than 350 ms
are not newly claimed and a click queued longer than 350 ms is dropped. The firmware's
500 ms filter timeout remains a limit: no app-level handler can guarantee consumption
through an arbitrarily stalled process.

The product deliberately owns a press whenever Navigator is confirmed visible, even if the
microphone node subsequently disappears. Builds 68–69 did nothing in that case and did not wake BYD;
build 70 adds the bounded [hidden-controls reveal](#hidden-route-controls-without-a-manual-touch).
An absent, fully covered or unknown Navigator window leaves the entire press stock. A second press while
the previous click is still queued is consumed without adding a duplicate; once completed,
a new press can click again without the probe's one-shot arm. An already-listening panel
may lack the known node and therefore do nothing. No background/cold launch, deep-link fallback
or injected key is used. Long-press code `328` is left stock and is not live-accepted here.

Build/test: `./gradlew :denza-apps:testDebugUnitTest :denza-apps:assembleDebug` with the root
JDK/SDK environment. All **2,141 tests in 255 suites passed**, including 15 controller cases
and actual rider-routing tests for disjoint media/star/voice key sets. Evidence:
`captures/alice-navigator-20261011/product-build-68.log`, `product-tests-68.json`.
APK SHA-256: `2275ee7e1ffb20d70363cf93a4c72bb5626457dd630a4e951c02e48f2990ea24`.

Install the APK with `adb -s 127.0.0.1:5555 install -r`, open `dev.denza.apps/.MainActivity`
and verify the shared `SimulcastAccessibilityService` is bound. It is active automatically
while that service is bound, including after its reconnect; disabling the shared service
in Android accessibility settings stops it together with the other riders. Diagnostics:
`DenzaAliceKey` logcat tag (`key action=… consumed=… callbackMs=…`, `click accepted=…`).
On this firmware the tag may require a temporary `log.tag.DenzaAliceKey=I`; restore its
previous value after diagnosis. Never run the disposable wheel probe alongside this rider.

The first product run **failed to invoke Alice in the hidden-controls guidance layout**.
At 16:22:13.886, physical `320` reached the filter; DOWN/UP were consumed at .890/.891
(0 ms callbacks), but `click accepted=false window=321` followed at .894. The owner confirmed
no Alice. The captured accessibility tree has no microphone node in this layout; the map
screenshot shows the route, speed and ETA without the vanishing controls. This is a real
limitation of the promoted single-node implementation, not successful general acceptance.
Evidence: `captures/alice-navigator-20261011/product-live/`. Hashes stayed pinned, service
settings stayed unchanged and no new crash appeared. No rollback APK is retained, by the
owner's request.

A separate shell check tested `ask_alice?text=` followed by the visible `alice_oknyx` button
in the opened panel. The initial late click found no node (only the host probe threw; no stock
app crashed). Immediate and 600 ms delayed clicks both returned `ACTION_CLICK accepted=true`
but did not restart MIC: the deep link's short recording stopped and none was active in the
+0.3/+1.5/+3.5 s snapshots. Evidence: `hidden-controls-fallback/`, `hidden-controls-fallback-02/`
and `hidden-controls-fallback-03/` beside the product run. This fallback is **not** in the
product. `com.yandex.alice.oknyx.g.e` dispatches clicks according to `OknyxState`; a clickable
node and an accepted action do not prove recognition. No coordinate tapping or route changes
were added to work around it.

The visible-control product check in `product-live-visible/` is **not accepted**. The owner's
initial “Alice listens” answer was corrected: that was a manual UI invocation. With an active
route, touching Navigator and pressing the physical voice key still did not invoke Alice;
the on-screen button did. The final flushed trace captured three presses at 16:29:06.216,
16:29:07.853 and 16:29:35.119: both halves consumed in 0 ms, followed by
`click accepted=false window=321` each time. Interception therefore works, but this trace does
not distinguish target absence, ambiguity, validation failure or action refusal. The owner
stopped the check; temporary log properties were restored. Do not infer
that the hidden node alone explains every product failure, or that the earlier probe success
proves this product build. Build 68 was incomplete at this point. Build 69 subsequently
passed the visible route-button and outside-Navigator cases below; build 70 then passed the
[hidden-controls case](#hidden-route-controls-without-a-manual-touch).

### Route microphone correction

The failure after revealing controls was a **wrong resource ID**, independent of the separate
hidden-controls limitation. The exact APK's `res/layout/navi_guidance_controls.xml` and
`res/layout-land/navi_guidance_controls.xml` declare `ControlVoiceSearch` with ID
`guidance_open_voice_search`. The free-map `navi_service_controller.xml` instead declares
`navi_service_open_voice_search`. Build 68 only searched the latter.

On 2026-10-11 the owner revealed the active-route controls without manually invoking Alice.
The bounded shell probe found `guidance_open_voice_search` in focused IVI window 336,
clicked it once and got `ACTION_CLICK accepted=true`. Audio session 345, Navigator UID 10133,
was recording MIC and `silenced:false` at +0.3/+1.5/+3.5 seconds. The owner confirmed that Alice
opened by itself and listened. Evidence: `captures/alice-navigator-20261011/guidance-fix/`,
`click.txt` and `audio-*.txt`. This isolates the ID mismatch; no coordinates, gestures,
private Navigator calls or deep links are needed for the visible route button.

Build **69** adds `NavigatorVoiceControls.findUnique`, searches both exact IDs and still
requires a unique visible, enabled, clickable node in the same focused Navigator window.
It does not select the state-dependent `alice_oknyx` panel button. `NavigatorVoiceWindow`
now logs missing/ambiguous targets, changed focus/target and the exact action's result.
The physical-key ownership and stock fallback are unchanged.

The regression test reproduced the miss with the old single-ID list (three failures), then
passed with both IDs. The full build passed **2,145 tests in 256 suites**. Build evidence:
`guidance-fix/red-original-id.log`, `build-69-final.log`, `tests-69.json`.
APK SHA-256: `c74d4265bfdfedeb27a502aa0438e90c1d9fd9edff072d1af70922fc1f38aa60`.
The first build 69 attempt (`guidance-fix/live-69/`) still found no node at the time of the
press (`target-count found=0 usable=0`). A read-only comparison then queried the exact route
ID and walked the tree, alternating `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`. When the owner
revealed the controls, both flag settings returned the visible, clickable node. These data
do not establish a shared-service flag/cache defect, nor do they prove that every earlier
failure was caused only by hidden controls. Evidence: `guidance-fix/compare-02.txt`.

Physical product acceptance then succeeded in `guidance-fix/live-69-compare/`:

- At **16:40:04.174**, physical `320` arrived. The product consumed DOWN/UP at .176 with
  1/0 ms callbacks and accepted `ACTION_CLICK` on `guidance_open_voice_search` at .213 in
  focused Navigator window 336. Audio session **361**, UID **10133**, was recording MIC
  with `silenced:false` at +0.3/+1.5/+3.5 seconds. The owner explicitly confirmed pressing
  the wheel and seeing Alice open. The comparison probe only read nodes; it sent no click.
- With Denza Apps focused (window 342), presses at **16:42:00.204** and **16:42:04.957**
  passed through (`consumed=false` on both halves, 0 ms callbacks). The firmware's
  `MediaKeyHandler` received `KEYCODE_AUTO_MEDIA_VOICE` UP each time. The owner confirmed
  that stock BYD opened; no Alice click was scheduled.
- Installed Denza Apps and Navigator hashes stayed pinned, accessibility service settings
  stayed unchanged, and the crash buffer gained no entries. The comparison disconnected
  and all three temporary log properties were restored to their original empty values.

The exact traces, audio snapshots and owner confirmations are kept in that capture directory.

A standalone repeat with the UiAutomation probe disconnected also succeeded
(`guidance-fix/live-69-product-only/`). At **16:43:03.051**, the product consumed physical
DOWN/UP in 0 ms and clicked the route microphone at .096. Session **377** was recording
unsilenced Navigator MIC at +0.3 and +1.5 seconds (it had ended by +3.5 seconds); the owner
confirmed Alice opened and listened. Hashes and service settings remained unchanged, no new
crash appeared, and temporary logging was restored. This confirms the product does not need
the tree-reading probe to invoke Alice.

When Navigator hides the controls, a direct accessibility lookup for the correct route ID
returns zero (`query-hidden.txt`). This is a different condition from the fixed ID mismatch;
it remained a limitation in build 69, and the wheel press was consumed without waking BYD. The firmware
`AccessibilityInteractionController` requires `isShown(target)` for node lookup/actions, so
retaining an old node is not a way to click a control whose view is hidden.

### Hidden route controls without a manual touch

> **Selection extended in build 72:** the focused-window gate described for build 70 below
> now accepts visible unfocused split panes. Losing focus alone no longer cancels an invocation;
> hiding or replacing the target window still does.

On 2026-10-11 the owner requested a wheel invocation without first touching the map. The
replacement for the build 69 limitation is a single touch of the existing **speed indicator**,
then an ordinary semantic click on the newly visible route microphone. It uses current node
bounds, not a fixed screen coordinate or a guessed point on the map.

Source chain in the pinned Navigator 29.8.1 decompile:

- `guidance/internal/view/binding/c` registers the speed view among interaction targets through
  `NaviGuidanceIntegrationController.E` and `NaviRideTouchLayout`.
- `NaviRideTouchLayout.onInterceptTouchEvent` emits an interaction on DOWN inside a registered
  shown view. `navi/ride/internal/l.f` activates interactive UI; `l.h` hides it after a 3 s debounce.
- `SpeedViewImpl` supplies the speed display and has no click command of its own. The probe
  touches `speedview_guidance`, not `speedlimitview_guidance` (the latter has a presenter click).

The shell probe `bash tools/yandex_alice_ui_probe.sh reveal-click` started with no route
microphone node, located the unique visible speed view in focused Navigator window 336 at
`Rect(2176,296–2352,472)`, and touched its center for 40 ms. The route microphone appeared and
accepted `ACTION_CLICK` **205 ms** after the touch began. Session **393** recorded unsilenced
Navigator MIC at +0.3/+1.5/+3.5 seconds. The owner confirmed Alice opened and listened without
touching the screen. No new crash appeared. Evidence: `captures/alice-navigator-20261011/hidden-controls-fix/`.

Build **70** implements this in the existing shared accessibility service with
`canPerformGestures=true`. This firmware's `AccessibilityServiceConnection.dispatchGesture`
checks capability 32 through `AccessibilitySecurityPolicy.canPerformGestures` and uses the
display's `MotionEventInjector`; no BYD permission or shell connection is used by the product.
`NavigatorVoiceWindow` requires the same focused Navigator application window on display 0,
no microphone node, a visible route touch container, and exactly one visible enabled speed
indicator. It refuses Alice/modal/dialog content and any higher window covering the touch
point, refreshes the target and window geometry, then dispatches one 40 ms gesture.

`AliceVoiceKeyController` keeps the invocation pending through gesture completion and checks
for the microphone every 50 ms for at most 1.5 s. It never repeats the reveal gesture. Focus
loss, detach, gesture cancellation, rejected dispatch or timeout cancels the invocation;
another wheel press during it is consumed without adding a gesture. Visible microphones
still use the immediate semantic click; outside Navigator the entire key stays stock.

The captured hidden-controls transition failed six new controller cases before the change.
After it, **2,152 tests in 256 suites passed**, including a focus-away-and-back cancellation
case. Build evidence: `red-controller.log`, `build-70.log`, `tests-70.json` in that directory.
APK SHA-256: `f5dc71ac28c8f9372d0ed112a5f4152e1fa62a9b8a23d2c514de2ce80e83414e`.
Product physical-key acceptance passed in `hidden-controls-fix/live-70/`:

- Before the press, the focused Navigator window was 336 and direct lookup returned zero route
  microphone nodes. The shell probe had disconnected; only the product handled the key.
- At **16:52:43.658**, physical `320` arrived. DOWN/UP were consumed at .660/.661 with 0 ms
  callbacks. The product logged no microphone at .667, dispatched the speed touch at .685,
  received gesture completion at .730, and clicked `guidance_open_voice_search` at .856 —
  **198 ms** from physical key arrival. MIC session **409**, Navigator UID **10133**, was
  active and unsilenced at +0.3/+1.5/+3.5 seconds. The owner confirmed Alice opened and
  listened **without any screen touch**.
- With Denza Apps focused (window 346), physical presses at **16:53:10.742** and
  **16:53:12.765** passed both halves through in 0 ms. `MediaKeyHandler` received the stock
  voice UP, no gesture/Alice invocation followed, and the owner confirmed stock BYD opened.
- The shared service was bound with capabilities **41** (content + key filtering + gestures).
  Installed hashes stayed pinned, enabled services stayed unchanged, the crash buffer gained
  no entries, and all temporary log properties were restored.

### Visible Navigator in split screen

The owner clarified the scope on 2026-10-11: if Navigator is on the main screen, the voice
key must call Alice even when focus belongs to the other split pane. The peer can be any app;
Denza Apps does not need a visible activity. A hidden/background task alone does not qualify.

Firmware evidence: `AccessibilityController.WindowsForAccessibilityObserver` in the pinned
`captures/split-firmware-20260923/jadx/services/` corpus builds its list from visible windows.
`windowMattersToAccessibility` admits unfocused touchable windows intersecting unaccounted
screen area; higher windows subtract their touchable region. The live split exposed Navigator
application window **355**, display 0, `focused=false`, bounds `(880,112)-(2536,1472)`, with
Denza Apps focused in window 346 on the left. A system divider may carry the title "Yandex Navi";
selection must use root package and application type, never that title.

The shell probe clicked `navi_service_open_voice_search` in this unfocused Navigator. Audio
session **465**, UID 10133, was active and unsilenced at +0.3/+1.5/+3.5 s; the owner confirmed
Alice opened and listened. No new crash occurred in that run. An earlier `reveal-visible`
attempt found a free map rather than route guidance and stopped before any touch: its uncaught
`IllegalStateException` affected only the shell probe process, not a BYD process. The host probe
now reports that guard failure without throwing. Evidence:
`captures/alice-navigator-20261011/visible-split-fix/`, especially `free-map-probe/`.

Build **72** applies this selection to both microphone clicks and hidden-route reveal gestures.
Package identities are prefetched off the key callback; current exposed area is rechecked on
DOWN and before each action. Focus changes between visible panes do not discard the cached
identity or cancel pending work. An absent/fully covered Navigator, a non-main display, unknown
package, non-application window or multiple eligible Navigator windows leaves the key stock.
The existing shared service supplies the feature while the Denza Apps activity is hidden.

The captured split failed three of six new window-policy cases under the old focus gate.
After the change, **2,158 tests in 257 suites passed** and the APK built successfully. Evidence:
`red-policy.log`, `build-72.log`, `tests-72.json`. Build 72 (`versionName=0.7.0`) SHA-256:
`07d7a1bb45d41540bac68e7faba8448b715fcdb71f3ba692afda8f5c514e6dc9`.
Build 72 failed physical-key acceptance with Music focused and Navigator visible: presses at
17:22:22 and 17:22:24 were consumed, but the semantic click was rejected as `window-hidden`.
The captured microphone bounds were `(888,820)-(1000,932)`; its center x=944 falls inside the
split divider's touch region x=780..956 even though the node is visible and semantically clickable.
Reusing the coordinate-gesture guard for `ACTION_CLICK` was incorrect. The fixture and trace are
in `visible-split-fix/live-72/`; no crash occurred and diagnostics were restored.

Build **73** restores semantic clicks based on exact visible/enabled/clickable node identity and
the still-visible Navigator window. Only the actual reveal gesture requires its touch point
to be uncovered. All **2,158 tests in 257 suites passed** (`build-73.log`, `tests-73.json`).
Build 73 (`versionName=0.7.0`) SHA-256:
`070bba8fc4d339eb4956742f4f77c7d5c5df3e9926e955f88bbd503ceb1c233e`.
Physical-key acceptance of the unfocused free map passed in `visible-split-fix/live-73/`:
Music window **335** was focused on the left, Navigator **383** visible/unfocused on the right,
and Denza Apps had no visible activity. At **17:24:43.332**, physical `320` arrived; DOWN/UP
were consumed in 0 ms, and the free-map microphone accepted its semantic click at **.397**
(65 ms after the key). MIC session **473** was active and unsilenced at +0.3/+1.5/+3.5 s;
the owner confirmed Alice opened and listened. No stock voice dispatch or new crash occurred.
APK identities and the logical enabled-service set were verified after the run; temporary
log properties were restored.

The combined hidden-route and negative cases also passed on build 73 in
`visible-split-fix/route-73/`:

- Before the press, Music 335 still had focus and Navigator 383 was visible/unfocused;
  the read-only probe found no microphone control and disconnected before acceptance.
- At **17:27:15.161**, physical `320` arrived. Both callbacks consumed it in 0 ms.
  The missing microphone was logged at .176; one speed-indicator gesture was dispatched
  at .234 and completed at .273. `guidance_open_voice_search` accepted `ACTION_CLICK`
  at **.452** (291 ms after the key). MIC session **545** was active and unsilenced at
  +0.3/+1.5/+3.5 s. The owner confirmed Alice opened and listened without touching the screen.
- HOME then showed the launcher and removed Navigator from the main-display window list.
  Physical presses at **17:27:45.972** and **17:27:47.489** were left unconsumed in 0–1 ms;
  `MediaKeyHandler` received the stock voice UP both times. No new Alice action followed;
  the owner confirmed BYD opened.
- The installed APK hashes and logical enabled-service set stayed unchanged, no crash was
  added, and all temporary log properties were restored. No shell probe or observer remains
  connected. This run proves these scenes on Navigator 29.8.1, not restart/sleep behavior or
  every later Navigator layout.

## Steering-wheel Play/Pause feasibility (2026-09-05)

> **Superseded 2026-09-05:** the "not live-tested" status below is out of date: physical key `386` interception and direct VK/Yandex resume were confirmed by the operator the same day, and the token memory was replaced by the package contract on 2026-09-11 — see [VK pause restored Yandex through transient audio focus (2026-09-05)](#vk-pause-restored-yandex-through-transient-audio-focus-2026-09-05) and [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11).

Status: **minimal in-app implementation; physical-key interception and resume
have not been live-tested**. The initial research pass only read the connected
car; it did not install an APK, inject keys, change roles, or start playback.

The connected IVI reports Android 13, fingerprint
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys`.
The local corpus matches the car by SHA-256:

| File under `/system/framework/` | SHA-256 |
| --- | --- |
| `dilink-services.jar` | `6e709f430c4b972dc1a869253717a999add80b74e484656d74716eabe1ac3f00` |
| `services.jar` | `23a58a4e3c98c50541785390f1234e8c4d7138b5dd170c4f5843bae15b93c019` |

### Why Pause can work while Play selects stock music

In the matching `dilink-services.jar` corpus,
`com.android.server.media.MediaKeyHandler.handleMediaPlayPauseKey` chooses the
user currently using the vehicle speakers, reads that user's current audio-focus
package, and dispatches to its active media controller. If no matching controller
exists, it tries `com.byd.mediacenter`. It does not remember the last paused app.
`dispatchMediaControlKey` also accepts a controller whose extras list the target
in `com.byd.media.session.EXTRA_CONTROL_PACKAGE_LIST`.

The vendor toggle is key **386**. `tramsformKeyEvent` converts it to **127
(Pause)** when the chosen controller reports state 3 (playing), otherwise **126
(Play)**. Vendor keys **334/335** become explicit Play/Pause. These are corpus
mappings, not a capture identifying this user's physical button.

> **Superseded 2026-09-18:** the Z9GT's physical play/pause is captured as `386`, and next/previous arrive as `307`/`308` followed by `87`/`88` — see [The press the preparation swallowed, and what the car taught on 2026-09-18](#the-press-the-preparation-swallowed-and-what-the-car-taught-on-2026-09-18).

`MediaSessionServiceExtImpl.dispatchMediaKeyEvent` uses the same current-focus
then stock-controller policy for generic media dispatch. Sending a global media
key again would therefore risk repeating the problem. The PersonBean
`MUSIC_SWITCH` default used by Shortcuts is absent from these routing methods.

The live `dumpsys media_session` snapshot showed `ru.yandex.music` active with
`state=2` (paused), position 544188 ms, and actions 3145725 (including Play).
MediaCenter was also active and paused. The platform's `Media button session`
line named an inactive SyncLink session, so that line alone cannot identify the
recipient selected by the vendor policy. No matching key trace remained in
`logcat` for `MediaKeyHandler` / `MediaSessionServiceExtImpl`. The focus-based
fallback explains the report, but the exact physical Pause -> focus loss ->
Play sequence is still a hypothesis, not a captured root cause.

### Candidate interception and direct command

In `services.jar`, `PhoneWindowManager.interceptKeyBeforeDispatching` calls
`mPwmExt.handleMediaKey(event)` for 386 and 334/335, then consumes the event.
`isKeyPassToUser` includes these codes. This is a plausible interception point
for an accessibility key filter upstream of final key dispatch. JADX's normal
mode failed to reconstruct the dispatch method; inspect the simple-mode output
at `reverse/speaker-lift/PhoneWindowManager-simple.java`, generated with:

```bash
jadx -r -m simple --single-class com.android.server.policy.PhoneWindowManager \
  --single-class-output reverse/speaker-lift/PhoneWindowManager-simple.java \
  captures/split-firmware-20260923/system/system/framework/services.jar
```

Android documents that
[`AccessibilityService.onKeyEvent`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onKeyEvent(android.view.KeyEvent))
can consume key events, with `canRequestFilterKeyEvents` and
`flagRequestFilterKeyEvents`. The product already declares both in
`simulcast_a11y_service.xml`. Before this change, `onKeyEvent` only handled the
navigation custom key. The live service was bound with capabilities 9, and its
notification listener was enabled.

For resume, keep the last actual playing controller's token and package, retain
it across pause, and call that controller's
[`TransportControls.play()`](https://developer.android.com/reference/android/media/session/MediaController.TransportControls#play()).
Pause uses `pause()` on the selected playing controller. This directly addresses
the session instead of invoking BYD's global routing. The existing
`SpeakerMediaSessionObserver` demonstrates active-session observation through
the enabled notification listener, but does not implement this memory or control.

> **Superseded 2026-09-11:** resume is keyed by package, not by token, and the package outlives the process — see [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11).

The initial research recommendation was an isolated probe observing key code/action/source/repeat and session
state without consuming events. On a stationary car, capture one physical
Pause/Play pair with Yandex initially playing. Only after the physical mapping
is known, arm consumption for that exact key: consume the complete down/up pair,
execute once per press, and address the remembered session. Success means Yandex
changes paused -> playing and advances position, while MediaCenter neither
starts playback nor opens its Activity. Failure includes a missing key callback
or stock execution despite consumption. A shell-injected key is not acceptance
of the physical steering button.

The recommended probe would stay outside the product, with one car owner, recorded installed APK
hashes, and a bounded armed window. Preserve the baseline accessibility services
on cleanup. Avoid simultaneous competing key filters. Handle destroyed sessions
and no remembered target by leaving the original key alone; do not guess a
package after reboot. Preserve the stock call/mute and special vehicle-mode
guards before any product promotion. A successful normal-UID key-consumption
test and direct Yandex resume are the remaining feasibility gates.

> **Superseded 2026-09-11:** both gates passed on the car on 2026-09-05, and "do not guess a package after reboot" is reversed: the persisted last-played package is resumed or reconnected (since 2026-10-08 only resumed: a package with no live session is left to the firmware, see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08)) — see [VK pause restored Yandex through transient audio focus (2026-09-05)](#vk-pause-restored-yandex-through-transient-audio-focus-2026-09-05) and [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11).

### Minimal built-in slice requested by the owner

> **Superseded 2026-09-11:** the one-token, process-lifetime memory and the no-relaunch rule of this slice are replaced by the package contract, and the physical check it still owed passed on 2026-09-05 — see [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11) and [VK pause restored Yandex through transient audio focus (2026-09-05)](#vk-pause-restored-yandex-through-transient-audio-focus-2026-09-05).

The owner explicitly requested implementation directly in Denza Apps, with no
settings or separate probe. `feature.media.MediaResumeController` attaches to
the existing `SimulcastAccessibilityService` lifetime. Startup ensures access
to the existing notification listener independently of HUD and speaker settings.
No additional service, manifest permission, preference, or UI is introduced.

Only Play/Pause/Toggle (126/127/85 and vendor toggle 386) are intercepted. Other
buttons, including passenger-specific 334/335, retain their existing route.
Commands address a real session through `MediaController`, never through global
media-key injection or a placeholder media session. The initial DOWN is consumed
only when the policy accepts the press; otherwise both DOWN and UP pass through.
After a consumed DOWN, its repeats and UP remain consumed, even if the target
disappears meanwhile, so firmware cannot interpret half of an already-handled
press as a second action. Binder transport submission cannot prove that the
remote player actually resumed; physical acceptance must also observe playback.

**Superseded on 2026-09-11.** The first slice remembered exactly one session
*token*, only for this process lifetime, and only while that token stayed in
`getActiveSessions`; it explicitly refused any application launch or
resurrection and left every other Play to the firmware. All three limits failed
in ordinary use, and on this firmware a refused Play is a Play that opens the
stock media center - which is what a second car (Denza N9, same DiLink build,
0.6.1) reported. The rule in force is
[Resume contract: the package, not the token](#resume-contract-the-package-not-the-token-2026-09-11)
below; where the two disagree, that section wins.

> **Superseded 2026-10-08:** the no-resurrection limit is back, by the owner's decision: a package with no live session, such as a player the car unloaded in its sleep, is left to the firmware — see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).

`MediaButtonEnvironment` leaves calls and muted audio with the firmware using
the public audio mode/stream mute state and available BYD mute/call accessors.
The firmware's separate streaming rear-view-mirror adjustment guard for key 386
is not reproduced in this minimal slice: that vendor-state access and the
physical-key test remain unverified. Do not claim full parity with every stock
vehicle mode from the unit tests.

Local validation: `:denza-apps:testDebugUnitTest`, `:denza-apps:assembleDebug`,
and `:denza-apps:lintDebug` passed; 1343 unit tests, including 13 media tests,
with no failures or skips. APK SHA-256:
`4b46a61a621b9f97ef7c095a60db42a2686900c7d3ef67f374a4192807987584`.
The APK was retained under
`captures/media-resume-4b46a61a621b/denza-apps.apk`. This is a build of the shared
working tree, including the trip changes in `f84f089`.

Installed on the connected IVI on 2026-09-05 using `adb install -r`; the
installed `base.apk` SHA-256 matches the value above. The launcher opened,
the new app process was running, and the existing accessibility service was
bound with capabilities 9. The paused Yandex and MediaCenter sessions each had
six controllers after the update (five in the research snapshot), consistent
with the added observer. No backup of the old installed APK was taken, as
requested by the owner. Physical Play/Pause interception and actual Yandex
resume still require an operator check; successful installation is not that
acceptance result.

### VK pause restored Yandex through transient audio focus (2026-09-05)

> **Superseded 2026-10-09:** the helper is no longer in the product at all: `MediaFocusPauseBridge`, `MediaFocusPauseProxyMain` and `MediaPausePreparation` moved to [`research/media-focus/`](../research/media-focus/README.md) by the owner's decision, and the deferred pause, its reasons and the packed `media-focus-pause-proxy.jar` left the APK with them.

> **Superseded 2026-09-18:** the diagnosis stands, but the focus-stack helper this section introduces is switched off (`MediaKeyExperiment.FOCUS_SURGERY = false`; `SimulcastAccessibilityService.java` no longer builds `MediaFocusPauseBridge`). A pause with paused predecessors is an ordinary pause, and a player paused under a video may resume when the video pauses — see "A pause is a pause" in [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11).

The operator reproduced: Yandex playing -> VK Video playing (Yandex pauses) ->
wheel pause -> VK pauses but Yandex starts. This was **not a wrong selected
session**. Narrow logging had to be enabled because global `log.tag=M` suppressed
our info messages. The physical-key trace showed:

```text
10:23:47.974 DenzaMediaResume: direct media command package=com.vk.vkvideo command=pause
10:23:47.974 DenzaMediaResume: media key=386 received allow=true consumed=true
10:23:47.987 audio: VK player paused
10:23:48.482 audio: VK abandonAudioFocus
10:23:48.488 audio: Yandex player started
```

The focus history records VK requesting `req=2` (transient gain), while Yandex
requests `req=1`. No second Play command from Denza appeared in this capture.
The return of focus after VK's delayed release therefore explains the
automatically resumed music. Raw traces are under
`captures/media-resume-vk-diagnosis/`.

An initial attempted fix sent explicit `TransportControls.pause()` to paused
predecessors before pausing VK. The operator and a second physical-key trace
rejected it: at 10:30:42 cancellation preceded VK pause, but VK abandoned focus
at 10:30:42.788 and Yandex started at 10:30:42.795. Direct shell Pause and Stop
also failed to cancel that suspended player's automatic focus-gain resume. The
installed APK for that rejected attempt was
`d0f823f84b7888e6928f3a392e66d22f9f05e4d6df5391af3418d9e09d909dfa`.

The replacement is package-independent. The observer remembers real tokens
observed playing. Before pausing a playing target with paused predecessors,
`MediaFocusPauseBridge` asks a bounded shell-UID helper to inspect audio focus.
It removes only matching package/UID/client entries with transient focus loss
(`-2`) and media audio attributes, below the still-playing target at the top of
the focus stack. A fresh session and focus read precedes each removal. Conflicting
sessions, another user, or a changed focus owner are not eligible. No package
names, fake focus request, player Stop command, or corrective playback loop are
part of the product logic.

The helper uses `IAudioService.getFocusStack()` and
`unregisterAudioFocusClient(clientId)`. Corpus evidence is the vehicle's
`reverse/speaker-lift/AudioService.java` and `MediaFocusControl.java`: stack reads
require MODIFY_AUDIO_ROUTING (available to shell); unregister removes that entry
without notifying the current top focus owner. Existing passive LocalADB runs a
small packaged shell asset; it does not trigger ADB authorization or add an app
permission. The separate app_process bootstrap must initialize
`MediaFrameworkPlatformInitializer`'s MediaServiceManager before obtaining
MediaSessionManager; the first incomplete bootstrap had exited 137 and supplied
no behavioral evidence.

A corrected shell experiment at 10:42:27 removed the suspended predecessor's
focus client before VK pause. Both media sessions remained PAUSED after VK's
focus release. The active VK focus entry was left intact until VK released it.
This establishes the mechanism on this IVI, separately from product acceptance.

Preparation runs off the accessibility callback thread. An accepted DOWN and
its UP/repeats are consumed immediately; additional media presses during the
bounded operation are ignored. After successful preparation, the controller
refreshes sessions and sends ordinary Pause only to the exact original live,
still-current PLAYING token. A destroyed/replaced target is never resumed or
replaced with another app. Before a press is accepted, an absent target or an
unavailable helper leaves the complete press to stock handling. If preparation
fails after acceptance, no guessed fallback command is injected.

Local validation of the replacement: 1351 unit tests, including 20 media tests,
passed without failures/skips; assembleDebug and lintDebug passed. Tests include
deferred pause selection, refused preparation, token replacement, a newer
playing target, already-paused completion, and closed-session stock fallback.
Physical acceptance on the integrated APK
`5d85e806d226140bfe47ccee5a6e6dff99e354c3fb656613c14b26469033a4f9`
was confirmed by the operator: VK and Yandex both resumed the last session
correctly. The physical key-386 trace at 10:53:00 removed one suspended focus
client then paused VK; 10:53:05 resumed VK. Repeated VK pause/play and subsequent
Yandex pause/play addressed their respective real sessions. The earlier
10:52:20 press with no observed target passed both key edges to stock handling.
Raw evidence: `captures/media-resume-vk-diagnosis/universal-focus-live.log`.

Final review broadened the same USAGE_MEDIA rule to include speech content
(podcasts/audiobooks), and changed ambiguous predecessor state/identity from
silent skip to preparation failure. These final guards passed the same build,
unit-test and lint tasks. The final combined build (versionCode 45,
0.6.1-alpha) has APK SHA-256
`fc46f12a2dc1de65a799245031d54c574cda7aa6039d2e1503e28c4915f4ebfb`.
Installed with `adb install -r`; installed base.apk SHA-256 matched, the launcher
opened, and the accessibility service remained bound with capabilities 9. No old
APK backup was taken. The physical confirmation above precedes those guard refinements; it is not a
separate physical acceptance of podcasts or conflicting sessions.

### Resume contract: the package, not the token (2026-09-11)

Normative for `feature/media`. The defect it answers: on the Denza N9 (same
DiLink build, app release 0.6.1) the wheel pauses correctly and the next Play
opens the stock player. That is not an N9 quirk. `MediaKeyHandler`'s Play
policy is "the audio-focus owner's controller, else `com.byd.mediacenter`", so
**every** press this policy refuses on Play starts the stock local player. Only
two refusals are legitimate, and in both the stock answer is the right one: no
record at all, and - since 2026-10-08, by the owner's decision - no live session
of the last-played package, which is what a car that slept leaves behind
([No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08)).

**Identity is the package.** A session token dies with the player's process,
with our own accessibility service (an APK update or a quickboot is enough), and
sometimes because the player simply rebuilt its session. The driver did not mean
a token. `AndroidTarget` is still per token - a `MediaController` can be built
from nothing else - but every policy decision is keyed by package. Where one
package owns several live sessions, the order is: the one the platform still
routes keys to, then the one most recently seen playing, then the newest.

**The last-played package is persisted.** Preferences `media_resume`, keys
`last_played_package` and `last_played_at`, written on every observation of
PLAYING - reconcile snapshot or callback - for any package, the vehicle's own
included. The timestamp is written and deliberately never read: after a night's
parking the driver expects the same thing as after a red light. It is stored so
that an expiry stays one comparison away in `MediaResumeCore.lastPlayedPackage`
if a car ever argues for one. `MediaResumeCoreTest` pins the decision with
"an old record is still honoured because there is no time limit".

> **Superseded 2026-10-09:** only `last_played_package` is written now. The timestamp nobody read is gone, so a PLAYING of the package already recorded no longer reaches the preferences file; an expiry would have to store a time again. A car updated from an older build keeps the stale `last_played_at` key, unread.

> **Superseded 2026-10-08:** the record still has no expiry, but after a night's parking it resolves nothing: the sleep unloads the player, and a package with no live session is left to the firmware. The record now only chooses among live sessions, for example after our own service restarted while the player kept running — see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).

**A session that leaves the active list is dormant, not gone.**
`getActiveSessions` is the platform's key-routing list, not a list of sessions
that exist; a player that deactivates its session when it pauses disappears from
it while its session is alive. This vehicle's own corpus settles what that
costs. In `reverse/speaker-lift/MediaSessionRecord-simple.java`, a controller's
`play()` reaches `SessionCb.play(pkg, pid, uid)`, which calls
`tempAllowlistTargetPkgIfPossible(...)` - so the app may still start its
foreground service - and then `mCb.onPlay(...)`. `mIsActive` is read in exactly
two places, `isActive()` and `dump()`. An inactive but undestroyed session is
therefore fully commandable. The controller keeps the target, callbacks
registered, until `onSessionDestroyed`; a registration the platform refuses is
not tracked at all.

> **Superseded 2026-10-09:** the callback is no longer the controller's own. The process's one `MediaSessionHub` (`platform/media`) holds the listener and one callback per session for the key, the speaker covers and the strip, keeps a session that left the active list until it is destroyed, and reports a refused registration once. The key keeps its targets from the hub's snapshots in `MediaResumeSessions` under the same rule - adopted from the active list, dropped only when the hub drops a destroyed session - and a press still re-reads the list synchronously, without the hub telling anyone else.

**Play resolves in one order**, in `MediaResumeCore.perform`:

1. something is PLAYING now, any package - the wheel key is a toggle, so this is
   the pause path, unchanged: remembered-playing preference, paused predecessors
   that once played, deferred pause through `MediaFocusPauseBridge`;

   > **Superseded 2026-10-09:** the deferred pause is gone from the code, not only switched off: `MediaResumeCore.perform` has no `deferPause`, and the bridge lives in [`research/media-focus/`](../research/media-focus/README.md).

   > **Superseded 2026-09-18:** the deferred pause no longer runs: `SimulcastAccessibilityService.java` builds no `MediaFocusPauseBridge` while `MediaKeyExperiment.FOCUS_SURGERY` is false, so every pause is dispatched directly — see "A pause is a pause" below in [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11).

2. a live target, active or dormant, for the last-played package - `play()` on
   it. There is no `ACTION_PLAY` gate: the platform does not enforce the
   advertised bits and some players only advertise `ACTION_PLAY_PAUSE`. A Play
   whose target already reports PLAYING dispatches nothing (`already-playing`);
3. no live target for that package - the press is refused
   (`stock-no-live-session`, naming the package) and goes to the firmware. A
   package with no session left has been unloaded - by the car's sleep, a
   quickboot, every time it parks, or by the driver - so the car is in its
   stock state, and the owner decided on 2026-10-08 that the feature does not
   intervene there; see
   [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).
   Until that date this step reconnected, below;
4. no record at all, a fresh install or cleared data - the press goes to stock.

Pause keeps its `ACTION_PAUSE` gate. That half was proven on the car, and a
press it refuses reaches the firmware's pause handling, which is harmless. An
explicit Pause key with nothing playing also stays with stock, as before.

**A pause is a pause (2026-09-18, owner's decision).** The shell helper that
removed suspended predecessors' audio-focus entries before a pause is switched
off (`MediaKeyExperiment.FOCUS_SURGERY`), and a pause with paused predecessors is
now dispatched like any other. The evidence is in the live section below: on
the car the helper threw on six presses out of seven, cost every pause about
650 ms, and the one time it succeeded it emptied the focus stack, so the next
wheel press after the pause went to the stock player. The behaviour given up
with it is the one it was built for: a player paused under a video may resume
when that video is paused, which is the platform's own rule. The bridge, the
helper and its proxy stay in the tree until the second step - taking the wheel's
next and previous keys into the same policy - decides what, if anything, of
theirs is still wanted. The rule the owner chose fits one sentence: the key
controls what is audible, and play brings back what was audible last.

> **Superseded 2026-10-09:** they did not wait for the second step. By the owner's decision the bridge, the helper, `MediaPausePreparation` and the `FOCUS_SURGERY` flag moved to [`research/media-focus/`](../research/media-focus/README.md), whose README says what it would take to bring them back; the product keeps only `MediaKeyExperiment.INTERCEPT_KEYS`.

> **Superseded 2026-10-08:** the reconnect described from here to the end of "Why the second reconnect path is not defensive" is deleted (`MediaResumeReconnect.kt`, `MediaResumeCore.adopt`, the `reconnect-*`, `no-browser-service`, `media-button-sent`, `no-media-button-receiver` and `resume-in-flight` reasons); step 3 leaves the press to the firmware. On this firmware the self-start gate refused both halves on every press. The text stays as the record of what was tried — see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).

**Reconnect (step 3)** uses the platform's own client contract, twice over, and
names no package in code:

- `MediaResumeReconnect` resolves the package's exported, enabled service for
  intent action `android.media.browse.MediaBrowserService`, connects
  `android.media.browse.MediaBrowser` on the main looper, takes
  `getSessionToken()`, builds a `MediaController`, hands it to the policy as a
  live target and sends `play()`. That is the good path: the next press is an
  ordinary direct command. `MediaBrowser` is a client API - no permission, no
  component, nothing vendor-specific. Package visibility already comes from the
  app's existing `QUERY_ALL_PACKAGES`.
- If the package declares no such service, or refuses the connection, the
  fallback is an explicit `ACTION_MEDIA_BUTTON` with `KEYCODE_MEDIA_PLAY`, down
  and up, `setComponent` to the **first** exported enabled receiver of that
  package with a `MEDIA_BUTTON` filter. One receiver only: a package normally
  declares one, and two would risk two Play commands for one press. This is a
  directed command to a named component, not a key given back to the firmware's
  routing, so nothing of ours can reach the stock player.

Both are bounded at 3 s by a handler timeout and consume the DOWN the moment
they start, on the deferred pause's rule - one press is never split between us
and the firmware. On failure or timeout the reason is logged and nothing else
happens: no guessed fallback, no stock injection. A second media press while a
reconnect or a deferred pause is in flight is consumed and logged
(`resume-in-flight`, `pause-in-flight`) instead of being silently swallowed.

**Why the second reconnect path is not defensive.** Static reading of Yandex
Music 2026.07.2 (`reverse/media-resume-yandex/`, `versionCode` 24026371) settles
it. `ru.yandex.music.common.media.mediabrowser.MusicBrowserService` is exported
with the browser filter and carries no `android:permission`, but its
`onGetRoot(int uid, String clientPackageName)` runs a Google UAMP-style
`PackageValidator` over `res/xml/android_auto_allowed_callers`. A caller is
accepted only when it is the app's own uid, uid 1000, signed by the platform,
listed in that XML (`com.google.android.projection.gearhead`,
`com.google.android.autosimulator`, `com.google.android.carassistant`,
`com.google.android.googlequicksearchbox`, `com.google.android.wearable.app`,
`com.yandex.music.sdk.demo`, plus Google signer fingerprints - no
`com.android.bluetooth`, no `com.android.car`, no `com.byd*`), or holds
`MEDIA_CONTENT_CONTROL` or `BIND_NOTIFICATION_LISTENER_SERVICE`. Everyone else
gets `reason="unknown caller"` and a **`null` BrowserRoot**, which is a refused
connection. Denza Apps holds none of those: its notification listener declares
`BIND_NOTIFICATION_LISTENER_SERVICE` as the component's own binding guard, which
is not the same as requesting the permission, and no new permission was added
for this. The method also takes no `rootHints` bundle, so there is no
`EXTRA_RECENT`/`EXTRA_SUGGESTED` resumption root to ask for. The one decompiled
detail worth keeping: `setSessionToken` is not called in `onCreate` - it is
delivered asynchronously once the player stack produces it - so even an accepted
connection can arrive with no token yet, which the code treats as a failure and
falls through to the media-button path.

Two components of that APK declare a `MEDIA_BUTTON` filter:
`ru.yandex.music.common.service.player.MediaSessionService` (not exported) and
`ru.yandex.music.common.service.player.DebugMediaButtonReceiver` (**exported**,
no permission). Despite its name that receiver is not debug-gated: it is
androidx's `MediaButtonReceiver` and forwards `KEYCODE_MEDIA_PLAY` with
`ACTION_DOWN`, repeat 0, to the real player service through
`startForegroundService`. So for Yandex the expected live path is
browser-refused then media-button-sent. Caveat that only the car can settle: a
background caller can make that `startForegroundService` throw
`ForegroundServiceStartNotAllowedException` inside Yandex's own process, where
it is caught and logged - the press would then be silently lost, and the log
would show `media-button-sent` for a Play that never happened. The above is
static reading of a decompiled APK, not a live observation.

> **Superseded 2026-09-18:** on the Z9GT (build 47) neither reconnect path reached Yandex: BYD's self-start gate refused the `MediaBrowser` bind and both `MEDIA_BUTTON` broadcasts, the controller still logged `media-button-sent`, and the foreground-service caveat is not the cause. `MediaResumeReconnect.kt` still takes both paths unchanged — see [The firmware's self-start gate blocks both reconnect paths (2026-09-18)](#the-firmwares-self-start-gate-blocks-both-reconnect-paths-2026-09-18). Deleted on 2026-10-08 — see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).

**Where the decisions come out.** Every accept and refusal leaves through
`MediaResumeController.decide` as one `Log.i` line on tag `DenzaMediaResume`:
`media command accepted|skipped key=<code> reason=<reason> package=<pkg>`. The
reason vocabulary lives in `MediaResumeReason` and nothing outside it invents a
reason string: `play`, `play-transport`, `already-playing`, `pause`,
`pause-deferred`, `pause-in-flight`, `pause-already-complete`,
`pause-preparation`, `pause-transport`, `pause-unsupported`, `session-access`,
`session-access-after-preparation`, `stale-target-after-preparation`,
`no-target`, `stock-no-history`, `stock-no-live-session`. The reconnect's
reasons (`resume-in-flight`, `reconnect-started`, `reconnect-played`,
`reconnect-failed`, `reconnect-timeout`, `no-browser-service`,
`media-button-sent`, `no-media-button-receiver`) left with it on 2026-10-08.

> **Superseded 2026-10-09:** `pause-deferred`, `pause-in-flight`, `pause-already-complete`, `pause-preparation`, `session-access-after-preparation` and `stale-target-after-preparation` left with the deferred pause, which moved to [`research/media-focus/`](../research/media-focus/README.md). The vocabulary is `play`, `play-transport`, `already-playing`, `pause`, `pause-transport`, `pause-unsupported`, `session-access`, `no-target`, `stock-no-history`, `stock-no-live-session`.

Local validation: `:denza-apps:testDebugUnitTest`, `:denza-apps:assembleDebug`
and `:denza-apps:lintDebug` passed; 1401 unit tests, 35 of them media tests, no
failures or skips. Eight deliberate mutations of the policy - wrong resolution
order, dormant targets dropped, an hour-long record expiry, the wrong session of
a package preferred, reconnect on the wrong package, stock chosen despite a
record, Play re-sent to a playing session, and the record never written - were
each caught by the suite. No APK was built for the car in this pass.

#### Live acceptance plan (Z9GT, owner)

> **Superseded 2026-09-18:** "nothing below has been run" is out of date. On the Z9GT step 3 failed (the self-start gate dropped both reconnect paths, build 47); step 1 held once build 51 stopped dropping pauses whose preparation failed; step 4's focus helper is switched off; steps 2 and 5 have no recorded run — see [The firmware's self-start gate blocks both reconnect paths (2026-09-18)](#the-firmwares-self-start-gate-blocks-both-reconnect-paths-2026-09-18) and [The press the preparation swallowed, and what the car taught on 2026-09-18](#the-press-the-preparation-swallowed-and-what-the-car-taught-on-2026-09-18).

Nothing below has been run on a car. Narrow logging first: global `log.tag=M`
suppresses these lines, so raise `DenzaMediaResume` before pressing anything,
and read `media command …` beside `media key=… received`.

1. **Yandex pause and play from the wheel, session alive.** Yandex playing,
   press: expect `pause` (or `pause-deferred`), then press again: expect `play`
   with `package=ru.yandex.music` and audible resume. This is the path that
   already worked; it must not regress.
2. **Our service restarted.** Force-stop Denza Apps with Yandex paused, reopen
   the app so the accessibility service binds again, then press Play. Expect
   `play` addressed at `ru.yandex.music` - the old build could only answer
   `no-target` here, and the car would open the stock player.

   > **Superseded 2026-10-08:** a new process knows only the sessions in the active list; a Yandex session that left it before the restart is not one of them, and that press reads `stock-no-live-session` and goes to the firmware.
3. **The player's process gone.** Force-stop Yandex (or reboot the head unit)
   and press Play. Expect `no-browser-service` or `reconnect-failed` followed by
   `media-button-sent`, and Yandex actually starting. If the log says
   `media-button-sent` and nothing plays, the foreground-service restriction
   above is the suspect and the finding needs a live correction.

   > **Superseded 2026-10-08:** with the reconnect deleted, expect `✗ ru.yandex.music stock-no-live-session` in «Последние нажатия» and the firmware answering the press, which by its Play fallback is the stock media center when no third-party player holds audio focus; Yandex does not start. The case that matters is the first press after the car slept — see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).
4. **The VK handover of 2026-09-05 still holds.** Yandex playing, start VK
   Video (Yandex pauses), press the wheel: VK must pause and Yandex must **not**
   start. The focus-helper path is unchanged, but dormant sessions are now
   eligible as paused predecessors, so this is the case that proves the wider
   candidate set costs nothing.
5. **Stock or Bluetooth as the last source.** Play something in the stock media
   center or over a Bluetooth sink, pause it from the wheel, then press Play.
   Expect that package resumed directly - and with a fresh install, or after
   clearing app data, expect `stock-no-history` and the firmware's own player,
   which is correct.

A shell-injected key is not acceptance. Watch playback, not the log line: a
binder command that was submitted is not a player that resumed.

### The firmware's self-start gate blocks both reconnect paths (2026-09-18)

Live on the Z9GT, build 47. The owner reported the wheel key doing nothing after
the car woke up. The trace below is the whole of it, and it is not an N9 quirk:

```text
18:19:04 am_kill  dev.denza.apps  stop ... due to from pid 5963   (com.byd.recents)
18:19:49 am_kill  ru.yandex.music stop ... due to quickboot 0     (35 packages)
18:28:47 am_proc_start dev.denza.apps  next-top-activity DenzaLauncherActivity
18:28:52 ActivityManager bindServiceLocked 10147 process dev.denza.apps want to bind 10132
18:28:52 skip            bindServiceLocked 10147 want to bind 10132 package ru.yandex.music ignored !!!
18:28:52 BroadcastQueue  Self start permission detection: reciever for uid 10132 ... broadcast=android.intent.action.MEDIA_BUTTON
18:28:52 BroadcastQueue  skip reciever for uid 10132 name = ru.yandex.music ignored !!!
18:29:01 am_proc_start ru.yandex.music next-top-activity MainScreenActivity   (by hand)
```

Three presses at 18:28:52, :54 and :56 each produced one refused bind and two
refused broadcasts. Nothing reached Yandex, the press was consumed, and the
controller logged `media-button-sent` for a Play that never happened. The
`ForegroundServiceStartNotAllowedException` suspected on 2026-09-11 is not the
cause; the press never gets that far.

**The gate.** `ActivityManagerService.isEnableFeature()` arms one BYD check in
three places of `services.jar`: `ActiveServices.bindServiceLocked`,
`ActiveServices.startServiceLocked` and `BroadcastQueue` receiver dispatch. Each
refuses a third-party caller reaching a third-party target unless one of these
holds: `isCallerAppGranted(callerPackage)`, `isTargetAppEnabledStartedBy3rd(target)`,
`isContainedByWhitelist(targetPackage)`, or (services only) the target service is
guarded by `BIND_ACCESSIBILITY_SERVICE`. The check reads lists, not process
state, so the `MediaBrowser` half of the reconnect could never have worked on
this firmware, with the player alive or dead. Activity starts carry no such gate.
`MediaController` commands are unaffected, which is why pause and a live-session
play keep working: those go to `system_server`, not to the player's process.

**Where the lists live.** System service `byd_datacached`
(`android.os.appstartup.IAppStartupDataCachedManager`), guarded by
`android.permission.ACCESS_APPSTARTUPDATA`, `prot=signature`, held by
`com.byd.appstartmanagement`. Neither shell nor an ordinary app can read or
write it: a shell `service call` answers `Neither user 2000 nor current process
has android.permission.ACCESS_APPSTARTUPDATA`. There is no local-ADB path to
this, unlike the notification-listener and appops grants.

**What the driver can change.** `com.byd.appstartmanagement/.frame.AppStartManagement`,
exported, reachable with action `android.intent.action.BYD_APPSTARTMANAGEMENT`.
It lists every non-system package that is not whitelisted, and its per-app switch
writes `setAppStartupData(String.valueOf(applicationInfo.uid), 1|0)`, reading back
`checked = getAppStartupData(uid) == 1`. The gate allows when
`isTargetAppEnabledStartedBy3rd` is true, so a switch that is on should be the
package that may be started by other apps. Note that
[adb-authorization-recovery.md](adb-authorization-recovery.md) records the
opposite polarity for that page ("a checked switch means blocked"); the decompiled
manager and the gate say a checked switch is the permissive state. One look at the
live page settles it, and the loser of that comparison must be corrected.

> **Superseded 2026-09-23:** this doc lost the comparison. A checked switch is the deny bit itself (`getAppStartupData(uid) == 1`, the value the gate refuses), a new install is written `1`, and every APK update re-blocks the app; [adb-authorization-recovery.md](adb-authorization-recovery.md) is right — see split-screen-findings.md, "The self-start switch, and a registrar that never registered (live 2026-09-23)".

Consequences for `feature/media`, none of them applied yet: a bind refused by the
gate returns false synchronously and must be recorded as its own refusal rather
than becoming an accepted `media-button-sent`; the broadcast refusal is invisible
to the caller and cannot be detected at all; and the only resurrection path that
the gate does not cover is starting the player's own activity, for which Denza
Apps is already exempt from background-activity-launch limits through
`SYSTEM_ALERT_WINDOW`, at the price of the player's window appearing.

> **Superseded 2026-10-08:** none of the three will be applied. The reconnect path is deleted, so there is no refused bind or dropped broadcast left to report, and starting the player's activity is rejected by the owner's decision; a press with no live session goes to the firmware — see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).

### No resurrection after sleep (2026-10-08)

**The decision.** A player with no session left has been unloaded, and the
everyday cause is the car itself: every sleep is a quickboot that force-stops
third-party packages - `ru.yandex.music` among the 35 in the trace above, and
Denza Apps too
([split-screen-findings.md](split-screen-findings.md#every-sleep-of-the-car-force-stops-the-product-live-2026-09-23)).
The car is then in its stock state, and by the owner's decision of 2026-10-08
the wheel's Play/Pause is the firmware's until a player runs again. The feature
commands sessions that exist; it does not start a player's process, browser
service, media-button receiver or activity.

**What the code does.** `MediaResumeCore.perform`, step 3: no live or dormant
session of the last-played package returns `accepted=false`, reason
`stock-no-live-session`, with that package named. `MediaResumeKeyInterceptor`
then owns neither half of the press, so `MediaResumeController.onKeyEvent`
returns false for the DOWN and the UP, the service's dispatch offers the key to its
next rider, `SteeringWheelKeyRider` (whose interceptor takes only `321`), and
returns false, and the key continues to the firmware's `MediaKeyHandler` - the
same route a `stock-no-history` press has always taken. The support report
records it as `HH:mm:ss 386 ✗ ru.yandex.music stock-no-live-session`; `✗` on an
entry with a key code means the press was not consumed. The `✓ media-button-sent`
of 2026-09-18, for a press that reached nothing, can no longer be written.

Deleted with it: `MediaResumeReconnect.kt` (the `MediaBrowser` bind and the
directed `ACTION_MEDIA_BUTTON`), `MediaResumeCore.adopt`, the `reconnect`
parameter of `perform`, the controller's reconnect-in-flight branch and the
reasons `resume-in-flight`, `reconnect-started`, `reconnect-played`,
`reconnect-failed`, `reconnect-timeout`, `no-browser-service`,
`media-button-sent` and `no-media-button-receiver`. Unchanged: Pause, Play on a
live or dormant session, and the last-played record, which still has no expiry
and now only chooses among live sessions.

**Rejected.** Starting the player's own activity (`getLaunchIntentForPackage`)
is the one resurrection the self-start gate does not cover, and Denza Apps may
start activities from the background through `SYSTEM_ALERT_WINDOW`. It was
weighed and rejected as a product decision: it puts the player's window on
screen on a car that has returned to its stock state.

**What the car still has to show.** What the firmware does with the press:
by its Play fallback, the audio-focus owner's controller, else
`com.byd.mediacenter`. Android drops a focus request when its owner's process
dies, so after a sleep the expected answer is the stock media center; that is
expectation, not a run on this car.
Acceptance step 3 above, rewritten, settles it. Once the driver opens the player
by hand its new session belongs to the last-played package, and the next press
resumes it directly (`MediaResumeCoreTest`, "a player opened again by hand is
resumed by package").

### The press the preparation swallowed, and what the car taught on 2026-09-18

Live on the Z9GT, builds 48 to 51, read from the support report because this
firmware's logcat shows nothing: a line written with `log -t DenzaMediaResume`
never appears at all, even with the tag's own property set.

**The defect.** A press is consumed the moment `MediaFocusPauseBridge` accepts a
preparation, so a preparation that then failed dropped the press on the floor.
The ring caught it three times in a row:

```text
19:49:41 386 ✓ ru.yandex.music pause-deferred
19:49:42     ✗ focus unchanged com.byd.mediacenter,com.vk.vkvideo
19:49:42     ✗ ru.yandex.music pause-preparation
```

Play went out normally, because Play never takes that path; pause and only pause
did nothing at all, in split and out of it. Build 51 dispatches the pause
whichever way the preparation went. The worst that costs is the suspended
predecessor resuming itself, which is the fault the path was built to avoid and
much the smaller of the two. Verified on the car: the pause landed, and neither
`com.byd.mediacenter` nor `com.vk.vkvideo` resumed.

**Open: why the helper cannot finish here.** It answered `unchanged` - no
`DENZA_MEDIA_FOCUS_READY` line, so it threw - with those same two predecessors
each time, while the focus stack held `com.byd.mediacenter` under
`ru.yandex.music` and Yandex played through the car. Its guards are strict
enough that several of them could be the one: every session of a package must
agree on the state, the current target must be the top of the focus stack, and a
predecessor must carry a transient loss. Not worth a live experiment until it
costs something, now that a failed preparation no longer eats the press.

> **Superseded 2026-09-18:** moot: the helper was switched off the same day (`MediaKeyExperiment.FOCUS_SURGERY = false`), so no preparation runs — see "A pause is a pause" in [Resume contract: the package, not the token (2026-09-11)](#resume-contract-the-package-not-the-token-2026-09-11).

**Remote playback invalidates any media-key reading.** Yandex Music casting to a
home speaker holds no audio focus in the car and owns no audio track there, while
its session still counts the track forward. That reads exactly like a broken car:
an empty focus stack, a focus request surviving 39 ms, silence with a running
position, and the firmware sending the wheel's keys to the stock player because
the app that plays owns nothing locally. A first pass on this day blamed our own
focus helper for all of it. Confirm the player is playing through the car's own
speakers before reading anything else.

**What the wheel sends.** Play/pause is `386`. Next and previous arrive twice
each: the vendor code `307` or `308`, and then the standard `87` or `88` that
`PhoneWindowManager` re-injects. So every skip costs two of the ring's twelve
slots, and a minute of skipping hides every play/pause that came before.

**Still ours, still unfixed.** Where several sessions report PLAYING at once -
the audible player, a video paused in another pane, the stock player woken by a
key we let through - the policy pauses whichever it finds first rather than the
one the driver means, and any package that reports PLAYING becomes the persisted
last-played, the stock player included. The report showed
`Запомненная сессия=com.byd.mediacenter` on a car whose driver had been listening
to Yandex all evening.

### What the support report says about the key (2026-09-11)

`Log.i` under `DenzaMediaResume` is invisible on a car whose owner has no host
ADB - this firmware ships a global `log.tag=M` - so the support report carries
three lines of its own, built from the live controller rather than from a second
copy of its state. `Кнопка play/pause=` is `слушает` while the filter is attached
to the session service, `нет доступа к сессиям` when `start()` failed or access
was lost, and `сервис не подключён` when no bound accessibility service owns a
controller; `Запомненная сессия=` is the persisted last-played package - what a
Play press resolves from - or `нет`; `Последние нажатия=` is the last twelve
initial DOWNs on one line, oldest first, each `HH:mm:ss <код> ✓|✗ <исход>`,
where `✓` means we took it - the press was consumed, or a transport command or a
directed media button went out. The outcome is `<пакет> <reason>` with the
reason from `MediaResumeReason` above (`play`, `pause`, `pause-deferred`,
`reconnect-started`, `media-button-sent`, `stock-no-history`, ...), or one of
the filter's own words: `not-media` for a code we never intercept,
`not-listening`, `already-down`, or the guard that refused the press
(`audio-mode`, `stream-mute`, `vendor-mute`, `in-call`, `unavailable`). An
entry with no key code is a decision reached after its press was over: a
deferred pause completing, or a reconnect ending (`reconnect-played`,
`reconnect-failed`, `reconnect-timeout`, `no-browser-service`).

> **Superseded 2026-10-09:** every entry carries its key code now. The deferred pause, the only decision left that ended after its press, moved to [`research/media-focus/`](../research/media-focus/README.md), and `MediaKeyDiagnostics.recordCompletion` went with it.

> **Superseded 2026-10-08:** the reconnect and its reasons are gone, and no directed media button goes out any more. A Play with no live session of the last-played package is `✗ <package> stock-no-live-session`, left to the firmware; an entry with no key code is now only a deferred pause completing, which does not run while `FOCUS_SURGERY` is false — see [No resurrection after sleep (2026-10-08)](#no-resurrection-after-sleep-2026-10-08).

> **Superseded 2026-09-18:** the section has four lines, not three: `Режим медиакнопки=` (`MediaKeyExperiment.label`, `без правки фокуса` in the current build) follows `Кнопка play/pause=` — see `MediaKeyDiagnostics.kt`, `MediaKeyReport.lines`.

To read a remote car, have the owner press the wheel button a few times and send
the report. No entry at all for those presses means the key never reaches
`onKeyEvent`; `not-media` names a code we do not intercept - the corpus maps
vendor 334/335 to explicit Play/Pause, and the reported N9 wheel may well use one
- and anything else says which of our own steps handed the press back to stock
routing, whose Play fallback opens the stock local player. The record is
write-only: nothing in it is read back by the filter, so the key decides exactly
what it decided before.

## Evidence base for the role findings (2026-08-16 to 2026-08-27)

Observed live on 2026-08-16, 2026-08-22, and 2026-08-27 over ADB (`shell` UID,
no root), plus
static reading of `BydAutoVoice.apk` (`v15.3.149.11.260512.7`, 11 dex files).
The 2026-08-22 pass decompiled the remaining automation dexes
(`AppMangerUtils`, `NaviManager`, `MediaApi`, `DiyChoiceScence5_1`,
`IOTProvider`, `HandleDefaultAppUtils`) and took read-only dumps of PersonBean
and installed role packages. A later bounded write the same day pointed
`DEFAULT_MAP_SWITCH` at `ru.yandex.yandexnavi`; a live Shortcuts Test Run of
`102000` opened Yandex Navigator. The row was restored to
`com.byd.launchermap` and the restore was read back. `byd_map_package` was
not changed.

The 2026-08-27 pass also proved the music and video roles with installed RF
apps. With `MUSIC_SWITCH=ru.yandex.music`, the firmware's **Continue playing**
action executed runtime command `129003`, read the Yandex package, and started
`ru.yandex.music/.main.MainScreenActivity`. The apparent **Open music** action
executed `129136` and opened the stock media center instead. With
`VIDEO_SWITCH=com.vk.vkvideo`, **Open video** opened VK Video and was confirmed
by the operator; the corresponding runtime path is `131500`. Both PersonBean
rows were restored to their stock values and read back after the test. On this
DiLink build a successful `content update` prints no row count, so the exact
post-write query is the authoritative success check.

## Where the feature lives

"Shortcuts" is `com.byd.autovoice/.DiyCommandActivity`, a second launcher entry
of the voice assistant package (`/system/byd/devices/users/app/BydAutoVoice`,
`android.uid.system`). It is not a separate app. `com.byd.scenemodes` is the
unrelated scene/privacy platform.

The editor model is `If <trigger> → Then execute <command>`, plus `Cycle Mode`
and `Repeat` settings, with `Save` and `Test Run`. Persistence is a GreenDAO
`DiyCommandBean` (`conditions` / `actions` as `QicAtom` lists). The exported
`content://com.byd.autovoice` provider does **not** expose that table: every
path segment still reads `PERSON_BEAN`.

## Trigger catalog (rich)

| Category | Options |
| --- | --- |
| Voice Trigger | custom spoken phrase ("Speak to the voice") |
| Doors and Windows | 4 doors, 4 windows, sunshade |
| Child lock | — |
| Seat Belt | — |
| Wiper | — |
| Environment | outside temperature, UV, charger/discharger |
| Vehicle status | battery SOC, fuel/electric/total range, power-on state, vehicle speed (above/equal/below), gear |
| Timer | timer, time range, location arrive/leave (geofence) |

## Execution catalog (closed in the UI)

The Then picker is compiled into `DiyChoiceScence5_1.initLevel1()`, not a
query of installed packages. An arbitrary package cannot be added through the
UI.

DiLink 5.1 Then groups and the IDs that matter for launching something. The
catalog ID comes from the decompiled picker; the runtime ID is what the live
firmware logged after `Test Run`:

| UI group | Then item | Catalog ID | Live runtime ID | What it actually starts |
| --- | --- | --- | --- | --- |
| 应用管理 | 打开应用 | `101000` | — | Label match among installed LAUNCHER apps. Catalog is five BYD names only: 智能语音助理, 百变主题, 文件管理, 用户手册, 应用市场 |
| 应用管理 | 关闭应用 | `101001` | — | Same closed name list |
| 媒体 | 音乐 → 打开 | `129136` on the live R123-style picker | `129136` | Stock `com.byd.mediacenter`; it did **not** honor `MUSIC_SWITCH` on this car |
| 媒体 | 音乐 → 继续播放 | `129003` | `129003` | `MediaApi.getDefaultMusicPkg()`, then generic launch when no usable current music session exists; live-proven with Yandex Music |
| 媒体 | 视频 → 打开 | static picker uses `131501` | `131500` | `MediaApi.getDefaultVideoPkg()`; live-proven visually with VK Video |
| 导航 | 地图 → 打开 | `102000` | `102000` | `MapUtils.getCurDefaultSelectMap()`. BYD map uses the automap API; any other installed package uses `AppMangerUtils.Y` |
| 导航 | 地图 → 关闭 | `102001` | — | Close map |
| 导航 | 导航 → 家 / 公司 | `102003` / `102004` | — | Destination handoff into the current map role |

The 2026-08-16 English UI names (`Desktop`, `Music`, `Navi`, `Video`,
`Navigation Customization`, `Mic-free Karaoke`) are the same engine with
localized labels. On this 5.1 catalog there is no Home/Desktop item and no
Karaoke Then-action; 全民K歌 appears only in the older `DiyChoiceScence_R123`
list.

`101000` is not an app picker. The names are literals in
`DiyChoiceScence5_1.CarNav()`. The executor, however, resolves the name with
a contains-match over every installed app's launcher label
(`AppMangerBaseUtils.j` / `y`). Only the editor is closed.

## How Then-actions start an activity

All three useful launch paths end in `PackageManager.getLaunchIntentForPackage`
plus `FLAG_ACTIVITY_NEW_TASK` (`0x10000000`). The target does not need a BYD
class name. On this car Denza Apps' launcher is
`dev.denza.apps/.DenzaLauncherActivity` (label `Denza Apps`, version `0.5.4`
as installed). Live build 41 later proved that `FROM=com.byd.autovoice` also
appears on the map-role path; it identifies the caller, not the semantic role.

### `102000` — open map (best Shortcuts hook)

`NaviManager.x(package)`:

```java
if (!str.equalsIgnoreCase(MapUtils.getBydMap())) {
    AppMangerUtils.getInstance().Y(this.a, str);   // any other installed package
    return;
}
// else BydAutoMapImpl / EmptyJumpActivity
```

`AppMangerBaseUtils.Y` (now decompiled from classes8) is only:

```java
Intent launchIntentForPackage = context.getPackageManager().getLaunchIntentForPackage(str);
if (launchIntentForPackage != null) {
    launchIntentForPackage.addFlags(268435456);
    context.startActivity(launchIntentForPackage);
}
```

No destination extra, no Gaode/Baidu protocol. A third-party map role **opens
the app**. Home/Company (`102003`/`102004`) remaining a destination-handoff
question is unchanged and is not required to launch a third-party package.

A `Test Run` of `Navi → Access map` while the role was still BYD (2026-08-16):

```
MapToFunction_sunny: updateData ... commandId=102000, curMapPackage=com.byd.launchermap
ActivityTaskManager: START u0 {flg=0x10000000
  cmp=com.byd.launchermap/com.byd.automap.activity.EmptyJumpActivity (has extras)}
```

On 2026-08-22 the same Then, after

```bash
adb shell content update --uri content://com.byd.autovoice/PersonBean \
  --bind VALUE:s:ru.yandex.yandexnavi --where "SETTING='DEFAULT_MAP_SWITCH'"
```

opened Yandex Navigator (`ru.yandex.yandexnavi` is installed; operator
confirmed the live start). `byd_map_package` stayed `com.byd.launchermap`.
The PersonBean row was restored to `com.byd.launchermap` and read back.
That is live proof of `NaviManager.x` → `AppMangerUtils.Y` →
`getLaunchIntentForPackage` for a non-BYD package. `dev.denza.apps` was
not the target of that run; it uses the same branch.

### Music — static open path versus the live launch action

The decompiled `DiyChoiceScence5_1` catalog labels `104451` as open music, and
its static executor contains this default-role path:

`MediaStart.i()`:

```java
String defaultMusicPkg = MediaApi.getDefaultMusicPkg();
if ("com.byd.mediacenter".equals(defaultMusicPkg)) {
    MediaFactory.d().b().E0(false, 1001, -1);     // mix-module, not a generic launch
} else {
    AppMangerUtils.getInstance().U(defaultMusicPkg); // same getLaunchIntentForPackage
}
```

`MediaApi.getDefaultMusicPkg()` is `PersonBean.MUSIC_SWITCH` with fallback
`com.byd.mediacenter`. Validation is only "is this package installed"
(`MediaApi.e` → `AppMangerUtils.f`). No Chinese-app whitelist on the stored
value.

The live 2026-08-27 picker behaved like the R123 catalog instead. With
`MUSIC_SWITCH=ru.yandex.music`:

```text
commandID = [129136]
MediaStart: whiteLaunch ... mediaApp = [com.byd.mediacenter]

commandID = [129003]
defaultMusicPkg:ru.yandex.music
MediaStart: launchMediaApp ... mediaApp = [ru.yandex.music]
ActivityTaskManager: START ... cmp=ru.yandex.music/.main.MainScreenActivity
```

Therefore the reliable user instruction for this exact firmware is
**Music → Continue playing**, not **Open music**. This remains state-sensitive:
the command may continue an already active supported media session instead of
starting a package. Denza Apps changes the role only; it does not rewrite the
Shortcuts catalog or inject the runtime command.

### Video — Open video

The static picker stores `131501` for **Open video**, while the live executor
logged `131500`. With the stock role, that runtime path read
`defaultVideoPkg=com.byd.videoplay`, reported `isNetworkEnable:true`, and
started the stock player. With `VIDEO_SWITCH=com.vk.vkvideo`, the same visible
**Open video** choice opened VK Video (operator-confirmed on 2026-08-27).
The tunnel dropped before the probe captured the complete VK launch chain, but
after reconnection the role still read `com.vk.vkvideo` and Android retained a
VK Video activity record. The row was then restored to `com.byd.videoplay` and
read back.

Video has two extra constraints: AutoVoice must report its network gate as
enabled, and an already visible supported video app in a split/top scene may be
continued instead of launching the configured role. For a launch check use a
clean fullscreen scene and **Open video**; Play/Pause/Continue are media-control
commands, not package selectors.

### `101000` — open named app

`APPToFunction` special-cases Chinese media titles (QQ音乐, 酷狗, 网易云, …)
then falls through to `AppMangerUtils.y(name)`, which keeps every installed
package whose launcher label contains the requested string
(case-insensitive, spaces stripped) and that has a LAUNCHER intent. The
blacklist is only `com.byd.bydcamera`. Voice "打开 Denza Apps" uses this
path; the Shortcuts Then-list simply never offers that name.

## Role switches (live 2026-08-22, rechecked 2026-08-27)

| Key | PersonBean | Live value | Consumer |
| --- | --- | --- | --- |
| `DEFAULT_MAP_SWITCH` | `_id=43` | `com.byd.launchermap` | voice navi, Shortcuts `102000` |
| `MUSIC_SWITCH` | `_id=45` | `com.byd.mediacenter` | live Shortcuts runtime `129003` (Music → Continue playing), voice/media default |
| `FM_SWITCH` | `_id=44` | `com.byd.mediacenter` | radio |
| `NEWS_SWITCH` | `_id=46` | `com.byd.mediacenter` | news |
| `VIDEO_SWITCH` | `_id=186` | `com.byd.videoplay` | visible Shortcuts Open video; live runtime `131500` |
| `KARAOKE_SWITCH` | (no row) | empty / third-music table | KTV |
| `byd_map_package` | Settings.Global | `com.byd.launchermap` | CustomKey action 7 only; this car uses action 1 (APA) |

Read:

```bash
adb shell content query --uri content://com.byd.autovoice/PersonBean --where "SETTING='DEFAULT_MAP_SWITCH'"
adb shell content query --uri content://com.byd.autovoice/PersonBean --where "SETTING='MUSIC_SWITCH'"
adb shell settings get global byd_map_package
```

All three role rows are now **proven writable from `shell`** with exact
post-write and restore readbacks:

- `DEFAULT_MAP_SWITCH`: Yandex Navigator opened from runtime `102000`
  (2026-08-22).
- `MUSIC_SWITCH`: Yandex Music opened from runtime `129003`
  (2026-08-27).
- `VIDEO_SWITCH`: VK Video opened from the visible Open video action, whose
  runtime path was `131500` (2026-08-27, operator-confirmed).

> **Superseded 2026-09-03:** shell is no longer the writer: the same rows are readable and writable from an ordinary app UID through `ContentResolver`, which is the product's path (`DefaultAppRoleRepository.kt`); shell `content` stays the host-side oracle — see [App-UID ContentResolver access (live-proven 2026-09-03)](#app-uid-contentresolver-access-live-proven-2026-09-03).

`settings put global byd_map_package` is also writable (2026-08-16) but is a
different wheel-key setting and was not touched in any of these role tests.
The DiLink 5.1 `content update` command returns exit success with empty stdout;
do not require an `Updated 1 row` string. Require exactly one row before the
write and an exact value readback afterwards.

The official voice-settings picker is narrower than the stored-value check:

- Map picker (`DefaultAppFragment.G0` / `HandleDefaultAppUtils` type `map`)
  only commits BYD / Gaode / Baidu / Huawei package names, matched by
  **display name**. `setDefaultApp` with `type=map` cannot point at Denza Apps.
- Music picker lists `MediaApi.getSupportMusicList()`, which is
  `THIRD_MUSIC_CONTROL_BEAN` rows of type `MUSIC`. Writing `MUSIC_SWITCH`
  directly, or registering via `FUNCTION_UPDATE` then picking in the UI,
  bypasses that.

## Signature-gated hooks (still closed)

A normally-signed APK still cannot join the voice/third-app SDK:

| Hook | Protection | Result |
| --- | --- | --- |
| `com.byd.autovoice.permission.thirdapp` | `signatureOrSystem` (`protectionLevel=0x3`) | cannot `pm grant` |
| `com.byd.autoiot.service.BROADCAST_PERMISSION` | `signature` | IoT DIY insert from a third-party app is rejected |
| `android.permission.BYDAUTO_*` | `signature` | unchanged |

`IOTProvider` (`content://com.byd.autovoice.iot_provider/IOT`) **is**
exported and can insert a full `DiyCommandBean` (JSON `QicAtom` conditions
and actions), but `checkCallingPackage()` allows only
`com.byd.iotmanager` and `com.byd.mediacenter`. An empty caller is rewritten
to `iotmanager`; `com.android.shell` would throw `SecurityException`. Query
does not check the caller (read 2026-08-22: empty `id`/`code` cursor, no
exception). Do not treat IOT insert as a Denza Apps API.

`dumpsys car_service` does not exist — this is not Android Automotive.

`com.byd.maphelper` is **not** an integration point. It only toggles
`com.byd.rsemap`/`com.byd.navigatormap` and `com.byd.fsemap`/`com.byd.deputymap`
via `setApplicationEnabledSetting`.

## Masquerading as a Chinese app does not win the role

Live `pm list packages` on 2026-08-22:

| Package | Role it would impersonate | On this car |
| --- | --- | --- |
| `com.byd.launchermap` | default / BYD map | **installed** (current `DEFAULT_MAP_SWITCH`) |
| `com.autonavi.minimap` / `com.autonavi.amapauto` | Gaode | missing |
| `com.baidu.BaiduMap` / `com.baidu.mapauto` | Baidu | missing |
| `com.byd.mediacenter` | default music / radio / news | **installed** (current `MUSIC_SWITCH`) |
| `com.netease.cloudmusic` / `com.kugou.android.auto` / `com.tencent.qqmusic` | music fallback names | missing |
| `com.byd.videoplay` | default video | **installed** (`VIDEO_SWITCH`) |
| `com.byd.minikaraoke` | KTV | installed |
| `dev.denza.apps` | — | installed |
| `ru.yandex.yandexnavi` / `ru.yandex.music` | — | installed |

`MapUtils.getCurDefaultSelectMap()` returns the stored PersonBean value if
that package is installed. The Gaode/Baidu list is only scanned when the
stored value is empty **or** that package is missing. `com.byd.launchermap`
is present, so installing a fake `com.autonavi.minimap` does nothing to
Shortcuts `102000`. The same is true of NetEase vs `com.byd.mediacenter` for
music.

A sidecar APK that steals a Chinese package name would also collide with a
later real install, cannot share `dev.denza.apps` updates, and is not needed:
the stored role already accepts any installed package.

Do not disable `com.byd.launchermap` to force the fallback scan.

## Registration APIs and adjacent paths

These are how a third-party app can *look* registered without the
`thirdapp` permission. The PersonBean path is now live-proven for all three
product roles; the other registration paths remain corpus findings unless
stated otherwise.

1. **PersonBean write (live-proven for map, music and video)** from `shell`
   through `content://com.byd.autovoice`. Points the matching Shortcuts
   Then-action at any installed launchable package. Shared with voice
   "open map/music". Restore the original row after a probe.

   > **Superseded 2026-09-03:** the same write works from the app UID through `ContentResolver`, with no ADB, and that is what the product uses — see [App-UID ContentResolver access (live-proven 2026-09-03)](#app-uid-contentresolver-access-live-proven-2026-09-03).

2. **`VoiceSettingDatabaseProvider.call("setDefaultApp")`** — exported, no
   caller check. Bundle keys `type`, `appName`, `pkgName`. `type=music|video|ktv|news|radio`
   writes `pkgName` with no whitelist. `type=map` still requires a known map
   display name, so it cannot elect Denza Apps. Callable from Denza Apps
   itself without ADB, or from `adb shell content call --uri content://com.byd.autovoice --method setDefaultApp …`.
3. **Broadcast `com.byd.action.FUNCTION_UPDATE`** — `MusicAppReceiver` is
   registered without a broadcast permission (live
   `dumpsys activity broadcasts`: `com.byd.autovoice/1000`). Extras:
   `EXTRA_PACKAGE_NAME`, `EXTRA_APPLICATION_TYPE` (`MUSIC` / video / …),
   `ISCONTROL`, `EXTRA_SERVICE_NAME` (required, non-empty). Inserts
   `THIRD_MUSIC_CONTROL_BEAN` and makes the package appear in the official
   default-music picker. Still need the user, or a PersonBean write, to
   *select* it.
4. **Voice by label** — no registration. "打开 Denza Apps" already matches
   `DenzaLauncherActivity`'s label. This is not a Shortcuts Then-item.

## Two independent map-role switches

There is no single "default navigation app". Two switches exist and different
consumers read different ones.

| Switch | Storage | Read by |
| --- | --- | --- |
| `DEFAULT_MAP_SWITCH` | `PersonBean` row in `com.byd.autovoice` | voice assistant, Shortcuts `102000` |
| `byd_map_package` | `Settings.Global` | `CustomKey` action 7 and `BydProjectionService` native Gaode/Baidu cluster-provider selection |

> **Superseded 2026-10-15:** the earlier claim that setting both replaces
> navigation everywhere, and that `byd_map_package` has no consumer with wheel
> action 1, missed the native projection service. See
> [the firmware integration study](stock-map-findings.md#native-cluster-provider-contract).

`DEFAULT_MAP_SWITCH` controls generic navigation opening from Shortcuts and
voice. It does not replace the launcher's fixed map widget/HOME providers or
AutoVoice's explicit stock destination-protocol binding. The projection service
accepts the exact Baidu package or defaults to Gaode; putting the Yandex package
in `byd_map_package` does not register Yandex as its provider. On this car the
wheel key's action 1 (APA) means action 7 is not used; it does not make that
setting globally inert.

### `DEFAULT_MAP_SWITCH` — the voice and Shortcuts path

`MapUtils.getBydMap()` does **not** read `Settings.Global`. It returns a fixed
choice by device type, falling back to the literal `"com.byd.launchermap"`.

The selectable value lives in `MapUtils.getCurDefaultSelectMap()`, backed by
`PersonDaoManger.z0 = "DEFAULT_MAP_SWITCH"`:

```java
String strG = PersonDaoManger.getInstance().g(PersonDaoManger.z0, getBydMap());
if (!TextUtils.isEmpty(strG) && AppMangerUtils.getInstance().f(strG)) {
    return strG;          // only validation is "is this package installed"
}
```

The stored value is **not** checked against the candidate list. That list
(`MapUtils.b` — BYD maps plus `com.autonavi.*` and `com.baidu.*`) is only the
fallback scan used when nothing is stored, and contains no Yandex, Waze, 2GIS
or `dev.denza.apps`.

## Product shape: inject into AutoVoice, do not daemonize Denza Apps

`com.byd.autovoice` is `SYSTEM|PERSISTENT` (`android.uid.system`). It is
already running at boot and is the process that observes seatbelt
(`CAD01` / `INSTRUMENT_DD_MAIN_SAFETYBELT_STATE` = `0x29400018`) and
executes Then. Denza Apps is a normal `/data/app` APK: it is not
persistent, and a background listener would have to be running first.
That is the wrong runtime.

Denza Apps may only be:

- a **one-shot editor** that writes AutoVoice state, then exits; or
- a **trampoline activity** that AutoVoice cold-starts, which immediately
  starts the target and finishes. AutoVoice, not Denza Apps, saw the
  seatbelt.

The stock Then picker will never list an arbitrary installed app.
`101000` *execution* already does a contains-match on every LAUNCHER
label; the editor just will not offer that label. A persisted
`DiyCommandBean` with condition `CAD01` and action `101000` /
value=`Яндекс Музыка` would be the real «any app per rule» inject.
Storage is GreenDAO `DIY_FUSION_BEAN` under AutoVoice's private data
dir. Shell cannot list that dir (`Permission denied`); `run-as` refuses
a non-debuggable system package.

Write APIs that could persist such a row:

| API | Caller gate | Status |
| --- | --- | --- |
| Official Shortcuts UI | user | Then catalog is closed; cannot pick Yandex Music |
| `IOTProvider.insert` (`content://com.byd.autovoice.iot_provider/IOT`) | only `com.byd.iotmanager`, `com.byd.mediacenter`, or an *empty* `getCallingPackage()` (rewritten to iotmanager) | Denza Apps UID is rejected. `com.android.shell` is expected to throw `SecurityException`. Empty-caller hole is unproven. Invalid JSON must be used if this is ever probed, so no row is created. |
| sqlite on `DIY_FUSION_BEAN` | filesystem | blocked without root |
| `VoiceSettingDatabaseProvider.call("setDefaultApp")` | none | writes a **role**, not a per-rule action |

Until a persist path for `101000`+label is proven, the only AutoVoice-native
Then that can start a third-party package is a **role**:

| Stock Then | Role key | Live occupant | Independent rules |
| --- | --- | --- | --- |
| 导航 → 地图 → 打开 (`102000`) | `DEFAULT_MAP_SWITCH` | `com.byd.launchermap` | one package for every open-map command |
| 媒体 → 音乐 → 继续播放 (live runtime `129003`) | `MUSIC_SWITCH` | `com.byd.mediacenter` | one default package, but an existing media session can win |
| 媒体 → 视频 → 打开 (static `131501`, live runtime `131500`) | `VIDEO_SWITCH` | `com.byd.videoplay` | one package for the Open video command |

The Denza Apps `Приложения` panel writes those three live-proven PersonBean
roles and explains which matching Shortcuts action to use. It is configuration,
not a runtime: AutoVoice remains the daemon. Navigation, music, and video all
keep the selected application's real package directly in PersonBean. That is at
most three always-on targets, not an arbitrary app per rule. Updating a selected
application can currently make AutoVoice fall back to stock; update recovery is
deliberately deferred rather than hidden behind a package proxy.

> **Superseded 2026-10-08:** the navigation role is repaired after an update that lands while Denza Apps is running; music and video, and an update while the app is stopped, are still not repaired — see [Navigation role repaired after a Store update while Denza Apps runs (2026-10-08)](#navigation-role-repaired-after-a-store-update-while-denza-apps-runs-2026-10-08).

First-run selection is handled once per role after a successful provider read.
An untouched stock value may be replaced with the first installed known app,
but the update predicate also requires the value to still be stock, so a
concurrent external choice cannot be overwritten. Keeping stock or preserving
a pre-existing non-stock value also completes first-run handling; installing a
known app later never causes a surprise role switch. PersonBean is reread on
Activity resume, and an unavailable read is shown only as a last-known value,
not as a confirmed current selection.

### Retired single-package navigation proxy experiment (2026-09-03)

The firmware's `AppReceiver` handles `PACKAGE_REMOVED` without checking
`Intent.EXTRA_REPLACING`. If the removed package equals `DEFAULT_MAP_SWITCH`,
`updateRemoveMap()` immediately replaces it with the first installed entry in
`MapUtils.b`, normally `com.byd.launchermap`. An ordinary Store update therefore
destroys a direct `ru.yandex.yandexnavi` binding before `PACKAGE_REPLACED`
arrives; the replacement handler updates only AutoVoice's package catalog and
does not restore PersonBean. Build 41 tried to avoid that reset inside the one
`denza-apps.apk`: PersonBean held `dev.denza.apps`, while an atomic app-owned
file held the logical navigator and a windowless `MAIN + CATEGORY_INFO`
trampoline forwarded the package launch.

The experiment failed its first live routing check. At 19:38 the Shortcuts map
action cold-started the trampoline, but it opened `MainActivity` instead of the
configured 2GIS target. The assumption that `FROM=com.byd.autovoice` identified
only a generic app-open was false: the live map-role intent carried the same
marker. Ignoring `FROM` would make navigation work, but would instead route the
ordinary AutoVoice "open Denza Apps" package launch to the navigator.

The clean-context adversarial review then found that this is not a missing
predicate but a package-identity limit:

- generic AutoVoice open and non-stock navigation both end in
  `getLaunchIntentForPackage(dev.denza.apps)` with the same `MAIN + INFO`
  contract; activities, aliases, and processes in one APK cannot distinguish
  those calls;
- music cannot use the same trampoline because its role identifies a media
  session/vendor-service endpoint, not only an Activity to launch;
- video has package-based network, top-task, and split branches, so substituting
  the Denza Apps package changes its firmware semantics too;
- the asynchronous `MY_PACKAGE_REPLACED` repair did not hold the receiver with
  `goAsync()`, and stale proxy ownership could overwrite an external stock
  choice;
- using one app-owned target plus PersonBean plus preferences created a
  multi-store operation without a transaction.

Decision: retire the proxy in build 42 and restore direct PersonBean packages for all three
roles before doing any update-recovery work. The proxy Activity, target store,
dispatch policy, repair state, and manifest entry are removed. The original
`MAIN + INFO` entry belongs to `SplitPickerActivity` again. The already installed
build 41 may have left `DEFAULT_MAP_SWITCH=dev.denza.apps`; after installing
build 42, select the navigator again in `Приложения` and verify the exact
package with a provider readback.

> **Superseded 2026-09-22:** later builds replaced build 41 (build 47 and up were on the Z9GT by 2026-09-18), and `DEFAULT_MAP_SWITCH` read `ru.yandex.yandexnavi` on 2026-09-22, so the build-41 value is gone — see [stock-map-findings.md](stock-map-findings.md) and [The firmware's self-start gate blocks both reconnect paths (2026-09-18)](#the-firmwares-self-start-gate-blocks-both-reconnect-paths-2026-09-18).

The proposed second stage is recovery, not indirection:

1. First use an isolated probe to establish whether Store delivers
   `PACKAGE_REMOVED`, `PACKAGE_ADDED`, and `PACKAGE_REPLACED` to a manifest
   receiver while Denza Apps is stopped, including event order and
   `EXTRA_REPLACING` on this firmware.
2. If proven, keep an app-owned per-role journal of desired target, last exact
   PersonBean readback, ownership, pending replacement, and generation.
3. Use a bounded `goAsync()` receiver to restore only a role whose exact selected
   package was observed entering a replacement; never restore over an
   unexplained external value.
4. Treat roles separately: navigation can be restored after launcher
   availability; music must also wait for media registration/session readiness;
   video must retain its package identity and firmware split/top semantics.
5. Accept that Denza Apps can provide eventual recovery after replacement, but
   cannot guarantee commands during the remove/install gap. Zero-gap behavior
   requires fixing AutoVoice to ignore `PACKAGE_REMOVED` when
   `EXTRA_REPLACING=true`, or separate package identities.

Do not poll `autoservice` from a Denza Apps service as the product
Shortcuts replacement. That reintroduces the "must already be running"
constraint the stock engine does not have.

### Navigation role repaired after a Store update while Denza Apps runs (2026-10-08)

Code change from a read-only review; not installed on a car, no live run.

Step 3 of the plan above is implemented for the navigation role only, inside
the running process, without the step-1 probe and without a durable journal.
`DefaultAppsCatalogCache` already holds a dynamic package receiver for as long
as the process lives (registered by the first default-apps refresh, which the
repository's startup runs). It now passes each broadcast to
`NavigationRoleRepair` before it starts its own refresh:

- `PACKAGE_REMOVED` with `EXTRA_REPLACING=true` **arms** the repair when the
  package is the one this app last confirmed in `DEFAULT_MAP_SWITCH`
  (`DefaultAppsSettings.confirmedSelection`) and is not the stock map. The
  remembered pick alone is not used: it survives the Shortcuts switch being
  turned off, and an update must not undo that.
- `PACKAGE_ADDED` with `EXTRA_REPLACING=true` of the armed package writes it back
  with `setIfCurrent(expected = com.byd.launchermap)` on the default-apps
  thread. A role holding anything other than the stock map is left alone. If
  that write finds the navigator still in place (AutoVoice's reset not yet
  applied), `PACKAGE_REPLACED` tries once more and the repair is disarmed.
- `PACKAGE_REMOVED` without `EXTRA_REPLACING` disarms it: an uninstalled
  navigator stays reset.

Not covered: an update while Denza Apps is not running (the open question
above), the music and video roles, and the remove/install gap itself, during
which voice and Shortcuts still open the stock map. One residual case remains.
If the driver sets the stock map in the stock settings and Denza Apps does not
read PersonBean before the navigator's next update, that update puts the
navigator back. `NavigationRoleRepairTest` holds the decision.

### App-UID ContentResolver access (live-proven 2026-09-03)

PersonBean is reachable from an ordinary app UID. On 2026-09-03 a disposable
probe holding no permission and nothing but `<queries>` for `com.byd.autovoice`
(uid 10144) read and wrote `content://com.byd.autovoice/PersonBean` through
`ContentResolver`; every row matched the shell oracle. The provider is exported
with no read or write permission and no caller check.

| Path | Cost |
| --- | --- |
| App `query`, one role (median of five) | 2.3 ms |
| App `query`, all three roles | 4.9 ms |
| App `update`, one role | 3.1 ms |
| Shell `content query`, one role | 1.21 s |

The shell path pays an `app_process` VM spawn per command and is about 500×
slower. The write used the conditional predicate `SETTING=? AND VALUE=?`, whose
int return is the matched-row count: `MUSIC_SWITCH` moved from `ru.yandex.music`
to `com.byd.mediacenter` with `count=1`, app readback and an independent shell
readback agreeing, and restore left all three roles at baseline with an
unchanged crash buffer and zero `com.byd.avc` lines.

`ContentObserver` delivery is not confirmed: an observer on the PersonBean URI
saw nothing for its own update within 3000 ms nor for an external shell update,
one on the authority root recorded zero events across both writes, and logcat
shows no provider error. Either `update()` never reaches `notifyChange` on this
build or ContentService here does not deliver cross-app observers. A reader
invalidates by re-reading, which costs about 2 ms, not by observing.

Reproduce with `experiments/personbean-provider-probe/` driven by
[tools/personbean_provider_probe.sh](../tools/personbean_provider_probe.sh),
restore-wrapped and self-uninstalling; it wakes the probe's no-display Activity
first, because the self-start gate recorded in
[dishare-api-notes.md](dishare-api-notes.md) drops an explicit broadcast to a
third-party UID with no live process (`UID … is not running` → `skip reciever …
ignored !!!`, `result=0` and no data, no permission denial).

Two consequences follow. Denza Apps' `Приложения` roles need no local-ADB
privilege path for PersonBean at all; the `content` commands and the ADB gate in
front of them are what cost the panel its seconds. Package-replacement recovery,
if pursued later, must separately prove that a manifest receiver is delivered
while Denza Apps is stopped and must hold its asynchronous work with
`goAsync()`; app-UID provider access alone proves neither property.

The product took the first consequence on 2026-09-03.
`DefaultAppRoleRepository` now talks to `ContentResolver` directly - a
`PersonBeanAccess` seam over the fixed URI and `_id, SETTING, VALUE` projection,
`SETTING=?` to read, `SETTING=? AND VALUE=?` for a conditional write whose
matched-row count must be exactly one, plus a `readAll()` that fetches all three
roles with one `SETTING IN (?,?,?)`. The shell command builders and their text
parsers are gone, and with them the ADB gate in front of the feature: the panel
reads its roles at process start, regardless of the ADB phase, and the
"Нужен доверенный локальный ADB" refusals no longer exist. The safety contract is
unchanged (three-key allowlist, package-name regex, one-row preflight, exact
readback) and unit-tested; `:denza-apps:testDebugUnitTest` and `assembleDebug`
pass. The product change itself has no live acceptance yet - only the probe
above has run in the car.

> **Superseded 2026-09-03:** build 41, the ContentResolver build (`95bd2794`), ran this path on the car the same evening: its first read rewrote `DEFAULT_MAP_SWITCH` (the "Build 41" paragraph below), and on 2026-09-22 the row read `ru.yandex.yandexnavi` ([stock-map-findings.md](stock-map-findings.md)). No owner acceptance of the «Приложения» panel itself is recorded.

The panel now hydrates from the last confirmed state, revalidates on every resume without changing
a known state or refusing a tap, and sweeps installed launchers once per process and after package
changes. All three selections are direct PersonBean packages; no update repair runs in the
background.

Build 41 (versionCode) went onto the car on 2026-09-03 18:03 through `adb
install -r`. Observed, read-only, right after the install: no crash and no
`com.byd.avc` line; `MY_PACKAGE_REPLACED` was delivered to `dev.denza.apps` and
BroadcastQueue started the process for it (`Need to start app [background]
dev.denza.apps for broadcast`), so on this car the self-start gate did not drop
the replacement broadcast that the probe's custom action was dropped by - one
data point, from an adb install, not a Store update; on the first read the
direct `ru.yandex.yandexnavi` binding was migrated and `DEFAULT_MAP_SWITCH` now
holds `dev.denza.apps`; `cmd package resolve-activity` for MAIN+INFO on the
package answers `DefaultNavigationProxyActivity`. At 19:38 the owner's first
Shortcuts 导航 → 地图 → 打开 run cold-started that trampoline with `MAIN + INFO`
and an AutoVoice extra, but build 41 incorrectly treated that extra as a generic
app-open request and opened `MainActivity`. The live run disproved that routing
assumption. The proposed follow-up of treating every `MAIN + INFO` entry as
navigation was rejected because it breaks generic app-open and cannot extend to
music or video. The proxy was retired before another vehicle build.

## Russia-oriented launch strategy

The car stays in RF. The stock map is China-only. Taking the map role therefore
costs little map utility and is the cleanest way to give Shortcuts a Then-action
that opens Denza Apps:

1. **Current product path: direct role package.** The direct target
   (`ru.yandex.yandexnavi`) opened from Shortcuts `102000` on 2026-08-22. The
   product writes the chosen navigator package directly to `DEFAULT_MAP_SWITCH`
   and uses the same 导航 → 地图 → 打开 action. For a restore-wrapped probe, use
   [tools/navi_role_probe.sh](../tools/navi_role_probe.sh); do not inject key
   `321`.
2. **Music is live-proven but state-sensitive.** Point `MUSIC_SWITCH` directly
   at the chosen installed player and use **Music → Continue playing** on this
   firmware (runtime `129003`). It opened Yandex Music from cold state. The
   visible Open music action instead opened `com.byd.mediacenter` in the live
   run. An already active supported media session can still take precedence.
3. **Video is live-proven for Open video.** Point `VIDEO_SWITCH` directly at
   the chosen installed player and use **Video → Open video** (live runtime
   `131500`). The network gate must be enabled and no supported video app
   should already occupy the active split/top scene.
4. **Voice without taking a role.** "打开 Denza Apps" uses `101000` label
   match. Useful as a fallback, not as a vehicle-status If/Then.
5. **Do not** ship a second APK as `com.autonavi.*` / `com.netease.*` /
   `com.byd.mediacenter`. Those names either already belong to system apps or
   do not beat the stored PersonBean value.
6. **Do not** declare `BYDAUTO_*` or `thirdapp` in the product manifest. The
   PersonBean rows themselves need no privileged writer: they are readable and
   writable straight from the app UID through `ContentResolver` (see "App-UID
   ContentResolver access" above). `DenzaLocalAdb` shell writes stay for what
   still needs them, such as the locale grant.

The former hidden automatic Map-mode follower and its
`StockClusterModeDetector` were removed on 2026-08-26. Moving
`DEFAULT_MAP_SWITCH` therefore no longer changes Denza Apps projection state.

Related RF work: the per-app `ru-RU` override for `com.byd.carsettings` in
[system-language.md](system-language.md). Shortcuts UI strings stay
Chinese; that is BYD's catalog, not a Denza Apps translation job.

## Restore-wrapped live probe

[tools/default_app_role_probe.sh](../tools/default_app_role_probe.sh) changes
exactly one PersonBean role, waits for a manual Shortcuts `Test Run`, records a
focused launch transcript, and restores the original with exact readback. It
never changes `byd_map_package`, injects input, clears logcat, starts an app, or
restarts ADB/AutoVoice.

```bash
tools/default_app_role_probe.sh run --serial 127.0.0.1:5555 \
  --role music --package ru.yandex.music --timeout 300 --require-stopped

tools/default_app_role_probe.sh run --serial 127.0.0.1:5555 \
  --role video --package com.vk.vkvideo --timeout 300 --require-stopped
```

For music on this firmware trigger **Continue playing**; for video trigger
**Open video**. The older [tools/navi_role_probe.sh](../tools/navi_role_probe.sh)
remains navigation-specific and also writes the separate global map key, so do
not use it for the three-role product acceptance. Exactly one session may
mutate PersonBean at a time.

### Never inject key code 321 as a navigation trigger

Key code `321` is the configurable steering-wheel custom key, not a navigation
key. On this car `CustomKeyHandler` logs
`key config(Double key: 3; Single key: 1,2): 1` and takes action `1`:

```
CustomKeyHandler: apaValue: 1 (1: APA_SUPPORT), hasParking: true
CustomKeyHandler: sendParkingBroadcast
ActivityTaskManager: START u0 {cmp=com.byd.avc/.AutoVideoActivity}
```

That opens `com.byd.avc` and starts an APA (auto parking assist) scan — a
physical vehicle function. Injected once on 2026-08-16; the parking module
reported `Obj=0, Slot=0, Track=0` (scan only, no slot, no maneuver) and
`com.byd.avc` did not crash. Do not repeat it.

The wheel-key action is per-car configuration, so action `7` (the map action
recorded in [instrument-display-findings.md](instrument-display-findings.md)) is
not what this car does.

## Cost to weigh before taking the role

The map role is shared by voice "navigate to", the Shortcuts `102000` family,
and the cluster Map mode. The stock map is China-only and this car stays in RF,
so the role has little to lose. Denza Apps no longer follows the stock cluster
Map task automatically.

The music role is shared by voice/media control and the live `129003` Continue
playing path. Taking it from `com.byd.mediacenter` is more user-visible than
taking the China map, and an already active session can still affect the
result. The video role also has a network gate and existing-scene special case.

See also [instrument-display-findings.md](instrument-display-findings.md), which
records `byd_map_package` and the package-scoped
`CUSTOM_NAVI_STANDARD_BROADCAST_RECV` broadcast for the wheel key.

## Next validation

> **Superseded 2026-09-22:** the first bullet is overtaken: builds 47 and up replaced build 41 on the Z9GT by 2026-09-18, and `DEFAULT_MAP_SWITCH` read `ru.yandex.yandexnavi` on 2026-09-22 ([stock-map-findings.md](stock-map-findings.md)). Of the second bullet only that map readback is recorded; the other bullets have no recorded run — see [Retired single-package navigation proxy experiment (2026-09-03)](#retired-single-package-navigation-proxy-experiment-2026-09-03).

- Install direct-role build 42. Because installed build 41 may leave
  `DEFAULT_MAP_SWITCH=dev.denza.apps` or AutoVoice may reset it to stock during
  replacement, select the navigator again in `Приложения`.
- Read back the real package names for `DEFAULT_MAP_SWITCH`, `MUSIC_SWITCH`, and
  `VIDEO_SWITCH`; none should be `dev.denza.apps` unless the user explicitly
  selected Denza Apps itself.
- Force-stop Denza Apps and run Shortcuts `102000`; prove the selected navigator
  opens directly and no Denza Apps process is required.
- Run live `129003` for music and `131500` for video from cold state, then with
  active sessions/top tasks, checking the real controlled package rather than
  only an Activity start.
- Re-run split open, picker selection, collapse/reopen, and reboot restore; the
  package's `MAIN + INFO` entry belongs to `SplitPickerActivity` again.
- Defer Store-update acceptance until the separate package-event probe and
  recovery design are implemented. (2026-10-08: the in-process navigation
  repair is implemented; its acceptance is a Store update of the chosen
  navigator with Denza Apps running, then a `DEFAULT_MAP_SWITCH` readback and
  Shortcuts `102000` — see [Navigation role repaired after a Store update while Denza Apps runs (2026-10-08)](#navigation-role-repaired-after-a-store-update-while-denza-apps-runs-2026-10-08).)
- Verify cold-start first-choice initialization on a clean product preference
  state without overwriting a pre-existing non-stock PersonBean choice.
- Per-rule any-app still needs a persist path for `101000`+label
  (`IOTProvider` caller gate is the next bounded probe, invalid JSON only).
- Do not spawn `app_process` while another session owns the car.
