#!/usr/bin/env python3
"""Log the cluster's vehicle signals from the host, one CSV row per second.

The panel has been designed on one parked start/stop cycle and no drive. What the
generation id means while the car moves, whether the rpm id follows anything but the
engine, what a 500 m bin of consumption looks like on a real road - none of it has
a recording behind it. This script is the recording.

It asks the same `autoservice` Binder the app asks, through the same `service call`
chain (`AutoserviceShell.command`), but from the host over ADB, so nothing in the
product changes and the car's shell carries one extra batch per second.

**Decoding is the app's, all of it**: the second parcel word, the signed `i32`
argument, the Binder sentinels, the rpm id's own "not available" pattern, floats
by bit pattern, the scale - and the plausibility gate. A value outside the range
its unit can physically occupy is a bad read the app drops rather than draws
(`VehicleKind.accepts`), so the decoded column is empty where the panel would
show nothing. The raw word is kept beside it either way, which is what lets a
decoding argument be settled from the file afterwards - including an argument
about the gate.

    python3 tools/vehicle_log.py --hours 8 --interval 1.0
    python3 tools/vehicle_log.py --check      # the decoding, without a car

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
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class Signal:
    """One id, and what the app would make of the word it answers with.

    `scale` is the app's own (`VehicleSignal.scale`); `invalid` is the signal's
    own "not available" pattern, on top of the Binder sentinels every signal
    shares. `low`/`high` are the plausibility gate the app puts every decoded
    value through (`VehicleKind.accepts`) - the Binder answers a failed or
    unsupported read with a *number*, and the app drops one outside the range
    its unit can physically occupy rather than drawing a decoded sentinel.

    The decoded column is empty for a word the app would drop; the raw column
    keeps it either way, so a decoding argument is still settleable from the
    file afterwards.
    """

    name: str
    device: int
    fid: int
    transact: int
    low: float
    high: float
    scale: float = 1.0
    invalid: Optional[int] = None


FLOAT = 7
INT = 5

# The ranges are `VehicleKind.accepts`, one line per kind the app gates with.
POWER = (-600.0, 600.0)
CHARGE_POWER = (-1.0, 160.0)
DISTANCE = (0.0, 2_000_000.0)          # OdometerGate.MAX_ODOMETER_KM
HIGH_VOLT = (60.0, 1000.0)
RPM = (0.0, 9000.0)
FLAG = (0.0, 255.0)
SWITCH = (0.0, 1.0)
# And two the app has no reader for, gated the way its own kinds are.
SPEED = (0.0, 300.0)
PERCENT = (0.0, 100.0)

SIGNALS = [
    Signal('power_kw', 1012, 0x14400020, INT, *POWER),               # + out of the pack
    Signal('generation_kw', 1006, 0x2610001F, INT, *POWER),
    Signal('generation_state', 1006, 0x34F0000A, INT, *FLAG),        # 1 generating, 2 spinning down
    Signal('engine_running', 1012, 0x10D00038, INT, *FLAG),          # 0 stopped, 3 running
    Signal('engine_rpm', 1012, 0x14400012, INT, *RPM, invalid=0x1FFF),
    Signal('engine_rpm_20d', 1012, 0x20D00008, INT, *RPM),           # cross-check
    Signal('engine_charge_kw', 1009, 0x2ED00010, INT, *POWER),       # catalog "engine charge power"
    Signal('speed_kmh', 1013, 0x94400008, FLOAT, *SPEED),
    Signal('odometer_km', 1014, 0x4A502010, INT, *DISTANCE, scale=0.1),
    Signal('pack_volt', 1009, 0x44400008, INT, *HIGH_VOLT),
    Signal('park', 1011, 0x05500030, INT, *SWITCH),                  # 1 in P
    Signal('accelerator', 1013, 0x34200008, INT, *PERCENT),
    Signal('brake', 1013, 0x34200010, INT, *SWITCH),
    Signal('charge_gun', 1009, 0x34400032, INT, 0.0, 8.0),
    Signal('charge_kw', 1009, 0x32300018, FLOAT, *CHARGE_POWER),
    Signal('fuel_percent', 1014, 0x4A507040, INT, *PERCENT),
]

MARKER = '@@'
PARCEL = re.compile(r'Parcel\(([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})')
SENTINELS = {0xFFFFD8E3, 0xFFFFD8E5, 0xBF800000}


def signed(word):
    """An `i32` argument is signed: the app sends 0x94400008 as -1807745016, and so must this."""
    return word - (1 << 32) if word >= (1 << 31) else word


def command():
    return '; '.join(
        f'echo {MARKER}{i}; service call autoservice {s.transact} i32 {s.device} i32 {signed(s.fid)}'
        for i, s in enumerate(SIGNALS))


def decode(signal, word):
    """The word as the app would read it, or None where the app would drop it."""
    if word in SENTINELS or (signal.invalid is not None and word == signal.invalid):
        return None
    if signal.transact == FLOAT:
        raw = struct.unpack('>f', struct.pack('>I', word))[0]
        if raw != raw or raw in (float('inf'), float('-inf')):
            return None
    else:
        raw = signed(word)
    value = raw * signal.scale
    if not signal.low <= value <= signal.high:
        return None
    return value


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


def format_value(signal, value):
    """A float id keeps three decimals; everything else is written as it decodes."""
    if value is None:
        return ''
    return f'{value:.3f}' if signal.transact == FLOAT else f'{value:g}'


def check():
    """A cheap self-test of the decoding, so a change to it is not a silent one.

    Not a substitute for the car: what it holds is the arithmetic between a
    parcel word and a column - the sentinels, the signed `i32`, the float bit
    pattern, the scale, and the plausibility gate the app puts every value
    through.
    """
    by_name = {s.name: s for s in SIGNALS}
    cases = [
        ('power_kw', 0x00000022, 34.0),
        ('power_kw', 0xFFFFFFEA, -22.0),
        ('power_kw', 0x00000400, None),          # 1024 kW is not a pack
        ('power_kw', 0xFFFFD8E3, None),          # a Binder sentinel
        ('engine_rpm', 0x00001FFF, None),        # the id's own "not available"
        ('engine_rpm', 0x000006F0, 1776.0),
        ('engine_running', 0x00000003, 3.0),
        ('odometer_km', 0x00002694, 987.6),      # tenths of a kilometre
        ('odometer_km', 0xFFFFFFFF, None),       # -1 is not a distance
        ('pack_volt', 0x00000228, 552.0),
        ('pack_volt', 0x0000000A, None),         # 10 V is not this pack
        ('park', 0x00000001, 1.0),
        ('park', 0x00000003, None),              # a switch has two answers
        ('speed_kmh', 0x42480000, 50.0),         # a float id
        ('charge_kw', 0x40199999, 2.4),
        ('charge_kw', 0x43960000, None),         # 300 kW is not this charger
        ('fuel_percent', 0x00000042, 66.0),
        ('fuel_percent', 0x000000FF, None),      # 255 is a word, not a percentage
    ]
    bad = 0
    for name, word, want in cases:
        signal = by_name[name]
        got = decode(signal, word)
        ok = want is None and got is None or (
            want is not None and got is not None and abs(got - want) < 1e-3)
        if not ok:
            bad += 1
            print(f'{name} {word:#010x}: expected {want}, decoded {got}')
    print(f'{len(cases) - bad}/{len(cases)} decode cases pass')
    return 0 if bad == 0 else 1


def adb(serial, *args, timeout=20):
    return subprocess.run(['adb', '-s', serial, *args], capture_output=True, text=True, timeout=timeout)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default=os.environ.get('ADB_SERIAL', '127.0.0.1:5555'))
    ap.add_argument('--hours', type=float, default=8.0)
    ap.add_argument('--interval', type=float, default=1.0)
    ap.add_argument('--out', default=None)
    ap.add_argument('--check', action='store_true',
                    help='run the decode self-test and exit; touches no car')
    args = ap.parse_args()

    if args.check:
        return check()

    start = dt.datetime.now()
    path = args.out or os.path.join('captures', 'vehicle-log', f'vehicle-{start:%Y%m%d-%H%M%S}.csv')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    cmd = command()
    names = [s.name for s in SIGNALS]
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
            for i, signal in enumerate(SIGNALS):
                w = words.get(i)
                v = decode(signal, w) if w is not None else None
                values.append(format_value(signal, v))
                raws.append('' if w is None else f'{w:08x}')
            f.write(','.join([dt.datetime.now().isoformat(timespec='milliseconds'), f'{tick:.3f}', *values, *raws]) + '\n')
            f.flush()
            rows += 1
            if rows % 600 == 0:
                print(f'{dt.datetime.now():%H:%M:%S} {rows} rows', flush=True)
            time.sleep(max(0.0, args.interval - (time.monotonic() - tick)))
    print(f'done: {rows} rows in {path}', flush=True)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main() or 0)
    except KeyboardInterrupt:
        sys.exit(130)
