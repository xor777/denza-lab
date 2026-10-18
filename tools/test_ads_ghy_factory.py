"""Independent synthetic TLS server; no ADB, stock identity or cloud traffic."""
import hashlib
import json
from pathlib import Path
import socket
import ssl
import tempfile
import threading
import unittest

import ads_ghy_factory as g


class FactoryHttpTests(unittest.TestCase):
    def test_export_scope_and_identity_source(self):
        c = g.common
        self.assertEqual(c.export_profile('ghy-factory')[0], c.GHY_FILES)
        self.assertEqual(len(c.GHY_FILES), 2)
        for action, path, profile in ((3, c.GHY_FILES[0], 'ghy-factory'),
                (1, c.FILES[1], 'ghy-factory'), (1, c.GHY_FILES[0], 'gcy'),
                (True, c.GHY_FILES[0], 'ghy-factory'), (1, c.FILES[0], 'ghy')):
            with self.subTest(action=action, path=path, profile=profile):
                with self.assertRaises(c.ERROR):
                    c.packet(action, path, profile)
        data = {c.GHY_FILES[0]: b'<map><string name="user_token">synthetic</string></map>', c.GHY_FILES[1]: b''}
        self.assertEqual(g.payload(data), ('synthetic', 'v1.0.0', ''))
        data[c.GHY_FILES[0]] = b'<map><string name="user_token">bad&#13;&#10;header</string></map>'
        with self.assertRaises(c.ERROR):
            g.payload(data)

    def exchange(self, mode):
        f = g.factory
        keys = [f.rsa.generate_private_key(public_exponent=65537, key_size=2048) for _ in range(5)]
        ca_key, issuer_key, server_key, client_key, other_key = keys
        def name(value):
            return f.x509.Name([f.x509.NameAttribute(f.NameOID.COMMON_NAME, value)])
        ca = f.make_cert(ca_key, ca_key, name('root'), name('root'), ca=True)
        issuer = f.make_cert(issuer_key, ca_key, ca.subject, name('issuer'), ca=True)
        server = f.make_cert(server_key, issuer_key, issuer.subject, name('localhost'), server=True)
        client = f.make_cert(client_key, issuer_key, issuer.subject, name('synthetic-client'))
        wrong_ca = f.make_cert(other_key, other_key, name('other-root'), name('other-root'), ca=True)
        state = {'request': b'', 'serverError': None}
        with tempfile.TemporaryDirectory(prefix='ads-ghy-synthetic-') as directory:
            folder = Path(directory)
            for filename, value in (
                ('server.pem', f.pem(server)), ('root.pem', f.pem(ca)),
                ('key.pem', server_key.private_bytes(f.serialization.Encoding.PEM, f.serialization.PrivateFormat.PKCS8, f.serialization.NoEncryption()))):
                p = folder / filename
                p.write_bytes(value)
                p.chmod(0o600)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
            context.set_ciphers('ECDHE-RSA-AES128-GCM-SHA256')
            context.load_cert_chain(str(folder / 'server.pem'), str(folder / 'key.pem'))
            context.load_verify_locations(str(folder / 'root.pem'))
            context.verify_mode = ssl.CERT_REQUIRED
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                listener.listen(1)
                listener.settimeout(6)
                def serve():
                    try:
                        conn, _ = listener.accept()
                        conn.settimeout(6)
                        with conn:
                            with context.wrap_socket(conn, server_side=True) as wrapped:
                                raw = b''
                                while b'\r\n\r\n' not in raw:
                                    part = wrapped.recv(4096)
                                    if not part:
                                        return
                                    raw += part
                                headers, body = raw.split(b'\r\n\r\n', 1)
                                length = int(next(l[16:] for l in headers.split(b'\r\n') if l.startswith(b'Content-Length: ')))
                                while len(body) < length:
                                    part = wrapped.recv(4096)
                                    if not part:
                                        return
                                    body += part
                                state['request'] = headers + b'\r\n\r\n' + body
                                payload = b'{"status":"100","ecuList":[]}'
                                if mode == 'chunked':
                                    reply = b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n' + hex(len(payload))[2:].encode() + b'\r\n' + payload + b'\r\n0\r\n\r\n'
                                else:
                                    reply = b'HTTP/1.1 200 OK\r\nContent-Length: ' + str(len(payload)).encode() + b'\r\nConnection: close\r\n\r\n' + payload
                                for i in range(0, len(reply), 7):
                                    wrapped.sendall(reply[i:i+7])
                    except Exception as error:
                        state['serverError'] = type(error).__name__
                worker = threading.Thread(target=serve, daemon=True)
                worker.start()
                def sign(block):
                    digest = f.check_block(block)
                    if mode == 'bad_signature':
                        return bytes(256)
                    return client_key.sign(digest, f.padding.PKCS1v15(), f.utils.Prehashed(f.hashes.SHA256()))
                fields = ('synthetic-token', 'v1.0.0', '')
                if mode == 'header_injection':
                    fields = ('synthetic-token\r\nX-Forged: true', 'v1.0.0', '')
                with socket.create_connection(listener.getsockname(), timeout=6) as transport:
                    transport.setblocking(True)
                    result = g.run_native(g.BINARY, transport, 'wrong.invalid' if mode == 'wrong_hostname' else 'localhost',
                        wrong_ca if mode == 'wrong_ca' else ca, client, issuer, sign, fields)
                worker.join(8)
                self.assertFalse(worker.is_alive())
        self.assertNotIn('synthetic-token', json.dumps(result))
        if mode in ('normal', 'chunked'):
            self.assertEqual(result['returncode'], 0)
            self.assertEqual(result['signerCalls'], 1)
            self.assertEqual(result['response']['status'], '100')
            self.assertEqual(result['response']['ecuCount'], 0)
            self.assertTrue(state['request'].startswith(b'POST /client-service/fota/api/v1/service/getEcuList HTTP/1.1\r\n'))
            self.assertTrue(state['request'].endswith(b'{"version":"v1.0.0","uuid":""}'))
        else:
            self.assertNotEqual(result['returncode'], 0)
            self.assertEqual(state['request'], b'')
            self.assertIsNone(result['response'])
            if mode in ('wrong_ca', 'wrong_hostname'):
                self.assertEqual(result['signerCalls'], 0)
        return result

    def test_independent_tls_cases(self):
        for mode in ('normal', 'chunked', 'wrong_hostname', 'wrong_ca', 'bad_signature', 'header_injection'):
            with self.subTest(mode=mode):
                self.exchange(mode)

    def test_incomplete_and_ambiguous_http_rejected(self):
        for raw in (
            b'HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n{}',
            b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10\r\n{}',
            b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}',
            b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}'):
            with self.subTest(sha256=hashlib.sha256(raw).hexdigest()):
                with self.assertRaises(Exception):
                    g.project_http(raw)


if __name__ == '__main__':
    unittest.main()
