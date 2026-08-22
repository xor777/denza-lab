#!/usr/bin/env bash
# Speaker-lift (Devialet pop-out covers) live probe driver.
# Builds tools/SpeakerLiftProbe.java into a dex, pushes it to the head unit,
# and runs it as shell UID via app_process. No APK install.
#
# Background and hazard rules: docs/speaker-lift-findings.md.
#   - capture/probe reads use autoservice transact 5 (getInt) only.
#   - Never issue autoservice transact 10/12/14/16 with hand-shaped parcels
#     (crashed autoservice once, 2026-08-22).
#   - All SETs go through the framework's own client classes inside the probe.
#
# usage:
#   speaker_lift_probe.sh build
#   speaker_lift_probe.sh run <mode args...>        e.g. run tone 14 5 8 focus
#   speaker_lift_probe.sh capture <label>           read-only state dump bundle
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
source_file="$script_dir/SpeakerLiftProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
javac_bin="${JAVA_HOME:-$default_java_home}/bin/javac"
adb_bin="$(command -v adb)"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
device_dir="/data/local/tmp/speakerlift"
probe_tmp="$(mktemp -d /tmp/denza-speaker-lift.XXXXXX)"

adb_cmd=("$adb_bin" "-s" "$serial")

cleanup() {
  rm -r "$probe_tmp"
}
trap cleanup EXIT

build() {
  mkdir -p "$probe_tmp/classes" "$probe_tmp/dex"
  "$javac_bin" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
    -d "$probe_tmp/classes" "$source_file"
  (cd "$probe_tmp/classes" && "$d8" --lib "$android_jar" \
    --output "$probe_tmp/dex" $(find . -name '*.class'))
}

push_dex() {
  "${adb_cmd[@]}" shell "mkdir -p $device_dir"
  "${adb_cmd[@]}" push "$probe_tmp/dex/classes.dex" "$device_dir/classes.dex" >/dev/null
}

run_probe() {
  build
  push_dex
  "${adb_cmd[@]}" shell \
    "CLASSPATH=$device_dir/classes.dex app_process /system/bin --nice-name=slp dev.denza.tools.SpeakerLiftProbe $*"
}

capture() {
  local label="$1"
  local stamp
  stamp="$(date +%Y%m%d-%H%M%S)"
  local out_dir="$repo_root/captures/speaker-lift/${stamp}-${label}"
  mkdir -p "$out_dir"

  # FID snapshot (read-only, transact 5 = getInt; see doc live snapshot table).
  {
    for spec in \
      "1002 1275068432 AUDIO_RLSA_COFIG" \
      "1002 1275068427 AUDIO_RLSA_STATE" \
      "1002 899678424  AUDIO_SPEAKER_FLIP_COVER_CONFIG" \
      "1002 899678426  AUDIO_SPEAKER_FLIP_SETTING_STATUS" \
      "1002 1281359884 AUDIO_MEDIA_SOUND_SOURCE_STATE" \
      "1002 1339031576 AUDIO_MASTER_VOLUME_STATE" \
      "1002 1339031597 AUDIO_MEDIA_SOUND_MUTE_STATE" \
      "1002 1339031600 AMP_CONFIG" \
      "1007 871366704  INSTRUMENT_MUSIC_SOURCE_SET"; do
      set -- $spec
      printf '%-38s dev=%-5s fid=%-11s ' "$3" "$1" "$2"
      "${adb_cmd[@]}" shell "service call autoservice 5 i32 $1 i32 $2" | tr '\n' ' '
      printf '\n'
    done
  } | tee "$out_dir/fids.txt"

  "${adb_cmd[@]}" shell dumpsys audio >"$out_dir/dumpsys-audio.txt" 2>&1 || true
  "${adb_cmd[@]}" shell dumpsys media_session >"$out_dir/dumpsys-media-session.txt" 2>&1 || true
  "${adb_cmd[@]}" shell dumpsys bluetooth_manager \
    >"$out_dir/dumpsys-bluetooth.txt" 2>&1 || true
  "${adb_cmd[@]}" shell logcat -d -v time >"$out_dir/logcat-full.txt" 2>&1 || true
  "${adb_cmd[@]}" shell \
    "logcat -d -v time | grep -iE 'SpeakerLiftProbe|AtmosphereLamp|AudioVisualizer|Amplifier|DiCar|BydAudio|AudioFlinger|audio_hw|Devialet|RLSA|speaker_flip|AudioTrack'" \
    >"$out_dir/logcat-audio.txt" 2>&1 || true

  echo "captured -> $out_dir"
}

if (( $# == 0 )); then
  sed -n '1,20p' "$0"
  exit 2
fi

command="$1"
shift
case "$command" in
  build) build; echo "dex OK: $probe_tmp/dex/classes.dex" ;;
  run) run_probe "$@" ;;
  capture) capture "$@" ;;
  *) echo "unknown command: $command" >&2; exit 2 ;;
esac
