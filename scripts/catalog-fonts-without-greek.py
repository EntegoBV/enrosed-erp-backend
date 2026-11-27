#!/usr/bin/env python3
"""Drop the Greek code points from the cmap of the catalogue fonts (Jost, Cormorant Garamond).

Jost and Cormorant carry a few Greek letters as maths symbols (Δ Ω μ π) and no other Greek. In a PDF the
renderer takes each character from the first face of the font-family list that maps it, so a Greek word
set in Jost drew those letters from Jost and the rest from DejaVu Sans: Ω read as a small ω. Without the
Greek code points DejaVu Sans draws every Greek letter, and Latin text and figures keep their face.

Only the Unicode cmap changes: the glyphs stay in the file (unmapped), every other table is copied byte for
byte (OS/2 keeps its Unicode range bits: the renderer picks faces by the cmap alone), the checksums are
recomputed. The cmap is written as plain delta segments, a larger table than the original's glyph arrays
with the same mapping. Python standard library only; run from the repository root:

    python3 scripts/catalog-fonts-without-greek.py src/main/resources/fonts/catalog/Jost-*.ttf ...

Running it again on a stripped font changes nothing. Expects TrueType fonts whose Unicode subtables are
all one format 4 table (true for the six catalogue fonts); anything else is refused.
"""
import struct
import sys

GREEK = [(0x0370, 0x03FF), (0x1F00, 0x1FFF)]


def is_greek(code):
    return any(low <= code <= high for low, high in GREEK)


def checksum(data):
    data = data + b'\0' * (-len(data) % 4)
    return sum(struct.unpack('>%dI' % (len(data) // 4), data)) & 0xFFFFFFFF


def read_format4(table, offset):
    fmt, length, _language, seg_x2 = struct.unpack('>HHHH', table[offset:offset + 8])
    if fmt != 4:
        raise SystemExit('cmap subtable is format %d, not 4' % fmt)
    segs = seg_x2 // 2
    base = offset + 14
    ends = struct.unpack('>%dH' % segs, table[base:base + seg_x2])
    starts = struct.unpack('>%dH' % segs, table[base + seg_x2 + 2:base + 2 * seg_x2 + 2])
    deltas = struct.unpack('>%dh' % segs, table[base + 2 * seg_x2 + 2:base + 3 * seg_x2 + 2])
    range_at = base + 3 * seg_x2 + 2
    ranges = struct.unpack('>%dH' % segs, table[range_at:range_at + seg_x2])
    mapping = {}
    for i in range(segs):
        for code in range(starts[i], ends[i] + 1):
            if code == 0xFFFF:
                continue
            if ranges[i] == 0:
                glyph = (code + deltas[i]) & 0xFFFF
            else:
                at = range_at + 2 * i + ranges[i] + 2 * (code - starts[i])
                glyph = struct.unpack('>H', table[at:at + 2])[0]
                if glyph:
                    glyph = (glyph + deltas[i]) & 0xFFFF
            if glyph:
                mapping[code] = glyph
    return mapping, length


def write_format4(mapping):
    segments = []
    for code in sorted(mapping):
        delta = (mapping[code] - code) & 0xFFFF
        if segments and segments[-1][1] == code - 1 and segments[-1][2] == delta:
            segments[-1][1] = code
        else:
            segments.append([code, code, delta])
    segments.append([0xFFFF, 0xFFFF, 1])
    count = len(segments)
    power = 1 << (count.bit_length() - 1)
    header_x2 = 2 * count
    body = b''.join([
        struct.pack('>%dH' % count, *[s[1] for s in segments]),
        b'\0\0',
        struct.pack('>%dH' % count, *[s[0] for s in segments]),
        struct.pack('>%dH' % count, *[s[2] for s in segments]),
        struct.pack('>%dH' % count, *([0] * count)),
    ])
    length = 14 + len(body)
    return struct.pack('>HHHHHHH', 4, length, 0, header_x2, 2 * power, power.bit_length() - 1,
                       header_x2 - 2 * power) + body


def strip(path):
    font = open(path, 'rb').read()
    version, count = struct.unpack('>IH', font[:6])
    if version != 0x00010000:
        raise SystemExit('%s: not a TrueType font' % path)
    records = []
    for i in range(count):
        tag, _sum, offset, length = struct.unpack('>4sIII', font[12 + 16 * i:28 + 16 * i])
        records.append([tag, offset, length])
    tables = {tag: font[offset:offset + length] for tag, offset, length in records}

    cmap = tables[b'cmap']
    _v, subtables = struct.unpack('>HH', cmap[:4])
    encodings = [struct.unpack('>HHI', cmap[4 + 8 * i:12 + 8 * i]) for i in range(subtables)]
    unicode_offsets = {offset for pid, eid, offset in encodings if pid == 0 or (pid == 3 and eid in (0, 1, 10))}
    if len(unicode_offsets) != 1:
        raise SystemExit('%s: expected one shared Unicode subtable, found %s' % (path, encodings))
    mapping, _length = read_format4(cmap, unicode_offsets.pop())
    kept = {code: glyph for code, glyph in mapping.items() if not is_greek(code)}
    dropped = sorted(set(mapping) - set(kept))
    if not dropped:
        print('%s: no Greek code points, unchanged' % path)
        return
    if any(pid not in (0, 3) for pid, _eid, _offset in encodings):
        raise SystemExit('%s: a non-Unicode subtable would be lost: %s' % (path, encodings))

    head_records = struct.pack('>HH', 0, subtables)
    sub_offset = 4 + 8 * subtables
    for pid, eid, _offset in encodings:
        head_records += struct.pack('>HHI', pid, eid, sub_offset)
    tables[b'cmap'] = head_records + write_format4(kept)

    head = bytearray(tables[b'head'])
    head[8:12] = b'\0\0\0\0'
    tables[b'head'] = bytes(head)

    out = bytearray(font[:12])
    directory_end = 12 + 16 * count
    out += b'\0' * (directory_end - 12)
    placed = {}
    for tag, _offset, _length in sorted(records, key=lambda record: record[1]):
        out += b'\0' * (-len(out) % 4)
        placed[tag] = len(out)
        out += tables[tag]
    out += b'\0' * (-len(out) % 4)
    for i, (tag, _offset, _length) in enumerate(records):
        data = tables[tag]
        out[12 + 16 * i:28 + 16 * i] = struct.pack('>4sIII', tag, checksum(data), placed[tag], len(data))
    adjustment = (0xB1B0AFBA - checksum(bytes(out))) & 0xFFFFFFFF
    head_at = placed[b'head']
    out[head_at + 8:head_at + 12] = struct.pack('>I', adjustment)
    open(path, 'wb').write(bytes(out))
    print('%s: dropped %d Greek code points (%s)' % (path, len(dropped), ' '.join(chr(code) for code in dropped)))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    for name in sys.argv[1:]:
        strip(name)
