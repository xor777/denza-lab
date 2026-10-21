#!/usr/bin/env python3
"""Check the retained OTA evidence offline; no device or network operations."""
import collections
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = ROOT / 'research/vehicle-diagnostics/ota-strategy-mismatch-20261021.json'


def load(relative):
    return json.loads((ROOT / relative).read_text())


def verify_refs(value):
    count = 0
    if isinstance(value, list):
        for item in value:
            count += verify_refs(item)
    elif isinstance(value, dict):
        if 'path' in value and 'sha256' in value:
            path = ROOT / value['path']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == value['sha256'], path
            count += 1
            if 'requiredTokens' in value:
                lines = path.read_text().splitlines()
                text = '\n'.join(lines[value['lineStart']-1:value['lineEnd']])
                assert all(token in text for token in value['requiredTokens']), path
        for item in value.values():
            count += verify_refs(item)
    return count


def main():
    data = json.loads(ARTIFACT.read_text())
    references = verify_refs(data)
    expected_fields = ['ecuId', 'ecuSoftCode', 'ecuSoftVer', 'ecuHardVer', 'ecuConfigCode', 'ecuConfigVer']
    for schema in data['requestSchemas']:
        lines = (ROOT / schema['path']).read_text().splitlines()
        text = '\n'.join(lines[schema['lineStart']-1:schema['lineEnd']])
        assert re.findall(r'map.put\("([^"]+)"', text) == expected_fields
        assert schema['ecuInfoFieldNames'] == expected_fields
        assert not schema['sendsLocalStrategyJson']

    projection = data['retainedResponseProjection']
    assert [row['status'] for row in projection['responses']] == ['104', '111']
    for saved in projection['responses']:
        line = (ROOT / saved['path']).read_text().splitlines()[saved['line']-1]
        reply = json.loads(line.split('responseStr :', 1)[1])
        assert reply['status'] == saved['status']
        if saved['status'] == '111':
            assert reply['cause'] == saved['cause']
            assert reply['strategy']['batchId'] == saved['batchId']
            assert not reply['strategy']['sequence']
            assert reply['strategy']['strategyVer'] is None
            assert all(reply[key] == [] and n == 0 for key, n in saved['packageCounts'].items())

    audit = data['cacheAudit']
    cached = load(audit['source']['path'])
    rows = cached['ecuInfo']
    assert len(rows) == len({row['ecuId'] for row in rows}) == audit['recordCount'] == 225
    counts = collections.Counter()
    for row in rows:
        value = row['ecuSoftCode']
        if value == '7F22':
            counts['stock_negative_marker'] += 1
        elif value == '000000000000000000':
            counts['zero_default'] += 1
        else:
            assert re.fullmatch('[0-9A-Fa-f]{18}', value)
            counts['nonzero_nine_byte_code'] += 1
    assert dict(counts) == audit['softwareCodeClassCounts']
    for key in ['sourceVersion', 'targetOrNewVersion']:
        saved = data['vehicleVersionProvenance'][key]
        assert (ROOT / saved['path']).read_text() == saved['value']
    assert cached['version'] == data['vehicleVersionProvenance']['sourceVersion']['value'] == 'V1.7.0'
    assert data['vehicleVersionProvenance']['targetOrNewVersion']['value'] == 'V1.0'
    assert not data['answer']['readingStrategyChangesAnything']
    assert data['finiteOfflineNextAnalysis']['actionsProposed'] == []
    assert not data['eligibleForProductAllowlist']
    depth = data['boundedSourceLeads']
    for version, n in depth['tupleProvenance']['retainedCacheSoftwareVersionTriggerCounts'].items():
        assert sum(row['ecuSoftVer'] == version for row in rows) == n
    print(f'PASS: {references} hashed references; three request schemas; exact104/111 projection;225 cache records; version provenance; both bounded source leads')


if __name__ == '__main__':
    main()
