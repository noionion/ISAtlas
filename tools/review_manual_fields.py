"""Independently audit the emitted catalog, including source evidence.

This checks all records, not a sample. Evidence proves traceability; it is
not a claim that string matching can prove a translation's correctness.
"""
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import unquote
from csr_storage import read_catalog, catalog_sha256

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
MANUAL = ROOT / "upstream/riscv-isa-manual"
REVISION = "584390d9d872835b1bc45ab9ea946b56db3ad485"
EXTERNAL_DEBUG = set()  # All eleven former address-only entries are now supplemented from Debug 1.0.
PARTIAL_INTERRUPT = {"hie", "hip"}


def evidence(field, reg):
    if "riscv-spmp/blob/" in field["sourceUrl"]:
        from review_spmp import evidence as spmp_evidence
        return spmp_evidence(field["sourceUrl"])
    if "riscv-debug-spec/blob/" in field["sourceUrl"]:
        from review_debug import evidence as debug_evidence
        return debug_evidence(field["sourceUrl"])
    if "riscv-aia/blob/" in field["sourceUrl"]:
        from review_aia import evidence as aia_evidence
        return aia_evidence(field["sourceUrl"])
    path = unquote(field["sourceUrl"].split(f"/blob/{REVISION}/", 1)[1]).split("#")[0]
    source = MANUAL / path
    assert source.is_file(), (reg["name"], field["name"], path)
    text = source.read_text(encoding="utf-8")
    name, csr = field["name"], reg["name"]
    terms = [name] + field.get("aliases", [])
    if re.fullmatch(r"(?:m|s|vs)i(?:select|reg[2-6]?)", csr) and name in ("DATA", "SELECT"):
        terms.append(csr)
    if csr.endswith("h") and name == csr.upper():
        terms.append(csr[:-1])  # The chapter uses cycle[h]/time[h]/instret[h].
    if re.fullmatch(r"PMP\d+[LARWX]", name):
        terms = [r"PMP configuration", r"PMP.*entries"]
    elif csr.startswith("pmpaddr"):
        terms = [r"PMP address"]
    elif name.startswith("HPM") or "hpmcounter" in csr:
        terms += [r"hpmcounter", r"HPM"]
    elif csr.startswith("mhpmevent"):
        terms += [r"mhpmevent"]
    elif re.fullmatch(r"SE[123]", name):
        terms += [r"bit 63", r"stateen"]
    elif csr.endswith("counteren") or csr.endswith("inhibit"):
        terms += [csr, r"counteren", r"countinhibit"]
    elif name.startswith("OF") and csr == "scountovf":
        terms += [r"scountovf"]
    elif "deleg" in csr:
        terms += [csr.removesuffix("h")]
    terms += {"SCRATCH": [r"scratch"], "CODE": [r"cause"], "INT": [r"interrupt"],
              "ADDRESS": [r"address"], "ROUNDINGMODE": [r"rounding mode"],
              "ARCHITECTUREID": [r"marchid"], "IMPLEMENTATION": [r"mimpid"],
              "GEIE": [r"hgeie"], "GEIP": [r"hgeip"],
              "HARTID": [r"mhartid"], "BANK": [r"JEDEC"], "OFFSET": [r"JEDEC"]}.get(name, [])
    match = next((m for term in terms if (m := re.search(r"(?<![A-Za-z])(?:" + term + r")(?![A-Za-z])", text, re.I))), None)
    assert match is not None, (csr, name, path)
    line = text.count("\n", 0, match.start()) + 1
    excerpt = " ".join(text[max(0, match.start() - 60):match.end() + 180].split())
    return {"path": path, "line": line, "excerpt": excerpt}


def review():
    pack = read_catalog(ASSETS)
    registers = [reg for reg in pack["registers"] if reg["namespace"] == "manual"]
    assert len(registers) == 402
    assert len({reg["id"] for reg in pack["registers"]}) == len(pack["registers"])
    assert {r["name"] for r in registers if r["coverage"] == "address-only"} == EXTERNAL_DEBUG
    rows, gaps, enumerated = [], [], 0
    for reg in registers:
        assert re.search(r"[\u4e00-\u9fff]", reg["summary"]) and reg["summaryEn"].strip()
        for field in reg["fields"]:
            key = reg["name"] + "." + field["name"]
            assert re.search(r"[\u4e00-\u9fff]", field["summary"]), key
            assert field["summaryEn"].strip() and len(field["summary"]) <= 120, key
            assert not any(token in field["summary"] for token in ("未描述", "待补", "图示字段", "VS 模式的状态视图。")), key
            common, common_en = field["values"], field["valuesEn"]
            width_maps, width_en = field["valuesByXlen"], field["valuesEnByXlen"]
            assert common.keys() == common_en.keys() and width_maps.keys() == width_en.keys(), key
            widths = {str(loc["xlen"]) for loc in field["layouts"]}
            assert width_maps.keys() <= widths, key
            for width, values in width_maps.items():
                assert values.keys() == width_en[width].keys(), key
            for loc in field["layouts"]:
                values = width_maps.get(str(loc["xlen"]), common)
                bits = loc["msb"] - loc["lsb"] + 1
                assert all(0 <= int(code) < 2 ** bits and value.strip() for code, value in values.items()), key
                if bits == 1:
                    assert values.keys() == {"0", "1"}, key
            assert all(value.strip() for value in common_en.values()), key
            assert all(value.strip() for values in width_en.values() for value in values.values()), key
            enumerated += bool(common or width_maps)
            rows.append({"field": key, "summary": field["summary"], "summaryEn": field["summaryEn"],
                         "evidence": evidence(field, reg), "layouts": field["layouts"],
                         "enumEntries": len(common) + sum(map(len, width_maps.values()))})
        for width in reg["xlens"]:
          for view in {f.get("view", "") for f in reg["fields"]}:
            used = set()
            for item in reg["fields"] + reg["regions"]:
                if item.get("view", "") != view:
                    continue
                for loc in item["layouts"]:
                    if loc["xlen"] != width:
                        continue
                    assert 0 <= loc["lsb"] <= loc["msb"] < width, reg["name"]
                    bits = set(range(loc["lsb"], loc["msb"] + 1))
                    assert not bits & used, reg["name"]
                    used |= bits
            if reg["coverage"] != "address-only" and len(used) != width:
                missing = sorted(set(range(width)) - used)
                assert reg["name"] in PARTIAL_INTERRUPT and missing == list(range(16, width)), (reg["name"], missing)
                gaps.append({"csr": reg["name"], "xlen": width, "range": [width - 1, 16],
                             "reason": "Figure defines standard bits 15:0 only; platform interrupt layouts are not invented."})
    vendor = ROOT / "examples/local-packages/thead/csr/th.sxstatus.json"
    sources = json.loads((ROOT / "tools/data-sources.lock.json").read_text(encoding="utf-8"))["sources"]
    vendor_source = next(source for source in sources if source["namespace"] == "thead")
    assert hashlib.sha256(vendor.read_bytes()).hexdigest() == vendor_source["sha256"]
    assert len(rows) == 1779
    report = {"revision": REVISION, "catalogSha256": catalog_sha256(ASSETS), "manualCsrs": len(registers),
              "describedFields": len(rows), "enumeratedFields": enumerated, "missingDescriptions": [],
              "addressOnlyExternalDebug": sorted(EXTERNAL_DEBUG), "partialPlatformLayouts": gaps,
              "vendorExtensionVerified": True, "fields": rows}
    report_path = ROOT / "docs/data/field-review.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in ("fields", "partialPlatformLayouts")}, ensure_ascii=False, indent=2))
    return report


if __name__ == "__main__":
    review()
