"""YandexHUD identity. Increment BUILD_NUMBER for each new distributed build.

Rebuilding the same revision keeps its number; this is not a local build counter.
The base version belongs to the SHA-pinned upstream APK.
Builds 5 to 29 were the ADAS bridge's, distributed from the branch stock-sender-parity;
build 30 was the first 31.1.4 rebase. Build 31 replaces its defective split merge,
which omitted every resource value supplied by config.xxhdpi.
"""

# The base: the reviewed upstream APK, its own version and where it lives. A new Navigator
# changes only this block (experiments/yandex-hud-module/README.md, "New upstream version");
# build.py checks it against the APK's own manifest.
APK = 'captures/yandex-hud-31.1.4-20261102/yandex-navigator-31.1.4-arm64-merged.apk'
PIN = '29229369e3d7fe6b643112e3f8d80ef1dcae6fc1379bd479a60f126b48435188'
BASE_VERSION_NAME = '31.1.4'
BASE_VERSION_CODE = 739735800

LABEL = 'YandexHUD'
BUILD_NUMBER = 31
VERSION_NAME = f'{BASE_VERSION_NAME}-{LABEL}.{BUILD_NUMBER}'
VERSION_CODE = BASE_VERSION_CODE + BUILD_NUMBER
