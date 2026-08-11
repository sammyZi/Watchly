"""Build Watchly red icon variants and sync into Android res."""
from pathlib import Path

from PIL import Image

ROOT = Path("assets")
RES = Path("android/app/src/main/res")
RED = (229, 9, 20)  # #E50914

icon = Image.open(ROOT / "icon.png").convert("RGBA").resize((1024, 1024), Image.Resampling.LANCZOS)

bg = Image.new("RGBA", (1024, 1024), RED + (255,))
bg.save(ROOT / "android-icon-background.png")

fg = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
inner = icon.resize((680, 680), Image.Resampling.LANCZOS)
pix = inner.load()
for y in range(inner.height):
    for x in range(inner.width):
        r, g, b, a = pix[x, y]
        if a < 10:
            continue
        if r > 160 and g < 80 and b < 80:
            pix[x, y] = (0, 0, 0, 0)
fg.paste(inner, ((1024 - 680) // 2, (1024 - 680) // 2), inner)
fg.save(ROOT / "android-icon-foreground.png")

mono = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
mp, fp = mono.load(), fg.load()
for y in range(1024):
    for x in range(1024):
        if fp[x, y][3] > 40:
            mp[x, y] = (255, 255, 255, 255)
mono.save(ROOT / "android-icon-monochrome.png")

splash = Image.new("RGBA", (512, 512), RED + (255,))
mark = fg.resize((360, 360), Image.Resampling.LANCZOS)
splash.paste(mark, ((512 - 360) // 2, (512 - 360) // 2), mark)
splash.convert("RGB").save(ROOT / "splash-icon.png", quality=95)

icon.convert("RGB").save(ROOT / "icon.png", quality=95)
icon.resize((48, 48), Image.Resampling.LANCZOS).convert("RGB").save(ROOT / "favicon.png")

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
adaptive = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

full = icon.convert("RGBA")
for folder, size in sizes.items():
    d = RES / folder
    d.mkdir(parents=True, exist_ok=True)
    full.resize((size, size), Image.Resampling.LANCZOS).save(d / "ic_launcher.webp", "WEBP")
    full.resize((size, size), Image.Resampling.LANCZOS).save(d / "ic_launcher_round.webp", "WEBP")

for folder, size in adaptive.items():
    d = RES / folder
    bg.resize((size, size), Image.Resampling.LANCZOS).save(d / "ic_launcher_background.webp", "WEBP")
    fg.resize((size, size), Image.Resampling.LANCZOS).save(d / "ic_launcher_foreground.webp", "WEBP")
    mono.resize((size, size), Image.Resampling.LANCZOS).save(d / "ic_launcher_monochrome.webp", "WEBP")

splash_sizes = {
    "drawable-mdpi": 192,
    "drawable-hdpi": 288,
    "drawable-xhdpi": 384,
    "drawable-xxhdpi": 576,
    "drawable-xxxhdpi": 768,
}
splash_src = Image.open(ROOT / "splash-icon.png").convert("RGBA")
for folder, size in splash_sizes.items():
    d = RES / folder
    d.mkdir(parents=True, exist_ok=True)
    splash_src.resize((size, size), Image.Resampling.LANCZOS).save(d / "splashscreen_logo.png")

(RES / "drawable").mkdir(parents=True, exist_ok=True)
splash_src.resize((288, 288), Image.Resampling.LANCZOS).save(RES / "drawable" / "splashscreen_logo.png")

print("Watchly brand synced")
