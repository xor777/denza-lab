"""Copy whole files out of the owner's OTA system partition, for the split reconstruction.

Builds on the bounded readers in research/telematics-firmware (same environment variables:
DENZA_FIRMWARE_ARCHIVE, DENZA_FIRMWARE_OUTPUT). Those cap a file at 16 MiB; SystemUI, Launcher3
and services.jar are larger, so this streams each extent in 8 MiB reads instead. Read-only: the
archive is never modified and nothing touches a car.

    python3 research/split-firmware/extract_system_files.py OUT_DIR /system/framework/services.jar ...
    python3 research/split-firmware/extract_system_files.py --list /system/framework

Every copied file is appended to OUT_DIR/extraction.json with its size and SHA-256.
"""
import hashlib
import json
import stat
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'telematics-firmware'))
from current_payload import Partition, Payload  # noqa: E402
from extract_cloud import Ext4, u16, u32  # noqa: E402

CHUNK = 8 * 1024 * 1024


class StreamingExt4(Ext4):
    def size_of(self, inode_number):
        inode = self.inode(inode_number)
        return u32(inode, 4) + (u32(inode, 108) << 32)

    def copy(self, path, target):
        inode = self.inode(self.resolve(path))
        if not stat.S_ISREG(u16(inode, 0)):
            raise ValueError(f'not a regular file: {path}')
        if not u32(inode, 32) & 0x80000:
            raise ValueError(f'non-extent inode: {path}')
        size = u32(inode, 4) + (u32(inode, 108) << 32)
        target.parent.mkdir(parents=True, exist_ok=True)
        with open(target, 'wb') as out:
            out.truncate(size)
            for offset, length, physical in self.extents(inode[40:100]):
                length = min(length, size - offset)
                done = 0
                while done < length:
                    step = min(CHUNK, length - done)
                    out.seek(offset + done)
                    out.write(self.p.read(physical + done, step))
                    done += step
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        return {'path': path, 'size': size, 'sha256': digest}


def main(argv):
    fs = StreamingExt4(Partition(Payload(), 'system'))
    if argv[:1] == ['--list']:
        for path in argv[1:]:
            for name, (inode, kind) in sorted(fs.entries(fs.resolve(path)).items()):
                label = 'D' if kind == 2 else 'F'
                size = '' if kind == 2 else f' {fs.size_of(inode)}'
                print(f'{label} {path.rstrip("/")}/{name}{size}')
        return
    out_dir = Path(argv[0]).resolve()
    manifest = out_dir / 'extraction.json'
    records = json.loads(manifest.read_text()) if manifest.exists() else []
    for path in argv[1:]:
        record = fs.copy(path, out_dir / 'system' / path.lstrip('/'))
        records.append(record)
        print(json.dumps(record), flush=True)
    manifest.write_text(json.dumps(records, indent=1))


if __name__ == '__main__':
    main(sys.argv[1:])
