from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from instruction_sources import ROOT
from instruction_context import representative_word
from review_manual_instructions import matches
from manual_pseudoinstructions import build as build_references
import manual_pseudo_coverage as coverage


class ManualAliasTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = {entry["name"]: entry for path in (ROOT / "data/official-packages/manual/inst").glob("manual-*.json")
                    for entry in [json.loads(path.read_text(encoding="utf-8"))]}
        cls.references = build_references(cls.data)

    def test_full_tree_scan_has_no_unreviewed_mentions_or_example_names(self):
        report = coverage.review_coverage(self.data, self.references)
        self.assertEqual(105, report["namedForms"])
        self.assertEqual(88, report["encodedAliases"])
        self.assertEqual(8, report["sequenceReferences"])
        self.assertEqual(9, report["mentionedOnly"])
        self.assertEqual(77, report["mentionsReviewed"])
        self.assertEqual(report["sourceFilesScanned"], 72)
        self.assertGreater(report["assemblyExamplesReviewed"], 100)
        self.assertEqual([], report["errors"])

    def test_missing_name_prevents_a_false_complete_report(self):
        bad = dict(self.data)
        bad.pop("csrr")
        with self.assertRaisesRegex(ValueError, "Missing reviewed pseudo names"):
            coverage.review_coverage(bad, self.references)

    def test_unknown_declaration_or_assembly_example_fails_scan(self):
        for text in ["A pseudoinstruction not in the reviewed inventory.\n", "  imaginary x1, x2\n"]:
            with tempfile.TemporaryDirectory() as directory:
                Path(directory, "new.adoc").write_text(text, encoding="utf-8")
                with patch.object(coverage, "SOURCE", Path(directory)):
                    with self.assertRaises(ValueError):
                        coverage.review_coverage(self.data, self.references)

    def test_trap_record_pseudoinstructions_are_not_assembler_entries(self):
        records = coverage.review_coverage(self.data, self.references)["records"]
        traps = [row for row in records if row["classification"] == "not-assembler"]
        self.assertGreaterEqual(len(traps), 10)
        self.assertTrue(all(row["path"] == "hypervisor.adoc" and not row["names"] for row in traps))

    def test_every_alias_has_a_valid_witness_and_matches_its_canonical_base(self):
        for name, entry in self.data.items():
            if entry["kind"] != "alias":
                continue
            for variant in entry["encodings"]:
                for width in variant["xlens"]:
                    word = representative_word(variant, width)
                    with self.subTest(name=name, width=width):
                        self.assertTrue(matches(variant, word, width))
                        self.assertTrue(any(matches(base, word, width) for base in self.data[entry["aliasOf"]]["encodings"]))

    def test_register_equalities_are_constraints_not_just_summary_text(self):
        checked = 0
        for name, entry in self.data.items():
            for variant in entry["encodings"]:
                for relation in variant.get("operandRelations", []):
                    width = variant["xlens"][-1]
                    word = representative_word(variant, width)
                    field = next(item for item in variant["fields"] if item.get("operand") == relation["left"])
                    invalid = word ^ (1 << field["lsb"])
                    with self.subTest(name=name, relation=relation):
                        self.assertFalse(matches(variant, invalid, width))
                    checked += 1
        self.assertGreater(checked, 10)

    def test_immediate_transformations_do_not_silently_claim_a_single_fixed_encoding(self):
        for name in ["vmslt.vi", "vmsltu.vi", "vmsge.vi", "vmsgeu.vi"]:
            self.assertNotIn(name, self.data)
            reference = next(entry for entry in self.references if entry["name"] == name)
            self.assertIn("i-1", reference["expansions"][0]["steps"][0]["assembly"])
            self.assertNotIn("encodings", reference)


if __name__ == "__main__":
    unittest.main()
