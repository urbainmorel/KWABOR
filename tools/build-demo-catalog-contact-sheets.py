#!/usr/bin/env python3
"""Build four review contact sheets for the closed-beta catalog media."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CATALOG_ROOT = REPOSITORY_ROOT / "demo" / "catalog" / "v1"
MANIFEST_PATH = CATALOG_ROOT / "manifest.json"
MEDIA_ROOT = CATALOG_ROOT / "media"
FAMILIES = ("places", "events", "hotels", "restaurants")
EXPECTED_PER_FAMILY = 45
COLUMNS = 5
ROWS = 9
CELL_WIDTH = 192
IMAGE_HEIGHT = 256
LABEL_HEIGHT = 42
HEADER_HEIGHT = 54


class ContactSheetError(ValueError):
    """Raised when reviewed catalog media cannot produce the expected sheets."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContactSheetError(message)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-directory",
        type=Path,
        default=REPOSITORY_ROOT / "build" / "demo-catalog-contact-sheets",
    )
    return parser.parse_args()


def load_reviewed_media() -> dict[str, list[tuple[str, int, Path]]]:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    approval = manifest.get("mediaRightsApproval")
    require(
        isinstance(approval, dict)
        and approval.get("status") == "approved-by-product-owner",
        "Media rights must be approved before contact sheets are authoritative",
    )
    grouped = {family: [] for family in FAMILIES}
    for listing in manifest.get("listings", []):
        family = listing.get("family")
        require(family in grouped, f"Unknown listing family: {family}")
        listing_id = listing.get("id")
        media_rows = listing.get("media", [])
        require(len(media_rows) == 3, f"{listing_id} must expose three reviewed media")
        for media in media_rows:
            require(media.get("reviewStatus") == "approved", f"Unapproved media for {listing_id}")
            require(
                media.get("rightsApprovalStatus") == approval["status"],
                f"Unapproved media rights for {listing_id}",
            )
            path = MEDIA_ROOT / media["storagePath"]
            require(path.is_file(), f"Missing served media: {path}")
            grouped[family].append((listing_id, media["displayOrder"], path))
    for family, rows in grouped.items():
        rows.sort(key=lambda row: (row[0], row[1]))
        require(len(rows) == EXPECTED_PER_FAMILY, f"{family} must contain 45 reviewed media")
    return grouped


def build_sheet(family: str, media_rows: list[tuple[str, int, Path]], output_path: Path) -> None:
    cell_height = IMAGE_HEIGHT + LABEL_HEIGHT
    canvas = Image.new(
        "RGB",
        (COLUMNS * CELL_WIDTH, HEADER_HEIGHT + ROWS * cell_height),
        "white",
    )
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.load_default()
    draw.rectangle((0, 0, canvas.width, HEADER_HEIGHT), fill=(18, 18, 18))
    draw.text(
        (16, 18),
        f"Kwabor closed beta — {family} — 45 reviewed media",
        fill="white",
        font=font,
    )
    for index, (listing_id, display_order, source_path) in enumerate(media_rows):
        column = index % COLUMNS
        row = index // COLUMNS
        left = column * CELL_WIDTH
        top = HEADER_HEIGHT + row * cell_height
        with Image.open(source_path) as source:
            thumbnail = ImageOps.fit(
                source.convert("RGB"),
                (CELL_WIDTH, IMAGE_HEIGHT),
                method=Image.Resampling.LANCZOS,
            )
        canvas.paste(thumbnail, (left, top))
        label_top = top + IMAGE_HEIGHT
        draw.rectangle((left, label_top, left + CELL_WIDTH, label_top + LABEL_HEIGHT), fill="white")
        draw.text(
            (left + 6, label_top + 6),
            f"{listing_id[-4:]} · image {display_order + 1}/3",
            fill=(18, 18, 18),
            font=font,
        )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output_path, format="JPEG", quality=84, optimize=True, progressive=True)


def main() -> None:
    args = parse_args()
    grouped = load_reviewed_media()
    for family in FAMILIES:
        output_path = args.output_directory / f"{family}-contact-sheet.jpg"
        build_sheet(family, grouped[family], output_path)
        print(f"OK wrote {output_path.relative_to(REPOSITORY_ROOT)}")


if __name__ == "__main__":
    main()
