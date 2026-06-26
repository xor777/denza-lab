#!/usr/bin/env bash
set -euo pipefail

serial="${1:-127.0.0.1:5557}"
package="com.byd.cluster.projection.mapdemo"
activity="${package}/.AvcAidlDashActivity"
overlay_duration_ms="${OVERLAY_DURATION_MS:-300000}"
overlay_delay_s="${OVERLAY_DELAY_S:-0.20}"
stop_delay_s="${STOP_DELAY_S:-0.60}"
poll_s="${POLL_S:-0.35}"
viewpoint="${VIEWPOINT:-3204}"
slot="${SLOT:-right}"

overlay_running=0
pending_start_pid=0
pending_stop_pid=0

is_avc_alert_visible() {
  adb -s "$serial" shell dumpsys window visible 2>/dev/null | awk '
    /Window #[0-9]+ Window\{/ {
      in_avc = ($0 ~ /com\.byd\.avc/)
      display0 = 0
      package = 0
      alert = 0
    }
    in_avc && /mDisplayId=0/ { display0 = 1 }
    in_avc && /package=com\.byd\.avc/ { package = 1 }
    in_avc && /ty=SYSTEM_ALERT/ { alert = 1 }
    in_avc && display0 && package && alert { print "1"; exit }
  '
}

send_finish_overlay() {
  adb -s "$serial" shell am start --activity-no-animation \
    -n "$activity" \
    --ez finish true >/dev/null || true
}

cancel_pending_stop() {
  if (( pending_stop_pid > 0 )); then
    kill "$pending_stop_pid" 2>/dev/null || true
    pending_stop_pid=0
    echo "pending stop cancelled"
  fi
}

start_overlay() {
  cancel_pending_stop
  if (( overlay_running == 1 )); then
    return
  fi
  overlay_running=1
  echo "overlay start scheduled viewpoint=$viewpoint slot=$slot"
  (
    sleep "$overlay_delay_s"
    adb -s "$serial" shell am start --activity-no-animation \
      -n "$activity" \
      --ei display_id 4 \
      --ei viewpoint "$viewpoint" \
      --es slot "$slot" \
      --el duration_ms "$overlay_duration_ms" >/dev/null
  ) &
  pending_start_pid=$!
}

schedule_stop_overlay() {
  if (( overlay_running == 0 )); then
    return
  fi
  overlay_running=0
  if (( pending_start_pid > 0 )); then
    kill "$pending_start_pid" 2>/dev/null || true
    pending_start_pid=0
  fi
  if (( pending_stop_pid > 0 )); then
    kill "$pending_stop_pid" 2>/dev/null || true
  fi
  echo "overlay stop scheduled"
  (
    sleep "$stop_delay_s"
    echo "overlay stop"
    send_finish_overlay
  ) &
  pending_stop_pid=$!
}

stop_overlay() {
  overlay_running=0
  if (( pending_start_pid > 0 )); then
    kill "$pending_start_pid" 2>/dev/null || true
    pending_start_pid=0
  fi
  if (( pending_stop_pid > 0 )); then
    kill "$pending_stop_pid" 2>/dev/null || true
    pending_stop_pid=0
  fi
  echo "overlay stop"
  send_finish_overlay
}

trap stop_overlay EXIT INT TERM

echo "monitoring com.byd.avc SYSTEM_ALERT on $serial viewpoint=$viewpoint slot=$slot"
while true; do
  if [[ "$(is_avc_alert_visible)" == "1" ]]; then
    start_overlay
  else
    schedule_stop_overlay
  fi
  sleep "$poll_s"
done
