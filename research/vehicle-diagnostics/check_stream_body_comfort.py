#!/usr/bin/env python3
"""Validate the retained body/comfort stream contracts, entirely offline."""
import hashlib
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[2]
    path = root / 'research/vehicle-diagnostics/stream-body-comfort-20261021.json'
    d = json.loads(path.read_text())
    manifest = {s['path']: s['sha256'] for s in d['sourceManifest']}
    for name, sha in manifest.items():
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == sha, name
    reg = json.loads((root / d['crossReferences']['allDeclarations']).read_text())
    sweep = (root / 'captures/diagnostics-sweep-20261020/fids.tsv').read_text().splitlines()
    seen = set()
    getter_ids = {g['id'] for g in d['selectedSDKGetters']}
    assert len(getter_ids) == len(d['selectedSDKGetters'])
    for s in d['signals']:
        key = (s['nativeDevice'], s['propertyKey'], s['name'])
        assert key not in seen, key
        seen.add(key)
        r = dict(zip(reg['columns'], reg['rows'][s['registryRow']]))
        for a, b in [('symbol', 'name'), ('nativeDevice', 'nativeDevice'),
                     ('propertyKey', 'propertyKey'), ('nativeCanFdHex', 'nativeFidHex'),
                     ('declaredJavaType', 'declaredJavaType'), ('access', 'access')]:
            assert r[a] == s[b], (key, a)
        assert r['access'] in ('read', 'read_write')
        assert s['physicalEcuBinding'] is None
        assert s['familyRu'] and s['labelRu'] and s['readinessRu'] and s['validityRu']
        assert set(s['sdkGetterIds']) <= getter_ids, (s['name'], s['sdkGetterIds'])
        assert s['readRecipe'] in d['readRecipes'], s['readRecipe']
        if s['nativeFidHex'] is None:
            assert not s['observations']
        if s['formula'] == 'stock service and callback: native numeric value * 0.01':
            keys = root / 'captures/ambient-light-20260923/jadx/DiCarServer/sources/com/byd/car/feature/seat/property/AdjustmentKeys.java'
            assert s['name'] in keys.read_text(), s['name']
        for o in s['observations']:
            row = sweep[o['sourceLine'] - 1].split('\t')
            assert row[:2] == ['FID', str(o['pass'])]
            assert row[3:6] == [str(s['nativeDevice']), s['nativeFidHex'], o['intWord']]
            assert (row[6] or None) == o['floatWord']
        for source in s['sources']:
            assert source['path'] in manifest, source
            lines = (root / source['path']).read_text().splitlines()
            assert all(0 < n <= len(lines) for n in source['lines']), source
    anchors = root / 'captures/stream-body-comfort-20261021/sdk-getter-anchors.json'
    anchor_count = 0
    if anchors.exists():
        anchor_rows = json.loads(anchors.read_text())
        assert d['selectedSDKGetters'] == [dict(g, sourceBody=None) for g in anchor_rows]
        for g in anchor_rows:
            source = g['source']
            text = (root / source['path']).read_text().splitlines()
            actual = '\n'.join(text[n - 1] for n in source['lines'])
            assert actual == g['sourceBody'], g['id']
            assert 'super.set(' not in actual and '.setValue(' not in actual, g['id']
            anchor_count += 1
    assert len(d['signals']) == d['signalCount']
    audio = [s for s in d['signals'] if s['family'] == 'audio_diagnostic_states']
    audit_ref = d['audioConsumerAudit']
    assert audit_ref['path'] in manifest
    audit = json.loads((root / audit_ref['path']).read_text())
    assert all(not scope['matches'] for scope in audit['results'])
    assert {scope['label'] for scope in audit['results']} == {'sdk', 'dicar_consumers', 'ivi_systemui', 'devtools'}
    assert {s['nativeFidHex'] for s in audio} == {'0x35201042', '0x99000246', '0x99000247', '0x99000248', '0x99000249'}
    for s in audio:
        assert s['nativeDevice'] == 1002 and s['declaredJavaType'] == 'int'
        assert s['enum'] is None and s['unit'] is None and s['formula'] is None
        assert s['dedicatedTypedGetter'] is None and not s['sdkGetterIds']
        assert s['kind'] == 'vendor_diagnostic_status_not_dtc'
        assert 'zero_meaning_unproved' in s['unknownFlags'] and 'not_a_dtc_record' in s['unknownFlags']
        assert len(s['observations']) == 2 and all(o['intSigned'] == 0 and o['floatWord'] is None for o in s['observations'])
    print(json.dumps({'ok': True, 'signals': len(d['signals']), 'sources': len(manifest),
                      'validatedGetterAnchors': anchor_count,
                      'sourceFamilies': len({s['family'] for s in d['signals']}),
                      'physicalEcuBindingsAsserted': 0}, indent=2))


if __name__ == '__main__':
    main()
