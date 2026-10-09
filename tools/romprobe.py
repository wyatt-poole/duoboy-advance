# Dumps the GF ROM header pointers of a Gen 3 ROM and decodes a few names. Usage: romprobe.py rom.gba
import sys, struct
d = open(sys.argv[1], 'rb').read()
CH = {0x00:' ',0xAD:'.',0xAE:'-',0xB4:"'",0xB8:',',0x1B:'é',0xBA:'/',0xB5:'♂',0xB6:'♀'}
CH.update({0xA1+i: str(i) for i in range(10)})
CH.update({0xBB+i: chr(65+i) for i in range(26)}); CH.update({0xD5+i: chr(97+i) for i in range(26)})
def text(o, n): return ''.join(CH.get(b, '?') for b in d[o:o+n]).split('\xff')[0]
def name(o, n):
    s = d[o:o+n]; s = s[:s.index(0xFF)] if 0xFF in s else s
    return ''.join(CH.get(b, '?') for b in s)
names = ['monFrontPics','monBackPics','monNormalPalettes','monShinyPalettes','monIcons','monIconPaletteIds','monIconPalettes','monSpeciesNames','moveNames','decorations']
h = 0x100 + 8 + 32
for i, n in enumerate(names):
    p = struct.unpack_from('<I', d, h + 4*i)[0]; print(f'{n:20s} {p:08X}')
sp = struct.unpack_from('<I', d, h + 28)[0] - 0x08000000
mv = struct.unpack_from('<I', d, h + 32)[0] - 0x08000000
print([name(sp + 11*i, 11) for i in (1, 4, 6, 25, 151, 252, 387, 494, 650, 722, 810, 906)])
print([name(mv + 13*i, 13) for i in (1, 33, 53, 354, 500, 700, 800)])

# Start menu action table: run of {text*, func*} whose first two texts are POKéDEX / POKéMON (any case).
def ptr(o): return struct.unpack_from('<I', d, o)[0]
def isrom(p): return 0x08000000 <= p < 0x08000000 + len(d)
for o in range(0, len(d) - 16, 4):
    a, f, b = ptr(o), ptr(o+4), ptr(o+8)
    if isrom(a) and isrom(b) and isrom(f) and f & 1 and name(a-0x08000000, 12).lower() == 'pokédex' and name(b-0x08000000, 12).lower() == 'pokémon':
        ents = []
        for i in range(24):
            t, fn = ptr(o+8*i), ptr(o+8*i+4)
            if not (isrom(t) and isrom(fn)): break
            ents.append(name(t-0x08000000, 16))
        print(f'start menu table @ {0x08000000+o:08X}:', ents)
