"""HUD Speed Mod identity: the SHA-pinned upstream base and the mod number on top of it.

A new upstream release changes only the base block (see experiments/hud-speed-module/README.md,
"New upstream version"); build.py checks the base against the APK's own manifest.
MOD_NUMBER is the owner's to choose for each distributed build.
"""
# The base: the reviewed upstream APK, its own version and where it lives.
APK = 'captures/hud-speed-20261018/hudspeed-radarbase-release-79000000.apk'
PIN = '393220d5d139adf191e616b60126ecf888e102d4310be1d527a8df5aad967e49'
BASE_VERSION_NAME = '79.0'
BASE_VERSION_CODE = 79000000

# The upstream name plus the mark of a modified build; never another brand.
LABEL = 'HUD Speed Mod'
MOD_NUMBER = 1
VERSION_NAME = f'{BASE_VERSION_NAME}-mod.{MOD_NUMBER}'
VERSION_CODE = BASE_VERSION_CODE + MOD_NUMBER
PACKAGE = 'air.StrelkaHUDFREE'
