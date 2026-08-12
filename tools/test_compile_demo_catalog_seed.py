from __future__ import annotations

import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("compile-demo-catalog-seed.py")
SPEC = importlib.util.spec_from_file_location("compile_demo_catalog_seed", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
COMPILER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = COMPILER
SPEC.loader.exec_module(COMPILER)


def source_listing(listing_id: str, family: str, order: int) -> dict:
    variants = {
        "places": ("lieu", "nature", "patrimonial", "heritage-nature", "place"),
        "events": ("evenement", "culture", "evenementiel", "event-culture", "event"),
        "hotels": ("etablissement", "hotel", "commercial", "commercial-hotel", "lodging"),
        "restaurants": ("etablissement", "restaurant", "commercial", "commercial-restaurant", "food"),
    }
    listing_type, subtype, listing_class, category_id, variant = variants[family]
    suffix = int(listing_id[-3:])
    paid = family != "places"
    detail: dict
    if variant == "place":
        detail = {
            "variant": "place",
            "placeCategory": subtype,
            "isFree": True,
            "entryFeeXof": None,
            "feeNote": "Accès libre pour la démonstration.",
        }
    elif variant == "event":
        detail = {
            "variant": "event",
            "category": "culture",
            "schedule": {
                "kind": "relative_to_seed_date",
                "startOffsetDays": order + 1,
                "startLocalTime": "18:30",
                "durationMinutes": 90,
                "timeZone": "Africa/Porto-Novo",
            },
            "venueListingId": "00000000-0000-4000-8000-000000000201",
            "organizerName": "Collectif fictif",
            "organizerContact": "events@kwabor.test",
            "ticketType": "payant",
            "ticketUrl": "https://tickets.kwabor.test/demo",
            "capacity": 40,
            "ticketTiers": [{"label": "Démo", "priceXof": 3000, "displayOrder": 0}],
        }
    elif variant == "lodging":
        detail = {
            "variant": "lodging",
            "starRating": 3,
            "roomCount": 10,
            "checkinTime": "14:00:00",
            "checkoutTime": "11:00:00",
            "roomTypes": [
                {
                    "id": f"10000000-0000-4000-8000-{suffix:012d}",
                    "name": "Chambre Démo",
                    "priceXof": 30000,
                    "displayOrder": 0,
                }
            ],
        }
    else:
        detail = {
            "variant": "food",
            "cuisines": ["béninoise"],
            "meals": ["déjeuner"],
            "reservation": True,
            "menuUrl": None,
        }
    opening_hours = (
        {
            day: {"status": "open_24_hours", "periods": []}
            for day in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        }
        if family in ("hotels", "restaurants")
        else {}
    )
    price = 3000 if family == "events" else 30000 if family == "hotels" else 5000 if family == "restaurants" else None
    unit = "par_entree" if family == "events" else "par_nuit" if family == "hotels" else "par_personne" if family == "restaurants" else "aucune"
    return {
        "id": listing_id,
        "type": listing_type,
        "subtype": subtype,
        "listingClass": listing_class,
        "categoryId": category_id,
        "status": "publie",
        "name": f"Fiche démo {listing_id[-3:]}",
        "slug": f"fiche-demo-{listing_id[-3:]}",
        "description": "Description suffisamment longue pour représenter une fiche de démonstration Kwabor.",
        "contentLang": "fr",
        "cityId": "cotonou",
        "district": "Quartier Démo",
        "address": "Adresse fictive à Cotonou",
        "lat": 6.37,
        "lng": 2.40,
        "priceFromXof": price,
        "priceUnit": unit,
        "priceTier": 1 if paid else None,
        "openingHours": opening_hours,
        "contactPhone": None,
        "contactWhatsapp": None,
        "externalUrl": None,
        "email": "demo@kwabor.test" if family in ("hotels", "restaurants") else None,
        "socials": {},
        "tags": ["demo-kwabor", "contenu-fictif"],
        "verified": False,
        "ratingAvg": None,
        "ratingCount": 0,
        "viewsCount": 0,
        "likesCount": 0,
        "publishedAt": "2026-08-12T00:00:00Z",
        "detail": detail,
        "amenities": ["parking"] if family != "events" else [],
        "media": [
            {
                "role": "cover" if media_order == 0 else "gallery",
                "displayOrder": media_order,
                "isCover": media_order == 0,
                "alt": f"Image démo {listing_id[-3:]} ordre {media_order}",
                "prompt": f"Prompt démo {listing_id[-3:]} ordre {media_order}",
                "kind": "image",
            }
            for media_order in range(3)
        ],
    }


class CompilerFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.config = root / "seed-config.json"
        self.fragments = root / "fragments"
        self.fragments.mkdir(parents=True)
        self.config.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "catalogVersion": "v1",
                    "catalogAnchorDate": "2026-08-12",
                    "timeZone": "Africa/Porto-Novo",
                    "mediaRightsApproval": {
                        "status": "approved-by-product-owner",
                        "approvedBy": "Kwabor product owner",
                        "approvedAt": "2026-08-12",
                        "scope": "closed-beta-demo-only",
                    },
                }
            ),
            encoding="utf-8",
        )
        starts = {"places": 201, "events": 301, "hotels": 401, "restaurants": 501}
        for family, start in starts.items():
            listings = [
                source_listing(f"00000000-0000-4000-8000-{suffix:012d}", family, order)
                for order, suffix in enumerate(range(start, start + 15))
            ]
            self.write(family, listings)
            media_rows = []
            for listing in listings:
                for order in range(3):
                    digest = f"{start:03d}{int(listing['id'][-3:]):03d}{order}".ljust(64, "a")[:64]
                    media_rows.append(
                        {
                            "listingId": listing["id"],
                            "displayOrder": order,
                            "storagePath": f"v1/{listing['id']}/{order:02d}-image-{digest[:12]}-960x1280.jpg",
                            "sha256": digest,
                            "byteSize": 100000,
                        }
                    )
            self.write(f"{family}-media", media_rows)

    def read(self, name: str):
        return json.loads((self.fragments / f"{name}.json").read_text(encoding="utf-8"))

    def write(self, name: str, value) -> None:
        (self.fragments / f"{name}.json").write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )


class DemoCatalogCompilerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.fixture = CompilerFixture(Path(self.temporary.name))

    def load(self):
        return COMPILER.load_sources(self.fixture.config, self.fixture.fragments)

    def test_compilation_is_deterministic_and_materializes_anchor(self) -> None:
        first = self.load()
        second = self.load()
        first_seed = COMPILER.render_seed_sql(first)
        second_seed = COMPILER.render_seed_sql(second)
        self.assertEqual(first.source_digest, second.source_digest)
        self.assertEqual(first_seed, second_seed)
        self.assertEqual(COMPILER.render_rollback_sql(first), COMPILER.render_rollback_sql(second))
        self.assertIn("2026-08-13T18:30:00+01:00", first_seed)
        self.assertIn("2026-08-13T20:00:00+01:00", first_seed)

    def test_seed_has_guard_transaction_and_safe_publish_order(self) -> None:
        sql = COMPILER.render_seed_sql(self.load())
        self.assertIn("begin;", sql)
        self.assertIn("set local lock_timeout = '5s';", sql)
        self.assertIn("set local statement_timeout = '120s';", sql)
        self.assertIn("set local idle_in_transaction_session_timeout = '60s';", sql)
        self.assertIn("set constraints all deferred;", sql)
        self.assertIn("pg_try_advisory_xact_lock", sql)
        self.assertNotIn("select pg_advisory_xact_lock", sql)
        self.assertIn("app.kwabor_environment", sql)
        self.assertIn("app.kwabor_demo_catalog_enabled", sql)
        self.assertIn("app.kwabor_demo_media_base_url", sql)
        self.assertIn("canonical public HTTPS", sql)
        self.assertIn("Operational guard only", sql)
        self.assertLess(sql.index("do $preflight$"), sql.index("insert into public.listings"))
        self.assertLess(sql.index("insert into public.listings"), sql.index("insert into public.event_details"))
        self.assertLess(
            sql.index("Venues and establishments are ready"),
            sql.index("insert into public.event_details"),
        )
        self.assertTrue(sql.rstrip().endswith("commit;"))

    def test_seed_preserves_interaction_tables_and_uses_deterministic_children(self) -> None:
        sql = COMPILER.render_seed_sql(self.load())
        self.assertNotIn("delete from public.favorites", sql)
        self.assertNotIn("delete from public.likes", sql)
        self.assertNotIn("uuid_generate_v5", sql)
        self.assertIn(str(COMPILER.uuid.uuid5(COMPILER.MEDIA_UUID_NAMESPACE, "00000000-0000-4000-8000-000000000201:media:0")), sql)
        self.assertIn("where not exists (select 1 from public.listings existing where existing.id = desired.id)", sql)
        self.assertNotIn("on conflict (id) do nothing", sql)
        self.assertIn("null::numeric(3, 2)", sql)
        self.assertIn("null::timestamptz", sql)
        self.assertIn("Diff sets remove only obsolete generated children", sql)
        self.assertIn("where row(target.", sql)
        self.assertIn("is distinct from row(excluded.", sql)
        self.assertNotIn("likes_count = excluded.likes_count", sql)
        self.assertNotIn("views_count = excluded.views_count", sql)
        self.assertNotIn("set status = 'brouillon'", sql)
        self.assertNotIn("delete from public.event_details", sql)
        self.assertNotIn("delete from public.lodging_details", sql)
        self.assertNotIn("delete from public.food_details", sql)
        self.assertNotIn("delete from public.place_details", sql)

    def test_seed_preflights_all_identity_collision_domains_before_parent_mutation(self) -> None:
        sql = COMPILER.render_seed_sql(self.load())
        insert_parent = sql.index("insert into public.listings")
        for message in (
            "parent detail-variant collision",
            "slug collision detected",
            "room identity collision detected",
            "ticket-tier identity collision detected",
            "amenity identity collision detected",
            "media identity collision detected",
            "typed-detail collision detected",
        ):
            self.assertIn(message, sql)
            self.assertLess(sql.index(message), insert_parent)
        sources = self.load()
        self.assertIn(f"or '{sources.catalog_marker}' <> all(listing.tags)", sql)
        self.assertIn(f"or '{sources.anchor_marker}' <> all(listing.tags)", sql)
        self.assertIn("or listing.slug <> expected.slug", sql)
        self.assertIn("or listing.category_id <> expected.category_id", sql)
        self.assertIn("listing.owner_id is not null", sql)
        self.assertIn("listing.steward_id is not null", sql)
        self.assertIn("listing.submitted_by is not null", sql)
        for table in (
            "public.listings",
            "public.room_types",
            "public.ticket_tiers",
            "public.listing_amenities",
            "public.listing_media",
        ):
            self.assertLess(sql.index(table, sql.index("lock table")), insert_parent)

    def test_seed_noop_paths_are_timestamp_stable(self) -> None:
        sql = COMPILER.render_seed_sql(self.load())
        self.assertIn("update public.listings as target", sql)
        self.assertIn("on conflict (listing_id) do update set", sql)
        self.assertIn("on conflict (id) do update set", sql)
        self.assertGreaterEqual(sql.count("where row(target."), 9)
        self.assertGreaterEqual(sql.count("is distinct from row(excluded."), 8)
        self.assertNotIn("created_at = excluded.created_at", sql)
        self.assertNotIn("updated_at = excluded.updated_at", sql)
        self.assertNotIn("published_at = excluded.published_at", sql)
        for publication in ("set status = 'publie'",):
            self.assertEqual(sql.count(publication), 2)
        self.assertGreaterEqual(
            sql.count("is distinct from row('publie'::public.listing_status"), 2
        )

    def test_rollback_is_logical_safe_and_excludes_canonical_fixtures(self) -> None:
        sql = COMPILER.render_rollback_sql(self.load())
        self.assertIn("Rollback catalog identity mismatch", sql)
        self.assertIn("Rollback target overlaps canonical fixtures", sql)
        self.assertIn("count(*) from kwabor_demo_expected_listings", sql)
        self.assertIn("lock table public.listings, public.event_details", sql)
        self.assertIn("Rollback refuses to archive a venue used by an external active event", sql)
        first_archive = sql.index("set status = 'archive'")
        identity_proof = sql.index("do $identity$")
        self.assertLess(identity_proof, first_archive)
        self.assertIn("Rollback neutralization proof failed", sql)
        self.assertEqual(sql.count("set status = 'archive'"), 2)
        self.assertEqual(sql.count("is distinct from row('archive'::public.listing_status"), 2)
        self.assertNotIn("delete from ", sql.lower())
        self.assertNotIn("where id like", sql.lower())
        for fixture_id in sorted(COMPILER.CANONICAL_FIXTURE_IDS):
            self.assertEqual(sql.count(fixture_id), 1)

    def test_rejects_canonical_fixture_overlap_before_rendering(self) -> None:
        places = self.fixture.read("places")
        old_id = places[0]["id"]
        places[0]["id"] = "00000000-0000-4000-8000-000000000101"
        self.fixture.write("places", places)
        media = self.fixture.read("places-media")
        for row in media:
            if row["listingId"] == old_id:
                row["listingId"] = places[0]["id"]
                row["storagePath"] = row["storagePath"].replace(old_id, places[0]["id"])
        self.fixture.write("places-media", media)
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "overlap canonical fixtures"):
            self.load()

    def test_canonical_fixture_slug_guard_matches_the_canonical_seed(self) -> None:
        seed = (COMPILER.REPOSITORY_ROOT / "supabase" / "seed.sql").read_text(encoding="utf-8")
        insert_start = seed.index("insert into public.listings")
        insert_end = seed.index("on conflict (id) do update set", insert_start)
        listing_insert = seed[insert_start:insert_end]
        fixture_positions = sorted(
            (listing_insert.index(f"'{fixture_id}'"), fixture_id)
            for fixture_id in COMPILER.CANONICAL_FIXTURES
        )
        for index, (start, fixture_id) in enumerate(fixture_positions):
            end = fixture_positions[index + 1][0] if index + 1 < len(fixture_positions) else len(listing_insert)
            expected_slug = COMPILER.CANONICAL_FIXTURES[fixture_id]
            self.assertIn(f"'{expected_slug}'", listing_insert[start:end])

    def test_rejects_missing_media_manifest(self) -> None:
        (self.fixture.fragments / "events-media.json").unlink()
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "Missing required source"):
            self.load()

    def test_rejects_event_schedule_time_zone_drift(self) -> None:
        events = self.fixture.read("events")
        events[0]["detail"]["schedule"]["timeZone"] = "UTC"
        self.fixture.write("events", events)
        sources = self.load()
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "timeZone must match"):
            COMPILER.render_seed_sql(sources)

    def test_changed_anchor_changes_digest_marker_and_event_times(self) -> None:
        first = self.load()
        raw = json.loads(self.fixture.config.read_text(encoding="utf-8"))
        raw["catalogAnchorDate"] = "2026-08-20"
        self.fixture.config.write_text(json.dumps(raw), encoding="utf-8")
        second = self.load()
        self.assertNotEqual(first.source_digest, second.source_digest)
        self.assertNotEqual(first.anchor_marker, second.anchor_marker)
        self.assertIn("2026-08-21T18:30:00+01:00", COMPILER.render_seed_sql(second))

    def test_rejects_pending_media_rights(self) -> None:
        raw = json.loads(self.fixture.config.read_text(encoding="utf-8"))
        raw["mediaRightsApproval"]["status"] = "pending-product-owner-confirmation"
        self.fixture.config.write_text(json.dumps(raw), encoding="utf-8")
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "rights are not approved"):
            self.load()

    def test_rejects_duplicate_slug(self) -> None:
        places = self.fixture.read("places")
        places[1]["slug"] = places[0]["slug"]
        self.fixture.write("places", places)
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "slugs must be globally unique"):
            self.load()

    def test_rejects_canonical_fixture_slug_overlap(self) -> None:
        events = self.fixture.read("events")
        events[0]["slug"] = "festival-culturel-ouidah-test"
        self.fixture.write("events", events)
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "slugs overlap canonical fixtures"):
            self.load()

    def test_rejects_duplicate_room_identity_and_order(self) -> None:
        hotels = self.fixture.read("hotels")
        hotels[1]["detail"]["roomTypes"][0]["id"] = hotels[0]["detail"]["roomTypes"][0]["id"]
        self.fixture.write("hotels", hotels)
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "Duplicate room UUID"):
            self.load()

        hotels = self.fixture.read("hotels")
        hotels[1]["detail"]["roomTypes"].append(
            {
                "id": "90000000-0000-4000-8000-000000000401",
                "name": "Autre chambre",
                "priceXof": 40000,
                "displayOrder": 0,
            }
        )
        self.fixture.write("hotels", hotels)
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "display orders must be unique"):
            self.load()

    def test_rejects_duplicate_ticket_tier_order(self) -> None:
        events = self.fixture.read("events")
        events[0]["detail"]["ticketTiers"].append(
            {"label": "Autre", "priceXof": 5000, "displayOrder": 0}
        )
        self.fixture.write("events", events)
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "display orders must be unique"):
            self.load()

    def test_rejects_duplicate_media_storage_path(self) -> None:
        media = self.fixture.read("places-media")
        media[1]["storagePath"] = media[0]["storagePath"]
        self.fixture.write("places-media", media)
        with self.assertRaisesRegex(COMPILER.CatalogCompileError, "storage paths must be globally unique"):
            self.load()


if __name__ == "__main__":
    unittest.main()
