#!/usr/bin/env python3
"""Build locally, inspect a fixed plan, or run the eight-child shell probe.

Run uses only an explicit existing ADB serial. No connect/server/forward/tunnel,
APK install, property setter, session write or product whitelist change exists.
The operator owns vehicle pre/post health, firmware identity and session ownership.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
JDK = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
SDK = Path('/opt/homebrew/share/android-commandlinetools')
ANDROID = SDK / 'platforms/android-35/android.jar'
D8 = SDK / 'build-tools/35.0.0/d8'
MAIN = 'dev.denza.diagnostics.probe.ChildIdentityReadProbe'
MANIFEST = ROOT / 'research/vehicle-diagnostics/ecu-child-identification-candidates-20261021.json'
BOOTSTRAP = ROOT / 'apps/denza-apps/src/main/java/dev/denza/apps/platform/shell/ShellProxyBootstrap.java'
SOURCES = [HERE / 'ChildIdentityReadProbe.java', HERE / 'ChildIdentityReadProbeTest.java', BOOTSTRAP, MANIFEST]
DECODER = ROOT / 'research/vehicle-diagnostics/decode_child_identity.py'
SOURCES += [Path(__file__).resolve(), DECODER]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def fixed_plan():
    rows = json.loads(MANIFEST.read_text())['candidates']
    source = (HERE / 'ChildIdentityReadProbe.java').read_text()
    compiled = re.findall(r'new Step\("([^"]+)", (0x[0-9A-F]+), (0x[0-9A-F]+), "([0-9A-F]+)"\)', source)
    expected = [(r['ecuId'], r['route']['requestId'], int(r['codeDid'], 16), r['cachedSoftwareCode']) for r in rows]
    actual = [(ecu, int(address, 16), int(did, 16), code) for ecu, address, did, code in compiled]
    if len(rows) != 8 or actual != expected:
        raise ValueError('Java plan differs from exact eight-child manifest')
    return [{'step': i, 'ecuId': r['ecuId'], 'requestIdHex': f"{r['route']['requestId']:03X}",
             'responseIdHex': f"{r['route']['responseId']:03X}", 'udsHex': r['proposedReadHex'],
             'cachedSoftwareCode': r['cachedSoftwareCode']} for i, r in enumerate(rows)]


def local(command, output=None):
    p = subprocess.run([str(x) for x in command], cwd=ROOT, capture_output=True, timeout=60)
    if output:
        output.write_bytes(p.stdout + p.stderr)
    if p.returncode:
        raise RuntimeError('Local command failed: ' + str(command[0]) + '\n' + p.stderr.decode(errors='replace'))
    return p.stdout.decode(errors='replace')


def validate_output(text):
    """Exact local reconciliation; completed sequence is distinct from eight matched identities."""
    plan = fixed_plan()
    requests = re.findall(r'^REQUEST step=(\d+) ecu=(\S+) t_ns=(\d+) raw=([0-9A-F]+)$', text, re.M)
    ends = re.findall(r'^STEP_END step=(\d+) state=(\S+)$', text, re.M)
    transports = re.findall(r'^TRANSPORT_RESULT step=(\d+) result=(\S+)$', text, re.M)
    if len(requests) != 8 or [int(x[0]) for x in requests] != list(range(8)):
        raise ValueError('Incomplete or reordered request list')
    if [int(x[0]) for x in ends] != list(range(8)) or transports != [(str(i), '0') for i in range(8)]:
        raise ValueError('Incomplete completion or transport list')
    if (text.count('\nREGISTERED\n') != 1 or text.count('\nUNREGISTERED\n') != 1
            or len(re.findall(r'^START steps=8 passive_ms=2000 request_ms=4000 overall_ms=50000$', text, re.M)) != 1
            or len(re.findall(r'^END reason=PLAN_DONE requests=8 completed=8 cleanup=true callbacks=\d+ queued=0$', text, re.M)) != 1):
        raise ValueError('Lifecycle completion not established')
    spec = importlib.util.spec_from_file_location('child_identity_decoder', DECODER)
    decoder = importlib.util.module_from_spec(spec); spec.loader.exec_module(decoder)
    frames = [[] for _ in range(8)]
    for step, at, raw in re.findall(r'^FRAME step=(\d+) t_ns=(\d+) raw=([0-9A-F]+)$', text, re.M):
        i = int(step)
        if i not in range(8): raise ValueError('Frame step outside fixed plan')
        start = int(requests[i][2])
        if not start <= int(at) < start + 4_000_000_000: raise ValueError('Frame outside request deadline')
        row = plan[i]
        frames[i].append(decoder.decode(bytes.fromhex(raw), request_id=int(row['requestIdHex'],16),
            response_id=int(row['responseIdHex'],16), did=int(row['udsHex'][2:],16), cached_code=row['cachedSoftwareCode']))
    decoded = []
    for i, (request, (_, state)) in enumerate(zip(requests, ends)):
        row = plan[i]
        expected = '0A' + row['requestIdHex'].zfill(4) + '03' + row['udsHex']
        if request[1] != row['ecuId'] or request[3] != expected: raise ValueError('Request bytes differ from manifest')
        if i and int(request[2]) <= int(requests[i-1][2]): raise ValueError('Non-increasing request timestamps')
        last = frames[i][-1] if frames[i] else None
        if state == 'REQUEST_TIMEOUT':
            if last is not None and last['state'] != 'response_pending': raise ValueError('Timeout contradicts received answer')
        else:
            if last is None: raise ValueError('Completion has no answer')
            expected_state = {'CACHE_MATCH':'identity_reply','CACHE_MISMATCH':'identity_reply',
                'ZERO_PLACEHOLDER':'zero_placeholder','NO_RESPONSE':'no_response','GATEWAY_TIMEOUT':'gateway_timeout'}.get(state)
            if state.startswith('UDS_NEGATIVE_'):
                expected_state = 'uds_negative'
                if last.get('negativeResponseCodeHex') != state.removeprefix('UDS_NEGATIVE_'): raise ValueError('NRC mismatch')
            if last['state'] != expected_state: raise ValueError('Completion contradicts strict decoder')
            if state == 'CACHE_MATCH' and last['matchesCachedCode'] is not True: raise ValueError('Cache match not proved')
            if state == 'CACHE_MISMATCH' and last['matchesCachedCode'] is not False: raise ValueError('Cache mismatch not proved')
        decoded.append({'step':i,'ecuId':row['ecuId'],'state':state,'frames':frames[i],'childDtcRead':False})
    return decoded


def build(output):
    plan = fixed_plan()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    local([JDK / 'javac', '--release', '8', '-cp', ANDROID, '-d', classes, *SOURCES[:3]], output / 'javac.txt')
    tests = local([JDK / 'java', '-cp', str(classes) + os.pathsep + str(ANDROID), MAIN + 'Test'], output / 'tests.txt')
    # Ship only the probe and bootstrap. Tests never enter the device dex.
    classfiles = [p for p in sorted(classes.rglob('*.class')) if 'ChildIdentityReadProbeTest' not in p.name]
    jar = output / 'child-identity-probe.jar'
    local([D8, '--release', '--min-api', '33', '--lib', ANDROID, '--output', jar, *classfiles], output / 'd8.txt')
    value = {'schema': 1, 'state': 'built_not_run', 'main': MAIN, 'minApi': 33, 'jar': jar.name,
             'jarSha256': sha(jar), 'sources': [{'path': str(p.relative_to(ROOT)), 'sha256': sha(p)} for p in SOURCES],
             'tests': tests.strip(), 'fixedPlan': plan, 'requestTimeoutMs': 4000, 'overallSoftMs': 50000,
             'overallHardMs': 52000, 'requestStallHardMs': 6000, 'vehicleOperationsDuringBuild': 0}
    (output / 'manifest.json').write_text(json.dumps(value, indent=2) + '\n')
    print(json.dumps(value, indent=2))


def run(build_dir, serial, output):
    fixed_plan()
    m = json.loads((build_dir / 'manifest.json').read_text())
    jar = build_dir / 'child-identity-probe.jar'
    if m.get('main') != MAIN or m.get('jarSha256') != sha(jar):
        raise ValueError('Build artifact mismatch')
    if m['sources'] != [{'path': str(p.relative_to(ROOT)), 'sha256': sha(p)} for p in SOURCES]:
        raise ValueError('Build source inputs changed')
    if not re.fullmatch(r'[A-Za-z0-9._:\-]+', serial):
        raise ValueError('Explicit existing serial required')
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    token = secrets.token_hex(12)
    remote = '/data/local/tmp/denza-child-identity-' + token + '.jar'
    adb = ['adb', '-s', serial]
    state = {'schema': 1, 'state': 'starting', 'serial': serial, 'remoteJar': remote,
             'jarSha256': sha(jar), 'operatorOwnsPrePostHealth': True, 'cleanupVerified': False}
    process = None
    buffers = {'stdout': bytearray(), 'stderr': bytearray()}

    def command(args, seconds=12):
        p = subprocess.run(adb + args, capture_output=True, timeout=seconds)
        if p.returncode:
            raise RuntimeError('Owned ADB command failed')
        return p.stdout.decode(errors='replace').strip()

    try:
        command(['push', str(jar), remote])
        if command(['shell', '-T', 'sha256sum ' + shlex.quote(remote)]).split()[0] != sha(jar):
            raise RuntimeError('Staged artifact hash mismatch')
        shell = 'CLASSPATH=' + shlex.quote(remote) + ' app_process /system/bin --nice-name=denza-child-identity-' + token + ' ' + MAIN
        process = subprocess.Popen(adb + ['shell', '-T', shell], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        # Keep stdin open, so caller loss/EOF becomes an independent cancellation signal.
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ, 'stdout')
        selector.register(process.stderr, selectors.EVENT_READ, 'stderr')
        deadline = time.monotonic() + 55
        while selector.get_map():
            if time.monotonic() >= deadline:
                raise TimeoutError('Host deadline')
            for key, _ in selector.select(min(.25, max(0, deadline - time.monotonic()))):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:
                    selector.unregister(key.fileobj)
                else:
                    buffers[key.data].extend(data)
                    if sum(map(len, buffers.values())) > 1024 * 1024:
                        raise RuntimeError('Probe output bound')
        rc = process.wait(timeout=2)
        text = bytes(buffers['stdout']).decode(errors='replace')
        (output / 'probe.txt').write_text(text)
        (output / 'probe.stderr').write_bytes(buffers['stderr'])
        decoded = validate_output('\n' + text) if rc == 0 else None
        complete = rc == 0 and decoded is not None
        if decoded is not None: (output / 'decoded.json').write_text(json.dumps(decoded, indent=2) + '\n')
        state.update(state='plan_completed' if complete else 'incomplete', exitCode=rc,
                     cacheMatches=len(re.findall(r'^STEP_END .* state=CACHE_MATCH$', text, re.M)),
                     childDtcRead=False, identitiesRequireOfflineDecoder=True)
    except Exception as error:
        state.update(state='failed', error=type(error).__name__)
        raise
    finally:
        if process is not None:
            if process.stdin and not process.stdin.closed:
                process.stdin.close()
            if process.poll() is None:
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try:
                        process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait(timeout=2)
        (output / 'probe.txt').write_bytes(buffers['stdout'])
        (output / 'probe.stderr').write_bytes(buffers['stderr'])
        # Remove exactly our nonce-named dex; never touch the shared lock or another process.
        try:
            command(['shell', '-T', 'rm -f ' + shlex.quote(remote)])
            state['cleanupVerified'] = command(['shell', '-T', 'test ! -e ' + shlex.quote(remote) + ' && echo ABSENT']) == 'ABSENT'
        except Exception:
            state['cleanupVerified'] = False
        (output / 'run.json').write_text(json.dumps(state, indent=2) + '\n')
    print(json.dumps(state, indent=2))
    if state['state'] != 'plan_completed' or not state['cleanupVerified']:
        raise SystemExit(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    p = sub.add_parser('plan'); p = sub.add_parser('build'); p.add_argument('--output', type=Path, required=True)
    p = sub.add_parser('run'); p.add_argument('--build', type=Path, required=True)
    p.add_argument('--serial', required=True); p.add_argument('--output', type=Path, required=True)
    a = parser.parse_args()
    if a.action == 'plan': print(json.dumps(fixed_plan(), indent=2))
    elif a.action == 'build': build(a.output.resolve())
    else: run(a.build.resolve(), a.serial, a.output.resolve())


if __name__ == '__main__':
    main()
