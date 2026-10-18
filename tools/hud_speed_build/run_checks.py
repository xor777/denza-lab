#!/usr/bin/env python3
"""Run paired instrumentation on an explicitly selected disposable emulator, never a vehicle."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True)
    p.add_argument('--source-apk',type=Path,required=True)
    p.add_argument('--host-apk',type=Path,required=True)
    p.add_argument('--checks',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    args=p.parse_args()
    if not args.serial.startswith('emulator-'): p.error('Disposable emulator only')
    adb=['/opt/homebrew/share/android-commandlinetools/platform-tools/adb','-s',args.serial]
    def command(*a): return subprocess.check_output(adb+list(a),timeout=30,text=True)
    if command('shell','getprop','ro.hardware').strip() not in ('ranchu','goldfish'): p.error('Not an emulator')
    args.out.mkdir(parents=True,exist_ok=False)
    for apk in [args.source_apk,args.host_apk,args.checks/'source/emulator-only-check.apk',args.checks/'host/emulator-only-check.apk']:
        result=command('install','--no-incremental','-r',str(apk))
        if 'Success' not in result: raise RuntimeError(result)
    for package in ['ru.yandex.yandexnavi','air.StrelkaHUDFREE','dev.denza.apps']:
        command('shell','am','force-stop',package)
    report={}
    for package,apk in [('air.StrelkaHUDFREE',args.source_apk),('dev.denza.apps',args.host_apk)]:
        path=command('shell','pm','path',package).strip().removeprefix('package:')
        actual=command('shell','sha256sum',path).split()[0]
        expected=hashlib.sha256(apk.read_bytes()).hexdigest()
        if actual!=expected: raise AssertionError('Installed APK differs: '+package)
        report[package]=actual
    host_log=args.out/'host.txt'; source_log=args.out/'source.txt'
    with host_log.open('w') as out:
        host=subprocess.Popen(adb+['shell','am','instrument','-w','dev.denza.hud.speed.check.host/dev.denza.hud.speed.check.HostCheck'],stdout=out,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+25
            while 'state=ready' not in host_log.read_text():
                if host.poll() is not None or time.monotonic()>deadline: raise RuntimeError(host_log.read_text())
                time.sleep(.1)
            source=subprocess.run(adb+['shell','am','instrument','-w','dev.denza.hud.speed.check.source/dev.denza.hud.speed.check.SourceCheck'],capture_output=True,text=True,timeout=35)
            source_log.write_text(source.stdout+source.stderr)
            host.wait(timeout=30)
        finally:
            if host.poll() is None: host.terminate(); host.wait(timeout=5)
    for log in [host_log,source_log]:
        if 'INSTRUMENTATION_CODE: -1' not in log.read_text() or 'PASS' not in log.read_text(): raise AssertionError(log.read_text())
    (args.out/'logcat.txt').write_text(command('logcat','-d','-v','brief','DenzaHudSpeed:I','DenzaHudHost:I','HudSpeedSourceCheck:E','HudSpeedHostCheck:E','*:S'))
    cards=command('shell','run-as','dev.denza.apps','ls','files/hud-speed-qa').splitlines()
    for card in cards:
        data=subprocess.check_output(adb+['exec-out','run-as','dev.denza.apps','cat','files/hud-speed-qa/'+card],timeout=15)
        (args.out/card).write_bytes(data)
    (args.out/'report.json').write_text(json.dumps(dict(apks=report,result='PASS',cards=cards),indent=2)+'\n')
    print(host_log.read_text()+source_log.read_text())


if __name__=='__main__': main()
