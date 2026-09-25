#!/usr/bin/env python3
"""Offline process bridge for Java integration with the actual ARM64 runtime.

Uses the existing Unicorn ELF/syscall model. Only process stdin/stdout are
interactive; no native socket or vehicle access is implemented. Capabilities
and all original instruction paths remain unchanged. Run only with synthetic
Java platform/transport backends; this helper is never packaged in the APK.
"""
import argparse
import os
import re
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'research/telematics-firmware'))
from verify_persistent_engine import Process
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                                UC_ARM64_REG_X8, UC_ARM64_REG_PC, UC_ARM64_REG_LR)


class InteractiveProcess(Process):
    def __init__(self, binary):
        super().__init__(binary, b'', auto_replies=False)

    def interrupt(self, engine, number, data):
        op = self.u.reg_read(UC_ARM64_REG_X8)
        fd = self.u.reg_read(UC_ARM64_REG_X0)
        target = self.u.reg_read(UC_ARM64_REG_X1)
        size = self.u.reg_read(UC_ARM64_REG_X2)
        if op == 63:
            if fd != 0 or size > 4096:
                raise RuntimeError('unexpected input descriptor or bound')
            if not self.input:
                line = sys.stdin.buffer.readline(4097)
                if line and (len(line) > 4096 or not line.endswith(b'\n')):
                    raise RuntimeError('incomplete or oversized input line')
                self.input.extend(line)
            count = min(size, len(self.input))
            if count:
                self.u.mem_write(target, bytes(self.input[:count]))
                del self.input[:count]
            self.u.reg_write(UC_ARM64_REG_X0, count)
        elif op == 64:
            if fd not in (1, 2) or size > 4096:
                raise RuntimeError('unexpected output descriptor or bound')
            output = bytes(self.u.mem_read(target, size))
            self.output.extend(output)
            if self.output.endswith(b'\n'):
                last_line = self.output.rsplit(b'\n', 2)[-2]
                failure = re.fullmatch(rb'\{"passed":false,"stage":"([a-z0-9_]{1,80})"\}', last_line)
                if failure:
                    print('offline native stage=' + failure[1].decode('ascii'),
                          file=sys.stderr, flush=True)
            # os.write may complete only a prefix; model its real byte count.
            count = os.write(fd, output)
            self.u.reg_write(UC_ARM64_REG_X0, count)
        else:
            super().interrupt(engine, number, data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    args = parser.parse_args()
    process = InteractiveProcess(args.binary)
    try:
        code, _ = process.run()
        if code is None:
            raise RuntimeError('native instruction budget exhausted without exit')
    except Exception as error:
        # Do not dump the protocol transcript or identity inputs on failure.
        print('offline native integration failed: ' + type(error).__name__ +
              f' pc={process.u.reg_read(UC_ARM64_REG_PC):#x}' +
              f' lr={process.u.reg_read(UC_ARM64_REG_LR):#x}', file=sys.stderr)
        return 1
    return code


if __name__ == '__main__':
    sys.exit(main())
