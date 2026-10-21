#!/usr/bin/env python3
"""Check the finite washer producer-routing evidence without vehicle/network I/O.

Run with .venv-firmware/bin/python (pyelftools is required). --write records the
new report only. Firmware is inspected as data, never executed.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'captures/washer-diagnostics-20261021/producer-routing'
REPORT = ROOT / 'research/vehicle-diagnostics/washer-producer-routing-20261021.json'
PINS = {
    'cells': ('captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so', '124d6b567c0c6205a59579a3d4df06cc2e0dba5a89bead73cb4151852298b3f6'),
    'mcu': ('captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin', '6c277d1eb819b765d997554fc727835314b9477cae414aa806ea7cf78eda4d02'),
    'factory': ('research/vehicle-diagnostics/factory-ecu-map-20261020.json', '8db4eb52600c869bcf749114ef925f1fb7bba9941babfec0328c1dcfa4de2fac'),
    'catalog': ('research/vehicle-diagnostics/xtool-z9gt-dm-20261021.json', '93cb9bfbb6cd5b91393671d26d206db230447467785ba91fa600ef5e93b39a24'),
    'cache': ('captures/diagnostics-discovery-20261018/approved-export-1/files/04-ECUVersion.json', '8e31658bc0387da5ea905595da8bb0d3df859bf7b172d3f80d9ce77f9ebd1d53'),
    'lowPower': ('captures/firmware/pulled-from-car-20261018/jadx/SpotInspection/resources/res/raw/low_power_script.json', 'c3883631aa7f336d7c5d3da2af6a3549c988d166704106425bc9be9535cb064f'),
    'topology': ('research/vehicle-diagnostics/ecu-route-topology-20261021.json', 'c5b8b112599f66f97ae8ae1f993d8ab40ea2bad88fcec6db6ec93355f6fa2ef0'),
}


def main(write=False):
    data, sources = {}, []
    for key, (path, expected) in PINS.items():
        raw = (ROOT / path).read_bytes()
        actual = hashlib.sha256(raw).hexdigest()
        assert actual == expected, 'different source: ' + path
        data[key] = raw
        sources.append(dict(path=path, sha256=actual, bytes=len(raw)))

    # Re-read the actual OEM ELF DWARF enum, rather than trusting a grep hit or
    # only the earlier derived enum JSON. Exactly one known DIE is visited.
    with (ROOT / PINS['cells'][0]).open('rb') as source:
        dwarf = ELFFile(source).get_dwarf_info()
        enum = dwarf.get_DIE_from_refaddr(0x1B5AF)
        assert enum.tag == 'DW_TAG_enumeration_type'
        assert enum.attributes['DW_AT_name'].value == b'vehicle_data_appid_enum'
        values = [dict(name=die.attributes['DW_AT_name'].value.decode(),
                       value=die.attributes['DW_AT_const_value'].value)
                  for die in enum.iter_children() if die.tag == 'DW_TAG_enumerator']
    assert len(values) == 2021
    prefixes = collections.Counter(row['name'].split('_')[0] for row in values)
    warning = [row for row in values if row['value'] == 0x26100008]
    assert warning == [dict(name='DOMAIN_261_10_21_TEXT_INFO', value=0x26100008)]
    frame_names = [row for row in values if row['name'].startswith('DOMAIN_261_')]
    assert len(frame_names) == 24
    assert prefixes['DOMAIN'] == 1371 and prefixes['VCU'] == 187 and prefixes['CLUSTER'] == 72

    factory = json.loads(data['factory'])['ecus']
    catalog = json.loads(data['catalog'])['systems']
    cache = json.loads(data['cache'])['ecuInfo']
    target_ids = ['HTH-2021_CDC_BYD_00100001', 'HTH-2021_INS_BYD_00100001',
                  'HTH-2021_F_ZCU_BYD_00100001', 'HTH-2021_R_ZCU_BYD_00100001']
    candidates = []
    for target in target_ids:
        index = next(i for i, row in enumerate(factory) if row['ecuId'] == target)
        record = factory[index]
        cached = [dict(pointer='/ecuInfo/' + str(i), **row) for i, row in enumerate(cache)
                  if row['ecuId'] == target]
        assert len(cached) == 1
        catalog_rows = [dict(rowId=row['rowId'], token=row['token'], systemZh=row['systemZh'],
                             networkZh=row['networkZh']) for row in catalog
                        if any(candidate['ecuId'] == target for candidate in row['factoryCandidates'])]
        candidates.append(dict(factoryPointer='/ecus/' + str(index), factory=record,
            cachedIdentity=cached[0], modelCatalogLabels=catalog_rows,
            CAN261SenderProved=False, warning1DDSelectorProved=False, washerSensorInputOwnerProved=False))
    cdc = candidates[0]
    assert cdc['factory']['ecuDiaId'] == '720/728'
    assert cdc['cachedIdentity']['ecuSoftCode'] == '4B310011E0019A0002'
    assert any(row['systemZh'] == '诊断网/跨域计算平台(比亚迪)(CDC)' for row in cdc['modelCatalogLabels'])
    attachment = json.loads(data['lowPower'])['data']['attach'][0]
    stock_label = {key: attachment[key] for key in ('ecuAbbrName', 'ecuName', 'bus', 'can')}
    assert stock_label['ecuAbbrName'] == 'LA_BCM'
    assert stock_label['ecuName'] == '左车身域控制器'
    assert stock_label['can']['reqCanId'] == 0x720
    assert stock_label['can']['rspCanId'] == [0x728]

    # A bounded inventory of the normal FID dispatch table. FID prefixes are
    # not assumed to be physical CAN transmit IDs; generic/raw paths are not
    # excluded by this inventory.
    mcu = data['mcu']
    u32 = lambda va: struct.unpack_from('<I', mcu, va-0x40000)[0]
    pointers = sorted({u32(0xBC55C + i*4) for i in range(0x2286)} - {0})
    assert len(pointers) == 3017
    dispatch = [dict(recordVA=hex(pointer), fid=hex(u32(pointer)), handlerVA=hex(u32(pointer+4)),
                     contextVA=hex(u32(pointer+8))) for pointer in pointers]
    warning_prefix = [row for row in dispatch if int(row['fid'],16) >> 20 == 0x261]
    assert not warning_prefix
    contexts = sum(u32(pointer+8) != 0 for pointer in pointers)
    assert contexts == 2933

    report = dict(schema='denza-lab.washer-producer-routing.v1', date='2026-10-21',
        offlineOnly=True, vehicleCalls=0, ADBCalls=0, networkCalls=0, firmwareExecuted=False,
        finding='The OEM source names the CAN 261 text input DOMAIN. No independent source joins that namespace to a unique physical transmitter, 1DD selector or reservoir-input ECU.',
        ownership=dict(
            physicalCAN261Transmitter=dict(identity=None,
                known='The retained IVI implementation receives CAN 261 and decodes its existing warning enum.',
                gap='No sender-node definition, bus communication matrix or matching transmitting-controller firmware is retained in this evidence set.',
                gatewayBoundary='A gateway may retransmit a frame selected by another controller. Its physical TX ownership would not by itself identify the 1DD-selection algorithm.'),
            warning1DDSelector=dict(identity=None,
                known='The cluster displays the admitted warning identifier; its input name is DOMAIN_261_10_21_TEXT_INFO.',
                gap='No producer implementation maps a named reservoir input or condition to warning 1DD in the selected sources.'),
            reservoirInputOwner=dict(identity=None,
                acceptedPremise='Sensors are fitted and the N9 reservoir was empty, as reported by the owner/service.',
                gap='No applicable circuit sheet, sensor-to-controller definition, diagnostic measurement definition or ECU firmware binds this input.',
                boundary='A stock front-washer actuator route does not establish level-input ownership.')),
        nativeNamespace=dict(source=PINS['cells'][0], DIEOffset='0x1b5af', enum='vehicle_data_appid_enum',
            totalEnumerators=len(values), warning=warning[0], frame261Names=frame_names,
            prefixCounts=dict(prefixes),
            interpretation='DOMAIN is an explicit OEM namespace. It covers 1371 properties, including body, warning, drivetrain and ADAS data. The name alone does not establish one physical ECU or equate DOMAIN with the factory CDC token.'),
        channelBoundary=dict(logicalCaptureChannel=0, physicalVehicleNetwork=None,
            boardTransceiver=None, vehicleConnectorOrPins=None,
            known='The earlier ingress audit follows the MCU receive record and logical channel into its lookup key. MCU receive-register record 0 has control base FFD00000 and message base FFD00700.',
            notEstablished='A source mapping from this MCU controller/interface index to the vehicle harness, named CAN network, physical transceiver or connector is absent from the selected evidence.',
            prohibitedJoin='Neither logical capture channel 0 nor a matching numeric factory ecuChannel 0 identifies a physical network or sender. Diagnostic net indices and broadcast capture channels are separate namespaces until a source joins them.'),
        identityAnchorScope='The following four controller records are available identity/document anchors. They are not a ranked list of proven or probable CAN 261 senders.',
        candidateIdentityAnchors=candidates,
        nameConflict=dict(factoryLabel='CDC / 跨域计算平台', modelCatalogRow=1452,
            factoryRoute='720/728', stockLowPowerPointer='/data/attach/0', stockLowPowerLabel=stock_label,
            interpretation='The same diagnostic address has CDC/cross-domain and LA_BCM/left-body-domain labels in different retained sources. This is a documented naming ambiguity, not a CAN 261 sender binding.'),
        normalMCUDispatch=dict(tableBase='0xbc55c', buckets=0x2286, uniqueRecords=len(dispatch),
            recordsWithContext=contexts, FIDPrefix261Matches=warning_prefix,
            interpretation='No normal FID dispatch entry has prefix 261. This does not prove absence of CAN 261 transmission from all MCU code, dynamic state, bridge logic or arbitrary raw/diagnostic paths.',
            tableSHA256=hashlib.sha256(mcu[0xBC55C-0x40000:0xBC55C-0x40000+0x2286*4]).hexdigest()),
        concreteDocumentAnchor=dict(
            requestedArtifact='Model/software-applicable CAN-FD communication matrix or sender-side signal definition for frame 0x261, its text-info bits 0..9 and warning 1DD.',
            exactSignalLabel='DOMAIN_261_10_21_TEXT_INFO / FID 26100008',
            requiredFields=['Physical bus and transmitting node for CAN 261',
                'Origin/forwarding node if a gateway relays the frame',
                'ECU and software that select warning 1DD',
                'Source reservoir input and applicable equipment/coding gates'],
            modelBoundary='Obtain applicability separately for N9/profile 168 and Z9GT/profile 170; the retained factory identities below are profile 170 candidates/cache.',
            factoryRecordToDisambiguate=dict(ecuId=cdc['factory']['ecuId'], route='720/728',
                identityReadDefinition='F195/F194', cachedSoftwareCode=cdc['cachedIdentity']['ecuSoftCode'],
                cachedVersion=cdc['cachedIdentity']['ecuSoftVer'], liveRefreshPerformed=False),
            caution='This identifies a concrete record for obtaining an applicable definition; it is not a claim that CDC is the sender and authorizes no new diagnostic or private-network request.'),
        limits=['The RX/cache chain is not re-derived as a substitute for identifying its upstream producer.',
                'Diagnostic request addresses, native DOMAIN naming, factory ecuChannel and model network labels do not independently identify a broadcast sender.',
                'No generic-looking MCU context pointer is interpreted as a physical CAN transmit ID.',
                'No inference of sensor absence, no Ready2/3 inference, and no attribution to R_ZCU from pump actuation.',
                'No product, shared documentation, prior report, live configuration or physical state changed.'],
        sources=sources)
    if write:
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / 'origin-evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        (OUT / 'normal-fid-dispatch.json').write_text(json.dumps(dispatch, indent=2)+'\n')
        REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    return dict(offlineOnly=True, pinnedSources=len(sources), nativeEnumerators=len(values),
        exactWarningName=warning[0]['name'], frame261Enumerators=len(frame_names),
        normalDispatchRecords=len(dispatch), exactPhysicalSenderProved=False,
        warningSelectorProved=False, sensorInputOwnerProved=False,
        candidateECUIds=target_ids, report=str(REPORT.relative_to(ROOT)) if write else None)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    print(json.dumps(main(parser.parse_args().write), ensure_ascii=False, indent=2))
