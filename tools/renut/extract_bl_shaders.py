"""Shader containers of Burst Limit from the player's LONG2DATA_US.CPK, for XenosRecomp (Renut M1).

    python3 -I tools/renut/extract_bl_shaders.py <LONG2DATA_US.CPK> <out dir>

The game's shader library is /XBOX360.NSH inside the CPK (docs/renut/AVALIACAO_M1.md, section 3). Two differences
from the MGR reader (mgr-nx-deko tools/installer_probe): file offsets count from ContentOffset, not TocOffset, and the
CRILAYLA compression has its 8 magic bytes zeroed. Each Xenos container (magic 0x102A1100/01) is written once as
vs_/ps_<sha256 prefix>.bin, like extract_mgr_cpk_shaders.py. The output is game data: never commit or distribute it.
"""
import hashlib
import os
import struct
import sys

UTF_TYPES = {0: ('B', 1), 1: ('b', 1), 2: ('H', 2), 3: ('h', 2), 4: ('I', 4), 5: ('i', 4), 6: ('Q', 8),
             7: ('q', 8), 8: ('f', 4), 0xA: ('I', 4), 0xB: ('II', 8)}


def utf_table(b):
    if b[:4] != b'@UTF':  # tables encrypted with the usual CRI xor stream
        m, out = 0x655F, bytearray(b)
        for i in range(len(out)):
            out[i] ^= m & 0xFF
            m = (m * 0x4115) & 0xFFFF
        b = bytes(out)
    assert b[:4] == b'@UTF', b[:4]
    _, rows_off, str_off, _, _ = struct.unpack('>IIIII', b[4:24])
    ncol, rowlen, nrows = struct.unpack('>HHI', b[24:32])

    def string(o):
        s = 8 + str_off + o
        return b[s:b.index(b'\0', s)].decode('utf8', 'replace')

    def value(t, p):
        f, n = UTF_TYPES[t]
        v = struct.unpack('>' + f, b[p:p + n])
        return (string(v[0]) if t == 0xA else v if t == 0xB else v[0]), n

    cols, p = [], 32
    for _ in range(ncol):
        flags, name = b[p], string(struct.unpack('>I', b[p + 1:p + 5])[0])
        p += 5
        const = None
        if flags & 0xF0 == 0x30:
            const, n = value(flags & 0x0F, p)
            p += n
        cols.append((name, flags & 0xF0, flags & 0x0F, const))
    rows = []
    for r in range(nrows):
        p, row = 8 + rows_off + r * rowlen, {}
        for name, storage, t, const in cols:
            if storage == 0x50:
                row[name], n = value(t, p)
                p += n
            else:
                row[name] = const
        rows.append(row)
    return rows


def cpk_files(f):
    h = f.read(0x800)
    assert h[:4] == b'CPK ', 'not a CPK'
    hdr = utf_table(h[16:16 + struct.unpack('<Q', h[8:16])[0]])[0]
    f.seek(hdr['TocOffset'])
    t = f.read(16)
    rows = utf_table(f.read(struct.unpack('<Q', t[8:16])[0]))
    base = hdr['ContentOffset']
    return {f"{r.get('DirName') or ''}/{r['FileName']}": (base + r['FileOffset'], r['FileSize'], r['ExtractSize'])
            for r in rows}


def crilayla(src):
    """CRILAYLA, accepting a zeroed magic: 8 bytes magic, u32 LE size, u32 LE compressed size, the compressed bits
    (read backwards), then the first 0x100 bytes stored raw."""
    size, csize = struct.unpack('<II', src[8:16])
    prefix = src[0x10 + csize:0x10 + csize + 0x100]
    out = bytearray(size)
    pos, pool, left = 0x10 + csize - 1, 0, 0

    def bits(n):
        nonlocal pos, pool, left
        v = 0
        while n:
            if left == 0:
                pool, left, pos = src[pos], 8, pos - 1
            take = min(left, n)
            v = (v << take) | ((pool >> (left - take)) & ((1 << take) - 1))
            left -= take
            n -= take
        return v

    o = size - 1
    while o >= 0:
        if bits(1):
            ref = o + 3 + bits(13)
            length, level, widths = 3, 0, (2, 3, 5, 8)
            while True:
                v = bits(widths[level])
                length += v
                if v != (1 << widths[level]) - 1:
                    break
                level = min(level + 1, 3)
            for _ in range(length):
                out[o] = out[ref]
                o, ref = o - 1, ref - 1
        else:
            out[o] = bits(8)
            o -= 1
    return bytes(prefix) + bytes(out)


def containers(data):
    """Xenos shader containers (ShaderContainer of XenosRecomp shader.h): flags 0x102A1100 (pixel) / 0x102A1101
    (vertex), length = virtualSize + physicalSize."""
    found, p = [], 0
    while True:
        p = data.find(b'\x10\x2a\x11', p)
        if p < 0:
            return found
        if p % 4 == 0 and data[p + 3] in (0, 1):
            vsize, psize = struct.unpack('>II', data[p + 4:p + 12])
            if 0 < vsize + psize <= len(data) - p:
                found.append(data[p:p + vsize + psize])
                p += vsize + psize
                continue
        p += 1


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    cpk, out = sys.argv[1:]
    with open(cpk, 'rb') as f:
        files = cpk_files(f)
        off, fsize, esize = files['/XBOX360.NSH']
        f.seek(off)
        raw = f.read(fsize)
    nsh = crilayla(raw) if fsize != esize else raw
    if len(nsh) != esize or nsh[:4] != b'NSXR':
        sys.exit(f'extract_bl_shaders: XBOX360.NSH came out wrong ({len(nsh)} bytes of {esize}, {nsh[:4]!r})')
    os.makedirs(out, exist_ok=True)
    seen, kinds = set(), {'vs': 0, 'ps': 0}
    for c in containers(nsh):
        digest = hashlib.sha256(c).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        stage = 'vs' if c[3] & 1 else 'ps'
        kinds[stage] += 1
        with open(os.path.join(out, f'{stage}_{digest[:16]}.bin'), 'wb') as w:
            w.write(c)
    print(f"extract_bl_shaders: XBOX360.NSH {fsize} -> {len(nsh)} bytes, {kinds['vs']} VS + {kinds['ps']} PS -> {out}")


if __name__ == '__main__':
    main()
