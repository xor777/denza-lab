# Native Denza vehicle-control prototype

Standalone `dev.denza.vehiclecontrol.probe`, version 0.3.8 / code 12, Android 13+.
Native Java Views and a Java protocol controller. No WebView, JavaScript, Python,
Chaquopy, HTTP server or laptop dependency. This remains an opt-in experiment.

## Build and update

```sh
python3 experiments/vehicle-control-probe/prepare_voice_model.py
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew -Pexperiments :vehicle-control-probe:testDebugUnitTest \
  :vehicle-control-probe:assembleDebug :vehicle-control-probe:lintDebug
```

Output: `build/outputs/apk/debug/vehicle-control-probe-debug.apk` in this module.
Application ID, default debug signing and private `no_backup/adb_auth_key_v1`
format/path are unchanged from 0.2.1; update with `adb install -r`, never uninstall
or clear data. The existing authorized app identity and STOP journal survive.
The owner approved the app identity on 0.2.1. Native 0.3.0 was installed over it
on N9: installed APK hash matched, the key was preserved and real GET/climate UI
worked. Physical seat/HVAC acceptance remains pending. Polling flicker reported in
0.3.0 is fixed in the installed 0.3.1: live readings and enabled controls persist
across updates. The authorized key remains unchanged.

Host remote and APK share the working branch `codex/vehicle-control-remote`.

All protocol properties, windows and firmware hashes come from the single
`tools/vehicle_controls.json` bundled as an asset. Host tools read the same catalog.
The Java engine does not load host Python or maintain another FID list.

## Voice over the whole remote

The voice pipeline now covers every control of this remote except seat movement, which
needs a held button: windows (open/close/stop and an open percentage), electric doors,
central locks, both roof shades, all five climate zones (power, temperature 17..33 °C, fan
0..7, AUTO, airflow direction), A/C, recirculation, both defrosters, heating and ventilation
of the six seats, massage on four seats, headlights (off/auto/low), matrix light, DRL, rear
fog lamp and the logo light. Not yet built on the car; nothing was sent to a vehicle.

`VoiceParser` reads a whole utterance as clauses: a connector followed by its own verb opens
a new command, without a verb it adds an object to the same command. The verb may follow its
object («заднее окно приоткрыть на пятьдесят процентов»). Units decide where a number goes:
degrees only to temperature, percent only to windows, a bare 17..33 only to temperature.
A singular window or door with a row but no side takes the side of the previous
window or door in the same utterance («окно слева …, а заднее окно …» → rear left); otherwise
it is refused as before. Defaults: a temperature, fan, AUTO or airflow without a zone is the
driver's; heating, ventilation or massage without a seat is the driver's; «включи подогрев»
is level 2; «приоткрыть окно» is 20 %. Relative steps («потеплее», «вентилятор посильнее»,
«подогрев послабее») read a fresh state and stay in range; an unknown reading refuses only
that target. Unknown words, negation, conflicting positions, values out of range and objects
no verb applies to still reject the whole utterance before transport.

Ambiguous or contradictory speech rejects the whole utterance; the parser never guesses
toward a physical action that was not clearly asked for:

- *Implied setting.* Only a later object of a movement verb takes its value as a setting
  («закрой окна и климат на двадцать три» closes the windows and sets 23 °C). The verb's own
  object and other verbs never do: «открой климат на двадцать», «выключи свет на авто»,
  «закрой вентилятор на три» are refused. A verb that applies never turns into setting
  («закрой окна на пятьдесят» is refused).
- *Numbers.* A window position needs «на»/«до», «процентов» or a word such as «наполовину»;
  a number right before an object counts it («открой два окна» is refused). Level words
  («тройку», «третий») set only fan, heating/ventilation or massage levels. Roles and adverbs
  without a noun or a movement verb never name a window: «поставь пассажиру сто» is refused,
  «поставь пассажиру двадцать четыре» is a temperature. «до N» is always the absolute value N,
  also after «убавь» or «потеплее»; relative words take a level step («вентилятор посильнее на
  два» → +2).
- *Sharing within one command.* Positions said once with adverbs, roles or rows (never gendered
  adjectives), on the first or last object and on no other, also place the other objects that
  can take them («выключи климат и подогрев сзади», «открой окна и двери слева»; «закрой окна и
  левую дверь» still closes all four windows). A single value on the first or last object goes
  to the others whose kind takes it — degrees to a temperature, a level to fan, heating,
  ventilation or massage, a percentage to a window — unless they would act on its own targets
  («включи подогрев и вентиляцию на тройку»; «открой окна и левое на двадцать» → left 20 %,
  the others open).
- *Conflicts.* Two different actions for one target within one command are refused («сделай
  двадцать два и потеплее», «включи режим рециркуляции и свежий воздух»), except airflow
  directions of one zone, which combine («обдув в ноги и в лицо» → face_feet; the rear zones
  only face/feet), and a member named next to its group. Across commands the last action wins.
- *Also refused:* bare «противотуманки»/«противотуманные фары» (front fog lamps are not in the
  catalog; «задние противотуманки» works), a program or AUTO without a noun («волна», «авто»),
  one seat of a row without a side («подогрев заднего сиденья»; «задних сидений» is both).
  «включи климат сзади на двадцать три» switches on the named zones, not `climate.power`.

Every phrase the 0.3.4 window/door grammar accepted keeps its exact plan: all 11 366 were
replayed against the old parser once; `src/sharedTest/resources/voice-window-door-0.3.4.tsv`
keeps the 5 524 that use «и», «потом» or «and then» (the other connectors are the same lexicon
class) and `VoiceWholeCarTest` replays them. Two former refusals are now commands on purpose: a verb-first second
command without «и» («открой левое окно закрой правое») and «запри двери» (lock). «открой
замки»/«открой машину» stay refused: unlocking needs «разблокируй».

`VoiceGrammar` generates 11 817 recognizer phrases (228 words). Vosk builds a bigram model
from them, so they cover word pairs, not every sentence. A JVM test reads the model's own
vocabulary from `graph/Gr.fst` and fails on any grammar word the model does not know (the old
grammar's «еще», «and», «then» were silently dropped by Vosk; the grammar now spells «ещё»).
«приоткрой», «рециркуляцию», «отопри» are not in the model, so the grammar uses
«приоткрыть», «режим рециркуляции», «разблокируй».

Window percentage is new in the controller: the stock MyCar `WindowManager.setWindowPercent`
writes `BODYWORK_*_WINDOW_TARGET_POSITION_SET` (device 1001, FIDs `0x4C119010/18/20/28` for
LF/LR/RF/RR) = N and 300 ms later 255 («no target»). The catalog carries these as
`window_targets`; `fl:50%` passes the same fresh window guards, journals the target write and
always sends the reset, also after an unknown result, never retrying the target. 0 and 100
use the existing close/open movement. An accepted target whose reset is refused or unknown is
an `uncertain` outcome and stops the rest of the plan. The reset is journalled
(`phase: reset_attempt`) before its write and its result goes into the result row; a percent
attempt without a later accepted reset latches the window (`pendingResets`, rebuilt from the
journal on start, `pending_window_resets` in the snapshot). Any later command for that window,
STOP included, first sends the 255 reset alone, journalled, and is refused unless the reset is
accepted; the old target is never resent. All relative steps of one plan are resolved from one
snapshot taken before the first write, so «потеплее везде» does not compound under climate
sync; a fan step never goes below 1 and never weakens a fan that is off (only «выключи
вентилятор» writes 0). **Firmware only:** the percent write has not been sent
to the car; the first test is one window, in P, with the owner at the glass.

### One batch per plan, executed by default

On the car the journal showed voice «закрой все двери» writing the doors 1.95–2.08 s apart: every
command opened a new ADB connection and read the whole catalog (217 `service call`s) before its one
write. `NativeController.actAll` now runs a plan with one lock (waiting up to 4 s for a remote poll),
one connection, one read of only the plan's guard properties (`guardKeys`; four windows: 14 calls),
relative steps resolved from that read, and one shell script with every write (window percentages:
targets, 300 ms, resets). Every write and result is journaled; a guard refusal drops only its
target, HAL replies are parsed per write, a transport failure leaves every sent write uncertain,
nothing is retried. Window STOP is sent even when clearing an earlier target fails. The hands-free
turn executes plans by default (switch in the voice settings); an endpoint of only [unk] keeps the
turn listening.

## Minimal offline voice pipeline

0.3.4 separates full parsing from the ASR confidence decision. The owner's exact
«открой правое окно и левую дверь» parses to `fr:open` and `door.fl:open`.
In0.3.3, any content-word confidence below0.85 produced the same generic error
as an unsupported phrase; the real scores of the reported attempt were not saved.
Low confidence remains compatible with the observed refusal; the failing word
and its actual score cannot be recovered from that old build.

A valid complete plan with low scores now shows the recognized phrase, target
actions and exact weak-word scores, and offers **Выполнить план**. It sends
nothing until the owner taps that button. A new recording, manual control,
leaving/focus loss or rebuilding the screen cancels the pending plan. A complete
high-confidence result still executes directly. Unknown/negated/extra content,
missing/invalid scores or disagreement between words and text never offer a plan.
The ASR grammar now covers every alias as both operands of every connector,
including «и левую» and the exact reported phrase.

Only the latest raw recognition JSON (text/word scores) and latest decision are
retained in private `files/voice/last-result.json` and `last-decision.json`, each
overwritten on the next result/decision. No audio is retained. These are diagnostic
evidence, not an execution queue; pending confirmation is never restored on restart.
Installed0.3.4/code8 SHA256 matches
`59bd6f5860cdb25212f292d41ee52a891f2d824deb63ce0b252aaa6f93f84c0a`.
52 JVM tests, assemble/lint passed; the earlier refused command was not replayed.
Owner retry and confirmation UI acceptance remain pending.

0.3.3 adds electric-door open/close/stop through the existing `door.fl/fr/rl/rr`
controller actions. «Открой левую дверь», «закрой правую заднюю дверь»,
«открой дверь сзади слева», «закрой все двери» select physical electric-door
movement; they do not select central locking. Bare feminine «левую/правую» means
the corresponding front door; neuter «левое/правое» retains its window meaning.
Every non-STOP action reads fresh P, door configuration, initialization, position,
obstacle, drive protection and lock thermal guards through the unchanged catalog.

The full utterance compiles before any command is sent: «закрой окна и открой
правую дверь», «открой левое окно и правую дверь», «открой левое и правое окно,
потом закрой заднюю правую дверь». Shared verbs, «и потом»/«затем»/«ещё», polite
fillers and corrections compose into one final plan. Last action per named
window/door wins before writes; unknown/unsupported/negative content rejects
the entire plan. Each target reports its own result. A definite guard/HAL refusal
does not stop independent targets; cancellation, unknown write outcome or an
unclassified failure stops the remainder without retrying.
Installed0.3.3/code7 SHA256 was verified as
`2d83c50de0ff7f2acfe93fd2afb230f79ea16808d66a861d60613cb0175ceada`;
the original key, microphone grant and voice key filter remain present.
46 JVM tests, assemble/lint passed. No actuator commands were sent by the agent;
owner physical voice acceptance remains pending.

0.3.2/code6 is based on the latest native remote `9deda371`, retaining the stable
polling/attached-View fixes `6af5c5b1` and neutral resume feedback `26b86dd5`.
The initial voice APK based on `1d4cc415` regressed those fixes and is superseded.
Installed on N9 as 0.3.2/code6: exact APK SHA256
`4a5d66bf345f2f5a841d772ffb16a737d9ed135d8c8c5061205d3ca82446fdd1`;
microphone granted, key filter enabled, existing private ADB key retained.
32 JVM tests, assemble and lint passed. Owner voice acceptance remains pending.


0.3.2 adds voice to this same native APK, using its existing controller, catalog,
ADB identity and action journal. Press **Голос**, grant microphone permission
once, press again and wait for **Говорите**. A second press cancels. First use unpacks
and loads the model; later phrases reuse it. There is no always-on microphone.

Pipeline: button → exclusive transient audio focus → cabin `MIC`, PCM16 mono16kHz
→ Vosk small RU0.22 streaming recognition/endpoint → full-coverage command parser
→ named window/door actions → existing fresh P/profile/boot/hash/control guards →
single HAL writes and a displayed per-window result. A HAL1 response means accepted,
not confirmed movement; the next GET supplies the observed percentage.

Commands include «открой левое окно», «закрой правое окно», «открой левое заднее
окно», «закрой окно сзади справа», «открой левое и правое окно», «останови окна».
Singular left/right without a row means the corresponding **front** window;
plural «левые окна»/«правые окна» means both on that side. Driver/passenger,
front/rear/all groups, shared verbs and composed commands use the same alias
table as the ASR grammar. Unsupported words, negation and incomplete phrases reject
the entire plan (percentages are accepted since the whole-remote voice section above). Later contradictory actions for one window replace
earlier ones before execution. No fuzzy direction matching is used.

Vosk `[unk]` and explicit negative grammar examples help reject unsupported speech.
Content-word confidence0.85 (conjunctions/polite filler0.5) selects direct execution
or explicit plan confirmation; these are heuristics, not calibrated probabilities.
Only a completed endpoint can
execute. Partial results and the eight-second timeout never execute; no timeout or
refusal causes a retry. A definite refusal is reported per target; unknown results
or leaving the screen stop the remainder. Audio stays in memory, is never saved or sent
over the network. `logcat -s DenzaVoice` records loading/capture time, levels and text.

For the steering-wheel voice key320, enable **Голос пульта · кнопка руля** in system
accessibility settings (long-press **Голос** opens those settings). The isolated
service uses the key-filter method already proven by Denza Apps. It takes the
complete press only while this remote has focus; outside the remote it passes it.
No Denza Apps APK or Alice settings need changing. Losing focus/pause cancels
capture; stale callbacks cannot start a vehicle action. This prototype deliberately
keeps the remote visible, so hidden-app microphone access remains unverified.

The [official model](https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip)
is prepared by `prepare_voice_model.py` into ignored `build/voice-input/` and bundled
as a generated asset. It is not a source-controlled binary or runtime download.
Both preparation and Gradle require SHA256
`961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4`.
The APK verifies the ZIP before installing it under private `files/voice-model/`.
Dependencies: [Vosk Android0.3.75](https://github.com/alphacep/vosk-android-demo/blob/master/app/build.gradle)
and JNA5.18.1. This is the architecture's fast RU/window prototype: Vosk's own
endpoint substitutes for Silero, and English ASR, Alice fallback, TTS, background
capture and vehicle functions beyond windows/electric doors are not implemented here.

On-car acceptance: with the owner present, P and unobstructed glass, first note
percentages; say «открой левое окно», compare the displayed text/FL HAL response
and actual glass/GET, then «закрой левое окно» and compare the return. Repeat for
FR/RL/RR; exercise «останови …» during a single movement. Falsification: wrong
target, a command without a complete accepted phrase, silenced MIC, or accepted
HAL without observed movement. Do not repeat uncertain commands. Physical voice
acceptance is pending until the owner performs this sequence.


## Access

Existing 0.2.1 users open directly with the same key. On a fresh installation,
**Разрешить доступ ADB** creates the app's own RSA2048 key, shows its fingerprint,
then submits one explicit local request. Approve the matching system dialog,
then press **Я подтвердил — проверить** (PASSIVE `shell true`). A persistent latch
prevents another public-key submission after Activity/process restart.
**Разрешить новую попытку запроса** only rearms the app latch. It does not submit,
replace keys, clear the system queue, restart adbd or alter the laptop tunnel.
The native controller always uses `LocalAdbClient.loopbackOnly`, PASSIVE,
`127.0.0.1:5555` on the head unit; no other device address is attempted.

Advanced import remains in **Доступ ADB** and preserves the journal. It accepts an
existing RSA2048 `adb_auth_key_v1` binary through Android's document picker, validates
the pair and writes an AtomicFile in private no_backup storage. No key is a build
input or an APK resource. Optional `export_key.py` converts an explicitly selected
existing PEM without generating/replacing a key or contacting the car. Do not import
a laptop's multi-device identity by default. On this N9, no other owner app with an
identity was present; the normal one-shot request is the working provisioning path.

## Native control contract

Sections cover windows, two roof shades, lights, central locking/four electric doors,
six seats with every catalogued axis/heat/ventilation option, massage on four seats,
and the verified climate catalog. Unknown readings/configuration disable the relevant
actions with a reason. STOP bypasses P/calibration/thermal guards, retaining boot identity.
Other writes use fresh P and per-control guards. Firmware/profile/boot checks and
all three pinned hashes remain mandatory. GETint=5, GETfloat=7; SET=6, scalar int,
null callback. Accepted is one raw HAL word 1; an accepted call does not prove motion.

The process owns one controller and one operation lock. Activity recreation reuses
it without waiting for a read or losing pending STOP. All ADB work runs off the UI
thread. Reads poll every 4s only while the remote is foreground; no service or boot
receiver runs. Polling updates attached Views in place without a busy banner or
disabling controls. One explicit user intent may wait for the current read, then
checks fresh guards; additional writes are not queued. A released hold waiting for
a read cannot start later. Paused/destroyed screens reject new actions and ignore
stale callbacks; an in-flight transaction completes. Failed reads retain visibly
stale values and disable new writes while keeping STOP available.

### Seats: hold rather than tap pulses

Press and hold a direction, release to stop. All axes use the same control semantics.
The display says **Удерживайте · до5с**; after the deadline a fresh press is required.
ACTION_UP/CANCEL, moving outside the button, pause/back, seat/section switch and
manual STOP all signal release once through a separate LocalAdbClient connection.
Periodic reads and the held shell never occupy this release connection.

After fresh guards and an fsynced attempt, an Android shell emits directional 1/2,
refreshes the held intent every 300ms only after the preceding exact HAL1, and sends
STOP 0 on release/deadline. There are no intermediate STOP pulses. Refused/malformed
START or refresh exits immediately to STOP; unknown commands are never replayed.
A 4.8s Android watchdog and 100ms polling give a nominal five-second limit.
Synchronous service-call stalls or adbd/kernel failure can delay STOP; this is not
an absolute real-time actuator guarantee.
The release token is unpredictable per hold, fixed-prefix shell-owned; an early
release flag is never deleted before the START check. UP during preflight cancels
before START. A release during journal fsync selects STOP-only instead of START.

The EXIT/HUP/INT/TERM/PIPE trap executes STOP and captures its result BEFORE printing
STOP acknowledgement, so a broken output pipe cannot skip the STOP command.
The attempt/result journal is fsynced. Only a known STOP acknowledgement, durably
logged, clears the latch. A successful release-flag write is not a STOP acknowledgement.
Missing STOP latches across process death and updates; recovery STOP stays available.
Never clear application data to bypass it. Logs: private `files/actions/actions.jsonl`.

The stock hold path refreshes directional events every 300ms without an intervening
STOP; this is source evidence, not proof that the ECU itself requires a keepalive.
Sources and exact on-car acceptance limits: `docs/window-control-findings.md`.
Finish stock rest/camping before manual control; raw scene GET65535 is not an interlock.

## Validation

Native JVM tests cover strict scalar parsers, command allowlisting/dispatch, fresh
P and per-control guards, unknown float/config states, hash/boot identity, journal
failure/replay, no overlapping action queue, early release and independent release
connection. The 34 host tests still pass against the shared catalog.
ARM64 Android 35 tests exercise native Views/seat selector and pause-release, actual
Android shell early release/refused/malformed/no-refresh, held refresh/release,
deadline and broken-output STOP execution. Measured watchdog run: 4950 ms.
Native 0.3.1 was installed by the root session. Three read-only live UI snapshots
across polling cycles retained data and enabled controls without the busy banner.
Nine Android tests include View/focus/selector stability, one intent during a read,
early/late hold release, stepper boundaries, recovery insertion and stale lifecycle.
Final code commit `26b86dd5` changes only resume copy after those tests; 17 JVM tests,
build and lint passed again. Installed SHA-256:
`2cac520d42bf67d3c6efe117af47f30ea8c1a1e6d199e65314b408b09c5ba453`.
The implementing subagent did not access the car.

### Climate

Thirty catalogued controls: five common functions plus five controls for each of
five zones (driver/passenger, middle-left/right, rear third row). The selector
shows the common functions and one zone. Temperature17..33°C/step1, fan0..7,
power/AUTO and supported airflow directions use explicit catalog options.
AUTO0=on/1=off; fanGET0 uses SET8. Unit/config/state guards fail closed.
The only allowed negative read sentinel is the observed -10011 for HVAC precision,
matching the stock whole-degree fallback; other read errors remain unknown.
Captured real HVAC parcels run through the full native read pipeline in tests,
not just a synthetic state builder. No HVAC SET has been exercised on the car.

## Bounded 10-channel microphone diagnostic (0.3.5)

Shell-only `MicrophoneProbeActivity` starts a `microphone` foreground service from
a visible window, then finishes after seven seconds. The service captures at the
ordinary app UID: `VOICE_RECOGNITION`, PCM16, 16000 Hz, channel index mask `0x3ff`.
It stops after 30 seconds of capture, with a 45-second overall watchdog. It never
constructs VoiceInput/ASR/NativeController and sends no vehicle commands. It takes
no audio focus and stores no PCM. The existing authorized key is used passively
over loopback only to read `dumpsys audio`, at intervals of at least two seconds.

Preflight: update with `adb install -r`, preserving app data and the ADB key;
confirm the key hash, microphone runtime grant and stock voice APK identities.
No accessibility setting, appop, audio route or vendor callback is changed.
Start once from a quiet scene with no active remote hold or voice plan:

```sh
adb -s 127.0.0.1:5555 shell am start -n dev.denza.vehiclecontrol.probe/.MicrophoneProbeActivity --es run tench-01
```

Use a fresh run identifier each time. Emergency stop only the diagnostic:

```sh
adb -s 127.0.0.1:5555 shell am stopservice -n dev.denza.vehiclecontrol.probe/.MicrophoneProbeService
```

Evidence: private `files/microphone-probe/<run>/events.jsonl` and `summary.json`.
Levels, nonzero counts, per-channel fingerprints, actual recording configuration,
AudioTimestamp and ordinary UID are recorded. No speech text or raw audio is kept.
Read evidence with `adb shell run-as dev.denza.vehiclecontrol.probe cat <path>`.

Hypothesis: ten interleaved sample slots keep arriving after the Activity closes,
and the stock UID1000 recording keeps its session, format and `silenced:false`.
A missing/changed/silenced stock recording, lost app capture or ADB failure stops
the diagnostic without retry. This is verified by parsing the current recording
section, never by historical log entries or the misleading shared-UID package name.
External before/during/after AudioFlinger dumps additionally check the actual
10-channel index mask, client tracks and advancing stock frames.

Expected falsification: failure to create/read ten channels; app silence in the
background; stock capture disappearing, changing or being silenced; engine restart.
A successful transport/format test alone does not prove all-seat acoustic quality
or the assistant's response; the owner separately calls the stock wake word while
capture runs in the background, without a vehicle command.

### Live result, N9, 2026-10-16

Installed 0.3.5 / code 9 SHA256:
`8f0d2a5f44d67fcb47c11183bf4f8d9c37e80a92510a39b66f6d2ed89718678b`.
55 JVM tests, assemble and lint passed. This is the same APK used in all three
live runs. Source diagnostics do not invoke the existing voice/action paths.

Ordinary UID 10132 obtained PCM16 / 16000 Hz / 10-channel index mask 0x3ff in three
30-second captures. Activity intentionally finished at 7 s; Launcher was resumed
while the microphone FGS kept reading. Per-channel sample counts were 479680,
480000 and 480000 in runs tench-01/02/03. Each run recorded 29 own unsilenced
configuration samples and 15 unchanged, unsilenced stock-recording checks.

AudioFlinger showed both client tracks active on the same ten-channel input:
stock PID 4398 / session 161 and app PID 25316 / sessions 505, 513, 521. Both masks 800003FF,
rate 16000, source 6, Sil=n; both server frame counters advanced. After capture
the app track disappeared while the original stock track remained.

The first two wake attempts were inconclusive because pronunciation was also
unstable without capture. In tench-03 the owner called the stock Chinese wake
phrase and confirmed its response. sys.voice.windowshow and sys.bydvoice.vr.state
were both 1 at device epoch 1792147710..1792147711, within the capture. The app
and stock recordings stayed unsilenced through that response.

All ten slots had equal sample counts. Six slots 0, 1, 4, 5, 8, 9 carried distinct
ambient signals; pairs 2=3 and 6=7 were near-constant in quiet and had larger peaks
in the responding-assistant run. This is compatible with the firmware 6mic/4ref
layout, not a measured seat/channel map or ten independent microphones.

ADB key and action journal SHA256 remained unchanged. Stock PIDs 4398 / 4873 did
not restart, crash log gained no entries. No actuator command was sent. The
diagnostic stopped automatically and the normal remote was restored. The
foreground-service background interval was about 23 s; sleep, phone calls,
long runs and acoustic quality from every seat are not accepted by this test.

Evidence in the primary checkout: captures/voice-activation-20261016/probe-035/
(build.json, analysis.json, three summaries/event streams, audio/AudioFlinger,
Activity state, voice-window timeline and before/after identity/crash checks).
The primary checkout also has docs/voice-assistant-activation.md with the
firmware research and exact live boundaries.

## «Привет, Денза» and speaker seat (0.3.8)

### Reliable call detection

Replaying the 14 recorded car decodes: the previous detector accepted 2 of 6 real calls; the
unrestricted pass spelled the name «дэнс», «за», «бензо» and only exact strings were accepted.
Now the narrow pass decides (call at the start of the utterance, scores ≥ 0.75, up to 0.9 s after
«привет», 0.45–3 s) and the unrestricted pass only vetoes: it must hear «привет» in the same place,
and the words in the name's place, taken together, must not be a confident (≥ 0.9) word that sounds
unlike «денза» (sound-key distance > 0.3; «Денис», «где заправка» veto, «дэнс», «за», «бензо» do not).
Words after the name are allowed: one breath «Привет, Денза, открой окно» is a call plus a command.
A lone «привет» waits 1.5 s for the name in the next utterance. All six microphone candidates are kept
per utterance and the one with the most voiced energy is decoded; pre-roll is 0.4 s; an 8 s ring and
the words after the name feed the command recognizer first, so speech started while the call was
being recognised is kept. The narrow pass runs first, the newest utterance replaces a waiting one.
Replay result: 6 of 6 calls accepted, 8 other utterances rejected. Not yet run on the car.


Open **Привет, Денза** in the remote header, then **Слушать**. This is a
separate observation Activity: no `NativeRuntime`, command parser or vehicle
writes. A microphone foreground service keeps listening after **Оставить в фоне**;
the notification opens the screen or stops it. Sessions last at most ten minutes,
without boot/startup auto-restart. Back/return to remote stops the probe. The
regular push-to-talk command feature remains a separate mode.

The screen distinguishes incoming levels from a speech capture, decoding and
wake acceptance. It shows six live level bars, **Слышу речь**, **Распознаю**,
always the unrestricted **Услышано** transcript, captured/decoded counts and a
specific refusal (different phrase, weak word score or timing). This addresses
the owner's report that silent filtering made it impossible to tell if speech
was captured. Full ASR is now run for every captured utterance; only the latest
metadata is kept. A busy decoder says that the preceding utterance is still
being processed instead of silently dropping the observation.

Capture is the proven source6 / PCM16 / 16000Hz / index0x3ff, ordinary app UID,
without audio focus. Slots0,1,4,5,8,9 are observed microphone candidates;
2,3,6,7 are excluded reference candidates. This is not a physical seat order.
A one-second quiet noise baseline gates speech, keeps200ms pre-roll, fixes the
strongest mic at speech onset and bounds mono PCM to five seconds in memory.
Gain is normalized without clipping, capped at8x. Own recording config is checked
every second, stock recording via existing local ADB every5s. Silencing/loss or
stock session/format change stops the probe. The strict guard can also stop on
legitimate stock audio transitions. No PCM is stored or uploaded.

The pinned small Vosk RU0.22 model has no `денза` token, but has `ден`, `дэн`,
`за`. Speak normal **Привет, Денза**. The internal phonetic grammar needs all
three words at>=0.75; the unrestricted model must independently return the full
phrase (`ден`, `дэн` or `день` + `за`) at>=0.5. Two-pass matching rejects the
reproduced narrow-grammar false matches **Привет, Денис** and **Привет, где
заправка**. The name remains difficult for the generic model. On the owner's
accepted0.3.6 call its full-model name scores were0.519391, close to0.5. Thus an
unstable result is not evidence that the microphones are unavailable or that
the owner pronounces it wrong. The screen now exposes the actual text/reason.

**Calibration is independent of wake/ASR acceptance.** Sitting in a chosen seat,
tap its button and speak the phrase three times, with three-second pauses.
The active seat gets up to60s. A gated utterance with0.3..4s of voiced frames
and a start after selection contributes one normalized six-mic energy template;
three samples replace that seat's profile and show3/3. Long/short or preceding
utterances show an explicit calibration error. Old partial one-sample0.3.6
profiles survive updates but do not count as a completed seat. Other speech or
noise may be collected: this is a controlled calibration, not a voice-identity
classifier. Keep the cabin quiet and speak from the selected place.

Order: driver, front passenger, row2left, row2right, row3left, row3right. After
all six have3/3, repeat from each place without selecting it. Cosine similarity
>=0.94 with best/second margin>=0.015 yields **Предположительно:** and the seat;
otherwise **Место не определено**. These thresholds are experimental; this
level-template method is not native DoA/beamforming/AEC and has no validated
cross-seat/noise guarantee. Until6/6 the wake mark shows calibration progress.

Private `files/wake-probe/`: `seat-profiles.json`, overwritten latest
`last-recognition.json`, `last-wake.json`, `last-state.json`. Metadata includes
text, word scores, energy values, selected mic and decode duration. No raw audio.
`VoiceModelFiles` shares the original SHA-pinned, bounded ZIP installer with
push-to-talk; asset/install path and its cancellation behavior remain the same.

Validation:60 JVM tests, assembleDebug and lintDebug pass. The actual synthetic
result corpus (Milena ru_RU through Java framing/gate/gain and hostVosk) accepts
two positive variants and rejects seven negatives. `wake-synthetic.json` keeps
results, not audio; it does not measure cabin false activations per hour.
Owner confirmed a visible wake mark in0.3.6; both decoders matched
`привет дэн за` in332ms. The first driver3/3 calibration in0.3.8 was owner-confirmed;
All six seats now have3/3 samples, verified in private JSON and owner-confirmed. Owner also confirmed automatic driver detection. Full other-seat accuracy remains open.0.3.7 driver-only was built but
never installed; the owner then chose all six seats and clear diagnostics.

Current installed0.3.8 SHA256: `ae2bc7fd0b90e218f001a1ab147ff0c88161939477be5417234decbbc1cd364e`.
Exact hash, existing key, action journal and prior seat template were checked
after `install -r`. Stock PIDs4398/4873 remained unchanged. Capture evidence is
`captures/voice-activation-20261016/probe-038/` in the primary local checkout.
Keep owner acceptance separate from installation/capture proof.

ASR diagnostics also enter the bounded Android logcat ring; app-private latest-result files are overwritten, raw PCM is never logged.

Final0.3.8 audit: all18templates persisted; APK/key/action journal exact hashes
unchanged, stock PIDs unchanged, no new crash entries. Background capture was
verified with CarSettings resumed: probeFGS notification73, PID3367/session537
and stocksession161 both active10ch, Sil=n. Owner reported automatic driver;
owner clarified that other places were tested afterward, explaining the different
last-saved seat (14:14:29, row2right). Later individual utterances are not fully
labelled, so full per-seat accuracy remains open.
