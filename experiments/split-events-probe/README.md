# Split events probe

Disposable normal-UID APK for three live questions on the tested DiLink 5.1
car, all answered by the firmware corpus and never tried from an app before
(see "The firmware read whole" in
[split-screen-findings.md](../../docs/split-screen-findings.md)):

1. can an ordinary app subscribe to the split area push through
   `android.app.UnionActivityManager.registerScreenAreaInfoForMultiListener`;
2. does it receive `ACTION_CLOSE_SYSTEM_DIALOGS` with `reason=homekey` from a
   runtime receiver;
3. can it flip the split gate with a raw `activity_task` transaction 126.

Answered yes, yes, yes on 2026-09-23; the numbers are in the findings.

The APK requests no permission. Its surface is one receiver, `ProbeReceiver`,
guarded by `android.permission.DUMP` so only the shell can drive it, and
`ProbeWakeActivity`, an invisible Activity that only starts the process - BYD's
self-start gate drops a broadcast to a UID with no live process.

| Action (`dev.denza.splitevents.probe.`) | Extras | Answer |
| --- | --- | --- |
| `ARM` | none | registers the area listener and the `homekey` receiver on one thread |
| `READ` | none | `area=`, `raw30=`, `rootN=`, `panesN=<taskId>:<component>,…` for areas 1, 2, 4, with timings |
| `GATE` | `value` 0 or 1 | raw tx126, `gate_us=` |
| `REPORT` | none | every event since `ARM`: kind, value, `elapsed=`, `wall=` |

Every answer carries a per-process `nonce=`, so a later `REPORT` shows whether
the process that saw the events is still the one answering.

`GATE` changes global firmware state. The firmware answers an unchanged value
with `The split-screen mode has not changed, return` (tag
`BydSmartMultiController`), so a run starts with the value the gate should
already have, reads that line to learn the original state, and restores it
before leaving if the line was not there.

```bash
./gradlew -Pexperiments :split-events-probe:assembleDebug
adb install -r experiments/split-events-probe/build/outputs/apk/debug/split-events-probe.apk
adb shell am start -n dev.denza.splitevents.probe/.ProbeWakeActivity
adb shell am broadcast -n dev.denza.splitevents.probe/.ProbeReceiver -a dev.denza.splitevents.probe.ARM
adb shell am broadcast -n dev.denza.splitevents.probe/.ProbeReceiver -a dev.denza.splitevents.probe.READ
adb shell input keyevent KEYCODE_HOME
adb shell am broadcast -n dev.denza.splitevents.probe/.ProbeReceiver -a dev.denza.splitevents.probe.REPORT
adb uninstall dev.denza.splitevents.probe
```
