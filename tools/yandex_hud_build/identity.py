"""YandexHUD identity. Increment BUILD_NUMBER for each new distributed build.

Rebuilding the same revision keeps its number; this is not a local build counter.
The base version belongs to the SHA-pinned upstream APK.
Builds 5 to 29 were the ADAS bridge's, distributed from the branch stock-sender-parity;
build 30 was the first 31.1.4 rebase. Build 31 replaces its defective split merge,
which omitted every resource value supplied by config.xxhdpi.
Build 31 was built with the `current` renderer profile and never distributed; build 32
carries the car's profile, RENDERER_PROFILE below.
"""

# The base: the reviewed upstream APK, its own version and where it lives. A new Navigator
# changes only this block (experiments/yandex-hud-module/README.md, "New upstream version");
# build.py checks it against the APK's own manifest.
APK = 'captures/yandex-hud-31.1.4-20261102/yandex-navigator-31.1.4-arm64-merged.apk'
PIN = '29229369e3d7fe6b643112e3f8d80ef1dcae6fc1379bd479a60f126b48435188'
BASE_VERSION_NAME = '31.1.4'
BASE_VERSION_CODE = 739735800

LABEL = 'YandexHUD'
BUILD_NUMBER = 32
# The map the car drives with since 29.8.1-YandexHUD.4: the stock HUD camera, the close zoom
# when slow, our colours. build.py's default, so a release cannot fall back to `current`.
RENDERER_PROFILE = 'stock-prototype'
VERSION_NAME = f'{BASE_VERSION_NAME}-{LABEL}.{BUILD_NUMBER}'
VERSION_CODE = BASE_VERSION_CODE + BUILD_NUMBER
