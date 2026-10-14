#!/usr/bin/env python3
"""Exercise an already selected route in the disposable emulator; never targets a car."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--apk', required=True, type=Path)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Disposable emulator only')

    def adb(*command, binary=False):
        return subprocess.check_output(['adb', '-s', args.serial, *command], timeout=25,
                                       text=not binary)

    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        parser.error('Not a QEMU emulator')
    paths = adb('shell', 'pm', 'path', 'ru.yandex.yandexnavi').splitlines()
    if len(paths) != 1: parser.error('Expected one installed APK')
    expected = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    if adb('shell', 'sha256sum', paths[0][8:]).split()[0] != expected:
        parser.error('Installed candidate differs from --apk')
    args.out.mkdir(parents=True, exist_ok=False)
    report = {'apk_sha256': expected, 'snapshots': [], 'checks': {}, 'partial_json_retries': 0}
    original_pid = adb('shell', 'pidof', 'ru.yandex.yandexnavi').strip()

    def command(action, **values):
        path = args.out / 'command.json'
        path.write_text(json.dumps(dict(action=action, nonce=time.time_ns(), **values)))
        adb('push', str(path), '/data/local/tmp/denza-hud-command.json')
        adb('shell', 'cp', '/data/local/tmp/denza-hud-command.json',
            '/data/data/ru.yandex.yandexnavi/files/denza-hud-command.pending')
        adb('shell', 'chmod', '644', '/data/data/ru.yandex.yandexnavi/files/denza-hud-command.pending')
        adb('shell', 'mv', '/data/data/ru.yandex.yandexnavi/files/denza-hud-command.pending',
            '/data/data/ru.yandex.yandexnavi/files/denza-hud-command.json')

    def read_json(path):
        # Emulator evidence files are rewritten in place at frame rate. A read can
        # land between truncate and write; retry only decoding, never failed ADB.
        for attempt in range(5):
            raw = adb('shell', 'cat', path)
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                if attempt == 4:
                    raise
                report['partial_json_retries'] += 1
                time.sleep(0.05)

    def snapshot(name):
        item = {'name': name, 'host_monotonic': time.monotonic()}
        for key, path in [('renderer', '/data/data/ru.yandex.yandexnavi/files/denza-hud-evidence/latest.json'),
                          ('receiver', '/data/data/com.ts.car.someip.service/files/report.json')]:
            item[key] = read_json(path)
        item['activity'] = adb('shell', 'dumpsys', 'activity', 'activities')
        report['snapshots'].append(item)
        (args.out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(name, item['renderer'], item['receiver'], flush=True)
        return item

    def frames(name):
        target = args.out / name
        target.mkdir()
        adb('pull', '/data/data/com.ts.car.someip.service/files/.', str(target))
        return len({hashlib.sha256(p.read_bytes()).hexdigest() for p in target.glob('frame-*.png')})

    try:
        command('background', enabled=True)
        time.sleep(1)
        command('simulate')
        time.sleep(8)
        first = snapshot('foreground-start')
        time.sleep(8)
        second = snapshot('foreground-end')
        report['checks']['foreground_frames'] = second['receiver']['frames'] - first['receiver']['frames']
        report['checks']['foreground_unique_png'] = frames('foreground')
        # Road packets with a manoeuvre picture, metres and id; the limit only where the route has one.
        report['checks']['hint_packets'] = second['receiver'].get('hints', 0) - first['receiver'].get('hints', 0)
        report['checks']['limit_packets'] = second['receiver'].get('limits', 0) - first['receiver'].get('limits', 0)
        report['checks']['last_hint'] = second['receiver'].get('lastHint')
        (args.out / 'foreground.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(3)
        first = snapshot('background-start')
        time.sleep(12)
        second = snapshot('background-end')
        report['checks']['background_frames'] = second['receiver']['frames'] - first['receiver']['frames']
        report['checks']['background_unique_png'] = frames('background')
        report['checks']['hidden'] = not second['renderer']['visible']
        (args.out / 'background.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        receiver_pid = adb('shell', 'pidof', 'com.ts.car.someip.service').strip()
        if not receiver_pid.isdecimal(): raise RuntimeError('Expected one emulator receiver process')
        adb('shell', 'kill', '-9', receiver_pid)
        time.sleep(4)
        recovered = snapshot('receiver-reconnected')
        report['checks']['reconnected'] = (recovered['receiver']['starts'] == 1
                and recovered['receiver']['frames'] > 0 and recovered['receiver']['active']
                and recovered['receiver']['uptime'] > second['receiver']['uptime']
                and adb('shell', 'pidof', 'com.ts.car.someip.service').strip() != receiver_pid)
        command('background', enabled=False)
        time.sleep(3)
        off = snapshot('background-disabled')
        time.sleep(3)
        off_after = snapshot('background-disabled-stable')
        report['checks']['background_disabled_clears'] = not off['receiver']['active'] and not off['receiver']['session']
        report['checks']['background_disabled_stops_frames'] = off['receiver']['frames'] == off_after['receiver']['frames']
        command('stop')
        time.sleep(2)
        final = snapshot('cancelled')
        report['checks']['wire_errors'] = max(item['receiver']['bad'] for item in report['snapshots'])
        report['checks']['clear_frames'] = final['receiver']['blanks']
    finally:
        command('stop')
        time.sleep(2)
        report['checks']['process_survived_cleanup'] = adb('shell', 'pidof', 'ru.yandex.yandexnavi').strip() == original_pid
        (args.out / 'crash.log').write_text(adb('logcat', '-d', '-b', 'crash', '--pid=' + original_pid))
        (args.out / 'module.log').write_text(adb('logcat', '-d', '-v', 'threadtime', '-s', 'DenzaYandexHud', 'HudReceiver'))
        (args.out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    check = report['checks']
    ok = (check.get('foreground_frames', 0) >= 50 and check.get('background_frames', 0) >= 80
          and check.get('hint_packets', 0) > 0
          and check.get('foreground_unique_png', 0) > 10 and check.get('background_unique_png', 0) > 10
          and check.get('hidden') and check.get('background_disabled_clears')
          and check.get('reconnected')
          and check.get('process_survived_cleanup')
          and check.get('background_disabled_stops_frames') and check.get('wire_errors') == 0
          and check.get('clear_frames', 0) >= 3)
    report['passed'] = bool(ok)
    (args.out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    if not ok: raise SystemExit('Acceptance failed; inspect report and pixels')
    print('Foreground/background/clear acceptance passed', flush=True)


if __name__ == '__main__':
    main()
