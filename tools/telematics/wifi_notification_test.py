#!/usr/bin/env python3
"""One owner-authorized Denza diagnostic. Changes APN policy; see saved plan.

No installation, Wi-Fi toggle, router operation, fabricated APN1 state or
manual telemetry publication. Run only against the already trusted transport.
"""
import argparse
import datetime
import fcntl
import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading
import time

ADB = ['adb', '-s', '127.0.0.1:5555']
KEYS = ['persist.sys.byd.apn_type', 'ro.build.byd.apn_type',
        'persist.radio.net.lte.apn1.disable', 'persist.sys.byd.default_data_slot',
        'net.lte.apn1.state', 'net.lte.apn1.real_state', 'net.lte.apn1.cid',
        'net.lte.apn1.ifname', 'net.lte.apn1.ip', 'net.lte.apn3.state',
        'net.lte.apn3.ifname', 'net.apn3.wifi', 'persist.sys.cloud.app_reg_status',
        'persist.sys.cloud.token_flag', 'sys.cloudserviceapp.mqtt', 'sys.cloud_domin_ip']


def now():
    return datetime.datetime.now().astimezone().isoformat(timespec='milliseconds')


def run(args, timeout=12):
    p = subprocess.run(ADB + args, capture_output=True, text=True,
                       errors='replace', timeout=timeout)
    return dict(returncode=p.returncode, stdout=p.stdout.strip(), stderr=p.stderr.strip())


def save(out, name, data):
    (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def event(out, text):
    line = now() + ' ' + text
    print(line, flush=True)
    with (out / 'events.txt').open('a') as f:
        f.write(line + '\n')


def properties():
    command = '; '.join('printf "%s=" ' + k + '; getprop ' + k for k in KEYS)
    r = run(['shell', command])
    if r['returncode']:
        raise RuntimeError('Property read failed')
    values = dict(line.split('=', 1) for line in r['stdout'].splitlines() if '=' in line)
    if set(values) != set(KEYS):
        raise RuntimeError('Incomplete property read')
    return values


def wifi():
    r = run(['shell', 'dumpsys', 'connectivity'])
    text = r['stdout']
    m = re.search(r'Active default network:\s*(\d+)', text)
    network = m.group(1) if m else None
    lines = [l for l in text.splitlines() if 'NetworkAgentInfo' in l
             and network and 'network{' + network + '}' in l]
    return {'network': network, 'validated_wifi': r['returncode'] == 0 and any(
        'WIFI CONNECTED' in l and 'IS_VALIDATED' in l for l in lines)}


def snapshot(out, name):
    result = dict(time=now(), properties=properties(), wifi=wifi(),
                  cloud=run(['shell', 'service', 'call', 'cloudmanager', '7']),
                  mqtt=run(['shell', 'service', 'call', 'mqttserv', '8']),
                  adb=run(['get-state']))
    save(out, name + '.json', result)
    event(out, name + ' ' + json.dumps({
        'profile': result['properties'][KEYS[0]], 'wifi': result['wifi'],
        'cloud': result['cloud']['stdout'], 'mqtt': result['mqtt']['stdout'],
        'registration': result['properties']['persist.sys.cloud.app_reg_status'],
        'token_flag': result['properties']['persist.sys.cloud.token_flag']}))
    return result


def switch(profile):
    return run(['shell', 'am', 'broadcast', '--user', '0', '-a',
                'com.byd.action.RADIO_CONFIG', '-p', 'com.android.phone',
                '-f', '0x01000000', '--es', 'opt_name', 'set_default_data',
                '--es', 'apn_type', profile])


def restore(out, actor):
    # Main and deadline guard share the lock, so only one performs restoration.
    with (out / 'restore.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if (out / 'restored.json').exists():
            return
        current = properties()
        if current[KEYS[0]] != 'triple_apn' or current[KEYS[2]] != '0':
            event(out, actor + ': restoring triple_apn')
            save(out, actor + '-restore-command.json', switch('triple_apn'))
            time.sleep(3)
        final = snapshot(out, actor + '-after-restore')
        baseline = json.loads((out / 'before.json').read_text())['properties']
        difference = {k: [baseline[k], final['properties'][k]] for k in KEYS
                      if baseline[k] != final['properties'][k]}
        ok = (final['properties'][KEYS[0]] == 'triple_apn'
              and final['properties'][KEYS[2]] == '0'
              and final['adb']['stdout'] == 'device' and final['wifi']['validated_wifi'])
        save(out, actor + '-restoration-check.json', dict(operational_restore=ok,
                                                       property_differences=difference))
        event(out, actor + ': operational restore=' + str(ok)
              + ', property differences=' + json.dumps(difference))
        if ok:
            save(out, 'restored.json', dict(time=now(), actor=actor))
        else:
            raise RuntimeError('Restoration is NOT confirmed')


def guard(out, delay):
    # Some macOS Python versions have a process-relative monotonic origin.
    # Pass a duration across the process boundary, never an absolute reading.
    deadline = time.monotonic() + delay
    while time.monotonic() < deadline:
        if (out / 'restored.json').exists():
            return
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    restore(out, 'deadline-guard')


def reader(process, target):
    allow = re.compile(r'notify_nw|netStatus|apn|domain|domian|registDomain|addrDomain|'
                       r'tcpReconnect|send211|keepalive|retry_count|clear542Cfg|'
                       r'addr =|getSSLIP|connect|RADIO_CONFIG|set_default_data', re.I)
    private = re.compile(r'\b(?:vin|guid|imei|imsi|iccid|password|secret|signature|'
                         r'latitude|longitude|payload|packet|encrypted|encrypt|decrypt|'
                         r'bytes|key)\b|token|(?:send|recv)\s*data', re.I)
    with target.open('w') as f:
        for line in process.stdout:
            m = re.match(r'^(\S+\s+\S+\s+\d+\s+\d+\s+\w\s+[^:]+:)(.*)$', line)
            if not m or not allow.search(m[2]):
                continue
            head, body = m.groups()
            if private.search(body) or '{' in body:
                body = ' [sensitive content omitted]'
            elif re.search(r'(?:\b[0-9A-Fa-f]{2}[ ,]){8,}', body):
                body = ' [raw payload omitted]'
            body = re.sub(r'\b[A-HJ-NPR-Z0-9]{17}\b', '<vehicle-id-redacted>', body)
            body = re.sub(r'\b[0-9A-Fa-f]{32,}\b', '<hex-redacted>', body)
            f.write(head + ''.join(c if c.isprintable() else '?' for c in body) + '\n')
            f.flush()


def main(out):
    if out.exists():
        raise RuntimeError('Refusing to overwrite an existing test directory')
    out.mkdir(parents=True)
    before = snapshot(out, 'before')
    if not (before['properties'][KEYS[0]] == 'triple_apn'
            and before['properties'][KEYS[2]] == '0'
            and before['adb']['stdout'] == 'device'
            and before['wifi']['validated_wifi']
            and all('Parcel(00000000 00000000' in before[k]['stdout'] for k in ['cloud', 'mqtt'])):
        raise RuntimeError('Baseline differs: no change performed')
    pid = run(['shell', 'pidof', 'cloudmanager'])['stdout']
    if not pid.isdigit():
        raise RuntimeError('Expected exactly one cloudmanager process')
    commands = [
        ['logcat', '-b', 'main', '-b', 'system', '-v', 'threadtime', '--pid=' + pid, '-T', '1', '*:V'],
        ['logcat', '-b', 'main', '-b', 'system', '-b', 'radio', '-v', 'threadtime', '-T', '1',
         '[BYDCLOUD]BYDTCPConnectService:V', '[BYDV2C]BYDConnectManager:V',
         '[BYDV2C]BYDMultiApnConnReceiver:V', '[CloudService]AppsReceiver:V', 'c_ares_dns:V', '*:S']]
    processes, threads = [], []
    attempted = False
    for command, name in zip(commands, ['native-redacted.txt', 'framework-redacted.txt']):
        p = subprocess.Popen(ADB + command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, errors='replace')
        t = threading.Thread(target=reader, args=(p, out / name), daemon=True)
        processes.append(p)
        threads.append(t)
        t.start()
    try:
        time.sleep(1)
        started = time.monotonic()
        with (out / 'guard-output.txt').open('w') as log:
            watchdog = subprocess.Popen([sys.executable, str(Path(__file__).resolve()),
                '--guard', '--out', str(out), '--delay', '265'],
                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        if watchdog.poll() is not None:
            raise RuntimeError('Rollback guard failed to start')
        save(out, 'timing.json', dict(start_time=now(), start_monotonic=started,
             guard_pid=watchdog.pid, guard_delay_seconds=265))
        attempted = True
        event(out, 'Single authorized double_apn transition')
        save(out, 'switch-double.json', switch('double_apn'))
        time.sleep(3)
        after = snapshot(out, 'after-switch')
        if not (after['properties'][KEYS[0]] == 'double_apn'
                and after['properties'][KEYS[2]] == '1'
                and after['wifi']['validated_wifi']):
            raise RuntimeError('Public-profile transition or real Wi-Fi state not confirmed')
        event(out, 'Sending exactly one notify_nw(2), actual Wi-Fi connected')
        response = run(['shell', 'service', 'call', 'cloudmanager', '1', 'i32', '2'])
        save(out, 'notify-wifi.json', dict(time=now(), result=response))
        event(out, 'Notification reply: ' + json.dumps(response))
        for seconds in [15, 60, 105, 150, 195, 240, 255]:
            time.sleep(max(0, started + seconds - time.monotonic()))
            observation = snapshot(out, 'observe-' + str(seconds))
            if not observation['wifi']['validated_wifi'] or observation['adb']['stdout'] != 'device':
                raise RuntimeError('Transport baseline changed; ending observation')
        event(out, 'Passive cycle finished; restoring original profile')
    finally:
        try:
            if attempted:
                restore(out, 'main')
        finally:
            for p in processes:
                p.terminate()
                try:
                    p.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    p.kill()
                    p.wait(timeout=5)
            for t in threads:
                t.join(timeout=3)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--guard', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--delay', type=float, default=265, help=argparse.SUPPRESS)
    parser.add_argument('--owner-approved', action='store_true')
    args = parser.parse_args()
    if args.guard:
        guard(args.out.resolve(), args.delay)
    elif args.owner_approved:
        def interrupted(signum, frame):
            raise KeyboardInterrupt('Signal ' + str(signum))
        signal.signal(signal.SIGTERM, interrupted)
        main(args.out.resolve())
    else:
        parser.error('This state-changing diagnostic requires explicit owner approval.')
