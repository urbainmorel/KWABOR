#!/usr/bin/env bash

set -Eeuo pipefail

case "$(uname -s)" in
  MINGW* | MSYS*)
    export MSYS_NO_PATHCONV=1
    ;;
esac

api_level="${1:?Usage: capture-android-launch-evidence.sh API_LEVEL [APK_PATH]}"
apk_path="${2:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
case "${api_level}" in
  30 | 31 | 36) ;;
  *)
    echo "Unsupported Android launch-evidence API level: ${api_level}" >&2
    exit 1
    ;;
esac
package_name="com.kwabor.android"
activity_name="${package_name}/.MainActivity"
evidence_root="build/brand-evidence/api-${api_level}"
# Android screenrecord follows the emulator presentation clock and emits variable-rate
# frames. Retain that untouched stream and also sample the composited display with
# screencap so short-lived system layers remain reviewable on every supported API.
# The PNG loop runs inside one bounded device-side shell: repeated adb exec-out
# transfers can stall for seconds under KVM and erase the splash interval being proved.
screenrecord_time_limit_seconds=120
minimum_evidence_seconds=15
normalized_frame_rate=30
contact_sheet_samples=150
contact_sheet_columns=15
contact_sheet_rows=10
contact_sheet_frame_width=180
minimum_recording_wall_seconds=24
minimum_raw_decoded_frames=30
post_launch_hold_seconds=5
onboarding_deadline_seconds=60
remote_header_probe_bytes=16384
screenrecord_startup_timeout_seconds=30
screenrecord_post_launch_sample_timeout_seconds=15
screenrecord_graceful_stop_seconds=60
screenrecord_forced_stop_seconds=5
screenrecord_host_reap_seconds=10
adb_probe_timeout_seconds=3
adb_general_timeout_seconds=60
command_kill_grace_seconds=2
uiautomator_command_timeout_seconds=12
screencap_hard_timeout_seconds=75
screencap_arm_timeout_seconds=10
screencap_frame_timeout_seconds=2
screencap_sample_interval_microseconds=50000
screencap_maximum_gap_seconds=0.75
screencap_maximum_frames=1600
screencap_maximum_frame_bytes=33554432
screencap_remote_maximum_bytes=536870912
screencap_remote_reserve_bytes=268435456
screencap_pull_timeout_seconds=180
screencap_validation_timeout_seconds=120
minimum_screencap_frames=32
minimum_screencap_duration_seconds=24
screencap_maximum_frame_duration_seconds=$((screencap_frame_timeout_seconds +
  command_kill_grace_seconds))
ffmpeg_command_timeout_seconds=120
ffprobe_command_timeout_seconds=30
intro_accessibility_label="Découvrir le Bénin avec Kwabor"
configuration_unavailable_message="Kwabor est indisponible pour le moment. Réessayez plus tard."
landing_title="Découvrez le Bénin"
landing_sign_in="Se connecter"

declare -a density_profiles=(
  "mdpi:160:360x780:360x780"
  "xhdpi:320:720x1560:720x1560"
  "xxxhdpi:640:1440x3120:720x1560"
)

if [[ -z "${EPOCHREALTIME:-}" ]]; then
  echo "Bash 5+ with EPOCHREALTIME support is required" >&2
  exit 1
fi
for command_name in adb ffmpeg ffprobe sha256sum timeout; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Missing required command: ${command_name}" >&2
    exit 1
  fi
done
timeout_binary="$(command -v timeout)"
timeout() {
  "${timeout_binary}" --kill-after="${command_kill_grace_seconds}" "$@"
}
python_command=""
for python_candidate in python3 python; do
  if command -v "${python_candidate}" >/dev/null 2>&1 &&
    "${python_candidate}" -c \
      'import sys; raise SystemExit(0 if sys.version_info >= (3, 8) else 1)' \
      >/dev/null 2>&1; then
    python_command="${python_candidate}"
    break
  fi
done
if [[ -z "${python_command}" ]]; then
  echo "Missing a working Python 3.8+ interpreter (python3 or python)" >&2
  exit 1
fi
ffmpeg_major_version="$(
  timeout 15 ffmpeg -version |
    sed -n '1s/^ffmpeg version \([0-9][0-9]*\).*/\1/p'
)"
if [[ ! "${ffmpeg_major_version}" =~ ^[0-9]+$ ]] ||
  ((ffmpeg_major_version < 5)); then
  echo "ffmpeg 5+ is required for bounded VFR launch evidence" >&2
  exit 1
fi
if [[ "$(timeout 15 ffmpeg -hide_banner -encoders 2>/dev/null)" != *"libx264"* ]]; then
  echo "ffmpeg does not provide the required libx264 encoder" >&2
  exit 1
fi

ffmpeg_binary="$(command -v ffmpeg)"
ffprobe_binary="$(command -v ffprobe)"

ffmpeg() {
  timeout "${ffmpeg_command_timeout_seconds}" "${ffmpeg_binary}" "$@"
}

ffprobe() {
  timeout "${ffprobe_command_timeout_seconds}" "${ffprobe_binary}" "$@"
}

if [[ ! -f "${apk_path}" ]]; then
  echo "Missing debug APK: ${apk_path}" >&2
  exit 1
fi

actual_api="$(
  timeout "${adb_general_timeout_seconds}" adb shell getprop ro.build.version.sdk |
    tr -d '\r'
)"
if [[ "${actual_api}" != "${api_level}" ]]; then
  echo "Expected API ${api_level}, connected emulator reports API ${actual_api}" >&2
  exit 1
fi

rm -rf -- "${evidence_root}"
mkdir -p "${evidence_root}"

reset_display() {
  timeout "${adb_probe_timeout_seconds}" \
    adb shell wm size reset >/dev/null 2>&1 ||
    true
  timeout "${adb_probe_timeout_seconds}" \
    adb shell wm density reset >/dev/null 2>&1 ||
    true
}

read_effective_display_size() {
  local display_output=""
  local override_size=""
  local physical_size=""

  if ! display_output="$(
    timeout "${adb_probe_timeout_seconds}" adb shell wm size |
      tr -d '\r'
  )"; then
    echo "Unable to query the effective display size" >&2
    return 1
  fi
  override_size="$(
    printf '%s\n' "${display_output}" |
      sed -n 's/^Override size: //p'
  )"
  physical_size="$(
    printf '%s\n' "${display_output}" |
      sed -n 's/^Physical size: //p'
  )"
  if [[ "${override_size}" =~ ^[0-9]+x[0-9]+$ ]]; then
    printf '%s' "${override_size}"
    return 0
  fi
  if [[ "${physical_size}" =~ ^[0-9]+x[0-9]+$ ]]; then
    printf '%s' "${physical_size}"
    return 0
  fi
  echo "Unable to parse the effective display size: ${display_output}" >&2
  return 1
}

read_effective_display_density() {
  local density_output=""
  local override_density=""
  local physical_density=""

  if ! density_output="$(
    timeout "${adb_probe_timeout_seconds}" adb shell wm density |
      tr -d '\r'
  )"; then
    echo "Unable to query the effective display density" >&2
    return 1
  fi
  override_density="$(
    printf '%s\n' "${density_output}" |
      sed -n 's/^Override density: //p'
  )"
  physical_density="$(
    printf '%s\n' "${density_output}" |
      sed -n 's/^Physical density: //p'
  )"
  if [[ "${override_density}" =~ ^[0-9]+$ ]]; then
    printf '%s' "${override_density}"
    return 0
  fi
  if [[ "${physical_density}" =~ ^[0-9]+$ ]]; then
    printf '%s' "${physical_density}"
    return 0
  fi
  echo "Unable to parse the effective display density: ${density_output}" >&2
  return 1
}

assert_display_profile() {
  local expected_size="$1"
  local expected_density="$2"
  local actual_size=""
  local actual_density=""

  actual_size="$(read_effective_display_size)" || return 1
  actual_density="$(read_effective_display_density)" || return 1
  if [[ "${actual_size}" != "${expected_size}" ||
    "${actual_density}" != "${expected_density}" ]]; then
    echo \
      "Unexpected display profile: ${actual_size}@${actual_density}, expected ${expected_size}@${expected_density}" \
      >&2
    return 1
  fi
}

apply_display_profile() {
  local expected_size="$1"
  local expected_density="$2"
  local attempt=0

  for attempt in 1 2 3; do
    if timeout "${adb_probe_timeout_seconds}" adb shell wm size reset >/dev/null &&
      timeout "${adb_probe_timeout_seconds}" adb shell wm density reset >/dev/null &&
      timeout "${adb_probe_timeout_seconds}" adb shell wm size "${expected_size}" >/dev/null &&
      timeout "${adb_probe_timeout_seconds}" adb shell wm density "${expected_density}" >/dev/null; then
      sleep 1
      if assert_display_profile "${expected_size}" "${expected_density}"; then
        return 0
      fi
    fi
    echo "Display profile attempt ${attempt} did not settle" >&2
    timeout "${adb_probe_timeout_seconds}" adb wait-for-device >/dev/null 2>&1 || true
  done
  echo \
    "Unable to apply display profile ${expected_size}@${expected_density} after 3 attempts" \
    >&2
  return 1
}

active_recorder_pid=""
active_remote_recorder_pid=""
active_screencap_pid=""
active_screencap_remote_root=""
active_screencap_nonce=""
active_screencap_started=0
screenrecord_ready_bytes=""
screenrecord_mdat_payload_offset=""
screenrecord_frame_bootstrap=""
screenrecord_post_launch_bytes=""

probe_screenrecord_processes() {
  local probe_timeout_seconds="$1"
  local probe_output=""
  local remote_status=""
  local remote_processes=""

  if ! probe_output="$(
    timeout "${probe_timeout_seconds}" adb shell \
      "processes=\"\$(pidof screenrecord 2>/dev/null)\"; status=\$?; printf '%s|%s\n' \"\${status}\" \"\${processes}\"" |
      tr -d '\r\n'
  )"; then
    echo "Unable to query the remote screenrecord process" >&2
    return 2
  fi
  remote_status="${probe_output%%|*}"
  remote_processes="${probe_output#*|}"
  case "${remote_status}" in
    0)
      if [[ -z "${remote_processes}" ]]; then
        echo "Remote pidof reported success without a screenrecord PID" >&2
        return 2
      fi
      printf '%s' "${remote_processes}"
      ;;
    1)
      if [[ -n "${remote_processes}" ]]; then
        echo "Remote pidof reported no process but returned: ${remote_processes}" >&2
        return 2
      fi
      return 1
      ;;
    *)
      echo "Remote pidof failed with status ${remote_status}" >&2
      return 2
      ;;
  esac
}

remote_recorder_is_running() {
  local remote_recorder_pid="$1"
  local probe_timeout_seconds="${2:-${adb_probe_timeout_seconds}}"
  local remote_processes=""
  local probe_status=0

  if remote_processes="$(
    probe_screenrecord_processes "${probe_timeout_seconds}"
  )"; then
    :
  else
    probe_status=$?
    return "${probe_status}"
  fi
  [[ " ${remote_processes} " == *" ${remote_recorder_pid} "* ]]
}

signal_remote_recorder() {
  local remote_recorder_pid="$1"
  local signal_number="$2"
  local signal_result=""

  if [[ ! "${remote_recorder_pid}" =~ ^[0-9]+$ ]] ||
    [[ "${signal_number}" != "2" && "${signal_number}" != "9" ]]; then
    echo "Refusing an invalid remote screenrecord signal request" >&2
    return 2
  fi
  if ! signal_result="$(
    timeout "${adb_probe_timeout_seconds}" adb shell \
      "process_name=\"\$(cat /proc/${remote_recorder_pid}/comm 2>/dev/null)\"; if [ \"\${process_name}\" != screenrecord ]; then printf ABSENT; elif kill -${signal_number} ${remote_recorder_pid} 2>/dev/null; then printf DELIVERED; elif [ ! -r /proc/${remote_recorder_pid}/comm ]; then printf ABSENT; else printf ERROR; fi" |
      tr -d '\r\n'
  )"; then
    echo "Unable to signal the remote screenrecord process" >&2
    return 2
  fi
  case "${signal_result}" in
    DELIVERED) return 0 ;;
    ABSENT) return 1 ;;
    *)
      echo "Remote screenrecord signal failed: ${signal_result}" >&2
      return 2
      ;;
  esac
}

validate_remote_screencap_root() {
  local remote_root="$1"

  [[ "${remote_root}" =~ ^/data/local/tmp/kwabor-brand-002-api-(30|31|36)-(mdpi|xhdpi|xxxhdpi)-[0-9a-f]{16}$ ]]
}

validate_screencap_nonce() {
  local nonce="$1"

  [[ "${nonce}" =~ ^[0-9a-f]{16}$ ]]
}

remote_screencap_marker_matches() {
  local marker_path="$1"
  local expected_nonce="$2"
  local marker_value=""

  if ! validate_screencap_nonce "${expected_nonce}"; then
    return 1
  fi
  if ! marker_value="$(
    timeout "${adb_probe_timeout_seconds}" \
      adb shell cat "${marker_path}" 2>/dev/null |
      tr -d '\r\n'
  )"; then
    return 1
  fi
  [[ "${marker_value}" == "${expected_nonce}" ]]
}

request_remote_screencap_control() {
  local pending_path="$1"
  local marker_path="$2"
  local expected_nonce="$3"

  if remote_screencap_marker_matches \
    "${marker_path}" \
    "${expected_nonce}"; then
    return 0
  fi
  if ! remote_screencap_marker_matches \
    "${pending_path}" \
    "${expected_nonce}"; then
    echo "Invalid remote screencap control marker: ${pending_path}" >&2
    return 1
  fi
  timeout "${adb_probe_timeout_seconds}" \
    adb shell mv "${pending_path}" "${marker_path}" >/dev/null 2>&1 ||
    true
  if remote_screencap_marker_matches \
    "${marker_path}" \
    "${expected_nonce}"; then
    return 0
  fi
  echo "Unable to publish the remote screencap control marker: ${marker_path}" >&2
  return 1
}

wait_for_remote_screencap_done() {
  local remote_root="$1"
  local expected_nonce="$2"
  local timeout_seconds="$3"
  local deadline=$((SECONDS + timeout_seconds + 1))

  while ((SECONDS < deadline)); do
    if remote_screencap_marker_matches \
      "${remote_root}/screencap-done" \
      "${expected_nonce}"; then
      return 0
    fi
    sleep 0.1
  done
  remote_screencap_marker_matches \
    "${remote_root}/screencap-done" \
    "${expected_nonce}"
}

wait_for_local_process_exit() {
  local process_pid="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds + 1))

  while ((SECONDS < deadline)); do
    if ! kill -0 "${process_pid}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  ! kill -0 "${process_pid}" >/dev/null 2>&1
}

reap_bounded_local_process() {
  local process_pid="$1"
  local graceful_wait_seconds="$2"
  local process_status=0

  if ! wait_for_local_process_exit \
    "${process_pid}" \
    "${graceful_wait_seconds}"; then
    kill "${process_pid}" >/dev/null 2>&1 || true
    if ! wait_for_local_process_exit "${process_pid}" 2; then
      kill -9 "${process_pid}" >/dev/null 2>&1 || true
    fi
  fi
  if wait "${process_pid}"; then
    process_status=0
  else
    process_status=$?
  fi
  return "${process_status}"
}

remove_remote_screencap_root() {
  local remote_root="$1"

  if ! validate_remote_screencap_root "${remote_root}"; then
    echo "Refusing to remove an invalid remote screencap root: ${remote_root}" >&2
    return 1
  fi
  timeout "${adb_general_timeout_seconds}" \
    adb shell rm -rf "${remote_root}"
}

cleanup_active_screencap() {
  local cleanup_wait_seconds=$((screencap_frame_timeout_seconds +
    command_kill_grace_seconds +
    adb_probe_timeout_seconds))
  local done_observed=0

  if [[ -n "${active_screencap_remote_root}" &&
    -n "${active_screencap_nonce}" ]]; then
    if ((active_screencap_started != 0)); then
      request_remote_screencap_control \
        "${active_screencap_remote_root}/screencap-abort.pending" \
        "${active_screencap_remote_root}/screencap-abort" \
        "${active_screencap_nonce}" \
        >/dev/null 2>&1 ||
        true
    fi
    if wait_for_remote_screencap_done \
      "${active_screencap_remote_root}" \
      "${active_screencap_nonce}" \
      "${cleanup_wait_seconds}"; then
      done_observed=1
    fi
  fi
  if [[ -n "${active_screencap_pid}" ]]; then
    reap_bounded_local_process \
      "${active_screencap_pid}" \
      "${cleanup_wait_seconds}" \
      >/dev/null 2>&1 ||
      true
    active_screencap_pid=""
  fi
  if ((done_observed == 0)) &&
    [[ -n "${active_screencap_remote_root}" &&
      -n "${active_screencap_nonce}" ]] &&
    remote_screencap_marker_matches \
      "${active_screencap_remote_root}/screencap-done" \
      "${active_screencap_nonce}"; then
    done_observed=1
  fi
  if ((active_screencap_started == 0)); then
    done_observed=1
  fi
  # Once a spawn was possible, absence of the nonce-bound done marker means the
  # device worker may still be writing after a transport failure. Leave that
  # unique, quota-bounded root to emulator teardown instead of racing an rm.
  if [[ -n "${active_screencap_remote_root}" ]] &&
    ((done_observed != 0)); then
    remove_remote_screencap_root \
      "${active_screencap_remote_root}" \
      >/dev/null 2>&1 ||
      true
  fi
  active_screencap_remote_root=""
  active_screencap_nonce=""
  active_screencap_started=0
}

cleanup() {
  local exit_status=$?
  set +e
  cleanup_active_screencap
  if [[ -n "${active_remote_recorder_pid}" ]]; then
    signal_remote_recorder \
      "${active_remote_recorder_pid}" \
      2 >/dev/null 2>&1 ||
      true
    signal_remote_recorder \
      "${active_remote_recorder_pid}" \
      2 >/dev/null 2>&1 ||
      true
    signal_remote_recorder \
      "${active_remote_recorder_pid}" \
      9 >/dev/null 2>&1 ||
      true
  fi
  if [[ -n "${active_recorder_pid}" ]]; then
    reap_bounded_local_process \
      "${active_recorder_pid}" \
      0 >/dev/null 2>&1 ||
      true
  fi
  for cleanup_density in mdpi xhdpi xxxhdpi; do
    cleanup_frames="${evidence_root}/${cleanup_density}/screencap-frames"
    if [[ -d "${cleanup_frames}" ]]; then
      rm -rf -- "${cleanup_frames}"
    fi
    for cleanup_staging in \
      "${evidence_root}/${cleanup_density}"/.screencap-staging-*; do
      if [[ -d "${cleanup_staging}" ]]; then
        rm -rf -- "${cleanup_staging}"
      fi
    done
  done
  reset_display
  return "${exit_status}"
}
trap cleanup EXIT

capture_screencap_frames() {
  local remote_root="$1"
  local nonce="$2"
  local device_timeout_seconds=$((screencap_hard_timeout_seconds +
    screencap_frame_timeout_seconds +
    command_kill_grace_seconds +
    1))
  local host_timeout_seconds=$((device_timeout_seconds +
    command_kill_grace_seconds +
    5))

  if ! validate_remote_screencap_root "${remote_root}" ||
    ! validate_screencap_nonce "${nonce}"; then
    echo "Refusing invalid remote screencap capture parameters" >&2
    return 1
  fi
  exec "${timeout_binary}" \
    --signal=TERM \
    --kill-after="${command_kill_grace_seconds}" \
    "${host_timeout_seconds}" \
    adb shell -T \
    /system/bin/toybox timeout \
    -k "${command_kill_grace_seconds}" \
    "${device_timeout_seconds}" \
    /system/bin/sh -s -- \
    "${remote_root}" \
    "${nonce}" \
    "${screencap_hard_timeout_seconds}" \
    "${screencap_frame_timeout_seconds}" \
    "${screencap_sample_interval_microseconds}" \
    "${screencap_maximum_frames}" \
    "${minimum_screencap_frames}" \
    "${minimum_screencap_duration_seconds}" \
    "${screencap_maximum_frame_bytes}" \
    "${screencap_remote_maximum_bytes}" <<'ANDROID_SCREENCAP'
set -u

remote_root="$1"
nonce="$2"
hard_timeout_seconds="$3"
frame_timeout_seconds="$4"
sample_interval_microseconds="$5"
maximum_frames="$6"
minimum_frames="$7"
minimum_duration_seconds="$8"
maximum_frame_bytes="$9"
shift 9
maximum_total_bytes="$1"
umask 077
frames_directory="${remote_root}/screencap-frames"
timestamps_file="${remote_root}/screencap-timestamps.tsv"
armed_file="${remote_root}/screencap-armed"
armed_pending_file="${armed_file}.pending"
stop_file="${remote_root}/screencap-stop"
stop_pending_file="${stop_file}.pending"
abort_file="${remote_root}/screencap-abort"
abort_pending_file="${abort_file}.pending"
status_file="${remote_root}/screencap-status"
status_pending_file="${status_file}.pending"
done_file="${remote_root}/screencap-done"
done_pending_file="${done_file}.pending"
pid_file="${remote_root}/screencap.pid"
frame_index=0
captured_frames=0
total_bytes=0
first_captured_at=""
last_captured_at=""
current_partial_path=""

marker_matches() {
  marker_path="$1"
  marker_value=""

  [ -f "${marker_path}" ] || return 1
  IFS= read -r marker_value <"${marker_path}" || return 1
  [ "${marker_value}" = "${nonce}" ]
}

finish_capture() {
  capture_status=$?
  status_committed=0

  trap - 0 HUP INT TERM
  if [ -n "${current_partial_path}" ]; then
    rm -f "${current_partial_path}"
  fi
  if [ "${capture_status}" -ne 0 ]; then
    rm -rf "${frames_directory}"
    rm -f "${timestamps_file}"
  fi
  if printf '%s\t%s\t%s\t%s\n' \
    "${nonce}" \
    "${capture_status}" \
    "${captured_frames}" \
    "${total_bytes}" \
    >"${status_pending_file}" 2>/dev/null &&
    mv "${status_pending_file}" "${status_file}" 2>/dev/null; then
    status_committed=1
  else
    echo "Unable to commit remote screencap status" >&2
    rm -rf "${frames_directory}"
    rm -f "${timestamps_file}" "${status_pending_file}"
    capture_status=125
    captured_frames=0
    total_bytes=0
    if printf '%s\t%s\t%s\t%s\n' \
      "${nonce}" \
      "${capture_status}" \
      "${captured_frames}" \
      "${total_bytes}" \
      >"${status_pending_file}" 2>/dev/null &&
      mv "${status_pending_file}" "${status_file}" 2>/dev/null; then
      status_committed=1
    fi
  fi
  if [ "${status_committed}" -eq 1 ] &&
    marker_matches "${done_pending_file}"; then
    mv "${done_pending_file}" "${done_file}" 2>/dev/null || true
  fi
  exit "${capture_status}"
}

trap finish_capture 0
trap 'exit 130' HUP INT TERM

if [ ! -d "${frames_directory}" ] ||
  ! marker_matches "${armed_pending_file}" ||
  ! marker_matches "${stop_pending_file}" ||
  ! marker_matches "${abort_pending_file}" ||
  ! marker_matches "${done_pending_file}"; then
  echo "Missing remote screencap frame directory: ${frames_directory}" >&2
  exit 1
fi
printf '%s\t%s\n' "${nonce}" "$$" >"${pid_file}"
: >"${timestamps_file}"
IFS=' ' read -r started_at ignored </proc/uptime

while :; do
  IFS=' ' read -r current_at ignored </proc/uptime
  if marker_matches "${abort_file}"; then
    echo "Screencap sequence aborted by the parent capture" >&2
    exit 130
  fi
  if awk \
    -v started="${started_at}" \
    -v current="${current_at}" \
    -v maximum="${hard_timeout_seconds}" \
    'BEGIN { exit !(current - started >= maximum) }'; then
    echo "Screencap sequence exceeded its ${hard_timeout_seconds}s deadline" >&2
    exit 1
  fi
  if marker_matches "${stop_file}" &&
    [ -n "${first_captured_at}" ] &&
    awk \
      -v first="${first_captured_at}" \
      -v last="${last_captured_at}" \
      -v minimum="${minimum_duration_seconds}" \
      'BEGIN { exit !(last - first >= minimum) }'; then
    break
  fi
  if [ "${frame_index}" -ge "${maximum_frames}" ]; then
    echo "Screencap sequence exceeded ${maximum_frames} frame attempts" >&2
    exit 1
  fi
  if [ "${total_bytes}" -gt "$((maximum_total_bytes - maximum_frame_bytes))" ]; then
    echo \
      "Screencap sequence cannot reserve another ${maximum_frame_bytes}-byte frame" \
      >&2
    exit 1
  fi

  frame_name="$(printf 'frame-%05d.png' "${frame_index}")"
  frame_path="${frames_directory}/${frame_name}"
  partial_path="${frame_path}.partial"
  current_partial_path="${partial_path}"
  IFS=' ' read -r attempted_at ignored </proc/uptime
  if /system/bin/toybox timeout -s 9 "${frame_timeout_seconds}" \
    /system/bin/screencap -p >"${partial_path}" &&
    [ -s "${partial_path}" ]; then
    IFS=' ' read -r completed_at ignored </proc/uptime
    frame_bytes="$(/system/bin/toybox stat -c %s "${partial_path}")"
    png_header="$(
      /system/bin/toybox xxd -p -l 8 "${partial_path}" |
        /system/bin/toybox tr -d '\n'
    )"
    png_trailer="$(
      /system/bin/toybox tail -c 12 "${partial_path}" |
        /system/bin/toybox xxd -p |
        /system/bin/toybox tr -d '\n'
    )"
    case "${frame_bytes}" in
      '' | *[!0-9]*)
        echo "Invalid screencap frame size: ${frame_name}" >&2
        exit 1
        ;;
    esac
    if [ "${png_header}" != "89504e470d0a1a0a" ] ||
      [ "${png_trailer}" != "0000000049454e44ae426082" ]; then
      echo "Incomplete screencap PNG: ${frame_name}" >&2
      exit 1
    fi
    if [ "${frame_bytes}" -gt "${maximum_frame_bytes}" ]; then
      echo \
        "Screencap frame exceeds ${maximum_frame_bytes} bytes: ${frame_name} (${frame_bytes})" \
        >&2
      exit 1
    fi
    if [ "$((total_bytes + frame_bytes))" -gt "${maximum_total_bytes}" ]; then
      echo \
        "Screencap sequence exceeds ${maximum_total_bytes} bytes at ${frame_name}" \
        >&2
      exit 1
    fi
    if ! mv "${partial_path}" "${frame_path}"; then
      echo "Unable to publish screencap frame: ${frame_name}" >&2
      exit 1
    fi
    current_partial_path=""
    if ! printf '%s\t%s\t%s\t%s\n' \
      "${frame_name}" \
      "${attempted_at}" \
      "${completed_at}" \
      "${frame_bytes}" \
      >>"${timestamps_file}"; then
      rm -f "${frame_path}"
      echo "Unable to append screencap manifest: ${frame_name}" >&2
      exit 1
    fi
    captured_frames=$((captured_frames + 1))
    total_bytes=$((total_bytes + frame_bytes))
    last_captured_at="${attempted_at}"
    if [ "${captured_frames}" -eq 1 ]; then
      first_captured_at="${attempted_at}"
      if ! mv "${armed_pending_file}" "${armed_file}"; then
        echo "Unable to publish the remote screencap arm marker" >&2
        exit 1
      fi
    fi
  else
    rm -f "${partial_path}"
    current_partial_path=""
  fi
  frame_index=$((frame_index + 1))
  /system/bin/toybox usleep "${sample_interval_microseconds}"
done

echo "captured_frames=${captured_frames}"
echo "captured_bytes=${total_bytes}"
if [ "${captured_frames}" -lt "${minimum_frames}" ]; then
  echo \
    "Screencap sequence has too few frames: ${captured_frames}, expected at least ${minimum_frames}" \
    >&2
  exit 1
fi
if ! awk \
  -v first="${first_captured_at}" \
  -v last="${last_captured_at}" \
  -v minimum="${minimum_duration_seconds}" \
  'BEGIN { exit !(last - first >= minimum) }'; then
  echo \
    "Screencap sequence did not span ${minimum_duration_seconds}s of successful captures" \
    >&2
  exit 1
fi
ANDROID_SCREENCAP
}

wait_for_screencap_arm() {
  local screencap_pid="$1"
  local remote_root="$2"
  local nonce="$3"
  local deadline=$((SECONDS + screencap_arm_timeout_seconds + 1))

  while ((SECONDS < deadline)); do
    if remote_screencap_marker_matches \
      "${remote_root}/screencap-armed" \
      "${nonce}"; then
      return 0
    fi
    if ! kill -0 "${screencap_pid}" >/dev/null 2>&1; then
      echo "Screencap sequence exited before capturing its HOME frame" >&2
      return 1
    fi
    sleep 0.05
  done
  echo "Timed out waiting for the first screencap HOME frame" >&2
  return 1
}

prepare_remote_screencap() {
  local remote_root="$1"
  local nonce="$2"

  if ! validate_remote_screencap_root "${remote_root}" ||
    ! validate_screencap_nonce "${nonce}"; then
    echo "Refusing invalid remote screencap preparation parameters" >&2
    return 1
  fi
  timeout "${adb_general_timeout_seconds}" \
    adb shell -T /system/bin/sh -s -- \
    "${remote_root}" \
    "${nonce}" \
    "${screencap_remote_maximum_bytes}" \
    "${screencap_remote_reserve_bytes}" <<'ANDROID_SCREENCAP_PREPARE'
set -eu

remote_root="$1"
nonce="$2"
maximum_bytes="$3"
reserve_bytes="$4"
umask 077
remote_root_created=0

rollback_remote_preparation() {
  preparation_status=$?

  trap - 0 HUP INT TERM
  if [ "${preparation_status}" -ne 0 ] &&
    [ "${remote_root_created}" -eq 1 ]; then
    rm -rf "${remote_root}" || true
  fi
  exit "${preparation_status}"
}

trap rollback_remote_preparation 0
trap 'exit 130' HUP INT TERM

test -r /proc/uptime
test -x /system/bin/screencap
/system/bin/toybox timeout -s 9 1 /system/bin/toybox true
/system/bin/toybox usleep 1
/system/bin/toybox xxd -p -l 8 /dev/null >/dev/null
/system/bin/toybox tail -c 1 /dev/null >/dev/null
/system/bin/toybox stat -c %s /dev/null >/dev/null
awk 1 /dev/null
available_kilobytes="$(
  /system/bin/toybox df -k /data/local/tmp |
    awk 'END { print $4 }'
)"
case "${available_kilobytes}" in
  '' | *[!0-9]*)
    echo "Unable to read free remote screencap storage" >&2
    exit 1
    ;;
esac
available_bytes=$((available_kilobytes * 1024))
required_bytes=$((maximum_bytes + reserve_bytes))
if [ "${available_bytes}" -lt "${required_bytes}" ]; then
  echo \
    "Insufficient remote screencap storage: ${available_bytes}, required ${required_bytes}" \
    >&2
  exit 1
fi
if ! mkdir "${remote_root}"; then
  echo "Remote screencap root already exists: ${remote_root}" >&2
  exit 1
fi
remote_root_created=1
mkdir "${remote_root}/screencap-frames"
for marker_name in armed stop abort done; do
  printf '%s\n' "${nonce}" \
    >"${remote_root}/screencap-${marker_name}.pending"
done
echo "remote_screencap_available_bytes=${available_bytes}"
ANDROID_SCREENCAP_PREPARE
}

pull_remote_screencap() {
  local remote_root="$1"
  local nonce="$2"
  local capture_directory="$3"
  local expected_width="$4"
  local expected_height="$5"
  local frames_directory="${capture_directory}/screencap-frames"
  local timestamps_file="${capture_directory}/screencap-timestamps.tsv"
  local staging_directory="${capture_directory}/.screencap-staging-${nonce}"
  local staging_frames="${staging_directory}/screencap-frames"
  local staging_timestamps="${staging_directory}/screencap-timestamps.tsv"
  local remote_status=""
  local status_nonce=""
  local status_code=""
  local status_frames=""
  local status_bytes=""
  local staged_frames=""
  local staged_bytes=""

  if ! validate_remote_screencap_root "${remote_root}" ||
    ! validate_screencap_nonce "${nonce}"; then
    echo "Refusing invalid remote screencap pull parameters" >&2
    return 1
  fi
  if ! remote_screencap_marker_matches \
    "${remote_root}/screencap-done" \
    "${nonce}"; then
    echo "Remote screencap sequence did not publish its done marker" >&2
    return 1
  fi
  remote_status="$(
    timeout "${adb_probe_timeout_seconds}" \
      adb shell cat "${remote_root}/screencap-status" |
      tr -d '\r\n'
  )"
  IFS=$'\t' read -r \
    status_nonce \
    status_code \
    status_frames \
    status_bytes <<<"${remote_status}"
  if [[ "${status_nonce}" != "${nonce}" ||
    "${status_code}" != "0" ||
    ! "${status_frames}" =~ ^[0-9]+$ ||
    ! "${status_bytes}" =~ ^[0-9]+$ ]]; then
    echo "Remote screencap sequence reported status: ${remote_status}" >&2
    return 1
  fi
  rm -rf -- "${staging_directory}"
  mkdir -p "${staging_frames}"
  timeout "${screencap_pull_timeout_seconds}" \
    adb pull \
    "${remote_root}/screencap-frames/." \
    "${staging_frames}/"
  timeout "${screencap_pull_timeout_seconds}" \
    adb pull \
    "${remote_root}/screencap-timestamps.tsv" \
    "${staging_timestamps}"
  validate_screencap_pngs \
    "${staging_timestamps}" \
    "${staging_frames}" \
    "${expected_width}" \
    "${expected_height}" \
    "${screencap_maximum_frame_duration_seconds}" \
    "${screencap_maximum_frame_bytes}"
  staged_frames="$(awk 'END { print NR }' "${staging_timestamps}")"
  staged_bytes="$(
    awk -F '\t' '{ total += $4 } END { printf "%.0f", total }' \
      "${staging_timestamps}"
  )"
  if [[ "${staged_frames}" != "${status_frames}" ||
    "${staged_bytes}" != "${status_bytes}" ]]; then
    echo \
      "Pulled screencap manifest disagrees with remote status: ${staged_frames}/${staged_bytes} vs ${status_frames}/${status_bytes}" \
      >&2
    return 1
  fi
  rm -rf -- "${frames_directory}"
  rm -f -- "${timestamps_file}"
  mv -- "${staging_frames}" "${frames_directory}"
  mv -- "${staging_timestamps}" "${timestamps_file}"
  rmdir -- "${staging_directory}"
}

validate_screencap_pngs() {
  local timestamps_file="$1"
  local frames_directory="$2"
  local expected_width="$3"
  local expected_height="$4"
  local maximum_frame_duration="$5"
  local maximum_frame_bytes="$6"

  timeout "${screencap_validation_timeout_seconds}" "${python_command}" - \
    "${timestamps_file}" \
    "${frames_directory}" \
    "${expected_width}" \
    "${expected_height}" \
    "${maximum_frame_duration}" \
    "${maximum_frame_bytes}" <<'PY'
from decimal import Decimal, InvalidOperation
from pathlib import Path
import re
import struct
import sys
import zlib

timestamps_path = Path(sys.argv[1])
frames_directory = Path(sys.argv[2])
expected_dimensions = (int(sys.argv[3]), int(sys.argv[4]))
maximum_frame_duration = Decimal(sys.argv[5])
maximum_frame_bytes = int(sys.argv[6])
frame_pattern = re.compile(r"frame-[0-9]{5}[.]png")


def fail(message: str) -> None:
    raise SystemExit(message)


def read_exact(source, count: int, frame_name: str) -> bytes:
    data = source.read(count)
    if len(data) != count:
        fail(f"Truncated PNG chunk in {frame_name}")
    return data


def validate_png(path: Path, expected_size: int) -> None:
    with path.open("rb") as source:
        if read_exact(source, 8, path.name) != b"\x89PNG\r\n\x1a\n":
            fail(f"Invalid PNG signature: {path.name}")
        dimensions = None
        saw_image_data = False
        saw_end = False
        while not saw_end:
            chunk_length = struct.unpack(">I", read_exact(source, 4, path.name))[0]
            chunk_type = read_exact(source, 4, path.name)
            remaining_bytes = expected_size - source.tell()
            if chunk_length > maximum_frame_bytes or chunk_length + 4 > remaining_bytes:
                fail(f"Invalid PNG chunk length: {path.name}")
            chunk_data = read_exact(source, chunk_length, path.name)
            expected_crc = struct.unpack(">I", read_exact(source, 4, path.name))[0]
            actual_crc = zlib.crc32(chunk_type)
            actual_crc = zlib.crc32(chunk_data, actual_crc) & 0xFFFFFFFF
            if actual_crc != expected_crc:
                fail(f"Invalid PNG checksum: {path.name}")
            if chunk_type == b"IHDR":
                if dimensions is not None or chunk_length != 13:
                    fail(f"Invalid PNG header: {path.name}")
                dimensions = struct.unpack(">II", chunk_data[:8])
            elif chunk_type == b"IDAT":
                saw_image_data = True
            elif chunk_type == b"IEND":
                if chunk_length != 0:
                    fail(f"Invalid PNG end chunk: {path.name}")
                saw_end = True
        if source.read(1):
            fail(f"Unexpected PNG trailing data: {path.name}")
    if dimensions != expected_dimensions or not saw_image_data:
        fail(
            f"Unexpected screencap frame {path.name}: "
            f"{dimensions}, expected {expected_dimensions}"
        )


records = []
previous_name = ""
previous_timestamp = None
for line_number, raw_line in enumerate(
    timestamps_path.read_text(encoding="utf-8").splitlines(),
    start=1,
):
    fields = raw_line.split("\t")
    if len(fields) != 4 or frame_pattern.fullmatch(fields[0]) is None:
        fail(f"Invalid screencap record at line {line_number}")
    frame_name, raw_started_at, raw_completed_at, raw_size = fields
    try:
        started_at = Decimal(raw_started_at)
        completed_at = Decimal(raw_completed_at)
    except InvalidOperation:
        fail(f"Invalid screencap timestamp at line {line_number}")
    if (
        not started_at.is_finite()
        or not completed_at.is_finite()
        or started_at < 0
        or completed_at < started_at
        or completed_at - started_at > maximum_frame_duration
    ):
        fail(f"Invalid screencap timestamp at line {line_number}")
    try:
        recorded_size = int(raw_size)
    except ValueError:
        fail(f"Invalid screencap frame size at line {line_number}")
    if (
        recorded_size <= 0
        or recorded_size > maximum_frame_bytes
        or str(recorded_size) != raw_size
    ):
        fail(f"Invalid screencap frame size at line {line_number}")
    if frame_name <= previous_name:
        fail(f"Non-monotonic screencap filename at line {line_number}")
    if previous_timestamp is not None and started_at <= previous_timestamp:
        fail(f"Non-monotonic screencap timestamp at line {line_number}")
    frame_path = frames_directory / frame_name
    if not frame_path.is_file():
        fail(f"Missing screencap frame: {frame_name}")
    if frame_path.stat().st_size != recorded_size:
        fail(f"Screencap frame size mismatch: {frame_name}")
    validate_png(frame_path, recorded_size)
    records.append(frame_name)
    previous_name = frame_name
    previous_timestamp = started_at

actual_frames = sorted(path.name for path in frames_directory.glob("frame-*.png"))
if actual_frames != records:
    fail("Screencap frame files do not exactly match the timestamp manifest")
if any(frames_directory.glob("*.partial")):
    fail("Partial screencap files remain in the source sequence")
print(f"validated_screencap_frames={len(records)}")
PY
}

finalize_screencap_evidence() {
  local capture_directory="$1"
  local display_size="$2"
  local record_size="$3"
  local frames_directory="${capture_directory}/screencap-frames"
  local timestamps_file="${capture_directory}/screencap-timestamps.tsv"
  local concat_file="${capture_directory}/screencap-concat.txt"
  local source_video="${capture_directory}/cold-start-screencap-source.mp4"
  local review_video="${capture_directory}/cold-start-screencap.mp4"
  local contact_sheet="${capture_directory}/screencap-contact-sheet.png"
  local source_frame_count=""
  local expected_display_width="${display_size%x*}"
  local expected_display_height="${display_size#*x}"
  local expected_record_width="${record_size%x*}"
  local expected_record_height="${record_size#*x}"
  local source_codec=""
  local source_duration=""
  local source_width=""
  local source_height=""
  local source_decoded_frames=""
  local review_duration_seconds=""
  local review_codec=""
  local review_duration=""
  local review_start_time=""
  local review_frame_rate=""
  local review_width=""
  local review_height=""
  local review_frames=""
  local expected_review_frames=0
  local contact_sheet_dimensions=""
  local source_capture_span=""
  local source_maximum_gap=""
  local source_total_bytes=""

  source_frame_count="$(awk 'END { print NR }' "${timestamps_file}")"
  if [[ ! "${source_frame_count}" =~ ^[0-9]+$ ]] ||
    ((source_frame_count < minimum_screencap_frames)); then
    echo "Invalid screencap source frame count: ${source_frame_count}" >&2
    return 1
  fi
  validate_screencap_pngs \
    "${timestamps_file}" \
    "${frames_directory}" \
    "${expected_display_width}" \
    "${expected_display_height}" \
    "${screencap_maximum_frame_duration_seconds}" \
    "${screencap_maximum_frame_bytes}"

  source_capture_span="$(
    awk -F '\t' '
      NR == 1 { first = $2 + 0 }
      END { printf "%.6f", ($2 + 0) - first }
    ' "${timestamps_file}"
  )"
  if [[ ! "${source_capture_span}" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
    ! awk \
      -v duration="${source_capture_span}" \
      -v minimum="${minimum_screencap_duration_seconds}" \
      'BEGIN { exit !(duration >= minimum) }'; then
    echo "Screencap source span is too short: ${source_capture_span}s" >&2
    return 1
  fi

  if ! awk -F '\t' \
    -v prefix='screencap-frames/' \
    -v maximum_gap="${screencap_maximum_gap_seconds}" '
    NR == 1 {
      previous_file = $1
      previous_time = $2 + 0
      count = 1
      next
    }
    {
      current_time = $2 + 0
      duration = current_time - previous_time
      if ($1 <= previous_file) {
        printf "Non-monotonic screencap filename: %s after %s\n",
          $1, previous_file > "/dev/stderr"
        invalid = 1
        exit
      }
      if (duration <= 0) {
        printf "Non-monotonic screencap timestamp: %.9fs at %s\n",
          duration, $1 > "/dev/stderr"
        invalid = 1
        exit
      }
      if (duration > maximum_gap) {
        printf "Screencap gap %.9fs exceeds %.9fs before %s\n",
          duration, maximum_gap, $1 > "/dev/stderr"
        invalid = 1
        exit
      }
      printf "file '\''%s%s'\''\n", prefix, previous_file
      printf "duration %.9f\n", duration
      previous_file = $1
      previous_time = current_time
      count++
    }
    END {
      if (invalid || count < 2) {
        exit 1
      }
      printf "file '\''%s%s'\''\n", prefix, previous_file
      print "duration 0.250000000"
      printf "file '\''%s%s'\''\n", prefix, previous_file
    }
  ' "${timestamps_file}" >"${concat_file}"; then
    echo "Unable to build a monotonic screencap timeline" >&2
    return 1
  fi
  source_maximum_gap="$(
    awk -F '\t' '
      NR == 1 {
        previous_time = $2 + 0
        maximum = 0
        next
      }
      {
        current_time = $2 + 0
        gap = current_time - previous_time
        if (gap > maximum) maximum = gap
        previous_time = current_time
      }
      END { printf "%.6f", maximum }
    ' "${timestamps_file}"
  )"
  source_total_bytes="$(
    awk -F '\t' '{ total += $4 } END { printf "%.0f", total }' \
      "${timestamps_file}"
  )"

  ffmpeg \
    -hide_banner \
    -loglevel error \
    -y \
    -f concat \
    -safe 0 \
    -i "${concat_file}" \
    -an \
    -vf "scale=${expected_record_width}:${expected_record_height}:flags=lanczos,setsar=1" \
    -c:v libx264 \
    -preset veryfast \
    -crf 15 \
    -pix_fmt yuv420p \
    -fps_mode vfr \
    -movflags +faststart \
    "${source_video}"

  source_codec="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=codec_name -of csv=p=0 "${source_video}"
  )"
  source_duration="$(
    ffprobe -v error -show_entries format=duration -of csv=p=0 "${source_video}"
  )"
  source_width="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=width -of csv=p=0 "${source_video}"
  )"
  source_height="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=height -of csv=p=0 "${source_video}"
  )"
  source_decoded_frames="$(
    ffprobe -v error -count_frames -select_streams v:0 \
      -show_entries stream=nb_read_frames -of csv=p=0 "${source_video}"
  )"
  if [[ "${source_codec}" != "h264" ||
    "${source_width}" != "${expected_record_width}" ||
    "${source_height}" != "${expected_record_height}" ]]; then
    echo \
      "Unexpected screencap source video: ${source_codec} ${source_width}x${source_height}" \
      >&2
    return 1
  fi
  if [[ ! "${source_decoded_frames}" =~ ^[0-9]+$ ]] ||
    ((source_decoded_frames != source_frame_count + 1)); then
    echo \
      "Unexpected screencap source frame count: ${source_decoded_frames}/$((source_frame_count + 1))" \
      >&2
    return 1
  fi
  if [[ ! "${source_duration}" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
    ! awk \
      -v duration="${source_duration}" \
      -v minimum="${minimum_screencap_duration_seconds}" \
      'BEGIN { exit !(duration >= minimum) }'; then
    echo "Screencap source video is too short: ${source_duration}s" >&2
    return 1
  fi

  review_duration_seconds="$(
    awk -v duration="${source_duration}" -v minimum="${minimum_evidence_seconds}" \
      'BEGIN {
        target = int(duration)
        if (duration > target) target++
        if (target < minimum) target = minimum
        print target
      }'
  )"
  if [[ ! "${review_duration_seconds}" =~ ^[0-9]+$ ]]; then
    echo "Unable to normalize screencap duration ${source_duration}s" >&2
    return 1
  fi
  ffmpeg \
    -hide_banner \
    -loglevel error \
    -y \
    -i "${source_video}" \
    -an \
    -vf "fps=${normalized_frame_rate},tpad=stop_mode=clone:stop_duration=${review_duration_seconds}" \
    -t "${review_duration_seconds}" \
    -c:v libx264 \
    -preset veryfast \
    -crf 15 \
    -pix_fmt yuv420p \
    -movflags +faststart \
    "${review_video}"

  ffprobe \
    -v error \
    -count_frames \
    -select_streams v:0 \
    -show_entries stream=codec_name,width,height,start_time,avg_frame_rate,nb_read_frames \
    -show_entries format=duration,size \
    -of default=noprint_wrappers=1 \
    "${review_video}" \
    >"${capture_directory}/screencap-video.txt"
  review_codec="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=codec_name -of csv=p=0 "${review_video}"
  )"
  review_duration="$(
    ffprobe -v error -show_entries format=duration -of csv=p=0 "${review_video}"
  )"
  review_start_time="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=start_time -of csv=p=0 "${review_video}"
  )"
  review_frame_rate="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=avg_frame_rate -of csv=p=0 "${review_video}"
  )"
  review_width="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=width -of csv=p=0 "${review_video}"
  )"
  review_height="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=height -of csv=p=0 "${review_video}"
  )"
  review_frames="$(
    ffprobe -v error -count_frames -select_streams v:0 \
      -show_entries stream=nb_read_frames -of csv=p=0 "${review_video}"
  )"
  expected_review_frames=$((review_duration_seconds * normalized_frame_rate))
  if [[ "${review_codec}" != "h264" ||
    "${review_frame_rate}" != "${normalized_frame_rate}/1" ||
    "${review_width}" != "${expected_record_width}" ||
    "${review_height}" != "${expected_record_height}" ||
    "${review_frames}" != "${expected_review_frames}" ]]; then
    echo \
      "Unexpected normalized screencap video contract: ${review_codec} ${review_width}x${review_height} ${review_frame_rate} ${review_frames}" \
      >&2
    return 1
  fi
  if ! awk -v duration="${review_duration}" -v target="${review_duration_seconds}" \
    'BEGIN { exit !(duration >= target - 0.05 && duration <= target + 0.05) }'; then
    echo "Unexpected normalized screencap duration: ${review_duration}s" >&2
    return 1
  fi
  if ! awk -v start="${review_start_time}" \
    'BEGIN { exit !(start >= -0.05 && start <= 0.05) }'; then
    echo "Unexpected normalized screencap start time: ${review_start_time}s" >&2
    return 1
  fi

  ffmpeg \
    -hide_banner \
    -loglevel error \
    -y \
    -i "${review_video}" \
    -vf "fps=${contact_sheet_samples}/${review_duration_seconds},scale=${contact_sheet_frame_width}:-2,tile=${contact_sheet_columns}x${contact_sheet_rows}" \
    -frames:v 1 \
    "${contact_sheet}"
  contact_sheet_dimensions="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=width,height -of csv=s=x:p=0 \
      "${contact_sheet}"
  )"
  if [[ "${contact_sheet_dimensions}" != "2700x3900" ]]; then
    echo "Unexpected screencap contact sheet size: ${contact_sheet_dimensions}" >&2
    return 1
  fi

  {
    echo "capture_hard_timeout_seconds=${screencap_hard_timeout_seconds}"
    echo "maximum_source_gap_seconds=${screencap_maximum_gap_seconds}"
    echo "maximum_frame_duration_seconds=${screencap_maximum_frame_duration_seconds}"
    echo "maximum_frame_bytes=${screencap_maximum_frame_bytes}"
    echo "maximum_sequence_bytes=${screencap_remote_maximum_bytes}"
    echo "source_timestamp_clock=android_proc_uptime_seconds"
    echo "source_timestamp_semantics=screencap_command_started_and_completed_at"
    echo "source_frames=${source_frame_count}"
    echo "source_total_bytes=${source_total_bytes}"
    echo "source_capture_span_seconds=${source_capture_span}"
    echo "source_observed_maximum_gap_seconds=${source_maximum_gap}"
    echo "source_video_duration_seconds=${source_duration}"
    echo "source_video_decoded_frames=${source_decoded_frames}"
    echo "source_video_sha256=$(sha256sum "${source_video}" | awk '{print $1}')"
    echo "normalized_duration_seconds=${review_duration}"
    echo "normalized_frame_rate=${review_frame_rate}"
    echo "normalized_decoded_frames=${review_frames}"
    echo "normalized_sha256=$(sha256sum "${review_video}" | awk '{print $1}')"
    echo "contact_sheet_samples=${contact_sheet_samples}"
    echo "contact_sheet_dimensions=${contact_sheet_dimensions}"
  } >"${capture_directory}/screencap.txt"

  rm -rf -- "${frames_directory}"
  rm -f -- "${concat_file}"
}

reap_finished_recorder() {
  local recorder_pid="$1"
  local recorder_log="$2"
  local recorder_status=0

  if wait "${recorder_pid}"; then
    recorder_status=0
  else
    recorder_status=$?
  fi
  active_recorder_pid=""
  if ((recorder_status != 0)); then
    sed 's/^/screenrecord: /' "${recorder_log}" >&2
  fi
  return "${recorder_status}"
}

read_remote_mdat_payload_offset() {
  local remote_video="$1"
  local probe_timeout_seconds="$2"

  timeout "${probe_timeout_seconds}" \
    adb exec-out dd if="${remote_video}" bs="${remote_header_probe_bytes}" count=1 2>/dev/null |
    "${python_command}" -c '
import sys

data = sys.stdin.buffer.read()
offset = 0
while offset + 8 <= len(data):
    size = int.from_bytes(data[offset:offset + 4], "big")
    box_type = data[offset + 4:offset + 8]
    header_size = 8
    if size == 1:
        if offset + 16 > len(data):
            break
        size = int.from_bytes(data[offset + 8:offset + 16], "big")
        header_size = 16
    elif size == 0:
        size = len(data) - offset
    if box_type == b"mdat":
        print(offset + header_size)
        raise SystemExit(0)
    if size < header_size:
        break
    offset += size
raise SystemExit(1)
'
}

wait_for_screenrecord() {
  local recorder_pid="$1"
  local recorder_log="$2"
  local remote_video="$3"
  local remote_pid=""
  local confirmed_remote_pid=""
  local probe_status=0
  local recorded_bytes=""
  local refreshed_recorded_bytes=""
  local mdat_payload_offset=""
  local deadline=$((SECONDS + screenrecord_startup_timeout_seconds + 1))
  local remaining_seconds=0
  local command_timeout_seconds=0
  while ((SECONDS < deadline)); do
    remaining_seconds=$((deadline - SECONDS))
    command_timeout_seconds="${adb_probe_timeout_seconds}"
    if ((remaining_seconds < command_timeout_seconds)); then
      command_timeout_seconds="${remaining_seconds}"
    fi
    if remote_pid="$(
      probe_screenrecord_processes "${command_timeout_seconds}"
    )"; then
      :
    else
      probe_status=$?
      if ((probe_status == 2)); then
        return 1
      fi
      remote_pid=""
    fi
    if [[ "${remote_pid}" =~ ^[0-9]+$ ]]; then
      active_remote_recorder_pid="${remote_pid}"
      remaining_seconds=$((deadline - SECONDS))
      command_timeout_seconds="${adb_probe_timeout_seconds}"
      if ((remaining_seconds < command_timeout_seconds)); then
        command_timeout_seconds="${remaining_seconds}"
      fi
      if ((command_timeout_seconds > 0)); then
        recorded_bytes="$(
          timeout "${command_timeout_seconds}" \
            adb shell stat -c %s "${remote_video}" 2>/dev/null |
            tr -d '\r' ||
            true
        )"
      fi
      remaining_seconds=$((deadline - SECONDS))
      command_timeout_seconds="${adb_probe_timeout_seconds}"
      if ((remaining_seconds < command_timeout_seconds)); then
        command_timeout_seconds="${remaining_seconds}"
      fi
      if [[ "${recorded_bytes}" =~ ^[0-9]+$ ]] &&
        ((command_timeout_seconds > 0)); then
        if mdat_payload_offset="$(
          read_remote_mdat_payload_offset \
            "${remote_video}" \
            "${command_timeout_seconds}"
        )"; then
          if [[ "${mdat_payload_offset}" =~ ^[0-9]+$ ]]; then
            remaining_seconds=$((deadline - SECONDS))
            command_timeout_seconds="${adb_probe_timeout_seconds}"
            if ((remaining_seconds < command_timeout_seconds)); then
              command_timeout_seconds="${remaining_seconds}"
            fi
            if ((command_timeout_seconds <= 0)); then
              continue
            fi
            refreshed_recorded_bytes="$(
              timeout "${command_timeout_seconds}" \
                adb shell stat -c %s "${remote_video}" 2>/dev/null |
                tr -d '\r' ||
                true
            )"
            if [[ "${refreshed_recorded_bytes}" =~ ^[0-9]+$ ]] &&
              ((refreshed_recorded_bytes < recorded_bytes)); then
              echo "screenrecord size regressed while its muxer became ready" >&2
              return 1
            fi
            if [[ "${refreshed_recorded_bytes}" =~ ^[0-9]+$ ]] &&
              ((refreshed_recorded_bytes >= mdat_payload_offset)); then
              remaining_seconds=$((deadline - SECONDS))
              command_timeout_seconds="${adb_probe_timeout_seconds}"
              if ((remaining_seconds < command_timeout_seconds)); then
                command_timeout_seconds="${remaining_seconds}"
              fi
              if ((command_timeout_seconds <= 0)); then
                continue
              fi
              if confirmed_remote_pid="$(
                probe_screenrecord_processes "${command_timeout_seconds}"
              )"; then
                :
              else
                probe_status=$?
                if ((probe_status == 2)); then
                  return 1
                fi
                echo "screenrecord disappeared while its muxer became ready" >&2
                return 1
              fi
              if [[ "${confirmed_remote_pid}" != "${remote_pid}" ]]; then
                echo "screenrecord PID changed while its muxer became ready: ${remote_pid} -> ${confirmed_remote_pid}" >&2
                return 1
              fi
              if ! kill -0 "${recorder_pid}" >/dev/null 2>&1; then
                reap_finished_recorder "${recorder_pid}" "${recorder_log}" || true
                echo "screenrecord exited while its muxer became ready" >&2
                return 1
              fi
              if ((SECONDS >= deadline)); then
                echo "screenrecord muxer became ready after its startup deadline" >&2
                return 1
              fi
              screenrecord_ready_bytes="${refreshed_recorded_bytes}"
              screenrecord_mdat_payload_offset="${mdat_payload_offset}"
              if ((refreshed_recorded_bytes > mdat_payload_offset)); then
                screenrecord_frame_bootstrap="natural-sample"
              else
                screenrecord_frame_bootstrap="muxer-header"
              fi
              return 0
            fi
          fi
        fi
      fi
    elif [[ -n "${remote_pid}" ]]; then
      echo "Expected one screenrecord process, found: ${remote_pid}" >&2
      return 1
    fi
    if ! kill -0 "${recorder_pid}" >/dev/null 2>&1; then
      reap_finished_recorder "${recorder_pid}" "${recorder_log}" || true
      echo "screenrecord exited before becoming ready" >&2
      return 1
    fi
    sleep 0.25
  done
  sed 's/^/screenrecord: /' "${recorder_log}" >&2
  echo "Timed out waiting for the screenrecord muxer header" >&2
  return 1
}

wait_for_screenrecord_growth_after_launch() {
  local recorder_pid="$1"
  local recorder_log="$2"
  local remote_video="$3"
  local expected_remote_pid="$4"
  local baseline_bytes="$5"
  local overall_deadline="$6"
  local remote_pid=""
  local confirmed_remote_pid=""
  local probe_status=0
  local recorded_bytes=""
  local deadline=$((SECONDS + screenrecord_post_launch_sample_timeout_seconds + 1))
  local remaining_seconds=0
  local command_timeout_seconds=0

  if [[ ! "${expected_remote_pid}" =~ ^[0-9]+$ ]] ||
    [[ ! "${baseline_bytes}" =~ ^[0-9]+$ ]] ||
    [[ ! "${overall_deadline}" =~ ^[0-9]+$ ]]; then
    echo "Invalid screenrecord post-launch growth baseline" >&2
    return 1
  fi
  if ((overall_deadline < deadline)); then
    deadline="${overall_deadline}"
  fi
  while ((SECONDS < deadline)); do
    remaining_seconds=$((deadline - SECONDS))
    command_timeout_seconds="${adb_probe_timeout_seconds}"
    if ((remaining_seconds < command_timeout_seconds)); then
      command_timeout_seconds="${remaining_seconds}"
    fi
    if remote_pid="$(
      probe_screenrecord_processes "${command_timeout_seconds}"
    )"; then
      :
    else
      probe_status=$?
      if ((probe_status == 2)); then
        return 1
      fi
      echo "screenrecord disappeared after the cold launch" >&2
      return 1
    fi
    if [[ "${remote_pid}" != "${expected_remote_pid}" ]]; then
      echo "Unexpected screenrecord PID after the cold launch: ${remote_pid}" >&2
      return 1
    fi
    if ! kill -0 "${recorder_pid}" >/dev/null 2>&1; then
      reap_finished_recorder "${recorder_pid}" "${recorder_log}" || true
      echo "screenrecord exited after the cold launch" >&2
      return 1
    fi
    remaining_seconds=$((deadline - SECONDS))
    command_timeout_seconds="${adb_probe_timeout_seconds}"
    if ((remaining_seconds < command_timeout_seconds)); then
      command_timeout_seconds="${remaining_seconds}"
    fi
    if ((command_timeout_seconds > 0)); then
      recorded_bytes="$(
        timeout "${command_timeout_seconds}" \
          adb shell stat -c %s "${remote_video}" 2>/dev/null |
          tr -d '\r' ||
          true
      )"
    fi
    if [[ "${recorded_bytes}" =~ ^[0-9]+$ ]]; then
      if ((recorded_bytes < baseline_bytes)); then
        echo "screenrecord size regressed after the cold launch" >&2
        return 1
      fi
      if ((recorded_bytes > baseline_bytes)); then
        remaining_seconds=$((deadline - SECONDS))
        command_timeout_seconds="${adb_probe_timeout_seconds}"
        if ((remaining_seconds < command_timeout_seconds)); then
          command_timeout_seconds="${remaining_seconds}"
        fi
        if ((command_timeout_seconds <= 0)); then
          break
        fi
        if confirmed_remote_pid="$(
          probe_screenrecord_processes "${command_timeout_seconds}"
        )"; then
          :
        else
          probe_status=$?
          if ((probe_status == 2)); then
            return 1
          fi
          echo "screenrecord disappeared after encoding its launch sample" >&2
          return 1
        fi
        if [[ "${confirmed_remote_pid}" != "${expected_remote_pid}" ]]; then
          echo "screenrecord PID changed after encoding its launch sample: ${confirmed_remote_pid}" >&2
          return 1
        fi
        if ! kill -0 "${recorder_pid}" >/dev/null 2>&1; then
          reap_finished_recorder "${recorder_pid}" "${recorder_log}" || true
          echo "screenrecord exited after encoding its launch sample" >&2
          return 1
        fi
        if ((SECONDS >= deadline)); then
          break
        fi
        screenrecord_post_launch_bytes="${recorded_bytes}"
        return 0
      fi
    fi
    sleep 0.25
  done
  sed 's/^/screenrecord: /' "${recorder_log}" >&2
  echo "Timed out waiting for an encoded sample after the cold launch" >&2
  return 1
}

wait_for_configured_onboarding() {
  local capture_directory="$1"
  local remote_window="$2"
  local recorder_pid="$3"
  local recording_ready_at="$4"
  local recorder_log="$5"
  local uiautomator_log="${capture_directory}/uiautomator.txt"
  local window_file="${capture_directory}/window.xml"
  local deadline=$((recording_ready_at + onboarding_deadline_seconds + 1))
  local attempt=0
  local remaining_seconds=0
  local command_timeout_seconds=0

  : >"${uiautomator_log}"
  while ((SECONDS < deadline)); do
    attempt=$((attempt + 1))
    echo "attempt=${attempt}" >>"${uiautomator_log}"
    timeout "${adb_probe_timeout_seconds}" \
      adb shell rm -f "${remote_window}" >/dev/null 2>&1 ||
      true
    remaining_seconds=$((deadline - SECONDS))
    command_timeout_seconds="${uiautomator_command_timeout_seconds}"
    if ((remaining_seconds < command_timeout_seconds)); then
      command_timeout_seconds="${remaining_seconds}"
    fi
    if ((command_timeout_seconds > 0)) &&
      timeout "${command_timeout_seconds}" \
        adb shell uiautomator dump "${remote_window}" >>"${uiautomator_log}" 2>&1; then
      remaining_seconds=$((deadline - SECONDS))
      command_timeout_seconds="${uiautomator_command_timeout_seconds}"
      if ((remaining_seconds < command_timeout_seconds)); then
        command_timeout_seconds="${remaining_seconds}"
      fi
      if ((command_timeout_seconds > 0)) &&
        timeout "${command_timeout_seconds}" \
          adb pull "${remote_window}" "${window_file}" >>"${uiautomator_log}" 2>&1; then
        if grep -Fq "${configuration_unavailable_message}" "${window_file}"; then
          echo "Brand evidence reached the unavailable-configuration screen" >&2
          return 1
        fi
        if grep -Fq "${intro_accessibility_label}" "${window_file}"; then
          echo "intro" >"${capture_directory}/post-launch-state.txt"
          timeout "${adb_probe_timeout_seconds}" \
            adb shell rm -f "${remote_window}" >/dev/null 2>&1 ||
            true
          return 0
        fi
        if grep -Fq "${landing_title}" "${window_file}" &&
          grep -Fq "${landing_sign_in}" "${window_file}"; then
          echo "onboarding-landing" >"${capture_directory}/post-launch-state.txt"
          timeout "${adb_probe_timeout_seconds}" \
            adb shell rm -f "${remote_window}" >/dev/null 2>&1 ||
            true
          return 0
        fi
      fi
    fi
    if ! kill -0 "${recorder_pid}" >/dev/null 2>&1; then
      reap_finished_recorder "${recorder_pid}" "${recorder_log}" || true
      echo "screenrecord ended before a configured onboarding surface was observed" >&2
      return 1
    fi
    sleep 1
  done

  echo "Brand evidence did not reach a configured onboarding surface before the deadline" >&2
  return 1
}

wait_for_remote_recorder_exit() {
  local remote_recorder_pid="$1"
  local timeout_seconds="$2"
  # SECONDS has integer granularity. The extra second guarantees that callers
  # receive at least the requested observation window.
  local deadline=$((SECONDS + timeout_seconds + 1))
  local remote_state=0
  local remaining_seconds=0
  local command_timeout_seconds=0

  while ((SECONDS < deadline)); do
    remaining_seconds=$((deadline - SECONDS))
    command_timeout_seconds="${adb_probe_timeout_seconds}"
    if ((remaining_seconds < command_timeout_seconds)); then
      command_timeout_seconds="${remaining_seconds}"
    fi
    if remote_recorder_is_running \
      "${remote_recorder_pid}" \
      "${command_timeout_seconds}"; then
      remote_state=0
    else
      remote_state=$?
      if ((remote_state == 1)); then
        return 0
      fi
    fi
    sleep 0.25
  done
  if ((remote_state == 0)); then
    return 1
  fi
  return 2
}

reap_local_recorder() {
  local recorder_pid="$1"

  reap_bounded_local_process \
    "${recorder_pid}" \
    "${screenrecord_host_reap_seconds}"
}

screenrecord_output_is_finalized() {
  local recorder_log="$1"
  local remote_video="$2"
  local media_scanner_command="Executing: /system/bin/am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://${remote_video}"

  ! grep -Fq "Encoder failed" "${recorder_log}" &&
    grep -Eq 'Encoder stopping; recorded [0-9]+ frames in [0-9]+ seconds' \
      "${recorder_log}" &&
    grep -Fq "Stopping encoder and muxer" "${recorder_log}" &&
    grep -Fq "${media_scanner_command}" "${recorder_log}" &&
    grep -Fq \
      "Broadcasting: Intent { act=android.intent.action.MEDIA_SCANNER_SCAN_FILE" \
      "${recorder_log}"
}

finish_screenrecord() {
  local recorder_pid="$1"
  local remote_recorder_pid="$2"
  local recorder_log="$3"
  local remote_video="$4"
  local recorder_status=0
  local forced_stop=0
  local remote_state=0
  local remote_stopped=0
  local signal_status=0

  if remote_recorder_is_running "${remote_recorder_pid}"; then
    remote_state=0
  else
    remote_state=$?
  fi
  if ((remote_state == 1)); then
    remote_stopped=1
  else
    if signal_remote_recorder "${remote_recorder_pid}" 2; then
      signal_status=0
    else
      signal_status=$?
      if ((signal_status == 1)); then
        remote_stopped=1
      fi
    fi
    if ((remote_stopped == 0)); then
      if wait_for_remote_recorder_exit \
        "${remote_recorder_pid}" \
        "${screenrecord_graceful_stop_seconds}"; then
        remote_stopped=1
      else
        if signal_remote_recorder "${remote_recorder_pid}" 2; then
          forced_stop=1
        else
          signal_status=$?
          if ((signal_status == 1)); then
            remote_stopped=1
          fi
        fi
      fi
    fi
    if ((remote_stopped == 0)); then
      if wait_for_remote_recorder_exit \
        "${remote_recorder_pid}" \
        "${screenrecord_forced_stop_seconds}"; then
        remote_stopped=1
      fi
    fi
    if ((remote_stopped == 0)); then
      if signal_remote_recorder "${remote_recorder_pid}" 9; then
        forced_stop=1
      else
        signal_status=$?
        if ((signal_status == 1)); then
          remote_stopped=1
        fi
      fi
      if ((remote_stopped == 0)); then
        if wait_for_remote_recorder_exit \
          "${remote_recorder_pid}" \
          "${screenrecord_forced_stop_seconds}"; then
          remote_stopped=1
        fi
      fi
    fi
  fi
  if reap_local_recorder "${recorder_pid}"; then
    recorder_status=0
  else
    recorder_status=$?
  fi
  active_recorder_pid=""
  if ((remote_stopped != 0)); then
    active_remote_recorder_pid=""
  fi
  if ((remote_stopped == 0)); then
    sed 's/^/screenrecord: /' "${recorder_log}" >&2
    echo "Unable to verify that screenrecord stopped" >&2
    return 1
  fi
  if ((forced_stop != 0)); then
    sed 's/^/screenrecord: /' "${recorder_log}" >&2
    echo "screenrecord required a forced shutdown" >&2
    return 1
  fi
  if ((recorder_status != 0)); then
    sed 's/^/screenrecord: /' "${recorder_log}" >&2
    echo "screenrecord did not stop cleanly" >&2
    return 1
  fi
  if ! screenrecord_output_is_finalized "${recorder_log}" "${remote_video}"; then
    sed 's/^/screenrecord: /' "${recorder_log}" >&2
    echo "screenrecord did not report a finalized MP4 before shutdown" >&2
    return 1
  fi
}

{
  echo "requested_api=${api_level}"
  echo "actual_api=${actual_api}"
  echo "device=$(timeout "${adb_general_timeout_seconds}" adb shell getprop ro.product.model | tr -d '\r')"
  echo "build_fingerprint=$(timeout "${adb_general_timeout_seconds}" adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "apk_sha256=$(sha256sum "${apk_path}" | awk '{print $1}')"
  echo "ffmpeg_version=$(ffmpeg -version | sed -n '1p')"
} >"${evidence_root}/device.txt"

timeout "${adb_general_timeout_seconds}" adb shell settings put system accelerometer_rotation 0
timeout "${adb_general_timeout_seconds}" adb shell settings put system user_rotation 0
timeout "${adb_general_timeout_seconds}" adb shell settings put secure immersive_mode_confirmations confirmed
immersive_mode_confirmations="$(
  timeout "${adb_general_timeout_seconds}" \
    adb shell settings get secure immersive_mode_confirmations |
    tr -d '\r'
)"
if [[ "${immersive_mode_confirmations}" != "confirmed" ]]; then
  echo "Unable to suppress the emulator immersive-mode education overlay" >&2
  exit 1
fi

for profile in "${density_profiles[@]}"; do
  IFS=":" read -r density_name density_value display_size record_size <<<"${profile}"
  case "${density_name}" in
    mdpi | xhdpi | xxxhdpi) ;;
    *)
      echo "Unexpected density profile: ${density_name}" >&2
      exit 1
      ;;
  esac
  capture_directory="${evidence_root}/${density_name}"
  remote_video="/sdcard/kwabor-brand-002-api-${api_level}-${density_name}.mp4"
  remote_window="/sdcard/kwabor-brand-002-api-${api_level}-${density_name}.xml"
  screencap_nonce="$(
    printf '%s:%s:%s:%s:%s\n' \
      "${EPOCHREALTIME}" \
      "$$" \
      "${RANDOM}" \
      "${api_level}" \
      "${density_name}" |
      sha256sum |
      awk '{ print substr($1, 1, 16) }'
  )"
  if ! validate_screencap_nonce "${screencap_nonce}"; then
    echo "Unable to generate a valid screencap nonce" >&2
    exit 1
  fi
  remote_screencap_root="/data/local/tmp/kwabor-brand-002-api-${api_level}-${density_name}-${screencap_nonce}"
  raw_video="${capture_directory}/cold-start-raw.mp4"
  local_video="${capture_directory}/cold-start.mp4"
  recorder_log="${capture_directory}/screenrecord.txt"
  screencap_log="${capture_directory}/screencap-capture.log"
  screenrecord_ready_bytes=""
  screenrecord_mdat_payload_offset=""
  screenrecord_frame_bootstrap=""
  screenrecord_post_launch_bytes=""
  screencap_pid=""

  mkdir -p "${capture_directory}"
  timeout "${adb_general_timeout_seconds}" \
    adb uninstall "${package_name}" >/dev/null 2>&1 ||
    true
  timeout 120 adb install --no-streaming "${apk_path}" |
    tee "${capture_directory}/install.txt"
  apply_display_profile "${display_size}" "${density_value}"
  assert_display_profile "${display_size}" "${density_value}"
  timeout "${adb_general_timeout_seconds}" adb shell am force-stop "${package_name}"
  timeout "${adb_general_timeout_seconds}" adb shell input keyevent KEYCODE_HOME
  sleep 1
  timeout "${adb_general_timeout_seconds}" adb shell rm -f "${remote_video}"
  timeout "${adb_general_timeout_seconds}" adb shell rm -f "${remote_window}"
  stale_recorder_processes=""
  stale_probe_status=0
  if stale_recorder_processes="$(
    probe_screenrecord_processes "${adb_probe_timeout_seconds}"
  )"; then
    echo "A stale screenrecord process is already running: ${stale_recorder_processes}" >&2
    exit 1
  else
    stale_probe_status=$?
    if ((stale_probe_status == 2)); then
      exit 1
    fi
  fi
  adb shell screenrecord \
    --verbose \
    --size "${record_size}" \
    --bit-rate 4000000 \
    --time-limit "${screenrecord_time_limit_seconds}" \
    "${remote_video}" \
    >"${recorder_log}" 2>&1 &
  recorder_pid=$!
  active_recorder_pid="${recorder_pid}"

  wait_for_screenrecord \
    "${recorder_pid}" \
    "${recorder_log}" \
    "${remote_video}"
  remote_recorder_pid="${active_remote_recorder_pid}"
  recording_ready_at="${SECONDS}"
  active_screencap_remote_root="${remote_screencap_root}"
  active_screencap_nonce="${screencap_nonce}"
  active_screencap_started=0
  prepare_remote_screencap \
    "${remote_screencap_root}" \
    "${screencap_nonce}"
  active_screencap_started=1
  capture_screencap_frames \
    "${remote_screencap_root}" \
    "${screencap_nonce}" \
    >"${screencap_log}" 2>&1 &
  screencap_pid=$!
  active_screencap_pid="${screencap_pid}"
  if ! wait_for_screencap_arm \
    "${screencap_pid}" \
    "${remote_screencap_root}" \
    "${screencap_nonce}"; then
    sed 's/^/screencap: /' "${screencap_log}" >&2
    exit 1
  fi
  assert_display_profile "${display_size}" "${density_value}"
  timeout "${onboarding_deadline_seconds}" \
    adb shell am start -S -W -n "${activity_name}" |
    tee "${capture_directory}/activity-start.txt"
  if ! grep -Fq "Status: ok" "${capture_directory}/activity-start.txt"; then
    echo "MainActivity did not report a successful cold start" >&2
    exit 1
  fi
  wait_for_screenrecord_growth_after_launch \
    "${recorder_pid}" \
    "${recorder_log}" \
    "${remote_video}" \
    "${remote_recorder_pid}" \
    "${screenrecord_ready_bytes}" \
    "$((recording_ready_at + onboarding_deadline_seconds + 1))"
  assert_display_profile "${display_size}" "${density_value}"
  wait_for_configured_onboarding \
    "${capture_directory}" \
    "${remote_window}" \
    "${recorder_pid}" \
    "${recording_ready_at}" \
    "${recorder_log}"
  recording_elapsed_seconds=$((SECONDS - recording_ready_at))
  recording_hold_seconds="${post_launch_hold_seconds}"
  if ((recording_elapsed_seconds + recording_hold_seconds < minimum_evidence_seconds)); then
    recording_hold_seconds=$((minimum_evidence_seconds - recording_elapsed_seconds))
  fi
  sleep "${recording_hold_seconds}"
  request_remote_screencap_control \
    "${remote_screencap_root}/screencap-stop.pending" \
    "${remote_screencap_root}/screencap-stop" \
    "${screencap_nonce}"
  if wait "${screencap_pid}"; then
    active_screencap_pid=""
    if ! remote_screencap_marker_matches \
      "${remote_screencap_root}/screencap-done" \
      "${screencap_nonce}"; then
      echo "Remote screencap sequence exited without its done marker" >&2
      exit 1
    fi
  else
    active_screencap_pid=""
    sed 's/^/screencap: /' "${screencap_log}" >&2
    echo "Unable to capture the bounded composited-display sequence" >&2
    exit 1
  fi
  finish_screenrecord \
    "${recorder_pid}" \
    "${remote_recorder_pid}" \
    "${recorder_log}" \
    "${remote_video}"
  recording_wall_seconds=$((SECONDS - recording_ready_at))
  if ((recording_wall_seconds < minimum_recording_wall_seconds)); then
    echo "Cold-start recorder stopped too early: ${recording_wall_seconds}s wall time" >&2
    exit 1
  fi
  pull_remote_screencap \
    "${remote_screencap_root}" \
    "${screencap_nonce}" \
    "${capture_directory}" \
    "${display_size%x*}" \
    "${display_size#*x}"
  remove_remote_screencap_root "${remote_screencap_root}"
  active_screencap_remote_root=""
  active_screencap_nonce=""
  active_screencap_started=0
  timeout "${adb_general_timeout_seconds}" adb pull "${remote_video}" "${raw_video}"
  timeout "${adb_general_timeout_seconds}" adb shell rm -f "${remote_video}"
  assert_display_profile "${display_size}" "${density_value}"
  finalize_screencap_evidence \
    "${capture_directory}" \
    "${display_size}" \
    "${record_size}"

  resumed_activity="$(
    timeout "${adb_general_timeout_seconds}" adb shell dumpsys activity activities |
      grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' ||
      true
  )"
  printf '%s\n' "${resumed_activity}" >"${capture_directory}/resumed-activity.txt"
  if [[ "${resumed_activity}" != *"${package_name}"* ]]; then
    echo "Kwabor MainActivity is not resumed after the cold start" >&2
    exit 1
  fi

  process_id="$(
    timeout "${adb_general_timeout_seconds}" adb shell pidof "${package_name}" |
      tr -d '\r'
  )"
  printf '%s\n' "${process_id}" >"${capture_directory}/process.txt"
  if [[ -z "${process_id}" ]]; then
    echo "Kwabor process is not alive after the cold start" >&2
    exit 1
  fi

  ffprobe \
    -v error \
    -count_frames \
    -select_streams v:0 \
    -show_entries stream=codec_name,width,height,start_time,duration,nb_read_frames \
    -show_entries format=duration,size \
    -of default=noprint_wrappers=1 \
    "${raw_video}" \
    >"${capture_directory}/raw-video.txt"
  raw_video_codec="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=codec_name -of csv=p=0 "${raw_video}"
  )"
  raw_video_duration="$(
    ffprobe -v error -show_entries format=duration -of csv=p=0 "${raw_video}"
  )"
  raw_video_width="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=width -of csv=p=0 "${raw_video}"
  )"
  raw_video_height="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=height -of csv=p=0 "${raw_video}"
  )"
  raw_video_frames="$(
    ffprobe -v error -count_frames -select_streams v:0 \
      -show_entries stream=nb_read_frames -of csv=p=0 "${raw_video}"
  )"
  raw_video_size="$(
    ffprobe -v error -show_entries format=size -of csv=p=0 "${raw_video}"
  )"
  expected_width="${record_size%x*}"
  expected_height="${record_size#*x}"
  if [[ "${raw_video_codec}" != "h264" ]]; then
    echo "Unexpected raw recording codec: ${raw_video_codec}" >&2
    exit 1
  fi
  if [[ "${raw_video_width}" != "${expected_width}" ||
    "${raw_video_height}" != "${expected_height}" ]]; then
    echo "Unexpected raw recording size: ${raw_video_width}x${raw_video_height}, expected ${record_size}" >&2
    exit 1
  fi
  if [[ ! "${raw_video_frames}" =~ ^[0-9]+$ ]] ||
    ((raw_video_frames < minimum_raw_decoded_frames)); then
    echo "Raw cold-start recording has too few decoded frames: ${raw_video_frames}" >&2
    exit 1
  fi
  if [[ ! "${raw_video_size}" =~ ^[0-9]+$ ]] || ((raw_video_size == 0)); then
    echo "Raw cold-start recording is empty" >&2
    exit 1
  fi
  if [[ ! "${raw_video_duration}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    echo "Raw cold-start recording has an invalid duration: ${raw_video_duration}" >&2
    exit 1
  fi
  if ! awk -v duration="${raw_video_duration}" \
    'BEGIN { exit !(duration > 0) }'; then
    echo "Raw cold-start recording has a non-positive duration: ${raw_video_duration}" >&2
    exit 1
  fi
  # ActivityManager WaitTime and screenrecord PTS use different clocks under an
  # accelerated emulator. Preserve both metrics; the armed-muxer barrier,
  # post-launch sample growth, and in-recording UI assertion prove sequencing
  # without comparing those clocks.
  launch_wait_ms="$(
    awk -F': ' '$1 == "WaitTime" { print $2 }' \
      "${capture_directory}/activity-start.txt" |
      tr -d '\r'
  )"
  if [[ ! "${launch_wait_ms}" =~ ^[0-9]+$ ]]; then
    echo "MainActivity did not report a numeric WaitTime" >&2
    exit 1
  fi
  normalized_evidence_seconds="$(
    awk -v duration="${raw_video_duration}" -v minimum="${minimum_evidence_seconds}" \
      'BEGIN {
        target = int(duration)
        if (duration > target) target++
        if (target < minimum) target = minimum
        print target
      }'
  )"
  if [[ ! "${normalized_evidence_seconds}" =~ ^[0-9]+$ ]]; then
    echo "Unable to derive a normalized evidence duration from ${raw_video_duration}s" >&2
    exit 1
  fi

  ffmpeg \
    -hide_banner \
    -loglevel error \
    -y \
    -i "${raw_video}" \
    -an \
    -vf "fps=${normalized_frame_rate},tpad=stop_mode=clone:stop_duration=${normalized_evidence_seconds}" \
    -t "${normalized_evidence_seconds}" \
    -c:v libx264 \
    -preset veryfast \
    -crf 18 \
    -pix_fmt yuv420p \
    -movflags +faststart \
    "${local_video}"

  ffprobe \
    -v error \
    -count_frames \
    -select_streams v:0 \
    -show_entries stream=codec_name,width,height,start_time,avg_frame_rate,nb_read_frames \
    -show_entries format=duration,size \
    -of default=noprint_wrappers=1 \
    "${local_video}" \
    >"${capture_directory}/video.txt"
  video_codec="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=codec_name -of csv=p=0 "${local_video}"
  )"
  video_duration="$(
    ffprobe -v error -show_entries format=duration -of csv=p=0 "${local_video}"
  )"
  video_start_time="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=start_time -of csv=p=0 "${local_video}"
  )"
  video_frame_rate="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=avg_frame_rate -of csv=p=0 "${local_video}"
  )"
  video_width="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=width -of csv=p=0 "${local_video}"
  )"
  video_height="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=height -of csv=p=0 "${local_video}"
  )"
  video_frames="$(
    ffprobe -v error -count_frames -select_streams v:0 \
      -show_entries stream=nb_read_frames -of csv=p=0 "${local_video}"
  )"
  expected_normalized_frames=$((normalized_evidence_seconds * normalized_frame_rate))
  if [[ "${video_codec}" != "h264" ]]; then
    echo "Unexpected normalized recording codec: ${video_codec}" >&2
    exit 1
  fi
  if ! awk -v duration="${video_duration}" -v target="${normalized_evidence_seconds}" \
    'BEGIN { exit !(duration >= target - 0.05 && duration <= target + 0.05) }'; then
    echo "Unexpected normalized recording duration: ${video_duration}s" >&2
    exit 1
  fi
  if ! awk -v start="${video_start_time}" \
    'BEGIN { exit !(start >= -0.05 && start <= 0.05) }'; then
    echo "Unexpected normalized recording start time: ${video_start_time}s" >&2
    exit 1
  fi
  if [[ "${video_frame_rate}" != "${normalized_frame_rate}/1" ]]; then
    echo "Unexpected normalized frame rate: ${video_frame_rate}" >&2
    exit 1
  fi
  if [[ "${video_width}" != "${expected_width}" ||
    "${video_height}" != "${expected_height}" ]]; then
    echo "Unexpected normalized recording size: ${video_width}x${video_height}, expected ${record_size}" >&2
    exit 1
  fi
  if [[ "${video_frames}" != "${expected_normalized_frames}" ]]; then
    echo "Unexpected normalized frame count: ${video_frames}, expected ${expected_normalized_frames}" >&2
    exit 1
  fi

  {
    echo "screenrecord_time_limit_seconds=${screenrecord_time_limit_seconds}"
    echo "screenrecord_ready_bytes=${screenrecord_ready_bytes}"
    echo "screenrecord_mdat_payload_offset=${screenrecord_mdat_payload_offset}"
    echo "screenrecord_frame_bootstrap=${screenrecord_frame_bootstrap}"
    echo "screenrecord_post_launch_bytes=${screenrecord_post_launch_bytes}"
    echo "recording_wall_seconds=${recording_wall_seconds}"
    echo "launch_wait_ms=${launch_wait_ms}"
    echo "raw_duration_seconds=${raw_video_duration}"
    echo "raw_decoded_frames=${raw_video_frames}"
    echo "raw_sha256=$(sha256sum "${raw_video}" | awk '{print $1}')"
    echo "normalized_duration_seconds=${video_duration}"
    echo "normalized_frame_rate=${video_frame_rate}"
    echo "normalized_decoded_frames=${video_frames}"
    echo "normalized_sha256=$(sha256sum "${local_video}" | awk '{print $1}')"
    echo "normalization=fps=${normalized_frame_rate},tpad=clone,target=${normalized_evidence_seconds}s"
  } >"${capture_directory}/capture.txt"

  ffmpeg \
    -hide_banner \
    -loglevel error \
    -y \
    -i "${local_video}" \
    -vf "fps=${contact_sheet_samples}/${normalized_evidence_seconds},scale=${contact_sheet_frame_width}:-2,tile=${contact_sheet_columns}x${contact_sheet_rows}" \
    -frames:v 1 \
    "${capture_directory}/contact-sheet.png"
  contact_sheet_dimensions="$(
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=width,height -of csv=s=x:p=0 \
      "${capture_directory}/contact-sheet.png"
  )"
  if [[ "${contact_sheet_dimensions}" != "2700x3900" ]]; then
    echo "Unexpected contact sheet size: ${contact_sheet_dimensions}" >&2
    exit 1
  fi
  {
    echo "contact_sheet_samples=${contact_sheet_samples}"
    echo "contact_sheet_dimensions=${contact_sheet_dimensions}"
  } >>"${capture_directory}/capture.txt"

  {
    echo "density_name=${density_name}"
    echo "density_value=${density_value}"
    echo "display_override=${display_size}"
    echo "record_size=${record_size}"
    timeout "${adb_general_timeout_seconds}" adb shell wm size
    timeout "${adb_general_timeout_seconds}" adb shell wm density
  } >"${capture_directory}/display.txt"
  assert_display_profile "${display_size}" "${density_value}"
done

reset_display
trap - EXIT

echo "Android launch evidence written to ${evidence_root}"
