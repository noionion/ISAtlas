from copy import deepcopy
import json
import unittest

from instruction_sources import ROOT
import manual_pseudoinstructions as adapter


class ManualPseudoTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.assets = ROOT / "data/official-packages/manual"
        cls.instructions = {entry["name"]: entry for path in (cls.assets / "inst").glob("manual-*.json")
                            for entry in [json.loads(path.read_text(encoding="utf-8"))]}
        cls.entries = adapter.build(cls.instructions)

    def test_all_files_sources_bilingual_fields_and_literal_sequences(self):
        self.assertEqual({"pseudoinstructions": 17, "expansions": 18, "errors": []}, adapter.review_assets(self.assets, self.instructions))

    def test_pseudos_have_no_fake_machine_encoding(self):
        for entry in self.entries:
            self.assertNotIn("encodings", entry)
            self.assertNotIn("mask", entry)
            self.assertNotIn("match", entry)
        bad = deepcopy(self.entries)
        bad[0]["encodings"] = []
        with self.assertRaises(ValueError):
            adapter.review(bad, self.instructions)

    def test_unsigned_mapping_and_temporary_register_conditions(self):
        for entry in self.entries:
            if entry["name"] in {"vmsge.vx", "vmsgeu.vx"}:
                self.assertEqual(5, len(entry["expansions"]))
                base = "vmsltu.vx" if entry["name"] == "vmsgeu.vx" else "vmslt.vx"
                self.assertTrue(all(variant["steps"][0]["name"] == base for variant in entry["expansions"][:4]))
                self.assertEqual(["vt"], entry["expansions"][2]["clobbers"])
                self.assertEqual(4, len(entry["expansions"][3]["steps"]))

    def test_unknown_steps_and_changed_literal_expansions_are_rejected(self):
        for name in ["no.instruction", "vmor.mm"]:
            bad = deepcopy(self.entries)
            vector = next(entry for entry in bad if entry["name"] == "vmsge.vx")
            vector["expansions"][0]["steps"][0] = {"name": name, "assembly": name + " vd, va, x"}
            with self.assertRaises(ValueError):
                adapter.review(bad, self.instructions)

    def test_source_fingerprint_tampering_is_rejected(self):
        bad = deepcopy(self.entries)
        bad[0]["semanticSources"][0]["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            adapter.review(bad, self.instructions)

    def test_call_tail_are_examples_not_a_relocation_encoder(self):
        call = next(entry for entry in self.entries if entry["name"] == "call")
        tail = next(entry for entry in self.entries if entry["name"] == "tail")
        self.assertIn("relocations", call["notesEn"])
        self.assertIn("not a universal assembler", tail["notesEn"])
        self.assertTrue(all(variant["clobbers"] == ["x7"] for variant in tail["expansions"]))


if __name__ == "__main__":
    unittest.main()
