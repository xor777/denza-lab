#!/usr/bin/env python3
"""Recover local ELF function names from compressed .gnu_debugdata, offline.

Does not load/execute the input program. Writes the embedded ELF and a TSV of
function addresses, sizes, original names and c++filt-demangled names.
"""
import argparse
import csv
import io
import lzma
from pathlib import Path
import subprocess

from elftools.elf.elffile import ELFFile


def extract(path, out):
    with Path(path).open('rb') as source:
        elf = ELFFile(source)
        section = elf.get_section_by_name('.gnu_debugdata')
        if section is None:
            raise ValueError('No .gnu_debugdata section')
        decoder = lzma.LZMADecompressor(memlimit=128 * 1024 * 1024)
        raw = decoder.decompress(section.data(), max_length=32 * 1024 * 1024)
        if not decoder.eof or decoder.unused_data:
            raise ValueError('Incomplete, oversized or trailing mini debug data')
    mini = ELFFile(io.BytesIO(raw))
    table = mini.get_section_by_name('.symtab')
    if table is None:
        raise ValueError('Mini debug ELF has no symbol table')
    rows = [(s['st_value'], s['st_size'], s.name) for s in table.iter_symbols()
            if s['st_info']['type'] == 'STT_FUNC' and s['st_shndx'] != 'SHN_UNDEF']
    names = subprocess.run(['c++filt', '-n'], input='\n'.join(r[2] for r in rows) + '\n',
                           text=True, capture_output=True, check=True).stdout.splitlines()
    if len(names) != len(rows):
        raise ValueError('c++filt line count mismatch')
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'mini-debug.elf').write_bytes(raw)
    with (out / 'mini-functions.tsv').open('w', newline='') as target:
        writer = csv.writer(target, delimiter='\t', lineterminator='\n')
        writer.writerow(['address', 'size', 'name', 'demangled'])
        for row, name in zip(rows, names):
            writer.writerow([hex(row[0]), row[1], row[2], name])
    print('Recovered function symbols:', len(rows))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('elf', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    extract(args.elf, args.out)
