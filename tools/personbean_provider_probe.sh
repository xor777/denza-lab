#!/usr/bin/env bash
# Restore-wrapped live driver for the app-UID PersonBean ContentResolver probe.
#
# It tests whether an ordinary app UID can read, write and observe
# content://com.byd.autovoice/PersonBean without ADB, and how fast that round
# trip is compared with one shell `content query` (app_process VM spawn).
#
# The run mutates exactly one PersonBean row, from a value read through the
# already-proven shell oracle, verifies every app-UID write with an independent
# shell readback, and restores the row through the shell path. The cleanup trap
# restores and re-verifies on any earlier exit.
#
# It deliberately never restarts ADB or AutoVoice, never launches an Activity,
# never injects input, and never clears a logcat buffer.
set -euo pipefail

umask 077

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

provider_uri="content://com.byd.autovoice/PersonBean"
provider_package="com.byd.autovoice"
probe_package="dev.denza.personbean.probe"
receiver_component="$probe_package/.ProbeReceiver"
wake_activity=".ProbeWakeActivity"
action_prefix="dev.denza.personbean.probe"
gradle_task=":personbean-provider-probe:assembleDebug"
apk_path="$repo_root/experiments/personbean-provider-probe/build/outputs/apk/debug/personbean-provider-probe.apk"

default_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
default_android_home="/opt/homebrew/share/android-commandlinetools"

serial="${ADB_SERIAL:-127.0.0.1:5555}"
adb_bin=""
adb_cmd=()

# --- run state -------------------------------------------------------------
role_name="music"
role_key="MUSIC_SWITCH"
value_arg=""
keep_probe=0
no_build=0

transcript_file=""
lock_dir=""
lock_acquired=0
snapshot_ready=0
mutation_attempted=0
restore_completed=0
probe_installed=0
crash_baseline_ready=0

original_map=""
original_music=""
original_video=""
oracle_map=""
oracle_music=""
oracle_video=""

target_key=""
target_value=""
original_target=""

crash_avc_before=0
crash_total_before=0

read_via_app_uid=""
write_via_app_uid=""
notify_own=""
notify_external=""
observer_note=""
query_ms_median=""
shell_content_query_s=""
restore_verified=""
avc_alert=0

broadcast_raw=""
broadcast_data=""
broadcast_result_code=""

usage() {
  cat <<'EOF'
Usage:
  tools/personbean_provider_probe.sh build
  tools/personbean_provider_probe.sh install [--no-build] [--serial SERIAL]
  tools/personbean_provider_probe.sh uninstall [--serial SERIAL]
  tools/personbean_provider_probe.sh query [--role KEY] [--serial SERIAL]
  tools/personbean_provider_probe.sh run [--role music|video|navigation]
                                         [--value PACKAGE] [--keep]
                                         [--no-build] [--serial SERIAL]
  tools/personbean_provider_probe.sh --help

Subcommands:
  build      assembles :personbean-provider-probe (debug).
  install    builds (unless --no-build), installs the probe APK, verifies
             `pm path`. Installs no Activity and launches nothing.
  uninstall  force-stops and removes the probe package.
  query      sends one probe QUERY broadcast and prints its result data line.
             --role takes a PersonBean key (DEFAULT_MAP_SWITCH, MUSIC_SWITCH,
             VIDEO_SWITCH); the friendly names navigation/music/video are also
             accepted. Without --role all three roles are queried.
  run        the full restore-wrapped hypothesis test (see below).

This firmware drops a broadcast to a third-party app that has no live process
("Self start permission detection" -> "skip reciever ... ignored"), so the
driver first starts the probe's own no-display ProbeWakeActivity and waits for
its PID; it re-wakes the probe whenever the process is gone before a broadcast.

`run` mutates exactly one PersonBean row and restores it from the recorded
original through the shell path, with an independent shell readback after every
app-UID write. Default role is music, default value is that role's stock
package (music=com.byd.mediacenter, video=com.byd.videoplay,
navigation=com.byd.launchermap). --role navigation is refused unless --value is
given explicitly, because DEFAULT_MAP_SWITCH is a live product binding.

Environment:
  ADB_SERIAL    device serial, default 127.0.0.1:5555 (--serial overrides)
  JAVA_HOME     default /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  ANDROID_HOME  default /opt/homebrew/share/android-commandlinetools

Exit codes:
  0   read, write and restore all verified
  1   the run completed but a verdict was not ok
  2   usage or precondition failure
  70  RESTORE FAILED - the manual recovery command is printed and the lock kept
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 2
}

record() {
  if [[ -n "$transcript_file" ]]; then
    printf '%s\n' "$*" | tee -a "$transcript_file"
  else
    printf '%s\n' "$*"
  fi
}

is_safe_package() {
  [[ "$1" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]
}

is_number() {
  [[ "$1" =~ ^-?[0-9]+(\.[0-9]+)?$ ]]
}

is_integer() {
  [[ "$1" =~ ^-?[0-9]+$ ]]
}

is_role_key() {
  case "$1" in
    DEFAULT_MAP_SWITCH|MUSIC_SWITCH|VIDEO_SWITCH) return 0 ;;
    *) return 1 ;;
  esac
}

# --- pure parsing helpers (no side effects, unit-testable) -----------------

# Extract the result code from `am broadcast` output.
broadcast_result_code_of() {
  printf '%s\n' "$1" | tr -d '\r' \
    | sed -n 's/^Broadcast completed: result=\(-*[0-9][0-9]*\).*$/\1/p' \
    | tail -n 1
}

# Extract the result data of `am broadcast`: everything between the first
# `data="` and the last `"` on the completion line.
broadcast_data_of() {
  printf '%s\n' "$1" | tr -d '\r' | awk '
    index($0, "Broadcast completed:") == 1 {
      i = index($0, "data=\"")
      if (i == 0) next
      rest = substr($0, i + 6)
      j = length(rest)
      while (j > 0 && substr(rest, j, 1) != "\"") j--
      if (j == 0) next
      out = substr(rest, 1, j - 1)
      found = 1
    }
    END { if (found) print out }
  '
}

# probe_value KEY [LINE] - value of one `key=value` token of a probe result
# line. LINE defaults to the data of the last broadcast.
probe_value() {
  local key="$1"
  local line="${2-$broadcast_data}"
  printf '%s\n' "$line" | awk -v key="$key" '{
    for (i = 1; i <= NF; i++) {
      p = index($i, "=")
      if (p > 0 && substr($i, 1, p - 1) == key) {
        print substr($i, p + 1)
        exit
      }
    }
  }'
}

# Read `content query` output on stdin, print one "SETTING VALUE" line per row.
# A row that lacks either column is printed as "MALFORMED <row>" so callers
# fail closed instead of silently seeing fewer rows.
parse_person_rows() {
  tr -d '\r' | awk '
    /^Row:/ {
      line = $0
      sub(/^Row: *[0-9]+ */, "", line)
      n = split(line, f, ", ")
      setting = ""; value = ""; have_s = 0; have_v = 0
      for (i = 1; i <= n; i++) {
        if (substr(f[i], 1, 8) == "SETTING=") { setting = substr(f[i], 9); have_s = 1 }
        else if (substr(f[i], 1, 6) == "VALUE=") { value = substr(f[i], 7); have_v = 1 }
      }
      if (have_s && have_v && setting != "" && value != "") print setting " " value
      else print "MALFORMED " line
    }
  '
}

# Read the stderr of the device shell `time` builtin on stdin, print the real
# time in seconds. Accepts `0m1.20s`, `1.20s` and bare-float spellings.
parse_real_seconds() {
  tr -d '\r' | awk '
    index($0, "real") > 0 {
      n = split($0, t, /[ \t]+/)
      for (i = 1; i <= n; i++) {
        tok = t[i]
        if (tok ~ /^[0-9]+m[0-9]+(\.[0-9]+)?s?$/) {
          p = index(tok, "m")
          mins = substr(tok, 1, p - 1) + 0
          secs = substr(tok, p + 1)
          sub(/s$/, "", secs)
          printf "%.3f\n", mins * 60 + secs
          exit
        }
        if (tok ~ /^[0-9]+(\.[0-9]+)?s$/ || tok ~ /^[0-9]+\.[0-9]+$/) {
          sub(/s$/, "", tok)
          printf "%.3f\n", tok + 0
          exit
        }
      }
    }
  '
}

# Median of the numbers on stdin, one per line, to one decimal.
median_of() {
  awk '
    { v[NR] = $1 + 0 }
    END {
      if (NR == 0) exit 1
      for (i = 1; i <= NR; i++)
        for (j = i + 1; j <= NR; j++)
          if (v[j] < v[i]) { t = v[i]; v[i] = v[j]; v[j] = t }
      if (NR % 2) printf "%.1f\n", v[(NR + 1) / 2]
      else printf "%.1f\n", (v[NR / 2] + v[NR / 2 + 1]) / 2
    }'
}

count_lines() {
  awk 'END { print NR + 0 }'
}

role_key_of() {
  case "$1" in
    navigation|DEFAULT_MAP_SWITCH) printf 'DEFAULT_MAP_SWITCH\n' ;;
    music|MUSIC_SWITCH) printf 'MUSIC_SWITCH\n' ;;
    video|VIDEO_SWITCH) printf 'VIDEO_SWITCH\n' ;;
    *) return 1 ;;
  esac
}

role_name_of() {
  case "$1" in
    navigation|DEFAULT_MAP_SWITCH) printf 'navigation\n' ;;
    music|MUSIC_SWITCH) printf 'music\n' ;;
    video|VIDEO_SWITCH) printf 'video\n' ;;
    *) return 1 ;;
  esac
}

stock_package_of() {
  case "$1" in
    DEFAULT_MAP_SWITCH) printf 'com.byd.launchermap\n' ;;
    MUSIC_SWITCH) printf 'com.byd.mediacenter\n' ;;
    VIDEO_SWITCH) printf 'com.byd.videoplay\n' ;;
    *) return 1 ;;
  esac
}

# --- device helpers --------------------------------------------------------

setup_adb() {
  local android_home
  android_home="${ANDROID_HOME:-$default_android_home}"
  adb_bin="$(command -v adb || true)"
  if [[ -z "$adb_bin" && -x "$android_home/platform-tools/adb" ]]; then
    adb_bin="$android_home/platform-tools/adb"
  fi
  [[ -n "$adb_bin" ]] || die "adb not found on PATH or at $android_home/platform-tools/adb"
  [[ "$serial" =~ ^[A-Za-z0-9._:-]+$ ]] || die "unsafe ADB serial: $serial"
  adb_cmd=("$adb_bin" -s "$serial")
}

require_device() {
  local state
  state="$("${adb_cmd[@]}" get-state 2>/dev/null || true)"
  [[ "$state" == "device" ]] \
    || die "ADB state is '${state:-missing}' on $serial, expected 'device'; this probe never connects or restarts ADB"
}

require_shell_identity() {
  local identity
  identity="$("${adb_cmd[@]}" shell id 2>/dev/null | tr -d '\r' || true)"
  [[ "$identity" == *"uid=2000(shell)"* ]] || die "expected uid=2000(shell), got: ${identity:-none}"
  record "shell_identity=$identity"
}

adb_shell() {
  "${adb_cmd[@]}" shell "$@"
}

package_installed() {
  local path
  path="$(adb_shell "pm path --user 0 $1" 2>/dev/null | tr -d '\r' || true)"
  [[ "$path" == package:* ]] || return 1
  printf '%s\n' "$path"
}

probe_pid() {
  "${adb_cmd[@]}" shell "pidof $probe_package" 2>/dev/null \
    | tr -d '\r' | awk 'NF { print $1; exit }' || true
}

# This firmware drops a broadcast whose target UID has no live process
# ("Self start permission detection" -> "skip reciever ... ignored"), so the
# probe needs a process before it can receive anything. ProbeWakeActivity is a
# Theme.NoDisplay activity of the probe itself that finishes in onCreate; it is
# the only Activity this driver ever starts, and it belongs to the probe.
wake_probe() {
  local out pid i
  out="$("${adb_cmd[@]}" shell am start -W -n "$probe_package/$wake_activity" 2>&1 | tr -d '\r' || true)"
  record "wake_start=$(printf '%s' "$out" | tr '\n' ' ')"
  for i in {1..20}; do
    pid="$(probe_pid)"
    if [[ -n "$pid" ]]; then
      record "probe_pid=$pid"
      return 0
    fi
    sleep 0.1
  done
  die "$probe_package has no process after $wake_activity; this firmware will drop every broadcast to it"
}

ensure_probe_running() {
  local pid
  pid="$(probe_pid)"
  if [[ -z "$pid" ]]; then
    record "probe_rewoken=1"
    wake_probe
  fi
}

# --- shell oracle ----------------------------------------------------------

# One `content query` for the three roles. Fills oracle_map/music/video.
read_oracle_triplet() {
  local raw rows n key value seen_map=0 seen_music=0 seen_video=0

  if ! raw="$(adb_shell "content query --uri $provider_uri --projection _id:SETTING:VALUE --where \"SETTING IN ('DEFAULT_MAP_SWITCH','MUSIC_SWITCH','VIDEO_SWITCH')\"" 2>&1)"; then
    printf 'PersonBean oracle query failed: %s\n' "$raw" >&2
    return 1
  fi
  rows="$(printf '%s\n' "$raw" | parse_person_rows)"
  n="$(printf '%s\n' "$rows" | awk 'NF { c++ } END { print c + 0 }')"
  if [[ "$n" != "3" ]]; then
    printf 'expected exactly 3 PersonBean rows, got %s: %s\n' "$n" "$raw" >&2
    return 1
  fi

  oracle_map=""
  oracle_music=""
  oracle_video=""
  while IFS=' ' read -r key value; do
    [[ -n "$key" ]] || continue
    if ! is_safe_package "$value"; then
      printf 'unsafe or empty PersonBean value for %s: %s\n' "$key" "$value" >&2
      return 1
    fi
    case "$key" in
      DEFAULT_MAP_SWITCH) oracle_map="$value"; seen_map=$((seen_map + 1)) ;;
      MUSIC_SWITCH) oracle_music="$value"; seen_music=$((seen_music + 1)) ;;
      VIDEO_SWITCH) oracle_video="$value"; seen_video=$((seen_video + 1)) ;;
      *)
        printf 'unexpected PersonBean row: %s %s\n' "$key" "$value" >&2
        return 1
        ;;
    esac
  done <<< "$rows"

  if (( seen_map != 1 || seen_music != 1 || seen_video != 1 )); then
    printf 'PersonBean oracle did not return each role exactly once: %s\n' "$rows" >&2
    return 1
  fi
}

original_value_of() {
  case "$1" in
    DEFAULT_MAP_SWITCH) printf '%s\n' "$original_map" ;;
    MUSIC_SWITCH) printf '%s\n' "$original_music" ;;
    VIDEO_SWITCH) printf '%s\n' "$original_video" ;;
  esac
}

# Independent single-role shell readback.
read_role_strict() {
  local key="$1" raw rows n setting value

  if ! raw="$(adb_shell "content query --uri $provider_uri --projection _id:SETTING:VALUE --where \"SETTING='$key'\"" 2>&1)"; then
    printf 'PersonBean query failed: %s\n' "$raw" >&2
    return 1
  fi
  rows="$(printf '%s\n' "$raw" | parse_person_rows)"
  n="$(printf '%s\n' "$rows" | awk 'NF { c++ } END { print c + 0 }')"
  if [[ "$n" != "1" ]]; then
    printf 'expected exactly one %s row, got %s: %s\n' "$key" "$n" "$raw" >&2
    return 1
  fi
  setting="$(printf '%s\n' "$rows" | awk 'NF { print $1; exit }')"
  value="$(printf '%s\n' "$rows" | awk 'NF { print $2; exit }')"
  if [[ "$setting" != "$key" ]]; then
    printf 'unexpected PersonBean setting: %s\n' "$setting" >&2
    return 1
  fi
  if ! is_safe_package "$value"; then
    printf 'unsafe or empty value for %s: %s\n' "$key" "$value" >&2
    return 1
  fi
  printf '%s\n' "$value"
}

# The already-proven shell write path, used for restore only.
write_role_shell() {
  local key="$1" value="$2" raw

  if ! raw="$(adb_shell "content update --uri $provider_uri --bind VALUE:s:$value --where \"SETTING='$key'\"" 2>&1)"; then
    printf 'PersonBean update failed: %s\n' "$raw" >&2
    return 1
  fi
  raw="$(printf '%s\n' "$raw" | tr -d '\r')"
  # DiLink 5.1's `content update` succeeds with an empty stdout. Callers always
  # perform an exact readback, so accept that platform-specific form while
  # still rejecting any non-empty, unexpected result.
  if [[ -n "$raw" && ! "$raw" =~ ^Updated[[:space:]]1[[:space:]]rows?\.?$ ]]; then
    printf 'expected one updated row, got: %s\n' "$raw" >&2
    return 1
  fi
}

manual_restore_command() {
  printf '%s\n' "adb -s $serial shell \"content update --uri $provider_uri --bind VALUE:s:$original_target --where \\\"SETTING='$target_key'\\\"\""
}

# --- probe broadcast -------------------------------------------------------

# A broadcast that completes with no result data is usually the firmware
# refusing to deliver it to a UID with no live process. Say so in the
# transcript instead of leaving an unexplained empty result. Note the vendor's
# own misspelling of "reciever" - it is matched literally.
diagnose_self_start_gate() {
  local tail_log hits
  tail_log="$("${adb_cmd[@]}" shell "logcat -d -v time" 2>/dev/null | tr -d '\r' | tail -n 400 || true)"
  hits="$(printf '%s\n' "$tail_log" \
    | grep -E "skip reciever for uid .* name = $probe_package|Self start permission detection.*$probe_package" || true)"
  if [[ -n "$hits" ]]; then
    record "firmware_self_start_gate=1"
    record "$hits"
  fi
}

probe_broadcast() {
  local action="$1"
  shift
  local raw status=0

  raw="$("${adb_cmd[@]}" shell am broadcast --include-stopped-packages \
    -n "$receiver_component" -a "$action_prefix.$action" "$@" 2>&1)" || status=$?

  broadcast_raw="$(printf '%s\n' "$raw" | tr -d '\r')"
  broadcast_result_code="$(broadcast_result_code_of "$broadcast_raw")"
  broadcast_data="$(broadcast_data_of "$broadcast_raw")"

  if (( status != 0 )); then
    record "broadcast_failed action=$action adb_status=$status"
    record "$broadcast_raw"
    return 1
  fi
  if [[ -z "$broadcast_data" ]]; then
    record "broadcast_missing_data action=$action"
    record "$broadcast_raw"
    diagnose_self_start_gate
    return 1
  fi
  record "broadcast=$action result=${broadcast_result_code:-none} data=$broadcast_data"
  return 0
}

probe_broadcast_ok() {
  local action="$1"
  probe_broadcast "$@" || die "probe $action broadcast produced no result line (see transcript)"
  [[ "$broadcast_result_code" == "0" ]] \
    || die "probe $action failed with result=${broadcast_result_code:-none}: $broadcast_data"
}

# --- build / install -------------------------------------------------------

build_probe() {
  (
    cd "$repo_root"
    JAVA_HOME="${JAVA_HOME:-$default_java_home}" \
      ANDROID_HOME="${ANDROID_HOME:-$default_android_home}" \
      ./gradlew "$gradle_task"
  )
  [[ -f "$apk_path" ]] || die "build finished but the APK is missing: $apk_path"
  record "apk=$apk_path"
}

install_probe() {
  local out
  if (( no_build == 0 )); then
    build_probe
  fi
  [[ -f "$apk_path" ]] || die "APK not found: $apk_path (build it, or drop --no-build)"
  probe_installed=1
  out="$("${adb_cmd[@]}" install -r "$apk_path" 2>&1)" || {
    record "$out"
    die "adb install failed for $apk_path"
  }
  record "install=$(printf '%s' "$out" | tr -d '\r' | tr '\n' ' ')"
  local path
  path="$(package_installed "$probe_package")" \
    || die "$probe_package is not installed for user 0 after install"
  record "probe_path=$path"
}

remove_probe() {
  "${adb_cmd[@]}" shell am force-stop "$probe_package" >/dev/null 2>&1 || true
  "${adb_cmd[@]}" uninstall "$probe_package" >/dev/null 2>&1 || return 1
  return 0
}

# --- crash buffer ----------------------------------------------------------

crash_dump() {
  "${adb_cmd[@]}" shell "logcat -b crash -d -v time" 2>/dev/null | tr -d '\r' || true
}

crash_check() {
  local raw total after_avc delta new_avc

  raw="$(crash_dump)"
  if [[ -z "$raw" ]]; then
    total=0
  else
    total="$(printf '%s\n' "$raw" | count_lines)"
  fi
  after_avc="$(printf '%s\n' "$raw" | grep -c 'com\.byd\.avc' || true)"

  record ""
  record "=== crash buffer ==="
  record "crash_lines_before=$crash_total_before crash_lines_after=$total"
  record "avc_lines_before=$crash_avc_before avc_lines_after=$after_avc"

  if (( total >= crash_total_before )); then
    delta="$(printf '%s\n' "$raw" | tail -n "+$((crash_total_before + 1))")"
  else
    record "crash_buffer_rotated=1 (whole buffer inspected)"
    delta="$raw"
  fi

  new_avc="$(printf '%s\n' "$delta" | grep -E 'com\.byd\.avc' || true)"
  if (( after_avc > crash_avc_before )) || [[ -n "$new_avc" ]]; then
    avc_alert=1
    record "AVC_ALERT=1"
    record "$new_avc"
  else
    record "AVC_ALERT=0"
  fi

  local fatal
  fatal="$(printf '%s\n' "$delta" | grep -E 'FATAL EXCEPTION' || true)"
  if [[ -n "$fatal" ]] && printf '%s\n' "$delta" | grep -E 'com\.byd\.autovoice' >/dev/null 2>&1; then
    record "autovoice_fatal=1"
    record "$(printf '%s\n' "$delta" | grep -E 'FATAL EXCEPTION|com\.byd\.autovoice' || true)"
  fi
}

# --- verdict / cleanup -----------------------------------------------------

print_verdict() {
  record ""
  record "=== verdict ==="
  record "read_via_app_uid=${read_via_app_uid:-not_reached}"
  record "write_via_app_uid=${write_via_app_uid:-not_reached}"
  record "notify_own=${notify_own:-not_reached}"
  record "notify_external=${notify_external:-not_reached}"
  if [[ -n "$observer_note" ]]; then
    record "observer=$observer_note"
  fi
  record "query_ms_median=${query_ms_median:-not_reached}"
  record "shell_content_query_s=${shell_content_query_s:-not_reached}"
  record "restore_verified=${restore_verified:-no}"
  record "AVC_ALERT=$avc_alert"
  record "transcript=$transcript_file"
  return 0
}

cleanup() {
  local status=$? restore_write_ok=1 restore_failed=0 current="" restored=""
  trap - EXIT INT TERM HUP
  set +e

  if [[ "$mutation_attempted" == "1" && "$snapshot_ready" == "1" && "$restore_completed" != "1" ]]; then
    current="$(read_role_strict "$target_key" 2>/dev/null)"
    if [[ -n "$current" && "$current" != "$target_value" && "$current" != "$original_target" ]]; then
      record "OWNERSHIP VIOLATION: $target_key changed externally to $current during the probe"
    fi

    record ""
    record "=== restoring $target_key ==="
    write_role_shell "$target_key" "$original_target" >/dev/null 2>&1 || restore_write_ok=0
    restored="$(read_role_strict "$target_key" 2>/dev/null)"
    record "restored_value=${restored:-unreadable}"
    if [[ "$restore_write_ok" != "1" || "$restored" != "$original_target" ]]; then
      restore_failed=1
    elif read_oracle_triplet 2>/dev/null \
      && [[ "$oracle_map" == "$original_map" && "$oracle_music" == "$original_music" && "$oracle_video" == "$original_video" ]]; then
      restore_verified="yes"
      record "restore_verified=yes"
    else
      restore_failed=1
      record "final PersonBean oracle does not match the baseline"
    fi

    if [[ "$restore_failed" == "1" ]]; then
      restore_verified="no"
      record "RESTORE FAILED. Run this exact recovery command after the tunnel is healthy:"
      manual_restore_command | tee -a "$transcript_file"
      if [[ "$lock_acquired" == "1" ]]; then
        printf 'Restore failed for %s; inspect %s\n' "$target_key" "$transcript_file" > "$lock_dir/RESTORE_FAILED"
      fi
    fi
  fi

  if [[ "$crash_baseline_ready" == "1" ]]; then
    crash_check
  fi

  if [[ "$probe_installed" == "1" ]]; then
    if [[ "$keep_probe" == "1" ]]; then
      record "probe_kept=$probe_package"
    elif [[ "$restore_failed" == "1" ]]; then
      record "probe_kept=$probe_package (retained for inspection after RESTORE FAILED)"
    elif remove_probe; then
      record "probe_uninstalled=$probe_package"
    else
      record "probe_uninstall_failed=$probe_package"
    fi
  fi

  print_verdict

  if [[ "$lock_acquired" == "1" && "$restore_failed" == "0" ]]; then
    rm -f "$lock_dir/owner"
    rmdir "$lock_dir" 2>/dev/null || true
  elif [[ "$restore_failed" == "1" ]]; then
    record "lock_retained=$lock_dir"
  fi

  if [[ "$restore_failed" == "1" ]]; then
    exit 70
  fi
  exit "$status"
}

# --- subcommands -----------------------------------------------------------

refuse_when_locked() {
  lock_dir="/tmp/denza-personbean-provider-probe.${serial}.lock"
  [[ ! -d "$lock_dir" ]] \
    || die "a run owns the car: $lock_dir (inspect it; stale locks are never removed automatically)"
}

cmd_build() {
  build_probe
}

cmd_install() {
  setup_adb
  refuse_when_locked
  require_device
  install_probe
  echo "Installed without launching an Activity."
}

cmd_uninstall() {
  setup_adb
  refuse_when_locked
  require_device
  if remove_probe; then
    echo "uninstalled: $probe_package"
  else
    die "pm uninstall failed for $probe_package"
  fi
}

cmd_query() {
  local key="$1"
  setup_adb
  require_device
  package_installed "$probe_package" >/dev/null \
    || die "$probe_package is not installed; run: $0 install"
  wake_probe >&2
  # The narration goes to stderr so stdout carries only the probe result line.
  if [[ -n "$key" ]]; then
    probe_broadcast QUERY --es role "$key" >&2 || exit 1
  else
    probe_broadcast QUERY >&2 || exit 1
  fi
  printf '%s\n' "$broadcast_data"
  [[ "$broadcast_result_code" == "0" ]] || exit 1
}

cmd_run() {
  local raw path pid timing i ms samples="" rows uid rv rk rvv
  local seen_map=0 seen_music=0 seen_video=0
  local update_count readback notified notify_ms
  local nonce_observe nonce_report events_after_own events_after_external
  local run_exit=1

  setup_adb

  transcript_file="$(mktemp "/tmp/personbean_provider_probe.${role_name}.log.XXXXXX")"
  lock_dir="/tmp/denza-personbean-provider-probe.${serial}.lock"

  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP

  # 1. Lock, device state, shell identity, provider presence.
  if ! mkdir "$lock_dir" 2>/dev/null; then
    die "probe lock exists: $lock_dir (inspect it; stale locks are never removed automatically)"
  fi
  lock_acquired=1
  printf 'pid=%s role=%s value=%s transcript=%s\n' \
    "$$" "$role_name" "${value_arg:-default}" "$transcript_file" > "$lock_dir/owner"

  record "serial=$serial"
  record "role=$role_name"
  record "role_key=$role_key"
  record "keep=$keep_probe"
  record "no_build=$no_build"

  require_device
  require_shell_identity

  path="$(package_installed "$provider_package")" \
    || die "$provider_package is not installed for user 0"
  record "autovoice_path=$path"

  # 2. Shell oracle baseline + crash baseline.
  read_oracle_triplet || die "could not read the PersonBean shell oracle"
  original_map="$oracle_map"
  original_music="$oracle_music"
  original_video="$oracle_video"
  snapshot_ready=1
  record "original_DEFAULT_MAP_SWITCH=$original_map"
  record "original_MUSIC_SWITCH=$original_music"
  record "original_VIDEO_SWITCH=$original_video"

  raw="$(crash_dump)"
  if [[ -z "$raw" ]]; then
    crash_total_before=0
  else
    crash_total_before="$(printf '%s\n' "$raw" | count_lines)"
  fi
  crash_avc_before="$(printf '%s\n' "$raw" | grep -c 'com\.byd\.avc' || true)"
  crash_baseline_ready=1
  record "crash_lines_before=$crash_total_before"
  record "avc_lines_before=$crash_avc_before"

  pid="$(adb_shell "pidof $provider_package" 2>/dev/null | tr -d '\r' || true)"
  record "autovoice_pid_before=${pid:-none}"

  # 3. Timing oracle: one shell `content query`.
  timing="$(adb_shell "time content query --uri $provider_uri --projection _id:SETTING:VALUE --where \"SETTING='MUSIC_SWITCH'\" >/dev/null" 2>&1 || true)"
  shell_content_query_s="$(printf '%s\n' "$timing" | parse_real_seconds)"
  if [[ -z "$shell_content_query_s" ]]; then
    shell_content_query_s="unknown"
    record "shell_time_raw=$(printf '%s' "$timing" | tr -d '\r' | tr '\n' ' ')"
  fi
  record "shell_content_query_s=$shell_content_query_s"

  # 4. Build and install the probe, then give it a process to receive with.
  install_probe
  wake_probe

  # 5. App-UID read of all three roles against the shell oracle.
  ensure_probe_running
  probe_broadcast_ok QUERY
  uid="$(probe_value uid)"
  rows="$(probe_value rows)"
  record "uid=$uid"
  record "query_ms=$(probe_value query_ms)"
  if [[ "$rows" != "3" ]]; then
    read_via_app_uid="fail"
    die "probe QUERY returned rows=$rows, expected 3: $broadcast_data"
  fi
  for i in 0 1 2; do
    rv="$(probe_value "r$i")"
    [[ -n "$rv" ]] || { read_via_app_uid="fail"; die "probe QUERY has no r$i token: $broadcast_data"; }
    rk="${rv%%:*}"
    rvv="${rv#*:}"
    is_role_key "$rk" || { read_via_app_uid="fail"; die "probe QUERY r$i has an unknown role key: $rv"; }
    case "$rk" in
      DEFAULT_MAP_SWITCH) seen_map=$((seen_map + 1)) ;;
      MUSIC_SWITCH) seen_music=$((seen_music + 1)) ;;
      VIDEO_SWITCH) seen_video=$((seen_video + 1)) ;;
    esac
    if [[ "$rvv" != "$(original_value_of "$rk")" ]]; then
      read_via_app_uid="fail"
      die "probe QUERY disagrees with the shell oracle for $rk: app=$rvv shell=$(original_value_of "$rk")"
    fi
  done
  if (( seen_map != 1 || seen_music != 1 || seen_video != 1 )); then
    read_via_app_uid="fail"
    die "probe QUERY did not return each role exactly once: $broadcast_data"
  fi
  read_via_app_uid="ok"
  record "read_via_app_uid=ok"

  # 6. Five single-role app-UID queries for the median round trip.
  ensure_probe_running
  for i in 1 2 3 4 5; do
    probe_broadcast_ok QUERY --es role MUSIC_SWITCH
    ms="$(probe_value query_ms)"
    is_number "$ms" || die "probe QUERY sample $i has no numeric query_ms: $broadcast_data"
    record "query_ms_sample_$i=$ms"
    samples="$samples$ms
"
  done
  query_ms_median="$(printf '%s' "$samples" | median_of)" || die "could not compute the query_ms median"
  record "query_ms_median=$query_ms_median"

  # 7. Process-lifetime observer.
  ensure_probe_running
  probe_broadcast_ok OBSERVE
  [[ "$(probe_value registered)" == "true" ]] || die "probe OBSERVE did not register: $broadcast_data"
  nonce_observe="$(probe_value nonce)"
  [[ -n "$nonce_observe" ]] || die "probe OBSERVE returned no nonce: $broadcast_data"
  record "nonce_observe=$nonce_observe"
  record "observe_already=$(probe_value already)"

  # 8. Resolve the role and the value to write.
  target_key="$role_key"
  if [[ -n "$value_arg" ]]; then
    target_value="$value_arg"
  else
    target_value="$(stock_package_of "$target_key")"
  fi
  original_target="$(original_value_of "$target_key")"
  record "target_key=$target_key"
  record "target_value=$target_value"
  record "original_target=$original_target"

  if [[ "$target_value" == "$original_target" ]]; then
    die "$target_key already equals $target_value; the write would prove nothing. Pass --value with another installed package (check it with: adb -s $serial shell pm path --user 0 <package>)."
  fi
  path="$(package_installed "$target_value")" \
    || die "--value package is not installed for user 0: $target_value"
  record "target_value_path=$path"

  # 9. The one app-UID write, with an independent shell readback.
  # A re-wake here means a new process, so the step 7 observer is gone; the
  # nonce comparison in step 10 turns that into inconclusive on its own.
  ensure_probe_running
  mutation_attempted=1
  probe_broadcast_ok UPDATE \
    --es role "$target_key" --es expected "$original_target" --es value "$target_value"
  update_count="$(probe_value count)"
  readback="$(probe_value readback)"
  notified="$(probe_value notified)"
  notify_ms="$(probe_value notify_ms)"
  record "update_count=$update_count"
  record "update_ms=$(probe_value update_ms)"
  record "app_readback=$readback"
  record "app_readback_rows=$(probe_value readback_rows)"
  record "notified=$notified"
  record "notify_ms=$notify_ms"
  if [[ "$notified" == "true" ]]; then
    notify_own="ok"
  else
    notify_own="fail"
  fi
  record "notify_own=$notify_own"

  [[ "$update_count" == "1" ]] || { write_via_app_uid="fail"; die "probe UPDATE matched count=$update_count, expected 1"; }
  [[ "$readback" == "$target_value" ]] || { write_via_app_uid="fail"; die "probe UPDATE app readback is $readback, expected $target_value"; }

  raw="$(read_role_strict "$target_key")" || { write_via_app_uid="fail"; die "shell readback of $target_key failed after the app-UID write"; }
  record "shell_readback=$raw"
  if [[ "$raw" != "$target_value" ]]; then
    write_via_app_uid="fail"
    record "write_via_app_uid=fail"
    die "shell readback is $raw, expected $target_value"
  fi
  write_via_app_uid="ok"
  record "write_via_app_uid=ok"

  # 10. Observer events after the probe's own write.
  ensure_probe_running
  probe_broadcast_ok REPORT
  events_after_own="$(probe_value events)"
  nonce_report="$(probe_value nonce)"
  record "events_after_own=$events_after_own"
  if [[ "$nonce_report" != "$nonce_observe" ]]; then
    observer_note="inconclusive_process_restarted"
    record "observer=inconclusive_process_restarted"
  fi

  # 11. Restore through the shell path, then look for the external notification.
  record ""
  record "=== restoring $target_key ==="
  write_role_shell "$target_key" "$original_target" || die "shell restore of $target_key failed"
  raw="$(read_role_strict "$target_key")" || die "shell readback after restore failed"
  record "restored_value=$raw"
  [[ "$raw" == "$original_target" ]] || die "restore readback is $raw, expected $original_target"

  ensure_probe_running
  probe_broadcast_ok REPORT
  events_after_external="$(probe_value events)"
  nonce_report="$(probe_value nonce)"
  record "events_after_external=$events_after_external"
  if [[ "$nonce_report" != "$nonce_observe" ]]; then
    observer_note="inconclusive_process_restarted"
    notify_external="inconclusive"
  elif ! is_integer "$events_after_own" || ! is_integer "$events_after_external"; then
    notify_external="inconclusive"
  elif (( events_after_external > events_after_own )); then
    notify_external="ok"
  elif (( events_after_external == events_after_own )); then
    notify_external="fail"
  else
    notify_external="inconclusive"
  fi
  record "notify_external=$notify_external"

  # 12. Final oracle equals the baseline.
  read_oracle_triplet || die "final PersonBean oracle read failed"
  record "final_DEFAULT_MAP_SWITCH=$oracle_map"
  record "final_MUSIC_SWITCH=$oracle_music"
  record "final_VIDEO_SWITCH=$oracle_video"
  if [[ "$oracle_map" != "$original_map" || "$oracle_music" != "$original_music" || "$oracle_video" != "$original_video" ]]; then
    die "final PersonBean oracle does not match the baseline"
  fi
  restore_verified="yes"
  restore_completed=1
  record "restore_verified=yes"

  # 13-15 run in the cleanup trap: crash check, uninstall, verdict block.
  if [[ "$read_via_app_uid" == "ok" && "$write_via_app_uid" == "ok" && "$restore_verified" == "yes" ]]; then
    run_exit=0
  fi
  exit "$run_exit"
}

# --- argument parsing ------------------------------------------------------

if (( $# == 0 )); then
  usage >&2
  exit 2
fi

subcommand="$1"
shift

case "$subcommand" in
  --help|-h|help)
    usage
    exit 0
    ;;
  build|install|uninstall|query|run) ;;
  *)
    usage >&2
    die "unknown subcommand: $subcommand"
    ;;
esac

query_role_key=""

while (( $# > 0 )); do
  case "$1" in
    --serial)
      [[ $# -ge 2 ]] || die "--serial requires a value"
      serial="$2"
      shift 2
      ;;
    --role)
      [[ $# -ge 2 ]] || die "--role requires a value"
      case "$subcommand" in
        query)
          query_role_key="$(role_key_of "$2" || true)"
          [[ -n "$query_role_key" ]] \
            || die "--role must be DEFAULT_MAP_SWITCH, MUSIC_SWITCH or VIDEO_SWITCH"
          ;;
        run)
          role_key="$(role_key_of "$2" || true)"
          [[ -n "$role_key" ]] || die "--role must be exactly navigation, music, or video"
          role_name="$(role_name_of "$2")"
          ;;
        *)
          die "--role is not valid for $subcommand"
          ;;
      esac
      shift 2
      ;;
    --value)
      [[ "$subcommand" == "run" ]] || die "--value is only valid for run"
      [[ $# -ge 2 ]] || die "--value requires a value"
      value_arg="$2"
      is_safe_package "$value_arg" || die "unsafe Android package: $value_arg"
      shift 2
      ;;
    --keep)
      [[ "$subcommand" == "run" ]] || die "--keep is only valid for run"
      keep_probe=1
      shift
      ;;
    --no-build)
      case "$subcommand" in
        run|install) no_build=1 ;;
        *) die "--no-build is not valid for $subcommand" ;;
      esac
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

if [[ "$subcommand" == "run" && "$role_key" == "DEFAULT_MAP_SWITCH" && -z "$value_arg" ]]; then
  die "--role navigation is refused without an explicit --value: DEFAULT_MAP_SWITCH is a live product binding"
fi
case "$subcommand" in
  build) cmd_build ;;
  install) cmd_install ;;
  uninstall) cmd_uninstall ;;
  query) cmd_query "$query_role_key" ;;
  run) cmd_run ;;
esac
