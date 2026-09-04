#!/usr/bin/env bash
# Build and run the bounded, read-only turn-signal CAN capture.
#
# The device-side process subscribes only to the stock BigData callback. It does
# not install an APK, alter the CanDataCollect table, issue setters, wake an ECU,
# or restart a service. Raw captures stay outside the repository by default.
#
# Usage:
#   tools/raw_can_turn_probe.sh build
#   tools/raw_can_turn_probe.sh run [seconds] [queue-capacity] [output-file]
#
# During `run`, write `MARK neutral_1`, `MARK left_1`, etc. to stdin. Write
# `STOP` to unregister early. A hard device-side timeout always applies.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/RawCanTurnProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
remote_dex="/data/local/tmp/denza-raw-can-turn-probe.dex"
probe_tmp="$(mktemp -d /tmp/denza-raw-can-turn.XXXXXX)"
device_touched=0

adb_cmd=("$adb_bin" -s "$serial")

cleanup() {
  if (( device_touched == 1 )); then
    "${adb_cmd[@]}" shell rm -f "$remote_dex" >/dev/null 2>&1 || true
  fi
  rm -r "$probe_tmp"
}
trap cleanup EXIT INT TERM

require_file() {
  if [[ ! -f "$1" ]]; then
    echo "missing required file: $1" >&2
    exit 2
  fi
}

build_probe() {
  require_file "$source_file"
  require_file "$android_jar"
  require_file "$d8"
  require_file "$javac_bin"
  mkdir -p "$probe_tmp/classes" "$probe_tmp/dex"
  "$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
    -d "$probe_tmp/classes" "$source_file"

  class_files=()
  while IFS= read -r -d '' class_file; do
    class_files+=("$class_file")
  done < <(find "$probe_tmp/classes" -type f -name '*.class' -print0)
  if (( ${#class_files[@]} == 0 )); then
    echo "javac produced no class files" >&2
    exit 2
  fi
  "$d8" --lib "$android_jar" --output "$probe_tmp/dex" "${class_files[@]}"
}

run_probe() {
  local seconds="${1:-150}"
  local queue_capacity="${2:-4096}"
  local output_file="${3:-/tmp/denza-raw-can-turn-$(date +%Y%m%d-%H%M%S).log}"

  if [[ ! "$seconds" =~ ^[0-9]+$ ]] || (( seconds < 10 || seconds > 180 )); then
    echo "seconds must be an integer from 10 through 180" >&2
    exit 2
  fi
  if [[ ! "$queue_capacity" =~ ^[0-9]+$ ]] \
      || (( queue_capacity < 128 || queue_capacity > 8192 )); then
    echo "queue-capacity must be an integer from 128 through 8192" >&2
    exit 2
  fi
  if [[ "$output_file" != /* ]]; then
    echo "output-file must be an absolute path" >&2
    exit 2
  fi

  build_probe
  "${adb_cmd[@]}" get-state >/dev/null
  device_touched=1
  "${adb_cmd[@]}" push "$probe_tmp/dex/classes.dex" "$remote_dex" >/dev/null

  echo "capture: $output_file"
  echo "commands: MARK <label> | STOP"
  "${adb_cmd[@]}" shell \
    "CLASSPATH=$remote_dex exec app_process /system/bin --nice-name=denza_raw_can_turn dev.denza.tools.RawCanTurnProbe $seconds $queue_capacity" \
    | tee "$output_file"
}

if (( $# == 0 )); then
  sed -n '1,16p' "$0"
  exit 2
fi

command="$1"
shift
case "$command" in
  build)
    build_probe
    echo "raw CAN turn probe build OK"
    ;;
  run)
    run_probe "$@"
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
