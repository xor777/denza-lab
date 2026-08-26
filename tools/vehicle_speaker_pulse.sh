#!/usr/bin/env bash
# Driver for tools/VehicleSpeakerPulse.java — the stock "IVI uses the vehicle
# speaker" pulse (SET_USE_AUDIO_SCENE_SET = REASON_MEDIA) without MediaCenter.
# Runs as shell UID via app_process; no APK install.
#
# Background and hazard rules: docs/speaker-lift-findings.md.
#
# usage:
#   vehicle_speaker_pulse.sh prepare                  # build + push only, fires nothing
#   vehicle_speaker_pulse.sh run snap                 # read-only FIDs + broker state
#   vehicle_speaker_pulse.sh run usespeaker
#   vehicle_speaker_pulse.sh run requestspeaker
#   vehicle_speaker_pulse.sh run streamallowed 3 com.android.shell
#   vehicle_speaker_pulse.sh run mediaplayer /sdcard/Music/clip.ogg 3
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_file="$script_dir/VehicleSpeakerPulse.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
device_dir="/data/local/tmp/vehicle-speaker-pulse"
build_dir="$(mktemp -d /tmp/denza-vehicle-speaker.XXXXXX)"

cleanup() {
  rm -r "$build_dir"
}
trap cleanup EXIT

build_and_push() {
  mkdir -p "$build_dir/classes" "$build_dir/dex"
  "$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
    -d "$build_dir/classes" "$source_file"
  (
    cd "$build_dir/classes"
    "$d8" --lib "$android_jar" --output "$build_dir/dex" \
      $(find . -name '*.class')
  )
  adb -s "$serial" shell "mkdir -p $device_dir"
  adb -s "$serial" push "$build_dir/dex/classes.dex" "$device_dir/classes.dex" >/dev/null
}

case "${1:-}" in
  prepare)
    build_and_push
    echo "dex pushed -> $device_dir/classes.dex (nothing fired)"
    ;;
  run)
    shift
    build_and_push
    # Mark the log so a null result can be told apart from "the broker never ran".
    marker="VSP-$(date +%H%M%S)"
    adb -s "$serial" shell "log -t VehicleSpeakerPulse START $marker" >/dev/null 2>&1 || true
    adb -s "$serial" shell \
      "CLASSPATH=$device_dir/classes.dex app_process /system/bin --nice-name=vsp dev.denza.tools.VehicleSpeakerPulse $*"
    echo "--- broker evidence (logcat since $marker) ---"
    adb -s "$serial" shell \
      "logcat -d -v time | sed -n '/START $marker/,\$p' | grep -E 'IviVehicleAudioBroker|VehicleAudioStateManager|isStreamAllowed|33f00024|SpeakerFlip'" \
      2>/dev/null | tail -30 || true
    ;;
  *)
    sed -n '1,16p' "$0"
    exit 2
    ;;
esac
