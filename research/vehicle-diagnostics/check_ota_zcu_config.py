#!/usr/bin/env python3
"""Verify the retained front-ZCU read and replay stock parsing offline.

No device/network access. The replay uses captured UDS data in a synthetic OTA
envelope; it checks parser output, not the original OTA transport or server rules.
"""
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

from check_ota_strategy_followup import ROOT, verify_refs

ARTIFACT = ROOT / 'research/vehicle-diagnostics/ota-zcu-config-live-20261021.json'
JAVA = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
DRIVER = '''public class ZcuCapturedReplyReplay {
  public static void main(String[] args) {
    CanDiagnostic c = CanDiagnostic.fresh();
    String version = c.parseEcuVersionInfo(CanDiagnostic.packet(args[0], 1, 0, 0));
    String code = c.parseEcuSoftCodeInfo(CanDiagnostic.packet(args[1], 1, 0, 0));
    if (!"6.55.35".equals(version) || !"FFFFFFFFFFFFFFFFFF".equals(code))
      throw new AssertionError("Stock parse differs from retained cache");
    System.out.println("{\\"stockVersion\\":\\"" + version
      + "\\",\\"stockCode\\":\\"" + code + "\\"}");
  }
}
'''


def main():
    evidence = json.loads(ARTIFACT.read_text())
    errors = []
    refs = verify_refs(evidence, errors)
    assert not errors, errors
    paths = evidence['evidence']
    protocol = (ROOT / paths['protocol']['path']).read_text()
    commands = re.findall(r'^HOST x (\d+) (\S+) (\S+) (\d+)$', protocol, re.M)
    assert commands == [('1', '722', '22F194', '4000'),
                        ('2', '722', '22FF94', '4000'),
                        ('3', '722', '22FF95', '4000')]
    replies = re.findall(r'^a (\d+) (\d+) (\d+) (\d+) ([0-9A-F]+)$', protocol, re.M)
    assert len(replies) == 3
    nonce = re.fullmatch(r'DENZA_SERVE_([0-9a-f]{24}):READY', protocol.splitlines()[0])[1]
    expected_lines = [f'DENZA_SERVE_{nonce}:READY']
    for row, command, reply in zip(evidence['responses'], commands, replies):
        seq, frames, latency, timestamp, uds = reply
        assert int(seq) == row['seq'] and int(frames) == row['frames'] == 1
        assert int(latency) == row['latencyMs'] < 4000
        assert int(timestamp) == row['receivedNanos']
        assert uds == row['udsHex'] and uds[:6] == '62' + command[2][2:]
        assert uds[6:] == row['dataHex'] and len(bytes.fromhex(uds[6:])) == row['dataBytes']
        expected_lines += ['HOST x ' + ' '.join(command), f'DENZA_SERVE_{nonce}:BEGIN',
                           'a ' + ' '.join(reply), f'DENZA_SERVE_{nonce}:END ok']
    assert protocol.splitlines() == expected_lines
    assert replies[0][4] == '62F1944B310011E001540100'
    assert replies[1][4] == '62FF94' + 'FF' * 9
    assert replies[2][4] == '62FF95' + 'FF' * 6
    result = json.loads((ROOT / paths['result']['path']).read_text())
    assert result['state'] == 'stopped' and len(result['responses']) == 2
    assert result['error'] == 'Unexpected UDS length; preserved raw reply, no padded parsing'
    assert result['exitCode'] == 0 and result['jarRemoved'] and result['processAbsent']
    for name in ('preflight', 'postflight'):
        state = json.loads((ROOT / paths[name]['path']).read_text())
        assert all(state[k] for k in ('guardPass', 'firmwareHashMatch', 'appHashMatch', 'crashEmpty'))
    for name in ('preProcesses', 'postProcesses'):
        pids = dict((line.split()[1], line.split()[0]) for line in
                    (ROOT / paths[name]['path']).read_text().splitlines() if len(line.split()) == 2)
        assert all(pids[k] == v for k, v in evidence['cleanup']['selectedProcessPidsUnchanged'].items())
    sources = evidence['sources']
    build_path = ROOT / sources['compiledProbeManifest']['path']
    build = json.loads(build_path.read_text())
    refs += verify_refs(build, errors)
    assert not errors, errors
    assert hashlib.sha256((build_path.parent / 'front-zcu-config-probe.jar').read_bytes()).hexdigest() == build['jarSha256']
    replay_source = ROOT / sources['stockParserReplaySource']['path']
    replay = json.loads((ROOT / sources['stockParserReplayManifest']['path']).read_text())
    assert hashlib.sha256(replay_source.read_bytes()).hexdigest() == replay['javaSha256']
    for item in replay['methods']:
        assert hashlib.sha256((ROOT / item['source']).read_bytes()).hexdigest() == item['sourceSha256']
    with tempfile.TemporaryDirectory(prefix='ota-zcu-parser-') as tmp:
        driver = Path(tmp) / 'ZcuCapturedReplyReplay.java'
        driver.write_text(DRIVER)
        subprocess.run([str(JAVA / 'javac'), '-d', tmp, str(replay_source), str(driver)],
                       check=True, capture_output=True, timeout=30)
        run = subprocess.run([str(JAVA / 'java'), '-cp', tmp, 'ZcuCapturedReplyReplay',
                              replies[2][4], replies[1][4]], check=True,
                             capture_output=True, text=True, timeout=10)
    print(json.dumps({'passed': True, 'referencesVerified': refs, 'deviceOrNetworkAccess': False,
                      'capturedRequests': 3, 'capturedReplies': 3, 'hostAcceptedReplies': 2,
                      'hostLengthRefusalPreserved': True, 'cleanupVerified': True,
                      'stockParserReplay': json.loads(run.stdout),
                      'otaMismatchCauseProved': False}))


if __name__ == '__main__':
    main()
