#!/usr/bin/env python3
"""Regenerate someip_schema.json from the stock map's generated protobuf-lite classes.

Field numbers come from `X_FIELD_NUMBER`, types from the matching private
`x_` member of each GeneratedMessageLite class in the BydLaunchermap decompile
(git-ignored corpus). Run from the repository root:

    python3 -m tools.adas_bridge.schema_gen
"""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / 'captures/hud-firmware-20260923/jadx/BydLaunchermap/sources/someip'
FILES = ['sd/map/inform/service/SomeipSdMapInformService.java',
         'navigation/status_/link/info/service/SomeipNavigationStatusLinkInfoService.java',
         'navigation/sd/link2/service/SomeipNavigationSdLink2Service.java',
         'navi/path/user/select/sts/service/SomeipNaviPathUserSelectStsService.java',
         'navigation/path/match/status/service/SomeipNavigationPathMatchStatusService.java',
         'pilot/status_/alarm/info/service/SomeipPilotStatusAlarmInfoService.java']
SCALAR = {'int': 'int32', 'long': 'int64', 'boolean': 'bool', 'double': 'double', 'float': 'float',
          'String': 'string', 'ByteString': 'bytes', 'Internal.IntList': 'repeated int32',
          'Internal.LongList': 'repeated int64', 'Internal.DoubleList': 'repeated double',
          'Internal.FloatList': 'repeated float', 'Internal.BooleanList': 'repeated bool'}
CLASS = re.compile(r'^    public static final class (\w+) extends GeneratedMessageLite<')
NUMBER = re.compile(r'^        public static final int (\w+)_FIELD_NUMBER = (\d+);')
MEMBER = re.compile(r'^        private ([\w.<>]+) (\w+)_(?: = .*)?;')


def parse(path):
    messages, current = {}, None
    numbers, members = {}, {}

    def close():
        if current:
            fields = {}
            for upper, number in numbers.items():
                name = next((m for m in members if m.upper() == upper.replace("_", "")), None)
                if name is None:
                    continue
                java = members[name]
                if java in SCALAR:
                    kind = SCALAR[java]
                elif java.startswith('Internal.ProtobufList<'):
                    inner = java[len('Internal.ProtobufList<'):-1]
                    kind = 'repeated ' + SCALAR.get(inner, 'message:' + inner)
                else:
                    kind = 'message:' + java
                fields[str(number)] = [name, kind]
            messages[current] = fields

    for line in path.read_text(encoding='utf-8').splitlines():
        match = CLASS.match(line)
        if match:
            close()
            current, numbers, members = match.group(1), {}, {}
            continue
        match = NUMBER.match(line)
        if match and current:
            numbers[match.group(1)] = int(match.group(2))
            continue
        match = MEMBER.match(line)
        if match and current:
            members[match.group(2)] = match.group(1)
    close()
    return messages


def main():
    schema = {}
    for name in FILES:
        for message, fields in parse(SOURCES / name).items():
            schema[message] = fields
    out = Path(__file__).with_name('someip_schema.json')
    out.write_text(json.dumps({'source': 'BydLaunchermap jadx someip/* generated protobuf-lite classes',
                               'messages': dict(sorted(schema.items()))}, indent=1, sort_keys=False) + '\n')
    print(out, len(schema))


if __name__ == '__main__':
    main()
