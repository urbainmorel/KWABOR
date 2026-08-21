"""Pure standard-library tests for the closed-beta pilot gate."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = ROOT / "tools" / "validate-closed-beta-pilot-evidence.py"
SPEC = importlib.util.spec_from_file_location("closed_beta_pilot_validator", VALIDATOR_PATH)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import bootstrap guard
    raise RuntimeError("Unable to load the closed-beta pilot validator.")
VALIDATOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VALIDATOR
SPEC.loader.exec_module(VALIDATOR)


def proof(slug: str) -> dict[str, str]:
    digest = hashlib.sha256(slug.encode("ascii")).hexdigest()
    return {
        "sha256": digest,
        "uri": f"urn:kwabor:evidence:ev-{digest[:32]}",
    }


def participant_ids() -> list[str]:
    return [f"T-A{index:02d}" for index in range(1, 11)] + [
        f"T-I{index:02d}" for index in range(1, 6)
    ]


def device_id_for(participant_id: str) -> str:
    return participant_id.replace("T-", "D-", 1)


def samples(duration_ms: int = 1_400) -> list[dict[str, int | str]]:
    return [
        {
            "duration_ms": duration_ms,
            "index": index,
            "mode": "cold" if index <= 10 else "warm",
        }
        for index in range(1, 31)
    ]


def valid_document() -> dict[str, object]:
    rc_id = "RC-001"
    identifiers = participant_ids()
    participants = []
    devices = []
    for identifier in identifiers:
        platform = "android" if identifier.startswith("T-A") else "ios"
        device_id = device_id_for(identifier)
        participants.append(
            {
                "consent": {"analytics": True, "diagnostics": True},
                "device_id": device_id,
                "id": identifier,
                "platform": platform,
                "proof": proof(f"participant-{identifier.lower()}"),
                "rc_id": rc_id,
            }
        )
        devices.append(
            {
                "id": device_id,
                "model_code": "Pixel_6a" if platform == "android" else "iPhone_13",
                "os_version": "Android_14" if platform == "android" else "iOS_17_6",
                "participant_id": identifier,
                "physical": True,
                "platform": platform,
                "proof": proof(f"device-{device_id.lower()}"),
                "rc_id": rc_id,
            }
        )

    first_day = date(2026, 1, 2)
    session_counts = [29, 29, 29, 29, 28, 28, 28]
    crash_counts = [1, 0, 0, 0, 0, 0, 0]
    days = []
    for offset, (session_count, crash_count) in enumerate(
        zip(session_counts, crash_counts, strict=True)
    ):
        current = first_day + timedelta(days=offset)
        following = current + timedelta(days=1)
        days.append(
            {
                "day": offset + 1,
                "ended_at_utc": f"{following.isoformat()}T00:00:00Z",
                "participant_ids": identifiers,
                "proof": proof(f"cohort-j{offset + 1}"),
                "rc_id": rc_id,
                "run_id": "RUN-001",
                "sessions_observed": session_count,
                "sessions_with_crash": crash_count,
                "started_at_utc": f"{current.isoformat()}T00:00:00Z",
            }
        )

    return {
        "accessibility": [
            {
                "announcements": True,
                "assistive_technology": "talkback",
                "contrast_aa": True,
                "device_id": "D-A01",
                "focus_order": True,
                "labels": True,
                "physical": True,
                "platform": "android",
                "proof": proof("accessibility-android"),
                "rc_id": rc_id,
                "touch_targets": True,
            },
            {
                "announcements": True,
                "assistive_technology": "voiceover",
                "contrast_aa": True,
                "device_id": "D-I01",
                "focus_order": True,
                "labels": True,
                "physical": True,
                "platform": "ios",
                "proof": proof("accessibility-ios"),
                "rc_id": rc_id,
                "touch_targets": True,
            },
        ],
        "canary": {
            "ended_at_utc": "2026-01-01T22:00:00Z",
            "participant_ids": ["T-A01", "T-A02", "T-I01"],
            "proof": proof("canary"),
            "rc_id": rc_id,
            "started_at_utc": "2026-01-01T20:00:00Z",
        },
        "catalog": {
            "broken_media": 0,
            "duplicate_listings": 0,
            "fictitious_cta_violations": 0,
            "listings": 60,
            "media": 180,
            "missing_listings": 0,
            "proof": proof("catalog"),
            "rc_id": rc_id,
        },
        "cohort_days": days,
        "critical_checks": {
            "account_deletion": {
                "passed": True,
                "proof": proof("account-deletion"),
                "rc_id": rc_id,
            },
            "consent_revocation": {
                "passed": True,
                "proof": proof("consent-revocation"),
                "rc_id": rc_id,
                "zero_events_after_revocation": True,
            },
        },
        "decision": {
            "all_signed": True,
            "declared": "go",
            "proof": proof("decision"),
            "rc_id": rc_id,
            "review_roles": ["content", "product", "security-privacy", "technical"],
            "run_id": "RUN-001",
        },
        "devices": devices,
        "evaluated_at_utc": "2026-01-09T00:00:00Z",
        "fictitious": False,
        "incidents": [],
        "participants": participants,
        "performance": [
            {
                "clock": "monotonic",
                "device_id": "D-A01",
                "network": {"down_kbps": 1_600, "rtt_ms": 150, "up_kbps": 750},
                "outliers_removed": False,
                "p75_limit_ms": 1_500,
                "p75_method": "nearest-rank",
                "physical": True,
                "platform": "android",
                "proof": proof("performance-android"),
                "rc_id": rc_id,
                "samples": samples(),
            },
            {
                "clock": "monotonic",
                "device_id": "D-I01",
                "network": {"down_kbps": 1_600, "rtt_ms": 150, "up_kbps": 750},
                "outliers_removed": False,
                "p75_limit_ms": 1_500,
                "p75_method": "nearest-rank",
                "physical": True,
                "platform": "ios",
                "proof": proof("performance-ios"),
                "rc_id": rc_id,
                "samples": samples(),
            },
        ],
        "release_candidate": {
            "build_id": "beta-001",
            "environment": "staging",
            "expected_sha": "a" * 40,
            "proofs": {
                "android_distribution": proof("release-android"),
                "ci": proof("release-ci"),
                "ios_distribution": proof("release-ios"),
            },
            "rc_id": rc_id,
            "version_name": "1.0.0",
        },
        "run_reset": {
            "active_run_id": "RUN-001",
            "carryover_sessions": 0,
            "confirmed_no_carryover": True,
            "generation": 0,
            "j1_started_at_utc": "2026-01-02T00:00:00Z",
            "previous_runs": [],
            "proof": proof("run-reset"),
        },
        "schema_version": 1,
    }


def failure_codes(document: dict[str, object]) -> set[str]:
    return {failure["code"] for failure in VALIDATOR.validate_document(document)["failures"]}


class ClosedBetaPilotValidatorTest(unittest.TestCase):
    def test_valid_boundary_evidence_is_go(self) -> None:
        receipt = VALIDATOR.validate_document(valid_document())

        self.assertEqual("go", receipt["status"])
        self.assertTrue(receipt["eligible_for_go"])
        self.assertEqual([], receipt["failures"])
        self.assertEqual("99.5000", receipt["summary"]["sessions"]["crash_free_percent"])
        self.assertEqual(1_400, receipt["summary"]["performance"]["android"]["p75_ms"])

    def test_report_is_deterministic(self) -> None:
        document = valid_document()
        self.assertEqual(
            VALIDATOR.validate_document(document),
            VALIDATOR.validate_document(copy.deepcopy(document)),
        )

    def test_fictitious_complete_evidence_can_never_be_go(self) -> None:
        document = valid_document()
        document["fictitious"] = True
        self.assertIn("FICTITIOUS_EVIDENCE", failure_codes(document))

    def test_duplicate_fictitious_true_false_keys_are_always_no_go(self) -> None:
        raw_documents = (
            b'{"schema_version":1,"fictitious":true,"fictitious":false}',
            b'{"schema_version":1,"fictitious":false,"fictitious":true}',
        )
        receipts = []
        with tempfile.TemporaryDirectory() as directory:
            for index, raw_document in enumerate(raw_documents):
                with self.subTest(index=index):
                    evidence_path = Path(directory) / f"duplicate-{index}.json"
                    evidence_path.write_bytes(raw_document)
                    completed = subprocess.run(
                        [sys.executable, "-B", str(VALIDATOR_PATH), str(evidence_path)],
                        check=False,
                        capture_output=True,
                        text=True,
                    )
                    receipt = json.loads(completed.stdout)
                    receipts.append(receipt)
                    self.assertEqual(1, completed.returncode, completed.stderr)
                    self.assertFalse(receipt["eligible_for_go"])
                    self.assertEqual("JSON_DUPLICATE_KEY", receipt["failures"][0]["code"])
                    self.assertIsNone(receipt["source_sha256"])
                    self.assertEqual(
                        hashlib.sha256(raw_document).hexdigest(),
                        receipt["source_bytes_sha256"],
                    )
        self.assertNotEqual(receipts[0]["source_bytes_sha256"], receipts[1]["source_bytes_sha256"])

    def test_participant_and_device_pseudonyms_must_be_unique(self) -> None:
        document = valid_document()
        document["participants"][1]["id"] = document["participants"][0]["id"]
        document["devices"][1]["id"] = document["devices"][0]["id"]
        codes = failure_codes(document)
        self.assertIn("PARTICIPANT_DUPLICATE", codes)
        self.assertIn("DEVICE_DUPLICATE", codes)

    def test_participant_and_device_pseudonym_sets_are_exact(self) -> None:
        participant_document = valid_document()
        participant_document["participants"][0]["id"] = "T-A11"
        self.assertIn("PARTICIPANT_ID_SET", failure_codes(participant_document))

        device_document = valid_document()
        device_document["devices"][0]["id"] = "D-A11"
        self.assertIn("DEVICE_ID_SET", failure_codes(device_document))

    def test_199_sessions_is_no_go(self) -> None:
        document = valid_document()
        document["cohort_days"][-1]["sessions_observed"] = 27
        document["cohort_days"][0]["sessions_with_crash"] = 0
        self.assertIn("SESSION_MINIMUM", failure_codes(document))

    def test_99_49_percent_crash_free_is_no_go(self) -> None:
        document = valid_document()
        session_counts = [1_429, 1_429, 1_429, 1_429, 1_428, 1_428, 1_428]
        crash_counts = [8, 8, 7, 7, 7, 7, 7]
        for day, sessions_observed, sessions_with_crash in zip(
            document["cohort_days"], session_counts, crash_counts, strict=True
        ):
            day["sessions_observed"] = sessions_observed
            day["sessions_with_crash"] = sessions_with_crash
        receipt = VALIDATOR.validate_document(document)
        self.assertEqual("99.4900", receipt["summary"]["sessions"]["crash_free_percent"])
        self.assertIn("CRASH_FREE_THRESHOLD", {item["code"] for item in receipt["failures"]})

    def test_p75_equal_to_1500_is_no_go(self) -> None:
        document = valid_document()
        durations = [1_499] * 22 + [1_500] * 8
        for sample, duration in zip(document["performance"][0]["samples"], durations, strict=True):
            sample["duration_ms"] = duration
        self.assertIn("PERFORMANCE_P75_THRESHOLD", failure_codes(document))

    def test_mixed_release_candidate_is_no_go(self) -> None:
        document = valid_document()
        document["participants"][0]["rc_id"] = "RC-002"
        self.assertIn("MIXED_RELEASE_CANDIDATE", failure_codes(document))

    def test_canary_must_not_overlap_j1(self) -> None:
        document = valid_document()
        document["canary"]["ended_at_utc"] = "2026-01-02T01:00:00Z"
        self.assertIn("CANARY_COHORT_OVERLAP", failure_codes(document))

    def test_missing_day_or_reset_is_no_go(self) -> None:
        without_day = valid_document()
        without_day["cohort_days"].pop()
        self.assertIn("COHORT_DAY_COUNT", failure_codes(without_day))

        without_reset = valid_document()
        del without_reset["run_reset"]
        self.assertIn("REQUIRED_FIELD_MISSING", failure_codes(without_reset))

    def test_reset_requires_a_proven_prior_p0_or_p1_release_change(self) -> None:
        restarted = valid_document()
        restarted["run_reset"]["generation"] = 1
        restarted["run_reset"]["previous_runs"] = [
            {
                "expected_sha": "b" * 40,
                "generation": 0,
                "proof": proof("prior-run-release-change"),
                "rc_id": "RC-000",
                "reason": "P1",
                "run_id": "RUN-000",
            }
        ]
        self.assertTrue(VALIDATOR.validate_document(restarted)["eligible_for_go"])

        unchanged = copy.deepcopy(restarted)
        unchanged["run_reset"]["previous_runs"][0]["expected_sha"] = "a" * 40
        unchanged["run_reset"]["previous_runs"][0]["rc_id"] = "RC-001"
        self.assertIn("RESET_SUCCESSOR_RELEASE_NOT_NEW", failure_codes(unchanged))

        same_sha = copy.deepcopy(restarted)
        same_sha["run_reset"]["previous_runs"][0]["expected_sha"] = "a" * 40
        self.assertIn("RESET_SUCCESSOR_RELEASE_NOT_NEW", failure_codes(same_sha))

        same_rc = copy.deepcopy(restarted)
        same_rc["run_reset"]["previous_runs"][0]["rc_id"] = "RC-001"
        self.assertIn("RESET_SUCCESSOR_RELEASE_NOT_NEW", failure_codes(same_rc))

        missing_proof = copy.deepcopy(restarted)
        del missing_proof["run_reset"]["previous_runs"][0]["proof"]
        self.assertIn("REQUIRED_FIELD_MISSING", failure_codes(missing_proof))

    def test_reset_compares_run_003_to_immediate_run_002_not_old_run_001(self) -> None:
        document = valid_document()
        document["run_reset"]["active_run_id"] = "RUN-003"
        document["run_reset"]["generation"] = 2
        document["run_reset"]["previous_runs"] = [
            {
                "expected_sha": "b" * 40,
                "generation": 0,
                "proof": proof("run-001-p0"),
                "rc_id": "RC-101",
                "reason": "P0",
                "run_id": "RUN-001",
            },
            {
                "expected_sha": "a" * 40,
                "generation": 1,
                "proof": proof("run-002-p1"),
                "rc_id": "RC-001",
                "reason": "P1",
                "run_id": "RUN-002",
            },
        ]
        for day in document["cohort_days"]:
            day["run_id"] = "RUN-003"
        document["decision"]["run_id"] = "RUN-003"

        self.assertIn("RESET_SUCCESSOR_RELEASE_NOT_NEW", failure_codes(document))

        fixed = copy.deepcopy(document)
        fixed["run_reset"]["previous_runs"][1]["expected_sha"] = "c" * 40
        fixed["run_reset"]["previous_runs"][1]["rc_id"] = "RC-102"
        self.assertTrue(VALIDATOR.validate_document(fixed)["eligible_for_go"])

    def test_reset_forbids_global_rc_sha_and_pair_recycling(self) -> None:
        def three_run_document(first_rc: str, first_sha: str) -> dict[str, object]:
            document = valid_document()
            document["run_reset"]["active_run_id"] = "RUN-003"
            document["run_reset"]["generation"] = 2
            document["run_reset"]["previous_runs"] = [
                {
                    "expected_sha": first_sha,
                    "generation": 0,
                    "proof": proof("global-reuse-run-001"),
                    "rc_id": first_rc,
                    "reason": "P0",
                    "run_id": "RUN-001",
                },
                {
                    "expected_sha": "c" * 40,
                    "generation": 1,
                    "proof": proof("global-reuse-run-002"),
                    "rc_id": "RC-102",
                    "reason": "P1",
                    "run_id": "RUN-002",
                },
            ]
            for day in document["cohort_days"]:
                day["run_id"] = "RUN-003"
            document["decision"]["run_id"] = "RUN-003"
            return document

        recycled_pair = three_run_document("RC-001", "a" * 40)
        pair_codes = failure_codes(recycled_pair)
        self.assertIn("RESET_RELEASE_CANDIDATE_REUSED", pair_codes)
        self.assertIn("RESET_EXPECTED_SHA_REUSED", pair_codes)
        self.assertIn("RESET_RELEASE_PAIR_REUSED", pair_codes)
        self.assertNotIn("RESET_SUCCESSOR_RELEASE_NOT_NEW", pair_codes)

        recycled_rc = three_run_document("RC-001", "b" * 40)
        rc_codes = failure_codes(recycled_rc)
        self.assertIn("RESET_RELEASE_CANDIDATE_REUSED", rc_codes)
        self.assertNotIn("RESET_EXPECTED_SHA_REUSED", rc_codes)
        self.assertNotIn("RESET_RELEASE_PAIR_REUSED", rc_codes)

        recycled_sha = three_run_document("RC-101", "a" * 40)
        sha_codes = failure_codes(recycled_sha)
        self.assertIn("RESET_EXPECTED_SHA_REUSED", sha_codes)
        self.assertNotIn("RESET_RELEASE_CANDIDATE_REUSED", sha_codes)
        self.assertNotIn("RESET_RELEASE_PAIR_REUSED", sha_codes)

    def test_p0_and_p1_are_independently_blocking(self) -> None:
        for severity in ("P0", "P1"):
            with self.subTest(severity=severity):
                document = valid_document()
                document["incidents"] = [
                    {
                        "detected_at_utc": "2026-01-04T12:00:00Z",
                        "id": "INC-001",
                        "proof": proof(f"incident-{severity.lower()}"),
                        "rc_id": "RC-001",
                        "run_id": "RUN-001",
                        "severity": severity,
                        "status": "open",
                    }
                ]
                self.assertIn("BLOCKING_INCIDENT", failure_codes(document))

    def test_incident_timestamps_are_calendar_valid_and_ordered(self) -> None:
        reversed_resolution = valid_document()
        reversed_resolution["incidents"] = [
            {
                "detected_at_utc": "2026-01-04T12:00:00Z",
                "id": "INC-001",
                "proof": proof("incident-reversed-resolution"),
                "rc_id": "RC-001",
                "resolved_at_utc": "2026-01-04T11:59:59Z",
                "run_id": "RUN-001",
                "severity": "P2",
                "status": "closed",
            }
        ]
        self.assertIn("INCIDENT_RESOLUTION_ORDER", failure_codes(reversed_resolution))

        invalid_calendar = valid_document()
        invalid_calendar["incidents"] = [
            {
                "detected_at_utc": "2026-02-30T12:00:00Z",
                "id": "INC-001",
                "proof": proof("incident-invalid-calendar"),
                "rc_id": "RC-001",
                "run_id": "RUN-001",
                "severity": "P2",
                "status": "open",
            }
        ]
        self.assertIn("TIMESTAMP_INVALID", failure_codes(invalid_calendar))

    def test_incident_open_closed_resolution_contract(self) -> None:
        closed_without_resolution = valid_document()
        closed_without_resolution["incidents"] = [
            {
                "detected_at_utc": "2026-01-04T12:00:00Z",
                "id": "INC-001",
                "proof": proof("incident-closed-missing-resolution"),
                "rc_id": "RC-001",
                "run_id": "RUN-001",
                "severity": "P2",
                "status": "closed",
            }
        ]
        self.assertIn("INCIDENT_RESOLUTION_MISSING", failure_codes(closed_without_resolution))

        open_with_resolution = copy.deepcopy(closed_without_resolution)
        open_with_resolution["incidents"][0]["status"] = "open"
        open_with_resolution["incidents"][0]["resolved_at_utc"] = "2026-01-04T13:00:00Z"
        self.assertIn("INCIDENT_OPEN_WITH_RESOLUTION", failure_codes(open_with_resolution))

    def test_unknown_and_pii_fields_are_rejected(self) -> None:
        document = valid_document()
        document["participants"][0]["email"] = "pilot@example.test"
        receipt = VALIDATOR.validate_document(document)
        codes = {failure["code"] for failure in receipt["failures"]}
        self.assertIn("UNKNOWN_FIELD", codes)
        self.assertIn("PII_OR_SECRET_FIELD", codes)
        self.assertIn("PII_EMAIL", codes)
        self.assertNotIn("pilot@example.test", json.dumps(receipt))

    def test_dangerous_evidence_uris_are_rejected(self) -> None:
        dangerous = {
            "query": "https://evidence.example.test/pilot.json?token=value",
            "empty-query": "https://evidence.example.test/pilot.json?",
            "empty-fragment": "https://evidence.example.test/pilot.json#",
            "credentials": "https://operator:password@evidence.example.test/pilot.json",
            "ip": "https://192.0.2.1/pilot.json",
            "encoded-path": "https://evidence.example.test/pilot%2Fsecret.json",
            "double-encoded-path": "https://evidence.example.test/ev-0123456789abcdef%252fsecret",
            "person-name": "urn:kwabor:evidence:Alice_Dupont",
            "encoded-person-name": "urn:kwabor:evidence:Alice%5FDupont",
            "phone": "urn:kwabor:evidence:33612345678",
            "hostname-pii": "https://alice-dupont.example/ev-0123456789abcdef0123456789abcdef",
            "malformed": "https://[invalid/pilot.json",
        }
        expected_codes = {
            "query": "EVIDENCE_URI_CREDENTIAL",
            "empty-query": "EVIDENCE_URI_CREDENTIAL",
            "empty-fragment": "EVIDENCE_URI_CREDENTIAL",
            "credentials": "EVIDENCE_URI_SENSITIVE",
            "ip": "EVIDENCE_URI_SENSITIVE",
            "encoded-path": "EVIDENCE_URI_ENCODING",
            "double-encoded-path": "EVIDENCE_URI_ENCODING",
            "person-name": "EVIDENCE_URI_OPAQUE",
            "encoded-person-name": "EVIDENCE_URI_ENCODING",
            "phone": "EVIDENCE_URI_SENSITIVE",
            "hostname-pii": "EVIDENCE_URI_SCHEME",
            "malformed": "EVIDENCE_URI_SCHEME",
        }
        for label, uri in dangerous.items():
            with self.subTest(label=label):
                document = valid_document()
                document["catalog"]["proof"]["uri"] = uri
                receipt = VALIDATOR.validate_document(document)
                self.assertIn(expected_codes[label], {item["code"] for item in receipt["failures"]})
                self.assertNotIn(uri, json.dumps(receipt))

    def test_evidence_urn_suffix_must_match_declared_sha256(self) -> None:
        document = valid_document()
        document["catalog"]["proof"]["uri"] = "urn:kwabor:evidence:ev-00000000000000000000000000000000"
        self.assertIn("EVIDENCE_URI_SHA_LINK", failure_codes(document))

    def test_evidence_urn_is_globally_immutable_and_unique(self) -> None:
        shared_prefix = "0123456789abcdef" * 2
        conflicting = valid_document()
        conflicting["canary"]["proof"] = {
            "sha256": shared_prefix + "a" * 32,
            "uri": f"urn:kwabor:evidence:ev-{shared_prefix}",
        }
        conflicting["catalog"]["proof"] = {
            "sha256": shared_prefix + "b" * 32,
            "uri": f"urn:kwabor:evidence:ev-{shared_prefix}",
        }
        conflict_codes = failure_codes(conflicting)
        self.assertIn("EVIDENCE_URI_REUSED", conflict_codes)
        self.assertIn("EVIDENCE_URI_SHA_CONFLICT", conflict_codes)
        self.assertNotIn("EVIDENCE_URI_SHA_LINK", conflict_codes)

        duplicated = valid_document()
        duplicated["catalog"]["proof"] = copy.deepcopy(duplicated["canary"]["proof"])
        duplicate_codes = failure_codes(duplicated)
        self.assertIn("EVIDENCE_URI_REUSED", duplicate_codes)
        self.assertIn("EVIDENCE_SHA256_REUSED", duplicate_codes)

    def test_contractual_numeric_hex_urn_suffix_is_not_scanned_as_phone(self) -> None:
        document = valid_document()
        numeric_prefix = "12345678901234567890123456789012"
        document["catalog"]["proof"] = {
            "sha256": numeric_prefix + "a" * 32,
            "uri": f"urn:kwabor:evidence:ev-{numeric_prefix}",
        }
        receipt = VALIDATOR.validate_document(document)
        self.assertTrue(receipt["eligible_for_go"], receipt["failures"])

    def test_missing_consent_is_no_go(self) -> None:
        document = valid_document()
        document["participants"][0]["consent"]["analytics"] = False
        self.assertIn("PARTICIPANT_CONSENT", failure_codes(document))

    def test_cli_writes_same_deterministic_receipt_it_prints(self) -> None:
        document = valid_document()
        with tempfile.TemporaryDirectory() as directory:
            evidence_path = Path(directory) / "evidence.json"
            receipt_path = Path(directory) / "receipt.json"
            evidence_path.write_text(json.dumps(document), encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, "-B", str(VALIDATOR_PATH), str(evidence_path), "--receipt", str(receipt_path)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            receipt = json.loads(completed.stdout)
            self.assertEqual(receipt, json.loads(receipt_path.read_text(encoding="utf-8")))
            source_bytes = evidence_path.read_bytes()
            canonical_bytes = json.dumps(
                document,
                ensure_ascii=True,
                separators=(",", ":"),
                sort_keys=True,
            ).encode("utf-8")
            self.assertEqual(hashlib.sha256(source_bytes).hexdigest(), receipt["source_bytes_sha256"])
            self.assertEqual(hashlib.sha256(canonical_bytes).hexdigest(), receipt["source_sha256"])
            self.assertNotEqual(receipt["source_bytes_sha256"], receipt["source_sha256"])

    def test_schema_closes_all_objects_and_resolves_local_refs(self) -> None:
        schema_path = ROOT / "docs" / "templates" / "closed-beta-pilot-evidence.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        definitions = schema["$defs"]

        def visit(value: object, path: str) -> None:
            if isinstance(value, list):
                for index, nested in enumerate(value):
                    visit(nested, f"{path}[{index}]")
                return
            if not isinstance(value, dict):
                return
            if value.get("type") == "object":
                self.assertIs(False, value.get("additionalProperties"), path)
            reference = value.get("$ref")
            if isinstance(reference, str) and reference.startswith("#/$defs/"):
                self.assertIn(reference.removeprefix("#/$defs/"), definitions, path)
            for key, nested in value.items():
                visit(nested, f"{path}.{key}")

        visit(schema, "$")
        self.assertEqual(
            VALIDATOR.EXPECTED_PARTICIPANT_IDS,
            frozenset(definitions["participantId"]["enum"]),
        )
        self.assertEqual(
            VALIDATOR.EXPECTED_DEVICE_IDS,
            frozenset(definitions["deviceId"]["enum"]),
        )

    def test_fictitious_incomplete_repository_example_is_stable_no_go(self) -> None:
        evidence_path = ROOT / "demo" / "pilot" / "v1" / "pilot-evidence.example.json"
        expected_path = ROOT / "demo" / "pilot" / "v1" / "go-no-go.example.json"
        source_bytes = evidence_path.read_bytes()
        evidence = json.loads(source_bytes)
        expected = json.loads(expected_path.read_text(encoding="utf-8"))
        actual = VALIDATOR.validate_document(evidence, source_bytes=source_bytes)

        self.assertTrue(evidence["fictitious"])
        self.assertEqual("no-go", actual["status"])
        self.assertFalse(actual["eligible_for_go"])
        self.assertEqual(expected, actual)

        completed = subprocess.run(
            [sys.executable, "-B", str(VALIDATOR_PATH), str(evidence_path)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(1, completed.returncode, completed.stderr)
        self.assertEqual(expected, json.loads(completed.stdout))


if __name__ == "__main__":
    unittest.main()
