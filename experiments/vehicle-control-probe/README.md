# Denza vehicle-control APK prototype

Standalone `dev.denza.vehiclecontrol.probe`, version 0.2.1. Opt-in experiment;
no product manifest additions. Uses the exact host controller/catalog/page copied by Gradle
from `tools/vehicle_control*` and `vehicle_windows.py`, with an Android shell adapter.
Chaquopy 17.0 / Python 3.13; Android 13+ (API33), arm64-v8a and x86_64.
The N9 read-only check on 2026-10-16 returned API33 and arm64-v8a.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew -Pexperiments :vehicle-control-probe:assembleDebug
```

Output: `build/outputs/apk/debug/vehicle-control-probe-debug.apk` in this module.
The APK contains no private key, no HTTP server, and no vendor/system permission.
The WebView displays only bundled HTML; navigation, file/content access and network loads
are disabled. Its one bridge method accepts only the controller's named actions; arbitrary
shell strings/FIDs/durations are rejected by the Python allowlist. Binder calls are scalar,
GET int=5 / float=7 and SET=6 with null callback; accepted SET is one HAL word=1.

## Connect after the owner installs

The owner-requested installation and first launch on the N9 passed on 2026-10-16;
the installed 0.1.0 APK hash matched its build and reached the key import screen.
Version 0.2.0 was then installed and its system dialog approved by the owner;
Activity recreation exposed the lifecycle bug corrected in 0.2.1. Version 0.2.1
was installed with the same private identity; its installed hash matched the build.
Local ADB, real vehicle GETs and the access-panel → passive check → controller cycle
passed on the API33 car. Full physical control acceptance remains open.

1. Install the APK using the existing trusted connection, for example
   `adb -s 127.0.0.1:5555 install -r <apk>`, when the owner schedules installation.
2. Launch «Denza · Пульт» and press **Разрешить доступ ADB**. That explicit press
   creates this app's own RSA2048 identity in private `no_backup` storage, displays its
   fingerprint, then submits exactly one authorization request to local adbd. Compare the
   fingerprint in the system dialog, approve it with persistent trust if desired, then
   press **Я подтвердил — проверить**. That check is PASSIVE (`shell true`): no public
   key submission. After success the controller opens; no laptop is needed.
   A persistent latch disables repeat submissions across Activity/process restarts.
   **Разрешить новую попытку запроса** only clears the app latch; it does not submit
   a key, clear the system queue, or replace any identity. There are no automatic retries,
   boot receivers, service restarts or programmatic approval calls. Reopen **Доступ ADB**
   from the controller if access is lost. The normal path needs no imported laptop key.
3. Optional advanced import accepts the existing binary `adb_auth_key_v1` from an already trusted
   on-device app's private `no_backup` directory, exported through an authorized owner
   workflow, for example the debug app export (only if the existing app is debuggable):
   `adb -s 127.0.0.1:5555 exec-out run-as dev.denza.apps cat no_backup/adb_auth_key_v1 > /private/owner-only/vehicle-adb-key.bin`
   with an owner-only directory and umask077. This reads the existing key and does not
   modify it. Do not run this example without the owner's explicit provisioning request;
   it was not run for this build. App data cannot be read by an ordinary different APK; this is an explicit
   provisioning step, not a claim that cross-app access is available. It must be a key
   that THIS head unit currently trusts. Merely having a trusted laptop tunnel is insufficient.
4. If the owner deliberately selects an existing PEM identity, the optional host helper
   `python3 experiments/vehicle-control-probe/export_key.py <existing.pem> <private-output>`
   converts it to the same binary format. It does not generate/replace the key or contact
   the car. Do not use the laptop's multi-device identity by default; sharing that identity
   extends all of its trust to the app. No identity is a build input or APK resource.
5. Transfer the private file through the owner's chosen secure workflow, select it once,
   then delete the transfer copy. Import checks the RSA key pair and writes an AtomicFile
   in the app's private `no_backup/adb_auth_key_v1`; backup is disabled. Future launches
   use that file. There is no cloud upload or key printed in logs. The «Доступ ADB» panel permits another import without clearing the STOP journal.
6. The controller connects only to the head unit's `127.0.0.1:5555`, PASSIVE authorization.
   An unavailable adbd or untrusted identity blocks the UI. Only the explicit access
   button can submit a key; the controller always uses PASSIVE mode. It does not repair ADB. Once provisioned, no laptop or host server is needed.

Every launch verifies CANFD/N9 identity, boot ID and three firmware hashes. STOP still
checks boot identity but bypasses P/calibration/thermal gates. Other writes take fresh
state and require P and the same per-control guards as the host prototype. Each click
is serialized without a queue or automatic retry. Seat steps run Android-side shell
`trap` + `sleep 0.3` + STOP0; fsync of the attempt precedes START, and journal replay
keeps the pending STOP latch across process death. The shell trap is not a guarantee
against adbd/kernel/device power loss. No background service or boot receiver is installed.

The owner must finish stock rest/camping before manual control. The raw scene GET65535
cannot provide an automatic scene interlock. Accepted commands are not proof of physical
motion. All controls still need controlled vehicle acceptance, especially STOP.

## Validation

2026-10-16: 34 host controller tests, 54 bridge JVM tests, debug assemble and lint passed.
An ARM64 Android35 emulator instrumentation smoke test runs the packaged Python3.13,
loads the full HTML in a real WebView and receives its offline error through the real bridge, checks float GET7/scalar SET parsing and seat trap, and confirms
an offline transport fails closed. This proves Android runtime loading, not API33 or car
actuator acceptance. A later owner-requested install and first launch passed on the
API33 car, initially reaching the key import screen. Version 0.2.0 obtained its own
owner-approved identity; 0.2.1 then passed live local ADB/GETs and repeat entry.
The owner's FL window open/close clicks were accepted in the APK journal; the
assistant did not send actuator commands. No key import or secret packaging occurred. See
[the installation evidence](../../docs/window-control-findings.md#apk-пульта).

Stop: close the Activity. The native bridge rejects new requests while paused; an already
started command finishes, including its seat STOP. No new reads/writes start from paused polling.
Logs: private `files/actions/actions.jsonl`, accessible with owner-authorized `run-as`
on this debug package. Do not clear the data to bypass an unknown seat STOP.

## Lifecycle correction 0.2.1

After the owner approved the 0.2.0 system dialog, Activity recreation raced an old
read and the adapter raised a fatal busy screen. Version 0.2.1 reuses the same
process controller/client immediately for the same key and journal directory.
It does not reset verification, transport, or the pending STOP latch on recreation.
An explicitly changed key publishes a pending binding; only the next request applies
it under the shared operation lock. An in-flight command retains its client through
STOP. There is no UI-thread wait and no second action queue.
Two targeted fake-transport tests cover recreation during a running read and deferred
identity replacement with preserved STOP latch. Installed 0.2.1 passed the access
panel → passive check → controller cycle on N9/API33 with real window readings.
Its SHA-256 is `a0e6f2ca3f0c6b03274ae0bcb0048f4c0fcb3294ddd1071befca89b1a6fe2a1a`.
