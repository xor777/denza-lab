"""Read the FSE (passenger-screen computer) OTA without unpacking it: list partitions, walk a
partition's ext4 tree, copy selected files.

The FSE package (`Di5.1_FSE_…zip`) is a plain Android A/B OTA: its `update.zip` member is stored
(not compressed, not encrypted) and carries an ordinary `payload.bin`. This reader opens that
member in place, parses the payload manifest, and hands partitions to the bounded ext4 reader from
research/telematics-firmware and research/split-firmware (full-replacement operations only; each
operation's SHA-256 is checked before it is decoded). Read-only: the archive is never modified.

    python3 research/fse-firmware/read_fse_ota.py ARCHIVE partitions
    python3 research/fse-firmware/read_fse_ota.py ARCHIVE walk PARTITION OUT.txt
    python3 research/fse-firmware/read_fse_ota.py ARCHIVE copy PARTITION OUT_DIR /path/in/partition ...
"""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve()
sys.path.insert(0, str(HERE.parents[1] / 'telematics-firmware'))
sys.path.insert(0, str(HERE.parents[1] / 'split-firmware'))


def _import_readers():
    # current_payload imports check_config, which wants these two variables; they are unused here.
    import os
    os.environ.setdefault('DENZA_FIRMWARE_OUTPUT', str(HERE.parent / '.unused-output'))
    os.environ.setdefault('DENZA_FIRMWARE_ARCHIVE', '/dev/null')
    from current_payload import Partition, fields, one  # noqa: E402
    from extract_system_files import StreamingExt4  # noqa: E402
    return Partition, fields, one, StreamingExt4


class Window:
    """A read-only file view of [base, base + length) of a larger file."""

    def __init__(self, path, base, length):
        self.f = open(path, 'rb')
        self.base = base
        self.length = length
        self.pos = 0

    def seek(self, pos, whence=0):
        self.pos = pos if whence == 0 else self.pos + pos if whence == 1 else self.length + pos
        return self.pos

    def tell(self):
        return self.pos

    def read(self, n=-1):
        if n < 0:
            n = self.length - self.pos
        n = max(0, min(n, self.length - self.pos))
        self.f.seek(self.base + self.pos)
        data = self.f.read(n)
        self.pos += len(data)
        return data

    def seekable(self):
        return True


def stored_member(archive, zf, name):
    info = zf.getinfo(name)
    assert info.compress_type == 0, f'{name} is compressed'
    with open(archive, 'rb') as f:
        f.seek(info.header_offset)
        header = f.read(30)
    n, x = struct.unpack_from('<HH', header, 26)
    return info.header_offset + 30 + n + x, info.file_size


class FsePayload:
    """Duck-types research/telematics-firmware's Payload for Partition."""

    def __init__(self, archive):
        _, fields, one, _ = _import_readers()
        outer = zipfile.ZipFile(archive)
        base, size = stored_member(archive, outer, 'update.zip')
        inner_view = Window(archive, base, size)
        inner = zipfile.ZipFile(inner_view)
        info = inner.getinfo('payload.bin')
        assert info.compress_type == 0
        inner_view.seek(info.header_offset)
        header = inner_view.read(30)
        n, x = struct.unpack_from('<HH', header, 26)
        self.view = Window(archive, base + info.header_offset + 30 + n + x, info.file_size)
        magic, version, manifest_len, sig_len = struct.unpack('>4sQQI', self.view.read(24))
        assert magic == b'CrAU' and version == 2
        self.manifest = fields(self.view.read(manifest_len))
        self.blob = 24 + manifest_len + sig_len
        self.block = one(self.manifest, 3, 4096)
        self.parts = {}
        for raw in self.manifest[13]:
            part = fields(raw)
            self.parts[one(part, 1).decode()] = part

    def read(self, offset, length):
        self.view.seek(self.blob + offset)
        return self.view.read(length)


def main(argv):
    if len(argv) < 2 or argv[0] in ('-h', '--help'):
        print(__doc__)
        return
    Partition, fields, one, StreamingExt4 = _import_readers()
    archive, command = argv[0], argv[1]
    payload = FsePayload(archive)
    if command == 'partitions':
        from collections import Counter
        for name, part in payload.parts.items():
            info = fields(one(part, 7))
            kinds = Counter(one(fields(o), 1) for o in part[8])
            print(name, one(info, 1), dict(kinds))
        return
    part = Partition(payload, argv[2])
    magic = part.read(1024 + 56, 2)
    if magic != b'\x53\xef':
        print('not ext4; first bytes:', part.read(0, 16).hex(), part.read(1024, 16).hex())
        return
    fs = StreamingExt4(part)
    if command == 'walk':
        with open(argv[3], 'w') as out:
            def walk(ino, path):
                for name, (child, kind) in sorted(fs.entries(ino).items()):
                    full = f'{path}/{name}'
                    if kind == 2:
                        out.write(f'D {full}\n')
                        walk(child, full)
                    elif kind == 7:
                        out.write(f'L {full}\n')
                    else:
                        out.write(f'F {full} {fs.size_of(child)}\n')
            walk(2, '')
        return
    if command == 'copy':
        out_dir = Path(argv[3]).resolve()
        manifest = out_dir / 'extraction.json'
        records = json.loads(manifest.read_text()) if manifest.exists() else []
        for path in argv[4:]:
            record = fs.copy(path, out_dir / argv[2] / path.lstrip('/'))
            record['partition'] = argv[2]
            record['archive'] = Path(archive).name
            records.append(record)
            print(json.dumps(record), flush=True)
        manifest.write_text(json.dumps(records, indent=1))
        return
    raise SystemExit('unknown command')


if __name__ == '__main__':
    main(sys.argv[1:])
