import copy
import json
from pathlib import Path
import tempfile
import unittest
from csr_storage import write_catalog, read_catalog, catalog_sha256


class CsrStorageTests(unittest.TestCase):
    def setUp(self):
        self.pack = json.loads((Path(__file__).resolve().parents[1] / "core/src/test/resources/riscv-manual-sample.json").read_text(encoding="utf-8"))

    def test_roundtrip_dedup_and_deterministic_output(self):
        original = copy.deepcopy(self.pack)
        self.pack["registers"] += [copy.deepcopy(self.pack["registers"][0])]
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(1, write_catalog(directory, self.pack))
            actual = read_catalog(directory)
            original["registers"].sort(key=lambda r: (r.get("namespace", original["namespace"]), r["locator"]["address"], r["name"]))
            self.assertEqual(original, actual)
            digest = catalog_sha256(directory)
            write_catalog(directory, original)
            self.assertEqual(digest, catalog_sha256(directory))

    def test_conflicting_identity_does_not_overwrite_existing_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            write_catalog(directory, self.pack)
            digest = catalog_sha256(directory)
            other = copy.deepcopy(self.pack["registers"][0])
            other["summary"] += " conflict"
            self.pack["registers"].append(other)
            with self.assertRaises(ValueError):
                write_catalog(directory, self.pack)
            self.assertEqual(digest, catalog_sha256(directory))

    def test_same_address_across_namespaces_is_preserved(self):
        reg = copy.deepcopy(self.pack["registers"][0])
        reg["namespace"] = "vendor"
        reg["id"] = "riscv/vendor/csr/" + reg["name"]
        self.pack["registers"].append(reg)
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(0, write_catalog(directory, self.pack))
            self.assertEqual(len(self.pack["registers"]), len(read_catalog(directory)["registers"]))


if __name__ == "__main__":
    unittest.main()
