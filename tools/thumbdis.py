# Thumb disassembler for ROM ranges with literal-pool annotation. Usage: thumbdis.py rom.gba 0x08XXXXXX len
import sys, struct, capstone
d = open(sys.argv[1], 'rb').read()
a = int(sys.argv[2], 16) & ~1; n = int(sys.argv[3], 16)
md = capstone.Cs(capstone.CS_ARCH_ARM, capstone.CS_MODE_THUMB)
for i in md.disasm(d[a - 0x08000000:a - 0x08000000 + n], a):
    extra = ''
    if i.mnemonic == 'ldr' and '[pc' in i.op_str:
        off = int(i.op_str.split('#')[-1].rstrip(']'), 16)
        lit = ((i.address + 4) & ~3) + off
        extra = f'   ; ={struct.unpack_from("<I", d, lit - 0x08000000)[0]:08X}'
    print(f'{i.address:08X}: {i.mnemonic:6s} {i.op_str}{extra}')
