#!/usr/bin/env bash
set -euo pipefail

display_id="${1:?usage: surface_control_mirror_probe.sh DISPLAY_ID [OUTPUT_PNG] [SCALE]}"
output_png="${2:-/tmp/denza-display-${display_id}.png}"
scale="${3:-0.5}"

if [[ ! "$display_id" =~ ^[0-9]+$ ]]; then
  echo "DISPLAY_ID must be a non-negative integer" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/SurfaceControlMirrorProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
javac_bin="${javac_bin:-$(command -v javac)}"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-}"
remote_jar="/data/local/tmp/denza-surface-mirror-probe.jar"
remote_png="/data/local/tmp/denza-surface-mirror-probe.png"
probe_tmp="$(mktemp -d /tmp/denza-surface-mirror.XXXXXX)"

adb_cmd=("$adb_bin")
if [[ -n "$serial" ]]; then
  adb_cmd+=("-s" "$serial")
fi

cleanup() {
  "${adb_cmd[@]}" shell rm -f "$remote_jar" "$remote_png" >/dev/null 2>&1 || true
  rm -r "$probe_tmp"
}
trap cleanup EXIT

mkdir -p "$probe_tmp/classes" "$probe_tmp/dex"
"$javac_bin" -source 8 -target 8 -cp "$android_jar" \
  -d "$probe_tmp/classes" "$source_file"
"$d8" --lib "$android_jar" --output "$probe_tmp/dex" \
  "$probe_tmp/classes/dev/denza/tools/SurfaceControlMirrorProbe.class"
(
  cd "$probe_tmp/dex"
  zip -q "$probe_tmp/probe.jar" classes.dex
)

"${adb_cmd[@]}" push "$probe_tmp/probe.jar" "$remote_jar" >/dev/null
"${adb_cmd[@]}" shell \
  CLASSPATH="$remote_jar" \
  app_process /system/bin dev.denza.tools.SurfaceControlMirrorProbe \
  "$display_id" "$remote_png" "$scale"
"${adb_cmd[@]}" pull "$remote_png" "$output_png" >/dev/null
echo "$output_png"
