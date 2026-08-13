#!/usr/bin/env python3
"""Fail-closed helpers for the Kwabor iOS closed-beta release workflow."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import plistlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Iterable


EXPECTED_BUNDLE_ID = "com.kwabor.ios"
EXPECTED_ENVIRONMENT = "staging"
EXPECTED_REPOSITORY = "urbainmorel/KWABOR"
ASC_BASE_URL = "https://api.appstoreconnect.apple.com"
TEAM_ID_PATTERN = re.compile(r"^[A-Z0-9]{10}$")
KEY_ID_PATTERN = re.compile(r"^[A-Z0-9]{10}$")
ISSUER_ID_PATTERN = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
RESOURCE_ID_PATTERN = re.compile(r"^[A-Za-z0-9-]{1,128}$")
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
BUILD_PATTERN = re.compile(r"^[1-9][0-9]*$")


class ReleaseError(RuntimeError):
    """Raised when release authority or evidence is invalid."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseError(message)


def github_run_url(run_id: str) -> str:
    require(BUILD_PATTERN.fullmatch(run_id) is not None, "Invalid workflow run ID")
    return f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}"


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"Unable to read JSON evidence: {path.name}") from error
    require(isinstance(value, dict), f"JSON evidence is not an object: {path.name}")
    return value


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _normalized_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def parse_utc(value: str) -> datetime:
    normalized = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as error:
        raise ReleaseError("Invalid UTC timestamp") from error
    return _normalized_utc(parsed)


def utc_text(value: datetime) -> str:
    return _normalized_utc(value).isoformat(timespec="seconds").replace("+00:00", "Z")


def validate_release_identity(
    *,
    expected_sha: str,
    version_name: str,
    build_number: str,
    team_id: str,
    bundle_id: str,
) -> None:
    require(SHA_PATTERN.fullmatch(expected_sha) is not None, "Invalid expected_sha")
    require(VERSION_PATTERN.fullmatch(version_name) is not None, "Invalid iOS version")
    require(BUILD_PATTERN.fullmatch(build_number) is not None, "Invalid iOS build number")
    require(TEAM_ID_PATTERN.fullmatch(team_id) is not None, "Invalid Apple Team ID")
    require(bundle_id == EXPECTED_BUNDLE_ID, "Unexpected iOS bundle identifier")


def validate_profile(
    profile: dict[str, Any],
    *,
    expected_team_id: str,
    expected_bundle_id: str,
    expected_profile_name: str,
    certificate_sha1: str,
    now: datetime | None = None,
) -> dict[str, Any]:
    require(TEAM_ID_PATTERN.fullmatch(expected_team_id) is not None, "Invalid Apple Team ID")
    require(expected_bundle_id == EXPECTED_BUNDLE_ID, "Unexpected iOS bundle identifier")
    require(bool(expected_profile_name.strip()), "Expected provisioning profile name is missing")
    require(re.fullmatch(r"[0-9A-Fa-f]{40}", certificate_sha1) is not None, "Invalid certificate SHA-1")

    name = profile.get("Name")
    profile_uuid = profile.get("UUID")
    team_identifiers = profile.get("TeamIdentifier")
    expiration = profile.get("ExpirationDate")
    entitlements = profile.get("Entitlements")
    developer_certificates = profile.get("DeveloperCertificates")

    require(name == expected_profile_name, "Provisioning profile name does not match protected configuration")
    require(isinstance(profile_uuid, str) and bool(profile_uuid), "Provisioning profile UUID is missing")
    require(team_identifiers == [expected_team_id], "Provisioning profile targets another Apple team")
    require(isinstance(expiration, datetime), "Provisioning profile expiration is missing")
    require(isinstance(entitlements, dict), "Provisioning profile entitlements are missing")
    require(isinstance(developer_certificates, list), "Provisioning profile certificates are missing")

    current_time = now or datetime.now(timezone.utc)
    require(
        _normalized_utc(expiration) > _normalized_utc(current_time) + timedelta(days=7),
        "Provisioning profile expires in seven days or less",
    )
    require("ProvisionedDevices" not in profile, "Ad Hoc/development profiles are forbidden")
    require(profile.get("ProvisionsAllDevices") is not True, "Enterprise profiles are forbidden")

    application_identifier = f"{expected_team_id}.{expected_bundle_id}"
    require(
        entitlements.get("application-identifier") == application_identifier,
        "Provisioning profile application identifier is not exact",
    )
    require(
        entitlements.get("com.apple.developer.team-identifier") == expected_team_id,
        "Provisioning profile entitlement targets another team",
    )
    require(entitlements.get("get-task-allow") is False, "Debuggable profiles are forbidden")
    require(entitlements.get("aps-environment") == "production", "Production APNs is required")
    require(
        entitlements.get("com.apple.developer.applesignin") == ["Default"],
        "Sign in with Apple entitlement is required",
    )
    require(entitlements.get("beta-reports-active") is True, "App Store beta profile is required")

    certificate_hashes = {
        hashlib.sha1(bytes(certificate)).hexdigest().upper()
        for certificate in developer_certificates
        if isinstance(certificate, (bytes, bytearray))
    }
    require(
        certificate_sha1.upper() in certificate_hashes,
        "Imported distribution certificate is not authorized by the profile",
    )

    return {
        "bundleId": expected_bundle_id,
        "certificateSha1": certificate_sha1.upper(),
        "expirationDateUtc": utc_text(_normalized_utc(expiration)),
        "name": name,
        "teamId": expected_team_id,
        "uuid": profile_uuid,
    }


def create_export_options(
    *,
    destination: str,
    team_id: str,
    bundle_id: str,
    profile_name: str,
) -> dict[str, Any]:
    require(destination in {"export", "upload"}, "Unsupported App Store Connect destination")
    require(TEAM_ID_PATTERN.fullmatch(team_id) is not None, "Invalid Apple Team ID")
    require(bundle_id == EXPECTED_BUNDLE_ID, "Unexpected iOS bundle identifier")
    require(bool(profile_name.strip()), "Provisioning profile name is missing")
    result: dict[str, Any] = {
        "destination": destination,
        "distributionBundleIdentifier": bundle_id,
        "manageAppVersionAndBuildNumber": False,
        "method": "app-store-connect",
        "provisioningProfiles": {bundle_id: profile_name},
        "signingCertificate": "Apple Distribution",
        "signingStyle": "manual",
        "stripSwiftSymbols": True,
        "teamID": team_id,
        "uploadSymbols": True,
    }
    if destination == "upload":
        result["testFlightInternalTestingOnly"] = True
    return result


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _artifact(directory: Path, filename: str) -> dict[str, Any]:
    require(Path(filename).name == filename, "Artifact filename must not contain a path")
    path = directory / filename
    require(path.is_file(), f"Release artifact is missing: {filename}")
    return {"filename": filename, "sha256": _sha256(path), "sizeBytes": path.stat().st_size}


def archive_provenance(
    *,
    expected_sha: str,
    validated_ci_run_id: str,
    version_name: str,
    build_number: str,
    profile_metadata: dict[str, Any],
    repository: str,
    actor: str,
    run_id: str,
    run_attempt: str,
    created_at: datetime | None = None,
) -> dict[str, Any]:
    validate_release_identity(
        expected_sha=expected_sha,
        version_name=version_name,
        build_number=build_number,
        team_id=str(profile_metadata.get("teamId", "")),
        bundle_id=str(profile_metadata.get("bundleId", "")),
    )
    require(repository == EXPECTED_REPOSITORY, "Unexpected repository")
    require(0 < len(actor.strip()) <= 100, "Invalid workflow actor")
    require(BUILD_PATTERN.fullmatch(validated_ci_run_id) is not None, "Invalid CI run ID")
    require(BUILD_PATTERN.fullmatch(run_id) is not None, "Invalid workflow run ID")
    require(BUILD_PATTERN.fullmatch(run_attempt) is not None, "Invalid workflow run attempt")
    return {
        "actor": actor.strip(),
        "buildNumber": build_number,
        "createdAtUtc": utc_text(created_at or datetime.now(timezone.utc)),
        "environment": EXPECTED_ENVIRONMENT,
        "expectedSha": expected_sha,
        "profile": profile_metadata,
        "repository": repository,
        "runAttempt": int(run_attempt),
        "runId": int(run_id),
        "schemaVersion": 1,
        "validatedCiRunId": int(validated_ci_run_id),
        "versionName": version_name,
    }


def release_evidence(
    *,
    directory: Path,
    archive_filename: str,
    ipa_filename: str,
    dsym_filename: str,
    expected_sha: str,
    validated_ci_run_id: str,
    version_name: str,
    build_number: str,
    profile_metadata: dict[str, Any],
    repository: str,
    actor: str,
    run_id: str,
    run_attempt: str,
    run_url: str,
    created_at: datetime | None = None,
) -> dict[str, Any]:
    provenance = archive_provenance(
        expected_sha=expected_sha,
        validated_ci_run_id=validated_ci_run_id,
        version_name=version_name,
        build_number=build_number,
        profile_metadata=profile_metadata,
        repository=repository,
        actor=actor,
        run_id=run_id,
        run_attempt=run_attempt,
        created_at=created_at,
    )
    require(run_url == github_run_url(run_id), "Workflow run URL does not match its run ID")
    provenance.update(
        {
            "artifacts": {
                "archive": _artifact(directory, archive_filename),
                "dSYM": _artifact(directory, dsym_filename),
                "ipa": _artifact(directory, ipa_filename),
            },
            "distribution": "archive-only",
            "gate": "G6",
            "runUrl": run_url,
            "taskId": "B7.03",
        }
    )
    return provenance


def verify_release_evidence(
    evidence: dict[str, Any],
    *,
    directory: Path,
    expected_sha: str,
    validated_ci_run_id: str,
    version_name: str,
    build_number: str,
    archive_run_id: str,
    current_profile_metadata: dict[str, Any],
) -> None:
    require(evidence.get("schemaVersion") == 1, "Unsupported iOS release evidence schema")
    require(evidence.get("gate") == "G6" and evidence.get("taskId") == "B7.03", "Wrong GEL task")
    require(evidence.get("distribution") == "archive-only", "Source artifact is not archive-only")
    require(evidence.get("environment") == EXPECTED_ENVIRONMENT, "Source artifact is not staging")
    require(evidence.get("repository") == EXPECTED_REPOSITORY, "Source artifact repository mismatch")
    require(evidence.get("expectedSha") == expected_sha, "Source artifact SHA mismatch")
    require(evidence.get("validatedCiRunId") == int(validated_ci_run_id), "Source CI run mismatch")
    require(evidence.get("runId") == int(archive_run_id), "Source archive run mismatch")
    require(evidence.get("runUrl") == github_run_url(archive_run_id), "Source archive run URL mismatch")
    require(evidence.get("versionName") == version_name, "Source version mismatch")
    require(evidence.get("buildNumber") == build_number, "Source build mismatch")
    require(evidence.get("profile") == current_profile_metadata, "Signing profile/certificate drifted since archive")

    artifacts = evidence.get("artifacts")
    require(isinstance(artifacts, dict) and set(artifacts) == {"archive", "dSYM", "ipa"}, "Artifact set mismatch")
    for label, metadata in artifacts.items():
        require(isinstance(metadata, dict), f"Invalid {label} evidence")
        filename = metadata.get("filename")
        require(isinstance(filename, str), f"Invalid {label} filename")
        actual = _artifact(directory, filename)
        require(actual == metadata, f"{label} artifact digest or size mismatch")


def verify_archive_provenance(
    provenance: dict[str, Any],
    evidence: dict[str, Any],
) -> None:
    shared_keys = {
        "buildNumber",
        "actor",
        "environment",
        "expectedSha",
        "profile",
        "repository",
        "runAttempt",
        "runId",
        "schemaVersion",
        "validatedCiRunId",
        "versionName",
    }
    require(set(provenance) == shared_keys | {"createdAtUtc"}, "Archive provenance fields are invalid")
    for key in shared_keys:
        require(provenance.get(key) == evidence.get(key), f"Archive provenance mismatch: {key}")


def validate_github_run(
    document: dict[str, Any],
    *,
    expected_run_id: str,
    expected_sha: str,
    expected_workflow_path: str,
    allowed_events: Iterable[str],
) -> None:
    require(BUILD_PATTERN.fullmatch(expected_run_id) is not None, "Invalid GitHub run ID")
    require(SHA_PATTERN.fullmatch(expected_sha) is not None, "Invalid expected_sha")
    repository = document.get("repository")
    path = str(document.get("path", "")).split("@", maxsplit=1)[0]
    require(document.get("id") == int(expected_run_id), "GitHub run ID mismatch")
    require(isinstance(repository, dict) and repository.get("full_name") == EXPECTED_REPOSITORY, "Run belongs to another repository")
    require(document.get("head_sha") == expected_sha, "Run belongs to another commit")
    require(document.get("head_branch") == "main", "Run did not execute on main")
    require(document.get("status") == "completed" and document.get("conclusion") == "success", "Run is not successfully completed")
    require(path == expected_workflow_path, "Unexpected source workflow")
    require(document.get("event") in set(allowed_events), "Unexpected source workflow event")


def validate_environment_protection(
    document: dict[str, Any],
    *,
    expected_name: str,
) -> dict[str, Any]:
    require(expected_name in {"staging", "testflight-internal"}, "Unexpected GitHub Environment name")
    require(document.get("name") == expected_name, "GitHub Environment identity mismatch")
    require(document.get("can_admins_bypass") is False, "GitHub Environment permits administrator bypass")

    rules = document.get("protection_rules")
    require(isinstance(rules, list), "GitHub Environment protection rules are missing")
    required_reviewer_rules = [
        rule for rule in rules if isinstance(rule, dict) and rule.get("type") == "required_reviewers"
    ]
    branch_rules = [
        rule for rule in rules if isinstance(rule, dict) and rule.get("type") == "branch_policy"
    ]
    require(len(required_reviewer_rules) == 1, "Exactly one required-reviewer rule must be configured")
    require(len(branch_rules) == 1, "Exactly one protected-branch rule must be configured")
    reviewer_rule = required_reviewer_rules[0]
    reviewers = reviewer_rule.get("reviewers")
    require(isinstance(reviewers, list) and bool(reviewers), "At least one Environment reviewer is required")
    require(
        reviewer_rule.get("prevent_self_review") is True,
        "Environment must prevent workflow initiators from self-approving",
    )
    reviewer_types: set[str] = set()
    for reviewer_link in reviewers:
        require(isinstance(reviewer_link, dict), "Environment reviewer linkage is invalid")
        reviewer_type = reviewer_link.get("type")
        reviewer = reviewer_link.get("reviewer")
        require(reviewer_type in {"User", "Team"}, "Environment reviewer type is invalid")
        require(
            isinstance(reviewer, dict)
            and isinstance(reviewer.get("id"), int)
            and reviewer["id"] > 0,
            "Environment reviewer identity is invalid",
        )
        reviewer_types.add(str(reviewer_type))

    branch_policy = document.get("deployment_branch_policy")
    require(isinstance(branch_policy, dict), "Environment deployment branch policy is missing")
    require(
        branch_policy.get("protected_branches") is True
        and branch_policy.get("custom_branch_policies") is False,
        "Environment is not restricted to protected branches",
    )
    environment_id = document.get("id")
    require(isinstance(environment_id, int) and environment_id > 0, "Environment resource ID is invalid")
    return {
        "canAdminsBypass": False,
        "environmentId": environment_id,
        "name": expected_name,
        "preventSelfReview": True,
        "protectedBranchesOnly": True,
        "reviewerCount": len(reviewers),
        "reviewerTypes": sorted(reviewer_types),
        "schemaVersion": 1,
        "updatedAt": document.get("updated_at"),
    }


def _base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def ecdsa_der_to_raw(signature: bytes, component_size: int = 32) -> bytes:
    def read_length(data: bytes, offset: int) -> tuple[int, int]:
        require(offset < len(data), "Truncated DER signature")
        first = data[offset]
        offset += 1
        if first < 0x80:
            return first, offset
        count = first & 0x7F
        require(0 < count <= 4 and offset + count <= len(data), "Invalid DER length")
        return int.from_bytes(data[offset : offset + count], "big"), offset + count

    require(bool(signature) and signature[0] == 0x30, "Invalid DER signature sequence")
    sequence_length, offset = read_length(signature, 1)
    require(offset + sequence_length == len(signature), "Invalid DER signature size")
    integers: list[int] = []
    for _ in range(2):
        require(offset < len(signature) and signature[offset] == 0x02, "Invalid DER signature integer")
        integer_length, offset = read_length(signature, offset + 1)
        require(0 < integer_length and offset + integer_length <= len(signature), "Invalid DER integer size")
        integers.append(int.from_bytes(signature[offset : offset + integer_length], "big"))
        offset += integer_length
    require(offset == len(signature), "Trailing DER signature data")
    maximum = 1 << (component_size * 8)
    require(all(0 < value < maximum for value in integers), "DER signature component is out of range")
    return b"".join(value.to_bytes(component_size, "big") for value in integers)


def create_asc_token(
    *,
    key_id: str,
    issuer_id: str,
    private_key_path: Path,
    now: int | None = None,
) -> str:
    require(KEY_ID_PATTERN.fullmatch(key_id) is not None, "Invalid App Store Connect key ID")
    require(ISSUER_ID_PATTERN.fullmatch(issuer_id) is not None, "Invalid App Store Connect issuer ID")
    require(private_key_path.is_file(), "App Store Connect private key is missing")
    issued_at = int(time.time() if now is None else now) - 5
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    payload = {"aud": "appstoreconnect-v1", "exp": issued_at + 600, "iat": issued_at, "iss": issuer_id}
    signing_input = (
        _base64url(json.dumps(header, separators=(",", ":"), sort_keys=True).encode("utf-8"))
        + "."
        + _base64url(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    )
    completed = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(private_key_path)],
        input=signing_input.encode("ascii"),
        capture_output=True,
        check=False,
    )
    require(completed.returncode == 0, "Unable to sign App Store Connect token")
    raw_signature = ecdsa_der_to_raw(completed.stdout)
    return signing_input + "." + _base64url(raw_signature)


class AppStoreConnectClient:
    def __init__(self, key_id: str, issuer_id: str, private_key_path: Path) -> None:
        self.key_id = key_id
        self.issuer_id = issuer_id
        self.private_key_path = private_key_path

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        expected_statuses: tuple[int, ...] = (200,),
    ) -> dict[str, Any] | None:
        require(path.startswith("/v1/"), "App Store Connect API path is invalid")
        payload = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        transient = {429, 500, 502, 503, 504}
        for attempt in range(1, 5):
            token = create_asc_token(
                key_id=self.key_id,
                issuer_id=self.issuer_id,
                private_key_path=self.private_key_path,
            )
            request = urllib.request.Request(
                ASC_BASE_URL + path,
                data=payload,
                method=method,
                headers={
                    "Accept": "application/json",
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json",
                },
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    status = response.status
                    response_body = response.read()
            except urllib.error.HTTPError as error:
                status = error.code
                response_body = error.read()
            except urllib.error.URLError as error:
                if attempt == 4:
                    raise ReleaseError("App Store Connect request failed") from error
                time.sleep(attempt * 2)
                continue

            if status in expected_statuses:
                if not response_body:
                    return None
                try:
                    decoded = json.loads(response_body.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError) as error:
                    raise ReleaseError("App Store Connect returned invalid JSON") from error
                require(isinstance(decoded, dict), "App Store Connect returned an invalid document")
                return decoded
            if status in transient and attempt < 4:
                time.sleep(attempt * 2)
                continue

            message = f"App Store Connect request failed with HTTP {status}"
            try:
                decoded_error = json.loads(response_body.decode("utf-8"))
                errors = decoded_error.get("errors", []) if isinstance(decoded_error, dict) else []
                safe_codes = [str(item.get("code", "")) for item in errors if isinstance(item, dict)]
                if safe_codes:
                    message += ": " + ", ".join(safe_codes[:3])
            except (UnicodeDecodeError, json.JSONDecodeError):
                pass
            raise ReleaseError(message)
        raise ReleaseError("App Store Connect request exhausted retries")


def _resource(document: dict[str, Any], expected_type: str) -> dict[str, Any]:
    data = document.get("data")
    require(isinstance(data, dict) and data.get("type") == expected_type, f"Invalid {expected_type} resource")
    return data


def _resources(document: dict[str, Any], expected_type: str) -> list[dict[str, Any]]:
    data = document.get("data")
    require(isinstance(data, list), f"Invalid {expected_type} collection")
    require(all(isinstance(item, dict) and item.get("type") == expected_type for item in data), f"Invalid {expected_type} item")
    return data


def _resolve_app_group(
    client: AppStoreConnectClient,
    *,
    bundle_id: str,
    app_id: str,
    group_id: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    require(bundle_id == EXPECTED_BUNDLE_ID, "Unexpected iOS bundle identifier")
    require(RESOURCE_ID_PATTERN.fullmatch(app_id) is not None, "Invalid App Store Connect app ID")
    require(RESOURCE_ID_PATTERN.fullmatch(group_id) is not None, "Invalid TestFlight group ID")
    query = urllib.parse.urlencode({"filter[bundleId]": bundle_id, "limit": "2"})
    apps_document = client.request("GET", f"/v1/apps?{query}")
    require(apps_document is not None, "App Store Connect app lookup returned no document")
    apps = _resources(apps_document, "apps")
    require(len(apps) == 1 and apps[0].get("id") == app_id, "App Store Connect app identity mismatch")
    app_attributes = apps[0].get("attributes")
    require(
        isinstance(app_attributes, dict) and app_attributes.get("bundleId") == bundle_id,
        "App Store Connect bundle identifier mismatch",
    )

    group_document = client.request("GET", f"/v1/betaGroups/{group_id}?include=app")
    require(group_document is not None, "TestFlight group lookup returned no document")
    group = _resource(group_document, "betaGroups")
    attributes = group.get("attributes")
    relationships = group.get("relationships")
    require(group.get("id") == group_id, "TestFlight group identity mismatch")
    require(isinstance(attributes, dict), "TestFlight group attributes are missing")
    require(attributes.get("isInternalGroup") is True, "TestFlight group is not internal")
    require(attributes.get("hasAccessToAllBuilds") is False, "Automatic all-build distribution must be disabled")
    require(attributes.get("publicLinkEnabled") in {None, False}, "Public TestFlight links are forbidden")
    require(isinstance(attributes.get("name"), str) and bool(attributes["name"].strip()), "TestFlight group name is missing")
    require(isinstance(relationships, dict), "TestFlight group app relationship is missing")
    app_relationship = relationships.get("app")
    app_data = app_relationship.get("data") if isinstance(app_relationship, dict) else None
    require(isinstance(app_data, dict) and app_data.get("id") == app_id, "TestFlight group belongs to another app")
    return apps[0], group


def _matching_builds(
    client: AppStoreConnectClient,
    *,
    app_id: str,
    version_name: str,
    build_number: str,
) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode(
        {
            "filter[app]": app_id,
            "filter[version]": build_number,
            "fields[builds]": "version,uploadedDate,expired,processingState,buildAudienceType",
            "limit": "10",
        }
    )
    document = client.request("GET", f"/v1/builds?{query}")
    require(document is not None, "Build lookup returned no document")
    matches: list[dict[str, Any]] = []
    for build in _resources(document, "builds"):
        build_id = build.get("id")
        require(isinstance(build_id, str), "Build resource ID is missing")
        prerelease_document = client.request("GET", f"/v1/builds/{build_id}/preReleaseVersion")
        require(prerelease_document is not None, "Prerelease version lookup returned no document")
        prerelease = _resource(prerelease_document, "preReleaseVersions")
        prerelease_attributes = prerelease.get("attributes")
        if isinstance(prerelease_attributes, dict) and prerelease_attributes.get("version") == version_name:
            matches.append(build)
    return matches


def asc_preflight(
    client: AppStoreConnectClient,
    *,
    bundle_id: str,
    app_id: str,
    group_id: str,
    version_name: str,
    build_number: str,
) -> dict[str, Any]:
    app, group = _resolve_app_group(client, bundle_id=bundle_id, app_id=app_id, group_id=group_id)
    existing = _matching_builds(
        client,
        app_id=app_id,
        version_name=version_name,
        build_number=build_number,
    )
    require(not existing, "This App Store Connect version/build already exists")
    group_attributes = group["attributes"]
    return {
        "appId": app["id"],
        "bundleId": bundle_id,
        "groupId": group["id"],
        "groupName": group_attributes["name"],
        "internalOnly": True,
        "publicLinkEnabled": False,
        "schemaVersion": 1,
    }


def validate_release_notes(value: str) -> str:
    notes = value.strip()
    require(1 <= len(notes) <= 4000, "TestFlight release notes must contain 1 to 4000 characters")
    require(all(character >= " " or character in "\n\t" for character in notes), "Release notes contain control characters")
    return notes


def _upsert_release_notes(
    client: AppStoreConnectClient,
    *,
    build_id: str,
    locale: str,
    release_notes: str,
) -> str:
    document = client.request(
        "GET",
        f"/v1/builds/{build_id}/betaBuildLocalizations?fields%5BbetaBuildLocalizations%5D=locale%2CwhatsNew&limit=200",
    )
    require(document is not None, "Beta localization lookup returned no document")
    matches = [
        item
        for item in _resources(document, "betaBuildLocalizations")
        if isinstance(item.get("attributes"), dict) and item["attributes"].get("locale") == locale
    ]
    require(len(matches) <= 1, "Duplicate TestFlight localizations exist")
    if matches:
        localization_id = str(matches[0].get("id", ""))
        payload = {
            "data": {
                "attributes": {"whatsNew": release_notes},
                "id": localization_id,
                "type": "betaBuildLocalizations",
            }
        }
        client.request("PATCH", f"/v1/betaBuildLocalizations/{localization_id}", payload)
        return localization_id

    payload = {
        "data": {
            "attributes": {"locale": locale, "whatsNew": release_notes},
            "relationships": {"build": {"data": {"id": build_id, "type": "builds"}}},
            "type": "betaBuildLocalizations",
        }
    }
    created = client.request("POST", "/v1/betaBuildLocalizations", payload, (201,))
    require(created is not None, "Beta localization creation returned no document")
    return str(_resource(created, "betaBuildLocalizations").get("id", ""))


def _associate_group(client: AppStoreConnectClient, *, group_id: str, build_id: str) -> None:
    relationship_path = f"/v1/betaGroups/{group_id}/relationships/builds?limit=200"
    before = client.request("GET", relationship_path)
    require(before is not None, "TestFlight group build lookup returned no document")
    related = {str(item.get("id")) for item in _resources(before, "builds")}
    if build_id not in related:
        payload = {"data": [{"id": build_id, "type": "builds"}]}
        client.request(
            "POST",
            f"/v1/betaGroups/{group_id}/relationships/builds",
            payload,
            (204,),
        )
    after = client.request("GET", relationship_path)
    require(after is not None, "TestFlight group verification returned no document")
    verified = {str(item.get("id")) for item in _resources(after, "builds")}
    require(build_id in verified, "Processed build was not assigned to the internal group")


def publish_internal_build(
    client: AppStoreConnectClient,
    *,
    bundle_id: str,
    app_id: str,
    group_id: str,
    version_name: str,
    build_number: str,
    release_notes: str,
    expected_sha: str,
    validated_ci_run_id: str,
    archive_run_id: str,
    upload_run_id: str,
    upload_run_attempt: str,
    upload_run_url: str,
    actor: str,
    uploaded_after: datetime,
    timeout_seconds: int,
    poll_seconds: int = 30,
    clock: Callable[[], float] = time.monotonic,
    sleeper: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    require(SHA_PATTERN.fullmatch(expected_sha) is not None, "Invalid expected_sha")
    require(BUILD_PATTERN.fullmatch(validated_ci_run_id) is not None, "Invalid CI run ID")
    require(BUILD_PATTERN.fullmatch(archive_run_id) is not None, "Invalid archive run ID")
    require(BUILD_PATTERN.fullmatch(upload_run_id) is not None, "Invalid upload run ID")
    require(BUILD_PATTERN.fullmatch(upload_run_attempt) is not None, "Invalid upload run attempt")
    require(
        upload_run_url == github_run_url(upload_run_id),
        "Upload run URL does not match its run ID",
    )
    require(0 < len(actor.strip()) <= 100, "Invalid workflow actor")
    notes = validate_release_notes(release_notes)
    app, group = _resolve_app_group(client, bundle_id=bundle_id, app_id=app_id, group_id=group_id)
    deadline = clock() + timeout_seconds
    selected: dict[str, Any] | None = None
    while clock() <= deadline:
        matches = _matching_builds(
            client,
            app_id=app_id,
            version_name=version_name,
            build_number=build_number,
        )
        require(len(matches) <= 1, "Multiple App Store Connect builds match the exact version/build")
        if matches:
            candidate = matches[0]
            attributes = candidate.get("attributes")
            require(isinstance(attributes, dict), "Build attributes are missing")
            state = attributes.get("processingState")
            require(state not in {"FAILED", "INVALID"}, f"App Store Connect processing failed: {state}")
            if state == "VALID":
                uploaded_date = attributes.get("uploadedDate")
                require(isinstance(uploaded_date, str), "Build upload date is missing")
                require(
                    parse_utc(uploaded_date) >= _normalized_utc(uploaded_after) - timedelta(minutes=5),
                    "Matched build predates this protected upload",
                )
                require(attributes.get("expired") is not True, "Processed TestFlight build is expired")
                require(
                    attributes.get("buildAudienceType") == "INTERNAL_ONLY",
                    "Uploaded build is App Store eligible instead of TestFlight Internal Only",
                )
                selected = candidate
                break
        sleeper(poll_seconds)
    require(selected is not None, "Timed out waiting for App Store Connect processing")
    build_id = str(selected.get("id", ""))
    require(RESOURCE_ID_PATTERN.fullmatch(build_id) is not None, "Processed build ID is invalid")
    localization_id = _upsert_release_notes(
        client,
        build_id=build_id,
        locale="fr-FR",
        release_notes=notes,
    )
    _associate_group(client, group_id=group_id, build_id=build_id)
    attributes = selected["attributes"]
    group_attributes = group["attributes"]
    return {
        "actor": actor.strip(),
        "appId": app["id"],
        "archiveRunId": int(archive_run_id),
        "buildAudienceType": "INTERNAL_ONLY",
        "buildId": build_id,
        "buildNumber": build_number,
        "bundleId": bundle_id,
        "environment": EXPECTED_ENVIRONMENT,
        "expectedSha": expected_sha,
        "gate": "G6",
        "groupId": group["id"],
        "groupName": group_attributes["name"],
        "localizationId": localization_id,
        "notesSha256": hashlib.sha256(notes.encode("utf-8")).hexdigest(),
        "processingState": attributes["processingState"],
        "schemaVersion": 1,
        "taskId": "B7.05",
        "uploadRunAttempt": int(upload_run_attempt),
        "uploadRunId": int(upload_run_id),
        "uploadRunUrl": upload_run_url,
        "uploadedDate": attributes["uploadedDate"],
        "validatedCiRunId": int(validated_ci_run_id),
        "versionName": version_name,
    }


def _profile_command(args: argparse.Namespace) -> None:
    try:
        with Path(args.profile_plist).open("rb") as source:
            profile = plistlib.load(source)
    except (OSError, plistlib.InvalidFileException) as error:
        raise ReleaseError("Unable to read decoded provisioning profile") from error
    require(isinstance(profile, dict), "Decoded provisioning profile is invalid")
    metadata = validate_profile(
        profile,
        expected_team_id=args.team_id,
        expected_bundle_id=args.bundle_id,
        expected_profile_name=args.profile_name,
        certificate_sha1=args.certificate_sha1,
    )
    _write_json(Path(args.output), metadata)


def _export_options_command(args: argparse.Namespace) -> None:
    options = create_export_options(
        destination=args.destination,
        team_id=args.team_id,
        bundle_id=args.bundle_id,
        profile_name=args.profile_name,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as destination:
        plistlib.dump(options, destination, fmt=plistlib.FMT_XML, sort_keys=True)


def _archive_provenance_command(args: argparse.Namespace) -> None:
    metadata = _load_json(Path(args.profile_metadata))
    value = archive_provenance(
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
        version_name=args.version_name,
        build_number=args.build_number,
        profile_metadata=metadata,
        repository=args.repository,
        actor=args.actor,
        run_id=args.run_id,
        run_attempt=args.run_attempt,
    )
    _write_json(Path(args.output), value)


def _evidence_command(args: argparse.Namespace) -> None:
    metadata = _load_json(Path(args.profile_metadata))
    value = release_evidence(
        directory=Path(args.directory),
        archive_filename=args.archive_filename,
        ipa_filename=args.ipa_filename,
        dsym_filename=args.dsym_filename,
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
        version_name=args.version_name,
        build_number=args.build_number,
        profile_metadata=metadata,
        repository=args.repository,
        actor=args.actor,
        run_id=args.run_id,
        run_attempt=args.run_attempt,
        run_url=args.run_url,
    )
    _write_json(Path(args.output), value)


def _verify_evidence_command(args: argparse.Namespace) -> None:
    evidence = _load_json(Path(args.evidence))
    profile_metadata = _load_json(Path(args.profile_metadata))
    verify_release_evidence(
        evidence,
        directory=Path(args.directory),
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
        version_name=args.version_name,
        build_number=args.build_number,
        archive_run_id=args.archive_run_id,
        current_profile_metadata=profile_metadata,
    )


def _verify_archive_provenance_command(args: argparse.Namespace) -> None:
    verify_archive_provenance(
        _load_json(Path(args.provenance)),
        _load_json(Path(args.evidence)),
    )


def _github_run_command(args: argparse.Namespace) -> None:
    validate_github_run(
        _load_json(Path(args.document)),
        expected_run_id=args.run_id,
        expected_sha=args.expected_sha,
        expected_workflow_path=args.workflow_path,
        allowed_events=args.allowed_event,
    )


def _environment_command(args: argparse.Namespace) -> None:
    result = validate_environment_protection(
        _load_json(Path(args.document)),
        expected_name=args.name,
    )
    _write_json(Path(args.output), result)


def _asc_client(args: argparse.Namespace) -> AppStoreConnectClient:
    return AppStoreConnectClient(args.key_id, args.issuer_id, Path(args.private_key))


def _asc_preflight_command(args: argparse.Namespace) -> None:
    result = asc_preflight(
        _asc_client(args),
        bundle_id=args.bundle_id,
        app_id=args.app_id,
        group_id=args.group_id,
        version_name=args.version_name,
        build_number=args.build_number,
    )
    _write_json(Path(args.output), result)


def _asc_publish_command(args: argparse.Namespace) -> None:
    result = publish_internal_build(
        _asc_client(args),
        bundle_id=args.bundle_id,
        app_id=args.app_id,
        group_id=args.group_id,
        version_name=args.version_name,
        build_number=args.build_number,
        release_notes=args.release_notes,
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
        archive_run_id=args.archive_run_id,
        upload_run_id=args.upload_run_id,
        upload_run_attempt=args.upload_run_attempt,
        upload_run_url=args.upload_run_url,
        actor=args.actor,
        uploaded_after=parse_utc(args.uploaded_after),
        timeout_seconds=args.timeout_seconds,
    )
    _write_json(Path(args.output), result)


def _identity_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--validated-ci-run-id", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--build-number", required=True)


def _asc_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--issuer-id", required=True)
    parser.add_argument("--private-key", required=True)
    parser.add_argument("--bundle-id", required=True)
    parser.add_argument("--app-id", required=True)
    parser.add_argument("--group-id", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--build-number", required=True)
    parser.add_argument("--output", required=True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    profile = subparsers.add_parser("validate-profile")
    profile.add_argument("--profile-plist", required=True)
    profile.add_argument("--team-id", required=True)
    profile.add_argument("--bundle-id", required=True)
    profile.add_argument("--profile-name", required=True)
    profile.add_argument("--certificate-sha1", required=True)
    profile.add_argument("--output", required=True)
    profile.set_defaults(handler=_profile_command)

    export = subparsers.add_parser("export-options")
    export.add_argument("--destination", choices=("export", "upload"), required=True)
    export.add_argument("--team-id", required=True)
    export.add_argument("--bundle-id", required=True)
    export.add_argument("--profile-name", required=True)
    export.add_argument("--output", required=True)
    export.set_defaults(handler=_export_options_command)

    provenance = subparsers.add_parser("archive-provenance")
    _identity_arguments(provenance)
    provenance.add_argument("--profile-metadata", required=True)
    provenance.add_argument("--repository", required=True)
    provenance.add_argument("--actor", required=True)
    provenance.add_argument("--run-id", required=True)
    provenance.add_argument("--run-attempt", required=True)
    provenance.add_argument("--output", required=True)
    provenance.set_defaults(handler=_archive_provenance_command)

    evidence = subparsers.add_parser("write-evidence")
    _identity_arguments(evidence)
    evidence.add_argument("--directory", required=True)
    evidence.add_argument("--archive-filename", required=True)
    evidence.add_argument("--ipa-filename", required=True)
    evidence.add_argument("--dsym-filename", required=True)
    evidence.add_argument("--profile-metadata", required=True)
    evidence.add_argument("--repository", required=True)
    evidence.add_argument("--actor", required=True)
    evidence.add_argument("--run-id", required=True)
    evidence.add_argument("--run-attempt", required=True)
    evidence.add_argument("--run-url", required=True)
    evidence.add_argument("--output", required=True)
    evidence.set_defaults(handler=_evidence_command)

    verify = subparsers.add_parser("verify-evidence")
    _identity_arguments(verify)
    verify.add_argument("--directory", required=True)
    verify.add_argument("--evidence", required=True)
    verify.add_argument("--archive-run-id", required=True)
    verify.add_argument("--profile-metadata", required=True)
    verify.set_defaults(handler=_verify_evidence_command)

    verify_provenance = subparsers.add_parser("verify-archive-provenance")
    verify_provenance.add_argument("--provenance", required=True)
    verify_provenance.add_argument("--evidence", required=True)
    verify_provenance.set_defaults(handler=_verify_archive_provenance_command)

    github_run = subparsers.add_parser("validate-github-run")
    github_run.add_argument("--document", required=True)
    github_run.add_argument("--run-id", required=True)
    github_run.add_argument("--expected-sha", required=True)
    github_run.add_argument("--workflow-path", required=True)
    github_run.add_argument("--allowed-event", action="append", required=True)
    github_run.set_defaults(handler=_github_run_command)

    environment = subparsers.add_parser("validate-environment")
    environment.add_argument("--document", required=True)
    environment.add_argument("--name", choices=("staging", "testflight-internal"), required=True)
    environment.add_argument("--output", required=True)
    environment.set_defaults(handler=_environment_command)

    asc_preflight_parser = subparsers.add_parser("asc-preflight")
    _asc_arguments(asc_preflight_parser)
    asc_preflight_parser.set_defaults(handler=_asc_preflight_command)

    asc_publish_parser = subparsers.add_parser("asc-publish")
    _asc_arguments(asc_publish_parser)
    asc_publish_parser.add_argument("--release-notes", required=True)
    asc_publish_parser.add_argument("--expected-sha", required=True)
    asc_publish_parser.add_argument("--validated-ci-run-id", required=True)
    asc_publish_parser.add_argument("--archive-run-id", required=True)
    asc_publish_parser.add_argument("--upload-run-id", required=True)
    asc_publish_parser.add_argument("--upload-run-attempt", required=True)
    asc_publish_parser.add_argument("--upload-run-url", required=True)
    asc_publish_parser.add_argument("--actor", required=True)
    asc_publish_parser.add_argument("--uploaded-after", required=True)
    asc_publish_parser.add_argument("--timeout-seconds", type=int, default=1800)
    asc_publish_parser.set_defaults(handler=_asc_publish_command)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        args.handler(args)
    except ReleaseError as error:
        print(f"iOS closed-beta release refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
