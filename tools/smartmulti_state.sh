#!/usr/bin/env bash
set -euo pipefail

action="${1:-snapshot}"
serial="${2:-${ANDROID_SERIAL:-127.0.0.1:15555}}"
adb_target=(adb -s "$serial")

snapshot() {
  "${adb_target[@]}" get-state >/dev/null
  echo "serial=$serial"
  echo "primary=$("${adb_target[@]}" shell settings get system byd_smart_multi_primary_activity | tr -d '\r')"
  echo "secondary=$("${adb_target[@]}" shell settings get system byd_smart_multi_second_activity | tr -d '\r')"
  echo "position=$("${adb_target[@]}" shell settings get system byd_smart_multi_primary_position | tr -d '\r')"
  echo "mode=$("${adb_target[@]}" shell settings get system byd_smart_multi_split_window_mode | tr -d '\r')"
  echo "force_resizable=$("${adb_target[@]}" shell settings get global force_resizable_activities | tr -d '\r')"
  echo "gate=$("${adb_target[@]}" shell service call activity_task 123 | tr -d '\r')"
  echo "area=$("${adb_target[@]}" shell service call activity_task 30 | tr -d '\r')"
  "${adb_target[@]}" shell service call activity_task 112 s16 dev.denza.apps
  "${adb_target[@]}" shell run-as dev.denza.apps cat shared_prefs/denza_split_screen.xml \
    2>/dev/null || true
  "${adb_target[@]}" shell am stack list
}

restore_resizeability_lease() {
  local preferences
  preferences="$(
    "${adb_target[@]}" shell run-as dev.denza.apps \
      cat shared_prefs/denza_split_screen.xml 2>/dev/null || true
  )"
  if [[ "$preferences" == *'name="force_resizable_original">MISSING<'* ]]; then
    "${adb_target[@]}" shell settings delete global force_resizable_activities >/dev/null
  elif [[ "$preferences" == *'name="force_resizable_original">ZERO<'* ]]; then
    "${adb_target[@]}" shell settings put global force_resizable_activities 0
  elif [[ "$preferences" == *'name="force_resizable_original">ONE<'* ]]; then
    "${adb_target[@]}" shell settings put global force_resizable_activities 1
  fi
}

reset_acceptance_state() {
  "${adb_target[@]}" get-state >/dev/null
  local platform
  platform="$("${adb_target[@]}" shell getprop ro.build.ads.platform | tr -d '\r')"
  if [[ "$platform" == "huawei" ]]; then
    echo "Refusing the non-Huawei baseline on ro.build.ads.platform=huawei" >&2
    exit 2
  fi

  restore_resizeability_lease
  "${adb_target[@]}" shell am force-stop dev.denza.apps
  "${adb_target[@]}" shell input keyevent KEYCODE_HOME

  local primary secondary position mode
  primary="$("${adb_target[@]}" shell settings get system byd_smart_multi_primary_activity | tr -d '\r')"
  secondary="$("${adb_target[@]}" shell settings get system byd_smart_multi_second_activity | tr -d '\r')"
  if [[ "$primary" == "dev.denza.apps" || "$secondary" == "dev.denza.apps" ]]; then
    # The package-change receiver is the firmware's exact supported path for replacing an
    # unavailable remembered member in both memory and Settings. The reset temporarily disables
    # the one product package and immediately rolls it back even if verification fails.
    local picker_disabled=1
    trap '
      if [[ "${picker_disabled:-0}" == "1" ]]; then
        "${adb_target[@]}" shell pm enable --user 0 dev.denza.apps >/dev/null || true
      fi
    ' EXIT
    "${adb_target[@]}" shell pm disable-user --user 0 dev.denza.apps >/dev/null
    sleep 0.8
    "${adb_target[@]}" shell pm enable --user 0 dev.denza.apps >/dev/null
    picker_disabled=0
    trap - EXIT
    sleep 0.5
  elif ! {
    [[ "$primary" == "com.byd.sr" && "$secondary" == "com.byd.launchermap" ]] ||
      [[ "$primary" == "com.android.launcher3" && "$secondary" == "com.byd.launchermap" ]]
  }; then
    echo "Refusing to overwrite a non-Denza remembered pair: $primary + $secondary" >&2
    exit 3
  fi

  # This branch initializes mIsEnterSplit=true in the exact services.jar. Raw Settings writes
  # are deliberately absent: they would leave stale in-memory package fields.
  "${adb_target[@]}" shell service call activity_task 126 i32 1 >/dev/null
  primary="$("${adb_target[@]}" shell settings get system byd_smart_multi_primary_activity | tr -d '\r')"
  secondary="$("${adb_target[@]}" shell settings get system byd_smart_multi_second_activity | tr -d '\r')"
  position="$("${adb_target[@]}" shell settings get system byd_smart_multi_primary_position | tr -d '\r')"
  mode="$("${adb_target[@]}" shell settings get system byd_smart_multi_split_window_mode | tr -d '\r')"
  if ! {
    [[ "$primary" == "com.byd.sr" && "$secondary" == "com.byd.launchermap" &&
       ( "$position" == "1" || "$position" == "2" ) && "$mode" == "100" ]] ||
      [[ "$primary" == "com.android.launcher3" && "$secondary" == "com.byd.launchermap" &&
         ( "$position" == "1" || "$position" == "2" ) && "$mode" == "102" ]]
  }; then
    echo "SmartMulti rejected the expected baseline: $primary $secondary $position $mode" >&2
    exit 3
  fi

  "${adb_target[@]}" shell input keyevent KEYCODE_HOME
  sleep 0.5
  "${adb_target[@]}" shell am force-stop dev.denza.apps
  "${adb_target[@]}" shell run-as dev.denza.apps \
    rm -f shared_prefs/denza_split_screen.xml

  echo "SmartMulti persistent pair and Denza split preferences reset."
  echo "Runtime tx125 additions remain until a controlled system_server/head-unit reboot."
  snapshot
}

case "$action" in
  snapshot)
    snapshot
    ;;
  reset)
    reset_acceptance_state
    ;;
  *)
    echo "Usage: $0 [snapshot|reset] [adb-serial]" >&2
    exit 64
    ;;
esac
