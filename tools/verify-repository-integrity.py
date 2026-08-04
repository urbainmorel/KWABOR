#!/usr/bin/env python3
"""Verify critical Kwabor repository configuration and release invariants."""

from __future__ import annotations

import hashlib
import os
import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path, PurePosixPath


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
TIERS = ("DEVELOPMENT", "STAGING", "PRODUCTION")
IOS_PRIVACY_MANIFEST_PATH = "iosApp/Kwabor/Resources/PrivacyInfo.xcprivacy"
IOS_INFO_PLIST_PATH = "iosApp/Kwabor/Resources/Info.plist"
IOS_OBSERVABILITY_SOURCE_PATH = "iosApp/Kwabor/Observability/FirebaseObservability.swift"
IOS_ONBOARDING_COORDINATOR_PATH = "iosApp/Kwabor/Onboarding/OnboardingCoordinator.swift"
IOS_CONTENT_VIEW_PATH = "iosApp/Kwabor/App/ContentView.swift"
IOS_APP_SOURCE_PATH = "iosApp/Kwabor/App/KwaborApp.swift"
IOS_XCODE_PROJECT_PATH = "iosApp/Kwabor.xcodeproj/project.pbxproj"
IOS_ROOM_DATABASE_BUILDER_PATH = (
    "shared/src/iosMain/kotlin/com/kwabor/shared/data/local/"
    "IosKwaborDatabaseBuilder.kt"
)
IOS_ROOM_DATABASE_BUILDER_SHA256 = (
    "15fcfc8200f26445f1fd44c2e5e836e8d70cb0ac3b6507659f5255f646eb960e"
)
IOS_PRIVACY_CRITICAL_SOURCE_SHA256 = {
    IOS_OBSERVABILITY_SOURCE_PATH: "123738c638e69c098955f4683e0e2448b7f4014cfe041f7f42aed930a80cd638",
    IOS_ONBOARDING_COORDINATOR_PATH: "9611046fa70872e87dd41195bc0a6bd9c75f1fbadc63351cb763c41c193670aa",
    IOS_CONTENT_VIEW_PATH: "4e77ac43ae244704b48cd53a15ea38c1ac4cb927a2d6555b6c402d94f4447b8d",
    IOS_APP_SOURCE_PATH: "a825864ff5044587adf8961e7bef9cc4e27a058958c7cc31f212d90b857c16be",
}
ANDROID_MANIFEST_PATH = "androidApp/src/main/AndroidManifest.xml"
ANDROID_ROOM_DATABASE_BUILDER_PATH = (
    "shared/src/androidMain/kotlin/com/kwabor/shared/data/local/"
    "AndroidKwaborDatabaseBuilder.kt"
)
ANDROID_ROOM_DATABASE_BUILDER_SHA256 = (
    "76db407c136d5c85e23826d0e6d4f903eba0ffec24cf46892b43d49c441da4ba"
)
ANDROID_BACKUP_RULES_PATH = "androidApp/src/main/res/xml/backup_rules.xml"
ANDROID_DATA_EXTRACTION_RULES_PATH = (
    "androidApp/src/main/res/xml/data_extraction_rules.xml"
)
ANDROID_BACKUP_RULE_FILENAMES = frozenset(
    {"backup_rules.xml", "data_extraction_rules.xml"}
)
ANDROID_BACKUP_DOMAINS = frozenset(
    {
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    }
)
ANDROID_SOURCE_MANIFEST_ROOT = "androidApp/src"
ANDROID_FIREBASE_SOURCE_ROOTS = ("androidApp/src", "shared/src/androidMain")
ANDROID_BUILD_GRADLE_PATH = "androidApp/build.gradle.kts"
ROOT_BUILD_GRADLE_PATH = "build.gradle.kts"
ANDROID_GRADLE_PROPERTIES_PATH = "gradle.properties"
GRADLE_CONFIGURATION_IGNORED_DIRECTORIES = frozenset(
    {".git", ".gradle", ".idea", "build", "node_modules"}
)
ANDROID_OBSERVABILITY_BACKEND_PATH = (
    "androidApp/src/main/kotlin/com/kwabor/android/observability/"
    "FirebaseAndroidObservabilityBackend.kt"
)
ANDROID_OBSERVABILITY_CONTROLLER_PATH = (
    "androidApp/src/main/kotlin/com/kwabor/android/observability/AndroidObservabilityController.kt"
)
ANDROID_OBSERVABILITY_STORE_PATH = (
    "androidApp/src/main/kotlin/com/kwabor/android/observability/"
    "SharedPreferencesObservabilityConsentStore.kt"
)
ANDROID_OBSERVABILITY_RUNTIME_PATH = (
    "androidApp/src/main/kotlin/com/kwabor/android/observability/AndroidObservabilityRuntime.kt"
)
ANDROID_OBSERVABILITY_MAINTENANCE_PATH = (
    "androidApp/src/main/kotlin/com/kwabor/android/observability/AndroidObservabilityMaintenance.kt"
)
ANDROID_REMOTE_CONFIGURATION_COORDINATOR_PATH = (
    "androidApp/src/main/kotlin/com/kwabor/android/observability/"
    "AndroidRemoteConfigurationCoordinator.kt"
)
ANDROID_MAIN_ACTIVITY_PATH = "androidApp/src/main/kotlin/com/kwabor/android/MainActivity.kt"
ANDROID_PRIVACY_CRITICAL_SOURCE_SHA256 = {
    ANDROID_OBSERVABILITY_BACKEND_PATH: (
        "1d0d12c32f2b2958bb5298e44fc750f50d9aff46da97ed5db8c509d709f26310"
    ),
    ANDROID_OBSERVABILITY_CONTROLLER_PATH: (
        "eb5e5e2eca1e7f5bbffa506433e60049df8bfd9d3f5e2f552c055aba917d7b05"
    ),
    ANDROID_OBSERVABILITY_STORE_PATH: (
        "0dcab8d12c6226cf3de3ca6dde07482933dd14c233a0b654e0721dc8577879b6"
    ),
    ANDROID_OBSERVABILITY_RUNTIME_PATH: (
        "8f3932c7181b9aabd09b23509343fc3badd6f23a8ca819ddb2d8a672cbb21e40"
    ),
    ANDROID_OBSERVABILITY_MAINTENANCE_PATH: (
        "a5299db4add460fade4d9360e1564b73e963c0ad8be3f8d11b4157ceec342160"
    ),
    ANDROID_REMOTE_CONFIGURATION_COORDINATOR_PATH: (
        "cfb64b908f049cef32529b242180d13aaa9c535d09db788f7b71349b5ce8a0cf"
    ),
    ANDROID_MAIN_ACTIVITY_PATH: (
        "c41b1945759e379f79f9f459df9381a2fe831fab1bad0fb4931787a7c37cc2ca"
    ),
}
ANDROID_FIREBASE_CONFIGURATION_SHA256 = {
    ANDROID_BUILD_GRADLE_PATH: "c557c9f3a9fdbe6a4bfc5213f094cb8d14e7f2b6b1c15651998f6cf62f182aa0",
    ROOT_BUILD_GRADLE_PATH: "350ac8bd380ecaafdd3faf5158b068669e23c55019ba13d7572c1368fa5a1162",
    ANDROID_GRADLE_PROPERTIES_PATH: (
        "ad09dcf116ffab62b052e4212833d8090213d6cc7b28c751b7d7c8e519e6c9e1"
    ),
}
AUDITED_GRADLE_CONFIGURATION_SHA256 = {
    ROOT_BUILD_GRADLE_PATH: "350ac8bd380ecaafdd3faf5158b068669e23c55019ba13d7572c1368fa5a1162",
    "settings.gradle.kts": "0c009c069bbf448da8050b44e77ffb7da59fa19ba6daa77c362f908f8aaee8d1",
    ANDROID_BUILD_GRADLE_PATH: "c557c9f3a9fdbe6a4bfc5213f094cb8d14e7f2b6b1c15651998f6cf62f182aa0",
    "shared/build.gradle.kts": "011ab4721733a60fe50a30891b05f9829e1f45f8b4907d012bc95fa746c8098c",
}
ANDROID_EXPECTED_FIREBASE_DEPENDENCIES = (
    "com.google.firebase:firebase-analytics",
    "com.google.firebase:firebase-bom:34.15.0",
    "com.google.firebase:firebase-config",
    "com.google.firebase:firebase-crashlytics",
    "com.google.firebase:firebase-installations",
    "com.google.firebase:firebase-perf",
)
ANDROID_DISABLED_COLLECTION_METADATA = frozenset(
    {
        "firebase_analytics_collection_enabled",
        "firebase_data_collection_default_enabled",
        "firebase_crashlytics_collection_enabled",
        "firebase_performance_collection_enabled",
        "google_analytics_adid_collection_enabled",
        "google_analytics_default_allow_ad_personalization_signals",
    }
)
ANDROID_FORBIDDEN_ATTRIBUTION_PERMISSIONS = frozenset(
    {
        "android.permission.ACCESS_ADSERVICES_AD_ID",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        "com.google.android.gms.permission.AD_ID",
    }
)
ANDROID_ADSERVICES_LIBRARY = "android.ext.adservices"
ANDROID_FIREBASE_INIT_PROVIDER = "com.google.firebase.provider.FirebaseInitProvider"
ANDROID_XML_NAMESPACE = "{http://schemas.android.com/apk/res/android}"
ANDROID_TOOLS_NAMESPACE = "{http://schemas.android.com/tools}"
IOS_USER_DEFAULTS_API_TYPE = "NSPrivacyAccessedAPICategoryUserDefaults"
IOS_APP_FUNCTIONALITY_PURPOSE = "NSPrivacyCollectedDataTypePurposeAppFunctionality"
IOS_ANALYTICS_PURPOSE = "NSPrivacyCollectedDataTypePurposeAnalytics"
IOS_PRIVACY_MANIFEST_ROOT_KEYS = {
    "NSPrivacyAccessedAPITypes",
    "NSPrivacyCollectedDataTypes",
    "NSPrivacyTracking",
}

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


def audited_source_sha256(source: str) -> str:
    """Hash audited source bytes while tolerating only BOM and newline representation."""

    normalized_source = source.removeprefix("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized_source.encode("utf-8")).hexdigest()


def strip_kotlin_java_comments(source: str) -> str:
    """Mask Kotlin/Java comments while preserving code and string literals for audits."""

    masked: list[str] = []
    index = 0
    source_length = len(source)

    def mask(character: str) -> None:
        masked.append("\n" if character == "\n" else " ")

    while index < source_length:
        if source.startswith("//", index):
            while index < source_length and source[index] != "\n":
                mask(source[index])
                index += 1
            continue
        if source.startswith("/*", index):
            depth = 0
            while index < source_length:
                if source.startswith("/*", index):
                    depth += 1
                    mask(source[index])
                    mask(source[index + 1])
                    index += 2
                    continue
                if source.startswith("*/", index):
                    depth -= 1
                    mask(source[index])
                    mask(source[index + 1])
                    index += 2
                    if depth == 0:
                        break
                    continue
                mask(source[index])
                index += 1
            continue
        if source.startswith('"""', index):
            closing_index = source.find('"""', index + 3)
            string_end = source_length if closing_index < 0 else closing_index + 3
            masked.extend(source[index:string_end])
            index = string_end
            continue
        if source[index] in {'"', "'"}:
            quote = source[index]
            masked.append(quote)
            index += 1
            while index < source_length:
                masked.append(source[index])
                if source[index] == "\\":
                    index += 1
                    if index < source_length:
                        masked.append(source[index])
                elif source[index] == quote:
                    index += 1
                    break
                index += 1
            continue
        masked.append(source[index])
        index += 1
    return "".join(masked)


def decode_java_unicode_escapes(source: str) -> str:
    """Apply conservative Java/Kotlin Unicode translation before lexical source audits."""

    unicode_escape_pattern = re.compile(r"\\u+([0-9a-fA-F]{4})")
    translated = source
    while True:
        decoded = unicode_escape_pattern.sub(
            lambda match: chr(int(match.group(1), 16)),
            translated,
        )
        if decoded == translated:
            return decoded
        translated = decoded


def validate_ios_firebase_source_boundary(source_files: dict[str, str]) -> None:
    """Keep direct Firebase SDK access inside the single audited platform adapter."""

    forbidden_usage_pattern = re.compile(
        r"\b(?:Analytics|Crashlytics|Performance|RemoteConfig|Installations|FirebaseApp)\s*[.(]"
    )
    objective_c_firebase_pattern = re.compile(
        r"(?m)^\s*(?:@import\s+Firebase[A-Za-z0-9_]*\s*;|#\s*import\s*[<\"]Firebase)"
        r"|\bFIR(?:Analytics|Crashlytics|Performance|RemoteConfig|Installations|App)\b"
    )
    for source_path, source in source_files.items():
        normalized_path = PurePosixPath(source_path).as_posix()
        if normalized_path == IOS_OBSERVABILITY_SOURCE_PATH:
            continue
        active_source = strip_swift_comments_and_string_literals(source)
        if PurePosixPath(normalized_path).suffix.lower() in {".m", ".mm", ".h"}:
            require(
                objective_c_firebase_pattern.search(active_source) is None,
                f"{normalized_path} must not access Firebase through Objective-C; use "
                f"{IOS_OBSERVABILITY_SOURCE_PATH}",
            )
            continue
        require(
            re.search(r"(?m)^\s*import\s+Firebase[A-Za-z0-9_]*\s*$", active_source) is None
            and forbidden_usage_pattern.search(active_source) is None,
            f"{normalized_path} must not access Firebase directly; use "
            f"{IOS_OBSERVABILITY_SOURCE_PATH}",
        )


def validate_android_firebase_source_boundary(source_files: dict[str, str]) -> None:
    """Keep direct Android Firebase SDK access inside the audited backend."""

    firebase_package_pattern = re.compile(r"com\s*\.\s*google\s*\.\s*firebase", re.IGNORECASE)
    forbidden_usage_pattern = re.compile(
        r"\b(?:FirebaseApp|FirebaseAnalytics|FirebaseCrashlytics|FirebasePerformance|"
        r"FirebaseRemoteConfig|FirebaseInstallations)\s*[.(]"
    )
    dynamic_class_loading_pattern = re.compile(
        r"\b(?:Class\s*(?:\.|::)\s*forName|ClassLoader|DexClassLoader|PathClassLoader|loadClass)\s*(?:\(|\b)",
        re.IGNORECASE,
    )
    for source_path, source in source_files.items():
        normalized_path = PurePosixPath(source_path.replace("\\", "/")).as_posix()
        if normalized_path == ANDROID_OBSERVABILITY_BACKEND_PATH:
            continue
        translated_source = decode_java_unicode_escapes(source)
        active_source = strip_kotlin_java_comments(translated_source)
        require(
            dynamic_class_loading_pattern.search(active_source) is None,
            f"{normalized_path} must not load classes dynamically outside "
            f"{ANDROID_OBSERVABILITY_BACKEND_PATH}",
        )
        require(
            "FirebaseAndroidObservabilityBackend" not in active_source,
            f"{normalized_path} must not reference the private Firebase backend; use "
            "createAndroidObservabilityController",
        )
        require(
            re.search(r"(?m)^\s*import\s+com\.google\.firebase(?:\.|\s*$)", active_source)
            is None
            and firebase_package_pattern.search(active_source) is None
            and forbidden_usage_pattern.search(active_source) is None,
            f"{normalized_path} must not access Firebase directly; use "
            f"{ANDROID_OBSERVABILITY_BACKEND_PATH}",
        )


def validate_android_firebase_dependency_boundary(
    configuration_files: dict[str, str],
) -> None:
    """Keep Firebase Gradle references inside exact-hashed audited configuration files."""

    firebase_group_pattern = re.compile(
        r"com\s*\.\s*google\s*\.\s*firebase\b",
        re.IGNORECASE,
    )
    audited_paths = {ANDROID_BUILD_GRADLE_PATH, ROOT_BUILD_GRADLE_PATH}
    offenders: list[str] = []
    for source_path, source in configuration_files.items():
        normalized_path = PurePosixPath(source_path.replace("\\", "/")).as_posix()
        if normalized_path in audited_paths:
            continue
        translated_source = decode_java_unicode_escapes(source)
        active_source = strip_kotlin_java_comments(translated_source)
        if normalized_path.endswith(".toml"):
            active_source = re.sub(r"(?m)^\s*#.*$", "", active_source)
        concatenated_literals = re.sub(r"[\s'\"`+]", "", active_source)
        if (
            firebase_group_pattern.search(active_source) is not None
            or firebase_group_pattern.search(concatenated_literals) is not None
        ):
            offenders.append(normalized_path)
    require(
        not offenders,
        "Firebase Gradle references must only appear in the audited "
        f"{ANDROID_BUILD_GRADLE_PATH} and {ROOT_BUILD_GRADLE_PATH}; unexpected references in: "
        + ", ".join(sorted(offenders)),
    )
    actual_paths = set(configuration_files)
    expected_paths = set(AUDITED_GRADLE_CONFIGURATION_SHA256)
    require(
        actual_paths == expected_paths,
        "Gradle configuration inventory changed outside its audited snapshot; missing: "
        + ", ".join(sorted(expected_paths - actual_paths))
        + "; unexpected: "
        + ", ".join(sorted(actual_paths - expected_paths)),
    )
    for source_path, expected_sha256 in AUDITED_GRADLE_CONFIGURATION_SHA256.items():
        require(
            audited_source_sha256(configuration_files[source_path]) == expected_sha256,
            f"{source_path} changed outside its audited Gradle configuration snapshot",
        )


def parse_android_manifest(source_path: str, source: str) -> ET.Element:
    try:
        return ET.fromstring(source)
    except ET.ParseError as error:
        raise RepositoryIntegrityError(f"{source_path} is invalid XML: {error}") from error


def is_unscoped_android_privacy_removal(element: ET.Element) -> bool:
    return (
        element.get(f"{ANDROID_TOOLS_NAMESPACE}node") == "remove"
        and element.get(f"{ANDROID_TOOLS_NAMESPACE}selector") is None
    )


def validate_android_source_manifests(manifest_sources: dict[str, str]) -> None:
    """Reject privacy overrides in every checked-in Android source-set manifest."""

    normalized_sources = {
        PurePosixPath(source_path).as_posix(): source
        for source_path, source in manifest_sources.items()
    }
    require(
        ANDROID_MANIFEST_PATH in normalized_sources,
        f"Missing required file: {ANDROID_MANIFEST_PATH}",
    )
    for source_path, source in normalized_sources.items():
        manifest = parse_android_manifest(source_path, source)
        permission_elements = [
            *manifest.findall("uses-permission"),
            *manifest.findall("uses-permission-sdk-23"),
        ]
        for permission in permission_elements:
            permission_name = permission.get(f"{ANDROID_XML_NAMESPACE}name")
            if permission_name not in ANDROID_FORBIDDEN_ATTRIBUTION_PERMISSIONS:
                continue
            require(
                is_unscoped_android_privacy_removal(permission),
                f"{source_path} must remove inherited attribution permission {permission_name} "
                "without tools:selector",
            )

        application = manifest.find("application")
        if application is None:
            continue
        for metadata in application.findall("meta-data"):
            metadata_name = metadata.get(f"{ANDROID_XML_NAMESPACE}name")
            if metadata_name not in ANDROID_DISABLED_COLLECTION_METADATA:
                continue
            require(
                metadata.get(f"{ANDROID_XML_NAMESPACE}value") == "false"
                and metadata.get(f"{ANDROID_TOOLS_NAMESPACE}node") != "remove",
                f"{source_path} must keep {metadata_name}=false",
            )
        for provider in application.findall("provider"):
            if provider.get(f"{ANDROID_XML_NAMESPACE}name") != ANDROID_FIREBASE_INIT_PROVIDER:
                continue
            require(
                is_unscoped_android_privacy_removal(provider),
                f"{source_path} must remove {ANDROID_FIREBASE_INIT_PROVIDER} without tools:selector",
            )
        for library in application.findall("uses-library"):
            if library.get(f"{ANDROID_XML_NAMESPACE}name") != ANDROID_ADSERVICES_LIBRARY:
                continue
            require(
                is_unscoped_android_privacy_removal(library),
                f"{source_path} must remove inherited library {ANDROID_ADSERVICES_LIBRARY} "
                "without tools:selector",
            )


def validate_android_backup_exclusions(
    *,
    source_path: str,
    parent: ET.Element,
) -> None:
    children = list(parent)
    require(
        all(child.tag == "exclude" for child in children),
        f"{source_path} must contain only exclude elements",
    )
    exclusions: list[tuple[str | None, str | None]] = []
    for child in children:
        require(
            set(child.attrib) == {"domain", "path"},
            f"{source_path} excludes must declare only domain and path",
        )
        exclusions.append((child.get("domain"), child.get("path")))
    expected = Counter((domain, ".") for domain in ANDROID_BACKUP_DOMAINS)
    require(
        Counter(exclusions) == expected,
        f"{source_path} must exclude every audited Android data domain at path '.'",
    )


def validate_android_local_backup_contract(
    *,
    manifest_sources: dict[str, str],
    backup_rule_sources: dict[str, str],
) -> None:
    """Lock Android cloud-backup and device-transfer exclusions."""

    normalized_manifests = {
        PurePosixPath(source_path).as_posix(): source
        for source_path, source in manifest_sources.items()
    }
    require(
        ANDROID_MANIFEST_PATH in normalized_manifests,
        f"Missing required file: {ANDROID_MANIFEST_PATH}",
    )
    expected_application_attributes = {
        "allowBackup": "false",
        "fullBackupContent": "@xml/backup_rules",
        "dataExtractionRules": "@xml/data_extraction_rules",
    }
    for source_path, source in normalized_manifests.items():
        manifest = parse_android_manifest(source_path, source)
        application = manifest.find("application")
        if application is None:
            continue
        for attribute_name, expected_value in expected_application_attributes.items():
            value = application.get(f"{ANDROID_XML_NAMESPACE}{attribute_name}")
            if source_path == ANDROID_MANIFEST_PATH or value is not None:
                require(
                    value == expected_value,
                    f"{source_path} must set android:{attribute_name}={expected_value}",
                )
        require(
            application.get(f"{ANDROID_XML_NAMESPACE}backupAgent") is None,
            f"{source_path} must not install a custom Android BackupAgent",
        )

    normalized_rules = {
        PurePosixPath(source_path).as_posix(): source
        for source_path, source in backup_rule_sources.items()
    }
    expected_rule_paths = {
        ANDROID_BACKUP_RULES_PATH,
        ANDROID_DATA_EXTRACTION_RULES_PATH,
    }
    require(
        set(normalized_rules) == expected_rule_paths,
        "Android backup rules must exist only in the audited main source set",
    )

    full_backup_root = parse_android_manifest(
        ANDROID_BACKUP_RULES_PATH,
        normalized_rules[ANDROID_BACKUP_RULES_PATH],
    )
    require(
        full_backup_root.tag == "full-backup-content" and not full_backup_root.attrib,
        f"{ANDROID_BACKUP_RULES_PATH} must use an attribute-free full-backup-content root",
    )
    validate_android_backup_exclusions(
        source_path=ANDROID_BACKUP_RULES_PATH,
        parent=full_backup_root,
    )

    extraction_root = parse_android_manifest(
        ANDROID_DATA_EXTRACTION_RULES_PATH,
        normalized_rules[ANDROID_DATA_EXTRACTION_RULES_PATH],
    )
    require(
        extraction_root.tag == "data-extraction-rules" and not extraction_root.attrib,
        f"{ANDROID_DATA_EXTRACTION_RULES_PATH} must use an attribute-free data-extraction-rules root",
    )
    sections = list(extraction_root)
    require(
        Counter(section.tag for section in sections)
        == Counter({"cloud-backup": 1, "device-transfer": 1}),
        f"{ANDROID_DATA_EXTRACTION_RULES_PATH} must contain cloud-backup and device-transfer",
    )
    for section in sections:
        require(
            not section.attrib,
            f"{ANDROID_DATA_EXTRACTION_RULES_PATH} sections must not weaken the policy with attributes",
        )
        validate_android_backup_exclusions(
            source_path=f"{ANDROID_DATA_EXTRACTION_RULES_PATH}:{section.tag}",
            parent=section,
        )


def validate_ios_room_storage_contract(source: str) -> None:
    """Lock the fail-closed, device-bound iOS Room directory policy."""

    active_source = strip_kotlin_java_comments(decode_java_unicode_escapes(source))
    required_fragments = (
        'private const val KWABOR_ROOM_DIRECTORY_NAME = "KwaborRoom"',
        "prepareIosRoomDirectory(",
        "NSFileProtectionCompleteUntilFirstUserAuthentication",
        "fileManager.createDirectoryAtURL(",
        "internal fun interface IosRoomFileProtectionApplicator",
        "private fun NSFileManager.iosRoomFileProtectionApplicator()",
        "IosRoomFileProtectionApplicator { path, attributes ->",
        "protectionApplicator.apply(",
        "roomDirectoryUrl.setResourceValue(",
        "NSNumber(bool = true)",
        "NSURLIsExcludedFromBackupKey",
        "removeLegacyIosDatabaseFiles(",
        "var allFilesRemoved = true",
        "var allFilesProtected = true",
        'listOf("", "-wal", "-shm", "-journal")',
        "IosRoomStoragePolicyException",
        "catch (_: IosRoomStoragePolicyException)",
        "Room.inMemoryDatabaseBuilder<KwaborDatabase>(",
        "onPolicyFailure()",
        "NSLog(",
    )
    for fragment in required_fragments:
        require(
            fragment in active_source,
            f"{IOS_ROOM_DATABASE_BUILDER_PATH} is missing required local-storage control: {fragment}",
        )
    require("runCatching" not in active_source, f"{IOS_ROOM_DATABASE_BUILDER_PATH} must not use runCatching")
    require(
        active_source.count("catch (") == 1
        and "catch (_: IosRoomStoragePolicyException)" in active_source,
        f"{IOS_ROOM_DATABASE_BUILDER_PATH} must catch only its typed policy failure",
    )
    require(
        active_source.count("fileManager.iosRoomFileProtectionApplicator()") == 2,
        f"{IOS_ROOM_DATABASE_BUILDER_PATH} must default both entry points to the production "
        "file-protection adapter",
    )
    require(
        active_source.count("protectionApplicator.apply(") == 2,
        f"{IOS_ROOM_DATABASE_BUILDER_PATH} must apply the injected protection policy to the "
        "Room directory and existing database family",
    )
    adapter_start = active_source.index(
        "private fun NSFileManager.iosRoomFileProtectionApplicator()"
    )
    adapter_end = active_source.index(
        "private fun resolveIosRoomDirectoryUrl",
        adapter_start,
    )
    adapter_source = active_source[adapter_start:adapter_end]
    for fragment in (
        "IosRoomFileProtectionApplicator { path, attributes ->",
        "setAttributes(",
        "attributes = attributes",
        "ofItemAtPath = path",
        "error = null",
    ):
        require(
            fragment in adapter_source,
            f"{IOS_ROOM_DATABASE_BUILDER_PATH} production file-protection adapter must "
            f"delegate to NSFileManager.setAttributes: {fragment}",
        )
    require(
        active_source.index("removeLegacyIosDatabaseFiles(")
        < active_source.index("val roomDirectoryUrl = resolveIosRoomDirectoryUrl"),
        f"{IOS_ROOM_DATABASE_BUILDER_PATH} must clean legacy files before preparing protected storage",
    )
    require(
        audited_source_sha256(source) == IOS_ROOM_DATABASE_BUILDER_SHA256,
        f"{IOS_ROOM_DATABASE_BUILDER_PATH} changed outside its audited local-storage snapshot; "
        "perform a new review before updating the expected SHA-256",
    )


def validate_android_room_storage_contract(source: str) -> None:
    """Lock Android Room into no-backup storage with a memory-only fallback."""

    active_source = strip_kotlin_java_comments(decode_java_unicode_escapes(source))
    required_fragments = (
        'private const val ANDROID_ROOM_DIRECTORY_NAME = "KwaborRoom"',
        "context.noBackupFilesDir.canonicalFile",
        'listOf("", "-wal", "-shm", "-journal")',
        "removeLegacyAndroidDatabaseFiles(",
        "val legacyFilesRemoved = removeLegacyAndroidDatabaseFiles(context)",
        "ANDROID_DATABASE_FILE_SUFFIXES.forEach",
        "Room.inMemoryDatabaseBuilder<KwaborDatabase>(",
        "Log.e(",
        "catch (_: IOException)",
        "catch (_: SecurityException)",
    )
    for fragment in required_fragments:
        require(
            fragment in active_source,
            f"{ANDROID_ROOM_DATABASE_BUILDER_PATH} is missing required local-storage control: "
            f"{fragment}",
        )
    require(
        "runCatching" not in active_source
        and "ANDROID_DATABASE_FILE_SUFFIXES.all" not in active_source
        and Counter(
            re.findall(r"catch\s*\(\s*_:\s*([A-Za-z0-9_.]+)\s*\)", active_source)
        )
        == Counter({"IOException": 1, "SecurityException": 2}),
        f"{ANDROID_ROOM_DATABASE_BUILDER_PATH} must attempt every legacy deletion and catch only "
        "Android filesystem failures",
    )
    require(
        active_source.index(
            "val legacyFilesRemoved = removeLegacyAndroidDatabaseFiles(context)"
        )
        < active_source.index("val noBackupRoot = context.noBackupFilesDir.canonicalFile"),
        f"{ANDROID_ROOM_DATABASE_BUILDER_PATH} must clean legacy files before preparing no-backup storage",
    )
    require(
        audited_source_sha256(source) == ANDROID_ROOM_DATABASE_BUILDER_SHA256,
        f"{ANDROID_ROOM_DATABASE_BUILDER_PATH} changed outside its audited local-storage snapshot; "
        "perform a new review before updating the expected SHA-256",
    )


def validate_android_firebase_privacy_contract(
    *,
    manifest_source: str,
    gradle_properties_source: str,
    build_gradle_source: str,
    root_build_gradle_source: str,
    backend_source: str,
    controller_source: str,
    store_source: str,
    runtime_source: str,
    maintenance_source: str,
    remote_configuration_source: str,
    main_activity_source: str,
) -> None:
    """Lock Android Firebase lazy initialization and durable privacy maintenance."""

    validate_android_source_manifests({ANDROID_MANIFEST_PATH: manifest_source})
    manifest = parse_android_manifest(ANDROID_MANIFEST_PATH, manifest_source)
    application = manifest.find("application")
    require(application is not None, f"{ANDROID_MANIFEST_PATH} must declare an application")
    permission_elements = [
        *manifest.findall("uses-permission"),
        *manifest.findall("uses-permission-sdk-23"),
    ]
    for permission_name in ANDROID_FORBIDDEN_ATTRIBUTION_PERMISSIONS:
        permission_removals = [
            permission
            for permission in permission_elements
            if permission.get(f"{ANDROID_XML_NAMESPACE}name") == permission_name
        ]
        require(
            len(permission_removals) == 1
            and is_unscoped_android_privacy_removal(permission_removals[0]),
            f"{ANDROID_MANIFEST_PATH} must explicitly remove {permission_name} without tools:selector",
        )
    metadata_values: dict[str, list[str]] = {}
    for metadata in application.findall("meta-data"):
        name = metadata.get(f"{ANDROID_XML_NAMESPACE}name")
        value = metadata.get(f"{ANDROID_XML_NAMESPACE}value")
        if name is not None and value is not None:
            metadata_values.setdefault(name, []).append(value)
    for metadata_name in ANDROID_DISABLED_COLLECTION_METADATA:
        require(
            metadata_values.get(metadata_name) == ["false"],
            f"{ANDROID_MANIFEST_PATH} must set exactly one {metadata_name}=false",
        )
    firebase_init_providers = [
        provider
        for provider in application.findall("provider")
        if provider.get(f"{ANDROID_XML_NAMESPACE}name") == ANDROID_FIREBASE_INIT_PROVIDER
    ]
    require(
        len(firebase_init_providers) == 1
        and is_unscoped_android_privacy_removal(firebase_init_providers[0]),
        f"{ANDROID_MANIFEST_PATH} must remove FirebaseInitProvider without tools:selector "
        "before Application startup",
    )
    adservices_libraries = [
        library
        for library in application.findall("uses-library")
        if library.get(f"{ANDROID_XML_NAMESPACE}name") == ANDROID_ADSERVICES_LIBRARY
    ]
    require(
        len(adservices_libraries) == 1
        and is_unscoped_android_privacy_removal(adservices_libraries[0]),
        f"{ANDROID_MANIFEST_PATH} must explicitly remove {ANDROID_ADSERVICES_LIBRARY} "
        "without tools:selector",
    )

    performance_instrumentation_values = re.findall(
        r"(?m)^\s*firebasePerformanceInstrumentationEnabled\s*=\s*([^#\s]+)\s*$",
        gradle_properties_source,
    )
    require(
        performance_instrumentation_values == ["false"],
        f"{ANDROID_GRADLE_PROPERTIES_PATH} must disable Firebase Performance instrumentation",
    )

    active_build_gradle_source = strip_kotlin_java_comments(
        decode_java_unicode_escapes(build_gradle_source)
    )
    firebase_dependencies = re.findall(
        r'["\'](com\.google\.firebase:[^"\']+)["\']',
        active_build_gradle_source,
    )
    require(
        Counter(firebase_dependencies) == Counter(ANDROID_EXPECTED_FIREBASE_DEPENDENCIES)
        and re.search(
            r'implementation\s*\(\s*platform\s*\(\s*"com\.google\.firebase:'
            r'firebase-bom:34\.15\.0"\s*\)\s*\)',
            active_build_gradle_source,
        )
        is not None
        and all(
            re.search(
                rf'implementation\s*\(\s*"{re.escape(dependency)}"\s*\)',
                active_build_gradle_source,
            )
            is not None
            for dependency in ANDROID_EXPECTED_FIREBASE_DEPENDENCIES
            if ":firebase-bom:" not in dependency
        ),
        f"{ANDROID_BUILD_GRADLE_PATH} must declare exactly the audited Firebase dependency set",
    )
    active_root_build_gradle_source = strip_kotlin_java_comments(
        decode_java_unicode_escapes(root_build_gradle_source)
    )
    required_dependency_boundary_task_tokens = {
        "val verifyFirebaseDependencyBoundary by tasks.registering",
        '.filter { project -> project.path != ":androidApp" }',
        '.filter { dependency -> dependency.group == "com.google.firebase" }',
        "check(forbiddenDependencies.isEmpty())",
        "dependsOn(verifyFirebaseDependencyBoundary)",
        'dependsOn(rootProject.tasks.named("verifyFirebaseDependencyBoundary"))',
    }
    missing_dependency_boundary_task_tokens = sorted(
        token
        for token in required_dependency_boundary_task_tokens
        if token not in active_root_build_gradle_source
    )
    require(
        not missing_dependency_boundary_task_tokens,
        f"{ROOT_BUILD_GRADLE_PATH} must register the evaluated Firebase dependency boundary: "
        + ", ".join(missing_dependency_boundary_task_tokens),
    )
    required_merged_manifest_task_tokens = {
        "val verifyFirebaseMergedManifests by tasks.registering",
        'dependsOn("process${variantName}MainManifest")',
        "forbiddenPermissions.isEmpty()",
        '"com.google.firebase.provider.FirebaseInitProvider" !in providerNames',
        '"android.ext.adservices" !in libraryNames',
        'values == listOf("false")',
        "dependsOn(verifyFirebaseMergedManifests)",
    }
    missing_merged_manifest_task_tokens = sorted(
        token for token in required_merged_manifest_task_tokens if token not in active_build_gradle_source
    )
    require(
        not missing_merged_manifest_task_tokens,
        f"{ANDROID_BUILD_GRADLE_PATH} must register the audited verifyFirebaseMergedManifests task: "
        + ", ".join(missing_merged_manifest_task_tokens),
    )

    active_backend_source = strip_swift_comments_and_string_literals(
        decode_java_unicode_escapes(backend_source)
    )
    crashlytics_arguments = re.findall(
        r"setCrashlyticsCollectionEnabled\s*\(\s*([^)]*?)\s*\)",
        active_backend_source,
    )
    require(
        crashlytics_arguments
        and all(argument.strip() == "false" for argument in crashlytics_arguments),
        f"{ANDROID_OBSERVABILITY_BACKEND_PATH} must keep automatic Crashlytics disabled",
    )
    required_backend_tokens = {
        "internal fun createAndroidObservabilityController(context: Context)",
        "private class FirebaseAndroidObservabilityBackend(",
        "FirebaseApp.initializeApp(context)",
        "initializedAnalytics.setAnalyticsCollectionEnabled(false)",
        "initializedPerformance.isPerformanceCollectionEnabled = false",
        "FirebaseInstallations.getInstance(app)",
        "fun deleteInstallation(onResult: (Boolean) -> Unit)",
    }
    missing_backend_tokens = sorted(
        token for token in required_backend_tokens if token not in active_backend_source
    )
    require(
        not missing_backend_tokens,
        f"{ANDROID_OBSERVABILITY_BACKEND_PATH} is missing lazy-safe Firebase controls: "
        + ", ".join(missing_backend_tokens),
    )

    required_controller_tokens = {
        "fun retryPendingMaintenance(): Boolean",
        "private var pendingConsentMutation: PendingConsentMutation? = null",
        "is PendingConsentMutation.Update -> attemptConsentUpdate(pending)",
        "PendingConsentMutation.Revoke -> attemptConsentRevocation(clearRequestedUser = false)",
        "pendingConsentMutation = mutation",
        "pendingConsentMutation = PendingConsentMutation.Revoke",
        "runtimeSuspendedAfterPersistenceFailure",
    }
    active_controller_source = strip_swift_comments_and_string_literals(
        decode_java_unicode_escapes(controller_source)
    )
    missing_controller_tokens = sorted(
        token for token in required_controller_tokens if token not in active_controller_source
    )
    require(
        not missing_controller_tokens,
        f"{ANDROID_OBSERVABILITY_CONTROLLER_PATH} is missing durable privacy controls: "
        + ", ".join(missing_controller_tokens),
    )
    required_store_tokens = {
        "requestIdProvider",
        "DIAGNOSTICS_PURGE_REQUEST_ID_KEY",
        "INSTALLATION_DELETION_REQUEST_ID_KEY",
        "persistForceDisabled",
        "persistRevokedConsent",
        "currentRequestId != expectedRequestId",
        "private fun ObservabilityPreferences.commitDurably",
        "repeat(DURABLE_COMMIT_ATTEMPTS)",
        "if (commit(mutation)) return true",
        "preferences.commitDurably(failClosedMutation)",
        "preferences.commitDurably(finalMutation)",
        "preferences.commitDurably(plan.rollbackMutation)",
    }
    active_store_source = strip_swift_comments_and_string_literals(
        decode_java_unicode_escapes(store_source)
    )
    missing_store_tokens = sorted(
        token for token in required_store_tokens if token not in active_store_source
    )
    require(
        not missing_store_tokens,
        f"{ANDROID_OBSERVABILITY_STORE_PATH} is missing durable privacy storage controls: "
        + ", ".join(missing_store_tokens),
    )
    require(
        re.search(r"\.\s*apply\s*[({]", active_store_source) is None,
        f"{ANDROID_OBSERVABILITY_STORE_PATH} must not use asynchronous SharedPreferences.apply",
    )

    required_runtime_tokens = {
        "private var desiredConsent = ObservabilityConsent()",
        "private var effectiveConsent = ObservabilityConsent()",
        "val requiresBackend = desiredConsent.allowsAnyCollection || stored.hasPendingMaintenance",
        "applyEffectiveConsent(ObservabilityConsent())",
        "installationDeletion.resume(installationDeletionRequestId)",
        "diagnosticsReports.resumePurge(stored.diagnosticsReportPurgeRequestId)",
        "diagnosticsReports.resumeRestoredSend()",
        "desiredConsent.diagnosticsAllowed && stored.diagnosticsReportPurgeRequestId == null",
    }
    active_runtime_source = strip_swift_comments_and_string_literals(
        decode_java_unicode_escapes(runtime_source)
    )
    missing_runtime_tokens = sorted(
        token for token in required_runtime_tokens if token not in active_runtime_source
    )
    require(
        not missing_runtime_tokens,
        f"{ANDROID_OBSERVABILITY_RUNTIME_PATH} is missing fail-closed runtime controls: "
        + ", ".join(missing_runtime_tokens),
    )

    required_maintenance_tokens = {
        "if (inFlightRequestId != requestId) return",
        "consentStore.completeInstallationDeletion(requestId)",
        "processState != DiagnosticsReportProcessState.CheckingPurge(requestId)",
        "consentStore.completeDiagnosticsReportPurge(requestId)",
        "generation != sessionGeneration || !isDiagnosticsAllowed()",
        "backend.deleteUnsentReports()",
    }
    active_maintenance_source = strip_swift_comments_and_string_literals(
        decode_java_unicode_escapes(maintenance_source)
    )
    missing_maintenance_tokens = sorted(
        token for token in required_maintenance_tokens if token not in active_maintenance_source
    )
    require(
        not missing_maintenance_tokens,
        f"{ANDROID_OBSERVABILITY_MAINTENANCE_PATH} is missing stale-safe maintenance controls: "
        + ", ".join(missing_maintenance_tokens),
    )

    required_remote_configuration_tokens = {
        "private var generation = 0L",
        "if (!isActive(activeGeneration)) return",
        "if (!isActive(activeGeneration)) backend.stopRemoteConfigurationUpdates()",
        "if (!succeeded && isActive(callbackGeneration)) reportFailure()",
        "isRemoteConfigurationAllowed() && callbackGeneration == generation",
    }
    active_remote_configuration_source = strip_swift_comments_and_string_literals(
        decode_java_unicode_escapes(remote_configuration_source)
    )
    missing_remote_configuration_tokens = sorted(
        token
        for token in required_remote_configuration_tokens
        if token not in active_remote_configuration_source
    )
    require(
        not missing_remote_configuration_tokens,
        f"{ANDROID_REMOTE_CONFIGURATION_COORDINATOR_PATH} is missing generation-safe remote "
        "configuration controls: " + ", ".join(missing_remote_configuration_tokens),
    )

    active_main_activity_source = strip_swift_comments_and_string_literals(
        decode_java_unicode_escapes(main_activity_source)
    )
    require(
        "override fun onStart()" in active_main_activity_source
        and "observability.retryPendingMaintenance()" in active_main_activity_source,
        f"{ANDROID_MAIN_ACTIVITY_PATH} must retry durable Firebase maintenance on foreground",
    )

    audited_sources = {
        ANDROID_OBSERVABILITY_BACKEND_PATH: backend_source,
        ANDROID_OBSERVABILITY_CONTROLLER_PATH: controller_source,
        ANDROID_OBSERVABILITY_STORE_PATH: store_source,
        ANDROID_OBSERVABILITY_RUNTIME_PATH: runtime_source,
        ANDROID_OBSERVABILITY_MAINTENANCE_PATH: maintenance_source,
        ANDROID_REMOTE_CONFIGURATION_COORDINATOR_PATH: remote_configuration_source,
        ANDROID_MAIN_ACTIVITY_PATH: main_activity_source,
    }
    for source_path, source in audited_sources.items():
        require(
            audited_source_sha256(source) == ANDROID_PRIVACY_CRITICAL_SOURCE_SHA256[source_path],
            f"{source_path} changed outside its audited privacy snapshot; perform a new review "
            "before updating the expected SHA-256",
        )

    audited_configuration = {
        ANDROID_BUILD_GRADLE_PATH: build_gradle_source,
        ROOT_BUILD_GRADLE_PATH: root_build_gradle_source,
        ANDROID_GRADLE_PROPERTIES_PATH: gradle_properties_source,
    }
    for source_path, source in audited_configuration.items():
        require(
            audited_source_sha256(source) == ANDROID_FIREBASE_CONFIGURATION_SHA256[source_path],
            f"{source_path} changed outside its audited Firebase configuration snapshot; "
            "perform a new review before updating the expected SHA-256",
        )


def strip_swift_comments_and_string_literals(source: str) -> str:
    """Keep Swift code positions stable while masking comments and string literals."""

    masked: list[str] = []
    index = 0
    source_length = len(source)

    def mask(character: str) -> None:
        masked.append("\n" if character == "\n" else " ")

    while index < source_length:
        if source.startswith("//", index):
            while index < source_length and source[index] != "\n":
                mask(source[index])
                index += 1
            continue

        if source.startswith("/*", index):
            depth = 0
            while index < source_length:
                if source.startswith("/*", index):
                    depth += 1
                    mask(source[index])
                    mask(source[index + 1])
                    index += 2
                    continue
                if source.startswith("*/", index):
                    depth -= 1
                    mask(source[index])
                    mask(source[index + 1])
                    index += 2
                    if depth == 0:
                        break
                    continue
                mask(source[index])
                index += 1
            continue

        raw_hash_count = 0
        while index + raw_hash_count < source_length and source[index + raw_hash_count] == "#":
            raw_hash_count += 1
        quote_index = index + raw_hash_count
        if quote_index < source_length and source[quote_index] == '"':
            triple_quoted = source.startswith('"""', quote_index)
            quote = '"""' if triple_quoted else '"'
            closing_delimiter = quote + ("#" * raw_hash_count)
            opening_length = raw_hash_count + len(quote)
            for character in source[index : index + opening_length]:
                mask(character)
            index += opening_length
            while index < source_length:
                if source.startswith(closing_delimiter, index):
                    for character in closing_delimiter:
                        mask(character)
                    index += len(closing_delimiter)
                    break
                if raw_hash_count == 0 and source[index] == "\\":
                    mask(source[index])
                    index += 1
                    if index < source_length:
                        mask(source[index])
                        index += 1
                    continue
                mask(source[index])
                index += 1
            continue

        masked.append(source[index])
        index += 1

    return "".join(masked)


def swift_function_region(source: str, function_name: str, next_function_name: str) -> str:
    function_match = re.search(rf"\bfunc\s+{re.escape(function_name)}\b", source)
    require(function_match is not None, f"Missing Swift function: {function_name}")
    next_function_match = re.search(
        rf"\bfunc\s+{re.escape(next_function_name)}\b",
        source[function_match.end() :],
    )
    require(next_function_match is not None, f"Missing Swift function: {next_function_name}")
    next_function_start = function_match.end() + next_function_match.start()
    preceding_line_break = source.rfind("\n", function_match.end(), next_function_start)
    region_end = next_function_start if preceding_line_break < 0 else preceding_line_break + 1
    return source[function_match.start() : region_end]


def read_text(relative_path: str) -> str:
    path = REPOSITORY_ROOT / relative_path
    require(path.is_file(), f"Missing required file: {relative_path}")
    return path.read_text(encoding="utf-8")


def discover_android_firebase_source_files(
    repository_root: Path = REPOSITORY_ROOT,
) -> dict[str, str]:
    source_files: dict[str, str] = {}
    for relative_root in ANDROID_FIREBASE_SOURCE_ROOTS:
        source_root = repository_root / relative_root
        if not source_root.is_dir():
            continue
        for path in sorted(source_root.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in {".kt", ".java"}:
                continue
            source_files[path.relative_to(repository_root).as_posix()] = path.read_text(
                encoding="utf-8"
            )
    return source_files


def discover_android_source_manifests(
    repository_root: Path = REPOSITORY_ROOT,
) -> dict[str, str]:
    manifest_root = repository_root / ANDROID_SOURCE_MANIFEST_ROOT
    if not manifest_root.is_dir():
        return {}
    return {
        path.relative_to(repository_root).as_posix(): path.read_text(encoding="utf-8")
        for path in sorted(manifest_root.rglob("AndroidManifest.xml"))
        if path.is_file()
    }


def discover_android_backup_rule_sources(
    repository_root: Path = REPOSITORY_ROOT,
) -> dict[str, str]:
    return {
        path.relative_to(repository_root).as_posix(): path.read_text(encoding="utf-8")
        for path in sorted(repository_root.rglob("*.xml"))
        if path.is_file()
        and len(path.relative_to(repository_root).parts) >= 5
        and path.relative_to(repository_root).parts[-5] == "src"
        and path.relative_to(repository_root).parts[-3] == "res"
        and (path.parent.name == "xml" or path.parent.name.startswith("xml-"))
        and path.name in ANDROID_BACKUP_RULE_FILENAMES
    }


def discover_gradle_configuration_files(
    repository_root: Path = REPOSITORY_ROOT,
) -> dict[str, str]:
    configuration_files: dict[str, str] = {}
    for directory, directory_names, file_names in os.walk(repository_root):
        directory_names[:] = sorted(
            directory_name
            for directory_name in directory_names
            if directory_name not in GRADLE_CONFIGURATION_IGNORED_DIRECTORIES
        )
        for file_name in sorted(file_names):
            if not (
                file_name.endswith((".gradle", ".gradle.kts"))
                or file_name == "libs.versions.toml"
            ):
                continue
            path = Path(directory) / file_name
            relative_path = path.relative_to(repository_root)
            configuration_files[relative_path.as_posix()] = path.read_text(encoding="utf-8")
    return configuration_files


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
    validate_ios_privacy_manifest(manifest)


def validate_ios_privacy_manifest(manifest: dict[str, object]) -> None:
    """Validate the exact audited host privacy declaration."""

    require_exact_keys(
        IOS_PRIVACY_MANIFEST_PATH,
        set(manifest),
        IOS_PRIVACY_MANIFEST_ROOT_KEYS,
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
    expected_collected_data_types = [
        collected_data_type("NSPrivacyCollectedDataTypeName"),
        collected_data_type("NSPrivacyCollectedDataTypeEmailAddress"),
        collected_data_type("NSPrivacyCollectedDataTypeUserID"),
        collected_data_type("NSPrivacyCollectedDataTypeCoarseLocation"),
        collected_data_type(
            "NSPrivacyCollectedDataTypeProductInteraction",
            purposes=[IOS_APP_FUNCTIONALITY_PURPOSE, IOS_ANALYTICS_PURPOSE],
        ),
    ]
    require(
        manifest.get("NSPrivacyCollectedDataTypes") == expected_collected_data_types,
        f"{IOS_PRIVACY_MANIFEST_PATH} must exactly declare the audited host data collection",
    )
    require(
        manifest.get("NSPrivacyTracking") is False,
        f"{IOS_PRIVACY_MANIFEST_PATH} must keep host tracking disabled",
    )


def verify_android_firebase_privacy_contract() -> None:
    source_manifests = discover_android_source_manifests()
    validate_android_source_manifests(source_manifests)
    validate_android_firebase_dependency_boundary(discover_gradle_configuration_files())
    validate_android_firebase_privacy_contract(
        manifest_source=read_text(ANDROID_MANIFEST_PATH),
        gradle_properties_source=read_text(ANDROID_GRADLE_PROPERTIES_PATH),
        build_gradle_source=read_text(ANDROID_BUILD_GRADLE_PATH),
        root_build_gradle_source=read_text(ROOT_BUILD_GRADLE_PATH),
        backend_source=read_text(ANDROID_OBSERVABILITY_BACKEND_PATH),
        controller_source=read_text(ANDROID_OBSERVABILITY_CONTROLLER_PATH),
        store_source=read_text(ANDROID_OBSERVABILITY_STORE_PATH),
        runtime_source=read_text(ANDROID_OBSERVABILITY_RUNTIME_PATH),
        maintenance_source=read_text(ANDROID_OBSERVABILITY_MAINTENANCE_PATH),
        remote_configuration_source=read_text(ANDROID_REMOTE_CONFIGURATION_COORDINATOR_PATH),
        main_activity_source=read_text(ANDROID_MAIN_ACTIVITY_PATH),
    )
    validate_android_firebase_source_boundary(discover_android_firebase_source_files())


def verify_local_storage_privacy_contract() -> None:
    validate_android_local_backup_contract(
        manifest_sources=discover_android_source_manifests(),
        backup_rule_sources=discover_android_backup_rule_sources(),
    )
    validate_android_room_storage_contract(read_text(ANDROID_ROOM_DATABASE_BUILDER_PATH))
    validate_ios_room_storage_contract(read_text(IOS_ROOM_DATABASE_BUILDER_PATH))


def verify_ios_observability_privacy_contract() -> None:
    info_plist_path = REPOSITORY_ROOT / IOS_INFO_PLIST_PATH
    try:
        with info_plist_path.open("rb") as info_plist_file:
            info_plist = plistlib.load(info_plist_file)
    except (OSError, plistlib.InvalidFileException) as error:
        raise RepositoryIntegrityError(
            f"{IOS_INFO_PLIST_PATH} is not a valid property list: {error}"
        ) from error

    validate_ios_observability_privacy_contract(
        info_plist=info_plist,
        observability_source=read_text(IOS_OBSERVABILITY_SOURCE_PATH),
        coordinator_source=read_text(IOS_ONBOARDING_COORDINATOR_PATH),
        content_view_source=read_text(IOS_CONTENT_VIEW_PATH),
        app_source=read_text(IOS_APP_SOURCE_PATH),
        xcode_project_source=read_text(IOS_XCODE_PROJECT_PATH),
    )
    validate_ios_firebase_source_boundary(
        {
            path.relative_to(REPOSITORY_ROOT).as_posix(): path.read_text(encoding="utf-8")
            for path in (REPOSITORY_ROOT / "iosApp").rglob("*")
            if path.is_file() and path.suffix.lower() in {".swift", ".m", ".mm", ".h"}
        }
    )


def validate_ios_observability_privacy_contract(
    *,
    info_plist: dict[str, object],
    observability_source: str,
    coordinator_source: str,
    content_view_source: str,
    app_source: str,
    xcode_project_source: str,
) -> None:
    """Lock the audited fail-closed iOS Firebase consent integration."""

    audited_sources = {
        IOS_OBSERVABILITY_SOURCE_PATH: observability_source,
        IOS_ONBOARDING_COORDINATOR_PATH: coordinator_source,
        IOS_CONTENT_VIEW_PATH: content_view_source,
        IOS_APP_SOURCE_PATH: app_source,
    }
    for source_path, source in audited_sources.items():
        require(
            audited_source_sha256(source) == IOS_PRIVACY_CRITICAL_SOURCE_SHA256[source_path],
            f"{source_path} changed outside its audited privacy snapshot; perform a new review "
            "before updating the expected SHA-256",
        )

    active_observability_source = strip_swift_comments_and_string_literals(observability_source)
    active_coordinator_source = strip_swift_comments_and_string_literals(coordinator_source)
    active_content_view_source = strip_swift_comments_and_string_literals(content_view_source)
    active_app_source = strip_swift_comments_and_string_literals(app_source)

    require(
        info_plist.get("FIREBASE_ANALYTICS_COLLECTION_ENABLED") is False,
        f"{IOS_INFO_PLIST_PATH} must disable Analytics before Firebase configuration",
    )
    require(
        info_plist.get("FirebaseCrashlyticsCollectionEnabled") is False,
        f"{IOS_INFO_PLIST_PATH} must disable automatic Crashlytics reporting",
    )
    require(
        info_plist.get("firebase_performance_instrumentation_enabled") is False,
        f"{IOS_INFO_PLIST_PATH} must disable automatic Performance instrumentation",
    )

    crashlytics_collection_arguments = re.findall(
        r"setCrashlyticsCollectionEnabled\s*\(\s*([^)]*?)\s*\)",
        active_observability_source,
    )
    require(
        crashlytics_collection_arguments
        and all(argument.strip() == "false" for argument in crashlytics_collection_arguments),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must keep every Crashlytics collection override false",
    )
    performance_instrumentation_arguments = [
        expression.strip()
        for expression in re.findall(
            r"\bisInstrumentationEnabled\s*=\s*([^\r\n;}]*)",
            active_observability_source,
        )
    ]
    require(
        performance_instrumentation_arguments
        and all(argument == "false" for argument in performance_instrumentation_arguments),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must keep automatic Performance instrumentation false",
    )
    analytics_collection_arguments = re.findall(
        r"setAnalyticsCollectionEnabled\s*\(\s*([^)]*?)\s*\)",
        active_observability_source,
    )
    require(
        analytics_collection_arguments
        and all(
            argument.strip() == "effectiveAnalyticsAllowed"
            for argument in analytics_collection_arguments
        ),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must gate every Analytics collection override",
    )
    performance_collection_arguments = [
        expression.strip()
        for expression in re.findall(
            r"\bisDataCollectionEnabled\s*=\s*([^\r\n;}]*)",
            active_observability_source,
        )
    ]
    require(
        performance_collection_arguments
        and "effectiveDiagnosticsAllowed" in performance_collection_arguments
        and all(
            argument in {"false", "effectiveDiagnosticsAllowed"}
            for argument in performance_collection_arguments
        ),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must gate every Performance collection override",
    )
    effective_collection_getters = {
        "Analytics": (
            r"private\s+var\s+effectiveAnalyticsAllowed\s*:\s*Bool\s*\{\s*"
            r"consent\.analyticsAllowed\s*&&\s*maintenanceAllowsCollection\s*\}"
        ),
        "diagnostics": (
            r"private\s+var\s+effectiveDiagnosticsAllowed\s*:\s*Bool\s*\{\s*"
            r"consent\.diagnosticsAllowed\s*&&\s*diagnosticsMaintenanceAllowsCollection\s*\}"
        ),
        "Remote Config": (
            r"private\s+var\s+effectiveRemoteConfigurationAllowed\s*:\s*Bool\s*\{\s*"
            r"consent\.remoteConfigurationAllowed\s*&&\s*maintenanceAllowsCollection\s*\}"
        ),
    }
    for collection_label, getter_pattern in effective_collection_getters.items():
        require(
            re.search(getter_pattern, active_observability_source) is not None,
            f"{IOS_OBSERVABILITY_SOURCE_PATH} must derive effective {collection_label} "
            "collection from consent and completed maintenance",
        )
    required_observability_tokens = {
        "import FirebaseInstallations",
        "try await Installations.installations().delete()",
        "firebase-installation-deletion",
        "markInstallationDeletionPending",
        "FirebaseInstallationDeletionIntent",
        "replaceConsent(ObservabilityConsent, ownerUserId: String)",
        "reconcileInstallationDeletionIntent",
        "expectedRequestID",
        "case .superseded",
        "firebase-diagnostics-report-purge",
        "markDiagnosticsReportPurgePending",
        "checkForUnsentReports()",
        "clearDiagnosticsReportPurgePending",
        "allCollectionRevoked",
        "maintenanceAllowsCollection",
        "retryPendingMaintenance",
        "sendUnsentReports()",
        "deleteUnsentReports()",
        "DiagnosticsReportAction",
        "FirebaseDiagnosticsReportPurgeProcessState",
        "persistConsentBeforePurge",
        "confirmedNoReportsPendingClear",
        "checkConsumed",
        "deletionRequested",
        "firebase-override-sanitization",
        "markCrashlyticsDisableScheduled",
        "resetConsentForFreshInstallation",
        "phase: .sanitized",
    }
    missing_observability_tokens = sorted(
        token for token in required_observability_tokens if token not in observability_source
    )
    require(
        not missing_observability_tokens,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} is missing audited consent controls: "
        + ", ".join(missing_observability_tokens),
    )
    maintenance_entry_points = (
        ("bindToAuthenticatedUser", "updateConsent"),
        ("updateConsent", "revokeAllConsent"),
        ("revokeAllConsent", "resetConsentForFreshInstallation"),
        ("retryPendingMaintenance", "suspendEffectiveConsent"),
    )
    suspension_pattern = re.compile(
        r"\bsuspendEffectiveConsent\s*\(\s*configureForMaintenance\s*:\s*false\s*\)"
    )
    refresh_pattern = re.compile(r"\brefreshPersistedMaintenanceState\s*\(\s*\)")
    for function_name, next_function_name in maintenance_entry_points:
        function_source = swift_function_region(
            active_observability_source,
            function_name,
            next_function_name,
        )
        suspension_match = suspension_pattern.search(function_source)
        refresh_match = refresh_pattern.search(function_source)
        require(
            suspension_match is not None
            and refresh_match is not None
            and refresh_match.start() > suspension_match.end(),
            f"{IOS_OBSERVABILITY_SOURCE_PATH} {function_name} must close configured SDKs "
            "before maintenance refresh",
        )

    bind_source = swift_function_region(
        active_observability_source,
        "bindToAuthenticatedUser",
        "updateConsent",
    )
    bind_refresh = refresh_pattern.search(bind_source)
    bind_reconciliation = re.search(
        r"guard\s+consentStore\.reconcileInstallationDeletionIntent\s*\(\s*\)\s+else",
        bind_source,
    )
    bind_read = re.search(r"switch\s+consentStore\.read\s*\(\s*\)", bind_source)
    require(
        bind_refresh is not None
        and bind_reconciliation is not None
        and bind_read is not None
        and bind_refresh.end() < bind_reconciliation.start() < bind_read.start(),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must reconcile durable FID intent before consent restore",
    )

    update_source = swift_function_region(
        active_observability_source,
        "updateConsent",
        "revokeAllConsent",
    )
    require(
        re.search(
            r"let\s+allCollectionRevoked\s*=\s*\(?\s*consent\.allowsAnyCollection\s*&&\s*"
            r"!\s*updatedConsent\.allowsAnyCollection\s*\)?",
            update_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must detect transition to complete collection revocation",
    )
    require(
        re.search(
            r"let\s+requiresDiagnosticsReportPurge\s*=\s*"
            r"diagnosticsReportAction\.requiresDurablePurge\s*\|\|\s*allCollectionRevoked",
            update_source,
        )
        is not None
        and re.search(
            r"let\s+persistConsentBeforePurge\s*=\s*"
            r"diagnosticsReportAction\s*==\s*\.revoked\s*\|\|\s*allCollectionRevoked",
            update_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must distinguish opt-in and revocation persistence order",
    )
    pre_persistence_purge_gate = re.search(
        r"if\s+!\s*persistConsentBeforePurge\s*,\s*requiresDiagnosticsReportPurge\s*,\s*"
        r"!\s*requestDiagnosticsReportPurge\s*\(\s*\)\s*\{",
        update_source,
    )
    installation_deletion_gate = re.search(
        r"if\s+remoteConfigurationRevoked\s*\|\|\s*allCollectionRevoked\s*\|\|\s*"
        r"installationDeletionState\.isPending\s*\{",
        update_source,
    )
    post_persistence_purge_gate = re.search(
        r"if\s+persistConsentBeforePurge\s*,\s*requiresDiagnosticsReportPurge\s*,\s*"
        r"!\s*requestDiagnosticsReportPurge\s*\(\s*\)\s*\{",
        update_source,
    )
    require(
        pre_persistence_purge_gate is not None
        and installation_deletion_gate is not None
        and post_persistence_purge_gate is not None
        and pre_persistence_purge_gate.end() < installation_deletion_gate.start()
        and installation_deletion_gate.end() < post_persistence_purge_gate.start(),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must keep crash-safe report purge ordering",
    )

    revoke_source = swift_function_region(
        active_observability_source,
        "revokeAllConsent",
        "resetConsentForFreshInstallation",
    )
    revoke_purge = re.search(r"\brequestDiagnosticsReportPurge\s*\(\s*\)", revoke_source)
    revoke_installation = re.search(
        r"\brequestFirebaseInstallationDeletion\s*\(\s*\.revokeConsent\s*\)",
        revoke_source,
    )
    require(
        revoke_purge is not None
        and revoke_installation is not None
        and revoke_installation.end() < revoke_purge.start(),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must persist revocation before report purge",
    )

    apply_consent_source = swift_function_region(
        active_observability_source,
        "applyConsent",
        "refreshPersistedMaintenanceState",
    )
    require(
        re.search(
            r"case\s+\.restored\s*:\s*if\s+effectiveDiagnosticsAllowed\s*\{\s*"
            r"crashlytics\.sendUnsentReports\s*\(\s*\)\s*\}",
            apply_consent_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must send reports only for effective restored consent",
    )
    require(
        len(re.findall(r"\.sendUnsentReports\s*\(\s*\)", active_observability_source)) == 1,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must have exactly one gated report-send call site",
    )

    diagnostics_purge_source = swift_function_region(
        active_observability_source,
        "resumePendingDiagnosticsReportPurge",
        "completeDiagnosticsReportPurge",
    )
    require(
        re.search(
            r"let\s+hasUnsentReports\s*=\s*await\s+crashlytics\.checkForUnsentReports\s*"
            r"\(\s*\).*?crashlytics\.deleteUnsentReports\s*\(\s*\)\s*"
            r"if\s+hasUnsentReports\s*\{\s*diagnosticsReportPurgeProcessState\s*=\s*"
            r"\.deletionRequested\s*return\s*\}\s*diagnosticsReportPurgeProcessState\s*=\s*"
            r"\.confirmedNoReportsPendingClear\s*completeDiagnosticsReportPurge\s*\(\s*\)",
            diagnostics_purge_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must provide delete action and retain non-empty purge",
    )
    require(
        re.search(
            r"case\s+\.checking\s*,\s*\.checkConsumed\s*,\s*\.deletionRequested\s*:\s*return",
            diagnostics_purge_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must defer every later same-process purge to restart",
    )
    require(
        len(
            re.findall(
                r"\bcompleteDiagnosticsReportPurge\s*\(\s*\)",
                diagnostics_purge_source,
            )
        )
        == 2
        and len(
            re.findall(
                r"\bcheckForUnsentReports\s*\(\s*\)",
                active_observability_source,
            )
        )
        == 1,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must have only the two audited purge completions",
    )
    diagnostics_purge_completion_source = swift_function_region(
        active_observability_source,
        "completeDiagnosticsReportPurge",
        "requestFirebaseInstallationDeletion",
    )
    require(
        re.search(
            r"guard\s+consentStore\.clearDiagnosticsReportPurgePending\s*\(\s*\)\s+else",
            diagnostics_purge_completion_source,
        )
        is not None
        and re.search(
            r"diagnosticsReportPurgeState\s*=\s*\.notRequired\s*"
            r"diagnosticsReportPurgeProcessState\s*=\s*\.checkConsumed",
            diagnostics_purge_completion_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must clear report purge only in its completion path",
    )
    require(
        len(
            re.findall(
                r"\bconsentStore\.clearDiagnosticsReportPurgePending\s*\(\s*\)",
                active_observability_source,
            )
        )
        == 1,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must have one durable report-purge clear call site",
    )

    diagnostics_purge_request_source = swift_function_region(
        active_observability_source,
        "requestDiagnosticsReportPurge",
        "resumePendingDiagnosticsReportPurge",
    )
    require(
        re.search(
            r"guard\s+consentStore\.markDiagnosticsReportPurgePending\s*\(\s*\)\s+else",
            diagnostics_purge_request_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must persist diagnostics purge before processing it",
    )

    installation_request_source = swift_function_region(
        active_observability_source,
        "requestFirebaseInstallationDeletion",
        "resumePendingFirebaseInstallationDeletion",
    )
    installation_marker = re.search(
        r"guard\s+consentStore\.markInstallationDeletionPending\s*"
        r"\(\s*intent\s*:\s*intent\s*\)\s+else\s*\{",
        installation_request_source,
    )
    installation_reconciliation = re.search(
        r"return\s+consentStore\.reconcileInstallationDeletionIntent\s*\(\s*\)",
        installation_request_source,
    )
    require(
        installation_marker is not None
        and installation_reconciliation is not None
        and installation_marker.end() < installation_reconciliation.start(),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must persist FID intent before reconciliation",
    )

    installation_resume_source = swift_function_region(
        active_observability_source,
        "resumePendingFirebaseInstallationDeletion",
        "advanceOverrideSanitizationAfterConfiguration",
    )
    resume_reconciliation = re.search(
        r"guard\s+consentStore\.reconcileInstallationDeletionIntent\s*\(\s*\)\s+else\s*"
        r"\{\s*return\s*\}",
        installation_resume_source,
    )
    installation_delete = re.search(
        r"try\s+await\s+Installations\.installations\s*\(\s*\)\.delete\s*\(\s*\)",
        installation_resume_source,
    )
    installation_completion = re.search(
        r"consentStore\.completeInstallationDeletion\s*\(\s*expectedRequestID\s*:\s*"
        r"deletionRequest\.requestID\s*\)",
        installation_resume_source,
    )
    require(
        resume_reconciliation is not None
        and installation_delete is not None
        and installation_completion is not None
        and resume_reconciliation.end()
        < installation_delete.start()
        < installation_completion.start(),
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must reconcile, delete, then acknowledge the FID",
    )
    require(
        len(
            re.findall(
                r"try\s+await\s+Installations\.installations\s*\(\s*\)\.delete\s*\(\s*\)",
                active_observability_source,
            )
        )
        == 1,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must have exactly one reconciled FID delete call site",
    )
    require(
        len(
            re.findall(
                r"consentStore\.completeInstallationDeletion\s*\(",
                active_observability_source,
            )
        )
        == 1,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must have exactly one post-delete FID acknowledgement",
    )

    installation_completion_source = swift_function_region(
        active_observability_source,
        "completeInstallationDeletion",
        "prepareOverrideSanitization",
    )
    require(
        re.fullmatch(
            r"func\s+completeInstallationDeletion\s*\(\s*expectedRequestID\s*:\s*String\s*\)\s*"
            r"->\s*FirebaseInstallationDeletionCompletion\s*\{\s*"
            r"switch\s+readData\s*\(\s*account\s*:\s*installationDeletionKeychainAccount\s*\)"
            r"\s*\{\s*case\s+\.missing\s*:\s*installationDeletionState\s*=\s*\.notRequired\s*"
            r"return\s+\.completed\s*case\s+\.failure\s*:\s*return\s+\.failure\s*"
            r"case\s+let\s+\.data\s*\(\s*data\s*\)\s*:\s*guard\s+let\s+record\s*=\s*"
            r"decodeInstallationDeletionRecord\s*\(\s*data\s*\)\s+else\s*\{\s*"
            r"installationDeletionState\s*=\s*\.failure\s*return\s+\.failure\s*\}\s*"
            r"guard\s+record\.requestID\s*==\s*expectedRequestID\s+else\s*\{\s*"
            r"installationDeletionState\s*=\s*\.pending\s*\(\s*record\s*\)\s*"
            r"return\s+\.superseded\s*\}\s*guard\s+remove\s*\(\s*account\s*:\s*"
            r"installationDeletionKeychainAccount\s*\)\s+else\s*\{\s*return\s+\.failure\s*\}\s*"
            r"installationDeletionState\s*=\s*\.notRequired\s*return\s+\.completed\s*\}\s*\}\s*",
            installation_completion_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must validate, remove, and then complete the current FID",
    )

    override_preparation_source = swift_function_region(
        active_observability_source,
        "prepareOverrideSanitization",
        "writeSanitizedOverrideState",
    )
    corrupted_override_source = swift_function_region(
        active_observability_source,
        "repairCorruptedOverrideSanitizationMarker",
        "prepareDiagnosticsReportPurgeState",
    )
    for migration_source, migration_label in (
        (override_preparation_source, "missing override state"),
        (corrupted_override_source, "corrupted override state"),
    ):
        purge_marker = re.search(
            r"\bmarkDiagnosticsReportPurgePending\s*\(\s*\)", migration_source
        )
        fid_marker = re.search(r"\bmarkInstallationDeletionPending\s*\(\s*\)", migration_source)
        require(
            purge_marker is not None
            and fid_marker is not None
            and purge_marker.end() < fid_marker.start(),
            f"{IOS_OBSERVABILITY_SOURCE_PATH} must purge reports before {migration_label}",
        )

    override_requirement_source = swift_function_region(
        active_observability_source,
        "requireOverrideSanitization",
        "requestDiagnosticsReportPurge",
    )
    require(
        re.search(
            r"guard\s+consentStore\.requireOverrideSanitization\s*\([^)]*"
            r"configuredProcessToken\s*:\s*isConfigured\s*\?\s*"
            r"firebaseObservabilityProcessToken\s*:\s*nil\s*\)\s+else\s*\{\s*"
            r"overrideSanitizationState\s*=\s*\.failure\s*return\s+false\s*\}\s*"
            r"overrideSanitizationState\s*=\s*consentStore\.overrideSanitizationState\s*"
            r"return\s+true",
            override_requirement_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must atomically schedule configured override restart",
    )

    override_advance_source = swift_function_region(
        active_observability_source,
        "advanceOverrideSanitizationAfterConfiguration",
        "configureRemoteConfig",
    )
    require(
        len(re.findall(r"overrideSanitizationState\s*=\s*\.failure", override_advance_source))
        >= 2
        and re.search(
            r"guard\s+consentStore\.markCrashlyticsDisableScheduled",
            override_advance_source,
        )
        is not None
        and re.search(
            r"guard\s+consentStore\.clearOverrideSanitizationMarker\s*\(\s*\)\s+else",
            override_advance_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must fail closed on override marker persistence errors",
    )
    configure_source = swift_function_region(
        active_observability_source,
        "configureIfNeeded",
        "track",
    )
    require(
        re.search(
            r"func\s+configureIfNeeded\s*\([^)]*\)\s*\{\s*if\s+isConfigured\s*\{\s*"
            r"advanceOverrideSanitizationAfterConfiguration\s*\(\s*\)\s*return\s*\}",
            configure_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must retry override transition when already configured",
    )

    consent_store_start = active_observability_source.find("final class FirebaseConsentStore")
    require(consent_store_start >= 0, f"Missing FirebaseConsentStore in {IOS_OBSERVABILITY_SOURCE_PATH}")
    consent_store_source = active_observability_source[consent_store_start:]
    store_override_requirement_source = swift_function_region(
        consent_store_source,
        "requireOverrideSanitization",
        "markCrashlyticsDisableScheduled",
    )
    require(
        re.search(
            r"case\s+\.awaitingRestart\s*,\s*\.readyAfterRestart\s*:\s*return\s+true",
            store_override_requirement_source,
        )
        is not None
        and re.search(
            r"let\s+phase\s*:\s*FirebaseOverrideSanitizationPhase\s*=\s*"
            r"configuredProcessToken\s*==\s*nil\s*\?\s*\.requiresSafeConfiguration\s*:\s*"
            r"\.awaitingRestart",
            store_override_requirement_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must not regress an override restart already scheduled",
    )
    store_override_preparation_source = swift_function_region(
        consent_store_source,
        "prepareOverrideSanitization",
        "writeSanitizedOverrideState",
    )
    require(
        re.search(
            r"guard\s+let\s+scheduledProcessToken\s*=\s*record\.processToken\s+else\s*\{\s*"
            r"return\s+repairCorruptedOverrideSanitizationMarker\s*\(\s*\)\s*\}",
            store_override_preparation_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must repair an awaiting-restart record without token",
    )

    store_diagnostics_marker_source = swift_function_region(
        consent_store_source,
        "markDiagnosticsReportPurgePending",
        "clearDiagnosticsReportPurgePending",
    )
    require(
        re.search(
            r"func\s+markDiagnosticsReportPurgePending\s*\(\s*forceRewrite\s*:\s*Bool\s*=\s*"
            r"false\s*\)\s*->\s*Bool\s*\{\s*if\s+diagnosticsReportPurgeState\s*==\s*"
            r"\.pending\s*,\s*!\s*forceRewrite\s*\{\s*return\s+true\s*\}\s*"
            r"let\s+record\s*=\s*StoredFirebaseDiagnosticsReportPurgeRecord.*?"
            r"writeData\s*\(\s*data\s*,\s*account\s*:\s*"
            r"diagnosticsReportPurgeKeychainAccount\s*\).*?"
            r"diagnosticsReportPurgeState\s*=\s*\.pending\s*return\s+true",
            store_diagnostics_marker_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must durably write diagnostics purge before success",
    )

    store_installation_marker_source = swift_function_region(
        consent_store_source,
        "markInstallationDeletionPending",
        "reconcileInstallationDeletionIntent",
    )
    require(
        re.search(
            r"func\s+markInstallationDeletionPending\s*\([^)]*\)\s*->\s*Bool\s*\{\s*"
            r"if\s+case\s+\.preserveConsent\s*=\s*intent\s*,\s*case\s+\.pending\s*=\s*"
            r"installationDeletionState\s*\{\s*return\s+true\s*\}.*?"
            r"let\s+record\s*=\s*StoredFirebaseInstallationDeletionRecord.*?"
            r"return\s+persistInstallationDeletionRecord\s*\(\s*record\s*\)",
            store_installation_marker_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must durably write typed FID intent before success",
    )

    store_installation_reconciliation_source = swift_function_region(
        consent_store_source,
        "reconcileInstallationDeletionIntent",
        "completeInstallationDeletion",
    )
    require(
        re.fullmatch(
            r"func\s+reconcileInstallationDeletionIntent\s*\(\s*\)\s*->\s*Bool\s*\{\s*"
            r"guard\s+case\s+let\s+\.pending\s*\(\s*record\s*\)\s*=\s*"
            r"installationDeletionState\s+else\s*\{\s*return\s+installationDeletionState\s*"
            r"==\s*\.notRequired\s*\}\s*switch\s+record\.consentMutation\s*\{\s*"
            r"case\s+\.preserve\s*:\s*return\s+true\s*case\s+\.replace\s*:\s*"
            r"guard\s+let\s+replacementConsent\s*=\s*record\.replacementConsent\s*,\s*"
            r"isValidReplacementConsent\s*\(\s*replacementConsent\s*\)\s*,\s*"
            r"let\s+data\s*=\s*try\?\s*JSONEncoder\s*\(\s*\)\.encode\s*"
            r"\(\s*replacementConsent\s*\)\s+else\s*\{\s*installationDeletionState\s*=\s*"
            r"\.failure\s*return\s+false\s*\}\s*return\s+writeData\s*\(\s*data\s*,\s*"
            r"account\s*:\s*consentKeychainAccount\s*\)\s*case\s+\.revoke\s*:\s*"
            r"return\s+revoke\s*\(\s*\)\s*\}\s*\}\s*",
            store_installation_reconciliation_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must reconcile every typed FID consent mutation",
    )

    store_installation_preparation_source = swift_function_region(
        consent_store_source,
        "prepareInstallationDeletionState",
        "storedConsentRecord",
    )
    require(
        re.fullmatch(
            r"func\s+prepareInstallationDeletionState\s*\(\s*\)\s*->\s*"
            r"FirebaseInstallationDeletionState\s*\{\s*switch\s+readData\s*\(\s*account\s*:\s*"
            r"installationDeletionKeychainAccount\s*\)\s*\{\s*case\s+\.missing\s*:\s*"
            r"return\s+\.notRequired\s*case\s+\.failure\s*:\s*return\s+\.failure\s*"
            r"case\s+let\s+\.data\s*\(\s*data\s*\)\s*:\s*if\s+let\s+record\s*=\s*"
            r"decodeInstallationDeletionRecord\s*\(\s*data\s*\)\s*\{\s*return\s+"
            r"\.pending\s*\(\s*record\s*\)\s*\}\s*let\s+recoveryIntent\s*:\s*"
            r"FirebaseInstallationDeletionIntent\s*=\s*\.revokeConsent\s*return\s+"
            r"markInstallationDeletionPending\s*\(\s*intent\s*:\s*recoveryIntent\s*\)\s*"
            r"\?\s*installationDeletionState\s*:\s*\.failure\s*\}\s*\}\s*",
            store_installation_preparation_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must restore or safely recover every durable FID intent",
    )

    store_installation_persistence_source = swift_function_region(
        consent_store_source,
        "persistInstallationDeletionRecord",
        "decodeInstallationDeletionRecord",
    )
    require(
        re.search(
            r"func\s+persistInstallationDeletionRecord\s*\([^)]*\)\s*->\s*Bool\s*\{\s*"
            r"guard\s+let\s+data\s*=\s*try\?\s*JSONEncoder\s*\(\s*\)\.encode\s*"
            r"\(\s*record\s*\)\s*,\s*writeData\s*\(\s*data\s*,\s*account\s*:\s*"
            r"installationDeletionKeychainAccount\s*\)\s*else\s*\{\s*return\s+false\s*\}\s*"
            r"installationDeletionState\s*=\s*\.pending\s*\(\s*record\s*\)\s*return\s+true",
            store_installation_persistence_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must persist and publish the typed FID record before success",
    )

    store_installation_decoder_source = swift_function_region(
        consent_store_source,
        "decodeInstallationDeletionRecord",
        "isValidReplacementConsent",
    )
    require(
        re.fullmatch(
            r"func\s+decodeInstallationDeletionRecord\s*\(\s*_\s+data\s*:\s*Data\s*\)\s*"
            r"->\s*StoredFirebaseInstallationDeletionRecord\?\s*\{\s*guard\s+let\s+record\s*=\s*"
            r"try\?\s*JSONDecoder\s*\(\s*\)\.decode\s*\(\s*"
            r"StoredFirebaseInstallationDeletionRecord\.self\s*,\s*from\s*:\s*data\s*\)\s*,\s*"
            r"record\.schemaVersion\s*==\s*installationDeletionSchemaVersion\s*,\s*"
            r"!\s*record\.requestID\.trimmingCharacters\s*\(\s*in\s*:\s*"
            r"\.whitespacesAndNewlines\s*\)\.isEmpty\s+else\s*\{\s*return\s+nil\s*\}\s*"
            r"switch\s+record\.consentMutation\s*\{\s*case\s+\.preserve\s*,\s*\.revoke\s*:\s*"
            r"return\s+record\.replacementConsent\s*==\s*nil\s*\?\s*record\s*:\s*nil\s*"
            r"case\s+\.replace\s*:\s*guard\s+let\s+replacementConsent\s*=\s*"
            r"record\.replacementConsent\s*,\s*isValidReplacementConsent\s*"
            r"\(\s*replacementConsent\s*\)\s+else\s*\{\s*return\s+nil\s*\}\s*"
            r"return\s+record\s*\}\s*\}\s*",
            store_installation_decoder_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must validate the complete typed FID record",
    )

    keychain_write_source = swift_function_region(
        consent_store_source,
        "writeData",
        "remove",
    )
    require(
        re.fullmatch(
            r"func\s+writeData\s*\(\s*_\s+data\s*:\s*Data\s*,\s*account\s*:\s*String\s*\)\s*"
            r"->\s*Bool\s*\{\s*let\s+query\s*=\s*baseQuery\s*\(\s*account\s*:\s*account\s*\)\s*"
            r"let\s+updateStatus\s*=\s*SecItemUpdate\s*\(\s*query\s+as\s+CFDictionary\s*,\s*"
            r"\[\s*kSecValueData\s+as\s+String\s*:\s*data\s*\]\s+as\s+CFDictionary\s*\)\s*"
            r"if\s+updateStatus\s*==\s*errSecSuccess\s*\{\s*return\s+true\s*\}\s*"
            r"guard\s+updateStatus\s*==\s*errSecItemNotFound\s+else\s*\{\s*return\s+false\s*\}\s*"
            r"var\s+insert\s*=\s*query\s*insert\s*\[\s*kSecValueData\s+as\s+String\s*\]\s*=\s*data\s*"
            r"insert\s*\[\s*kSecAttrAccessible\s+as\s+String\s*\]\s*=\s*"
            r"kSecAttrAccessibleWhenUnlockedThisDeviceOnly\s*return\s+SecItemAdd\s*"
            r"\(\s*insert\s+as\s+CFDictionary\s*,\s*nil\s*\)\s*==\s*errSecSuccess\s*\}\s*",
            keychain_write_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must report Keychain writes only after update or insert success",
    )

    keychain_read_source = swift_function_region(
        consent_store_source,
        "readData",
        "writeData",
    )
    require(
        re.fullmatch(
            r"func\s+readData\s*\(\s*account\s*:\s*String\s*\)\s*->\s*"
            r"FirebaseKeychainReadResult\s*\{\s*var\s+query\s*=\s*baseQuery\s*"
            r"\(\s*account\s*:\s*account\s*\)\s*query\s*\[\s*kSecReturnData\s+as\s+String\s*\]"
            r"\s*=\s*true\s*query\s*\[\s*kSecMatchLimit\s+as\s+String\s*\]\s*=\s*"
            r"kSecMatchLimitOne\s*var\s+item\s*:\s*CFTypeRef\?\s*let\s+status\s*=\s*"
            r"SecItemCopyMatching\s*\(\s*query\s+as\s+CFDictionary\s*,\s*&\s*item\s*\)\s*"
            r"if\s+status\s*==\s*errSecItemNotFound\s*\{\s*return\s+\.missing\s*\}\s*"
            r"guard\s+status\s*==\s*errSecSuccess\s*,\s*let\s+data\s*=\s*item\s+as\?\s+Data\s*"
            r"else\s*\{\s*return\s+\.failure\s*\}\s*return\s+\.data\s*\(\s*data\s*\)\s*\}\s*",
            keychain_read_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must distinguish missing, failed, and stored Keychain data",
    )

    keychain_remove_source = swift_function_region(
        consent_store_source,
        "remove",
        "baseQuery",
    )
    require(
        re.fullmatch(
            r"func\s+remove\s*\(\s*account\s*:\s*String\s*\)\s*->\s*Bool\s*\{\s*"
            r"let\s+status\s*=\s*SecItemDelete\s*\(\s*baseQuery\s*\(\s*account\s*:\s*account\s*\)"
            r"\s+as\s+CFDictionary\s*\)\s*return\s+status\s*==\s*errSecSuccess\s*\|\|\s*"
            r"status\s*==\s*errSecItemNotFound\s*\}\s*",
            keychain_remove_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must report Keychain removal only after delete or absence",
    )

    require(
        re.search(
            r"func\s+baseQuery\s*\(\s*account\s*:\s*String\s*\)\s*->\s*\[\s*String\s*:\s*Any\s*\]"
            r"\s*\{\s*\[\s*kSecClass\s+as\s+String\s*:\s*kSecClassGenericPassword\s*,\s*"
            r"kSecAttrService\s+as\s+String\s*:\s*service\s*,\s*kSecAttrAccount\s+as\s+String\s*:\s*"
            r"account\s*,?\s*\]\s*\}",
            consent_store_source,
            re.DOTALL,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must isolate every Keychain record by service and account",
    )

    require(
        re.search(
            r"private\s+var\s+maintenanceAllowsCollection\s*:\s*Bool\s*\{\s*"
            r"authenticatedSessionBound\s*&&\s*!\s*runtimeCollectionSuspended\s*&&\s*"
            r"overrideSanitizationState\.allowsCollection\s*&&\s*"
            r"installationDeletionState\s*==\s*\.notRequired\s*\}",
            active_observability_source,
        )
        is not None
        and re.search(
            r"private\s+var\s+diagnosticsMaintenanceAllowsCollection\s*:\s*Bool\s*\{\s*"
            r"maintenanceAllowsCollection\s*&&\s*"
            r"diagnosticsReportPurgeState\s*==\s*\.notRequired\s*\}",
            active_observability_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must gate collection on completed durable maintenance",
    )
    require(
        re.search(
            r"var\s+requiresDurablePurge\s*:\s*Bool\s*\{\s*switch\s+self\s*\{\s*"
            r"case\s+\.newlyGranted\s*,\s*\.revoked\s*:\s*return\s+true\s*"
            r"case\s+\.none\s*,\s*\.restored\s*:\s*return\s+false\s*\}\s*\}",
            active_observability_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must purge both diagnostics opt-in and revocation",
    )
    require(
        re.search(
            r"var\s+allowsAnyCollection\s*:\s*Bool\s*\{\s*analyticsAllowed\s*\|\|\s*"
            r"diagnosticsAllowed\s*\|\|\s*remoteConfigurationAllowed\s*\}",
            active_observability_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must detect every active collection category",
    )
    require(
        re.search(
            r"diagnosticsReportPurgeProcessState\s*:\s*"
            r"FirebaseDiagnosticsReportPurgeProcessState\s*=\s*\.notChecked",
            active_observability_source,
        )
        is not None,
        f"{IOS_OBSERVABILITY_SOURCE_PATH} must start each process before any report check",
    )
    require(
        "observability.resetConsentForFreshInstallation()" in active_coordinator_source,
        f"{IOS_ONBOARDING_COORDINATOR_PATH} must clear surviving consent before fresh-install restore",
    )
    handle_auth_state_start = active_coordinator_source.find("private func handleAuthState")
    apply_standard_auth_state_start = active_coordinator_source.find(
        "private func applyStandardAuthState"
    )
    handle_auth_state_source = active_coordinator_source[
        handle_auth_state_start:apply_standard_auth_state_start
    ]
    fresh_install_guard_offset = handle_auth_state_source.find(
        "guard freshInstallSessionCleanupCompleted else"
    )
    first_observability_bind_offset = handle_auth_state_source.find("bindObservability(to: nil)")
    require(
        handle_auth_state_start >= 0
        and apply_standard_auth_state_start > handle_auth_state_start
        and fresh_install_guard_offset >= 0
        and first_observability_bind_offset > fresh_install_guard_offset,
        f"{IOS_ONBOARDING_COORDINATOR_PATH} must not bind Firebase before fresh-install cleanup",
    )
    require(
        "coordinator.applicationBecameActive()" in active_app_source,
        f"{IOS_APP_SOURCE_PATH} must retry durable Firebase maintenance on foreground",
    )
    application_became_active_start = active_coordinator_source.find("func applicationBecameActive()")
    complete_intro_start = active_coordinator_source.find("func completeIntro")
    application_became_active_source = active_coordinator_source[
        application_became_active_start:complete_intro_start
    ]
    require(
        application_became_active_start >= 0
        and complete_intro_start > application_became_active_start
        and "guard freshInstallSessionCleanupCompleted else" in application_became_active_source,
        f"{IOS_ONBOARDING_COORDINATOR_PATH} must not retry Firebase before fresh-install cleanup",
    )
    require(
        "productName = FirebaseInstallations;" in xcode_project_source
        and "FirebaseInstallations in Frameworks" in xcode_project_source,
        f"{IOS_XCODE_PROJECT_PATH} must link FirebaseInstallations explicitly",
    )
    required_category_updates = {
        "onConsentChanged(.analytics, allowed)",
        "onConsentChanged(.diagnostics, allowed)",
        "onConsentChanged(.remoteConfiguration, allowed)",
    }
    missing_category_updates = sorted(
        token for token in required_category_updates if token not in active_content_view_source
    )
    require(
        not missing_category_updates,
        f"{IOS_CONTENT_VIEW_PATH} must emit category intents instead of stale consent snapshots: "
        + ", ".join(missing_category_updates),
    )


def collected_data_type(
    data_type: str,
    purposes: list[str] | None = None,
) -> dict[str, object]:
    return {
        "NSPrivacyCollectedDataType": data_type,
        "NSPrivacyCollectedDataTypeLinked": True,
        "NSPrivacyCollectedDataTypePurposes": purposes or [IOS_APP_FUNCTIONALITY_PURPOSE],
        "NSPrivacyCollectedDataTypeTracking": False,
    }


def main() -> int:
    try:
        verify_configuration_templates()
        verify_gradle_wrapper()
        verify_git_hygiene()
        verify_android_firebase_privacy_contract()
        verify_local_storage_privacy_contract()
        verify_ios_privacy_manifest()
        verify_ios_observability_privacy_contract()
    except RepositoryIntegrityError as error:
        print(f"ERROR repository integrity: {error}", file=sys.stderr)
        return 1

    print(
        "OK repository integrity: configuration templates complete, "
        "sensitive artifacts untracked, Gradle 9.4.1 wrapper checksummed, "
        "Android/iOS audited Firebase privacy sources, local backup policy, "
        "and iOS host manifest locked"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
