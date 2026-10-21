#!/usr/bin/env python3
"""Replay the car's hostname verifier on the retained public GHY certificate.

Run with .venv-firmware/bin/python. No device, server, identity or trust changes.
Compiles the retained decompiled firmware class unchanged in a temporary folder.
"""
import base64
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes

from check_ghy_trust import verify_signature

ROOT = Path(__file__).resolve().parents[2]
CAPTURE = ROOT / 'captures/ota-ghy-20261021'
SOURCE = ROOT / ('captures/firmware/pulled-from-car-20261021/jadx/okhttp/sources/'
                 'com/android/okhttp/internal/tls/OkHostnameVerifier.java')
JAVA = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
HOST = 'api-ota-cn.denzacloud.com'
DRIVER = '''import java.nio.file.*;
import java.security.cert.*;
import com.android.okhttp.internal.tls.OkHostnameVerifier;
public class GhyHostnameReplay {
  public static void main(String[] args) throws Exception {
    X509Certificate c;
    try (java.io.InputStream in = Files.newInputStream(Path.of(args[0]))) {
      c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
    boolean accepted = OkHostnameVerifier.INSTANCE.verify(args[1], c);
    if (c.getSubjectAlternativeNames() != null || accepted) throw new AssertionError();
    System.out.println("{\\"hostnameAccepted\\":false,\\"subjectAlternativeNames\\":null}");
  }
}
'''


def main():
    observed = json.loads((CAPTURE / 'public-ghy-server-20261021.json').read_text())
    assert observed['host'] == HOST and observed['authorized']
    assert observed['httpRequests'] == observed['hardwareApiCalls'] == 0
    certs = [x509.load_der_x509_certificate(base64.b64decode(c['derBase64']))
             for c in observed['chain']]
    assert len(certs) == 3
    for c, meta in zip(certs, observed['chain']):
        assert c.fingerprint(hashes.SHA256()).hex() == meta['sha256']
    leaf, issuer, root = certs
    verify_signature(leaf, issuer)
    verify_signature(issuer, root)
    verify_signature(root, root)
    assert leaf.subject.get_attributes_for_oid(x509.NameOID.COMMON_NAME)[0].value == HOST
    try:
        leaf.extensions.get_extension_for_class(x509.SubjectAlternativeName)
    except x509.ExtensionNotFound:
        pass
    else:
        raise AssertionError('unexpected SAN')
    runtime = json.loads((CAPTURE / 'public-tls-runtime-hashes.json').read_text())
    for row in runtime['rows']:
        assert row['matches']
        assert hashlib.sha256((ROOT / row['localPath']).read_bytes()).hexdigest() == row['sha256']
    with tempfile.TemporaryDirectory(prefix='ghy-hostname-replay-') as tmp:
        directory = Path(tmp)
        (directory / 'GhyHostnameReplay.java').write_text(DRIVER)
        (directory / 'leaf.der').write_bytes(base64.b64decode(observed['chain'][0]['derBase64']))
        subprocess.run([str(JAVA / 'javac'), '-d', tmp, str(SOURCE),
                        str(directory / 'GhyHostnameReplay.java')], check=True, timeout=30)
        result = subprocess.run([str(JAVA / 'java'), '-cp', tmp, 'GhyHostnameReplay',
                                 str(directory / 'leaf.der'), HOST], check=True,
                                capture_output=True, text=True, timeout=10)
    replay = json.loads(result.stdout)
    assert replay['hostnameAccepted'] is False
    print(json.dumps({'passed': True, 'deviceOrNetworkAccess': False,
                      'certificateSignaturesVerified': 3, 'runtimeJarHashesVerified': 3,
                      'unchangedDecompiledVerifierCompiled': True,
                      'commonNameMatches': True, **replay,
                      'limitation': 'Host replay; the failed Android handshake did not retain its peer certificate or exception message.'}))


if __name__ == '__main__':
    main()
