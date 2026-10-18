"""Copy the reviewed stock primary cyan artwork, unchanged, into local build assets."""
import hashlib
import re
import struct
import zipfile

PIN = '3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793'
PATTERN = re.compile(r'res/drawable-hdpi-v4/global_image_(land(?:back|front)_hud_[0-9a-f]+|ar_hud_sou[0-9]+)\.png')


def extract(apk):
    if hashlib.sha256(apk.read_bytes()).hexdigest() != PIN:
        raise ValueError('Stock artwork must come from the reviewed BydLaunchermap APK')
    assets, report = {}, {}
    with zipfile.ZipFile(apk) as source:
        for name in source.namelist():
            match = PATTERN.fullmatch(name)
            if not match:
                continue
            data = source.read(name)
            expected = (37, 60) if match[1].startswith('land') else (80, 80)
            if data[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', data[16:24]) != expected:
                raise ValueError('Unexpected stock PNG: ' + name)
            target = 'assets/denza-hud/stock/' + match[1] + '.png'
            if target in assets:
                raise ValueError('Duplicate stock asset: ' + target)
            assets[target] = data
            report[target] = hashlib.sha256(data).hexdigest()
    if len(assets) != 127:
        raise ValueError('Expected 77 lane and 50 manoeuvre PNGs')
    return assets, {'input_sha256': PIN, 'assets_sha256': report,
                    'bytes': sum(map(len, assets.values()))}
