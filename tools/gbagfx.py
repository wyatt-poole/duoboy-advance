# GBA graphics helpers: LZ77 (BIOS type 0x10) decompress, 4bpp tile render, RGB555 palettes.
import struct
from PIL import Image

def lz77(d, o):
    assert d[o] == 0x10, hex(d[o])
    size = d[o+1] | d[o+2] << 8 | d[o+3] << 16
    out = bytearray(); o += 4
    while len(out) < size:
        flags = d[o]; o += 1
        for bit in range(8):
            if len(out) >= size: break
            if flags & (0x80 >> bit):
                b1, b2 = d[o], d[o+1]; o += 2
                n, disp = (b1 >> 4) + 3, ((b1 & 15) << 8 | b2) + 1
                for _ in range(n): out.append(out[-disp])
            else:
                out.append(d[o]); o += 1
    return bytes(out)

def pal(d, o, n=16):
    cols = []
    for i in range(n):
        c = struct.unpack_from('<H', d, o + 2*i)[0]
        cols.append(((c & 31) << 3, (c >> 5 & 31) << 3, (c >> 10 & 31) << 3, 0 if i == 0 else 255))
    return cols

def sprite(data, tw, th, p, off=0):
    """Sprite frame: tiles in row-major order (1D mapping)."""
    img = Image.new('RGBA', (tw*8, th*8))
    for t in range(tw*th):
        for i in range(32):
            b = data[off + t*32 + i]; x = (t % tw)*8 + (i % 4)*2; y = (t // tw)*8 + i // 4
            img.putpixel((x, y), p[b & 15]); img.putpixel((x+1, y), p[b >> 4])
    return img
