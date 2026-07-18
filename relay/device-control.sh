#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "ERROR missing forced device identity" >&2
  exit 2
fi
device_id=$1
set -- ${SSH_ORIGINAL_COMMAND:-}

case "${1:-}" in
  pair-open)
    [ "$#" -eq 2 ] || exit 2
    exec /opt/cag/cag-state pair-open "$device_id" "$2"
    ;;
  pair-commit)
    [ "$#" -eq 2 ] || exit 2
    exec /opt/cag/cag-state pair-commit "$device_id" "$2"
    ;;
  pair-abort)
    [ "$#" -eq 2 ] || exit 2
    exec /opt/cag/cag-state pair-abort "$device_id" "$2"
    ;;
  set-endpoint)
    [ "$#" -eq 3 ] || exit 2
    exec /opt/cag/cag-state set-endpoint "$device_id" "$2" "$3"
    ;;
  set-enabled)
    [ "$#" -eq 2 ] || exit 2
    exec /opt/cag/cag-state set-enabled "$device_id" "$2"
    ;;
  status)
    [ "$#" -eq 1 ] || exit 2
    exec /opt/cag/cag-state device-status "$device_id"
    ;;
  *)
    echo "ERROR unsupported device control command" >&2
    exit 2
    ;;
esac
