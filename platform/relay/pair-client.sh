#!/bin/sh
set -eu

set -- ${SSH_ORIGINAL_COMMAND:-}
if [ "$#" -ne 3 ] || [ "$1" != "pair" ]; then
  echo "ERROR expected: pair CODE PAYLOAD" >&2
  exit 2
fi

exec /opt/cag/cag-state pair-submit "$2" "$3"
