#!/usr/bin/env python3
"""Export only named stock UI resources from the already reviewed, local Navigator corpus.
Output goes to stdout for the stand packager; never copies extracted assets into Git.
"""
import base64
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'captures/firmware/yandexnavi-29.8.1-3b0dec32/jadx/Navigator/resources/res'
NS = '{http://schemas.android.com/apk/res/android}'
DIRECTIONS = ['straightahead','left90','right90','left45','right45','left135','right135','left180','right180']
assets = {}
for direction in DIRECTIONS:
    name = f'context_lane_{direction}_large'
    doc = ET.parse(RES / 'drawable' / f'{name}.xml').getroot()
    width, height = doc.get(NS+'viewportWidth'), doc.get(NS+'viewportHeight')
    svg = ET.Element('svg', {'xmlns':'http://www.w3.org/2000/svg','viewBox':f'0 0 {width} {height}'})
    for group in doc.iter('group'):
        if group.attrib: raise ValueError(f'Unreviewed group transform in {name}')
    for element in doc.iter():
        if element.tag in ('vector', 'group'): continue
        if element.tag != 'path':
            raise ValueError(f'Unreviewed drawable element in {name}: {element.tag}')
        attrs={'d':element.get(NS+'pathData'),'fill':element.get(NS+'fillColor','none')}
        for android, web in [('strokeColor','stroke'),('strokeWidth','stroke-width'),('strokeLineCap','stroke-linecap'),('strokeLineJoin','stroke-linejoin'),('fillAlpha','fill-opacity'),('strokeAlpha','stroke-opacity')]:
            if element.get(NS+android) is not None: attrs[web]=element.get(NS+android)
        ET.SubElement(svg,'path',attrs)
    assets[name]='data:image/svg+xml;base64,'+base64.b64encode(ET.tostring(svg)).decode()
arrow=RES/'drawable-sw600dp-mdpi/notification_arrow.png'
assets['notification_arrow']='data:image/png;base64,'+base64.b64encode(arrow.read_bytes()).decode()
print(json.dumps(assets))
