#!/usr/bin/env bash
# Inspect Navigator's microphone controls; an explicit click mode activates exactly one view.
# Shell UID, no APK installation, no coordinates, existing accessibility services kept enabled.
# See docs/shortcuts-automation-findings.md, "Alice in Yandex Navigator".
set -euo pipefail

mode="${1:-inspect}"
view_id="${2:-}"
if [[ "$mode" != inspect && "$mode" != dump && "$mode" != reveal-click && "$mode" != reveal-visible && "$mode" != query && "$mode" != compare && "$mode" != click && "$mode" != wait-click ]]; then
  echo 'Usage: yandex_alice_ui_probe.sh inspect | dump | reveal-click | reveal-visible | {query|compare|click|wait-click} ru.yandex.yandexnavi:id/VIEW_ID' >&2
  exit 2
fi
if [[ ( "$mode" == click || "$mode" == wait-click ) && ! "$view_id" =~ ^ru\.yandex\.yandexnavi:id/[A-Za-z0-9_]+$ ]]; then
  echo 'Click requires an exact Navigator view ID from inspect.' >&2
  exit 2
fi
script_dir="$(cd "$(dirname "$0")" && pwd)"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
task_java="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
android_jar="$android_sdk/platforms/android-33/android.jar"
serial="${ADB_SERIAL:-127.0.0.1:5555}"
build_dir="$(mktemp -d /tmp/denza-alice-ui.XXXXXX)"
trap 'rm -r "$build_dir"' EXIT
mkdir -p "$build_dir/classes" "$build_dir/dex"
"$task_java/bin/javac" -Xlint:-options -source 8 -target 8 -cp "$android_jar" \
  -d "$build_dir/classes" "$script_dir/YandexAliceUiProbe.java"
JAVA_HOME="$task_java" "$android_sdk/build-tools/36.0.0/d8" --min-api 26 \
  --lib "$android_jar" --output "$build_dir/dex" \
  "$build_dir/classes/dev/denza/tools/YandexAliceUiProbe.class"
device_dir=/data/local/tmp/denza-alice-ui-probe
perl -e 'alarm shift; exec @ARGV' 10 adb -s "$serial" shell "mkdir -p $device_dir"
perl -e 'alarm shift; exec @ARGV' 15 adb -s "$serial" push "$build_dir/dex/classes.dex" "$device_dir/classes.dex" >/dev/null
perl -e 'alarm shift; exec @ARGV' 130 adb -s "$serial" shell \
  "CLASSPATH=$device_dir/classes.dex app_process /system/bin dev.denza.tools.YandexAliceUiProbe $mode $view_id"
