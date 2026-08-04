#!/usr/bin/env python3
"""Validate the Store-released Kwabor onboarding assets and revision contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
ANDROID_ASSET = REPOSITORY_ROOT / "androidApp/src/main/res/raw/kwabor_intro.mp4"
IOS_ASSET = REPOSITORY_ROOT / "iosApp/Kwabor/Resources/KwaborIntro.mp4"
ANDROID_FALLBACK = REPOSITORY_ROOT / "androidApp/src/main/res/drawable-nodpi/kwabor_intro_fallback.png"
IOS_FALLBACK = (
    REPOSITORY_ROOT / "iosApp/Kwabor/Resources/Assets.xcassets/IntroFallback.imageset/IntroFallback.png"
)
ANDROID_REVISION_SOURCE = (
    REPOSITORY_ROOT / "androidApp/src/main/kotlin/com/kwabor/android/onboarding/FirstLaunchStore.kt"
)
IOS_REVISION_SOURCE = REPOSITORY_ROOT / "iosApp/Kwabor/Onboarding/IntroVideoPresentationStore.swift"
STORE_ONLY_ADR = REPOSITORY_ROOT / "docs/adr/0021-store-released-onboarding-media.md"
MAX_SIZE_BYTES = 3 * 1024 * 1024
MIN_DURATION_SECONDS = 15.0
MAX_DURATION_SECONDS = 25.0
ALLOWED_PROFILES = {"Baseline", "Constrained Baseline", "Main"}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
EXPECTED_FALLBACK_DIMENSIONS = (941, 1_672)
INITIAL_BUNDLED_REVISION = 1
ANDROID_REVISION_PATTERN = re.compile(
    rb"(?m)^\s*internal\s+const\s+val\s+BUNDLED_INTRO_REVISION\s*=\s*([0-9]+)L\s*$"
)
IOS_REVISION_PATTERN = re.compile(
    rb"(?m)^\s*private\s+let\s+bundledIntroRevision\s*:\s*Int64\s*=\s*([0-9]+)\s*$"
)
ANDROID_LEGACY_BASELINE_PATTERN = re.compile(
    rb"(?m)^\s*private\s+const\s+val\s+LEGACY_BUNDLED_INTRO_REVISION\s*=\s*([0-9]+)L\s*$"
)
IOS_LEGACY_BASELINE_PATTERN = re.compile(
    rb"(?m)^\s*private\s+let\s+legacyBundledIntroBaselineRevision\s*:\s*Int64\s*=\s*([0-9]+)\s*$"
)
ACTIVE_CONTRACT_ROOTS = (
    REPOSITORY_ROOT / "androidApp/src",
    REPOSITORY_ROOT / "iosApp/Kwabor",
    REPOSITORY_ROOT / "iosApp/Kwabor.xcodeproj/project.pbxproj",
    REPOSITORY_ROOT / "shared/src",
)
SCANNED_CONTRACT_SUFFIXES = {
    ".json",
    ".kt",
    ".kts",
    ".pbxproj",
    ".plist",
    ".properties",
    ".swift",
    ".xcconfig",
    ".xml",
}
FORBIDDEN_REMOTE_MEDIA_TOKENS = (
    "intro_video_enabled",
    "intro_video_url",
    "intro_video_sha256",
    "intro_video_revision",
    "RemoteFeatureConfiguration",
    "RemoteIntroVideoStatus",
    "RemoteIntroVideo",
    "FirebaseRemoteFeatureConfiguration",
    "FirebaseRemoteIntroVideo",
    "createRemoteFeatureConfiguration",
    "AndroidIntroMediaManager",
    "AndroidIntroVideoCache",
    "IntroVideoCache",
    "PendingRemoteIntro",
    "PendingIntroVideo",
)


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


def parse_revision(payload: bytes, pattern: re.Pattern[bytes], label: str) -> int:
    matches = pattern.findall(payload)
    require(len(matches) == 1, f"Expected exactly one {label} revision constant, found {len(matches)}")
    revision = int(matches[0])
    require(revision > 0, f"{label} bundled intro revision must be strictly positive")
    return revision


def read_current_revisions() -> tuple[int, int]:
    android_source = read_required_bytes(ANDROID_REVISION_SOURCE)
    ios_source = read_required_bytes(IOS_REVISION_SOURCE)
    android_revision = parse_revision(
        android_source,
        ANDROID_REVISION_PATTERN,
        "Android",
    )
    ios_revision = parse_revision(
        ios_source,
        IOS_REVISION_PATTERN,
        "iOS",
    )
    require(
        android_revision == ios_revision,
        f"Android/iOS bundled intro revisions differ: {android_revision} != {ios_revision}",
    )
    android_legacy_baseline = parse_revision(
        android_source,
        ANDROID_LEGACY_BASELINE_PATTERN,
        "Android legacy baseline",
    )
    ios_legacy_baseline = parse_revision(
        ios_source,
        IOS_LEGACY_BASELINE_PATTERN,
        "iOS legacy baseline",
    )
    require(
        android_legacy_baseline == INITIAL_BUNDLED_REVISION
        and ios_legacy_baseline == INITIAL_BUNDLED_REVISION,
        "Android/iOS legacy migration baselines must remain fixed at revision 1",
    )
    return android_revision, ios_revision


def read_required_bytes(path: Path) -> bytes:
    require(path.is_file(), f"Missing required file: {path}")
    return path.read_bytes()


def iter_contract_files() -> list[Path]:
    files: list[Path] = []
    for root in ACTIVE_CONTRACT_ROOTS:
        if root.is_file():
            files.append(root)
            continue
        require(root.is_dir(), f"Missing contract root: {root}")
        files.extend(
            path
            for path in root.rglob("*")
            if path.is_file() and path.suffix.lower() in SCANNED_CONTRACT_SUFFIXES
        )
    return sorted(set(files))


def verify_remote_media_contract_is_absent() -> None:
    violations: list[str] = []
    token_patterns = {
        token: re.compile(rf"(?<![A-Za-z0-9_]){re.escape(token)}(?![A-Za-z0-9_])")
        for token in FORBIDDEN_REMOTE_MEDIA_TOKENS
    }
    for path in iter_contract_files():
        text = path.read_text(encoding="utf-8", errors="replace")
        for token, pattern in token_patterns.items():
            if pattern.search(text):
                relative_path = path.relative_to(REPOSITORY_ROOT).as_posix()
                violations.append(f"{relative_path}: {token}")
    require(
        not violations,
        "Retired remote intro media contract is still present:\n" + "\n".join(violations),
    )


def git_output(*arguments: str) -> bytes:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
    )
    require(
        completed.returncode == 0,
        f"Git command failed: git {' '.join(arguments)}: "
        f"{completed.stderr.decode(encoding='utf-8', errors='replace').strip()}",
    )
    return completed.stdout


def read_git_file(base_ref: str, path: Path, *, required: bool = True) -> bytes | None:
    relative_path = path.relative_to(REPOSITORY_ROOT).as_posix()
    completed = subprocess.run(
        ["git", "show", f"{base_ref}:{relative_path}"],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
    )
    if completed.returncode == 0:
        return completed.stdout
    if not required:
        return None
    error = completed.stderr.decode(encoding="utf-8", errors="replace").strip()
    raise MediaVerificationError(f"Unable to read {relative_path} from {base_ref}: {error}")


def parse_optional_base_revision(
    payload: bytes | None,
    pattern: re.Pattern[bytes],
    label: str,
) -> int | None:
    if payload is None:
        return None
    matches = pattern.findall(payload)
    if not matches:
        return None
    require(len(matches) == 1, f"Expected at most one base {label} revision, found {len(matches)}")
    revision = int(matches[0])
    require(revision > 0, f"Base {label} bundled intro revision must be strictly positive")
    return revision


def verify_revision_against_base(base_ref: str, current_revision: int, current_video: bytes) -> None:
    resolved_ref = git_output("rev-parse", "--verify", f"{base_ref}^{{commit}}").decode().strip()
    base_android_source = read_git_file(resolved_ref, ANDROID_REVISION_SOURCE, required=False)
    base_ios_source = read_git_file(resolved_ref, IOS_REVISION_SOURCE, required=False)
    base_android_revision = parse_optional_base_revision(
        base_android_source,
        ANDROID_REVISION_PATTERN,
        "Android",
    )
    base_ios_revision = parse_optional_base_revision(
        base_ios_source,
        IOS_REVISION_PATTERN,
        "iOS",
    )
    base_store_only_policy = read_git_file(resolved_ref, STORE_ONLY_ADR, required=False)
    if base_store_only_policy is not None:
        require(
            base_android_revision is not None and base_ios_revision is not None,
            "The base ref declares Store-only onboarding but its revision constants cannot be parsed",
        )

    if base_android_revision is None and base_ios_revision is None:
        base_android_video = read_git_file(resolved_ref, ANDROID_ASSET)
        base_ios_video = read_git_file(resolved_ref, IOS_ASSET)
        require(base_android_video == base_ios_video, "Base Android/iOS onboarding videos differ")
        require(
            current_video == base_android_video,
            "The Store-only migration must preserve the existing video; change it in a later revision",
        )
        require(
            current_revision == INITIAL_BUNDLED_REVISION,
            "The first Store-only bundled intro revision must initialize to 1",
        )
        return

    require(
        base_android_revision is not None and base_ios_revision is not None,
        "The base ref contains only one platform bundled intro revision",
    )
    require(
        base_android_revision == base_ios_revision,
        f"Base Android/iOS bundled intro revisions differ: "
        f"{base_android_revision} != {base_ios_revision}",
    )
    base_android_video = read_git_file(resolved_ref, ANDROID_ASSET)
    base_ios_video = read_git_file(resolved_ref, IOS_ASSET)
    require(base_android_video == base_ios_video, "Base Android/iOS onboarding videos differ")
    video_changed = current_video != base_android_video
    if video_changed:
        require(
            current_revision > base_android_revision,
            f"Changed onboarding video requires a revision greater than {base_android_revision}",
        )
    else:
        require(
            current_revision == base_android_revision,
            f"Unchanged onboarding video must keep revision {base_android_revision}",
        )


def format_summary(label: str, metadata: MediaMetadata) -> str:
    return (
        f"OK {label}: {metadata.size_bytes} bytes, sha256={metadata.sha256}, "
        f"H.264 {metadata.profile} L{metadata.level / 10:.1f}, "
        f"{metadata.width}x{metadata.height}, {metadata.pixel_format}, "
        f"{metadata.duration_seconds:.3f}s, silent, faststart"
    )


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate Store-released onboarding assets and their Android/iOS revision contract."
    )
    parser.add_argument(
        "--base-ref",
        help=(
            "optional Git ref used to enforce that video bytes and bundled revision "
            "change together"
        ),
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    android_payload = read_asset(ANDROID_ASSET)
    ios_payload = read_asset(IOS_ASSET)
    require(
        android_payload == ios_payload,
        "Android and iOS onboarding assets must contain exactly the same bytes",
    )
    current_revision, _ = read_current_revisions()
    verify_remote_media_contract_is_absent()
    metadata = verify_media(ANDROID_ASSET)
    fallback_metadata = verify_fallback_assets()
    if arguments.base_ref is not None:
        verify_revision_against_base(
            base_ref=arguments.base_ref,
            current_revision=current_revision,
            current_video=android_payload,
        )

    print(format_summary("embedded onboarding media", metadata))
    print(
        "OK onboarding fallback: "
        f"{fallback_metadata.size_bytes} bytes, sha256={fallback_metadata.sha256}, "
        f"{fallback_metadata.width}x{fallback_metadata.height}, 8-bit RGB PNG"
    )
    print(f"OK bundled intro revision: Android=iOS={current_revision}")
    if arguments.base_ref is not None:
        print(f"OK bundled intro revision history against Git ref: {arguments.base_ref}")
    print("OK retired remote intro media contract is absent from active source/configuration files")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MediaVerificationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
