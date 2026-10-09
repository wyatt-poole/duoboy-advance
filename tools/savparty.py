# Decodes the party from a FireRed-family .sav with the same logic as Game.decodeMon. Usage: savparty.py rom.gba save.sav
import sys, struct
rom = open(sys.argv[1], 'rb').read(); sav = open(sys.argv[2], 'rb').read()
CH = {0: ' ', 0xAD: '.', 0xAE: '-', 0xB4: "'", 0xB8: ',', 0x1B: 'é', 0xB5: 'm', 0xB6: 'f'}
CH.update({0xA1+i: str(i) for i in range(10)}); CH.update({0xBB+i: chr(65+i) for i in range(26)}); CH.update({0xD5+i: chr(97+i) for i in range(26)})
def txt(b): return ''.join(CH.get(c, '?') for c in b.split(b'\xff')[0])
GROWTH_POS = [0]*6 + [1,1,2,3,2,3]*3
# newest of the two 14-section save slots
slots = []
for s in (0, 0xE000):
    idx = struct.unpack_from('<I', sav, s + 0xFFC)[0]; slots.append((idx, s))
base = max(slots)[1]
sec = {struct.unpack_from('<H', sav, base + i*0x1000 + 0xFF4)[0]: base + i*0x1000 for i in range(14)}
sb1 = sav[sec[1]:sec[1] + 0xF80]
count = sb1[0x34]
hdr = 0x100 + 8 + 32; names = struct.unpack_from('<I', rom, hdr + 28)[0] - 0x08000000
print('party count', count)
for i in range(count):
    o = 0x38 + i*100
    pid, otid = struct.unpack_from('<II', sb1, o); key = pid ^ otid
    ws = [w ^ key for w in struct.unpack_from('<12I', sb1, o + 32)]
    if sum((w & 0xFFFF) + (w >> 16) for w in ws) & 0xFFFF == struct.unpack_from('<H', sb1, o + 28)[0]:
        g = ws[GROWTH_POS[pid % 24] * 3]  # vanilla: encrypted + shuffled
    else: g = struct.unpack_from('<I', sb1, o + 32)[0]  # CFRU/Unbound: raw, fixed order
    sp = g & 0xFFFF
    lv, hp, mx = sb1[o+84], *struct.unpack_from('<HH', sb1, o+86)
    print(f'{txt(sb1[o+8:o+18]):10s} species {sp:4d} {txt(rom[names+11*sp:names+11*sp+11]):11s} Lv{lv} {hp}/{mx}')
