#!/usr/bin/env bash
# Build and run the narrow, read-only vendor turn-signal event probe.
#
# Usage:
#   tools/turn_signal_event_probe.sh build
#   tools/turn_signal_event_probe.sh run [seconds] [output-file]
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/TurnSignalEventProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
remote_dex="/data/local/tmp/denza-turn-signal-event-probe.dex"
probe_tmp="$(mktemp -d /tmp/denza-turn-signal-event.XXXXXX)"
device_touched=0

adb_cmd=("$adb_bin" -s "$serial")

cleanup() {
  if (( device_touched == 1 )); then
    "${adb_cmd[@]}" shell rm -f "$remote_dex" >/dev/null 2>&1 || true
  fi
  rm -r "$probe_tmp"
}
trap cleanup EXIT INT TERM

build_probe() {
  for required in "$source_file" "$android_jar" "$d8" "$javac_bin"; do
    if [[ ! -f "$required" ]]; then
      echo "missing required file: $required" >&2
      exit 2
    fi
  done
  mkdir -p "$probe_tmp/classes" "$probe_tmp/dex"
  "$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
    -d "$probe_tmp/classes" "$source_file"

  class_files=()
  while IFS= read -r -d '' class_file; do
    class_files+=("$class_file")
  done < <(find "$probe_tmp/classes" -type f -name '*.class' -print0)
  "$d8" --lib "$android_jar" --output "$probe_tmp/dex" "${class_files[@]}"
}

run_probe() {
  local seconds="${1:-90}"
  local output_file="${2:-/tmp/denza-turn-signal-event-$(date +%Y%m%d-%H%M%S).log}"
  if [[ ! "$seconds" =~ ^[0-9]+$ ]] || (( seconds < 10 || seconds > 120 )); then
    echo "seconds must be an integer from 10 through 120" >&2
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
    "CLASSPATH=$remote_dex exec app_process /system/bin --nice-name=denza_turn_signal_event dev.denza.tools.TurnSignalEventProbe $seconds" \
    | tee "$output_file"
}

if (( $# == 0 )); then
  sed -n '1,8p' "$0"
  exit 2
fi

command="$1"
shift
case "$command" in
  build)
    build_probe
    echo "turn-signal event probe build OK"
    ;;
  run)
    run_probe "$@"
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
