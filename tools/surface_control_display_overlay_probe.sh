#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 11 ]]; then
  echo "usage: surface_control_display_overlay_probe.sh SOURCE_DISPLAY TARGET_DISPLAY SRC_LEFT SRC_TOP SRC_RIGHT SRC_BOTTOM DST_LEFT DST_TOP DST_RIGHT DST_BOTTOM DURATION_MS" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/SurfaceControlDisplayOverlayProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
javac_bin="${javac_bin:-$(command -v javac)}"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-}"
remote_jar="/data/local/tmp/denza-display-overlay-probe.jar"
probe_tmp="$(mktemp -d /tmp/denza-display-overlay.XXXXXX)"

adb_cmd=("$adb_bin")
if [[ -n "$serial" ]]; then
  adb_cmd+=("-s" "$serial")
fi

cleanup() {
  "${adb_cmd[@]}" shell rm -f "$remote_jar" >/dev/null 2>&1 || true
  rm -r "$probe_tmp"
}
trap cleanup EXIT

mkdir -p "$probe_tmp/classes" "$probe_tmp/dex"
"$javac_bin" -source 8 -target 8 -cp "$android_jar" \
  -d "$probe_tmp/classes" "$source_file"
"$d8" --lib "$android_jar" --output "$probe_tmp/dex" \
  "$probe_tmp/classes/dev/denza/tools/SurfaceControlDisplayOverlayProbe.class"
(
  cd "$probe_tmp/dex"
  zip -q "$probe_tmp/probe.jar" classes.dex
)

"${adb_cmd[@]}" push "$probe_tmp/probe.jar" "$remote_jar" >/dev/null
"${adb_cmd[@]}" shell \
  CLASSPATH="$remote_jar" \
  app_process /system/bin dev.denza.tools.SurfaceControlDisplayOverlayProbe "$@"
