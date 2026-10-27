"""Independent complete output audit against pinned SPMP prose and diagrams."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET

from csr_storage import read_catalog, catalog_sha256
from import_spmp import ROOT, ASSETS, UPSTREAM, REVISION


def evidence(url):
    match=re.fullmatch(r"https://github.com/riscv/riscv-spmp/blob/"+REVISION+r"/([A-Za-z0-9_./-]+)#L(\d+)-L(\d+)",url)
    assert match,url
    path,first,last=match.groups()
    resolved=(UPSTREAM/path).resolve()
    assert resolved.is_relative_to(UPSTREAM.resolve())
    lines=resolved.read_text(encoding="utf-8").splitlines(keepends=True)
    first,last=int(first),int(last)
    assert 1<=first<=last<=len(lines),url
    return dict(path=path,lineStart=first,lineEnd=last,sha256=hashlib.sha256("".join(lines[first-1:last]).encode()).hexdigest())


def review(write_report=True):
    pack=read_catalog(ASSETS)
    regs={r["name"]:r for r in pack["registers"]}
    targets=[r for r in pack["registers"] if any(s["namespace"]=="spmp" for s in r.get("sources",[]))]
    assert len(targets)==103
    assert sum(len(r["fields"]) for r in targets)==721
    assert len(regs)==len(pack["registers"])==429
    assert len({r["locator"]["address"] for r in pack["registers"]})==429
    assert {r["name"] for r in pack["registers"] if r["namespace"]=="spmp"}=={"mpmpdeleg","spmpen","spmpenh"}
    sharing=(UPSTREAM/"sspmp/Resource_Sharing.adoc").read_text(encoding="utf-8")
    switching=(UPSTREAM/"sspmp/spmpswitch.adoc").read_text(encoding="utf-8")
    for name,number in (("mpmpdeleg",0x316),("spmpen",0x183),("spmpenh",0x193)):
        assert f"`{name}` (CSR number {hex(number)})" in sharing+switching
        assert regs[name]["locator"]["address"]==number
    manual=(ROOT/"upstream/riscv-isa-manual/src/indirect-csr.adoc").read_text(encoding="utf-8")
    for mode,base in (("m",0x350),("s",0x150)):
        for number,offset in ((2,2),(3,3),(4,5),(5,6),(6,7)):
            name=f"{mode}ireg{number}"
            assert f"|{hex(base+offset)} |{mode.upper()}RW |XLEN |`{name}`" in manual
            assert regs[name]["locator"]["address"]==base+offset
            assert regs[name]["sourceRevision"]=="584390d9d872835b1bc45ab9ea946b56db3ad485"
    assert not any(name in regs for name in ("spmpaddr0","spmpcfg0","vspmpen","vspmpenh","hspmpdeleg","hspmpen","hspmpenh"))
    sources={}
    for reg in targets:
        assert len({f["name"] for f in reg["fields"]})==len(reg["fields"]),reg["name"]
        assert reg["summaryEn"] and re.search(r"[\u4e00-\u9fff]",reg["summary"]),reg["name"]
        for f in reg["fields"]:
            key=reg["name"]+"."+f["name"]
            assert f["summaryEn"] and re.search(r"[\u4e00-\u9fff]",f["summary"]) and len(f["summary"])<=120,key
            assert f["values"].keys()==f["valuesEn"].keys(),key
            for loc in f["layouts"]:
                values=f.get("valuesByXlen",{}).get(str(loc["xlen"]),f["values"])
                assert all(0<=int(k)<2**(loc["msb"]-loc["lsb"]+1) and v for k,v in values.items()),key
                if loc["msb"]==loc["lsb"]:
                    assert set(values)=={"0","1"},key
        for width in reg["xlens"]:
            for view in {f.get("view","") for f in reg["fields"]+reg["regions"]}:
                used=set()
                for item in reg["fields"]+reg["regions"]:
                    if item.get("view","")!=view:
                        continue
                    if "riscv-spmp/blob/" in item["sourceUrl"]:
                        sources[item["sourceUrl"]]=evidence(item["sourceUrl"])
                    for loc in item["layouts"]:
                        if loc["xlen"]!=width:
                            continue
                        bits=set(range(loc["lsb"],loc["msb"]+1))
                        assert not used&bits and 0<=loc["lsb"]<=loc["msb"]<width,(reg["name"],view,loc)
                        used|=bits
                assert used==set(range(width)),(reg["name"],view,width)
    # Compare label/range encodings, never the bytefield's visual span.
    cfg=(UPSTREAM/"sspmp/images/bytefield/spmpcfg.adoc").read_text(encoding="utf-8")
    assert '(draw-box "9"' in cfg and '(draw-box "8"' in cfg and '(draw-box "7"' in cfg
    assert '"SXLEN-10"' in cfg and '"Reserved (WPRI)"' in cfg
    for mode in ("s","m"):
        fields={f["name"]:f for f in regs[mode+"ireg2"]["fields"]}
        for name,hi,lo in (("SHARED",9,9),("U",8,8),("L",7,7),("A",4,3),("X",2,2),("W",1,1),("R",0,0)):
            assert all((loc["msb"],loc["lsb"])==(hi,lo) for loc in fields["spmpcfg."+name]["layouts"])
        address=[f for f in regs[mode+"ireg"]["fields"] if f.get("view")=="spmpaddr"]
        assert {(b["xlen"],b["msb"],b["lsb"]) for f in address for b in f["layouts"]}=={(32,31,0),(64,53,0)}
        for width,pa in ((32,33),(64,55)):
            text=(UPSTREAM/f"sspmp/images/bytefield/spmpaddr-rv{width}.adoc").read_text(encoding="utf-8")
            assert f'address[{pa}:2]' in text
        values=regs[mode+"iselect"]["fields"][0]["values"]
        assert all(f"SPMP[{i}]" in values[str(0x100+i)] for i in range(64))
    rv32=(UPSTREAM/"sspmp/images/bytefield/mpmpdeleg_format_rv32.adoc").read_text(encoding="utf-8")
    assert '"0 (WARL)"' in rv32 and '(text "5"' in rv32 and '(text "2"' in rv32
    assert regs["mpmpdeleg"]["fields"][0]["layouts"]==[dict(xlen=32,msb=6,lsb=2),dict(xlen=64,msb=6,lsb=0)]
    assert len(regs["spmpen"]["fields"])==64 and len(regs["spmpenh"]["fields"])==32
    assert regs["spmpenh"]["xlens"]==[32]
    table=" ".join(" ".join(n.itertext()) for n in ET.parse(UPSTREAM/"sspmp/images/SPMP_Encoding_Table_v13.svg").iter() if n.tag.endswith("text"))
    assert "Read" in table and "Exec" in table and "110" in table and "111" in table
    sources["SPMP permission table"]=dict(path="sspmp/images/SPMP_Encoding_Table_v13.svg",sha256=hashlib.sha256((UPSTREAM/"sspmp/images/SPMP_Encoding_Table_v13.svg").read_bytes()).hexdigest())
    report=dict(revision=REVISION,catalogSha256=catalog_sha256(ASSETS),directSpmpCsrs=3,addedManualWindows=10,
                reviewedCsrs=len(targets),reviewedFields=sum(len(r["fields"]) for r in targets),sourceEvidence=sources,
                missingDescriptions=[],layoutErrors=[],duplicateIdentities=[],
                sourceDiscrepancies=["RV32 mpmpdeleg diagram defines PMP boundary bits 6:2 and 0 (WARL) bits 1:0; prose describes the full boundary without explaining this granularity difference. Keep the diagram and disclose the discrepancy."],
                limitations=["Selectors 0x100..0x13F are not CSR addresses; spmpaddr/spmpcfg are format views on ireg/ireg2.","Hypervisor 0.2 draft has no assigned numbers for vspmpen[h], hspmpdeleg or hspmpen[h]; not added as direct CSRs.","Unimplemented spmpen bits have ignored writes; read values are not inferred."])
    if write_report:
        output=ROOT/"docs/data/spmp-review.json"
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    return report


if __name__=="__main__":
    print(json.dumps(review(),ensure_ascii=False,indent=2))
