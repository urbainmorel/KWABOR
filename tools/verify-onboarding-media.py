#!/usr/bin/env python3
"""Validate embedded or remotely published Kwabor onboarding videos."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
ANDROID_ASSET = REPOSITORY_ROOT / "androidApp/src/main/res/raw/kwabor_intro.mp4"
IOS_ASSET = REPOSITORY_ROOT / "iosApp/Kwabor/Resources/KwaborIntro.mp4"
ANDROID_FALLBACK = REPOSITORY_ROOT / "androidApp/src/main/res/drawable-nodpi/kwabor_intro_fallback.png"
IOS_FALLBACK = (
    REPOSITORY_ROOT / "iosApp/Kwabor/Resources/Assets.xcassets/IntroFallback.imageset/IntroFallback.png"
)
MAX_SIZE_BYTES = 3 * 1024 * 1024
MIN_DURATION_SECONDS = 15.0
MAX_DURATION_SECONDS = 25.0
ALLOWED_PROFILES = {"Baseline", "Constrained Baseline", "Main"}
MIN_REMOTE_URL_LENGTH = 9
MAX_REMOTE_URL_LENGTH = 2_048
MAX_REMOTE_REVISION = 2**63 - 1
UNSAFE_REMOTE_URL_CHARACTERS = frozenset('\\"<>^`{|}')
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
EXPECTED_FALLBACK_DIMENSIONS = (941, 1_672)


@dataclass(frozen=True)
class MediaMetadata:
    """Validated media properties shared by CI and the publication runbook."""

    size_bytes: int
    sha256: str
    profile: str
    level: int
    width: int
    height: int
    pixel_format: str
    duration_seconds: float
    rotation_degrees: float


@dataclass(frozen=True)
class FallbackMetadata:
    """Locked properties of the byte-identical Android/iOS static fallback."""

    size_bytes: int
    sha256: str
    width: int
    height: int


class MediaVerificationError(RuntimeError):
    """Raised when an onboarding-media invariant is not satisfied."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise MediaVerificationError(message)


def read_asset(path: Path) -> bytes:
    require(path.is_file(), f"Missing onboarding media: {path}")
    payload = path.read_bytes()
    require(payload, f"Onboarding media is empty: {path}")
    require(
        len(payload) <= MAX_SIZE_BYTES,
        f"Onboarding media exceeds 3 MiB: {path} ({len(payload)} bytes)",
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


def verify_zero_rotation(video: dict[str, Any]) -> float:
    rotations: list[Any] = []
    tags = video.get("tags", {})
    if isinstance(tags, dict) and "rotate" in tags:
        rotations.append(tags["rotate"])
    side_data = video.get("side_data_list", [])
    require(isinstance(side_data, list), "Onboarding media rotation metadata is invalid")
    rotations.extend(
        entry["rotation"]
        for entry in side_data
        if isinstance(entry, dict) and "rotation" in entry
    )
    for value in rotations:
        try:
            rotation = float(value)
        except (TypeError, ValueError) as error:
            raise MediaVerificationError("Onboarding media rotation metadata is invalid") from error
        require(math.isfinite(rotation), "Onboarding media rotation metadata must be finite")
        normalized = rotation % 360.0
        zero_rotation = math.isclose(normalized, 0.0, abs_tol=0.01) or math.isclose(
            normalized,
            360.0,
            abs_tol=0.01,
        )
        require(
            zero_rotation,
            "Onboarding media must be physically portrait without display-rotation metadata",
        )
    return 0.0


def verify_streams(metadata: dict[str, Any]) -> tuple[str, int, int, int, str, float, float]:
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
    pixel_format = str(video.get("pix_fmt", ""))
    require(pixel_format == "yuv420p", "Onboarding media must use yuv420p")
    require((width, height) == (720, 1280), f"Expected 720x1280 portrait video, found {width}x{height}")
    require(width < height, "Onboarding media must be portrait")
    rotation = verify_zero_rotation(video)
    require(duration_value is not None, "Onboarding media duration is unavailable")

    duration = float(duration_value)
    require(
        MIN_DURATION_SECONDS <= duration <= MAX_DURATION_SECONDS,
        f"Duration must be between 15 and 25 seconds, found {duration:.3f}",
    )
    return profile, level, width, height, pixel_format, duration, rotation


def verify_media(path: Path) -> MediaMetadata:
    payload = read_asset(path)
    verify_faststart(payload)
    profile, level, width, height, pixel_format, duration, rotation = verify_streams(probe(path))
    return MediaMetadata(
        size_bytes=len(payload),
        sha256=hashlib.sha256(payload).hexdigest(),
        profile=profile,
        level=level,
        width=width,
        height=height,
        pixel_format=pixel_format,
        duration_seconds=duration,
        rotation_degrees=rotation,
    )


def verify_decodable_png(path: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    require(ffmpeg is not None, "ffmpeg is required to decode the onboarding fallback")
    completed = subprocess.run(
        [ffmpeg, "-nostdin", "-xerror", "-v", "error", "-i", str(path), "-frames:v", "1", "-f", "null", "-"],
        check=False,
        capture_output=True,
        text=True,
    )
    require(
        completed.returncode == 0,
        f"ffmpeg could not decode the onboarding fallback: {completed.stderr.strip()}",
    )
    metadata = probe(path)
    streams = metadata.get("streams", [])
    require(len(streams) == 1, "Onboarding fallback must contain exactly one PNG stream")
    stream = streams[0]
    require(stream.get("codec_name") == "png", "Onboarding fallback must decode as PNG")
    require(stream.get("pix_fmt") == "rgb24", "Onboarding fallback must decode as 8-bit RGB")


def verify_fallback_assets() -> FallbackMetadata:
    android_payload = read_asset(ANDROID_FALLBACK)
    ios_payload = read_asset(IOS_FALLBACK)
    require(
        android_payload == ios_payload,
        "Android and iOS onboarding fallback images must contain exactly the same bytes",
    )
    require(android_payload.startswith(PNG_SIGNATURE), "Onboarding fallback must be a PNG")
    require(len(android_payload) >= 33, "Onboarding fallback PNG is truncated")
    require(int.from_bytes(android_payload[8:12], "big") == 13, "Onboarding fallback has an invalid IHDR")
    require(android_payload[12:16] == b"IHDR", "Onboarding fallback PNG is missing IHDR")
    width = int.from_bytes(android_payload[16:20], "big")
    height = int.from_bytes(android_payload[20:24], "big")
    require(
        (width, height) == EXPECTED_FALLBACK_DIMENSIONS,
        f"Expected {EXPECTED_FALLBACK_DIMENSIONS[0]}x{EXPECTED_FALLBACK_DIMENSIONS[1]} fallback, "
        f"found {width}x{height}",
    )
    require(android_payload[24:29] == bytes((8, 2, 0, 0, 0)), "Onboarding fallback must be 8-bit RGB PNG")
    verify_decodable_png(ANDROID_FALLBACK)
    return FallbackMetadata(
        size_bytes=len(android_payload),
        sha256=hashlib.sha256(android_payload).hexdigest(),
        width=width,
        height=height,
    )


def validate_remote_publication(url: str, revision: int) -> None:
    require(
        0 < revision <= MAX_REMOTE_REVISION,
        "Remote Config revision must be a positive signed 64-bit integer",
    )
    require(
        MIN_REMOTE_URL_LENGTH <= len(url) <= MAX_REMOTE_URL_LENGTH,
        "CDN URL length must be between 9 and 2048 characters",
    )
    require(not any(character.isspace() for character in url), "CDN URL must not contain whitespace")
    require(
        not any(character in UNSAFE_REMOTE_URL_CHARACTERS for character in url),
        "CDN URL contains a character rejected by the mobile clients",
    )
    try:
        parts = urlsplit(url)
        _ = parts.port
    except ValueError as error:
        raise MediaVerificationError("CDN URL has an invalid authority or port") from error
    require(parts.scheme.lower() == "https", "CDN URL must use HTTPS")
    require(parts.hostname is not None and "." in parts.hostname, "CDN URL must have a qualified host")
    require(parts.username is None and parts.password is None, "CDN URL must not contain user information")


def format_summary(label: str, metadata: MediaMetadata) -> str:
    return (
        f"OK {label}: {metadata.size_bytes} bytes, sha256={metadata.sha256}, "
        f"H.264 {metadata.profile} L{metadata.level / 10:.1f}, "
        f"{metadata.width}x{metadata.height}, {metadata.pixel_format}, "
        f"{metadata.duration_seconds:.3f}s, silent, faststart"
    )


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Validate the byte-identical embedded onboarding assets, or one remote "
            "campaign candidate and its Remote Config publication values."
        )
    )
    parser.add_argument(
        "--input",
        type=Path,
        help="remote campaign MP4 to validate instead of the two embedded assets",
    )
    parser.add_argument("--url", help="immutable HTTPS CDN URL for the remote candidate")
    parser.add_argument("--revision", type=int, help="strictly increasing positive Remote Config revision")
    parser.add_argument(
        "--expected-sha256",
        help="fail unless the candidate bytes match this previously validated SHA-256",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable validation output")
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    publication_requested = arguments.url is not None or arguments.revision is not None
    require(
        not publication_requested or arguments.input is not None,
        "--url and --revision are only valid with --input",
    )
    require(
        arguments.expected_sha256 is None or arguments.input is not None,
        "--expected-sha256 is only valid with --input",
    )
    require(
        (arguments.url is None) == (arguments.revision is None),
        "--url and --revision must be provided together",
    )

    fallback_metadata: FallbackMetadata | None = None
    if arguments.input is None:
        android_payload = read_asset(ANDROID_ASSET)
        ios_payload = read_asset(IOS_ASSET)
        require(
            android_payload == ios_payload,
            "Android and iOS onboarding assets must contain exactly the same bytes",
        )
        metadata = verify_media(ANDROID_ASSET)
        fallback_metadata = verify_fallback_assets()
        label = "onboarding media"
    else:
        metadata = verify_media(arguments.input.resolve())
        label = "remote onboarding media candidate"

    if arguments.expected_sha256 is not None:
        expected_sha256 = arguments.expected_sha256.strip().lower()
        require(
            len(expected_sha256) == 64 and all(character in "0123456789abcdef" for character in expected_sha256),
            "--expected-sha256 must contain exactly 64 hexadecimal characters",
        )
        require(
            metadata.sha256 == expected_sha256,
            f"Onboarding media SHA-256 mismatch: expected {expected_sha256}, found {metadata.sha256}",
        )

    remote_config: dict[str, str | int | bool] | None = None
    if arguments.url is not None and arguments.revision is not None:
        validate_remote_publication(arguments.url, arguments.revision)
        remote_config = {
            "intro_video_enabled": True,
            "intro_video_url": arguments.url,
            "intro_video_sha256": metadata.sha256,
            "intro_video_revision": arguments.revision,
        }

    if arguments.json:
        result: dict[str, Any] = {
            "label": label,
            "media": asdict(metadata),
        }
        if remote_config is not None:
            result["remote_config"] = remote_config
        if fallback_metadata is not None:
            result["fallback"] = asdict(fallback_metadata)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0

    print(format_summary(label, metadata))
    if fallback_metadata is not None:
        print(
            "OK onboarding fallback: "
            f"{fallback_metadata.size_bytes} bytes, sha256={fallback_metadata.sha256}, "
            f"{fallback_metadata.width}x{fallback_metadata.height}, 8-bit RGB PNG"
        )
    if remote_config is not None:
        print("Remote Config values:")
        for key, value in remote_config.items():
            serialized = str(value).lower() if isinstance(value, bool) else value
            print(f"{key}={serialized}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MediaVerificationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
