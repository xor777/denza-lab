#!/usr/bin/env python3
"""Build/run a finite, single-FID typed warning listener over existing ADB.

Uses the stock AbsBYDAutoInstrumentListener implementation at runtime. The ABI
stub is compile-only, and dex class definitions are checked before deployment.
No APK install, vehicle setter, UDS, registration-table update or reconnect.
"""
import argparse
import datetime as dt
import importlib.util
import json
from pathlib import Path
import re
import secrets
import selectors
import shlex
import struct
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = 'dev.denza.tools.WasherWarningEventProbe'
SOURCE = ROOT / 'tools/WasherWarningEventProbe.java'
STUB = ROOT / 'tools/washer-warning-stubs/android/hardware/bydauto/instrument/AbsBYDAutoInstrumentListener.java'
BOOTSTRAP = ROOT / 'apps/denza-apps/src/main/java/dev/denza/apps/platform/shell/ShellProxyBootstrap.java'
HEALTH = ROOT / 'tools/vehicle-diagnostics/run_supported_dtc_probe.py'
JDK = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
SDK = Path('/opt/homebrew/share/android-commandlinetools')
ANDROID = SDK / 'platforms/android-35/android.jar'
SERIAL = '127.0.0.1:5555'
spec = importlib.util.spec_from_file_location('washer_health', HEALTH)
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def defined_classes(jar):
    with zipfile.ZipFile(jar) as archive:
        assert archive.namelist() == ['classes.dex'], 'unexpected dex payload'
        data = archive.read('classes.dex')
    count, offset = struct.unpack_from('<II', data, 96)
    _, strings = struct.unpack_from('<II', data, 56)
    _, types = struct.unpack_from('<II', data, 64)
    result = []
    for index in range(count):
        type_index = struct.unpack_from('<I', data, offset + index * 32)[0]
        string_index = struct.unpack_from('<I', data, types + type_index * 4)[0]
        start = struct.unpack_from('<I', data, strings + string_index * 4)[0]
        while data[start] & 128: start += 1
        start += 1
        result.append(data[start:data.index(0, start)].decode('ascii'))
    return result


def build(out):
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'; classes.mkdir()
    sources = [SOURCE, STUB, BOOTSTRAP, Path(__file__).resolve(), HEALTH]
    source_pins = {str(p.relative_to(ROOT)): health.sha(p) for p in sources}
    (out/'source').mkdir()
    for path in sources:
        (out/'source'/path.name).write_bytes(path.read_bytes())
    commands = [
        [str(JDK/'javac'), '--release', '8', '-cp', str(ANDROID), '-d', str(classes),
         str(SOURCE), str(STUB), str(BOOTSTRAP)],
        [str(SDK/'build-tools/36.0.0/d8'), '--release', '--min-api', '33', '--lib', str(ANDROID),
         '--classpath', str(classes), '--output', str(out/'probe.jar')]]
    for index, command in enumerate(commands):
        if index == 1: command += [str(p) for p in sorted((classes/'dev').rglob('*.class'))]
        result = subprocess.run(command, capture_output=True, timeout=60)
        (out/f'build-{index}.txt').write_bytes(result.stdout + result.stderr)
        if result.returncode: raise RuntimeError(f'build step {index} failed')
    definitions = defined_classes(out/'probe.jar')
    assert definitions and all(p.startswith('Ldev/denza/') for p in definitions), definitions
    assert 'Ldev/denza/tools/WasherWarningEventProbe;' in definitions
    assert source_pins == {str(p.relative_to(ROOT)): health.sha(p) for p in sources}, 'source changed during build'
    save(out/'build.json', dict(main=MAIN, sources=source_pins, jarSha256=health.sha(out/'probe.jar'),
                               dexDefinitions=definitions, compileOnlyStubExcluded=True))
    print(json.dumps(dict(build=str(out), compileOnlyStubExcluded=True)))


def parse_log(text):
    lines = [line.split('] ', 1)[1] for line in text.splitlines() if line.startswith('[WasherWarningEventProbe] ')]
    events = [re.fullmatch(r'EVENT seq=(\d+) t_ns=(\d+) fid=0x26100008 value=(\d+) hex=0x([0-9A-F]+)', line)
              for line in lines if line.startswith('EVENT ')]
    if not all(events): raise ValueError('malformed callback')
    rows = [dict(sequence=int(m[1]), elapsedNs=int(m[2]), value=int(m[3])) for m in events]
    if any(int(m[3]) != int(m[4],16) or not 0 <= int(m[3]) <= 1023 for m in events): raise ValueError('invalid callback value')
    if [r['sequence'] for r in rows] != list(range(1, len(rows)+1)): raise ValueError('callback gap')
    if any(a['elapsedNs'] > b['elapsedNs'] for a,b in zip(rows,rows[1:])): raise ValueError('callback time reversal')
    done = [re.fullmatch(r'DONE registered=true events=(\d+) errors=0', line) for line in lines if line.startswith('DONE ')]
    if len(done) != 1 or not done[0] or int(done[0][1]) != len(rows): raise ValueError('missing/failed completion')
    if lines.count('UNREGISTERED') != 1: raise ValueError('no unique unregister')
    gets = [re.fullmatch(r'GET phase=(before_register|before_unregister|door_callback) t_ns=(\d+) value=(\d+) hex=0x([0-9A-F]+)', line)
            for line in lines if line.startswith('GET ')]
    if not all(gets) or [m[1] for m in gets if m[1] != 'door_callback'] != ['before_register','before_unregister']: raise ValueError('missing GETs')
    if any(int(m[3]) != int(m[4],16) or not 0 <= int(m[3]) <= 1023 for m in gets): raise ValueError('invalid GET')
    doors = [re.fullmatch(r'DOOR_EVENT seq=(\d+) t_ns=(\d+) area=([1-4]) state=([01])', line)
             for line in lines if line.startswith('DOOR_EVENT ')]
    if not all(doors) or [int(m[1]) for m in doors] != list(range(1,len(doors)+1)): raise ValueError('invalid door callbacks')
    door_done = [line for line in lines if line.startswith('DOOR_DONE ')]
    if door_done and door_done != ['DOOR_DONE events='+str(len(doors))]: raise ValueError('door completion mismatch')
    if doors and not door_done: raise ValueError('missing door completion')
    if len([m for m in gets if m[1] == 'door_callback']) != len(doors): raise ValueError('missing door-time warning GET')
    return dict(callbacks=rows, callbackCount=len(rows), distinctValues=sorted({r['value'] for r in rows}),
                changes=sum(a['value'] != b['value'] for a,b in zip(rows,rows[1:])),
                getValues=[int(m[3]) for m in gets], heartbeatCount=sum(x.startswith('HEARTBEAT ') for x in lines),
                doorCallbacks=[dict(sequence=int(m[1]),elapsedNs=int(m[2]),area=int(m[3]),state=int(m[4])) for m in doors],
                realLowFluidQualified=False)


def run(build_dir, out, seconds, doors=False):
    if not 10 <= seconds <= 120: raise ValueError('duration: 10..120')
    manifest = json.loads((build_dir/'build.json').read_text())
    if manifest['main'] != MAIN or health.sha(build_dir/'probe.jar') != manifest['jarSha256']: raise ValueError('build mismatch')
    for path, digest in manifest['sources'].items():
        if health.sha(ROOT/path) != digest: raise ValueError('source changed after build: '+path)
    if defined_classes(build_dir/'probe.jar') != manifest['dexDefinitions']: raise ValueError('dex changed')
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    name = 'denza_washer_warning_' + secrets.token_hex(8)
    remote = '/data/local/tmp/' + name + '.jar'
    state = dict(started=dt.datetime.now(dt.timezone.utc).isoformat(), processName=name, remoteJar=remote,
                 durationSeconds=seconds, doorControl=doors, apkInstalled=False, vehicleWrites=0, sourceBuild=manifest,
                 hypothesis='Typed callback delivers the selected warning independently of raw CAN export.',
                 falsifiers=['registration/error/cleanup failure', 'no callback does not qualify delivery',
                             'constant callback value does not qualify real changes or washer trigger'],
                 reset='No persisted setting touched; fresh unique process, unregister and remove own jar.')
    save(out/'run.json', state)
    staged = False
    def adb(args, required=True, limit=20):
        result = health.adb_command(SERIAL, args, limit=limit)
        if required and result.returncode: raise RuntimeError('ADB failed: '+repr(args[:2]))
        return result
    try:
        before = health.health(SERIAL, out/'preflight')
        if not health.acceptable(before): raise ValueError('preflight refused; inspect retained status')
        processes = (out/'preflight/processes.txt').read_text()
        if 'denza_washer_warning_' in processes: raise ValueError('another washer observer exists')
        staged = True
        adb(['push', str(build_dir/'probe.jar'), remote])
        if adb(['shell','sha256sum',remote]).stdout.decode().split() != [manifest['jarSha256'], remote]: raise ValueError('remote hash mismatch')
        command = 'CLASSPATH='+shlex.quote(remote)+' exec '+shlex.join(['app_process','/system/bin','--nice-name='+name,MAIN,str(seconds)] + (['doors'] if doors else []))
        print(json.dumps(dict(started=True, seconds=seconds, processName=name)), flush=True)
        # Stream readiness and actual events to the host while preserving the full log.
        # A file-backed stderr cannot block a pipe; the independent device watchdog remains.
        with (out/'stdout.txt').open('wb') as stdout, (out/'stderr.txt').open('wb') as stderr:
            process = subprocess.Popen(['adb','-s',SERIAL,'shell','-T',command],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=stderr)
            deadline = time.monotonic()+seconds+35
            selector = selectors.DefaultSelector(); selector.register(process.stdout, selectors.EVENT_READ)
            try:
                while selector.get_map():
                    if time.monotonic() > deadline: raise TimeoutError('host deadline; device watchdog owns exit')
                    for key,_ in selector.select(timeout=0.25):
                        chunk = key.fileobj.read1(65536)
                        if not chunk: selector.unregister(key.fileobj); break
                        stdout.write(chunk); stdout.flush()
                        print(chunk.decode(errors='replace'), end='', flush=True)
                state['exitCode'] = process.wait(timeout=5)
            finally:
                selector.close()
                if process.poll() is None:
                    process.kill(); process.wait(timeout=5) # Host adb client only, never a remote process.
        if state['exitCode']: raise ValueError('probe did not complete normally')
        state['observation'] = parse_log((out/'stdout.txt').read_text())
    except Exception as error:
        state['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        if staged:
            try:
                absent = adb(['shell','pidof',name], required=False)
                state['processAbsent'] = absent.returncode == 1 and not absent.stdout and not absent.stderr
                # Never remove a jar still in use or stop unrelated processes.
                if state['processAbsent']:
                    adb(['shell','rm','--',remote])
                    missing = adb(['shell','test','-e',remote], required=False)
                    state['jarRemoved'] = missing.returncode == 1 and not missing.stdout and not missing.stderr
                after = health.health(SERIAL, out/'postflight')
                state['healthStable'] = (health.acceptable(after) and before['stockPids'] == after['stockPids']
                                         and before['appSha256'] == after['appSha256'])
            except Exception as error:
                state['cleanupError'] = type(error).__name__ + ': ' + str(error)
        state['ended'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save(out/'run.json', state)
    if not all(state.get(k) for k in ['processAbsent','jarRemoved','healthStable']): raise ValueError('cleanup/health incomplete')
    print(json.dumps(state['observation']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build','run'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--doors', action='store_true', help='also observe four fixed door FIDs as controls')
    args = parser.parse_args()
    if args.action == 'build': build(args.output.resolve())
    else:
        if not args.build: parser.error('--build required')
        run(args.build.resolve(), args.output.resolve(), args.seconds, args.doors)
