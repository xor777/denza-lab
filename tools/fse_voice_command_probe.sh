#!/usr/bin/env bash
set -euo pipefail

if (( $# == 0 )); then
  echo 'usage: fse_voice_command_probe.sh TEXT...' >&2
  exit 2
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/FseVoiceCommandProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-}"
probe_tmp="$(mktemp -d /tmp/denza-fse-voice-command.XXXXXX)"
command_text="$*"
command_base64="$(printf '%s' "$command_text" | base64 | tr -d '\n')"
aapt2="$android_sdk/build-tools/36.0.0/aapt2"
apksigner="$android_sdk/build-tools/36.0.0/apksigner"
manifest="$script_dir/fse-voice-command-probe/AndroidManifest.xml"
debug_keystore="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

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
  "$probe_tmp/classes/dev/denza/tools/FseVoiceCommandProbe.class" \
  "$probe_tmp/classes/dev/denza/tools/FseVoiceCommandProbe\$1.class"
"$aapt2" link -o "$probe_tmp/probe-unsigned.apk" \
  --manifest "$manifest" -I "$android_jar" --min-sdk-version 26 --target-sdk-version 31
zip -q -j "$probe_tmp/probe-unsigned.apk" "$probe_tmp/dex/classes.dex"
"$apksigner" sign --ks "$debug_keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$probe_tmp/probe.apk" "$probe_tmp/probe-unsigned.apk"

"${adb_cmd[@]}" install -r "$probe_tmp/probe.apk" >/dev/null
"${adb_cmd[@]}" shell am start -W \
  -n dev.denza.tools.fsevoiceprobe/dev.denza.tools.FseVoiceCommandProbe \
  --es command_base64 "$command_base64"
