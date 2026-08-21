#!/usr/bin/env python3
"""Validate the closed-beta pilot evidence without contacting external systems.

The validator is deliberately fail-closed.  It accepts only a small, versioned
JSON contract, derives every release gate from raw counters, and emits a
deterministic aggregate receipt.  Participant identities, free-form feedback,
provider credentials, and device identifiers do not belong in this contract.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = 1
MINIMUM_PARTICIPANTS_ANDROID = 10
MINIMUM_PARTICIPANTS_IOS = 5
TOTAL_PARTICIPANTS = 15
CANARY_PARTICIPANTS = 3
CANARY_MINIMUM_SECONDS = 2 * 60 * 60
COHORT_DAYS = 7
MINIMUM_OBSERVED_SESSIONS = 200
CRASH_FREE_PER_MILLE = 995
PERFORMANCE_SAMPLES = 30
COLD_SAMPLES = 10
WARM_SAMPLES = 20
P75_LIMIT_MILLISECONDS = 1_500
MAX_EVIDENCE_URI_LENGTH = 512
EXPECTED_PARTICIPANT_IDS = frozenset(
    [f"T-A{index:02d}" for index in range(1, 11)]
    + [f"T-I{index:02d}" for index in range(1, 6)]
)
EXPECTED_DEVICE_IDS = frozenset(identifier.replace("T-", "D-", 1) for identifier in EXPECTED_PARTICIPANT_IDS)

SHA1_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
PARTICIPANT_PATTERN = re.compile(r"^T-(?:A|I)[0-9]{2}$")
DEVICE_PATTERN = re.compile(r"^D-(?:A|I)[0-9]{2}$")
RC_PATTERN = re.compile(r"^RC-[A-Z0-9][A-Z0-9._-]{0,31}$")
RUN_PATTERN = re.compile(r"^RUN-[A-Z0-9][A-Z0-9._-]{0,31}$")
BUILD_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]{0,23})?$")
MODEL_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
INCIDENT_PATTERN = re.compile(r"^INC-[0-9]{3}$")
EVIDENCE_URN_PREFIX = "urn:kwabor:evidence:ev-"
EVIDENCE_URN_PATTERN = re.compile(r"^urn:kwabor:evidence:ev-[0-9a-f]{32}$")
UTC_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
EMAIL_PATTERN = re.compile(r"(?i)(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PHONE_PATTERN = re.compile(r"(?<![A-Za-z0-9])\+?[0-9][0-9 .()/-]{7,}[0-9](?![A-Za-z0-9])")
IPV4_PATTERN = re.compile(r"(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])")
SECRET_PATTERNS = (
    re.compile(r"(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?i)\b(?:bearer|service_role)\s+[A-Za-z0-9._~+/=-]{8,}"),
    re.compile(r"\bghp_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{20,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
    re.compile(r"(?i)\b(?:sk|sb_secret)_[A-Za-z0-9_-]{16,}\b"),
)
FORBIDDEN_UNKNOWN_KEYS = {
    "address",
    "comment",
    "comments",
    "credential",
    "credentials",
    "email",
    "gps",
    "imei",
    "ip",
    "latitude",
    "longitude",
    "name",
    "phone",
    "query",
    "raw_text",
    "secret",
    "serial",
    "token",
    "udid",
    "url",
}
@dataclass(frozen=True, order=True)
class Finding:
    code: str
    path: str
    message: str

    def as_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message, "path": self.path}


class Findings:
    def __init__(self) -> None:
        self._values: set[Finding] = set()

    def add(self, code: str, path: str, message: str) -> None:
        self._values.add(Finding(code=code, path=path, message=message))

    def __bool__(self) -> bool:
        return bool(self._values)

    def sorted(self) -> list[Finding]:
        return sorted(self._values)


class DuplicateJsonKeyError(ValueError):
    """Raised without retaining the duplicated key, which may itself be sensitive."""


def _strict_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKeyError("Duplicate JSON object key.")
        result[key] = value
    return result


def _reject_nonfinite_json_constant(_: str) -> None:
    raise ValueError("Non-finite JSON number.")


def _canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _canonical_pretty_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"


def _object(
    value: Any,
    path: str,
    required: Iterable[str],
    findings: Findings,
    optional: Iterable[str] = (),
) -> dict[str, Any] | None:
    if type(value) is not dict:
        findings.add("TYPE_OBJECT_REQUIRED", path, "Expected an object.")
        return None
    required_set = set(required)
    allowed = required_set | set(optional)
    for key in sorted(set(value) - allowed):
        unknown_path = f"{path}.[unknown]"
        findings.add("UNKNOWN_FIELD", unknown_path, "Unknown fields are forbidden.")
        if key.lower() in FORBIDDEN_UNKNOWN_KEYS:
            findings.add("PII_OR_SECRET_FIELD", unknown_path, "PII and secret fields are forbidden.")
    for key in sorted(required_set - set(value)):
        findings.add("REQUIRED_FIELD_MISSING", f"{path}.{key}", "Required field is missing.")
    return value


def _array(value: Any, path: str, findings: Findings) -> list[Any] | None:
    if type(value) is not list:
        findings.add("TYPE_ARRAY_REQUIRED", path, "Expected an array.")
        return None
    return value


def _string(
    value: Any,
    path: str,
    findings: Findings,
    *,
    pattern: re.Pattern[str] | None = None,
    maximum: int = 128,
) -> str | None:
    if type(value) is not str:
        findings.add("TYPE_STRING_REQUIRED", path, "Expected a string.")
        return None
    if not value or len(value) > maximum:
        findings.add("STRING_BOUNDS", path, "String length is outside the accepted bounds.")
        return None
    if pattern is not None and pattern.fullmatch(value) is None:
        findings.add("STRING_FORMAT", path, "String does not match the closed contract.")
    return value


def _boolean(value: Any, path: str, findings: Findings) -> bool | None:
    if type(value) is not bool:
        findings.add("TYPE_BOOLEAN_REQUIRED", path, "Expected a boolean.")
        return None
    return value


def _integer(
    value: Any,
    path: str,
    findings: Findings,
    *,
    minimum: int = 0,
    maximum: int = 10_000_000,
) -> int | None:
    if type(value) is not int:
        findings.add("TYPE_INTEGER_REQUIRED", path, "Expected an integer.")
        return None
    if value < minimum or value > maximum:
        findings.add("INTEGER_BOUNDS", path, "Integer is outside the accepted bounds.")
    return value


def _enum(value: Any, path: str, allowed: set[str], findings: Findings) -> str | None:
    parsed = _string(value, path, findings, maximum=64)
    if parsed is not None and parsed not in allowed:
        findings.add("ENUM_VALUE", path, "Value is not part of the closed enum.")
    return parsed


def _timestamp(value: Any, path: str, findings: Findings) -> str | None:
    return _string(value, path, findings, pattern=UTC_PATTERN, maximum=20)


def _parse_timestamp(value: str, path: str, findings: Findings) -> datetime | None:
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        findings.add("TIMESTAMP_INVALID", path, "Timestamp must be a real UTC second in YYYY-MM-DDTHH:MM:SSZ form.")
        return None
    return parsed


def _validate_safe_uri(uri: str, path: str, findings: Findings) -> bool:
    if EVIDENCE_URN_PATTERN.fullmatch(uri) is not None:
        return True
    if len(uri) > MAX_EVIDENCE_URI_LENGTH or any(ord(character) < 0x21 or ord(character) > 0x7E for character in uri):
        findings.add("EVIDENCE_URI_BOUNDS", path, "Evidence URI must be bounded printable ASCII.")
        return False
    if "?" in uri or "#" in uri:
        findings.add("EVIDENCE_URI_CREDENTIAL", path, "Credentials, query strings, and fragments are forbidden in evidence URIs.")
        return False
    if "%" in uri:
        findings.add("EVIDENCE_URI_ENCODING", path, "Percent-encoded evidence identifiers are forbidden.")
        return False
    if (
        "\\" in uri
        or EMAIL_PATTERN.search(uri)
        or PHONE_PATTERN.search(uri)
        or IPV4_PATTERN.search(uri)
        or any(pattern.search(uri) for pattern in SECRET_PATTERNS)
    ):
        findings.add("EVIDENCE_URI_SENSITIVE", path, "Evidence URI contains sensitive or ambiguous material.")
        return False
    if not uri.startswith("urn:kwabor:evidence:"):
        findings.add("EVIDENCE_URI_SCHEME", path, "Only Kwabor evidence URNs are accepted.")
        return False
    findings.add("EVIDENCE_URI_OPAQUE", path, "Evidence URNs require one opaque ev- identifier.")
    return False


def _proof(value: Any, path: str, findings: Findings) -> None:
    evidence = _object(value, path, {"sha256", "uri"}, findings)
    if evidence is None:
        return
    digest = _string(evidence.get("sha256"), f"{path}.sha256", findings, pattern=SHA256_PATTERN, maximum=64)
    uri = _string(evidence.get("uri"), f"{path}.uri", findings, maximum=MAX_EVIDENCE_URI_LENGTH)
    uri_is_valid = uri is not None and _validate_safe_uri(uri, f"{path}.uri", findings)
    digest_is_valid = digest is not None and SHA256_PATTERN.fullmatch(digest) is not None
    if uri_is_valid and digest_is_valid and uri != f"{EVIDENCE_URN_PREFIX}{digest[:32]}":
        findings.add(
            "EVIDENCE_URI_SHA_LINK",
            path,
            "Evidence URN suffix must equal the first 32 hexadecimal characters of its SHA-256.",
        )


def _proof_records(value: Any) -> Iterable[tuple[str, str]]:
    if type(value) is dict:
        if set(value) == {"sha256", "uri"} and type(value["sha256"]) is str and type(value["uri"]) is str:
            yield value["sha256"], value["uri"]
            return
        for nested in value.values():
            yield from _proof_records(nested)
        return
    if type(value) is list:
        for nested in value:
            yield from _proof_records(nested)


def _validate_global_proof_bindings(document: Any, findings: Findings) -> None:
    uri_to_sha: dict[str, str] = {}
    sha_to_uri: dict[str, str] = {}
    for digest, uri in _proof_records(document):
        if uri in uri_to_sha:
            findings.add("EVIDENCE_URI_REUSED", "$.[proof]", "Every evidence URN must be globally unique.")
            if uri_to_sha[uri] != digest:
                findings.add(
                    "EVIDENCE_URI_SHA_CONFLICT",
                    "$.[proof]",
                    "One evidence URN cannot identify two SHA-256 values.",
                )
        if digest in sha_to_uri:
            findings.add("EVIDENCE_SHA256_REUSED", "$.[proof]", "Every evidence SHA-256 must be globally unique.")
            if sha_to_uri[digest] != uri:
                findings.add(
                    "EVIDENCE_SHA256_URI_CONFLICT",
                    "$.[proof]",
                    "One evidence SHA-256 cannot identify two URNs.",
                )
        uri_to_sha.setdefault(uri, digest)
        sha_to_uri.setdefault(digest, uri)


def _scan_sensitive_values(
    value: Any,
    path: str,
    findings: Findings,
    field_name: str | None = None,
) -> None:
    if type(value) is dict:
        for key, nested in value.items():
            nested_path = f"{path}.[field]"
            if key.lower() in FORBIDDEN_UNKNOWN_KEYS:
                findings.add("PII_OR_SECRET_FIELD", nested_path, "PII and secret fields are forbidden.")
            _scan_sensitive_values(nested, nested_path, findings, key)
        return
    if type(value) is list:
        for index, nested in enumerate(value):
            _scan_sensitive_values(nested, f"{path}[{index}]", findings, field_name)
        return
    if type(value) is not str:
        return
    if (
        field_name in {"expected_sha", "sha256"}
        or (field_name == "uri" and EVIDENCE_URN_PATTERN.fullmatch(value) is not None)
        or UTC_PATTERN.fullmatch(value)
    ):
        return
    if EMAIL_PATTERN.search(value):
        findings.add("PII_EMAIL", path, "Email-like data is forbidden.")
    if PHONE_PATTERN.search(value):
        findings.add("PII_PHONE", path, "Phone-like data is forbidden.")
    if IPV4_PATTERN.search(value):
        findings.add("PII_IP", path, "IP-like data is forbidden.")
    if any(pattern.search(value) for pattern in SECRET_PATTERNS):
        findings.add("SECRET_VALUE", path, "Secret-like data is forbidden.")


def _validate_release(value: Any, path: str, findings: Findings) -> None:
    release = _object(
        value,
        path,
        {"build_id", "environment", "expected_sha", "proofs", "rc_id", "version_name"},
        findings,
    )
    if release is None:
        return
    _string(release.get("expected_sha"), f"{path}.expected_sha", findings, pattern=SHA1_PATTERN, maximum=40)
    _string(release.get("version_name"), f"{path}.version_name", findings, pattern=VERSION_PATTERN, maximum=32)
    _string(release.get("build_id"), f"{path}.build_id", findings, pattern=BUILD_PATTERN, maximum=32)
    _string(release.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _enum(release.get("environment"), f"{path}.environment", {"staging"}, findings)
    proofs = _object(
        release.get("proofs"),
        f"{path}.proofs",
        {"android_distribution", "ci", "ios_distribution"},
        findings,
    )
    if proofs is not None:
        for key in ("android_distribution", "ci", "ios_distribution"):
            _proof(proofs.get(key), f"{path}.proofs.{key}", findings)


def _validate_participant(value: Any, path: str, findings: Findings) -> None:
    participant = _object(value, path, {"consent", "device_id", "id", "platform", "proof", "rc_id"}, findings)
    if participant is None:
        return
    _string(participant.get("id"), f"{path}.id", findings, pattern=PARTICIPANT_PATTERN, maximum=5)
    _enum(participant.get("platform"), f"{path}.platform", {"android", "ios"}, findings)
    _string(participant.get("device_id"), f"{path}.device_id", findings, pattern=DEVICE_PATTERN, maximum=5)
    _string(participant.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    consent = _object(participant.get("consent"), f"{path}.consent", {"analytics", "diagnostics"}, findings)
    if consent is not None:
        _boolean(consent.get("analytics"), f"{path}.consent.analytics", findings)
        _boolean(consent.get("diagnostics"), f"{path}.consent.diagnostics", findings)
    _proof(participant.get("proof"), f"{path}.proof", findings)


def _validate_device(value: Any, path: str, findings: Findings) -> None:
    device = _object(
        value,
        path,
        {"id", "model_code", "os_version", "participant_id", "physical", "platform", "proof", "rc_id"},
        findings,
    )
    if device is None:
        return
    _string(device.get("id"), f"{path}.id", findings, pattern=DEVICE_PATTERN, maximum=5)
    _enum(device.get("platform"), f"{path}.platform", {"android", "ios"}, findings)
    _string(device.get("participant_id"), f"{path}.participant_id", findings, pattern=PARTICIPANT_PATTERN, maximum=5)
    _string(device.get("model_code"), f"{path}.model_code", findings, pattern=MODEL_PATTERN, maximum=64)
    _string(device.get("os_version"), f"{path}.os_version", findings, pattern=MODEL_PATTERN, maximum=64)
    _boolean(device.get("physical"), f"{path}.physical", findings)
    _string(device.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _proof(device.get("proof"), f"{path}.proof", findings)


def _validate_canary(value: Any, path: str, findings: Findings) -> None:
    canary = _object(value, path, {"ended_at_utc", "participant_ids", "proof", "rc_id", "started_at_utc"}, findings)
    if canary is None:
        return
    identifiers = _array(canary.get("participant_ids"), f"{path}.participant_ids", findings)
    if identifiers is not None:
        for index, identifier in enumerate(identifiers):
            _string(identifier, f"{path}.participant_ids[{index}]", findings, pattern=PARTICIPANT_PATTERN, maximum=5)
    _string(canary.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _timestamp(canary.get("started_at_utc"), f"{path}.started_at_utc", findings)
    _timestamp(canary.get("ended_at_utc"), f"{path}.ended_at_utc", findings)
    _proof(canary.get("proof"), f"{path}.proof", findings)


def _validate_previous_run(value: Any, path: str, findings: Findings) -> None:
    previous_run = _object(
        value,
        path,
        {"expected_sha", "generation", "proof", "rc_id", "reason", "run_id"},
        findings,
    )
    if previous_run is None:
        return
    _string(previous_run.get("run_id"), f"{path}.run_id", findings, pattern=RUN_PATTERN, maximum=36)
    _integer(previous_run.get("generation"), f"{path}.generation", findings, maximum=99)
    _string(previous_run.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _string(previous_run.get("expected_sha"), f"{path}.expected_sha", findings, pattern=SHA1_PATTERN, maximum=40)
    _enum(previous_run.get("reason"), f"{path}.reason", {"P0", "P1"}, findings)
    _proof(previous_run.get("proof"), f"{path}.proof", findings)


def _validate_reset(value: Any, path: str, findings: Findings) -> None:
    reset = _object(
        value,
        path,
        {
            "active_run_id",
            "carryover_sessions",
            "confirmed_no_carryover",
            "generation",
            "j1_started_at_utc",
            "previous_runs",
            "proof",
        },
        findings,
    )
    if reset is None:
        return
    _integer(reset.get("generation"), f"{path}.generation", findings, maximum=100)
    _string(reset.get("active_run_id"), f"{path}.active_run_id", findings, pattern=RUN_PATTERN, maximum=36)
    previous_runs = _array(reset.get("previous_runs"), f"{path}.previous_runs", findings)
    if previous_runs is not None:
        for index, previous_run in enumerate(previous_runs):
            _validate_previous_run(previous_run, f"{path}.previous_runs[{index}]", findings)
    _timestamp(reset.get("j1_started_at_utc"), f"{path}.j1_started_at_utc", findings)
    _integer(reset.get("carryover_sessions"), f"{path}.carryover_sessions", findings)
    _boolean(reset.get("confirmed_no_carryover"), f"{path}.confirmed_no_carryover", findings)
    _proof(reset.get("proof"), f"{path}.proof", findings)


def _validate_day(value: Any, path: str, findings: Findings) -> None:
    day = _object(
        value,
        path,
        {
            "day",
            "ended_at_utc",
            "participant_ids",
            "proof",
            "rc_id",
            "run_id",
            "sessions_observed",
            "sessions_with_crash",
            "started_at_utc",
        },
        findings,
    )
    if day is None:
        return
    _integer(day.get("day"), f"{path}.day", findings, minimum=1, maximum=COHORT_DAYS)
    _string(day.get("run_id"), f"{path}.run_id", findings, pattern=RUN_PATTERN, maximum=36)
    _string(day.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _timestamp(day.get("started_at_utc"), f"{path}.started_at_utc", findings)
    _timestamp(day.get("ended_at_utc"), f"{path}.ended_at_utc", findings)
    identifiers = _array(day.get("participant_ids"), f"{path}.participant_ids", findings)
    if identifiers is not None:
        for index, identifier in enumerate(identifiers):
            _string(identifier, f"{path}.participant_ids[{index}]", findings, pattern=PARTICIPANT_PATTERN, maximum=5)
    _integer(day.get("sessions_observed"), f"{path}.sessions_observed", findings)
    _integer(day.get("sessions_with_crash"), f"{path}.sessions_with_crash", findings)
    _proof(day.get("proof"), f"{path}.proof", findings)


def _validate_incident(value: Any, path: str, findings: Findings) -> None:
    incident = _object(
        value,
        path,
        {"detected_at_utc", "id", "proof", "rc_id", "run_id", "severity", "status"},
        findings,
        optional={"resolved_at_utc"},
    )
    if incident is None:
        return
    _string(incident.get("id"), f"{path}.id", findings, pattern=INCIDENT_PATTERN, maximum=7)
    _string(incident.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _string(incident.get("run_id"), f"{path}.run_id", findings, pattern=RUN_PATTERN, maximum=36)
    _enum(incident.get("severity"), f"{path}.severity", {"P0", "P1", "P2", "P3"}, findings)
    _enum(incident.get("status"), f"{path}.status", {"closed", "open"}, findings)
    _timestamp(incident.get("detected_at_utc"), f"{path}.detected_at_utc", findings)
    if "resolved_at_utc" in incident:
        _timestamp(incident.get("resolved_at_utc"), f"{path}.resolved_at_utc", findings)
    _proof(incident.get("proof"), f"{path}.proof", findings)


def _validate_catalog(value: Any, path: str, findings: Findings) -> None:
    catalog = _object(
        value,
        path,
        {
            "broken_media",
            "duplicate_listings",
            "fictitious_cta_violations",
            "listings",
            "media",
            "missing_listings",
            "proof",
            "rc_id",
        },
        findings,
    )
    if catalog is None:
        return
    _string(catalog.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    for key in ("listings", "media", "missing_listings", "duplicate_listings", "broken_media", "fictitious_cta_violations"):
        _integer(catalog.get(key), f"{path}.{key}", findings, maximum=10_000)
    _proof(catalog.get("proof"), f"{path}.proof", findings)


def _validate_critical_checks(value: Any, path: str, findings: Findings) -> None:
    checks = _object(value, path, {"account_deletion", "consent_revocation"}, findings)
    if checks is None:
        return
    deletion = _object(checks.get("account_deletion"), f"{path}.account_deletion", {"passed", "proof", "rc_id"}, findings)
    if deletion is not None:
        _string(deletion.get("rc_id"), f"{path}.account_deletion.rc_id", findings, pattern=RC_PATTERN, maximum=35)
        _boolean(deletion.get("passed"), f"{path}.account_deletion.passed", findings)
        _proof(deletion.get("proof"), f"{path}.account_deletion.proof", findings)
    revocation = _object(
        checks.get("consent_revocation"),
        f"{path}.consent_revocation",
        {"passed", "proof", "rc_id", "zero_events_after_revocation"},
        findings,
    )
    if revocation is not None:
        _string(revocation.get("rc_id"), f"{path}.consent_revocation.rc_id", findings, pattern=RC_PATTERN, maximum=35)
        _boolean(revocation.get("passed"), f"{path}.consent_revocation.passed", findings)
        _boolean(
            revocation.get("zero_events_after_revocation"),
            f"{path}.consent_revocation.zero_events_after_revocation",
            findings,
        )
        _proof(revocation.get("proof"), f"{path}.consent_revocation.proof", findings)


def _validate_accessibility(value: Any, path: str, findings: Findings) -> None:
    entry = _object(
        value,
        path,
        {
            "announcements",
            "assistive_technology",
            "contrast_aa",
            "device_id",
            "focus_order",
            "labels",
            "physical",
            "platform",
            "proof",
            "rc_id",
            "touch_targets",
        },
        findings,
    )
    if entry is None:
        return
    _enum(entry.get("platform"), f"{path}.platform", {"android", "ios"}, findings)
    _string(entry.get("device_id"), f"{path}.device_id", findings, pattern=DEVICE_PATTERN, maximum=5)
    _string(entry.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _boolean(entry.get("physical"), f"{path}.physical", findings)
    _enum(entry.get("assistive_technology"), f"{path}.assistive_technology", {"talkback", "voiceover"}, findings)
    for key in ("focus_order", "labels", "announcements", "touch_targets", "contrast_aa"):
        _boolean(entry.get(key), f"{path}.{key}", findings)
    _proof(entry.get("proof"), f"{path}.proof", findings)


def _validate_performance(value: Any, path: str, findings: Findings) -> None:
    entry = _object(
        value,
        path,
        {
            "clock",
            "device_id",
            "network",
            "outliers_removed",
            "p75_limit_ms",
            "p75_method",
            "physical",
            "platform",
            "proof",
            "rc_id",
            "samples",
        },
        findings,
    )
    if entry is None:
        return
    _enum(entry.get("platform"), f"{path}.platform", {"android", "ios"}, findings)
    _string(entry.get("device_id"), f"{path}.device_id", findings, pattern=DEVICE_PATTERN, maximum=5)
    _string(entry.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _boolean(entry.get("physical"), f"{path}.physical", findings)
    _enum(entry.get("clock"), f"{path}.clock", {"monotonic"}, findings)
    _enum(entry.get("p75_method"), f"{path}.p75_method", {"nearest-rank"}, findings)
    _integer(entry.get("p75_limit_ms"), f"{path}.p75_limit_ms", findings, minimum=1, maximum=60_000)
    _boolean(entry.get("outliers_removed"), f"{path}.outliers_removed", findings)
    network = _object(entry.get("network"), f"{path}.network", {"down_kbps", "rtt_ms", "up_kbps"}, findings)
    if network is not None:
        _integer(network.get("down_kbps"), f"{path}.network.down_kbps", findings, minimum=1, maximum=1_000_000)
        _integer(network.get("up_kbps"), f"{path}.network.up_kbps", findings, minimum=1, maximum=1_000_000)
        _integer(network.get("rtt_ms"), f"{path}.network.rtt_ms", findings, minimum=0, maximum=60_000)
    samples = _array(entry.get("samples"), f"{path}.samples", findings)
    if samples is not None:
        for index, sample_value in enumerate(samples):
            sample_path = f"{path}.samples[{index}]"
            sample = _object(sample_value, sample_path, {"duration_ms", "index", "mode"}, findings)
            if sample is None:
                continue
            _integer(sample.get("index"), f"{sample_path}.index", findings, minimum=1, maximum=PERFORMANCE_SAMPLES)
            _enum(sample.get("mode"), f"{sample_path}.mode", {"cold", "warm"}, findings)
            _integer(sample.get("duration_ms"), f"{sample_path}.duration_ms", findings, minimum=1, maximum=60_000)
    _proof(entry.get("proof"), f"{path}.proof", findings)


def _validate_decision(value: Any, path: str, findings: Findings) -> None:
    decision = _object(
        value,
        path,
        {"all_signed", "declared", "proof", "rc_id", "review_roles", "run_id"},
        findings,
    )
    if decision is None:
        return
    _enum(decision.get("declared"), f"{path}.declared", {"go", "go-with-corrections", "no-go"}, findings)
    _boolean(decision.get("all_signed"), f"{path}.all_signed", findings)
    _string(decision.get("rc_id"), f"{path}.rc_id", findings, pattern=RC_PATTERN, maximum=35)
    _string(decision.get("run_id"), f"{path}.run_id", findings, pattern=RUN_PATTERN, maximum=36)
    roles = _array(decision.get("review_roles"), f"{path}.review_roles", findings)
    if roles is not None:
        for index, role in enumerate(roles):
            _enum(
                role,
                f"{path}.review_roles[{index}]",
                {"content", "product", "security-privacy", "technical"},
                findings,
            )
    _proof(decision.get("proof"), f"{path}.proof", findings)


def _validate_structure(document: Any, findings: Findings) -> bool:
    root = _object(
        document,
        "$",
        {
            "accessibility",
            "canary",
            "catalog",
            "cohort_days",
            "critical_checks",
            "decision",
            "devices",
            "evaluated_at_utc",
            "fictitious",
            "incidents",
            "participants",
            "performance",
            "release_candidate",
            "run_reset",
            "schema_version",
        },
        findings,
    )
    if root is None:
        return False
    _integer(root.get("schema_version"), "$.schema_version", findings, minimum=SCHEMA_VERSION, maximum=SCHEMA_VERSION)
    _boolean(root.get("fictitious"), "$.fictitious", findings)
    _timestamp(root.get("evaluated_at_utc"), "$.evaluated_at_utc", findings)
    _validate_release(root.get("release_candidate"), "$.release_candidate", findings)

    participants = _array(root.get("participants"), "$.participants", findings)
    if participants is not None:
        for index, participant in enumerate(participants):
            _validate_participant(participant, f"$.participants[{index}]", findings)

    devices = _array(root.get("devices"), "$.devices", findings)
    if devices is not None:
        for index, device in enumerate(devices):
            _validate_device(device, f"$.devices[{index}]", findings)

    _validate_canary(root.get("canary"), "$.canary", findings)
    _validate_reset(root.get("run_reset"), "$.run_reset", findings)

    days = _array(root.get("cohort_days"), "$.cohort_days", findings)
    if days is not None:
        for index, day in enumerate(days):
            _validate_day(day, f"$.cohort_days[{index}]", findings)

    incidents = _array(root.get("incidents"), "$.incidents", findings)
    if incidents is not None:
        for index, incident in enumerate(incidents):
            _validate_incident(incident, f"$.incidents[{index}]", findings)

    _validate_catalog(root.get("catalog"), "$.catalog", findings)
    _validate_critical_checks(root.get("critical_checks"), "$.critical_checks", findings)

    accessibility = _array(root.get("accessibility"), "$.accessibility", findings)
    if accessibility is not None:
        for index, entry in enumerate(accessibility):
            _validate_accessibility(entry, f"$.accessibility[{index}]", findings)

    performance = _array(root.get("performance"), "$.performance", findings)
    if performance is not None:
        for index, entry in enumerate(performance):
            _validate_performance(entry, f"$.performance[{index}]", findings)

    _validate_decision(root.get("decision"), "$.decision", findings)
    _validate_global_proof_bindings(document, findings)
    _scan_sensitive_values(document, "$", findings)
    return not findings


def _expect_equal(actual: Any, expected: Any, code: str, path: str, message: str, findings: Findings) -> None:
    if actual != expected:
        findings.add(code, path, message)


def _participant_platform(identifier: str) -> str:
    return "android" if identifier.startswith("T-A") else "ios"


def _device_platform(identifier: str) -> str:
    return "android" if identifier.startswith("D-A") else "ios"


def _nearest_rank_p75(samples: list[int]) -> int | None:
    if not samples:
        return None
    rank = math.ceil(0.75 * len(samples))
    return sorted(samples)[rank - 1]


def _validate_semantics(document: dict[str, Any], findings: Findings) -> dict[str, Any]:
    release = document["release_candidate"]
    rc_id = release["rc_id"]
    participants: list[dict[str, Any]] = document["participants"]
    devices: list[dict[str, Any]] = document["devices"]
    days: list[dict[str, Any]] = document["cohort_days"]
    incidents: list[dict[str, Any]] = document["incidents"]

    if document["schema_version"] != SCHEMA_VERSION:
        findings.add("SCHEMA_VERSION", "$.schema_version", "Only schema version 1 is accepted.")
    if document["fictitious"]:
        findings.add("FICTITIOUS_EVIDENCE", "$.fictitious", "Fictitious evidence can never authorize GO.")

    _expect_equal(len(participants), TOTAL_PARTICIPANTS, "PARTICIPANT_COUNT", "$.participants", "Exactly 15 participants are required.", findings)
    participant_ids = [participant["id"] for participant in participants]
    participant_device_ids = [participant["device_id"] for participant in participants]
    if len(set(participant_ids)) != len(participant_ids):
        findings.add("PARTICIPANT_DUPLICATE", "$.participants", "Participant pseudonyms must be unique.")
    if set(participant_ids) != EXPECTED_PARTICIPANT_IDS:
        findings.add(
            "PARTICIPANT_ID_SET",
            "$.participants",
            "Participant pseudonyms must be exactly T-A01 through T-A10 and T-I01 through T-I05.",
        )
    if len(set(participant_device_ids)) != len(participant_device_ids):
        findings.add("PARTICIPANT_DEVICE_DUPLICATE", "$.participants", "Each participant must use one unique device.")
    platform_counts = {
        platform: sum(participant["platform"] == platform for participant in participants)
        for platform in ("android", "ios")
    }
    _expect_equal(
        platform_counts["android"],
        MINIMUM_PARTICIPANTS_ANDROID,
        "ANDROID_PARTICIPANT_COUNT",
        "$.participants",
        "Exactly 10 Android participants are required.",
        findings,
    )
    _expect_equal(
        platform_counts["ios"],
        MINIMUM_PARTICIPANTS_IOS,
        "IOS_PARTICIPANT_COUNT",
        "$.participants",
        "Exactly 5 iOS participants are required.",
        findings,
    )
    for index, participant in enumerate(participants):
        if participant["platform"] != _participant_platform(participant["id"]):
            findings.add("PARTICIPANT_PLATFORM", f"$.participants[{index}]", "Participant pseudonym and platform disagree.")
        if participant["platform"] != _device_platform(participant["device_id"]):
            findings.add("PARTICIPANT_DEVICE_PLATFORM", f"$.participants[{index}]", "Participant and device platforms disagree.")
        if participant["rc_id"] != rc_id:
            findings.add("MIXED_RELEASE_CANDIDATE", f"$.participants[{index}].rc_id", "Every participant must use the same RC.")
        if not participant["consent"]["analytics"] or not participant["consent"]["diagnostics"]:
            findings.add(
                "PARTICIPANT_CONSENT",
                f"$.participants[{index}].consent",
                "Only sessions from participants consenting to analytics and diagnostics are eligible.",
            )

    _expect_equal(len(devices), TOTAL_PARTICIPANTS, "DEVICE_COUNT", "$.devices", "Exactly 15 physical devices are required.", findings)
    device_ids = [device["id"] for device in devices]
    device_participant_ids = [device["participant_id"] for device in devices]
    if len(set(device_ids)) != len(device_ids):
        findings.add("DEVICE_DUPLICATE", "$.devices", "Device pseudonyms must be unique.")
    if set(device_ids) != EXPECTED_DEVICE_IDS:
        findings.add(
            "DEVICE_ID_SET",
            "$.devices",
            "Device pseudonyms must be exactly D-A01 through D-A10 and D-I01 through D-I05.",
        )
    if len(set(device_participant_ids)) != len(device_participant_ids):
        findings.add("DEVICE_PARTICIPANT_DUPLICATE", "$.devices", "Each device must map to one participant.")
    if set(device_ids) != set(participant_device_ids) or set(device_participant_ids) != set(participant_ids):
        findings.add("DEVICE_PARTICIPANT_BIJECTION", "$.devices", "Participant and device mappings must be a bijection.")
    participant_by_id = {participant["id"]: participant for participant in participants}
    device_by_id = {device["id"]: device for device in devices}
    for index, device in enumerate(devices):
        participant = participant_by_id.get(device["participant_id"])
        if participant is not None and (
            participant["device_id"] != device["id"] or participant["platform"] != device["platform"]
        ):
            findings.add("DEVICE_MAPPING", f"$.devices[{index}]", "Device mapping does not match its participant.")
        if device["platform"] != _device_platform(device["id"]):
            findings.add("DEVICE_PLATFORM", f"$.devices[{index}]", "Device pseudonym and platform disagree.")
        if not device["physical"]:
            findings.add("DEVICE_NOT_PHYSICAL", f"$.devices[{index}].physical", "Simulator and emulator evidence is forbidden.")
        if device["rc_id"] != rc_id:
            findings.add("MIXED_RELEASE_CANDIDATE", f"$.devices[{index}].rc_id", "Every device must use the same RC.")

    if len(days) != COHORT_DAYS:
        findings.add("COHORT_DAY_COUNT", "$.cohort_days", "Exactly seven complete cohort days are required.")
    day_numbers = [day["day"] for day in days]
    if day_numbers != list(range(1, COHORT_DAYS + 1)):
        findings.add("COHORT_DAY_SEQUENCE", "$.cohort_days", "Cohort days must be ordered J1 through J7 without gaps.")
    all_participant_ids = set(participant_ids)
    parsed_days: list[tuple[datetime | None, datetime | None]] = []
    reset = document["run_reset"]
    active_run_id = reset["active_run_id"]
    for index, day in enumerate(days):
        day_path = f"$.cohort_days[{index}]"
        start = _parse_timestamp(day["started_at_utc"], f"{day_path}.started_at_utc", findings)
        end = _parse_timestamp(day["ended_at_utc"], f"{day_path}.ended_at_utc", findings)
        parsed_days.append((start, end))
        if start is not None and (start.hour, start.minute, start.second) != (0, 0, 0):
            findings.add("COHORT_DAY_UTC_BOUNDARY", f"{day_path}.started_at_utc", "Each cohort day must start at UTC midnight.")
        if start is not None and end is not None and end - start != timedelta(days=1):
            findings.add("COHORT_DAY_DURATION", day_path, "Each cohort day must span exactly 24 UTC hours.")
        if index > 0 and parsed_days[index - 1][1] is not None and start != parsed_days[index - 1][1]:
            findings.add("COHORT_DAY_CONSECUTIVE", day_path, "Cohort days must be consecutive without overlap or gap.")
        if len(day["participant_ids"]) != TOTAL_PARTICIPANTS or set(day["participant_ids"]) != all_participant_ids:
            findings.add("COHORT_DAY_PARTICIPANTS", f"{day_path}.participant_ids", "Every day must cover the same 15 participants.")
        if len(set(day["participant_ids"])) != len(day["participant_ids"]):
            findings.add("COHORT_DAY_PARTICIPANT_DUPLICATE", f"{day_path}.participant_ids", "A participant may occur only once per day.")
        if day["sessions_with_crash"] > day["sessions_observed"]:
            findings.add("CRASH_SESSION_BOUNDS", day_path, "Crashed sessions cannot exceed observed sessions.")
        if day["rc_id"] != rc_id:
            findings.add("MIXED_RELEASE_CANDIDATE", f"{day_path}.rc_id", "Every cohort day must use the same RC.")
        if day["run_id"] != active_run_id:
            findings.add("MIXED_COHORT_RUN", f"{day_path}.run_id", "Every cohort day must belong to the active reset run.")

    generation = reset["generation"]
    previous_runs = reset["previous_runs"]
    prior_run_ids = [previous_run["run_id"] for previous_run in previous_runs]
    if generation != len(previous_runs):
        findings.add("RESET_GENERATION", "$.run_reset", "Reset generation must equal the number of prior runs.")
    if [previous_run["generation"] for previous_run in previous_runs] != list(range(len(previous_runs))):
        findings.add(
            "RESET_RUN_ORDER",
            "$.run_reset.previous_runs",
            "Prior runs must be ordered oldest to newest with consecutive generations starting at zero.",
        )
    if len(set(prior_run_ids)) != len(prior_run_ids) or active_run_id in prior_run_ids:
        findings.add("RESET_RUN_IDS", "$.run_reset.previous_runs", "Prior run IDs must be unique and exclude the active run.")
    release_chain = previous_runs + [{"expected_sha": release["expected_sha"], "rc_id": rc_id}]
    release_candidate_ids = [entry["rc_id"] for entry in release_chain]
    expected_shas = [entry["expected_sha"] for entry in release_chain]
    release_pairs = list(zip(release_candidate_ids, expected_shas, strict=True))
    if len(set(release_candidate_ids)) != len(release_candidate_ids):
        findings.add(
            "RESET_RELEASE_CANDIDATE_REUSED",
            "$.run_reset.previous_runs",
            "A release candidate ID cannot be recycled anywhere in the active or historical chain.",
        )
    if len(set(expected_shas)) != len(expected_shas):
        findings.add(
            "RESET_EXPECTED_SHA_REUSED",
            "$.run_reset.previous_runs",
            "An expected SHA cannot be recycled anywhere in the active or historical chain.",
        )
    if len(set(release_pairs)) != len(release_pairs):
        findings.add(
            "RESET_RELEASE_PAIR_REUSED",
            "$.run_reset.previous_runs",
            "A release candidate and expected SHA pair cannot be recycled.",
        )
    for index, previous_run in enumerate(previous_runs):
        successor = release_chain[index + 1]
        if (
            previous_run["rc_id"] == successor["rc_id"]
            or previous_run["expected_sha"] == successor["expected_sha"]
        ):
            findings.add(
                "RESET_SUCCESSOR_RELEASE_NOT_NEW",
                f"$.run_reset.previous_runs[{index}]",
                "Every P0/P1 run requires its immediate successor to use both a new RC and a new SHA.",
            )
    if reset["carryover_sessions"] != 0 or not reset["confirmed_no_carryover"]:
        findings.add("RESET_CARRYOVER", "$.run_reset", "A restarted cohort must carry zero sessions into the active run.")
    if parsed_days and parsed_days[0][0] is not None:
        reset_j1 = _parse_timestamp(reset["j1_started_at_utc"], "$.run_reset.j1_started_at_utc", findings)
        if reset_j1 != parsed_days[0][0]:
            findings.add("RESET_J1_MISMATCH", "$.run_reset.j1_started_at_utc", "Reset marker must identify the active J1 exactly.")

    canary = document["canary"]
    canary_ids = canary["participant_ids"]
    if len(canary_ids) != CANARY_PARTICIPANTS or len(set(canary_ids)) != CANARY_PARTICIPANTS:
        findings.add("CANARY_PARTICIPANTS", "$.canary.participant_ids", "Canary requires exactly three unique participants.")
    if not set(canary_ids).issubset(all_participant_ids):
        findings.add("CANARY_UNKNOWN_PARTICIPANT", "$.canary.participant_ids", "Canary participants must belong to the cohort.")
    if canary["rc_id"] != rc_id:
        findings.add("MIXED_RELEASE_CANDIDATE", "$.canary.rc_id", "Canary and cohort must use the same RC.")
    canary_start = _parse_timestamp(canary["started_at_utc"], "$.canary.started_at_utc", findings)
    canary_end = _parse_timestamp(canary["ended_at_utc"], "$.canary.ended_at_utc", findings)
    if canary_start is not None and canary_end is not None:
        if (canary_end - canary_start).total_seconds() < CANARY_MINIMUM_SECONDS:
            findings.add("CANARY_DURATION", "$.canary", "Canary must last at least two hours.")
        if parsed_days and parsed_days[0][0] is not None and canary_end > parsed_days[0][0]:
            findings.add("CANARY_COHORT_OVERLAP", "$.canary", "Canary must end before or exactly when J1 starts.")

    evaluated_at = _parse_timestamp(document["evaluated_at_utc"], "$.evaluated_at_utc", findings)
    if evaluated_at is not None and parsed_days and parsed_days[-1][1] is not None and evaluated_at < parsed_days[-1][1]:
        findings.add("EVALUATION_TOO_EARLY", "$.evaluated_at_utc", "Evaluation must happen after J7 completes.")

    for index, incident in enumerate(incidents):
        incident_path = f"$.incidents[{index}]"
        if incident["rc_id"] != rc_id:
            findings.add("MIXED_RELEASE_CANDIDATE", f"{incident_path}.rc_id", "Every incident must use the same RC.")
        if incident["run_id"] != active_run_id:
            findings.add("MIXED_COHORT_RUN", f"{incident_path}.run_id", "Every incident must belong to the active run.")
        if incident["severity"] in {"P0", "P1"}:
            findings.add("BLOCKING_INCIDENT", f"{incident_path}.severity", "Any P0/P1 in the active run forces NO-GO and a reset.")
        detected_at = _parse_timestamp(
            incident["detected_at_utc"],
            f"{incident_path}.detected_at_utc",
            findings,
        )
        resolved_at = (
            _parse_timestamp(
                incident["resolved_at_utc"],
                f"{incident_path}.resolved_at_utc",
                findings,
            )
            if "resolved_at_utc" in incident
            else None
        )
        if incident["status"] == "closed" and "resolved_at_utc" not in incident:
            findings.add("INCIDENT_RESOLUTION_MISSING", incident_path, "Closed incidents require a resolution timestamp.")
        if incident["status"] == "open" and "resolved_at_utc" in incident:
            findings.add("INCIDENT_OPEN_WITH_RESOLUTION", incident_path, "Open incidents cannot carry a resolution timestamp.")
        if detected_at is not None and resolved_at is not None and resolved_at < detected_at:
            findings.add(
                "INCIDENT_RESOLUTION_ORDER",
                f"{incident_path}.resolved_at_utc",
                "Incident resolution must not precede detection.",
            )

    sessions_observed = sum(day["sessions_observed"] for day in days)
    sessions_with_crash = sum(day["sessions_with_crash"] for day in days)
    if sessions_observed < MINIMUM_OBSERVED_SESSIONS:
        findings.add("SESSION_MINIMUM", "$.cohort_days", "At least 200 consented observed sessions are required.")
    if sessions_observed == 0 or (sessions_observed - sessions_with_crash) * 1_000 < sessions_observed * CRASH_FREE_PER_MILLE:
        findings.add("CRASH_FREE_THRESHOLD", "$.cohort_days", "Crash-free sessions must be at least 99.5 percent.")

    catalog = document["catalog"]
    if catalog["rc_id"] != rc_id:
        findings.add("MIXED_RELEASE_CANDIDATE", "$.catalog.rc_id", "Catalog proof must target the same RC.")
    expected_catalog = {
        "broken_media": 0,
        "duplicate_listings": 0,
        "fictitious_cta_violations": 0,
        "listings": 60,
        "media": 180,
        "missing_listings": 0,
    }
    for key, expected in expected_catalog.items():
        if catalog[key] != expected:
            findings.add("CATALOG_GATE", f"$.catalog.{key}", "Catalog must remain exactly 60/180 with zero integrity violation.")

    critical_checks = document["critical_checks"]
    deletion = critical_checks["account_deletion"]
    revocation = critical_checks["consent_revocation"]
    if deletion["rc_id"] != rc_id or revocation["rc_id"] != rc_id:
        findings.add("MIXED_RELEASE_CANDIDATE", "$.critical_checks", "Critical checks must target the same RC.")
    if not deletion["passed"]:
        findings.add("ACCOUNT_DELETION_GATE", "$.critical_checks.account_deletion.passed", "Real account deletion must pass.")
    if not revocation["passed"] or not revocation["zero_events_after_revocation"]:
        findings.add("CONSENT_REVOCATION_GATE", "$.critical_checks.consent_revocation", "Consent revocation and zero post-revocation events must pass.")

    accessibility_entries: list[dict[str, Any]] = document["accessibility"]
    if len(accessibility_entries) != 2 or {entry["platform"] for entry in accessibility_entries} != {"android", "ios"}:
        findings.add("ACCESSIBILITY_PLATFORM_SET", "$.accessibility", "One Android TalkBack and one iOS VoiceOver proof are required.")
    accessibility_summary: dict[str, bool] = {}
    for index, entry in enumerate(accessibility_entries):
        path = f"$.accessibility[{index}]"
        device = device_by_id.get(entry["device_id"])
        if device is None or device["platform"] != entry["platform"] or not device["physical"]:
            findings.add("ACCESSIBILITY_DEVICE", f"{path}.device_id", "Accessibility proof must reference a physical cohort device on the same platform.")
        expected_technology = "talkback" if entry["platform"] == "android" else "voiceover"
        if entry["assistive_technology"] != expected_technology:
            findings.add("ACCESSIBILITY_TECHNOLOGY", f"{path}.assistive_technology", "Assistive technology does not match the platform.")
        if entry["rc_id"] != rc_id or not entry["physical"]:
            findings.add("ACCESSIBILITY_RELEASE", path, "Accessibility proof must use the same RC on a physical device.")
        checks_pass = all(entry[key] for key in ("focus_order", "labels", "announcements", "touch_targets", "contrast_aa"))
        if not checks_pass:
            findings.add("ACCESSIBILITY_GATE", path, "All physical accessibility checks must pass.")
        accessibility_summary[entry["platform"]] = checks_pass

    performance_entries: list[dict[str, Any]] = document["performance"]
    if len(performance_entries) != 2 or {entry["platform"] for entry in performance_entries} != {"android", "ios"}:
        findings.add("PERFORMANCE_PLATFORM_SET", "$.performance", "One Android and one iOS performance series are required.")
    performance_summary: dict[str, dict[str, int | None]] = {}
    for index, entry in enumerate(performance_entries):
        path = f"$.performance[{index}]"
        platform = entry["platform"]
        device = device_by_id.get(entry["device_id"])
        if device is None or device["platform"] != platform or not device["physical"]:
            findings.add("PERFORMANCE_DEVICE", f"{path}.device_id", "Performance proof must reference a physical cohort device on the same platform.")
        if entry["rc_id"] != rc_id or not entry["physical"]:
            findings.add("PERFORMANCE_RELEASE", path, "Performance proof must use the same RC on a physical device.")
        if entry["clock"] != "monotonic":
            findings.add("PERFORMANCE_CLOCK", f"{path}.clock", "Only an instrumented monotonic clock is accepted.")
        if entry["p75_method"] != "nearest-rank" or entry["p75_limit_ms"] != P75_LIMIT_MILLISECONDS:
            findings.add("PERFORMANCE_P75_CONTRACT", path, "P75 must use nearest-rank and the fixed 1500 ms limit.")
        if entry["outliers_removed"]:
            findings.add("PERFORMANCE_OUTLIER", f"{path}.outliers_removed", "No sample or outlier may be removed.")
        if entry["network"] != {"down_kbps": 1_600, "rtt_ms": 150, "up_kbps": 750}:
            findings.add("PERFORMANCE_NETWORK", f"{path}.network", "Performance network profile must be 1600/750 kbps with 150 ms RTT.")
        samples = entry["samples"]
        if len(samples) != PERFORMANCE_SAMPLES:
            findings.add("PERFORMANCE_SAMPLE_COUNT", f"{path}.samples", "Exactly 30 raw performance samples are required.")
        indices = [sample["index"] for sample in samples]
        modes = [sample["mode"] for sample in samples]
        if indices != list(range(1, PERFORMANCE_SAMPLES + 1)):
            findings.add("PERFORMANCE_SAMPLE_ORDER", f"{path}.samples", "Performance sample indices must be exactly 1 through 30.")
        if modes != ["cold"] * COLD_SAMPLES + ["warm"] * WARM_SAMPLES:
            findings.add("PERFORMANCE_MODE_ORDER", f"{path}.samples", "Samples must contain 10 cold runs followed by 20 warm runs.")
        durations = [sample["duration_ms"] for sample in samples]
        p75 = _nearest_rank_p75(durations)
        if p75 is None or p75 >= P75_LIMIT_MILLISECONDS:
            findings.add("PERFORMANCE_P75_THRESHOLD", path, "Nearest-rank P75 must be strictly below 1500 ms.")
        performance_summary[platform] = {
            "cold_samples": sum(mode == "cold" for mode in modes),
            "p75_ms": p75,
            "warm_samples": sum(mode == "warm" for mode in modes),
        }

    decision = document["decision"]
    required_roles = {"content", "product", "security-privacy", "technical"}
    if decision["rc_id"] != rc_id:
        findings.add("MIXED_RELEASE_CANDIDATE", "$.decision.rc_id", "Decision must target the same RC.")
    if decision["run_id"] != active_run_id:
        findings.add("MIXED_COHORT_RUN", "$.decision.run_id", "Decision must target the active run.")
    if set(decision["review_roles"]) != required_roles or len(decision["review_roles"]) != len(required_roles):
        findings.add("DECISION_REVIEWERS", "$.decision.review_roles", "All four independent review roles are required exactly once.")
    if not decision["all_signed"]:
        findings.add("DECISION_UNSIGNED", "$.decision.all_signed", "Go/no-go decision must be signed.")
    if decision["declared"] != "go":
        findings.add("DECISION_NOT_GO", "$.decision.declared", "The validator never upgrades a declared non-GO decision.")

    full_consent_count = sum(
        participant["consent"]["analytics"] and participant["consent"]["diagnostics"]
        for participant in participants
    )
    crash_free_percent = (
        f"{((sessions_observed - sessions_with_crash) * 100 / sessions_observed):.4f}"
        if sessions_observed
        else "0.0000"
    )
    canary_duration_minutes = (
        int((canary_end - canary_start).total_seconds() // 60)
        if canary_start is not None and canary_end is not None and canary_end >= canary_start
        else 0
    )
    incident_counts = {
        severity: sum(incident["severity"] == severity for incident in incidents)
        for severity in ("P0", "P1", "P2", "P3")
    }
    summary = {
        "accessibility_passed": {
            "android": accessibility_summary.get("android", False),
            "ios": accessibility_summary.get("ios", False),
        },
        "canary": {
            "duration_minutes": canary_duration_minutes,
            "participants": len(set(canary_ids)),
        },
        "catalog": {
            "listings": catalog["listings"],
            "media": catalog["media"],
        },
        "cohort": {
            "days": len(days),
            "reset_generation": generation,
        },
        "consent_coverage": {
            "analytics_and_diagnostics": full_consent_count,
            "without_full_consent": len(participants) - full_consent_count,
        },
        "critical_checks": {
            "account_deletion": deletion["passed"],
            "consent_revocation": revocation["passed"] and revocation["zero_events_after_revocation"],
        },
        "incidents": incident_counts,
        "participants": {
            "android": platform_counts["android"],
            "ios": platform_counts["ios"],
            "total": len(participants),
        },
        "performance": performance_summary,
        "sessions": {
            "crash_free_percent": crash_free_percent,
            "observed": sessions_observed,
            "with_crash": sessions_with_crash,
        },
    }
    return summary


def _empty_summary() -> dict[str, Any]:
    return {
        "accessibility_passed": {"android": False, "ios": False},
        "canary": {"duration_minutes": 0, "participants": 0},
        "catalog": {"listings": 0, "media": 0},
        "cohort": {"days": 0, "reset_generation": 0},
        "consent_coverage": {"analytics_and_diagnostics": 0, "without_full_consent": 0},
        "critical_checks": {"account_deletion": False, "consent_revocation": False},
        "incidents": {"P0": 0, "P1": 0, "P2": 0, "P3": 0},
        "participants": {"android": 0, "ios": 0, "total": 0},
        "performance": {},
        "sessions": {"crash_free_percent": "0.0000", "observed": 0, "with_crash": 0},
    }


def validate_document(document: Any, *, source_bytes: bytes | None = None) -> dict[str, Any]:
    """Return a deterministic aggregate receipt for a parsed JSON document."""

    findings = Findings()
    structurally_valid = _validate_structure(document, findings)
    summary = _empty_summary()
    if structurally_valid and type(document) is dict:
        summary = _validate_semantics(document, findings)
    failures = [finding.as_dict() for finding in findings.sorted()]
    status = "no-go" if failures else "go"
    raw_release = document.get("release_candidate") if type(document) is dict else None
    release = raw_release if structurally_valid and type(raw_release) is dict else {}
    raw_decision = document.get("decision") if type(document) is dict else None
    decision = raw_decision if type(raw_decision) is dict else {}
    evaluated_at = document.get("evaluated_at_utc") if type(document) is dict else None
    if type(evaluated_at) is not str or UTC_PATTERN.fullmatch(evaluated_at) is None:
        evaluated_at = None
    fictitious = document.get("fictitious") if type(document) is dict else None
    if type(fictitious) is not bool:
        fictitious = None
    declared_decision = decision.get("declared")
    if declared_decision not in {"go", "go-with-corrections", "no-go"}:
        declared_decision = None
    fingerprint = {
        "build_id": release.get("build_id"),
        "expected_sha": release.get("expected_sha"),
        "rc_id": release.get("rc_id"),
        "version_name": release.get("version_name"),
    }
    canonical_source_bytes = _canonical_json_bytes(document)
    receipt_source_bytes = source_bytes if source_bytes is not None else canonical_source_bytes
    return {
        "declared_decision": declared_decision,
        "eligible_for_go": status == "go",
        "evaluated_at_utc": evaluated_at,
        "failures": failures,
        "fictitious": fictitious,
        "release_fingerprint": fingerprint,
        "schema_version": SCHEMA_VERSION,
        "source_bytes_sha256": hashlib.sha256(receipt_source_bytes).hexdigest(),
        "source_sha256": hashlib.sha256(canonical_source_bytes).hexdigest(),
        "status": status,
        "summary": summary,
    }


def _invalid_json_receipt(raw_bytes: bytes, code: str, message: str) -> dict[str, Any]:
    receipt = validate_document(None, source_bytes=raw_bytes)
    receipt["source_sha256"] = None
    receipt["failures"] = [
        Finding(code=code, path="$", message=message).as_dict()
    ]
    return receipt


def _write_receipt(path: Path, receipt: dict[str, Any], source: Path) -> None:
    if path.resolve() == source.resolve():
        raise ValueError("Receipt path must not overwrite the evidence input.")
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = _canonical_pretty_json(receipt)
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        dir=path.parent,
        delete=False,
        newline="\n",
    ) as handle:
        handle.write(payload)
        temporary_path = Path(handle.name)
    try:
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise


def _parse_arguments(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate Kwabor closed-beta pilot evidence.")
    parser.add_argument("evidence", type=Path, help="Path to the versioned pilot evidence JSON.")
    parser.add_argument("--receipt", type=Path, help="Optional path for the deterministic aggregate receipt.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    arguments = _parse_arguments(argv)
    try:
        raw_bytes = arguments.evidence.read_bytes()
    except OSError as error:
        print(f"Unable to read evidence: {error}", file=sys.stderr)
        return 2
    try:
        document = json.loads(
            raw_bytes,
            object_pairs_hook=_strict_json_object,
            parse_constant=_reject_nonfinite_json_constant,
        )
    except DuplicateJsonKeyError:
        receipt = _invalid_json_receipt(
            raw_bytes,
            "JSON_DUPLICATE_KEY",
            "Duplicate JSON object keys are forbidden.",
        )
    except (UnicodeDecodeError, ValueError):
        receipt = _invalid_json_receipt(raw_bytes, "JSON_INVALID", "Input is not valid strict JSON.")
    else:
        receipt = validate_document(document, source_bytes=raw_bytes)
    if arguments.receipt is not None:
        try:
            _write_receipt(arguments.receipt, receipt, arguments.evidence)
        except (OSError, ValueError) as error:
            print(f"Unable to write receipt: {error}", file=sys.stderr)
            return 2
    sys.stdout.write(_canonical_pretty_json(receipt))
    return 0 if receipt["eligible_for_go"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
