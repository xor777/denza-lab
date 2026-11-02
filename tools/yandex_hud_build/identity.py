"""YandexHUD identity. Increment BUILD_NUMBER for each new distributed build.

Rebuilding the same revision keeps its number; this is not a local build counter.
The base version belongs to the SHA-pinned upstream APK.
Builds 5 to 29 were the ADAS bridge's, distributed from the branch stock-sender-parity;
the next build from main is 30, so that it installs over any of them.
"""

# The base: the reviewed upstream APK, its own version and where it lives. A new Navigator
# changes only this block (experiments/yandex-hud-module/README.md, "New upstream version");
# build.py checks it against the APK's own manifest.
APK = 'captures/hud-lanes-20261011/yandex-installed.apk'
PIN = '3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3'
BASE_VERSION_NAME = '29.8.1'
BASE_VERSION_CODE = 739494300

LABEL = 'YandexHUD'
BUILD_NUMBER = 4
VERSION_NAME = f'{BASE_VERSION_NAME}-{LABEL}.{BUILD_NUMBER}'
VERSION_CODE = BASE_VERSION_CODE + BUILD_NUMBER
