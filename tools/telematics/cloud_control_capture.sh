#!/system/bin/sh
# Bounded read-only control observation. No vehicle setters or network changes.
# Run with: timeout 150s sh SCRIPT PRIVATE_DIRECTORY
set -eu
umask 077
capture_dir=$1
[ "$capture_dir" = /data/local/tmp/denza-climate-observe-20260924 ]
[ "$(getprop sys.car.protocol)" = CANFD ]
cd "$capture_dir"
capture_pid=
cleanup() {
    if [ -n "$capture_pid" ]; then
        kill "$capture_pid" 2>/dev/null || true
        wait "$capture_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 143' HUP INT TERM
# Full-message allowlist: no raw packets, correlation tokens or identifiers.
pattern='^(server_data_to_mcu mFuncNum is [0-9]{1,5},replyFlag is [0-9]{1,3},mFuncVision [0-9]{1,3}|recv 532 cmd is 0x[0-9a-fA-F]{1,2}|rsp 536 mMcuStatus :[0-9]{1,3}, m536Type :[0-9]{1,3}|mcu_data_ind mFuncNum is:[0-9]{1,5}, mFucVision is:[0-9]{1,3},mReplyFlag:[0-9]{1,3},mDLen:[0-9]{1,3}|send_complete status :[01], key_id :[0-9]{1,5}) *$'
timeout 120s logcat -b main -b system -v epoch -T 1 -m 256 -e "$pattern" '[BYDCLOUD]main:V' '[BYDCLOUD]socket:V' '*:S' > events.log 2> logcat-errors.log &
capture_pid=$!
cat /proc/sys/kernel/random/boot_id > boot-before.txt
echo READY
sample=0
while [ "$sample" -lt 24 ]; do
    {
        echo "SAMPLE $sample $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo POWER
        service call autoservice 5 i32 1000 i32 1077936144
        echo MAIN_TEMP
        service call autoservice 5 i32 1000 i32 1077936168
        echo DEPUTY_TEMP
        service call autoservice 5 i32 1000 i32 1077936176
        echo LAST_RESULT
        getprop sys.cloud_532_reply
        echo BUSY
        getprop sys.cloud.remote_controling
    } >> state.log
    sample=$((sample + 1))
    sleep 5
done
cat /proc/sys/kernel/random/boot_id > boot-after.txt
echo DONE
