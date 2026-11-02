#!/usr/bin/env python3
"""Parked, read-only CPU and memory runs of the car, for comparing two builds.

The 2026-11-01 drives (`captures/drive-freezes/`) were two different configurations - Denza Apps,
Navigator and Music on, then all three off - so they say where the CPU goes, not what one change
saves. A comparison is two runs of this script with everything but the build held: parked, the
same foreground apps, a stable temperature, the same duration. It reads only: one shell loop on
the car prints a sample every few seconds, and the host keeps the raw text and a summary. It
installs nothing, starts or stops nothing, and changes no setting.

  run      one run into captures/perf-ab/<label>-<time>/: raw samples, identities, the app's
           meminfo at both ends, summary.json
  compare  two run directories (or their summary.json) side by side
  windows  pairs of `dumpsys window visible` and `dumpsys window com.byd.avc`, one a second,
           while the turn signals are worked by hand: the data to decide whether the side
           cameras' monitor can ask for AVC's windows alone (docs/instrument-display-findings.md)

Per process, CPU is utime+stime+cutime+cstime over the interval, as a percentage of one core
(CLK_TCK is read from the car). The shells the app holds open, with the processes they waited for,
and the resident vehicle reader are children of adbd and are summed as `adbd children`, so the two
ways of reading the car compare whole; a `denza_*` helper is also shown on its own. The sampling
shell itself is left out.
"""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
ADB = os.environ.get('ADB', 'adb')
SERIAL = os.environ.get('ADB_SERIAL', '127.0.0.1:5555')

# Processes followed by their `ps` name. The name in /proc/<pid>/stat is no use: it is cut to 15
# characters, and AVC renames its main thread (`TS_loadApaModel` on 2026-11-01).
NAMED = ('dev.denza.apps', 'autoservice', 'system_server', 'surfaceflinger',
         'ru.yandex.yandexnavi', 'ru.yandex.music', 'com.byd.avc', 'com.byd.cdr', 'com.byd.bydlogtool')
APPS = ('dev.denza.apps', 'ru.yandex.yandexnavi', 'ru.yandex.music')

SAMPLER = r'''
adbd=$(pidof adbd)
echo "CLK_TCK $(getconf CLK_TCK 2>/dev/null || echo 100)"
i=0
while [ "$i" -lt COUNT ]; do
  echo "SAMPLE $(cat /proc/uptime)"
  grep -E '^(cpu |processes )' /proc/stat
  grep -E '^MemAvailable:' /proc/meminfo
  for z in /sys/class/thermal/thermal_zone*; do
    read -r t < "$z/type"; [ "$t" = soc_max ] && { read -r v < "$z/temp"; echo "THERMAL $v"; }
  done
  ps -A -o PID,PPID,NAME | while read -r pid ppid name; do
    [ "$pid" = "$$" ] && continue
    case "$name" in
      NAMES|denza_*) read -r l < /proc/$pid/stat 2>/dev/null && echo "PSTAT $ppid $name $l" ;;
      *) [ "$ppid" = "$adbd" ] && read -r l < /proc/$pid/stat 2>/dev/null && echo "PSTAT $ppid $name $l" ;;
    esac
  done
  echo "ADBD $adbd"
  i=$((i+1))
  sleep INTERVAL
done
echo END
'''


def adb(*args, timeout=60):
    return subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, timeout=timeout)


def shell(command, timeout=60):
    result = adb('shell', command, timeout=timeout)
    return result.stdout.decode('utf-8', 'replace')


def sampler(count, interval):
    return (SAMPLER.replace('COUNT', str(count)).replace('INTERVAL', str(interval))
            .replace('NAMES', '|'.join(NAMED)))


def parse(text):
    """The raw samples as [{uptime, cpu, processes, mem_kb, thermal, procs: {pid: {...}}, adbd}]."""
    samples, clk = [], 100
    for line in text.splitlines():
        words = line.split()
        if not words:
            continue
        if words[0] == 'CLK_TCK':
            clk = int(words[1])
        elif words[0] == 'SAMPLE':
            samples.append({'uptime': float(words[1]), 'procs': {}, 'thermal': None})
        elif not samples:
            continue
        elif words[0] == 'cpu':
            samples[-1]['cpu'] = [int(value) for value in words[1:8]]
        elif words[0] == 'processes':
            samples[-1]['processes'] = int(words[1])
        elif words[0] == 'MemAvailable:':
            samples[-1]['mem_kb'] = int(words[1])
        elif words[0] == 'THERMAL':
            samples[-1]['thermal'] = int(words[1]) / 1000.0
        elif words[0] == 'ADBD':
            samples[-1]['adbd'] = words[1] if len(words) > 1 else None
        elif words[0] == 'PSTAT':
            ppid, name, stat = words[1], words[2], line.split(None, 3)[3]
            pid, rest = stat.split(' ', 1)
            fields = rest[rest.rindex(')') + 2:].split()
            # After the name: state ppid ... utime(14) stime(15) cutime(16) cstime(17), 1-based.
            ticks = sum(int(fields[index]) for index in (11, 12, 13, 14))
            samples[-1]['procs'][pid] = {'name': name, 'ppid': ppid, 'ticks': ticks}
    return clk, [sample for sample in samples if 'cpu' in sample]


def stats(values):
    values = [value for value in values if value is not None]
    if not values:
        return None
    ordered = sorted(values)
    return {'n': len(values), 'median': round(statistics.median(values), 2),
            'mean': round(statistics.fmean(values), 2),
            'p95': round(ordered[min(len(ordered) - 1, int(0.95 * len(ordered)))], 2),
            'max': round(ordered[-1], 2)}


def summarize(clk, samples):
    """Whole-system and per-process figures over consecutive sample pairs."""
    busy, kernel, forks, mem, thermal = [], [], [], [], []
    per = {}
    for before, after in zip(samples, samples[1:]):
        seconds = after['uptime'] - before['uptime']
        if seconds <= 0:
            continue
        a, b = before['cpu'], after['cpu']
        delta = [y - x for x, y in zip(a, b)]
        total = sum(delta)
        if total > 0:
            # user nice system idle iowait irq softirq
            busy.append(100.0 * (total - delta[3] - delta[4]) / total)
            kernel.append(100.0 * (delta[2] + delta[5] + delta[6]) / total)
        if 'processes' in before and 'processes' in after:
            forks.append((after['processes'] - before['processes']) / seconds)
        mem.append(after.get('mem_kb', 0) / 1024.0 if after.get('mem_kb') else None)
        thermal.append(after['thermal'])
        groups = {}
        for pid, proc in after['procs'].items():
            old = before['procs'].get(pid)
            if old is None or old['name'] != proc['name']:
                continue
            core = 100.0 * (proc['ticks'] - old['ticks']) / (clk * seconds)
            key = 'adbd children' if proc['ppid'] == after.get('adbd') and proc['name'] not in NAMED \
                else proc['name']
            groups[key] = groups.get(key, 0.0) + core
            if proc['name'].startswith('denza_'):
                groups[proc['name']] = groups.get(proc['name'], 0.0) + core
        for key, value in groups.items():
            per.setdefault(key, []).append(value)
    return {
        'intervals': len(busy),
        'cpu_busy_percent': stats(busy),
        'kernel_irq_percent': stats(kernel),
        'processes_per_second': stats(forks),
        'mem_available_mib': stats(mem),
        'soc_max_c': stats(thermal),
        'process_one_core_percent': {key: stats(values) for key, values in sorted(per.items())},
    }


def identities():
    lines = []
    for package in APPS:
        paths = shell('pm path ' + package).split()
        for path in paths:
            apk = path.removeprefix('package:')
            lines.append(package + ' ' + shell('sha256sum ' + apk).split(' ')[0])
    return '\n'.join(lines) + '\n'


def run(args):
    label = args.label
    stamp = dt.datetime.now().strftime('%Y%m%d-%H%M%S')
    out = Path(args.out) if args.out else ROOT / 'captures' / 'perf-ab' / f'{label}-{stamp}'
    out.mkdir(parents=True, exist_ok=False)
    (out / 'identity.txt').write_text(identities())
    (out / 'meminfo-start.txt').write_text(shell('dumpsys meminfo dev.denza.apps'))
    (out / 'top-start.txt').write_text(shell('top -b -n 1 -m 40'))
    count = int(args.minutes * 60 / args.interval) + 1
    print(f'{label}: {count} samples, one every {args.interval} s, into {out}', file=sys.stderr)
    started = time.time()
    result = adb('shell', sampler(count, args.interval), timeout=count * args.interval + 120)
    raw = result.stdout.decode('utf-8', 'replace')
    (out / 'raw.txt').write_text(raw)
    (out / 'meminfo-end.txt').write_text(shell('dumpsys meminfo dev.denza.apps'))
    (out / 'top-end.txt').write_text(shell('top -b -n 1 -m 40'))
    (out / 'readers-log.txt').write_text(shell('logcat -d -v time -s DenzaVehicleReaders'))
    clk, samples = parse(raw)
    summary = {'label': label, 'started': dt.datetime.fromtimestamp(started).isoformat(),
               'complete': raw.rstrip().endswith('END'), 'clk_tck': clk, **summarize(clk, samples)}
    (out / 'summary.json').write_text(json.dumps(summary, indent=1, ensure_ascii=False) + '\n')
    print(json.dumps(summary, indent=1, ensure_ascii=False))


def load(path):
    path = Path(path)
    return json.loads((path / 'summary.json' if path.is_dir() else path).read_text())


def compare(args):
    a, b = load(args.a), load(args.b)
    rows = [('CPU busy, median %', ('cpu_busy_percent', 'median')),
            ('kernel + IRQ, median %', ('kernel_irq_percent', 'median')),
            ('processes / s, median', ('processes_per_second', 'median')),
            ('MemAvailable, median MiB', ('mem_available_mib', 'median')),
            ('soc_max, median C', ('soc_max_c', 'median'))]

    def pick(summary, key, field):
        value = summary.get(key) or {}
        return value.get(field)

    print(f'{"":34} {a["label"]:>14} {b["label"]:>14}')
    for title, (key, field) in rows:
        print(f'{title:34} {pick(a, key, field)!s:>14} {pick(b, key, field)!s:>14}')
    names = sorted(set(a['process_one_core_percent']) | set(b['process_one_core_percent']))
    print('\none core %, mean')
    for name in names:
        left = (a['process_one_core_percent'].get(name) or {}).get('mean')
        right = (b['process_one_core_percent'].get(name) or {}).get('mean')
        print(f'  {name:32} {left!s:>14} {right!s:>14}')


def windows(args):
    stamp = dt.datetime.now().strftime('%Y%m%d-%H%M%S')
    out = Path(args.out) if args.out else ROOT / 'captures' / 'perf-ab' / f'windows-{stamp}'
    out.mkdir(parents=True, exist_ok=False)
    print(f'{args.count} pairs into {out}; work the turn signals, hazard and R meanwhile',
          file=sys.stderr)
    for index in range(args.count):
        at = time.time()
        (out / f'{index:04d}-visible.txt').write_text(shell('dumpsys window visible'))
        (out / f'{index:04d}-named.txt').write_text(shell('dumpsys window com.byd.avc'))
        time.sleep(max(0.0, 1.0 - (time.time() - at)))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    commands = parser.add_subparsers(dest='command', required=True)
    one = commands.add_parser('run')
    one.add_argument('label')
    one.add_argument('--minutes', type=float, default=10.0)
    one.add_argument('--interval', type=int, default=5)
    one.add_argument('--out')
    two = commands.add_parser('compare')
    two.add_argument('a')
    two.add_argument('b')
    pairs = commands.add_parser('windows')
    pairs.add_argument('--count', type=int, default=120)
    pairs.add_argument('--out')
    args = parser.parse_args()
    {'run': run, 'compare': compare, 'windows': windows}[args.command](args)


if __name__ == '__main__':
    main()
