#!/usr/bin/env python3
"""Plan fixed remaining positive-cache CAN namespaces using the qualified reader.

No guessed DIDs, dynamic URL/UDS input or native OTA invocation. build is offline;
run executes exactly one named <=10-request group. An incomplete group stops.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'captures/ota-core-fields-20261021/build-v2/runner.py'
BASE_SHA = '47961ed4a84d78e48a9093fc680a7170b792f8857e60d5abb6c1df71d74f3ce8'
SMM = ROOT / 'captures/firmware/ivi-34.1.33.2605218.1/jadx/OTAUpdatePlatformizedUI/resources/res/raw/smm_module_config.json'
SMM_SHA = '6624a79e66402329085092697cea051e4391c19f7bad4a9832c6287df52d88e9'
CORE = ROOT / 'research/vehicle-diagnostics/ota-core-fields-live-20261021.json'
CORE_SHA = '5125cd7b7fe91a5b6b65411282e3a11c0da1c296c41c235f80cb6657f50f9fea'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def baseline():
    if sha(BASE) != BASE_SHA:
        raise ValueError('Qualified reader snapshot changed')
    spec = importlib.util.spec_from_file_location('qualified_ota_field_reader', BASE)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    # The immutable source snapshot lives deeper than its original tools/ path.
    module.ROOT = ROOT
    module.OLD = ROOT / 'captures/diagnostics-followup-20261021/front-zcu/build'
    module.MAP = ROOT / 'research/vehicle-diagnostics/factory-ecu-map-20261020.json'
    module.CACHE = ROOT / 'captures/diagnostics-discovery-20261018/approved-export-1/files/04-ECUVersion.json'
    module.self_test()
    return module


def plan(module):
    for path, expected in [(SMM, SMM_SHA), (CORE, CORE_SHA), (module.MAP, module.MAP_SHA), (module.CACHE, module.CACHE_SHA)]:
        if sha(path) != expected: raise ValueError('Pinned input changed: '+str(path))
    suffixes = tuple(r['ecuIdSuffix'] for r in json.loads(SMM.read_text()))
    completed = set(json.loads(CORE.read_text())['selectedEcus'])
    mapping = json.loads(module.MAP.read_text())['ecus']
    cache = {r['ecuId']: r for r in json.loads(module.CACHE.read_text())['ecuInfo']}
    keys = ['ecuReprogramType','ecuChannel','ecuDiaId','logicAddress','ecuDId','ecuHardDid','ecuConfigDid']
    fields = ['ecuSoftCode','ecuSoftVer','ecuHardVer','ecuConfigCode','ecuConfigVer']
    grouped = defaultdict(list); excluded = []
    for row in mapping:
        ecu = row['ecuId']; values = cache[ecu]; reason = None
        if ecu in completed: reason = 'core_already_read'
        elif '_Media_' in ecu: reason = 'Media_special_reader'
        elif ecu.endswith(suffixes): reason = 'SMM_ASCII_version_reader'
        elif row['ecuReprogramType'] not in ('can','canfd'): reason = 'DoIP_transport'
        elif not re.fullmatch('[0-9A-F]{18}', values['ecuSoftCode']) or int(values['ecuSoftCode'],16) == 0:
            reason = 'no_positive_cached_identity'
        if reason: excluded.append({'ecuId':ecu,'reason':reason}); continue
        grouped[tuple(row.get(k) for k in keys)].append(row)
    namespaces = []; omissions = []
    for key, rows in grouped.items():
        row = rows[0]; ids = [r['ecuId'] for r in rows]; values = cache[ids[0]]
        if len({tuple(cache[i][f] for f in fields) for i in ids}) != 1:
            raise ValueError('Conflicting cache aliases')
        match = re.fullmatch(r'([0-9A-F]{3})/([0-9A-F]{3})',row['ecuDiaId'])
        pair = re.fullmatch(r'([0-9A-F]{4})/([0-9A-F]{4})',row['ecuDId'])
        if not match or not pair or int(match[2],16) != int(match[1],16)+8:
            raise ValueError('Unqualified request/response or DID pair')
        address = match[1]
        # Only the already observed CAN address cohort; never turn an arbitrary
        # catalogue address into a working route by inference.
        allowed_low = {'582','590','595','597','5A0','5A7','5B0','5B1','5B2','5E0','5E4','5F4'}
        if not (0x700 <= int(address,16) <= 0x7F7 and address != '7DF') and address not in allowed_low:
            raise ValueError('Address outside retained response cohort')
        requests = [(pair[2],'ecuSoftCode'),(pair[1],'ecuSoftVer')]
        if row['ecuHardDid'] and re.fullmatch(r'\d\.\d\.\d',values['ecuHardVer']):
            requests.append((row['ecuHardDid'],'ecuHardVer'))
        else:
            omissions.append({'ecuIds':ids,'field':'ecuHardVer','reason':
                              'no hardware DID in factory map' if not row['ecuHardDid'] else 'cached field is not a positive hardware version'})
        if row['ecuConfigDid']:
            config = re.fullmatch(r'([0-9A-F]{4})/([0-9A-F]{4})',row['ecuConfigDid'])
            if not config: raise ValueError('Malformed configuration DIDs')
            if re.fullmatch('[0-9A-F]{18}',values['ecuConfigCode']) and int(values['ecuConfigCode'],16) != 0:
                requests += [(config[2],'ecuConfigCode'),(config[1],'ecuConfigVer')]
            else:
                omissions.append({'ecuIds':ids,'field':'configuration','reason':'no positive cached configuration code'})
        for did, field in requests:
            if not re.fullmatch('[0-9A-F]{4}',did): raise ValueError('Missing qualified DID')
            if field.endswith('Ver') and field != 'ecuHardVer' and not re.fullmatch(r'\d\.\d{2}\.\d{2}',values[field]):
                raise ValueError('Field needs another parser')
        steps = [dict(ecuId=ids[0],ecuIds=ids,address=address,request='22'+did,field=field,
                      cached=values[field],identityGate=(index==0)) for index,(did,field) in enumerate(requests)]
        priority = 0 if len(steps)==5 else 1 if pair[2]!='F194' else 2
        namespaces.append({'signature':dict(zip(keys,key)),'ecuIds':ids,'steps':steps,'priority':priority})
    namespaces.sort(key=lambda n:(n['priority'],int(n['steps'][0]['address'],16),n['steps'][0]['request']))
    groups = {}; chunk = []
    for namespace in namespaces:
        if chunk and len(chunk)+len(namespace['steps']) > 10:
            groups[f"remaining-{len(groups)+1:03d}"] = chunk; chunk=[]
        chunk += namespace['steps']
    if chunk: groups[f"remaining-{len(groups)+1:03d}"] = chunk
    assert len(mapping)==225 and len(excluded)+sum(len(n['ecuIds']) for n in namespaces)==225
    assert all(1 <= len(g) <= 10 for g in groups.values())
    return {'summary':{'records':sum(len(n['ecuIds']) for n in namespaces),'namespaces':len(namespaces),
                       'groups':len(groups),'requests':sum(map(len,groups.values())),
                       'excluded':dict(Counter(e['reason'] for e in excluded))},
            'plans':groups,'namespaces':namespaces,'excluded':excluded,'fieldOmissions':omissions,
            'limits':['Positive-cache CAN fields only; exclusions and omitted fields remain unverified.',
                      'Same diagnostic namespace can have several server aliases; a read is not separate physical presence proof.',
                      'No automatic cloud request, version substitution, retry, download or install.']}


def configure(module, fixed):
    module.plans = lambda: fixed['plans']
    module.self_test = lambda: fixed['plans']


def build(module, fixed, output):
    configure(module,fixed)
    original_local = module.local
    pairs = sorted({(int(s['address'],16),int(s['request'][2:],16)) for g in fixed['plans'].values() for s in g})
    # Change only the offline policy test to enumerate all selected DIDs, not
    # the original core reader's five-DID subset. Runtime generator is reused.
    array = ','.join('{'+str(a)+','+str(d)+'}' for a,d in pairs)
    test = '''package dev.denza.diagnostics.probe;
import java.util.*;
public class FixedAllowlistTest {
 public static void main(String[] args) {
  int[][] pairs=new int[][]{PAIRS};Set<Integer> expected=new HashSet<>(),addresses=new HashSet<>(),dids=new HashSet<>();
  for(int[] p:pairs){expected.add((p[0]<<16)|p[1]);addresses.add(p[0]);dids.add(p[1]);}
  int count=0;
  for(int a=0;a<2048;a++)for(int did:dids){boolean actual=DiagnosticWhitelist.allowed(a,new byte[]{0x22,(byte)(did>>8),(byte)did});
   if(actual!=expected.contains((a<<16)|did))throw new AssertionError("address/DID");if(actual)count++;}
  for(int a:addresses)for(int did=0;did<65536;did++)if(DiagnosticWhitelist.allowed(a,new byte[]{0x22,(byte)(did>>8),(byte)did})!=expected.contains((a<<16)|did))throw new AssertionError("DID scope");
  if(count!=expected.size())throw new AssertionError("count");int a=pairs[0][0],d=pairs[0][1];
  for(int sid=0;sid<256;sid++)if(DiagnosticWhitelist.allowed(a,new byte[]{(byte)sid,(byte)(d>>8),(byte)d})!=(sid==0x22))throw new AssertionError("service");
  if(DiagnosticWhitelist.allowed(a,null)||DiagnosticWhitelist.allowed(a,new byte[]{0x22,(byte)(d>>8)})||DiagnosticWhitelist.allowed(a,new byte[]{0x22,(byte)(d>>8),(byte)d,0}))throw new AssertionError("shape");
  System.out.println("PASS fixed remaining pairs="+count+"; address/DID/service/shape");
 }
}
'''.replace('PAIRS',array)
    def local(args, log):
        if Path(args[0]).name == 'javac':
            (output/'source/FixedAllowlistTest.java').write_text(test)
        return original_local(args,log)
    module.local = local
    module.build(output)
    (output/'extension.py').write_bytes(Path(__file__).read_bytes())
    module.dump(output/'scope.json',fixed)
    manifest=json.loads((output/'build.json').read_text())
    manifest['sources'] += [{'path':p.name,'sha256':sha(p)} for p in [output/'extension.py',output/'scope.json']]
    manifest['extension']={'base':str(BASE.relative_to(ROOT)),'baseSha256':BASE_SHA,'scope':fixed['summary'],
                           'plannerSha256':sha(Path(__file__)),'specialSmmSuffixesSha256':SMM_SHA}
    module.dump(output/'build.json',manifest)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['plan','build','run']);p.add_argument('--output',type=Path)
    p.add_argument('--build',type=Path);p.add_argument('--group')
    a=p.parse_args();module=baseline();fixed=plan(module)
    if a.action=='plan':
        if a.output:module.dump(a.output.resolve(),fixed)
        print(json.dumps(fixed['summary']))
    elif a.action=='build':
        if not a.output:p.error('build requires --output')
        build(module,fixed,a.output.resolve())
    else:
        if not a.output or not a.build or a.group not in fixed['plans']:p.error('run requires --build, --output and a fixed --group')
        manifest=json.loads((a.build/'build.json').read_text())
        if manifest['extension']['plannerSha256']!=sha(Path(__file__)):raise ValueError('Planner changed after build')
        configure(module,fixed)
        module.run(a.build.resolve(),a.group,a.output.resolve())


if __name__=='__main__':main()
