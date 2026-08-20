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
  without retrying. The hidden diagnostics dialog retains the same controls for support use.
- A persisted one-shot latch prevents process or activity restarts from rearming the request.
  After submission, only a passive check is automatic. **Разрешить новую попытку** merely clears
  the app latch; it does not submit a key and does not clear Android's system queue.
- No key material, endpoint, or token is included in support diagnostics.

Technical diagnostics remain hidden: tap the `Трансляция` card header seven times, with no more
than three seconds between taps.

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
