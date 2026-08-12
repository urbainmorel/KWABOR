#!/usr/bin/env python3
"""Prepare and statically validate an Android App Bundle JAR signature layout."""

from __future__ import annotations

import argparse
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
from zipfile import BadZipFile, ZipFile, ZipInfo


SIGNATURE_BLOCK_SUFFIXES = (".RSA", ".DSA", ".EC")


class BundlePreparationError(RuntimeError):
    """Raised when an input bundle cannot be transformed safely."""


def _require_regular_file(path: Path, label: str) -> None:
    if not path.is_file() or path.is_symlink():
        raise BundlePreparationError(f"{label} must be a regular file: {path}")


def _validated_infos(bundle: ZipFile) -> list[ZipInfo]:
    infos = bundle.infolist()
    if not infos:
        raise BundlePreparationError("The Android App Bundle is empty")

    seen: set[str] = set()
    seen_casefolded: set[str] = set()
    for info in infos:
        name = info.filename
        pure_name = PurePosixPath(name)
        if (
            not name
            or "\\" in name
            or pure_name.is_absolute()
            or ".." in pure_name.parts
        ):
            raise BundlePreparationError(f"Unsafe Android App Bundle entry: {name!r}")
        folded = name.casefold()
        if name in seen or folded in seen_casefolded:
            raise BundlePreparationError(f"Duplicate Android App Bundle entry: {name!r}")
        seen.add(name)
        seen_casefolded.add(folded)
    return infos


def _root_signature_kind(name: str) -> str | None:
    parts = name.split("/")
    if len(parts) != 2 or parts[0].upper() != "META-INF":
        return None

    basename = parts[1].upper()
    if basename == "MANIFEST.MF":
        return "manifest"
    if basename.endswith(".SF"):
        return "signature_file"
    if basename.endswith(SIGNATURE_BLOCK_SUFFIXES) or basename.startswith("SIG-"):
        return "signature_block"
    return None


def _manifest_entry_names(manifest: bytes) -> set[str]:
    normalized = manifest.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    physical_lines = normalized.split(b"\n")
    logical_lines: list[bytes] = []
    for line in physical_lines:
        if line.startswith(b" "):
            if not logical_lines:
                raise BundlePreparationError("JAR manifest starts with an invalid continuation line")
            logical_lines[-1] += line[1:]
        else:
            logical_lines.append(line)

    names: set[str] = set()
    for line in logical_lines:
        if not line.lower().startswith(b"name: "):
            continue
        try:
            name = line[6:].decode("utf-8")
        except UnicodeDecodeError as error:
            raise BundlePreparationError("JAR manifest contains a non-UTF-8 entry name") from error
        if not name or name in names:
            raise BundlePreparationError(f"JAR manifest contains an invalid duplicate name: {name!r}")
        names.add(name)
    return names


def strip_jar_signatures(source: Path, output: Path) -> tuple[str, ...]:
    """Copy an AAB while removing every root META-INF JAR signature entry."""

    source = source.resolve(strict=False)
    output = output.resolve(strict=False)
    _require_regular_file(source, "Source bundle")
    if source == output:
        raise BundlePreparationError("Source and output bundle paths must be different")
    output.parent.mkdir(parents=True, exist_ok=True)

    temporary_path: Path | None = None
    try:
        with ZipFile(source, "r") as source_bundle:
            infos = _validated_infos(source_bundle)
            removed = tuple(
                info.filename for info in infos if _root_signature_kind(info.filename) is not None
            )

            temporary = tempfile.NamedTemporaryFile(
                mode="wb",
                prefix=f".{output.name}.",
                suffix=".tmp",
                dir=output.parent,
                delete=False,
            )
            temporary_path = Path(temporary.name)
            temporary.close()

            with ZipFile(temporary_path, "w", allowZip64=True) as output_bundle:
                output_bundle.comment = source_bundle.comment
                for info in infos:
                    if _root_signature_kind(info.filename) is None:
                        output_bundle.writestr(info, source_bundle.read(info))

        output_mode = stat.S_IMODE(source.stat().st_mode)
        os.chmod(temporary_path, output_mode)
        os.replace(temporary_path, output)
        temporary_path = None
        return removed
    except (BadZipFile, OSError) as error:
        raise BundlePreparationError(f"Unable to prepare Android App Bundle: {error}") from error
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def verify_single_jar_signature(bundle_path: Path) -> None:
    """Require the one-signer root META-INF layout produced by jarsigner."""

    bundle_path = bundle_path.resolve(strict=False)
    _require_regular_file(bundle_path, "Signed bundle")
    try:
        with ZipFile(bundle_path, "r") as bundle:
            infos = _validated_infos(bundle)
    except (BadZipFile, OSError) as error:
        raise BundlePreparationError(f"Unable to inspect signed Android App Bundle: {error}") from error

    signature_infos = [
        (info, kind)
        for info in infos
        if (kind := _root_signature_kind(info.filename)) is not None
    ]
    signature_kinds = [kind for _, kind in signature_infos]
    expected_counts = {
        "manifest": 1,
        "signature_file": 1,
        "signature_block": 1,
    }
    actual_counts = {kind: signature_kinds.count(kind) for kind in expected_counts}
    if actual_counts != expected_counts:
        raise BundlePreparationError(
            "Signed bundle must contain exactly one JAR signer layout; "
            f"found {actual_counts}"
        )

    manifest_info = next(info for info, kind in signature_infos if kind == "manifest")
    try:
        with ZipFile(bundle_path, "r") as bundle:
            manifest_names = _manifest_entry_names(bundle.read(manifest_info))
    except (BadZipFile, OSError) as error:
        raise BundlePreparationError(f"Unable to read signed JAR manifest: {error}") from error
    signable_entries = {
        info.filename
        for info in infos
        if not info.is_dir() and _root_signature_kind(info.filename) is None
    }
    if manifest_names != signable_entries:
        missing = sorted(signable_entries - manifest_names)
        stale = sorted(manifest_names - signable_entries)
        raise BundlePreparationError(
            "Signed bundle manifest must cover every non-signature file exactly; "
            f"missing={missing[:3]}, stale={stale[:3]}"
        )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    strip_parser = subparsers.add_parser(
        "strip", description="Remove existing JAR signature metadata into a new AAB"
    )
    strip_parser.add_argument("source", type=Path)
    strip_parser.add_argument("output", type=Path)

    verify_parser = subparsers.add_parser(
        "verify", description="Verify that an AAB has exactly one JAR signer layout"
    )
    verify_parser.add_argument("bundle", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        if args.command == "strip":
            removed = strip_jar_signatures(args.source, args.output)
            print(f"OK prepared unsigned AAB; removed {len(removed)} signature entries")
        else:
            verify_single_jar_signature(args.bundle)
            print("OK AAB contains exactly one JAR signer layout")
    except BundlePreparationError as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
