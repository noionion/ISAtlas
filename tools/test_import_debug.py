import unittest

from csr_storage import read_catalog
from import_debug import ASSETS, arithmetic, enrich, REVISION
from review_debug import review


class DebugTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pack = read_catalog(ASSETS)
        cls.regs = {r["name"]: r for r in cls.pack["registers"]}

    def field(self, reg, name):
        return next(f for f in self.regs[reg]["fields"] if f["name"] == name)

    def test_all_hart_csrs_formats_and_evidence(self):
        result = review(False)
        self.assertEqual(14, result["directCsrs"])
        self.assertEqual(150, result["reviewedFields"])
        self.assertEqual(7, len(result["tdata1Formats"]))

    def test_prior_manual_identities_are_preserved_and_only_three_csrs_are_added(self):
        self.assertEqual("riscv/manual/csr/dcsr", self.regs["dcsr"]["id"])
        self.assertEqual("fields", self.regs["dcsr"]["coverage"])
        self.assertEqual({"mscontext", "tcontrol", "tinfo"}, {r["name"] for r in self.pack["registers"] if r["namespace"] == "debug"})
        self.assertFalse(any(n in self.regs for n in ("dmcontrol", "dmstatus", "dmi", "priv", "mcontrol6", "textra32")))

    def test_composite_encodings_are_not_attached_to_narrow_physical_fields(self):
        size = self.field("tdata1", "mcontrol.SIZELO")
        self.assertEqual({"0", "1", "2", "3"}, set(size["values"]))
        self.assertIn("SIZEHI<<2", size["typeRule"])
        self.assertEqual([64], [b["xlen"] for b in self.field("tdata1", "mcontrol.SIZEHI")["layouts"]])
        hit = self.field("tdata1", "mcontrol6.HIT0")
        self.assertEqual({"0", "1"}, set(hit["values"]))
        self.assertIn("不连续", hit["typeRule"])
        self.assertEqual(22, hit["layouts"][0]["lsb"])
        self.assertEqual(25, self.field("tdata1", "mcontrol6.HIT1")["layouts"][0]["lsb"])

    def test_trace_actions_and_normative_source_discrepancy_are_explicit(self):
        action = self.field("tdata1", "mcontrol6.ACTION")
        self.assertEqual(16, len(action["values"]))
        self.assertIn("开启 trace", action["values"]["2"])
        self.assertIn("Trace 规范保留", action["values"]["5"])
        self.assertIn("TM 外部触发输出 1", action["values"]["9"])
        type_field = self.field("tdata1", "tmexttrigger.TYPE")
        self.assertEqual({"7"}, set(type_field["values"]))
        self.assertIn("reset=4", type_field["typeRule"])

    def test_context_alias_layouts_and_privileges_follow_the_explicit_legacy_exception(self):
        self.assertEqual(self.field("mcontext", "HCONTEXT")["layouts"], self.field("hcontext", "HCONTEXT")["layouts"])
        self.assertEqual(self.field("scontext", "DATA")["layouts"], self.field("mscontext", "DATA")["layouts"])
        self.assertEqual("S", self.regs["mscontext"]["privilege"])
        self.assertEqual("RO", self.regs["tinfo"]["access"])

    def test_safe_bit_expressions_and_deterministic_debug_overlay(self):
        self.assertEqual(27, arithmetic("XLEN-5", 32))
        self.assertEqual(63, arithmetic("DXLEN-1", 64))
        with self.assertRaises(ValueError):
            arithmetic("__import__('os').system('anything')", 64)
        actual, _ = enrich(self.pack["registers"])
        self.assertEqual(sorted(self.pack["registers"], key=lambda r: r["id"]), sorted(actual, key=lambda r: r["id"]))


if __name__ == "__main__":
    unittest.main()
