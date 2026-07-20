#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
  echo 'usage: fse_cross_message_probe.sh JSON' >&2
  exit 2
fi

wait_seconds="${FSE_CROSS_WAIT_SECONDS:-3}"
if [[ ! "$wait_seconds" =~ ^[0-9]+$ ]] || (( wait_seconds < 1 || wait_seconds > 120 )); then
  echo "FSE_CROSS_WAIT_SECONDS must be an integer in [1, 120]" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/FseCrossMessageProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
aapt2="$android_sdk/build-tools/36.0.0/aapt2"
apksigner="$android_sdk/build-tools/36.0.0/apksigner"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-}"
manifest="$script_dir/fse-cross-message-probe/AndroidManifest.xml"
debug_keystore="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
probe_tmp="$(mktemp -d /tmp/denza-fse-cross-message.XXXXXX)"
message_base64="$(printf '%s' "$1" | base64 | tr -d '\n')"
capture_pid=""

adb_cmd=("$adb_bin")
if [[ -n "$serial" ]]; then
  adb_cmd+=("-s" "$serial")
fi

cleanup() {
  if [[ -n "$capture_pid" ]]; then
    kill "$capture_pid" >/dev/null 2>&1 || true
    wait "$capture_pid" 2>/dev/null || true
  fi
  rm -r "$probe_tmp"
}
trap cleanup EXIT

mkdir -p "$probe_tmp/classes" "$probe_tmp/dex"
"$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
  -d "$probe_tmp/classes" "$source_file"
"$d8" --lib "$android_jar" --output "$probe_tmp/dex" \
  "$probe_tmp/classes/dev/denza/tools/FseCrossMessageProbe.class"
"$aapt2" link -o "$probe_tmp/probe-unsigned.apk" \
  --manifest "$manifest" -I "$android_jar" --min-sdk-version 26 --target-sdk-version 27
zip -q -j "$probe_tmp/probe-unsigned.apk" "$probe_tmp/dex/classes.dex"
"$apksigner" sign --ks "$debug_keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$probe_tmp/probe.apk" "$probe_tmp/probe-unsigned.apk"

"${adb_cmd[@]}" install -r "$probe_tmp/probe.apk" >/dev/null
"${adb_cmd[@]}" logcat -v time -T 1 \
  -s BYDCrossService:D AbsBYDCrossDevice:D FseCrossMessageProbe:I \
  Launcher.CrossUtil:I '*:S' >"$probe_tmp/logcat.txt" &
capture_pid=$!
sleep 1
"${adb_cmd[@]}" shell am start -W \
  -n dev.denza.tools.fsecrossmessageprobe/dev.denza.tools.FseCrossMessageProbe \
  --es message_base64 "$message_base64" >/dev/null
sleep "$wait_seconds"
kill "$capture_pid" >/dev/null 2>&1 || true
wait "$capture_pid" 2>/dev/null || true
capture_pid=""
rg 'FF 30 00 15|ff300015|FseCrossMessageProbe|Launcher.CrossUtil' \
  "$probe_tmp/logcat.txt"
