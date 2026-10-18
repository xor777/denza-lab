#!/usr/bin/env python3
"""Inspect the owner's pinned stock HUD artwork; outputs stay in ignored captures/.

Uses Pillow. Does not decompile, modify an APK, contact a car, or copy artwork
into the HUD module. Sheets contain original assets on black, with labelled
enlargement; lane rows are examples assembled at the stock XML's 41x60 pitch.
"""
import argparse
from collections import Counter
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont


APK_SHA = "3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793"
PREFIX = "res/drawable-hdpi-v4/global_image_"
FAMILIES = ("landback_hud_", "landfront_hud_", "ar_hud_")
SCENES = (
    ("Прямо: рекомендована средняя полоса", ("back_1", "front_0", "back_3")),
    ("Составные стрелки: рекомендовано прямо", ("back_1", "front_20", "front_40")),
    ("Направо: две рекомендуемые полосы", ("back_0", "front_43", "front_3")),
    ("Разворот из левой полосы", ("front_95", "back_0", "back_3")),
    ("Шесть полос: съезд справа", ("back_0", "back_0", "back_0", "front_43", "front_3", "front_3")),
)


def artwork(assets, key, alternate=False):
    side, code = key.split("_")
    name = f"global_image_land{side}_hud_{'new_' if alternate else ''}{code}"
    return Image.open(assets / (name + (".jpg" if alternate else ".png"))).convert("RGBA")


def lane_row(assets, keys, alternate=False):
    row = Image.new("RGBA", (41 * len(keys), 60), "black")
    for index, key in enumerate(keys):
        im = artwork(assets, key, alternate)
        # ImageView FIT_CENTER in 37x60 px, 2 px margins each side.
        im.thumbnail((37, 60), Image.Resampling.LANCZOS)
        row.alpha_composite(im, (index * 41 + 2 + (37 - im.width) // 2, (60 - im.height) // 2))
    return row


def font(path, size):
    return ImageFont.truetype(str(path), size)


def sheets(out, records, font_path):
    assets = out / "assets"
    canvas = Image.new("RGB", (1160, 1260), "#10141b")
    draw = ImageDraw.Draw(canvas)
    f26, f20, f16, f13 = (font(font_path, x) for x in (26, 20, 16, 13))
    draw.text((28, 22), "Штатная графика HUD · BydLaunchermap", font=f26, fill="white")
    draw.text((28, 61), "Исходные ресурсы APK; примеры компоновки, не фотография проекции", font=f16, fill="#adb9ca")
    for col, (alternate, title, color) in enumerate(((False, "Основной набор · color flag ≠ 1", "#15c6ff"),
                                                  (True, "Альтернативный · color flag = 1", "#428cff"))):
        x = 28 + col * 570
        draw.text((x, 102), title, font=f20, fill=color)
        for i, (label, keys) in enumerate(SCENES):
            y = 148 + i * 166
            draw.text((x, y), label, font=f16, fill="#c8d1df")
            row = lane_row(assets, keys, alternate)
            row.save(out / f"lanes-{'alternate' if alternate else 'standard'}-{i + 1}.png")
            enlarged = row.resize((row.width * 2, 120), Image.Resampling.NEAREST)
            canvas.paste(enlarged.convert("RGB"), (x, y + 28))
    draw.text((28, 991), "Штатные значки манёвров · основной набор · оригинал 80×80 px, показ ×2", font=f20, fill="white")
    for i, (code, title) in enumerate(((2, "Налево"), (3, "Направо"), (9, "Прямо"), (8, "Разворот"), (50, "Кольцо: выезд 1"), (71, "Слияние"))):
        im = Image.open(assets / f"global_image_ar_hud_sou{code}.png").convert("RGBA")
        im = im.resize((160, 160), Image.Resampling.NEAREST)
        x = 28 + 188 * i
        canvas.paste("black", (x, 1032, x + 160, 1192))
        canvas.paste(im, (x, 1032), im)
        draw.text((x, 1204), title, font=f13, fill="#c8d1df")
    canvas.save(out / "stock-hud-overview.png")

    # Complete PNG lane catalogue, with original asset suffixes as identifiers.
    lanes = [r for r in records if r["name"].startswith("global_image_land") and r["name"].endswith(".png")]
    sheet = Image.new("RGB", (1100, 85 + ((len(lanes) + 10) // 11) * 170), "#10141b")
    sd = ImageDraw.Draw(sheet)
    sd.text((20, 18), "Полный набор полос · исходные PNG ×2", font=f26, fill="white")
    for i, record in enumerate(lanes):
        x, y = 16 + (i % 11) * 99, 75 + (i // 11) * 170
        im = Image.open(assets / record["name"]).convert("RGBA").resize((74, 120), Image.Resampling.NEAREST)
        sheet.paste("black", (x, y, x + 74, y + 120))
        sheet.paste(im, (x, y), im)
        label = record["name"].replace("global_image_land", "").replace("_hud", "").replace(".png", "")
        sd.text((x, y + 125), label, font=f13, fill="#c8d1df")
    sheet.save(out / "stock-lanes-catalogue.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--font", type=Path, default=Path("/System/Library/Fonts/Supplemental/Arial.ttf"))
    args = parser.parse_args()
    checksum = sha256()
    with args.apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    digest = checksum.hexdigest()
    if digest != APK_SHA:
        raise SystemExit("Unreviewed APK: SHA-256 mismatch")
    if "captures" not in args.out.resolve().parts:
        raise SystemExit("Keep proprietary/generated outputs in ignored captures/")
    assets = args.out / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    records = []
    with ZipFile(args.apk) as apk:
        for entry in sorted(apk.namelist()):
            if not entry.startswith(PREFIX) or not any(entry[len(PREFIX):].startswith(p) for p in FAMILIES):
                continue
            if not entry.endswith((".png", ".jpg")):
                continue
            blob = apk.read(entry)
            name = Path(entry).name
            (assets / name).write_bytes(blob)
            im = Image.open(BytesIO(blob)).convert("RGBA")
            pixels = Counter(im.get_flattened_data() if hasattr(im, "get_flattened_data") else im.getdata())
            visible = Counter({c: n for c, n in pixels.items() if c[3] == 255 and max(c[:3]) > 32})
            records.append({"name": name, "entry": entry, "sha256": sha256(blob).hexdigest(),
                            "size": list(im.size), "bytes": len(blob), "alpha_range": im.getextrema()[3],
                            "opaque_colors": visible.most_common(4)})
    if len(records) != 254:
        raise SystemExit("Unexpected resource inventory")
    report = {"apk": str(args.apk.resolve()), "sha256": digest, "resources": records,
              "lane_cell_px": [37, 60], "lane_side_margin_px": 2,
              "limits": "Offline source resources and example rows, not Android or glass acceptance."}
    (args.out / "artwork-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    sheets(args.out, records, args.font)
    print(f"{len(records)} original assets; sheets and artwork-report.json in {args.out}")


if __name__ == "__main__":
    main()
