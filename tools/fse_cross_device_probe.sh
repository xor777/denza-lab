#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/FseCrossDeviceProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
aapt2="$android_sdk/build-tools/36.0.0/aapt2"
apksigner="$android_sdk/build-tools/36.0.0/apksigner"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-}"
manifest="$script_dir/fse-cross-device-probe/AndroidManifest.xml"
debug_keystore="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
probe_tmp="$(mktemp -d /tmp/denza-fse-cross-device.XXXXXX)"

adb_cmd=("$adb_bin")
if [[ -n "$serial" ]]; then
  adb_cmd+=("-s" "$serial")
fi

cleanup() {
  rm -r "$probe_tmp"
}
trap cleanup EXIT

mkdir -p "$probe_tmp/classes" "$probe_tmp/dex"
"$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
  -d "$probe_tmp/classes" "$source_file"
"$d8" --lib "$android_jar" --output "$probe_tmp/dex" \
  "$probe_tmp/classes/dev/denza/tools/FseCrossDeviceProbe.class"
"$aapt2" link -o "$probe_tmp/probe-unsigned.apk" \
  --manifest "$manifest" -I "$android_jar" --min-sdk-version 26 --target-sdk-version 27
zip -q -j "$probe_tmp/probe-unsigned.apk" "$probe_tmp/dex/classes.dex"
"$apksigner" sign --ks "$debug_keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$probe_tmp/probe.apk" "$probe_tmp/probe-unsigned.apk"

"${adb_cmd[@]}" install -r "$probe_tmp/probe.apk" >/dev/null
"${adb_cmd[@]}" shell am force-stop dev.denza.tools.fsecrossprobe
"${adb_cmd[@]}" shell am start -W \
  -n dev.denza.tools.fsecrossprobe/dev.denza.tools.FseCrossDeviceProbe >/dev/null
sleep 1
probe_pid="$("${adb_cmd[@]}" shell pidof dev.denza.tools.fsecrossprobe | tr -d '\r')"
if [[ -z "$probe_pid" ]]; then
  echo "FSE cross-device probe process exited before logs were collected" >&2
  exit 1
fi
"${adb_cmd[@]}" logcat -d --pid="$probe_pid" -s FseCrossDeviceProbe:I '*:S'
