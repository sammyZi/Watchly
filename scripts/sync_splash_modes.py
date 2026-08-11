"""Build light + dark Watchly splash logos and sync into Android res."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path("assets")
RES = Path("android/app/src/main/res")
RED = (229, 9, 20)
DARK = (11, 14, 20)  # app bg #0b0e14
LIGHT = (255, 255, 255)
INK = (28, 28, 30)


def load_font(size: int):
    for path in (
        "C:/Windows/Fonts/segoeuib.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def tint_mark(src: Image.Image, rgb: tuple[int, int, int]) -> Image.Image:
    """Keep alpha; recolor opaque pixels."""
    out = src.convert("RGBA")
    pix = out.load()
    r, g, b = rgb
    for y in range(out.height):
        for x in range(out.width):
            a = pix[x, y][3]
            if a > 20:
                pix[x, y] = (r, g, b, a)
    return out


def splash(mark_rgb: tuple[int, int, int], text_rgb: tuple[int, int, int]) -> Image.Image:
    """Transparent canvas: mark + Watchly wordmark (bg comes from theme color)."""
    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    fg = Image.open(ROOT / "android-icon-foreground.png").convert("RGBA")
    mark = tint_mark(fg.resize((560, 560), Image.Resampling.LANCZOS), mark_rgb)
    canvas.paste(mark, ((1024 - 560) // 2, 160), mark)

    draw = ImageDraw.Draw(canvas)
    font = load_font(96)
    text = "Watchly"
    bbox = draw.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    draw.text(((1024 - tw) // 2, 760), text, fill=text_rgb + (255,), font=font)
    return canvas


def write_densities(img: Image.Image, folder_prefix: str):
    sizes = {
        "mdpi": 192,
        "hdpi": 288,
        "xhdpi": 384,
        "xxhdpi": 576,
        "xxxhdpi": 768,
    }
    for dens, size in sizes.items():
        d = RES / f"{folder_prefix}-{dens}"
        d.mkdir(parents=True, exist_ok=True)
        img.resize((size, size), Image.Resampling.LANCZOS).save(d / "splashscreen_logo.png")


light = splash(RED, INK)
dark = splash((255, 255, 255), (255, 255, 255))

light.save(ROOT / "splash-icon.png")
dark.save(ROOT / "splash-icon-dark.png")

# Preview plates (full-bleed) for sanity checks — not used by Android theme.
Image.alpha_composite(Image.new("RGBA", (1024, 1024), LIGHT + (255,)), light).convert("RGB").save(
    ROOT / "splash-preview-light.png", quality=95
)
Image.alpha_composite(Image.new("RGBA", (1024, 1024), DARK + (255,)), dark).convert("RGB").save(
    ROOT / "splash-preview-dark.png", quality=95
)

write_densities(light, "drawable")
write_densities(dark, "drawable-night")

# Default / night unqualified drawables
(RES / "drawable").mkdir(parents=True, exist_ok=True)
(RES / "drawable-night").mkdir(parents=True, exist_ok=True)
light.resize((288, 288), Image.Resampling.LANCZOS).save(RES / "drawable" / "splashscreen_logo.png")
dark.resize((288, 288), Image.Resampling.LANCZOS).save(RES / "drawable-night" / "splashscreen_logo.png")

print("light + dark splash synced")
