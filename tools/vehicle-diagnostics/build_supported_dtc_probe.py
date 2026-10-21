#!/usr/bin/env python3
"""Offline build/test of fixed DTC inventory plans; no device/run operation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
JDK = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
SDK = Path('/opt/homebrew/share/android-commandlinetools')
ANDROID = SDK / 'platforms/android-35/android.jar'
D8 = SDK / 'build-tools/35.0.0/d8'
MAIN = 'dev.denza.diagnostics.probe.SupportedDtcReadProbe'
BOOTSTRAP = ROOT / 'apps/denza-apps/src/main/java/dev/denza/apps/platform/shell/ShellProxyBootstrap.java'
ASSEMBLER = ROOT / 'apps/denza-apps/src/main/java/dev/denza/apps/feature/diagnostics/bus/DiagnosticFrameAssembler.java'
CATALOG = ROOT / 'apps/denza-apps/src/main/java/dev/denza/apps/feature/diagnostics/ecu/EcuCatalog.kt'
JAVA = HERE / 'SupportedDtcReadProbe.java'
TEST = HERE / 'SupportedDtcReadProbeTest.java'
SOURCES = [JAVA, TEST, BOOTSTRAP, ASSEMBLER, Path(__file__).resolve(), CATALOG]
PINNED = [
    ('ECM', 0x7E0, '32440011E004000000'),
    ('BMS', 0x7E7, '4A520011E00D00047B'),
    ('FMCU', 0x7E3, '48370011E075000000'),
    ('ISG', 0x7E6, '48370011E0EA000000'),
    ('SMCU', 0x744, '48370011E086001100'),
    ('SMCU3', 0x745, '48370011E088001100'),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def plans():
    actual = [(name, int(address, 16), code) for name, address, code in re.findall(
        r'new Target\("([A-Z0-9]+)", (0x[0-9A-F]+), "([0-9A-F]{18})"\)', JAVA.read_text())]
    if actual != PINNED:
        raise ValueError('Java identities/addresses differ from six pinned targets')
    expected_calls = [('ecm-bms', 35000, 0x0A, False, 'ECM,BMS'), ('drives', 65000, 0x0A, False, 'FMCU,ISG,SMCU,SMCU3'),
                      ('rear-drives', 35000, 0x0A, False, 'SMCU,SMCU3'), ('isg-observe', 15000, 0x0A, False, 'ISG'),
                      ('snapshot-ecm-bms', 35000, 0x03, False, 'ECM,BMS'),
                      ('snapshot-drives', 65000, 0x03, False, 'FMCU,ISG,SMCU,SMCU3'),
                      ('bms-snapshot-record', 25000, 0x03, True, 'BMS')]
    compiled = []
    for name, ms, members in re.findall(
            r'if \("([^\"]+)"\.equals\(name\)\) return new Plan\(name,([0-9_]+),([^);]+)\);', JAVA.read_text()):
        parts = members.split(',')
        subfunction = int(parts.pop(0), 16) if parts[0].startswith('0x') else 0x0A
        read_record = parts.pop(0) == 'true' if parts[0] in ('true', 'false') else False
        compiled.append((name, int(ms.replace('_', '')), subfunction, read_record, ','.join(parts)))
    if compiled != expected_calls:
        raise ValueError('Java fixed plan membership/timeouts differ')
    catalog = CATALOG.read_text()
    for name, address, code in PINNED:
        # The product catalog is a pinned input, never edited by this builder.
        where = catalog.index('"' + name + '",')
        section = catalog[where:catalog.find('dtcQualified', where)]
        if f'0x{address:X}' not in section or code not in section:
            raise ValueError('Identity no longer agrees with retained product catalog: ' + name)
    result = {}
    for name, ms, subfunction, read_record, members in expected_calls:
        targets = []
        for member in members.split(','):
            label, address, code = next(p for p in PINNED if p[0] == member)
            requests = ['22F194', '190109', f'19{subfunction:02X}'] + (['19041A3A2201'] if read_record else [])
            target = {'name': label, 'requestIdHex': f'{address:03X}', 'responseIdHex': f'{address+8:03X}',
                      'expectedSoftwareCode': code, 'requests': requests}
            if read_record:
                target.update(expectedFormatIdHex='00', requiredSnapshotPair={'rawDtcHex': '1A3A22', 'recordNumberHex': '01'})
            targets.append(target)
        result[name] = {'name': name, 'maxRequests': len(targets)*(4 if read_record else 3), 'overallSoftMs': ms,
                        'overallHardMs': ms+2000, 'readKind': 'snapshot-record' if read_record else 'snapshot-index' if subfunction == 3 else 'supported-dtc',
                        'targets': targets}
    return result


def local(command, logfile):
    result = subprocess.run([str(x) for x in command], cwd=ROOT, capture_output=True, timeout=60)
    logfile.write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError('Local build/test failed: ' + str(command[0]) + '\n' + result.stderr.decode(errors='replace'))
    return result.stdout.decode(errors='replace')


def build(output):
    fixed = plans()
    pinned_sources = [{'path': str(p.relative_to(ROOT)), 'sha256': sha(p)} for p in SOURCES]
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    snapshot = output / 'sources'
    for source in pinned_sources:
        saved = snapshot / source['path']
        saved.parent.mkdir(parents=True, exist_ok=True)
        saved.write_bytes((ROOT / source['path']).read_bytes())
        if sha(saved) != source['sha256']:
            raise RuntimeError('Source changed while taking snapshot: ' + source['path'])
    classes = output / 'classes'
    classes.mkdir()
    local([JDK/'javac', '--release', '8', '-cp', ANDROID, '-d', classes,
           *[snapshot / p.relative_to(ROOT) for p in (JAVA, TEST, BOOTSTRAP, ASSEMBLER)]], output/'javac.txt')
    tests = local([JDK/'java', '-cp', str(classes)+os.pathsep+str(ANDROID), MAIN+'Test'], output/'tests.txt')
    classfiles = [p for p in sorted(classes.rglob('*.class')) if 'SupportedDtcReadProbeTest' not in p.name]
    jar = output/'supported-dtc-probe.jar'
    local([D8, '--release', '--min-api', '33', '--lib', ANDROID, '--output', jar, *classfiles], output/'d8.txt')
    if pinned_sources != [{'path': str(p.relative_to(ROOT)), 'sha256': sha(p)} for p in SOURCES]:
        raise RuntimeError('Source inputs changed during build; no successful manifest emitted')
    value = {'schema': 1, 'state': 'built_not_run', 'main': MAIN, 'minApi': 33, 'jar': jar.name,
             'jarSha256': sha(jar), 'sources': pinned_sources, 'sourceSnapshotDirectory': 'sources',
             'plans': fixed, 'tests': tests.strip(),
             'timeouts': {'requestTimeoutMs': 4000, 'requestStallHardMs': 6000, 'gapMs': 500,
                          'silenceMs': 2000, 'overallSoftMs': 65000, 'overallHardMs': 67000},
             'requestTimeoutMs': 4000, 'overallSoftMs': 65000, 'overallHardMs': 67000,
             'requestStallHardMs': 6000, 'vehicleOperationsDuringBuild': 0,
             'policyCaps': {'maxFrames': 16, 'maxUdsBytes': 4095, 'maxCallbackBufferBytes': 1024,
                            'maxCallbacksPerExchange': 64, 'maxCallbacksPerPlan': 1024,
                            'meaning': 'Conservative policy limits, not physical channel maxima; oversize aborts without partial success.'},
             'semantics': {'supportedListIsNotActiveFaults': True, 'formatSource': 'previous exact190109 response',
                           'snapshotIndexHasNoStatusMaskOrFormatByte': True,
                           'snapshotIndexPairsAreRawDtcAndRecordNumber': True,
                           'snapshotPayloadReadOnlyByPinnedBmsRecordPlan': True,
                           'snapshotRecordPrerequisites': 'Exact BMS identity,190109 format00,fresh1903 contains1A3A22/01',
                           'snapshotRecordPayload': 'Opaque bytes after exact5904 DTC/status/record/identifierCount header; no DID boundaries, units or active-fault inference',
                           'countMask09MustNotEqualSupportedListLength': True,
                           'nrc78': 'wait within original deadline',
                           'otherNrc': 'record raw negative, stop target; only11/12 imply unsupported subfunction/service'}}
    (output/'plan.json').write_text(json.dumps({'schema': 1, 'plans': fixed}, indent=2)+'\n')
    (output/'manifest.json').write_text(json.dumps(value, indent=2)+'\n')
    print(json.dumps(value, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    p = sub.add_parser('plan'); p.add_argument('--plan', choices=[
        'ecm-bms', 'drives', 'rear-drives', 'isg-observe', 'snapshot-ecm-bms', 'snapshot-drives', 'bms-snapshot-record'])
    p = sub.add_parser('build'); p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.action == 'plan':
        fixed = plans(); print(json.dumps(fixed[args.plan] if args.plan else {'schema': 1, 'plans': fixed}, indent=2))
    else:
        build(args.output.resolve())


if __name__ == '__main__':
    main()
