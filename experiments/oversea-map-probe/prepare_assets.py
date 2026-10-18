#!/usr/bin/env python3
"""Prepare local-only BYD map comparison assets from verified capture files.

Requires texture2ddecoder==1.0.6 and Pillow==11.3.0. No vehicle access.
Original textures/screenshots and generated assets stay in ignored directories.
"""
import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_SHA256 = "13d9c82deb985236c90149980ae3730739c5aa61d98b86fbbcbe0ff5d98e2f0c"
SCENE_SHA256 = "4eda2586da52dea7a9e2b37cc2d0847e4d6149120614996ec3b3a8d3afbe3d70"
SHADER_SHA256 = "c983bea1cd5ba2a1114647af7c62b186acb0ac3eb3019e7e09ff78d79fafca99"
TEXTURES = {
    "oversea_map_mask_2560_720": "1bfb78733a3de7aa9ddeb552775949c96f7dcbf09e5fea644e274bbc8c1553eb",
    "oversea_map_mask_light_2560_720": "4bfa7e3c16adb5481a81a75643bc515ac02f06b556221aeaa77ad317e07a81bf",
    "oversea_map_mask_left_2560_720": "85e5914eaadd0d9814e17885a290aae498967302a54a9340768309bf4fc77f4e",
    "oversea_map_mask_right_2560_720": "ccfabceef5d14593b50a3bc04332675b23d9b1d2c36cee2560116ce7404e4ed3",
}
# Only map pixels: exclude the central screen's menu, status and climate bars.
# This is a crop of the central map, not a captured cluster projection.
CROP = (744, 450, 2536, 954)  # 1792 x 504, same aspect as 2560 x 720.


def verified(path, expected, evidence):
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected:
        raise ValueError("Unexpected source hash: %s" % path)
    evidence.append({"path": str(path.relative_to(ROOT)), "sha256": digest, "bytes": len(data)})
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--decoder-path", type=Path)
    args = parser.parse_args()
    if args.decoder_path:
        sys.path.insert(0, str(args.decoder_path.resolve()))
    import texture2ddecoder
    from PIL import Image, ImageChops
    sys.path.insert(0, str(ROOT / "research/cluster-native"))
    from read_binary_scene import read_scene

    capture, output = args.capture.resolve(), args.output.resolve()
    firmware = capture / "firmware-assets"
    evidence = []
    source = capture / "stock-map-source.png"
    verified(source, SOURCE_SHA256, evidence)
    scene_path = firmware / ".godot/exported/133200997/export-4549ebe529f5b001704c92a2a6ef5c24-drive_map.scn"
    verified(scene_path, SCENE_SHA256, evidence)
    verified(firmware / "scenes/drive_map/shaders/oversea_mask.gdshader", SHADER_SHA256, evidence)
    scene = read_scene(scene_path)
    nodes = {node["name"]: node["properties"] for node in scene["nodes"]}
    left, right = nodes["MaskLeft"], nodes["MaskRight"]
    if not (left["offset_right"] == 960 and left["offset_bottom"] == 720
            and right["anchor_left"] == right["anchor_right"] == 1
            and right["offset_left"] == -960 and right["offset_bottom"] == 720):
        raise ValueError("Unexpected mask geometry")

    decoded = {}
    for name, digest in TEXTURES.items():
        matches = list((firmware / ".godot/imported").glob(name + ".png-*.astc.ctex"))
        if len(matches) != 1:
            raise ValueError("Expected one texture: " + name)
        data = verified(matches[0], digest, evidence)
        version, width, height = struct.unpack_from("<III", data, 4)
        kind, width2, height2, mips, fmt = struct.unpack_from("<IHHII", data, 36)
        if (data[:4], version, kind, mips, fmt, width2, height2) != (b"GST2", 1, 0, 0, 35, width, height):
            raise ValueError("Expected single-level ASTC 4x4 RGBA texture")
        if len(data) != 52 + ((width + 3) // 4) * ((height + 3) // 4) * 16:
            raise ValueError("Unexpected ASTC payload size")
        bgra = texture2ddecoder.decode_astc(data[52:], width, height, 4, 4)
        decoded[name] = Image.frombytes("RGBA", (width, height), bgra, "raw", "BGRA")

    output.mkdir(parents=True, exist_ok=True)
    full_source = Image.open(source).convert("RGBA")
    if full_source.size != (2560, 1600):
        raise ValueError("Unexpected central-screen snapshot size")
    background = full_source.crop(CROP).resize((2560, 720), Image.Resampling.BILINEAR)
    background.save(output / "stock-map.png")
    metrics = {}
    for theme, name, color in (
        ("dark", "oversea_map_mask_2560_720", (56, 72, 88)),
        ("light", "oversea_map_mask_light_2560_720", (207, 218, 229)),
    ):
        # TextureRect default STRETCH_SCALE; script calls MapMask.set_size(wSize).
        overlay = decoded[name].resize((2560, 720), Image.Resampling.BILINEAR)
        for side, position in (("left", (0, 0)), ("right", (1600, 0))):
            original = decoded["oversea_map_mask_" + side + "_2560_720"]
            if original.size != (960, 720):
                raise ValueError("Unexpected side texture size")
            tinted = Image.new("RGBA", original.size, color + (0,))
            tinted.putalpha(original.getchannel("A"))  # Shader replaces RGB only.
            overlay.alpha_composite(tinted, position)
        overlay.save(output / (theme + "-overlay.png"))
        preview = Image.alpha_composite(background, overlay)
        preview.save(capture / (theme + "-reference.png"))
        # Transparent middle must preserve the screenshot; opaque edges must show shader RGB.
        for point in ((1280, 360), (1150, 360), (1400, 360)):
            if overlay.getpixel(point)[3] != 0 or preview.getpixel(point) != background.getpixel(point):
                raise ValueError("Transparent center changed")
        if preview.getpixel((0, 360))[:3] != color or preview.getpixel((2559, 360))[:3] != color:
            raise ValueError("Shader edge color mismatch")
        metrics[theme] = {"changed_bbox": ImageChops.difference(background, preview).convert("RGB").getbbox(),
                          "center_unchanged": True, "edge_rgb": color}
    manifest = {
        "source": "Static crop of com.byd.launchermap, central display, 2026-10-18",
        "crop_xyxy": CROP, "canvas": [2560, 720],
        "layout": {"center": [0, 0, 2560, 720], "left": [0, 0, 960, 720], "right": [1600, 0, 2560, 720]},
        "limitations": "Mask-only reconstruction; no AVM cutout, live map, region switch or Godot runtime assertion.",
        "inputs": evidence, "pixel_checks": metrics,
        "outputs": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.glob("*.png"))},
        "dependencies": {"texture2ddecoder": texture2ddecoder.__version__, "Pillow": Image.__version__},
    }
    (output / "provenance.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    (capture / "probe-assets-verification.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Prepared 3 PNG assets; source hashes, scene geometry, transparency and edge colors verified.")


if __name__ == "__main__":
    main()
