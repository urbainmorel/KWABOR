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
# frames. Use a generous watchdog, stop after the configured UI is proven, retain the
# untouched stream, and normalize a separate review copy to at least 15 seconds.
screenrecord_time_limit_seconds=70
minimum_evidence_seconds=15
normalized_frame_rate=30
contact_sheet_samples=150
contact_sheet_columns=15
contact_sheet_rows=10
contact_sheet_frame_width=180
minimum_recording_wall_seconds=14
minimum_raw_decoded_frames=30
post_launch_hold_seconds=2
onboarding_deadline_seconds=40
remote_header_probe_bytes=16384
screenrecord_startup_timeout_seconds=20
screenrecord_graceful_stop_seconds=15
screenrecord_forced_stop_seconds=5
screenrecord_host_reap_seconds=10
adb_probe_timeout_seconds=3
uiautomator_command_timeout_seconds=12
intro_accessibility_label="Découvrir le Bénin avec Kwabor"
configuration_unavailable_message="Kwabor est indisponible pour le moment. Réessayez plus tard."
landing_title="Découvrez le Bénin"
landing_sign_in="Se connecter"

declare -a density_profiles=(
  "mdpi:160:360x780:360x780"
  "xhdpi:320:720x1560:720x1560"
  "xxxhdpi:640:1440x3120:720x1560"
)

for command_name in adb ffmpeg ffprobe sha256sum timeout; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Missing required command: ${command_name}" >&2
    exit 1
  fi
done
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
if [[ "$(ffmpeg -hide_banner -encoders 2>/dev/null)" != *"libx264"* ]]; then
  echo "ffmpeg does not provide the required libx264 encoder" >&2
  exit 1
fi

if [[ ! -f "${apk_path}" ]]; then
  echo "Missing debug APK: ${apk_path}" >&2
  exit 1
fi

actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
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

active_recorder_pid=""
active_remote_recorder_pid=""
screenrecord_ready_bytes=""
screenrecord_mdat_payload_offset=""

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

cleanup() {
  local remote_state=1
  if [[ -n "${active_remote_recorder_pid}" ]]; then
    if remote_recorder_is_running "${active_remote_recorder_pid}"; then
      remote_state=0
    else
      remote_state=$?
    fi
    if ((remote_state != 1)); then
      timeout "${adb_probe_timeout_seconds}" \
        adb shell kill -2 "${active_remote_recorder_pid}" >/dev/null 2>&1 ||
        true
      timeout "${adb_probe_timeout_seconds}" \
        adb shell kill -2 "${active_remote_recorder_pid}" >/dev/null 2>&1 ||
        true
      timeout "${adb_probe_timeout_seconds}" \
        adb shell kill -9 "${active_remote_recorder_pid}" >/dev/null 2>&1 ||
        true
    fi
  fi
  if [[ -n "${active_recorder_pid}" ]]; then
    kill -9 "${active_recorder_pid}" >/dev/null 2>&1 || true
  fi
  reset_display
}
trap cleanup EXIT

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
  local probe_status=0
  local recorded_bytes=""
  local mdat_payload_offset=""
  local deadline=$((SECONDS + screenrecord_startup_timeout_seconds))
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
          if [[ "${mdat_payload_offset}" =~ ^[0-9]+$ ]] &&
            ((recorded_bytes > mdat_payload_offset)); then
            if ! kill -0 "${recorder_pid}" >/dev/null 2>&1; then
              reap_finished_recorder "${recorder_pid}" "${recorder_log}" || true
              echo "screenrecord exited after writing its first sample" >&2
              return 1
            fi
            screenrecord_ready_bytes="${recorded_bytes}"
            screenrecord_mdat_payload_offset="${mdat_payload_offset}"
            return 0
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
  echo "Timed out waiting for the first encoded screenrecord frame" >&2
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
  local deadline=$((recording_ready_at + onboarding_deadline_seconds))
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
  local deadline=$((SECONDS + timeout_seconds))
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
  local watchdog_pid=""
  local recorder_status=0

  (
    sleep "${screenrecord_host_reap_seconds}"
    kill "${recorder_pid}" >/dev/null 2>&1 || exit 0
    sleep 2
    kill -9 "${recorder_pid}" >/dev/null 2>&1 || true
  ) &
  watchdog_pid=$!
  if wait "${recorder_pid}"; then
    recorder_status=0
  else
    recorder_status=$?
  fi
  kill "${watchdog_pid}" >/dev/null 2>&1 || true
  wait "${watchdog_pid}" >/dev/null 2>&1 || true
  return "${recorder_status}"
}

finish_screenrecord() {
  local recorder_pid="$1"
  local remote_recorder_pid="$2"
  local recorder_log="$3"
  local recorder_status=0
  local forced_stop=0
  local remote_state=0
  local remote_stopped=0

  if remote_recorder_is_running "${remote_recorder_pid}"; then
    remote_state=0
  else
    remote_state=$?
  fi
  if ((remote_state == 1)); then
    remote_stopped=1
  else
    timeout "${adb_probe_timeout_seconds}" \
      adb shell kill -2 "${remote_recorder_pid}" >/dev/null 2>&1 ||
      true
    if wait_for_remote_recorder_exit \
      "${remote_recorder_pid}" \
      "${screenrecord_graceful_stop_seconds}"; then
      remote_stopped=1
    else
      forced_stop=1
      timeout "${adb_probe_timeout_seconds}" \
        adb shell kill -2 "${remote_recorder_pid}" >/dev/null 2>&1 ||
        true
      if wait_for_remote_recorder_exit \
        "${remote_recorder_pid}" \
        "${screenrecord_forced_stop_seconds}"; then
        remote_stopped=1
      else
        timeout "${adb_probe_timeout_seconds}" \
          adb shell kill -9 "${remote_recorder_pid}" >/dev/null 2>&1 ||
          true
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
  if ! grep -Eq 'Encoder stopping; recorded [0-9]+ frames in [0-9]+ seconds' \
    "${recorder_log}"; then
    sed 's/^/screenrecord: /' "${recorder_log}" >&2
    echo "screenrecord did not report a complete encoder shutdown" >&2
    return 1
  fi
}

{
  echo "requested_api=${api_level}"
  echo "actual_api=${actual_api}"
  echo "device=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "build_fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "apk_sha256=$(sha256sum "${apk_path}" | awk '{print $1}')"
  echo "ffmpeg_version=$(ffmpeg -version | sed -n '1p')"
} >"${evidence_root}/device.txt"

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0

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
  raw_video="${capture_directory}/cold-start-raw.mp4"
  local_video="${capture_directory}/cold-start.mp4"
  recorder_log="${capture_directory}/screenrecord.txt"
  screenrecord_ready_bytes=""
  screenrecord_mdat_payload_offset=""

  mkdir -p "${capture_directory}"
  adb uninstall "${package_name}" >/dev/null 2>&1 || true
  adb shell wm size "${display_size}"
  adb shell wm density "${density_value}"
  timeout 180 adb install --no-streaming "${apk_path}" |
    tee "${capture_directory}/install.txt"
  adb shell am force-stop "${package_name}"
  adb shell input keyevent KEYCODE_HOME
  adb shell rm -f "${remote_video}"
  adb shell rm -f "${remote_window}"
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

  wait_for_screenrecord "${recorder_pid}" "${recorder_log}" "${remote_video}"
  remote_recorder_pid="${active_remote_recorder_pid}"
  recording_ready_at="${SECONDS}"
  adb shell am start -S -W -n "${activity_name}" |
    tee "${capture_directory}/activity-start.txt"
  if ! grep -Fq "Status: ok" "${capture_directory}/activity-start.txt"; then
    echo "MainActivity did not report a successful cold start" >&2
    exit 1
  fi
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
  finish_screenrecord "${recorder_pid}" "${remote_recorder_pid}" "${recorder_log}"
  recording_wall_seconds=$((SECONDS - recording_ready_at))
  if ((recording_wall_seconds < minimum_recording_wall_seconds)); then
    echo "Cold-start recorder stopped too early: ${recording_wall_seconds}s wall time" >&2
    exit 1
  fi
  adb pull "${remote_video}" "${raw_video}"
  adb shell rm -f "${remote_video}"

  resumed_activity="$(adb shell dumpsys activity activities |
    grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' || true)"
  printf '%s\n' "${resumed_activity}" >"${capture_directory}/resumed-activity.txt"
  if [[ "${resumed_activity}" != *"${package_name}"* ]]; then
    echo "Kwabor MainActivity is not resumed after the cold start" >&2
    exit 1
  fi

  process_id="$(adb shell pidof "${package_name}" | tr -d '\r')"
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
  # ActivityManager WaitTime and screenrecord PTS use different clocks under an
  # accelerated emulator. Preserve both metrics; the first-frame barrier and
  # in-recording UI assertion prove sequence coverage without comparing them.
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
    adb shell wm size
    adb shell wm density
  } >"${capture_directory}/display.txt"
done

reset_display
trap - EXIT

echo "Android launch evidence written to ${evidence_root}"
