#!/usr/bin/env python3
"""Isolated stock authentication for reading one ECU catalog; no native login manager.

Identity and the resulting token stay in car memory. Registration, persistence,
fault status writes, version reports, downloads and installation are absent.
"""
import argparse
import json
from pathlib import Path
import shutil

import ota_http_status_catalog as exact

catalog = exact.catalog
SOURCE = catalog.SOURCE
JAVA_CHANGES = [
    ('SSLContext context = tlsContext(inputs);',
     'SSLContext context = tlsContext(inputs);\n            stage="login_inputs"; OtaLoginProtocol.identity=OtaLoginInputs.read();'),
    ('stage="https"; byte[] response=post(inputs, context, networkEnd);',
     'stage="https_login"; inputs=OtaLoginProtocol.login(inputs, context, networkEnd);\n            stage="https_catalog"; byte[] response=post(inputs, context, networkEnd);'),
    ('privacyCheck(ecus, inputs);', 'privacyCheck(ecus, inputs); OtaLoginProtocol.privacy(ecus);'),
    ('byte[] request=httpRequest(inputs);', 'byte[] request=OtaLoginProtocol.request(inputs);'),
]
NEW_CODES = ('login_inputs_busy login_inputs_profile login_inputs_bench login_inputs_vin '
             'login_inputs_iccid login_inputs_iccid_missing login_inputs_iccid_shape '
             'login_inputs_context login_inputs_package login_inputs_phone login_inputs_iccid_getter '
             'login_inputs_permission login_inputs_country login_request_order login_response_shape '
             'login_registration_required login_iccid_binding login_vin_unknown '
             'login_auth_required login_application_status login_token_invalid')
HOST_CHANGES = [
    ('"input_validate", "trust", "go_wait", "https", "projection"',
     '"input_validate", "trust", "go_wait", "https", "projection", "login_inputs", "https_login", "https_catalog"'),
    ('operation_failed runtime_guard arguments deadline', NEW_CODES+'\noperation_failed runtime_guard arguments deadline'),
]
TEST_OLD = '"sleep": "#!/bin/sh\\n/bin/sleep 0.01\\n",'
TEST_NEW = ('"sleep": "#!/bin/sh\\nwhile [ ! -e \\"$FIXTURE/start_entered\\" ] && '
            '[ ! -e \\"$FIXTURE/allow_start\\" ]; do /bin/sleep 0.01; done\\n/bin/sleep 0.01\\n",')


def build(output):
    # Reuse the already tested and executed exact-status build. Its upstream
    # files and hashes are verified before deriving this separate artifact.
    template = catalog.ROOT/'captures/ota-http-status-catalog-20261021/build'
    template_manifest = json.loads((template/'build.json').read_text())
    if template_manifest['statusReaderSha256'] != catalog.qualified.sha(Path(exact.__file__)):
        raise ValueError('Precise-status template source changed')
    for row in template_manifest['sources']:
        if catalog.qualified.sha(template/row['path']) != row['sha256']: raise ValueError('Template source changed')
    if catalog.qualified.sha(template/'dex/classes.dex') != template_manifest['artifactSha256']:
        raise ValueError('Template dex changed')
    shutil.copytree(template, output)
    module = catalog.qualified.baseline()
    source, classes, dex = output/'source', output/'classes', output/'dex'
    for name, changes in [('EcuMapMetadataProbe.java', JAVA_CHANGES), ('run_ecu_map_metadata.py', HOST_CHANGES)]:
        path = source/name; text = path.read_text()
        for old, new in changes: text = exact.transform(text, old, new)
        path.write_text(text)
    for name in ['OtaLoginInputs.java', 'OtaLoginProtocol.java', 'OtaLoginProtocolTest.java', 'OtaTelephonyBootstrap.java']:
        (source/name).write_bytes((SOURCE/name).read_bytes())
    # Start the fixture's accelerated deadline only after fake am is entered.
    # This makes the intended delayed-START scenario deterministic under host
    # load. Generated production shell scripts and their deadlines are unchanged.
    test = source/'test_run_ecu_map_metadata.py'
    test.write_text(exact.transform(test.read_text(), TEST_OLD, TEST_NEW))
    module.local([module.JDK/'javac', '--release', '8', '-cp', module.ANDROID,
                  '-d', classes, *sorted(source.glob('*.java'))], output/'javac-login.txt')
    for test in ['EcuMapMetadataProbeTest', 'EcuMapDoipProjectionTest', 'EcuMapHttpStatusTest', 'OtaLoginProtocolTest']:
        module.local([module.JDK/'java', '-cp', classes, test], output/(test+'.txt'))
    (source/'test_login_codes.py').write_text('''import json
import unittest
import run_ecu_map_metadata as host
class LoginCodesTest(unittest.TestCase):
    def test_login_stage_and_codes(self):
        for stage,code in [('login_inputs','login_inputs_iccid'),('https_login','login_iccid_binding'),('https_catalog','http_401')]:
            with self.assertRaises(host.HelperFailure) as caught:
                host.parse_message(json.dumps(dict(state='ERROR',stage=stage,code=code)).encode())
            self.assertEqual((caught.exception.stage,caught.exception.code),(stage,code))
''')
    module.local(['python3', '-B', '-m', 'unittest', 'discover', '-s', source, '-p', 'test*.py'], output/'host-tests-login.txt')
    production = [p for p in sorted(classes.glob('*.class')) if 'Test' not in p.name]
    module.local([module.SDK/'build-tools/35.0.0/d8', '--release', '--min-api', '33', '--lib', module.ANDROID,
                  '--output', dex, *production], output/'d8-login.txt')
    # Keep the prior live artifact's source immutable. The copied outer owner
    # differs only in the recorded two-request bound (its transport is delegated).
    owner = output/'catalog-reader.py'
    owner.write_text(owner.read_text().replace("'cloudRequestLimit':1", "'cloudRequestLimit':2"))
    (output/'login-reader.py').write_bytes(Path(__file__).read_bytes())
    manifest = json.loads((output/'build.json').read_text())
    manifest.update({
        'sources': [{'path': str(p.relative_to(output)), 'sha256': catalog.qualified.sha(p)}
                    for p in [*sorted(source.iterdir()), owner, output/'http-status-reader.py',
                              output/'login-reader.py', output/'health.py'] if p.is_file()],
        'artifactSha256': catalog.qualified.sha(dex/'classes.dex'),
        'plannerSha256': catalog.qualified.sha(owner),
        'loginReaderSha256': catalog.qualified.sha(Path(__file__)),
        'transformations': manifest['transformations'] + ['Fixed in-memory loginIn, then getEcuList; two POSTs total.',
                                                        'Fixed buffer getter for VIN and TelephonyManager ICCID getter; no OEM device constructors.',
                                                        'Host test only: barrier starts the accelerated watchdog clock after fake am entry.'],
        'hypothesis': 'A fresh stock login token restores catalog access after the observed HTTP401.',
        'expected': 'loginIn status100 permits one catalog query, or a fixed error identifies the failing stage.',
        'falsification': 'Any identity getter, login or catalog failure stops without registration, fallback or retry.',
        'cloudRequestLimit': 2, 'loginOrRegistration': 'login_only',
        'tokenPersistence': False, 'nativeLoginManager': False,
        'vehicleCalls': ['autoservice getBuffer transaction13 device1001 FID0x9900021a', 'TelephonyManager.getSimSerialNumber'],
        'ecuCalls': 0, 'totalNetworkDeadlineSeconds': 25,
    })
    module.dump(output/'build.json', manifest)
    print(json.dumps({'built': str(output), 'artifactSha256': manifest['artifactSha256'], 'cloudRequestLimit': 2,
                      'tests': [(output/n).read_text().strip() for n in ['OtaLoginProtocolTest.txt', 'host-tests-login.txt']]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'run'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build', type=Path)
    args = parser.parse_args()
    if args.action == 'build': build(args.output.resolve())
    elif args.build:
        manifest = json.loads((args.build/'build.json').read_text())
        if manifest['loginReaderSha256'] != catalog.qualified.sha(Path(__file__)): raise ValueError('Login reader changed')
        for name in ['OtaLoginInputs.java','OtaLoginProtocol.java','OtaLoginProtocolTest.java','OtaTelephonyBootstrap.java']:
            if (SOURCE/name).read_bytes() != (args.build/'source'/name).read_bytes(): raise ValueError('Login source changed')
        owner = catalog.load(args.build/'catalog-reader.py', 'isolated_login_catalog_owner')
        owner.run(args.build.resolve(), args.output.resolve())
    else: parser.error('--build required')


if __name__ == '__main__': main()
