#!/usr/bin/env python3
"""Validate the closed-beta catalog sources and, when present, served media."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

from PIL import Image


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CATALOG_ROOT = REPOSITORY_ROOT / "demo" / "catalog" / "v1"
FRAGMENT_ROOT = CATALOG_ROOT / "fragments"
MEDIA_ROOT = CATALOG_ROOT / "media"
FRAGMENTS = ("places", "events", "hotels", "restaurants")
EXPECTED_VARIANTS = {
    "places": "place",
    "events": "event",
    "hotels": "lodging",
    "restaurants": "food",
}
EXPECTED_TYPES = {
    "places": ("lieu", {"historique", "nature", "marche"}),
    "events": ("evenement", {"culture"}),
    "hotels": ("etablissement", {"hotel"}),
    "restaurants": ("etablissement", {"restaurant"}),
}
EXPECTED_CITIES = {"cotonou", "ouidah", "porto-novo"}
CANONICAL_FIXTURE_IDS = {
    "00000000-0000-4000-8000-000000000101",
    "00000000-0000-4000-8000-000000000102",
    "00000000-0000-4000-8000-000000000103",
    "00000000-0000-4000-8000-000000000104",
}
DEMO_REPLACEMENT_IDS = {
    "00000000-0000-4000-8000-000000000214",
    "00000000-0000-4000-8000-000000000215",
    "00000000-0000-4000-8000-000000000315",
    "00000000-0000-4000-8000-000000000515",
}
DESCRIPTION_SUFFIX = "Contenu fictif créé pour la bêta fermée Kwabor."
UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
TAG_PATTERN = re.compile(r"^[^\x00-\x1f\x7f]{1,24}$")
TEST_EMAIL_PATTERN = re.compile(r"^[^\s@]+@[^\s@]+\.test$")
TEST_URL_PATTERN = re.compile(r"^https://[a-z0-9.-]+\.test(?:/[^\s#]*)?$")
SHA_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SERVED_SIZE = (960, 1280)
MAX_FILE_BYTES = 320 * 1024
MAX_CORPUS_BYTES = 48 * 1024 * 1024


class CatalogValidationError(ValueError):
    """Raised when one or more beta-catalog invariants are violated."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CatalogValidationError(message)


def load_json(path: Path) -> Any:
    require(path.is_file(), f"Missing JSON source: {path.relative_to(REPOSITORY_ROOT)}")
    payload = path.read_bytes()
    require(not payload.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is forbidden: {path}")
    try:
        return json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CatalogValidationError(f"Invalid UTF-8 JSON {path}: {error}") from error


def verify_text(value: Any, label: str, minimum: int, maximum: int) -> str:
    require(isinstance(value, str), f"{label} must be a string")
    require(value == value.strip(), f"{label} must have canonical edges")
    require("\n" not in value and "\r" not in value, f"{label} must be single-line")
    require(minimum <= len(value) <= maximum, f"{label} length must be {minimum}..{maximum}")
    return value


def verify_opening_hours(value: Any, label: str, establishment: bool) -> None:
    require(isinstance(value, dict), f"{label} must be an object")
    if not establishment:
        require(not value or set(value) == DAYS, f"{label} must be empty or seven-day")
        return
    require(set(value) == DAYS, f"{label} must contain exactly seven days")
    for day, entry in value.items():
        require(isinstance(entry, dict), f"{label}.{day} must be an object")
        require(set(entry) == {"status", "periods"}, f"{label}.{day} has invalid keys")
        status = entry["status"]
        periods = entry["periods"]
        require(status in {"closed", "open_24_hours", "periods"}, f"{label}.{day} status")
        require(isinstance(periods, list), f"{label}.{day}.periods must be a list")
        require((status == "periods") == bool(periods), f"{label}.{day} periods mismatch")
        previous_close = None
        for period in periods:
            require(
                isinstance(period, dict)
                and set(period) == {"opens_minute", "closes_minute", "closes_next_day"},
                f"{label}.{day} invalid period",
            )
            opens = period["opens_minute"]
            closes = period["closes_minute"]
            next_day = period["closes_next_day"]
            require(type(opens) is int and 0 <= opens <= 1439, f"{label}.{day} opens")
            require(type(closes) is int and 0 <= closes <= 1439, f"{label}.{day} closes")
            require(type(next_day) is bool, f"{label}.{day} closes_next_day")
            require(closes <= opens if next_day else closes > opens, f"{label}.{day} interval")
            require(previous_close is None or opens >= previous_close, f"{label}.{day} overlap")
            previous_close = closes


DAYS = {
    "monday",
    "tuesday",
    "wednesday",
    "thursday",
    "friday",
    "saturday",
    "sunday",
}


def verify_media_sources(listing: dict[str, Any], seen_alts: set[str], seen_prompts: set[str]) -> None:
    media = listing.get("media")
    require(isinstance(media, list) and len(media) == 3, f"{listing['id']} requires three media")
    for order, item in enumerate(media):
        label = f"{listing['id']}.media[{order}]"
        require(isinstance(item, dict), f"{label} must be an object")
        require(item.get("displayOrder") == order, f"{label} display order mismatch")
        require(item.get("role") == ("cover" if order == 0 else "gallery"), f"{label} role")
        require(item.get("isCover") is (order == 0), f"{label} cover mismatch")
        require(item.get("kind") == "image", f"{label} must be an image")
        alt = verify_text(item.get("alt"), f"{label}.alt", 60, 160)
        prompt = verify_text(item.get("prompt"), f"{label}.prompt", 120, 1200)
        require(alt not in seen_alts, f"Duplicate alt: {alt}")
        require(prompt not in seen_prompts, f"Duplicate media prompt for {listing['id']}")
        seen_alts.add(alt)
        seen_prompts.add(prompt)
        lowered = prompt.lower()
        for forbidden in ("texte", "logo", "personne"):
            require(forbidden in lowered, f"{label}.prompt must forbid {forbidden}")


def verify_detail(listing: dict[str, Any], family: str) -> None:
    detail = listing.get("detail")
    require(isinstance(detail, dict), f"{listing['id']} detail must be an object")
    require(detail.get("variant") == EXPECTED_VARIANTS[family], f"{listing['id']} detail variant")
    price = listing.get("priceFromXof")
    unit = listing.get("priceUnit")
    if family == "places":
        require(detail.get("placeCategory") == listing["subtype"], f"{listing['id']} place subtype")
        is_free = detail.get("isFree")
        require(type(is_free) is bool, f"{listing['id']} place isFree")
        if is_free:
            require(price is None and unit == "aucune" and detail.get("entryFeeXof") is None, f"{listing['id']} free price")
        else:
            require(type(price) is int and price > 0 and unit == "par_entree", f"{listing['id']} paid place price")
            require(detail.get("entryFeeXof") == price, f"{listing['id']} entry price mismatch")
    elif family == "events":
        require(detail.get("category") == "culture", f"{listing['id']} event category")
        schedule = detail.get("schedule")
        require(isinstance(schedule, dict), f"{listing['id']} event schedule")
        require(schedule.get("kind") == "relative_to_seed_date", f"{listing['id']} schedule kind")
        require(schedule.get("timeZone") == "Africa/Porto-Novo", f"{listing['id']} timezone")
        require(type(schedule.get("startOffsetDays")) is int, f"{listing['id']} start offset")
        require(type(schedule.get("durationMinutes")) is int and 30 <= schedule["durationMinutes"] <= 1440, f"{listing['id']} duration")
        require(TEST_EMAIL_PATTERN.fullmatch(detail.get("organizerContact", "")) is not None, f"{listing['id']} event contact")
        tiers = detail.get("ticketTiers")
        require(isinstance(tiers, list), f"{listing['id']} ticket tiers")
        if detail.get("ticketType") == "gratuit":
            require(price is None and unit == "aucune" and not tiers and detail.get("ticketUrl") is None, f"{listing['id']} free event")
        else:
            require(detail.get("ticketType") == "payant" and tiers, f"{listing['id']} paid event")
            require(TEST_URL_PATTERN.fullmatch(detail.get("ticketUrl", "")) is not None, f"{listing['id']} test ticket URL")
            tier_prices = [tier.get("priceXof") for tier in tiers]
            require(all(type(value) is int and value > 0 for value in tier_prices), f"{listing['id']} tier price")
            require(price == min(tier_prices) and unit == "par_entree", f"{listing['id']} event price")
    elif family == "hotels":
        require(type(detail.get("starRating")) is int and 1 <= detail["starRating"] <= 5, f"{listing['id']} stars")
        require(type(detail.get("roomCount")) is int and detail["roomCount"] > 0, f"{listing['id']} rooms")
        room_types = detail.get("roomTypes")
        require(isinstance(room_types, list) and len(room_types) >= 2, f"{listing['id']} room types")
        room_prices = [room.get("priceXof") for room in room_types]
        require(all(type(value) is int and value > 0 for value in room_prices), f"{listing['id']} room prices")
        require(price == min(room_prices) and unit == "par_nuit", f"{listing['id']} lodging price")
    else:
        cuisines = detail.get("cuisines")
        meals = detail.get("meals")
        require(isinstance(cuisines, list) and cuisines, f"{listing['id']} cuisines")
        require(isinstance(meals, list) and meals, f"{listing['id']} meals")
        require(type(price) is int and price > 0 and unit == "par_personne", f"{listing['id']} food price")


def verify_listing(listing: Any, family: str, seen_alts: set[str], seen_prompts: set[str]) -> None:
    require(isinstance(listing, dict), f"{family} entry must be an object")
    listing_id = listing.get("id")
    require(isinstance(listing_id, str) and UUID_PATTERN.fullmatch(listing_id), f"Invalid UUID: {listing_id}")
    expected_type, subtypes = EXPECTED_TYPES[family]
    require(listing.get("type") == expected_type, f"{listing_id} type")
    require(listing.get("subtype") in subtypes, f"{listing_id} subtype")
    require(listing.get("status") == "publie", f"{listing_id} status")
    verify_text(listing.get("name"), f"{listing_id}.name", 3, 80)
    slug = verify_text(listing.get("slug"), f"{listing_id}.slug", 3, 160)
    require(SLUG_PATTERN.fullmatch(slug) is not None, f"{listing_id} slug")
    description = verify_text(listing.get("description"), f"{listing_id}.description", 40, 1500)
    require(description.endswith(DESCRIPTION_SUFFIX), f"{listing_id} disclosure suffix")
    verify_text(listing.get("demoDisclosure"), f"{listing_id}.demoDisclosure", 40, 500)
    require(listing.get("contentLang") == "fr", f"{listing_id} language")
    require(listing.get("cityId") in EXPECTED_CITIES, f"{listing_id} city")
    require(type(listing.get("lat")) in (int, float) and 6.0 <= listing["lat"] <= 12.6, f"{listing_id} latitude")
    require(type(listing.get("lng")) in (int, float) and 0.7 <= listing["lng"] <= 4.2, f"{listing_id} longitude")
    tags = listing.get("tags")
    require(isinstance(tags, list) and 3 <= len(tags) <= 10, f"{listing_id} tags")
    require(len(tags) == len(set(tags)) and all(isinstance(tag, str) and TAG_PATTERN.fullmatch(tag) for tag in tags), f"{listing_id} tag contract")
    require({"demo-kwabor", "contenu-fictif"} <= set(tags), f"{listing_id} demo tags")
    require(listing.get("verified") is False, f"{listing_id} must be unverified")
    require(listing.get("ratingAvg") is None, f"{listing_id} must not expose a rating")
    for counter in ("ratingCount", "viewsCount", "likesCount"):
        require(listing.get(counter) == 0, f"{listing_id}.{counter} must be zero")
    establishment = family in {"hotels", "restaurants"}
    verify_opening_hours(listing.get("openingHours"), f"{listing_id}.openingHours", establishment)
    if establishment:
        require(TEST_EMAIL_PATTERN.fullmatch(listing.get("email", "")) is not None, f"{listing_id} test email")
        amenities = listing.get("amenities")
        require(isinstance(amenities, list) and amenities, f"{listing_id} amenities")
    verify_detail(listing, family)
    verify_media_sources(listing, seen_alts, seen_prompts)


def load_and_verify_fragments() -> list[dict[str, Any]]:
    all_listings: list[dict[str, Any]] = []
    seen_alts: set[str] = set()
    seen_prompts: set[str] = set()
    family_by_id: dict[str, str] = {}
    for family in FRAGMENTS:
        listings = load_json(FRAGMENT_ROOT / f"{family}.json")
        require(isinstance(listings, list) and len(listings) == 15, f"{family} requires 15 listings")
        for listing in listings:
            verify_listing(listing, family, seen_alts, seen_prompts)
            listing_id = listing["id"]
            require(listing_id not in family_by_id, f"Duplicate listing UUID: {listing_id}")
            family_by_id[listing_id] = family
            all_listings.append(listing)
        city_counts = Counter(listing["cityId"] for listing in listings)
        require(city_counts == Counter({city: 5 for city in EXPECTED_CITIES}), f"{family} city distribution: {city_counts}")
    require(len(all_listings) == 60, "Catalog requires exactly 60 listings")
    listing_ids = set(family_by_id)
    require(not (CANONICAL_FIXTURE_IDS & listing_ids), "Demo catalog must not overwrite canonical fixtures")
    require(DEMO_REPLACEMENT_IDS <= listing_ids, "All four replacement demo listing IDs must be present")
    slugs = [listing["slug"] for listing in all_listings]
    require(len(slugs) == len(set(slugs)), "Listing slugs must be globally unique")
    place_counts = Counter(
        listing["subtype"] for listing in all_listings if family_by_id[listing["id"]] == "places"
    )
    require(place_counts == Counter({"historique": 5, "nature": 5, "marche": 5}), f"Place subtype distribution: {place_counts}")
    for listing in all_listings:
        if listing["type"] == "evenement":
            venue_id = listing["detail"].get("venueListingId")
            require(venue_id is None or venue_id in listing_ids, f"{listing['id']} unknown venue {venue_id}")
    return all_listings


def verify_served_media(listings: list[dict[str, Any]]) -> None:
    expected_keys = {(listing["id"], order) for listing in listings for order in range(3)}
    metadata_by_key: dict[tuple[str, int], dict[str, Any]] = {}
    for family in FRAGMENTS:
        rows = load_json(FRAGMENT_ROOT / f"{family}-media.json")
        require(isinstance(rows, list) and len(rows) == 45, f"{family}-media requires 45 rows")
        for row in rows:
            require(isinstance(row, dict), f"{family}-media entry must be object")
            key = (row.get("listingId"), row.get("displayOrder"))
            require(key in expected_keys and key not in metadata_by_key, f"Invalid/duplicate media key: {key}")
            metadata_by_key[key] = row
    require(set(metadata_by_key) == expected_keys, "Media metadata must cover every listing/order")
    seen_hashes: set[str] = set()
    total_bytes = 0
    for key, row in metadata_by_key.items():
        digest = row.get("sha256")
        storage_path = row.get("storagePath")
        require(isinstance(digest, str) and SHA_PATTERN.fullmatch(digest), f"{key} invalid SHA")
        require(isinstance(storage_path, str) and storage_path.startswith(f"v1/{key[0]}/"), f"{key} storage path")
        require(digest[:12] in storage_path, f"{key} path must include hash")
        require(digest not in seen_hashes, f"Duplicate media SHA: {digest}")
        seen_hashes.add(digest)
        media_path = MEDIA_ROOT / storage_path
        require(media_path.is_file(), f"Missing served media: {storage_path}")
        payload = media_path.read_bytes()
        require(hashlib.sha256(payload).hexdigest() == digest, f"SHA mismatch: {storage_path}")
        require(len(payload) == row.get("byteSize") and len(payload) <= MAX_FILE_BYTES, f"Size mismatch: {storage_path}")
        total_bytes += len(payload)
        with Image.open(media_path) as image:
            require(image.format == "JPEG", f"Not JPEG: {storage_path}")
            require(image.size == SERVED_SIZE, f"Wrong dimensions: {storage_path}")
            require(image.mode == "RGB", f"Not RGB: {storage_path}")
            require(not image.getexif(), f"EXIF is forbidden: {storage_path}")
            require(image.info.get("progressive") in (1, True), f"Not progressive: {storage_path}")
    require(total_bytes <= MAX_CORPUS_BYTES, f"Media corpus exceeds {MAX_CORPUS_BYTES} bytes")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--require-media",
        action="store_true",
        help="Require all 180 served JPEGs and their generated metadata",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    listings = load_and_verify_fragments()
    if args.require_media:
        verify_served_media(listings)
    suffix = " and 180 media" if args.require_media else ""
    print(f"OK closed-beta demo catalog: 60 listings{suffix}")


if __name__ == "__main__":
    main()
