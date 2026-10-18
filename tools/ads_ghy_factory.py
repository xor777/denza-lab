#!/usr/bin/env python3
"""One fixed GHY metadata query using the car's hardware-backed factory identity.

Three public-certificate calls and at most one stock TLS signature; no key
export, retries, login, ECU calls or update. Stock crypto can initialize/recover
the chip internally, so this is an owner-authorized crypto operation, not a
pure file read. Public certs use temporary 0600 files, removed on exit.
"""
import argparse
import base64
import datetime as dt
import hashlib
import http.client
import io
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile

import ads_ota_metadata as common
sys.path.insert(0, str(common.ROOT / 'tools/telematics'))
import tls_identity_probe as factory

HOST = 'api-ota-cn.denzacloud.com'
BINARY = common.ROOT / 'captures/adas-speed-20261018/reports/ads-ghy/factory-tls-client'
LIMIT = 1024 * 1024


def payload(data):
    prefs = data[common.GHY_FILES[0]]
    token = common.inventory._preference(prefs, 'user_token')
    uuid = common.inventory._preference(prefs, 'upgradeUuid') or ''
    raw = data[common.GHY_FILES[1]]
    version = raw.decode('utf-8').strip() if raw else 'v1.0.0'
    if (not token or not re.fullmatch(r'[\x21-\x7e]{1,8192}', token) or
            not re.fullmatch(r'[A-Za-z0-9._-]{0,256}', uuid) or
            not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', version)):
        raise common.ERROR('invalid_ghy_identity')
    return token, version, uuid


def project_http(raw):
    class BufferedSocket:
        def makefile(self, *_args, **_kwargs):
            return io.BytesIO(raw)
    if len(raw) > LIMIT + 16384 or b'\r\n\r\n' not in raw:
        raise common.ERROR('invalid_http_envelope')
    if raw.index(b'\r\n\r\n') > 16384:
        raise common.ERROR('oversized_http_headers')
    response = http.client.HTTPResponse(BufferedSocket())
    response.begin()
    length = response.getheader('Content-Length')
    if len(response.headers.get_all('Content-Length', [])) > 1:
        raise common.ERROR('ambiguous_http_length')
    if length is not None and (not re.fullmatch(r'[0-9]{1,7}', length) or int(length) > LIMIT):
        raise common.ERROR('invalid_http_length')
    if response.getheader('Transfer-Encoding') not in (None, 'chunked'):
        raise common.ERROR('unsupported_http_encoding')
    if response.chunked and length is not None:
        raise common.ERROR('ambiguous_http_framing')
    if not response.chunked and length is None:
        raise common.ERROR('unframed_http_body')
    body = response.read(LIMIT + 1)
    if len(body) > LIMIT or (length is not None and len(body) != int(length)):
        raise common.ERROR('incomplete_or_oversized_http_body')
    result = {'httpStatus': response.status, 'method': 'getEcuList',
              'responseBytes': len(body), 'responseSha256': hashlib.sha256(body).hexdigest()}
    if response.status == 200:
        result.update(common.project(common.inventory._json(body)), outcome='projected')
    else:
        result['outcome'] = 'http_error'
        if b'No required SSL certificate was sent' in body:
            result['httpErrorReason'] = 'client_certificate_required'
        elif b'The SSL certificate error' in body:
            result['httpErrorReason'] = 'client_certificate_rejected'
    return result


def run_native(binary, transport, hostname, root, leaf, issuer, signer, fields):
    """Private pipe protocol; no credentials in argv, stderr or saved response."""
    with tempfile.TemporaryDirectory(prefix='ads-ghy-public-', dir='/private/tmp') as directory:
        paths = []
        for name, cert in (('root', root), ('leaf', leaf), ('issuer', issuer)):
            path = Path(directory) / (name + '.pem')
            raw = factory.pem(cert) + (factory.pem(issuer) if name == 'root' else b'')
            path.write_bytes(raw)
            path.chmod(0o600)
            paths.append(str(path))
        process = subprocess.Popen([str(binary), str(transport.fileno()), hostname, *paths],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            pass_fds=(transport.fileno(),))
        calls, sent, result = 0, False, None
        try:
            while True:
                line = process.stdout.readline(2 * (LIMIT + 16384) + 20)
                if not line:
                    break
                if line.startswith(b'SIGN ') and calls == 0 and re.fullmatch(rb'SIGN [0-9a-f]{512}\n', line):
                    block = bytes.fromhex(line[5:-1].decode())
                    factory.check_block(block)
                    calls += 1
                    signature = signer(block)
                    if len(signature) != 256:
                        raise common.ERROR('invalid_signature_length')
                    process.stdin.write(signature.hex().encode() + b'\n')
                elif line == b'READY_HTTP\n' and calls == 1 and not sent:
                    for value in fields:
                        process.stdin.write(value.encode('ascii') + b'\n')
                    sent = True
                elif line.startswith(b'HTTP_DATA ') and sent and result is None:
                    if not re.fullmatch(rb'HTTP_DATA [0-9a-f]{2,2129920}\n', line):
                        raise common.ERROR('invalid_http_pipe')
                    result = project_http(bytes.fromhex(line[10:-1].decode()))
                    continue
                else:
                    raise common.ERROR('unexpected_tls_pipe_message')
                process.stdin.flush()
            process.wait(timeout=5)
            events = [json.loads(line) for line in process.stderr.read(32768).splitlines()]
            return {'returncode': process.returncode, 'signerCalls': calls,
                    'applicationRequested': sent, 'response': result, 'events': events}
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()


def execute(fields, report):
    """Same three cert selectors and verified RSA block as the proven TLS probe."""
    if common.shell('sha256sum /system/lib64/libsafekeyservice.so').split()[0] != factory.LIBRARY_HASH:
        raise common.ERROR('factory_library_changed')
    certs = []
    report['publicCertificates'] = []
    for selector in (5, 6, 7):
        reply = common.shell('service call safekeyservice 14 i32 10102 i32 ' + str(selector), timeout=20)
        _, encoded, _ = factory.decode_parcel(reply)
        cert = factory.x509.load_der_x509_certificate(base64.b64decode(''.join(encoded.split()), validate=True))
        certs.append(cert)
        report['publicCertificates'].append({'selector': selector, 'sha256': cert.fingerprint(factory.hashes.SHA256()).hex()})
    root, issuer, leaf = certs
    if leaf.fingerprint(factory.hashes.SHA256()).hex() != factory.CLIENT_FINGERPRINT:
        raise common.ERROR('factory_identity_changed')
    root.verify_directly_issued_by(root)
    issuer.verify_directly_issued_by(root)
    leaf.verify_directly_issued_by(issuer)
    now = dt.datetime.now(dt.timezone.utc)
    if any(not (c.not_valid_before_utc <= now <= c.not_valid_after_utc) for c in certs):
        raise common.ERROR('factory_certificate_expired')
    if not all(c.extensions.get_extension_for_class(factory.x509.BasicConstraints).value.ca for c in certs[:2]):
        raise common.ERROR('factory_chain_not_ca')
    public = leaf.public_key()
    if not isinstance(public, factory.rsa.RSAPublicKey) or public.key_size != 2048:
        raise common.ERROR('unexpected_factory_public_key')
    signatures = 0
    def sign(block):
        nonlocal signatures
        digest = factory.check_block(block)
        if signatures:
            raise common.ERROR('signature_budget_exhausted')
        signatures += 1
        report['stockSignatureCalls'] = signatures
        reply = common.shell('service call safekeyservice 6 i32 10106 i32 ' + str(factory.RAW_RSA_PARAMETER) +
                             ' s16 ' + block.hex() + ' i32 256', timeout=20)
        _, encoded, _ = factory.decode_parcel(reply)
        if not re.fullmatch(r'[0-9a-fA-F]{512}', encoded):
            raise common.ERROR('invalid_factory_signature')
        signature = bytes.fromhex(encoded)
        public.verify(signature, digest, factory.padding.PKCS1v15(), factory.utils.Prehashed(factory.hashes.SHA256()))
        report['signatureLocallyVerified'] = True
        return signature
    with socket.create_connection((HOST, 443), timeout=10) as transport:
        transport.setblocking(True)
        report['native'] = run_native(BINARY, transport, HOST, root, leaf, issuer, sign, fields)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(mode=0o700)
    report = {'status': 'started', 'error': None, 'stockSignatureCalls': 0,
              'privateKeyExported': False, 'rawIdentityRetained': False, 'diagnosticRequests': 0}
    data, fields, before, crash = {}, None, None, None
    try:
        data = common.export(args.output, profile='ghy-factory')
        fields = payload(data)
        report['vehicleVersion'] = fields[1]
        report['versionSource'] = 'file' if data[common.GHY_FILES[1]] else 'OEM_missing_or_empty_fallback'
        data.clear()
        before = common.shell(common.STATE + '\npidof safekeyservice\npidof cloudmanager')
        crash = common.shell('logcat -d -b crash -v time')
        execute(fields, report)
        native = report['native']
        result = native.pop('response')
        if result is not None:
            common.save(args.output, 'get-ecu-list.json', result)
            report['request'] = {k: result[k] for k in ('httpStatus', 'status', 'ecuCount', 'outcome', 'httpErrorReason') if k in result}
            report['adsRouteCount'] = len(result.get('adsRoutes', []))
        report['status'] = 'completed' if native['returncode'] == 0 and result is not None else 'stopped'
    except Exception as error:
        report.update(status='stopped', error=str(error) if isinstance(error, common.ERROR) else type(error).__name__)
    finally:
        fields = None
        data.clear()
        if before is not None:
            try:
                report['stateUnchanged'] = common.shell(common.STATE + '\npidof safekeyservice\npidof cloudmanager') == before
                after_crash = common.shell('logcat -d -b crash -v time')
                report['crashUnchanged'] = after_crash == crash
                if after_crash != crash:
                    common.wire._save_new(args.output / 'crash-after.txt', after_crash.encode())
            except Exception:
                report['postflightUnconfirmed'] = True
            if report.get('stateUnchanged') is not True or report.get('crashUnchanged') is not True:
                report.update(status='stopped', error='postflight_changed_or_unconfirmed')
        common.save(args.output, 'result.json', report)
        print(json.dumps(report), flush=True)
    return 0 if report['status'] == 'completed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
