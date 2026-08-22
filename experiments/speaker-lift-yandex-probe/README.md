# Speaker lift Yandex probe

Disposable normal-UID APK for one live question on the tested DiLink 5.1 car:
does the verified stock MediaCenter LOCAL pulse still extend the Devialet
covers when it is initiated by an ordinary installed app as Yandex Music opens?

The APK has no Activity, launcher icon, overlay, BYD permission, Bluetooth
path, or local ADB client. One accessibility service observes only foreground
window package transitions. On entry into `ru.yandex.music` it starts the
known `MediaAction=14` LOCAL track through the exported MediaCenter service,
then sends `MediaAction=2` after one second. It never requests `withui`.

Build and live-test lifecycle is owned by
`tools/speaker_lift_yandex_probe.sh`. The script preserves all existing
accessibility components when enabling or disabling this probe. The live path
is unverified until a clean post-reboot acceptance run succeeds.

Its application id is `dev.denza.speakerlift.yandexprobe`, intentionally
separate from the earlier installed `dev.denza.speakerlift.probe` bridge APK.
