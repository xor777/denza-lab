#!/usr/bin/env python3
"""One separately authorized, bounded export of an existing IDC log.

No collector startup, cloud request, ECU command, or setting change. The stock
export service exposes more than this reader uses, so it needs fresh owner
authorization for this session. Starts a 30-second device STOP watchdog before
opening the service, also stops on the host, then verifies closure and state.
Only lists one directory and reads its newest matching log (<=1 MiB); no retry.
Dates and APK identities deliberately bind this probe to the current research.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import socket
import struct
import time

import ads_ota_metadata as common

wire = common.wire
DIRECTORY = '/data/user/0/com.byd.idclogcollect/Adas/idclogs'
NAME = re.compile(r'IDC_2026-10-(?:17|18)_[0-9]{13}\.log')
WATCHLOG = '/data/local/tmp/ads-idc-read-stop-20261018.log'
EXPECTED_STATE = ['170', 'denza', 'r@-1_0&w@-1_0', '135', '4822']
APK_HASHES = [
    '589ec5b9555a0b64354aeee9393eb18ab9ca984ef3f0c6148d7a110eacbd97d5',
    '2ffae011548cb383f21187eb0faef23557c7b15eb98cd1c58183fe94b103dc40',
]


def packet(action, path):
    allowed = (type(action) is int and
               ((action == 2 and path == DIRECTORY) or
                (action == 1 and str(PurePosixPath(path).parent) == DIRECTORY
                 and str(PurePosixPath(path)) == path
                 and NAME.fullmatch(PurePosixPath(path).name))))
    if not allowed:
        raise wire.ExportError('request_not_allowed')
    body = json.dumps({'directory' if action == 2 else 'filePath':
                       wire.EXPORT_PREFIX + path.lstrip('/')}, separators=(',', ':')).encode()
    return struct.pack('>III', wire.MAGIC, action, len(body)) + body


def select_log(listing, report=None):
    candidates = [name for name in listing if NAME.fullmatch(name)]
    if not candidates:
        raise wire.ExportError('no_matching_existing_log')
    name = max(candidates, key=lambda value: int(value.rsplit('_', 1)[1][:-4]))
    size = listing[name]
    if report is not None:
        report.update(file=DIRECTORY + '/' + name, listedBytes=size)
    if type(size) is not int or not 0 < size <= wire.MAX_FILE:
        raise wire.ExportError('file_size_out_of_bounds')
    return DIRECTORY + '/' + name, size


def exchange(port, action, path, deadline, size=None):
    request = packet(action, path)
    with socket.create_connection(('127.0.0.1', port), timeout=deadline.timeout()) as sock:
        sock.sendall(request)
        doc = wire._envelope(sock, deadline)
        if action == 2:
            listing = wire._list(doc)
            wire._eof(sock, deadline)
            return listing
        return wire._download(doc, PurePosixPath(path).name, size, sock, deadline)


def export(out, owner_authorized=False):
    if owner_authorized is not True:
        raise wire.ExportError('fresh_owner_authorization_required')
    out = Path(out)
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    report = {'started': False, 'file': None, 'bytes': 0, 'cleanup': {}, 'error': None,
              'authority': 'One fresh owner-approved <=30-second IDC log export',
              'diagnosticRequests': 0, 'cloudRequests': 0, 'collectorStarted': False}
    raw = None
    forward = armed = None
    staged = attempted = False
    try:
        baseline = common.shell(common.STATE)
        if baseline.splitlines() != EXPECTED_STATE:
            raise wire.ExportError('baseline_changed')
        report['before'] = baseline.splitlines()
        hashes = common.shell('sha256sum /system/app/BydLogTool/BydLogTool.apk '
                              '/system/priv-app/IdcLogCollect/IdcLogCollect.apk')
        if [line.split()[0] for line in hashes.splitlines()] != APK_HASHES:
            raise wire.ExportError('oem_identity_changed')
        if common.ports(common.shell('cat /proc/net/tcp /proc/net/tcp6')) & {5320, 5321, 5322}:
            raise wire.ExportError('export_already_open')
        crash = common.shell('logcat -d -b crash -v time')
        common.shell('test ! -e ' + WATCHLOG + ' && mkdir /data/local/tmp/logtool')
        staged = True
        forward = common.adb(['forward', 'tcp:0', 'tcp:5321']).strip()
        if not forward.isdigit():
            raise wire.ExportError('invalid_forward')
        armed = time.monotonic()
        pid = common.shell('nohup sh -c ' + shlex.quote('sleep 30\n' + common.STOP) +
                           ' > ' + WATCHLOG + ' 2>&1 < /dev/null &\necho $!').strip()
        if not pid.isdigit():
            raise wire.ExportError('invalid_watchdog')
        common.shell('kill -0 ' + pid)
        attempted = True
        common.shell(common.START, 5)
        report['started'] = True
        deadline = wire.Deadline()
        listing = exchange(int(forward), 2, DIRECTORY, deadline)
        path, size = select_log(listing, report)
        report['file'] = path
        raw = exchange(int(forward), 1, path, deadline, size)
        deadline.timeout()
        report['bytes'] = len(raw)
    except Exception as error:
        report['error'] = str(error) if isinstance(error, wire.ExportError) else type(error).__name__
        raw = None
    finally:
        if attempted:
            try:
                common.shell(common.STOP, 5)
                report['cleanup']['hostStopSent'] = True
                report['cleanup']['hostStopAfterArmingSeconds'] = round(time.monotonic() - armed, 3)
            except Exception:
                report['cleanup']['hostStopFailed'] = True
        if forward and forward.isdigit():
            try:
                common.adb(['forward', '--remove', 'tcp:' + forward])
                report['cleanup']['forwardRemoved'] = True
            except Exception:
                report['cleanup']['forwardRemovalFailed'] = True
        if armed is not None:
            time.sleep(max(0, 31.5 - (time.monotonic() - armed)))
            try:
                report['cleanup']['watchdogStopObserved'] = 'Broadcast completed: result=0' in common.shell('cat ' + WATCHLOG)
                report['cleanup']['remainingPorts'] = sorted(common.ports(common.shell('cat /proc/net/tcp /proc/net/tcp6')) & {5320, 5321, 5322})
                report['cleanup']['stateUnchanged'] = common.shell(common.STATE) == baseline
                crash_after = common.shell('logcat -d -b crash -v time')
                report['cleanup']['crashUnchanged'] = crash_after == crash
                if crash_after != crash:
                    wire._save_new(out / 'crash-after.txt', crash_after.encode())
                common.shell('rm ' + WATCHLOG)
                report['cleanup']['watchdogLogRemoved'] = True
            except Exception as error:
                report['cleanup']['verificationError'] = type(error).__name__
        if staged:
            try:
                common.shell('rmdir /data/local/tmp/logtool')
                report['cleanup']['stagingRemoved'] = True
            except Exception:
                report['cleanup']['stagingRemovalFailed'] = True
        common.save(out, 'export.json', report)
    clean = (report['cleanup'].get('remainingPorts') == [] and all(
        report['cleanup'].get(key) for key in ('watchdogStopObserved', 'stateUnchanged',
        'crashUnchanged', 'forwardRemoved', 'stagingRemoved', 'watchdogLogRemoved')))
    if not clean and attempted:
        raise wire.ExportError('cleanup_not_confirmed')
    if report['error']:
        raise wire.ExportError(report['error'])
    wire._save_new(out / 'idc-existing.log', raw)
    common.save(out, 'identity.json', {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
    print(json.dumps({'bytes': len(raw), 'cleanupVerified': clean}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--owner-authorized', action='store_true')
    args = parser.parse_args()
    export(args.out, args.owner_authorized)
