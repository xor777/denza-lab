#!/usr/bin/env bash
# Host driver for experiments/hud-frames-probe: moving test frames into the HUD.
#
# Three channels, one question each (see docs/hud-projection-findings.md):
#   icon   the maneuver-picture slot of the road packet (event 0x8001, field 8). Proven to show
#          a still picture while driving; does it follow a changing one, and how fast?
#   map    the map window (event 0x8003). This HUD reports no map feature; does it show
#          anything at all?
#   video  DiShare video: Denza Apps' debug receiver casts the probe's pattern Activity to
#          screen_hud. Moving video on the HUD, parked.
#
# Every subcommand is one explicit step; nothing runs on its own. The probe never touches AVC,
# never reinstalls Denza Apps and never clears a logcat buffer.
#
#   tools/hud_frames_probe.sh flags                    # read-only HUD, gear and speed signals
#   tools/hud_frames_probe.sh install                  # build if needed, install the probe only
#   tools/hud_frames_probe.sh icon [fps-list] [secs]   # default 1,2,5,10 for 10 s each
#   tools/hud_frames_probe.sh map  [fps-list] [secs] [w] [h] [marker]
#                                  # default 1,2,5 for 10 s, 300x180; marker=1 puts a still
#                                  # square-and-cross in the maneuver slot beside the map
#   tools/hud_frames_probe.sh grid [secs] [w] [h]    # still calibration grid in the map window,
#                                                    # default 15 s at 600x360 with the marker
#   tools/hud_frames_probe.sh yandex [fps] [secs] [cx] [cy] [cw] [invert] [road]
#                                  # move the running Yandex Navigator onto the probe's display,
#                                  # send a 300x180 crop of it to the map window for secs
#                                  # (default 5 fps, 60 s, crop centre 0.5/0.55, width 0.6, no
#                                  # invert), then move it back and pull the saved frames;
#                                  # road=1 also sends a "navigating" road packet (no route)
#   tools/hud_frames_probe.sh yandex-return          # recovery: move Yandex back, stop the probe
#   tools/hud_frames_probe.sh stop                     # cancel a running icon/map plan
#   tools/hud_frames_probe.sh video-start              # cast the pattern to screen_hud
#   tools/hud_frames_probe.sh video-stop
#   tools/hud_frames_probe.sh uninstall
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
probe_package="dev.denza.hudframes.probe"
apk="$repo_root/experiments/hud-frames-probe/build/outputs/apk/debug/hud-frames-probe.apk"
out_dir="$repo_root/captures/hud-live-20260923"
tag="DenzaHudFramesProbe"

adb_() { adb -s "$serial" "$@"; }
stamp() { date +%Y%m%d-%H%M%S; }

# Read-only autoservice getInt/getFloat (transacts 5 and 7 only).
read_int() { adb_ shell "service call autoservice 5 i32 $1 i32 $2" | sed -n 's/.*Parcel(00000000 \([0-9a-f]\{8\}\).*/\1/p'; }
read_float() { adb_ shell "service call autoservice 7 i32 $1 i32 $2" | sed -n 's/.*Parcel(00000000 \([0-9a-f]\{8\}\).*/\1/p'; }

flags() {
    local speed
    speed="$(read_float 1013 -1807745016)"
    printf '%s gear=0x%s speed_bits=0x%s(%s) hud_type=0x%s hud_switch=0x%s video_present=0x%s video_available=0x%s map_config=0x%s\n' \
        "$(date +%H:%M:%S)" \
        "$(read_int 1011 555745336)" \
        "$speed" "$(python3 -c "import struct;print(round(struct.unpack('>f',bytes.fromhex('$speed'))[0],1))")" \
        "$(read_int 1023 951058453)" \
        "$(read_int 1023 951058460)" \
        "$(read_int 1023 951058490)" \
        "$(read_int 1023 951058486)" \
        "$(read_int 1007 951058480)"
}

crash_mark() { adb_ shell 'logcat -d -b crash -v time | tail -1'; }

plan() {
    local channel="$1" rates="$2" seconds="$3" map_w="${4:-300}" map_h="${5:-180}"
    local marker="false"
    [ "${6:-0}" = "1" ] && marker="true"
    local steps file pid waited=0 limit
    steps="$(awk -F, '{print NF}' <<<"$rates")"
    limit=$((steps * seconds + 20))
    file="$out_dir/frames-$channel-$(stamp).log"
    mkdir -p "$out_dir"
    echo "crash before: $(crash_mark)"
    flags
    # Follow only the probe's own tag, from now on; the buffer is never cleared.
    adb_ logcat -v time -T 1 -s "$tag" >"$file" 2>&1 &
    pid=$!
    adb_ shell am start -n "$probe_package/.SomeIpFramesActivity" \
        --es channel "$channel" --es fps "$rates" --ei seconds "$seconds" \
        --ei map_w "$map_w" --ei map_h "$map_h" --ez marker "$marker" >/dev/null
    until grep -q "offer withdrawn" "$file" || [ "$waited" -ge "$limit" ]; do
        sleep 1
        waited=$((waited + 1))
    done
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    grep -E "start ret|fps: sent|ret=|withdrawn|failed" "$file" || true
    [ "$waited" -ge "$limit" ] && echo "no end marker after ${limit}s; see $file"
    echo "log: $file"
    flags
    echo "crash after:  $(crash_mark)"
}

# --- yandex channel ---------------------------------------------------------------------------
# Task moves use the shell half of Denza Apps' own cluster projection (ClusterProxyMain, run from
# the installed Denza Apps APK exactly as the product runs it), so the navigator leaves and
# re-enters its native root - split roots included - by the product's proven path.
yandex_package="${YANDEX_PACKAGE:-ru.yandex.yandexnavi}"
state_file="$out_dir/yandex-state"
vd_w=960
vd_h=576

proxy() {
    local apk
    apk="$(adb_ shell pm path dev.denza.apps | sed -n 's/^package://p' | head -1 | tr -d '\r')"
    [ -n "$apk" ] || { echo "Denza Apps not installed" >&2; return 1; }
    adb_ shell "CLASSPATH='$apk' app_process /system/bin --nice-name=denza_nav_cmd \
        dev.denza.apps.feature.navigation.ClusterProxyMain $*" \
        | sed -n 's/.*DENZA_RESULT://p' | tail -1 | tr -d '\r'
}

yandex_return() {
    [ -f "$state_file" ] || { echo "no saved Yandex state"; return 0; }
    local task origin src comp comp_root display
    read -r task origin <"$state_file"
    IFS=, read -r src comp comp_root <<<"$origin"
    display="$(proxy task-display "$yandex_package" "$task" || true)"
    echo "return: task=$task display=$display origin=$origin"
    if [ -n "$display" ] && [ "$display" != "0" ]; then
        echo "return-task -> $(proxy return-task "$yandex_package" "$task" "$src" "$comp" "$comp_root" || true)"
    fi
    adb_ shell am start -n "$probe_package/.SomeIpFramesActivity" --es channel stop >/dev/null || true
    sleep 2
    # The stop intent brings the probe's preview to the front of the main screen; close it.
    adb_ shell am force-stop "$probe_package" || true
    rm -f "$state_file"
    local pulled_at
    pulled_at="$(stamp)"
    for name in yandex-full.png yandex-hud.png; do
        adb_ pull "/sdcard/Android/data/$probe_package/files/$name" \
            "$out_dir/$pulled_at-$name" >/dev/null 2>&1 && echo "pulled $out_dir/$pulled_at-$name"
    done
    echo "after: display=$(proxy task-display "$yandex_package" "$task" || true)"
    echo "crash after:  $(crash_mark)"
}

yandex_run() {
    local fps="${1:-5}" secs="${2:-60}" cx="${3:-0.5}" cy="${4:-0.55}" cw="${5:-0.6}" invert="false"
    [ "${6:-0}" = "1" ] && invert="true"
    local road="false"
    [ "${7:-0}" = "1" ] && road="true"
    local task display origin src log pid display_id root projected waited=0
    mkdir -p "$out_dir"
    [ -f "$state_file" ] && { echo "a previous run left $state_file; run yandex-return first"; return 1; }
    echo "crash before: $(crash_mark)"
    flags
    task="$(proxy find-task "$yandex_package")"
    [ -n "$task" ] && [ "$task" -gt 0 ] 2>/dev/null || { echo "Yandex Navigator has no task; open it first"; return 1; }
    display="$(proxy task-display "$yandex_package" "$task")"
    [ "$display" = "0" ] || { echo "Yandex task $task is on display $display, not the main screen; turn off its cluster projection first"; return 1; }
    origin="$(proxy projection-origin "$yandex_package" "$task")"
    echo "yandex task=$task origin(sourceRoot,companionTask,companionRoot)=$origin"
    echo "$task $origin" >"$state_file"
    trap 'echo; echo "interrupted: returning Yandex"; yandex_return; exit 130' INT TERM

    log="$out_dir/frames-yandex-$(stamp).log"
    adb_ logcat -v time -T 1 -s "$tag" >"$log" 2>&1 &
    pid=$!
    adb_ shell am start -n "$probe_package/.SomeIpFramesActivity" --es channel yandex \
        --es fps "$fps" --ei seconds $((secs + 30)) --ei vd_w "$vd_w" --ei vd_h "$vd_h" \
        --ef cx "$cx" --ef cy "$cy" --ef cw "$cw" --ez invert "$invert" --ez road "$road" >/dev/null
    until display_id="$(sed -n 's/.*yandex display id=\([0-9]*\).*/\1/p' "$log" | tail -1)"; [ -n "$display_id" ] || [ "$waited" -ge 10 ]; do
        sleep 1
        waited=$((waited + 1))
    done
    [ -n "$display_id" ] || { echo "probe did not report its display"; kill "$pid"; yandex_return; return 1; }
    IFS=, read -r src _ _ <<<"$origin"
    root=0
    if [ "$src" != "$task" ]; then
        root="$(proxy create-root "$display_id")"
        echo "created projection root=$root on display $display_id"
    fi
    projected="$(proxy project-task "$yandex_package" "$task" "$root" "$display_id" "$vd_w" "$vd_h")"
    echo "project-task -> $projected (display $display_id)"
    if [ "$projected" = "true" ]; then
        echo "running ${secs}s; Ctrl-C returns Yandex early"
        sleep "$secs"
    fi
    yandex_return
    trap - INT TERM
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    grep -E "yandex (display|[0-9]+fps)|start ret|withdrawn|failed|blank" "$log" | awk '!seen[$0]++' || true
    echo "log: $log"
}

case "${1:-}" in
    flags) flags ;;
    install)
        if [ ! -f "$apk" ]; then
            (cd "$repo_root" && ./gradlew -Pexperiments :hud-frames-probe:assembleDebug -q)
        fi
        adb_ install -r "$apk"  # the probe package only
        ;;
    icon) plan icon "${2:-1,2,5,10}" "${3:-10}" ;;
    grid) plan grid 2 "${2:-15}" "${3:-600}" "${4:-360}" 1 ;;
    map) plan map "${2:-1,2,5}" "${3:-10}" "${4:-300}" "${5:-180}" "${6:-0}" ;;
    stop) adb_ shell am start -n "$probe_package/.SomeIpFramesActivity" --es channel stop >/dev/null ;;
    video-start)
        echo "crash before: $(crash_mark)"
        flags
        adb_ shell am broadcast -a dev.denza.apps.START_SIMULCAST_TARGET -p dev.denza.apps \
            --es targetPackage "$probe_package" --es receiver screen_hud
        ;;
    video-stop)
        adb_ shell am broadcast -a dev.denza.apps.STOP_SIMULCAST_TARGET -p dev.denza.apps
        # DiShare hands the cast task back to the main display (seen 2026-09-23: into the
        # byd-freeform root), where the pattern keeps running; the probe is closed explicitly.
        sleep 2
        adb_ shell am force-stop "$probe_package"
        flags
        echo "crash after:  $(crash_mark)"
        ;;
    yandex) shift; yandex_run "$@" ;;
    yandex-return) yandex_return ;;
    uninstall) adb_ uninstall "$probe_package" ;;
    *) sed -n '2,36p' "$0"; exit 2 ;;
esac
