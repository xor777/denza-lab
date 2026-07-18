#!/bin/sh
set -eu

set -- ${SSH_ORIGINAL_COMMAND:-}
if [ "$#" -ne 3 ] || [ "$1" != "enroll" ]; then
  echo "ERROR expected: enroll CODE PAYLOAD" >&2
  exit 2
fi

exec /opt/cag/cag-state enroll "$2" "$3"
