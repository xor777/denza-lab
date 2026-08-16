#!/usr/bin/env bash
set -euo pipefail

action="${1:-status}"
serial="${2:-${ANDROID_SERIAL:-127.0.0.1:15555}}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
apk="$repo_root/experiments/single-package-split-probe/build/outputs/apk/debug/single-package-split-probe.apk"
package="dev.denza.singlepackage.probe"
control_component="$package/$package.ProbeControlActivity"
entry_alias="$package/$package.SplitEntryAlias"
picker_component="$package/$package.ProbePickerActivity"
primary_category="byd.intent.category.START_IVI_PRIMARY"
secondary_category="byd.intent.category.START_IVI_SECOND"
adb_target=(adb -s "$serial")

require_device() {
  "${adb_target[@]}" get-state >/dev/null
}

shell_value() {
  "${adb_target[@]}" shell "$@" | tr -d '\r'
}

parcel_int() {
  local output hex
  output="$(shell_value "$@")"
  hex="$(printf '%s\n' "$output" \
    | sed -nE 's/.*Parcel\(00000000 ([0-9a-fA-F]{8}).*/\1/p' \
    | head -n 1)"
  if [[ -z "$hex" ]]; then
    echo "Cannot parse activity_task response: $output" >&2
    return 2
  fi
  printf '%d\n' "$((16#$hex))"
}

package_installed() {
  shell_value pm path "$package" | grep -q '^package:'
}

launcher_entries() {
  shell_value cmd package query-activities --brief \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    | grep "$package/" || true
}

latest_picker_task_id() {
  shell_value am stack list \
    | sed -nE "s/.*taskId=([0-9]+): $package\/$package\.ProbePickerActivity.*/\1/p" \
    | sort -n \
    | tail -n 1
}

task_root_id() {
  local task_id="$1"
  shell_value am stack list | awk -v task_id="$task_id" '
    $1 == "RootTask" {
      split($2, value, "=")
      root_id = value[2]
    }
    index($0, "taskId=" task_id ":") {
      print root_id
      exit
    }
  '
}

root_bounds() {
  local root_id="$1"
  shell_value am stack list \
    | sed -nE "s/^RootTask id=$root_id bounds=\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\].*/\1 \2 \3 \4/p" \
    | head -n 1
}

task_bounds() {
  local task_id="$1"
  shell_value am stack list \
    | sed -nE "s/.*taskId=$task_id: .* bounds=\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\].*/\1 \2 \3 \4/p" \
    | head -n 1
}

latest_component_task_id() {
  local component_pattern="$1"
  shell_value am stack list \
    | sed -nE "s|.*taskId=([0-9]+): $component_pattern .*|\1|p" \
    | sort -n \
    | tail -n 1
}

component_task_ids() {
  local component_pattern="$1"
  shell_value am stack list \
    | sed -nE "s|.*taskId=([0-9]+): $component_pattern .*|\1|p" \
    | sort -n \
    | uniq
}

bootstrap_default_roots() {
  local primary_root="$1" secondary_root="$2"
  local primary_task secondary_task primary_bounds secondary_bounds
  primary_task="$(latest_component_task_id 'com\.byd\.sr/com\.byd\.sr\.MainActivity')"
  secondary_task="$(latest_component_task_id 'com\.byd\.launchermap/com\.byd\.automap\.activity\.EmptyJumpActivity')"
  [[ -n "$primary_task" && -n "$secondary_task" ]] || {
    echo "Cannot find the exact firmware baseline tasks" >&2
    return 2
  }
  primary_bounds="$(task_bounds "$primary_task")"
  secondary_bounds="$(task_bounds "$secondary_task")"
  [[ -n "$primary_bounds" && -n "$secondary_bounds" ]] || {
    echo "Cannot read firmware baseline task bounds" >&2
    return 2
  }
  "${adb_target[@]}" shell am stack move-task "$primary_task" "$primary_root" true >/dev/null
  "${adb_target[@]}" shell am stack move-task "$secondary_task" "$secondary_root" true >/dev/null
  # shellcheck disable=SC2086 -- each value is four parsed integer coordinates.
  "${adb_target[@]}" shell am task resize "$primary_task" $primary_bounds >/dev/null
  # shellcheck disable=SC2086 -- each value is four parsed integer coordinates.
  "${adb_target[@]}" shell am task resize "$secondary_task" $secondary_bounds >/dev/null
  "${adb_target[@]}" shell am task focus "$primary_task" >/dev/null
  sleep 0.8
  if [[ "$(parcel_int service call activity_task 30)" != "3" ]]; then
    "${adb_target[@]}" shell service call activity_task 115 >/dev/null
    sleep 1
    "${adb_target[@]}" shell am task focus "$primary_task" >/dev/null
    sleep 0.8
  fi
  [[ "$(parcel_int service call activity_task 30)" == "3" ]] || {
    echo "Firmware baseline did not recreate balanced split" >&2
    return 2
  }
  printf '%s %s\n' "$primary_task" "$secondary_task"
}

place_task_in_root() {
  local task_id="$1" root_id="$2" bounds
  [[ -n "$task_id" && -n "$root_id" ]] || return 2
  if [[ "$(task_root_id "$task_id")" != "$root_id" ]]; then
    "${adb_target[@]}" shell am stack move-task "$task_id" "$root_id" true >/dev/null
  fi
  bounds="$(root_bounds "$root_id")"
  [[ -n "$bounds" ]] || { echo "Cannot read bounds for root $root_id" >&2; return 2; }
  # shellcheck disable=SC2086 -- four validated integer coordinates are required by `am task`.
  "${adb_target[@]}" shell am task resize "$task_id" $bounds >/dev/null
}

snapshot() {
  require_device
  echo "serial=$serial"
  echo "installed=$(package_installed && echo yes || echo no)"
  echo "primary=$(shell_value settings get system byd_smart_multi_primary_activity)"
  echo "secondary=$(shell_value settings get system byd_smart_multi_second_activity)"
  echo "position=$(shell_value settings get system byd_smart_multi_primary_position)"
  echo "mode=$(shell_value settings get system byd_smart_multi_split_window_mode)"
  echo "gate=$(parcel_int service call activity_task 123)"
  echo "area=$(parcel_int service call activity_task 30)"
  if package_installed; then
    echo "supported=$(parcel_int service call activity_task 112 s16 "$package")"
    echo "info_resolver=$(shell_value cmd package resolve-activity --brief \
      -a android.intent.action.MAIN \
      -c android.intent.category.INFO \
      "$package" | tail -n 1)"
    echo "launcher_entries:"
    launcher_entries
  fi
  echo "probe_tasks:"
  shell_value am stack list | grep -E "RootTask|taskId=|$package" || true
}

set_icon() {
  local enabled="$1"
  require_device
  package_installed || { echo "Probe package is not installed" >&2; exit 3; }
  "${adb_target[@]}" shell am start -W \
    -n "$control_component" \
    --ez dev.denza.singlepackage.probe.extra.ICON_ENABLED "$enabled" >/dev/null
  sleep 0.5
  echo "launcher_entries after icon=$enabled:"
  launcher_entries
}

require_clean_pair() {
  local primary secondary
  primary="$(shell_value settings get system byd_smart_multi_primary_activity)"
  secondary="$(shell_value settings get system byd_smart_multi_second_activity)"
  if {
    [[ "$primary" == "com.byd.sr" && "$secondary" == "com.byd.launchermap" ]] ||
      [[ "$primary" == "com.android.launcher3" && "$secondary" == "com.byd.launchermap" ]]
  }; then
    return
  fi
  echo "Refusing live probe outside the documented baseline: $primary + $secondary" >&2
  exit 4
}

open_split() {
  require_device
  package_installed || { echo "Probe package is not installed" >&2; exit 3; }
  require_clean_pair

  local supported gate primary_root secondary_root area stack picker_count
  local primary_task secondary_task primary_task_root secondary_task_root
  local bootstrap_primary_task="" bootstrap_secondary_task="" stock_picker_tasks=""
  supported="$(parcel_int service call activity_task 112 s16 "$package")"
  if [[ "$supported" != "1" ]]; then
    echo "Manifest marker did not make the single package split-capable; refusing tx125" >&2
    exit 5
  fi
  gate="$(parcel_int service call activity_task 123)"
  if [[ "$gate" != "1" ]]; then
    echo "Refusing to change a closed split gate in this package-identity probe" >&2
    exit 6
  fi

  primary_root="$(parcel_int service call activity_task 118 i32 1)"
  secondary_root="$(parcel_int service call activity_task 118 i32 2)"
  if (( primary_root <= 0 || secondary_root <= 0 || primary_root == secondary_root )); then
    echo "Invalid native roots: primary=$primary_root secondary=$secondary_root" >&2
    exit 7
  fi

  area="$(parcel_int service call activity_task 30)"
  if [[ "$area" != "3" ]]; then
    read -r bootstrap_primary_task bootstrap_secondary_task \
      < <(bootstrap_default_roots "$primary_root" "$secondary_root")
  fi

  "${adb_target[@]}" logcat -c
  "${adb_target[@]}" shell am start \
    -a android.intent.action.MAIN \
    -c "$primary_category" \
    -n "$picker_component" \
    -f 0x18010000 >/dev/null
  sleep 0.4
  primary_task="$(latest_picker_task_id)"
  [[ -n "$primary_task" ]] || { echo "Primary picker task was not created" >&2; exit 8; }
  "${adb_target[@]}" shell am start \
    -a android.intent.action.MAIN \
    -c "$secondary_category" \
    -n "$picker_component" \
    -f 0x18010000 >/dev/null
  sleep 0.8
  secondary_task="$(latest_picker_task_id)"
  if [[ -z "$secondary_task" || "$secondary_task" == "$primary_task" ]]; then
    echo "Secondary picker task was not created independently" >&2
    exit 8
  fi

  area="$(parcel_int service call activity_task 30)"
  primary_task_root="$(task_root_id "$primary_task")"
  secondary_task_root="$(task_root_id "$secondary_task")"
  if [[ "$area" != "3" || "$primary_task_root" != "$primary_root" ||
    "$secondary_task_root" != "$secondary_root" ]]; then
    place_task_in_root "$primary_task" "$primary_root"
    place_task_in_root "$secondary_task" "$secondary_root"
    "${adb_target[@]}" shell am task focus "$primary_task" >/dev/null
    sleep 0.8
  fi

  area="$(parcel_int service call activity_task 30)"
  stack="$(shell_value am stack list)"
  picker_count="$(printf '%s\n' "$stack" | grep -c "$package.*ProbePickerActivity" || true)"
  echo "primary_root=$primary_root"
  echo "secondary_root=$secondary_root"
  echo "primary_task=$primary_task"
  echo "secondary_task=$secondary_task"
  echo "area=$area"
  echo "picker_component_lines=$picker_count"
  printf '%s\n' "$stack" | grep -E "RootTask|taskId=|$package" || true
  echo "probe_log:"
  "${adb_target[@]}" logcat -d -v time -s SinglePackageProbe:I '*:S'

  primary_task_root="$(task_root_id "$primary_task")"
  secondary_task_root="$(task_root_id "$secondary_task")"
  if [[ "$area" != "3" || "$picker_count" -lt 2 ||
    "$primary_task_root" != "$primary_root" ||
    "$secondary_task_root" != "$secondary_root" ]]; then
    echo "Single-package picker pair did not settle in native split" >&2
    exit 8
  fi


  stock_picker_tasks="$(
    component_task_ids \
      'com\.android\.launcher3/com\.android\.launcher3\.SplitScreenListActivity'
  )"
  for task_id in "$bootstrap_primary_task" "$bootstrap_secondary_task" $stock_picker_tasks; do
    [[ -n "$task_id" ]] || continue
    if [[ "$(task_root_id "$task_id")" == "$primary_root" ||
      "$(task_root_id "$task_id")" == "$secondary_root" ]]; then
      "${adb_target[@]}" shell am stack move-task "$task_id" 4 false >/dev/null
    fi
  done
  "${adb_target[@]}" shell am task focus "$primary_task" >/dev/null
  sleep 0.8
  [[ "$(parcel_int service call activity_task 30)" == "3" ]] || {
    echo "Removing firmware bootstrap collapsed the probe pair" >&2
    exit 8
  }
}

restore_pair() {
  require_device
  local primary secondary
  primary="$(shell_value settings get system byd_smart_multi_primary_activity)"
  secondary="$(shell_value settings get system byd_smart_multi_second_activity)"
  if [[ "$primary" != "$package" || "$secondary" != "$package" ]]; then
    echo "SmartMulti has not persisted the exact probe pair: $primary + $secondary" >&2
    exit 9
  fi
  "${adb_target[@]}" shell input keyevent KEYCODE_HOME
  sleep 0.5
  "${adb_target[@]}" shell service call activity_task 115 >/dev/null
  sleep 1
  snapshot
}

reset_probe() {
  require_device
  if ! package_installed; then
    echo "Probe package is not installed; nothing to reset."
    return
  fi

  set_icon false >/dev/null
  "${adb_target[@]}" shell am force-stop "$package"
  "${adb_target[@]}" shell input keyevent KEYCODE_HOME
  sleep 0.5

  local primary secondary disabled
  primary="$(shell_value settings get system byd_smart_multi_primary_activity)"
  secondary="$(shell_value settings get system byd_smart_multi_second_activity)"
  if [[ "$primary" == "$package" || "$secondary" == "$package" ]]; then
    disabled=1
    trap '
      if [[ "${disabled:-0}" == "1" ]]; then
        "${adb_target[@]}" shell pm enable --user 0 "$package" >/dev/null || true
      fi
    ' EXIT
    "${adb_target[@]}" shell pm disable-user --user 0 "$package" >/dev/null
    sleep 0.8
    "${adb_target[@]}" shell pm enable --user 0 "$package" >/dev/null
    disabled=0
    trap - EXIT
    sleep 0.5
  elif ! {
    [[ "$primary" == "com.byd.sr" && "$secondary" == "com.byd.launchermap" ]] ||
      [[ "$primary" == "com.android.launcher3" && "$secondary" == "com.byd.launchermap" ]]
  }; then
    echo "Refusing to overwrite an unrelated remembered pair: $primary + $secondary" >&2
    exit 10
  fi

  "${adb_target[@]}" shell service call activity_task 126 i32 1 >/dev/null
  require_clean_pair
  echo "Probe state reset to the documented SmartMulti baseline."
  snapshot
}

case "$action" in
  install)
    require_device
    [[ -f "$apk" ]] || { echo "Build the probe first: $apk" >&2; exit 2; }
    "${adb_target[@]}" install -r "$apk"
    set_icon false
    snapshot
    ;;
  status|snapshot)
    snapshot
    ;;
  icon-on)
    set_icon true
    ;;
  icon-off)
    set_icon false
    ;;
  entry)
    require_device
    "${adb_target[@]}" shell am start -W -n "$entry_alias"
    ;;
  split)
    open_split
    ;;
  restore)
    restore_pair
    ;;
  reset)
    reset_probe
    ;;
  uninstall)
    reset_probe
    "${adb_target[@]}" uninstall "$package"
    ;;
  *)
    echo "Usage: $0 {install|status|icon-on|icon-off|entry|split|restore|reset|uninstall} [adb-serial]" >&2
    exit 64
    ;;
esac
