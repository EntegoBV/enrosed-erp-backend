#!/usr/bin/env python3
"""Regenerates the branded quote QR codes bundled with the catalogue brochure.

The brochure prints one QR per catalogue language (catalog-assets/quote-qr-<lang>.png)
on the ordering page and the back cover. Each code links to the localized website
quote page, exactly like the clickable links PdfCatalogRenderer writes next to it.

Style: wine modules as soft tiles on an ivory card with a gold hairline, square
finder patterns (rounded finders are rejected by stricter scanners) and the gold
ENROSED rose on a wine medallion in the centre. Error correction H keeps the code
readable with the medallion covering the middle. The PNG keeps an alpha channel so
PdfImageEncoder embeds it losslessly instead of re-encoding it as JPEG.

Usage (from the repository root):
    python3 -m venv /tmp/qr-venv && /tmp/qr-venv/bin/pip install segno pillow
    /tmp/qr-venv/bin/python scripts/generate-catalog-quote-qr.py

Scan every generated file with a phone before committing new artwork.
"""
from pathlib import Path

import segno
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ASSETS = Path(__file__).resolve().parent.parent / 'src/main/resources/catalog-assets'
LANGUAGES = ('nl', 'fr', 'en', 'de', 'es', 'pl', 'pt', 'tr', 'el')

IVORY = (255, 247, 237, 255)
WINE = (101, 22, 41, 255)
GOLD = (214, 178, 94, 255)        # the gold of logo-gold-print.png
GOLD_LINE = (185, 146, 80, 255)   # a deeper gold that stays visible on ivory

VERSION = 4           # 33 x 33 modules holds every localized URL at level H
MODULE_PX = 24        # 1008 px for the whole card: 640 dpi or more at the printed 30-40 mm
QUIET = 4.5           # ivory margin around the symbol, in modules
SUPERSAMPLE = 4       # drawn large, then downscaled for smooth edges
TILE = 0.9            # module tile size within its cell
TILE_RADIUS = 0.32
MEDALLION = 4.6       # medallion radius, in modules
ROSE_BOX = (1729, 55, 2442, 730)  # the rose inside the "O" of the word mark


def quote_url(language):
    return 'https://enrosed.com/' + ('' if language == 'en' else language + '/') + 'quote/'


def rose_emblem():
    """Cuts the rose out of the word mark: petals are the solid shapes inside the crop,
    the neighbouring R and S cross its edge and loose anti-aliasing crumbs are tiny."""
    alpha = Image.open(ASSETS / 'logo-gold-print.png').convert('RGBA').getchannel('A').crop(ROSE_BOX)
    solid = alpha.point(lambda value: 255 if value > 128 else 0)
    pixels = solid.load()
    width, height = solid.size
    for y in range(height):
        for x in range(width):
            if pixels[x, y] != 255:
                continue
            ImageDraw.floodfill(solid, (x, y), 128)
            part = solid.point(lambda value: 255 if value == 128 else 0)
            left, top, right, bottom = part.getbbox()
            petal = (left > 0 and top > 0 and right < width and bottom < height
                     and part.histogram()[255] >= 400)
            ImageDraw.floodfill(solid, (x, y), 254 if petal else 0)
    petals = solid.point(lambda value: 255 if value == 254 else 0)
    rose = ImageChops.multiply(alpha, petals.filter(ImageFilter.MaxFilter(5)))
    return rose.crop(rose.getbbox())


def branded_qr(url, rose):
    qr = segno.make(url, error='h', version=VERSION, boost_error=False)
    matrix = [list(row) for row in qr.matrix]
    n = len(matrix)
    m = MODULE_PX * SUPERSAMPLE
    size = round((n + 2 * QUIET) * m)
    offset = QUIET * m
    card = Image.new('RGBA', (size, size), IVORY)
    draw = ImageDraw.Draw(card)

    inset = 1.5 * m
    draw.rectangle((inset, inset, size - 1 - inset, size - 1 - inset),
                   outline=GOLD_LINE, width=round(0.22 * m))

    finders = ((0, 0), (0, n - 7), (n - 7, 0))
    centre = n / 2

    def in_finder(row, col):
        return any(r <= row < r + 7 and c <= col < c + 7 for r, c in finders)

    def under_medallion(row, col):
        return ((row + .5 - centre) ** 2 + (col + .5 - centre) ** 2) ** .5 < MEDALLION + .7

    pad = (1 - TILE) / 2 * m
    for row in range(n):
        for col in range(n):
            if matrix[row][col] and not in_finder(row, col) and not under_medallion(row, col):
                x, y = offset + col * m, offset + row * m
                draw.rounded_rectangle((x + pad, y + pad, x + m - pad, y + m - pad),
                                       radius=TILE_RADIUS * m, fill=WINE)
    for row, col in finders:
        x, y = offset + col * m, offset + row * m
        draw.rectangle((x, y, x + 7 * m, y + 7 * m), fill=WINE)
        draw.rectangle((x + m, y + m, x + 6 * m, y + 6 * m), fill=IVORY)
        draw.rectangle((x + 2 * m, y + 2 * m, x + 5 * m, y + 5 * m), fill=WINE)

    c = offset + centre * m
    radius = MEDALLION * m
    draw.ellipse((c - radius, c - radius, c + radius, c + radius), fill=WINE)
    ring = radius - .4 * m
    draw.ellipse((c - ring, c - ring, c + ring, c + ring), outline=GOLD, width=round(.16 * m))
    scale = 2 * radius * .6 / max(rose.size)
    mask = rose.resize((round(rose.width * scale), round(rose.height * scale)), Image.LANCZOS)
    card.paste(Image.new('RGBA', mask.size, GOLD),
               (round(c - mask.width / 2), round(c - mask.height / 2)), mask)
    return card.resize((size // SUPERSAMPLE, size // SUPERSAMPLE), Image.LANCZOS)


def main():
    rose = rose_emblem()
    for language in LANGUAGES:
        target = ASSETS / f'quote-qr-{language}.png'
        branded_qr(quote_url(language), rose).save(target, optimize=True)
        print(target.relative_to(ASSETS.parent.parent.parent.parent), quote_url(language))


if __name__ == '__main__':
    main()
