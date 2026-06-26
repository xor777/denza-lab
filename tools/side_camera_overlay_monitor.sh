#!/usr/bin/env bash
set -euo pipefail

serial="${1:-127.0.0.1:5557}"
package="com.byd.cluster.projection.mapdemo"
activity="${package}/.AvcAidlDashActivity"

display_id="${DISPLAY_ID:-4}"
overlay_duration_ms="${OVERLAY_DURATION_MS:-300000}"
center_extend_percent="${CENTER_EXTEND_PERCENT:-20}"
overlay_window="${OVERLAY_WINDOW:-true}"

left_viewpoint="${LEFT_VIEWPOINT:-3205}"
left_slot="${LEFT_SLOT:-left}"
left_crop_source="${LEFT_CROP_SOURCE:-left}"
left_delay_s="${LEFT_DELAY_S:-0.00}"

right_viewpoint="${RIGHT_VIEWPOINT:-3204}"
right_slot="${RIGHT_SLOT:-right}"
right_crop_source="${RIGHT_CROP_SOURCE:-none}"
right_delay_s="${RIGHT_DELAY_S:-0.00}"
right_hide_delay_s="${RIGHT_HIDE_DELAY_S:-0.45}"
right_failsafe_s="${RIGHT_FAILSAFE_S:-45}"
right_min_active_ms="${RIGHT_MIN_ACTIVE_MS:-1800}"
hide_stock_right="${HIDE_STOCK_RIGHT:-0}"

stop_delay_s="${STOP_DELAY_S:-0.60}"
poll_s="${POLL_S:-0.15}"

tmpdir="$(mktemp -d)"
fifo="${tmpdir}/events"
mkfifo "$fifo"
exec 3<> "$fifo"

logcat_pid=0
pip_logcat_pid=0
poll_pid=0
tick_pid=0
pending_start_pid=0
pending_stop_pid=0
pending_hide_pid=0
current_side=""
overlay_started=0
right_stock_hidden=0
last_start_ms=0
stopping=0

now_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

overlay_window_bool() {
  case "$overlay_window" in
    1|true|TRUE|yes|YES) printf 'true' ;;
    *) printf 'false' ;;
  esac
}

emit() {
  printf '%s\n' "$*" >&3 2>/dev/null || true
}

set_stock_alert_allowed() {
  if [[ "$hide_stock_right" != "1" ]]; then
    return
  fi
  adb -s "$serial" shell cmd appops set com.byd.avc SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
  right_stock_hidden=0
}

set_stock_alert_hidden() {
  if [[ "$hide_stock_right" != "1" ]]; then
    return
  fi
  adb -s "$serial" shell cmd appops set com.byd.avc SYSTEM_ALERT_WINDOW ignore >/dev/null 2>&1 || true
  right_stock_hidden=1
  echo "stock avc alert hidden"
}

send_finish_overlay() {
  adb -s "$serial" shell am start --activity-no-animation \
    -n "$activity" \
    --ez finish true >/dev/null 2>&1 || true
}

cancel_pid() {
  local pid="$1"
  if (( pid > 0 )); then
    kill "$pid" 2>/dev/null || true
  fi
}

cancel_pending_jobs() {
  cancel_pid "$pending_start_pid"
  cancel_pid "$pending_stop_pid"
  cancel_pid "$pending_hide_pid"
  pending_start_pid=0
  pending_stop_pid=0
  pending_hide_pid=0
}

start_overlay_now() {
  local side="$1"
  local viewpoint="$left_viewpoint"
  local slot="$left_slot"
  local crop_source="$left_crop_source"
  if [[ "$side" == "right" ]]; then
    viewpoint="$right_viewpoint"
    slot="$right_slot"
    crop_source="$right_crop_source"
  fi

  if [[ "$current_side" != "$side" ]]; then
    return
  fi

  adb -s "$serial" shell am start --activity-no-animation \
    -n "$activity" \
    --ei display_id "$display_id" \
    --ei viewpoint "$viewpoint" \
    --es slot "$slot" \
    --es crop_source "$crop_source" \
    --ei center_extend_percent "$center_extend_percent" \
    --ez overlay_window "$(overlay_window_bool)" \
    --el duration_ms "$overlay_duration_ms" >/dev/null

  overlay_started=1
  echo "overlay started side=$side display=$display_id viewpoint=$viewpoint slot=$slot crop_source=$crop_source extend=$center_extend_percent overlay=$overlay_window"
  if [[ "$side" == "right" && "$hide_stock_right" == "1" ]]; then
    (
      sleep "$right_hide_delay_s"
      emit "right_hide"
    ) &
    pending_hide_pid=$!
  fi
}

stop_overlay_now() {
  local reason="$1"
  cancel_pending_jobs
  if [[ -z "$current_side" ]]; then
    set_stock_alert_allowed
    send_finish_overlay
    return
  fi
  local stopped_side="$current_side"
  current_side=""
  overlay_started=0
  set_stock_alert_allowed
  send_finish_overlay
  echo "overlay stopped side=$stopped_side reason=$reason"
}

schedule_stop_overlay() {
  local side="$1"
  local reason="$2"
  if [[ -z "$current_side" || "$current_side" != "$side" ]]; then
    return
  fi
  if [[ "$side" == "right" ]]; then
    local now elapsed_ms
    now="$(now_ms)"
    elapsed_ms=$(( now - last_start_ms ))
    if (( elapsed_ms >= 0 && elapsed_ms < right_min_active_ms )); then
      echo "ignore early right stop reason=$reason elapsed=${elapsed_ms}ms"
      return
    fi
  fi
  cancel_pid "$pending_start_pid"
  cancel_pid "$pending_hide_pid"
  pending_start_pid=0
  pending_hide_pid=0
  cancel_pid "$pending_stop_pid"
  stop_overlay_now "$reason"
}

start_overlay() {
  local side="$1"
  local reason="$2"
  local delay="$left_delay_s"
  if [[ "$side" == "right" ]]; then
    delay="$right_delay_s"
  fi

  if [[ "$current_side" == "$side" ]]; then
    return
  fi
  cancel_pending_jobs
  if [[ -n "$current_side" ]]; then
    stop_overlay_now "switch to ${side}"
  fi

  current_side="$side"
  overlay_started=0
  right_stock_hidden=0
  last_start_ms="$(now_ms)"
  echo "overlay start scheduled side=$side reason=$reason"
  if [[ "$side" == "right" && "$right_delay_s" == "0.00" ]]; then
    start_overlay_now "$side"
    return
  fi
  if [[ "$side" == "left" && "$left_delay_s" == "0.00" ]]; then
    start_overlay_now "$side"
    return
  fi
  (
    sleep "$delay"
    emit "start_now ${side}"
  ) &
  pending_start_pid=$!
}

is_avc_alert_visible() {
  adb -s "$serial" shell dumpsys window visible 2>/dev/null | awk '
    /Window #[0-9]+ Window\{/ {
      in_avc = ($0 ~ /com\.byd\.avc/)
      display0 = 0
      package = 0
      alert = 0
      compact = 0
    }
    in_avc && /mDisplayId=0/ { display0 = 1 }
    in_avc && /package=com\.byd\.avc/ { package = 1 }
    in_avc && /ty=SYSTEM_ALERT/ {
      alert = 1
      if ($0 ~ /\(720x450\)/) compact = 1
    }
    in_avc && display0 && package && alert && compact { print "1"; exit }
  '
}

is_left_pip_visible() {
  adb -s "$serial" shell dumpsys window visible 2>/dev/null | awk '
    /Window #[0-9]+ Window\{/ {
      in_pip = ($0 ~ /com\.byd\.avc\/com\.byd\.avc\.PIP2MeterActivity/)
      display4 = 0
      package = 0
    }
    in_pip && /mDisplayId=4/ { display4 = 1 }
    in_pip && /package=com\.byd\.avc/ { package = 1 }
    in_pip && display4 && package { print "1"; exit }
  '
}

watch_windows() {
  local right_visible=0
  local left_visible=0
  while true; do
    if [[ "$(is_left_pip_visible)" == "1" ]]; then
      if (( left_visible == 0 )); then
        left_visible=1
        emit "left_window_on"
      fi
    else
      if (( left_visible == 1 )); then
        left_visible=0
        emit "left_window_off"
      fi
    fi

    if [[ "$(is_avc_alert_visible)" == "1" ]]; then
      if (( right_visible == 0 )); then
        right_visible=1
        emit "right_alert_on"
      fi
    else
      if (( right_visible == 1 )); then
        right_visible=0
        emit "right_alert_off"
      fi
    fi
    sleep "$poll_s"
  done
}

watch_pip_logcat() {
  adb -s "$serial" shell logcat -v time \
    ActivityTaskManager:D \
    AVC_PIP2MeterActivity:D \
    RecentTasksController:D \
    "[Cluster]-BydProjectionService:D" \
    "*:S" 2>/dev/null |
  while IFS= read -r line; do
    if [[ "$line" == *"request stop top left com.byd.avc.PIP2MeterActivity"* ]]; then
      emit "left_stop projection_stop"
      continue
    fi
    if [[ "$line" == *"START u0 {"* && "$line" == *"com.byd.avc.START_TOP_LEFT_ACTIVITY"* && "$line" == *"PIP2MeterActivity"* ]]; then
      emit "left_start pip_start"
      continue
    fi
    if [[ "$line" == *"AVC_PIP2MeterActivity"* && "$line" == *"onDestroy"* ]]; then
      emit "left_stop pip_destroy"
      continue
    fi
    if [[ "$line" == *"onTaskRemoved taskInfo"* && "$line" == *"PIP2MeterActivity"* ]]; then
      emit "left_stop pip_task_removed"
      continue
    fi
  done
}

watch_logcat() {
  adb -s "$serial" shell logcat -b all -v time 2>/dev/null |
  while IFS= read -r line; do
    if [[ "$line" =~ onTurnLightStateChanged.*value[[:space:]]*=[[:space:]]*(-?[0-9]+) ]]; then
      case "${BASH_REMATCH[1]}" in
        2|3)
          emit "turn_left value_${BASH_REMATCH[1]}"
          ;;
        4|5)
          emit "turn_right value_${BASH_REMATCH[1]}"
          ;;
        *)
          emit "turn_off value_${BASH_REMATCH[1]}"
          ;;
      esac
    fi
  done
}

watch_ticks() {
  while true; do
    sleep 1
    emit "tick"
  done
}

cleanup() {
  if (( stopping == 1 )); then
    return
  fi
  stopping=1
  cancel_pid "$logcat_pid"
  cancel_pid "$pip_logcat_pid"
  cancel_pid "$poll_pid"
  cancel_pid "$tick_pid"
  stop_overlay_now "monitor_exit"
  exec 3>&- 3<&- || true
  rm -rf "$tmpdir"
}

trap cleanup EXIT
trap 'cleanup; exit 130' INT TERM

set_stock_alert_allowed
if [[ "$(overlay_window_bool)" == "true" ]]; then
  adb -s "$serial" shell cmd appops set "$package" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
fi
adb -s "$serial" shell logcat -c >/dev/null 2>&1 || true
echo "monitoring side cameras on $serial: left vp=$left_viewpoint/$left_slot crop=$left_crop_source, right vp=$right_viewpoint/$right_slot crop=$right_crop_source, extend=$center_extend_percent overlay=$overlay_window hide_stock_right=$hide_stock_right"

watch_pip_logcat &
pip_logcat_pid=$!
watch_logcat &
logcat_pid=$!
watch_windows &
poll_pid=$!
watch_ticks &
tick_pid=$!

while IFS= read -r event rest; do
  case "$event" in
    left_start)
      start_overlay "left" "${rest:-logcat}"
      ;;
    left_stop)
      schedule_stop_overlay "left" "${rest:-logcat}"
      ;;
    left_window_on)
      start_overlay "left" "pip_window"
      ;;
    left_window_off)
      schedule_stop_overlay "left" "pip_window_off"
      ;;
    right_alert_on)
      start_overlay "right" "avc_alert"
      ;;
    right_alert_off)
      if [[ "$current_side" == "right" ]]; then
        if (( right_stock_hidden == 1 )); then
          echo "right alert disappeared after hide; waiting for turn-off"
        else
          schedule_stop_overlay "right" "avc_alert_off"
        fi
      fi
      ;;
    turn_left)
      start_overlay "left" "${rest:-turn_state}"
      ;;
    turn_right)
      start_overlay "right" "${rest:-turn_state}"
      ;;
    turn_off)
      if [[ "$current_side" == "left" ]]; then
        schedule_stop_overlay "left" "${rest:-turn_off}"
      elif [[ "$current_side" == "right" ]]; then
        schedule_stop_overlay "right" "${rest:-turn_off}"
      fi
      ;;
    start_now)
      start_overlay_now "$rest"
      pending_start_pid=0
      ;;
    stop_now)
      if [[ -n "$current_side" && "$rest" == "$current_side"* ]]; then
        stop_overlay_now "$rest"
      fi
      pending_stop_pid=0
      ;;
    right_hide)
      pending_hide_pid=0
      if [[ "$current_side" == "right" ]]; then
        set_stock_alert_hidden
      fi
      ;;
    tick)
      if [[ "$current_side" == "right" && "$right_failsafe_s" != "0" ]]; then
        elapsed_ms=$(( $(now_ms) - last_start_ms ))
        if (( elapsed_ms > right_failsafe_s * 1000 )); then
          schedule_stop_overlay "right" "right_failsafe_${right_failsafe_s}s"
        fi
      fi
      ;;
  esac
done <&3
