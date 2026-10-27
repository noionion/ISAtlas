"""Audit all AIA records, including high-half projections and pinned evidence."""
import hashlib
import json
import re

from csr_storage import read_catalog, catalog_sha256
from import_aia import ROOT, ASSETS, UPSTREAM, REVISION, csr_rows


def evidence(url):
    match = re.fullmatch(r"https://github.com/riscv/riscv-aia/blob/" + REVISION + r"/src/([A-Za-z]+\.adoc)#L(\d+)-L(\d+)", url)
    assert match, url
    path, first, last = match.groups()
    lines = (UPSTREAM / "src" / path).read_text(encoding="utf-8").splitlines(keepends=True)
    first, last = int(first), int(last)
    assert 1 <= first <= last <= len(lines), url
    return dict(path="src/" + path, lineStart=first, lineEnd=last,
                sha256=hashlib.sha256("".join(lines[first - 1:last]).encode()).hexdigest())


def review(write_report=True):
    pack = read_catalog(ASSETS)
    targeted = [r for r in pack["registers"] if r["namespace"] == "aia" or any(s["namespace"] == "aia" for s in r.get("sources", []))]
    registers = {r["name"]: r for r in targeted}
    assert len(targeted) == len(registers) == 51
    assert sum(r["namespace"] == "aia" for r in targeted) == 32
    assert len({r["id"] for r in pack["registers"]}) == len(pack["registers"])
    assert len({(r["namespace"], r["locator"]["address"]) for r in pack["registers"]}) == len(pack["registers"])
    source_rows = csr_rows()
    for name, row in source_rows.items():
        matches = [r for r in pack["registers"] if r["name"] == name]
        assert len(matches) == 1, name
        reg = matches[0]
        assert reg["locator"] == dict(type="riscv-csr", address=row["address"]), name
        assert reg["xlens"] == ([32] if row["width"] == "32" else [32, 64]), name
    sources = {}
    for reg in targeted:
        assert reg["summaryEn"] and re.search(r"[\u4e00-\u9fff]", reg["summary"])
        assert len({f["name"] for f in reg["fields"]}) == len(reg["fields"]), reg["name"]
        for f in reg["fields"]:
            key = reg["name"] + "." + f["name"]
            assert f["summaryEn"] and re.search(r"[\u4e00-\u9fff]", f["summary"]) and len(f["summary"]) <= 120, key
            assert not any(term in f["summary"] for term in ("未描述", "待补", "图示字段")), key
            assert f["access"], key
            assert f["values"].keys() == f["valuesEn"].keys(), key
            for loc in f["layouts"]:
                assert loc["xlen"] in reg["xlens"] and 0 <= loc["lsb"] <= loc["msb"] < loc["xlen"], key
                width = loc["msb"] - loc["lsb"] + 1
                values = f.get("valuesByXlen", {}).get(str(loc["xlen"]), f["values"])
                assert all(0 <= int(k) < 2 ** width and v for k, v in values.items()), key
                if width == 1:
                    assert set(values) == {"0", "1"}, key
        for width in reg["xlens"]:
          for view in {f.get("view", "") for f in reg["fields"] + reg["regions"]}:
            used = set()
            for item in reg["fields"] + reg["regions"]:
                if item.get("view", "") != view:
                    continue
                if "riscv-aia/blob/" in item["sourceUrl"]:
                    sources[item["sourceUrl"]] = evidence(item["sourceUrl"])
                for loc in item["layouts"]:
                    if loc["xlen"] != width:
                        continue
                    bits = set(range(loc["lsb"], loc["msb"] + 1))
                    assert not bits & used, (reg["name"], width, bits & used)
                    used |= bits
            assert used == set(range(width)), (reg["name"], width, sorted(set(range(width)) - used))
    # Verify every RV32 high half against the independently loaded parent definition.
    for name, row in source_rows.items():
        if row["width"] != "32":
            continue
        parent_name = {"midelegh": "mideleg", "hidelegh": "hideleg"}.get(name, name[:-1])
        expected = {}
        for f in registers[parent_name]["fields"]:
            loc = next((b for b in f["layouts"] if b["xlen"] == 64 and b["msb"] >= 32), None)
            if loc:
                expected[f["name"]] = (loc["msb"] - 32, max(32, loc["lsb"]) - 32)
        actual = {f["name"]: (f["layouts"][0]["msb"], f["layouts"][0]["lsb"]) for f in registers[name]["fields"]}
        assert expected == actual, name
    report = dict(revision=REVISION, catalogSha256=catalog_sha256(ASSETS),
        directCsrTableEntries=len(source_rows), addedCsrs=32, supplementedCsrs=19,
        reviewedFields=sum(len(r["fields"]) for r in targeted),
        highHalfCsrs=sum(row["width"] == "32" for row in source_rows.values()),
        missingDescriptions=[], duplicateIdentities=[], layoutErrors=[], sourceEvidence=sources,
        scope="Direct CSRs and base-manual supplements; IMSIC indirect register selectors and APLIC MMIO offsets are not CSR addresses.")
    if write_report:
        output = ROOT / "docs/data/aia-review.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return report


if __name__ == "__main__":
    print(json.dumps(review(), ensure_ascii=False, indent=2))
