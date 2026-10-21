#!/usr/bin/env python3
"""Replay the two finite typed-listener captures; never contacts the vehicle."""
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'captures/washer-diagnostics-20261021/direct-listener'
REPORT = ROOT / 'research/vehicle-diagnostics/washer-direct-listener-live-20261021.json'
sys.path.insert(0, str(ROOT/'tools'))
import washer_warning_event_probe as probe


def main():
    sources = set()
    runs = []
    for number in (1, 2):
        run = BASE/f'run-{number:02}'
        build = BASE/f'build-{number:02}'
        state = json.loads((run/'run.json').read_text())
        assert state['exitCode'] == 0 and not state.get('error') and not state.get('cleanupError')
        assert all(state[k] for k in ['processAbsent','jarRemoved','healthStable'])
        assert not state['apkInstalled'] and state['vehicleWrites'] == 0
        manifest = json.loads((build/'build.json').read_text())
        assert state['sourceBuild'] == manifest
        assert probe.health.sha(build/'probe.jar') == manifest['jarSha256']
        assert probe.defined_classes(build/'probe.jar') == manifest['dexDefinitions']
        assert all(name.startswith('Ldev/denza/') for name in manifest['dexDefinitions'])
        for original, expected in manifest['sources'].items():
            archived = build/'source'/Path(original).name
            assert probe.health.sha(archived) == expected, original
            sources.add(archived)
        sources.update([run/'run.json',run/'stdout.txt',run/'stderr.txt',build/'build.json',build/'probe.jar'])
        assert not (run/'stderr.txt').read_bytes()
        healths = []
        for phase in ['preflight','postflight']:
            folder = run/phase
            status = json.loads((folder/'status.json').read_text())
            assert probe.health.acceptable(status)
            raw = {name:(folder/(name+'.txt')).read_text() for name in [*status['commands'],'app-hash']}
            decoded = probe.health.parse_health(raw)
            assert all(status[key] == value for key,value in decoded.items())
            for name in [*status['commands'],'app-hash']:
                sources.add(folder/(name+'.txt'))
            sources.add(folder/'status.json')
            healths.append(status)
        assert healths[0]['stockPids'] == healths[1]['stockPids']
        assert healths[0]['appSha256'] == healths[1]['appSha256']
        stdout = (run/'stdout.txt').read_text()
        observation = probe.parse_log(stdout)
        assert all(observation[key] == value for key,value in state['observation'].items())
        prefix = r'(?m)^\[WasherWarningEventProbe\] '
        registration = re.findall(prefix+r'REGISTER_RETURN fid=0x26100008 t_ns=(\d+)$', stdout)
        assert len(registration) == 1
        gets = re.findall(prefix+r'GET phase=(\w+) t_ns=(\d+) value=(\d+) hex=0x[0-9A-F]+$', stdout)
        ready = re.findall(prefix+r'READY doors=(true|false) t_ns=(\d+)$', stdout)
        doors = re.findall(prefix+r'DOOR_GET phase=(after_register|before_unregister) t_ns=(\d+) area=([1-4]) state=([01])$', stdout)
        if number == 2:
            assert len(ready) == 1 and ready[0][0] == 'true'
            assert [(phase,int(area)) for phase,_,area,_ in doors] == [(phase,area) for phase in ['after_register','before_unregister'] for area in range(1,5)]
        runs.append(dict(name=run.name, startedUtc=state['started'], endedUtc=state['ended'],
            durationSeconds=state['durationSeconds'], registrationReturned=True,
            registrationToEndGetSeconds=(int(gets[-1][1])-int(registration[0]))/1e9,
            baselineToRegistrationMs=(int(registration[0])-int(gets[0][1]))/1e6,
            readyOnDeviceNs=int(ready[0][1]) if ready else None,
            observation=observation,
            warningReads=[dict(phase=p,elapsedNs=int(t),value=int(v)) for p,t,v in gets],
            doorReads=[dict(phase=p,elapsedNs=int(t),area=int(a),state=int(v)) for p,t,a,v in doors],
            jarSha256=manifest['jarSha256'], appSha256=healths[0]['appSha256'],
            guardAndFirmwareMatched=True, stockPidsStable=True, crashBuffersEmpty=True,
            processAbsent=True, ownTemporaryJarRemoved=True,
            ownerGestureTiming='Owner opened/closed a door but was unsure it fell within this window.' if number==1 else 'Use timestamped door callback evidence, not an assumed user-message timestamp.'))
    sources.update([ROOT/'tools/washer_warning_event_probe.py', ROOT/'tools/test_washer_warning_event_probe.py',
                    ROOT/'research/vehicle-diagnostics/washer-direct-listener-source-20261021.json'])
    popup = ROOT/'captures/firmware/pulled-from-car-20261018/popup_nads.json'
    sources.add(popup)
    dictionary = {row['id']:row['text'] for row in json.loads(popup.read_text())['dictionary']}
    return dict(schema=1, date='2026-10-21', vehicle='Z9GT AutoType170',
        fluidContext='Owner last reported fluid present; no emptying or refill requested in these tests.',
        runs=runs,
        retainedDictionary={key:dictionary[key] for key in ['13F','7D','1DD']},
        liveQualification=dict(typedWarningCallbacksObserved=bool(runs[1]['observation']['callbacks']),
            sameDeviceDoorControlObserved=bool(runs[1]['observation']['doorCallbacks']),
            warningSequenceIncludingInitialGet=[runs[1]['observation']['getValues'][0]]+
                [row['value'] for row in runs[1]['observation']['callbacks']],
            washerTriggerQualified=False, nativeProducerIdentified=False,
            n9MissingWarningCauseEstablished=False),
        sources=[dict(path=str(p.relative_to(ROOT)),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted(sources)],
        interpretation=[
            'Registration returning void plus a running Looper is not a native enable ACK or proof that onTextInfoChanged delivered.',
            'Door callbacks are a same-device scalar-listener control, not a substitute for an actual selected-warning transition.',
            'A zero selected warning does not establish fluid OK, and no callback does not establish the car never forms1DD.',
            'GETs during door callbacks are separately timestamped cache reads, not atomic CAN snapshots or a complete warning history.',
            'These temporary shell-UID probes do not qualify ordinary app-UID access, background/reconnect reliability, or low-fluid delivery.',
            'No physical low-fluid1DD test was made. No application notification was installed or left running.'
        ])


if __name__ == '__main__':
    result = main()
    if sys.argv[1:] == ['--write']:
        REPORT.write_text(json.dumps(result, indent=2)+'\n')
    else:
        assert not sys.argv[1:]
        assert result == json.loads(REPORT.read_text()), 'report differs from retained captures'
    print(json.dumps(dict(ok=True,runs=[dict(name=r['name'],warningCallbacks=r['observation']['callbackCount'],
        doorCallbacks=len(r['observation']['doorCallbacks']),warningGetValues=r['observation']['getValues']) for r in result['runs']],sources=len(result['sources']))))
