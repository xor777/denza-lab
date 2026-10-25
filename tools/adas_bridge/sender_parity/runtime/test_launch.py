import hashlib
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from .launch import run


class LauncherTest(unittest.TestCase):
    def test_supplied_source_and_report_are_identical(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out = root / 'output'
            prefix = root / 'prefix.js'
            prefix.write_text('globalThis.Java = {};\n')
            config = root / 'runtime-config.json'
            config.write_text(json.dumps(dict(
                frida_version='17.4.1', bridge_version='7.0.10',
                bridge_git_head='pinned-bridge', server_sha256='configured-server',
                prelude_path='prefix.js', prelude_sha256=hashlib.sha256(prefix.read_bytes()).hexdigest())))
            runner = root / 'runner.py'
            runner.write_text('''import frida, json, pathlib, sys
out=pathlib.Path(sys.argv[sys.argv.index('--out')+1]);out.mkdir()
source="send('original');"
(out/'agent.js').write_text(source)
try:
    frida.core.Session.create_script(object(),source,name='capture')
finally:
    (out/'report.json').write_text(json.dumps({'agent_sha256':'old','pid':123,'messages':[1]}))
''')
            calls = []

            class Session:
                def create_script(self, source, **kwargs):
                    calls.append((source, kwargs))
                    return object()

            fake = types.SimpleNamespace(__version__='17.4.1',
                                         core=types.SimpleNamespace(Session=Session))
            old = Session.create_script
            with patch.dict(sys.modules, {'frida': fake}):
                run(runner, ['--out', str(out)], config)
            self.assertIs(Session.create_script, old)
            actual = (out / 'agent.js').read_bytes()
            report = json.loads((out / 'report.json').read_text())
            self.assertEqual(calls, [(actual.decode(), {'name': 'capture'})])
            self.assertEqual(report['agent_sha256'], hashlib.sha256(actual).hexdigest())
            self.assertEqual(report['original_agent_sha256'], hashlib.sha256(b"send('original');").hexdigest())
            self.assertEqual((out / 'agent.original.js').read_text(), "send('original');")
            self.assertEqual((report['pid'], report['messages']), (123, [1]))
            self.assertTrue(report['runtime17']['script_create_succeeded'])

    def test_bad_prefix_hash_fails_before_runner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'prefix.js').write_text('prefix')
            config = root / 'config.json'
            config.write_text(json.dumps(dict(frida_version='17.4.1',
                                              prelude_path='prefix.js', prelude_sha256='wrong')))
            with patch.dict(sys.modules, {'frida': types.SimpleNamespace(__version__='17.4.1')}):
                with self.assertRaisesRegex(ValueError, 'pinned hash'):
                    run(root / 'never-run.py', ['--out', str(root / 'out')], config)


if __name__ == '__main__':
    unittest.main()
