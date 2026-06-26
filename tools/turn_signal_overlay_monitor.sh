#!/usr/bin/env bash
set -euo pipefail

serial="${1:-127.0.0.1:5557}"
package="com.byd.cluster.projection.mapdemo"
activity="${package}/.AvcAidlDashActivity"
overlay_duration_ms="${OVERLAY_DURATION_MS:-300000}"
overlay_delay_s="${OVERLAY_DELAY_S:-0.70}"
stop_delay_s="${STOP_DELAY_S:-1.00}"
stop_echo_suppress_ms="${STOP_ECHO_SUPPRESS_MS:-180}"
viewpoint="${VIEWPOINT:-3205}"
slot="${SLOT:-full}"
crop_source="${CROP_SOURCE:-none}"

last_start_ms=0
overlay_running=0
pending_stop_pid=0
stop_requested_ms=0
suppress_stop_echo=0

now_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

start_overlay() {
  local now
  now="$(now_ms)"
  if (( suppress_stop_echo == 1 )); then
    local since_stop=$((now - stop_requested_ms))
    suppress_stop_echo=0
    if (( since_stop >= 0 && since_stop < stop_echo_suppress_ms )); then
      echo "ignore stop echo start"
      return
    fi
    echo "treat start as new cycle after stop"
  fi
  if (( pending_stop_pid > 0 )); then
    kill "$pending_stop_pid" 2>/dev/null || true
    pending_stop_pid=0
    echo "pending stop cancelled by new start"
  fi
  if (( overlay_running == 1 )); then
    return
  fi
  last_start_ms="$now"
  overlay_running=1
  echo "overlay start scheduled viewpoint=$viewpoint slot=$slot"
  (
    sleep "$overlay_delay_s"
    adb -s "$serial" shell am start --activity-no-animation \
      -n "$activity" \
      --ei display_id 4 \
      --ei viewpoint "$viewpoint" \
      --es slot "$slot" \
      --es crop_source "$crop_source" \
      --el duration_ms "$overlay_duration_ms" >/dev/null
  ) &
}

send_finish_overlay() {
  adb -s "$serial" shell am start --activity-no-animation \
    -n "$activity" \
    --ez finish true >/dev/null || true
}

stop_overlay() {
  if (( overlay_running == 0 )); then
    return
  fi
  overlay_running=0
  echo "overlay stop"
  send_finish_overlay
}

schedule_stop_overlay() {
  local reason="$1"
  local now
  now="$(now_ms)"
  if [[ "$reason" != "projection stop" ]] && (( now - last_start_ms < 800 )); then
    echo "ignore stale teardown: $reason"
    return
  fi
  echo "overlay stop scheduled: $reason"
  if [[ "$reason" == "projection stop" ]]; then
    stop_requested_ms="$now"
    suppress_stop_echo=1
  fi
  if (( pending_stop_pid > 0 )); then
    kill "$pending_stop_pid" 2>/dev/null || true
  fi
  overlay_running=0
  (
    sleep "$stop_delay_s"
    echo "overlay stop"
    send_finish_overlay
  ) &
  pending_stop_pid=$!
}

trap stop_overlay EXIT INT TERM

adb -s "$serial" shell logcat -c
echo "monitoring turn-signal PIP on $serial viewpoint=$viewpoint slot=$slot crop_source=$crop_source"
adb -s "$serial" shell logcat -v time \
  ActivityTaskManager:D \
  AVC_PIP2MeterActivity:D \
  RecentTasksController:D \
  "[Cluster]-BydProjectionService:D" \
  "*:S" |
while IFS= read -r line; do
  case "$line" in
    *"request stop top left com.byd.avc.PIP2MeterActivity"*)
      echo "$line"
      schedule_stop_overlay "projection stop"
      ;;
    *"START u0 {"*"com.byd.avc.START_TOP_LEFT_ACTIVITY"*"PIP2MeterActivity"*)
      echo "$line"
      start_overlay
      ;;
    *"AVC_PIP2MeterActivity"*"onDestroy"*)
      echo "$line"
      schedule_stop_overlay "pip destroy"
      ;;
    *"onTaskRemoved taskInfo"*"PIP2MeterActivity"*)
      echo "$line"
      schedule_stop_overlay "pip task removed"
      ;;
  esac
done
