# DiCar media probe

Disposable probe for one question: can an ordinary app UID report a playback
state to the car through the stock media service, and does the amplifier treat
that report as the cue to raise the dash speaker covers?

Background and the reasoning that led here:
[docs/speaker-lift-findings.md](../../docs/speaker-lift-findings.md), section
"N9 vs Z9GT".

## What it does

`LOOKUP` queries the exported provider `CarServiceProvider` for
`ICarMediaService`, exactly as the vendor SDK does, and reports whether a binder
came back. It reads nothing from the vehicle and writes nothing to it.

`STATE` calls `setPlaybackState` with `PLAYING`, `PAUSED` or `CLOSED`. This is
the one write. The car turns it into `INSTRUMENT_MUSIC_STATE_SET` `0x43E0000A`
on the instrument device, which is the signal that separates the stock
pause-resume (covers came out) from a plain track change (they did not).

The probe requests no permissions. The receiver is guarded by `DUMP`, which uid
2000 holds and an ordinary app cannot be granted, so nothing but a host shell
can drive it.

`com/byd/spi/ipc/cursor/BinderCursor.java` recreates one vendor class name so
the platform can unmarshal the binder the provider returns. Only the wire shape
is reproduced.

## Build and install

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :dicar-media-probe:assembleDebug
adb -s 127.0.0.1:5555 install -r -g \
  experiments/dicar-media-probe/build/outputs/apk/debug/dicar-media-probe.apk
```

## Run

The firmware self-start gate ignores a broadcast to a UID with no live process,
so wake the process first.

```bash
adb -s 127.0.0.1:5555 shell am start -n dev.denza.dicarmedia.probe/.ProbeWakeActivity
adb -s 127.0.0.1:5555 shell am broadcast -a dev.denza.dicarmedia.probe.LOOKUP \
  -n dev.denza.dicarmedia.probe/.ProbeReceiver
```

The write, once a live run is agreed:

```bash
adb -s 127.0.0.1:5555 shell am broadcast -a dev.denza.dicarmedia.probe.STATE \
  -n dev.denza.dicarmedia.probe/.ProbeReceiver --es state PAUSED
adb -s 127.0.0.1:5555 shell am broadcast -a dev.denza.dicarmedia.probe.STATE \
  -n dev.denza.dicarmedia.probe/.ProbeReceiver --es state PLAYING
```

Watch the car side with:

```bash
adb -s 127.0.0.1:5555 shell logcat -v time | grep -E 'DiCarMediaProbe|43e0000a|SET_PROPERTY'
```

## Result so far

`LOOKUP` on the Z9GT, 2026-09-03:

```
op=lookup uid=10148 binder=yes descriptor=com.byd.car.feature.media.ICarMediaService alive=true
```

An ordinary app UID that asks for no permissions gets a live handle on the stock
media service. The transport half of the experiment is settled; what the
amplifier does with the report still needs an N9.

## Status

Disposable. Delete once the speaker-lift question is closed, per the probe rules
in [CLAUDE.md](../../CLAUDE.md).
