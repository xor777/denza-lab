#!/usr/bin/env bash
# Probe whether the stock map role can be handed to a third-party navigator.
#
# Two independent switches select "the map app", each read by a different
# consumer:
#
#   DEFAULT_MAP_SWITCH   PersonBean row in com.byd.autovoice, reached through the
#                        exported content://com.byd.autovoice provider. Read by
#                        the voice assistant and by the Shortcuts "Navi" commands
#                        (MapUtils.getCurDefaultSelectMap).
#   byd_map_package      Settings.Global. Read by CustomKey for the steering-wheel
#                        navigation key.
#
# The probe points both at a target package, waits for a manual trigger, and
# reports the component the system actually started. Both originals are restored
# on exit, including on error or Ctrl-C.
#
# usage: navi_role_probe.sh [TARGET_PACKAGE]
set -euo pipefail

target_package="${1:-ru.yandex.yandexnavi}"

provider_uri="content://com.byd.autovoice/PersonBean"
map_switch_key="DEFAULT_MAP_SWITCH"
global_key="byd_map_package"

adb_bin="$(command -v adb)"
adb_cmd=("$adb_bin")
if [[ -n "${ADB_SERIAL:-}" ]]; then
  adb_cmd+=("-s" "$ADB_SERIAL")
fi

adb_shell() { "${adb_cmd[@]}" shell "$@"; }

read_map_switch() {
  adb_shell "content query --uri $provider_uri --where \"SETTING='$map_switch_key'\"" 2>/dev/null \
    | sed -n 's/.*VALUE=\([^,]*\).*/\1/p' | tr -d '\r' | head -1
}

write_map_switch() {
  adb_shell "content update --uri $provider_uri --bind VALUE:s:$1 --where \"SETTING='$map_switch_key'\"" 2>&1
}

if ! adb_shell "pm path $target_package" >/dev/null 2>&1; then
  echo "target package is not installed: $target_package" >&2
  exit 1
fi

original_switch="$(read_map_switch)"
original_global="$(adb_shell "settings get global $global_key" | tr -d '\r')"

if [[ -z "$original_switch" ]]; then
  echo "could not read $map_switch_key; refusing to run without a restore point" >&2
  exit 1
fi

echo "current  $map_switch_key = $original_switch"
echo "current  $global_key = $original_global"

restore() {
  echo
  echo "--- restoring ---"
  write_map_switch "$original_switch" >/dev/null || true
  local back_switch back_global
  back_switch="$(read_map_switch)"
  echo "restored $map_switch_key = $back_switch"
  if [[ "$back_switch" != "$original_switch" ]]; then
    echo "RESTORE FAILED - run by hand:" >&2
    echo "  adb shell content update --uri $provider_uri --bind VALUE:s:$original_switch --where \"SETTING='$map_switch_key'\"" >&2
  fi
  if [[ "$original_global" != "null" && -n "$original_global" ]]; then
    adb_shell "settings put global $global_key $original_global" >/dev/null 2>&1 || true
    back_global="$(adb_shell "settings get global $global_key" | tr -d '\r')"
    echo "restored $global_key = $back_global"
  fi
}
trap restore EXIT

echo
echo "--- applying $target_package ---"
write_map_switch "$target_package"
applied_switch="$(read_map_switch)"
echo "applied  $map_switch_key = $applied_switch"
if [[ "$applied_switch" != "$target_package" ]]; then
  echo "provider write did not take effect - shell may not be allowed to update it" >&2
fi

adb_shell "settings put global $global_key $target_package" >/dev/null 2>&1 || true
applied_global="$(adb_shell "settings get global $global_key" | tr -d '\r')"
echo "applied  $global_key = $applied_global"

"${adb_cmd[@]}" logcat -c || true

cat <<EOF

Trigger a navigation request on the head unit now, either of:
  - say the wake word and ask for navigation     (exercises $map_switch_key)
  - Shortcuts -> rule with "Navi -> Access map" -> Test Run   (exercises $map_switch_key)

DO NOT inject key code 321 to trigger this. That code is the configurable
steering-wheel custom key; on this car it is configured to action 1, which opens
com.byd.avc and starts an APA (auto parking assist) scan. Observed 2026-08-16.
Use the wheel key only if the car's own key configuration maps it to navigation.

Press Enter once it has run.
EOF
read -r _

log_file="${NAVI_PROBE_LOG:-/tmp/navi_role_probe.log}"
{
  echo "target      = $target_package"
  echo "applied     $map_switch_key = $applied_switch"
  echo "applied     $global_key = $applied_global"
  echo
  echo "=== started component ==="
  "${adb_cmd[@]}" logcat -d 2>/dev/null \
    | grep -E "ActivityTaskManager: START u0|ActivityNotFound|curMapPackage|getCurDefaultSelectMap|CUSTOM_NAVI|MapToFunction|NaviManager" \
    | tail -40
  echo
  echo "=== foreground ==="
  adb_shell "dumpsys activity activities" 2>/dev/null | grep -E "topResumedActivity" | head -3
} 2>&1 | tee "$log_file"

echo
echo "transcript written to $log_file"

cat <<'EOF'

Read the START line:
  cmp=<target>/<the target's own activity>  -> the map role accepts a third-party
      navigator; note whether a destination was carried or only the app opened
  no START for the target                   -> the request was filtered before launch
EOF
