#!/usr/bin/env python3
"""Create one immutable JPEG served by the closed-beta demo catalog."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageOps


SERVED_SIZE = (960, 1280)
TARGET_BYTES = 240 * 1024
MAX_BYTES = 320 * 1024
MIN_QUALITY = 62
MAX_QUALITY = 84
UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
ROLE_PATTERN = re.compile(r"^[a-z]+(?:-[a-z]+)*$")


class DemoImageError(ValueError):
    """Raised when a generated master cannot satisfy the serving contract."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="ImageGen master to process")
    parser.add_argument("--output-root", required=True, type=Path, help="Versioned media directory")
    parser.add_argument("--listing-id", required=True, help="Canonical listing UUID")
    parser.add_argument("--display-order", required=True, type=int, choices=(0, 1, 2))
    parser.add_argument("--role", required=True, help="cover or gallery")
    return parser.parse_args()


def validate_identity(listing_id: str, display_order: int, role: str) -> None:
    if UUID_PATTERN.fullmatch(listing_id) is None:
        raise DemoImageError(f"Invalid listing UUID: {listing_id}")
    if ROLE_PATTERN.fullmatch(role) is None:
        raise DemoImageError(f"Invalid media role: {role}")
    expected_role = "cover" if display_order == 0 else "gallery"
    if role != expected_role:
        raise DemoImageError(
            f"Display order {display_order} requires role {expected_role}, received {role}"
        )


def flatten_alpha(source: Image.Image) -> Image.Image:
    normalized = ImageOps.exif_transpose(source)
    if normalized.mode in ("RGBA", "LA") or "transparency" in normalized.info:
        rgba = normalized.convert("RGBA")
        background = Image.new("RGBA", rgba.size, "white")
        background.alpha_composite(rgba)
        return background.convert("RGB")
    return normalized.convert("RGB")


def encode_jpeg(image: Image.Image, quality: int) -> bytes:
    output = BytesIO()
    image.save(
        output,
        format="JPEG",
        quality=quality,
        optimize=True,
        progressive=True,
        subsampling=2,
    )
    return output.getvalue()


def choose_encoding(image: Image.Image) -> tuple[bytes, int]:
    candidates: list[tuple[bytes, int]] = []
    for quality in range(MAX_QUALITY, MIN_QUALITY - 1, -2):
        payload = encode_jpeg(image, quality)
        if len(payload) <= MAX_BYTES:
            candidates.append((payload, quality))
            if len(payload) <= TARGET_BYTES:
                return payload, quality
    if candidates:
        return candidates[-1]
    raise DemoImageError(
        f"Image exceeds {MAX_BYTES} bytes even at JPEG quality {MIN_QUALITY}"
    )


def process_image(source_path: Path) -> tuple[bytes, int]:
    if not source_path.is_file():
        raise DemoImageError(f"Missing generated master: {source_path}")
    with Image.open(source_path) as source:
        if source.width < SERVED_SIZE[0] or source.height < SERVED_SIZE[1]:
            raise DemoImageError(
                f"Master is too small: {source.width}x{source.height}; "
                f"minimum is {SERVED_SIZE[0]}x{SERVED_SIZE[1]}"
            )
        rgb = flatten_alpha(source)
        served = ImageOps.fit(
            rgb,
            SERVED_SIZE,
            method=Image.Resampling.LANCZOS,
            centering=(0.5, 0.5),
        )
        return choose_encoding(served)


def write_immutable(
    output_root: Path,
    listing_id: str,
    display_order: int,
    role: str,
    payload: bytes,
) -> tuple[Path, str]:
    digest = hashlib.sha256(payload).hexdigest()
    filename = (
        f"{display_order:02d}-{role}-{digest[:12]}-"
        f"{SERVED_SIZE[0]}x{SERVED_SIZE[1]}.jpg"
    )
    relative_path = Path("v1") / listing_id / filename
    output_path = output_root / relative_path
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.exists() and output_path.read_bytes() != payload:
        raise DemoImageError(f"Immutable output path already differs: {output_path}")
    output_path.write_bytes(payload)
    return relative_path, digest


def main() -> None:
    args = parse_args()
    validate_identity(args.listing_id, args.display_order, args.role)
    payload, quality = process_image(args.input)
    relative_path, digest = write_immutable(
        args.output_root,
        args.listing_id,
        args.display_order,
        args.role,
        payload,
    )
    print(
        json.dumps(
            {
                "storagePath": relative_path.as_posix(),
                "sha256": digest,
                "byteSize": len(payload),
                "width": SERVED_SIZE[0],
                "height": SERVED_SIZE[1],
                "format": "image/jpeg",
                "progressive": True,
                "quality": quality,
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
