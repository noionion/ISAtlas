"""Source-equivalence and independent provenance checks for built-in archives."""
import itertools
import json
import unittest
from official_packages import ASSETS, DATA, ORDER, read_catalog


def canonical(value):
    if isinstance(value, dict):
        return {str(k): canonical(v) for k, v in value.items()}
    if isinstance(value, list):
        return sorted((canonical(v) for v in value), key=lambda v: json.dumps(v, sort_keys=True))
    return " ".join(value.split()) if isinstance(value, str) else value


class OfficialPackageTests(unittest.TestCase):
    def test_all_enabled_matches_the_original_sequential_specification_adapters(self):
        from import_isa_manual import build_registers
        from import_aia import enrich as aia
        from import_debug import enrich as debug
        from import_spmp import enrich as spmp
        expected = spmp(debug(aia(build_registers())[0])[0])[0]
        self.assertEqual(canonical(expected), canonical(read_catalog()["registers"]))

    def test_every_combination_has_only_its_own_sources_and_keeps_base_identities(self):
        base = read_catalog(enabled=())["registers"]
        base_ids = {r["id"] for r in base}
        for flags in itertools.product((False, True), repeat=3):
            enabled = {name for name, on in zip(ORDER[1:], flags) if on}
            regs = read_catalog(enabled=enabled)["registers"]
            self.assertTrue(base_ids <= {r["id"] for r in regs})
            self.assertEqual(402 + sum({"aia": 26, "debug": 3, "spmp": 3}[n] for n in enabled), len(regs))
            for reg in regs:
                self.assertTrue(reg["namespace"] == "manual" or reg["namespace"] in enabled)
                self.assertTrue(all(s["namespace"] in enabled for s in reg.get("sources", [])))
                for field in reg["fields"]:
                    for namespace, repository in (("aia", "riscv-aia"), ("debug", "riscv-debug-spec"), ("spmp", "riscv-spmp")):
                        if repository + "/blob/" in field["sourceUrl"]:
                            self.assertIn(namespace, enabled)

    def test_archives_match_readable_source_files_exactly(self):
        import hashlib
        import zipfile
        index = json.loads((ASSETS / "riscv-packages.json").read_text(encoding="utf-8"))
        for item in index["packages"]:
            archive = ASSETS / item["file"]
            self.assertEqual(item["sha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
            folder = DATA / item["id"]
            expected = {p.relative_to(folder).as_posix(): p.read_bytes() for p in folder.rglob("*") if p.is_file()}
            with zipfile.ZipFile(archive) as z:
                self.assertEqual(expected, {n: z.read(n) for n in z.namelist()})


if __name__ == "__main__":
    unittest.main()
