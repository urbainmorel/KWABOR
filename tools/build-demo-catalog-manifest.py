#!/usr/bin/env python3
"""Build the immutable closed-beta catalog manifest from reviewed fragments."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import date
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CATALOG_ROOT = REPOSITORY_ROOT / "demo" / "catalog" / "v1"
FRAGMENT_ROOT = CATALOG_ROOT / "fragments"
CONFIG_PATH = CATALOG_ROOT / "seed-config.json"
OUTPUT_PATH = CATALOG_ROOT / "manifest.json"
FAMILIES = ("places", "events", "hotels", "restaurants")
FAMILY_ORDER = {family: index for index, family in enumerate(FAMILIES)}
MEDIA_REVIEWER = "Codex visual QA"
MEDIA_DECISION = "approved-for-closed-beta-demo"
MEDIA_RIGHTS_BASIS = "OpenAI-generated output for Kwabor closed-beta demonstration"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=OUTPUT_PATH,
        help="Manifest output path",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail when the generated manifest differs from the committed output",
    )
    return parser.parse_args()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_catalog_config() -> tuple[str, dict[str, str]]:
    config = load_json(CONFIG_PATH)
    if not isinstance(config, dict) or set(config) != {
        "schemaVersion",
        "catalogVersion",
        "catalogAnchorDate",
        "timeZone",
        "mediaRightsApproval",
    }:
        raise SystemExit(f"Invalid demo seed config shape: {CONFIG_PATH}")
    try:
        catalog_anchor_date = date.fromisoformat(config["catalogAnchorDate"]).isoformat()
    except (TypeError, ValueError) as error:
        raise SystemExit(f"Invalid catalogAnchorDate in {CONFIG_PATH}: {error}") from error
    approval = config["mediaRightsApproval"]
    expected_approval_keys = {"status", "approvedBy", "approvedAt", "scope"}
    if not isinstance(approval, dict) or set(approval) != expected_approval_keys:
        raise SystemExit(f"Invalid media rights approval shape in {CONFIG_PATH}")
    if (
        approval["status"] != "approved-by-product-owner"
        or approval["approvedBy"] != "Kwabor product owner"
        or approval["scope"] != "closed-beta-demo-only"
        or date.fromisoformat(approval["approvedAt"]).isoformat() != approval["approvedAt"]
    ):
        raise SystemExit(f"Media rights approval is not valid for the closed beta: {CONFIG_PATH}")
    return catalog_anchor_date, approval


def verify_sources() -> None:
    subprocess.run(
        [sys.executable, "-B", "tools/verify-demo-catalog.py", "--require-media"],
        cwd=REPOSITORY_ROOT,
        check=True,
    )


def load_media_metadata() -> dict[tuple[str, int], dict[str, Any]]:
    metadata: dict[tuple[str, int], dict[str, Any]] = {}
    for family in FAMILIES:
        for row in load_json(FRAGMENT_ROOT / f"{family}-media.json"):
            key = (row["listingId"], row["displayOrder"])
            metadata[key] = row
    return metadata


def build_listings(rights_approval: dict[str, str]) -> list[dict[str, Any]]:
    metadata = load_media_metadata()
    combined: list[tuple[str, dict[str, Any]]] = []
    for family in FAMILIES:
        combined.extend((family, listing) for listing in load_json(FRAGMENT_ROOT / f"{family}.json"))
    combined.sort(key=lambda item: (FAMILY_ORDER[item[0]], item[1]["cityId"], item[1]["name"], item[1]["id"]))
    listings: list[dict[str, Any]] = []
    for family, source in combined:
        listing = dict(source)
        listing["family"] = family
        enriched_media = []
        for item in source["media"]:
            key = (source["id"], item["displayOrder"])
            generated = metadata[key]
            enriched = dict(item)
            enriched.update(
                {
                    "storagePath": generated["storagePath"],
                    "sha256": generated["sha256"],
                    "byteSize": generated["byteSize"],
                    "width": generated["width"],
                    "height": generated["height"],
                    "format": generated["format"],
                    "progressive": generated["progressive"],
                    "quality": generated["quality"],
                    "generator": generated["generator"],
                    "generatedAt": generated["generatedAt"],
                    "reviewStatus": generated["reviewStatus"],
                    "reviewer": MEDIA_REVIEWER,
                    "decision": MEDIA_DECISION,
                    "rightsBasis": MEDIA_RIGHTS_BASIS,
                    "rightsApprovalStatus": rights_approval["status"],
                    "disclosureRequired": True,
                }
            )
            enriched_media.append(enriched)
        listing["media"] = enriched_media
        listings.append(listing)
    return listings


def main() -> None:
    args = parse_args()
    verify_sources()
    catalog_anchor_date, media_rights_approval = load_catalog_config()
    manifest = {
        "schemaVersion": 1,
        "catalogId": "kwabor-closed-beta-v1",
        "decision": "ADR-0036",
        "generatedAt": catalog_anchor_date,
        "catalogAnchorDate": catalog_anchor_date,
        "mediaRightsApproval": media_rights_approval,
        "environment": "staging-only",
        "demoDisclosure": "Données fictives — bêta fermée",
        "storage": {
            "bucket": "kwabor-catalog-demo",
            "publicRead": True,
            "clientWrites": False,
            "contentType": "image/jpeg",
            "cacheControl": "public,max-age=31536000,immutable",
            "upsert": False,
        },
        "counts": {
            "listings": 60,
            "places": 15,
            "events": 15,
            "hotels": 15,
            "restaurants": 15,
            "media": 180,
        },
        "listings": build_listings(media_rights_approval),
    }
    rendered = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if args.check:
        if not args.output.is_file() or args.output.read_text(encoding="utf-8") != rendered:
            raise SystemExit(f"Stale demo catalog manifest: {args.output}")
        print(f"OK verified {args.output.relative_to(REPOSITORY_ROOT)} with 60 listings and 180 media")
        return

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8", newline="\n")
    print(f"OK wrote {args.output.relative_to(REPOSITORY_ROOT)} with 60 listings and 180 media")


if __name__ == "__main__":
    main()
