#!/usr/bin/env python3
"""Read version-field presence in one authenticated GCY ECU catalog.

Only the response projection differs from the qualified login/catalog session.
Identity and token remain in car memory; no ECU or native OTA calls are added.
"""
import argparse
import json
from pathlib import Path
import shutil

import ota_login_catalog as login

catalog = login.catalog
TEMPLATE = catalog.ROOT/'captures/ota-login-catalog-20261021/build-v6'
TEMPLATE_SHA = '24ad243a9f90d292a2a3d287f1e53b8ab45ecce636e99849c4951b67b9de1936'
FIELDS = ('ecuSoftCode', 'ecuSoftVer', 'ecuHardVer', 'ecuConfigCode', 'ecuConfigVer', 'isRepairEcu')
ANCHOR = '"logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq", "ecuIp"'
TEST_OLD = 'lambda r: r["ecus"][0].update(ecuSoftVer="PRIVATE")'
TEST_NEW = 'lambda r: r["ecus"][0].update(unexpectedPrivate="PRIVATE")'


def derived(text):
    return login.exact.transform(text, ANCHOR, ANCHOR+', '+', '.join(json.dumps(k) for k in FIELDS))


def verify(base, manifest):
    for row in manifest['sources']:
        if catalog.qualified.sha(base/row['path']) != row['sha256']:
            raise ValueError('Qualified source changed')
    if catalog.qualified.sha(base/'dex/classes.dex') != manifest['artifactSha256']:
        raise ValueError('Qualified artifact changed')


def build(output):
    if catalog.qualified.sha(TEMPLATE/'build.json') != TEMPLATE_SHA:
        raise ValueError('Qualified login build changed')
    manifest = json.loads((TEMPLATE/'build.json').read_text())
    verify(TEMPLATE, manifest)
    shutil.copytree(TEMPLATE, output)
    source, classes, dex = output/'source', output/'classes', output/'dex'
    for name in ['EcuMapMetadataProbe.java', 'run_ecu_map_metadata.py']:
        path = source/name
        path.write_text(derived(path.read_text()))
    # ecuSoftVer is now explicitly allowed. Keep the unknown-field rejection
    # test using a genuinely unlisted key; privacy checks in Java are unchanged.
    test = source/'test_run_ecu_map_metadata.py'
    test.write_text(login.exact.transform(test.read_text(), TEST_OLD, TEST_NEW))
    (source/'OtaCatalogVersionsTest.java').write_text(r'''import java.nio.charset.StandardCharsets;
import java.util.*;
public final class OtaCatalogVersionsTest {
 static Map<?,?> project(String tail) throws Exception {
  String json="{\"status\":\"100\",\"ecuList\":[{\"ecuId\":\"TEST\",\"ecuReprogramType\":\"can\",\"ecuDiaId\":\"620/628\""+tail+"}]}";
  return (Map<?,?>)EcuMapMetadataProbe.projectResponse(json.getBytes(StandardCharsets.UTF_8)).get(0);
 }
 public static void main(String[] args) throws Exception {
  String[] fields={"ecuSoftCode","ecuSoftVer","ecuHardVer","ecuConfigCode","ecuConfigVer","isRepairEcu"};
  for(String field:fields) {
   if(project("").containsKey(field)) throw new AssertionError("missing became present");
   Map<?,?> nul=project(",\""+field+"\":null");
   if(!nul.containsKey(field)||nul.get(field)!=null) throw new AssertionError("null");
   if(!"".equals(project(",\""+field+"\":\"\"").get(field))) throw new AssertionError("empty");
   if(!"4.00.36".equals(project(",\""+field+"\":\"4.00.36\"").get(field))) throw new AssertionError("value");
   try { project(",\""+field+"\":123"); throw new AssertionError("number accepted"); }
   catch(EcuMapMetadataProbe.Failure expected) {}
  }
  if(project(",\"unrelatedPrivate\":\"CANARY\"").containsKey("unrelatedPrivate")) throw new AssertionError("extra");
  System.out.println("PASS 31 version projection cases: absent/null/empty/value/type; unrelated fields discarded");
 }
}
''')
    (source/'test_catalog_versions.py').write_text('''import unittest
import run_ecu_map_metadata as host
class VersionProjection(unittest.TestCase):
    def test_nullable_version_fields(self):
        row=dict(ecuId='TEST',ecuReprogramType='can',ecuDiaId='620/628',route=dict(kind='can',requestId=1568,responseId=1576,physicalBus=None),evidence='server_candidate_only',eligibleForProductAllowlist=False)
        result=dict(state='RESULT',profile=None,evidence='server_candidate_only',eligibleForProductAllowlist=False,ecus=[row])
        for field in ('ecuSoftCode','ecuSoftVer','ecuHardVer','ecuConfigCode','ecuConfigVer','isRepairEcu'):
            for value in (None,'','4.00.36'):
                row[field]=value
                host.validate_result(result)
        row['unexpected']='PRIVATE_CANARY'
        with self.assertRaises(host.Refused): host.validate_result(result)
''')
    module = catalog.qualified.baseline()
    module.local([module.JDK/'javac', '--release', '8', '-cp', module.ANDROID,
                  '-d', classes, *sorted(source.glob('*.java'))], output/'javac-versions.txt')
    tests = ['EcuMapMetadataProbeTest', 'EcuMapDoipProjectionTest', 'EcuMapHttpStatusTest',
             'OtaLoginProtocolTest', 'OtaCatalogVersionsTest']
    for test in tests:
        module.local([module.JDK/'java', '-cp', classes, test], output/(test+'-versions.txt'))
    module.local(['python3', '-B', '-m', 'unittest', 'discover', '-s', source, '-p', 'test*.py'], output/'host-tests-versions.txt')
    production = [p for p in sorted(classes.glob('*.class')) if 'Test' not in p.name]
    module.local([module.SDK/'build-tools/35.0.0/d8', '--release', '--min-api', '33', '--lib', module.ANDROID,
                  '--output', dex, *production], output/'d8-versions.txt')
    (output/'versions-reader.py').write_bytes(Path(__file__).read_bytes())
    manifest.update({
        'sources': [{'path': str(p.relative_to(output)), 'sha256': catalog.qualified.sha(p)}
                    for p in [*sorted(source.iterdir()), output/'catalog-reader.py', output/'http-status-reader.py',
                              output/'login-reader.py', output/'versions-reader.py', output/'health.py'] if p.is_file()],
        'artifactSha256': catalog.qualified.sha(dex/'classes.dex'),
        'versionsReaderSha256': catalog.qualified.sha(Path(__file__)),
        'parentBuild': {'path': str((TEMPLATE/'build.json').relative_to(catalog.ROOT)), 'sha256': TEMPLATE_SHA},
        'transformations': manifest['transformations']+['Retain five nullable version fields and isRepairEcu; request and transport unchanged.',
                                                       'Unknown-field test uses unexpectedPrivate because ecuSoftVer is now allowed.'],
        'hypothesis': 'The prior catalog projection may have discarded ECU version values supplied by the server.',
        'expected': 'Distinguish absent, null, empty and nonempty version fields for all returned candidates.',
        'falsification': 'Absent or null fields cannot supply a baseline; nonempty fields alone do not establish campaign semantics.',
        'addedProjectionFields': list(FIELDS),
    })
    module.dump(output/'build.json', manifest)
    print(json.dumps({'built': str(output), 'artifactSha256': manifest['artifactSha256'],
                      'cloudRequestLimit': 2, 'ecuCalls': 0,
                      'tests': [(output/(t+'-versions.txt')).read_text().strip() for t in tests]
                               +[(output/'host-tests-versions.txt').read_text().strip()]}))


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
        if manifest['versionsReaderSha256'] != catalog.qualified.sha(Path(__file__)):
            raise ValueError('Version reader changed')
        verify(args.build, manifest)
        for name in ['EcuMapMetadataProbe.java', 'run_ecu_map_metadata.py']:
            if derived((TEMPLATE/'source'/name).read_text()) != (args.build/'source'/name).read_text():
                raise ValueError('Unexpected transformation')
        owner = catalog.load(args.build/'catalog-reader.py', 'catalog_versions_owner')
        owner.run(args.build.resolve(), args.output.resolve())
    else:
        parser.error('--build required')


if __name__ == '__main__':
    main()
