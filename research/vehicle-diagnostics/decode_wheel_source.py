"""Reconstruct the MCU's candidate wheel packet from one saved CAN122 frame.

Offline only. This predicts bytes from the retained firmware producer; it does
not establish that the MCU gate was enabled or that a 99000059 event arrived.
Physical units, acquisition age and installed-MCU applicability remain open.
"""
import argparse
import json

from decode_wheel_packet import decode


def reconstruct(*, can_id, sub_id, channel, payload):
    if (can_id, sub_id, channel) != (0x122, 0, 1):
        raise ValueError('Requires the observed CAN122/sub0/channel1 tuple')
    if len(payload) != 8:
        raise ValueError('Requires exactly eight source payload bytes')
    values = [int.from_bytes(payload[i:i + 2], 'little') & 0xFFF
              for i in (0, 2, 4, 6)]
    status = [(payload[1] >> bit) & 1 for bit in (5, 4, 6, 7)]
    packet = b''.join(value.to_bytes(2, 'big') for value in values) + bytes(status)
    return {
        'source': {'canId': '0x122', 'subId': 0, 'channel': 1,
                   'payloadHex': payload.hex().upper()},
        'predictedPacketHex': packet.hex().upper(),
        'eventObserved': False,
        'producerGateKnown': False,
        'decodedCandidate': decode(packet),
        'scope': 'Firmware reconstruction from saved source bytes, not a received wheel event',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('payload_hex', help='Eight bytes from CAN122/sub0/channel1')
    args = parser.parse_args()
    print(json.dumps(reconstruct(can_id=0x122, sub_id=0, channel=1,
                                 payload=bytes.fromhex(args.payload_hex)), indent=2))


if __name__ == '__main__':
    main()
