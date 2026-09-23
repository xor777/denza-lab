# AVC stock state probe

Disposable app-UID probe for the stock `com.byd.avc` Messenger
(`com.byd.avc/.AutoVideoService`, action `com.byd.action.AVCSERVICE`), which the
OTA image shows exported with no permission and no caller check.

```bash
./gradlew -Pexperiments :avc-stock-probe:assembleDebug
adb install -r experiments/avc-stock-probe/build/outputs/apk/debug/avc-stock-probe.apk
adb shell am start -n dev.denza.avcstock.probe/.ProbeWakeActivity
adb shell am broadcast -n dev.denza.avcstock.probe/.ProbeReceiver -a dev.denza.avcstock.probe.STATE
# Persistent vendor setting; the stock default is 0. Only with the owner's consent:
adb shell am broadcast -n dev.denza.avcstock.probe/.ProbeReceiver \
  -a dev.denza.avcstock.probe.SET_LIGHT --ei value 1
```

`STATE` answers `mode=` (`5000` idle, `5095` PIP left, `5096`/`5099` PIP right)
and `light_choice=` (`0` PIP with the left image on the meter, `1` PIP with both
on the head unit, `2` full-screen, `3` off). `SET_LIGHT` writes the choice and
reports the state before and after. The receiver is guarded by `DUMP`, so only
the shell can drive it.

Findings live in `docs/instrument-display-findings.md`, "The stock turn-signal
camera, read from the firmware".
