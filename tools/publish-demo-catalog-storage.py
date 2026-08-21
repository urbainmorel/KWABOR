#!/usr/bin/env python3
"""Publish, verify, or roll back the immutable closed-beta media on staging."""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from email.message import Message
from pathlib import Path, PurePosixPath
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = REPOSITORY_ROOT / "demo" / "catalog" / "v1" / "manifest.json"
MEDIA_ROOT = REPOSITORY_ROOT / "demo" / "catalog" / "v1" / "media"
EXPECTED_BUCKET = "kwabor-catalog-demo"
EXPECTED_MEDIA_COUNT = 180
EXPECTED_CONTENT_TYPE = "image/jpeg"
EXPECTED_CACHE_CONTROL = "public,max-age=31536000,immutable"
EXPECTED_FILE_SIZE_LIMIT = 512 * 1024
STORAGE_REMOVE_BATCH_LIMIT = 1_000
MUTATING_METHODS = frozenset({"DELETE", "PATCH", "POST", "PUT"})
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
PROJECT_HOST_PATTERN = re.compile(r"^(?P<ref>[a-z0-9]{20})\.supabase\.co$")


class PublicationError(RuntimeError):
    """Raised when a publication invariant or Storage request fails."""


class StorageRequestUncertain(PublicationError):
    """Raised when a request cannot prove the remote state safely."""


class StorageMutationConflict(PublicationError):
    """Raised when a mutation response contradicts the exact requested target set."""


@dataclass(frozen=True)
class MediaObject:
    path: str
    sha256: str
    byte_size: int
    local_path: Path


@dataclass(frozen=True)
class CatalogContract:
    bucket: str
    cache_control: str
    objects: tuple[MediaObject, ...]


@dataclass(frozen=True)
class StorageResponse:
    body: bytes
    headers: Message


def _validate_upload_acknowledgement(
    response: StorageResponse,
    bucket: str,
    path: str,
) -> None:
    try:
        acknowledgement = json.loads(response.body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StorageRequestUncertain(
            "Storage POST returned an unreadable JSON upload acknowledgement; "
            "outcome is uncertain"
        ) from error
    expected_key = f"{bucket}/{path}"
    if (
        not isinstance(acknowledgement, dict)
        or acknowledgement.get("Key") != expected_key
    ):
        raise StorageRequestUncertain(
            "Storage POST upload acknowledgement was not bound to the exact "
            "bucket/path; outcome is uncertain"
        )


@dataclass(frozen=True)
class DownloadedObject:
    body: bytes
    content_type: str | None
    cache_control: str | None
    content_length: int | None


class StorageClient:
    def __init__(self, supabase_url: str, service_key: str, timeout: float) -> None:
        self._base_url = supabase_url.rstrip("/")
        self._service_key = service_key
        self._timeout = timeout
        self._ssl_context = ssl.create_default_context()

    def get_bucket(self, bucket: str) -> dict[str, Any] | None:
        try:
            return self._request_json("GET", f"bucket/{_quote_segment(bucket)}")
        except PublicationError as error:
            if "HTTP 404" in str(error):
                return None
            raise

    def create_bucket(self, bucket: str) -> None:
        self._request_json(
            "POST",
            "bucket",
            {
                "id": bucket,
                "name": bucket,
                "public": True,
                "file_size_limit": EXPECTED_FILE_SIZE_LIMIT,
                "allowed_mime_types": [EXPECTED_CONTENT_TYPE],
            },
        )

    def upload(self, bucket: str, media: MediaObject, cache_control: str) -> None:
        payload = media.local_path.read_bytes()
        if len(payload) != media.byte_size:
            raise PublicationError(f"Local size changed before upload: {media.path}")
        if hashlib.sha256(payload).hexdigest() != media.sha256:
            raise PublicationError(f"Local SHA-256 changed before upload: {media.path}")
        response = self._request(
            "POST",
            f"object/{_quote_segment(bucket)}/{_quote_path(media.path)}",
            payload,
            {
                "Content-Type": EXPECTED_CONTENT_TYPE,
                "cache-control": cache_control,
                "x-upsert": "false",
            },
        )
        _validate_upload_acknowledgement(response, bucket, media.path)

    def download_public(self, bucket: str, path: str) -> DownloadedObject:
        response = self._request(
            "GET",
            f"object/public/{_quote_segment(bucket)}/{_quote_path(path)}",
            authenticated=False,
        )
        content_length_header = response.headers.get("Content-Length")
        try:
            content_length = (
                None
                if content_length_header is None
                else int(content_length_header)
            )
        except ValueError as error:
            raise PublicationError(
                f"Storage returned an invalid Content-Length for {path}"
            ) from error
        return DownloadedObject(
            body=response.body,
            content_type=response.headers.get("Content-Type"),
            cache_control=response.headers.get("Cache-Control"),
            content_length=content_length,
        )

    def object_exists(self, bucket: str, path: str) -> bool:
        try:
            self._request(
                "HEAD",
                f"object/{_quote_segment(bucket)}/{_quote_path(path)}",
            )
            return True
        except PublicationError as error:
            if "HTTP 404" in str(error):
                return False
            raise

    def remove_exact(self, bucket: str, paths: list[str]) -> tuple[str, ...]:
        _require(paths, "Storage deletion requires at least one exact path")
        _require(
            len(paths) <= STORAGE_REMOVE_BATCH_LIMIT,
            f"Storage deletion exceeds the {STORAGE_REMOVE_BATCH_LIMIT}-object API limit",
        )
        _require(len(paths) == len(set(paths)), "Storage deletion paths are duplicated")
        result = self._request_json(
            "DELETE",
            f"object/{_quote_segment(bucket)}",
            {"prefixes": paths},
        )
        if not isinstance(result, list):
            raise StorageRequestUncertain(
                "Storage DELETE response did not prove an exact committed target set"
            )
        deleted_paths: list[str] = []
        for item in result:
            if not isinstance(item, dict):
                raise StorageRequestUncertain(
                    "Storage DELETE response contained unreadable object metadata"
                )
            deleted_path = item.get("name")
            if not isinstance(deleted_path, str):
                raise StorageRequestUncertain(
                    "Storage DELETE response omitted an exact object name"
                )
            deleted_paths.append(deleted_path)
        if len(deleted_paths) != len(set(deleted_paths)):
            raise StorageMutationConflict(
                "Storage DELETE response contains duplicate object names"
            )
        if set(deleted_paths) != set(paths):
            raise StorageMutationConflict(
                "Storage DELETE response does not match the exact requested paths"
            )
        return tuple(deleted_paths)

    def _request_json(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
    ) -> Any:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {} if payload is None else {"Content-Type": "application/json"}
        response = self._request(method, path, body, headers)
        if not response.body:
            return None
        try:
            return json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            if method.upper() in MUTATING_METHODS:
                raise StorageRequestUncertain(
                    f"Storage returned invalid JSON for {method} {path}; outcome is uncertain"
                ) from error
            raise PublicationError(
                f"Storage returned invalid JSON for {method} {path}"
            ) from error

    def _request(
        self,
        method: str,
        path: str,
        payload: bytes | None = None,
        extra_headers: dict[str, str] | None = None,
        *,
        authenticated: bool = True,
    ) -> StorageResponse:
        headers = {
            "User-Agent": "kwabor-closed-beta-storage/1",
            **(extra_headers or {}),
        }
        if authenticated:
            headers["Authorization"] = f"Bearer {self._service_key}"
            headers["apikey"] = self._service_key
        request = urllib.request.Request(
            f"{self._base_url}/storage/v1/{path}",
            data=payload,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(
                request,
                timeout=self._timeout,
                context=self._ssl_context,
            ) as response:
                return StorageResponse(response.read(), response.headers)
        except urllib.error.HTTPError as error:
            if method.upper() in MUTATING_METHODS and 500 <= error.code <= 599:
                error.close()
                raise StorageRequestUncertain(
                    f"Storage {method} returned HTTP {error.code}; server outcome is uncertain"
                ) from error
            detail = error.read(4096).decode("utf-8", errors="replace")
            error.close()
            raise PublicationError(
                f"Storage {method} failed with HTTP {error.code}: {detail}"
            ) from error
        except (OSError, http.client.HTTPException) as error:
            raise StorageRequestUncertain(
                f"Storage {method} response was not received; server outcome is uncertain"
            ) from error


def _quote_segment(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def _quote_path(value: str) -> str:
    return "/".join(_quote_segment(part) for part in PurePosixPath(value).parts)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise PublicationError(message)


def _load_contract(manifest_path: Path) -> CatalogContract:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PublicationError(f"Cannot read catalog manifest: {manifest_path}") from error

    _require(manifest.get("environment") == "staging-only", "Manifest is not staging-only")
    rights_approval = manifest.get("mediaRightsApproval")
    _require(
        isinstance(rights_approval, dict)
        and rights_approval.get("status") == "approved-by-product-owner"
        and rights_approval.get("approvedBy") == "Kwabor product owner"
        and rights_approval.get("scope") == "closed-beta-demo-only",
        "Demo media rights are not approved for the closed beta",
    )
    _require(
        manifest.get("counts", {}).get("media") == EXPECTED_MEDIA_COUNT,
        "Manifest media count is not 180",
    )
    storage = manifest.get("storage")
    _require(isinstance(storage, dict), "Manifest Storage contract is missing")
    _require(storage.get("bucket") == EXPECTED_BUCKET, "Unexpected Storage bucket")
    _require(storage.get("publicRead") is True, "Demo bucket must be public-read")
    _require(storage.get("clientWrites") is False, "Demo bucket must reject client writes")
    _require(storage.get("contentType") == EXPECTED_CONTENT_TYPE, "Unexpected media MIME type")
    _require(storage.get("upsert") is False, "Demo publication must be immutable")
    cache_control = storage.get("cacheControl")
    _require(cache_control == EXPECTED_CACHE_CONTROL, "Unexpected cache-control contract")

    objects: list[MediaObject] = []
    seen_paths: set[str] = set()
    for listing in manifest.get("listings", []):
        listing_id = listing.get("id")
        for media in listing.get("media", []):
            path = media.get("storagePath")
            digest = media.get("sha256")
            byte_size = media.get("byteSize")
            _require(media.get("reviewStatus") == "approved", f"Media review is incomplete: {path}")
            _require(
                media.get("rightsApprovalStatus") == rights_approval["status"],
                f"Media rights approval is incomplete: {path}",
            )
            _require(isinstance(path, str), f"Missing media path for {listing_id}")
            _require(PurePosixPath(path).as_posix() == path, f"Non-canonical media path: {path}")
            _require(path.startswith(f"v1/{listing_id}/"), f"Media escapes listing path: {path}")
            _require(
                path.endswith(".jpg") and ".." not in PurePosixPath(path).parts,
                f"Unsafe media path: {path}",
            )
            _require(path not in seen_paths, f"Duplicate media path: {path}")
            _require(
                isinstance(digest, str)
                and SHA256_PATTERN.fullmatch(digest) is not None,
                f"Invalid SHA-256: {path}",
            )
            _require(
                digest[:12] in PurePosixPath(path).name,
                f"Path does not embed SHA-256: {path}",
            )
            _require(
                type(byte_size) is int
                and 0 < byte_size <= EXPECTED_FILE_SIZE_LIMIT,
                f"Invalid byte size: {path}",
            )
            local_path = (MEDIA_ROOT / path).resolve()
            _require(
                local_path.is_relative_to(MEDIA_ROOT.resolve()),
                f"Media escapes local root: {path}",
            )
            _require(local_path.is_file(), f"Missing local media: {path}")
            seen_paths.add(path)
            objects.append(MediaObject(path, digest, byte_size, local_path))

    _require(len(objects) == EXPECTED_MEDIA_COUNT, "Manifest does not resolve to 180 unique media")
    return CatalogContract(
        EXPECTED_BUCKET,
        cache_control,
        tuple(sorted(objects, key=lambda item: item.path)),
    )


def _validate_staging_target(supabase_url: str, expected_project_ref: str) -> str:
    _require(
        os.environ.get("KWABOR_ENVIRONMENT") == "staging",
        "KWABOR_ENVIRONMENT must equal staging",
    )
    parsed = urllib.parse.urlsplit(supabase_url)
    _require(
        parsed.scheme == "https" and parsed.path in ("", "/"),
        "Supabase URL must be an HTTPS project root",
    )
    _require(
        parsed.query == ""
        and parsed.fragment == ""
        and parsed.username is None
        and parsed.port is None,
        "Supabase URL contains unsupported components",
    )
    host_match = PROJECT_HOST_PATTERN.fullmatch((parsed.hostname or "").lower())
    _require(host_match is not None, "Supabase URL must use the canonical project host")
    project_ref = host_match.group("ref")
    _require(
        expected_project_ref == project_ref,
        "Supabase URL does not match KWABOR_SUPABASE_PROJECT_REF",
    )
    production_ref = os.environ.get("KWABOR_PRODUCTION_SUPABASE_PROJECT_REF", "").strip()
    _require(
        PROJECT_HOST_PATTERN.fullmatch(f"{production_ref}.supabase.co") is not None,
        "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF is required and invalid",
    )
    _require(production_ref != project_ref, "Staging project ref equals production")
    expected_staging_ref_digest = os.environ.get(
        "KWABOR_STAGING_PROJECT_REF_SHA256",
        "",
    ).strip()
    _require(
        SHA256_PATTERN.fullmatch(expected_staging_ref_digest) is not None,
        "KWABOR_STAGING_PROJECT_REF_SHA256 is required and invalid",
    )
    actual_staging_ref_digest = hashlib.sha256(project_ref.encode("ascii")).hexdigest()
    _require(
        actual_staging_ref_digest == expected_staging_ref_digest,
        "Staging project ref does not match its protected SHA-256 identity",
    )
    return parsed.geturl().rstrip("/")


def _assert_bucket_contract(bucket: dict[str, Any]) -> None:
    allowed_mime_types = bucket.get("allowed_mime_types", bucket.get("allowedMimeTypes"))
    file_size_limit = bucket.get("file_size_limit", bucket.get("fileSizeLimit"))
    _require(bucket.get("id") == EXPECTED_BUCKET, "Existing bucket has an unexpected id")
    _require(bucket.get("name") == EXPECTED_BUCKET, "Existing bucket has an unexpected name")
    _require(bucket.get("public") is True, "Existing demo bucket is not public")
    _require(
        allowed_mime_types == [EXPECTED_CONTENT_TYPE],
        "Existing bucket MIME allowlist differs",
    )
    _require(
        str(file_size_limit) == str(EXPECTED_FILE_SIZE_LIMIT),
        "Existing bucket size limit differs",
    )


def _cache_control_directives(value: str | None) -> tuple[str, ...]:
    if value is None:
        return ()
    return tuple(
        sorted(
            directive.strip().lower()
            for directive in value.split(",")
            if directive.strip()
        )
    )


def _download_verified_bytes(
    client: StorageClient,
    contract: CatalogContract,
    media: MediaObject,
) -> DownloadedObject:
    downloaded = client.download_public(contract.bucket, media.path)
    _require(len(downloaded.body) == media.byte_size, f"Downloaded size mismatch: {media.path}")
    _require(
        hashlib.sha256(downloaded.body).hexdigest() == media.sha256,
        f"Downloaded SHA-256 mismatch: {media.path}",
    )
    return downloaded


def _verify_object(client: StorageClient, contract: CatalogContract, media: MediaObject) -> None:
    downloaded = _download_verified_bytes(client, contract, media)
    _require(
        downloaded.content_length in (None, media.byte_size),
        f"Downloaded Content-Length mismatch: {media.path}",
    )
    content_type = (downloaded.content_type or "").split(";", maxsplit=1)[0].strip().lower()
    _require(
        content_type == EXPECTED_CONTENT_TYPE,
        f"Downloaded Content-Type mismatch: {media.path}",
    )
    _require(
        _cache_control_directives(downloaded.cache_control)
        == _cache_control_directives(contract.cache_control),
        f"Downloaded Cache-Control mismatch: {media.path}",
    )


def _delete_exact_batches(
    client: StorageClient,
    contract: CatalogContract,
    paths: list[str],
) -> tuple[str, ...]:
    expected_by_path = {media.path: media for media in contract.objects}
    _require(
        set(paths).issubset(expected_by_path),
        "Storage deletion contains a path outside the exact manifest",
    )
    deleted: list[str] = []
    for offset in range(0, len(paths), STORAGE_REMOVE_BATCH_LIMIT):
        batch = paths[offset : offset + STORAGE_REMOVE_BATCH_LIMIT]
        try:
            deleted.extend(client.remove_exact(contract.bucket, batch))
        except (StorageRequestUncertain, StorageMutationConflict) as deletion_error:
            remaining: list[str] = []
            for path in batch:
                if not client.object_exists(contract.bucket, path):
                    continue
                try:
                    _verify_object(client, contract, expected_by_path[path])
                except PublicationError as conflict_error:
                    raise PublicationError(
                        "Storage DELETE reconciliation found a conflicting exact path; "
                        f"no further deletion was attempted: {path}"
                    ) from conflict_error
                remaining.append(path)
            if isinstance(deletion_error, StorageMutationConflict):
                raise PublicationError(
                    "Storage DELETE response contradicted the requested exact paths; "
                    "reconciliation completed without retry"
                ) from deletion_error
            if remaining:
                raise PublicationError(
                    "Storage DELETE outcome is uncertain and reconciliation found "
                    f"{len(remaining)} exact expected object(s) still present: {remaining[0]}"
                ) from deletion_error
            deleted.extend(batch)
    _require(
        len(deleted) == len(paths) and set(deleted) == set(paths),
        "Exact-path rollback confirmation is incomplete",
    )
    for path in paths:
        _require(
            not client.object_exists(contract.bucket, path),
            f"Rollback left object metadata present: {path}",
        )
    return tuple(deleted)


def _publish(client: StorageClient, contract: CatalogContract) -> dict[str, Any]:
    bucket = client.get_bucket(contract.bucket)
    if bucket is None:
        try:
            client.create_bucket(contract.bucket)
        except StorageRequestUncertain as creation_error:
            bucket = client.get_bucket(contract.bucket)
            if bucket is None:
                raise PublicationError(
                    "Storage bucket creation outcome was uncertain and reconciliation "
                    "proved the bucket absent"
                ) from creation_error
            try:
                _assert_bucket_contract(bucket)
            except PublicationError as conflict_error:
                raise PublicationError(
                    "Storage bucket creation outcome was uncertain and reconciliation "
                    "found a conflicting bucket; no cleanup was attempted"
                ) from conflict_error
        bucket = client.get_bucket(contract.bucket)
    _require(bucket is not None, "Demo bucket is unavailable after creation")
    _assert_bucket_contract(bucket)
    collisions = [
        media.path
        for media in contract.objects
        if client.object_exists(contract.bucket, media.path)
    ]
    if collisions:
        raise PublicationError(
            f"Refusing to overwrite {len(collisions)} existing manifest object(s): {collisions[0]}"
        )

    verified_created: list[str] = []
    try:
        for index, media in enumerate(contract.objects, start=1):
            try:
                client.upload(contract.bucket, media, contract.cache_control)
            except StorageRequestUncertain as upload_error:
                if not client.object_exists(contract.bucket, media.path):
                    raise PublicationError(
                        "Storage upload outcome was uncertain and exact-path "
                        "reconciliation proved the object absent; the path was not "
                        "scheduled for deletion"
                    ) from upload_error
                try:
                    _verify_object(client, contract, media)
                except PublicationError as conflict_error:
                    raise PublicationError(
                        "Storage upload outcome was uncertain and exact-path "
                        "reconciliation found conflicting bytes or metadata; "
                        "the path was not scheduled for deletion: "
                        f"{conflict_error}"
                    ) from conflict_error
                verified_created.append(media.path)
                raise PublicationError(
                    "Storage upload outcome was uncertain; exact-path reconciliation "
                    "classified the exact object as committed for compensating rollback"
                ) from upload_error
            _verify_object(client, contract, media)
            verified_created.append(media.path)
            print(f"published-and-verified {index}/{len(contract.objects)} {media.path}")
    except Exception as publication_error:
        if verified_created:
            try:
                _delete_exact_batches(
                    client,
                    contract,
                    list(reversed(verified_created)),
                )
            except Exception as rollback_error:
                raise PublicationError(
                    "Publication failed and its exact-path compensating rollback also failed: "
                    f"{rollback_error}"
                ) from publication_error
        raise
    return _operation_result(
        "publish",
        "published-and-verified",
        manifest_objects=len(contract.objects),
        created_objects=len(verified_created),
        verified_objects=len(verified_created),
    )


def _verify(client: StorageClient, contract: CatalogContract) -> dict[str, Any]:
    bucket = client.get_bucket(contract.bucket)
    _require(bucket is not None, "Demo bucket does not exist")
    _assert_bucket_contract(bucket)
    for index, media in enumerate(contract.objects, start=1):
        _verify_object(client, contract, media)
        print(f"verified {index}/{len(contract.objects)} {media.path}")
    return _operation_result(
        "verify",
        "verified",
        manifest_objects=len(contract.objects),
        verified_objects=len(contract.objects),
    )


def _rollback(
    client: StorageClient,
    contract: CatalogContract,
    confirmation: str | None,
) -> dict[str, Any]:
    _require(confirmation == "DELETE-EXACT-DEMO-MANIFEST", "Rollback confirmation is missing")
    bucket = client.get_bucket(contract.bucket)
    _require(bucket is not None, "Demo bucket does not exist")
    _assert_bucket_contract(bucket)
    verified_existing: list[MediaObject] = []
    already_absent = 0
    for media in contract.objects:
        if client.object_exists(contract.bucket, media.path):
            _verify_object(client, contract, media)
            verified_existing.append(media)
        else:
            already_absent += 1
    paths = [media.path for media in reversed(verified_existing)]
    deleted = _delete_exact_batches(client, contract, paths)
    print(
        f"rolled-back {len(deleted)} exact verified manifest paths; "
        f"{already_absent} already absent; bucket retained"
    )
    return _operation_result(
        "rollback",
        "rolled-back-exact-manifest",
        manifest_objects=len(contract.objects),
        verified_objects=len(verified_existing),
        deleted_objects=len(deleted),
        already_absent_objects=already_absent,
    )


def _operation_result(
    operation: str,
    mode: str,
    *,
    manifest_objects: int,
    created_objects: int = 0,
    verified_objects: int = 0,
    deleted_objects: int = 0,
    already_absent_objects: int = 0,
) -> dict[str, Any]:
    return {
        "counts": {
            "alreadyAbsentObjects": already_absent_objects,
            "createdObjects": created_objects,
            "deletedObjects": deleted_objects,
            "manifestObjects": manifest_objects,
            "verifiedObjects": verified_objects,
        },
        "kind": "demo-storage-operation",
        "mode": mode,
        "operation": operation,
        "outcome": "succeeded",
        "schemaVersion": 1,
    }


def _write_result(path: Path, result: dict[str, Any]) -> None:
    _require(not path.is_symlink(), "Storage result output must not be a symbolic link")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("publish", "verify", "rollback"))
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--confirm-rollback")
    parser.add_argument("--result-json", type=Path)
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    _require(args.timeout > 0, "Timeout must be positive")
    supabase_url = os.environ.get("KWABOR_SUPABASE_URL", "").strip()
    project_ref = os.environ.get("KWABOR_SUPABASE_PROJECT_REF", "").strip()
    service_key = os.environ.get("KWABOR_SUPABASE_SERVICE_ROLE_KEY", "").strip()
    _require(supabase_url != "", "KWABOR_SUPABASE_URL is required")
    _require(
        PROJECT_HOST_PATTERN.fullmatch(f"{project_ref}.supabase.co") is not None,
        "KWABOR_SUPABASE_PROJECT_REF is invalid",
    )
    _require(service_key != "", "KWABOR_SUPABASE_SERVICE_ROLE_KEY is required")
    safe_url = _validate_staging_target(supabase_url, project_ref)
    contract = _load_contract(args.manifest.resolve())
    client = StorageClient(safe_url, service_key, args.timeout)
    if args.command == "publish":
        result = _publish(client, contract)
    elif args.command == "verify":
        result = _verify(client, contract)
    else:
        result = _rollback(client, contract, args.confirm_rollback)
    if args.result_json is not None:
        _write_result(args.result_json, result)


if __name__ == "__main__":
    try:
        main()
    except PublicationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
