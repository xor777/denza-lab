"""YandexHUD identity. Increment BUILD_NUMBER for each new distributed build.

Rebuilding the same revision keeps its number; this is not a local build counter.
The base version belongs to the SHA-pinned upstream APK.
"""

LABEL = 'YandexHUD'
BUILD_NUMBER = 25
BASE_VERSION_NAME = '29.8.1'
BASE_VERSION_CODE = 739494300
VERSION_NAME = f'{BASE_VERSION_NAME}-{LABEL}.{BUILD_NUMBER}'
VERSION_CODE = BASE_VERSION_CODE + BUILD_NUMBER
