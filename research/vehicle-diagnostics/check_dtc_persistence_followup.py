#!/usr/bin/env python3
"""Replay source and DEX-byte evidence; local read-only, no firmware execution."""
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name('dtc-persistence-followup-20261021.json')


def main():
    checks = 0

    def check(condition, label):
        nonlocal checks
        assert condition, label
        checks += 1

    def verified(item):
        raw = (ROOT / item['path']).read_bytes()
        check(len(raw) == item['bytes'], item['path'] + ' bytes')
        check(hashlib.sha256(raw).hexdigest() == item['sha256'], item['path'] + ' hash')
        return raw

    report = json.loads(REPORT.read_text())
    check(all(x == 0 for x in report['actions'].values()), 'offline only')
    result = report['result']
    for key in ('newQualifiedOemDefinitions', 'newQualifiedWarningDtcLinks', 'newQualifiedFormat01Mappings'):
        check(result[key] == 0, key)
    check(result['selectedScriptTuple'] is None, 'no selected tuple')
    for key in ('applicableOriginalScriptRecovered', 'actualAfterRunResultRecovered', 'actualManualDatabaseRecovered'):
        check(result[key] is False, key)
    evidence = {k: verified(v) for k, v in report['evidence'].items()}
    prior = {x['path']: verified(x) for x in report['priorEvidence']}
    sources = {}
    for key, item in json.loads(evidence['sourceAnchors']).items():
        raw = verified(item)
        sources[key] = raw.decode()
        lines = sources[key].splitlines(keepends=True)
        for span in item['ranges']:
            selected = ''.join(lines[span['start'] - 1:span['end']])
            check(selected == span['text'], key + ' exact range')
            check(hashlib.sha256(selected.encode()).hexdigest() == span['sha256'], key + ' range hash')
    check(len(sources) == report['verification']['javaAndManifestSources'] == 23, 'sources')
    anchors = {
        'preferences': ['createDeviceProtectedStorageContext().getSharedPreferences(context.getPackageName(), 0)'],
        'task': ['private DownloadCommand downloadCommand;', 'JSON.toJSONString(this.taskInfo)', 'RUNNING_TASK'],
        'download': ['private List<ScriptRequest> scriptList;', 'private String token;'],
        'result_model': ['private transient RunCommandData runCommandData;', 'private List<SystemResult> systemResultList;', 'private String itemDesc;', 'private String name;'],
        'dtc_lookup': ['Objects.equals(Long.valueOf(dtc.getCode()), Long.valueOf(jLongValue))', '((Attach.Dtc) obj).getDesc()', 'findFirst().orElse("")', 'String.format("%s - %s", str3, str2)', 'MatchType.MATCH_WHITES'],
        'result_builder': ['systemStatus.getStatus() != CommandStatusEnum.SUCCESS', 'caseStatus.getCode() != 1000', 'setName(systemStatus.getSystemId()).setItemDesc((String) obj)', 'getAfterDoneEnterSuperLowPower()', 'CommandStatusEnum.STRATEGY_FAIL', 'ScriptResultDao.getInstance().save(runResultModelData);'],
        'result_storage': ['AFTER_RUN_RES = "afterRunRes"', 'saveString(AFTER_RUN_RES, JSON.toJSONString(runResultModelData))'],
        'result_restore': ['getShowTime() >= 3', 'ScriptResultDao.getInstance().clear()'],
        'result_close': ['ScriptResultDao.getInstance().clear();\n        finish();'],
        'manual_writer': ['contentValues.put(ClassifyDbUtil.TableClassify.COLUMN_HEADER, zipInfo.getZipUploadTime())', 'contentValues.put(ClassifyDbUtil.TableClassify.COLUMN_COVER_PATH, zipInfo.getZipUrl())', 'contentValues.put(ClassifyDbUtil.TableClassify.COLUMN_FOOTER, zipInfo.getZipSize())', 'contentValues.put(ClassifyDbUtil.TableClassify.COLUMN_PATH, zipInfo.getTargetFile())', 'contentValues.put(ClassifyDbUtil.TableClassify.COLUMN_RESERVE_1, zipInfo.getServerFilepath())'],
        'manual_schema': ['DB_NAME = "classify_cache.db"', '_id_id integer primary key autoincrement', 'getWritableDatabase()', 'Method not decompiled:'],
        'media_schema': ['DB_NAME = "media_cache.db"', 'local_path text not null, url text not null, state integer, current integer, file_size integer, time text', '"url=?"'],
        'media_state': ['STATE_DONE = 1', 'STATE_LOADING = 0'],
        'manual_context': ['context = getApplicationContext()'],
        'manual_locator': ['replaceAll("[^\\\\w]", "")', 'mediaCache.getState() == 1', 'getLocalFile(mediaCache).exists()', 'startDownLoad(str, current, 0, localPath, iDownloadListener)'],
        'manual_cache_dir': ['externalCacheDir == null', 'externalCacheDir.exists()', 'context.getCacheDir()'],
        'manual_log': ['TAG = "UserManual"', 'Log.d(TAG, getMsg(str, str2))'],
        'eol_log': ['import android.util.Log;', 'Log.d(TAG + str, str2)'],
    }
    for key, values in anchors.items():
        for value in values:
            check(value in sources[key], key + ': ' + value)
    check('defaultToDeviceProtectedStorage' not in sources['manual_manifest'], 'ordinary manifest context')
    bytecode = json.loads(evidence['bytecode'])
    methods = {}
    summary = []
    instruction_lines = 0
    for package in bytecode['packages']:
        verified(package)
        verified(package['disassembly'])
        with zipfile.ZipFile(ROOT / package['path']) as apk:
            dex = {}
            for member in package['dexMembers']:
                data = apk.read(member['member'])
                check(len(data) == member['bytes'], 'DEX bytes')
                check(hashlib.sha256(data).hexdigest() == member['sha256'], 'DEX hash')
                dex[member['member']] = data
        for method in package['methods']:
            data = dex[method['dexMember']]
            offset = int(method['codeItemFileOffset'], 16)
            start = int(method['instructionsFileOffset'], 16)
            check(start == offset + 16, 'DEX code_item header')
            size = struct.unpack_from('<I', data, offset + 12)[0]
            check(size == method['instructionUnits'], 'DEX instruction length')
            check(hashlib.sha256(data[start:start + size * 2]).hexdigest() == method['instructionsSha256'], 'whole method bytes')
            header = re.search(r'^([0-9a-f]+):\s+\|\[([0-9a-f]+)\]', method['text'], re.M)
            check(int(header[1], 16) == int(header[2], 16) == offset, 'printed file offset')
            count = 0
            for line in method['text'].splitlines():
                match = re.match(r'^([0-9a-f]+): ((?:[0-9a-f]{4} ?)+) +\|', line)
                if match:
                    pos = int(match[1], 16)
                    raw = bytes.fromhex(match[2])
                    check(start <= pos and pos + len(raw) <= start + size * 2, 'instruction bounds')
                    check(data[pos:pos + len(raw)] == raw, method['method'] + ' instruction bytes')
                    count += 1
            check(count == method['verifiedInstructionLines'], 'instruction line count')
            instruction_lines += count
            key = method['class'] + '#' + method['method'] if method['method'] in ('apply', 'test') else method['method']
            methods[key] = method['text']
            summary.append({'package': package['path'], **{k: v for k, v in method.items() if k != 'text'}})
    check(summary == report['verification']['methods'], 'exact report method evidence')
    check(len(methods) == report['verification']['dexMethods'] == 11, 'method count')
    check(instruction_lines == report['verification']['rawInstructionLines'], 'instruction total')
    for value in ('"vin = ? and page = ?"', '"header"', '"coverPath"', '"footer"', '"path"', '"reserve_one"', '"_id_id"', '.MANUAL:', '.SANBAO:', '.setZipUploadTime:', '.setZipUrl:', '.setZipSize:', '.setTargetFile:', '.setServerFilepath:'):
        check(value in methods['queryByPage'], 'actual query reader: ' + value)
    for value in ('#int -3', '.getAbsolutePath:', '.substring:(II)'):
        check(value in methods['getUnZipFolder'], 'archive directory: ' + value)
    for value in ('.getTroubleCodes:', '"%s - %s"', 'MatchUtils$$ExternalSyntheticLambda16;', 'MatchUtils$$ExternalSyntheticLambda14;', 'MatchUtils$$ExternalSyntheticLambda3;'):
        check(value in methods['lambda$log1902$9'], 'actual result label chain: ' + value)
    prefix = 'Lcom/byd/spotinspection/eol/executor/util/MatchUtils$$ExternalSyntheticLambda'
    check('.getDtcList:' in methods[prefix + '16;#apply'], 'actual dictionary accessor')
    check('.getDesc:' in methods[prefix + '14;#apply'], 'actual description accessor')
    check('.lambda$log1902$7:' in methods[prefix + '3;#test'], 'actual numeric predicate dispatch')
    for value in ('.getCode:', 'Ljava/util/Objects;.equals:'):
        check(value in methods['lambda$log1902$7'], 'exact numeric predicate: ' + value)
    for value in ('.getSystemId:', '.setName:', '.setItemDesc:'):
        check(value in methods['lambda$complete$2'], 'actual persistent label: ' + value)
    logs = json.loads(evidence['manualLogs'])
    check(len(logs['files']) == report['verification']['historicalLogFiles'] == 4, 'log count')
    # Pin the retained logs and repeat only the new marker search, streaming chunks.
    # There were zero matches; presence testing suffices without reprinting lines.
    for item in logs['files']:
        hasher = hashlib.sha256()
        size = 0
        newline_count = 0
        markers = [x.encode() for x in item['markerCounts']]
        check(all(v == 0 for v in item['markerCounts'].values()), 'negative marker projection')
        tail = b''
        last = b''
        with (ROOT / item['path']).open('rb') as handle:
            while True:
                chunk = handle.read(1024 * 1024)
                if not chunk:
                    break
                hasher.update(chunk)
                size += len(chunk)
                newline_count += chunk.count(b'\n')
                last = chunk[-1:]
                check(not any(m in tail + chunk for m in markers), 'no manual marker')
                tail = chunk[-128:]
        check(hasher.hexdigest() == item['sha256'] and size == item['bytes'], 'log identity')
        check(newline_count + int(bool(size) and last != b'\n') == item['lines'], 'log line count')
    check(sum(x['bytes'] for x in logs['files']) == report['verification']['historicalLogBytes'], 'log total bytes')
    check(sum(x['lines'] for x in logs['files']) == report['verification']['historicalLogLines'], 'log total lines')
    check(json.loads(evidence['cacheNames'])['matchingFiles'] == [], 'bounded historical filename audit')
    receipt = json.loads(prior['captures/diagnostics-discovery-20261018/approved-export-2/files/report.json'])
    task = next(x for x in receipt['files'] if x.get('sourceKey') == 'RUNNING_TASK')
    check(task['status'] == 'key_missing' and task['rawSizeBytes'] == 65, 'historical selected-key result')
    check(not any(x.get('sourceKey') == 'afterRunRes' for x in receipt['files']), 'afterRunRes was not queried')
    print(json.dumps({'status': 'PASS', 'checks': checks, 'sources': len(sources),
                      'dexMethods': len(methods), 'verifiedInstructionLines': instruction_lines,
                      'historicalLogFiles': 4, 'qualifiedDefinitions': 0,
                      'vehicleOperations': 0, 'networkOperations': 0}, indent=2))


if __name__ == '__main__':
    main()
