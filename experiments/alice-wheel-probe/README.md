# Alice wheel probe

Disposable ordinary-UID test of physical voice key `320` → Navigator microphone, only when
Navigator has input focus on the central display. Findings and live evidence are owned by
[shortcuts-automation-findings.md](../../docs/shortcuts-automation-findings.md#app-uid-wheel-acceptance).
No product module depends on this probe.

The service starts disarmed. An explicit shell command arms it for at most 180 seconds and one
click attempt. Other keys and ineligible presses pass unchanged. An accepted press owns both
DOWN and UP, even if focus changes; a new foreground/node check can cancel the later click.
The probe never launches Navigator, sends a broadcast to BYD, injects a key or changes car data.
It uses the exact view ID observed in Navigator 29.8.1; missing controls fail closed for Alice.

Build with JDK 17 and the repository Android SDK:

```sh
./gradlew -Pexperiments :alice-wheel-probe:testDebugUnitTest :alice-wheel-probe:assembleDebug
```

Before installing, follow the repository's single-owner live-car method and record the installed
Navigator/Denza Apps hashes, enabled accessibility services and crash buffer. The APK is
`build/outputs/apk/debug/alice-wheel-probe-debug.apk` under this directory. Start
`dev.denza.alicewheel.probe/.WakeActivity` once to wake its process; wait for package registration,
then append `dev.denza.alicewheel.probe/dev.denza.alicewheel.probe.VoiceKeyService` to the existing
`enabled_accessibility_services` value. Preserve all other components. Require both a bound
service and a successful status response before arming.

The host helper performs installation, captures baseline/hash/log evidence, waits disarmed and
cleans up on a STOP file or after ten minutes. Use a new output directory for every run:

```sh
python3 tools/alice_wheel_capture.py captures/alice-wheel-NEW-RUN
# In another terminal, after the run:
touch captures/alice-wheel-NEW-RUN/STOP
```

```sh
adb -s 127.0.0.1:5555 shell am broadcast \
  -n dev.denza.alicewheel.probe/.ProbeReceiver \
  -a dev.denza.alicewheel.probe.COMMAND --es op status
adb -s 127.0.0.1:5555 shell am broadcast \
  -n dev.denza.alicewheel.probe/.ProbeReceiver \
  -a dev.denza.alicewheel.probe.COMMAND --es op arm --ei seconds 120
```

Use `op disarm` to cancel an armed window. The receiver requires the caller's `DUMP` permission;
the APK itself requests none. Observe `AliceWheelProbe` logcat or the service dump. Test both
foreground Navigator (Alice MIC active, no stock wake) and another foreground app (stock BYD,
no Alice click), with physical presses. Denza Apps before build 68 ignores `320`, so its media/star
filters did not compete in the recorded run. Build 68 adds the product Alice handler; do not
enable this probe alongside it.

Afterwards disarm, remove only this probe's component from the current enabled-service list,
uninstall `dev.denza.alicewheel.probe`, restore any temporary log properties and compare hashes,
service set and crash buffer. The recorded host runner and before/after evidence are in
`captures/alice-navigator-20261011/`; it cleaned up on STOP, timeout or exception. The reusable
host helper lives at [`tools/alice_wheel_capture.py`](../../tools/alice_wheel_capture.py).
