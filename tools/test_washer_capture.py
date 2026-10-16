import json
from pathlib import Path
import tempfile
import unittest

import washer_capture as capture


class WasherCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, name, value=0, payload=0, lost=0, done=True, vehicle="168"):
        directory = self.root / name
        directory.mkdir()
        (directory / "manifest.json").write_text(json.dumps({
            "identity": {"persist.sys.AutoType": vehicle}, "state": name}))
        (directory / "firmware-hashes.txt").write_text("known hash\n")
        (directory / "fids.tsv").write_text(
            "CAT\t1001\t0x05500031\tNAME\nBEGIN\t1\t1\t2\n"
            f"FID\t1\t1\t1001\t0x05500031\t0x{value:08X}\t\n"
            f"FID\t2\t2\t1001\t0x05500031\t0x{value:08X}\t\n"
            + ("DONE\t2\t3\n" if done else ""))
        (directory / "raw-can.log").write_text(
            f"FRAME seq=1 t_ns=1 id=0x55 sub=0x00 ch=0 cnt=1 len=1 payload={payload:02x}\n"
            f"FRAME seq=2 t_ns=2 id=0x55 sub=0x00 ch=0 cnt=2 len=1 payload={payload:02x}\n"
            f"DONE received=2 dropped={lost} callback_errors=0 queued=0\n")
        return directory

    def test_stable_bit_flip_and_scalar_change_remain_candidates(self):
        before = self.fixture("liquid-present")
        after = self.fixture("empty", value=1, payload=4)
        result = capture.compare(before, after)
        self.assertEqual(1, len(result["changed_fids"]))
        self.assertEqual(1, len(result["separated_bits"]))
        self.assertEqual(2, result["separated_bits"][0]["bit_lsb0"])
        self.assertIn("Candidates only", result["meaning"])

    def test_missing_scalar_footer_or_raw_loss_rejects_comparison(self):
        before = self.fixture("before")
        for name, kwargs in (("truncated", {"done": False}), ("loss", {"lost": 1})):
            with self.subTest(name=name):
                after = self.fixture(name, **kwargs)
                self.assertFalse(capture.summarize(after)["complete"])
                with self.assertRaisesRegex(ValueError, "complete loss-free"):
                    capture.compare(before, after)

    def test_rejects_other_car_even_with_matching_firmware(self):
        with self.assertRaisesRegex(ValueError, "Vehicle/build mismatch"):
            capture.compare(self.fixture("n9"), self.fixture("z9", vehicle="170"))

    def test_no_data_is_preserved_not_coerced_to_zero(self):
        before = self.fixture("before", value=0xFFFFD8E5)  # -10011, not zero
        after = self.fixture("after", value=0)
        result = capture.compare(before, after)
        self.assertEqual([["0xFFFFD8E5", ""]], [list(v) for v in result["changed_fids"][0]["before"]])

    def test_changed_firmware_refuses_comparison(self):
        before, after = self.fixture("before"), self.fixture("after")
        (after / "firmware-hashes.txt").write_text("other hash\n")
        with self.assertRaisesRegex(ValueError, "Firmware hashes differ"):
            capture.compare(before, after)


if __name__ == "__main__":
    unittest.main()
