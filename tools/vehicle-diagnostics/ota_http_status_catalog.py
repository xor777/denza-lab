#!/usr/bin/env python3
"""One read-only catalog request with precise 401/403 error classification.

Builds on the pinned catalog reader without changing its request, TLS or export
lifecycle. No login, registration, retries, ECU calls or OTA lifecycle methods.
"""
import argparse
import json
from pathlib import Path

import ota_doip_catalog as catalog

JAVA_OLD = r'if(status.matches("HTTP/1\\.[01] (?:401|403)(?: [ -~]*)?"))throw new Failure("http_auth");'
JAVA_NEW = (r'if(status.matches("HTTP/1\\.[01] 401(?: [ -~]*)?"))throw new Failure("http_401");'
            '\n        ' + r'if(status.matches("HTTP/1\\.[01] 403(?: [ -~]*)?"))throw new Failure("http_403");')
HOST_OLD = 'tls_protocol http_auth http_status'
HOST_NEW = 'tls_protocol http_auth http_401 http_403 http_status'


def transform(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Expected diagnostic anchor changed')
    return text.replace(old, new)


def build(output):
    catalog.build(output)
    module = catalog.qualified.baseline()
    source, classes, dex = output/'source', output/'classes', output/'dex'
    for name, old, new in [('EcuMapMetadataProbe.java', JAVA_OLD, JAVA_NEW),
                           ('run_ecu_map_metadata.py', HOST_OLD, HOST_NEW)]:
        path = source/name
        path.write_text(transform(path.read_text(), old, new))
    (source/'EcuMapHttpStatusTest.java').write_text(r'''import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
public class EcuMapHttpStatusTest {
 static void check(String line, String expected) throws Exception {
  try {
   EcuMapMetadataProbe.readHttp(new ByteArrayInputStream((line+"\r\nPRIVATE_RESPONSE_BODY").getBytes(StandardCharsets.US_ASCII)), System.nanoTime()+1000000000L);
   throw new AssertionError("Unexpected success");
  } catch(EcuMapMetadataProbe.Failure failure) {
   if(!expected.equals(failure.code)) throw new AssertionError(failure.code);
  }
 }
 public static void main(String[] args) throws Exception {
  check("HTTP/1.1 401 Unauthorized", "http_401");
  check("HTTP/1.0 401", "http_401");
  check("HTTP/1.1 403 Forbidden", "http_403");
  check("HTTP/1.0 403", "http_403");
  check("HTTP/1.1 4010 Invalid", "http_status");
  check("HTTP/1.1 403X Invalid", "http_status");
  check("HTTP/1.1 302 Redirect", "http_status");
  System.out.println("PASS 7 exact HTTP classifications; response bodies not consumed");
 }
}
''')
    (source/'test_http_status.py').write_text('''import json
import unittest
import run_ecu_map_metadata as host
class HttpStatusTest(unittest.TestCase):
    def test_only_static_status_codes_propagate(self):
        for code in ('http_401', 'http_403'):
            with self.assertRaises(host.HelperFailure) as caught:
                host.parse_message(json.dumps(dict(state='ERROR', stage='https', code=code)).encode())
            self.assertEqual(caught.exception.code, code)
        with self.assertRaises(host.Refused) as caught:
            host.parse_message(b'{"state":"ERROR","stage":"https","code":"http_401_PRIVATE"}')
        self.assertNotIsInstance(caught.exception, host.HelperFailure)
''')
    module.local([module.JDK/'javac', '--release', '8', '-d', classes,
                  *sorted(source.glob('*.java'))], output/'javac-exact.txt')
    for test, filename in [('EcuMapMetadataProbeTest', 'java-tests-exact.txt'),
                           ('EcuMapDoipProjectionTest', 'projection-test-exact.txt'),
                           ('EcuMapHttpStatusTest', 'http-status-test.txt')]:
        module.local([module.JDK/'java', '-cp', classes, test], output/filename)
    module.local(['python3', '-B', '-m', 'unittest', 'discover', '-s', source,
                  '-p', 'test*.py'], output/'host-tests-exact.txt')
    production = [p for p in sorted(classes.glob('EcuMapMetadataProbe*.class')) if 'Test' not in p.name]
    module.local([module.SDK/'build-tools/35.0.0/d8', '--release', '--min-api', '33',
                  '--lib', module.ANDROID, '--output', dex, *production], output/'d8-exact.txt')
    (output/'http-status-reader.py').write_bytes(Path(__file__).read_bytes())
    manifest = json.loads((output/'build.json').read_text())
    manifest.update({
        'sources': [{'path': str(p.relative_to(output)), 'sha256': catalog.qualified.sha(p)}
                    for p in [*sorted(source.iterdir()), output/'catalog-reader.py',
                              output/'http-status-reader.py', output/'health.py'] if p.is_file()],
        'artifactSha256': catalog.qualified.sha(dex/'classes.dex'),
        'statusReaderSha256': catalog.qualified.sha(Path(__file__)),
        'transformations': manifest['transformations'] + [
            'Split http_auth into http_401/http_403; allow only these fixed diagnostic codes.'],
        'hypothesis': 'The previous HTTP authentication refusal was 401, which stock handles with token login.',
        'expected': 'One request retains exact 401 or 403, or returns the normal projected catalog.',
        'falsification': '403 rules out the stock HTTP-401 retry condition; a new response does not explain the earlier refusal.',
        'loginOrRegistration': False,
    })
    module.dump(output/'build.json', manifest)
    print(json.dumps({'built': str(output), 'artifactSha256': manifest['artifactSha256'],
                      'tests': [(output/n).read_text().strip() for n in
                                ['java-tests-exact.txt', 'projection-test-exact.txt',
                                 'http-status-test.txt', 'host-tests-exact.txt']]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'run'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build', type=Path)
    args = parser.parse_args()
    if args.action == 'build':
        build(args.output.resolve())
    elif args.build:
        manifest = json.loads((args.build/'build.json').read_text())
        if manifest['statusReaderSha256'] != catalog.qualified.sha(Path(__file__)):
            raise ValueError('Status reader changed')
        catalog.run(args.build.resolve(), args.output.resolve())
    else:
        parser.error('--build required')


if __name__ == '__main__':
    main()
