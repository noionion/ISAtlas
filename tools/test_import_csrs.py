import unittest
from import_isa_manual import addresses, arithmetic, wave_fields, byte_fields, build_registers, SOURCE, REVISION


class ManualImporterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registers = {reg["name"]: reg for reg in build_registers()}

    def test_main_table_expands_only_explicit_numeric_families(self):
        rows = addresses((SOURCE / "priv-csrs.adoc").read_text(encoding="utf-8"))
        lookup = {name: address for address, _, name, _ in rows}
        self.assertEqual(0xB03, lookup["mhpmcounter3"])
        self.assertEqual(0xB1F, lookup["mhpmcounter31"])
        self.assertEqual(0x3EF, lookup["pmpaddr63"])
        self.assertEqual(0x644, lookup["hip"])
        self.assertEqual(377, len(rows))

    def test_bytefield_geometry_is_not_bit_width(self):
        text = (SOURCE / "images/bytefield/mcausereg.edn").read_text(encoding="utf-8")
        fields = byte_fields(text, 64)
        self.assertEqual(("Interrupt", 63, 63), fields[0])
        self.assertEqual(("Exception Code (WLRL)", 62, 0), fields[1])
        text = (SOURCE / "images/bytefield/rv64satp.edn").read_text(encoding="utf-8")
        self.assertEqual(("MODE (WARL)", 63, 60), byte_fields(text, 64)[0])

    def test_wave_brackets_inside_labels_and_attrs_are_not_array_terminators(self):
        text = (SOURCE / "images/wavedrom/float-csr.edn").read_text(encoding="utf-8")
        self.assertEqual(7, len(wave_fields(text)))
        text = (SOURCE / "images/wavedrom/mstatusreg.edn").read_text(encoding="utf-8")
        self.assertIn(("MPP[1:0]", 12, 11), wave_fields(text))

    def test_width_expressions_never_execute_code(self):
        self.assertEqual(62, arithmetic("MXLEN-2", 64))
        with self.assertRaises(ValueError):
            arithmetic("__import__('os').system('anything')", 64)

    def test_explicit_reserved_fields_and_prose_override_are_preserved(self):
        status = self.registers["mstatus"]
        self.assertTrue(any(region["access"] == "WPRI" for region in status["regions"]))
        mxl = next(field for field in self.registers["misa"]["fields"] if field["name"] == "MXL")
        self.assertEqual("RO", mxl["access"])
        code = next(field for field in self.registers["mcause"]["fields"] if field["name"] == "CODE")
        self.assertEqual("WLRL", code["access"])
        self.assertTrue(any(region["access"] == "WPRI" for region in self.registers["fcsr"]["regions"]))
        self.assertTrue(any(region["access"] == "Reserved" for region in self.registers["mnstatus"]["regions"]))
        self.assertTrue(any(field["name"] == "HPM28" for field in self.registers["mcounteren"]["fields"]))
        low_half = self.registers["medeleg"]["fields"][0]["layouts"]
        self.assertIn({"xlen": 32, "msb": 31, "lsb": 0}, low_half)
        self.assertEqual("fields", self.registers["medelegh"]["coverage"])

    def test_all_imported_bits_are_valid_nonoverlapping_and_traceable(self):
        for reg in self.registers.values():
            self.assertEqual(REVISION, reg["sourceRevision"])
            self.assertEqual("manual", reg["namespace"])
            for width in reg["xlens"]:
                used = set()
                for item in reg["fields"] + reg["regions"]:
                    for loc in item["layouts"]:
                        if loc["xlen"] != width:
                            continue
                        self.assertTrue(0 <= loc["lsb"] <= loc["msb"] < width)
                        bits = set(range(loc["lsb"], loc["msb"] + 1))
                        self.assertFalse(used & bits, (reg["name"], item, width))
                        used |= bits

    def test_all_field_descriptions_are_bilingual_with_matching_encoding_domains(self):
        fields = [field for reg in self.registers.values() for field in reg["fields"]]
        self.assertEqual(1591, len(fields))
        for field in fields:
            self.assertRegex(field["summary"], r"[\u4e00-\u9fff]")
            self.assertTrue(field["summaryEn"].strip())
            self.assertEqual(field["values"].keys(), field["valuesEn"].keys())
            for width, values in field["valuesByXlen"].items():
                self.assertEqual(values.keys(), field["valuesEnByXlen"][width].keys())

    def test_architectural_encoding_edge_cases_and_access_modes(self):
        def field(csr, name):
            return next(item for item in self.registers[csr]["fields"] if item["name"] == name)
        self.assertIn("保留", field("frm", "ROUNDINGMODE")["values"]["7"])
        self.assertEqual("Sv32", field("satp", "MODE")["valuesByXlen"][32]["1"])
        self.assertEqual("Sv39x4", field("hgatp", "MODE")["valuesByXlen"][64]["8"])
        self.assertEqual("PMLEN=16", field("mseccfg", "PMM")["valuesByXlen"][64]["3"])
        self.assertIn("只读零", field("mseccfgh", "PMM")["valuesByXlen"][32]["0"])
        self.assertEqual("RO", field("mip", "MEIP")["access"])
        self.assertEqual("RO", field("hip", "VSEIP")["access"])
        self.assertEqual("RW", field("hvip", "VSEIP")["access"])
        self.assertEqual([32, 64], self.registers["pmpcfg2"]["xlens"])
        self.assertEqual(40, len(self.registers["pmpcfg2"]["fields"]))
        self.assertIn("VU 模式", field("vsstatus", "UXL")["summary"])


if __name__ == "__main__":
    unittest.main()
