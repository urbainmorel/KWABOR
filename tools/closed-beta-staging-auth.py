#!/usr/bin/env python3
"""Plan, apply, or verify the exact Supabase Auth contract for Kwabor staging.

The runner is intentionally usable only from the protected manual GitHub workflow.
It never prints Management API responses or secret values. Evidence contains only
public configuration, presence flags for write-only credentials, and SHA-256
digests for repository-owned email templates.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


EXPECTED_REPOSITORY = "urbainmorel/KWABOR"
EXPECTED_ENVIRONMENT = "staging"
EXPECTED_WORKFLOW = ".github/workflows/closed-beta-staging-auth.yml"
EXPECTED_CI_WORKFLOW = ".github/workflows/ci.yml"
EXPECTED_SITE_URL = "kwabor://app/home"
EXPECTED_REDIRECT_URLS = ("kwabor://auth/promoter-activate",)
EXPECTED_APPLE_CLIENT_ID = "com.kwabor.ios"
EXPECTED_SMTP_SENDER_NAME = "Kwabor"
APPLY_CONFIRMATION = "APPLY-EXACT-STAGING-AUTH"
RECEIPT_NAME = "GEL-G5-STAGING-AUTH.json"
MANAGEMENT_API_ORIGIN = "https://api.supabase.com"
MAX_EVIDENCE_BYTES = 1024 * 1024
MAX_PLAN_ARTIFACT_BYTES = 16 * 1024 * 1024
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
PROJECT_REF_PATTERN = re.compile(r"^[a-z0-9]{20}$")
POSITIVE_INTEGER_PATTERN = re.compile(r"^[1-9][0-9]*$")
GOOGLE_CLIENT_ID_PATTERN = re.compile(r"^[A-Za-z0-9-]+\.apps\.googleusercontent\.com$")
HOST_PATTERN = re.compile(
    r"^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+"
    r"[A-Za-z]{2,63}$"
)
EMAIL_PATTERN = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")

PUBLIC_FIELDS = (
    "site_url",
    "uri_allow_list",
    "disable_signup",
    "external_anonymous_users_enabled",
    "external_email_enabled",
    "external_phone_enabled",
    "password_min_length",
    "mailer_otp_length",
    "mailer_otp_exp",
    "smtp_admin_email",
    "smtp_host",
    "smtp_port",
    "smtp_max_frequency",
    "smtp_sender_name",
    "mailer_allow_unverified_email_sign_ins",
    "mailer_autoconfirm",
    "mailer_secure_email_change_enabled",
    "security_captcha_enabled",
    "mailer_subjects_magic_link",
    "mailer_subjects_recovery",
    "external_google_enabled",
    "external_google_client_id",
    "external_google_additional_client_ids",
    "external_google_skip_nonce_check",
    "external_google_email_optional",
    "external_apple_enabled",
    "external_apple_client_id",
    "external_apple_additional_client_ids",
    "external_apple_email_optional",
)
TEMPLATE_FIELDS = (
    "mailer_templates_magic_link_content",
    "mailer_templates_recovery_content",
)
WRITE_ONLY_FIELDS = (
    "external_google_secret",
    "smtp_user",
    "smtp_pass",
)
WRITE_ONLY_EVIDENCE_FIELDS = {
    "external_google_secret": "googleProviderCredentialConfigured",
    "smtp_user": "smtpIdentityConfigured",
    "smtp_pass": "smtpCredentialConfigured",
}
ALLOWED_ENABLED_EXTERNAL_PROVIDERS = frozenset(
    {
        "external_apple_enabled",
        "external_email_enabled",
        "external_google_enabled",
    }
)
NON_PATCHABLE_DRIFT = frozenset(
    {
        "appleWebCredentialMustBeAbsent",
        "unexpectedExternalProvidersEnabled",
    }
)
LIST_FIELDS = frozenset(
    {
        "uri_allow_list",
        "external_google_additional_client_ids",
        "external_apple_additional_client_ids",
    }
)
SENSITIVE_PUBLIC_EVIDENCE_FIELDS = {
    "smtp_admin_email": ("smtpAdminEmailConfigured", "smtpAdminEmailSha256"),
    "smtp_host": ("smtpHostConfigured", "smtpHostSha256"),
}


class StagingAuthError(RuntimeError):
    """Fail-closed error carrying a stable non-sensitive code."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class TargetAuthority:
    project_ref: str
    project_ref_sha256: str
    api_url: str


@dataclass(frozen=True)
class DesiredAuthConfiguration:
    public: dict[str, Any]
    templates: dict[str, str]
    write_only: dict[str, str]

    @property
    def patch(self) -> dict[str, Any]:
        payload = {**self.public, **self.templates, **self.write_only}
        # Supabase's Management API accepts provider audiences as a CSV in the
        # primary client-id field. Its GET response separates the first audience
        # from `*_additional_client_ids`, so qualification intentionally keeps
        # the read-back representation distinct from the write representation.
        for provider in ("google", "apple"):
            client_id_field = f"external_{provider}_client_id"
            additional_field = f"external_{provider}_additional_client_ids"
            primary = str(payload[client_id_field])
            additional = normalize_list(payload.pop(additional_field))
            payload[client_id_field] = ",".join((primary, *additional))
        return payload


class ManagementApiClient:
    """Small Management API client that never exposes response bodies in errors."""

    def __init__(self, *, access_token: str, project_ref: str, timeout_seconds: int = 30) -> None:
        if not access_token.strip():
            raise StagingAuthError("SUPABASE_ACCESS_TOKEN_MISSING")
        self._access_token = access_token
        self._project_ref = project_ref
        self._timeout_seconds = timeout_seconds

    def get_auth_config(self) -> dict[str, Any]:
        return self._request(method="GET")

    def patch_auth_config(self, payload: Mapping[str, Any]) -> None:
        self._request(method="PATCH", payload=payload)

    def _request(self, *, method: str, payload: Mapping[str, Any] | None = None) -> dict[str, Any]:
        url = f"{MANAGEMENT_API_ORIGIN}/v1/projects/{self._project_ref}/config/auth"
        body = None
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self._access_token}",
            "User-Agent": "kwabor-closed-beta-staging-auth/1",
        }
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url=url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                response_body = response.read()
                if not response_body:
                    return {}
                document = json.loads(response_body.decode("utf-8"))
        except urllib.error.HTTPError as error:
            raise StagingAuthError(f"MANAGEMENT_API_HTTP_{error.code}") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise StagingAuthError("MANAGEMENT_API_UNAVAILABLE") from None
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise StagingAuthError("MANAGEMENT_API_RESPONSE_INVALID") from None
        if not isinstance(document, dict):
            raise StagingAuthError("MANAGEMENT_API_RESPONSE_INVALID")
        return document


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def utc_text() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def validate_target_authority(
    *,
    environment: str,
    api_url: str,
    project_ref: str,
    production_project_ref: str,
    project_ref_sha256: str,
) -> TargetAuthority:
    require(environment == EXPECTED_ENVIRONMENT, "ENVIRONMENT_NOT_STAGING")
    require(PROJECT_REF_PATTERN.fullmatch(project_ref) is not None, "STAGING_PROJECT_REF_INVALID")
    require(
        PROJECT_REF_PATTERN.fullmatch(production_project_ref) is not None,
        "PRODUCTION_PROJECT_REF_INVALID",
    )
    require(project_ref != production_project_ref, "STAGING_PRODUCTION_PROJECTS_IDENTICAL")
    require(api_url == f"https://{project_ref}.supabase.co", "STAGING_API_URL_MISMATCH")
    require(
        SHA256_PATTERN.fullmatch(project_ref_sha256) is not None,
        "STAGING_PROJECT_REF_DIGEST_INVALID",
    )
    require(sha256_text(project_ref) == project_ref_sha256, "STAGING_PROJECT_REF_DIGEST_MISMATCH")
    return TargetAuthority(
        project_ref=project_ref,
        project_ref_sha256=project_ref_sha256,
        api_url=api_url,
    )


def require(condition: bool, code: str) -> None:
    if not condition:
        raise StagingAuthError(code)


def required_value(environment: Mapping[str, str], name: str) -> str:
    value = environment.get(name, "").strip()
    require(bool(value), f"{name}_MISSING")
    return value


def reversed_google_client_id(client_id: str) -> str:
    suffix = ".apps.googleusercontent.com"
    require(client_id.endswith(suffix), "GOOGLE_IOS_CLIENT_ID_INVALID")
    return "com.googleusercontent.apps." + client_id[: -len(suffix)]


def read_email_template(path: Path, *, label: str) -> str:
    try:
        require(path.is_file() and not path.is_symlink(), f"{label}_TEMPLATE_MISSING")
        require(path.stat().st_size <= 64 * 1024, f"{label}_TEMPLATE_TOO_LARGE")
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise StagingAuthError(f"{label}_TEMPLATE_UNREADABLE") from error
    require("{{ .Token }}" in content, f"{label}_TEMPLATE_TOKEN_MISSING")
    require("{{ .ConfirmationURL }}" not in content, f"{label}_TEMPLATE_LINK_FLOW_FORBIDDEN")
    return content


def build_desired_configuration(
    *,
    environment: Mapping[str, str],
    repository_root: Path,
    require_mutation_secrets: bool,
) -> DesiredAuthConfiguration:
    google_web_client_id = required_value(environment, "KWABOR_GOOGLE_WEB_CLIENT_ID")
    google_server_client_id = required_value(environment, "KWABOR_GOOGLE_SERVER_CLIENT_ID")
    google_ios_client_id = required_value(environment, "KWABOR_GOOGLE_IOS_CLIENT_ID")
    google_reversed_client_id = required_value(environment, "KWABOR_GOOGLE_REVERSED_CLIENT_ID")
    require(
        GOOGLE_CLIENT_ID_PATTERN.fullmatch(google_web_client_id) is not None,
        "GOOGLE_WEB_CLIENT_ID_INVALID",
    )
    require(
        GOOGLE_CLIENT_ID_PATTERN.fullmatch(google_ios_client_id) is not None,
        "GOOGLE_IOS_CLIENT_ID_INVALID",
    )
    require(google_server_client_id == google_web_client_id, "GOOGLE_SERVER_CLIENT_ID_MISMATCH")
    require(google_ios_client_id != google_web_client_id, "GOOGLE_IOS_WEB_CLIENT_IDS_IDENTICAL")
    require(
        google_reversed_client_id == reversed_google_client_id(google_ios_client_id),
        "GOOGLE_REVERSED_CLIENT_ID_MISMATCH",
    )

    smtp_admin_email = required_value(environment, "KWABOR_AUTH_SMTP_ADMIN_EMAIL")
    smtp_host = required_value(environment, "KWABOR_AUTH_SMTP_HOST")
    smtp_port = required_value(environment, "KWABOR_AUTH_SMTP_PORT")
    require(EMAIL_PATTERN.fullmatch(smtp_admin_email) is not None, "SMTP_ADMIN_EMAIL_INVALID")
    require(HOST_PATTERN.fullmatch(smtp_host) is not None, "SMTP_HOST_INVALID")
    parsed_smtp_port = int(smtp_port) if smtp_port.isdigit() else 0
    require(1 <= parsed_smtp_port <= 65535, "SMTP_PORT_INVALID")

    magic_link_template = read_email_template(
        repository_root / "supabase" / "templates" / "magic_link.html",
        label="MAGIC_LINK",
    )
    recovery_template = read_email_template(
        repository_root / "supabase" / "templates" / "recovery.html",
        label="RECOVERY",
    )
    write_only: dict[str, str] = {}
    if require_mutation_secrets:
        write_only = {
            "external_google_secret": required_value(environment, "KWABOR_GOOGLE_WEB_CLIENT_SECRET"),
            "smtp_user": required_value(environment, "KWABOR_AUTH_SMTP_USER"),
            "smtp_pass": required_value(environment, "KWABOR_AUTH_SMTP_PASSWORD"),
        }

    public: dict[str, Any] = {
        "site_url": EXPECTED_SITE_URL,
        "uri_allow_list": ",".join(EXPECTED_REDIRECT_URLS),
        "disable_signup": False,
        "external_anonymous_users_enabled": False,
        "external_email_enabled": True,
        "external_phone_enabled": False,
        "password_min_length": 8,
        "mailer_otp_length": 6,
        "mailer_otp_exp": 3600,
        "smtp_admin_email": smtp_admin_email,
        "smtp_host": smtp_host,
        "smtp_port": smtp_port,
        "smtp_max_frequency": 30,
        "smtp_sender_name": EXPECTED_SMTP_SENDER_NAME,
        "mailer_allow_unverified_email_sign_ins": False,
        # Kwabor's registration contract is email -> OTP -> password. Enabling
        # autoconfirm would skip the email-verification gate entirely.
        "mailer_autoconfirm": False,
        "mailer_secure_email_change_enabled": True,
        "security_captcha_enabled": False,
        "mailer_subjects_magic_link": "Votre code de vérification Kwabor",
        "mailer_subjects_recovery": "Réinitialisez votre mot de passe Kwabor",
        "external_google_enabled": True,
        "external_google_client_id": google_web_client_id,
        "external_google_additional_client_ids": google_ios_client_id,
        "external_google_skip_nonce_check": False,
        "external_google_email_optional": False,
        "external_apple_enabled": True,
        "external_apple_client_id": EXPECTED_APPLE_CLIENT_ID,
        "external_apple_additional_client_ids": "",
        "external_apple_email_optional": False,
    }
    templates = {
        "mailer_templates_magic_link_content": magic_link_template,
        "mailer_templates_recovery_content": recovery_template,
    }
    return DesiredAuthConfiguration(public=public, templates=templates, write_only=write_only)


def normalize_list(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    require(isinstance(value, str), "AUTH_CONFIG_LIST_FIELD_INVALID")
    items = tuple(part.strip() for part in value.split(",") if part.strip())
    require(len(items) == len(set(items)), "AUTH_CONFIG_LIST_FIELD_DUPLICATED")
    return tuple(sorted(items))


def normalized_value(field: str, value: Any) -> Any:
    if field in LIST_FIELDS:
        return normalize_list(value)
    return value


def template_metadata(content: Any) -> dict[str, Any]:
    if not isinstance(content, str):
        content = ""
    return {
        "configured": bool(content),
        "containsToken": "{{ .Token }}" in content,
        "sha256": sha256_text(content),
    }


def safe_snapshot(document: Mapping[str, Any]) -> dict[str, Any]:
    snapshot: dict[str, Any] = {}
    for field in PUBLIC_FIELDS:
        value = normalized_value(field, document.get(field))
        evidence_fields = SENSITIVE_PUBLIC_EVIDENCE_FIELDS.get(field)
        if evidence_fields is not None:
            configured_field, digest_field = evidence_fields
            text_value = value.strip() if isinstance(value, str) else ""
            snapshot[configured_field] = bool(text_value)
            snapshot[digest_field] = sha256_text(text_value)
            continue
        snapshot[field] = list(value) if isinstance(value, tuple) else value
    for field in TEMPLATE_FIELDS:
        snapshot[field] = template_metadata(document.get(field))
    for field in WRITE_ONLY_FIELDS:
        value = document.get(field)
        snapshot[WRITE_ONLY_EVIDENCE_FIELDS[field]] = isinstance(value, str) and bool(value.strip())
    apple_web_credential = document.get("external_apple_secret")
    snapshot["appleWebCredentialConfigured"] = (
        isinstance(apple_web_credential, str) and bool(apple_web_credential.strip())
    )
    snapshot["unexpectedExternalProvidersEnabled"] = unexpected_enabled_external_providers(document)
    return snapshot


def expected_snapshot(desired: DesiredAuthConfiguration) -> dict[str, Any]:
    expectation = safe_snapshot({**desired.public, **desired.templates})
    for field in WRITE_ONLY_FIELDS:
        expectation[WRITE_ONLY_EVIDENCE_FIELDS[field]] = True
    expectation["appleWebCredentialConfigured"] = False
    expectation["unexpectedExternalProvidersEnabled"] = []
    return expectation


def unexpected_enabled_external_providers(document: Mapping[str, Any]) -> list[str]:
    return sorted(
        field
        for field, value in document.items()
        if field.startswith("external_")
        and field.endswith("_enabled")
        and value is True
        and field not in ALLOWED_ENABLED_EXTERNAL_PROVIDERS
    )


def auth_config_drift(
    current: Mapping[str, Any],
    desired: DesiredAuthConfiguration,
) -> list[str]:
    drift: list[str] = []
    for field, expected in desired.public.items():
        if normalized_value(field, current.get(field)) != normalized_value(field, expected):
            drift.append(field)
    for field, expected in desired.templates.items():
        if current.get(field) != expected:
            drift.append(field)
    for field in WRITE_ONLY_FIELDS:
        current_value = current.get(field)
        if not isinstance(current_value, str) or not current_value.strip():
            drift.append(WRITE_ONLY_EVIDENCE_FIELDS[field])
    apple_web_credential = current.get("external_apple_secret")
    if isinstance(apple_web_credential, str) and apple_web_credential.strip():
        drift.append("appleWebCredentialMustBeAbsent")
    if unexpected_enabled_external_providers(current):
        drift.append("unexpectedExternalProvidersEnabled")
    return sorted(drift)


def configuration_fingerprint(desired: DesiredAuthConfiguration) -> str:
    encoded = json.dumps(
        expected_snapshot(desired),
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    return sha256_text(encoded)


def mutation_configuration_fingerprint(desired: DesiredAuthConfiguration) -> str:
    """Bind a reviewed plan to the exact public and write-only PATCH values.

    Only the one-way digest is archived. The aggregate includes multiple
    high-entropy provider credentials, so individual secret values are never
    exposed in GEL evidence.
    """

    encoded = json.dumps(
        desired.patch,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    return sha256_text(encoded)


def validate_plan_inputs(
    *,
    operation: str,
    plan_run_id: str,
    plan_artifact_id: str,
    plan_artifact_digest: str,
) -> dict[str, Any] | None:
    values = (plan_run_id, plan_artifact_id, plan_artifact_digest)
    if operation != "apply":
        require(not any(values), "PLAN_AUTHORITY_UNEXPECTED")
        return None
    require(POSITIVE_INTEGER_PATTERN.fullmatch(plan_run_id) is not None, "PLAN_RUN_ID_INVALID")
    require(
        POSITIVE_INTEGER_PATTERN.fullmatch(plan_artifact_id) is not None,
        "PLAN_ARTIFACT_ID_INVALID",
    )
    require(
        SHA256_PATTERN.fullmatch(plan_artifact_digest) is not None,
        "PLAN_ARTIFACT_DIGEST_INVALID",
    )
    return {
        "runId": int(plan_run_id),
        "artifactId": int(plan_artifact_id),
        "artifactDigest": plan_artifact_digest,
    }


def _load_json_object(path: Path, *, error_code: str) -> dict[str, Any]:
    try:
        require(path.is_file() and not path.is_symlink(), error_code)
        require(0 < path.stat().st_size <= MAX_EVIDENCE_BYTES, error_code)
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingAuthError(error_code) from error
    require(isinstance(document, dict), error_code)
    return document


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        raise StagingAuthError("PLAN_ARTIFACT_ARCHIVE_UNREADABLE") from error
    return digest.hexdigest()


def _parse_github_timestamp(value: object) -> datetime:
    require(isinstance(value, str) and value.endswith("Z"), "PLAN_ARTIFACT_TIMESTAMP_INVALID")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise StagingAuthError("PLAN_ARTIFACT_TIMESTAMP_INVALID") from error
    require(parsed.tzinfo is not None, "PLAN_ARTIFACT_TIMESTAMP_INVALID")
    return parsed.astimezone(timezone.utc)


def validate_plan_artifact_bundle(
    *,
    run_document_path: Path,
    artifact_document_path: Path,
    archive_path: Path,
    plan_run_id: int,
    plan_artifact_id: int,
    plan_artifact_digest: str,
    expected_sha: str,
    validated_ci_run_id: int,
    project_ref_sha256: str,
    desired: DesiredAuthConfiguration,
) -> dict[str, Any]:
    run_document = _load_json_object(run_document_path, error_code="PLAN_RUN_DOCUMENT_INVALID")
    artifact_document = _load_json_object(
        artifact_document_path,
        error_code="PLAN_ARTIFACT_DOCUMENT_INVALID",
    )
    require(run_document.get("id") == plan_run_id, "PLAN_RUN_ID_DRIFT")
    require(run_document.get("event") == "workflow_dispatch", "PLAN_RUN_EVENT_DRIFT")
    require(run_document.get("head_branch") == "main", "PLAN_RUN_BRANCH_DRIFT")
    require(run_document.get("head_sha") == expected_sha, "PLAN_RUN_SHA_DRIFT")
    require(run_document.get("path") == EXPECTED_WORKFLOW, "PLAN_RUN_WORKFLOW_DRIFT")
    require(run_document.get("status") == "completed", "PLAN_RUN_NOT_COMPLETED")
    require(run_document.get("conclusion") == "success", "PLAN_RUN_NOT_SUCCESSFUL")
    require(
        isinstance(run_document.get("repository"), dict)
        and run_document["repository"].get("full_name") == EXPECTED_REPOSITORY,
        "PLAN_RUN_REPOSITORY_DRIFT",
    )
    run_attempt = run_document.get("run_attempt")
    require(isinstance(run_attempt, int) and run_attempt >= 1, "PLAN_RUN_ATTEMPT_INVALID")
    expected_run_url = f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{plan_run_id}"
    require(run_document.get("html_url") == expected_run_url, "PLAN_RUN_URL_DRIFT")

    expected_artifact_name = (
        f"kwabor-gel-g5-staging-auth-plan-{expected_sha}-{run_attempt}"
    )
    require(artifact_document.get("id") == plan_artifact_id, "PLAN_ARTIFACT_ID_DRIFT")
    require(artifact_document.get("name") == expected_artifact_name, "PLAN_ARTIFACT_NAME_DRIFT")
    require(artifact_document.get("expired") is False, "PLAN_ARTIFACT_EXPIRED")
    require(
        artifact_document.get("digest") == f"sha256:{plan_artifact_digest}",
        "PLAN_ARTIFACT_DIGEST_DRIFT",
    )
    size_bytes = artifact_document.get("size_in_bytes")
    require(
        isinstance(size_bytes, int) and 0 < size_bytes <= MAX_PLAN_ARTIFACT_BYTES,
        "PLAN_ARTIFACT_SIZE_INVALID",
    )
    require(
        _parse_github_timestamp(artifact_document.get("expires_at")) > datetime.now(timezone.utc),
        "PLAN_ARTIFACT_EXPIRED",
    )
    workflow_run = artifact_document.get("workflow_run")
    require(isinstance(workflow_run, dict), "PLAN_ARTIFACT_RUN_MISSING")
    require(workflow_run.get("id") == plan_run_id, "PLAN_ARTIFACT_RUN_DRIFT")
    require(workflow_run.get("head_sha") == expected_sha, "PLAN_ARTIFACT_SHA_DRIFT")
    require(workflow_run.get("head_branch") == "main", "PLAN_ARTIFACT_BRANCH_DRIFT")

    require(archive_path.is_file() and not archive_path.is_symlink(), "PLAN_ARTIFACT_ARCHIVE_MISSING")
    require(
        archive_path.stat().st_size == size_bytes,
        "PLAN_ARTIFACT_ARCHIVE_SIZE_DRIFT",
    )
    require(_sha256_file(archive_path) == plan_artifact_digest, "PLAN_ARTIFACT_ARCHIVE_DIGEST_DRIFT")
    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            members = archive.infolist()
            require(len(members) == 1, "PLAN_ARTIFACT_ENTRY_COUNT_INVALID")
            member = members[0]
            require(
                member.filename == RECEIPT_NAME
                and not member.is_dir()
                and member.file_size <= MAX_EVIDENCE_BYTES
                and ((member.external_attr >> 16) & 0o170000) != 0o120000,
                "PLAN_ARTIFACT_ENTRY_INVALID",
            )
            raw_receipt = archive.read(RECEIPT_NAME)
    except StagingAuthError:
        raise
    except (OSError, KeyError, RuntimeError, zipfile.BadZipFile) as error:
        raise StagingAuthError("PLAN_ARTIFACT_ARCHIVE_INVALID") from error
    try:
        plan_receipt = json.loads(raw_receipt.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingAuthError("PLAN_RECEIPT_INVALID") from error
    require(isinstance(plan_receipt, dict), "PLAN_RECEIPT_INVALID")

    expected_receipt = {
        "schemaVersion": 1,
        "gate": "G5",
        "taskId": "B6.AUTH",
        "workflow": EXPECTED_WORKFLOW,
        "operation": "plan",
        "environment": EXPECTED_ENVIRONMENT,
        "repository": EXPECTED_REPOSITORY,
        "expectedSha": expected_sha,
        "validatedCiRunId": validated_ci_run_id,
        "runId": plan_run_id,
        "runAttempt": run_attempt,
        "runUrl": expected_run_url,
        "status": "succeeded",
        "gateDecision": "ready-to-apply",
        "mutationState": "not-requested",
        "executionDisposition": "PLANNED",
        "errorCode": None,
        "projectRefSha256": project_ref_sha256,
        "configurationFingerprint": configuration_fingerprint(desired),
        "mutationConfigurationFingerprint": mutation_configuration_fingerprint(desired),
        "expected": expected_snapshot(desired),
        "templateDigests": {
            field: sha256_text(content) for field, content in sorted(desired.templates.items())
        },
    }
    for field, value in expected_receipt.items():
        require(plan_receipt.get(field) == value, f"PLAN_RECEIPT_{field.upper()}_DRIFT")
    require(
        SHA256_PATTERN.fullmatch(str(plan_receipt.get("ciProvenanceSha256", ""))) is not None,
        "PLAN_RECEIPT_CI_DIGEST_INVALID",
    )
    current_before = plan_receipt.get("currentBefore")
    drift_fields = plan_receipt.get("driftFields")
    require(isinstance(current_before, dict), "PLAN_RECEIPT_CURRENT_INVALID")
    require(
        isinstance(drift_fields, list)
        and all(isinstance(field, str) for field in drift_fields)
        and drift_fields == sorted(set(drift_fields)),
        "PLAN_RECEIPT_DRIFT_FIELDS_INVALID",
    )
    return {
        "runId": plan_run_id,
        "runAttempt": run_attempt,
        "runUrl": expected_run_url,
        "artifactId": plan_artifact_id,
        "artifactName": expected_artifact_name,
        "artifactDigest": plan_artifact_digest,
        "artifactSizeBytes": size_bytes,
        "internalReceiptSha256": hashlib.sha256(raw_receipt).hexdigest(),
        "currentBefore": current_before,
        "currentBeforeSha256": sha256_text(
            json.dumps(current_before, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        ),
        "driftFields": drift_fields,
    }


def validate_ci_provenance(
    path: Path,
    *,
    expected_sha: str,
    validated_ci_run_id: str,
) -> str:
    try:
        require(path.is_file() and not path.is_symlink(), "CI_PROVENANCE_MISSING")
        require(path.stat().st_size <= 1024 * 1024, "CI_PROVENANCE_TOO_LARGE")
        raw = path.read_bytes()
        document = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingAuthError("CI_PROVENANCE_INVALID") from error
    require(isinstance(document, dict), "CI_PROVENANCE_INVALID")
    expected = {
        "schemaVersion": 1,
        "kind": "github-actions-run-provenance",
        "repository": EXPECTED_REPOSITORY,
        "headBranch": "main",
        "headSha": expected_sha,
        "runId": int(validated_ci_run_id),
        "workflow": EXPECTED_CI_WORKFLOW,
        "status": "completed",
        "conclusion": "success",
        "event": "push",
    }
    for field, value in expected.items():
        require(document.get(field) == value, f"CI_PROVENANCE_{field.upper()}_MISMATCH")
    return hashlib.sha256(raw).hexdigest()


def validate_github_context(expected_sha: str) -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    require(os.environ.get("GITHUB_REPOSITORY") == EXPECTED_REPOSITORY, "GITHUB_REPOSITORY_MISMATCH")
    require(os.environ.get("GITHUB_REF") == "refs/heads/main", "GITHUB_REF_NOT_MAIN")
    require(os.environ.get("GITHUB_SHA") == expected_sha, "GITHUB_SHA_MISMATCH")
    try:
        actual_sha = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError) as error:
        raise StagingAuthError("CHECKOUT_SHA_UNAVAILABLE") from error
    require(actual_sha == expected_sha, "CHECKOUT_SHA_MISMATCH")


def base_receipt(
    *,
    operation: str,
    expected_sha: str,
    validated_ci_run_id: str,
) -> dict[str, Any]:
    run_id = int(os.environ.get("GITHUB_RUN_ID", "0") or "0")
    return {
        "schemaVersion": 1,
        "gate": "G5",
        "taskId": "B6.AUTH",
        "workflow": EXPECTED_WORKFLOW,
        "operation": operation,
        "environment": EXPECTED_ENVIRONMENT,
        "repository": EXPECTED_REPOSITORY,
        "expectedSha": expected_sha,
        "validatedCiRunId": int(validated_ci_run_id) if validated_ci_run_id.isdigit() else 0,
        "actor": os.environ.get("GITHUB_ACTOR", "local-validation"),
        "runId": run_id,
        "runAttempt": int(os.environ.get("GITHUB_RUN_ATTEMPT", "0") or "0"),
        "runUrl": (
            f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}"
            if run_id > 0
            else None
        ),
        "createdAtUtc": utc_text(),
        "status": "failed",
        "gateDecision": "blocked",
        "mutationState": "not-started",
        "executionDisposition": "REFUSED",
        "errorCode": None,
        "projectRefSha256": None,
        "ciProvenanceSha256": None,
        "configurationFingerprint": None,
        "mutationConfigurationFingerprint": None,
        "planProof": None,
        "driftFields": [],
        "currentBefore": None,
        "currentAfter": None,
        "expected": None,
        "templateDigests": {},
    }


def assert_receipt_safe(receipt: Mapping[str, Any], secret_values: Sequence[str]) -> None:
    serialized = json.dumps(receipt, ensure_ascii=False, sort_keys=True)
    for value in secret_values:
        if len(value) >= 4 and value in serialized:
            raise StagingAuthError("SECRET_LEAK_IN_EVIDENCE")


def write_receipt(
    *,
    directory: Path,
    receipt: dict[str, Any],
    secret_values: Sequence[str],
) -> Path:
    assert_receipt_safe(receipt, secret_values)
    directory.mkdir(parents=True, exist_ok=True)
    require(not directory.is_symlink(), "EVIDENCE_DIRECTORY_SYMLINK_FORBIDDEN")
    output = directory / RECEIPT_NAME
    require(not output.is_symlink(), "EVIDENCE_OUTPUT_SYMLINK_FORBIDDEN")
    temporary = directory / f".{RECEIPT_NAME}.tmp"
    temporary.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(output)
    return output


def execute(args: argparse.Namespace) -> None:
    evidence_directory = Path(args.evidence_directory).resolve()
    receipt = base_receipt(
        operation=args.operation,
        expected_sha=args.expected_sha,
        validated_ci_run_id=args.validated_ci_run_id,
    )
    secret_values = [
        os.environ.get("SUPABASE_ACCESS_TOKEN", ""),
        os.environ.get("KWABOR_GOOGLE_WEB_CLIENT_SECRET", ""),
        os.environ.get("KWABOR_AUTH_SMTP_USER", ""),
        os.environ.get("KWABOR_AUTH_SMTP_PASSWORD", ""),
    ]
    mutation_started = False
    try:
        require(SHA_PATTERN.fullmatch(args.expected_sha) is not None, "EXPECTED_SHA_INVALID")
        require(
            POSITIVE_INTEGER_PATTERN.fullmatch(args.validated_ci_run_id) is not None,
            "VALIDATED_CI_RUN_ID_INVALID",
        )
        plan_authority = validate_plan_inputs(
            operation=args.operation,
            plan_run_id=args.validated_plan_run_id,
            plan_artifact_id=args.validated_plan_artifact_id,
            plan_artifact_digest=args.validated_plan_artifact_digest,
        )
        validate_github_context(args.expected_sha)
        authority = validate_target_authority(
            environment=os.environ.get("KWABOR_ENVIRONMENT", ""),
            api_url=os.environ.get("KWABOR_SUPABASE_URL", ""),
            project_ref=os.environ.get("KWABOR_SUPABASE_PROJECT_REF", ""),
            production_project_ref=os.environ.get("KWABOR_PRODUCTION_SUPABASE_PROJECT_REF", ""),
            project_ref_sha256=os.environ.get("KWABOR_STAGING_PROJECT_REF_SHA256", ""),
        )
        receipt["projectRefSha256"] = authority.project_ref_sha256
        receipt["ciProvenanceSha256"] = validate_ci_provenance(
            Path(args.ci_provenance),
            expected_sha=args.expected_sha,
            validated_ci_run_id=args.validated_ci_run_id,
        )
        desired = build_desired_configuration(
            environment=os.environ,
            repository_root=Path(args.repository_root).resolve(),
            require_mutation_secrets=args.operation in {"plan", "apply"},
        )
        receipt["configurationFingerprint"] = configuration_fingerprint(desired)
        receipt["mutationConfigurationFingerprint"] = (
            mutation_configuration_fingerprint(desired)
            if args.operation in {"plan", "apply"}
            else None
        )
        receipt["expected"] = expected_snapshot(desired)
        receipt["templateDigests"] = {
            field: sha256_text(content) for field, content in sorted(desired.templates.items())
        }
        if args.operation == "apply":
            require(args.apply_confirmation == APPLY_CONFIRMATION, "APPLY_CONFIRMATION_INVALID")
            require(plan_authority is not None, "PLAN_AUTHORITY_MISSING")
            current_run_id = int(os.environ.get("GITHUB_RUN_ID", "0") or "0")
            if current_run_id > 0:
                require(plan_authority["runId"] != current_run_id, "PLAN_RUN_IS_CURRENT_RUN")
            receipt["planProof"] = validate_plan_artifact_bundle(
                run_document_path=Path(args.plan_run_json),
                artifact_document_path=Path(args.plan_artifact_json),
                archive_path=Path(args.plan_artifact_zip),
                plan_run_id=plan_authority["runId"],
                plan_artifact_id=plan_authority["artifactId"],
                plan_artifact_digest=plan_authority["artifactDigest"],
                expected_sha=args.expected_sha,
                validated_ci_run_id=int(args.validated_ci_run_id),
                project_ref_sha256=authority.project_ref_sha256,
                desired=desired,
            )
        else:
            require(not args.apply_confirmation, "APPLY_CONFIRMATION_UNEXPECTED")

        client = ManagementApiClient(
            access_token=required_value(os.environ, "SUPABASE_ACCESS_TOKEN"),
            project_ref=authority.project_ref,
        )
        current_before = client.get_auth_config()
        receipt["currentBefore"] = safe_snapshot(current_before)
        receipt["driftFields"] = auth_config_drift(current_before, desired)
        if args.operation == "apply":
            require(receipt["planProof"] is not None, "PLAN_PROOF_MISSING")
            require(
                receipt["currentBefore"] == receipt["planProof"]["currentBefore"],
                "AUTH_CONFIG_CHANGED_SINCE_PLAN",
            )
            require(
                receipt["driftFields"] == receipt["planProof"]["driftFields"],
                "AUTH_CONFIG_DRIFT_CHANGED_SINCE_PLAN",
            )
        require(
            not (set(receipt["driftFields"]) & NON_PATCHABLE_DRIFT),
            "AUTH_CONFIG_NON_PATCHABLE_DRIFT",
        )

        if args.operation == "plan":
            receipt.update(
                status="succeeded",
                gateDecision="ready-to-apply",
                mutationState="not-requested",
                executionDisposition="PLANNED",
            )
        elif args.operation == "verify":
            require(not receipt["driftFields"], "AUTH_CONFIG_DRIFT")
            receipt["currentAfter"] = receipt["currentBefore"]
            receipt.update(
                status="succeeded",
                gateDecision="qualified",
                mutationState="not-requested",
                executionDisposition="VERIFIED",
            )
        else:
            mutation_started = True
            client.patch_auth_config(desired.patch)
            receipt["mutationState"] = "submitted"
            current_after = client.get_auth_config()
            receipt["currentAfter"] = safe_snapshot(current_after)
            receipt["driftFields"] = auth_config_drift(current_after, desired)
            require(not receipt["driftFields"], "POST_APPLY_AUTH_CONFIG_DRIFT")
            receipt.update(
                status="succeeded",
                gateDecision="qualified",
                mutationState="committed",
                executionDisposition="EXECUTED",
            )
    except StagingAuthError as error:
        receipt["errorCode"] = error.code
        if mutation_started:
            receipt["mutationState"] = "indeterminate"
            receipt["executionDisposition"] = "DO_NOT_RETRY_VERIFY_FIRST"
        write_receipt(
            directory=evidence_directory,
            receipt=receipt,
            secret_values=secret_values,
        )
        raise

    write_receipt(
        directory=evidence_directory,
        receipt=receipt,
        secret_values=secret_values,
    )
    print(f"OK staging Auth operation={args.operation} GEL={RECEIPT_NAME}")


def verify_receipt(args: argparse.Namespace) -> None:
    path = Path(args.receipt)
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingAuthError("GEL_RECEIPT_INVALID") from error
    require(isinstance(document, dict), "GEL_RECEIPT_INVALID")
    expected = {
        "schemaVersion": 1,
        "gate": "G5",
        "taskId": "B6.AUTH",
        "workflow": EXPECTED_WORKFLOW,
        "environment": EXPECTED_ENVIRONMENT,
        "repository": EXPECTED_REPOSITORY,
        "operation": args.operation,
        "expectedSha": args.expected_sha,
        "validatedCiRunId": int(args.validated_ci_run_id),
    }
    for field, value in expected.items():
        require(document.get(field) == value, f"GEL_{field.upper()}_MISMATCH")
    require(SHA256_PATTERN.fullmatch(str(document.get("projectRefSha256", ""))) is not None, "GEL_PROJECT_DIGEST_INVALID")
    require(SHA256_PATTERN.fullmatch(str(document.get("ciProvenanceSha256", ""))) is not None, "GEL_CI_DIGEST_INVALID")
    require(SHA256_PATTERN.fullmatch(str(document.get("configurationFingerprint", ""))) is not None, "GEL_CONFIG_DIGEST_INVALID")
    if args.operation in {"plan", "apply"}:
        require(
            SHA256_PATTERN.fullmatch(
                str(document.get("mutationConfigurationFingerprint", ""))
            )
            is not None,
            "GEL_MUTATION_CONFIG_DIGEST_INVALID",
        )
    else:
        require(
            document.get("mutationConfigurationFingerprint") is None,
            "GEL_MUTATION_CONFIG_DIGEST_UNEXPECTED",
        )
    if document.get("status") == "succeeded":
        require(document.get("errorCode") is None, "GEL_SUCCESS_HAS_ERROR")
        if args.operation == "plan":
            require(document.get("gateDecision") == "ready-to-apply", "GEL_PLAN_DECISION_INVALID")
            require(document.get("mutationState") == "not-requested", "GEL_PLAN_MUTATION_INVALID")
            require(document.get("planProof") is None, "GEL_PLAN_PROOF_UNEXPECTED")
        elif args.operation == "verify":
            require(document.get("gateDecision") == "qualified", "GEL_VERIFY_DECISION_INVALID")
            require(document.get("driftFields") == [], "GEL_VERIFY_DRIFT_INVALID")
        else:
            require(document.get("gateDecision") == "qualified", "GEL_APPLY_DECISION_INVALID")
            require(document.get("mutationState") == "committed", "GEL_APPLY_MUTATION_INVALID")
            require(document.get("driftFields") == [], "GEL_APPLY_DRIFT_INVALID")
            plan_proof = document.get("planProof")
            require(isinstance(plan_proof, dict), "GEL_APPLY_PLAN_PROOF_MISSING")
            require(
                POSITIVE_INTEGER_PATTERN.fullmatch(str(plan_proof.get("runId", ""))) is not None
                and POSITIVE_INTEGER_PATTERN.fullmatch(
                    str(plan_proof.get("artifactId", ""))
                )
                is not None
                and SHA256_PATTERN.fullmatch(
                    str(plan_proof.get("artifactDigest", ""))
                )
                is not None
                and SHA256_PATTERN.fullmatch(
                    str(plan_proof.get("internalReceiptSha256", ""))
                )
                is not None,
                "GEL_APPLY_PLAN_PROOF_INVALID",
            )
    else:
        require(document.get("gateDecision") == "blocked", "GEL_FAILURE_DECISION_INVALID")
        require(isinstance(document.get("errorCode"), str), "GEL_FAILURE_CODE_MISSING")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    execute_parser = subparsers.add_parser("execute")
    execute_parser.add_argument("--operation", choices=("plan", "apply", "verify"), required=True)
    execute_parser.add_argument("--expected-sha", required=True)
    execute_parser.add_argument("--validated-ci-run-id", required=True)
    execute_parser.add_argument("--apply-confirmation", default="")
    execute_parser.add_argument("--validated-plan-run-id", default="")
    execute_parser.add_argument("--validated-plan-artifact-id", default="")
    execute_parser.add_argument("--validated-plan-artifact-digest", default="")
    execute_parser.add_argument("--ci-provenance", required=True)
    execute_parser.add_argument("--plan-run-json", default="")
    execute_parser.add_argument("--plan-artifact-json", default="")
    execute_parser.add_argument("--plan-artifact-zip", default="")
    execute_parser.add_argument("--repository-root", default=".")
    execute_parser.add_argument("--evidence-directory", required=True)
    execute_parser.set_defaults(handler=execute)

    verify_parser = subparsers.add_parser("verify-receipt")
    verify_parser.add_argument("--receipt", required=True)
    verify_parser.add_argument("--operation", choices=("plan", "apply", "verify"), required=True)
    verify_parser.add_argument("--expected-sha", required=True)
    verify_parser.add_argument("--validated-ci-run-id", required=True)
    verify_parser.set_defaults(handler=verify_receipt)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        args.handler(args)
    except StagingAuthError as error:
        print(f"ERROR closed-beta staging Auth: {error.code}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
