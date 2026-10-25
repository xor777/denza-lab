"""Regressions from actual recorder failures; no Android/device dependency."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest


@unittest.skipUnless(shutil.which('node'), 'Node is required to exercise the Frida adapter')
class RecorderTest(unittest.TestCase):
    def run_js(self, assertion):
        source = Path(__file__).with_name('stock_inputs.js').read_text()
        harness = r'''
const vm = require('vm');
const fs = require('fs');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const messages = [];
let originalCalls = 0;
const method = {
  overload() { return this; },
  call(self, value) { originalCalls++; return value + 1; }
};
class LongValue { constructor(value) { this.value = value; } toString() { return this.value; } }
const context = {
  Int64: LongValue, UInt64: class extends LongValue {},
  report(kind, data) { messages.push({kind, ...data}); },
  abReport() {}, rtNow() { return 123; },
  Java: {
    perform() {}, // Install no actual hooks while loading the script.
    use(name) {
      if (name === 'java.lang.Thread') return {currentThread() { return {getId() { return 1; }}; }};
      if (name === 'android.os.SystemClock') return {uptimeMillis() { return 123; }, elapsedRealtime() { return 123; }};
      if (name === 'fixture.Sender') return {run: method};
      throw new Error('Unexpected Java access: ' + name);
    }
  }
};
vm.createContext(context);
vm.runInContext(input.source, context);
context.method = method;
vm.runInContext(input.assertion, context);
process.stdout.write(JSON.stringify({calls: originalCalls, messages, result: context.result}));
'''
        result = subprocess.run(['node', '-e', harness],
                                input=json.dumps(dict(source=source, assertion=assertion)),
                                text=True, capture_output=True, check=True, timeout=10)
        return json.loads(result.stdout)

    def test_java_long_is_not_passed_to_gson_as_object_pointer(self):
        result = self.run_js("result = spJson(new Int64('9223372036854775807'));")
        self.assertEqual(result['result'], {'type': 'int64', 'decimal': '9223372036854775807'})

    def test_reader_failure_preserves_original_call_and_result(self):
        result = self.run_js("""
spHook('fixture.Sender', 'run', ['int'], 'fixture', function () {
  throw new Error('snapshot failed');
}, true);
result = method.implementation.call({}, 6);
""")
        self.assertEqual(result['calls'], 1)
        self.assertEqual(result['result'], 7)
        self.assertTrue(any(r['kind'] == 'sender_error' for r in result['messages']))

    def test_jadx_collision_aliases_use_real_dex_fields(self):
        result = self.run_js("result = [spField({b: {value: 42}}, 'f13314b'), spField({b: {value: 77}}, 'f13986b')];")
        self.assertEqual(result['result'], [42, 77])


if __name__ == '__main__':
    unittest.main()
