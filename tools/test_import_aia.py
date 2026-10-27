"""Specific source-semantic cases plus full-catalog AIA review."""
import unittest

from csr_storage import read_catalog
from import_aia import ASSETS, REVISION
from review_aia import review


class AiaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registers = {r["name"]: r for r in read_catalog(ASSETS)["registers"]}

    def field(self, name, key):
        return next(f for f in self.registers[name]["fields"] if f["name"] == key)

    def bits(self, name, key, width=64):
        b = next(b for b in self.field(name, key)["layouts"] if b["xlen"] == width)
        return b["msb"], b["lsb"]

    def test_full_catalog_and_source_evidence(self):
        result = review(False)
        self.assertEqual(41, result["directCsrTableEntries"])
        self.assertEqual(14, result["highHalfCsrs"])
        self.assertGreater(len(result["sourceEvidence"]), 5)

    def test_topi_and_topei_have_different_identity_and_priority_widths(self):
        for name in ("mtopi", "stopi", "vstopi"):
            self.assertEqual((27, 16), self.bits(name, "IID"))
            self.assertEqual((7, 0), self.bits(name, "IPRIO"))
            self.assertEqual("RO", self.registers[name]["access"])
        for name in ("mtopei", "stopei", "vstopei"):
            self.assertEqual((26, 16), self.bits(name, "IID"))
            self.assertEqual((10, 0), self.bits(name, "IPRIO"))
            self.assertEqual("RW", self.registers[name]["access"])
            self.assertIn("写入值被忽略", self.registers[name]["notes"])

    def test_high_half_ras_bits_and_priority_projection(self):
        self.assertEqual((35, 35), self.bits("mie", "RASLIE"))
        self.assertEqual((3, 3), self.bits("mieh", "RASLIE", 32))
        self.assertEqual((43, 43), self.bits("mip", "RASHIP"))
        self.assertEqual((11, 11), self.bits("miph", "RASHIP", 32))
        self.assertEqual((15, 8), self.bits("hviprio1h", "IPRIO13", 32))
        self.assertEqual((31, 24), self.bits("hviprio1h", "IPRIO15", 32))
        self.assertEqual([32], self.registers["hviprio1h"]["xlens"])

    def test_virtual_controls_and_priority_slots_match_normative_tables(self):
        self.assertEqual((30, 30), self.bits("hvictl", "VTI"))
        self.assertEqual((27, 16), self.bits("hvictl", "IID"))
        self.assertEqual("WARL", self.field("hvictl", "IID")["access"])
        self.assertEqual({"IPRIO1", "IPRIO5", "IPRIO13", "IPRIO14", "IPRIO15"}, {f["name"] for f in self.registers["hviprio1"]["fields"]})
        self.assertEqual((15, 8), self.bits("hviprio1", "IPRIO1"))
        self.assertEqual((31, 24), self.bits("hviprio1", "IPRIO5"))
        self.assertEqual((63, 56), self.bits("hviprio2", "IPRIO23"))

    def test_window_selectors_are_not_csr_addresses(self):
        self.assertEqual(0x350, self.registers["miselect"]["locator"]["address"])
        self.assertEqual(0x351, self.registers["mireg"]["locator"]["address"])
        self.assertEqual("WARL", self.field("miselect", "SELECT")["access"])
        self.assertIn("eithreshold", self.field("miselect", "SELECT")["values"]["114"])
        self.assertIn("VS 不可访问", self.field("vsiselect", "SELECT")["values"]["48"])
        self.assertIn("WLRL", self.registers["mireg"]["notes"])
        self.assertFalse(any(name in self.registers for name in ("eidelivery", "eithreshold", "eip0", "eie0", "domaincfg")))

    def test_supplements_keep_manual_identity_and_classify_aia_interrupt_causes(self):
        self.assertEqual("riscv/manual/csr/mip", self.registers["mip"]["id"])
        self.assertEqual("manual", self.registers["mip"]["namespace"])
        self.assertTrue(any(s["revision"] == REVISION for s in self.registers["mip"]["sources"]))
        self.assertIn("高优先级 RAS", self.field("mcause", "CODE")["values"]["43"])
        self.assertIn("标准本地中断保留", self.field("mcause", "CODE")["values"]["32"])
        self.assertIn("自定义中断", self.field("mcause", "CODE")["values"]["48"])
        self.assertIn("不强制", self.field("hgeip", "GEIP")["summary"])

    def test_aia_software_pending_aliases_do_not_keep_unconditional_base_access(self):
        self.assertIn("mvip[9]", self.field("sip", "SEIP")["summary"])
        self.assertIn("RW", self.field("sip", "SEIP")["access"])
        self.assertIn("RO（mvien[9]=1）", self.field("mip", "SEIP")["access"])
        self.assertIn("虚拟可见控制", self.field("mvien", "LOCAL16_23")["summary"])
        self.assertIn("RO1", self.field("hvien", "LOCAL16_23")["access"])


if __name__ == "__main__":
    unittest.main()
