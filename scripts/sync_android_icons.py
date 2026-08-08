"""Copy Expo assets into android/app/src/main/res for assembleRelease."""
from PIL import Image
import os

ROOT = r"D:\projects\torrent_viewer"
ASSETS = os.path.join(ROOT, "assets")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# density -> (legacy launcher px, adaptive layer px, splash logo px)
DENSITIES = {
    "mdpi": (48, 108, 288),
    "hdpi": (72, 162, 432),
    "xhdpi": (96, 216, 576),
    "xxhdpi": (144, 324, 864),
    "xxxhdpi": (192, 432, 1152),
}


def save_webp(im: Image.Image, path: str) -> None:
    im.convert("RGBA").save(path, "WEBP", quality=95, method=6)


def save_png(im: Image.Image, path: str) -> None:
    im.convert("RGBA").save(path, "PNG", optimize=True)


def main() -> None:
    icon = Image.open(os.path.join(ASSETS, "icon.png")).convert("RGBA")
    fg = Image.open(os.path.join(ASSETS, "android-icon-foreground.png")).convert("RGBA")
    bg = Image.open(os.path.join(ASSETS, "android-icon-background.png")).convert("RGBA")
    mono = Image.open(os.path.join(ASSETS, "android-icon-monochrome.png")).convert("RGBA")
    splash = Image.open(os.path.join(ASSETS, "splash-icon.png")).convert("RGBA")

    for density, (legacy, adaptive, splash_px) in DENSITIES.items():
        mip = os.path.join(RES, f"mipmap-{density}")
        draw = os.path.join(RES, f"drawable-{density}")
        os.makedirs(mip, exist_ok=True)
        os.makedirs(draw, exist_ok=True)

        legacy_im = icon.resize((legacy, legacy), Image.Resampling.LANCZOS)
        save_webp(legacy_im, os.path.join(mip, "ic_launcher.webp"))
        save_webp(legacy_im, os.path.join(mip, "ic_launcher_round.webp"))

        save_webp(fg.resize((adaptive, adaptive), Image.Resampling.LANCZOS), os.path.join(mip, "ic_launcher_foreground.webp"))
        save_webp(bg.resize((adaptive, adaptive), Image.Resampling.LANCZOS), os.path.join(mip, "ic_launcher_background.webp"))
        save_webp(mono.resize((adaptive, adaptive), Image.Resampling.LANCZOS), os.path.join(mip, "ic_launcher_monochrome.webp"))

        # Splash API wants the mark padded in a larger canvas (existing Expo sizes).
        mark = splash.resize((splash_px * 42 // 100, splash_px * 42 // 100), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (splash_px, splash_px), (0, 0, 0, 0))
        x = (splash_px - mark.size[0]) // 2
        y = (splash_px - mark.size[1]) // 2
        canvas.paste(mark, (x, y), mark)
        save_png(canvas, os.path.join(draw, "splashscreen_logo.png"))
        print(density, "ok")

    colors = os.path.join(RES, "values", "colors.xml")
    with open(colors, "w", encoding="utf-8", newline="\n") as f:
        f.write(
            """<resources>
  <color name="splashscreen_background">#ffffff</color>
  <color name="iconBackground">#3b6fe0</color>
  <color name="colorPrimary">#3b6fe0</color>
  <color name="activityBackground">#ffffff</color>
</resources>
"""
        )
    print("colors.xml updated")
    print("done")


if __name__ == "__main__":
    main()
