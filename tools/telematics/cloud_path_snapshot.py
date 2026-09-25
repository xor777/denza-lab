#!/usr/bin/env python3
"""Preview or collect read-only metadata for the alternative cloud-path study.

Uses an existing explicitly selected ADB transport. No connect, installation,
service calls, property writes, process startup, crypto or cloud traffic. SIM
identifiers are compared in memory and are never included in the report.
"""
import argparse
from datetime import datetime, timezone
import json
import re
import subprocess
import time


IDENTITY_PROPERTIES = ('ril.csim.iccid', 'persist.radio.iccid',
                       'ril.csim.iccid2', 'ril.imsi', 'ril.imsi2')
STATE_PROPERTIES = ('persist.sys.byd.apn_type', 'sys.tcp_connect_status',
                    'init.svc.cloudmanager', 'init.svc.mqttserv', 'init.svc.cloudctrlserv')
SERVICES = ('cloudmanager', 'mqttserv', 'cloudctrlserv', 'cloud_server_app_service',
            'autoservice', 'stateservice', 'safekeyservice')
PATHS = ('/system/bin/cloudmanager', '/system/bin/cloudmanager.sh',
         '/data/local/tmp', '/data/cloudservice', '/data/mqttserv',
         '/data/system/cloud')


def summarize_identity(values):
    def shape(value):
        if value is None:
            return {'readable': False}
        return {'readable': True, 'length': len(value),
                'ascii_decimal': bool(value) and value.isascii() and value.isdecimal()}

    current, persistent = (values.get(k) for k in IDENTITY_PROPERTIES[:2])
    if current is None:
        source = 'unknown'
    elif len(current) == 20:
        source = IDENTITY_PROPERTIES[0]
    elif persistent is None:
        source = 'unknown'
    elif len(persistent) == 20:
        source = IDENTITY_PROPERTIES[1]
    else:
        source = 'no_valid_length'
    return {
        'properties': {key: shape(values.get(key)) for key in IDENTITY_PROPERTIES},
        'current_persistent_iccid_equal': (current == persistent)
            if current and persistent else None,
        'mqtt_getter_source_if_called_now': source,
        'native_cached_identity': 'not_observed',
        'interpretation': 'Sequential reads; a SIM transition can change values during collection. Source prediction assumes the reviewed firmware.',
    }


def plan():
    return {'mode': 'preview_only', 'vehicle_contacted': False,
            'reads': ['get-state', 'id', 'getenforce', 'service list',
                      *['getprop ' + key for key in (*IDENTITY_PROPERTIES, *STATE_PROPERTIES)],
                      *['ls -ldZ ' + path for path in PATHS]],
            'limits': '60 seconds total; at most 8 seconds per command; no retries',
            'privacy': 'Identifier lengths and equality only; no SIM values, hashes, VIN, account token or certificates'}


def collect(serial, runner=subprocess.run):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', serial):
        raise ValueError('Invalid ADB serial')
    start = time.monotonic()
    failures = []

    def read(label, arguments):
        remaining = 60 - (time.monotonic() - start)
        if remaining <= 0:
            failures.append({'read': label, 'reason': 'overall_deadline'})
            return None
        try:
            result = runner(['adb', '-s', serial, *arguments], capture_output=True,
                            text=True, timeout=min(8, remaining), check=False)
        except (OSError, subprocess.TimeoutExpired):
            failures.append({'read': label, 'reason': 'transport_or_timeout'})
            return None
        if result.returncode or result.stderr.strip() or len(result.stdout) > 262144:
            failures.append({'read': label, 'reason': 'failed_stderr_or_oversized'})
            return None
        return result.stdout.strip()

    if read('transport', ['get-state']) != 'device':
        return {'mode': 'read_only', 'complete': False, 'transport_ready': False,
                'failures': failures}
    report = {'mode': 'read_only', 'timestamp_utc': datetime.now(timezone.utc).isoformat(),
              'transport_ready': True, 'caller': read('caller', ['shell', 'id']),
              'selinux': read('selinux', ['shell', 'getenforce'])}
    values = {key: read(key, ['shell', 'getprop', key]) for key in IDENTITY_PROPERTIES}
    # getprop's reviewed outputs are ASCII property strings, bounded to 91 bytes.
    for key, value in values.items():
        if value is not None and (not value.isascii() or len(value) > 91 or '\n' in value):
            values[key] = None
            failures.append({'read': key, 'reason': 'unexpected_property_format'})
    report['identity_sources'] = summarize_identity(values)
    report['state'] = {key: read(key, ['shell', 'getprop', key]) for key in STATE_PROPERTIES}
    services = read('services', ['shell', 'service', 'list'])
    report['services'] = None if services is None else {
        name: any(re.match(r'^\d+\s+' + re.escape(name) + r':', line)
                  for line in services.splitlines()) for name in SERVICES}
    report['path_metadata'] = {path: read(path, ['shell', 'ls', '-ldZ', path]) for path in PATHS}
    report['failures'] = failures
    report['complete'] = not failures
    report['elapsed_seconds'] = round(time.monotonic() - start, 3)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', help='Perform only the listed read-only queries')
    parser.add_argument('--serial', help='Existing authorized ADB transport; required with --execute')
    args = parser.parse_args()
    if not args.execute:
        report = plan()
    else:
        if not args.serial:
            parser.error('--execute requires --serial')
        report = collect(args.serial)
    print(json.dumps(report, indent=2))
    if args.execute and not report.get('transport_ready'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
