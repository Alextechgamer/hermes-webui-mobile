#!/usr/bin/env python3
"""Build Hermes WebUI launcher icons from the official caduceus favicon."""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "branding/webui-caduceus-512.png"
NAVY = (2, 17, 40, 255)  # #021128
NAVY_MID = (13, 52, 96, 255)  # #0D3460


def fill_navy(src: Image.Image, size: int) -> Image.Image:
    """Full-bleed square: official mark on the WebUI navy tile (no transparent corners)."""
    mark = src.convert("RGBA").resize((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), NAVY)
    # Soft radial so the corners match the original tile.
    overlay = Image.new("RGBA", (size, size), NAVY_MID)
    mask = Image.new("L", (size, size), 0)
    cx = cy = size / 2
    r = size * 0.75
    pix = mask.load()
    for y in range(size):
        for x in range(size):
            d = ((x - cx) ** 2 + ((y - size * 0.42) - 0) ** 2) ** 0.5
            t = max(0.0, min(1.0, d / r))
            pix[x, y] = int(255 * (1.0 - t))
    canvas = Image.composite(overlay, canvas, mask)
    canvas.alpha_composite(mark)
    return canvas.convert("RGBA")


def adaptive_fg(src: Image.Image, size: int) -> Image.Image:
    """Caduceus inset into the 66% adaptive-icon safe zone."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.72)
    mark = src.convert("RGBA").resize((inner, inner), Image.Resampling.LANCZOS)
    x = (size - inner) // 2
    canvas.alpha_composite(mark, (x, x))
    return canvas


def save(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG")
    print(f"  {path.relative_to(ROOT)}  {im.size[0]}x{im.size[1]}")


def main() -> None:
    if not SRC.is_file():
        raise SystemExit(f"missing official favicon: {SRC}")
    src = Image.open(SRC)
    print("source", SRC, src.size)

    master = fill_navy(src, 1024)
    save(master, ROOT / "ios/HermesWebUI/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
    save(master, ROOT / "branding/app-icon-1024.png")

    mip = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    fg = {
        "mdpi": 108,
        "hdpi": 162,
        "xhdpi": 216,
        "xxhdpi": 324,
        "xxxhdpi": 432,
    }
    for dens, px in mip.items():
        icon = fill_navy(src, px)
        save(icon, ROOT / f"android/app/src/main/res/mipmap-{dens}/ic_launcher.png")
        save(icon, ROOT / f"android/app/src/main/res/mipmap-{dens}/ic_launcher_round.png")
    for dens, px in fg.items():
        save(adaptive_fg(src, px), ROOT / f"android/app/src/main/res/mipmap-{dens}/ic_launcher_foreground.png")

    (ROOT / "android/app/src/main/res/values/ic_launcher_background.xml").write_text(
        """<resources>
    <color name="ic_launcher_background">#021128</color>
</resources>
"""
    )
    anydpi = ROOT / "android/app/src/main/res/mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(xml)
    (anydpi / "ic_launcher_round.xml").write_text(xml)
    print("android adaptive xml written")


if __name__ == "__main__":
    main()
