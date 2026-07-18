#!/bin/sh
set -eu

case "${PAM_USER:-}" in
  cag-enroll|cag-pair) ;;
  *) exit 1 ;;
esac

exec /opt/cag/cag-state auth-check \
  --user "$PAM_USER" \
  --source "${PAM_RHOST:-unknown}" \
  --password-stdin
