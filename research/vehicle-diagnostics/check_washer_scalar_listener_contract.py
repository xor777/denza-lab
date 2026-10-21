#!/usr/bin/env python3
"""Pin and replay the retained scalar-listener contract. No car/network calls."""
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/washer-direct-listener-source-20261021.json"
FW = "captures/split-firmware-20260923/jadx/framework/sources/android/hardware/"
LIB = "captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/"


def elf_bytes(data, address, size):
    ph = struct.unpack_from("<Q", data, 32)[0]
    width, count = struct.unpack_from("<HH", data, 54)
    for index in range(count):
        kind, _, offset, base, _, length, _, _ = struct.unpack_from("<IIQQQQQQ", data, ph + index * width)
        if kind == 1 and base <= address and address + size <= base + length:
            return data[offset + address - base:offset + address - base + size]
    raise ValueError(hex(address))


def main():
    paths = set()
    anchors = []
    for relative, first, last in [
        ("bydauto/instrument/BYDAutoInstrumentDevice.java", 892, 926),
        ("bydauto/instrument/BYDAutoInstrumentDevice.java", 1943, 1947),
        ("bydauto/instrument/BYDAutoInstrumentDevice.java", 3334, 3364),
        ("bydauto/instrument/MediaStateDelegate.java", 17, 22),
        ("bydauto/instrument/AbsBYDAutoInstrumentListener.java", 70, 71),
        ("bydauto/instrument/AbsBYDAutoInstrumentListener.java", 554, 559),
        ("bydauto/instrument/AbsBYDAutoInstrumentListener.java", 1440, 1443),
        ("bydauto/AbsBYDAutoDevice.java", 68, 86),
        ("bydauto/AbsBYDAutoDevice.java", 120, 166),
        ("bydauto/AbsBYDAutoDevice.java", 393, 407),
        ("bydauto/BYDAutoDeviceManager.java", 42, 66),
        ("bydauto/BYDAutoDeviceManager.java", 87, 94),
        ("bydauto/BYDAutoDeviceManager.java", 176, 181),
        ("bydauto/BYDAutoDeviceManager.java", 196, 210),
        ("bydauto/BYDAutoDeviceManager.java", 242, 254),
        ("IBYDAutoListenerIdsMap.java", 20, 32),
        ("IBYDAutoListenerIdsMap.java", 102, 117),
        ("BYDAutoManager.java", 116, 136),
        ("BYDAutoManager.java", 152, 163),
        ("BYDAutoManager.java", 344, 390),
        ("BYDAutoManager.java", 426, 444),
    ]:
        path = FW + relative
        paths.add(path)
        lines = (ROOT / path).read_text().splitlines()
        selected = lines[first - 1:last]
        anchors.append(dict(path=path, firstLine=first, lastLine=last,
            textSha256=hashlib.sha256(("\n".join(selected)+"\n").encode()).hexdigest(),
            excerpt=selected if len(selected) <= 6 else selected[:3] + ["..."] + selected[-3:]))
    map_path = FW + "bydauto/BYDAutoDeviceFeaturesMap.java"
    text = (ROOT/map_path).read_text()
    instrument = text.split("public static class InstrumentMap {",1)[1].split("\n    }",1)[0]
    assert ", 638582792," in instrument
    assert "map2.put(1007, InstrumentMap.FEATURES_BYDAUTO_DEVICE_INSTRUMENT);" in text
    paths.add(map_path)
    ranges = []
    for name, address, size, symbol, finding in [
        ("libbydauto.so", 0x1C5E0, 692, "BYDAutoManager::enableDevice(int,int*,unsigned)", "Service-filter registration, then native local filter update after success."),
        ("libbydauto.so", 0x1C8A0, 800, "BYDAutoManager::nativeEnableDevice(int,int*,unsigned)", "Maintains the local device/FID subscription vector, no initial GET or history replay identified."),
        ("libbydauto.so", 0x1F060, 748, "BYDAutoManager::registerObserver(sp<IBYDAutoObserver>)", "Registers callback and local observer; no initial scalar replay identified."),
        ("libbydautoservice.so", 0x2AA10, 872, "BYDAutoService::enableDevice(int,int*,unsigned,callback)", "Checks GET permission; device1035 alone takes HAL enable branch2AA78..2AAA4. Device1007 takes callback-proxy filter branch2AC0C."),
        ("libbydautoservice.so", 0x28B00, 924, "BYDAutoCallbackProxy::enableDevice(int,int*,unsigned)", "Updates device/FID filter vector; no HAL setter, collection-table write or initial GET identified."),
        ("libbydautoservice.so", 0x2AD80, 836, "BYDAutoService::checkGetPermission(int,int*,unsigned)", "Same-process or callerUID<=9999 allowed after service/HAL checks; other UIDs use common and per-FID GET permissions. This is firmware logic, not new permission authority."),
        ("libbydautoservice.so", 0x32760, 1184, "BYDAutoService::registerCallback(callback)", "Adds callback proxy and binder lifecycle; no initial selected-ID replay identified."),
    ]:
        path = LIB + name
        paths.add(path)
        raw = elf_bytes((ROOT/path).read_bytes(), address, size)
        ranges.append(dict(path=path, va=hex(address), size=size, sha256=hashlib.sha256(raw).hexdigest(), symbol=symbol, finding=finding))
    for path in ["research/vehicle-diagnostics/washer-sensor-origin-20261021.json", "research/vehicle-diagnostics/washer-observation-modes-20261021.json"]:
        paths.add(path)
    return dict(schema=1, date="2026-10-21", offlineOnly=True, vehicleCalls=0,
        target=dict(device=1007, fid="0x26100008", decimalFID=638582792, getter="getTextInfo()", callback="onTextInfoChanged(int)", superclass="AbsBYDAutoDevice"),
        sources=[dict(path=path,sha256=hashlib.sha256((ROOT/path).read_bytes()).hexdigest()) for path in sorted(paths)],
        lineAnchors=anchors, nativeFunctionAnchors=ranges,
        registration=[
            "BYDAutoInstrumentDevice.registerListener(l,int[]) -> AbsBYDAutoDevice.registerListener(l,int[]) -> BYDAutoDeviceManager.enableDevice(device,int[]) -> BYDAutoManager.enableDevice(1007,int[]) -> nativeEnableDevice.",
            "The feature array must be nonempty and in device1007 FeaturesMap. FID26100008 is in the retained InstrumentMap. Prefer exactly one FID, not all-instrument subscription.",
            "AbsBYDAutoDevice tests isListenerAllIdsEmpty, not isListenerEmpty: filtered registration can call enable again while other filtered listeners exist. Do not register duplicate listeners or change a long-lived app's listener list for this probe.",
            "The Java registration method returns void and ignores enableDevice status except thrown exceptions. A REG_RETURNED marker alone is not proof of active native delivery.",
        ],
        delivery=[
            "Native scalar callback -> BYDAutoManager.dispatchNativeEvent(1007,26100008,value) -> OnBYDAutoListener.onChanged -> BYDAutoDeviceManager posts on Context.getMainLooper() -> device.postEvent -> AbsBYDAutoDevice selects listeners for the FID -> AbsBYDAutoInstrumentListener.onDataChanged -> onTextInfoChanged(value).",
            "The callback receives the same scalar selected-ID as getTextInfo(), not a popup-visible event, sensor reading, DTC, ECU identity, source timestamp or warning-history list.",
            "No explicit initial GET/cache replay is present in examined Java registration, native filter-enable and callback-register bodies. Initial delivery and its timing are not guaranteed; a separately marked baseline GET may precede registration as before_register. Preserve explicit operation ordering and the baseline-to-registration gap.",
            "A prepared, running main Looper is mandatory for this device-manager delivery. Stamp callback receipt with on-device SystemClock.elapsedRealtimeNanos(); it is on-device receipt time, not host or source generation time.",
            "The MCU static warning decoder compares and emits scalar changes before the separate raw-export policy. A scalar listener is therefore a justified candidate for changes that sparse GET/raw interval export can miss, not a guaranteed lossless original-bus history.",
        ],
        permissionsAndModes=[
            "BYDAutoManager applies package PMS_AUTOAPIBLACK to registration, GET, enable and dispatch. nativeEnableDevice permission-deny -2147482644 raises SecurityException. Stop and report on denial; do not grant permissions or retry through privilege escalation.",
            "Service enableDevice uses GET permissions, not SET permissions, for this callback subscription. Instrument declares android.permission.BYDAUTO_INSTRUMENT_GET for reading. Actual runtime access still requires qualification.",
            "No raw collection-mode argument or CAN-monitor table setter is passed by scalar registerListener. This does not change dynamic raw-export mode, owner bits, or guarantee any currently-unseen raw tuple becomes available.",
        ],
        safetyVerdict=dict(candidate="A single finite dedicated-process scalar observer plus explicitly marked baseline/end GET is source-justified receive-only research.",
            stateChanges="Listener/filter registration and unregister mutate process/service subscription state. No vehicle setter or MCU collection-table write is identified for device1007 in the examined path; this is not a claim of literally zero IPC or zero internal state change.",
            sourceLimits="JNI trampoline implementation was not independently recovered in this pass. Native exported client/service registration bodies and retained Java route were inspected; runtime build, registration success, ordering, reconnect and complete event delivery are not live-qualified.",
            finiteProbeContract=["One dedicated shell process, one filtered listener, device1007/FID26100008 only.",
                "Prepare the main Looper and keep Context valid; log baseline GET as before_register, register the listener, then run the Looper. Preserve operation ordering and the baseline-to-registration gap; end GET is separately marked.",
                "Log BEGIN, BASELINE(before_register), REG_RETURNED, CALLBACK(value,on-device elapsedRealtimeNanos receipt time), ERROR, END_GET, UNREGISTER, DONE separately; preserve every callback including repeated values.",
                "Bound duration in the process; unregister in finally and quit the Looper. Do not depend solely on killing the host transport for cleanup.",
                "No software vehicle setters, injection, collection-table updates, UDS, resets, synthetic values or APK install. The software probe is passive; an optional ordinary owner door opening is not injection or a forced vehicle command.",
                "A quiet run does not prove absence of1DD generation: no source-freshness/lossless/reconnect guarantee; current13F already cached may yield no callback until it changes."]))


if __name__ == "__main__":
    result = main()
    if sys.argv[1:] == ["--report"]:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        assert not sys.argv[1:]
        assert result == json.loads(REPORT.read_text()), "report differs from retained-source replay"
        print(json.dumps(dict(ok=True,sources=len(result["sources"]),lineAnchors=len(result["lineAnchors"]),nativeFunctions=len(result["nativeFunctionAnchors"]))))
