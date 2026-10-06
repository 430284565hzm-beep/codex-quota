from PIL import Image, ImageDraw, ImageFilter

SCALE = 3
SIZE = 256 * SCALE
icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))

def rounded_rectangle(draw, box, radius, fill):
    x0, y0, x1, y1 = box
    draw.rectangle((x0+radius, y0, x1-radius, y1), fill=fill)
    draw.rectangle((x0, y0+radius, x1, y1-radius), fill=fill)
    draw.ellipse((x0, y0, x0+2*radius, y0+2*radius), fill=fill)
    draw.ellipse((x1-2*radius, y0, x1, y0+2*radius), fill=fill)
    draw.ellipse((x0, y1-2*radius, x0+2*radius, y1), fill=fill)
    draw.ellipse((x1-2*radius, y1-2*radius, x1, y1), fill=fill)

shadow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(shadow)
rounded_rectangle(draw, (21*SCALE, 24*SCALE, 235*SCALE, 238*SCALE),
                  57*SCALE, (0, 0, 0, 75))
icon.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(12*SCALE)))

mask = Image.new("L", (SIZE, SIZE))
draw = ImageDraw.Draw(mask)
rounded_rectangle(draw, (19*SCALE, 18*SCALE, 237*SCALE, 236*SCALE),
                  57*SCALE, 255)

surface = Image.new("RGBA", (SIZE, SIZE))
pixels = surface.load()
for y in range(SIZE):
    t = y / float(SIZE)
    for x in range(SIZE):
        pixels[x, y] = (int(53 - 23*t), int(68 - 28*t), int(75 - 29*t), 255)
surface.putalpha(mask)
icon.alpha_composite(surface)

draw = ImageDraw.Draw(icon)
edge = (226, 250, 249, 125)
draw.line((76*SCALE, 19*SCALE, 180*SCALE, 19*SCALE), fill=edge, width=2*SCALE)
draw.line((76*SCALE, 235*SCALE, 180*SCALE, 235*SCALE), fill=edge, width=2*SCALE)
draw.line((20*SCALE, 75*SCALE, 20*SCALE, 179*SCALE), fill=edge, width=2*SCALE)
draw.line((236*SCALE, 75*SCALE, 236*SCALE, 179*SCALE), fill=edge, width=2*SCALE)
for bounds, start, end in [((20,19,132,131),180,270), ((124,19,236,131),270,360),
                           ((20,123,132,235),90,180), ((124,123,236,235),0,90)]:
    draw.arc(tuple(v*SCALE for v in bounds), start=start, end=end, fill=edge, width=2*SCALE)
draw.arc((63*SCALE, 63*SCALE, 193*SCALE, 193*SCALE),
         start=0, end=359, fill=(161, 188, 188, 95), width=15*SCALE)
draw.arc((63*SCALE, 63*SCALE, 193*SCALE, 193*SCALE),
         start=-90, end=172, fill=(134, 238, 199, 255), width=15*SCALE)
draw.ellipse((116*SCALE, 116*SCALE, 140*SCALE, 140*SCALE),
             fill=(235, 253, 246, 245))

icon = icon.resize((256, 256), Image.LANCZOS)
icon.save(__import__("pathlib").Path(__file__).with_name("CodexUsage.ico"),
          format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                               (64, 64), (128, 128), (256, 256)])
