import sys, struct
def e(s): return s.encode('cp037')
def field(tid, data=b''):
    return b'\x5a' + struct.pack('>H', 8 + len(data)) + tid.to_bytes(3, 'big') + b'\x00\x00\x00' + data
def name8(n): return e(n.ljust(8))
def trip(i, d): return bytes([2 + len(d), i]) + d
def tle(n, v, qualifier=b''):
    return field(0xD3A090, qualifier + trip(0x02, b'\x0b\x00' + e(n)) + trip(0x36, b'\x00\x00' + e(v)))
BDT,EDT,BNG,ENG,BPG,EPG,BAG,EAG,PGD = 0xD3A8A8,0xD3A9A8,0xD3A8AD,0xD3A9AD,0xD3A8AF,0xD3A9AF,0xD3A8C9,0xD3A9C9,0xD3A6AF
# PGD: units base 00/00 (10 inches), 14400 units per 10 in, page 8.5 x 11 in = 12240 x 15840
pgd = bytes([0,0]) + struct.pack('>HH', 14400, 14400) + (12240).to_bytes(3,'big') + (15840).to_bytes(3,'big') + b'\x00\x00\x00'
qual = trip(0x80, bytes([0,0,0,1, 0,0,0,1]))
out = (field(BDT, name8('STMTRUN'))
  + field(BNG, name8('G0000001'))
  + tle('accountNumber', '1234567890', qual) + tle('name', 'AVERY NGUYEN') + tle('zip', '80202')
  + field(BPG, name8('P0000001')) + field(BAG) + field(PGD, pgd) + field(EAG) + field(EPG)
  + field(ENG)
  + field(BNG, name8('G0000002'))
  + tle('accountNumber', '5550001234')
  + field(BPG, name8('P0000002')) + field(EPG)
  + field(ENG)
  + field(EDT))
open(sys.argv[1], 'wb').write(out)
print(len(out), 'bytes')
