#!/usr/bin/env bash
set -euo pipefail

package="dev.denza.nightvision.probe"
activity="${package}/.NightVisionProbeActivity"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${repo_root}/experiments/night-vision-probe/build/outputs/apk/debug/night-vision-probe.apk"
duration_ms=8000
serial="${ANDROID_SERIAL:-}"

usage() {
  printf '%s\n' \
    "Usage:" \
    "  tools/night_vision_probe.sh build" \
    "  tools/night_vision_probe.sh install" \
    "  tools/night_vision_probe.sh preflight" \
    "  tools/night_vision_probe.sh start --confirm-parked [--duration-ms 1000..10000]" \
    "  tools/night_vision_probe.sh stop" \
    "  tools/night_vision_probe.sh status"
}

resolve_serial() {
  if [[ -n "$serial" ]]; then
    return
  fi
  local devices
  devices="$(adb devices | awk '$2 == "device" { print $1 }')"
  local count
  count="$(printf '%s\n' "$devices" | awk 'NF { count += 1 } END { print count + 0 }')"
  if [[ "$count" != "1" ]]; then
    printf 'expected exactly one authorized ADB device, found %s\n' "$count" >&2
    exit 64
  fi
  serial="$(printf '%s\n' "$devices" | awk 'NF { print; exit }')"
}

adb_target() {
  adb -s "$serial" "$@"
}

service_active() {
  local component="$1"
  adb_target shell dumpsys activity services "$component" | rg -q 'ServiceRecord\{'
}

crash_count() {
  local count
  count="$(
    adb_target logcat -b crash -d -v time \
      | rg -c 'Cmdline: com\.byd\.avc' \
      || true
  )"
  printf '%s\n' "${count:-0}"
}

preflight() {
  resolve_serial
  if service_active \
    "dev.denza.apps/.feature.mirrors.SideCameraMonitorService"; then
    printf '%s\n' \
      "refusing: Denza Apps mirror monitor is active." \
      "Disable Mirrors in Denza Apps before taking ownership of the AVC surface." >&2
    exit 65
  fi
  if service_active \
    "dev.denza.mirrors/.SideCameraOverlayMonitorService"; then
    printf '%s\n' \
      "refusing: legacy Denza Mirrors monitor is active." \
      "Stop the legacy monitor before running this probe." >&2
    exit 65
  fi
  if adb_target shell dumpsys window windows \
    | rg -q 'com\.byd\.avc/(com\.byd\.avc\.)?(PIP2MeterActivity|CompactAlertActivity)'; then
    printf '%s\n' \
      "refusing: a stock AVC camera window is currently visible." \
      "Wait for it to close before running this probe." >&2
    exit 65
  fi
  printf 'preflight passed on %s; parked state still requires human confirmation\n' "$serial"
}

capture_run() {
  local run_id="$1"
  local log_start="$2"
  local capture_dir="${repo_root}/captures/night-vision-probe"
  local capture_file="${capture_dir}/${run_id}.txt"
  mkdir -p "$capture_dir"
  {
    printf 'serial=%s\n' "$serial"
    printf 'run_id=%s\n' "$run_id"
    printf 'captured_at=%s\n' "$(date -Iseconds)"
    printf 'avc_pid=%s\n' "$(adb_target shell pidof com.byd.avc || true)"
    printf '%s\n' "probe_status:"
    adb_target shell run-as "$package" \
      cat "files/status-${run_id}.txt" \
      || true
    printf '%s\n' "logcat:"
    adb_target logcat -d -v time -T "$log_start" \
      | rg 'NightVisionProbe|com\.byd\.avc|Fatal signal|ANativeWindow_getWidth|native_setSurface' \
      || true
    adb_target logcat -b crash -d -v time
  } >"$capture_file"
  printf '%s\n' "$capture_file"
}

command="${1:-}"
shift || true

case "$command" in
  build)
    cd "$repo_root"
    ./gradlew :night-vision-probe:assembleDebug
    printf '%s\n' "$apk"
    ;;
  install)
    resolve_serial
    if [[ ! -f "$apk" ]]; then
      printf 'APK not found: %s; run build first\n' "$apk" >&2
      exit 66
    fi
    adb_target install -r "$apk"
    ;;
  preflight)
    preflight
    ;;
  start)
    confirmed_parked=false
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --confirm-parked)
          confirmed_parked=true
          shift
          ;;
        --duration-ms)
          duration_ms="${2:-}"
          shift 2
          ;;
        *)
          printf 'unknown argument: %s\n' "$1" >&2
          usage >&2
          exit 64
          ;;
      esac
    done
    if [[ "$confirmed_parked" != "true" ]]; then
      printf '%s\n' \
        "refusing: pass --confirm-parked only after verifying the vehicle is stationary in P." >&2
      exit 65
    fi
    if ! [[ "$duration_ms" =~ ^[0-9]+$ ]] \
      || (( duration_ms < 1000 || duration_ms > 10000 )); then
      printf 'duration must be an integer from 1000 to 10000 ms\n' >&2
      exit 64
    fi
    preflight
    if ! adb_target shell pm path "$package" >/dev/null 2>&1; then
      printf 'probe APK is not installed; run install first\n' >&2
      exit 66
    fi

    run_id="$(date '+%Y%m%d-%H%M%S')"
    log_start="$(date '+%m-%d %H:%M:%S.000')"
    pid_before="$(adb_target shell pidof com.byd.avc || true)"
    crashes_before="$(crash_count)"
    adb_target shell am start -W --display 4 \
      -n "$activity" \
      --es run_id "$run_id" \
      --el duration_ms "$duration_ms"

    wait_seconds=$((duration_ms / 1000 + 3))
    sleep "$wait_seconds"
    capture_file="$(capture_run "$run_id" "$log_start")"
    pid_after="$(adb_target shell pidof com.byd.avc || true)"
    crashes_after="$(crash_count)"

    if [[ "$pid_before" != "$pid_after" ]] \
      || (( crashes_after > crashes_before )); then
      printf '%s\n' \
        "ALERT: com.byd.avc restarted or added a crash during the probe." \
        "Evidence: $capture_file" >&2
      exit 2
    fi
    if ! rg -q "run=${run_id} ready .*viewpoint=2001" "$capture_file"; then
      printf '%s\n' \
        "front camera activation was not acknowledged." \
        "Evidence: $capture_file" >&2
      exit 1
    fi
    if ! rg -q "run=${run_id} frame first buffer received" "$capture_file"; then
      printf '%s\n' \
        "AVC accepted the front viewpoint, but TextureView received no frame." \
        "Evidence: $capture_file" >&2
      exit 1
    fi
    if ! rg -q "run=${run_id} stopped" "$capture_file"; then
      printf '%s\n' \
        "probe did not record a clean stop." \
        "Run tools/night_vision_probe.sh stop immediately." \
        "Evidence: $capture_file" >&2
      exit 1
    fi
    printf '%s\n' \
      "Denza Mirrors renderer selected the front camera; AVC PID remained ${pid_after}" \
      "Evidence: $capture_file"
    ;;
  stop)
    resolve_serial
    adb_target shell am start -W \
      -n "$activity" \
      --es run_id "stop-$(date '+%Y%m%d-%H%M%S')" \
      --ez finish true >/dev/null
    printf 'clean stop requested for %s\n' "$package"
    ;;
  status)
    resolve_serial
    adb_target shell dumpsys activity activities \
      | rg -n -C 3 "$package" \
      || true
    adb_target logcat -d -v time -s NightVisionProbe:I '*:S' \
      | tail -80
    ;;
  *)
    usage >&2
    exit 64
    ;;
esac
