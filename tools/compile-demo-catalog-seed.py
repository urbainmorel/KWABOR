#!/usr/bin/env python3
"""Compile the opt-in closed-beta catalog into deterministic, staging-only SQL."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CATALOG_ROOT = REPOSITORY_ROOT / "demo" / "catalog" / "v1"
FRAGMENT_ROOT = CATALOG_ROOT / "fragments"
CONFIG_PATH = CATALOG_ROOT / "seed-config.json"
GENERATED_ROOT = CATALOG_ROOT / "generated"
SEED_OUTPUT = GENERATED_ROOT / "seed.sql"
ROLLBACK_OUTPUT = GENERATED_ROOT / "rollback.sql"
FAMILIES = ("places", "events", "hotels", "restaurants")
EXPECTED_FAMILY_COUNTS = {family: 15 for family in FAMILIES}
CANONICAL_FIXTURES = {
    "00000000-0000-4000-8000-000000000101": "porte-du-non-retour-ouidah",
    "00000000-0000-4000-8000-000000000102": "marche-dantokpa-cotonou",
    "00000000-0000-4000-8000-000000000103": "table-locale-cotonou",
    "00000000-0000-4000-8000-000000000104": "festival-culturel-ouidah-test",
}
CANONICAL_FIXTURE_IDS = frozenset(CANONICAL_FIXTURES)
CANONICAL_FIXTURE_SLUGS = frozenset(CANONICAL_FIXTURES.values())
UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
ALLOWED_TIME_ZONE = "Africa/Porto-Novo"
PORTO_NOVO_OFFSET = timezone(timedelta(hours=1))
MARKER_TAG_PREFIX = "demo-catalog:"
ANCHOR_TAG_PREFIX = "demo-anchor:"
MAX_TAG_LENGTH = 24
TICKET_UUID_NAMESPACE = uuid.UUID("00000000-0000-5000-8000-000000000001")
MEDIA_UUID_NAMESPACE = uuid.UUID("00000000-0000-5000-8000-000000000002")
LISTING_COLUMNS = (
    "id",
    "type",
    "subtype",
    "listing_class",
    "category_id",
    "status",
    "name",
    "slug",
    "description",
    "content_lang",
    "city_id",
    "district",
    "address",
    "lat",
    "lng",
    "price_from_xof",
    "price_unit",
    "price_tier",
    "opening_hours",
    "contact_phone",
    "contact_whatsapp",
    "external_url",
    "email",
    "socials",
    "tags",
    "verified",
    "rating_avg",
    "rating_count",
    "views_count",
    "likes_count",
    "published_at",
)
INTERACTION_COLUMNS = frozenset(
    {"rating_avg", "rating_count", "views_count", "likes_count"}
)
NON_EVENT_VARIANTS = frozenset({"place", "lodging", "food"})
EVENT_VARIANTS = frozenset({"event"})


class CatalogCompileError(RuntimeError):
    """Raised when source data cannot safely be compiled."""


@dataclass(frozen=True)
class CompilerConfig:
    schema_version: int
    catalog_version: str
    anchor_date: date
    time_zone: str
    media_rights_approval: dict[str, str]


@dataclass(frozen=True)
class CatalogSources:
    config: CompilerConfig
    listings: tuple[dict[str, Any], ...]
    media_by_key: dict[tuple[str, int], dict[str, Any]]
    source_digest: str
    catalog_marker: str
    anchor_marker: str


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CatalogCompileError(message)


def load_json(path: Path) -> Any:
    try:
        display_path = path.relative_to(REPOSITORY_ROOT)
    except ValueError:
        display_path = path
    require(path.is_file(), f"Missing required source: {display_path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CatalogCompileError(f"Invalid JSON source {display_path}: {error}") from error


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def load_config(path: Path = CONFIG_PATH) -> CompilerConfig:
    raw = load_json(path)
    require(isinstance(raw, dict), "seed-config.json must contain an object")
    require(
        set(raw) == {
            "schemaVersion",
            "catalogVersion",
            "catalogAnchorDate",
            "timeZone",
            "mediaRightsApproval",
        },
        "Unexpected seed config keys",
    )
    require(raw["schemaVersion"] == 1, "Unsupported seed config schemaVersion")
    require(raw["catalogVersion"] == "v1", "Unsupported demo catalog version")
    require(raw["timeZone"] == ALLOWED_TIME_ZONE, f"timeZone must be {ALLOWED_TIME_ZONE}")
    approval = raw["mediaRightsApproval"]
    require(
        isinstance(approval, dict)
        and set(approval) == {"status", "approvedBy", "approvedAt", "scope"},
        "mediaRightsApproval has an invalid shape",
    )
    require(
        approval["status"] == "approved-by-product-owner"
        and approval["approvedBy"] == "Kwabor product owner"
        and approval["scope"] == "closed-beta-demo-only",
        "Demo media rights are not approved for the closed beta",
    )
    try:
        anchor_date = date.fromisoformat(raw["catalogAnchorDate"])
        date.fromisoformat(approval["approvedAt"])
    except (TypeError, ValueError) as error:
        raise CatalogCompileError("Catalog anchor and media approval dates must be valid ISO dates") from error
    return CompilerConfig(
        schema_version=raw["schemaVersion"],
        catalog_version=raw["catalogVersion"],
        anchor_date=anchor_date,
        time_zone=raw["timeZone"],
        media_rights_approval=approval,
    )


def _fragment_paths(fragment_root: Path) -> tuple[Path, ...]:
    return tuple(fragment_root / f"{family}.json" for family in FAMILIES)


def _media_paths(fragment_root: Path) -> tuple[Path, ...]:
    return tuple(fragment_root / f"{family}-media.json" for family in FAMILIES)


def _validate_listing_shape(listing: dict[str, Any], family: str) -> None:
    required = {
        "id",
        "type",
        "subtype",
        "listingClass",
        "categoryId",
        "status",
        "name",
        "slug",
        "description",
        "contentLang",
        "cityId",
        "district",
        "address",
        "lat",
        "lng",
        "priceFromXof",
        "priceUnit",
        "priceTier",
        "openingHours",
        "contactPhone",
        "contactWhatsapp",
        "externalUrl",
        "email",
        "socials",
        "tags",
        "verified",
        "ratingAvg",
        "ratingCount",
        "viewsCount",
        "likesCount",
        "publishedAt",
        "detail",
        "amenities",
        "media",
    }
    require(required <= set(listing), f"{family} listing has missing fields: {listing.get('id')}")
    listing_id = listing["id"]
    require(isinstance(listing_id, str) and UUID_PATTERN.fullmatch(listing_id), f"Invalid listing UUID: {listing_id}")
    require(listing["status"] == "publie", f"Demo listing must target publie: {listing_id}")
    require(isinstance(listing["tags"], list), f"tags must be a list: {listing_id}")
    require("demo-kwabor" in listing["tags"], f"Missing demo-kwabor tag: {listing_id}")
    require(isinstance(listing["media"], list) and len(listing["media"]) == 3, f"Exactly three media required: {listing_id}")
    require(isinstance(listing["detail"], dict), f"Missing typed detail: {listing_id}")
    require(isinstance(listing["amenities"], list), f"amenities must be a list: {listing_id}")


def _validate_media_metadata(
    listings: Sequence[dict[str, Any]], fragment_root: Path
) -> dict[tuple[str, int], dict[str, Any]]:
    expected_keys = {(listing["id"], order) for listing in listings for order in range(3)}
    rows_by_key: dict[tuple[str, int], dict[str, Any]] = {}
    for family, path in zip(FAMILIES, _media_paths(fragment_root), strict=True):
        rows = load_json(path)
        require(isinstance(rows, list) and len(rows) == 45, f"{family}-media.json must contain 45 rows")
        for row in rows:
            require(isinstance(row, dict), f"Invalid media metadata in {family}")
            key = (row.get("listingId"), row.get("displayOrder"))
            require(key in expected_keys, f"Unexpected media metadata key: {key}")
            require(key not in rows_by_key, f"Duplicate media metadata key: {key}")
            storage_path = row.get("storagePath")
            require(
                isinstance(storage_path, str) and storage_path.startswith(f"v1/{key[0]}/"),
                f"Invalid storage path for {key}",
            )
            rows_by_key[key] = row
    require(set(rows_by_key) == expected_keys, "Media metadata must cover all 180 listing/order pairs")
    storage_paths = [row["storagePath"] for row in rows_by_key.values()]
    require(
        len(storage_paths) == len(set(storage_paths)),
        "Media storage paths must be globally unique",
    )
    return rows_by_key


def _validate_child_identities(listings: Sequence[dict[str, Any]]) -> None:
    listing_ids = {listing["id"] for listing in listings}
    room_ids: set[str] = set()
    generated_tier_ids: set[str] = set()
    generated_media_ids: set[str] = set()
    for listing in listings:
        listing_id = listing["id"]
        media_orders = [entry.get("displayOrder") for entry in listing["media"]]
        require(
            sorted(media_orders) == [0, 1, 2] and len(set(media_orders)) == 3,
            f"Media display orders must be exactly 0, 1 and 2: {listing_id}",
        )
        covers = [entry for entry in listing["media"] if entry.get("isCover") is True]
        require(
            len(covers) == 1 and covers[0].get("displayOrder") == 0,
            f"Media cover identity must be display order zero: {listing_id}",
        )
        for order in media_orders:
            media_id = deterministic_uuid(MEDIA_UUID_NAMESPACE, f"{listing_id}:media:{order}")
            require(media_id not in generated_media_ids, f"Duplicate generated media UUID: {media_id}")
            require(media_id not in listing_ids, f"Media UUID collides with a listing UUID: {media_id}")
            generated_media_ids.add(media_id)

        detail = listing["detail"]
        variant = detail.get("variant")
        if variant == "lodging":
            rooms = detail.get("roomTypes")
            require(isinstance(rooms, list), f"roomTypes must be a list: {listing_id}")
            room_orders = [room.get("displayOrder") for room in rooms]
            room_names = [room.get("name") for room in rooms]
            require(
                len(room_orders) == len(set(room_orders)),
                f"Room display orders must be unique: {listing_id}",
            )
            require(
                len(room_names) == len(set(room_names)),
                f"Room names must be unique: {listing_id}",
            )
            for room in rooms:
                room_id = room.get("id")
                require(
                    isinstance(room_id, str) and UUID_PATTERN.fullmatch(room_id) is not None,
                    f"Invalid room UUID: {room_id}",
                )
                require(room_id not in room_ids, f"Duplicate room UUID: {room_id}")
                require(room_id not in listing_ids, f"Room UUID collides with a listing UUID: {room_id}")
                room_ids.add(room_id)
        elif variant == "event":
            tiers = detail.get("ticketTiers")
            require(isinstance(tiers, list), f"ticketTiers must be a list: {listing_id}")
            tier_orders = [tier.get("displayOrder") for tier in tiers]
            tier_labels = [tier.get("label") for tier in tiers]
            require(
                len(tier_orders) == len(set(tier_orders)),
                f"Ticket tier display orders must be unique: {listing_id}",
            )
            require(
                len(tier_labels) == len(set(tier_labels)),
                f"Ticket tier labels must be unique: {listing_id}",
            )
            for order in tier_orders:
                require(type(order) is int, f"Invalid ticket tier display order: {listing_id}")
                tier_id = deterministic_uuid(
                    TICKET_UUID_NAMESPACE, f"{listing_id}:ticket:{order}"
                )
                require(tier_id not in generated_tier_ids, f"Duplicate generated tier UUID: {tier_id}")
                require(tier_id not in listing_ids, f"Tier UUID collides with a listing UUID: {tier_id}")
                generated_tier_ids.add(tier_id)
    child_ids = room_ids | generated_tier_ids | generated_media_ids
    require(
        len(child_ids) == len(room_ids) + len(generated_tier_ids) + len(generated_media_ids),
        "Generated room, ticket-tier and media UUID domains must remain disjoint",
    )


def load_sources(
    config_path: Path = CONFIG_PATH, fragment_root: Path = FRAGMENT_ROOT
) -> CatalogSources:
    config = load_config(config_path)
    listings: list[dict[str, Any]] = []
    for family, path in zip(FAMILIES, _fragment_paths(fragment_root), strict=True):
        fragment = load_json(path)
        require(isinstance(fragment, list), f"{family}.json must contain a list")
        require(len(fragment) == EXPECTED_FAMILY_COUNTS[family], f"{family}.json must contain 15 listings")
        for listing in fragment:
            require(isinstance(listing, dict), f"Invalid listing entry in {family}.json")
            _validate_listing_shape(listing, family)
        listings.extend(fragment)

    ids = [listing["id"] for listing in listings]
    require(len(ids) == 60 and len(set(ids)) == 60, "Catalog must contain exactly 60 unique listing UUIDs")
    slugs = [listing["slug"] for listing in listings]
    require(
        all(isinstance(slug, str) and slug.strip() == slug and slug for slug in slugs),
        "Catalog slugs must be non-empty canonical strings",
    )
    require(len(slugs) == len(set(slugs)), "Catalog slugs must be globally unique")
    slug_overlap = sorted(set(slugs) & CANONICAL_FIXTURE_SLUGS)
    require(not slug_overlap, f"Demo slugs overlap canonical fixtures: {', '.join(slug_overlap)}")
    overlap = sorted(set(ids) & CANONICAL_FIXTURE_IDS)
    require(not overlap, f"Demo UUIDs overlap canonical fixtures: {', '.join(overlap)}")
    listing_ids = set(ids)
    for listing in listings:
        if listing["detail"].get("variant") == "event":
            venue_id = listing["detail"].get("venueListingId")
            require(venue_id is None or venue_id in listing_ids, f"Unknown event venue: {venue_id}")
            require(venue_id not in CANONICAL_FIXTURE_IDS, f"Event venue targets canonical fixture: {venue_id}")

    _validate_child_identities(listings)
    media_by_key = _validate_media_metadata(listings, fragment_root)
    digest_payload = {
        "config": {
            "schemaVersion": config.schema_version,
            "catalogVersion": config.catalog_version,
            "catalogAnchorDate": config.anchor_date.isoformat(),
            "timeZone": config.time_zone,
            "mediaRightsApproval": config.media_rights_approval,
        },
        "listings": sorted(listings, key=lambda item: item["id"]),
        "media": [media_by_key[key] for key in sorted(media_by_key)],
    }
    source_digest = hashlib.sha256(canonical_json(digest_payload).encode("utf-8")).hexdigest()
    catalog_marker = f"{MARKER_TAG_PREFIX}{source_digest[:11]}"
    anchor_marker = f"{ANCHOR_TAG_PREFIX}{config.anchor_date.strftime('%Y%m%d')}"
    require(len(catalog_marker) <= MAX_TAG_LENGTH, "Catalog marker exceeds the listing tag contract")
    require(len(anchor_marker) <= MAX_TAG_LENGTH, "Anchor marker exceeds the listing tag contract")
    return CatalogSources(
        config=config,
        listings=tuple(sorted(listings, key=lambda item: item["id"])),
        media_by_key=media_by_key,
        source_digest=source_digest,
        catalog_marker=catalog_marker,
        anchor_marker=anchor_marker,
    )


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_nullable_string(value: str | None) -> str:
    return "null" if value is None else sql_string(value)


def sql_bool(value: bool) -> str:
    return "true" if value else "false"


def sql_number(value: int | float | None) -> str:
    if value is None:
        return "null"
    require(type(value) in (int, float), f"Expected a numeric value, got {value!r}")
    return str(value)


def sql_json(value: Any) -> str:
    return f"{sql_string(canonical_json(value))}::jsonb"


def sql_text_array(values: Iterable[str]) -> str:
    return "array[" + ", ".join(sql_string(value) for value in values) + "]::text[]"


def values_block(rows: Sequence[Sequence[str]], indent: str = "  ") -> str:
    require(bool(rows), "Cannot render an empty VALUES block")
    return ",\n".join(indent + "(" + ", ".join(row) + ")" for row in rows)


def id_list_sql(ids: Sequence[str], indent: str = "    ") -> str:
    return ",\n".join(f"{indent}{sql_string(listing_id)}::uuid" for listing_id in ids)


def materialize_event_schedule(schedule: dict[str, Any], config: CompilerConfig) -> tuple[datetime, datetime]:
    require(schedule.get("kind") == "relative_to_seed_date", "Event schedule kind must be relative_to_seed_date")
    require(schedule.get("timeZone") == config.time_zone, "Event schedule timeZone must match seed config")
    offset_days = schedule.get("startOffsetDays")
    duration_minutes = schedule.get("durationMinutes")
    require(type(offset_days) is int and offset_days >= 0, "startOffsetDays must be a non-negative integer")
    require(type(duration_minutes) is int and duration_minutes > 0, "durationMinutes must be a positive integer")
    try:
        local_time = datetime.strptime(schedule["startLocalTime"], "%H:%M").time()
    except (KeyError, TypeError, ValueError) as error:
        raise CatalogCompileError("startLocalTime must use HH:MM") from error
    start_local = datetime.combine(config.anchor_date + timedelta(days=offset_days), local_time, PORTO_NOVO_OFFSET)
    return start_local, start_local + timedelta(minutes=duration_minutes)


def sql_timestamp(value: datetime) -> str:
    return f"{sql_string(value.isoformat(timespec='seconds'))}::timestamptz"


def deterministic_uuid(namespace: uuid.UUID, name: str) -> str:
    return str(uuid.uuid5(namespace, name))


def _guard_sql() -> str:
    return """do $guard$
declare
  target_environment text := current_setting('app.kwabor_environment', true);
  catalog_enabled text := current_setting('app.kwabor_demo_catalog_enabled', true);
  media_base_url text := current_setting('app.kwabor_demo_media_base_url', true);
begin
  if target_environment is null or target_environment not in ('local', 'staging') then
    raise exception 'Demo catalog execution is allowed only for explicit local/staging operation';
  end if;
  if catalog_enabled is distinct from 'true' then
    raise exception 'Demo catalog execution requires app.kwabor_demo_catalog_enabled=true';
  end if;
  if media_base_url is null
    or media_base_url !~ '^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:443)?/([^[:space:]#\\\\]*/)?$'
    or media_base_url ~ 'https://[^/]*@'
    or media_base_url ~ 'https://[^/]*(localhost|\\.localhost|\\.local|\\.internal)(:443)?/'
  then
    raise exception 'Demo catalog execution requires a canonical public HTTPS app.kwabor_demo_media_base_url ending in /';
  end if;
end;
$guard$;"""


def _header(kind: str, sources: CatalogSources) -> str:
    return f"""-- GENERATED FILE. DO NOT EDIT.
-- Kind: {kind}
-- Catalog version: {sources.config.catalog_version}
-- Catalog anchor: {sources.config.anchor_date.isoformat()} ({sources.config.time_zone})
-- Source SHA-256: {sources.source_digest}
-- Catalog marker: {sources.catalog_marker}
-- Operational guard only: these GUC checks are not a security boundary.
-- Execution is permitted only through the protected local/staging Environment workflow.
-- Required session settings:
--   set app.kwabor_environment = 'local' | 'staging';
--   set app.kwabor_demo_catalog_enabled = 'true';
--   set app.kwabor_demo_media_base_url = 'https://.../';
"""


def _listing_rows(sources: CatalogSources) -> list[list[str]]:
    rows: list[list[str]] = []
    for listing in sources.listings:
        tags = [tag for tag in listing["tags"] if not tag.startswith((MARKER_TAG_PREFIX, ANCHOR_TAG_PREFIX))]
        tags.extend((sources.catalog_marker, sources.anchor_marker))
        require(len(tags) <= 10 and len(tags) == len(set(tags)), f"Invalid compiled tags: {listing['id']}")
        rows.append(
            [
                f"{sql_string(listing['id'])}::uuid",
                f"{sql_string(listing['type'])}::public.listing_type",
                sql_string(listing["subtype"]),
                f"{sql_string(listing['listingClass'])}::public.listing_class",
                sql_string(listing["categoryId"]),
                "'brouillon'::public.listing_status",
                sql_string(listing["name"]),
                sql_string(listing["slug"]),
                sql_string(listing["description"]),
                sql_string(listing["contentLang"]),
                sql_string(listing["cityId"]),
                sql_nullable_string(listing["district"]),
                sql_nullable_string(listing["address"]),
                sql_number(listing["lat"]),
                sql_number(listing["lng"]),
                sql_number(listing["priceFromXof"]),
                f"{sql_string(listing['priceUnit'])}::public.price_unit",
                sql_number(listing["priceTier"]),
                sql_json(listing["openingHours"]),
                sql_nullable_string(listing["contactPhone"]),
                sql_nullable_string(listing["contactWhatsapp"]),
                sql_nullable_string(listing["externalUrl"]),
                sql_nullable_string(listing["email"]),
                sql_json(listing["socials"]),
                sql_text_array(tags),
                sql_bool(listing["verified"]),
                sql_number(listing["ratingAvg"]),
                sql_number(listing["ratingCount"]),
                sql_number(listing["viewsCount"]),
                sql_number(listing["likesCount"]),
                "null",
            ]
        )
    return rows


def _detail_sql(
    sources: CatalogSources,
    included_variants: frozenset[str] | None = None,
) -> str:
    place_rows: list[list[str]] = []
    lodging_rows: list[list[str]] = []
    room_rows: list[list[str]] = []
    food_rows: list[list[str]] = []
    event_rows: list[list[str]] = []
    tier_rows: list[list[str]] = []
    amenity_rows: list[list[str]] = []
    media_rows: list[list[str]] = []

    for listing in sources.listings:
        listing_id = listing["id"]
        detail = listing["detail"]
        variant = detail["variant"]
        if included_variants is not None and variant not in included_variants:
            continue
        if variant == "place":
            place_rows.append(
                [
                    f"{sql_string(listing_id)}::uuid",
                    sql_string(detail["placeCategory"]),
                    sql_bool(detail["isFree"]),
                    sql_number(detail["entryFeeXof"]),
                    sql_nullable_string(detail["feeNote"]),
                ]
            )
        elif variant == "lodging":
            lodging_rows.append(
                [
                    f"{sql_string(listing_id)}::uuid",
                    sql_number(detail["starRating"]),
                    sql_number(detail["roomCount"]),
                    f"{sql_nullable_string(detail['checkinTime'])}::time",
                    f"{sql_nullable_string(detail['checkoutTime'])}::time",
                ]
            )
            for room in sorted(detail["roomTypes"], key=lambda value: value["displayOrder"]):
                require(UUID_PATTERN.fullmatch(room["id"]) is not None, f"Invalid room UUID: {room['id']}")
                room_rows.append(
                    [
                        f"{sql_string(room['id'])}::uuid",
                        f"{sql_string(listing_id)}::uuid",
                        sql_string(room["name"]),
                        sql_number(room["priceXof"]),
                        sql_number(room["displayOrder"]),
                    ]
                )
        elif variant == "food":
            food_rows.append(
                [
                    f"{sql_string(listing_id)}::uuid",
                    sql_text_array(detail["cuisines"]),
                    sql_text_array(detail["meals"]),
                    sql_bool(detail["reservation"]),
                    sql_nullable_string(detail["menuUrl"]),
                ]
            )
        elif variant == "event":
            start_at, end_at = materialize_event_schedule(detail["schedule"], sources.config)
            event_rows.append(
                [
                    f"{sql_string(listing_id)}::uuid",
                    sql_string(detail["category"]),
                    sql_timestamp(start_at),
                    sql_timestamp(end_at),
                    "null" if detail["venueListingId"] is None else f"{sql_string(detail['venueListingId'])}::uuid",
                    sql_string(detail["organizerName"]),
                    sql_string(detail["organizerContact"]),
                    f"{sql_string(detail['ticketType'])}::public.ticket_type",
                    sql_nullable_string(detail["ticketUrl"]),
                    sql_number(detail["capacity"]),
                ]
            )
            for tier in sorted(detail["ticketTiers"], key=lambda value: value["displayOrder"]):
                tier_rows.append(
                    [
                        f"{sql_string(deterministic_uuid(TICKET_UUID_NAMESPACE, f'{listing_id}:ticket:{tier['displayOrder']}'))}::uuid",
                        f"{sql_string(listing_id)}::uuid",
                        sql_string(tier["label"]),
                        sql_number(tier["priceXof"]),
                        sql_number(tier["displayOrder"]),
                    ]
                )
        else:
            raise CatalogCompileError(f"Unsupported detail variant {variant}: {listing_id}")

        for order, amenity in enumerate(listing["amenities"]):
            amenity_rows.append(
                [f"{sql_string(listing_id)}::uuid", sql_string(amenity), sql_number(order)]
            )
        media_by_order = {entry["displayOrder"]: entry for entry in listing["media"]}
        require(set(media_by_order) == {0, 1, 2}, f"Invalid media orders: {listing_id}")
        for order in range(3):
            entry = media_by_order[order]
            metadata = sources.media_by_key[(listing_id, order)]
            storage_path = metadata["storagePath"]
            media_rows.append(
                [
                    f"{sql_string(deterministic_uuid(MEDIA_UUID_NAMESPACE, f'{listing_id}:media:{order}'))}::uuid",
                    f"{sql_string(listing_id)}::uuid",
                    sql_string(storage_path),
                    f"current_setting('app.kwabor_demo_media_base_url') || {sql_string(storage_path)}",
                    sql_string(entry["alt"]),
                    sql_number(order),
                    sql_bool(entry["isCover"]),
                    f"{sql_string(entry.get('kind', 'image'))}::public.listing_media_kind",
                ]
            )

    sections = [
        _upsert_section("public.place_details", ("listing_id", "place_category", "is_free", "entry_fee_xof", "fee_note"), place_rows, ("listing_id",), ("place_category", "is_free", "entry_fee_xof", "fee_note")),
        _upsert_section("public.lodging_details", ("listing_id", "star_rating", "room_count", "checkin_time", "checkout_time"), lodging_rows, ("listing_id",), ("star_rating", "room_count", "checkin_time", "checkout_time")),
        _upsert_section("public.room_types", ("id", "listing_id", "name", "price_xof", "display_order"), room_rows, ("id",), ("listing_id", "name", "price_xof", "display_order")),
        _upsert_section("public.food_details", ("listing_id", "cuisines", "meals", "reservation", "menu_url"), food_rows, ("listing_id",), ("cuisines", "meals", "reservation", "menu_url")),
        _upsert_section("public.event_details", ("listing_id", "category", "start_at", "end_at", "venue_listing_id", "organizer_name", "organizer_contact", "ticket_type", "ticket_url", "capacity"), event_rows, ("listing_id",), ("category", "start_at", "end_at", "venue_listing_id", "organizer_name", "organizer_contact", "ticket_type", "ticket_url", "capacity")),
        _upsert_section("public.ticket_tiers", ("id", "listing_id", "label", "price_xof", "display_order"), tier_rows, ("id",), ("listing_id", "label", "price_xof", "display_order")),
        _upsert_section("public.listing_amenities", ("listing_id", "amenity_id", "display_order"), amenity_rows, ("listing_id", "amenity_id"), ("display_order",)),
        _upsert_section("public.listing_media", ("id", "listing_id", "storage_path", "url", "alt", "display_order", "is_cover", "kind"), media_rows, ("id",), ("listing_id", "storage_path", "url", "alt", "display_order", "is_cover", "kind")),
    ]
    return "\n\n".join(section for section in sections if section)


def _upsert_section(
    table: str,
    columns: Sequence[str],
    rows: Sequence[Sequence[str]],
    conflict_columns: Sequence[str],
    update_columns: Sequence[str],
) -> str:
    if not rows:
        return ""
    updates = ",\n  ".join(f"{column} = excluded.{column}" for column in update_columns)
    comparison = ", ".join(f"target.{column}" for column in update_columns)
    excluded = ", ".join(f"excluded.{column}" for column in update_columns)
    return (
        f"insert into {table} as target ({', '.join(columns)}) values\n"
        f"{values_block(rows)}\n"
        f"on conflict ({', '.join(conflict_columns)}) do update set\n  {updates}\n"
        f"where row({comparison}) is distinct from row({excluded});"
    )


def _insert_missing_section(
    table: str,
    columns: Sequence[str],
    rows: Sequence[Sequence[str]],
) -> str:
    require(bool(rows), "Cannot render an empty INSERT section")
    selected_columns = ", ".join(f"desired.{column}" for column in columns)
    return (
        f"insert into {table} ({', '.join(columns)})\n"
        f"select {selected_columns}\n"
        f"from (values\n{values_block(rows)}\n) as desired ({', '.join(columns)})\n"
        f"where not exists (select 1 from {table} existing where existing.id = desired.id);"
    )


def _listing_content_update_sql(sources: CatalogSources) -> str:
    update_columns = tuple(
        column
        for column in LISTING_COLUMNS
        if column
        not in {"id", "status", "published_at"}
        and column not in INTERACTION_COLUMNS
    )
    selected_columns = ("id", *update_columns)
    column_indexes = [LISTING_COLUMNS.index(column) for column in selected_columns]
    selected_rows = [
        [row[index] for index in column_indexes] for row in _listing_rows(sources)
    ]
    assignments = ",\n  ".join(
        f"{column} = desired.{column}" for column in update_columns
    )
    current_values = ", ".join(f"target.{column}" for column in update_columns)
    desired_values = ", ".join(f"desired.{column}" for column in update_columns)
    return f"""update public.listings as target
set
  {assignments}
from (values
{values_block(selected_rows)}
) as desired ({', '.join(selected_columns)})
where target.id = desired.id
  and row({current_values}) is distinct from row({desired_values});"""


def _expected_identity_tables_sql(
    sources: CatalogSources,
    *,
    include_children: bool,
) -> str:
    listing_rows = [
        [
            f"{sql_string(listing['id'])}::uuid",
            sql_string(listing["slug"]),
            sql_string(listing["categoryId"]),
            sql_string(listing["detail"]["variant"]),
        ]
        for listing in sources.listings
    ]
    sections = [
        """create temporary table kwabor_demo_expected_listings (
  id uuid primary key,
  slug text not null unique,
  category_id text not null,
  detail_variant text not null
) on commit drop;""",
        "insert into kwabor_demo_expected_listings (id, slug, category_id, detail_variant) values\n"
        + values_block(listing_rows)
        + ";",
    ]
    if not include_children:
        return "\n\n".join(sections)

    room_rows: list[list[str]] = []
    tier_rows: list[list[str]] = []
    amenity_rows: list[list[str]] = []
    media_rows: list[list[str]] = []
    for listing in sources.listings:
        listing_id = listing["id"]
        detail = listing["detail"]
        if detail["variant"] == "lodging":
            for room in detail["roomTypes"]:
                room_rows.append(
                    [
                        f"{sql_string(room['id'])}::uuid",
                        f"{sql_string(listing_id)}::uuid",
                        sql_string(room["name"]),
                        sql_number(room["displayOrder"]),
                    ]
                )
        elif detail["variant"] == "event":
            for tier in detail["ticketTiers"]:
                tier_rows.append(
                    [
                        f"{sql_string(deterministic_uuid(TICKET_UUID_NAMESPACE, f'{listing_id}:ticket:{tier['displayOrder']}'))}::uuid",
                        f"{sql_string(listing_id)}::uuid",
                        sql_string(tier["label"]),
                        sql_number(tier["displayOrder"]),
                    ]
                )
        for order, amenity in enumerate(listing["amenities"]):
            amenity_rows.append(
                [
                    f"{sql_string(listing_id)}::uuid",
                    sql_string(amenity),
                    sql_number(order),
                ]
            )
        for order in range(3):
            media_id = deterministic_uuid(MEDIA_UUID_NAMESPACE, f"{listing_id}:media:{order}")
            media_rows.append(
                [
                    f"{sql_string(media_id)}::uuid",
                    f"{sql_string(listing_id)}::uuid",
                    sql_string(sources.media_by_key[(listing_id, order)]["storagePath"]),
                    sql_number(order),
                    sql_bool(order == 0),
                ]
            )

    child_definitions = (
        (
            "rooms",
            """id uuid primary key,
  listing_id uuid not null,
  name text not null,
  display_order integer not null,
  unique (listing_id, name),
  unique (listing_id, display_order)""",
            ("id", "listing_id", "name", "display_order"),
            room_rows,
        ),
        (
            "tiers",
            """id uuid primary key,
  listing_id uuid not null,
  label text not null,
  display_order integer not null,
  unique (listing_id, label),
  unique (listing_id, display_order)""",
            ("id", "listing_id", "label", "display_order"),
            tier_rows,
        ),
        (
            "amenities",
            """listing_id uuid not null,
  amenity_id text not null,
  display_order integer not null,
  primary key (listing_id, amenity_id),
  unique (listing_id, display_order)""",
            ("listing_id", "amenity_id", "display_order"),
            amenity_rows,
        ),
        (
            "media",
            """id uuid primary key,
  listing_id uuid not null,
  storage_path text not null unique,
  display_order integer not null,
  is_cover boolean not null,
  unique (listing_id, display_order)""",
            ("id", "listing_id", "storage_path", "display_order", "is_cover"),
            media_rows,
        ),
    )
    for name, definition, columns, rows in child_definitions:
        sections.append(
            f"create temporary table kwabor_demo_expected_{name} (\n  {definition}\n) on commit drop;"
        )
        if rows:
            sections.append(
                f"insert into kwabor_demo_expected_{name} ({', '.join(columns)}) values\n"
                + values_block(rows)
                + ";"
            )
    return "\n\n".join(sections)


def _bounded_transaction_prelude() -> str:
    return """begin;
set local lock_timeout = '5s';
set local statement_timeout = '120s';
set local idle_in_transaction_session_timeout = '60s';
set constraints all deferred;

do $catalog_lock$
begin
  if not pg_try_advisory_xact_lock(hashtextextended('kwabor-demo-catalog-v1', 0)) then
    raise exception 'Another demo catalog operation already holds the advisory lock';
  end if;
end;
$catalog_lock$;"""


def _seed_table_locks_sql() -> str:
    return """lock table
  public.listings,
  public.place_details,
  public.lodging_details,
  public.room_types,
  public.food_details,
  public.event_details,
  public.ticket_tiers,
  public.listing_amenities,
  public.listing_media
in share row exclusive mode;"""


def _collision_preflight_sql(sources: CatalogSources) -> str:
    required_cities = sorted({listing["cityId"] for listing in sources.listings})
    required_categories = sorted({listing["categoryId"] for listing in sources.listings})
    required_amenities = sorted(
        {amenity for listing in sources.listings for amenity in listing["amenities"]}
    )
    city_values = ", ".join(sql_string(value) for value in required_cities)
    category_values = ", ".join(sql_string(value) for value in required_categories)
    amenity_values = ", ".join(sql_string(value) for value in required_amenities)
    return f"""do $preflight$
begin
  if exists (
    select 1
    from unnest(array[{city_values}]::text[]) required(id)
    where not exists (select 1 from public.cities city where city.id = required.id)
  ) then
    raise exception 'Demo catalog requires all referenced cities before import';
  end if;
  if exists (
    select 1
    from unnest(array[{category_values}]::text[]) required(id)
    where not exists (select 1 from public.categories category where category.id = required.id)
  ) then
    raise exception 'Demo catalog requires all referenced categories before import';
  end if;
  if exists (
    select 1
    from kwabor_demo_expected_listings expected
    join public.categories category on category.id = expected.category_id
    where category.detail_variant::text <> expected.detail_variant
  ) then
    raise exception 'Demo catalog source category/detail variant mismatch';
  end if;
  if exists (
    select 1
    from kwabor_demo_expected_listings expected
    join public.listings listing on listing.id = expected.id
    join public.categories category on category.id = listing.category_id
    where category.detail_variant::text <> expected.detail_variant
  ) then
    raise exception 'Demo catalog refuses a parent detail-variant collision';
  end if;
  if exists (
    select 1
    from unnest(array[{amenity_values}]::text[]) required(id)
    where not exists (select 1 from public.amenities amenity where amenity.id = required.id)
  ) then
    raise exception 'Demo catalog requires all referenced amenities before import';
  end if;
  if exists (
    select 1
    from public.listings listing
    join kwabor_demo_expected_listings expected on expected.id = listing.id
    where 'demo-kwabor' <> all(listing.tags)
      or {sql_string(sources.catalog_marker)} <> all(listing.tags)
      or {sql_string(sources.anchor_marker)} <> all(listing.tags)
      or listing.slug <> expected.slug
      or listing.category_id <> expected.category_id
      or listing.owner_id is not null
      or listing.steward_id is not null
      or listing.submitted_by is not null
  ) then
    raise exception 'Demo catalog refuses to overwrite a foreign or claimed parent listing';
  end if;
  if exists (
    select 1
    from public.listings listing
    join kwabor_demo_expected_listings expected on expected.slug = listing.slug
    where listing.id <> expected.id
  ) then
    raise exception 'Demo catalog slug collision detected';
  end if;
  if exists (
    select 1
    from public.room_types existing
    join kwabor_demo_expected_rooms expected
      on existing.id = expected.id
      or (
        existing.listing_id = expected.listing_id
        and (existing.name = expected.name or existing.display_order = expected.display_order)
      )
    where existing.id <> expected.id or existing.listing_id <> expected.listing_id
  ) then
    raise exception 'Demo catalog room identity collision detected';
  end if;
  if exists (
    select 1
    from public.ticket_tiers existing
    join kwabor_demo_expected_tiers expected
      on existing.id = expected.id
      or (
        existing.listing_id = expected.listing_id
        and (existing.label = expected.label or existing.display_order = expected.display_order)
      )
    where existing.id <> expected.id or existing.listing_id <> expected.listing_id
  ) then
    raise exception 'Demo catalog ticket-tier identity collision detected';
  end if;
  if exists (
    select 1
    from public.listing_amenities existing
    join kwabor_demo_expected_amenities expected
      on existing.listing_id = expected.listing_id
      and (
        existing.amenity_id = expected.amenity_id
        or existing.display_order = expected.display_order
      )
    where existing.amenity_id <> expected.amenity_id
  ) then
    raise exception 'Demo catalog amenity identity collision detected';
  end if;
  if exists (
    select 1
    from public.listing_media existing
    join kwabor_demo_expected_media expected
      on existing.id = expected.id
      or existing.storage_path = expected.storage_path
      or (
        existing.listing_id = expected.listing_id
        and (
          existing.display_order = expected.display_order
          or (existing.is_cover and expected.is_cover)
        )
      )
    where existing.id <> expected.id or existing.listing_id <> expected.listing_id
  ) then
    raise exception 'Demo catalog media identity collision detected';
  end if;
  if exists (
    select 1
    from kwabor_demo_expected_listings expected
    left join public.place_details place on place.listing_id = expected.id
    left join public.lodging_details lodging on lodging.listing_id = expected.id
    left join public.food_details food on food.listing_id = expected.id
    left join public.event_details event on event.listing_id = expected.id
    where (place.listing_id is not null and expected.detail_variant <> 'place')
      or (lodging.listing_id is not null and expected.detail_variant <> 'lodging')
      or (food.listing_id is not null and expected.detail_variant <> 'food')
      or (event.listing_id is not null and expected.detail_variant <> 'event')
  ) then
    raise exception 'Demo catalog typed-detail collision detected';
  end if;
end;
$preflight$;"""


def _diff_delete_sql(included_variants: frozenset[str]) -> str:
    variants = ", ".join(sql_string(value) for value in sorted(included_variants))
    sections = [
        f"""delete from public.listing_media existing
using kwabor_demo_expected_listings parent
where existing.listing_id = parent.id
  and parent.detail_variant in ({variants})
  and not exists (
    select 1 from kwabor_demo_expected_media expected where expected.id = existing.id
  );""",
        f"""delete from public.listing_amenities existing
using kwabor_demo_expected_listings parent
where existing.listing_id = parent.id
  and parent.detail_variant in ({variants})
  and not exists (
    select 1
    from kwabor_demo_expected_amenities expected
    where expected.listing_id = existing.listing_id
      and expected.amenity_id = existing.amenity_id
  );""",
    ]
    if "lodging" in included_variants:
        sections.append(
            """delete from public.room_types existing
using kwabor_demo_expected_listings parent
where existing.listing_id = parent.id
  and parent.detail_variant = 'lodging'
  and not exists (
    select 1 from kwabor_demo_expected_rooms expected where expected.id = existing.id
  );"""
        )
    if "event" in included_variants:
        sections.append(
            """delete from public.ticket_tiers existing
using kwabor_demo_expected_listings parent
where existing.listing_id = parent.id
  and parent.detail_variant = 'event'
  and not exists (
    select 1 from kwabor_demo_expected_tiers expected where expected.id = existing.id
  );"""
        )
    return "\n\n".join(sections)


def render_seed_sql(sources: CatalogSources) -> str:
    event_ids = [listing["id"] for listing in sources.listings if listing["type"] == "evenement"]
    non_event_ids = [listing["id"] for listing in sources.listings if listing["type"] != "evenement"]
    listings_insert = _insert_missing_section("public.listings", LISTING_COLUMNS, _listing_rows(sources))
    event_id_sql = id_list_sql(event_ids)
    non_event_id_sql = id_list_sql(non_event_ids)
    published_at = sql_string(
        sources.config.anchor_date.isoformat() + "T00:00:00+00:00"
    )
    return _header("seed", sources) + f"""
{_bounded_transaction_prelude()}

{_guard_sql()}

{_expected_identity_tables_sql(sources, include_children=True)}

{_seed_table_locks_sql()}

{_collision_preflight_sql(sources)}

-- Missing parents enter as drafts. Existing parents are never demoted.
{listings_insert}

-- Content updates exclude interaction counters and fire only on a real difference.
{_listing_content_update_sql(sources)}

-- Diff sets remove only obsolete generated children; unchanged rows remain untouched.
{_diff_delete_sql(NON_EVENT_VARIANTS)}

{_detail_sql(sources, NON_EVENT_VARIANTS)}

-- Venues and establishments are ready before dependent event details are applied.
update public.listings as target
set status = 'publie', published_at = {published_at}::timestamptz
where id in (
{non_event_id_sql}
)
  and row(target.status, target.published_at)
    is distinct from row('publie'::public.listing_status, {published_at}::timestamptz);

{_diff_delete_sql(EVENT_VARIANTS)}

{_detail_sql(sources, EVENT_VARIANTS)}

update public.listings as target
set status = 'publie', published_at = {published_at}::timestamptz
where id in (
{event_id_sql}
)
  and row(target.status, target.published_at)
    is distinct from row('publie'::public.listing_status, {published_at}::timestamptz);

do $proof$
declare
  matched integer;
begin
  select count(*) into matched
  from public.listings listing
  join kwabor_demo_expected_listings expected on expected.id = listing.id
  where listing.slug = expected.slug
    and listing.status = 'publie'
    and listing.published_at = {published_at}::timestamptz
    and listing.tags @> array[{sql_string(sources.catalog_marker)}, {sql_string(sources.anchor_marker)}]::text[];
  if matched <> 60 then
    raise exception 'Demo catalog identity proof failed after import: %/60', matched;
  end if;
  if (select count(*) from public.listing_media media
      join kwabor_demo_expected_media expected on expected.id = media.id) <> 180 then
    raise exception 'Demo catalog media proof failed after import';
  end if;
  if exists (
    select 1
    from public.listing_media media
    join kwabor_demo_expected_media expected on expected.id = media.id
    where media.listing_id <> expected.listing_id
      or media.storage_path <> expected.storage_path
      or media.display_order <> expected.display_order
      or media.is_cover <> expected.is_cover
  ) then
    raise exception 'Demo catalog media identity drift remains after import';
  end if;
end;
$proof$;

commit;
"""


def render_rollback_sql(sources: CatalogSources) -> str:
    event_ids = [listing["id"] for listing in sources.listings if listing["type"] == "evenement"]
    non_event_ids = [listing["id"] for listing in sources.listings if listing["type"] != "evenement"]
    event_id_sql = id_list_sql(event_ids)
    non_event_id_sql = id_list_sql(non_event_ids)
    return _header("rollback", sources) + f"""
{_bounded_transaction_prelude()}

{_guard_sql()}

-- Identity is proven before neutralization; missing, drifted or foreign rows fail closed.
{_expected_identity_tables_sql(sources, include_children=False)}

lock table public.listings, public.event_details in share row exclusive mode;

do $identity$
declare
  canonical_ids constant uuid[] := array[
    '00000000-0000-4000-8000-000000000101'::uuid,
    '00000000-0000-4000-8000-000000000102'::uuid,
    '00000000-0000-4000-8000-000000000103'::uuid,
    '00000000-0000-4000-8000-000000000104'::uuid
  ]::uuid[];
  matched integer;
begin
  if exists (
    select 1 from kwabor_demo_expected_listings demo where demo.id = any(canonical_ids)
  ) then
    raise exception 'Rollback target overlaps canonical fixtures';
  end if;
  if (select count(*) from kwabor_demo_expected_listings) <> 60 then
    raise exception 'Rollback requires exactly 60 distinct demo UUIDs';
  end if;
  select count(*) into matched
  from public.listings listing
  join kwabor_demo_expected_listings expected on expected.id = listing.id
  where listing.tags @> array[{sql_string(sources.catalog_marker)}, {sql_string(sources.anchor_marker)}]::text[];
  if matched <> 60 then
    raise exception 'Rollback catalog identity mismatch: %/60', matched;
  end if;
  if exists (
    select 1
    from public.event_details detail
    join kwabor_demo_expected_listings venue on venue.id = detail.venue_listing_id
    join public.listings event_listing on event_listing.id = detail.listing_id
    left join kwabor_demo_expected_listings demo_event on demo_event.id = event_listing.id
    where demo_event.id is null
      and event_listing.status in ('en_attente', 'publie')
  ) then
    raise exception 'Rollback refuses to archive a venue used by an external active event';
  end if;
end;
$identity$;

-- Logical rollback: archive events before their venues, retaining every parent,
-- typed child, media row, interaction and foreign-key reference.
update public.listings as target
set status = 'archive', published_at = null
where id in (
{event_id_sql}
)
  and row(target.status, target.published_at)
    is distinct from row('archive'::public.listing_status, null::timestamptz);

update public.listings as target
set status = 'archive', published_at = null
where id in (
{non_event_id_sql}
)
  and row(target.status, target.published_at)
    is distinct from row('archive'::public.listing_status, null::timestamptz);

do $proof$
declare
  neutralized integer;
begin
  select count(*) into neutralized
  from public.listings listing
  join kwabor_demo_expected_listings expected on expected.id = listing.id
  where listing.status = 'archive'
    and listing.published_at is null
    and listing.tags @> array[{sql_string(sources.catalog_marker)}, {sql_string(sources.anchor_marker)}]::text[];
  if neutralized <> 60 then
    raise exception 'Rollback neutralization proof failed: %/60', neutralized;
  end if;
end;
$proof$;

commit;
"""


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def compile_catalog(
    config_path: Path = CONFIG_PATH,
    fragment_root: Path = FRAGMENT_ROOT,
    seed_output: Path = SEED_OUTPUT,
    rollback_output: Path = ROLLBACK_OUTPUT,
) -> CatalogSources:
    sources = load_sources(config_path=config_path, fragment_root=fragment_root)
    write_text(seed_output, render_seed_sql(sources))
    write_text(rollback_output, render_rollback_sql(sources))
    return sources


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=CONFIG_PATH)
    parser.add_argument("--fragments", type=Path, default=FRAGMENT_ROOT)
    parser.add_argument("--seed-output", type=Path, default=SEED_OUTPUT)
    parser.add_argument("--rollback-output", type=Path, default=ROLLBACK_OUTPUT)
    parser.add_argument("--check", action="store_true", help="Verify generated outputs without rewriting them")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sources = load_sources(config_path=args.config, fragment_root=args.fragments)
    expected_seed = render_seed_sql(sources)
    expected_rollback = render_rollback_sql(sources)
    if args.check:
        for path, expected in ((args.seed_output, expected_seed), (args.rollback_output, expected_rollback)):
            require(path.is_file(), f"Missing generated output: {path}")
            require(path.read_text(encoding="utf-8") == expected, f"Generated output is stale: {path}")
    else:
        write_text(args.seed_output, expected_seed)
        write_text(args.rollback_output, expected_rollback)
    print(
        f"OK demo catalog seed: 60 listings, anchor {sources.config.anchor_date.isoformat()}, "
        f"source {sources.source_digest}"
    )


if __name__ == "__main__":
    try:
        main()
    except CatalogCompileError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
