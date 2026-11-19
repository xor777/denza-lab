"""YandexHUD identity. Increment BUILD_NUMBER for each new distributed build.

Rebuilding the same revision keeps its number; this is not a local build counter.
The base version belongs to the SHA-pinned upstream APK.
Builds 5 to 29 were the ADAS bridge's, distributed from the branch stock-sender-parity;
build 30 was the first 31.1.4 rebase. Build 31 replaces its defective split merge,
which omitted every resource value supplied by config.xxhdpi.
Build 31 was built with the `current` renderer profile and never distributed; build 32
carries the car's profile, RENDERER_PROFILE below. Build 33 carries the fixes of the
2026-11-19 pre-release review (docs/hud-output-architecture.md, section 14).
"""

# The base: the reviewed upstream APK, its own version and where it lives. A new Navigator
# changes only this block (experiments/yandex-hud-module/README.md, "New upstream version");
# build.py checks it against the APK's own manifest.
APK = 'captures/yandex-hud-31.1.4-20261102/yandex-navigator-31.1.4-arm64-merged.apk'
PIN = '29229369e3d7fe6b643112e3f8d80ef1dcae6fc1379bd479a60f126b48435188'
BASE_VERSION_NAME = '31.1.4'
BASE_VERSION_CODE = 739735800

# The release signer: every YandexHUD since the first was signed with this certificate, so an
# install over an earlier build needs it, and Denza Apps hosts only it (HudHostGate). The key lives
# in the main checkout's captures/yandex-hud-signing/experiment.p12, never in git; build.py refuses
# a release signed by anything else (review 2026-11-19, round 2, finding 10).
SIGNER_SHA256 = 'df4100bda2c4f581c6bb5251746f1b2bdee2f7662ead31a46ef01ae25f9d39c6'

LABEL = 'YandexHUD'
BUILD_NUMBER = 33
# The map the car drives with since 29.8.1-YandexHUD.4: the stock HUD camera, the close zoom
# when slow, our colours. build.py's default, so a release cannot fall back to `current`.
RENDERER_PROFILE = 'stock-prototype'
VERSION_NAME = f'{BASE_VERSION_NAME}-{LABEL}.{BUILD_NUMBER}'
VERSION_CODE = BASE_VERSION_CODE + BUILD_NUMBER
