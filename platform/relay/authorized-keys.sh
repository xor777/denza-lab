#!/bin/sh
set -eu

case "${1:-}" in
  cag-device|cag-client|cag-control)
    exec /opt/cag/cag-state authorized-keys "$1"
    ;;
  *) exit 0 ;;
esac
