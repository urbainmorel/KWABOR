#!/usr/bin/env bash

set -u -o pipefail

if (($# != 1)); then
  echo "Usage: $0 <api-level>" >&2
  exit 64
fi

capture_status=0
maximum_capture_attempts=3
retry_reason=""
for ((capture_attempt = 1; capture_attempt <= maximum_capture_attempts; capture_attempt++)); do
  if bash tools/capture-android-launch-evidence.sh "$1"; then
    exit 0
  else
    capture_status=$?
  fi

  case "${capture_status}" in
    75) retry_reason="transient screencap acquisition failure" ;;
    124) retry_reason="bounded command timeout" ;;
    *) exit "${capture_status}" ;;
  esac
  if ((capture_attempt == maximum_capture_attempts)); then
    exit "${capture_status}"
  fi

  echo \
    "::warning::Retrying complete launch evidence after ${retry_reason} (${capture_attempt}/${maximum_capture_attempts})"
done

exit "${capture_status}"
