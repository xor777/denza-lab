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
| Auto-lift setting | **Already on.** `AUDIO_SPEAKER_FLIP_SETTING_STATUS` (`0x35A000DA`) = 1. That is why stock music and Bluetooth extend the covers without a third-party helper. |
| Official raise/lower call | **On RLSA cars.** `setInt(audio=1002, AUDIO_RLSA_STATE_SET=0x16300025, 1/2)`. Same FID for voice 升起/降下扬声器 and DiCar `setRLSAEnable`. **This Devialet car has no RLSA** (`AUDIO_RLSA_COFIG=0`), so that SET is a HAL no-op. |
| Shell `0x16300025=1` | **Parcel `1` = HAL success** (`BYDAutoManager.setInt` maps raw `1` → Java `0`). `AUDIO_RLSA_STATE` stayed 0. Covers did **not** move (user live 2026-08-22). |
| Shell media-source SET | **Wrong lever.** Write changed STATE `1`→`6`, no extend. Live BT with covers out still has STATE **1**. |
| Play-state / mute SETs | HAL `1`. `AUDIO_MEDIA_SOUND_MUTE_STATE` stayed `1` after SET `0`. Single-int `WORKING_STATE_SET` has no GET. Wrong shape vs stock (stock writes an **intArray** of two FIDs). |
| Confirmed raise trigger | **Stock LOCAL playback through `com.byd.mediacenter`.** From a clean reboot with the covers retracted, `MediaAction=14` (`playById`) started a 1.729 s local OGG; the user confirmed that the covers extended. The track was `STREAM_MUSIC=3`, not BT stream 14. |
| Why Yandex Music does not extend | **Not** MCU source/volume, focus attributes, `STREAM_BT_MUSIC=14`, or `startAudioOutput`. The decisive observed difference is that the working player belongs to `com.byd.mediacenter` and reaches native MediaPlayer as `mIsLocalSource=true`. Whether the gate uses package identity or that native local-source flag remains unproved. |
| Stock Java visualizer call | **Lights only.** `startAudioOutput` → transact **20** → `AtmosphereLampCore` → MCU `[0x43E00040, 0x43E00044] = [2, 1]`. Live 18:46:12. No cover motion. |
| Normal `/data/app` APK | **Blocked** for `BYDAutoAudioDevice.set` (`android.permission.BYDAUTO_AUDIO_SET`). **Not blocked** for `startAudioOutput`: `BydAudioService` does not check permission or that the caller owns `pkg`. Do not put `BYDAUTO_*` in a product manifest. |

Do not treat “turn on auto-lift” as the missing step. It is already 1. Shell
`autoservice` SETs, BT-shaped focus/tracks, and the stock visualizer
`startAudioOutput` path do not move these covers. Do not guess more FIDs. The
working lever is a short stock LOCAL playback pulse through MediaCenter.

## Confirmed working path (2026-08-22 20:19)

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

A practical pre-roll is now available: register a very short local clip,
address it by MediaCenter's canonical-path hash, play it through stock LOCAL,
then pause MediaCenter and restore the user's previous player. A silent or
lower-volume clip and the resume hand-off still need a clean retracted-state
integration test; only the audible 1.729 s stock notification is proven here.

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

## The call (stock RLSA stack — dead on this car)

This is the Dynaudio/voice raise. DiCar `setRLSAEnable` still writes it on
amp=7. Live: HAL success, no cover motion. The Devialet auto-lift path is
`startAudioOutput` below.

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
| `AUDIO_RLSA_STATE_SET=1` | **Dead.** HAL success, no motion. DiCar `setRLSAEnable` writes this same FID even on Devialet amp=7. |
| Spoof `0x1B10001C=6` | **Dead.** STATE changed; covers did not. Live BT never uses this STATE. |
| Spoof instrument `0x33F00030` 26→6 | **Dead.** GET=6; covers did not (clean Yandex, user 2026-08-22). |
| Single-int `WORKING_STATE_SET` / media mute SET | HAL ack, mute GET unchanged. Stock shape is intArray `[mode, state]` on **two** FIDs. |
| `MASTER_VOL=0` after pause | Did not retract; power cycle did. |
| Voice 升起扬声器 | Untried on this car; same RLSA SET as above. |
| `IviVehicleAudioBroker` music-play bit | **Not the trigger.** Yandex already matches `{contentType 0/2/3/5}`. |
| `BydAudioManager.startAudioOutput("com.byd.mediacenter")` | **Dead for covers.** Java path live 18:46:12: MCU `[2, 1]` five times. User: covers did **not** move. Lights/visualizer only. |

## Hazard log (2026-08-22)

- **Do:** `getInt` transact `5` on the FIDs in the snapshot table.
- **Bounded:** `setInt` transact `6` of `0x16300025` with `1`/`2` and a `null` binder, only while someone can see the covers, then restore `2` if they moved.
- **Do not:** transact `10`/`12`/`14`/`16` with `i32`-only parcels (array/float + binder methods). This session crashed `autoservice` that way; it came back. Not `com.byd.avc`. Stock WORKING_MODE/STATE uses `setIntArray`; do not invent an `i32` parcel for it.
- **Do not:** add `BYDAUTO_AUDIO_SET` to Denza Apps.
- **Do not:** keep guessing `autoservice` SET FIDs for cover motion. Retract is power cycle.
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

Decision: T1-T3 were negative. The subsequent clean LOCAL `playById` test was
positive and is the reproducible product direction.

Do not retest `startAudioOutput`, `0x1B10001C`, instrument `0x33F00030`, or
single-int `0x4EF52026` — all proven dead on 2026-08-22. Do not hand-craft
autoservice parcels beyond transact 5 (getInt); the probe issues SETs only
through framework client classes.
