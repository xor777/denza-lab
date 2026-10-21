#!/usr/bin/env python3
"""One separately authorized session for two fixed diagnostic label caches."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import secrets
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
import run_vhds_config_export as session

sys.path.insert(0, str(ROOT / 'research/vehicle-diagnostics'))
import read_diagnostic_label_cache as reader

TARGET_APKS = {
    'com.byd.spotinspection': ('/system/priv-app/SpotInspection/SpotInspection.apk', '0677ac9473557dcd760da047c2639ec9336709f1e7c12b0f9980de1988093d2b'),
    'com.byd.otaupdate': ('/system/app/OTAUpdatePlatformizedUI/OTAUpdatePlatformizedUI.apk', 'a866917a5466cc72c9fd0fda9b2d6467654529478dd1c6a5c08c3ec5d14c3752'),
}


class Device(session.Device):
    def identity(self):
        super().identity()
        for package, (path, digest) in TARGET_APKS.items():
            if self.shell('pm path ' + package) != 'package:' + path:
                raise session.lifecycle.Refused('apk_path_mismatch')
            if self.shell('sha256sum ' + path).split() != [digest, path]:
                raise session.lifecycle.Refused('apk_hash_mismatch')


def plan():
    paths = [Path(__file__).resolve(), Path(session.__file__), Path(session.lifecycle.__file__),
             Path(reader.__file__), Path(reader.prefs.__file__), Path(reader.strict.__file__), Path(reader.wire.__file__)]
    return {'schema': 'denza-lab.diagnostic-label-cache-plan.v1',
            'sourceSha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
            'files': list(reader.ALLOWED_FILES), 'keys': ['RUNNING_TASK', 'afterRunRes', 'otaUpdateDownloadFileInfo'],
            'directoryListings': 2, 'maximumDownloads': 2, 'retries': 0,
            'maxFileBytes': reader.MAX_FILE, 'readDeadlineSeconds': 20,
            'watchdogBeginsStopSeconds': 30, 'hardClosureDeadlineGuaranteed': False,
            'projectionAfterConfirmedClosureOnly': True, 'rawPreferencesSaved': False,
            'manualDatabasesIncluded': False, 'ecuRequests': 0, 'factoryRequests': 0,
            'startsInspectionOrOta': False, 'targetApks': TARGET_APKS,
            'freshOwnerApprovalRequired': True}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--preflight', action='store_true')
    modes.add_argument('--run-acknowledged', action='store_true')
    parser.add_argument('--reviewed-plan', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    if args.preflight:
        try:
            Device().preflight()
            print(json.dumps({'status': 'ready', 'exportStarted': False}))
            return 0
        except session.lifecycle.Refused as error:
            print(json.dumps({'status': 'refused', 'reason': str(error), 'exportStarted': False}))
            return 2
    if not args.run_acknowledged:
        print(json.dumps(plan(), indent=2))
        return 0
    if args.reviewed_plan is None or args.output is None:
        parser.error('execution requires a reviewed plan and a new output directory')
    reviewed = json.loads(args.reviewed_plan.read_text())
    # JSON canonicalization turns the pinned tuples into arrays for comparison.
    if reviewed != json.loads(json.dumps(plan())):
        parser.error('reviewed plan/source mismatch')
    marker = args.reviewed_plan.with_name(args.reviewed_plan.name + '.consumed')
    with marker.open('x') as handle:
        handle.write('one session consumed\n')
    args.output.mkdir(mode=0o700, parents=False, exist_ok=False)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    state = session.run(Device(), reader, secrets.token_hex(16))
    state['schema'] = 'denza-lab.diagnostic-label-cache-session.v1'
    state['startedUtc'] = started
    state['finishedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    state['plan'] = reviewed
    result_path = args.output / 'result.json'
    with result_path.open('x') as handle:
        result_path.chmod(0o600)
        json.dump(state, handle, indent=2, ensure_ascii=True)
        handle.write('\n')
    print(json.dumps({k: v for k, v in state.items() if k not in ('result', 'plan')}))
    return 0 if state['status'] == 'complete' else 2


if __name__ == '__main__':
    raise SystemExit(main())
