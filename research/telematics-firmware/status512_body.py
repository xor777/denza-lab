"""Measured CAN-FD body layout from cloudmanager 55350; no transport.

The caller must supply all 25 eight-byte cache entries. A diagnostic caller may
explicitly insert the stock zero-initialized missing entry, with owner approval,
but must record that omission outside this pure builder.
"""


def build_body(cache, charging_override=255):
    if len(cache) != 25 or any(len(v) != 8 for v in cache):
        raise ValueError("Expected 25 explicit eight-byte cache entries")
    if not 0 <= charging_override <= 255:
        raise ValueError("Invalid charge-state override")
    result = bytearray(b"\x03" + b"".join(cache[:10]))
    result.extend(cache[10][2:5])
    result.extend(cache[11][6:8])
    result.extend(cache[12][3:5])
    result.extend(cache[13][4:6])
    result.extend(cache[14][5:7])
    result.extend(cache[15][4:6])
    result.extend(cache[16][2:4])
    result.extend(bytes([cache[17][4], cache[18][7], cache[19][2] if charging_override == 255 else charging_override,
                         cache[20][6], cache[21][0], cache[22][5], cache[23][0], cache[24][1]]))
    if len(result) != 104:
        raise AssertionError("Layout size")
    return bytes(result)
