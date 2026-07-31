#!/usr/bin/env python3
"""Verify the provenance and wiring of Kwabor's committed brand assets.

The native operating-system splash icon and the App Store icon are derived from
``kwabor_icone_app.png``. The full launch wordmark is deliberately kept as an
exact copy of ``kwabor_2.png`` so no renderer can crop, recolor, or redraw it.
"""

from __future__ import annotations

import hashlib
import json
import re
import struct
import sys
import xml.etree.ElementTree as ElementTree
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"
ANDROID_LAUNCH_BACKGROUND = (14, 14, 13)
VISIBLE_LUMINANCE_THRESHOLD = 32


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

ANDROID_BRAND_MARK_OUTPUTS = {
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

ANDROID_LAUNCH_MARK_OUTPUTS = {
    "mdpi": (
        288,
        "a754994e4dd87ecacdde67a08540554f1ab12689e920ec8c44bc011d3f2f3fae",
        (92, 78, 215, 205),
    ),
    "hdpi": (
        432,
        "77a424a66525055c14a8de9300eb89fd205605b4de5e097e4501b2b1aab877e0",
        (138, 118, 323, 308),
    ),
    "xhdpi": (
        576,
        "41870075bb16070535645acc2276a13a202384a40313377ca1805c174f0ba5fe",
        (185, 157, 430, 410),
    ),
    "xxhdpi": (
        864,
        "834b8ade73bbe3b0566cd5c5da77bbc6d1e546abea1a2cc3bd96d8f0280ed349",
        (277, 236, 645, 615),
    ),
    "xxxhdpi": (
        1152,
        "adeb414a67d97cab1f15dfeac3fad815b548b6ce0bb3a2eb4d5cb021ccecfb01",
        (370, 315, 860, 820),
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


def paeth_predictor(left: int, above: int, upper_left: int) -> int:
    estimate = left + above - upper_left
    left_distance = abs(estimate - left)
    above_distance = abs(estimate - above)
    upper_left_distance = abs(estimate - upper_left)
    if left_distance <= above_distance and left_distance <= upper_left_distance:
        return left
    if above_distance <= upper_left_distance:
        return above
    return upper_left


def decode_rgb_pixels(path: Path) -> tuple[int, int, bytes]:
    payload, width, height, mode = read_png_metadata(path)
    require(mode == "RGB", f"Geometry verification requires an RGB PNG: {path}")

    idat_chunks: list[bytes] = []
    offset = len(PNG_SIGNATURE)
    while offset < len(payload):
        length = int.from_bytes(payload[offset : offset + 4], "big")
        chunk_type = payload[offset + 4 : offset + 8]
        data_start = offset + 8
        data_end = data_start + length
        if chunk_type == b"IDAT":
            idat_chunks.append(payload[data_start:data_end])
        offset = data_end + 4

    require(idat_chunks, f"Missing PNG image data: {path}")
    try:
        filtered = zlib.decompress(b"".join(idat_chunks))
    except zlib.error as error:
        raise BrandVerificationError(f"Invalid PNG image data: {path}: {error}") from error

    bytes_per_pixel = 3
    stride = width * bytes_per_pixel
    require(
        len(filtered) == height * (stride + 1),
        f"Unexpected decompressed PNG size: {path}",
    )

    decoded = bytearray()
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = filtered[offset]
        offset += 1
        current = bytearray(filtered[offset : offset + stride])
        offset += stride
        require(filter_type in range(5), f"Unsupported PNG row filter: {path}")

        for index in range(stride):
            left = current[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            above = previous[index]
            upper_left = (
                previous[index - bytes_per_pixel]
                if index >= bytes_per_pixel
                else 0
            )
            if filter_type == 1:
                current[index] = (current[index] + left) & 0xFF
            elif filter_type == 2:
                current[index] = (current[index] + above) & 0xFF
            elif filter_type == 3:
                current[index] = (current[index] + ((left + above) // 2)) & 0xFF
            elif filter_type == 4:
                current[index] = (
                    current[index] + paeth_predictor(left, above, upper_left)
                ) & 0xFF

        decoded.extend(current)
        previous = current

    return width, height, bytes(decoded)


def verify_launch_geometry(
    relative_path: str,
    expected_bounds: tuple[int, int, int, int],
) -> None:
    path = repository_path(relative_path)
    width, height, pixels = decode_rgb_pixels(path)
    require(width == height, f"Android launch canvas must be square: {path}")
    require(width % 8 == 0, f"Android launch canvas must support exact 75% padding: {path}")

    visible_x: list[int] = []
    visible_y: list[int] = []
    maximum_radius_squared = 0.0
    center = width / 2.0
    padding = width // 8
    for y in range(height):
        row_offset = y * width * 3
        for x in range(width):
            pixel_offset = row_offset + x * 3
            red, green, blue = pixels[pixel_offset : pixel_offset + 3]
            if (
                x < padding
                or x >= width - padding
                or y < padding
                or y >= height - padding
            ):
                require(
                    (red, green, blue) == ANDROID_LAUNCH_BACKGROUND,
                    f"Unexpected Android launch padding pixel in {relative_path}",
                )
            luminance_numerator = 2126 * red + 7152 * green + 722 * blue
            if luminance_numerator < VISIBLE_LUMINANCE_THRESHOLD * 10_000:
                continue
            visible_x.append(x)
            visible_y.append(y)
            x_distance = (x + 0.5) - center
            y_distance = (y + 0.5) - center
            maximum_radius_squared = max(
                maximum_radius_squared,
                x_distance * x_distance + y_distance * y_distance,
            )

    require(visible_x, f"Android launch mark has no visible light pixels: {path}")
    actual_bounds = (
        min(visible_x),
        min(visible_y),
        max(visible_x) + 1,
        max(visible_y) + 1,
    )
    require(
        actual_bounds == expected_bounds,
        f"Unexpected visible launch geometry for {relative_path}: {actual_bounds}",
    )
    safe_radius = width / 3.0
    require(
        maximum_radius_squared <= safe_radius * safe_radius,
        f"Android launch mark exceeds the 192 dp safe circle: {relative_path}",
    )


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

    for density, (size, expected_hash) in ANDROID_BRAND_MARK_OUTPUTS.items():
        brand_payload = verify_png(
            PngSpec(
                path=(
                    f"androidApp/src/main/res/drawable-{density}/"
                    "kwabor_brand_mark.png"
                ),
                width=size,
                height=size,
                mode="RGB",
                sha256=expected_hash,
                source=ICON_MASTER,
            )
        )

        launch_size, launch_hash, launch_bounds = ANDROID_LAUNCH_MARK_OUTPUTS[density]
        launch_path = (
            f"androidApp/src/main/res/drawable-{density}/kwabor_launch_mark.png"
        )
        launch_payload = verify_png(
            PngSpec(
                path=launch_path,
                width=launch_size,
                height=launch_size,
                mode="RGB",
                sha256=launch_hash,
                source=ICON_MASTER,
            )
        )
        require(
            launch_size > size,
            f"Android launch canvas must exceed the launcher canvas: {density}",
        )
        require(
            launch_payload != brand_payload,
            f"Android launch and launcher assets must remain distinct: {density}",
        )
        verify_launch_geometry(launch_path, launch_bounds)

    obsolete_no_density_launch_mark = repository_path(
        "androidApp/src/main/res/drawable-nodpi/kwabor_launch_mark.png"
    )
    require(
        not obsolete_no_density_launch_mark.exists(),
        "Android launch mark must use explicit 288 dp density assets: "
        + str(obsolete_no_density_launch_mark),
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


def load_xml(relative_path: str) -> ElementTree.Element:
    path = repository_path(relative_path)
    require(path.is_file(), f"Missing Android resource XML: {path}")
    try:
        return ElementTree.fromstring(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ElementTree.ParseError) as error:
        raise BrandVerificationError(f"Invalid Android resource XML: {path}: {error}") from error


def verify_android_splash_xml() -> None:
    drawable_path = "androidApp/src/main/res/drawable/ic_kwabor_launch_mark.xml"
    drawable = load_xml(drawable_path)
    android_attribute = f"{{{ANDROID_XML_NAMESPACE}}}"
    require(drawable.tag == "bitmap", f"Unexpected root element: {drawable_path}")
    require(not list(drawable), f"Launch bitmap wrapper must not have children: {drawable_path}")
    require(
        drawable.attrib
        == {
            f"{android_attribute}antialias": "true",
            f"{android_attribute}dither": "true",
            f"{android_attribute}filter": "true",
            f"{android_attribute}gravity": "fill",
            f"{android_attribute}src": "@drawable/kwabor_launch_mark",
        },
        f"Unexpected launch bitmap policy in {drawable_path}: {drawable.attrib}",
    )

    styles_path = "androidApp/src/main/res/values/styles.xml"
    resources = load_xml(styles_path)
    require(resources.tag == "resources", f"Unexpected root element: {styles_path}")
    application_styles = [
        style
        for style in resources.findall("style")
        if style.attrib.get("name") == "KwaborTheme"
    ]
    require(
        len(application_styles) == 1,
        f"Expected one KwaborTheme definition in {styles_path}",
    )
    application_items = {
        item.attrib.get("name"): (item.text or "").strip()
        for item in application_styles[0].findall("item")
    }
    require(
        application_items.get("android:windowBackground")
        == "@color/kwabor_wordmark_background"
        and application_items.get("android:statusBarColor")
        == "@color/kwabor_wordmark_background"
        and application_items.get("android:navigationBarColor")
        == "@color/kwabor_wordmark_background"
        and application_items.get("android:windowLightStatusBar") == "false",
        f"KwaborTheme must bridge every launch surface in {styles_path}",
    )
    starting_styles = [
        style
        for style in resources.findall("style")
        if style.attrib.get("name") == "KwaborTheme.Starting"
    ]
    require(
        len(starting_styles) == 1,
        f"Expected one KwaborTheme.Starting definition in {styles_path}",
    )
    starting_style = starting_styles[0]
    require(
        starting_style.attrib
        == {"name": "KwaborTheme.Starting", "parent": "Theme.SplashScreen"},
        f"Unexpected starting theme attributes in {styles_path}: {starting_style.attrib}",
    )

    items: dict[str, str] = {}
    for item in starting_style.findall("item"):
        require(
            set(item.attrib) == {"name"} and not list(item),
            f"Invalid starting theme item in {styles_path}",
        )
        name = item.attrib["name"]
        require(name not in items, f"Duplicate starting theme item {name}: {styles_path}")
        items[name] = (item.text or "").strip()
    require(
        items
        == {
            "windowSplashScreenBackground": "@color/kwabor_icon_background",
            "windowSplashScreenAnimatedIcon": "@drawable/ic_kwabor_launch_mark",
            "windowSplashScreenAnimationDuration": "300",
            "postSplashScreenTheme": "@style/KwaborTheme",
            "android:statusBarColor": "@color/kwabor_icon_background",
            "android:navigationBarColor": "@color/kwabor_icon_background",
            "android:windowLightStatusBar": "false",
        },
        f"Unexpected active SplashScreen wiring in {styles_path}: {items}",
    )

    styles_v27_path = "androidApp/src/main/res/values-v27/styles.xml"
    resources_v27 = load_xml(styles_v27_path)
    application_styles_v27 = [
        style
        for style in resources_v27.findall("style")
        if style.attrib.get("name") == "KwaborTheme"
    ]
    require(
        len(application_styles_v27) == 1,
        f"Expected one KwaborTheme definition in {styles_v27_path}",
    )
    application_items_v27 = {
        item.attrib.get("name"): (item.text or "").strip()
        for item in application_styles_v27[0].findall("item")
    }
    require(
        application_styles_v27[0].attrib == application_styles[0].attrib
        and application_items_v27
        == {
            **application_items,
            "android:windowLightNavigationBar": "false",
        },
        f"KwaborTheme must keep API 27+ system bars dark in {styles_v27_path}",
    )
    starting_styles_v27 = [
        style
        for style in resources_v27.findall("style")
        if style.attrib.get("name") == "KwaborTheme.Starting"
    ]
    require(
        len(starting_styles_v27) == 1
        and starting_styles_v27[0].attrib == starting_style.attrib,
        f"Expected one matching KwaborTheme.Starting in {styles_v27_path}",
    )
    starting_items_v27 = {
        item.attrib.get("name"): (item.text or "").strip()
        for item in starting_styles_v27[0].findall("item")
    }
    require(
        starting_items_v27
        == {
            **items,
            "android:windowLightNavigationBar": "false",
        },
        f"Starting theme must keep API 27+ system bars dark in {styles_v27_path}",
    )

    styles_v33_path = "androidApp/src/main/res/values-v33/styles.xml"
    resources_v33 = load_xml(styles_v33_path)
    starting_styles_v33 = [
        style
        for style in resources_v33.findall("style")
        if style.attrib.get("name") == "KwaborTheme.Starting"
    ]
    require(
        len(starting_styles_v33) == 1,
        f"Expected one KwaborTheme.Starting definition in {styles_v33_path}",
    )
    starting_items_v33 = {
        item.attrib.get("name"): (item.text or "").strip()
        for item in starting_styles_v33[0].findall("item")
    }
    require(
        starting_styles_v33[0].attrib == starting_style.attrib
        and starting_items_v33
        == {
            **starting_items_v27,
            "android:windowSplashScreenBehavior": "icon_preferred",
        },
        f"API 33+ must prefer the launch icon in {styles_v33_path}",
    )

    colors_path = "androidApp/src/main/res/values/colors.xml"
    color_resources = load_xml(colors_path)
    require(color_resources.tag == "resources", f"Unexpected root element: {colors_path}")
    launch_backgrounds = [
        color
        for color in color_resources.findall("color")
        if color.attrib.get("name") == "kwabor_icon_background"
    ]
    require(
        len(launch_backgrounds) == 1,
        f"Expected one kwabor_icon_background definition in {colors_path}",
    )
    launch_background = launch_backgrounds[0]
    require(
        launch_background.attrib == {"name": "kwabor_icon_background"}
        and not list(launch_background)
        and (launch_background.text or "").strip().upper() == "#0E0E0D",
        f"Unexpected Android launch background in {colors_path}",
    )

    manifest_path = "androidApp/src/main/AndroidManifest.xml"
    manifest = load_xml(manifest_path)
    require(manifest.tag == "manifest", f"Unexpected root element: {manifest_path}")
    applications = manifest.findall("application")
    require(len(applications) == 1, f"Expected one application in {manifest_path}")
    main_activities = [
        activity
        for activity in applications[0].findall("activity")
        if activity.attrib.get(f"{android_attribute}name") == ".MainActivity"
    ]
    require(len(main_activities) == 1, f"Expected one .MainActivity in {manifest_path}")
    require(
        main_activities[0].attrib.get(f"{android_attribute}theme")
        == "@style/KwaborTheme.Starting",
        f"MainActivity must use KwaborTheme.Starting in {manifest_path}",
    )

    activity_source_path = "androidApp/src/main/kotlin/com/kwabor/android/MainActivity.kt"
    activity_source_file = repository_path(activity_source_path)
    require(activity_source_file.is_file(), f"Missing Android source: {activity_source_file}")
    activity_source = activity_source_file.read_text(encoding="utf-8")
    activity_source = re.sub(r"/\*.*?\*/", "", activity_source, flags=re.DOTALL)
    activity_source = re.sub(r"//[^\r\n]*", "", activity_source)
    on_create_position = activity_source.find("override fun onCreate(")
    splash_position = activity_source.find("installSplashScreen()", on_create_position)
    super_position = activity_source.find(
        "super.onCreate(savedInstanceState)",
        on_create_position,
    )
    keep_condition_position = activity_source.find(
        "setKeepOnScreenCondition(",
        on_create_position,
    )
    exit_listener_position = activity_source.find(
        "setOnExitAnimationListener",
        on_create_position,
    )
    require(
        on_create_position >= 0
        and splash_position > on_create_position
        and super_position > splash_position
        and keep_condition_position > super_position
        and exit_listener_position > keep_condition_position
        and activity_source.count("installSplashScreen()") == 1,
        "MainActivity must install and retain SplashScreen before its first application frame",
    )
    require(
        activity_source.count("setKeepOnScreenCondition(") == 1,
        "MainActivity must register exactly one SplashScreen keep condition",
    )
    require(
        activity_source.count("launchProcessState.consumeIsFirstActivityInProcess()") == 1,
        "MainActivity must apply the brand hold to the first Activity in each process",
    )
    require_text(
        "androidApp/src/main/kotlin/com/kwabor/android/LaunchSplashGuard.kt",
        "COLD_START_MINIMUM_SPLASH_MILLIS = 1_000L",
    )
    require_text(
        "androidApp/src/main/kotlin/com/kwabor/android/ui/screens/onboarding/IntroScreen.kt",
        "INTRO_WORDMARK_MINIMUM_VISIBLE_MILLIS = 500L",
    )
    require_text(
        "androidApp/src/main/kotlin/com/kwabor/android/ui/screens/onboarding/IntroPlayerLifecycleBinding.kt",
        "player.setMediaItem(MediaItem.fromUri(mediaUri), true)",
    )
    require_text(
        "androidApp/src/main/kotlin/com/kwabor/android/app/KwaborApp.kt",
        "RestoringLaunchContent.Wordmark -> LaunchDecisionPendingScreen(strings = strings)",
    )
    require_text(
        "androidApp/src/main/kotlin/com/kwabor/android/ui/screens/onboarding/LaunchDecisionPendingScreen.kt",
        "painter = painterResource(R.drawable.kwabor_launch_wordmark)",
    )
    require_text(
        "androidApp/src/main/kotlin/com/kwabor/android/ui/screens/onboarding/LaunchDecisionPendingScreen.kt",
        "contentScale = ContentScale.Fit",
    )
    require_text(
        "androidApp/src/main/kotlin/com/kwabor/android/ui/screens/onboarding/LaunchDecisionPendingScreen.kt",
        ".background(colorResource(R.color.kwabor_wordmark_background))",
    )


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
    for density, brand_size, launch_size in (
        ("mdpi", 108, 288),
        ("hdpi", 162, 432),
        ("xhdpi", 216, 576),
        ("xxhdpi", 324, 864),
        ("xxxhdpi", 432, 1152),
    ):
        require_text(
            "tools/generate-brand-assets.ps1",
            (
                f"@{{ Density = '{density}'; BrandSize = {brand_size}; "
                f"LaunchSize = {launch_size} }}"
            ),
        )
    require_text(
        "tools/generate-brand-assets.ps1",
        "-Size $_.LaunchSize",
    )
    require_text(
        "tools/generate-brand-assets.ps1",
        "-ContentScale 0.75",
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
    verify_android_splash_xml()
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
        "OK brand assets: 3 canonical masters, Android 288 dp launch geometry "
        "and hashes locked, icon derivatives locked, launch wordmarks byte-exact "
        "and Android/iOS references valid"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
