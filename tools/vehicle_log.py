#!/usr/bin/env python3
"""Log the cluster's vehicle signals from the host, one CSV row per second.

The panel has been designed on one parked start/stop cycle and no drive. What the
generation id means while the car moves, whether the rpm id follows anything but the
engine, what a 500 m bin of consumption looks like on a real road - none of it has
a recording behind it. This script is the recording.

It asks the same `autoservice` Binder the app asks, through the same `service call`
chain (`AutoserviceShell.command`), but from the host over ADB, so nothing in the
product changes and the car's shell carries one extra batch per second. Decoding is
the app's: the second parcel word, the Binder sentinels, the rpm id's own "not
available" pattern, floats by bit pattern. Raw words are kept beside the decoded
values so a decoding argument can be settled from the file later.

    python3 tools/vehicle_log.py --hours 8 --interval 1.0

Writes captures/vehicle-log/vehicle-<start>.csv (git-ignored). Reconnects when the
car sleeps and keeps going; Ctrl-C stops it.
"""
import argparse
import datetime as dt
import os
import re
import struct
import subprocess
import sys
import time

# (name, device, fid, transact, scale, offset, invalid_word)
SIGNALS = [
    ('power_kw',        1012, 0x14400020, 5, 1.0, 0.0, None),   # POWER_KW, + out of the pack
    ('generation_kw',   1006, 0x2610001F, 5, 1.0, 0.0, None),   # GENERATION_KW
    ('generation_state',1006, 0x34F0000A, 5, 1.0, 0.0, None),   # 1 generating, 2 spinning down
    ('engine_running',  1012, 0x10D00038, 5, 1.0, 0.0, None),   # 0 stopped, 3 running
    ('engine_rpm',      1012, 0x14400012, 5, 1.0, 0.0, 0x1FFF),
    ('engine_rpm_20d',  1012, 0x20D00008, 5, 1.0, 0.0, None),   # cross-check
    ('engine_charge_kw',1009, 0x2ED00010, 5, 1.0, 0.0, None),   # catalog "engine charge power", tracks pack power
    ('speed_kmh',       1013, 0x94400008, 7, 1.0, 0.0, None),   # float
    ('odometer_km',     1014, 0x4A502010, 5, 0.1, 0.0, None),
    ('pack_volt',       1009, 0x44400008, 5, 1.0, 0.0, None),
    ('park',            1011, 0x05500030, 5, 1.0, 0.0, None),   # 1 in P
    ('accelerator',     1013, 0x34200008, 5, 1.0, 0.0, None),
    ('brake',           1013, 0x34200010, 5, 1.0, 0.0, None),
    ('charge_gun',      1009, 0x34400032, 5, 1.0, 0.0, None),
    ('charge_kw',       1009, 0x32300018, 7, 1.0, 0.0, None),   # float
    ('fuel_percent',    1014, 0x4A507040, 5, 1.0, 0.0, None),
]

MARKER = '@@'
PARCEL = re.compile(r'Parcel\(([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})')
SENTINELS = {0xFFFFD8E3, 0xFFFFD8E5, 0xBF800000}


def command():
    return '; '.join(
        f'echo {MARKER}{i}; service call autoservice {tx} i32 {dev} i32 {fid}'
        for i, (_, dev, fid, tx, _, _, _) in enumerate(SIGNALS))


def decode(spec, word):
    _, _, _, tx, scale, offset, invalid = spec
    if word in SENTINELS or (invalid is not None and word == invalid):
        return None
    if tx == 7:
        raw = struct.unpack('>f', struct.pack('>I', word))[0]
        if raw != raw or raw in (float('inf'), float('-inf')):
            return None
    else:
        raw = word - (1 << 32) if word >= (1 << 31) else word
    return raw * scale + offset


def parse(output):
    words = {}
    index = -1
    answered = False
    for line in output.splitlines():
        line = line.strip()
        if line.startswith(MARKER):
            try:
                index = int(line[len(MARKER):])
            except ValueError:
                index = -1
            answered = False
        elif not answered and 0 <= index < len(SIGNALS):
            m = PARCEL.search(line)
            if m:
                answered = True
                words[index] = int(m.group(2), 16)
    return words


def adb(serial, *args, timeout=20):
    return subprocess.run(['adb', '-s', serial, *args], capture_output=True, text=True, timeout=timeout)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default=os.environ.get('ADB_SERIAL', '127.0.0.1:5555'))
    ap.add_argument('--hours', type=float, default=8.0)
    ap.add_argument('--interval', type=float, default=1.0)
    ap.add_argument('--out', default=None)
    args = ap.parse_args()

    start = dt.datetime.now()
    path = args.out or os.path.join('captures', 'vehicle-log', f'vehicle-{start:%Y%m%d-%H%M%S}.csv')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    cmd = command()
    names = [s[0] for s in SIGNALS]
    header = ['time', 'mono_s', *names, *[f'raw_{n}' for n in names]]
    deadline = time.monotonic() + args.hours * 3600.0
    rows = 0
    connected = None
    with open(path, 'w', encoding='utf-8') as f:
        f.write(','.join(header) + '\n')
        f.flush()
        print(f'logging to {path} for {args.hours:g} h at {args.interval:g} s', flush=True)
        while time.monotonic() < deadline:
            tick = time.monotonic()
            try:
                r = adb(args.serial, 'shell', cmd)
            except (subprocess.TimeoutExpired, OSError):
                r = None
            ok = r is not None and r.returncode == 0 and MARKER in r.stdout
            if not ok:
                if connected is not False:
                    print(f'{dt.datetime.now():%H:%M:%S} no answer from {args.serial}, reconnecting', flush=True)
                connected = False
                try:
                    subprocess.run(['adb', 'connect', args.serial], capture_output=True, text=True, timeout=15)
                except (subprocess.TimeoutExpired, OSError):
                    pass
                time.sleep(10.0)
                continue
            if connected is not True:
                print(f'{dt.datetime.now():%H:%M:%S} connected', flush=True)
                connected = True
            words = parse(r.stdout)
            values = []
            raws = []
            for i, spec in enumerate(SIGNALS):
                w = words.get(i)
                v = decode(spec, w) if w is not None else None
                values.append('' if v is None else (f'{v:.3f}' if isinstance(v, float) and spec[3] == 7 else f'{v:g}'))
                raws.append('' if w is None else f'{w:08x}')
            f.write(','.join([dt.datetime.now().isoformat(timespec='milliseconds'), f'{tick:.3f}', *values, *raws]) + '\n')
            f.flush()
            rows += 1
            if rows % 600 == 0:
                print(f'{dt.datetime.now():%H:%M:%S} {rows} rows', flush=True)
            time.sleep(max(0.0, args.interval - (time.monotonic() - tick)))
    print(f'done: {rows} rows in {path}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
