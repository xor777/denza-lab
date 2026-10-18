#!/usr/bin/env python3
"""Fixed ADS metadata read through an already-open, separately approved export.

Never starts a service, invokes ADB, downloads firmware, writes to the car or
queries an ECU/cloud endpoint. Raw preference XML stays in memory. URLs and
package decoding data, if present, stay in private local capture files.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import socket
import struct
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'research' / 'vehicle-diagnostics'))
import read_ecu_cache_export as wire
import read_ota_inventory_export as inventory

ECU_ID = 'HTH-2021_ADC_BYD_00100001'
SOFT_CODE = '48450011DF83000000'
VERSION = '4.14.07'
FILES = (
    '/data/user/0/com.byd.otaupdate/shared_prefs/download_info_prefs.xml',
    '/data/user/0/com.byd.otaupdate2/shared_prefs/download_info_prefs.xml',
    '/data/user/0/com.byd.productlineota/shared_prefs/download_info_prefs.xml',
    '/data/user/0/com.byd.productlineota/shared_prefs/share_data_prefs.xml',
    '/data/ota_package/ota_productline/result/strategy.json',
    '/data/ota_package/ota_ghy/result/upgradeInfo.json',
)
PACKAGE_DIRS = tuple('/data/ota_package/' + variant + '/package/vehicle/' + side
                    for variant in ('ota', 'ota_ghy', 'ota_productline')
                    for side in ('sourceECU', 'targetECU'))
DIRECTORIES = frozenset(str(PurePosixPath(p).parent) for p in FILES) | frozenset(PACKAGE_DIRS)
DOWNLOAD_FIELDS = ('fileName', 'fileSize', 'fileDir', 'status')
PACKAGE_FIELDS = ('ecuId', 'ecuSoftCode', 'ecuSoftVer', 'ecuHardVer', 'ecuConfigCode',
                  'ecuConfigVer', 'versionPath', 'fileSize', 'encryptMd5', 'decryMode',
                  'secretHeadData', 'secretIv', 'secretKey', 'ecuUpgradePart')
ROUTE_FIELDS = inventory.ECU_FIELDS + ('ecuIp', 'ecuPort')
Error = wire.ExportError


def request(action, path):
    if type(action) is not int or path not in (FILES if action == 1 else DIRECTORIES if action == 2 else ()):
        raise Error('request_not_allowed')
    key = 'filePath' if action == 1 else 'directory'
    payload = json.dumps({key: wire.EXPORT_PREFIX + path.lstrip('/')}, separators=(',', ':')).encode()
    return struct.pack('>III', wire.MAGIC, action, len(payload)) + payload


def exchange(endpoint, action, path, deadline, size=None):
    packet = request(action, path)
    try:
        with socket.create_connection(endpoint, timeout=deadline.timeout()) as sock:
            sock.settimeout(deadline.timeout())
            sock.sendall(packet)
            doc = wire._envelope(sock, deadline)
            if action == 2:
                rows = wire._list(doc)
                wire._eof(sock, deadline)
                return rows
            return wire._download(doc, PurePosixPath(path).name, size, sock, deadline)
    except socket.timeout:
        raise Error('socket_timeout') from None
    except OSError:
        raise Error('socket_error') from None


def fields(row, allowed):
    if type(row) is not dict:
        raise Error('invalid_metadata_row')
    result = {}
    for key in allowed:
        if key in row:
            value = row[key]
            if value is not None and type(value) is not str:
                raise Error('invalid_metadata_field')
            if isinstance(value, str) and len(value) > 16384:
                raise Error('metadata_field_too_long')
            result[key] = value
    return result


def rows(value):
    if value is None:
        return []
    if type(value) is not list or len(value) > 512 or any(type(r) is not dict for r in value):
        raise Error('invalid_metadata_rows')
    return value


def download_preferences(raw):
    try:
        text = raw.decode('utf-8-sig')
        if '<!' in text:
            raise Error('xml_declaration_forbidden')
        root = ET.fromstring(text)
    except (UnicodeError, ET.ParseError):
        raise Error('invalid_preferences_xml') from None
    if root.tag != 'map' or root.attrib or len(root) > 512:
        raise Error('invalid_preferences_xml')
    selected, seen = [], set()
    for child in root:
        name = child.get('name')
        if not name or name in seen:
            raise Error('duplicate_or_missing_preference_key')
        seen.add(name)
        if not name.startswith('DOWNLOAD_FILE_INFO_'):
            continue
        if child.tag != 'string' or len(child) or set(child.attrib) != {'name'}:
            raise Error('invalid_download_preference')
        value = inventory._json(child.text or '')
        item = fields(value, DOWNLOAD_FIELDS)
        url = value.get('fileUrl')
        if url is not None and (type(url) is not str or len(url) > 16384):
            raise Error('invalid_download_url')
        # Preserve a URL only when the record explicitly names the observed ADS
        # software code. Other entries retain filename/size/state only.
        match = SOFT_CODE in (item.get('fileName') or '') or SOFT_CODE in (url or '')
        item['matchesAdsSoftwareCode'] = match
        if match and url:
            item['fileUrl'] = url
        selected.append(item)
    return {'downloadRecords': selected}


def project(path, raw):
    if path not in FILES or len(raw) > wire.MAX_FILE:
        raise Error('source_not_allowed')
    result = {'source': path, 'rawBytes': len(raw), 'rawSha256': hashlib.sha256(raw).hexdigest()}
    if path.endswith('/download_info_prefs.xml'):
        result.update(download_preferences(raw))
    elif path.endswith('/share_data_prefs.xml'):
        text = inventory._preference(raw, inventory.PREF_KEY)
        result['keyPresent'] = text is not None
        result['adsRoutes'] = [fields(r, ROUTE_FIELDS) for r in rows(inventory._json(text) if text and text.strip() else None)
                               if r.get('ecuId') == ECU_ID]
    else:
        doc = inventory._json(raw)
        if type(doc) is not dict:
            raise Error('invalid_strategy_schema')
        if path.endswith('/upgradeInfo.json'):
            result['adsRoutes'] = [fields(r, ROUTE_FIELDS) for r in rows(doc.get('ecuModel')) if r.get('ecuId') == ECU_ID]
        else:
            strategy = doc.get('strategy')
            if strategy is not None and type(strategy) is not dict:
                raise Error('invalid_strategy_schema')
            result['adsRoutes'] = [fields(r, ROUTE_FIELDS) for r in rows((strategy or {}).get('sequence')) if r.get('ecuId') == ECU_ID]
            for key in ('sourcePackageInfo', 'targetPackageInfo', 'configSourceInfo', 'configPackageInfo'):
                result[key] = [fields(r, PACKAGE_FIELDS) for r in rows(doc.get(key)) if r.get('ecuId') == ECU_ID]
    return result


def run(port, output):
    endpoint = wire._endpoint('127.0.0.1', port)
    output = Path(output)
    try:
        output.mkdir(mode=0o700)
    except FileExistsError:
        raise Error('output_already_exists') from None
    deadline = wire.Deadline()
    report = {'status': 'complete', 'error': None, 'targetEcuId': ECU_ID, 'cachedSoftCode': SOFT_CODE,
              'version': VERSION, 'files': [], 'packageDirectories': [],
              'limits': {'fileBytes': wire.MAX_FILE, 'jsonBytes': wire.MAX_JSON, 'totalSeconds': wire.TOTAL_TIMEOUT},
              'rawPreferencesSaved': False, 'firmwareDownloaded': False}
    listings = {}
    try:
        for index, path in enumerate(FILES):
            parent, name = str(PurePosixPath(path).parent), PurePosixPath(path).name
            row = {'path': path, 'status': 'not_attempted'}
            report['files'].append(row)
            if parent not in listings:
                try:
                    listings[parent] = exchange(endpoint, 2, parent, deadline)
                except wire.MissingFile:
                    listings[parent] = {}
            size = listings[parent].get(name)
            if size is None:
                row['status'] = 'missing'
                continue
            if size > wire.MAX_FILE:
                raise Error('file_size_out_of_bounds')
            raw = exchange(endpoint, 1, path, deadline, size)
            projection = project(path, raw)
            del raw
            deadline.timeout()
            filename = '%02d-metadata.json' % (index + 1)
            data = (json.dumps(projection, ensure_ascii=True, indent=2) + '\n').encode()
            wire._save_new(output / filename, data)
            row.update(status='projected', savedAs=filename, sha256=hashlib.sha256(data).hexdigest())
        for path in PACKAGE_DIRS:
            row = {'path': path, 'status': 'not_attempted'}
            report['packageDirectories'].append(row)
            try:
                entries = exchange(endpoint, 2, path, deadline)
                row.update(status='listed', entryCount=len(entries),
                           adsSoftwareMatches=[{'name': name, 'bytes': size} for name, size in entries.items() if SOFT_CODE in name],
                           packageNames=[{'name': name, 'bytes': size} for name, size in entries.items() if name.lower().endswith(('.zip', '.bin', '.enc', '.xcd'))])
            except wire.MissingFile:
                row['status'] = 'missing'
        deadline.timeout()
    except (Error, OSError) as error:
        report.update(status='aborted', error=str(error) if isinstance(error, Error) else 'local_io_error')
    wire._save_new(output / 'report.json', (json.dumps(report, indent=2) + '\n').encode())
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        report = run(args.port, args.output)
        print(json.dumps({'status': report['status'], 'error': report['error'], 'filesAttempted': len(report['files'])}))
        return 0 if report['status'] == 'complete' else 1
    except (Error, OSError) as error:
        print(json.dumps({'status': 'aborted', 'error': str(error) if isinstance(error, Error) else 'local_io_error'}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
