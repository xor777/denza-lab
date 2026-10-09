# Media focus surgery (parked)

> **Parked on 2026-10-09** by the owner's decision. Switched off since 2026-09-18
> (`MediaKeyExperiment.FOCUS_SURGERY = false`), taken out of the Denza Apps APK on
> 2026-10-09. Nothing here is compiled; the product does not depend on it.

## What it was

The steering wheel's play/pause key is answered by `feature/media`
(`MediaResumeCore`, `MediaResumeController`). On 2026-09-05 a pause had a known
fault: with Yandex Music playing, starting VK Video paused Yandex with a transient
focus loss; pausing VK from the wheel then made VK release its focus, and Yandex,
still holding its suspended entry in the car's audio-focus stack, started by
itself. A direct `pause()` or `stop()` to the suspended player does not prevent
that.

This experiment removed the suspended players' entries from the focus stack just
before the pause, so nothing was left to resume:

- `MediaFocusPauseBridge.kt` - the app half. It staged the helper jar under
  `/data/local/tmp/denza-media-focus-<sha256>.jar` over the existing passive local
  ADB, ran it off the accessibility thread and reported the outcome to the
  support report's ring (`MediaKeyDiagnostics.recordCompletion`).
- `MediaFocusPauseProxyMain.java` - the shell-UID helper started by
  `app_process`. It read `IAudioService.getFocusStack()` and called
  `unregisterAudioFocusClient(clientId)` only for entries of the named
  predecessor packages and UIDs that held a transient loss (`-2`) with media
  audio attributes, below the still-playing target at the top of the stack.
- `MediaPausePreparation.kt` - the contract between the two
  (`MediaPauseSession`, `MediaPausePreparationRequest`,
  `MediaPausePreparation`).

In the product, `MediaResumeCore.perform` handed a pause with paused
predecessors to a `deferPause` callback; `MediaResumeController` then owned the
press until the helper answered, and dispatched the pause through
`MediaResumeCore.completeDeferredPause`. The reasons `pause-deferred`,
`pause-in-flight`, `pause-already-complete`, `pause-preparation`,
`session-access-after-preparation` and `stale-target-after-preparation` belonged
to that path, and `apps/denza-apps/build.gradle.kts` packed the helper into
every APK as `assets/media-focus-pause-proxy.jar` (task
`pack<Variant>MediaFocusPauseProxy`).

## Why it is parked

Evidence is in
[`docs/shortcuts-automation-findings.md`](../../docs/shortcuts-automation-findings.md):
"VK pause restored Yandex through transient audio focus (2026-09-05)" and "The
press the preparation swallowed, and what the car taught on 2026-09-18".

- 2026-09-05, Z9GT: the mechanism worked. A shell run removed the suspended
  predecessor's focus client before the VK pause and neither player resumed;
  build `5d85e806…` passed the operator's VK/Yandex check.
- 2026-09-18, Z9GT, builds 48-51: the helper threw on six presses out of seven
  (`DENZA_MEDIA_FOCUS_READY` never printed), held every pause back by about
  650 ms, and a press consumed by a preparation that then failed did nothing at
  all. The one time it succeeded it emptied the focus stack; the firmware routes
  the wheel's next and previous keys to the focus owner, so with nobody left the
  next press reached the stock player.

The owner chose the platform's rule instead: the key controls what is audible,
and play brings back what was audible last. A player paused under a video may
resume when the video is paused. A pause with paused predecessors is an ordinary
pause (`MediaResumeCore`, "A pause is a pause" in the findings doc).

## How to revive it

Only together with the "second step" - taking the wheel's next and previous keys
into the same policy - and only after the 2026-09-18 failure is understood: the
helper's guards (all sessions of a package agree on the state, the target is the
top of the focus stack, a predecessor carries a transient loss) are each a
candidate for the throw.

1. Copy the three files back to
   `apps/denza-apps/src/main/java/dev/denza/apps/feature/media/`.
2. Restore the product wiring as it stood in commit `053d151d`, the last one
   with the experiment built in (`git show 053d151d:<path>`):
   `MediaKeyExperiment.FOCUS_SURGERY`, the `deferPause` parameter of
   `MediaResumeCore.perform` with `completeDeferredPause`,
   `DeferredPauseCompletion` and the six reasons above, the `PauseOperation`
   path and `AndroidTarget.pauseSession` in `MediaResumeController`,
   `MediaKeyDiagnostics.recordCompletion` with a nullable
   `MediaKeyPress.keyCode`, the bridge's construction, `warm()` and `close()` in
   `SimulcastAccessibilityService.java` (since 2026-10-09 the key lives and goes with
   `feature/media/MediaKeyRider.kt`, a rider of that service), and the `packMediaFocus` task in
   `apps/denza-apps/build.gradle.kts`. The deleted tests are in
   `MediaResumeCoreTest.kt` and `MediaKeyDiagnosticsTest.kt` at the same commit.
3. Raise `DenzaMediaResume` and `DenzaMediaFocus` logging before any live run;
   this firmware's global `log.tag=M` hides both, and the support report's
   «Кнопка play/pause на руле» ring is the only trace an owner without host ADB
   has. Confirm the player plays through the car's own speakers first: a player
   casting to a home speaker holds no focus in the car and reads like a broken
   one.
