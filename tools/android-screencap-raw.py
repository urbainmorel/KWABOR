#!/usr/bin/env python3
"""Validate and convert Android ``screencap`` raw frames.

AOSP serializes raw screenshots as four native uint32 values followed by a
dense pixel payload. Android is always little-endian, and the API 30/31/36
emulator capture path requests RGBA_8888. This tool deliberately rejects every
other pixel format instead of guessing how a changed system image should be
decoded.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import stat
import struct
import sys
import tempfile
import zlib
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import BinaryIO, Iterable


RAW_HEADER = struct.Struct("<4I")
RGBA_8888 = 1
VALID_COLORSPACES = frozenset({0, 1, 2})
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
FRAME_PATTERN = re.compile(r"frame-[0-9]{5}[.]raw")
DECIMAL_PATTERN = re.compile(r"[0-9]+(?:[.][0-9]+)?")


class RawScreencapError(ValueError):
    """Raised when captured evidence violates its declared contract."""


@dataclass(frozen=True)
class ManifestRecord:
    name: str
    started_at: Decimal
    completed_at: Decimal
    size: int


@dataclass(frozen=True)
class RawFrame:
    width: int
    height: int
    pixel_format: int
    colorspace: int
    payload: bytes
    size: int
    sha256: str


@dataclass(frozen=True)
class PublishedPath:
    path: Path
    device: int
    inode: int
    size: int | None


def _require_regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise RawScreencapError(f"{label} is not a regular file: {path}")


def _parse_decimal(value: str, label: str) -> Decimal:
    if DECIMAL_PATTERN.fullmatch(value) is None:
        raise RawScreencapError(f"Invalid {label}: {value}")
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise RawScreencapError(f"Invalid {label}: {value}") from error
    if not parsed.is_finite() or parsed < 0:
        raise RawScreencapError(f"Invalid {label}: {value}")
    return parsed


def read_manifest(
    path: Path,
    maximum_frame_duration: Decimal,
    maximum_frame_bytes: int,
) -> list[ManifestRecord]:
    _require_regular_file(path, "Screencap manifest")
    records: list[ManifestRecord] = []
    previous_name = ""
    previous_started_at: Decimal | None = None
    previous_completed_at: Decimal | None = None
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except UnicodeError as error:
        raise RawScreencapError("Screencap manifest is not valid UTF-8") from error
    for line_number, raw_line in enumerate(lines, start=1):
        fields = raw_line.split("\t")
        if len(fields) != 4 or FRAME_PATTERN.fullmatch(fields[0]) is None:
            raise RawScreencapError(
                f"Invalid screencap record at line {line_number}"
            )
        name, raw_started_at, raw_completed_at, raw_size = fields
        started_at = _parse_decimal(
            raw_started_at,
            f"screencap start timestamp at line {line_number}",
        )
        completed_at = _parse_decimal(
            raw_completed_at,
            f"screencap completion timestamp at line {line_number}",
        )
        if (
            completed_at < started_at
            or completed_at - started_at > maximum_frame_duration
        ):
            raise RawScreencapError(
                f"Invalid screencap duration at line {line_number}"
            )
        try:
            size = int(raw_size)
        except ValueError as error:
            raise RawScreencapError(
                f"Invalid screencap frame size at line {line_number}"
            ) from error
        if (
            size <= RAW_HEADER.size
            or size > maximum_frame_bytes
            or str(size) != raw_size
        ):
            raise RawScreencapError(
                f"Invalid screencap frame size at line {line_number}"
            )
        if name <= previous_name:
            raise RawScreencapError(
                f"Non-monotonic screencap filename at line {line_number}"
            )
        if previous_started_at is not None and started_at <= previous_started_at:
            raise RawScreencapError(
                f"Non-monotonic screencap timestamp at line {line_number}"
            )
        if previous_completed_at is not None and started_at < previous_completed_at:
            raise RawScreencapError(
                f"Overlapping screencap interval at line {line_number}"
            )
        records.append(
            ManifestRecord(
                name=name,
                started_at=started_at,
                completed_at=completed_at,
                size=size,
            )
        )
        previous_name = name
        previous_started_at = started_at
        previous_completed_at = completed_at
    if not records:
        raise RawScreencapError("Screencap manifest is empty")
    return records


def read_raw_frame(
    path: Path,
    expected_width: int,
    expected_height: int,
    maximum_frame_bytes: int,
    expected_size: int | None = None,
) -> RawFrame:
    _require_regular_file(path, "Raw screencap frame")
    file_size = path.stat().st_size
    if expected_size is not None and file_size != expected_size:
        raise RawScreencapError(f"Raw screencap size disagrees with manifest: {path.name}")
    if file_size <= RAW_HEADER.size or file_size > maximum_frame_bytes:
        raise RawScreencapError(f"Invalid raw screencap size: {path.name}")
    payload = path.read_bytes()
    if len(payload) != file_size:
        raise RawScreencapError(f"Unable to read the complete raw frame: {path.name}")
    width, height, pixel_format, colorspace = RAW_HEADER.unpack_from(payload)
    if (width, height) != (expected_width, expected_height):
        raise RawScreencapError(
            f"Unexpected raw screencap dimensions in {path.name}: "
            f"{width}x{height}, expected {expected_width}x{expected_height}"
        )
    if pixel_format != RGBA_8888:
        raise RawScreencapError(
            f"Unexpected raw screencap pixel format in {path.name}: "
            f"{pixel_format}, expected RGBA_8888 ({RGBA_8888})"
        )
    if colorspace not in VALID_COLORSPACES:
        raise RawScreencapError(
            f"Unexpected raw screencap colorspace in {path.name}: {colorspace}"
        )
    expected_file_size = RAW_HEADER.size + expected_width * expected_height * 4
    if file_size != expected_file_size:
        raise RawScreencapError(
            f"Truncated or extended raw screencap {path.name}: "
            f"{file_size} bytes, expected {expected_file_size}"
        )
    return RawFrame(
        width=width,
        height=height,
        pixel_format=pixel_format,
        colorspace=colorspace,
        payload=payload[RAW_HEADER.size :],
        size=file_size,
        sha256=hashlib.sha256(payload).hexdigest(),
    )


def _png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    checksum = zlib.crc32(chunk_type)
    checksum = zlib.crc32(data, checksum) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", checksum)


def _write_png_stream(target: BinaryIO, frame: RawFrame) -> None:
    target.write(PNG_SIGNATURE)
    target.write(
        _png_chunk(
            b"IHDR",
            struct.pack(">IIBBBBB", frame.width, frame.height, 8, 6, 0, 0, 0),
        )
    )
    if frame.colorspace == 1:
        target.write(_png_chunk(b"sRGB", b"\x00"))
    elif frame.colorspace == 2:
        # AOSP's value 2 is Display-P3 (D65 white point with the sRGB transfer
        # function). Keep the numeric RGBA samples and describe that space
        # instead of silently labelling them as sRGB.
        target.write(
            _png_chunk(
                b"cHRM",
                struct.pack(
                    ">8I",
                    31270,
                    32900,
                    68000,
                    32000,
                    26500,
                    69000,
                    15000,
                    6000,
                ),
            )
        )
        target.write(_png_chunk(b"gAMA", struct.pack(">I", 45455)))
        target.write(_png_chunk(b"cICP", bytes((12, 13, 0, 1))))
    compressor = zlib.compressobj(level=6)
    row_bytes = frame.width * 4
    for row_index in range(frame.height):
        start = row_index * row_bytes
        compressed = compressor.compress(b"\x00" + frame.payload[start : start + row_bytes])
        if compressed:
            target.write(_png_chunk(b"IDAT", compressed))
    compressed = compressor.flush()
    if compressed:
        target.write(_png_chunk(b"IDAT", compressed))
    target.write(_png_chunk(b"IEND", b""))


def _published_path(
    path: Path,
    details: os.stat_result,
    *,
    track_size: bool = True,
) -> PublishedPath:
    return PublishedPath(
        path=path,
        device=details.st_dev,
        inode=details.st_ino,
        size=details.st_size if track_size else None,
    )


def _path_matches(published: PublishedPath) -> bool:
    try:
        details = os.lstat(published.path)
    except FileNotFoundError:
        return False
    return (
        stat.S_ISREG(details.st_mode)
        and details.st_dev == published.device
        and details.st_ino == published.inode
        and (published.size is None or details.st_size == published.size)
    )


def _unlink_if_owned(published: PublishedPath) -> bool:
    if not _path_matches(published):
        return not os.path.lexists(published.path)
    try:
        published.path.unlink()
    except FileNotFoundError:
        return True
    return True


def _publish_without_replacement(
    partial: PublishedPath,
    target: Path,
    label: str,
) -> PublishedPath:
    if partial.size is None or not _path_matches(partial):
        raise RawScreencapError(f"{label} staging file changed before publication")
    try:
        os.link(partial.path, target)
    except FileExistsError as error:
        raise RawScreencapError(f"Refusing to overwrite {label}: {target}") from error
    published = PublishedPath(
        path=target,
        device=partial.device,
        inode=partial.inode,
        size=partial.size,
    )
    if not _path_matches(published):
        raise RawScreencapError(f"{label} changed during publication: {target}")
    if not _unlink_if_owned(partial):
        _unlink_if_owned(published)
        raise RawScreencapError(f"{label} staging file changed during publication")
    return published


def write_png(path: Path, frame: RawFrame) -> PublishedPath:
    partial_path = path.with_name(f"{path.name}.partial")
    if os.path.lexists(path) or os.path.lexists(partial_path):
        raise RawScreencapError(f"Refusing to overwrite converted evidence: {path}")
    partial: PublishedPath | None = None
    try:
        with partial_path.open("xb") as target:
            partial = _published_path(
                partial_path,
                os.fstat(target.fileno()),
                track_size=False,
            )
            _write_png_stream(target, frame)
            target.flush()
            partial = _published_path(partial_path, os.fstat(target.fileno()))
        return _publish_without_replacement(
            partial,
            path,
            "converted evidence",
        )
    except FileExistsError as error:
        raise RawScreencapError(
            f"Refusing to overwrite converted evidence staging: {partial_path}"
        ) from error
    except BaseException:
        if partial is not None:
            _unlink_if_owned(partial)
        raise


def _write_text_atomically(path: Path, lines: Iterable[str]) -> PublishedPath:
    partial_path = path.with_name(f"{path.name}.partial")
    if os.path.lexists(path) or os.path.lexists(partial_path):
        raise RawScreencapError(f"Refusing to overwrite converted metadata: {path}")
    partial: PublishedPath | None = None
    try:
        with partial_path.open("x", encoding="utf-8", newline="\n") as target:
            partial = _published_path(
                partial_path,
                os.fstat(target.fileno()),
                track_size=False,
            )
            for line in lines:
                target.write(line)
                target.write("\n")
            target.flush()
            partial = _published_path(partial_path, os.fstat(target.fileno()))
        return _publish_without_replacement(
            partial,
            path,
            "converted metadata",
        )
    except FileExistsError as error:
        raise RawScreencapError(
            f"Refusing to overwrite converted metadata staging: {partial_path}"
        ) from error
    except BaseException:
        if partial is not None:
            _unlink_if_owned(partial)
        raise


def convert_sequence(args: argparse.Namespace) -> None:
    manifest_path = Path(args.manifest)
    raw_directory = Path(args.raw_directory)
    png_directory = Path(args.png_directory)
    home_raw_path = Path(args.home_raw)
    home_png_path = Path(args.home_png)
    metadata_path = Path(args.metadata)
    png_manifest_path = Path(args.png_manifest)
    maximum_frame_duration = _parse_decimal(
        args.maximum_frame_duration,
        "maximum frame duration",
    )
    maximum_frame_bytes = args.maximum_frame_bytes
    if args.expected_width <= 0 or args.expected_height <= 0:
        raise RawScreencapError("Expected dimensions must be positive")
    if maximum_frame_duration <= 0:
        raise RawScreencapError("Maximum frame duration must be positive")
    if maximum_frame_bytes <= RAW_HEADER.size:
        raise RawScreencapError("Maximum frame size is too small")
    if raw_directory.is_symlink() or not raw_directory.is_dir():
        raise RawScreencapError(f"Raw frame directory is invalid: {raw_directory}")
    if png_directory.is_symlink() or not png_directory.is_dir():
        raise RawScreencapError(f"PNG frame directory is invalid: {png_directory}")
    if any(png_directory.iterdir()):
        raise RawScreencapError(f"PNG frame directory is not empty: {png_directory}")

    records = read_manifest(
        manifest_path,
        maximum_frame_duration,
        maximum_frame_bytes,
    )
    expected_raw_names = [record.name for record in records]
    actual_raw_names = sorted(path.name for path in raw_directory.iterdir())
    if actual_raw_names != expected_raw_names:
        raise RawScreencapError(
            "Raw screencap files do not exactly match the timestamp manifest"
        )
    if any(raw_directory.glob("*.partial")):
        raise RawScreencapError("Partial raw screencap files remain in the sequence")

    created_paths: list[PublishedPath] = []
    metadata_lines = [
        "name\twidth\theight\tpixel_format\tcolorspace\tbytes\tsha256"
    ]
    png_manifest_lines: list[str] = []
    try:
        home_frame = read_raw_frame(
            home_raw_path,
            args.expected_width,
            args.expected_height,
            maximum_frame_bytes,
        )
        created_paths.append(write_png(home_png_path, home_frame))
        metadata_lines.append(
            "\t".join(
                (
                    home_raw_path.name,
                    str(home_frame.width),
                    str(home_frame.height),
                    str(home_frame.pixel_format),
                    str(home_frame.colorspace),
                    str(home_frame.size),
                    home_frame.sha256,
                )
            )
        )
        for record in records:
            raw_path = raw_directory / record.name
            frame = read_raw_frame(
                raw_path,
                args.expected_width,
                args.expected_height,
                maximum_frame_bytes,
                expected_size=record.size,
            )
            png_path = png_directory / f"{Path(record.name).stem}.png"
            published_png = write_png(png_path, frame)
            created_paths.append(published_png)
            png_manifest_lines.append(
                "\t".join(
                    (
                        png_path.name,
                        str(record.started_at),
                        str(record.completed_at),
                        str(published_png.size),
                    )
                )
            )
            metadata_lines.append(
                "\t".join(
                    (
                        record.name,
                        str(frame.width),
                        str(frame.height),
                        str(frame.pixel_format),
                        str(frame.colorspace),
                        str(frame.size),
                        frame.sha256,
                    )
                )
            )
        created_paths.append(_write_text_atomically(metadata_path, metadata_lines))
        created_paths.append(
            _write_text_atomically(png_manifest_path, png_manifest_lines)
        )
    except BaseException:
        for created_path in reversed(created_paths):
            _unlink_if_owned(created_path)
        raise
    print(f"validated_raw_screencap_frames={len(records)}")


def _fixture_pixels(width: int, height: int) -> bytes:
    return bytes(
        component
        for pixel_index in range(width * height)
        for component in (
            pixel_index % 256,
            (pixel_index * 3) % 256,
            (pixel_index * 7) % 256,
            255,
        )
    )


def _write_raw_fixture(
    path: Path,
    width: int,
    height: int,
    pixel_format: int = RGBA_8888,
    colorspace: int = 1,
    suffix: bytes = b"",
) -> int:
    pixels = _fixture_pixels(width, height)
    payload = RAW_HEADER.pack(width, height, pixel_format, colorspace) + pixels + suffix
    path.write_bytes(payload)
    return len(payload)


def _read_test_png(path: Path, width: int, height: int) -> tuple[bytes, set[bytes]]:
    payload = path.read_bytes()
    if not payload.startswith(PNG_SIGNATURE):
        raise RawScreencapError("Self-test produced an invalid PNG signature")
    offset = len(PNG_SIGNATURE)
    image_data = bytearray()
    chunk_types: set[bytes] = set()
    while offset < len(payload):
        if offset + 12 > len(payload):
            raise RawScreencapError("Self-test produced a truncated PNG chunk")
        length = struct.unpack_from(">I", payload, offset)[0]
        chunk_type = payload[offset + 4 : offset + 8]
        end = offset + 12 + length
        if end > len(payload):
            raise RawScreencapError("Self-test produced a truncated PNG payload")
        chunk_data = payload[offset + 8 : offset + 8 + length]
        expected_crc = struct.unpack_from(">I", payload, offset + 8 + length)[0]
        actual_crc = zlib.crc32(chunk_type)
        actual_crc = zlib.crc32(chunk_data, actual_crc) & 0xFFFFFFFF
        if actual_crc != expected_crc:
            raise RawScreencapError("Self-test produced an invalid PNG checksum")
        chunk_types.add(chunk_type)
        if chunk_type == b"IDAT":
            image_data.extend(chunk_data)
        offset = end
        if chunk_type == b"IEND":
            break
    if offset != len(payload):
        raise RawScreencapError("Self-test produced trailing PNG data")
    decoded = zlib.decompress(image_data)
    row_bytes = width * 4
    expected_decoded_size = height * (row_bytes + 1)
    if len(decoded) != expected_decoded_size:
        raise RawScreencapError("Self-test produced an invalid decoded PNG size")
    pixels = bytearray()
    for row_index in range(height):
        start = row_index * (row_bytes + 1)
        if decoded[start] != 0:
            raise RawScreencapError("Self-test produced an unexpected PNG filter")
        pixels.extend(decoded[start + 1 : start + 1 + row_bytes])
    return bytes(pixels), chunk_types


def self_test() -> None:
    global _write_png_stream

    with tempfile.TemporaryDirectory(prefix="kwabor-screencap-raw-") as temporary:
        root = Path(temporary)
        raw_directory = root / "raw"
        png_directory = root / "png"
        raw_directory.mkdir()
        png_directory.mkdir()
        home_raw = root / "prelaunch-home.raw"
        home_png = root / "prelaunch-home.png"
        manifest = root / "timestamps.tsv"
        metadata = root / "metadata.tsv"
        png_manifest = root / "png-timestamps.tsv"
        _write_raw_fixture(home_raw, 2, 2)
        first_size = _write_raw_fixture(raw_directory / "frame-00000.raw", 2, 2)
        second_size = _write_raw_fixture(raw_directory / "frame-00002.raw", 2, 2, colorspace=2)
        manifest.write_text(
            "\n".join(
                (
                    f"frame-00000.raw\t1.000\t1.125\t{first_size}",
                    f"frame-00002.raw\t1.450\t1.600\t{second_size}",
                )
            )
            + "\n",
            encoding="utf-8",
        )
        args = argparse.Namespace(
            manifest=str(manifest),
            raw_directory=str(raw_directory),
            png_directory=str(png_directory),
            home_raw=str(home_raw),
            home_png=str(home_png),
            metadata=str(metadata),
            png_manifest=str(png_manifest),
            expected_width=2,
            expected_height=2,
            maximum_frame_duration="1",
            maximum_frame_bytes=1024,
        )
        convert_sequence(args)
        converted = sorted(path.name for path in png_directory.iterdir())
        if converted != ["frame-00000.png", "frame-00002.png"]:
            raise RawScreencapError("Self-test did not convert the exact frame set")
        if home_png.read_bytes()[:8] != PNG_SIGNATURE:
            raise RawScreencapError("Self-test produced an invalid HOME PNG")
        first_pixels, first_chunks = _read_test_png(
            png_directory / "frame-00000.png",
            2,
            2,
        )
        second_pixels, second_chunks = _read_test_png(
            png_directory / "frame-00002.png",
            2,
            2,
        )
        if first_pixels != _fixture_pixels(2, 2) or b"sRGB" not in first_chunks:
            raise RawScreencapError("Self-test did not preserve sRGB RGBA pixels")
        if second_pixels != _fixture_pixels(2, 2) or b"cICP" not in second_chunks:
            raise RawScreencapError("Self-test did not preserve Display-P3 RGBA pixels")
        metadata_lines = metadata.read_text(encoding="utf-8").splitlines()
        if len(metadata_lines) != 4:
            raise RawScreencapError("Self-test produced invalid metadata")
        if len(png_manifest.read_text(encoding="utf-8").splitlines()) != 2:
            raise RawScreencapError("Self-test produced an invalid PNG manifest")

        race_target = root / "competing-output.png"
        race_partial = root / "competing-output.png.partial"
        race_frame = read_raw_frame(
            raw_directory / "frame-00000.raw",
            2,
            2,
            1024,
            expected_size=first_size,
        )
        original_link = os.link

        def publish_competitor(source: Path, target: Path) -> None:
            Path(target).write_bytes(b"competitor")
            original_link(source, target)

        os.link = publish_competitor
        try:
            try:
                write_png(race_target, race_frame)
            except RawScreencapError:
                pass
            else:
                raise RawScreencapError(
                    "Self-test overwrote a concurrently published target"
                )
        finally:
            os.link = original_link
        if race_target.read_bytes() != b"competitor" or race_partial.exists():
            raise RawScreencapError(
                "Self-test did not preserve a competing target atomically"
            )

        interrupted_png = root / "interrupted.png"
        interrupted_png_partial = root / "interrupted.png.partial"
        original_png_writer = _write_png_stream

        def interrupt_png(target: BinaryIO, frame: RawFrame) -> None:
            del frame
            target.write(PNG_SIGNATURE)
            raise KeyboardInterrupt

        _write_png_stream = interrupt_png
        try:
            try:
                write_png(interrupted_png, race_frame)
            except KeyboardInterrupt:
                pass
            else:
                raise RawScreencapError(
                    "Self-test accepted an interrupted PNG publication"
                )
        finally:
            _write_png_stream = original_png_writer
        if os.path.lexists(interrupted_png) or os.path.lexists(
            interrupted_png_partial
        ):
            raise RawScreencapError(
                "Self-test retained an interrupted PNG publication"
            )

        interrupted_metadata = root / "interrupted-metadata.tsv"
        interrupted_metadata_partial = root / "interrupted-metadata.tsv.partial"

        def interrupted_lines() -> Iterable[str]:
            yield "written-before-interruption"
            raise KeyboardInterrupt

        try:
            _write_text_atomically(interrupted_metadata, interrupted_lines())
        except KeyboardInterrupt:
            pass
        else:
            raise RawScreencapError(
                "Self-test accepted an interrupted metadata publication"
            )
        if os.path.lexists(interrupted_metadata) or os.path.lexists(
            interrupted_metadata_partial
        ):
            raise RawScreencapError(
                "Self-test retained an interrupted metadata publication"
            )

        malformed = root / "malformed.raw"
        malformed_size = _write_raw_fixture(malformed, 2, 2, suffix=b"x")
        try:
            read_raw_frame(malformed, 2, 2, malformed_size)
        except RawScreencapError:
            pass
        else:
            raise RawScreencapError("Self-test accepted an extended raw frame")
        wrong_format = root / "wrong-format.raw"
        wrong_format_size = _write_raw_fixture(
            wrong_format,
            2,
            2,
            pixel_format=2,
        )
        try:
            read_raw_frame(wrong_format, 2, 2, wrong_format_size)
        except RawScreencapError:
            pass
        else:
            raise RawScreencapError("Self-test accepted an unexpected pixel format")
    print("android_screencap_raw_self_test=ok")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("self-test")
    convert = subparsers.add_parser("convert")
    convert.add_argument("--manifest", required=True)
    convert.add_argument("--raw-directory", required=True)
    convert.add_argument("--png-directory", required=True)
    convert.add_argument("--home-raw", required=True)
    convert.add_argument("--home-png", required=True)
    convert.add_argument("--metadata", required=True)
    convert.add_argument("--png-manifest", required=True)
    convert.add_argument("--expected-width", required=True, type=int)
    convert.add_argument("--expected-height", required=True, type=int)
    convert.add_argument("--maximum-frame-duration", required=True)
    convert.add_argument("--maximum-frame-bytes", required=True, type=int)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "self-test":
            self_test()
        elif args.command == "convert":
            convert_sequence(args)
        else:
            raise RawScreencapError(f"Unsupported command: {args.command}")
    except (OSError, RawScreencapError) as error:
        print(f"ERROR android raw screencap: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
