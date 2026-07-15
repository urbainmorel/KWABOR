#!/usr/bin/env python3
"""Validate the offline onboarding video embedded in both mobile clients."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
ANDROID_ASSET = REPOSITORY_ROOT / "androidApp/src/main/res/raw/kwabor_intro.mp4"
IOS_ASSET = REPOSITORY_ROOT / "iosApp/Kwabor/Resources/KwaborIntro.mp4"
MAX_SIZE_BYTES = 3 * 1024 * 1024
MIN_DURATION_SECONDS = 15.0
MAX_DURATION_SECONDS = 25.5
ALLOWED_PROFILES = {"Baseline", "Constrained Baseline", "Main"}


class MediaVerificationError(RuntimeError):
    """Raised when an embedded media invariant is not satisfied."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise MediaVerificationError(message)


def read_asset(path: Path) -> bytes:
    require(path.is_file(), f"Missing embedded onboarding asset: {path}")
    payload = path.read_bytes()
    require(payload, f"Embedded onboarding asset is empty: {path}")
    require(
        len(payload) <= MAX_SIZE_BYTES,
        f"Embedded onboarding asset exceeds 3 MiB: {path} ({len(payload)} bytes)",
    )
    return payload


def verify_faststart(payload: bytes) -> None:
    """Require the top-level MP4 moov atom to precede media data."""
    offset = 0
    moov_offset: int | None = None
    mdat_offset: int | None = None

    while offset + 8 <= len(payload):
        box_size = int.from_bytes(payload[offset : offset + 4], "big")
        box_type = payload[offset + 4 : offset + 8]
        header_size = 8

        if box_size == 1:
            require(offset + 16 <= len(payload), "Invalid extended MP4 box header")
            box_size = int.from_bytes(payload[offset + 8 : offset + 16], "big")
            header_size = 16
        elif box_size == 0:
            box_size = len(payload) - offset

        require(box_size >= header_size, "Invalid MP4 box size")
        require(offset + box_size <= len(payload), "MP4 box exceeds file bounds")

        if box_type == b"moov" and moov_offset is None:
            moov_offset = offset
        if box_type == b"mdat" and mdat_offset is None:
            mdat_offset = offset
        offset += box_size

    require(moov_offset is not None, "MP4 moov atom is missing")
    require(mdat_offset is not None, "MP4 media-data atom is missing")
    require(moov_offset < mdat_offset, "MP4 is not fast-start optimized")


def probe(path: Path) -> dict[str, Any]:
    ffprobe = shutil.which("ffprobe")
    require(ffprobe is not None, "ffprobe is required to validate onboarding media")
    completed = subprocess.run(
        [
            ffprobe,
            "-v",
            "error",
            "-show_streams",
            "-show_format",
            "-of",
            "json",
            str(path),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    require(
        completed.returncode == 0,
        f"ffprobe rejected {path}: {completed.stderr.strip()}",
    )
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise MediaVerificationError(f"ffprobe returned invalid JSON for {path}") from error


def verify_streams(metadata: dict[str, Any]) -> tuple[str, int, float]:
    streams = metadata.get("streams", [])
    video_streams = [stream for stream in streams if stream.get("codec_type") == "video"]
    audio_streams = [stream for stream in streams if stream.get("codec_type") == "audio"]

    require(len(video_streams) == 1, "Onboarding media must contain exactly one video stream")
    require(not audio_streams, "Onboarding media must not contain an audio stream")

    video = video_streams[0]
    profile = str(video.get("profile", ""))
    level = int(video.get("level") or 0)
    width = int(video.get("width") or 0)
    height = int(video.get("height") or 0)
    duration_value = video.get("duration") or metadata.get("format", {}).get("duration")

    require(video.get("codec_name") == "h264", "Onboarding media must use H.264")
    require(profile in ALLOWED_PROFILES, f"Unsupported H.264 profile: {profile or 'unknown'}")
    require(0 < level <= 31, f"H.264 level must be at most 3.1, found {level / 10:.1f}")
    require(video.get("pix_fmt") == "yuv420p", "Onboarding media must use yuv420p")
    require((width, height) == (720, 1280), f"Expected 720x1280 portrait video, found {width}x{height}")
    require(width < height, "Onboarding media must be portrait")
    require(duration_value is not None, "Onboarding media duration is unavailable")

    duration = float(duration_value)
    require(
        MIN_DURATION_SECONDS <= duration <= MAX_DURATION_SECONDS,
        f"Duration must be between 15 and 25.5 seconds, found {duration:.3f}",
    )
    return profile, level, duration


def main() -> int:
    android_payload = read_asset(ANDROID_ASSET)
    ios_payload = read_asset(IOS_ASSET)
    require(
        android_payload == ios_payload,
        "Android and iOS onboarding assets must contain exactly the same bytes",
    )

    verify_faststart(android_payload)
    profile, level, duration = verify_streams(probe(ANDROID_ASSET))
    digest = hashlib.sha256(android_payload).hexdigest()
    print(
        "OK onboarding media: "
        f"{len(android_payload)} bytes, sha256={digest}, H.264 {profile} L{level / 10:.1f}, "
        f"720x1280, yuv420p, {duration:.3f}s, silent, faststart"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MediaVerificationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
