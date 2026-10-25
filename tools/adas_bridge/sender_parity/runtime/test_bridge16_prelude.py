"""Host checks for choosing exactly one Java bridge before Frida 16 uses it."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest


@unittest.skipUnless(shutil.which('node'), 'Node.js required for JavaScript guard checks')
class Bridge16PreludeTest(unittest.TestCase):
    def test_unused_builtin_is_never_read_and_explicit_bridge_is_global(self):
        self._check('unused')

    def test_initialized_builtin_is_rejected_before_import(self):
        self._check('initialized')

    def test_nonconfigurable_builtin_is_rejected_before_import(self):
        self._check('fixed')

    def test_import_cannot_initialize_builtin_bridge(self):
        self._check('import_reads_builtin')

    def _check(self, scenario):
        prelude = Path(__file__).with_name('bridge16-prelude.js').read_text()
        script = '''const vm = require('node:vm'), assert = require('node:assert/strict');
const scenario = SCENARIO, bridge = {}, sandbox = {};
let reads = 0, imports = 0;
if (scenario === 'initialized') {
  Object.defineProperty(sandbox, 'Java', {value: {}, configurable: true});
} else {
  Object.defineProperty(sandbox, 'Java', {
    configurable: scenario !== 'fixed',
    get() {
      reads++;
      Object.defineProperty(sandbox, 'Java', {value: {}, configurable: true});
      return sandbox.Java;
    }
  });
}
sandbox.require = name => {
  imports++; assert.equal(name, 'frida-java-bridge');
  if (scenario === 'import_reads_builtin') void sandbox.Java;
  return bridge;
};
const context = vm.createContext(sandbox);
if (scenario === 'unused') {
  vm.runInContext(PRELUDE, context);
  assert.equal(reads, 0); assert.equal(imports, 1);
  assert.equal(sandbox.Java, bridge);
  assert.equal(sandbox.DenzaBridgeSelection.explicit_java_is_global, true);
  assert.equal(Object.getOwnPropertyDescriptor(sandbox, 'Java').configurable, false);
} else {
  assert.throws(() => vm.runInContext(PRELUDE, context),
    /Expected unused Frida 16 lazy Java accessor|Built-in Java accessor changed/);
  assert.equal(imports, scenario === 'import_reads_builtin' ? 1 : 0);
}
'''.replace('SCENARIO', json.dumps(scenario)).replace('PRELUDE', json.dumps(prelude))
        subprocess.run([shutil.which('node'), '-e', script], check=True,
                       capture_output=True, text=True, timeout=10)


if __name__ == '__main__':
    unittest.main()
