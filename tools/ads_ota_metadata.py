#!/usr/bin/env python3
"""Owner-authorized GCY metadata acquisition, with no ECU or update operations.

Reads five fixed GCY files in one <=30s stock export session, closes it, and uses
the existing identity/token in memory for getEcuList and checkVersion only.
No login/register, versionSync, report, signing request, diagnostic callback,
flash or firmware download. No raw preferences, client key or certificate is
written to disk, passed in argv or included in reports. The stock export binds
all interfaces; run only with current owner authorization for this operation.
The fixed GHY factory profile reads only its own preferences/version.
Its caller permits only GHY getEcuList with the hardware-backed identity.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import re
import shlex
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path, PurePosixPath

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'research/vehicle-diagnostics'))
import read_ecu_cache_export as wire
import read_ota_inventory_export as inventory
import read_ads_package_metadata as metadata

FILES = (
    '/data/user/0/com.byd.otaupdate/files/dcrt.crt',
    '/data/user/0/com.byd.otaupdate/files/dkey.key',
    '/data/user/0/com.byd.otaupdate/shared_prefs/share_data_prefs.xml',
    '/collect2/SourceVehicleVersion2',
    '/data/ota_package/ECUVersion.json',
)
DIRECTORIES = frozenset(str(PurePosixPath(p).parent) for p in FILES)
CAPS = (16384, 16384, 256 * 1024, 256, 1024 * 1024)
GHY_FILES = (
    '/data/user/0/com.byd.otaupdate2/shared_prefs/share_data_prefs.xml',
    '/collect2/SourceVehicleVersion',
)
GHY_CAPS = (256 * 1024, 256)
COTA_FILES = tuple(root + suffix for root in ('/collect2/cota', '/cust_overlay/cota')
                   for suffix in ('/cloud_config.json',
                                  '/apps/com.byd.cota.globalapp/current/params/params.json',
                                  '/apps/com.byd.launchermap/current/params/params.json'))
COTA_CAPS = (64 * 1024, 256 * 1024, 256 * 1024) * 2
ECU_FIELDS = ('ecuId', 'ecuSoftCode', 'ecuSoftVer', 'ecuHardVer', 'ecuConfigCode', 'ecuConfigVer')
ROUTE_FIELDS = metadata.ROUTE_FIELDS + ('ecuHardDId',)
PACKAGE_FIELDS = metadata.PACKAGE_FIELDS + ('encryptSHA256', 'dcdnVersionPath', 'dependencies', 'fileName')
PACKAGE_ARRAYS = ('sourcePackageInfo', 'targetPackageInfo', 'configSourceInfo', 'configPackageInfo')
ADB = ['adb', '-s', '127.0.0.1:5555']
RECEIVER = 'com.byd.bydlogtool/.LogReceiver'
START = 'am broadcast --user 0 -a action.system.START_SOCKET_SERVER -n ' + RECEIVER
STOP = 'am broadcast --user 0 -a action.system.STOP_SOCKET_SERVER -n ' + RECEIVER
WATCHLOG = '/data/local/tmp/ads-ota-identity-stop-20261018.log'
STATE = 'getprop persist.sys.AutoType\ngetprop ro.build.car.series\ngetprop sys.diagmgrsrv.task_bk\npidof autoservice\npidof com.byd.bydlogtool'
ERROR = wire.ExportError


def save(out, name, value):
    wire._save_new(out / name, (json.dumps(value, indent=2, ensure_ascii=True) + '\n').encode())


def adb(args, timeout=8):
    result = subprocess.run(ADB + args, capture_output=True, timeout=timeout)
    if result.returncode:
        raise ERROR('adb_command_failed')
    return result.stdout.decode('utf-8', 'replace')


def shell(command, timeout=8):
    return adb(['shell', command], timeout)


def ports(raw):
    return {int(parts[1].rsplit(':', 1)[1], 16)
            for line in raw.splitlines() if len(parts := line.split()) > 4
            and parts[0].endswith(':') and parts[3] == '0A'}


def export_profile(profile):
    if profile == 'gcy':
        return FILES, CAPS, frozenset()
    if profile == 'ghy-factory':
        return GHY_FILES, GHY_CAPS, frozenset((GHY_FILES[1],))
    if profile == 'cota-cache':
        return COTA_FILES, COTA_CAPS, frozenset(COTA_FILES)
    raise ERROR('export_profile_not_allowed')


def packet(action, path, profile='gcy'):
    files, _, _ = export_profile(profile)
    directories = frozenset(str(PurePosixPath(p).parent) for p in files)
    if type(action) is not int or path not in (files if action == 1 else directories if action == 2 else ()):
        raise ERROR('request_not_allowed')
    body = json.dumps({'filePath' if action == 1 else 'directory':
                       wire.EXPORT_PREFIX + path.lstrip('/')}, separators=(',', ':')).encode()
    return struct.pack('>III', wire.MAGIC, action, len(body)) + body


def exchange(port, action, path, deadline, size=None, profile='gcy'):
    request = packet(action, path, profile)
    with socket.create_connection(('127.0.0.1', port), timeout=deadline.timeout()) as sock:
        sock.sendall(request)
        doc = wire._envelope(sock, deadline)
        if action == 2:
            result = wire._list(doc)
            wire._eof(sock, deadline)
            return result
        return wire._download(doc, PurePosixPath(path).name, size, sock, deadline)


def export(out, profile='gcy'):
    files, caps, optional = export_profile(profile)
    report = {'started': False, 'files': [], 'cleanup': {}, 'error': None,
              'profile': profile,
              'authority': 'Current owner request to continue stock ADS metadata and package research',
              'rawIdentitySaved': False, 'diagnosticRequests': 0}
    data, forward, armed, staged, attempted = {}, None, None, False, False
    try:
        baseline = shell(STATE)
        state = baseline.splitlines()
        if state != ['170', 'denza', 'r@-1_0&w@-1_0', '135', '4822']:
            raise ERROR('baseline_changed')
        report['before'] = state
        ota_apk, ota_sha = (
            ('/system/app/OTAUpdatePlatformizedUI/OTAUpdatePlatformizedUI.apk',
             'a866917a5466cc72c9fd0fda9b2d6467654529478dd1c6a5c08c3ec5d14c3752')
            if profile == 'gcy' else
            ('/system/app/OTAUpdate2/OTAUpdate2.apk',
             '1529790d11ecf3c3a6883096f30a685e9af6ca3c6d57eb4359daec90a7afdb04'))
        if profile == 'cota-cache':
            ota_apk, ota_sha = ('/system/priv-app/Cota/Cota.apk',
                               '95ed3d8eb6477320fbd183e34c4eb0de440bbb92a5cb219dc5d3c9a53df58b63')
        hashes = shell('sha256sum /system/app/BydLogTool/BydLogTool.apk ' + ota_apk)
        if [line.split()[0] for line in hashes.splitlines()] != [
                '589ec5b9555a0b64354aeee9393eb18ab9ca984ef3f0c6148d7a110eacbd97d5',
                ota_sha]:
            raise ERROR('oem_identity_changed')
        if ports(shell('cat /proc/net/tcp /proc/net/tcp6')) & {5320, 5321, 5322}:
            raise ERROR('export_already_open')
        crash = shell('logcat -d -b crash -v time')
        shell('test ! -e ' + WATCHLOG + ' && mkdir /data/local/tmp/logtool')
        staged = True
        forward = adb(['forward', 'tcp:0', 'tcp:5321']).strip()
        if not forward.isdigit():
            raise ERROR('invalid_forward')
        armed = time.monotonic()
        pid = shell('nohup sh -c ' + shlex.quote('sleep 30\n' + STOP) +
                    ' > ' + WATCHLOG + ' 2>&1 < /dev/null &\necho $!').strip()
        if not pid.isdigit():
            raise ERROR('invalid_watchdog')
        shell('kill -0 ' + pid)
        attempted = True
        shell(START, 5)
        report['started'] = True
        if 5321 not in ports(shell('cat /proc/net/tcp /proc/net/tcp6')):
            raise ERROR('export_not_listening')
        deadline, listings = wire.Deadline(), {}
        for path, cap in zip(files, caps):
            row = {'path': path, 'status': 'pending'}
            report['files'].append(row)
            parent = str(PurePosixPath(path).parent)
            if parent not in listings:
                try:
                    listings[parent] = exchange(int(forward), 2, parent, deadline, profile=profile)
                except wire.MissingFile:
                    listings[parent] = {}
            size = listings[parent].get(PurePosixPath(path).name)
            if size is None:
                row['status'] = 'missing'
                if path in optional:
                    data[path] = b''
                    continue
                raise ERROR('required_file_missing')
            if size == 0 and path in optional:
                row['status'] = 'empty'
                data[path] = b''
                continue
            if not 0 < size <= cap:
                raise ERROR('file_size_out_of_bounds')
            data[path] = exchange(int(forward), 1, path, deadline, size, profile=profile)
            row.update(status='read_into_memory', bytes=size)
        deadline.timeout()
    except Exception as error:
        report['error'] = str(error) if isinstance(error, ERROR) else type(error).__name__
        data.clear()
    finally:
        if attempted:
            try:
                shell(STOP, 5)
                report['cleanup']['hostStopSent'] = True
                report['cleanup']['hostStopAfterArmingSeconds'] = round(time.monotonic() - armed, 3)
            except Exception:
                report['cleanup']['hostStopFailed'] = True
        if forward and forward.isdigit():
            try:
                adb(['forward', '--remove', 'tcp:' + forward])
                report['cleanup']['forwardRemoved'] = True
            except Exception:
                report['cleanup']['forwardRemovalFailed'] = True
        if armed is not None:
            time.sleep(max(0, 31.5 - (time.monotonic() - armed)))
            try:
                report['cleanup']['watchdogStopObserved'] = 'Broadcast completed: result=0' in shell('cat ' + WATCHLOG)
                report['cleanup']['remainingPorts'] = sorted(ports(shell('cat /proc/net/tcp /proc/net/tcp6')) & {5320, 5321, 5322})
                report['cleanup']['stateUnchanged'] = shell(STATE) == baseline
                crash_after = shell('logcat -d -b crash -v time')
                report['cleanup']['crashUnchanged'] = crash_after == crash
                if crash_after != crash:
                    wire._save_new(out / 'crash-after.txt', crash_after.encode())
                shell('rm ' + WATCHLOG)
                report['cleanup']['watchdogLogRemoved'] = True
            except Exception as error:
                report['cleanup']['verificationError'] = type(error).__name__
        if staged:
            try:
                shell('rmdir /data/local/tmp/logtool')
                report['cleanup']['stagingRemoved'] = True
            except Exception:
                report['cleanup']['stagingRemovalFailed'] = True
        save(out, 'export.json', report)
    if report['error']:
        raise ERROR(report['error'])
    if (report['cleanup'].get('remainingPorts') != [] or
            not all(report['cleanup'].get(k) for k in ('watchdogStopObserved', 'stateUnchanged', 'crashUnchanged', 'forwardRemoved', 'stagingRemoved'))):
        raise ERROR('cleanup_not_confirmed')
    print(json.dumps({'phase': 'export_closed',
                      'filesRead': sum(row['status'] == 'read_into_memory' for row in report['files']),
                      'cleanupVerified': True}), flush=True)
    return data


def certificate_identity(raw_cert, raw_key):
    cert = x509.load_pem_x509_certificate(raw_cert) if raw_cert.startswith(b'-----BEGIN') else x509.load_der_x509_certificate(base64.b64decode(raw_cert))
    # OEM strips the RSA PRIVATE KEY label and parses PKCS8 DER, even when the
    # label is inaccurate. Normalize that same DER to a standard PKCS8 PEM.
    clean_key = re.sub(rb'-----[^-]+-----|\s+', b'', raw_key)
    key = serialization.load_der_private_key(base64.b64decode(clean_key, validate=True), password=None)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 2048:
        raise ERROR('unexpected_private_key_type')
    pub = lambda k: k.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if pub(key.public_key()) != pub(cert.public_key()):
        raise ERROR('certificate_key_mismatch')
    return {'cert': cert.public_bytes(serialization.Encoding.PEM).decode(),
            'key': key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption()).decode()}


def identity(data):
    raw_cert, raw_key, prefs = (data[p] for p in FILES[:3])
    auth = certificate_identity(raw_cert, raw_key)
    token = inventory._preference(prefs, 'user_token')
    uuid = inventory._preference(prefs, 'upgradeUuid') or ''
    if not token or not re.fullmatch(r'[\x21-\x7e]{1,8192}', token) or len(uuid) > 256:
        raise ERROR('cached_token_missing_or_invalid')
    version = data[FILES[3]].decode('utf-8').strip()
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', version):
        raise ERROR('invalid_vehicle_version')
    return dict(auth, token=token), uuid, version


def check_body(raw, uuid, version):
    body = inventory._json(raw)
    if type(body) is not dict or body.get('version') != version or body.get('uuid') != uuid:
        raise ERROR('cached_check_identity_mismatch')
    if body.get('encType') != 'gcy-1;gcy-2;gcy-3;gcy-4':
        raise ERROR('cached_check_encoding_mismatch')
    values = metadata.rows(body.get('ecuInfo'))
    if not values or len({r.get('ecuId') for r in values}) != len(values):
        raise ERROR('invalid_cached_ecu_list')
    projected = [metadata.fields(row, ECU_FIELDS) for row in values]
    if any(set(row) != set(ECU_FIELDS) for row in projected):
        raise ERROR('incomplete_cached_ecu')
    return {'version': version, 'uuid': uuid, 'encType': body['encType'], 'ecuInfo': projected}


def measured_ads(folder):
    """Replay fixed read transcripts; never accept free-form ECU/version overrides."""
    report = json.loads((folder / 'result.json').read_text())
    if report.get('error') is not None or not all(report.get(k) is True for k in ('stateUnchanged', 'crashUnchanged')):
        raise ERROR('identity_read_not_verified')
    if report.get('cleanup') != {'stagedDexRemoved': True, 'stateUnchanged': True, 'crashUnchanged': True}:
        raise ERROR('identity_read_cleanup_not_verified')
    spec = importlib.util.spec_from_file_location('ads_identity_decoder', ROOT / 'experiments/ads-version-probe/decode_results.py')
    decoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(decoder)
    fields = {'software-code': 'ecuSoftCode', 'software': 'ecuSoftVer',
              'hardware': 'ecuHardVer', 'config-code': 'ecuConfigCode', 'config-version': 'ecuConfigVer'}
    values = {}
    for name, field in fields.items():
        row = decoder.decode(name, folder / (name + '.txt'))
        if row['state'] != 'complete' or row not in report['results']:
            raise ERROR('identity_read_incomplete_or_mismatched')
        values[field] = row['oem_display_value']
    return values


def apply_measured_ads(body, measured):
    rows = [row for row in body['ecuInfo'] if row['ecuId'] == metadata.ECU_ID]
    if len(rows) != 1 or any(rows[0][k] != measured[k] for k in ('ecuSoftCode', 'ecuSoftVer')):
        raise ERROR('measured_software_does_not_match_cache')
    row = rows[0]
    changes = {k: {'cached': row[k], 'measured': measured[k]}
               for k in ('ecuHardVer', 'ecuConfigCode', 'ecuConfigVer') if row[k] != measured[k]}
    for k in changes:
        row[k] = measured[k]
    return changes


def project(doc):
    if type(doc) is not dict:
        raise ERROR('response_not_object')
    result = {'status': doc.get('status')}
    if type(result['status']) not in (str, int):
        raise ERROR('invalid_api_status')
    ecu_rows = metadata.rows(doc.get('ecuList'))
    result['ecuCount'] = len(ecu_rows)
    result['adsRoutes'] = [metadata.fields(row, ROUTE_FIELDS) for row in ecu_rows if row.get('ecuId') == metadata.ECU_ID]
    strategy = doc.get('strategy') or {}
    if type(strategy) is not dict:
        raise ERROR('invalid_strategy')
    result['batchId'] = strategy.get('batchId')
    sequence = metadata.rows(strategy.get('sequence'))
    result['adsSequence'] = [metadata.fields(row, ROUTE_FIELDS) for row in sequence if row.get('ecuId') == metadata.ECU_ID]
    for name in PACKAGE_ARRAYS:
        packages = metadata.rows(doc.get(name))
        result[name] = [metadata.fields(row, PACKAGE_FIELDS) for row in packages if row.get('ecuId') == metadata.ECU_ID]
        result[name + 'TotalCount'] = len(packages)
    return result


def request(identity_data, method, body):
    if method not in ('getEcuList', 'checkVersion'):
        raise ERROR('method_not_allowed')
    payload = json.dumps(dict(identity_data, method=method, body=body)).encode()
    child = subprocess.run(['/opt/homebrew/bin/node', str(ROOT / 'tools/ads_ota_https.mjs')],
                           input=payload, capture_output=True, timeout=15)
    reply = inventory._json(child.stdout)
    if child.returncode or reply.get('error'):
        diagnostics = {k: reply[k] for k in ('error', 'stage', 'code', 'requestStarted', 'tlsEstablished') if k in reply}
        if any(type(v) not in (str, bool) or (isinstance(v, str) and not re.fullmatch(r'[A-Za-z0-9_]{1,80}', v)) for v in diagnostics.values()):
            raise ERROR('invalid_transport_diagnostics')
        return dict(httpStatus=None, method=method, outcome='transport_error', transport=diagnostics)
    raw = base64.b64decode(reply['body'], validate=True)
    result = {'httpStatus': reply['httpStatus'], 'responseBytes': len(raw),
              'responseSha256': hashlib.sha256(raw).hexdigest(), 'method': method}
    if result['httpStatus'] != 200:
        return dict(result, outcome='http_error')
    result.update(project(inventory._json(raw)), outcome='projected')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--ads-identity-dir', type=Path,
                        help='Verified fixed-read captures; replace only ADS hardware/config fields in the host request')
    args = parser.parse_args()
    args.output.mkdir(mode=0o700)
    report = {'status': 'started', 'error': None, 'requests': [], 'rawIdentitySaved': False,
              'identityUsedFrom': 'stock OTA private backup files, in host memory only'}
    try:
        measured = measured_ads(args.ads_identity_dir) if args.ads_identity_dir else None
        data = export(args.output)
        auth, uuid, version = identity(data)
        client_cert = x509.load_pem_x509_certificate(auth['cert'].encode())
        report['clientIdentity'] = {'certificateSha256': client_cert.fingerprint(hashes.SHA256()).hex(),
                                   'publicKeyBits': client_cert.public_key().key_size,
                                   'signatureOid': client_cert.signature_algorithm_oid.dotted_string,
                                   'notAfter': client_cert.not_valid_after_utc.isoformat()}
        body = check_body(data[FILES[4]], uuid, version)
        report['vehicleVersion'] = version
        report['cachedEcuCount'] = len(body['ecuInfo'])
        report['cachedAds'] = [row for row in body['ecuInfo'] if row['ecuId'] == metadata.ECU_ID]
        # Copy before the optional host-body update; never rewrite the car cache.
        report['cachedAds'] = [dict(row) for row in report['cachedAds']]
        if measured is not None:
            report['measuredAdsChanges'] = apply_measured_ads(body, measured)
            report['identityReadReportSha256'] = hashlib.sha256((args.ads_identity_dir / 'result.json').read_bytes()).hexdigest()
        data.clear()
        first = request(auth, 'getEcuList', {'version': version, 'uuid': uuid})
        save(args.output, 'get-ecu-list.json', first)
        report['requests'].append({'method': 'getEcuList', 'httpStatus': first['httpStatus'], 'status': first.get('status')})
        if first.get('transport'):
            report['transport'] = first['transport']
        print(json.dumps({'phase': 'getEcuList', 'httpStatus': first['httpStatus'], 'status': first.get('status'),
                          'adsRouteCount': len(first.get('adsRoutes', []))}), flush=True)
        if str(first.get('status')) != '100':
            raise ERROR('ecu_list_not_successful')
        second = request(auth, 'checkVersion', body)
        save(args.output, 'check-version.json', second)
        report['requests'].append({'method': 'checkVersion', 'httpStatus': second['httpStatus'], 'status': second.get('status')})
        report['status'] = 'completed'
        auth.clear()
    except Exception as error:
        report.update(status='stopped', error=str(error) if isinstance(error, ERROR) else type(error).__name__)
    finally:
        save(args.output, 'result.json', report)
        print(json.dumps(report), flush=True)
    return 0 if report['status'] == 'completed' else 1


if __name__ == '__main__':
    sys.exit(main())
