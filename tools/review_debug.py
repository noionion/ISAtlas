"""Review every Debug 1.0 hart CSR and view against the fixed upstream XML."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET

from csr_storage import read_catalog, catalog_sha256
from import_debug import ROOT, ASSETS, UPSTREAM, REVISION, FORMATS


def evidence(url):
    m = re.fullmatch(r"https://github.com/riscv/riscv-debug-spec/blob/" + REVISION + r"/xml/(core_registers|hwbp_registers)\.xml#L(\d+)-L(\d+)", url)
    assert m, url
    file, first, last = m.groups()
    lines = (UPSTREAM / "xml" / (file + ".xml")).read_text(encoding="utf-8").splitlines(keepends=True)
    first, last = int(first), int(last)
    assert 1 <= first <= last <= len(lines), url
    return dict(path="xml/" + file + ".xml", lineStart=first, lineEnd=last,
                sha256=hashlib.sha256("".join(lines[first - 1:last]).encode()).hexdigest())


def position(expr, width):
    expr = expr.replace("DXLEN", str(width)).replace("XLEN", str(width))
    assert re.fullmatch(r"\d+(?:-\d+)?", expr)
    terms = list(map(int, expr.split("-")))
    return terms[0] - sum(terms[1:])


def review(write_report=True):
    pack = read_catalog(ASSETS)
    regs = {r["name"]: r for r in pack["registers"]}
    targets = [r for r in regs.values() if any(s["namespace"] == "debug" for s in r.get("sources", []))]
    assert len(targets) == 18
    assert len({r["id"] for r in pack["registers"]}) == len(pack["registers"])
    xml = {}
    for name in ("core_registers", "hwbp_registers"):
        for node in ET.parse(UPSTREAM / "xml" / (name + ".xml")).getroot().findall("register"):
            xml[node.get("short")] = node
    direct = {n: node for n, node in xml.items() if n not in FORMATS and not n.startswith("textra")}
    assert len(direct) == 14
    type_codes = {v.get("name"): int(v.get("v")) for v in xml["tdata1"].find("field[@name='type']").findall("value") if v.get("v")}
    assert all(type_codes[name] == code for name, code in FORMATS.items())
    assert xml["tmexttrigger"].find("field[@name='type']").get("reset") == "4"
    for n, node in direct.items():
        assert regs[n]["locator"] == dict(type="riscv-csr", address=int(node.get("address"), 16)), n
    for short, node in xml.items():
        parent = "tdata1" if short in FORMATS else "tdata3" if short.startswith("textra") else short
        view = short if short in FORMATS else "textra" if short.startswith("textra") else ""
        alias = {"hcontext": "mcontext", "mscontext": "scontext"}.get(short)
        if alias:
            node = xml[alias]
        for width in (32, 64):
            if short.startswith("textra") and short != f"textra{width}":
                continue
            for raw in node.findall("field"):
                name = raw.get("name")
                if short == "mcontrol" and name == "sizehi" and width == 32:
                    continue
                parts = raw.get("bits").split(":")
                high, low = position(parts[0], width), position(parts[-1], width)
                if high < low or low >= width:
                    continue
                if name == "0":
                    locations = [loc for r in regs[parent]["regions"] if r.get("view", "") == view and r["access"] == "RO0" for loc in r["layouts"] if loc["xlen"] == width]
                    assert dict(xlen=width, msb=high, lsb=low) in locations, (short, name, width)
                else:
                    key = (view + "." if view else "") + name.upper()
                    f = next(f for f in regs[parent]["fields"] if f["name"] == key)
                    loc = next(b for b in f["layouts"] if b["xlen"] == width)
                    assert loc == dict(xlen=width, msb=high, lsb=low), (short, key, width, loc)
    sources = {}
    for reg in targets:
        assert reg["summaryEn"] and re.search(r"[\u4e00-\u9fff]", reg["summary"])
        assert len({f["name"] for f in reg["fields"]}) == len(reg["fields"])
        for f in reg["fields"]:
            key = reg["name"] + "." + f["name"]
            assert f["summaryEn"] and re.search(r"[\u4e00-\u9fff]", f["summary"]) and len(f["summary"]) <= 120, key
            assert f["values"].keys() == f["valuesEn"].keys(), key
            for loc in f["layouts"]:
                assert 0 <= loc["lsb"] <= loc["msb"] < loc["xlen"]
                width = loc["msb"] - loc["lsb"] + 1
                assert all(0 <= int(v) < 2 ** width for v in f["values"]), key
                if width == 1:
                    assert set(f["values"]) == {"0", "1"}, key
        for width in reg["xlens"]:
            for view in {f.get("view", "") for f in reg["fields"]}:
                used = set()
                for item in reg["fields"] + reg["regions"]:
                    if item.get("view", "") != view:
                        continue
                    if "riscv-debug-spec/blob/" in item["sourceUrl"]:
                        sources[item["sourceUrl"]] = evidence(item["sourceUrl"])
                    for b in item["layouts"]:
                        if b["xlen"] != width:
                            continue
                        bits = set(range(b["lsb"], b["msb"] + 1))
                        assert not used & bits, (reg["name"], view, width, used & bits)
                        used |= bits
                assert used == set(range(width)), (reg["name"], view, width, sorted(set(range(width)) - used))
    assert not any(r["namespace"] == "debug" and r["name"] not in {"tinfo", "tcontrol", "mscontext"} for r in pack["registers"])
    report = dict(revision=REVISION, catalogSha256=catalog_sha256(ASSETS), directCsrs=14, newCsrs=3,
        supplementedCsrs=15, reviewedFields=sum(len(r["fields"]) for r in targets),
        tdata1Formats=["generic"] + list(FORMATS), tdata3Formats=["generic", "textra"],
        missingDescriptions=[], layoutErrors=[], sourceEvidence=sources,
        sourceDiscrepancies=[dict(register="tdata1", view="tmexttrigger", field="TYPE", xmlReset="4", proseType="7", handling="Use the explicit type-7 prose and type table; preserve the XML reset discrepancy in typeRule.")],
        limitations=["DCSR XML defines bits 31:0 only; upper RV64 bits are marked unspecified, not inferred as WPRI/RO0.",
                     "Trace actions are recorded; independent trace encoder/control registers are outside this repository's hart CSR definitions.",
                     "DM/DMI, JTAG and debugger-virtual register spaces are not hart CSR addresses."])
    if write_report:
        output = ROOT / "docs/data/debug-review.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return report


if __name__ == "__main__":
    print(json.dumps(review(), ensure_ascii=False, indent=2))
