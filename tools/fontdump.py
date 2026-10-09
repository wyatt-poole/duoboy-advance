# Renders a FireRed-format latin font (16x16 2bpp glyphs, 4 tiles) from a ROM to PNG for eyeballing.
import sys
from PIL import Image
d = open(sys.argv[1], 'rb').read()
base = int(sys.argv[2], 16) - 0x08000000
img = Image.new('P', (16*16, 16*16)); img.putpalette([255,255,255, 0,0,0, 160,160,160, 255,0,0] + [0]*756)
for g in range(256):
    gx, gy = (g % 16) * 16, (g // 16) * 16
    for t in range(4):
        tx, ty = (t % 2) * 8, (t // 2) * 8
        for row in range(8):
            v = d[base + g*0x40 + t*16 + row*2] | d[base + g*0x40 + t*16 + row*2 + 1] << 8
            for px in range(8):
                c = (v >> (14 - 2*px)) & 3  # 2bpp, high bits = leftmost pixel; 1=fg 2=shadow
                img.putpixel((gx+tx+px, gy+ty+row), c)
img.resize((img.width*3, img.height*3), Image.NEAREST).save(sys.argv[3])
