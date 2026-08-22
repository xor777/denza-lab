#!/usr/bin/env bash
# Build/install/rollback wrapper for the normal-UID Yandex speaker-lift probe.
# Install only from a clean post-reboot scene with Yandex Music not foreground.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
adb_bin="$(command -v adb)"
package_name="dev.denza.speakerlift.yandexprobe"
service_class="dev.denza.speakerlift.probe.YandexSpeakerLiftAccessibilityService"
receiver_class="dev.denza.speakerlift.probe.ProbeCommandReceiver"
service_component="$package_name/$service_class"
receiver_component="$package_name/$receiver_class"
track_name="denza-speaker-lift-probe-20260822.ogg"
track_source="/product/media/audio/notifications/pixiedust.ogg"
track_device_path="/sdcard/Music/$track_name"
track_media_path="/storage/emulated/0/Music/$track_name"
apk_path="$repo_root/experiments/speaker-lift-yandex-probe/build/outputs/apk/debug/speaker-lift-yandex-probe.apk"
default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
default_android_home="/opt/homebrew/share/android-commandlinetools"

adb_cmd=("$adb_bin" -s "$serial")

require_device() {
  local state
  state="$("${adb_cmd[@]}" get-state 2>/dev/null || true)"
  if [[ "$state" != "device" ]]; then
    echo "ADB device is not ready on $serial (state=${state:-missing})" >&2
    exit 1
  fi
}

build_probe() {
  (
    cd "$repo_root"
    JAVA_HOME="${JAVA_HOME:-$default_java_home}" \
      ANDROID_HOME="${ANDROID_HOME:-$default_android_home}" \
      ./gradlew :speaker-lift-yandex-probe:assembleDebug
  )
  [[ -f "$apk_path" ]]
  echo "APK: $apk_path"
}

media_row() {
  "${adb_cmd[@]}" shell \
    "content query --uri content://media/external/audio/media --projection _id:_data:duration --where \"_data='$track_media_path'\"" \
    2>/dev/null || true
}

prepare_track() {
  require_device
  "${adb_cmd[@]}" shell "mkdir -p /sdcard/Music"
  "${adb_cmd[@]}" shell "cp '$track_source' '$track_device_path'"
  "${adb_cmd[@]}" shell am broadcast \
    -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://$track_device_path" >/dev/null

  local row=""
  local attempt
  for attempt in {1..20}; do
    row="$(media_row)"
    if [[ "$row" == *"_data=$track_media_path"* ]]; then
      echo "$row"
      return
    fi
    sleep 0.25
  done
  echo "MediaStore did not index $track_media_path" >&2
  exit 1
}

read_enabled_services() {
  local value
  value="$("${adb_cmd[@]}" shell settings get secure enabled_accessibility_services \
    | tr -d '\r')"
  if [[ "$value" == "null" ]]; then
    value=""
  fi
  printf '%s' "$value"
}

enable_probe() {
  require_device
  local current
  current="$(read_enabled_services)"
  if [[ ":$current:" != *":$service_component:"* ]]; then
    if [[ -n "$current" ]]; then
      current="$current:$service_component"
    else
      current="$service_component"
    fi
    "${adb_cmd[@]}" shell settings put secure enabled_accessibility_services "$current"
  fi
  "${adb_cmd[@]}" shell settings put secure accessibility_enabled 1
  echo "enabled: $service_component"
}

disable_probe() {
  require_device
  local current
  current="$(read_enabled_services)"
  local kept=()
  local entry
  IFS=':' read -r -a entries <<< "$current"
  for entry in "${entries[@]}"; do
    if [[ -n "$entry" && "$entry" != "$service_component" ]]; then
      kept+=("$entry")
    fi
  done
  local updated=""
  if (( ${#kept[@]} > 0 )); then
    updated="$(IFS=:; echo "${kept[*]}")"
  fi
  "${adb_cmd[@]}" shell settings put secure enabled_accessibility_services "$updated"
  "${adb_cmd[@]}" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  echo "disabled: $service_component"
}

assert_yandex_not_foreground() {
  local top
  top="$("${adb_cmd[@]}" shell dumpsys activity top | head -n 20)"
  if [[ "$top" == *"ru.yandex.music"* ]]; then
    echo "Yandex Music is foreground; return to Home before installing the probe" >&2
    exit 1
  fi
}

install_probe() {
  require_device
  assert_yandex_not_foreground
  build_probe
  prepare_track
  "${adb_cmd[@]}" install -r "$apk_path"
  enable_probe
  echo "Installed without launching an Activity. Open Yandex Music once to test."
}

send_command() {
  local action="$1"
  require_device
  "${adb_cmd[@]}" shell am broadcast \
    -a "$package_name.$action" \
    -n "$receiver_component"
}

show_status() {
  require_device
  "${adb_cmd[@]}" shell pm path "$package_name" 2>/dev/null || true
  printf 'accessibility_enabled='
  "${adb_cmd[@]}" shell settings get secure accessibility_enabled | tr -d '\r'
  printf 'probe_component_enabled='
  if [[ ":$(read_enabled_services):" == *":$service_component:"* ]]; then
    echo yes
  else
    echo no
  fi
  media_row
  "${adb_cmd[@]}" shell dumpsys media_session \
    | grep -m 1 -A 10 'MediaCenterServiceImpl com.byd.mediacenter' \
    | grep -E 'package=|state=PlaybackState' || true
}

usage() {
  cat <<EOF
usage: $0 build | install | enable | disable | uninstall | status | logs | pulse | pause

  install  builds, prepares/indexes the proven chime, installs the APK, and
           enables only its accessibility component while preserving others
  pulse    shell-only diagnostic; do not run before the clean Yandex test
  disable  removes only this accessibility component and force-stops the probe
EOF
}

if (( $# != 1 )); then
  usage >&2
  exit 2
fi

case "$1" in
  build)
    build_probe
    ;;
  install)
    install_probe
    ;;
  enable)
    enable_probe
    ;;
  disable)
    disable_probe
    ;;
  uninstall)
    disable_probe
    "${adb_cmd[@]}" uninstall "$package_name"
    ;;
  status)
    show_status
    ;;
  logs)
    require_device
    "${adb_cmd[@]}" logcat -d -v time -s SpeakerLiftYandexProbe:V '*:S'
    ;;
  pulse)
    send_command PULSE
    ;;
  pause)
    send_command PAUSE
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
