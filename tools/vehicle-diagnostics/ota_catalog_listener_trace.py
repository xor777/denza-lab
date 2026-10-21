#!/usr/bin/env python3
"""Observe the existing catalog export's listener checks without extra car calls.

Keeps the qualified dex, requests, timers, START/STOP scripts and guards exact.
Only fixed phase names, relative times and six known ports enter the trace.
"""
import argparse
import json
from pathlib import Path
import shutil

import ota_catalog_versions as versions

catalog = versions.catalog
TEMPLATE = catalog.ROOT/'captures/ota-catalog-versions-20261021/build-v2'
TEMPLATE_SHA = 'f35874731cb5022cd717043b5ddafa9d63c968da045c78338fceb56e029dc785'
READY_OLD = 'while self.ports() & ALL_PORTS != PORTS:'
READY_NEW = 'while (self.ports() & ALL_PORTS) not in (PORTS, PORTS - {46322}):'
CHANGES = [
    ('return listener_ports(self.shell("cat /proc/net/tcp /proc/net/tcp6"))',
     '''observed = listener_ports(self.shell("cat /proc/net/tcp /proc/net/tcp6"))
        if not hasattr(self, "listener_trace"):
            self.listener_trace = []
            self.listener_trace_start = time.monotonic()
        if len(self.listener_trace) < 256:
            self.listener_trace.append({"phase": getattr(self, "listener_phase", "preflight"),
                                        "elapsedMs": int((time.monotonic()-self.listener_trace_start)*1000),
                                        "ports": sorted(observed & ALL_PORTS)})
        return observed'''),
    ('def start(self):\n        self.shell(', 'def start(self):\n        self.listener_phase = "start"\n        self.shell('),
    ('def close_export(self, pid):\n', 'def close_export(self, pid):\n        self.listener_phase = "close"\n'),
    ('    return state\n\n\ndef main(',
     '    state["listenerTrace"] = getattr(device, "listener_trace", [])\n    return state\n\n\ndef main('),
]


def derived(text, user0_readiness=False):
    for old, new in CHANGES:
        text = versions.login.exact.transform(text, old, new)
    if user0_readiness:
        text = versions.login.exact.transform(text, READY_OLD, READY_NEW)
    return text


def build(output, user0_readiness=False):
    if catalog.qualified.sha(TEMPLATE/'build.json') != TEMPLATE_SHA:
        raise ValueError('Qualified version projection build changed')
    manifest = json.loads((TEMPLATE/'build.json').read_text())
    versions.verify(TEMPLATE, manifest)
    shutil.copytree(TEMPLATE, output)
    source = output/'source'; path = source/'run_ecu_map_metadata.py'
    path.write_text(derived(path.read_text(), user0_readiness))
    (source/'test_listener_trace.py').write_text('''import unittest
import run_ecu_map_metadata as host
class ListenerTraceTest(unittest.TestCase):
    def test_trace_preserves_guard_ports_and_omits_unrelated(self):
        device=host.Device()
        calls=[]
        def read(command):
            calls.append(command)
            return 'sl local_address rem_address st\\n0: 00000000:B4F1 0:0 0A\\n1: 00000000:0016 0:0 0A\\nsl local_address rem_address st\\n'
        device.shell=read
        for phase in ('preflight','start','close'):
            device.listener_phase=phase
            self.assertEqual(device.ports(),{46321,22})
        self.assertEqual(len(calls),3)
        self.assertTrue(all(c=='cat /proc/net/tcp /proc/net/tcp6' for c in calls))
        self.assertEqual([r['phase'] for r in device.listener_trace],['preflight','start','close'])
        self.assertTrue(all(r['ports']==[46321] and r['elapsedMs']>=0 for r in device.listener_trace))
        for _ in range(300): self.assertEqual(device.ports(),{46321,22})
        self.assertEqual(len(device.listener_trace),256)
''')
    module = catalog.qualified.baseline()
    if user0_readiness:
        # The helper reads only fixed user0 files through 46321. Keep all six
        # ports in exclusion/closure checks; only unused user10 readiness is optional.
        (source/'test_user0_readiness.py').write_text('''import itertools
import unittest
import run_ecu_map_metadata as host
class User0ReadinessTest(unittest.TestCase):
    def test_every_known_port_subset_and_cleanup_are_exact(self):
        from pathlib import Path
        text=Path(host.__file__).read_text()
        self.assertIn('while (self.ports() & ALL_PORTS) not in (PORTS, PORTS - {46322}):',text)
        self.assertIn('while self.ports() & ALL_PORTS:',text)
        self.assertEqual(host.PORTS,{46320,46321,46322})
        self.assertEqual(host.ALL_PORTS,{5320,5321,5322,46320,46321,46322})
        ports=sorted(host.ALL_PORTS)
        accepted=[]
        for bits in itertools.product((False,True),repeat=6):
            present={p for p,bit in zip(ports,bits) if bit}
            if (present & host.ALL_PORTS) in (host.PORTS,host.PORTS-{46322}): accepted.append(present)
        self.assertEqual(accepted,[{46320,46321},{46320,46321,46322}])
''')
    module.local(['python3', '-B', '-m', 'unittest', 'discover', '-s', source, '-p', 'test*.py'], output/'host-tests-listener-trace.txt')
    owner = output/'listener-trace-reader.py'
    owner.write_bytes(Path(__file__).read_bytes())
    manifest.update({
        'sources': [{'path': str(p.relative_to(output)), 'sha256': catalog.qualified.sha(p)}
                    for p in [*sorted(source.iterdir()), output/'catalog-reader.py', output/'http-status-reader.py',
                              output/'login-reader.py', output/'versions-reader.py', owner, output/'health.py'] if p.is_file()],
        'listenerTraceReaderSha256': catalog.qualified.sha(Path(__file__)),
        'parentBuild': {'path': str((TEMPLATE/'build.json').relative_to(catalog.ROOT)), 'sha256': TEMPLATE_SHA},
        'transformations': manifest['transformations']+['Observe existing listener checks; no extra car calls or changed bounds.'],
        'hypothesis': 'Recorded listener sets distinguish partial export startup from no observed startup.',
        'expected': 'Retain the known port set for each existing preflight/start/close check.',
        'falsification': 'No full set before deadline stops unchanged; no timeout relaxation or stock restart.',
        'dexChanged': False, 'extraVehicleCalls': 0,
        'user0Readiness': user0_readiness,
    })
    if user0_readiness:
        manifest['transformations'].append('Only startup readiness may omit unused 46322; all six exclusion/closure ports remain checked.')
        manifest['hypothesis'] = 'The catalog reader can use its existing user0 endpoint while unrelated established traffic occupies unused user10 port 46322.'
        manifest['expected'] = 'Proceed only with 46320/46321 present and no legacy listeners, then verify all export ports/threads closed before GO.'
    module.dump(output/'build.json', manifest)
    print(json.dumps({'built': str(output), 'artifactSha256': manifest['artifactSha256'],
                      'dexChanged': False, 'tests': (output/'host-tests-listener-trace.txt').read_text().strip()}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'run'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--user0-readiness', action='store_true', help='build with unused user10 listener optional; closure checks unchanged')
    args = parser.parse_args()
    if args.action == 'build':
        build(args.output.resolve(), args.user0_readiness)
    elif args.build:
        manifest = json.loads((args.build/'build.json').read_text())
        if manifest['listenerTraceReaderSha256'] != catalog.qualified.sha(Path(__file__)):
            raise ValueError('Listener trace reader changed')
        versions.verify(args.build, manifest)
        if derived((TEMPLATE/'source/run_ecu_map_metadata.py').read_text(), manifest.get('user0Readiness', False)) != (args.build/'source/run_ecu_map_metadata.py').read_text():
            raise ValueError('Unexpected runner change')
        owner = catalog.load(args.build/'catalog-reader.py', 'catalog_trace_owner')
        owner.run(args.build.resolve(), args.output.resolve())
    else:
        parser.error('--build required')


if __name__ == '__main__':
    main()
