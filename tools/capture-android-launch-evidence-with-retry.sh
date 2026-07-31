#!/usr/bin/env bash

set -u -o pipefail

if (($# != 1)); then
  echo "Usage: $0 <api-level>" >&2
  exit 64
fi

capture_status=0
for capture_attempt in 1 2; do
  if bash tools/capture-android-launch-evidence.sh "$1"; then
    exit 0
  else
    capture_status=$?
  fi

  if ((capture_status != 75 || capture_attempt == 2)); then
    exit "${capture_status}"
  fi

  echo "::warning::Retrying the complete launch evidence after a transient screencap idle gap"
done

exit "${capture_status}"
