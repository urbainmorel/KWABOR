#!/usr/bin/env python3
"""Create and verify sanitized Gate Evidence Ledger receipts for the closed beta."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable


EXPECTED_REPOSITORY = "urbainmorel/KWABOR"
EXPECTED_ENVIRONMENT = "staging"
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
POSITIVE_INTEGER_PATTERN = re.compile(r"^[1-9][0-9]*$")
LABEL_PATTERN = re.compile(r"^[a-z][A-Za-z0-9]{0,63}$")
FILENAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,199}$")
ACTOR_PATTERN = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}|[A-Za-z0-9-]{0,93}\[bot\])$")
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")

SENSITIVE_KEY_FRAGMENTS = (
    "apikey",
    "authorization",
    "connectionstring",
    "credential",
    "databaseurl",
    "passwd",
    "password",
    "privatekey",
    "publishablekey",
    "secret",
    "servicerole",
    "token",
)
SUSPICIOUS_VALUE_PATTERNS = (
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?i)\b(?:postgres|postgresql)://"),
    re.compile(r"(?i)\bBearer\s+\S+"),
    re.compile(r"\b(?:ghp_|github_pat_|sb_secret_|sk-)[A-Za-z0-9_-]+"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
    re.compile(r"[A-Za-z0-9+/]{80,}={0,2}"),
    re.compile(r"(?i)\.supabase\.co\b"),
    re.compile(r"(?i)\.gserviceaccount\.com\b"),
)


class GelError(RuntimeError):
    """Raised when evidence is incomplete, inconsistent, or unsafe to archive."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise GelError(message)


@dataclass(frozen=True)
class Profile:
    gate: str
    task_id: str
    artifacts: frozenset[str]
    counters: frozenset[str]
    details: frozenset[str]
    digests: frozenset[str]
    result_kind: str | None = None
    related_result_kind: str | None = None


STORAGE_COUNTERS = frozenset(
    {
        "alreadyAbsentObjects",
        "createdObjects",
        "deletedObjects",
        "manifestObjects",
        "verifiedObjects",
    }
)
DATABASE_COUNTERS = frozenset(
    {
        "afterArchivedListings",
        "afterMedia",
        "afterPublishedListings",
        "afterTaggedListings",
        "afterTargetListings",
        "beforeArchivedListings",
        "beforeMedia",
        "beforePublishedListings",
        "beforeTaggedListings",
        "beforeTargetListings",
    }
)
RELATED_DATABASE_COUNTERS = frozenset(f"database{name[0].upper()}{name[1:]}" for name in DATABASE_COUNTERS)
STAGING_DIGESTS = frozenset({"stagingProjectRefSha256"})
CI_PROVENANCE_ARTIFACT = frozenset({"ciProvenance"})

STORAGE_WORKFLOW = ".github/workflows/closed-beta-demo-storage.yml"
DATABASE_WORKFLOW = ".github/workflows/closed-beta-demo-catalog.yml"
ANDROID_WORKFLOW = ".github/workflows/android-release.yml"

PROFILES: dict[tuple[str, str], Profile] = {
    (STORAGE_WORKFLOW, "publish"): Profile(
        "G5",
        "B6.03",
        frozenset({"manifest", "operationResult"}) | CI_PROVENANCE_ARTIFACT,
        STORAGE_COUNTERS,
        frozenset({"operationMode"}),
        STAGING_DIGESTS,
        result_kind="demo-storage-operation",
    ),
    (STORAGE_WORKFLOW, "verify"): Profile(
        "G5",
        "B6.04",
        frozenset({"manifest", "operationResult"}) | CI_PROVENANCE_ARTIFACT,
        STORAGE_COUNTERS,
        frozenset({"operationMode"}),
        STAGING_DIGESTS,
        result_kind="demo-storage-operation",
    ),
    (STORAGE_WORKFLOW, "rollback"): Profile(
        "G5",
        "B6.09",
        frozenset({"databaseOperationResult", "manifest", "operationResult"})
        | CI_PROVENANCE_ARTIFACT,
        STORAGE_COUNTERS | RELATED_DATABASE_COUNTERS,
        frozenset({"databaseOperationMode", "operationMode"}),
        STAGING_DIGESTS,
        result_kind="demo-storage-operation",
        related_result_kind="demo-catalog-database-operation",
    ),
    (DATABASE_WORKFLOW, "publish"): Profile(
        "G5",
        "B6.05",
        frozenset({"manifest", "operationResult"}) | CI_PROVENANCE_ARTIFACT,
        DATABASE_COUNTERS,
        frozenset({"operationMode"}),
        STAGING_DIGESTS,
        result_kind="demo-catalog-database-operation",
    ),
    (DATABASE_WORKFLOW, "verify"): Profile(
        "G5",
        "B6.05",
        frozenset({"manifest", "operationResult"}) | CI_PROVENANCE_ARTIFACT,
        DATABASE_COUNTERS,
        frozenset({"operationMode"}),
        STAGING_DIGESTS,
        result_kind="demo-catalog-database-operation",
    ),
    (DATABASE_WORKFLOW, "rollback"): Profile(
        "G5",
        "B6.09",
        frozenset({"manifest", "operationResult"}) | CI_PROVENANCE_ARTIFACT,
        DATABASE_COUNTERS,
        frozenset({"operationMode"}),
        STAGING_DIGESTS,
        result_kind="demo-catalog-database-operation",
    ),
    (ANDROID_WORKFLOW, "build-aab"): Profile(
        "G6",
        "B7.02",
        frozenset({"aab", "checksums", "mapping", "provenance"})
        | CI_PROVENANCE_ARTIFACT,
        frozenset({"versionCode"}),
        frozenset({"applicationId", "variant", "versionName"}),
        frozenset(
            {
                "firebaseProjectIdSha256",
                "stagingProjectRefSha256",
                "uploadCertificateSha256",
            }
        ),
    ),
    (ANDROID_WORKFLOW, "publish-play-internal"): Profile(
        "G6",
        "B7.04",
        frozenset({"aab", "buildEvidence", "checksums", "mapping", "provenance"})
        | CI_PROVENANCE_ARTIFACT,
        frozenset({"versionCode"}),
        frozenset(
            {
                "gateDecision",
                "packageName",
                "publicationOutcome",
                "requestedStatus",
                "sourceArtifactName",
                "track",
                "versionName",
            }
        ),
        frozenset({"sourceArtifactSha256", "uploadCertificateSha256"}),
    ),
}

TOP_LEVEL_FIELDS = {
    "actor",
    "artifacts",
    "counters",
    "createdAtUtc",
    "details",
    "digests",
    "environment",
    "expectedSha",
    "gate",
    "gateDecision",
    "operation",
    "repository",
    "runAttempt",
    "runId",
    "runUrl",
    "schemaVersion",
    "status",
    "taskId",
    "validatedCiRunId",
    "validatedCiProvenance",
    "validatedCiRunUrl",
    "workflow",
}
FROZEN_CI_PROVENANCE_FIELDS = {
    "actor",
    "conclusion",
    "event",
    "headBranch",
    "headSha",
    "kind",
    "repository",
    "runAttempt",
    "runClassification",
    "runId",
    "runUrl",
    "schemaVersion",
    "status",
    "triggeringActor",
    "workflow",
}


def github_run_url(run_id: str) -> str:
    require(POSITIVE_INTEGER_PATTERN.fullmatch(run_id) is not None, "Invalid GitHub run ID")
    return f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}"


def _utc_text(value: datetime | None = None) -> str:
    timestamp = value or datetime.now(timezone.utc)
    require(timestamp.tzinfo is not None, "Evidence timestamp must be timezone-aware")
    return timestamp.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _load_json(path: Path) -> dict[str, Any]:
    try:
        require(path.is_file() and not path.is_symlink(), f"JSON evidence must be a regular file: {path.name}")
        require(path.stat().st_size <= 1024 * 1024, f"JSON evidence is unexpectedly large: {path.name}")
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GelError(f"Unable to read JSON evidence: {path.name}") from error
    require(isinstance(value, dict), f"JSON evidence is not an object: {path.name}")
    return value


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    require(not path.is_symlink(), "Evidence output must not be a symbolic link")
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _assert_safe_document(value: Any, location: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            require(isinstance(key, str), f"Non-string evidence key at {location}")
            normalized_key = re.sub(r"[^a-z0-9]", "", key.lower())
            require(
                not any(fragment in normalized_key for fragment in SENSITIVE_KEY_FRAGMENTS),
                f"Sensitive evidence field refused at {location}.{key}",
            )
            _assert_safe_document(child, f"{location}.{key}")
        return
    if isinstance(value, list):
        for index, child in enumerate(value):
            _assert_safe_document(child, f"{location}[{index}]")
        return
    if isinstance(value, str):
        require(len(value) <= 4096, f"Oversized evidence value refused at {location}")
        require("\x00" not in value, f"Binary-looking evidence value refused at {location}")
        for pattern in SUSPICIOUS_VALUE_PATTERNS:
            require(pattern.search(value) is None, f"Suspicious evidence value refused at {location}")
        return
    require(
        value is None or isinstance(value, (bool, int, float)),
        f"Unsupported evidence value at {location}",
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _artifact(directory: Path, filename: str) -> dict[str, Any]:
    require(FILENAME_PATTERN.fullmatch(filename) is not None, f"Unsafe artifact filename: {filename}")
    path = directory / filename
    require(path.parent.resolve() == directory.resolve(), "Artifact must be an immediate receipt file")
    require(path.is_file() and not path.is_symlink(), f"Artifact is missing or unsafe: {filename}")
    require(path.stat().st_size > 0, f"Artifact is empty: {filename}")
    return {"filename": filename, "sha256": _sha256(path), "sizeBytes": path.stat().st_size}


def _parse_assignments(values: Iterable[str], label: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        key, separator, item = value.partition("=")
        require(separator == "=" and item != "", f"{label} must use name=value")
        require(LABEL_PATTERN.fullmatch(key) is not None, f"Invalid {label} name: {key}")
        require(key not in result, f"Duplicate {label} name: {key}")
        result[key] = item
    return result


def _integer(value: Any, label: str) -> int:
    require(type(value) is int and value >= 0, f"{label} must be a non-negative integer")
    return value


def _validate_storage_result(document: dict[str, Any], operation: str) -> tuple[dict[str, int], dict[str, str]]:
    require(set(document) == {"counts", "kind", "mode", "operation", "outcome", "schemaVersion"}, "Storage result fields are invalid")
    require(document.get("schemaVersion") == 1, "Unsupported Storage result schema")
    require(document.get("kind") == "demo-storage-operation", "Wrong Storage result kind")
    require(document.get("operation") == operation, "Storage result operation mismatch")
    require(document.get("outcome") == "succeeded", "Storage result is not successful")
    counts = document.get("counts")
    require(isinstance(counts, dict) and set(counts) == STORAGE_COUNTERS, "Storage result counters are invalid")
    normalized = {key: _integer(value, key) for key, value in counts.items()}
    require(normalized["manifestObjects"] == 180, "Storage result does not cover 180 objects")
    mode = document.get("mode")
    if operation == "publish":
        require(mode == "published-and-verified", "Unexpected Storage publish mode")
        require(
            normalized == {
                "alreadyAbsentObjects": 0,
                "createdObjects": 180,
                "deletedObjects": 0,
                "manifestObjects": 180,
                "verifiedObjects": 180,
            },
            "Storage publish counters are incomplete",
        )
    elif operation == "verify":
        require(mode == "verified", "Unexpected Storage verify mode")
        require(
            normalized == {
                "alreadyAbsentObjects": 0,
                "createdObjects": 0,
                "deletedObjects": 0,
                "manifestObjects": 180,
                "verifiedObjects": 180,
            },
            "Storage verify counters are incomplete",
        )
    else:
        require(mode == "rolled-back-exact-manifest", "Unexpected Storage rollback mode")
        require(normalized["createdObjects"] == 0, "Storage rollback created objects")
        require(
            normalized["deletedObjects"] + normalized["alreadyAbsentObjects"] == 180,
            "Storage rollback does not reconcile the exact manifest",
        )
        require(
            normalized["verifiedObjects"] == normalized["deletedObjects"],
            "Storage rollback deleted unverified objects",
        )
    return normalized, {"operationMode": str(mode)}


def _database_state(
    counts: dict[str, int],
    prefix: str,
) -> tuple[int, int, int, int, int]:
    return (
        counts[f"{prefix}TargetListings"],
        counts[f"{prefix}TaggedListings"],
        counts[f"{prefix}PublishedListings"],
        counts[f"{prefix}ArchivedListings"],
        counts[f"{prefix}Media"],
    )


def _validate_database_result(
    document: dict[str, Any],
    operation: str,
    *,
    allow_absent_rollback: bool = False,
) -> tuple[dict[str, int], dict[str, str]]:
    require(set(document) == {"counts", "kind", "mode", "operation", "outcome", "schemaVersion"}, "Database result fields are invalid")
    require(document.get("schemaVersion") == 1, "Unsupported database result schema")
    require(document.get("kind") == "demo-catalog-database-operation", "Wrong database result kind")
    require(document.get("operation") == operation, "Database result operation mismatch")
    require(document.get("outcome") == "succeeded", "Database result is not successful")
    counts = document.get("counts")
    require(isinstance(counts, dict) and set(counts) == DATABASE_COUNTERS, "Database result counters are invalid")
    normalized = {key: _integer(value, key) for key, value in counts.items()}
    before = _database_state(normalized, "before")
    after = _database_state(normalized, "after")
    absent = (0, 0, 0, 0, 0)
    published = (60, 60, 60, 0, 180)
    archived = (60, 60, 0, 60, 180)
    mode = document.get("mode")
    if operation == "publish":
        require(mode == "published-and-verified", "Unexpected database publish mode")
        require(before in {absent, archived, published} and after == published, "Database publish state is invalid")
    elif operation == "verify":
        require(mode == "verified" and before == published and after == published, "Database verify state is invalid")
    else:
        require(mode in {"already-absent", "already-archived", "archived-exact-catalog"}, "Unexpected database rollback mode")
        require(
            mode != "already-absent" or allow_absent_rollback,
            "Absent database rollback evidence is reserved for coordinated Storage cleanup",
        )
        expected_pair = {
            "already-absent": (absent, absent),
            "already-archived": (archived, archived),
            "archived-exact-catalog": (published, archived),
        }[str(mode)]
        require((before, after) == expected_pair, "Database rollback state is invalid")
    return normalized, {"operationMode": str(mode)}


def _validate_result(
    document: dict[str, Any],
    operation: str,
    expected_kind: str,
    *,
    allow_absent_rollback: bool = False,
) -> tuple[dict[str, int], dict[str, str]]:
    _assert_safe_document(document)
    if expected_kind == "demo-storage-operation":
        return _validate_storage_result(document, operation)
    require(expected_kind == "demo-catalog-database-operation", "Unsupported operation result kind")
    return _validate_database_result(
        document,
        operation,
        allow_absent_rollback=allow_absent_rollback,
    )


def _validate_android_values(
    operation: str,
    counters: dict[str, int],
    details: dict[str, str],
) -> None:
    require(1 <= counters["versionCode"] <= 2_100_000_000, "Android versionCode is invalid")
    require(VERSION_PATTERN.fullmatch(details["versionName"]) is not None, "Android versionName is invalid")
    if operation == "build-aab":
        require(details["applicationId"] == "com.kwabor.android", "Android application ID mismatch")
        require(details["variant"] == "staging", "Android build is not staging")
    else:
        require(
            details["gateDecision"] == "not-closed-by-receipt",
            "Play receipt must not close G6",
        )
        require(details["packageName"] == "com.kwabor.android", "Play package name mismatch")
        require(details["track"] == "internal", "Play publication is not internal")
        require(details["requestedStatus"] == "completed", "Play status is not the protected value")
        require(details["publicationOutcome"] == "upload-action-succeeded", "Play upload did not succeed")
        require(FILENAME_PATTERN.fullmatch(details["sourceArtifactName"]) is not None, "Play source artifact name is unsafe")


def _profile(workflow: str, operation: str) -> Profile:
    profile = PROFILES.get((workflow, operation))
    require(profile is not None, "Unsupported closed-beta workflow operation")
    return profile


def build_receipt(
    *,
    directory: Path,
    workflow: str,
    operation: str,
    expected_sha: str,
    validated_ci_run_id: str,
    repository: str,
    actor: str,
    run_id: str,
    run_attempt: str,
    run_url: str,
    artifacts: dict[str, str],
    digests: dict[str, str],
    counters: dict[str, str],
    details: dict[str, str],
    ci_provenance_filename: str,
    result_filename: str | None = None,
    related_result_filename: str | None = None,
    created_at: datetime | None = None,
) -> dict[str, Any]:
    profile = _profile(workflow, operation)
    require(SHA_PATTERN.fullmatch(expected_sha) is not None, "Invalid expected_sha")
    require(POSITIVE_INTEGER_PATTERN.fullmatch(validated_ci_run_id) is not None, "Invalid validated CI run ID")
    require(repository == EXPECTED_REPOSITORY, "Unexpected repository")
    require(ACTOR_PATTERN.fullmatch(actor) is not None, "Invalid workflow actor")
    require(POSITIVE_INTEGER_PATTERN.fullmatch(run_id) is not None, "Invalid workflow run ID")
    require(POSITIVE_INTEGER_PATTERN.fullmatch(run_attempt) is not None, "Invalid workflow run attempt")
    require(run_url == github_run_url(run_id), "Workflow run URL mismatch")
    require(directory.is_dir() and not directory.is_symlink(), "Receipt directory is missing or unsafe")

    ci_provenance = _load_json(directory / ci_provenance_filename)
    _validate_frozen_ci_provenance(
        ci_provenance,
        expected_run_id=validated_ci_run_id,
        expected_sha=expected_sha,
    )
    require("ciProvenance" not in artifacts, "CI provenance artifact is duplicated")
    artifacts["ciProvenance"] = ci_provenance_filename

    normalized_counters: dict[str, int] = {}
    normalized_details = dict(details)
    for key, value in counters.items():
        require(re.fullmatch(r"0|[1-9][0-9]*", value) is not None, f"Invalid counter: {key}")
        normalized_counters[key] = int(value)

    if profile.result_kind is not None:
        require(result_filename is not None, "Operation result is required")
        result_path = directory / result_filename
        result_counters, result_details = _validate_result(
            _load_json(result_path), operation, profile.result_kind
        )
        require(not (set(normalized_counters) & set(result_counters)), "Duplicate result counters")
        require(not (set(normalized_details) & set(result_details)), "Duplicate result details")
        normalized_counters.update(result_counters)
        normalized_details.update(result_details)
        artifacts["operationResult"] = result_filename
    else:
        require(result_filename is None, "This workflow operation does not accept a result document")

    if profile.related_result_kind is not None:
        require(related_result_filename is not None, "Related database result is required")
        related_path = directory / related_result_filename
        related_counters, related_details = _validate_result(
            _load_json(related_path),
            "rollback",
            profile.related_result_kind,
            allow_absent_rollback=True,
        )
        normalized_counters.update(
            {
                f"database{key[0].upper()}{key[1:]}": value
                for key, value in related_counters.items()
            }
        )
        normalized_details["databaseOperationMode"] = related_details["operationMode"]
        artifacts["databaseOperationResult"] = related_result_filename
    else:
        require(related_result_filename is None, "Unexpected related operation result")

    require(set(artifacts) == profile.artifacts, "Artifact set does not match the GEL profile")
    require(set(normalized_counters) == profile.counters, "Counter set does not match the GEL profile")
    require(set(normalized_details) == profile.details, "Detail set does not match the GEL profile")
    require(set(digests) == profile.digests, "Digest set does not match the GEL profile")
    normalized_digests: dict[str, str] = {}
    for key, value in digests.items():
        require(SHA256_PATTERN.fullmatch(value) is not None, f"Invalid SHA-256 digest: {key}")
        normalized_digests[key] = value
    if workflow == ANDROID_WORKFLOW:
        _validate_android_values(operation, normalized_counters, normalized_details)

    artifact_metadata = {
        label: _artifact(directory, filename)
        for label, filename in sorted(artifacts.items())
    }
    filenames = [metadata["filename"] for metadata in artifact_metadata.values()]
    require(len(filenames) == len(set(filenames)), "GEL artifacts must use unique filenames")
    receipt = {
        "actor": actor,
        "artifacts": artifact_metadata,
        "counters": dict(sorted(normalized_counters.items())),
        "createdAtUtc": _utc_text(created_at),
        "details": dict(sorted(normalized_details.items())),
        "digests": dict(sorted(normalized_digests.items())),
        "environment": EXPECTED_ENVIRONMENT,
        "expectedSha": expected_sha,
        "gate": profile.gate,
        "gateDecision": "not-closed-by-receipt",
        "operation": operation,
        "repository": repository,
        "runAttempt": int(run_attempt),
        "runId": int(run_id),
        "runUrl": run_url,
        "schemaVersion": 1,
        "status": "succeeded",
        "taskId": profile.task_id,
        "validatedCiRunId": int(validated_ci_run_id),
        "validatedCiProvenance": ci_provenance,
        "validatedCiRunUrl": github_run_url(validated_ci_run_id),
        "workflow": workflow,
    }
    _assert_safe_document(receipt)
    return receipt


def verify_receipt(
    receipt: dict[str, Any],
    *,
    directory: Path,
    workflow: str,
    operation: str,
    expected_sha: str,
    validated_ci_run_id: str,
    run_id: str,
    run_attempt: str,
) -> None:
    require(SHA_PATTERN.fullmatch(expected_sha) is not None, "Invalid expected_sha")
    require(
        POSITIVE_INTEGER_PATTERN.fullmatch(validated_ci_run_id) is not None,
        "Invalid validated CI run ID",
    )
    require(POSITIVE_INTEGER_PATTERN.fullmatch(run_id) is not None, "Invalid workflow run ID")
    require(
        POSITIVE_INTEGER_PATTERN.fullmatch(run_attempt) is not None,
        "Invalid workflow run attempt",
    )
    _assert_safe_document(receipt)
    require(set(receipt) == TOP_LEVEL_FIELDS, "GEL receipt fields are invalid")
    profile = _profile(workflow, operation)
    expected_scalars = {
        "environment": EXPECTED_ENVIRONMENT,
        "expectedSha": expected_sha,
        "gate": profile.gate,
        "gateDecision": "not-closed-by-receipt",
        "operation": operation,
        "repository": EXPECTED_REPOSITORY,
        "runAttempt": int(run_attempt),
        "runId": int(run_id),
        "runUrl": github_run_url(run_id),
        "schemaVersion": 1,
        "status": "succeeded",
        "taskId": profile.task_id,
        "validatedCiRunId": int(validated_ci_run_id),
        "validatedCiRunUrl": github_run_url(validated_ci_run_id),
        "workflow": workflow,
    }
    for key, value in expected_scalars.items():
        require(receipt.get(key) == value, f"GEL receipt mismatch: {key}")
    require(ACTOR_PATTERN.fullmatch(str(receipt.get("actor", ""))) is not None, "Invalid GEL actor")
    created_at_text = str(receipt.get("createdAtUtc", ""))
    require(created_at_text.endswith("Z"), "GEL timestamp is not canonical UTC")
    try:
        created_at = datetime.fromisoformat(created_at_text.replace("Z", "+00:00"))
    except ValueError as error:
        raise GelError("Invalid GEL timestamp") from error
    require(
        created_at.tzinfo is not None and created_at.utcoffset().total_seconds() == 0,
        "GEL timestamp is not UTC-aware",
    )

    artifacts = receipt.get("artifacts")
    counters = receipt.get("counters")
    details = receipt.get("details")
    digests = receipt.get("digests")
    require(isinstance(artifacts, dict) and set(artifacts) == profile.artifacts, "GEL artifact set mismatch")
    require(isinstance(counters, dict) and set(counters) == profile.counters, "GEL counter set mismatch")
    require(isinstance(details, dict) and set(details) == profile.details, "GEL detail set mismatch")
    require(isinstance(digests, dict) and set(digests) == profile.digests, "GEL digest set mismatch")
    for key, value in counters.items():
        _integer(value, key)
    for key, value in digests.items():
        require(isinstance(value, str) and SHA256_PATTERN.fullmatch(value) is not None, f"Invalid GEL digest: {key}")
    if workflow == ANDROID_WORKFLOW:
        require(all(isinstance(value, str) for value in details.values()), "Android GEL details are invalid")
        _validate_android_values(operation, counters, details)
    artifact_filenames: list[str] = []
    for label, metadata in artifacts.items():
        require(isinstance(metadata, dict) and set(metadata) == {"filename", "sha256", "sizeBytes"}, f"Invalid GEL artifact metadata: {label}")
        filename = metadata.get("filename")
        require(isinstance(filename, str), f"Invalid GEL artifact filename: {label}")
        artifact_filenames.append(filename)
        require(
            isinstance(metadata.get("sha256"), str)
            and SHA256_PATTERN.fullmatch(metadata["sha256"]) is not None,
            f"Invalid GEL artifact digest: {label}",
        )
        require(
            type(metadata.get("sizeBytes")) is int and metadata["sizeBytes"] > 0,
            f"Invalid GEL artifact size: {label}",
        )
        require(_artifact(directory, filename) == metadata, f"GEL artifact digest or size mismatch: {label}")
    require(
        len(artifact_filenames) == len(set(artifact_filenames)),
        "GEL artifacts must use unique filenames",
    )
    ci_provenance_artifact = artifacts["ciProvenance"]
    frozen_ci_provenance = _load_json(
        directory / ci_provenance_artifact["filename"]
    )
    _validate_frozen_ci_provenance(
        frozen_ci_provenance,
        expected_run_id=validated_ci_run_id,
        expected_sha=expected_sha,
    )
    require(
        receipt.get("validatedCiProvenance") == frozen_ci_provenance,
        "GEL frozen CI provenance mismatch",
    )
    if profile.result_kind is not None:
        operation_result = artifacts["operationResult"]
        result_counters, result_details = _validate_result(
            _load_json(directory / operation_result["filename"]),
            operation,
            profile.result_kind,
        )
        for key, value in result_counters.items():
            require(counters.get(key) == value, f"GEL operation result counter mismatch: {key}")
        for key, value in result_details.items():
            require(details.get(key) == value, f"GEL operation result detail mismatch: {key}")
    if profile.related_result_kind is not None:
        related_result = artifacts["databaseOperationResult"]
        related_counters, related_details = _validate_result(
            _load_json(directory / related_result["filename"]),
            "rollback",
            profile.related_result_kind,
            allow_absent_rollback=True,
        )
        for key, value in related_counters.items():
            receipt_key = f"database{key[0].upper()}{key[1:]}"
            require(counters.get(receipt_key) == value, f"GEL related result counter mismatch: {receipt_key}")
        require(
            details.get("databaseOperationMode") == related_details["operationMode"],
            "GEL related result detail mismatch",
        )


def validate_github_run(
    document: dict[str, Any],
    *,
    expected_run_id: str,
    expected_sha: str,
    expected_workflow_path: str,
    allowed_events: Iterable[str],
) -> dict[str, Any]:
    require(POSITIVE_INTEGER_PATTERN.fullmatch(expected_run_id) is not None, "Invalid GitHub run ID")
    require(SHA_PATTERN.fullmatch(expected_sha) is not None, "Invalid expected_sha")
    repository = document.get("repository")
    path = str(document.get("path", "")).split("@", maxsplit=1)[0]
    run_attempt = document.get("run_attempt")
    actor = document.get("actor")
    triggering_actor = document.get("triggering_actor")
    actor_login = actor.get("login") if isinstance(actor, dict) else None
    triggering_actor_login = (
        triggering_actor.get("login")
        if isinstance(triggering_actor, dict)
        else None
    )
    require(document.get("id") == int(expected_run_id), "GitHub run ID mismatch")
    require(isinstance(repository, dict) and repository.get("full_name") == EXPECTED_REPOSITORY, "Run belongs to another repository")
    require(document.get("head_sha") == expected_sha, "Run belongs to another commit")
    require(document.get("head_branch") == "main", "Run did not execute on main")
    require(document.get("status") == "completed" and document.get("conclusion") == "success", "Run is not successfully completed")
    require(path == expected_workflow_path, "Unexpected source workflow")
    require(document.get("event") in set(allowed_events), "Unexpected source workflow event")
    require(type(run_attempt) is int and run_attempt > 0, "GitHub run attempt is invalid")
    require(
        isinstance(actor_login, str)
        and ACTOR_PATTERN.fullmatch(actor_login) is not None,
        "GitHub run actor is invalid",
    )
    require(
        triggering_actor_login is None
        or (
            isinstance(triggering_actor_login, str)
            and ACTOR_PATTERN.fullmatch(triggering_actor_login) is not None
        ),
        "GitHub triggering actor is invalid",
    )
    require(
        document.get("html_url") == github_run_url(expected_run_id),
        "GitHub run URL mismatch",
    )
    provenance = {
        "actor": actor_login,
        "conclusion": "success",
        "event": document["event"],
        "headBranch": "main",
        "headSha": expected_sha,
        "kind": "github-actions-run-provenance",
        "repository": EXPECTED_REPOSITORY,
        "runAttempt": run_attempt,
        "runClassification": "initial" if run_attempt == 1 else "rerun",
        "runId": int(expected_run_id),
        "runUrl": github_run_url(expected_run_id),
        "schemaVersion": 1,
        "status": "completed",
        "triggeringActor": triggering_actor_login,
        "workflow": expected_workflow_path,
    }
    _assert_safe_document(provenance)
    return provenance


def _validate_frozen_ci_provenance(
    document: dict[str, Any],
    *,
    expected_run_id: str,
    expected_sha: str,
) -> None:
    _assert_safe_document(document)
    require(
        set(document) == FROZEN_CI_PROVENANCE_FIELDS,
        "Frozen CI provenance fields are invalid",
    )
    run_attempt = document.get("runAttempt")
    require(type(run_attempt) is int and run_attempt > 0, "Frozen CI run attempt is invalid")
    expected = {
        "conclusion": "success",
        "event": "push",
        "headBranch": "main",
        "headSha": expected_sha,
        "kind": "github-actions-run-provenance",
        "repository": EXPECTED_REPOSITORY,
        "runClassification": "initial" if run_attempt == 1 else "rerun",
        "runId": int(expected_run_id),
        "runUrl": github_run_url(expected_run_id),
        "schemaVersion": 1,
        "status": "completed",
        "workflow": ".github/workflows/ci.yml",
    }
    for key, value in expected.items():
        require(document.get(key) == value, f"Frozen CI provenance mismatch: {key}")
    require(
        isinstance(document.get("actor"), str)
        and ACTOR_PATTERN.fullmatch(document["actor"]) is not None,
        "Frozen CI actor is invalid",
    )
    triggering_actor = document.get("triggeringActor")
    require(
        triggering_actor is None
        or (
            isinstance(triggering_actor, str)
            and ACTOR_PATTERN.fullmatch(triggering_actor) is not None
        ),
        "Frozen CI triggering actor is invalid",
    )


def _write_command(args: argparse.Namespace) -> None:
    directory = Path(args.directory)
    receipt = build_receipt(
        directory=directory,
        workflow=args.workflow,
        operation=args.operation,
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
        repository=args.repository,
        actor=args.actor,
        run_id=args.run_id,
        run_attempt=args.run_attempt,
        run_url=args.run_url,
        artifacts=_parse_assignments(args.artifact, "artifact"),
        digests=_parse_assignments(args.digest, "digest"),
        counters=_parse_assignments(args.counter, "counter"),
        details=_parse_assignments(args.detail, "detail"),
        ci_provenance_filename=args.ci_provenance,
        result_filename=args.result,
        related_result_filename=args.related_result,
    )
    output = Path(args.output)
    require(output.parent.resolve() == directory.resolve(), "GEL receipt must be written inside its artifact directory")
    _write_json(output, receipt)


def _verify_command(args: argparse.Namespace) -> None:
    directory = Path(args.directory)
    evidence = Path(args.evidence)
    require(
        evidence.parent.resolve() == directory.resolve(),
        "GEL receipt must be inside its artifact directory",
    )
    verify_receipt(
        _load_json(evidence),
        directory=directory,
        workflow=args.workflow,
        operation=args.operation,
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
        run_id=args.run_id,
        run_attempt=args.run_attempt,
    )


def _github_run_command(args: argparse.Namespace) -> None:
    provenance = validate_github_run(
        _load_json(Path(args.document)),
        expected_run_id=args.run_id,
        expected_sha=args.expected_sha,
        expected_workflow_path=args.workflow_path,
        allowed_events=args.allowed_event,
    )
    if args.output is not None:
        _write_json(Path(args.output), provenance)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    write = subparsers.add_parser("write")
    write.add_argument("--directory", required=True)
    write.add_argument("--workflow", required=True)
    write.add_argument("--operation", required=True)
    write.add_argument("--expected-sha", required=True)
    write.add_argument("--validated-ci-run-id", required=True)
    write.add_argument("--repository", required=True)
    write.add_argument("--actor", required=True)
    write.add_argument("--run-id", required=True)
    write.add_argument("--run-attempt", required=True)
    write.add_argument("--run-url", required=True)
    write.add_argument("--ci-provenance", required=True)
    write.add_argument("--result")
    write.add_argument("--related-result")
    write.add_argument("--artifact", action="append", default=[])
    write.add_argument("--digest", action="append", default=[])
    write.add_argument("--counter", action="append", default=[])
    write.add_argument("--detail", action="append", default=[])
    write.add_argument("--output", required=True)
    write.set_defaults(handler=_write_command)

    verify = subparsers.add_parser("verify")
    verify.add_argument("--directory", required=True)
    verify.add_argument("--evidence", required=True)
    verify.add_argument("--workflow", required=True)
    verify.add_argument("--operation", required=True)
    verify.add_argument("--expected-sha", required=True)
    verify.add_argument("--validated-ci-run-id", required=True)
    verify.add_argument("--run-id", required=True)
    verify.add_argument("--run-attempt", required=True)
    verify.set_defaults(handler=_verify_command)

    github_run = subparsers.add_parser("validate-github-run")
    github_run.add_argument("--document", required=True)
    github_run.add_argument("--run-id", required=True)
    github_run.add_argument("--expected-sha", required=True)
    github_run.add_argument("--workflow-path", required=True)
    github_run.add_argument("--allowed-event", action="append", required=True)
    github_run.add_argument("--output")
    github_run.set_defaults(handler=_github_run_command)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        args.handler(args)
    except GelError as error:
        print(f"Closed-beta GEL refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
