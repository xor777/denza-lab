# Speaker lift (pop-out covers)

How this Denza IVI extends the dash speakers, why stock music and Bluetooth do
it and Yandex Music does not, and which call actually drives the motor.

Corpus: AutoVoice `RLSAApiImpl` / `MusicHornApiImpl.J`, DiCar
`CarAmplifierServiceImpl` (`/system/priv-app/DiCarServer`), MapHelper +
framework `BYDAutoFeatureIds$Audio`, `libbydauto.so` `BnBYDAutoServer::onTransact`,
`services.jar` `IviVehicleAudioBroker` / `BydAudioService` /
`AudioVisualizerStore` / `AtmosphereLampCore`. Live read-only dump and bounded
`setInt` on 2026-08-22, DiLink 5.1 `adb -s 127.0.0.1:5555`. The confirmed
LOCAL path itself used a host dex via `app_process`, not an APK install.
Parallel locale session was not disturbed except for a recovered `autoservice`
restart (below).

## Verdict

The covers are a **Devialet speaker-flip** mechanism, not Dynaudio RLSA.

| Claim | Result |
| --- | --- |
| Hardware present | **Yes.** `AUDIO_SPEAKER_FLIP_COVER_CONFIG` (`0x35A000D8`) = 1. `AUDIO_RLSA_COFIG` (`0x4C000010`) = 0. Amp `0x4FD00030` = **7** (`DEVIALET_20_CHANNEL`). Speakers number = 26. |
| Auto-lift setting | `AUDIO_SPEAKER_FLIP_SETTING_STATUS` (`0x35A000DA`) read `1` until 2026-08-25 — that is why stock music and Bluetooth extend the covers without a helper. It is the amp's **setting**, not a cover-position readout: it stayed `1` throughout a MediaCenter raise. It now reads `2`; see "Direct cover control". |
| Direct raise/lower call | **Works on this car** (user live 2026-08-25). `setInt(audio=1002, AUDIO_RLSA_STATE_SET=0x16300025, value)`: `2` retracts the covers, `1` extends them. No audio, no MediaCenter, no reboot. `AUDIO_RLSA_COFIG=0` does **not** make this SET a no-op. |
| Why the 2026-08-22 run showed nothing | **Null experiment, not a negative result.** The value written was `1` while the amp already reported `1`, and the observable watched was `AUDIO_RLSA_STATE` (`0x4C00000B`), which is always 0 on a Devialet amp. The signal is **edge-triggered** — rewriting the current value cannot move anything. The Devialet-side echo is `AUDIO_SPEAKER_FLIP_SETTING_STATUS` (`0x35A000DA`). |
| Shell media-source SET | **Wrong lever.** Write changed STATE `1`→`6`, no extend. Live BT with covers out still has STATE **1**. |
| Play-state / mute SETs | HAL `1`. `AUDIO_MEDIA_SOUND_MUTE_STATE` stayed `1` after SET `0`. Single-int `WORKING_STATE_SET` has no GET. Wrong shape vs stock (stock writes an **intArray** of two FIDs). |
| Confirmed raise trigger | **Stock LOCAL playback through `com.byd.mediacenter`.** From a clean reboot with the covers retracted, `MediaAction=14` (`playById`) started a 1.729 s local OGG; the user confirmed that the covers extended. The track was `STREAM_MUSIC=3`, not BT stream 14. |
| Why Yandex Music does not extend | **Not** MCU source/volume, focus attributes, `STREAM_BT_MUSIC=14`, or `startAudioOutput`. Yandex plays through ExoPlayer/`AudioTrack` and never enters `MediaPlayer.startImpl()`. That `startImpl` is the stock Java gate: it calls `AudioManager.isStreamAllowed(stream, pkg)`, and for streams `3/2/0` that call **side-effects** `IviVehicleAudioBroker.setUseVehicleSpeaker()`. `mIsLocalSource` is only `setDataSource(FileDescriptor) → true`; it does **not** drive the motor and does **not** bypass the stream check when the file has an audio track. |
| Stock Java visualizer call | **Lights only.** `startAudioOutput` → transact **20** → `AtmosphereLampCore` → MCU `[0x43E00040, 0x43E00044] = [2, 1]`. Live 18:46:12. No cover motion. |
| Normal `/data/app` APK | **Blocked** for `BYDAutoAudioDevice.set` (`android.permission.BYDAUTO_AUDIO_SET`). **Not blocked** for `startAudioOutput`: `BydAudioService` does not check permission or that the caller owns `pkg`. Do not put `BYDAUTO_*` in a product manifest. |

The lever is `AUDIO_RLSA_STATE_SET`, written as an **edge**. The MediaCenter
LOCAL pulse below was the first stock-shaped raise, but it is not the product
path: it is audible, seizes MediaCenter, cannot retract, and stopped raising the
covers after the first direct `2` latched the amp's auto-lift setting off.

## Direct cover control (2026-08-25, live-proven both ways)

One FID moves the motor in both directions, with nothing playing:

```bash
# retract
adb -s 127.0.0.1:5555 shell "service call autoservice 6 i32 1002 i32 372244517 i32 2 null"
# extend
adb -s 127.0.0.1:5555 shell "service call autoservice 6 i32 1002 i32 372244517 i32 1 null"
```

`372244517` = `0x16300025` = `AUDIO_RLSA_STATE_SET`, device `1002`. Values are
`DevialetStatusData.SPEAKER_FLIP_COVER_ENABLE = 1` and
`SPEAKER_FLIP_COVER_DISABLE = 2`. Verified live by the user on 2026-08-25:
`2` pulled the covers in, `1` pushed them back out, repeatedly.

**Edge, not level.** Writing the value that is already in effect does nothing —
that is exactly why the 2026-08-22 attempt looked dead. To raise from a state
that already holds `1`, write `2` first, then `1`.

Raw bytecode of DiCar `CarAmplifierServiceImpl.setRLSAEnable` (jadx `--fallback`,
because normal decompilation silently drops the Devialet branch and prints an
empty `if (z) { }`):

```text
r3 = r1 & 31            # RLSA configs
if (r3 == 0) goto L37
if (r6 == 0) goto L3f
goto L3d
L37:
r1 = r1 & 2144          # 0x860 = CommonStatusData.DEVIALET_CONFIGS
if (r1 == 0) goto L64   # amp not supported
if (r6 == 0) goto L3f
L3d: r6 = 1
L40: setCarProperty("0x16300025", r6)
L3f: r6 = 2
```

Both the RLSA branch and the Devialet branch write the **same** FID with the
**same** `1`/`2`. There is no separate Devialet value and no hidden third
state. AutoVoice agrees: `MusicHornApiImpl.J()` (`setSpeakerAutomaticLifting`)
is gated on `p()` = `hasDiWaLeiAutoConfig()` (帝瓦雷 = Devialet, amp ∈ {7,11,18})
`&& AUDIO_SPEAKER_FLIP_COVER_CONFIG == 1` — both true here — and writes
`AUDIO_RLSA_STATE_SET`.

### Confirmed: direct retract disables stock auto-lift for the ignition cycle

`AUDIO_SPEAKER_FLIP_SETTING_STATUS` (`0x35A000DA`) is the amp's **auto-lift
setting**, not a cover-position readout — during the MediaCenter raise it stayed
`1` while the covers travelled out. Writing `2` moved it `1 → 2`; writing `1`
afterwards moved the covers but left it reading `2` across repeated reads.

The amp latches into a manual mode on the first `2`. This is not merely a
cosmetic status: after the covers were retracted and the setting read `2`, the
previously proven MediaCenter `MediaAction=14` positive control played but did
**not** extend them. A later direct `1` extends the motor but does not restore
stock auto-lift. This car exposes no stock speaker auto-lift toggle, and the
voice-setting path also only writes the same `1`. A power/ignition cycle is the
remaining expected reset, not yet re-verified after this latch.

Cover position stays unobservable regardless: `AUDIO_SPEAKER_FLIP_COVER_STATUS`
(`0x3D20001E`) and `AUDIO_SPEAKER_FLIP_COVER_STATUS_SET` (`0x4EF52026`) return
`−10011` on **all 50** device families, not just on `1002` (swept 2026-08-22).

### Product shape

`BYDAutoAudioDevice.set` needs `android.permission.BYDAUTO_AUDIO_SET`, which must
not go into a product manifest, so Denza Apps drives this through
`DenzaLocalAdb` shell, the same route as the BMS FIDs.

## Denza Apps automation (implemented 2026-08-26, redesigned 2026-08-28; live acceptance pending)

The product deliberately replaces the stock auto-lift once enabled. Its
persistent toggle is off by default and owns a foreground service while on.

**The contract in one sentence (2026-08-28): automation never overrides a manual
command; only the driver's hand or an ignition cycle can.** Everything below is
that sentence read from a different side.

**One automatic action per boot, and it is OPEN.** On the first evidence of
playback in a boot the service sends `1` once and then stands down until the car
is restarted. Evidence is the same three-layer OR as before:

1. a foreground transition into a known player opens immediately through the
   existing global accessibility observer;
2. any active `MediaSession` entering `STATE_PLAYING` opens immediately;
3. output-mix signal continuously above the calibrated `-58 dB` gate for three
   seconds opens as the source-agnostic fallback.

Only continuity is counted on layer 3: frames more than 1 s apart are two runs
rather than one, and capture that has stopped answering breaks the run without
meaning anything. Motor operations are still serialized, and a failed call is
still retried on the 30-second guard, forever, with the tile going DEGRADED and
then FAILED after three failures.

**There is no automatic close any more.** The 30-minute confirmed-silence timer,
the silence branch of the audio sampler, and `CLOSE_SILENCE_MS` were deleted on
2026-08-28. Three reasons, in order of weight: the fallback re-opened covers
about three seconds after the driver closed them by hand while music was still
playing, which is an ongoing level overruling the freshest possible act; the car
retracts the covers at power-off, so the timer was buying a state the ignition
already guarantees; and the timer only advanced on fresh session-0 FFT frames,
so a dead capture wedged the automaton for the life of the process (see the latch
section below).

**A button always physically moves the motor.** A press bypasses the same-wish
skip in the automaton and, at the motor, forces the `2` / 350 ms / `1` pair when
the property already holds the value asked for. This is the recovery path for the
amplifier lowering the covers on its own, which the app cannot see and cannot
otherwise answer.

**A press hands the wheel to the driver for the rest of the boot**, whether or
not the one automatic opening has already been spent, and whether or not the
automation is switched on at the time.

**Both facts are boot-scoped and persisted** next to `last_command_value` in
`speaker_covers.xml`, with the same stamp (wall clock less
`SystemClock.elapsedRealtime()`, 30 s of slack): `driver_took_over_boot` written
at the moment of the press, `auto_opened_boot` written only once the automatic
open is acknowledged. Without them a service restart mid-boot would re-open
covers the driver had just closed, or spend a second automatic opening.
(The scope in this paragraph and everywhere else in this section is superseded —
"boot" was falsified live the same evening; see *The trip is a waking, not a
boot* below. Everything else about the three facts still holds.)

**Switching the automation on clears both flags.** The toggle is fresh intent and
newer than any button pressed before it, so it hands the wheel back and the
automation is owed its one opening again for the rest of the boot: enabling the
feature while music is playing opens the covers there and then. Cleared on the
enable path in `DenzaAppRepository` before the service reads them, and the
service re-seeds its automaton in the same off-then-on branch of
`onStartCommand` that clears `windingDown` - the in-process object had read the
preferences once, in `onCreate`, and would otherwise keep announcing
«Управление у водителя» for the rest of its life. Nothing else in a boot re-arms
the automation; a press is one-way until either the toggle or the ignition.

Because the command is edge-triggered, a write that repeats the value the
property already holds moves nothing. Two different unknowns were conflated here
until 2026-08-26: *where the covers are*, which is unreadable and can change
behind the app's back because the amplifier lowers them on its own, and *what the
property last saw*, which is knowable because this app is its only writer. The
code answered the first by sending `2`, a 350 ms pause, then `1` whenever cover
position was unknown - which is every service start - and the owner saw exactly
that on the car: the covers twitching on every switch-on.

**The model is best effort with a storm guard, and nothing else.** There is no
believed position: a signal becomes one command, and the same wish is not sent
twice unless a hand asks for it. A repeat would be a no-op anyway - the value is
already there - so skipping it costs nothing, and paying for a forced edge is a
choice made only for a button. A failed command may be retried once the 30-second
guard has passed; without the guard, the 5 Hz audio sampler would retry at 5 Hz.
(As written on 2026-08-26 the list of signals also contained "thirty minutes of
confirmed silence"; that wish no longer exists.)

The `2`, 350 ms, `1` pair survives for two cases: the first command after a boot,
when the app has written nothing and the firmware could be holding either value;
and, since 2026-08-28, a panel button asking for the value the property already
holds. The second is a deliberate purchase - the pair is invisible on covers that
are in and a twitch on covers that are out, nothing on this car can tell those
apart, and a driver looking at the covers and pressing anyway is the one party
entitled to spend it. The remembered value is scoped to the boot (wall clock less
`SystemClock.elapsedRealtime()`, with 30 s of slack), because the amplifier goes
down with the car and a value remembered across an ignition cycle is a guess that
fails silently - the write matches, nothing changes, no motor moves, and the
feature looks dead with nothing to read. Covers are retracted at that point, so
the pair's close moves nothing and only the open is seen.

The one case that still shows a double movement: the very first command of a boot
while the stock system has already raised the covers. There is no way to detect
it - that is the price of an unreadable position, and it is one moment per
ignition cycle rather than every switch-on.

Turning automation off still tries to open the covers before stopping the
service, so a user cannot be left with closed covers and stock auto-lift already
suppressed - but since 2026-08-28 it is best effort in both directions. If the
value remembered for this boot is `2` it sends nothing at all and simply stops:
under the new contract a remembered close can only have come from the «Опустить»
button, and answering it with an open would be the automation getting the last
word on its way out. Otherwise it issues a non-manual open, which the ordinary
same-wish skip drops when the property already holds `1` - so no twitch on
toggle-off when the covers are already out.

The remembered `2` is only half of that refusal, because it is written when the
write is acknowledged and the press may not have got there yet. So the automaton
refuses too: `onBestEffortOpen` never replaces a manual desire - one whose adb
call is still running, or one queued behind a command already in flight - and the
parting open then simply does not happen. The queued press still leaves through
the motor result, the service's wind-down waits for it, and the process stops
without opening anything. A *retry* of a failed press is still allowed out, being
the same command carried further rather than a new one. The two checks divide the
cases with no window between them: the automaton covers this process, the
remembered value covers a press made in an earlier one.

The settings panel has explicit «Поднять» / «Опустить» buttons, which answer
whether or not the automation is switched on. They no longer merely tell the
automation where the covers went: they end its turn for the boot.

The eager foreground list is the user-approved set:

- stock: `com.byd.mediacenter`, `com.byd.videoplay`, `com.byd.minikaraoke`;
- music: `ru.yandex.music`, `com.google.android.apps.youtube.music`,
  `com.spotify.music`, `com.apple.android.music`, `com.uma.musicvk`,
  `org.videolan.vlc`;
- video: `com.google.android.youtube`, `com.vk.vkvideo`, `ru.rutube.app`,
  `ru.kinopoisk`, `ru.ivi.client`, `ru.rt.video.app.mobile`, `ru.mts.mtstv`,
  `ru.start.androidmobile`, `gpm.tnt_premier`, `com.netflix.mediaclient`,
  `com.amazon.avod.thirdpartyclient`, `com.plexapp.android`.

The MediaSession and output-mix layers intentionally cover players outside
this list. The integrated APK, boot recovery, the 3-second fallback, and the
whole of the 2026-08-28 contract - the per-boot one-shot, the forced edge behind
the buttons, the takeover, and the quiet toggle-off - still require live-car
acceptance; only the underlying motor calls and output-mix capture are live-proven
independently.

The dashboard tile now says which of the three states the automation is in, since
they are the only thing a driver needs from it: «Открою при первом
воспроизведении», «Автоматика отработала — дальше кнопками», «Управление у
водителя до перезапуска машины».

### The trip is a waking, not a boot (falsified live 2026-08-28)

Everything above scopes the three persisted facts — `last_command_value`,
`driver_took_over`, `auto_opened` — to the kernel boot, on the assumption that an
ignition cycle restarts Android. **It does not. This head unit suspends.**

Measured on the car on 2026-08-28: kernel boot time **31.4 h** against roughly
**7 h** of `uptimeMillis`, the unit having woken about 7 minutes before the check
(when the driver got in). Preference stamps written the previous day matched the
current `System.currentTimeMillis() - SystemClock.elapsedRealtime()` to within
**13 ms**, so the 30 s slack was never remotely challenged and all three facts
read as live. The amplifier does not share that boot: it goes down with the
ignition, physically retracts the covers, and comes back with its edge-triggered
property reset. From the seat that combination looked like a dead feature —
music playing on a fresh trip and the covers never coming out, because the
automation believed the driver had taken over (yesterday), that its one shot was
already spent (yesterday), and the remembered `last_command_value = 1` made an
automatic open a same-value no-op against firmware power-cycled twice since.

The fix scopes the facts to the **wake cycle**. Android's two monotonic clocks
differ by exactly the thing that was missing: `SystemClock.uptimeMillis()` stops
in deep sleep, `SystemClock.elapsedRealtime()` does not, so
`elapsedRealtime - uptimeMillis` is this boot's cumulative sleep and only ever
grows. That total is now written beside each stamp (`last_command_asleep`,
`driver_took_over_asleep`, `auto_opened_asleep` in the same `speaker_covers.xml`),
and a fact is live **iff** it was written in this kernel boot — the unchanged
30 s-slack stamp comparison — **and** the machine has slept less than **60 s**
since the write. The rule is one pure object,
`SpeakerCoverFactScope.isLive`, unit-tested without Android; `SpeakerCoverSettings`
only reads the clocks and the preferences and delegates. Boot slack stays
inclusive (exactly 30 s of drift is still the same boot); the sleep threshold is
strict (exactly 60 s asleep is already a different trip).

**A missing sleep record reads as expired**, not as live: preferences written by
the previous version of the app carry a boot stamp and nothing else, and those
are precisely the day-old facts this rule exists to discard.

Expiring early is the safe direction. The worst case is a stop short enough that
the amplifier stayed powered: the facts expire anyway, the next command finds
nothing remembered, and the once-per-trip forced `2` / 350 ms / `1` pair costs one
visible twitch — the price this design already documents for a property it cannot
read. The other direction has no such floor, as the night of 2026-08-28 showed.

The user-visible scope is unchanged and the tile copy stays true: sleep *is* the
car being switched off, so «Управление у водителя до перезапуска машины» and the
panel's «один раз за поездку» now describe what the code actually does.

## Superseded working path (2026-08-22 20:19)

This is the first test performed from a known retracted state after a full car
restart. It did not start Bluetooth or send AVRCP commands.

1. A short stock notification OGG was copied to
   `/sdcard/Music/denza-speaker-lift-probe-20260822.ogg` and indexed by
   MediaStore. Duration: 1,729 ms.
2. MediaCenter canonicalized the path as
   `/storage/FFFF-FFFF/Music/denza-speaker-lift-probe-20260822.ogg`.
3. MediaCenter's `musicId` is the Java `String.hashCode()` of that canonical
   path: signed `-725736716`. It is **not** the MediaStore row `_id`
   (`1000011112` in this run).
4. The probe started `com.byd.mediacenter/.main.MediaService` with:

   ```text
   action = byd.intent.action.START_MEDIA
   MediaMode = 1                 # music
   MediaAction = 14              # ControlConverter playByIndex -> handlePlayById
   sdkVersion = 501000
   MediaParams = Bundle(
       source = 0,               # LOCAL
       package = com.android.shell,
       media_id = -725736716L,
       media_list_type = 0
   )
   ```

5. Live evidence showed MediaCenter `PlaybackState=3`, a MediaPlayer owned by
   `com.byd.mediacenter`, `streamType=3`, `mIsLocalSource=true`, and a real
   audio track. The user then confirmed: **covers extended**.
6. `MediaAction=2` paused the pulse; `dumpsys media_session` confirmed
   MediaCenter `PlaybackState=2`.

The same service-only call was repeated once at the user's request, followed
by pause after one second. The short chime played, no Activity was requested,
and MediaCenter again reported `PlaybackState=2` immediately afterward.

The nested `MediaParams` Bundle cannot be represented by the plain `am`
command used for the pause. `tools/speaker_lift_local_pulse.sh` builds and
runs the shell-UID helper that sends the exact working shape.

The failed initial LOCAL attempt (`MediaAction=1`, `withui=true`) only opened
the MediaCenter page and left its queue empty. It was not evidence against
stock local playback. The successful `MediaAction=14` populated the LOCAL
list and reached the real player.

### Product implication

A practical pre-roll was available: register a very short local clip,
address it by MediaCenter's canonical-path hash, play it through stock LOCAL,
then pause MediaCenter and restore the user's previous player. A silent or
lower-volume clip and the resume hand-off still need a clean retracted-state
integration test; only the audible 1.729 s stock notification is proven here.

This route is retained as historical evidence only. Direct
`AUDIO_RLSA_STATE_SET` control is now live-proven both ways and is the product
path.

## Yandex-open normal-UID probe (built, live-unverified)

`experiments/speaker-lift-yandex-probe/` is a disposable APK for the next clean
test. It deliberately stays outside Denza Apps until normal-UID behavior is
proven.

- package: `dev.denza.speakerlift.yandexprobe` (kept separate from the earlier
  `dev.denza.speakerlift.probe` BT bridge);
- no Activity, launcher entry, overlay, Bluetooth path, local ADB client, or
  requested Android/BYD permission;
- accessibility component:
  `dev.denza.speakerlift.yandexprobe/dev.denza.speakerlift.probe.YandexSpeakerLiftAccessibilityService`;
- listens only for `TYPE_WINDOW_STATE_CHANGED`, does not retrieve window
  content, and detects a transition into `ru.yandex.music`;
- sends the exact `MediaAction=14` nested Bundle to the exported, unprotected
  `com.byd.mediacenter/.main.MediaService`, then `MediaAction=2` after 1,000 ms;
- never sends `withui` and therefore does not request a MediaCenter Activity;
- uses the same `/product/media/audio/notifications/pixiedust.ogg` copied and
  indexed at the already proven canonical path.

Local evidence on 2026-08-22:

```text
:speaker-lift-yandex-probe:assembleDebug  PASS
:speaker-lift-yandex-probe:lintDebug      PASS
APK manifest                             zero activities, zero requested permissions
```

Clean acceptance sequence after reboot:

```bash
# Preconditions: covers are retracted; Yandex Music is not foreground.
tools/speaker_lift_yandex_probe.sh install

# Do not run the script's manual `pulse` command: it would invalidate the test.
# Open Yandex Music once from its normal icon and watch the covers.

tools/speaker_lift_yandex_probe.sh status
tools/speaker_lift_yandex_probe.sh logs

# Exact rollback, preserving every other accessibility component:
tools/speaker_lift_yandex_probe.sh disable
tools/speaker_lift_yandex_probe.sh uninstall
```

Expected action: opening Yandex produces the same chime for about one second,
the covers extend, MediaCenter returns to paused, and no MediaCenter UI appears.
The hypothesis is falsified if the normal UID is rejected when starting the
exported service, MediaCenter cannot see the indexed row, or the chime plays
without cover motion. Capture logs before changing the probe if any of those
occurs.

## Live snapshot (2026-08-22)

Device family `BYDAUTO_DEVICE_AUDIO = 1002`. `getInt` = transact `5`.

| Name | FID | Parcel | Decode |
| --- | --- | --- | --- |
| `AUDIO_RLSA_COFIG` | `0x4C000010` = 1275068432 | `0` | no RLSA lift assembly (`isHaveRLSA` requires 1) |
| `AUDIO_RLSA_STATE` | `0x4C00000B` = 1275068427 | `0` | not 1=up / 2=down / 3–6=moving |
| `AUDIO_RLSA_STATE_SET` (get) | `0x16300025` = 372244517 | `−10011` | SET-only |
| `AUDIO_SPEAKER_FLIP_COVER_CONFIG` | `0x35A000D8` = 899678424 | `1` | flip covers present |
| `AUDIO_SPEAKER_FLIP_SETTING_STATUS` | `0x35A000DA` = 899678426 | `1` | auto-flip enabled |
| `AUDIO_SPEAKER_FLIP_COVER_STATUS` | `0x3D20001E` | `−10011` | not readable on 1002; **not in this firmware’s `BYDAutoFeatureIds`** |
| amp config | `0x4FD00030` | `7` | Devialet 20-channel |
| `AUDIO_SPEAKERS_NUMBER` | `0x4FD00008` | `26` | |
| `AUDIO_MEDIA_SOUND_SOURCE_STATE` | `0x4C60000C` | `1` | BYD source enum (1 = `LOCAL` in DiCar’s map) |
| instrument music source | `0x33F00030` on `dev=1007` | `21` | DiCar `QQ_MUSIC` underlying value |

Framework `BYDAutoFeatureIds$Audio` on this IVI uses the CanFD branch; those
decimals match the hex catalog in `com.byd.feature.audio.Audio`.

MediaSession at the dump: `com.byd.mediacenter` `state=1` (stopped), Bluetooth
disconnected. Nothing was playing.

### Paired capture — idle (2026-08-22 18:02)

User: nothing playing, covers in. `getInt` only. Source restored to `LOCAL=1`.

| FID | Idle payload |
| --- | --- |
| `AUDIO_RLSA_COFIG` `0x4C000010` | 0 |
| `AUDIO_RLSA_STATE` `0x4C00000B` | 0 |
| `AUDIO_SPEAKER_FLIP_COVER_CONFIG` `0x35A000D8` | 1 |
| `AUDIO_SPEAKER_FLIP_SETTING_STATUS` `0x35A000DA` | 1 |
| `AUDIO_MEDIA_SOUND_SOURCE_STATE` `0x4C60000C` | **1** |
| `AUDIO_DYNAUDIO_SOUND_FEATURES` `0x4C600008` | 4 |
| `AUDIO_SUBWOOFER_SET_STATE` `0x4C600010` | 0 |
| `AUDIO_FRONT_BACK_FIELD_STATE` `0x4C600030` | 16 |
| `AUDIO_INITIALIZATION_STATUS` `0x4C60003E` | 1 |
| `AUDIO_MASTER_VOLUME_STATE` `0x4FD00018` | **0** |
| `AUDIO_MEDIA_SOUND_MUTE_STATE` `0x4FD0002D` | **1** |
| amp `0x4FD00030` | 7 |
| `INSTRUMENT_MUSIC_SOURCE_SET` `0x33F00030` `dev=1007` | `0xffff` |

No `state:started` playback.

### Paired capture — BT playing, covers out (2026-08-22 18:06)

`com.android.bluetooth` + `com.byd.mediacenter` `PlaybackState=3`. Focus
`CONTENT_TYPE_BTMUSIC`. AAudio `state:started`. `volume_bt_music_speaker=4`.

| FID | Idle | Playing | Delta |
| --- | --- | --- | --- |
| `AUDIO_MEDIA_SOUND_SOURCE_STATE` `0x4C60000C` | 1 | 1 | **none** — real BT does *not* change this |
| `AUDIO_MASTER_VOLUME_STATE` `0x4FD00018` | 0 | **4** | **yes** |
| `AUDIO_MEDIA_SOUND_MUTE_STATE` `0x4FD0002D` | 1 | 1 | none |
| `AUDIO_SPEAKER_FLIP_SETTING_STATUS` `0x35A000DA` | 1 | 1 | none |
| `AUDIO_RLSA_STATE` `0x4C00000B` | 0 | 0 | none |
| `INSTRUMENT_MUSIC_SOURCE_SET` `0x33F00030` `dev=1007` | `0xffff` | **6** (DiCar BT) | **yes** |

Spoofing `0x1B10001C=6` was the wrong lever: live BT never writes that STATE.

### Paired capture — Yandex playing (2026-08-22 18:09)

`ru.yandex.music` `PlaybackState=3`. Focus `CONTENT_TYPE_MUSIC`. AudioTrack
started. `volume_music_speaker=2`. Mediacenter paused (`state=2`). BT disconnected.

| FID | Idle | BT (covers out) | Yandex | Notes |
| --- | --- | --- | --- | --- |
| `AUDIO_MEDIA_SOUND_SOURCE_STATE` | 1 | 1 | 1 | never the trigger |
| `AUDIO_MASTER_VOLUME_STATE` | 0 | 4 | **2** | Yandex already non-zero |
| `INSTRUMENT_MUSIC_SOURCE_SET` `dev=1007` | `0xffff` | **6** (BT) | **26** (`NON_WHITELISTED_APPS`) | only this distinguishes Yandex from BT |

Yandex already reports volume and a music source (`26`). The 18:09 dump had
covers still out from BT. **Clean Yandex** after power-cycle retract
(2026-08-22 18:22): covers in, `MASTER_VOL=2`, instrument **26**, `SRC=1`,
`PlaybackState=3`. Same MCU numbers as the contaminated dump — Yandex does
notify volume/source and still does not extend.

Bounded spoof while that session played: `setInt(1007, 0x33F00030, 6)`, GET=6.
**Covers did not extend.** Restored to `26`. Instrument source is not the motor.

Retract: pausing playback and writing `MASTER_VOL=0` did **not** pull the
covers in on a human wait. **Power cycle** is the proven retract (user
2026-08-22). After reboot, covers were in while `MASTER_VOL` still read **4**
and instrument source still **6** — so those two values are not a level
latch for “covers out”. Extend is likely an **edge** (play start on a
stock-controlled path), not “source=6 and volume>0”.

### Paired capture — clean Yandex still playing, covers in (2026-08-22 18:22+)

User: Яндекс играет, крышки внутри. `getInt` only. Same power-cycle-clean
session as the 18:22 row above; later reread while playback continued.

| FID | Parcel |
| --- | --- |
| `AUDIO_SPEAKER_FLIP_SETTING_STATUS` `0x35A000DA` | **1** |
| `AUDIO_MEDIA_SOUND_SOURCE_STATE` `0x4C60000C` | **1** |
| `AUDIO_MASTER_VOLUME_STATE` `0x4FD00018` | **3** |
| `INSTRUMENT_MUSIC_SOURCE_SET` `0x33F00030` `dev=1007` | **26** |
| `SET_MUSIC_MODE_STATE` CanFD `0x42E00040` `dev=1023` | **2** (music-rhythm lights **off**; 1=on, 2=off) |
| `BODYWORK_ONLINE_HAS_0X03A000` `0x3A000000` `dev=1001` | **1** (`hasFse()` is true) |
| `atmosphere_lamp_music_mode` `content://carsettings/config` | **1** (capability present) |
| `AUDIO_MEDIA_AUDIO_WORKING_MODE_SET` / `STATE_SET` | `−10011` (SET-only; no GET) |

MediaSession: `ru.yandex.music` `PlaybackState=3`. `com.byd.mediacenter`
`state=2` (paused). Focus stack top is Yandex `CONTENT_TYPE_MUSIC`; mediacenter
still listed with `CONTENT_TYPE_BTMUSIC` and `loss: LOSS`. Active playback:
Yandex `AudioTrack` `state:started` `CONTENT_TYPE_UNKNOWN`. No
`AudioVisualizerControl` / `AtmosphereLampCore` lines in logcat. `pm list users`
has only `0:Owner` — FSE user 999 is not running; the bodywork flag still makes
`hasFse()` true.

## The call (RLSA stack — this is the working lever)

Named for Dynaudio RLSA, but on this Devialet amp it is what actually drives the
flip covers; see "Direct cover control" above for the live proof and the 1/2
semantics.

```text
BYDAutoAudioDevice.set(new int[]{ AUDIO_RLSA_STATE_SET }, intValue)
  → BYDAutoDeviceManager.setInt(1002, 0x16300025, value)
  → native / autoservice setInt
```

Values (`BYDAutoAudioDevice` / `DevialetStatusData` / `DynaudioStatusData`):

| Value | Meaning |
| --- | --- |
| `1` | `RLSA_OPEN` / `RLSA_ENABLE` / `SPEAKER_FLIP_COVER_ENABLE` |
| `2` | `RLSA_CLOSE` / `RLSA_DISABLE` / `SPEAKER_FLIP_COVER_DISABLE` |

DiCar `CarAmplifierServiceImpl.setRLSAEnable(true)` writes **the same**
`0x16300025` = 1 even on this Devialet config. `isRLSAEnable()` on amp=7
(`7 & 31 ≠ 0`) reads `AUDIO_RLSA_STATE`, which is always 0 here — so the DiCar
boolean is a poor indicator of cover position on this car. The auto switch the
user already has on is `AUDIO_SPEAKER_FLIP_SETTING_STATUS`.

Voice (AutoVoice, no extra app):

| Command | Id | Action |
| --- | --- | --- |
| 升起扬声器 | `149502` | `setRLSAState(1)` |
| 降下扬声器 | `149503` | `setRLSAState(2)` |
| 打开扬声器自动升降 | `159574` | `MusicHornApiImpl.J(1)` → same `AUDIO_RLSA_STATE_SET` |

Bytecode of `J` is `sget AUDIO_RLSA_STATE_SET`, not a separate flip-cover SET.
`AUDIO_SPEAKER_FLIP_COVER_STATUS_SET` (`0x4EF52026`) exists only in the
MapHelper/DiCar *name* catalog; framework FeatureIds on this build do not
define it. Shell `setInt` of that FID returned `−10011`.

## Shell Binder

`autoservice` (`android.gui.BYDAutoServer`) in `libbydauto.so`:

| Transact | C++ | Parcel in |
| --- | --- | --- |
| `5` | `getInt` | `i32 dev` `i32 fid` |
| `6` | `setInt` | `i32 dev` `i32 fid` `i32 value` `null` (callback binder) |
| `7` | `getFloat` | `i32 dev` `i32 fid` |

```bash
# read (working)
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 1002 i32 899678424   # flip config
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 1002 i32 899678426   # auto setting
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 1002 i32 1275068427  # RLSA state

# RLSA motor SET — HAL returns 1, no cover motion on this Devialet car
adb -s 127.0.0.1:5555 shell service call autoservice 6 i32 1002 i32 372244517 i32 1 null

# BYD media source (this *does* change the readable STATE). 1=LOCAL, 6=BT, 26=NON_WHITELISTED
adb -s 127.0.0.1:5555 shell service call autoservice 6 i32 1002 i32 454033436 i32 6 null
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 1002 i32 1281359884
# restore
adb -s 127.0.0.1:5555 shell service call autoservice 6 i32 1002 i32 454033436 i32 1 null
```

`372244517` = `0x16300025`. `454033436` = `0x1B10001C`. Parcel `00000001` on
transact `6` is the raw HAL code that Java treats as success, not a failure.

Do **not** probe other transact codes with leftover `i32` args. Transact `10`
/`12` with three ints SIGSEGV’d `/system/bin/autoservice` (`BnBYDAutoServer::onTransact`,
stack overflow in `memset`). The process restarted; `getInt` worked again;
`com.byd.avc` stayed up. Repeat of that arity is an escalation, not a retry.

Java `BYDAutoAudioDevice.set` also requires `android.permission.BYDAUTO_AUDIO_SET`.
A product APK must not declare `BYDAUTO_*`. If a forced raise is promoted, it
goes through `DenzaLocalAdb` shell, same as BMS FIDs.

## Why stock music / BT work and Yandex does not

DiCar `MediaSource` / `MediaSourceProperty` write `0x33F00030`
(`INSTRUMENT_MUSIC_SOURCE_SET`) with a closed enum:

`LOCAL`, USB, SD, AUX, **BT**, RADIO, VIDEO, Kuwo, Kaola, Himalayan, Kugou,
Yunting, CarPlay, Android Auto, **Spotify**, **Amazon**, **Netease**, **QQ**,
Flo, DAB, Dynaudio Mobile, **`NON_WHITELISTED_APPS`**, SyncLink, DiAudio.

Yandex Music (`ru.yandex.music`) is not in that list. It still plays: the
output mix sees it ([audio-capture-findings.md](audio-capture-findings.md)).
Yandex is already classified as `NON_WHITELISTED_APPS` (26) with non-zero MCU
volume. Rewriting 26→BT 6 does not extend.

`IviVehicleAudioBroker.getCurrentDeviceMusicPlayState()` treats Yandex as
music: `usage=USAGE_MEDIA` and `contentType` in `{0, 2, 3, 5}`. That broker
is **not** the differentiator. At this stage the visualizer allowlist and
BT-specific stream were hypotheses; both were later rejected by live tests.
The confirmed working difference is MediaCenter's real LOCAL player path.

### Stock Java API behind that LOCAL pulse (corpus 2026-08-25, live-unverified as a motor)

Firmware zip `Di5.1_34.1.33.2605218.1` is the same build as the connected
IVI (`apps.setting.product.outswver=34.1.33.2605218.1`,
`eng.build20260705.011226`). Inner `Android/Target/android.zip` is
whole-file ciphertext (entropy ~8, no `PK`/`CrAU`); USB recovery decrypts
it. RapidUpdate uses AES-256-CBC (`AES256Utils`, hex key+IV from package
metadata / RSA-wrapped `encryptSecretKey`). UpgradeServer uses AES/ECB
with the strategy `secretKey` for the media ECU. Vendor `audio.primary`
is SELinux-blocked from shell; the Java/audio policy corpus below is
pulled from this same live system image.

BYD-patched `android.media.MediaPlayer.startImpl()`:

```text
Log "start mIsLocalSource = " + mIsLocalSource
if ((!mIsLocalSource || hasAudioTrack())
    && !mAudioManager.isStreamAllowed(mStreamType, mCurrentPackage))
    return;          // do not _start()
baseStart(0); _start();
```

`mCurrentPackage` is `ActivityThread.currentPackageName()`.
`setDataSource(FileDescriptor)` sets `mIsLocalSource=true`; `reset()`
clears it. For a local **music** file `hasAudioTrack()` is true, so the
allowlist still runs. `AudioServiceMultiUserImpl.isStreamAllowed` almost
always returns true except `STREAM_NOTIFICATION=5`. The important part
is the side effect, not the boolean:

```text
if (allowed
    && callingUid != 1013          // not mediaserver
    && stream ∈ {3, 2, 0, MIN_VALUE})  // MUSIC / RING / VOICE_CALL
    vehicleAudioBroker.setUseVehicleSpeaker();
```

`AudioTrack` / ExoPlayer never call this. That is why stream-14 tones
were audible and did not extend, and why Yandex (ExoPlayer) does not.

`IviVehicleAudioBroker.setUseVehicleSpeaker()`:

```text
setIviUseSelfAudio(REASON_MEDIA=4)
  → KeepPlaceHolderFocus(4) is false
  → abandon only the broker's own placeholder focus (not Yandex)
  → setUseVehicleSpeakerReasonToMCU(4)
       BYDAutoSettingDevice.set(
           SET_USE_AUDIO_SCENE_SET = 0x33F00024 = 871366692,
           intValue = 4)          // device type 1023
  → setUseIVIAudioClientToMCU(1)  // RSE_IVI_USE_AUDIO_PAD_TO_RSE_SET, routing
  → mCurrentUse = TYPE_IVI (1)
```

Reasons: `1=MUTE 2=RING 3=CALL 4=MEDIA`. Read-only GET on 2026-08-25
(idle, this session) already returned **4** on both `dev=1023` and
`dev=1007`, with auto-flip still 1. Scene=MEDIA is therefore not a
retracted-state latch. MCU may still treat each SET as a pulse; that
is unproven.

Public / platform calls, **no `BYDAUTO_*` permission** in
`BydAudioService` (same pattern as `startAudioOutput`):

| Call | Binder |
| --- | --- |
| `AudioManager.isStreamAllowed(STREAM_MUSIC, pkg)` | `IBydAudioService` transact **1**, side-effect raise |
| `VehicleAudioStateManager.getInstance(ctx).setUseVehicleSpeaker()` | transact **30** |
| `VehicleAudioStateManager.requestUseVehicleSpeaker()` | transact **32** (`setIviUseSelfAudio(4)` only if `mCurrentUse != TYPE_IVI`) |
| `MediaPlayer.setDataSource(fd); start()` on stream 3 | hits transact 1 from the app UID |

Do **not** shell-`setInt` `0x33F00024` until a watched retracted-state
run. Prefer the Java/`app_process` path so `Binder.clearCallingIdentity`
inside the broker owns the MCU write.

Next clean test, covers in after reboot, one owner:

1. Host dex: `VehicleAudioStateManager.setUseVehicleSpeaker()` (or
   `isStreamAllowed(3, opPackageName)`), no audio. If covers move, the
   motor is this binder pulse.
2. If not: a normal-UID `MediaPlayer` on a short local file (not
   MediaCenter, not ExoPlayer). If that moves them, the product API is
   MediaPlayer, not MediaCenter `playById`.
3. Do not retest `startAudioOutput`, RLSA, or instrument `0x33F00030`.

### Rejected stock visualizer path (`startAudioOutput`)

```text
BydAudioManager.startAudioOutput(pkg)          // or startAudioOutput() using opPackageName
  → IBydAudioService.startAudioOutput(pkg, cb) // TRANSACTION 20
  → BydAudioService (no permission check)
  → AudioVisualizerControl.startAudioVisualizer(pkg, binder, userId)
  → AudioVisualizerStore.getVisualizer(pkg)    // null unless allowlisted
  → MusicAtmosphereLamp.onStart()
  → AtmosphereLampCore.start(MODE_MUSIC=2)
  → BYDAutoAudioDevice.set(
        [AUDIO_MEDIA_AUDIO_WORKING_MODE_SET, AUDIO_MEDIA_AUDIO_WORKING_STATE_SET],
        intArrayValue = [2, 1]
    )
```

Stop is the same chain with `stopAudioOutput` / transact **21** → MCU
`[2, 2]`. `MSG_STOP_ALL` (ACC off / user switch) writes `intValue=2` to
`WORKING_STATE_SET` only.

Allowlist `MUSIC_VISUALIZER_PACKAGE` (`AudioVisualizerStore`):

| Package | How `startAudioOutput` is reached |
| --- | --- |
| `com.byd.mediacenter` | App calls it (`DiLinkAudioManager.startAudioOutput`). **Not** in `sAtmosphereLampPackages`, so `AudioTrackControl` will not auto-fire. |
| `cn.kuwo.kwmusiccar` | `AudioTrackControl.notifyTrackSessionId` auto-calls on play (`sAtmosphereLampPackages`) |
| `com.kugou.android.auto` | same |
| `com.byd.dynaudio_app` | same |
| `com.netease.cloudmusic.iot` | same |
| `com.byd.caraudioaosp` | same |
| `com.byd.minikaraoke` | karaoke visualizer, not music mode |

`ru.yandex.music` is on **neither** list. A `getVisualizer("ru.yandex.music")`
throws `IllegalArgumentException` and the session is not created.

Java class names are “atmosphere lamp” / music-rhythm lights.
`AmpVisualizerEffect` writes `setAmbientLightFreq` (spectrum →
`AUDIO_MCU_AMBIENT_LIGHT_SPECTRUM_DATA`) and is **lights**. The MCU pair
`AUDIO_MEDIA_AUDIO_WORKING_MODE/STATE` is what `AtmosphereLampCore` actually
sends as “media working”. Cover motion through that pair is **live
unproven**. Both may share the same MCU “music is working” edge.

Gate on `MusicAtmosphereLamp.setEnabled`:

```text
isMusicModeOn() = hasFse() || (atmosphere_lamp_music_mode==1 && SET_MUSIC_MODE_STATE==1)
```

On this car: config `atmosphere_lamp_music_mode=1`, user toggle
`SET_MUSIC_MODE_STATE=2` (off), **`hasFse()=true`** via bodywork
`0x3A000000=1`. So the music visualizer module is **enabled even with rhythm
lights off**. `VisualizerModule.start()` will call `onStart()` and write
`[2, 1]`. That made spoofing `startAudioOutput` worth one bounded test. The
live result below rejected it: the MCU writes happened, but the covers did not
move.

`BydAudioManager` is the platform `AudioManager`. Binder:
`ServiceManager.getService("audio").getExtension()` → `android.media.IBydAudioService`.
`startAudioOutput(String)` does **not** require `BYDAUTO_AUDIO_SET`. The
`IBinder` callback is `BydAudioManager.mICallBack`; if that binder dies, the
visualizer session is released. Keep the `AudioManager` instance alive.

Shell `service call audio 20` hits `IAudioService`, not the extension. There
is no `service call` to the extension binder.

### Rejected product-shaped call

From Denza Apps, while Yandex is playing, reflect the platform method with an
**allowlisted** package name. Do not declare `BYDAUTO_*`.

```java
AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
am.getClass().getMethod("startAudioOutput", String.class)
    .invoke(am, "com.byd.mediacenter");
// keep `am` alive — its Binder is the visualizer session
```

Stop on pause / teardown:

```java
am.getClass().getMethod("stopAudioOutput", String.class)
    .invoke(am, "com.byd.mediacenter");
```

Passing `ru.yandex.music` will not work (not on the allowlist). Passing
`dev.denza.apps` will not work. The MCU write still happens inside
`system_server` via `BYDAutoAudioDevice`.

Live falsification: the covers stayed in after
`startAudioOutput("com.byd.mediacenter")` while Yandex played, even though
logcat showed `AudioVisualizerControl.startAudioVisualizer` and
`AtmosphereLampCore MSG_START: 2`. This path is lights-only. Direct
`STREAM_BT_MUSIC` / `CONTENT_TYPE_BTMUSIC` playback was subsequently audible
but also failed; neither is the motor trigger.

| Approach | Status |
| --- | --- |
| `AUDIO_RLSA_STATE_SET` 1/2 | **This is the lever.** Live both ways 2026-08-25 — see "Direct cover control". The 2026-08-22 "dead" entry was a null experiment: same value rewritten, wrong observable. |
| Spoof `0x1B10001C=6` | **Dead.** STATE changed; covers did not. Live BT never uses this STATE. |
| Spoof instrument `0x33F00030` 26→6 | **Dead.** GET=6; covers did not (clean Yandex, user 2026-08-22). |
| Single-int `WORKING_STATE_SET` / media mute SET | HAL ack, mute GET unchanged. Stock shape is intArray `[mode, state]` on **two** FIDs. |
| `MASTER_VOL=0` after pause | Did not retract; power cycle did. |
| Voice 升起扬声器 | Untried on this car; same RLSA SET as above. |
| `IviVehicleAudioBroker` music-play bit | **Not the trigger.** Yandex already matches `{contentType 0/2/3/5}`. |
| `BydAudioManager.startAudioOutput("com.byd.mediacenter")` | **Dead for covers.** Java path live 18:46:12: MCU `[2, 1]` five times. User: covers did **not** move. Lights/visualizer only. |

## Hazard log (2026-08-22)

- **Do:** `getInt` transact `5` on the FIDs in the snapshot table.
- **Working control:** `setInt` transact `6` of `0x16300025` with `1` (extend) / `2` (retract) and a `null` binder. Live-proven both ways 2026-08-25. Edge-triggered — rewriting the current value is a no-op. Run it while someone can see the covers.
- **Do not:** transact `10`/`12`/`14`/`16` with `i32`-only parcels (array/float + binder methods). This session crashed `autoservice` that way; it came back. Not `com.byd.avc`. Stock WORKING_MODE/STATE uses `setIntArray`; do not invent an `i32` parcel for it.
- **Do not:** add `BYDAUTO_AUDIO_SET` to Denza Apps.
- **Do not:** keep guessing *other* `autoservice` SET FIDs for cover motion. Retract is `0x16300025 = 2`, not a power cycle.
- **Open side effect:** the first `2` left `AUDIO_SPEAKER_FLIP_SETTING_STATUS` reading `2`, and a later `1` did not restore it. The stock MediaCenter positive control then failed to raise the covers; there is no stock toggle on this car. Treat any direct retract as taking ownership of lift automation until the next ignition/power reset.
- Instrument spoof restore: Yandex native value is `26`.
- `startAudioOutput` is done: MCU write proven, no cover motion. Do not repeat it as a motor test.

## Live `startAudioOutput` (2026-08-22 18:46)

Yandex playing (`PlaybackState=3`), covers were in at the start of the
session. No APK install. Host dex via `app_process` (shell UID), binder held
45 s then `stopAudioOutput`.

Expected: `startAudioVisualizer` + MCU `[2, 1]`. Observed in logcat (pid 1139
`system_server`):

```text
18:46:12.863 D/AudioVisualizerControl: startAudioVisualizer: packageName = com.byd.mediacenter ... mAccOffState = false
18:46:12.864 D/AudioVisualizerControl: start Session{packageName='com.byd.mediacenter', ... userId=0
18:46:12.864 D/AtmosphereLampCore: start: mode = 2 mCurrentMode = 0
18:46:12.864 D/AtmosphereLampCore: MSG_START: 2 repeatCount = 5
18:46:12.864 D/AbsBYDAutoDevice: set featureIDs is int[]: [43e00040, 43e00044, ] intArrayValue is int[]: [2, 1, ]
# four more [2, 1] writes at +100 ms
18:46:57.866 D/AudioVisualizerControl: stopAudioVisualizer: packageName = com.byd.mediacenter
18:46:57.867 D/AtmosphereLampCore: stopAll
18:46:57.873 D/AtmosphereLampCore: MSG_STOP_ALL
18:46:57.873 D/AbsBYDAutoDevice: set featureID is 43e00044 intValue is 2
```

A first pulse at 18:43:26–18:44:56 returned the same client `OK` (binder
descriptor `android.media.IBydAudioService`); server logs for that pulse
rotated away. **User: covers did not move at all** on either pulse.
Visualizer MCU write is lights-only.

## Superseded route: HAL / trusted stream

The following was the plan before the clean stock-LOCAL test. It is retained
as negative-history context, not as the next action: direct BT-shaped focus,
stream 14, and content type 5 were all audible but did not extend the covers.

The pre-test assumption was that stock music and Bluetooth shared a trusted
audio shape distinct from Yandex. The completed tests showed otherwise:
Bluetooth audio is rendered by the BT stack, while the working LOCAL track is
rendered by MediaCenter on ordinary `STREAM_MUSIC=3`.

1. Corpus: `audio.primary.mediatek.so` / `audio_policy_configuration.xml` and
   `AudioSystem.setParameters` keys. No new `autoservice` SET.
2. Read-only paired dump while the user plays **stock mediacenter** (not BT)
   vs Yandex: started stream types, `dumpsys audio`, HAL parameters.
3. Only then a bounded play-test: an `AudioTrack` on `STREAM_BT_MUSIC` (14) /
   `CONTENT_TYPE_BTMUSIC` while Yandex is already playing. If covers stay in,
   a third-party app cannot spoof the trusted DSP bus, and the honest product
   answer is “no call for Yandex.”

## Corpus round 2 (2026-08-22, host-only)

Full decompile of `IntegrationMediaCenter.apk` (`reverse/mediacenter/`,
8 dexes) plus re-check of AutoVoice/MapHelper/openbyd originally suggested
that a BT-shaped public audio path might be sufficient. Live testing
**falsified that hypothesis**: focus 14 and audible stream-14/content-5 tones
did not extend the covers.

| Finding | Evidence | Consequence |
| --- | --- | --- |
| BT trust = plain `requestAudioFocus(l, streamType=14, GAIN)` | `DefaultBTMusicAudioFocusRequester.request()` (classes2) — public 3-arg API, **no BYD focus flags** | Any app can request focus on stream 14 exactly like BT music |
| Mediacenter never opens the BT audio stream | `BTMusicPlayerService` only sends AVRCP intents; no `AudioTrack`/`AAudio` in `com.byd.btmusic` | The stream-14 AAudio track is opened by the BT stack (`com.android.bluetooth` in the 18:06 dump), not by app Java |
| Custom streams / content types | bundled `DiLinkAudioConstants` + `android/media/AudioAttributes`: `STREAM_FM=12, AUX=13, BT_MUSIC=14, NAVI=15, MUTE=16, BTTS=17`; `CONTENT_TYPE_BTMUSIC=5`, `NAVI=6`, `MUTE=7`, `FM=8`, `AUX=9`, `BTTS=10` | Probe can build BT-shaped tracks (`stream 14` legacy ctor and/or `ct=5` attrs) |
| Stock local-music focus shape = Yandex shape | `PlaybackController` static `focusRequest`: `usage=USAGE_MEDIA(1)`, `legacyStreamType=3`, contentType default `UNKNOWN(0)` | Focus attrs **cannot** distinguish stock local from Yandex. If stock local really extends covers, the trigger keys on package identity or a HAL detail, not focus attrs |
| Stock local extend had not yet been captured clean | All round-2 paired dumps were idle / BT / Yandex | This gap was closed by the positive 20:19 LOCAL `playById` test above |
| Trusted BT attrs are queryable | `AAOSBTMusicAudioFocusRequester` builds attrs from `AudioBootstrap.getAudioInterface().getAudioAttributes(10001)` (`com.byd.audio.IBYDCarAudioService`) | Read-only way to fetch exact BT attributes instead of guessing |
| DiCar amp API has no flip drive | `ICarAmplifierManager` (bundled `com.byd.car.*`): speaker surface is RLSA/exterior/headrest only | No new DiCar lever; matches round 1 |
| Mediacenter music path does **not** use `setParameters` | Only `AAOSRadioPlayerImpl` does (radio band params); `audio_region_*` keys belong to the zone SDK, not playback start | `setParameters` demoted to a dump-diff hypothesis, not a prime suspect |
| Flip FIDs are catalog-only everywhere | `0x4EF52026`/`0x3D20001E` appear only as strings in `com.byd.feature.audio.Audio` catalogs (mediacenter, AutoVoice obfuscated `na`/`oa`, MapHelper); zero functional references | Dead route. The framework's one-FID setter does not accept an int-array value, so the proposed `0x4EF52026` intArray test was not structurally valid. |

Skeptical re-read of round 1: “the amp decides when to extend” is too
strong — Yandex is audible through the same amp, so plain signal-detect is
disproven. The BT path needs real A2DP frames from the BT stack; merely copying
its Java focus/stream attributes is insufficient. Stock LOCAL is now verified
and gives the simpler reproducible trigger.

## Live session protocol (probe: `tools/speaker_lift_probe.sh`)

Runs `tools/SpeakerLiftProbe.java` as shell UID via `app_process` (no APK
install, same technique as the 18:46 `startAudioOutput` pulse). Default
serial `127.0.0.1:5555`. One session owns the car at a time; the user watches
the covers during every active step.

Read-only baseline first, each from a **retracted** state where possible:

```bash
tools/speaker_lift_probe.sh capture idle          # nothing playing
# user starts stock LOCAL music in mediacenter:
tools/speaker_lift_probe.sh capture stock-local   # covers? dumpsys audio/session/FIDs
# user starts BT music:
tools/speaker_lift_probe.sh capture bt            # covers out expected
# user starts Yandex:
tools/speaker_lift_probe.sh capture yandex        # covers in expected
```

Historical active ladder (completed without moving the covers):

| Step | Command | Tests | If covers stay in |
| --- | --- | --- | --- |
| T1 | `run focus 14 20` | focus-on-stream-14 alone is the edge | **No motion** |
| T2 | `run tone 14 -1 8 focus` | stream-14 playback (legacy ctor, quiet 440 Hz sine) | **Audible; no motion** |
| T3 | `run attrs 10001`, then `run tone 14 5 8 focus` | faithful BT attrs (`CONTENT_TYPE_BTMUSIC=5`) | **Audible; no motion** |
| T4 | `run fidset 0x4EF52026 1` | proposed intArray shape on flip-cover SET | **Not run:** framework setter rejects this value shape |
| T5 | `run params "<k=v>"` | only with keys seen in the BT-vs-Yandex `dumpsys audio` diff | No evidence-backed key to test |

After every `tone`/`focus` step the probe abandons focus and releases the
track itself. `run snap` (read-only FID table via transact 5) can follow any
step. If the probe logs `WARNING: framework remapped stream 14 -> 3`, that
run tested nothing — record and move on.

Decision: T1-T3 were negative. The LOCAL `playById` test was positive, and the
`AUDIO_RLSA_STATE_SET` edge (2026-08-25) supersedes it as the product direction.

Do not retest `startAudioOutput`, `0x1B10001C`, instrument `0x33F00030`, or
single-int `0x4EF52026` — all proven dead. `AUDIO_RLSA_STATE_SET` is **not** in
that list: it is the working lever, and any future negative result on it must
state which value was written, what the previous value was, and that
`0x35A000DA` was the observable.

## Falsified 2026-08-25: the media-scene path

Both of these fired verifiably and moved nothing, from covers-in with Yandex
playing (`tools/vehicle_speaker_pulse.sh`):

| Test | Evidence it really ran | Covers |
| --- | --- | --- |
| `VehicleAudioStateManager.setUseVehicleSpeaker()` | `IviVehicleAudioBroker: setIviUseSelfAudio ... reason = REASON_MEDIA` → `setUseVehicleSpeakerReasonToMCU reason = 4` → `AbsBYDAutoDevice: set featureID is 33f00024 intValue is 4` | no |
| plain `MediaPlayer` on the same local OGG, stream 3, not MediaCenter | broker pulse plus `getCurrentDeviceMusicPlayState active config pid = 20087 uid = 2000`, `musicPlayState: true` | no |

The positive control ran straight afterwards in the same dirty session — the
MediaCenter `playById` pulse still raised the covers — so these are clean
falsifications, and the "needs a reboot / clean retracted state" worry is dead.

`SET_USE_AUDIO_SCENE_SET` (`0x33F00024`, dev 1023) already reads `4` at idle, so
scene=MEDIA is not a cover latch and the broker pulse is not the motor.

Sweeping all 762 audio FIDs immediately before and after a real MediaCenter
raise (`tools/speaker_lift_ab.sh`) produced exactly **one** change in the whole
set — `INSTRUMENT_MUSIC_SOURCE_SET` `26 → 1` on dev 1007 — and **nothing** on
dev 1002. The amp reports no cover state at all.

One gate detail worth keeping: native `AudioTrack` also calls `isStreamAllowed`,
but arrives as **uid 1013** (mediaserver), and
`AudioServiceMultiUserImpl.isStreamAllowed` fires the broker only when
`callingUid != 1013`. So only an app-side `MediaPlayer` can ever raise that
pulse — ExoPlayer/`AudioTrack` cannot, no matter how they are configured.

## The latch: after one successful open, the app cannot open again (live v39, 2026-08-27)

Reported from the seat: Yandex Music was opened, the covers came out and went back
in about a second later. The obvious reading — our own `2`, 350 ms, `1` pair — is
wrong twice over. That pair is close-then-open, which reads as in-then-out, and
more decisively **the app had sent nothing at all**: `last_command_boot` in
`speaker_covers.xml` stood at 10:26 while the sighting was around 16:40, six hours
later. Whatever moved the covers, it was not this product. The amplifier lowering
them on its own is already recorded above.

What the app did instead is nothing, and that is the defect. `reconcile` skips a
wish equal to the last one asked for, and re-asks only when that ask *failed*:

```kotlin
if (targetOpen == raised) {
    val retryDue = askFailed && nowMs - askedAtMs >= retryGuardMs
    if (!retryDue) return null
}
```

`raised` is what this app last asked for, not where the covers are — the field says
so itself. So one successful open latches the automaton for the life of the
process: no later signal can raise the covers again. Not three seconds of sustained
sound, not another player coming to the foreground, not a fresh MediaSession.
Reproduced: launching Yandex Music deliberately produced zero commands and left
`last_command_value` untouched, because the same package had already been in a pane
earlier in that process.

The escape is only a wish that *differs* — thirty minutes of confirmed silence, or a
manual close — after which an open is a change again.

**Why the obvious fix is not one.** Re-sending `1` moves nothing: the property is
edge-triggered and already holds `1`. Raising covers the amplifier lowered behind
the app's back needs the `2`, 350 ms, `1` pair, which is the twitch this feature was
reported for in the first place.

The asymmetry worth designing around: that pair is invisible exactly when the covers
are already down, because its close moves nothing and only the open is seen. It is
visible only when they are up. Nothing on this car can tell those apart, so the
choice is about which moments are worth paying a possible twitch for. A fresh piece
of user intent — a known player reaching the foreground, a new active MediaSession —
is rare, and is the moment when the covers are most likely to be down.

Not fixed: the owner is watching the covers first, since the position is exactly
what the code cannot see and a person in the seat can.

### Resolved by design, 2026-08-28

Not by making the automation re-open, but by having it stop promising to. The
automaton no longer reconciles anything mid-episode: it offers one opening per
boot and then leaves the covers to the buttons and to the ignition cycle. Under
that contract the latch is not a defect - it is the whole behaviour, said out
loud on the tile («Автоматика отработала — дальше кнопками»).

The recovery path for covers the amplifier lowered behind the app's back is the
«Поднять» button, which now always forces a real edge: `2`, 350 ms, `1` whenever
the property is already holding `1`. The asymmetry examined above is what makes
that affordable - the pair is invisible on covers that are in, so the only cost is
a twitch on covers that were already out, paid by a driver who is looking at them
and pressed anyway.

**A second defect found while designing the fix, and worse than the latch.**
`onManualPosition` fed the same `reconcile` as every automatic signal, so a press
equal to the last wish was swallowed by the same-wish skip. «Поднять» was
therefore a silent no-op in exactly the situation it exists for: the automaton
believed the covers were up, the amplifier had lowered them, and the one control
that could have answered did nothing and said nothing. Both halves of the fix
live in the manual path - it bypasses the skip in the automaton, and it carries a
`manual` flag into `SpeakerCoverMotorProtocol.needsEdgeBreak`, which is the only
caller allowed to buy a forced edge.

The reported bug that motivated the redesign is the same shape from the other
direction: covers closed by hand during music came back out ~3 s later, because
the sustained-sound fallback was allowed to overrule a press. Both are the same
rule missing - a level must never outrank an act.
