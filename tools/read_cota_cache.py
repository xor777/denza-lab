#!/usr/bin/env python3
"""Read six fixed existing Cota JSON candidates in one owner-approved export.

Uses only the stock exporter lifecycle; never starts Cota, asks for updates,
calls yqzx_service, changes settings, or queries an external server. Two possible
root partitions, each with cloud_config + global parameters + Launchermap
parameters. Missing files are reported. No arbitrary path or extra package.
"""
import argparse
import hashlib
import json
from pathlib import Path

import ads_ota_metadata as common


def read(out, owner_authorized=False):
    if owner_authorized is not True:
        raise common.ERROR('fresh_owner_authorization_required')
    out = Path(out)
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    data = common.export(out, profile='cota-cache')
    records = []
    for index, path in enumerate(common.COTA_FILES):
        raw = data[path]
        if not raw:
            records.append({'path': path, 'status': 'missing_or_empty'})
            continue
        # Do not include response content, parameter values or parse errors in stdout.
        row = {'path': path, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
        try:
            doc = json.loads(raw)
            row['jsonType'] = type(doc).__name__
        except (ValueError, UnicodeError):
            row['jsonType'] = 'invalid'
        name = str(index) + '-' + ('cloud-config.json' if path.endswith('/cloud_config.json')
                                  else 'global-params.json' if 'globalapp/' in path else 'launchermap-params.json')
        common.wire._save_new(out / name, raw)
        row.update(status='saved', local=name)
        records.append(row)
    common.save(out, 'files.json', records)
    print(json.dumps({'savedFiles': sum(r['status'] == 'saved' for r in records),
                      'cloudRequests': 0, 'diagnosticRequests': 0}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--owner-authorized', action='store_true')
    args = parser.parse_args()
    read(args.out, args.owner_authorized)
