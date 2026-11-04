"""Draws the Marketplace logo.

The 16 px icon that ships in the plug-in (icons/valuetable.png) is a green
table: a header band, a 2x2 grid, a dark green outline. That is the identity,
so this draws the same glyph at a size a Marketplace listing can use, where a
16 px bitmap scaled up would be mush.

Run it from this directory: python make-logo.py
"""

from PIL import Image, ImageChops, ImageDraw

SIZE = 1024

OUTLINE = (45, 120, 75, 255)      # #2d784b, sampled from the 16 px icon
HEADER = (63, 164, 95, 255)       # #3fa45f, same
CELL = (255, 255, 255, 255)
GRID = (174, 194, 182, 255)       # light green-grey, so the rules stay quiet

# Proportions follow the 16 px icon: a header about a quarter of the height,
# one vertical and one horizontal rule below it, an outline all round.
X0, Y0, X1, Y1 = 88, 168, 936, 856
BORDER = 44
RADIUS = 84
HEADER_BOTTOM = Y0 + 176
INSET = BORDER + 2

image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
draw.rounded_rectangle([X0, Y0, X1, Y1], radius=RADIUS, fill=CELL, outline=OUTLINE, width=BORDER)

# Everything that has to stay inside the outline is drawn on its own layer and
# then clipped to the inner rounded rectangle, so the corners keep their curve.
inner = Image.new("L", (SIZE, SIZE), 0)
ImageDraw.Draw(inner).rounded_rectangle(
    [X0 + INSET, Y0 + INSET, X1 - INSET, Y1 - INSET], radius=RADIUS - INSET, fill=255
)

layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
paint = ImageDraw.Draw(layer)
paint.rectangle([X0, Y0, X1, HEADER_BOTTOM], fill=HEADER)

mid_x = (X0 + X1) // 2
mid_y = (HEADER_BOTTOM + Y1) // 2
RULE = 22
paint.rectangle([mid_x - RULE // 2, HEADER_BOTTOM, mid_x + RULE // 2, Y1], fill=GRID)
paint.rectangle([X0, mid_y - RULE // 2, X1, mid_y + RULE // 2], fill=GRID)

image.paste(layer, (0, 0), ImageChops.multiply(layer.split()[3], inner))

for size in (512, 128):
    image.resize((size, size), Image.LANCZOS).save(f"logo-{size}.png", optimize=True)

print("wrote logo-512.png and logo-128.png")
