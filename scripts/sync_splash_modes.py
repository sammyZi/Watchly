"""Build light + dark Watchly splash logos and sync into Android res.

Android 12+ masks splash icons to a circle (~66% diameter). Mark + wordmark
must sit inside that disc or "Watchly" gets clipped.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path("assets")
RES = Path("android/app/src/main/res")
RED = (229, 9, 20)
INK = (28, 28, 30)
WHITE = (255, 255, 255)
SIZE = 1024
# Keep content inside ~60% of the canvas so the circular mask never clips it.
SAFE = int(SIZE * 0.58)


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
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    font = load_font(72)
    text = "Watchly"
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    gap = 28
    mark_size = SAFE - th - gap
    mark_size = max(280, min(mark_size, 420))

    fg = Image.open(ROOT / "android-icon-foreground.png").convert("RGBA")
    mark = tint_mark(fg.resize((mark_size, mark_size), Image.Resampling.LANCZOS), mark_rgb)

    stack_h = mark_size + gap + th
    top = (SIZE - stack_h) // 2
    canvas.paste(mark, ((SIZE - mark_size) // 2, top), mark)
    draw.text(
        ((SIZE - tw) // 2, top + mark_size + gap - bbox[1]),
        text,
        fill=text_rgb + (255,),
        font=font,
    )
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
dark = splash(WHITE, WHITE)

light.save(ROOT / "splash-icon.png")
dark.save(ROOT / "splash-icon-dark.png")

write_densities(light, "drawable")
write_densities(dark, "drawable-night")

(RES / "drawable").mkdir(parents=True, exist_ok=True)
(RES / "drawable-night").mkdir(parents=True, exist_ok=True)
light.resize((288, 288), Image.Resampling.LANCZOS).save(RES / "drawable" / "splashscreen_logo.png")
dark.resize((288, 288), Image.Resampling.LANCZOS).save(RES / "drawable-night" / "splashscreen_logo.png")

print("splash logos refit inside Android circle mask")
