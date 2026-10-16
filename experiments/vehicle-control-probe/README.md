# Native Denza vehicle-control prototype

Standalone `dev.denza.vehiclecontrol.probe`, version 0.3.0 / code 4, Android 13+.
Native Java Views and a Java protocol controller. No WebView, JavaScript, Python,
Chaquopy, HTTP server or laptop dependency. This remains an opt-in experiment.

## Build and update

```sh
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
worked. Physical seat/HVAC acceptance remains pending. The owner reported polling
flicker in 0.3.0; that UI defect is being fixed.

Host remote and APK share the working branch `codex/vehicle-control-remote`.

All protocol properties, windows and firmware hashes come from the single
`tools/vehicle_controls.json` bundled as an asset. Host tools read the same catalog.
The Java engine does not load host Python or maintain another FID list.

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
receiver runs. A busy operation never queues another press. Paused/destroyed screens
reject new actions and ignore stale callbacks; an in-flight transaction completes.

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
Native 0.3.0 was installed by the root session; read-only live UI checks passed.
The implementing subagent did not access the car. See the findings document for
the exact installed hash and the owner's polling-flicker report.

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
