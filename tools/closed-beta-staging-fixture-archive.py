#!/usr/bin/env python3
"""Archive the four historical staging fixtures with fail-closed evidence."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any, Mapping, Sequence
import zipfile


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DATABASE_GUARD_PATH = REPOSITORY_ROOT / "tools" / "closed-beta-staging-database.py"
EXPECTED_REPOSITORY = "urbainmorel/KWABOR"
EXPECTED_WORKFLOW = ".github/workflows/closed-beta-staging-fixture-archive.yml"
EXPECTED_CI_WORKFLOW = ".github/workflows/ci.yml"
EXPECTED_REF = "refs/heads/main"
TASK_ID = "BETA-STAGING-001.fixture-archive"
CONTRIBUTES_TO = "G5"
APPLY_CONFIRMATION = "ARCHIVE-EXACT-FOUR-STAGING-FIXTURES"
GEL_FILENAME = "GEL-G5-STAGING-FIXTURE-ARCHIVE.json"
GEL_HASH_FILENAME = f"{GEL_FILENAME}.sha256"
STATE_FILENAME = "STAGING-FIXTURE-STATE.json"
SCHEMA_VERSION = 1
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
COMMIT_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
POSITIVE_INTEGER_PATTERN = re.compile(r"^[1-9][0-9]*$")

# The hashes were derived from the hosted rows with only lifecycle timestamps/status and
# generated columns excluded. Every business column, including ownership/contact fields,
# remains covered. A schema or content change therefore fails closed until explicitly audited.
FIXTURES: tuple[tuple[str, str, str], ...] = (
    (
        "00000000-0000-4000-8000-000000000101",
        "porte-du-non-retour-ouidah",
        "86e72c58a55eace124b0882a97e382ac7726797e8518b77ca2caa73a9f8e2f83",
    ),
    (
        "00000000-0000-4000-8000-000000000102",
        "marche-dantokpa-cotonou",
        "dc8ccbc4f0308cc163dec802dd0d02141fe7e524beeaac39b7a5758e0e66463d",
    ),
    (
        "00000000-0000-4000-8000-000000000103",
        "table-locale-cotonou",
        "172fd05b8e8631291c27c24029112ff33b76bb6ec5b26607b443e41959a50c38",
    ),
    (
        "00000000-0000-4000-8000-000000000104",
        "festival-culturel-ouidah-test",
        "5504711033fccf4a01ffb8c7b438f531d6afe099cd7e5b79a1e0a0519949529c",
    ),
)
FIXTURE_IDS = tuple(item[0] for item in FIXTURES)
EVENT_FIXTURE_ID = FIXTURE_IDS[-1]
EXPECTED_PUBLIC_FK_COUNT = 65
EXPECTED_PUBLIC_FK_SHA256 = "7741df4e3c035770253e401cd398d9e9967b58a7ec56d84b9de992bdf9483f19"
EXPECTED_LISTING_TRIGGER_COUNT = 7
EXPECTED_LISTING_TRIGGER_SHA256 = "763a9d05798821363e2c78cf0f1c946164e192fdcd1005372c8f88cc886286ef"
EXPECTED_FIXTURE_SET_SHA256 = "420ca974ca1471e336f88761c9ca50ebdd92c3e8ca1f4c584aa129c8b77a8bb3"
EXPECTED_CHILD_ROW_COUNT = 14
EXPECTED_CHILD_SET_SHA256 = "bfdaea9f1926cb2a86b88cb2b633fa39957b542b1c3cdbe2d553e1cf088b94d7"
EXPECTED_CREATED_AT_SET_SHA256 = "18e3adf43fcbf9bd74585d91817303a1816bbf4f0998fb6bee9afdcf4b9bba86"
EXPECTED_PUBLISHED_LIFECYCLE_SET_SHA256 = (
    "563590d13fc324cd9d5e5f6634c342620640fc5bc531e56c420eec13a139a1d4"
)


class FixtureArchiveError(RuntimeError):
    """Stable, non-sensitive failure raised by the staging fixture operation."""

    def __init__(self, code: str) -> None:
        if re.fullmatch(r"[A-Z0-9_]{3,100}", code) is None:
            raise ValueError("Fixture archive errors require stable codes")
        self.code = code
        super().__init__(code)


def require(condition: bool, code: str) -> None:
    if not condition:
        raise FixtureArchiveError(code)


def canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def positive_integer(value: object, code: str) -> int:
    require(isinstance(value, str) and POSITIVE_INTEGER_PATTERN.fullmatch(value) is not None, code)
    return int(value)


def load_json(path: Path, code: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise FixtureArchiveError(code) from error
    require(isinstance(value, dict), code)
    return value


def write_json_exclusive(path: Path, value: Mapping[str, Any]) -> None:
    require(not path.exists() and not path.is_symlink(), "EVIDENCE_PATH_ALREADY_EXISTS")
    path.write_bytes(canonical_json_bytes(value))


def write_sidecar_exclusive(path: Path, target: Path) -> None:
    require(not path.exists() and not path.is_symlink(), "EVIDENCE_PATH_ALREADY_EXISTS")
    path.write_text(f"{sha256_file(target)}  {target.name}\n", encoding="ascii")


def load_database_guard() -> Any:
    spec = importlib.util.spec_from_file_location("kwabor_staging_database_guard", DATABASE_GUARD_PATH)
    require(spec is not None and spec.loader is not None, "DATABASE_GUARD_IMPORT_FAILED")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    except Exception as error:  # pragma: no cover - exact cause is intentionally not surfaced
        raise FixtureArchiveError("DATABASE_GUARD_IMPORT_FAILED") from error
    return module


def expected_rows_sql() -> str:
    rows = ",\n      ".join(
        f"('{fixture_id}'::uuid, '{slug}', '{digest}')" for fixture_id, slug, digest in FIXTURES
    )
    return f"values\n      {rows}"


def target_ids_sql(ids: Sequence[str] = FIXTURE_IDS) -> str:
    return ",\n      ".join(f"'{fixture_id}'::uuid" for fixture_id in ids)


def child_rows_sql() -> str:
    ids = target_ids_sql()
    target = f"array[\n      {ids}\n    ]::uuid[]"
    statements = (
        ("public.campaigns", f"campaign.listing_id = any({target})", "campaign"),
        ("public.claims", f"claim_row.listing_id = any({target})", "claim_row"),
        (
            "public.event_details",
            f"detail.listing_id = any({target}) or detail.venue_listing_id = any({target})",
            "detail",
        ),
        ("public.favorites", f"favorite.listing_id = any({target})", "favorite"),
        ("public.food_details", f"detail.listing_id = any({target})", "detail"),
        ("public.guide_details", f"detail.listing_id = any({target})", "detail"),
        ("public.likes", f"like_row.listing_id = any({target})", "like_row"),
        ("public.listing_amenities", f"link.listing_id = any({target})", "link"),
        ("public.listing_media", f"media.listing_id = any({target})", "media"),
        ("public.lodging_details", f"detail.listing_id = any({target})", "detail"),
        ("public.nightlife_details", f"detail.listing_id = any({target})", "detail"),
        (
            "public.notifications",
            f"notification.related_listing_id = any({target})",
            "notification",
        ),
        ("public.place_details", f"detail.listing_id = any({target})", "detail"),
        ("public.promoter_invites", f"invite.listing_id = any({target})", "invite"),
        ("public.social_posts", f"post.listing_id = any({target})", "post"),
    )
    direct = [
        f"select '{table}'::text, to_jsonb({row_alias}) from {table} {row_alias} where {condition}"
        for table, condition, row_alias in statements
    ]
    indirect = [
        (
            "select 'public.payments'::text, to_jsonb(payment) from public.payments payment "
            "join public.campaigns campaign on campaign.id = payment.campaign_id "
            f"where campaign.listing_id = any({target})"
        ),
        (
            "select 'public.ticket_tiers'::text, to_jsonb(tier) from public.ticket_tiers tier "
            f"where tier.listing_id = any({target})"
        ),
        (
            "select 'public.room_types'::text, to_jsonb(room) from public.room_types room "
            f"where room.listing_id = any({target})"
        ),
        (
            "select 'public.guide_service_cities'::text, to_jsonb(link) "
            "from public.guide_service_cities link "
            f"where link.listing_id = any({target})"
        ),
        (
            "select 'public.guide_service_languages'::text, to_jsonb(link) "
            "from public.guide_service_languages link "
            f"where link.listing_id = any({target})"
        ),
        (
            "select 'public.guide_service_specialties'::text, to_jsonb(link) "
            "from public.guide_service_specialties link "
            f"where link.listing_id = any({target})"
        ),
        (
            "select 'public.social_media'::text, to_jsonb(media) from public.social_media media "
            "join public.social_posts post on post.id = media.post_id "
            f"where post.listing_id = any({target})"
        ),
    ]
    return "\n    union all\n    ".join((*direct, *indirect))


def state_select_sql() -> str:
    ids = target_ids_sql()
    return f"""
with expected(id, slug, business_sha256) as (
  {expected_rows_sql()}
),
target as (
  select
    listing.created_at,
    listing.id,
    listing.slug,
    listing.status::text as status,
    listing.published_at,
    listing.updated_at,
    encode(
      extensions.digest(
        convert_to(
          (
            to_jsonb(listing)
            - array[
                'status', 'published_at', 'created_at', 'updated_at',
                'geog', 'is_claimable', 'catalog_search_document'
              ]
          )::text,
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as business_sha256
  from public.listings listing
  where listing.id = any(array[
      {ids}
    ]::uuid[])
),
child_rows(relation_name, row_payload) as (
    {child_rows_sql()}
),
child_summary as (
  select
    count(*)::bigint as row_count,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(relation_name || '|' || row_payload::text, E'\\n'
              order by relation_name, row_payload::text),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as content_sha256
  from child_rows
),
fk_lines as (
  select format(
    '%I.%I|%I.%I|%s|%s|%s',
    parent_namespace.nspname,
    parent_class.relname,
    child_namespace.nspname,
    child_class.relname,
    constraint_row.conname,
    array_to_string(constraint_row.conkey, ','),
    array_to_string(constraint_row.confkey, ',')
  ) as line
  from pg_constraint constraint_row
  join pg_class child_class on child_class.oid = constraint_row.conrelid
  join pg_namespace child_namespace on child_namespace.oid = child_class.relnamespace
  join pg_class parent_class on parent_class.oid = constraint_row.confrelid
  join pg_namespace parent_namespace on parent_namespace.oid = parent_class.relnamespace
  where constraint_row.contype = 'f'
    and child_namespace.nspname = 'public'
),
fk_summary as (
  select
    count(*)::bigint as row_count,
    encode(
      extensions.digest(
        convert_to(coalesce(string_agg(line, E'\\n' order by line), ''), 'UTF8'),
        'sha256'
      ),
      'hex'
    ) as content_sha256
  from fk_lines
),
trigger_lines as (
  select format(
    '%s|%s|%s',
    trigger_row.tgname,
    pg_get_triggerdef(trigger_row.oid, true),
    procedure_row.proname
  ) as line
  from pg_trigger trigger_row
  join pg_proc procedure_row on procedure_row.oid = trigger_row.tgfoid
  where trigger_row.tgrelid = 'public.listings'::regclass
    and not trigger_row.tgisinternal
),
trigger_summary as (
  select
    count(*)::bigint as row_count,
    encode(
      extensions.digest(
        convert_to(coalesce(string_agg(line, E'\\n' order by line), ''), 'UTF8'),
        'sha256'
      ),
      'hex'
    ) as content_sha256
  from trigger_lines
)
select jsonb_build_object(
  'archivedListings', (select count(*) from target where status = 'archive'),
  'businessContentExact', (
    select count(*) = 4
      and count(*) filter (
        where target.slug = expected.slug
          and target.business_sha256 = expected.business_sha256
      ) = 4
    from target
    join expected using (id)
  ),
  'childRowCount', (select row_count from child_summary),
  'childSetSha256', (select content_sha256 from child_summary),
  'createdAtSetSha256', encode(
    extensions.digest(
      convert_to(
        coalesce(
          (select string_agg(id::text || ':' || to_jsonb(created_at)::text, E'\\n' order by id)
           from target),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ),
  'fixtureSetSha256', encode(
    extensions.digest(
      convert_to(
        coalesce(
          (select string_agg(id::text || ':' || business_sha256, E'\\n' order by id) from target),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ),
  'identityExact', (
    (select count(*) from target) = 4
    and (select count(*) from target join expected using (id) where target.slug = expected.slug) = 4
    and (
      select count(*)
      from public.listings listing
      join expected on expected.slug = listing.slug
      where listing.id <> expected.id
    ) = 0
  ),
  'listingTriggerCount', (select row_count from trigger_summary),
  'listingTriggerSha256', (select content_sha256 from trigger_summary),
  'lifecycleSetSha256', encode(
    extensions.digest(
      convert_to(
        coalesce(
          (
            select string_agg(
              id::text || '|' || status || '|' || coalesce(to_jsonb(published_at)::text, 'null')
                || '|' || to_jsonb(updated_at)::text,
              E'\\n' order by id
            )
            from target
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ),
  'otherPublishedListings', (
    select count(*) from public.listings listing
    where listing.status = 'publie'
      and listing.id <> all(array[
        {ids}
      ]::uuid[])
  ),
  'publicForeignKeyCount', (select row_count from fk_summary),
  'publicForeignKeySha256', (select content_sha256 from fk_summary),
  'publishedAtSemanticsExact', not exists (
    select 1 from target
    where (status = 'publie' and published_at is null)
       or (status = 'archive' and published_at is not null)
       or status not in ('publie', 'archive')
  ),
  'publishedListings', (select count(*) from target where status = 'publie'),
  'schemaVersion', {SCHEMA_VERSION},
  'targetListings', (select count(*) from target)
)::text
""".strip()


def read_state_sql() -> str:
    return f"""
begin isolation level repeatable read read only;
set local timezone = 'UTC';
set local lock_timeout = '5s';
set local statement_timeout = '120s';
select case
  when pg_try_advisory_xact_lock_shared(hashtextextended('kwabor-staging-fixture-archive-v1', 0))
  then 'ADVISORY_LOCK_ACQUIRED'
  else 'ADVISORY_LOCK_REFUSED'
end;
{state_select_sql()};
commit;
""".strip()


CLOSURE_TABLES = (
    "public.campaigns",
    "public.claims",
    "public.event_details",
    "public.favorites",
    "public.food_details",
    "public.guide_details",
    "public.guide_service_cities",
    "public.guide_service_languages",
    "public.guide_service_specialties",
    "public.likes",
    "public.listing_amenities",
    "public.listing_media",
    "public.lodging_details",
    "public.nightlife_details",
    "public.notifications",
    "public.payments",
    "public.place_details",
    "public.promoter_invites",
    "public.room_types",
    "public.social_media",
    "public.social_posts",
    "public.ticket_tiers",
)


def sql_literal(value: str) -> str:
    require(re.fullmatch(r"[a-z0-9_:-]+", value) is not None, "SQL_LITERAL_INVALID")
    return "'" + value.replace("'", "''") + "'"


def apply_sql(
    plan_state: Mapping[str, Any],
    *,
    apply_valid_until: datetime,
) -> str:
    plan_mode = state_mode(plan_state)
    plan_child_digest = str(plan_state["childSetSha256"])
    plan_child_rows = int(plan_state["childRowCount"])
    plan_created_at_digest = str(plan_state["createdAtSetSha256"])
    plan_lifecycle_digest = str(plan_state["lifecycleSetSha256"])
    require(SHA256_PATTERN.fullmatch(plan_child_digest) is not None, "PLAN_CHILD_DIGEST_INVALID")
    require(SHA256_PATTERN.fullmatch(plan_created_at_digest) is not None, "PLAN_CREATED_AT_DIGEST_INVALID")
    require(SHA256_PATTERN.fullmatch(plan_lifecycle_digest) is not None, "PLAN_LIFECYCLE_DIGEST_INVALID")
    allowed_current_modes = [plan_mode] if plan_mode == "archived" else ["published", "archived"]
    allowed_mode_sql = ", ".join(sql_literal(value) for value in allowed_current_modes)
    locks = "\n".join(f"lock table {table} in share mode;" for table in CLOSURE_TABLES)
    non_event_ids = target_ids_sql(FIXTURE_IDS[:-1])
    state_sql = state_select_sql()
    deadline_text = apply_valid_until.astimezone(timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )
    require(
        re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", deadline_text)
        is not None,
        "BACKUP_APPLY_WINDOW_INVALID",
    )
    deadline_sql = f"'{deadline_text}'"
    return f"""
begin isolation level serializable;
set local timezone = 'UTC';
set local lock_timeout = '5s';
set local statement_timeout = '120s';
set local idle_in_transaction_session_timeout = '60s';
do $backup_window$
begin
  if clock_timestamp() >= {deadline_sql}::timestamptz then
    raise exception 'BACKUP_APPLY_WINDOW_EXPIRED' using errcode = 'P0001';
  end if;
end;
$backup_window$;
do $operation_lock$
begin
  if not pg_try_advisory_xact_lock(hashtextextended('kwabor-staging-fixture-archive-v1', 0)) then
    raise exception 'FIXTURE_ARCHIVE_OPERATION_LOCKED' using errcode = 'P0001';
  end if;
end;
$operation_lock$;
lock table public.listings in share row exclusive mode;
{locks}
create temporary table kwabor_fixture_archive_state(
  phase text primary key,
  document jsonb not null
) on commit drop;
insert into kwabor_fixture_archive_state(phase, document)
select 'before', result::jsonb from (
  {state_sql}
) state(result);
do $guard$
declare
  state jsonb;
  mode text;
begin
  select document into strict state
  from pg_temp.kwabor_fixture_archive_state
  where phase = 'before';
  mode := case
    when (state ->> 'publishedListings')::integer = 4
      and (state ->> 'archivedListings')::integer = 0 then 'published'
    when (state ->> 'publishedListings')::integer = 0
      and (state ->> 'archivedListings')::integer = 4 then 'archived'
    else 'drifted'
  end;
  if mode not in ({allowed_mode_sql})
    or (state ->> 'targetListings')::integer <> 4
    or (state ->> 'identityExact')::boolean is not true
    or (state ->> 'businessContentExact')::boolean is not true
    or (state ->> 'publishedAtSemanticsExact')::boolean is not true
    or (state ->> 'otherPublishedListings')::integer <> 0
    or (state ->> 'childSetSha256') <> '{plan_child_digest}'
    or (state ->> 'childRowCount')::bigint <> {plan_child_rows}
    or (state ->> 'createdAtSetSha256') <> '{plan_created_at_digest}'
    or (
      mode = '{plan_mode}'
      and (state ->> 'lifecycleSetSha256') <> '{plan_lifecycle_digest}'
    )
    or (state ->> 'publicForeignKeyCount')::integer <> {EXPECTED_PUBLIC_FK_COUNT}
    or (state ->> 'publicForeignKeySha256') <> '{EXPECTED_PUBLIC_FK_SHA256}'
    or (state ->> 'listingTriggerCount')::integer <> {EXPECTED_LISTING_TRIGGER_COUNT}
    or (state ->> 'listingTriggerSha256') <> '{EXPECTED_LISTING_TRIGGER_SHA256}'
  then
    raise exception 'FIXTURE_ARCHIVE_PRECONDITION_DRIFT' using errcode = 'P0001';
  end if;
end;
$guard$;
do $backup_window_final$
begin
  if clock_timestamp() >= {deadline_sql}::timestamptz then
    raise exception 'BACKUP_APPLY_WINDOW_EXPIRED' using errcode = 'P0001';
  end if;
end;
$backup_window_final$;
-- The event must be archived before its venue because the existing integrity trigger
-- refuses to hide a venue referenced by an active event.
update public.listings
set status = 'archive', published_at = null
where id = '{EVENT_FIXTURE_ID}'::uuid
  and row(status, published_at)
    is distinct from row('archive'::public.listing_status, null::timestamptz);
update public.listings
set status = 'archive', published_at = null
where id in (
  {non_event_ids}
)
  and row(status, published_at)
    is distinct from row('archive'::public.listing_status, null::timestamptz);
set constraints all immediate;
insert into kwabor_fixture_archive_state(phase, document)
select 'after', result::jsonb from (
  {state_sql}
) state(result);
do $proof$
declare
  before_state jsonb;
  after_state jsonb;
begin
  select document into strict before_state
  from pg_temp.kwabor_fixture_archive_state where phase = 'before';
  select document into strict after_state
  from pg_temp.kwabor_fixture_archive_state where phase = 'after';
  if (after_state ->> 'targetListings')::integer <> 4
    or (after_state ->> 'publishedListings')::integer <> 0
    or (after_state ->> 'archivedListings')::integer <> 4
    or (after_state ->> 'identityExact')::boolean is not true
    or (after_state ->> 'businessContentExact')::boolean is not true
    or (after_state ->> 'publishedAtSemanticsExact')::boolean is not true
    or (after_state ->> 'otherPublishedListings')::integer <> 0
    or (after_state ->> 'fixtureSetSha256') <> (before_state ->> 'fixtureSetSha256')
    or (after_state ->> 'createdAtSetSha256') <> (before_state ->> 'createdAtSetSha256')
    or (after_state ->> 'childSetSha256') <> (before_state ->> 'childSetSha256')
    or (after_state ->> 'childRowCount') <> (before_state ->> 'childRowCount')
  then
    raise exception 'FIXTURE_ARCHIVE_POSTCONDITION_DRIFT' using errcode = 'P0001';
  end if;
end;
$proof$;
select jsonb_build_object(
  'after', (select document from kwabor_fixture_archive_state where phase = 'after'),
  'before', (select document from kwabor_fixture_archive_state where phase = 'before'),
  'schemaVersion', {SCHEMA_VERSION}
)::text;
commit;
""".strip()


def state_mode(document: Mapping[str, Any]) -> str:
    target = document.get("targetListings")
    published = document.get("publishedListings")
    archived = document.get("archivedListings")
    if target == 4 and published == 4 and archived == 0:
        return "published"
    if target == 4 and published == 0 and archived == 4:
        return "archived"
    raise FixtureArchiveError("FIXTURE_STATUS_DRIFT")


def validate_state(document: Mapping[str, Any], *, required_mode: str | None = None) -> dict[str, Any]:
    base = dict(document)
    base.pop("mode", None)
    base.pop("stateSha256", None)
    integer_keys = (
        "archivedListings",
        "childRowCount",
        "listingTriggerCount",
        "otherPublishedListings",
        "publicForeignKeyCount",
        "publishedListings",
        "schemaVersion",
        "targetListings",
    )
    for key in integer_keys:
        require(type(base.get(key)) is int and int(base[key]) >= 0, "FIXTURE_STATE_INVALID")
    for key in ("businessContentExact", "identityExact", "publishedAtSemanticsExact"):
        require(isinstance(base.get(key), bool), "FIXTURE_STATE_INVALID")
    for key in (
        "childSetSha256",
        "createdAtSetSha256",
        "fixtureSetSha256",
        "lifecycleSetSha256",
        "listingTriggerSha256",
        "publicForeignKeySha256",
    ):
        require(
            isinstance(base.get(key), str)
            and SHA256_PATTERN.fullmatch(str(base[key])) is not None,
            "FIXTURE_STATE_INVALID",
        )
    require(base["schemaVersion"] == SCHEMA_VERSION, "FIXTURE_STATE_SCHEMA_DRIFT")
    require(base["identityExact"] is True, "FIXTURE_IDENTITY_DRIFT")
    require(base["businessContentExact"] is True, "FIXTURE_CONTENT_DRIFT")
    require(base["publishedAtSemanticsExact"] is True, "FIXTURE_LIFECYCLE_DRIFT")
    require(base["otherPublishedListings"] == 0, "OTHER_PUBLISHED_LISTING_FOUND")
    require(base["fixtureSetSha256"] == EXPECTED_FIXTURE_SET_SHA256, "FIXTURE_SET_DRIFT")
    require(
        base["childRowCount"] == EXPECTED_CHILD_ROW_COUNT
        and base["childSetSha256"] == EXPECTED_CHILD_SET_SHA256,
        "FIXTURE_CHILD_DRIFT",
    )
    require(
        base["createdAtSetSha256"] == EXPECTED_CREATED_AT_SET_SHA256,
        "FIXTURE_CREATED_AT_DRIFT",
    )
    require(base["publicForeignKeyCount"] == EXPECTED_PUBLIC_FK_COUNT, "PUBLIC_FK_SCHEMA_DRIFT")
    require(base["publicForeignKeySha256"] == EXPECTED_PUBLIC_FK_SHA256, "PUBLIC_FK_SCHEMA_DRIFT")
    require(base["listingTriggerCount"] == EXPECTED_LISTING_TRIGGER_COUNT, "LISTING_TRIGGER_DRIFT")
    require(base["listingTriggerSha256"] == EXPECTED_LISTING_TRIGGER_SHA256, "LISTING_TRIGGER_DRIFT")
    mode = state_mode(base)
    if mode == "published":
        require(
            base["lifecycleSetSha256"] == EXPECTED_PUBLISHED_LIFECYCLE_SET_SHA256,
            "FIXTURE_LIFECYCLE_DRIFT",
        )
    if required_mode is not None:
        require(mode == required_mode, "FIXTURE_STATUS_DRIFT")
    result = dict(base)
    result["mode"] = mode
    result["stateSha256"] = sha256_bytes(canonical_json_bytes(base))
    return result


def parse_state_output(output: str) -> dict[str, Any]:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    require("ADVISORY_LOCK_REFUSED" not in lines, "STAGING_OPERATION_LOCKED")
    documents: list[dict[str, Any]] = []
    for line in lines:
        if not line.startswith("{"):
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise FixtureArchiveError("PSQL_OUTPUT_INVALID") from error
        require(isinstance(value, dict), "PSQL_OUTPUT_INVALID")
        documents.append(value)
    require(len(documents) == 1, "PSQL_OUTPUT_INVALID")
    return documents[0]


@dataclass(frozen=True)
class PsqlResult:
    returncode: int
    stdout: str


def sanitized_psql_environment(database_url: str) -> dict[str, str]:
    allowed = {
        "CI",
        "GITHUB_ACTIONS",
        "HOME",
        "LANG",
        "LC_ALL",
        "PATH",
        "RUNNER_ARCH",
        "RUNNER_ENVIRONMENT",
        "RUNNER_OS",
        "RUNNER_TEMP",
        "SYSTEMROOT",
        "TMP",
        "TEMP",
    }
    environment = {key: value for key, value in os.environ.items() if key in allowed}
    environment.update(
        {
            "PGCONNECT_TIMEOUT": "10",
            "PGDATABASE": database_url,
            "PGTZ": "UTC",
        }
    )
    return environment


def run_psql(database_url: str, sql: str, *, timeout: int = 180) -> PsqlResult:
    command = [
        "psql",
        "--no-psqlrc",
        "--quiet",
        "--tuples-only",
        "--no-align",
        "--set",
        "ON_ERROR_STOP=1",
        "--command",
        sql,
    ]
    require(database_url not in command, "DATABASE_SECRET_IN_COMMAND")
    try:
        result = subprocess.run(
            command,
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=sanitized_psql_environment(database_url),
            timeout=timeout,
        )
    except FileNotFoundError as error:
        raise FixtureArchiveError("PSQL_NOT_AVAILABLE") from error
    except subprocess.TimeoutExpired as error:
        raise FixtureArchiveError("PSQL_TIMEOUT") from error
    return PsqlResult(returncode=result.returncode, stdout=result.stdout)


def inspect_state(database_url: str, *, required_mode: str | None = None) -> dict[str, Any]:
    result = run_psql(database_url, read_state_sql())
    require(result.returncode == 0, "STATE_QUERY_FAILED")
    return validate_state(parse_state_output(result.stdout), required_mode=required_mode)


def parse_apply_output(output: str) -> tuple[dict[str, Any], dict[str, Any]]:
    document = parse_state_output(output)
    require(document.get("schemaVersion") == SCHEMA_VERSION, "APPLY_OUTPUT_INVALID")
    before = document.get("before")
    after = document.get("after")
    require(isinstance(before, dict) and isinstance(after, dict), "APPLY_OUTPUT_INVALID")
    return validate_state(before), validate_state(after, required_mode="archived")


def summarize_ci(document: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "conclusion": document["conclusion"],
        "event": document["event"],
        "headBranch": document["headBranch"],
        "headSha": document["headSha"],
        "runAttempt": document["runAttempt"],
        "runId": document["runId"],
        "runUrl": document["runUrl"],
        "status": document["status"],
        "workflowPath": document["workflowPath"],
    }


def summarize_backup(document: Mapping[str, Any]) -> dict[str, Any]:
    keys = (
        "applyValidUntil",
        "artifactId",
        "artifactName",
        "artifactSha256",
        "databaseFingerprintSha256",
        "expiresAt",
        "internalReceiptSha256",
        "migrationPrefixCount",
        "migrationPrefixSha256",
        "restorable",
        "runAttempt",
        "runId",
        "targetDigestSha256",
    )
    require(all(key in document for key in keys), "BACKUP_EVIDENCE_INVALID")
    summary = {key: document[key] for key in keys}
    require(summary["restorable"] is True, "BACKUP_NOT_RESTORABLE")
    return summary


def require_backup_apply_window(
    guard: Any,
    backup: Mapping[str, Any],
    *,
    now: datetime | None = None,
) -> datetime:
    deadline = guard.parse_github_timestamp(
        backup.get("applyValidUntil"),
        "BACKUP_APPLY_WINDOW_INVALID",
    )
    current = now or datetime.now(timezone.utc)
    require(current.astimezone(timezone.utc) < deadline, "BACKUP_APPLY_WINDOW_EXPIRED")
    return deadline


def validate_plan_artifact(
    guard: Any,
    *,
    run_document: Mapping[str, Any],
    artifact_document: Mapping[str, Any],
    archive_path: Path,
    plan_run_id: int,
    plan_artifact_id: int,
    plan_artifact_digest: str,
    expected_sha: str,
    validated_ci_run_id: int,
    environment_evidence: Mapping[str, Any],
    target_evidence: Mapping[str, Any],
    backup_summary: Mapping[str, Any],
) -> dict[str, Any]:
    run_evidence = guard.validate_supporting_workflow_run(
        run_document,
        expected_run_id=plan_run_id,
        expected_sha=expected_sha,
        expected_workflow=EXPECTED_WORKFLOW,
    )
    expected_name = (
        f"kwabor-gel-g5-staging-fixture-archive-plan-{expected_sha}-"
        f"{run_evidence['runAttempt']}"
    )
    artifact_evidence = guard.validate_artifact_metadata(
        artifact_document,
        expected_artifact_id=plan_artifact_id,
        expected_run_id=plan_run_id,
        expected_repository_id=run_evidence["repositoryId"],
        expected_sha=expected_sha,
        expected_name=expected_name,
        expected_digest=plan_artifact_digest,
    )
    require(artifact_evidence["sizeBytes"] <= 32 * 1024 * 1024, "PLAN_ARTIFACT_TOO_LARGE")
    entries = guard.load_artifact_entries(
        archive_path,
        expected_digest=plan_artifact_digest,
        required_entries=(GEL_FILENAME, GEL_HASH_FILENAME, STATE_FILENAME),
        max_archive_bytes=32 * 1024 * 1024,
        max_total_uncompressed_bytes=8 * 1024 * 1024,
    )
    require(archive_path.stat().st_size == artifact_evidence["sizeBytes"], "PLAN_ARTIFACT_SIZE_DRIFT")
    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            require(
                {member.filename for member in archive.infolist()}
                == {GEL_FILENAME, GEL_HASH_FILENAME, STATE_FILENAME},
                "PLAN_ARTIFACT_CONTENT_SET_INVALID",
            )
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise FixtureArchiveError("PLAN_ARTIFACT_INVALID") from error
    receipt_digest = sha256_bytes(entries[GEL_FILENAME])
    try:
        sidecar = entries[GEL_HASH_FILENAME].decode("ascii")
        receipt = json.loads(entries[GEL_FILENAME].decode("utf-8"))
        state = json.loads(entries[STATE_FILENAME].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise FixtureArchiveError("PLAN_RECEIPT_INVALID") from error
    require(
        sidecar == f"{receipt_digest}  {GEL_FILENAME}\n",
        "PLAN_RECEIPT_HASH_INVALID",
    )
    require(isinstance(receipt, dict) and isinstance(state, dict), "PLAN_RECEIPT_INVALID")
    guard.assert_safe_document(receipt)
    guard.assert_safe_document(state)
    require(receipt.get("schemaVersion") == SCHEMA_VERSION, "PLAN_RECEIPT_SCHEMA_DRIFT")
    require(receipt.get("taskId") == TASK_ID, "PLAN_RECEIPT_TASK_DRIFT")
    require(receipt.get("contributesTo") == CONTRIBUTES_TO, "PLAN_RECEIPT_GATE_DRIFT")
    require(receipt.get("repository") == EXPECTED_REPOSITORY, "PLAN_RECEIPT_REPOSITORY_DRIFT")
    require(receipt.get("workflowPath") == EXPECTED_WORKFLOW, "PLAN_RECEIPT_WORKFLOW_DRIFT")
    require(receipt.get("ref") == EXPECTED_REF, "PLAN_RECEIPT_REF_DRIFT")
    require(receipt.get("operation") == "plan", "PLAN_RECEIPT_OPERATION_DRIFT")
    require(receipt.get("status") == "succeeded", "PLAN_RECEIPT_NOT_SUCCESSFUL")
    require(receipt.get("errorCode") is None, "PLAN_RECEIPT_NOT_SUCCESSFUL")
    require(receipt.get("gateClosed") is False, "PLAN_RECEIPT_GATE_DRIFT")
    require(receipt.get("mutationState") == "not_started", "PLAN_RECEIPT_MUTATION_DRIFT")
    require(receipt.get("expectedSha") == expected_sha, "PLAN_RECEIPT_SHA_DRIFT")
    require(receipt.get("validatedCiRunId") == validated_ci_run_id, "PLAN_RECEIPT_CI_DRIFT")
    receipt_ci = receipt.get("ci")
    require(
        isinstance(receipt_ci, dict)
        and receipt_ci.get("runId") == validated_ci_run_id
        and receipt_ci.get("headSha") == expected_sha
        and receipt_ci.get("event") == "push"
        and receipt_ci.get("headBranch") == "main"
        and receipt_ci.get("workflowPath") == EXPECTED_CI_WORKFLOW
        and receipt_ci.get("status") == "completed"
        and receipt_ci.get("conclusion") == "success",
        "PLAN_RECEIPT_CI_DRIFT",
    )
    require(receipt.get("environmentEvidence") == environment_evidence, "PLAN_RECEIPT_ENVIRONMENT_DRIFT")
    require(receipt.get("target") == target_evidence, "PLAN_RECEIPT_TARGET_DRIFT")
    require(receipt.get("backup") == backup_summary, "PLAN_RECEIPT_BACKUP_DRIFT")
    require(
        receipt.get("runId") == plan_run_id
        and receipt.get("runAttempt") == run_evidence["runAttempt"]
        and receipt.get("runUrl") == run_evidence["runUrl"],
        "PLAN_RECEIPT_RUN_DRIFT",
    )
    evidence = receipt.get("evidence")
    require(
        isinstance(evidence, dict)
        and evidence.get("filename") == STATE_FILENAME
        and evidence.get("sha256") == sha256_bytes(entries[STATE_FILENAME]),
        "PLAN_STATE_EVIDENCE_DRIFT",
    )
    validated_state = validate_state(state)
    require(receipt.get("state") == validated_state, "PLAN_STATE_RECEIPT_DRIFT")
    require(receipt.get("transition") == f"plan-{validated_state['mode']}", "PLAN_RECEIPT_TRANSITION_DRIFT")
    return {
        **artifact_evidence,
        "internalReceiptSha256": receipt_digest,
        "runAttempt": run_evidence["runAttempt"],
        "runId": plan_run_id,
        "state": validated_state,
    }


def request_evidence(guard: Any, args: argparse.Namespace) -> dict[str, Any]:
    try:
        checked_out_sha = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=REPOSITORY_ROOT,
            check=True,
            capture_output=True,
            text=True,
            encoding="ascii",
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise FixtureArchiveError("GIT_HEAD_UNAVAILABLE") from error
    return guard.validate_request_identity(
        repository=os.environ.get("GITHUB_REPOSITORY", ""),
        event_name=os.environ.get("GITHUB_EVENT_NAME", ""),
        github_ref=os.environ.get("GITHUB_REF", ""),
        github_sha=os.environ.get("GITHUB_SHA", ""),
        checked_out_sha=checked_out_sha,
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
        run_id=os.environ.get("GITHUB_RUN_ID", ""),
        run_attempt=os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        actor=os.environ.get("GITHUB_ACTOR", ""),
        server_url=os.environ.get("GITHUB_SERVER_URL", ""),
    )


def build_receipt(
    *,
    args: argparse.Namespace,
    request: Mapping[str, Any],
    ci: Mapping[str, Any],
    environment: Mapping[str, Any],
    target: Mapping[str, Any],
    backup: Mapping[str, Any],
    state: Mapping[str, Any],
    mutation_state: str,
    transition: str,
    plan: Mapping[str, Any] | None,
    status: str = "succeeded",
    error_code: str | None = None,
    retry_disposition: str = "NOT_APPLICABLE",
) -> dict[str, Any]:
    return {
        "backup": dict(backup),
        "ci": summarize_ci(ci),
        "contributesTo": CONTRIBUTES_TO,
        "environmentEvidence": dict(environment),
        "errorCode": error_code,
        "evidence": {
            "filename": STATE_FILENAME,
            "sha256": sha256_bytes(canonical_json_bytes(state)),
        },
        "expectedSha": request["expectedSha"],
        "gate": CONTRIBUTES_TO,
        "gateClosed": False,
        "mutationState": mutation_state,
        "operation": args.operation,
        "plan": dict(plan) if plan is not None else None,
        "qualifiedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "ref": EXPECTED_REF,
        "repository": EXPECTED_REPOSITORY,
        "retryDisposition": retry_disposition,
        "runAttempt": request["runAttempt"],
        "runId": request["runId"],
        "runUrl": request["runUrl"],
        "schemaVersion": SCHEMA_VERSION,
        "state": dict(state),
        "status": status,
        "target": dict(target),
        "taskId": TASK_ID,
        "transition": transition,
        "validatedCiRunId": request["validatedCiRunId"],
        "workflowPath": EXPECTED_WORKFLOW,
    }


def failure_receipt(args: argparse.Namespace, error_code: str) -> dict[str, Any]:
    expected_sha = args.expected_sha if COMMIT_SHA_PATTERN.fullmatch(args.expected_sha or "") else None
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    run_attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "")
    return {
        "errorCode": error_code,
        "expectedSha": expected_sha,
        "gate": CONTRIBUTES_TO,
        "gateClosed": False,
        "mutationState": "indeterminate" if error_code == "APPLY_INDETERMINATE" else "not_started",
        "operation": args.operation,
        "ref": EXPECTED_REF,
        "repository": EXPECTED_REPOSITORY,
        "retryDisposition": "DO_NOT_RETRY" if error_code == "APPLY_INDETERMINATE" else "NOT_APPLICABLE",
        "runAttempt": int(run_attempt) if POSITIVE_INTEGER_PATTERN.fullmatch(run_attempt) else None,
        "runId": int(run_id) if POSITIVE_INTEGER_PATTERN.fullmatch(run_id) else None,
        "schemaVersion": SCHEMA_VERSION,
        "status": "indeterminate" if error_code == "APPLY_INDETERMINATE" else "failed",
        "taskId": TASK_ID,
        "workflowPath": EXPECTED_WORKFLOW,
    }


def write_evidence(
    guard: Any,
    evidence_directory: Path,
    *,
    state: Mapping[str, Any],
    receipt: Mapping[str, Any],
) -> None:
    require(not evidence_directory.is_symlink(), "EVIDENCE_DIRECTORY_INVALID")
    evidence_directory.mkdir(parents=True, exist_ok=True)
    guard.assert_safe_document(state)
    guard.assert_safe_document(receipt)
    state_path = evidence_directory / STATE_FILENAME
    receipt_path = evidence_directory / GEL_FILENAME
    write_json_exclusive(state_path, state)
    write_json_exclusive(receipt_path, receipt)
    write_sidecar_exclusive(evidence_directory / GEL_HASH_FILENAME, receipt_path)


def execute(args: argparse.Namespace) -> None:
    evidence_directory = Path(args.evidence_directory)
    guard = load_database_guard()
    try:
        require(args.operation in {"plan", "apply", "verify"}, "OPERATION_INVALID")
        if args.operation == "apply":
            require(args.apply_confirmation == APPLY_CONFIRMATION, "APPLY_CONFIRMATION_INVALID")
        else:
            require(args.apply_confirmation in {"", None}, "UNEXPECTED_APPLY_CONFIRMATION")
        request = request_evidence(guard, args)
        ci = guard.validate_ci_run(
            load_json(Path(args.ci_run_json), "CI_EVIDENCE_INVALID"),
            expected_run_id=args.validated_ci_run_id,
            expected_sha=args.expected_sha,
        )
        environment = guard.validate_environment_protection(
            load_json(Path(args.environment_json), "ENVIRONMENT_EVIDENCE_INVALID")
        )
        authority = guard.validate_target_authority(
            environment=os.environ.get("KWABOR_ENVIRONMENT", ""),
            api_url=os.environ.get("KWABOR_SUPABASE_URL", ""),
            project_ref=os.environ.get("KWABOR_SUPABASE_PROJECT_REF", ""),
            production_project_ref=os.environ.get("KWABOR_PRODUCTION_SUPABASE_PROJECT_REF", ""),
            project_ref_sha256=os.environ.get("KWABOR_STAGING_PROJECT_REF_SHA256", ""),
            database_url=os.environ.get("KWABOR_STAGING_DATABASE_URL", ""),
        )
        backup_run_id = positive_integer(args.backup_run_id, "BACKUP_RUN_ID_INVALID")
        backup_artifact_id = positive_integer(args.backup_artifact_id, "BACKUP_ARTIFACT_ID_INVALID")
        require(SHA256_PATTERN.fullmatch(args.backup_artifact_digest or "") is not None, "BACKUP_DIGEST_INVALID")
        backup_evidence = guard.validate_backup_artifact_bundle(
            run_document=load_json(Path(args.backup_run_json), "BACKUP_RUN_EVIDENCE_INVALID"),
            artifact_document=load_json(Path(args.backup_artifact_json), "BACKUP_ARTIFACT_EVIDENCE_INVALID"),
            archive_path=Path(args.backup_artifact_zip),
            backup_run_id=backup_run_id,
            backup_artifact_id=backup_artifact_id,
            backup_artifact_digest=args.backup_artifact_digest,
            expected_sha=args.expected_sha,
            validated_ci_run_id=int(args.validated_ci_run_id),
            target_evidence=authority.public_evidence(),
        )
        backup = summarize_backup(backup_evidence)
        plan_evidence: dict[str, Any] | None = None
        plan_state: dict[str, Any] | None = None
        if args.operation == "apply":
            plan_run_id = positive_integer(args.plan_run_id, "PLAN_RUN_ID_INVALID")
            plan_artifact_id = positive_integer(args.plan_artifact_id, "PLAN_ARTIFACT_ID_INVALID")
            require(SHA256_PATTERN.fullmatch(args.plan_artifact_digest or "") is not None, "PLAN_DIGEST_INVALID")
            plan_evidence = validate_plan_artifact(
                guard,
                run_document=load_json(Path(args.plan_run_json), "PLAN_RUN_EVIDENCE_INVALID"),
                artifact_document=load_json(Path(args.plan_artifact_json), "PLAN_ARTIFACT_EVIDENCE_INVALID"),
                archive_path=Path(args.plan_artifact_zip),
                plan_run_id=plan_run_id,
                plan_artifact_id=plan_artifact_id,
                plan_artifact_digest=args.plan_artifact_digest,
                expected_sha=args.expected_sha,
                validated_ci_run_id=int(args.validated_ci_run_id),
                environment_evidence=environment,
                target_evidence=authority.public_evidence(),
                backup_summary=backup,
            )
            plan_state = plan_evidence["state"]
        elif any((args.plan_run_id, args.plan_artifact_id, args.plan_artifact_digest)):
            raise FixtureArchiveError("UNEXPECTED_PLAN_AUTHORITY")

        if args.operation == "plan":
            state = inspect_state(authority.database_url)
            mutation_state = "not_started"
            transition = f"plan-{state['mode']}"
        elif args.operation == "verify":
            state = inspect_state(authority.database_url, required_mode="archived")
            mutation_state = "not_started"
            transition = "verified-already-archived"
        else:
            assert plan_state is not None
            current_state = inspect_state(authority.database_url)
            require(
                current_state["fixtureSetSha256"] == plan_state["fixtureSetSha256"]
                and current_state["createdAtSetSha256"] == plan_state["createdAtSetSha256"]
                and current_state["childSetSha256"] == plan_state["childSetSha256"]
                and current_state["childRowCount"] == plan_state["childRowCount"],
                "PLAN_TO_APPLY_STATE_DRIFT",
            )
            plan_mode = state_mode(plan_state)
            current_mode = state_mode(current_state)
            require(
                current_mode == plan_mode or (plan_mode == "published" and current_mode == "archived"),
                "PLAN_TO_APPLY_STATUS_DRIFT",
            )
            if current_mode == plan_mode:
                require(
                    current_state["lifecycleSetSha256"] == plan_state["lifecycleSetSha256"],
                    "PLAN_TO_APPLY_LIFECYCLE_DRIFT",
                )
            result: PsqlResult | None
            try:
                apply_valid_until = require_backup_apply_window(guard, backup)
                result = run_psql(
                    authority.database_url,
                    apply_sql(
                        plan_state,
                        apply_valid_until=apply_valid_until,
                    ),
                    timeout=240,
                )
            except FixtureArchiveError as error:
                if error.code != "PSQL_TIMEOUT":
                    raise
                result = None
            parsed_apply: tuple[dict[str, Any], dict[str, Any]] | None = None
            if result is not None and result.returncode == 0:
                try:
                    parsed_apply = parse_apply_output(result.stdout)
                except FixtureArchiveError:
                    parsed_apply = None
            if parsed_apply is not None:
                before, state = parsed_apply
                require(
                    before["fixtureSetSha256"] == plan_state["fixtureSetSha256"]
                    and before["createdAtSetSha256"] == plan_state["createdAtSetSha256"]
                    and before["childSetSha256"] == plan_state["childSetSha256"],
                    "PLAN_TO_APPLY_STATE_DRIFT",
                )
                if before["mode"] == plan_mode:
                    require(
                        before["lifecycleSetSha256"] == plan_state["lifecycleSetSha256"],
                        "PLAN_TO_APPLY_LIFECYCLE_DRIFT",
                    )
                mutation_state = "committed" if before["mode"] == "published" else "already_committed"
                transition = (
                    "archived-exact-four" if before["mode"] == "published" else "already-archived"
                )
            else:
                try:
                    reconciled = inspect_state(authority.database_url)
                except FixtureArchiveError as error:
                    raise FixtureArchiveError("APPLY_INDETERMINATE") from error
                if (
                    reconciled["mode"] == "archived"
                    and reconciled["fixtureSetSha256"] == plan_state["fixtureSetSha256"]
                    and reconciled["createdAtSetSha256"] == plan_state["createdAtSetSha256"]
                    and reconciled["childSetSha256"] == plan_state["childSetSha256"]
                    and reconciled["childRowCount"] == plan_state["childRowCount"]
                ):
                    state = reconciled
                    mutation_state = (
                        "committed" if current_state["mode"] == "published" else "already_committed"
                    )
                    transition = (
                        "archived-exact-four-reconciled"
                        if current_state["mode"] == "published"
                        else "already-archived-reconciled"
                    )
                elif reconciled["stateSha256"] == current_state["stateSha256"]:
                    raise FixtureArchiveError("APPLY_NOT_COMMITTED")
                else:
                    raise FixtureArchiveError("APPLY_INDETERMINATE")

        plan_summary = None
        if plan_evidence is not None:
            plan_summary = {
                key: plan_evidence[key]
                for key in (
                    "artifactId",
                    "artifactName",
                    "artifactSha256",
                    "expiresAt",
                    "internalReceiptSha256",
                    "runAttempt",
                    "runId",
                )
            }
        receipt = build_receipt(
            args=args,
            request=request,
            ci=ci,
            environment=environment,
            target=authority.public_evidence(),
            backup=backup,
            state=state,
            mutation_state=mutation_state,
            transition=transition,
            plan=plan_summary,
        )
        write_evidence(guard, evidence_directory, state=state, receipt=receipt)
    except FixtureArchiveError as error:
        evidence_directory.mkdir(parents=True, exist_ok=True)
        if not (evidence_directory / GEL_FILENAME).exists():
            receipt = failure_receipt(args, error.code)
            guard.assert_safe_document(receipt)
            write_json_exclusive(evidence_directory / GEL_FILENAME, receipt)
            write_sidecar_exclusive(evidence_directory / GEL_HASH_FILENAME, evidence_directory / GEL_FILENAME)
        raise
    except Exception as error:
        evidence_directory.mkdir(parents=True, exist_ok=True)
        candidate_code = getattr(error, "code", "PRECONDITION_FAILED")
        error_code = (
            candidate_code
            if isinstance(candidate_code, str)
            and re.fullmatch(r"[A-Z0-9_]{3,100}", candidate_code) is not None
            else "PRECONDITION_FAILED"
        )
        if not (evidence_directory / GEL_FILENAME).exists():
            receipt = failure_receipt(args, error_code)
            guard.assert_safe_document(receipt)
            write_json_exclusive(evidence_directory / GEL_FILENAME, receipt)
            write_sidecar_exclusive(evidence_directory / GEL_HASH_FILENAME, evidence_directory / GEL_FILENAME)
        raise FixtureArchiveError(error_code) from error


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--operation", choices=("plan", "apply", "verify"), required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--validated-ci-run-id", required=True)
    parser.add_argument("--apply-confirmation", default="")
    parser.add_argument("--backup-run-id", required=True)
    parser.add_argument("--backup-artifact-id", required=True)
    parser.add_argument("--backup-artifact-digest", required=True)
    parser.add_argument("--plan-run-id", default="")
    parser.add_argument("--plan-artifact-id", default="")
    parser.add_argument("--plan-artifact-digest", default="")
    parser.add_argument("--ci-run-json", required=True)
    parser.add_argument("--environment-json", required=True)
    parser.add_argument("--backup-run-json", required=True)
    parser.add_argument("--backup-artifact-json", required=True)
    parser.add_argument("--backup-artifact-zip", required=True)
    parser.add_argument("--plan-run-json", required=True)
    parser.add_argument("--plan-artifact-json", required=True)
    parser.add_argument("--plan-artifact-zip", required=True)
    parser.add_argument("--evidence-directory", required=True)
    return parser


def emit_workflow_failure(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description="Emit a sanitized fixture-archive workflow failure receipt")
    parser.add_argument("--operation", choices=("plan", "apply", "verify"), required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--failure-code", required=True)
    parser.add_argument("--evidence-directory", required=True)
    args = parser.parse_args(argv)
    require(re.fullmatch(r"[A-Z0-9_]{3,100}", args.failure_code) is not None, "FAILURE_CODE_INVALID")
    evidence_directory = Path(args.evidence_directory)
    require(not evidence_directory.is_symlink(), "EVIDENCE_DIRECTORY_INVALID")
    evidence_directory.mkdir(parents=True, exist_ok=True)
    receipt_path = evidence_directory / GEL_FILENAME
    if receipt_path.exists():
        return 0
    guard = load_database_guard()
    receipt = failure_receipt(args, args.failure_code)
    guard.assert_safe_document(receipt)
    write_json_exclusive(receipt_path, receipt)
    write_sidecar_exclusive(evidence_directory / GEL_HASH_FILENAME, receipt_path)
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    raw_arguments = list(argv) if argv is not None else sys.argv[1:]
    if raw_arguments and raw_arguments[0] == "emit-failure":
        try:
            return emit_workflow_failure(raw_arguments[1:])
        except FixtureArchiveError as error:
            print(f"Closed-beta fixture failure receipt refused: {error.code}", file=sys.stderr)
            return 1
    args = build_parser().parse_args(raw_arguments)
    try:
        execute(args)
    except FixtureArchiveError as error:
        print(f"Closed-beta staging fixture archive refused: {error.code}", file=sys.stderr)
        return 1
    print(f"OK staging fixture archive operation={args.operation} receipt={GEL_FILENAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
