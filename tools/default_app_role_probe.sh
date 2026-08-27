#!/usr/bin/env bash
# Restore-wrapped live verifier for the AutoVoice default-app roles consumed by
# stock Shortcuts. It changes exactly one PersonBean row, waits for a manual
# Shortcuts Test Run, captures focused evidence, and restores the original row.
#
# It deliberately does not touch Settings.Global.byd_map_package, inject input,
# launch an activity, restart ADB/AutoVoice, or clear the shared logcat buffers.
set -euo pipefail

umask 077

provider_uri="content://com.byd.autovoice/PersonBean"

serial=""
role=""
target_package=""
timeout_seconds=180
require_stopped=0

role_key=""
command_id=""
shortcut_path=""

adb_bin=""
adb_cmd=()
lock_dir=""
lock_acquired=0
transcript_file=""
raw_log=""
logcat_pid=""
capture_started=0
capture_flushed=0
snapshot_ready=0
mutation_attempted=0
original_value=""
applied_value=""

usage() {
  cat <<'EOF'
Usage:
  tools/default_app_role_probe.sh run \
    --serial SERIAL \
    --role navigation|music|video \
    --package TARGET_PACKAGE \
    [--timeout SECONDS] \
    [--require-stopped]

Examples:
  tools/default_app_role_probe.sh run --serial 127.0.0.1:5555 \
    --role music --package ru.yandex.music

  tools/default_app_role_probe.sh run --serial 127.0.0.1:5555 \
    --role video --package com.vk.vkvideo --require-stopped

The probe never stops the target itself. For a cold-start check, stop a safe
third-party target before running it, then pass --require-stopped. Prepare the
Shortcuts action before starting because the role remains changed only for the
bounded manual-test window.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 2
}

is_safe_package() {
  [[ "$1" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]
}

record() {
  if [[ -n "$transcript_file" ]]; then
    printf '%s\n' "$*" | tee -a "$transcript_file"
  else
    printf '%s\n' "$*"
  fi
}

adb_shell() {
  "${adb_cmd[@]}" shell "$@"
}

read_role_strict() {
  local raw row_count setting value

  if ! raw="$(adb_shell "content query --uri $provider_uri --projection _id:SETTING:VALUE --where \"SETTING='$role_key'\"" 2>&1)"; then
    printf 'PersonBean query failed: %s\n' "$raw" >&2
    return 1
  fi
  raw="$(printf '%s\n' "$raw" | tr -d '\r')"
  row_count="$(printf '%s\n' "$raw" | awk '/^Row:/{count++} END{print count+0}')"
  if [[ "$row_count" != "1" ]]; then
    printf 'expected exactly one %s row, got %s: %s\n' "$role_key" "$row_count" "$raw" >&2
    return 1
  fi

  setting="$(printf '%s\n' "$raw" | sed -n 's/.*SETTING=\([^,]*\).*/\1/p')"
  value="$(printf '%s\n' "$raw" | sed -n 's/.*VALUE=\([^,]*\).*/\1/p')"
  if [[ "$setting" != "$role_key" ]]; then
    printf 'unexpected PersonBean setting: %s\n' "$setting" >&2
    return 1
  fi
  if ! is_safe_package "$value"; then
    printf 'unsafe or empty restore value for %s: %s\n' "$role_key" "$value" >&2
    return 1
  fi
  printf '%s\n' "$value"
}

write_role() {
  local value="$1" raw

  if ! raw="$(adb_shell "content update --uri $provider_uri --bind VALUE:s:$value --where \"SETTING='$role_key'\"" 2>&1)"; then
    printf 'PersonBean update failed: %s\n' "$raw" >&2
    return 1
  fi
  raw="$(printf '%s\n' "$raw" | tr -d '\r')"
  # DiLink 5.1's `content update` succeeds with an empty stdout.  Callers
  # always perform an exact readback, so accept that platform-specific form
  # while still rejecting any non-empty, unexpected result.
  if [[ -n "$raw" && ! "$raw" =~ ^Updated[[:space:]]1[[:space:]]rows?\.?$ ]]; then
    printf 'expected one updated row, got: %s\n' "$raw" >&2
    return 1
  fi
}

stop_logcat() {
  local pid="$logcat_pid"
  if [[ -z "$pid" ]]; then
    return
  fi
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
  logcat_pid=""
}

flush_capture() {
  if [[ "$capture_started" != "1" || "$capture_flushed" == "1" || -z "$raw_log" || ! -f "$raw_log" ]]; then
    return
  fi
  capture_flushed=1
  {
    echo
    echo "=== focused logcat (capture began at the applied readback) ==="
    grep -E "TestRunCommandService|ComplexIntent\.QCL|FunctionManager|MusicControlToFunction-summer|MusicControlToFunction|VideoToFunction_sunny|MediaApi_summer|MediaStart|AppMangerBaseUtils|ActivityTaskManager.*START u0|ActivityNotFound|SecurityException|Update failed|FATAL EXCEPTION|com\.byd\.avc|$command_id|$role_key|$target_package" "$raw_log" || true
  } | tee -a "$transcript_file"
}

manual_restore_command() {
  printf '%s\n' "adb -s $serial shell \"content update --uri $provider_uri --bind VALUE:s:$original_value --where \\\"SETTING='$role_key'\\\"\""
}

cleanup() {
  local status=$? restore_write_ok=1 restored="" restore_failed=0 current=""
  trap - EXIT INT TERM HUP
  set +e

  stop_logcat
  flush_capture

  if [[ "$mutation_attempted" == "1" && "$snapshot_ready" == "1" ]]; then
    current="$(read_role_strict 2>/dev/null)"
    if [[ -n "$current" && "$current" != "$applied_value" && "$current" != "$original_value" ]]; then
      record "OWNERSHIP VIOLATION: $role_key changed externally to $current during the probe"
    fi

    record ""
    record "=== restoring $role_key ==="
    write_role "$original_value" >/dev/null 2>&1 || restore_write_ok=0
    restored="$(read_role_strict 2>/dev/null)"
    record "restored_value=$restored"
    if [[ "$restore_write_ok" != "1" || "$restored" != "$original_value" ]]; then
      restore_failed=1
      record "RESTORE FAILED. Run this exact recovery command after the tunnel is healthy:"
      manual_restore_command | tee -a "$transcript_file"
      if [[ "$lock_acquired" == "1" ]]; then
        printf 'Restore failed for %s; inspect %s\n' "$role_key" "$transcript_file" > "$lock_dir/RESTORE_FAILED"
      fi
    else
      record "restore_verified=yes"
    fi
  fi

  if [[ -n "$raw_log" && -f "$raw_log" ]]; then
    rm -f "$raw_log"
  fi

  if [[ "$lock_acquired" == "1" && "$restore_failed" == "0" ]]; then
    rm -f "$lock_dir/owner"
    rmdir "$lock_dir" 2>/dev/null || true
  elif [[ "$restore_failed" == "1" ]]; then
    record "lock_retained=$lock_dir"
  fi

  if [[ -n "$transcript_file" ]]; then
    record "transcript=$transcript_file"
  fi

  if [[ "$restore_failed" == "1" ]]; then
    exit 70
  fi
  exit "$status"
}

resolve_launcher() {
  local category output component
  for category in android.intent.category.INFO android.intent.category.LAUNCHER; do
    output="$(adb_shell "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c $category -p $target_package" 2>/dev/null | tr -d '\r')"
    component="$(printf '%s\n' "$output" | awk -v prefix="$target_package/" 'index($0, prefix) == 1 {found=$0} END{print found}')"
    if [[ -n "$component" ]]; then
      printf '%s\n' "$component"
      return 0
    fi
  done
  return 1
}

pid_of_target() {
  adb_shell "pidof $target_package" 2>/dev/null | tr -d '\r' || true
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
if [[ "${1:-}" != "run" ]]; then
  usage >&2
  exit 2
fi
shift

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      [[ $# -ge 2 ]] || die "--serial requires a value"
      serial="$2"
      shift 2
      ;;
    --role)
      [[ $# -ge 2 ]] || die "--role requires a value"
      role="$2"
      shift 2
      ;;
    --package)
      [[ $# -ge 2 ]] || die "--package requires a value"
      target_package="$2"
      shift 2
      ;;
    --timeout)
      [[ $# -ge 2 ]] || die "--timeout requires a value"
      timeout_seconds="$2"
      shift 2
      ;;
    --require-stopped)
      require_stopped=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

case "$role" in
  navigation)
    role_key="DEFAULT_MAP_SWITCH"
    command_id="102000"
    shortcut_path="导航 → 地图 → 打开"
    ;;
  music)
    role_key="MUSIC_SWITCH"
    command_id="129003"
    shortcut_path="媒体 → 音乐 → 继续播放"
    ;;
  video)
    role_key="VIDEO_SWITCH"
    command_id="131500"
    shortcut_path="媒体 → 视频 → 打开"
    ;;
  *)
    die "--role must be exactly navigation, music, or video"
    ;;
esac

[[ -n "$serial" ]] || die "--serial is required"
[[ "$serial" =~ ^[A-Za-z0-9._:-]+$ ]] || die "unsafe ADB serial: $serial"
[[ -n "$target_package" ]] || die "--package is required"
is_safe_package "$target_package" || die "unsafe Android package: $target_package"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || die "--timeout must be an integer"
(( timeout_seconds >= 30 && timeout_seconds <= 600 )) || die "--timeout must be between 30 and 600 seconds"
[[ -t 0 ]] || die "an interactive terminal is required for the manual Test Run"

adb_bin="$(command -v adb || true)"
[[ -n "$adb_bin" ]] || die "adb is not installed"
adb_cmd=("$adb_bin" -s "$serial")

transcript_file="$(mktemp "/tmp/default_app_role_probe.${role}.log.XXXXXX")"
raw_log="$(mktemp "/tmp/default_app_role_probe.${role}.raw.XXXXXX")"
lock_dir="/tmp/denza-default-app-role-probe.${serial}.lock"

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

if ! mkdir "$lock_dir" 2>/dev/null; then
  die "probe lock exists: $lock_dir (inspect it; stale locks are never removed automatically)"
fi
lock_acquired=1
printf 'pid=%s role=%s package=%s transcript=%s\n' "$$" "$role" "$target_package" "$transcript_file" > "$lock_dir/owner"

record "serial=$serial"
record "role=$role"
record "role_key=$role_key"
record "command_id=$command_id"
record "target_package=$target_package"

device_state="$("${adb_cmd[@]}" get-state 2>/dev/null || true)"
[[ "$device_state" == "device" ]] || die "ADB state is '$device_state', expected 'device'; the probe will not connect or restart ADB"

shell_identity="$(adb_shell id 2>/dev/null | tr -d '\r')"
[[ "$shell_identity" == *"uid=2000(shell)"* ]] || die "expected uid=2000(shell), got: $shell_identity"
record "shell_identity=$shell_identity"

package_path="$(adb_shell "pm path --user 0 $target_package" 2>/dev/null | tr -d '\r')"
[[ "$package_path" == package:* ]] || die "target package is not installed for user 0: $target_package"

resolved_component="$(resolve_launcher || true)"
[[ -n "$resolved_component" ]] || die "target has no enabled MAIN+INFO or MAIN+LAUNCHER component: $target_package"
record "resolved_component=$resolved_component"

original_value="$(read_role_strict)"
snapshot_ready=1
record "original_value=$original_value"
[[ "$target_package" != "$original_value" ]] || die "target already occupies $role_key; this probe would prove nothing"

target_pid_before="$(pid_of_target)"
record "target_pid_before=${target_pid_before:-none}"
if [[ "$require_stopped" == "1" && -n "$target_pid_before" ]]; then
  die "--require-stopped was requested, but $target_package is running as PID $target_pid_before"
fi

current_before_write="$(read_role_strict)"
[[ "$current_before_write" == "$original_value" ]] || die "$role_key changed after snapshot; refusing to overwrite another owner"

mutation_attempted=1
write_role "$target_package"
applied_value="$(read_role_strict)"
record "applied_value=$applied_value"
[[ "$applied_value" == "$target_package" ]] || die "applied readback mismatch: expected $target_package, got $applied_value"

capture_started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
record "capture_started_at=$capture_started_at"
"${adb_cmd[@]}" logcat -T 1 -v threadtime -s \
  "ActivityTaskManager:I" \
  "ActivityManager:I" \
  "TestRunCommandService:V" \
  "ComplexIntent.QCL:V" \
  "FunctionManager:V" \
  "MusicControlToFunction-summer:V" \
  "MusicControlToFunction:V" \
  "VideoToFunction_sunny:V" \
  "MediaApi_summer:V" \
  "MediaStart:V" \
  "AppMangerBaseUtils:V" \
  "AndroidRuntime:E" \
  "*:S" > "$raw_log" 2>&1 &
logcat_pid=$!
capture_started=1

record ""
record "READY: on the IVI, run Shortcuts → $shortcut_path → Test Run."
record "Do not Save a temporary rule. Do not inject key events, broadcasts, or voice input."
record "Test Run needs the IVI's normal network connection before AutoVoice executes the local action."
if [[ "$role" == "navigation" ]]; then
  record "Never use key code 321: on this car it starts the APA parking scan."
fi
if [[ "$role" == "video" ]]; then
  record "Use a clean fullscreen scene with no registered video app already visible in a split pane."
fi
record "Press Enter here only after the target appears. Automatic rollback occurs after ${timeout_seconds}s."

if ! IFS= read -r -t "$timeout_seconds" _; then
  record "TIMEOUT/EOF: no confirmation received; restoring without claiming a result"
  exit 124
fi

stop_logcat
flush_capture

target_pid_after="$(pid_of_target)"
foreground="$(adb_shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'" 2>/dev/null | tr -d '\r' || true)"
record ""
record "=== post-trigger state ==="
record "target_pid_after=${target_pid_after:-none}"
record "foreground=${foreground:-unavailable}"

test_run_seen=0
command_seen=0
consumer_seen=0
start_seen=0
foreground_seen=0
video_gate_seen=1
error_seen=0

grep -F "testRun start execute" "$raw_log" >/dev/null 2>&1 && test_run_seen=1
grep -F "exeDiyCommand id = $command_id" "$raw_log" >/dev/null 2>&1 && command_seen=1
if [[ "$role" == "music" ]]; then
  if grep -F "defaultMusicPkg:$target_package" "$raw_log" >/dev/null 2>&1 \
    || grep -F "getDefaultPkg type=MUSIC_SWITCH,pkgName=$target_package" "$raw_log" >/dev/null 2>&1; then
    consumer_seen=1
  fi
elif [[ "$role" == "video" ]]; then
  grep -F "defaultVideoPkg:$target_package" "$raw_log" >/dev/null 2>&1 && consumer_seen=1
  grep -F "mediaType:6,isNetworkEnable:true" "$raw_log" >/dev/null 2>&1 || video_gate_seen=0
else
  if grep -F "curMapPackage=$target_package" "$raw_log" >/dev/null 2>&1 \
    || grep -F "curMapPackage:$target_package" "$raw_log" >/dev/null 2>&1; then
    consumer_seen=1
  fi
fi
if grep -F "ActivityTaskManager" "$raw_log" | grep -F "START u0" | grep -F "cmp=$target_package/" >/dev/null 2>&1; then
  start_seen=1
fi
[[ "$foreground" == *"$target_package/"* ]] && foreground_seen=1
if grep -E "ActivityNotFound|SecurityException|Update failed|FATAL EXCEPTION" "$raw_log" >/dev/null 2>&1; then
  error_seen=1
fi

record "evidence_test_run=$test_run_seen"
record "evidence_command_$command_id=$command_seen"
record "evidence_consumer_target=$consumer_seen"
record "evidence_target_start=$start_seen"
record "evidence_target_foreground=$foreground_seen"
if [[ "$role" == "video" ]]; then
  record "evidence_video_network_gate=$video_gate_seen"
fi
record "evidence_errors=$error_seen"

if [[ "$test_run_seen" == "1" && "$command_seen" == "1" && "$consumer_seen" == "1" \
  && "$start_seen" == "1" && "$foreground_seen" == "1" && "$video_gate_seen" == "1" \
  && "$error_seen" == "0" ]]; then
  record "RESULT=PASS"
  exit 0
fi

if [[ "$role" == "video" && "$command_seen" == "1" && "$video_gate_seen" == "0" ]]; then
  record "RESULT=INCONCLUSIVE_VIDEO_NETWORK_GATE"
else
  record "RESULT=FAIL_OR_INCONCLUSIVE"
fi
exit 1
