"""Host refusal and snapshot regressions; no ADB or vehicle access."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('supported_host', Path(__file__).with_name('run_supported_dtc_probe.py'))
host = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host)


def raw_health():
    return {
        'profile':'170', 'protocol':'CANFD', 'manager':'r@-1_0&w@-1_0',
        'park':"Result: Parcel(00000000 00000001   '........')",
        'speed':"Result: Parcel(00000000 00000000   '........')",
        'app-path':'package:/data/app/test/base.apk',
        'app-hash':'a'*64+'  /data/app/test/base.apk',
        'firmware-hashes':'\n'.join(v+'  '+k for k,v in host.FIRMWARE.items()),
        'processes':'\n'.join(str(i+100)+' '+n for i,n in enumerate(host.STOCK))+'\n4743 com.byd.bydlogtool',
        'crash':'', 'sockets':'local_address\nlocal_address\n',
        'export-leases':'absent', 'export-threads':'binder:4743_1\nBYDAutoManagerT',
    }


class HostTest(unittest.TestCase):
    def health(self, raw):
        return dict(host.parse_health(raw), state='read_complete')

    def test_resident_logtool_is_not_export(self):
        self.assertTrue(host.acceptable(self.health(raw_health())))

    def test_real_activity_and_vehicle_changes_refuse(self):
        cases = [
            ('manager','r@1_1&w@-1_0'), ('profile','169'), ('protocol','CAN'),
            ('park',"Result: Parcel(00000000 00000000 '........')"),
            ('speed',"Result: Parcel(00000000 00000001 '........')"),
            ('crash','FATAL EXCEPTION'), ('export-leases','occupied'),
            ('export-threads','FileTransferServer'),
            ('sockets','local_address\nlocal_address\n0: 00000000:B4F1 00000000:0000 0A'),
            ('processes',raw_health()['processes']+'\n999 denza-supported-dtc-peer'),
            ('firmware-hashes',raw_health()['firmware-hashes'].replace(next(iter(host.FIRMWARE.values())),'0'*64)),
        ]
        for key,value in cases:
            with self.subTest(key=key):
                raw=raw_health(); raw[key]=value
                self.assertFalse(host.acceptable(self.health(raw)))

    def test_incomplete_inventory_and_path_injection_refuse(self):
        for key,value in [('sockets','local_address'), ('app-path','package:/data/app/x;id/base.apk'),
                          ('app-hash','a'*64+'  /wrong/path')]:
            with self.subTest(key=key):
                raw=raw_health(); raw[key]=value
                with self.assertRaises(ValueError): self.health(raw)

    def make_build(self, root):
        build=root/'build'; build.mkdir()
        source=root/'probe.java'; source.write_text('reviewed source')
        jar=build/'supported-dtc-probe.jar'; jar.write_bytes(b'reviewed jar')
        manifest={'main':host.MAIN,'jar':jar.name,'jarSha256':host.sha(jar),
                  'sources':[{'path':source.name,'sha256':host.sha(source)}]}
        (build/'manifest.json').write_text(json.dumps(manifest))
        return build,source,manifest

    def test_source_drift_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve(); build,source,_=self.make_build(root)
            with patch.object(host,'ROOT',root):
                host.check_build(build)
                source.write_text('changed')
                with self.assertRaises(ValueError): host.check_build(build)

    def test_build_replacement_during_health_never_stages(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve(); build,_,manifest=self.make_build(root)
            def preflight(*_):
                (build/'supported-dtc-probe.jar').write_bytes(b'different jar')
                altered=copy.deepcopy(manifest)
                altered['jarSha256']=host.sha(build/'supported-dtc-probe.jar')
                (build/'manifest.json').write_text(json.dumps(altered))
                return self.health(raw_health())
            def adb(_,args,limit):
                self.assertEqual(args,['shell','sha256sum','/data/app/test/base.apk'])
                return subprocess.CompletedProcess(args,0,('a'*64+'  /data/app/test/base.apk').encode(),b'')
            with patch.object(host,'ROOT',root), patch.object(host,'health',preflight), patch.object(host,'adb_command',adb):
                with self.assertRaises(SystemExit): host.run('existing',build,'ecm-bms',root/'out')
            state=json.loads((root/'out/run.json').read_text())
            self.assertIn('Build changed after preflight',state['error'])
            self.assertNotIn('stage',state.get('hostCommands',{}))
            self.assertEqual((root/'out/supported-dtc-probe.jar').read_bytes(),b'reviewed jar')

    def test_refused_preflight_never_stages(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve(); build,_,_=self.make_build(root)
            with patch.object(host,'ROOT',root), patch.object(host,'health',return_value={'state':'failed'}), patch.object(host,'adb_command') as adb:
                with self.assertRaises(SystemExit): host.run('existing',build,'ecm-bms',root/'out')
                adb.assert_not_called()


if __name__=='__main__': unittest.main()
