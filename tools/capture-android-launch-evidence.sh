#!/usr/bin/env bash

set -Eeuo pipefail

case "$(uname -s)" in
  MINGW* | MSYS*)
    export MSYS_NO_PATHCONV=1
    ;;
esac

api_level="${1:?Usage: capture-android-launch-evidence.sh API_LEVEL [APK_PATH]}"
apk_path="${2:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
package_name="com.kwabor.android"
activity_name="${package_name}/.MainActivity"
evidence_root="build/brand-evidence/api-${api_level}"
recording_seconds=15
minimum_recording_seconds=14
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

if [[ ! -f "${apk_path}" ]]; then
  echo "Missing debug APK: ${apk_path}" >&2
  exit 1
fi

actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "${actual_api}" != "${api_level}" ]]; then
  echo "Expected API ${api_level}, connected emulator reports API ${actual_api}" >&2
  exit 1
fi

mkdir -p "${evidence_root}"

reset_display() {
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
}
trap reset_display EXIT

wait_for_screenrecord() {
  local recorder_pid="$1"
  local recorder_log="$2"
  local attempts=0
  while ((attempts < 40)); do
    if [[ -n "$(adb shell pidof screenrecord | tr -d '\r')" ]]; then
      return 0
    fi
    if ! kill -0 "${recorder_pid}" >/dev/null 2>&1; then
      if ! wait "${recorder_pid}"; then
        sed 's/^/screenrecord: /' "${recorder_log}" >&2
      fi
      echo "screenrecord exited before becoming ready" >&2
      return 1
    fi
    attempts=$((attempts + 1))
    sleep 0.25
  done
  echo "Timed out waiting for screenrecord" >&2
  return 1
}

{
  echo "requested_api=${api_level}"
  echo "actual_api=${actual_api}"
  echo "device=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "build_fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "apk_sha256=$(sha256sum "${apk_path}" | awk '{print $1}')"
} >"${evidence_root}/device.txt"

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0

for profile in "${density_profiles[@]}"; do
  IFS=":" read -r density_name density_value display_size record_size <<<"${profile}"
  capture_directory="${evidence_root}/${density_name}"
  remote_video="/sdcard/kwabor-brand-002-api-${api_level}-${density_name}.mp4"
  remote_window="/sdcard/kwabor-brand-002-api-${api_level}-${density_name}.xml"
  local_video="${capture_directory}/cold-start.mp4"

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
  adb shell screenrecord \
    --size "${record_size}" \
    --bit-rate 4000000 \
    --time-limit "${recording_seconds}" \
    "${remote_video}" \
    >"${capture_directory}/screenrecord.txt" 2>&1 &
  recorder_pid=$!

  wait_for_screenrecord "${recorder_pid}" "${capture_directory}/screenrecord.txt"
  adb shell am start -S -W -n "${activity_name}" |
    tee "${capture_directory}/activity-start.txt"
  if ! grep -Fq "Status: ok" "${capture_directory}/activity-start.txt"; then
    echo "MainActivity did not report a successful cold start" >&2
    exit 1
  fi
  wait "${recorder_pid}"
  adb pull "${remote_video}" "${local_video}"
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

  adb shell uiautomator dump "${remote_window}" |
    tee "${capture_directory}/uiautomator.txt"
  adb pull "${remote_window}" "${capture_directory}/window.xml"
  adb shell rm -f "${remote_window}"
  if grep -Fq "${configuration_unavailable_message}" "${capture_directory}/window.xml"; then
    echo "Brand evidence reached the unavailable-configuration screen" >&2
    exit 1
  fi
  if grep -Fq "${intro_accessibility_label}" "${capture_directory}/window.xml"; then
    echo "intro" >"${capture_directory}/post-launch-state.txt"
  elif grep -Fq "${landing_title}" "${capture_directory}/window.xml" &&
    grep -Fq "${landing_sign_in}" "${capture_directory}/window.xml"; then
    echo "onboarding-landing" >"${capture_directory}/post-launch-state.txt"
  else
    echo "Brand evidence did not reach a valid configured onboarding surface" >&2
    exit 1
  fi

  ffprobe \
    -v error \
    -show_entries stream=codec_name,width,height,r_frame_rate,duration \
    -show_entries format=duration,size \
    -of default=noprint_wrappers=1 \
    "${local_video}" \
    >"${capture_directory}/video.txt"
  video_duration="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "${local_video}")"
  video_width="$(ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "${local_video}")"
  video_height="$(ffprobe -v error -select_streams v:0 -show_entries stream=height -of csv=p=0 "${local_video}")"
  video_frames="$(
    ffprobe -v error -count_frames -select_streams v:0 \
      -show_entries stream=nb_read_frames -of csv=p=0 "${local_video}"
  )"
  expected_width="${record_size%x*}"
  expected_height="${record_size#*x}"
  if ! awk -v duration="${video_duration}" -v minimum="${minimum_recording_seconds}" \
    'BEGIN { exit !(duration >= minimum) }'; then
    echo "Cold-start recording is too short: ${video_duration}s" >&2
    exit 1
  fi
  if [[ "${video_width}" != "${expected_width}" || "${video_height}" != "${expected_height}" ]]; then
    echo "Unexpected recording size: ${video_width}x${video_height}, expected ${record_size}" >&2
    exit 1
  fi
  if [[ ! "${video_frames}" =~ ^[0-9]+$ ]] || ((video_frames < 90)); then
    echo "Cold-start recording has too few decoded frames: ${video_frames}" >&2
    exit 1
  fi
  ffmpeg \
    -hide_banner \
    -loglevel error \
    -y \
    -i "${local_video}" \
    -vf "fps=10,scale=180:-2,tile=15x10" \
    -frames:v 1 \
    "${capture_directory}/contact-sheet.png"

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
