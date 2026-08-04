import subprocess
import unittest
from unittest.mock import patch

from tools.detect_ios_validation_scope import (
    changed_files,
    changes_require_ios_validation,
    normalize_repository_path,
    requires_ios_validation,
)


class IosValidationScopeTest(unittest.TestCase):
    def test_shared_ios_and_build_inputs_require_validation(self) -> None:
        relevant_paths = (
            "shared/src/commonMain/kotlin/com/kwabor/shared/domain/Search.kt",
            "iosApp/Kwabor/App/KwaborApp.swift",
            "gradle/libs.versions.toml",
            "buildSrc/src/main/kotlin/ConventionPlugin.kt",
            ".github/actions/mobile/action.yml",
            ".github/workflows/ci.yml",
            "build.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "settings.gradle.kts",
            "tools/detect_ios_validation_scope.py",
            "tools/test_detect_ios_validation_scope.py",
        )

        self.assertTrue(all(requires_ios_validation(path) for path in relevant_paths))

    def test_backend_docs_and_android_only_changes_skip_validation(self) -> None:
        unrelated_paths = (
            "BACKLOG.md",
            "docs/V1-PROGRESS.md",
            "supabase/migrations/20260804170000_history.sql",
            "supabase/tests/search_history_test.sql",
            "androidApp/src/main/kotlin/com/kwabor/android/MainActivity.kt",
        )

        self.assertFalse(any(requires_ios_validation(path) for path in unrelated_paths))
        self.assertFalse(changes_require_ios_validation(unrelated_paths))

    def test_unknown_paths_run_ios_by_default(self) -> None:
        self.assertTrue(requires_ios_validation("scripts/new-build-hook.sh"))

    def test_any_relevant_change_keeps_the_full_matrix(self) -> None:
        self.assertTrue(
            changes_require_ios_validation(
                (
                    "docs/V1-PROGRESS.md",
                    "shared/src/commonMain/kotlin/com/kwabor/shared/domain/Search.kt",
                ),
            ),
        )

    def test_repository_paths_are_normalized_before_matching(self) -> None:
        self.assertEqual(
            "shared/src/commonMain/Search.kt",
            normalize_repository_path("./shared\\src\\commonMain\\Search.kt"),
        )
        self.assertTrue(requires_ios_validation("./shared\\src\\commonMain\\Search.kt"))

    @patch("tools.detect_ios_validation_scope.subprocess.run")
    def test_changed_files_keeps_both_sides_of_renames(self, run_mock) -> None:
        run_mock.return_value = subprocess.CompletedProcess(
            args=(),
            returncode=0,
            stdout=b"iosApp/Old.swift\0docs/New.md\0",
        )

        self.assertEqual(
            ("iosApp/Old.swift", "docs/New.md"),
            changed_files("base", "head"),
        )
        command = run_mock.call_args.args[0]
        self.assertIn("--no-renames", command)
        self.assertTrue(run_mock.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
