import unittest

from csr_storage import read_catalog
from import_spmp import ASSETS, REVISION, direct_rows, enrich
from review_spmp import review, evidence


class SpmpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pack=read_catalog(ASSETS)
        cls.regs={r["name"]:r for r in cls.pack["registers"]}

    def test_all_records_are_reviewed_against_the_pinned_source(self):
        r=review(False)
        self.assertEqual(3,r["directSpmpCsrs"])
        self.assertEqual(10,r["addedManualWindows"])
        self.assertEqual([],r["layoutErrors"])
        self.assertGreater(r["reviewedCsrs"],90)

    def test_only_explicit_addresses_and_manual_windows_are_added(self):
        self.assertEqual({"mpmpdeleg":0x316,"spmpen":0x183,"spmpenh":0x193},{n:v[0] for n,v in direct_rows().items()})
        self.assertEqual(0x155,self.regs["sireg4"]["locator"]["address"])
        self.assertEqual(0x352,self.regs["mireg2"]["locator"]["address"])
        self.assertEqual("manual",self.regs["sireg2"]["namespace"])
        for name in ("spmpcfg0","spmpaddr0","hspmpdeleg","vspmpen","hspmpen"):
            self.assertNotIn(name,self.regs)

    def test_rv32_delegation_granularity_is_preserved_without_silent_reinterpretation(self):
        reg=self.regs["mpmpdeleg"]
        self.assertEqual([dict(xlen=32,msb=6,lsb=2),dict(xlen=64,msb=6,lsb=0)],reg["fields"][0]["layouts"])
        self.assertTrue(any(r["label"]=="0" and r["access"]=="WARL" for r in reg["regions"]))
        self.assertIn("粒度差异",reg["fields"][0]["typeRule"])

    def test_shared_permissions_are_not_mistaken_for_ordinary_rwx(self):
        fields={f["name"]:f for f in self.regs["sireg2"]["fields"]}
        self.assertEqual({"0","1","2","3"},set(fields["spmpcfg.A"]["values"]))
        rule=fields["spmpcfg.R"]["typeRule"]
        self.assertIn("110 为 S 读写/U 仅读",rule)
        self.assertIn("111 为 S 读写执行/U 仅执行",rule)
        self.assertIn("SHARED=1,U=0 保留",rule)
        self.assertEqual(9,fields["spmpcfg.SHARED"]["layouts"][0]["lsb"])

    def test_enable_high_alias_and_lock_constraints(self):
        low,high=self.regs["spmpen"],self.regs["spmpenh"]
        self.assertEqual([32],high["xlens"])
        self.assertEqual(64,len(low["fields"]))
        self.assertEqual(32,len(high["fields"]))
        self.assertEqual("EN32",high["fields"][0]["name"])
        self.assertEqual(0,high["fields"][0]["layouts"][0]["lsb"])
        self.assertIn("L=1",high["fields"][0]["typeRule"])

    def test_indirect_views_keep_both_aia_and_spmp_accessible(self):
        for mode in ("s","m"):
            select=self.regs[mode+"iselect"]
            values=select["fields"][0]["values"]
            self.assertIn("eithreshold",values["114"])
            self.assertIn("SPMP[63]",values["319"])
            self.assertEqual({"","spmpaddr"},{f.get("view","") for f in self.regs[mode+"ireg"]["fields"]})
        self.assertIn("Smpmpdeleg",self.regs["pmpaddr63"]["notes"])
        self.assertEqual("riscv/manual/csr/pmpaddr63",self.regs["pmpaddr63"]["id"])

    def test_overlay_is_idempotent_and_evidence_rejects_other_revisions(self):
        actual,_=enrich(self.pack["registers"])
        self.assertEqual(sorted(self.pack["registers"],key=lambda r:r["id"]),sorted(actual,key=lambda r:r["id"]))
        source=self.regs["mpmpdeleg"]["fields"][0]["sourceUrl"]
        self.assertTrue(evidence(source)["sha256"])
        with self.assertRaises(AssertionError):
            evidence(source.replace(REVISION,"main"))


if __name__=="__main__":
    unittest.main()
