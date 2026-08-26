#!/usr/bin/env bash
# Wraps any speaker-lift action in a full before/after FID sweep and prints the
# delta. Now that a raise is reproducible on command (the MediaCenter LOCAL
# pulse), this is how the moment of motion gets captured instead of guessing
# which handful of FIDs to watch.
#
# Both sweeps are read-only (autoservice getInt, transact 5). Whatever the
# action does is entirely up to the command passed in.
#
# usage:
#   speaker_lift_ab.sh <label> <command...>
#
# examples:
#   speaker_lift_ab.sh usespeaker  tools/vehicle_speaker_pulse.sh run usespeaker
#   speaker_lift_ab.sh localpulse  tools/speaker_lift_local_pulse.sh run play
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
sweep="$script_dir/audio_fid_sweep.sh"

label="${1:?usage: speaker_lift_ab.sh <label> <command...>}"
shift
(( $# > 0 )) || { echo "no command given" >&2; exit 2; }

capture() {
  "$sweep" capture "$1" | sed -n 's/^captured -> //p' | tail -1
}

echo "== sweep before =="
before="$(capture "${label}-before")"
echo "$before"

echo "== action: $* =="
set +e
"$@"
action_status=$?
set -e
echo "== action exit: $action_status =="

echo "== sweep after =="
after="$(capture "${label}-after")"
echo "$after"

echo "== delta (device 1002) =="
"$sweep" diff "$before" "$after"

echo "== delta (other devices) =="
diff -u "$before/fids-other.txt" "$after/fids-other.txt" | sed -n '4,$p' || true

echo "== done: $label =="
