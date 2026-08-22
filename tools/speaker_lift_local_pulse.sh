#!/usr/bin/env bash
# Runs the verified stock MediaCenter LOCAL play/pause path as shell UID.
# The target file must already be indexed by MediaStore. See the findings doc.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/SpeakerLiftLocalPulse.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
device_dir="/data/local/tmp/speakerlift-local"
build_dir="$(mktemp -d /tmp/denza-speaker-lift-local.XXXXXX)"

cleanup() {
  rm -r "$build_dir"
}
trap cleanup EXIT

build() {
  mkdir -p "$build_dir/classes" "$build_dir/dex"
  "$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
    -d "$build_dir/classes" "$source_file"
  (
    cd "$build_dir/classes"
    "$d8" --lib "$android_jar" --output "$build_dir/dex" \
      $(find . -name '*.class')
  )
}

run_probe() {
  build
  "$adb_bin" -s "$serial" shell "mkdir -p $device_dir"
  "$adb_bin" -s "$serial" push \
    "$build_dir/dex/classes.dex" "$device_dir/classes.dex" >/dev/null
  "$adb_bin" -s "$serial" shell \
    "CLASSPATH=$device_dir/classes.dex app_process /system/bin --nice-name=sllp dev.denza.tools.SpeakerLiftLocalPulse $*"
}

if (( $# == 0 )); then
  echo "usage: $0 build | play-path <canonical-ivi-path> | play-id <id> | pause" >&2
  exit 2
fi

command="$1"
shift
case "$command" in
  build)
    build
    echo "dex build OK"
    ;;
  play-path|play-id|pause)
    run_probe "$command" "$@"
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
