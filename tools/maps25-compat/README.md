# Google Maps on the owner's Denza — verified 2026-10-14

The durable result and its limits are in
[Google Maps compatibility](../../docs/instrument-display-findings.md#google-maps-compatibility-2026-10-14).
This is a version-specific host-side experiment, not a Denza Apps dependency.
Do not apply these inputs/offset assumptions to another Maps version without
inspection and another live check.

## Working artifacts

Local, git-ignored directory: `captures/maps-microg-20261014/`.

| File | Version / package | SHA-256 |
|---|---|---|
| `maps-denza-25.16.03-bydhud.apk` (installed, direct HUD bridge) | 25.16.03.747108139 (1068022637), `app.revanced.android.apps.maps` | `6c17fd32a99ff260c3688a77d154b665695f74062772ed10ec5516fa3a9aa658` |
| `maps-denza-25.16.03-fixed.apk` (standalone rollback, uninstalled) | 25.16.03.747108139 (1068022637), `app.morphe.android.apps.maps` | `a20a92cc8f16b397bc9d2c2a4e8b5b892ea9fbc2b287d972d9f5aabc8570ea63` |
| `microg-0.3.13.3-hw.apk` | 0.3.13.3.250932-hw (250932006), `app.revanced.android.gms` | `b449bf82067b4040d19282e7f03d176f46bec117dfe72478caf497f55c6f43b4` |

These APK hashes were read back from their installed `/data/app/.../base.apk` files.
Maps is signed by the local Morphe key, SHA-256
`9b01a863587b8a164b4d1a24e8e78ed85642063271abb7c420cba580d055891c`.
Keep `morphe-data/morphe.keystore` for compatible updates; never commit it.

To launch the currently installed HUD-compatible variant through the already-authorized transport:

```sh
adb -s 127.0.0.1:5555 shell am start -W \
  -n app.revanced.android.apps.maps/com.google.android.maps.MapsActivity
```

Before a future reinstall, inspect installed package/signing identity. Do not
blindly uninstall an account-bearing microG: removal deletes its account state.
An incompatible signer requires removal; a compatible update uses `install -r`.
The owner explicitly authorized removing the broken variants during this run.
After installation, allow Maps and microG in BYD **Disable background Apps**
(their switches must be unchecked), grant Maps location and follow the microG
setup in the findings. BYD reset the Maps switch on each APK update.

## Why the extra fix is required

A Cronet netlog captured the Google backend's `API_KEY_ANDROID_APP_BLOCKED`
response, gRPC status 7, when the client sent the Morphe package and our APK
certificate. microG's spoof metadata alone does not affect these direct
HTTP headers. `ClientIdentity.java` restores the original Maps package name
and original Google signing certificate SHA-1 in **only** `X-Android-Package`
and `X-Android-Cert`. The physical installed package/signature stays local.
Other headers, including authentication and API keys, pass through unchanged.

`IdentityPatch.java` injects this normalizer before 30 Chromium `addHeader`
call sites in the tested APK. Changed application classes go into a new dex to
avoid overflowing the nearly-full original dex method table. Chromium API
classes are never moved: microG's Dynamite class loader needs their identity.
Native library ZIP storage must be preserved and page-aligned, or Android
rejects installation. The final APK contains no network logging helper.

`AdaptPatch.java` adapts the **JVM CLI classes** of the upstream 1.2.0 MPP:
it removes one invocation each of the newer notification and BYD audio
transforms, consuming their context argument. All required GmsCore/location
hooks remain. It asserts one match per omitted transform. This adapted MPP is
not an Android-manager bundle: its bundled dex is intentionally unmodified.

## Exact inputs

| Input | Source | SHA-256 |
|---|---|---|
| Clean universal Google Maps 25.16.03.747108139 / 1068022637 | [APKMirror](https://www.apkmirror.com/apk/google-inc/maps/google-maps-25-16-03-747108139-release/google-maps-25-16-03-747108139-2-android-apk-download/) | `151b9a5e25550d5bf312fb63b58ccaeeb105b1433f84986a25daec669fd89a57` |
| Morphe Desktop 1.18.1 CLI | [Official JAR](https://github.com/MorpheApp/morphe-desktop/releases/download/v1.18.1/morphe-desktop-1.18.1-all.jar) | `1b506ab5f03d16a2f65026d5e0e1910d01fc1e2152f21eaeb44ed2f30856597b` |
| gmaps-patches 1.2.0 | [Official MPP](https://github.com/fangkampanat/gmaps-patches/releases/download/v1.2.0/patches-1.2.0.mpp) | `587c7efef607d6c82c154d050b9410ae38bb9e58fda4c069820197ea50bcaa73` |
| Requested ReVanced microG | [Official release asset](https://github.com/ReVanced/GmsCore/releases/download/v0.3.13.3.250932/app.revanced.android.gms-250932006-hw-signed.apk) | `b449bf82067b4040d19282e7f03d176f46bec117dfe72478caf497f55c6f43b4` |

The clean Maps APK has Google signing lineage: SHA-1
`38918a453d07199354f8b19af05ec6562ced5788` for Android 7–12 and
`bd32424203e0fb25f36b57e5aa356f9bdd1da998` for Android 13+. The working header
uses the original certificate, matching the GmsCore patch's metadata.

## Reproduce, without installing anything

Download the verified clean APK as `maps25-clean.apk`, the JAR as
`morphe-cli.jar`, and MPP as `gmaps-patches-1.2.0.mpp` into a disposable
**git-ignored** build directory. Copy the saved `morphe-data/` beside the JAR
if the new APK must update the installed one. These large inputs were removed
after the successful run. Keep the two working APKs and signing key.

From the repository root, set `work` to that directory and run:

```sh
set -e
work=captures/maps-microg-20261014
src=tools/maps25-compat
jdk=/opt/homebrew/opt/openjdk@21/bin
bt=/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0
mkdir -p "$work/classes" "$work/helper-classes" "$work/helper-dex"
"$jdk/javac" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -d "$work/classes" "$src/AdaptPatch.java"
"$jdk/java" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -cp "$work/classes" AdaptPatch "$work/gmaps-patches-1.2.0.mpp" "$work/maps25-compat.mpp"
"$jdk/java" -Xmx4g -jar "$work/morphe-cli.jar" patch "$work/maps25-clean.apk" \
  -p "$work/maps25-compat.mpp" --exclusive -e 'Google Maps for MicroG-RE-BYD' \
  --bytecode-mode FULL --striplibs arm64-v8a -o "$work/maps-base.apk" \
  -t "$work/patch-work" -r "$work/patch-result.json"
"$jdk/javac" --release 8 -d "$work/helper-classes" "$src/ClientIdentity.java"
"$jdk/jar" cf "$work/helper.jar" -C "$work/helper-classes" .
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  "$bt/d8" --min-api 26 --output "$work/helper-dex" "$work/helper.jar"
"$jdk/javac" -cp "$work/morphe-cli.jar" -d "$work/classes" \
  "$src/IdentityPatch.java" "$src/SignApk.java"
"$jdk/java" -Xmx4g -cp "$work/classes:$work/morphe-cli.jar" IdentityPatch \
  "$work/maps-base.apk" "$work/maps-unsigned.apk" "$work/helper-dex/classes.dex"
"$bt/zipalign" -p -f 4 "$work/maps-unsigned.apk" "$work/maps-aligned.apk"
"$jdk/java" -cp "$work/classes:$work/morphe-cli.jar" SignApk \
  "$work/morphe-data/morphe.keystore" "$work/maps-aligned.apk" "$work/maps-fixed.apk"
"$bt/apksigner" verify --print-certs "$work/maps-fixed.apk"
shasum -a 256 "$work/maps-fixed.apk"
```

Expected patch result: PATCHING/REBUILDING/SIGNING all successful; header
normalizer matched 30 calls. The newly signed output hash may differ because
ZIP metadata/signing is not a reproducible-build guarantee; verify signer,
package/version and behavior. The immutable saved working APK has the exact
hash above. Delete disposable inputs/build outputs after preserving the final
APK and key; do not delete the already-verified pair during a rebuild.

Validation was map rendering, new place search/photos, live location dot,
and route computation on the head unit. Driving, voice output, account sync
and HUD data were separate checks for this original standalone APK. The
following section adds the BYD HUD bridge and records its separate acceptance.

## Adding the BYD HUD direct channel (2026-10-14 investigation)

The compatible direct bridge comes from `sunlixWhyNotAvailable/byd-hud` commit
`ce99ebac962b16a91221cc10c8d67821c9db4b89`. Compile its `NavInfoLogger.java`
against Android 34, build a helper JAR and convert it with D8 (`--min-api 26`).
Run `GmapsDiagnosticPatcher patch` from the upstream compiled host classes with
Dexlib2 2.5.2 and Guava 27.1-android on the classpath:

```sh
java -cp "$bydhud_classes:$host_libs" \
  com.bydhud.gmapsdiag.patcher.GmapsDiagnosticPatcher patch \
  "$work/maps-denza-25.16.03-fixed.apk" "$scratch/bridge-unsigned.apk" \
  "$scratch/bridge-dex/classes.dex" "$scratch/bridge-report.json"
```

The version-specific inspection must select `25.16`, produce one payload/start/
stop/register/bitmap hook, and report `directReady=true`. `releaseReady=false`
by itself means the separate PiP/audio/GmsCore-dialog optional components are
not all patched; do not interpret it as a successful full upstream installer.
The working local Maps already has its separate microG/header fixes.

BYD HUD expects `app.revanced.android.apps.maps`. The local `RenamePackage.java`
changes package string literals in DEX without moving Chromium or renaming
Morphe extension classes; `rename_package_resources.py` changes the matching
binary manifest/resource string-pool values while retaining indices and styles.
Compile the Java helper with Dexlib2/Guava and run:

```sh
java -cp "$helpers:$host_libs" RenamePackage \
  "$scratch/bridge-unsigned.apk" "$scratch/renamed-dex.apk"
python3 tools/maps25-compat/rename_package_resources.py \
  "$scratch/renamed-dex.apk" "$scratch/renamed-resources.apk"
```

**Do not omit package visibility.** The upstream Android installer applies
`HudPackageVisibilityPatcher` separately; its DEX CLI does not. Our
`AddHudVisibility.java` wrapper reuses that upstream helper. Compile it with
`$bydhud_classes` and run before alignment/signing:

```sh
java -cp "$helpers:$bydhud_classes" AddHudVisibility \
  "$scratch/renamed-resources.apk" "$scratch/visible-unsigned.apk"
"$bt/zipalign" -p -f 4 "$scratch/visible-unsigned.apk" "$scratch/aligned.apk"
java -cp "$helpers:$signing_libs" SignApk "$work/morphe-data/morphe.keystore" \
  "$scratch/aligned.apk" "$work/maps-denza-25.16.03-bydhud.apk"
"$bt/apksigner" verify --print-certs "$work/maps-denza-25.16.03-bydhud.apk"
"$bt/aapt" dump xmltree "$work/maps-denza-25.16.03-bydhud.apk" AndroidManifest.xml
```

The manifest must include the `com.bydhud.app` package query. Without it, the
Android 13 bridge rejects the unchanged identity check with
`missing_creator_package` / `creatorUid=-1`; adding an unsafe trust bypass is
unnecessary. Expected final bridge APK SHA-256:
`6c17fd32a99ff260c3688a77d154b665695f74062772ed10ec5516fa3a9aa658`.
Read the on-device hash after installation, re-allow background operation, then
confirm actual `HELLO` and current-step frames. Keep the original working APK
until this new package passes the same Maps acceptance plus channel registration.


## Stationary AR replay and reference output (2026-10-14)

`prepare_hud_replay.py` reads the owner's retained SOME/IP JSONL and writes bounded
replay files for `HudReplayActivity` in the isolated `hud-frames-probe`. It never
connects to a device. No global mock location, vehicle speed or other vehicle
property is changed. Private traces/coordinates remain under ignored `captures/`.
The probe must be the only guidance sender; stop Maps/BYD HUD and leave Denza
Apps guidance disabled for the replay. It clears at the end and unbinds.

```sh
python3 tools/maps25-compat/prepare_hud_replay.py \
  captures/maps-microg-20261014/bydhud-live/someip-tx.jsonl \
  captures/maps-microg-20261014/replay/denza-full \
  --start 2026-10-14T20:09:32 --duration-ms 25000 --denza-apps-reference
```

This mode runs the encoder methods extracted from the actual Denza Apps source
and uses its 350 ms cadence. Geometry comes from the saved trace, so it does not
validate Google acquisition or reproduce physical vehicle motion. Synthetic
packet goldens for the BYD HUD tests are generated by
`python3 tools/maps25-compat/denza_packet_reference.py`.

Owner results: changing field 2 alone did not help; the full original encoder
and cadence replay also retained the long intermediate animation. The AR fix is
**not accepted**. Compact Google arrow display and disappearance at route stop
were accepted earlier. There is no retained known-good Yandex wire trace yet.

The full-output BYD HUD build is local, not installed. Connection was lost after
the final replay had cleared and stopped. Device cleanup is pending: uninstall
`dev.denza.hudframes.probe`, restore Maps/BYD HUD from force-stop, and disable the
extra debug logs after copying final evidence. Preserve the existing tunnel and
ADB keys. No fork, push or PR has been created; the user must first review the
exact PR text.
