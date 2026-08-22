#!/usr/bin/env bash
# Read-only sweep of every audio FID this firmware exposes on BYDAUTO_DEVICE_AUDIO
# (1002), plus the few known off-device FIDs that matter for media state.
#
# Why: the speaker-lift investigation kept comparing a hand-picked half-dozen
# FIDs between "covers out" and "covers in". This reads the whole device-1002
# feature set in one pass (~8 s) so a paired capture can be diffed exhaustively
# instead of guessing which FID to look at.
#
# The FID table in tools/audio-fids-dev1002.txt was generated from this car's
# own /system/framework/framework.jar (BYDAutoDeviceFeaturesMap.AudioMap joined
# with the CanFD branch of BYDAutoFeatureIds$Audio), so it is exactly what the
# native service will accept — no guessed ids.
#
# Only autoservice transact 5 (getInt) is ever issued. No SET, no other
# transact code. See docs/speaker-lift-findings.md hazard log.
#
# usage:
#   audio_fid_sweep.sh capture <label>       # snapshot -> captures/speaker-lift/
#   audio_fid_sweep.sh diff <dirA> <dirB>    # show FIDs that differ
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
fid_table="$script_dir/audio-fids-dev1002.txt"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
device_dir="/data/local/tmp/audiofid"

adb_cmd=(adb -s "$serial")

# Extra FIDs that live on other device families but drive media state.
# dev fid name
extra_fids=(
  "1007 871366704  INSTRUMENT_MUSIC_SOURCE_SET"
  "1023 1122304064 SET_MUSIC_MODE_STATE"
  "1001 973078528  BODYWORK_ONLINE_HAS_0X03A000"
)

push_probe() {
  "${adb_cmd[@]}" shell "mkdir -p $device_dir"
  "${adb_cmd[@]}" push "$fid_table" "$device_dir/fids.txt" >/dev/null
  "${adb_cmd[@]}" shell "cat > $device_dir/sweep.sh" <<'EOF'
#!/system/bin/sh
while read name fid; do
  raw=$(service call autoservice 5 i32 1002 i32 "$fid" 2>/dev/null)
  v=$(echo "$raw" | sed -n 's/.*Parcel(\([0-9a-f]*\) \([0-9a-f]*\).*/\2/p')
  echo "$name $fid $v"
done < /data/local/tmp/audiofid/fids.txt
EOF
}

capture() {
  local label="${1:?usage: capture <label>}"
  local stamp out_dir
  stamp="$(date +%Y%m%d-%H%M%S)"
  out_dir="$repo_root/captures/speaker-lift/${stamp}-${label}"
  mkdir -p "$out_dir"

  push_probe
  "${adb_cmd[@]}" shell "sh $device_dir/sweep.sh" >"$out_dir/fids-1002.txt" 2>&1

  {
    for spec in "${extra_fids[@]}"; do
      # shellcheck disable=SC2086
      set -- $spec
      printf '%s %s %s ' "$3" "$1" "$2"
      "${adb_cmd[@]}" shell "service call autoservice 5 i32 $1 i32 $2" \
        | sed -n 's/.*Parcel(\([0-9a-f]*\) \([0-9a-f]*\).*/\2/p'
    done
  } >"$out_dir/fids-other.txt"

  "${adb_cmd[@]}" shell dumpsys audio >"$out_dir/dumpsys-audio.txt" 2>&1 || true
  "${adb_cmd[@]}" shell dumpsys media_session >"$out_dir/dumpsys-media-session.txt" 2>&1 || true
  "${adb_cmd[@]}" shell "dumpsys media.audio_flinger" \
    >"$out_dir/dumpsys-audioflinger.txt" 2>&1 || true

  echo "captured -> $out_dir"
}

diff_dirs() {
  local a="${1:?usage: diff <dirA> <dirB>}" b="${2:?usage: diff <dirA> <dirB>}"
  echo "--- $(basename "$a")  ->  $(basename "$b")"
  join -j 1 -o 0,1.3,2.3 \
    <(awk '{print $1" "$2" "$3}' "$a/fids-1002.txt" | sort) \
    <(awk '{print $1" "$2" "$3}' "$b/fids-1002.txt" | sort) \
    | awk '$2 != $3 { printf "%-52s %s -> %s\n", $1, $2, $3 }'
}

case "${1:-}" in
  capture) shift; capture "$@" ;;
  diff) shift; diff_dirs "$@" ;;
  *) sed -n '1,22p' "$0"; exit 2 ;;
esac
