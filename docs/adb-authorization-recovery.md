# ADB Authorization Recovery

Status: startup gate and the healthy one-shot path exercised on DiLink 5.1 on 2026-08-18;
deliberately stuck queue recovery is still pending.

This page covers the local ADB identity used by Denza Apps. Transport reachability and Android
ADB authorization are separate: a reachable `adbd` endpoint may still reject the app key, and a
trusted remote tunnel does not prove that the Denza Apps key is trusted.

## Product behaviour

Denza Apps has one canonical ADB identity and one owner for authorization prompts:

- Every application/runtime entry performs one passive startup probe before starting any
  ADB-dependent coordinator. The normal `CHECKING` state is visually silent. If the probe is
  unresolved, the dashboard remains inert behind a blocking overlay and internal callers do not
  retry; only an explicit user action can start another probe or the one-shot request.
- A refused/timeout/no-route endpoint is shown as **ADB недоступен** with the service-only
  instruction. A successful ADB handshake with an untrusted Denza Apps key is shown separately as
  **Подтвердите доступ к ADB**.
- Feature clients use `DenzaLocalAdb` in `PASSIVE` mode. They may sign a challenge with an
  existing key, but never submit that public key to Android's prompt queue.
- The key pair is stored in one atomic file protected by an OS file lock. The old
  `adb_auth` SharedPreferences pair is migrated without changing the identity. This prevents the
  main process and `:weather` process from racing to create different first-install keys.
- The startup overlay exposes the explicit **ADB Rescue** panel. A denied passive check enables
  **Отправить один запрос**; that action submits the public key once and closes the transport
  without retrying. The diagnostics screen retains the same controls for support use.
- A persisted one-shot latch prevents process or activity restarts from rearming the request.
  After submission, only a passive check is automatic. **Разрешить новую попытку** merely clears
  the app latch; it does not submit a key and does not clear Android's system queue.
- No key material, endpoint, or token is included in support diagnostics.

Technical diagnostics are an ordinary **Сервис** tile on the dashboard. They were a seven-tap
gesture on the `Трансляция` card header until v33; a live run found the other half of that
bargain, because a tap that missed the undisclosed target landed on a tile instead, and an odd
number of them switched the mirrors off in silence.

## Explaining the channel, and reaching diagnostics past the gate (v35, 2026-08-26)

Status: built and unit-covered; **not yet run on a car**. The two claims that only the car can
settle are that the seven taps land while the shield is up, and that the label survives the
narrow pane. Neither has a unit test — the module has no Robolectric and no `compose-ui-test`.

The gate covers the dashboard, and the dashboard is where the **Сервис** tile is. Until v35 that
meant the support ring — the product's only channel of truth about what it did — was unreachable
exactly when something was wrong. Two things close that:

- Every blocking state of the gate offers **Что такое ADB**, which opens one window with the same
  two paragraphs for all of them: what the channel is, and that it is opened at the car by someone
  with the equipment. The copy deliberately does not branch. A car whose switch is off and a car
  whose key is untrusted are different problems with the same answer for the owner, and writing
  that answer twice would be two records of one fact.
- Seven taps on that window's title, within three seconds of each other, open the service screen.
  The gesture is safe *here* for the reason it was unsafe on the dashboard: the window has no other
  controls in it, so a tap that is not the seventh has nothing to hit.

The window can be touched at all because it is a `Dialog`, so the platform gives it a window above
the activity, while the gate's shield is a full-screen `clickable` inside the activity's own
window. The recovery panel has reached the owner by that same route since v29.

What *does* differ between the two states is the cause, and that goes on the gate itself, under
the instruction. It carries a classification and never a failure label: a disabled switch is named
outright because it is a reading, an unreadable flag stays silent because absence of evidence is
not evidence of an off switch, and exception names like `ConnectException` stay on the service
screen where they mean something to whoever is reading them.

## Operator flow

1. Open Denza Apps. If no overlay remains, the passive startup probe proved existing trust and no
   authorization request was generated.
2. If **ADB недоступен** is shown, use the service path described on screen; an APK cannot unlock
   the disabled endpoint.
3. If **Подтвердите доступ к ADB** is shown, press **Запросить доступ** once. The compact
   **Восстановление ADB** panel exposes the same one-shot action and status.
4. Approve the Android dialog on the vehicle, then press **Я подтвердил — проверить** or
   **Проверить доступ**.
5. If no dialog appears, do not repeatedly rearm the request. Preserve the pending state and use
   the stuck-queue procedure below when a separately trusted transport is available.

An ordinary APK cannot inspect or drain `libadbd_auth` before its own ADB key is trusted. A second
APK is therefore not a *repair* on its own: it creates another RSA identity and adds another
pending request to the same queue. What it can be is the second asker - see below.

## The second asker (`:adb-rescue-probe`, built and run 2026-08-29)

Status: run on the owner's vehicle the day it was built. It answered the question it was made for
on the first attempt — see "The prompt is never drawn on this car" below.

A vehicle owner reported the state this exists for: Denza Apps shows an authorization instruction,
the owner says debugging is unlocked at the car, and no system dialog is ever drawn. From the
host there is nothing to look at - no ADB means no logcat, no `dumpsys`, no queue. From inside
Denza Apps there is nothing left to spend: it owns one key and one prompt slot by design, and on
that car the slot is already gone.

The probe is a separate application with an ADB identity of its own, so its request is independent
of whatever the product already spent. That independence is the whole instrument, because it turns
an unanswerable question into a two-way test the owner can run alone:

- a prompt appears for the probe's key - the authorization path on that car works, and the problem
  is Denza Apps' own latch or its own key, both of which the product can already clear from its own
  screen;
- no prompt appears for the probe's key either - the path itself is broken, and the queue is the
  first place to look.

The cost is exactly the one this page has always named: each press of the request button spends
another slot in the same queue. The probe does not hide that behind a latch. On a car where nothing
is drawn a latch would leave the owner with no move at all, so the count of requests it has sent is
printed on the screen instead, and the trade is stated rather than made for them.

Once the probe's key *is* trusted it stops being a diagnostic and becomes the repair, because a
trusted shell is the thing the product could never get to. Its **Спасти Denza Apps** button runs
the bounded procedure below - read `adbd_auth`, reject one prompt at a time with `service call
adb 2`, re-read after every call, stop at five - and then force-stops and relaunches Denza Apps so
it asks again on a car that can now answer. It does not touch Denza Apps' data: the product clears
its own one-shot latch from its own screen, and the thing it cannot clear is Android's queue.

This is also where the queue drain earns the live validation the acceptance gate asks for. It runs
in a probe, on an identity that is not the product's, which is what the governance lane is for.

What it reads with no ADB and no permission, all on one photographable screen: `adb_enabled`,
`ro.adb.secure`, `ro.debuggable`, `service.adb.tcp.port`, `init.svc.adbd`, `sys.usb.state`, the
build and model, whether Denza Apps is installed and at which version, and the probe's own key
fingerprint - the string the prompt would show, so a dialog that *does* appear can be told apart
from the product's.

Two things it deliberately does not do. It never reads or clicks the authorization dialog: an
accessibility service could do both, and automating a security confirmation is precisely what that
dialog exists to prevent. And it never runs `pm clear dev.denza.apps`, which would take the
product's ADB identity and its settings with it.

Build and install:

```bash
./gradlew :adb-rescue-probe:testDebugUnitTest :adb-rescue-probe:assembleDebug
```

The APK lands at `experiments/adb-rescue-probe/build/outputs/apk/debug/adb-rescue.apk`. On a car
without ADB it has to reach the head unit the same way Denza Apps did - see
[fse-app-installation.md](fse-app-installation.md).


## Stuck prompt queue

The observed failure signature is one dispatched authorization prompt followed by queued prompts,
with logcat repeating `adbd_auth: prompt currently pending, skipping`. The visible dialog can be
missing even though the system still considers that prompt pending; these are SystemUI/adbd auth
records, not Denza Apps windows that the app can safely close.

Queue recovery is intentionally **not active in the APK** until it is revalidated on the current
firmware. If a trusted safety/local ADB transport still exists, the previously successful bounded
procedure is:

1. Stop or quiet high-frequency ADB clients so they cannot enqueue more requests.
2. Capture `libadbd_auth` / `adbd_auth` logs and retain the already trusted transport.
3. Reject one queued prompt at a time with `service call adb 2`, observing the log after every
   call. Stop at `adbd_auth: no prompts to send`; never run an unbounded loop.
4. Start exactly one connection from the intended Denza Apps key.
5. Match the displayed key/fingerprint to that intended identity, approve it, then prove a full
   disconnect/reconnect with the same key.

Never clear all trusted ADB keys as part of this flow. If no trusted transport remains, recovery
requires physical/system UI access; Denza Apps must remain passive rather than guessing at binder
calls.

## 2026-08-18 live result

On the target DiLink 5.1 firmware:

- `adb install -r` preserved the Denza Apps identity and a trusted cold launch reached the normal
  dashboard without a visible checking overlay or crash.
- After clearing only Denza Apps data, one passive startup probe reached the authorization overlay.
  During a retained 15-second observation there were no additional auth attempts, feature-runtime
  log events, `:weather` process, permission dialog, or crash.
- The overlay's one-shot action produced exactly one standard Android **Allow USB debugging?**
  dialog. After approval, a passive check removed the overlay, started the main and `:weather`
  runtimes, and the trusted shell path granted the required location/audio permissions without
  separate runtime permission dialogs.
- The unavailable/service copy is unit-covered but was not forced live because disabling the only
  local `adbd` endpoint would also remove the retained safety transport.
- Pending-prompt restart suppression, same-fingerprint cross-process proof, and bounded stuck-queue
  draining remain acceptance items below; queue controls therefore remain disabled.

## Vehicle acceptance gate

Before enabling queue controls in the product, perform and retain evidence for all of these on the
target firmware:

1. Install the same-signed APK with `adb install -r`; do not uninstall, because uninstalling loses
   app state and the ADB identity.
2. With the Denza Apps key untrusted, run **Проверить доступ** and prove from auth logs that no
   public-key prompt was added.
3. Exercise representative feature clients (split, navigation, mirrors, HUD, FSE, weather) and
   prove they remain passive while authorization is absent.
4. Press **Отправить один запрос** and prove exactly one public-key request is submitted. Restart
   the activity and process and prove the persisted latch suppresses another request.
5. Approve the dialog, run **Проверить доступ**, and prove trusted shell access survives a full
   disconnect/reconnect.
6. Exercise the main and `:weather` processes and prove they use the same public-key fingerprint.
7. Reproduce a deliberately pending prompt under a retained safety transport, verify the bounded
   one-at-a-time drain command and stopping condition, and only then decide whether a firmware-
   gated queue action can be exposed in ADB Rescue.

Until this gate passes, the diagnostics UI reports queue recovery as disabled. Local unit tests and
an APK build prove only the state machine and packaging, not vehicle behaviour.

## The two states are not actually distinguished (reported 2026-08-26)

The product classifies its ADB situation from the handshake alone: a refused or
unroutable endpoint becomes **ADB недоступен**, and a completed handshake with an
untrusted key becomes **Подтвердите доступ к ADB**. Nothing else is consulted —
`adb_enabled` appears nowhere in the app or the bridge.

A vehicle owner reported the failure this allows, with a screenshot: **Подтвердите
доступ к ADB** on a car where ADB is not unlocked at all. The mechanism follows
from the classification: `adbd` answers, so the handshake completes and the key is
untrusted, so the product asks for a confirmation the system will never render.
The person is then told to press a button, approve a dialog that cannot appear,
and press *check* — an instruction that cannot succeed, which is worse than
silence.

`Settings.Global` carries the distinguishing signal and an ordinary app can read
it from its own process, with no ADB and no permission: `adb_enabled = 0` means no
prompt will ever be shown, whatever is enqueued. On the reference car it reads 1,
alongside `ro.adb.secure = 1` and `ro.debuggable = 0`, so authorization is
enforced and the untrusted-key state there is genuine.

Until the signal is read, the two instructions cannot be told apart, and copy that
promises the easy remedy is a guess. The service path is the only remedy true in
both states.

**The flag has now been read, and the fix misses this car** (live, 2026-08-29 —
see "The prompt is never drawn" below). It reads `adb_enabled = 1`. The
second mechanism this section allowed for is the one that happened, and the
reclassification below never fires on the car it was written for.

The paragraph that follows is kept as it was written, because its reasoning was
right and its conclusion was the one that held.

**The reported car's flag was never read, and the fix assumes it.** The
reclassification only fires when `adb_enabled` reads 0. Nobody has read that
value on the car in the screenshot — the reasoning runs the other way, from a
symptom to a mechanism that can produce it. There is at least one other way to
reach the same screen: if BYD's service-level "ADB unlock" is a separate gate
from Android's flag, that car can hold `adb_enabled = 1` with `adbd` listening
and an untrusted key, which is a genuine `AUTHORIZATION_REQUIRED` that this
change deliberately leaves alone. In that case the owner still gets an
instruction they cannot carry out, and the fix misses.

**The screen now answers this by itself (v38, 2026-08-27).** There is no access to
that car and no way to ask it anything, but the owner reported the defect with a
photograph — so the gate was made to carry what it read. Every screen a person
can be stuck on (`UNAVAILABLE`, `AUTHORIZATION_REQUIRED`, `AWAITING_CONFIRMATION`,
`ERROR`) states the system switch in plain words, and the three readings are
deliberately distinct:

- `Отладка по ADB выключена в системе автомобиля`
- `Отладка по ADB включена в системе автомобиля`
- `Состояние отладки по ADB прочитать не удалось`

Combined with the title, that is enough to tell the four cases apart from a
screenshot alone, with no instructions to the owner and no hidden gesture. A
switched-on flag under **Подтвердите доступ к ADB** means the reported car is a
genuine untrusted key and this classification never applied to it; a switched-off
flag under **ADB недоступен** means the fix caught it.

The earlier silence was reasoned as "absence of evidence is not evidence of an
off switch", which is true and still holds — the app never reports an unreadable
flag as a switched-off one. What was wrong is that saying *"could not be read"*
is itself a reading, so the gate stayed silent in exactly the two cases where
nobody knows the answer.

The explanation window holds either way, because it names the service path in
every state.

## The prompt is never drawn on this car (live, 2026-08-29)

The rescue probe was installed on the owner's vehicle and its request button was
pressed eight times. No authorization dialog appeared for any of them. What the
screen read, on that car:

| Reading | Value |
| --- | --- |
| `adb_enabled` | `1` |
| `ro.adb.secure` | `1` |
| `ro.debuggable` | `0` |
| `init.svc.adbd` | `running` |
| `service.adb.tcp.port` | `5555` |
| `sys.usb.state` / `persist.sys.usb.config` | `adb` / `adb` |
| `persist.adb.tls_server.enable` | unset |
| `ro.build.display.id` | `TP1A.220624.014 release-keys` (Android 13) |
| `ro.product.model` | `DiLink5,1` |
| Denza Apps | installed, 0.6.0-alpha, versionCode 40 |
| Handshake | adbd answers and refuses the key |
| Public keys submitted | 8, from an identity that had never asked before |

Every precondition for a prompt is satisfied and no prompt is produced. The
switch is on, so the v38 reclassification does not apply. adbd is alive,
listening, and completing handshakes, so the endpoint is not the problem. The
key is a fresh identity with no history on this car and no latch of its own, so
nothing in Denza Apps' state can account for it. Eight submissions is well past
any dedup or cooldown.

**This is a system-side failure, and it is not Denza Apps.** That is the finding
the probe existed to produce, and it is now established rather than assumed —
which is the one thing that could not be learned from the product, because the
product had already spent its single request before anyone could ask.

The mechanism is not identified. The signature — adbd refusing every key with no
prompt, on a car whose switch is on — is what AOSP produces when adbd has no
framework to ask: `adbd_auth` hands the key to `AdbDebuggingManager` in
`system_server`, which draws the dialog through the confirmation component. If
that path is gutted or points at a component this firmware does not have, adbd
can only reject. BYD's service-level "ADB unlock" being a separate gate from
Android's flag, as this page allowed for above, is the same class of cause.
Distinguishing them needs the auth log, which needs a shell, which needs the
prompt. The probe cannot break that circle and neither can any other APK.

Consequences for the product:

- The gate currently tells this owner to press a button and approve a dialog.
  On this car that instruction cannot be carried out, and `adb_enabled = 1`
  means the v38 copy states the switch is *on* — which is true and makes the
  instruction read as more actionable, not less.
- A car that answers, refuses a key, holds the switch on, and draws nothing is a
  fourth state, distinct from the three the gate knows. It is only detectable by
  spending a request and watching, so the product cannot classify into it on its
  own; what it can do is stop promising the remedy after the attempt is spent.
- Repeated pressing costs a queue slot each time and buys nothing here. The probe
  prints the count for exactly this reason.

Untried, and the only remaining leads that are not another APK: whether the
dialog is drawn on the passenger or cluster display rather than the head unit;
whether a restart re-dispatches the queue; and wireless debugging pairing, which
is a different code path from this dialog — though `persist.adb.tls_server.enable`
is unset on this car, so it has likely never been enabled.


## How ADB authorization actually works on DiLink 5.1 (corpus, 2026-08-29)

Read from this vehicle's own firmware: `reverse/hud/apks/com.android.systemui.apk` and
`reverse/speaker-lift/dex/services/classes.dex`. This settles what "the car is unlocked" means
here, and it is not what the phrase suggests.

**The prompt is dispatched by stock AOSP.** `AdbDebuggingManager.startConfirmationForKey` is
unmodified: it resolves `config_customAdbPublicKeyConfirmationComponent`, tries it as an activity
and then as a service, and logs `unable to start customAdbPublicKeyConfirmation…Component` if
neither resolves. `com.android.systemui.usb.UsbDebuggingActivity` is present in the dex *and*
declared in SystemUI's manifest, behind `MANAGE_DEBUGGING`, alongside a
`UsbDebuggingActivityAlias` gated on `DUMP` and a `WifiDebuggingActivity`. Nothing is missing, so
"BYD removed the confirmation component" is ruled out.

**The dialog itself is not stock, and the difference is the whole answer.** `UsbDebuggingActivity`
carries a BYD branch before it builds any UI:

```java
boolean z = SystemProperties.getInt("persist.sys.factory.version.flag.config", 0) == 1;
if (z && this.mKey != null) {
    IAdbManager.Stub.asInterface(ServiceManager.getService("adb")).allowDebugging(true, this.mKey);
    finish();
    return;
}
```

With that property at `1` the activity approves **every key that is ever offered**, permanently
(`alwaysAllow = true`), and finishes without drawing anything.

**This is a bypass that exists in the firmware; it is not how the reference car works.** That was
claimed here when the branch was first read, and the reference car refutes it: on 2026-08-18 its
one-shot request produced a normal *Allow USB debugging?* dialog (recorded above). The auto-approve
branch returns before any UI is built, so a car that draws the dialog necessarily has this flag at
`0`. The working configuration on this project's own vehicle is therefore flag `0` plus a
functioning stock prompt, over a network transport and with no USB anywhere.

**One stock detail that matters and was nearly misread.** `UsbDebuggingActivity` registers the
`UsbDisconnectedReceiver` that finishes the dialog on USB detach *only* when
`service.adb.tcp.port` reads 0:

```java
if (SystemProperties.getInt("service.adb.tcp.port", 0) == 0 && !zEquals) { ...register... }
```

This car reads `5555`, so the receiver is never registered. A dialog dying instantly because no
USB cable is attached is a real failure mode on head units and it is excluded here on the code,
not on a guess.

### What this means for the reported car

The owner's car refuses keys, which means the auto-approve branch is not running. Two states
remain, and one property separates them:

| `persist.sys.factory.version.flag.config` | What it would mean |
| --- | --- |
| `1` | The activity runs the bypass, so no dialog is ever drawn - which matches. It should also have trusted the key, and did not. `allowDebugging` failing or `getService("adb")` returning null both produce exactly that: the branch logs `Unable to notify Usb service`, finishes, and leaves the key refused with nothing on screen. |
| `0` | The same configuration as the reference car, which draws the dialog. The flag would then be a red herring and the suppression is something else. |

The probe reads the property and states which of the two it found. An unread value is reported as
unread and never as a lowered flag.

**The anchor measurement has not been taken.** Reading the flag on this project's own working car
is one command over the ADB that already works there, and it fixes the baseline that the reported
car's reading is compared against:

```bash
adb shell getprop persist.sys.factory.version.flag.config
```

The reference car is expected to read `0` or empty, because it draws the dialog. If it reads `1`,
the branch above is understood wrongly and this whole section needs revisiting.

If the reported car reads `0` as well, the flag explains nothing and the difference between the
two cars lies elsewhere. If it reads `1`, the flag is the difference, and the remedy is not an APK
and not a dialog: a `persist.` property is written below the application layer, by service or
factory tooling. Nothing in the decompiled corpus outside SystemUI so much as mentions it.


## The persistent shell is a terminal (live v31, 2026-08-26)

Feature clients do not run one command per ADB stream. `LocalAdbClient.openPersistentShell()`
opens the legacy `shell:sh` service once and writes command lines into it for the life of the
session, framed by two markers so each answer can be told apart from the next.

On this adbd that service hands back a **PTY**, so `/system/bin/sh` — mksh — starts its line
editor on the stream. Two consequences follow, and both have now cost a release:

- The shell **echoes** what is written to it. The frame reader has tolerated that echo since
  2026-08, which is the standing evidence that a terminal is there.
- The line editor **consumes raw control bytes as key presses**. A frame that carries `0x1E`
  or `0x1F` as literal bytes inside the command line loses them: the marker never comes back,
  `findFramedResult` never matches, and every read waits out `READ_TIMEOUT_MS` and fails with
  `Read timed out`. Live on v31 this took down every ADB-dependent feature at once — split,
  Simulcast, navigation, mirrors, telemetry, the FSE installer — while ADB Rescue still read
  *ADB-доступ подтверждён*, because the one-shot `shell:<command>` path was unaffected.

Markers therefore travel as escape text that the shell itself expands, never as bytes on the
command line.

**A frame is only proven on the channel the product opens.** Neither of the two channels that
are convenient to test on has a line editor: a unit test against `/bin/sh` under a pipe, and
`adb shell <command>` from the host, which is the one-shot service. Both accepted the frame that
the car rejected. Any change to the frame owes a run through an interactive `shell:sh` with a
terminal attached, over the same cases the unit tests cover: quotes, `$`, newlines, unicode,
backslashes, nested quotes, a non-zero status, and an output large enough to span several ADB
messages.
