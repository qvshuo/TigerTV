#!/usr/bin/env python3
"""Generate platform icons from the three original branding images."""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import struct
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from xml.etree import ElementTree

from PIL import Image, ImageDraw, ImageFont, ImageOps


ROOT = Path(__file__).resolve().parents[1]
BRANDING = ROOT / "assets/branding"
WEB = BRANDING / "web"


def png(image, size):
    output = io.BytesIO()
    image.resize(size, Image.Resampling.LANCZOS).save(output, format="PNG", optimize=True)
    return output.getvalue()


def render_icons():
    with Image.open(BRANDING / "originals/icon-transparent.png") as source:
        transparent = ImageOps.exif_transpose(source).convert("RGBA")
    with Image.open(BRANDING / "originals/icon-opaque.png") as source:
        opaque = ImageOps.exif_transpose(source).convert("RGB")
    with Image.open(BRANDING / "originals/tv-banner.png") as source:
        banner = ImageOps.fit(ImageOps.exif_transpose(source).convert("RGB"), (1280, 720), method=Image.Resampling.LANCZOS)
    font = ImageFont.truetype("/System/Library/Fonts/STHeiti Medium.ttc", 64)
    label = ElementTree.parse(ROOT / "AndroidTV/src/main/res/values/strings.xml").findtext("string[@name='app_name']")
    if not label:
        raise ValueError("Missing Android app_name for the TV banner")
    ImageDraw.Draw(banner).text((640, 22), label, font=font, anchor="mt", fill="#6F2E13")

    assets = {WEB / "logo.png": png(transparent, (256, 256))}
    for size in (16, 32, 48):
        assets[WEB / f"favicon-{size}.png"] = png(transparent, (size, size))

    app_icons = ROOT / "macOS/App/Assets.xcassets/AppIcon.appiconset"
    for item in json.loads((app_icons / "Contents.json").read_text())["images"]:
        size = int(item["size"].split("x")[0]) * int(item["scale"].removesuffix("x"))
        assets[app_icons / item["filename"]] = png(opaque, (size, size))

    # The artwork's silhouette stays inside the adaptive icon's 72dp safe zone.
    foreground = Image.new("RGBA", (1080, 1080))
    foreground.paste(opaque.resize((840, 840), Image.Resampling.LANCZOS), (120, 120))
    for density, icon_size, banner_size in (
        ("mdpi", 108, (160, 90)),
        ("hdpi", 162, (240, 135)),
        ("xhdpi", 216, (320, 180)),
        ("xxhdpi", 324, (480, 270)),
        ("xxxhdpi", 432, (640, 360)),
    ):
        folder = ROOT / f"AndroidTV/src/main/res/mipmap-{density}"
        assets[folder / "ic_launcher_foreground.png"] = png(foreground, (icon_size, icon_size))
        assets[folder / "banner.png"] = png(banner, banner_size)
    return assets


def compress_icons(assets, key):
    authorization = "Basic " + base64.b64encode(f"api:{key}".encode()).decode()
    headers = {"Authorization": authorization, "Content-Type": "application/octet-stream"}
    compressed = {}
    for path, data in assets.items():
        digest = hashlib.sha256(data).hexdigest()
        if digest not in compressed:
            request = Request("https://api.tinify.com/shrink", data=data, headers=headers)
            with urlopen(request, timeout=60) as response:
                output = json.load(response)["output"]["url"]
            if urlsplit(output).scheme != "https" or urlsplit(output).netloc != "api.tinify.com":
                raise ValueError("Unexpected TinyPNG download host")
            with urlopen(Request(output, headers={"Authorization": authorization}), timeout=60) as response:
                compressed[digest] = response.read()
            with Image.open(io.BytesIO(data)) as original, Image.open(io.BytesIO(compressed[digest])) as result:
                if result.format != "PNG" or result.size != original.size:
                    raise ValueError(f"TinyPNG changed image format or dimensions: {path.name}")
                original_alpha = original.convert("RGBA").getchannel("A").getextrema()
                result_alpha = result.convert("RGBA").getchannel("A").getextrema()
                if original_alpha != result_alpha:
                    raise ValueError(f"TinyPNG changed transparency range: {path.name}")
            print(f"TinyPNG: {path.relative_to(ROOT)} ({len(data):,} -> {len(compressed[digest]):,} bytes)", flush=True)
        assets[path] = compressed[digest]


def write_favicon():
    frames = [(size, (WEB / f"favicon-{size}.png").read_bytes()) for size in (16, 32, 48)]
    offset = 6 + 16 * len(frames)
    entries = []
    for size, data in frames:
        entries.append(struct.pack("<BBBBHHII", size, size, 0, 0, 1, 32, len(data), offset))
        offset += len(data)
    (WEB / "favicon.ico").write_bytes(
        struct.pack("<HHH", 0, 1, len(frames)) + b"".join(entries) + b"".join(data for _, data in frames)
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tinypng", action="store_true", help="Compress using TINYPNG_API_KEY before writing")
    args = parser.parse_args()
    key = os.environ.get("TINYPNG_API_KEY")
    if args.tinypng and not key:
        parser.error("Set TINYPNG_API_KEY in the environment; never put the key in this repository")
    assets = render_icons()
    before = sum(map(len, assets.values()))
    if args.tinypng:
        compress_icons(assets, key)
    for path, data in assets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    write_favicon()
    print(f"Generated {len(assets)} PNGs and favicon.ico; {before:,} -> {sum(map(len, assets.values())):,} bytes")


if __name__ == "__main__":
    main()
