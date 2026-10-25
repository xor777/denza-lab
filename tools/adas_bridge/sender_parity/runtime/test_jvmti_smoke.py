"""Bounded host checks; actual JVMTI event coverage needs the owned emulator."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).with_name('jvmti-event-smoke.js')


class JvmtiSmokeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_unsupported_abi_never_touches_java(self):
        script = '''const fs = require('node:fs'), vm = require('node:vm');
const context = {Process: {arch: 'x64', pointerSize: 8}};
Object.defineProperty(context, 'Java', {get() {throw Error('Java must remain unused');}});
vm.createContext(context); vm.runInContext(fs.readFileSync(process.argv[1], 'utf8'), context);
const result = context.DenzaJvmtiEventSmoke();
if (result.status !== 'UNSUPPORTED_ABI') throw Error(JSON.stringify(result));
'''
        subprocess.run([shutil.which('node'), '-e', script, str(SOURCE)],
                       check=True, capture_output=True, text=True, timeout=10)

    @unittest.skipUnless(shutil.which('clang'), 'Clang required')
    def test_c_trampoline_filters_and_preserves_jvalue_bits(self):
        code = re.search(r'    const code = `\n(.*?)\n`;', SOURCE.read_text(), re.S).group(1)
        harness = r'''
#include <assert.h>
#include <string.h>
gint active = 1;
gpointer selected_methods[4] = {(void*)1, (void*)2, (void*)3, (void*)4};
static unsigned calls, last_kind, last_index, last_popped;
static uint64_t last_bits;
gint g_atomic_int_add(volatile gint *p, gint value) {gint old = *p; *p += value; return old;}
void selected_event(guint kind, guint index, gpointer jni, gpointer thread,
                    guint popped, const void *raw) {
  calls++; last_kind = kind; last_index = index; last_popped = popped;
  assert(jni == (void*)5 && thread == (void*)6);
  if (kind == 1) memcpy(&last_bits, raw, 8);
}
int main(void) {
  DenzaJValue value;
  const uint64_t examples[] = {0, UINT64_MAX, UINT64_C(0xfff8000102030405),
                              UINT64_C(0x400921fb54442d18)};
  unsigned i;
  method_entry(NULL, (void*)5, (void*)6, (void*)2);
  assert(calls == 1 && last_kind == 0 && last_index == 1);
  for (i = 0; i < sizeof(examples) / sizeof(examples[0]); i++) {
    memcpy(&value, &examples[i], 8);
    method_exit(NULL, (void*)5, (void*)6, (void*)4, 1, value);
    assert(calls == i + 2 && last_kind == 1 && last_index == 3 && last_popped == 1);
    assert(last_bits == examples[i]);
  }
  method_entry(NULL, (void*)5, (void*)6, (void*)99);
  active = 0;
  method_exit(NULL, (void*)5, (void*)6, (void*)4, 0, value);
  assert(calls == 5);
  return 0;
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'glib.h').write_text('''#include <stddef.h>
typedef int gint; typedef unsigned int guint; typedef void* gpointer;
gint g_atomic_int_add(volatile gint*, gint);
''')
            source, executable = root / 'smoke.c', root / 'smoke'
            source.write_text(code + harness)
            subprocess.run([shutil.which('clang'), '-std=c11', '-O2', '-I', tmp,
                            str(source), '-o', str(executable)],
                           check=True, capture_output=True, text=True, timeout=20)
            subprocess.run([str(executable)], check=True, capture_output=True,
                           text=True, timeout=10)


if __name__ == '__main__':
    unittest.main()
