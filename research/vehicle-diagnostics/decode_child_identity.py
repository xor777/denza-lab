"""Offline validation of one declared single-frame software-identity reply.

No transport, Android or network access. The envelope is already observed for
F194. Non-F194 child examples in the tests are synthetic, not vehicle evidence.
This deliberately rejects multi-frame traffic; it is not a resident-bus reader.
"""
import argparse
import json
from pathlib import Path


def decode(frame, *, request_id, response_id, did, cached_code):
    if not (0 <= request_id <= 0x7FF and response_id == request_id + 8 and response_id <= 0x7FF):
        raise ValueError('not a qualified CAN request/reply pair')
    if not 0 <= did <= 0xFFFF:
        raise ValueError('invalid DID')
    expected = bytes.fromhex(cached_code)
    if len(expected) != 9 or not any(expected):
        raise ValueError('expected code must be a nonzero nine-byte cache identity')
    if not 7 <= len(frame) <= 1024:
        raise ValueError('frame size outside retained envelope')
    if frame[0] != 0x0A or int.from_bytes(frame[1:3], 'big') != response_id:
        raise ValueError('foreign owner or response address')
    if frame[4:6] != b'\x01\x01':
        raise ValueError('only one complete single-frame answer is supported')
    end = 7 + frame[3]
    if end > len(frame):
        raise ValueError('truncated declared payload')
    uds = frame[7:end]
    result = frame[6]
    row = dict(requestHex=f'{request_id:03X}', responseHex=f'{response_id:03X}',
               didHex=f'{did:04X}', gatewayResult=result, rawFrameHex=frame[:end].hex().upper(),
               rawUdsHex=uds.hex().upper(), codeHex=None, matchesCachedCode=None,
               childDtcRead=False)
    if result in (3, 4, 5):
        row['state'] = {3: 'no_response', 4: 'gateway_timeout', 5: 'gateway_busy'}[result]
        return row
    if result not in (1, 2):
        raise ValueError('unknown gateway result')
    if len(uds) == 3 and uds[:2] == b'\x7f\x22':
        row.update(state='response_pending' if uds[2] == 0x78 else 'uds_negative',
                   negativeResponseCodeHex=f'{uds[2]:02X}')
        return row
    if result != 1:
        raise ValueError('gateway negative result without a matching negative response')
    if len(uds) != 12 or uds[:3] != b'\x62' + did.to_bytes(2, 'big'):
        raise ValueError('wrong echoed DID or software-code length')
    code = uds[3:]
    row.update(state='zero_placeholder' if not any(code) else 'identity_reply',
               codeHex=code.hex().upper(), matchesCachedCode=code == expected)
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True, help='Exact ecuId in the retained eight-child manifest')
    parser.add_argument('--frame-file', type=Path, required=True, help='Local file containing one raw frame as hex')
    args = parser.parse_args()
    manifest = Path(__file__).with_name('ecu-child-identification-candidates-20261021.json')
    matches = [row for row in json.loads(manifest.read_text())['candidates'] if row['ecuId'] == args.candidate]
    if len(matches) != 1:
        raise ValueError('candidate must identify exactly one retained child')
    candidate = matches[0]
    result = decode(bytes.fromhex(args.frame_file.read_text()),
                    request_id=candidate['route']['requestId'], response_id=candidate['route']['responseId'],
                    did=int(candidate['codeDid'], 16), cached_code=candidate['cachedSoftwareCode'])
    result['candidateEcuId'] = candidate['ecuId']
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
