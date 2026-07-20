#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${1:-12}"
if [[ ! "$timeout_seconds" =~ ^[0-9]+$ ]] || (( timeout_seconds < 1 || timeout_seconds > 60 )); then
  echo "usage: fse_upgrade_info_probe.sh [TIMEOUT_SECONDS:1-60]" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/FseUpgradeInfoProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-}"
remote_jar="/data/local/tmp/denza-fse-upgrade-info-probe.jar"
probe_tmp="$(mktemp -d /tmp/denza-fse-upgrade-info.XXXXXX)"

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
"$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
  -d "$probe_tmp/classes" "$source_file"
"$d8" --lib "$android_jar" --output "$probe_tmp/dex" \
  "$probe_tmp/classes/dev/denza/tools/FseUpgradeInfoProbe.class" \
  "$probe_tmp/classes/dev/denza/tools/FseUpgradeInfoProbe\$ResponseListener.class"
(
  cd "$probe_tmp/dex"
  zip -q "$probe_tmp/probe.jar" classes.dex
)

"${adb_cmd[@]}" push "$probe_tmp/probe.jar" "$remote_jar" >/dev/null
"${adb_cmd[@]}" shell \
  CLASSPATH="$remote_jar" \
  app_process /system/bin dev.denza.tools.FseUpgradeInfoProbe "$timeout_seconds"
