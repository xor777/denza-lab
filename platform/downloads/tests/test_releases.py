import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from downloads import releases
from downloads.releases import ReleaseError, install


def entry(app="hud-speed", payload=b"apk-bytes", version="79.0-DenzaHUD.1", **overrides):
    value = {
        "version_name": version,
        "version_code": 79000001,
        "package": releases.APPS[app],
        "date": "2026-10-18",
        "bytes": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "file": f"{app}-{version}.apk",
    }
    value.update(overrides)
    return value


class InstallTest(unittest.TestCase):
    def setUp(self):
        self._directory = tempfile.TemporaryDirectory()
        self.root = Path(self._directory.name)

    def tearDown(self):
        self._directory.cleanup()

    def stage(self, payload=b"apk-bytes", name=".upload.part"):
        (self.root / name).write_bytes(payload)
        return name

    def test_moves_the_staged_apk_and_records_it(self):
        result = install(self.root, "hud-speed", self.stage(), entry())
        self.assertEqual((self.root / "hud-speed-79.0-DenzaHUD.1.apk").read_bytes(), b"apk-bytes")
        self.assertFalse((self.root / ".upload.part").exists())
        written = json.loads((self.root / "releases.json").read_text())
        self.assertEqual(written, result)
        self.assertEqual(written["version"], releases.SCHEMA)
        self.assertEqual(written["apps"]["hud-speed"]["file"], "hud-speed-79.0-DenzaHUD.1.apk")

    def test_a_new_version_replaces_the_previous_file_only(self):
        install(self.root, "hud-speed", self.stage(), entry())
        install(self.root, "denza-apps", self.stage(b"denza"), entry(
            "denza-apps", b"denza", "0.7.0", version_code=78))
        install(self.root, "hud-speed", self.stage(b"newer"), entry(payload=b"newer", version="79.0-DenzaHUD.2"))
        names = sorted(path.name for path in self.root.glob("*.apk"))
        self.assertEqual(names, ["denza-apps-0.7.0.apk", "hud-speed-79.0-DenzaHUD.2.apk"])
        apps = json.loads((self.root / "releases.json").read_text())["apps"]
        self.assertEqual(sorted(apps), ["denza-apps", "hud-speed"])

    def test_rebuilding_the_same_version_keeps_its_file(self):
        install(self.root, "hud-speed", self.stage(), entry())
        install(self.root, "hud-speed", self.stage(b"rebuilt"), entry(payload=b"rebuilt"))
        self.assertEqual((self.root / "hud-speed-79.0-DenzaHUD.1.apk").read_bytes(), b"rebuilt")

    def test_bytes_that_differ_from_the_entry_are_discarded(self):
        name = self.stage(b"corrupted")
        with self.assertRaises(ReleaseError):
            install(self.root, "hud-speed", name, entry())
        self.assertFalse((self.root / name).exists())
        self.assertFalse((self.root / "releases.json").exists())

    def test_rejects_a_package_that_is_not_the_apps(self):
        with self.assertRaises(ReleaseError):
            install(self.root, "hud-speed", self.stage(), entry(package="dev.denza.apps"))

    def test_rejects_names_that_leave_the_directory(self):
        for bad in ["../hud-speed-79.0-DenzaHUD.1.apk", "hud-speed-79.0-DenzaHUD.1.apk/x",
                    "denza-apps-79.0-DenzaHUD.1.apk", "hud-speed-.apk"]:
            with self.subTest(bad=bad), self.assertRaises(ReleaseError):
                install(self.root, "hud-speed", self.stage(), entry(file=bad))
        with self.assertRaises(ReleaseError):
            install(self.root, "hud-speed", self.stage(), entry(version="../x", file="hud-speed-../x.apk"))
        for staged in ["../x.part", "visible.part", ".x"]:
            with self.subTest(staged=staged), self.assertRaises(ReleaseError):
                install(self.root, "hud-speed", staged, entry())

    def test_rejects_unknown_apps_and_fields(self):
        with self.assertRaises(ReleaseError):
            install(self.root, "other", self.stage(), entry())
        extra = entry()
        extra["note"] = "x"
        with self.assertRaises(ReleaseError):
            install(self.root, "hud-speed", self.stage(), extra)


if __name__ == "__main__":
    unittest.main()
