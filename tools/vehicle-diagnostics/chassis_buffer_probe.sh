#!/usr/bin/env bash
# Offline build/test helper only. It never invokes adb, installs, or runs a device command.
# build /absolute/output-directory
# test /absolute/output-directory
# command wheels|steering 3..20 /data/local/tmp/denza-chassis-buffer-read-<32hex>.dex
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
source_file="$script_dir/ChassisBufferReadProbe.java"
android_sdk="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
android_jar="$android_sdk/platforms/android-35/android.jar"
d8="$android_sdk/build-tools/36.0.0/d8"
java_base="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
main_class="dev.denza.diagnostics.probe.ChassisBufferReadProbe"
require_file() { [[ -f "$1" ]] || { echo "missing required file: $1" >&2; exit 2; }; }
output_directory() {
  [[ "$1" == /* && "$1" != / && "$1" != *'/../'* && "$1" != *'/..' ]] || { echo 'output directory must be an absolute path without parent traversal' >&2; exit 2; }
  mkdir -p "$1/dex"
}
compile() {
  mkdir -p "$1/classes"
  require_file "$source_file"; require_file "$java_base/bin/javac"
  "$java_base/bin/javac" --release 8 -Xlint:all -d "$1/classes" "$source_file"
}
compile_tmp=""
cleanup() { if [[ -n "$compile_tmp" ]]; then rm -r "$compile_tmp"; fi; }
trap cleanup EXIT
case "${1:-}" in
  build)
    [[ $# == 2 ]] || exit 2
    output_directory "$2"; require_file "$android_jar"; require_file "$d8"
    compile_tmp="$(mktemp -d /tmp/denza-chassis-buffer-build.XXXXXX)"; compile "$compile_tmp"
    classes=()
    while IFS= read -r -d '' file; do classes+=("$file"); done < <(find "$compile_tmp/classes" -type f -name '*.class' -print0)
    (( ${#classes[@]} > 0 )) || exit 2
    "$d8" --min-api 26 --lib "$android_jar" --output "$2/dex" "${classes[@]}"
    shasum -a 256 "$source_file" "$2/dex/classes.dex"
    ;;
  test)
    [[ $# == 2 ]] || exit 2
    output_directory "$2"
    compile_tmp="$(mktemp -d /tmp/denza-chassis-buffer-test.XXXXXX)"; compile "$compile_tmp"
    test_file="$script_dir/ChassisBufferReadProbeTest.java"
    require_file "$test_file"
    "$java_base/bin/javac" --release 8 -Xlint:all -cp "$compile_tmp/classes" -d "$compile_tmp/classes" "$test_file"
    "$java_base/bin/java" -cp "$compile_tmp/classes" dev.denza.diagnostics.probe.ChassisBufferReadProbeTest
    ;;
  command)
    [[ $# == 4 && ( "$2" == wheels || "$2" == steering ) && "$3" =~ ^([3-9]|1[0-9]|20)$ \
      && "$4" =~ ^/data/local/tmp/denza-chassis-buffer-read-[0-9a-f]{32}\.dex$ ]] || exit 2
    printf 'CLASSPATH=%s exec app_process /system/bin --nice-name=denza_chassis_buffer_read %s --target %s --seconds %s\n' "$4" "$main_class" "$2" "$3"
    ;;
  *) sed -n '2,5p' "$0"; exit 2 ;;
esac
