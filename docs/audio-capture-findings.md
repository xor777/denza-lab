# Audio capture findings

What a normal app on this head unit can observe of the audio the car is
playing. The driving question was whether a spectrum analyser — jumping bars
under the trip panel — can be fed from real playback rather than faked.

Verified on the live car (DiLink5.1, `BYD AUTO`, Android 13 / API 33) with
`:audio-probe` on 2026-08-14.

## Verdict

**Yes.** `android.media.audiofx.Visualizer` attached to audio session 0 (the
global output mix) sees other applications' audio. It is the only capture path
of the ones tested that works, and it is source-agnostic: nothing about it is
specific to the app that happens to be playing.

| Path | Result |
| --- | --- |
| `Visualizer` on session 0 (output mix) | **Works.** FFT + waveform + peak/RMS, 60/60 reads |
| `AudioPlaybackCapture` via `MediaProjection` | Initialises, then returns pure silence |
| Cabin microphone | Not pursued — the output mix makes it unnecessary |

## Evidence

The probe was tested against a signal of known amplitude so that a pass could
not be confused with plausible-looking noise. A 25 s WAV generated at amplitude
4000/32768 (**-18.27 dBFS**) was played through **VLC**, a separate process, and
the Visualizer in the probe reported **peak = -18.26 dB**. The level matches to
a hundredth of a decibel, so the effect is measuring the other app's signal.

Real playback was then confirmed with **Yandex Music** (`ru.yandex.music`,
`PlaybackState state=3`): `signal=true`, waveform deviation 121 of a possible
128, `peak = +0.23 dB`, `rms = -8.84 dB`.

Collapsed into 16 log-spaced bands (40 Hz – 16 kHz), consecutive frames 200 ms
apart move independently rather than tracking overall loudness together, which
is what a spectrum display needs:

```
BANDS 40  6667765311223333
BANDS 44  6666776433333333
BANDS 48  6666775311223333
BANDS 52  5555776443333333
```

`AudioPlaybackCapture` failed differently and worth recording: it is not that
the capture cannot be built. The `AudioRecord` initialises, the consent token is
accepted, and reads return — 245 760 frames arrived, every sample zero
(`peak=0`) while VLC was audibly playing. A capture that succeeds and yields
silence is the failure mode to expect here, so any future check of this path
must assert on levels, not on a returned status.

## Sample rate is misreported — calibrate to 48 kHz

`Visualizer.getSamplingRate()` returns 44100, but the mix genuinely runs at
**48 kHz** (every output thread in `dumpsys media.audio_flinger` reports 48000).
Two reference tones pin this down: 440 Hz landed in bin 9 and 1000 Hz in bin 21,
which is only consistent with 48 kHz.

With `captureSize = 1024` the bin width is therefore **46.875 Hz**, not 43.07.
Trusting the reported rate skews every band edge by about 9% — audible as bars
that respond to the wrong part of the music.

## Permissions

- `RECORD_AUDIO` is **required**. Revoking it fails construction with
  `RuntimeException: Cannot initialize Visualizer engine, error: -3`.
- `MODIFY_AUDIO_SETTINGS` is declared alongside it.
- The op actually recorded is **`RECORD_AUDIO_OUTPUT`**, not `RECORD_AUDIO`.
  No microphone access is noted, so the mic privacy indicator does not appear
  and there is no contention with the always-on voice assistant, which holds the
  built-in mic continuously at `AUDIO_SOURCE_VOICE_RECOGNITION`.

The user-facing cost is a microphone permission prompt for a feature that never
touches the microphone. That is a UX question, not a technical blocker.

## Implementation notes

- Set `SCALING_MODE_AS_PLAYED`. The default normalising mode scales silence up
  into convincing noise, so bars would dance with nothing playing.
- **The first read returns all zeros** while the capture buffer fills; the probe
  saw `BANDS 00 0000000000000000` every run. Discard the first frame or the
  display starts with a visible collapse.
- `setCaptureSize` must be called before `setEnabled(true)`.
- `MEASUREMENT_MODE_PEAK_RMS` gives a cheap, correctly scaled loudness figure
  without touching the FFT.
- The FFT is 8-bit, so resolution above roughly 5 kHz is coarse; the top bands
  sat near a constant floor. Log-spaced bands and a dB (not linear) magnitude
  mapping are what make the low end legible.
- Keep the signal gate on the raw per-band dB values. The visual spectral tilt
  adds as much as roughly 18 dB at the top of the range; applying the gate after
  that correction turns quiet treble hiss into false playback. The automatic
  scale retains 5 dB above its recent loudest corrected band, leaving about
  12.5% steady-state headroom instead of pumping quiet audio almost full-height.
- Session 0 already carries a vendor effect chain (`Effect ID 11`) on
  `AudioOut_D`. Attaching a Visualizer alongside it caused no observed trouble.

## Audio stack context

- Bluetooth phone audio arrives as **A2DP Sink** (`com.android.bluetooth`,
  uid 1002, custom `CONTENT_TYPE_BTMUSIC`, stream type 14) fronted by
  `com.byd.mediacenter`. It appears in AudioFlinger as an ordinary mixer track
  on `AudioOut_D` — the same thread that carries the session 0 effect chain.
- Input devices include `Remote Submix In` and `Echo Ref In`, both of which need
  privileged permissions and were not usable.
- The device is a `user` build; `adb root` is refused and there is no `su`, so
  privileged and `/system/priv-app` routes are closed.

## Bluetooth: verified working

A2DP sink playback **is** captured, confirmed on the live car against BT audio
from a phone: `signal=true`, `peak = -9.06 dB`, 60/60 reads, and moving bands.

Two wrong theories were tried and killed on the way, both worth recording so
they are not re-run:

- *"BT is routed to its own bus."* It is not. `dumpsys media.audio_policy` puts
  BYD's custom `AUDIO_STREAM_BT_MUSIC` in the same `STRATEGY_MEDIA` and on the
  same `AUDIO_DEVICE_OUT_SPEAKER` as ordinary `AUDIO_STREAM_MUSIC`.
- *"BT never reaches the mixer as PCM."* It does. While playing, AudioFlinger
  shows a live track on `AudioOut_D` — the very thread carrying the session 0
  effect chain — from the bluetooth uid: `Format 0x5 (PCM_FLOAT)`, stereo,
  44100, `ST=14 (BT_MUSIC)`, `Usg=1`, `CT=5 (BTMUSIC)`.

The apparent failure was in the app, not the platform. See the shared-effect
note below.

## The session 0 effect is shared, and that bites

Session 0 carries one effect chain for the whole device, so a second client
attaching a `Visualizer` receives the *existing* instance rather than a fresh
one — already enabled. `setCaptureSize()` then throws
`IllegalStateException: setCaptureSize() called in wrong state: 2`, the
construction fails, and the analyser shows nothing while the capture path itself
is perfectly healthy. Disable the effect before configuring it.

This also means a probe left attached can break the product app, and vice versa.

## Attaching can fail transiently — retry it

Creating the effect can fail for reasons that pass: the audio server busy, a
previous client still releasing session 0, the permission grant landing a moment
later. A one-shot attach at panel start therefore leaves the analyser dead until
the process restarts, which reads as "it sometimes doesn't start". An attached
effect can also stop delivering without reporting an error, which looks exactly
like silence. Both need a watchdog that retries while unattached and rebuilds a
capture that has gone quiet.

## Not yet verified

- **Apple Music** (`com.apple.android.music`), which may use a protected output
  path that behaves differently.
