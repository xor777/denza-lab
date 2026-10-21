#!/usr/bin/env python3
"""Project two fixed preference files after an authorized export is closed.

No service/ADB/ECU/HTTP calls, writes or arbitrary-path option. Raw XML stays in
memory. The caller owns BydLogTool authorization, lifecycle and output storage.
The manual app's private SQLite databases are deliberately outside this plan.
"""
import hashlib
import json
from pathlib import PurePosixPath
import socket
import struct

import read_ecu_cache_export as wire
import read_ota_inventory_export as prefs
import read_vhds_config_export as strict

SPOT_FILE = '/data/user_de/0/com.byd.spotinspection/shared_prefs/com.byd.spotinspection.xml'
OTA_FILE = '/data/user/0/com.byd.otaupdate/shared_prefs/share_data_prefs.xml'
ALLOWED_FILES = (SPOT_FILE, OTA_FILE)
ALLOWED_DIRECTORIES = frozenset(str(PurePosixPath(p).parent) for p in ALLOWED_FILES)
MAX_FILE = 1024 * 1024
MAX_ROWS = 512
MAX_TEXT = 1024
SCRIPT_FIELDS = ('scriptCode', 'scriptVersion', 'scriptFormatVer', 'forStation', 'scriptName', 'funcType')
PACKAGE_FIELDS = ('ecuId', 'ecuDescription', 'ecuSoftCode', 'ecuSoftVer', 'ecuHardVer', 'ecuUpgradePart')
ExportError = wire.ExportError


def _request(action, path):
    allowed = ALLOWED_FILES if action == 1 else ALLOWED_DIRECTORIES if action == 2 else ()
    if type(action) is not int or type(path) is not str or path not in allowed:
        raise ExportError('request_not_allowed')
    key = 'filePath' if action == 1 else 'directory'
    data = json.dumps({key: wire.EXPORT_PREFIX + path.lstrip('/')}, separators=(',', ':')).encode()
    return struct.pack('>III', wire.MAGIC, action, len(data)) + data


def _exchange(endpoint, action, path, deadline, size=None):
    request = _request(action, path)
    endpoint = strict._endpoint(*endpoint)
    try:
        with socket.create_connection(endpoint, timeout=deadline.timeout()) as sock:
            sock.settimeout(deadline.timeout())
            sock.sendall(request)
            deadline.timeout()
            envelope = strict._envelope(sock, deadline)
            if action == 2:
                result = wire._list(envelope)
                wire._eof(sock, deadline)
                return result
            return wire._download(envelope, PurePosixPath(path).name, size, sock, deadline)
    except socket.timeout:
        raise ExportError('socket_timeout') from None
    except OSError:
        raise ExportError('socket_error') from None


def read_config(host, port):
    """Compatibility with the existing close-before-project lifecycle wrapper.

One listing and at most one download per exact file,20s total and no retry.
The in-memory object is not a projection and must never be serialized.
"""
    endpoint = strict._endpoint(host, port)
    deadline = wire.Deadline()
    result = {}
    for path in ALLOWED_FILES:
        directory, name = str(PurePosixPath(path).parent), PurePosixPath(path).name
        try:
            listing = _exchange(endpoint, 2, directory, deadline)
        except wire.MissingFile:
            result[path] = None
            continue
        if name not in listing:
            result[path] = None
            continue
        if listing[name] > MAX_FILE:
            raise ExportError('file_size_out_of_bounds')
        try:
            result[path] = _exchange(endpoint, 1, path, deadline, listing[name])
        except wire.MissingFile:
            result[path] = None
        deadline.timeout()
    deadline.timeout()
    return result


def _text(value):
    if value is None:
        return None
    if type(value) is not str or len(value) > MAX_TEXT or any(ord(c) < 32 for c in value):
        raise ExportError('invalid_selected_text')
    value.encode('utf-8')
    return value


def _fields(obj, fields):
    if type(obj) is not dict:
        raise ExportError('invalid_selected_object')
    return {key: _text(obj[key]) for key in fields if key in obj}


def _rows(value, fields):
    if type(value) is not list or len(value) > MAX_ROWS:
        raise ExportError('invalid_selected_rows')
    return [_fields(row, fields) for row in value]


def _integer(value):
    if type(value) is not int or not 0 <= value <= 2**31 - 1:
        raise ExportError('invalid_selected_integer')
    return value


def _selected(raw, key):
    value = prefs._preference(raw, key)
    if value is None:
        return {'status': 'key_missing'}, None
    if not value.strip():
        return {'status': 'key_no_data'}, None
    obj = strict._json(value.encode('utf-8'))
    if obj is None:
        return {'status': 'key_no_data'}, None
    if type(obj) is not dict:
        raise ExportError('invalid_selected_object')
    return {'status': 'projected'}, obj


def _spot(raw):
    result = {}
    state, obj = _selected(raw, 'RUNNING_TASK')
    if obj is not None:
        download = obj.get('downloadCommand')
        if type(download) is not dict:
            raise ExportError('invalid_running_task')
        state['scripts'] = _rows(download.get('scriptList'), SCRIPT_FIELDS)
        if any(type(row.get('scriptCode')) is not str or not row['scriptCode'] for row in state['scripts']):
            raise ExportError('invalid_script_identity')
    result['RUNNING_TASK'] = state
    state, obj = _selected(raw, 'afterRunRes')
    if obj is not None:
        state['value'] = _fields(obj, ('commandStatus',))
        for key in ('finish', 'total', 'showTime'):
            if key in obj:
                state['value'][key] = _integer(obj[key])
        if obj.get('systemResultList') is not None:
            rows = _rows(obj['systemResultList'], ('name', 'itemDesc'))
            if any(set(row) != {'name', 'itemDesc'} for row in rows):
                raise ExportError('invalid_result_row')
            state['value']['systemResultList'] = rows
        state['interpretation'] = 'historical_display_subset; generic_case_names_possible; no_current_fault_or_ECU_software_binding'
    result['afterRunRes'] = state
    return result


def _ota(raw):
    state, obj = _selected(raw, 'otaUpdateDownloadFileInfo')
    if obj is not None:
        state['value'] = _fields(obj, PACKAGE_FIELDS)
        if type(state['value'].get('ecuId')) is not str or not state['value']['ecuId']:
            raise ExportError('invalid_package_identity')
        if 'isSourcePackage' in obj:
            if type(obj['isSourcePackage']) is not bool:
                raise ExportError('invalid_source_package_flag')
            state['value']['isSourcePackage'] = obj['isSourcePackage']
        state['interpretation'] = 'download_record_only; installed_software_and_child_DID_applicability_unproved'
    return {'otaUpdateDownloadFileInfo': state}


def project_config(raw):
    """Only the fixed field projection may leave the closed session."""
    if type(raw) is not dict or set(raw) != set(ALLOWED_FILES):
        raise ExportError('invalid_source_set')
    rows = []
    for path in ALLOWED_FILES:
        data = raw[path]
        if data is None:
            rows.append({'source': path, 'status': 'not_returned', 'exists': None})
            continue
        if type(data) is not bytes or len(data) > MAX_FILE:
            raise ExportError('file_size_out_of_bounds')
        selected = _spot(data) if path == SPOT_FILE else _ota(data)
        rows.append({'source': path, 'status': 'projected', 'rawSizeBytes': len(data),
                     'rawSha256': hashlib.sha256(data).hexdigest(), 'selected': selected})
    return {'schema': 'denza-lab.diagnostic-label-cache-projection.v1', 'files': rows,
            'qualifiedDtcDefinitions': 0, 'qualifiedChildRoles': 0}
