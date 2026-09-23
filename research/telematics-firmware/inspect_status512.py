#!/usr/bin/env python3
"""Inspect SOC in a saved, unframed CAN-FD cloudmanager message-512 body.

Offline only: no transport, encoder, authentication, or vehicle access. Layout
comes from the owner's 2026-09-23 firmware corpus, not a live cloud response.
"""

import argparse
import json
from pathlib import Path


def inspect_body(body: bytes) -> dict:
    if len(body) != 104:
        raise ValueError("Expected exactly 104 bytes of message-512 body, without framing")
    if body[0] != 3:
        raise ValueError("Expected the traced CAN-FD body marker 3")
    # MCU descriptor [7, 0, 8, 3] is one-based; cloudmanager copies those
    # two CAN bytes unchanged into body[0x54:0x56]. The upper nibble is
    # adjacent vehicle information, not part of SOC.
    raw = body[0x54] | ((body[0x55] & 0x0F) << 8)
    plausible = raw <= 1000
    return {
        "source": "saved_unframed_message_512_canfd_body",
        "fid": "0x4A505038",
        "body_byte_offsets": [84, 85],
        "raw_tenths_percent": raw,
        "within_physical_range": plausible,
        "soc_percent": raw / 10 if plausible else None,
        "status": "plausible_value" if plausible else "outside_0_to_100_percent",
        "freshness_verified": False,
        "cloud_acceptance_verified": False,
        "official_phone_feed_verified": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("body", type=Path, help="Local binary file containing only the 104-byte body")
    args = parser.parse_args()
    try:
        # One extra byte is sufficient to reject oversized input.
        with args.body.open("rb") as stream:
            result = inspect_body(stream.read(105))
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
