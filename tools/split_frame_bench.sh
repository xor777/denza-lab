#!/bin/sh
# What one shell command costs the split feature on the car, and how much of that is the wrapper.
#
# Read-only. Sends nothing but reads and runs them on the device, so it is safe against a live
# scene. Every command of the product travels inside a frame that adds markers around it; this
# measures the frame itself against the bare command, for the three commands the recipes send
# most (`am stack list`, an `activity_task` transaction, a `settings get`).
#
#   OLD  - the frame up to v30: base64 in a command substitution plus two /system/bin/printf.
#   NEW  - the frame from wave 15: single quoting plus mksh's `print` builtin, chosen by a probe
#          that writes nothing (see LocalAdbClient.frameInteractiveCommand).
#
# Usage: tools/split_frame_bench.sh [serial]
set -e
SERIAL=${1:-127.0.0.1:5555}
adb -s "$SERIAL" shell <<'DEVICE'
N=41
now() { date +%s%N; }
run() { label=$1; shift
  j=0; while [ $j -lt 5 ]; do "$@" >/dev/null 2>&1; j=$((j+1)); done
  t0=$(now); i=0; while [ $i -lt $N ]; do "$@" >/dev/null 2>&1; i=$((i+1)); done; t1=$(now)
  echo "$label $(( (t1-t0)/100000/N ))x0.1ms"; }

E=$(printf '\036'); U=$(printf '\037')
prelude() { if print -nr '' 2>/dev/null; then __denza_emit() { print -nr "$1"; }; else __denza_emit() { printf '%s' "$1"; }; fi; }

old_frame() { __c=$(printf '%s' "$1" | base64 -d); printf '\036M:BEGIN\037'; ( eval "$__c" ) 2>&1; __s=$?; printf '\036M:%s\037' "$__s"; }
new_frame() { prelude; __denza_emit "${E}M:BEGIN${U}"; ( eval "$1" ) 2>&1; __s=$?; __denza_emit "${E}M:${__s}${U}"; }

bare_am()  { am stack list; }
bare_tx()  { service call activity_task 30; }
bare_set() { settings get global development_settings_enabled; }
old_am()   { old_frame 'YW0gc3RhY2sgbGlzdA=='; }
new_am()   { new_frame 'am stack list'; }
old_tx()   { old_frame 'c2VydmljZSBjYWxsIGFjdGl2aXR5X3Rhc2sgMzA='; }
new_tx()   { new_frame 'service call activity_task 30'; }
old_set()  { old_frame 'c2V0dGluZ3MgZ2V0IGdsb2JhbCBkZXZlbG9wbWVudF9zZXR0aW5nc19lbmFibGVk'; }
new_set()  { new_frame 'settings get global development_settings_enabled'; }

echo "sh=$(readlink -f /proc/$$/exe 2>/dev/null || echo /system/bin/sh)"
echo "print : $(type print 2>&1)"
echo "printf: $(type printf 2>&1)"
if print -nr '' 2>/dev/null; then echo "emitter=print (builtin, no process)"; else echo "emitter=printf (a process per marker)"; fi
echo
run "bare  am stack list      " bare_am
run "OLD   am stack list      " old_am
run "NEW   am stack list      " new_am
run "bare  activity_task 30   " bare_tx
run "OLD   activity_task 30   " old_tx
run "NEW   activity_task 30   " new_tx
run "bare  settings get       " bare_set
run "OLD   settings get       " old_set
run "NEW   settings get       " new_set
DEVICE
