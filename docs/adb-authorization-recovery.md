# ADB Authorization Recovery

Status: locally prepared on 2026-08-18; not yet exercised on a vehicle.

This page covers the local ADB identity used by Denza Apps. Transport reachability and Android
ADB authorization are separate: a reachable `adbd` endpoint may still reject the app key, and a
trusted remote tunnel does not prove that the Denza Apps key is trusted.

## Product behaviour

Denza Apps has one canonical ADB identity and one owner for authorization prompts:

- Feature clients use `DenzaLocalAdb` in `PASSIVE` mode. They may sign a challenge with an
  existing key, but never submit that public key to Android's prompt queue.
- The key pair is stored in one atomic file protected by an OS file lock. The old
  `adb_auth` SharedPreferences pair is migrated without changing the identity. This prevents the
  main process and `:weather` process from racing to create different first-install keys.
- The hidden diagnostics dialog owns the explicit **ADB Rescue** action. A denied passive check
  enables **Отправить один запрос**; that action submits the public key once and closes the
  transport without retrying.
- A persisted one-shot latch prevents process or activity restarts from rearming the request.
  After submission, only a passive check is automatic. **Разрешить новую попытку** merely clears
  the app latch; it does not submit a key and does not clear Android's system queue.
- No key material, endpoint, or token is included in support diagnostics.

The dialog is still hidden: tap the `Трансляция` card header seven times, with no more than three
seconds between taps.

## Operator flow

1. Open **Диагностика → ADB Rescue** and press **Проверить доступ**.
2. If access is already trusted, stop. No authorization request was generated.
3. If the status says permission is required, press **Отправить один запрос** once.
4. Approve the Android dialog on the vehicle, then press **Проверить доступ** again.
5. If no dialog appears, do not repeatedly rearm the request. Preserve the pending state and use
   the stuck-queue procedure below when a separately trusted transport is available.

An ordinary APK cannot inspect or drain `libadbd_auth` before its own ADB key is trusted. A second
APK is therefore not a rescue path: it creates another RSA identity and can add another pending
request to the same queue.

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
