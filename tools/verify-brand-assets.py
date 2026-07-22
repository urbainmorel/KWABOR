#!/usr/bin/env python3
"""Verify the provenance and wiring of Kwabor's committed brand assets.

The native operating-system splash icon and the App Store icon are derived from
``kwabor_icone_app.png``. The full launch wordmark is deliberately kept as an
exact copy of ``kwabor_2.png`` so no renderer can crop, recolor, or redraw it.
"""

from __future__ import annotations

import hashlib
import json
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class BrandVerificationError(RuntimeError):
    """Raised when a committed brand invariant is not satisfied."""


@dataclass(frozen=True)
class PngSpec:
    path: str
    width: int
    height: int
    mode: str
    sha256: str
    source: str | None = None


MASTER_SPECS = (
    PngSpec(
        path="kwabor_1.png",
        width=2172,
        height=724,
        mode="RGB",
        sha256="53297451c88f2a6867ccade7854dad3e511066cd458f25184171ca467894a125",
    ),
    PngSpec(
        path="kwabor_2.png",
        width=2172,
        height=724,
        mode="RGBA",
        sha256="cfbc2b928dbd9f41dce41c95d0051b7b4db664f3851bb8d4a70dd59a23421b46",
    ),
    PngSpec(
        path="kwabor_icone_app.png",
        width=1254,
        height=1254,
        mode="RGB",
        sha256="0d8366c53ea001869ee52e3175788ce9820952360c3b7e1706b3930e52c218e5",
    ),
)

ICON_MASTER = "kwabor_icone_app.png"
WORDMARK_MASTER = "kwabor_2.png"

DERIVED_ICON_SPECS = (
    PngSpec(
        path=(
            "iosApp/Kwabor/Resources/Assets.xcassets/"
            "AppIcon.appiconset/AppIcon-1024.png"
        ),
        width=1024,
        height=1024,
        mode="RGB",
        sha256="5e611d8093b7fa4d62dfae9574852ee2763fef30d3a60d1505087ea8170306d5",
        source=ICON_MASTER,
    ),
    PngSpec(
        path=(
            "iosApp/Kwabor/Resources/Assets.xcassets/"
            "LaunchMark.imageset/LaunchMark-1x.png"
        ),
        width=108,
        height=108,
        mode="RGB",
        sha256="6191af48506d4c319b8ab69d403b9abb4c317843a716e82c17cbb720c354e7b2",
        source=ICON_MASTER,
    ),
    PngSpec(
        path=(
            "iosApp/Kwabor/Resources/Assets.xcassets/"
            "LaunchMark.imageset/LaunchMark-2x.png"
        ),
        width=216,
        height=216,
        mode="RGB",
        sha256="a8ba33c16541339eb053d2eb3c339866b75b9159cd015e9408c31c082e752f4f",
        source=ICON_MASTER,
    ),
    PngSpec(
        path=(
            "iosApp/Kwabor/Resources/Assets.xcassets/"
            "LaunchMark.imageset/LaunchMark-3x.png"
        ),
        width=324,
        height=324,
        mode="RGB",
        sha256="1c3efcbc6f3566c24242c49a88d9742b142a589a078cec8b91382c032c04510f",
        source=ICON_MASTER,
    ),
)

ANDROID_ICON_OUTPUTS = {
    "mdpi": (
        108,
        "fccdad11d4e44ed5b968a5fdcdc82b137803812436d3a90631ef9708652a6ae3",
    ),
    "hdpi": (
        162,
        "5e557d4a15b5a4fb5a1535531853c5bc2c0a1d7cb21d7f4a0bafe41848fbfd66",
    ),
    "xhdpi": (
        216,
        "f0751f327d38a3d5de9d0045fe308319d88ab953242fca99e5deba640513545c",
    ),
    "xxhdpi": (
        324,
        "34c223389307f45629ef0996a53244e1b1f903d0e799761f22155223ddb457f5",
    ),
    "xxxhdpi": (
        432,
        "77a424a66525055c14a8de9300eb89fd205605b4de5e097e4501b2b1aab877e0",
    ),
}

WORDMARK_PATHS = (
    "androidApp/src/main/res/drawable-nodpi/kwabor_launch_wordmark.png",
    (
        "iosApp/Kwabor/Resources/Assets.xcassets/"
        "LaunchWordmark.imageset/LaunchWordmark.png"
    ),
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BrandVerificationError(message)


def repository_path(relative_path: str) -> Path:
    return REPOSITORY_ROOT / Path(relative_path)


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def read_png_metadata(path: Path) -> tuple[bytes, int, int, str]:
    require(path.is_file(), f"Missing brand asset: {path}")
    payload = path.read_bytes()
    require(payload.startswith(PNG_SIGNATURE), f"Invalid PNG signature: {path}")

    offset = len(PNG_SIGNATURE)
    ihdr: bytes | None = None
    saw_iend = False
    while offset < len(payload):
        require(offset + 12 <= len(payload), f"Truncated PNG chunk header: {path}")
        length = int.from_bytes(payload[offset : offset + 4], "big")
        chunk_type = payload[offset + 4 : offset + 8]
        data_start = offset + 8
        data_end = data_start + length
        crc_end = data_end + 4
        require(crc_end <= len(payload), f"Truncated PNG chunk: {path}")

        chunk_data = payload[data_start:data_end]
        expected_crc = int.from_bytes(payload[data_end:crc_end], "big")
        actual_crc = zlib.crc32(chunk_type + chunk_data) & 0xFFFFFFFF
        require(actual_crc == expected_crc, f"Invalid PNG chunk CRC: {path}")

        if chunk_type == b"IHDR":
            require(ihdr is None, f"Duplicate PNG IHDR chunk: {path}")
            ihdr = chunk_data
        if chunk_type == b"IEND":
            require(length == 0, f"Invalid PNG IEND chunk: {path}")
            saw_iend = True
            require(crc_end == len(payload), f"Trailing data after PNG IEND: {path}")
            break
        offset = crc_end

    require(ihdr is not None and len(ihdr) == 13, f"Missing PNG IHDR: {path}")
    require(saw_iend, f"Missing PNG IEND: {path}")
    width, height, bit_depth, color_type, compression, filtering, interlace = (
        struct.unpack(">IIBBBBB", ihdr)
    )
    require(bit_depth == 8, f"Brand PNG must use 8-bit channels: {path}")
    require(compression == 0, f"Unsupported PNG compression method: {path}")
    require(filtering == 0, f"Unsupported PNG filtering method: {path}")
    require(interlace == 0, f"Brand PNG must be non-interlaced: {path}")
    modes = {2: "RGB", 6: "RGBA"}
    require(color_type in modes, f"Brand PNG must be RGB or RGBA: {path}")
    return payload, width, height, modes[color_type]


def verify_png(spec: PngSpec) -> bytes:
    path = repository_path(spec.path)
    payload, width, height, mode = read_png_metadata(path)
    require(
        (width, height) == (spec.width, spec.height),
        f"Unexpected dimensions for {spec.path}: {width}x{height}",
    )
    require(mode == spec.mode, f"Unexpected mode for {spec.path}: {mode}")
    digest = sha256(payload)
    require(
        digest == spec.sha256,
        f"Unexpected SHA-256 for {spec.path}: {digest}",
    )
    return payload


def verify_masters() -> dict[str, bytes]:
    masters = {spec.path: verify_png(spec) for spec in MASTER_SPECS}
    width = next(spec.width for spec in MASTER_SPECS if spec.path == WORDMARK_MASTER)
    height = next(spec.height for spec in MASTER_SPECS if spec.path == WORDMARK_MASTER)
    require(width == 3 * height, f"{WORDMARK_MASTER} must have a 3:1 ratio")
    return masters


def verify_icon_derivatives() -> None:
    for spec in DERIVED_ICON_SPECS:
        require(spec.source == ICON_MASTER, f"Invalid icon provenance: {spec.path}")
        verify_png(spec)

    for density, (size, expected_hash) in ANDROID_ICON_OUTPUTS.items():
        paths = (
            f"androidApp/src/main/res/drawable-{density}/kwabor_brand_mark.png",
            f"androidApp/src/main/res/drawable-{density}/kwabor_launch_mark.png",
        )
        for path in paths:
            verify_png(
                PngSpec(
                    path=path,
                    width=size,
                    height=size,
                    mode="RGB",
                    sha256=expected_hash,
                    source=ICON_MASTER,
                )
            )


def verify_wordmarks(master_payload: bytes) -> None:
    for relative_path in WORDMARK_PATHS:
        path = repository_path(relative_path)
        payload, width, height, mode = read_png_metadata(path)
        require(width == 2172 and height == 724, f"Unexpected wordmark size: {path}")
        require(width == 3 * height, f"Launch wordmark must keep a 3:1 ratio: {path}")
        require(mode == "RGBA", f"Launch wordmark must keep its RGBA mode: {path}")
        require(
            payload == master_payload,
            f"Launch wordmark is not a byte-exact copy of {WORDMARK_MASTER}: {path}",
        )


def load_json(relative_path: str) -> dict[str, Any]:
    path = repository_path(relative_path)
    require(path.is_file(), f"Missing asset catalog metadata: {path}")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BrandVerificationError(f"Invalid JSON metadata: {path}: {error}") from error
    require(isinstance(document, dict), f"JSON root must be an object: {path}")
    return document


def verify_imageset(relative_path: str, expected: dict[str, str]) -> None:
    document = load_json(relative_path)
    images = document.get("images")
    require(isinstance(images, list), f"Missing images array: {relative_path}")
    actual: dict[str, str] = {}
    for image in images:
        require(isinstance(image, dict), f"Invalid image entry: {relative_path}")
        filename = image.get("filename")
        scale = image.get("scale")
        require(isinstance(filename, str), f"Missing image filename: {relative_path}")
        require(isinstance(scale, str), f"Missing image scale: {relative_path}")
        require(image.get("idiom") == "universal", f"Invalid idiom: {relative_path}")
        require(scale not in actual, f"Duplicate image scale {scale}: {relative_path}")
        actual[scale] = filename
    require(actual == expected, f"Unexpected imageset wiring in {relative_path}: {actual}")


def verify_app_icon_catalog() -> None:
    relative_path = (
        "iosApp/Kwabor/Resources/Assets.xcassets/"
        "AppIcon.appiconset/Contents.json"
    )
    document = load_json(relative_path)
    require(
        document.get("images")
        == [
            {
                "filename": "AppIcon-1024.png",
                "idiom": "universal",
                "platform": "ios",
                "size": "1024x1024",
            }
        ],
        f"Unexpected AppIcon wiring in {relative_path}",
    )


def require_text(relative_path: str, expected_text: str) -> None:
    path = repository_path(relative_path)
    require(path.is_file(), f"Missing brand reference file: {path}")
    text = path.read_text(encoding="utf-8")
    require(expected_text in text, f"Missing brand reference {expected_text!r} in {path}")


def require_source_reference(root: str, pattern: str, suffix: str) -> None:
    source_root = repository_path(root)
    require(source_root.is_dir(), f"Missing source directory: {source_root}")
    matches = [
        path
        for path in source_root.rglob(f"*{suffix}")
        if pattern in path.read_text(encoding="utf-8")
    ]
    require(matches, f"No {suffix} source references {pattern!r} below {source_root}")


def verify_references() -> None:
    require_text(
        "tools/generate-brand-assets.ps1",
        "$masterAsset = Join-Path $repositoryRoot 'kwabor_icone_app.png'",
    )
    require_text(
        "tools/generate-brand-assets.ps1",
        "$launchWordmarkMasterAsset = Join-Path $repositoryRoot 'kwabor_2.png'",
    )
    require_text(
        "tools/generate-brand-assets.ps1",
        "'kwabor_launch_wordmark.png'",
    )
    require_text(
        "tools/generate-brand-assets.ps1",
        "'LaunchWordmark.png'",
    )

    require_text(
        "androidApp/src/main/AndroidManifest.xml",
        'android:icon="@mipmap/ic_launcher"',
    )
    require_text(
        "androidApp/src/main/res/drawable/ic_kwabor_launch_mark.xml",
        'android:src="@drawable/kwabor_launch_mark"',
    )
    require_text(
        "androidApp/src/main/res/values/styles.xml",
        "@drawable/ic_kwabor_launch_mark",
    )
    require_source_reference(
        "androidApp/src",
        "R.drawable.kwabor_launch_wordmark",
        ".kt",
    )
    require_text(
        "androidApp/src/main/res/values/colors.xml",
        '<color name="kwabor_wordmark_background">#080707</color>',
    )
    require_source_reference(
        "androidApp/src",
        "R.color.kwabor_wordmark_background",
        ".kt",
    )

    verify_app_icon_catalog()
    verify_imageset(
        (
            "iosApp/Kwabor/Resources/Assets.xcassets/"
            "LaunchMark.imageset/Contents.json"
        ),
        {
            "1x": "LaunchMark-1x.png",
            "2x": "LaunchMark-2x.png",
            "3x": "LaunchMark-3x.png",
        },
    )
    verify_imageset(
        (
            "iosApp/Kwabor/Resources/Assets.xcassets/"
            "LaunchWordmark.imageset/Contents.json"
        ),
        {"1x": "LaunchWordmark.png"},
    )
    require_text("iosApp/Kwabor/Resources/Info.plist", "UILaunchStoryboardName")
    require_text("iosApp/Kwabor/Resources/Info.plist", "<string>LaunchScreen</string>")
    require_text(
        "iosApp/Kwabor/Resources/LaunchScreen.storyboard",
        'contentMode="scaleAspectFit" image="LaunchWordmark"',
    )
    require_text(
        "iosApp/Kwabor/Resources/LaunchScreen.storyboard",
        '<image name="LaunchWordmark" width="2172" height="724"/>',
    )
    require_source_reference("iosApp/Kwabor", '"LaunchWordmark"', ".swift")


def main() -> int:
    try:
        masters = verify_masters()
        verify_icon_derivatives()
        verify_wordmarks(masters[WORDMARK_MASTER])
        verify_references()
    except BrandVerificationError as error:
        print(f"ERROR brand assets: {error}", file=sys.stderr)
        return 1

    print(
        "OK brand assets: 3 canonical masters, icon derivatives locked, "
        "launch wordmarks byte-exact and Android/iOS references valid"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
