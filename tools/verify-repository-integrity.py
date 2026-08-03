#!/usr/bin/env python3
"""Verify critical Kwabor repository configuration and release invariants."""

from __future__ import annotations

import hashlib
import plistlib
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path, PurePosixPath


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
TIERS = ("DEVELOPMENT", "STAGING", "PRODUCTION")
IOS_PRIVACY_MANIFEST_PATH = "iosApp/Kwabor/Resources/PrivacyInfo.xcprivacy"
IOS_USER_DEFAULTS_API_TYPE = "NSPrivacyAccessedAPICategoryUserDefaults"

# Audited against Apple's approved reasons for required-reason APIs.
# https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons
IOS_USER_DEFAULTS_REASON = "CA92.1"

# Audited against https://gradle.org/release-checksums/.
GRADLE_DISTRIBUTION_URL = (
    "https\\://services.gradle.org/distributions/gradle-9.4.1-bin.zip"
)
GRADLE_DISTRIBUTION_SHA256 = (
    "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
)
GRADLE_WRAPPER_JAR_SHA256 = (
    "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"
)
GRADLE_WRAPPER_SCRIPT_SHA256 = {
    "gradlew": "aed171fb114f82e6eaea4970a245a200e0582a7dcc8ec0891ca41b6e4a62b754",
    "gradlew.bat": "9ca26d733ada3a45f27b2151288f54e75c9f95b287d1f82ef942ec5cc2d4f006",
}
EXPECTED_GRADLE_WRAPPER_PROPERTIES = {
    "distributionBase": "GRADLE_USER_HOME",
    "distributionPath": "wrapper/dists",
    "distributionSha256Sum": GRADLE_DISTRIBUTION_SHA256,
    "distributionUrl": GRADLE_DISTRIBUTION_URL,
    "networkTimeout": "10000",
    "validateDistributionUrl": "true",
    "zipStoreBase": "GRADLE_USER_HOME",
    "zipStorePath": "wrapper/dists",
}

ENV_KEYS = {
    "KWABOR_ENVIRONMENT",
    "KWABOR_SUPABASE_URL",
    "KWABOR_SUPABASE_PUBLISHABLE_KEY",
    "KWABOR_GOOGLE_WEB_CLIENT_ID",
    "KWABOR_GOOGLE_IOS_CLIENT_ID",
    "KWABOR_GOOGLE_SERVER_CLIENT_ID",
    "KWABOR_GOOGLE_REVERSED_CLIENT_ID",
    "KWABOR_FIREBASE_PROJECT_ID",
    "KWABOR_VERSION_CODE",
    "KWABOR_VERSION_NAME",
    "KWABOR_ANDROID_KEYSTORE_BASE64",
    "KWABOR_ANDROID_KEYSTORE_PATH",
    "KWABOR_ANDROID_KEYSTORE_PASSWORD",
    "KWABOR_ANDROID_KEY_ALIAS",
    "KWABOR_ANDROID_KEY_PASSWORD",
    "KWABOR_IOS_DEVELOPMENT_TEAM",
    "KWABOR_IOS_DISTRIBUTION_CERTIFICATE_BASE64",
    "KWABOR_IOS_DISTRIBUTION_CERTIFICATE_PASSWORD",
    "KWABOR_IOS_PROVISIONING_PROFILE_BASE64",
    "KWABOR_FIREBASE_ANDROID_CONFIG_BASE64",
    "KWABOR_FIREBASE_IOS_CONFIG_BASE64",
}
for tier in TIERS:
    ENV_KEYS.update(
        {
            f"KWABOR_SUPABASE_URL_{tier}",
            f"KWABOR_SUPABASE_PUBLISHABLE_KEY_{tier}",
            f"KWABOR_GOOGLE_WEB_CLIENT_ID_{tier}",
        }
    )

BLANK_ENV_KEYS = ENV_KEYS - {
    "KWABOR_ENVIRONMENT",
    "KWABOR_VERSION_CODE",
    "KWABOR_VERSION_NAME",
}

LOCAL_PROPERTIES_KEYS = {
    "kwabor.environment",
    "kwabor.supabase.url",
    "kwabor.supabase.publishableKey",
    "kwabor.google.webClientId",
    "kwabor.versionCode",
    "kwabor.versionName",
    "kwabor.android.signing.storePath",
    "kwabor.android.signing.storePassword",
    "kwabor.android.signing.keyAlias",
    "kwabor.android.signing.keyPassword",
}
for tier in (tier.lower() for tier in TIERS):
    LOCAL_PROPERTIES_KEYS.update(
        {
            f"kwabor.{tier}.supabase.url",
            f"kwabor.{tier}.supabase.publishableKey",
            f"kwabor.{tier}.google.webClientId",
        }
    )
BLANK_LOCAL_PROPERTIES_KEYS = LOCAL_PROPERTIES_KEYS - {
    "kwabor.environment",
    "kwabor.versionCode",
    "kwabor.versionName",
}

LOCAL_XCCONFIG_KEYS = {
    "KWABOR_MARKETING_VERSION",
    "KWABOR_CURRENT_PROJECT_VERSION",
    "KWABOR_DEVELOPMENT_TEAM",
    "KWABOR_PROVISIONING_PROFILE_SPECIFIER",
}
for tier in TIERS:
    LOCAL_XCCONFIG_KEYS.update(
        {
            f"KWABOR_SUPABASE_URL_{tier}",
            f"KWABOR_SUPABASE_PUBLISHABLE_KEY_{tier}",
            f"KWABOR_FIREBASE_IOS_CONFIG_PATH_{tier}",
            f"KWABOR_GOOGLE_IOS_CLIENT_ID_{tier}",
            f"KWABOR_GOOGLE_SERVER_CLIENT_ID_{tier}",
            f"KWABOR_GOOGLE_REVERSED_CLIENT_ID_{tier}",
        }
    )
BLANK_LOCAL_XCCONFIG_KEYS = LOCAL_XCCONFIG_KEYS - {
    "KWABOR_MARKETING_VERSION",
    "KWABOR_CURRENT_PROJECT_VERSION",
}

REQUIRED_IGNORE_RULES = {
    ".env",
    ".env.*",
    "!/.env.example",
    "local.properties",
    "iosApp/Kwabor/Config/Local.xcconfig",
    "google-services.json",
    "GoogleService-Info.plist",
    "*.keystore",
    "*.jks",
    "*.p12",
    "*.p8",
    "*.mobileprovision",
    "*.xcarchive",
    "*.xcresult",
    "*.ipa",
}
SENSITIVE_SUFFIXES = (
    ".jks",
    ".keystore",
    ".mobileprovision",
    ".p12",
    ".p8",
)
GENERATED_FILE_SUFFIXES = (".aab", ".apk", ".ipa")
GENERATED_DIRECTORY_SUFFIXES = (".xcarchive", ".xcresult")


class RepositoryIntegrityError(RuntimeError):
    """Raised when a repository integrity invariant is not satisfied."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RepositoryIntegrityError(message)


def read_text(relative_path: str) -> str:
    path = REPOSITORY_ROOT / relative_path
    require(path.is_file(), f"Missing required file: {relative_path}")
    return path.read_text(encoding="utf-8")


def sha256(relative_path: str) -> str:
    path = REPOSITORY_ROOT / relative_path
    require(path.is_file(), f"Missing required file: {relative_path}")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_assignments(
    relative_path: str,
    line_pattern: str,
    comment_prefixes: tuple[str, ...] = ("#",),
) -> dict[str, str]:
    matches: list[tuple[str, str]] = []
    for line_number, line in enumerate(read_text(relative_path).splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith(comment_prefixes):
            continue
        match = re.fullmatch(line_pattern, line)
        require(
            match is not None,
            f"{relative_path}:{line_number} is not a canonical assignment",
        )
        matches.append((match.group(1), match.group(2)))

    names = [name for name, _ in matches]
    duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
    require(not duplicates, f"{relative_path} contains duplicate keys: {', '.join(duplicates)}")
    return dict(matches)


def require_exact_keys(relative_path: str, actual: set[str], expected: set[str]) -> None:
    missing = sorted(expected - actual)
    require(not missing, f"{relative_path} is missing keys: {', '.join(missing)}")
    unexpected = sorted(actual - expected)
    require(
        not unexpected,
        f"{relative_path} contains unexpected keys: {', '.join(unexpected)}",
    )


def verify_configuration_templates() -> None:
    env_values = parse_assignments(
        ".env.example",
        r"([A-Z][A-Z0-9_]*)=(.*)",
    )
    require_exact_keys(".env.example", set(env_values), ENV_KEYS)
    populated = sorted(key for key in BLANK_ENV_KEYS if env_values[key].strip())
    require(
        not populated,
        ".env.example must not contain provider or signing values: "
        + ", ".join(populated),
    )

    local_properties = parse_assignments(
        "local.properties.example",
        r"([A-Za-z][A-Za-z0-9_.]*)=(.*)",
        comment_prefixes=("#", "!"),
    )
    require_exact_keys(
        "local.properties.example",
        set(local_properties),
        LOCAL_PROPERTIES_KEYS,
    )
    populated_local_properties = sorted(
        key for key in BLANK_LOCAL_PROPERTIES_KEYS if local_properties[key].strip()
    )
    require(
        not populated_local_properties,
        "local.properties.example must not contain provider or signing values: "
        + ", ".join(populated_local_properties),
    )

    local_xcconfig = parse_assignments(
        "iosApp/Kwabor/Config/Local.xcconfig.example",
        r"[ \t]*([A-Z][A-Z0-9_]*)[ \t]*=[ \t]*(.*)",
        comment_prefixes=("//",),
    )
    require_exact_keys(
        "iosApp/Kwabor/Config/Local.xcconfig.example",
        set(local_xcconfig),
        LOCAL_XCCONFIG_KEYS,
    )
    populated_xcconfig = sorted(
        key for key in BLANK_LOCAL_XCCONFIG_KEYS if local_xcconfig[key].strip()
    )
    require(
        not populated_xcconfig,
        "Local.xcconfig.example must not contain provider or signing values: "
        + ", ".join(populated_xcconfig),
    )


def verify_gradle_wrapper() -> None:
    properties = parse_assignments(
        "gradle/wrapper/gradle-wrapper.properties",
        r"([A-Za-z][A-Za-z0-9]*)=(.*)",
        comment_prefixes=("#", "!"),
    )
    require_exact_keys(
        "gradle/wrapper/gradle-wrapper.properties",
        set(properties),
        set(EXPECTED_GRADLE_WRAPPER_PROPERTIES),
    )
    changed_properties = sorted(
        key
        for key, expected in EXPECTED_GRADLE_WRAPPER_PROPERTIES.items()
        if properties[key] != expected
    )
    require(
        not changed_properties,
        "Gradle wrapper properties differ from the audited values: "
        + ", ".join(changed_properties),
    )
    require(
        sha256("gradle/wrapper/gradle-wrapper.jar") == GRADLE_WRAPPER_JAR_SHA256,
        "Gradle wrapper JAR does not match the official Gradle 9.4.1 checksum",
    )
    changed_scripts = sorted(
        path
        for path, expected_hash in GRADLE_WRAPPER_SCRIPT_SHA256.items()
        if sha256(path) != expected_hash
    )
    require(
        not changed_scripts,
        "Gradle wrapper launchers differ from the audited 9.4.1 generation: "
        + ", ".join(changed_scripts),
    )


def tracked_paths() -> list[str]:
    completed = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
    )
    require(
        completed.returncode == 0,
        "git ls-files failed; run this verifier from a Git clone",
    )
    return [
        path.decode("utf-8").replace("\\", "/")
        for path in completed.stdout.split(b"\0")
        if path
    ]


def is_sensitive_tracked_path(path: str) -> bool:
    pure_path = PurePosixPath(path)
    lower_name = pure_path.name.lower()
    lower_parts = tuple(part.lower() for part in pure_path.parts)

    if path == ".env.example":
        return False
    if lower_name == ".env" or lower_name.startswith(".env."):
        return True
    if lower_name in {
        "local.properties",
        "local.xcconfig",
        "google-services.json",
        "googleservice-info.plist",
    }:
        return True
    if lower_name.endswith(SENSITIVE_SUFFIXES):
        return True
    if lower_name.endswith(GENERATED_FILE_SUFFIXES):
        return True
    return any(part.endswith(GENERATED_DIRECTORY_SUFFIXES) for part in lower_parts)


def verify_git_hygiene() -> None:
    ignore_rules = {
        line.strip()
        for line in read_text(".gitignore").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    missing_rules = sorted(REQUIRED_IGNORE_RULES - ignore_rules)
    require(not missing_rules, ".gitignore is missing rules: " + ", ".join(missing_rules))

    sensitive_paths = sorted(path for path in tracked_paths() if is_sensitive_tracked_path(path))
    require(
        not sensitive_paths,
        "Sensitive or generated local artifacts are tracked: " + ", ".join(sensitive_paths),
    )


def verify_ios_privacy_manifest() -> None:
    manifest_path = REPOSITORY_ROOT / IOS_PRIVACY_MANIFEST_PATH
    require(
        manifest_path.is_file(),
        f"Missing required file: {IOS_PRIVACY_MANIFEST_PATH}",
    )
    try:
        with manifest_path.open("rb") as manifest_file:
            manifest = plistlib.load(manifest_file)
    except (OSError, plistlib.InvalidFileException) as error:
        raise RepositoryIntegrityError(
            f"{IOS_PRIVACY_MANIFEST_PATH} is not a valid property list: {error}"
        ) from error

    require(
        isinstance(manifest, dict),
        f"{IOS_PRIVACY_MANIFEST_PATH} must contain a dictionary",
    )
    accessed_api_types = manifest.get("NSPrivacyAccessedAPITypes")
    require(
        isinstance(accessed_api_types, list),
        f"{IOS_PRIVACY_MANIFEST_PATH} must declare NSPrivacyAccessedAPITypes as an array",
    )
    expected_accessed_api_types = [
        {
            "NSPrivacyAccessedAPIType": IOS_USER_DEFAULTS_API_TYPE,
            "NSPrivacyAccessedAPITypeReasons": [IOS_USER_DEFAULTS_REASON],
        }
    ]
    require(
        accessed_api_types == expected_accessed_api_types,
        f"{IOS_PRIVACY_MANIFEST_PATH} must exactly declare app-only UserDefaults "
        f"with reason {IOS_USER_DEFAULTS_REASON}",
    )


def main() -> int:
    try:
        verify_configuration_templates()
        verify_gradle_wrapper()
        verify_git_hygiene()
        verify_ios_privacy_manifest()
    except RepositoryIntegrityError as error:
        print(f"ERROR repository integrity: {error}", file=sys.stderr)
        return 1

    print(
        "OK repository integrity: configuration templates complete, "
        "sensitive artifacts untracked, Gradle 9.4.1 wrapper checksummed, "
        "iOS UserDefaults privacy reason declared"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
