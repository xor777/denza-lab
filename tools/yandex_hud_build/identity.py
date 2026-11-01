"""YandexHUD identity. Increment BUILD_NUMBER for each new distributed build.

Rebuilding the same revision keeps its number; this is not a local build counter.
The base version belongs to the SHA-pinned upstream APK.
Builds 5 to 29 were the ADAS bridge's, distributed from the branch stock-sender-parity;
the next build from main is 30, so that it installs over any of them.
"""

LABEL = 'YandexHUD'
BUILD_NUMBER = 4
BASE_VERSION_NAME = '29.8.1'
BASE_VERSION_CODE = 739494300
VERSION_NAME = f'{BASE_VERSION_NAME}-{LABEL}.{BUILD_NUMBER}'
VERSION_CODE = BASE_VERSION_CODE + BUILD_NUMBER
