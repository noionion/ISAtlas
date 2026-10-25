"""Generate per-mnemonic offline instruction JSON from the pinned ISA manual.

No external opcode database and no upstream code execution are used. The
adapter handles printed tables, literal diagrams, and explicitly documented
encoding derivations; each variant retains line-level evidence.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import subprocess

from instruction_sources import (ROOT, SOURCE, REVISION, REPOSITORY, clean,
                                 evidence, field, encoding, table_rows,
                                 byte_rows, named_sections, waves)
from instruction_summaries import summary

ASSETS = ROOT / "app/src/main/assets"
INST = ASSETS / "inst"
CATALOG = ASSETS / "riscv-instructions.json"
REPORT = ROOT / "docs/data/instruction-import-report.json"
INSTRUCTIONS = {}
DIAGNOSTICS = []


def add(name, variant, semantic_source=None, synopsis=None):
    name = name.lower()
    if not re.fullmatch(r"[a-z][a-z0-9.]*", name):
        raise ValueError(f"Invalid mnemonic: {name}")
    zh, en = summary(name)
    for f in variant["fields"]:
        if "value" not in f:
            if f["name"] == "rm":
                f["values"] = {"0": "RNE", "1": "RTZ", "2": "RDN", "3": "RUP", "4": "RMM", "7": "DYN"}
                variant["constraints"].append("rm=5/6 reserved; DYN uses frm, whose reserved values are also invalid")
            if f.get("registerOffset") == 8 and f["msb"] - f["lsb"] != 2:
                raise ValueError(f"Non-three-bit compact register: {name} {f}")
    if name not in INSTRUCTIONS:
        INSTRUCTIONS[name] = {"schemaVersion": 1, "id": f"riscv/manual/inst/{name}",
            "architecture": "riscv", "namespace": "manual", "name": name,
            "summary": zh, "summaryEn": en, "kind": "instruction",
            "sourceRevision": REVISION, "license": "CC-BY-4.0",
            "semanticSources": [], "encodings": []}
    inst = INSTRUCTIONS[name]
    if name == "pause" or name.startswith(("ntl.", "c.ntl.")):
        inst["kind"] = "hint"
    if semantic_source and semantic_source not in inst["semanticSources"]:
        inst["semanticSources"].append(semantic_source)
    if synopsis:
        variant["sourceSynopsis"] = synopsis
    existing = next((v for v in inst["encodings"] if all(v[k] == variant[k] for k in ("length", "xlens", "mask", "match", "requires", "fields"))), None)
    if existing:
        for s in variant["sources"]:
            if s not in existing["sources"]:
                existing["sources"].append(s)
    else:
        inst["encodings"].append(variant)


def source_for(path, needle, method="semantic-paragraph", after=0):
    text = (SOURCE / path).read_text(encoding="utf-8")
    m = re.search(needle, text[after:], re.I | re.M)
    if not m:
        raise ValueError(f"Missing evidence {path}: {needle}")
    start = after + m.start()
    end = text.find("\n\n", after + m.end())
    return evidence(path, start, end if end >= 0 else len(text), method)


def change(variant, replacements, xlens=None, requires=None, source=None):
    fields = []
    for f in variant["fields"]:
        key = (f["msb"], f["lsb"])
        copied = deepcopy(f)
        if key in replacements:
            copied.pop("operand", None)
            copied["value"] = replacements[key]
        fields.append(copied)
    out = encoding(fields, xlens or variant["xlens"], requires or variant["requires"], source or variant["sources"][0])
    return out


def semantic_path(name, extension):
    if name in {"pause"}:
        return "zihintpause.adoc"
    if name == "fence.tso":
        return "ztso-st-ext.adoc"
    if extension == "I":
        return "rv64.adoc" if name.endswith("w") or name in {"ld", "sd", "lwu"} else "rv32.adoc"
    return {"M": "m-st-ext.adoc", "F": "f-st-ext.adoc", "D": "d-st-ext.adoc", "Q": "q-st-ext.adoc", "Zfh": "zfh.adoc", "Zicsr": "zicsr.adoc", "Zifencei": "zifencei.adoc", "Zalrsc": "a-st-ext.adoc", "Zaamo": "a-st-ext.adoc", "Zawrs": "zawrs.adoc"}[extension]


def base_instructions():
    for name, variant, section in table_rows():
        ext = variant["requires"][0][0]
        path = semantic_path(name, ext)
        # Some extension chapters describe instruction families rather than each
        # mnemonic. Keep the complete chapter as explicit semantic evidence.
        text = (SOURCE / path).read_text(encoding="utf-8")
        match = re.search(r"\b" + re.escape(name) + r"\b", text, re.I)
        s = evidence(path, match.start() if match else 0, (text.find("\n\n", match.end()) if match and text.find("\n\n", match.end()) >= 0 else len(text)), "manual-semantics")
        if name in {"fence", "fence.tso", "pause"}:
            variant["sources"].append(source_for("images/wavedrom/mem-order.edn", r"reg:", "explicit-fence-field-widths"))
            variant["constraints"].append("rs1 and rd are reserved for future use; standard software writes zero")
        if name=="flq":
            variant["sources"].append(source_for("images/wavedrom/quad-ls.edn",r"reg:","corroborate-load-immediate-width"))
        if name == "fence.i":
            variant["constraints"].append("imm, rs1 and rd are reserved for future use; standard software writes zero")
        add(name, variant, s)
    for quadrant in range(3):
        path = f"images/bytefield/rvc-instr-quad{quadrant}.edn"
        for name, fields, label, src in byte_rows(path):
            first_xlen=re.search(r"RV(32|64)",label)
            xlens = [int(first_xlen[1])] if first_xlen else [32, 64]
            requirements = [["Zca"]]
            if name.startswith(("c.flw", "c.fsw")):
                requirements = [["Zcf", "F"]]
            elif name.startswith(("c.fld", "c.fsd")):
                requirements = [["Zcd", "D"]]
            variant = encoding(fields, xlens, requirements, src)
            # Preserve the complete printed RES/HINT annotation, not just its title.
            variant["constraints"].append("Printed operand restrictions: " + label)
            add(name, variant, source_for("c-st-ext.adoc", re.escape(name), "compressed-semantics"))
    for name, fields, label, src in byte_rows("images/bytefield/priv-instr-set.edn", [7, 5, 5, 3, 5, 7]):
        ext = "H" if name.startswith(("hfence", "hlv", "hsv", "hinval")) else "Svinval" if name in {"sinval.vma", "sfence.w.inval", "sfence.inval.ir"} else "Smrnmi" if name == "mnret" else "S" if name in {"sret", "sfence.vma"} else "M-privileged"
        path = "hypervisor.adoc" if ext == "H" else "rnmi.adoc" if name == "mnret" else "supervisor.adoc" if ext in {"S", "Svinval"} else "machine.adoc"
        xlens = [64] if name in {"hlv.d", "hsv.d", "hlv.wu", "hlvx.wu"} else [32, 64]
        # HLVX.WU is explicitly defined on RV32 as well.
        if name == "hlvx.wu":
            xlens = [32, 64]
        v = encoding(fields, xlens, [[]] if ext=="M-privileged" else [[ext]], src)
        v["privilege"] = "M" if name in {"mret","mnret"} else "HS" if ext=="H" else "S" if ext in {"S","Svinval"} else "U"
        if name.startswith("hinval"):
            v["requires"] = [["H", "Svinval"]]
        v["constraints"].append("Execution is subject to current privilege and virtualization trap controls")
        add(name, v, source_for(path, re.escape(name), "privileged-semantics"))


SYMBOLS = {"OP-VE": 0x77, "OP-V": 0x57, "OPIVV": 0, "OPFVV": 1, "OPMVV": 2, "OPIVI": 3, "OPIVX": 4, "OPFVF": 5, "OPMVX": 6}


def resolve_symbols(fields):
    for f in fields:
        if f["name"] in SYMBOLS:
            f["value"] = SYMBOLS[f["name"]]
            f.pop("operand", None)
        # WaveDrom occasionally quotes a short zero constant in a wider field.
        if re.fullmatch("[01]+", f["name"]):
            f["value"] = int(f["name"], 2)
            f.pop("operand", None)
    return fields


def section_requirements(s, name):
    included = re.search(r"(?m)^Included in::?(.*)", s.text, re.S)
    extensions = []
    if included:
        body=re.split(r"<<<|\n\[\[|\n\[#",included[1])[0]
        extensions = re.findall(r"\b(Z[a-zA-Z0-9]+)\b", body)
        extensions += [x.capitalize() if x.islower() else x for x in re.findall(r"<<#?(z[a-z0-9]+)", body)]
    # Lowercase references need canonical spelling, not str.capitalize() Z* names.
    canonical = {x.lower(): x for x in re.findall(r"\bZ[a-z][a-z0-9]+\b", (SOURCE / s.path).read_text(encoding="utf-8"))}
    extensions = sorted({canonical.get(x.lower(), x) for x in extensions})
    if "Zvknh" in extensions:
        extensions.remove("Zvknh")
        extensions.extend(["Zvknha","Zvknhb"])
    if s.path == "zc.adoc":
        ext = "Zcmp" if name.startswith(("cm.push", "cm.pop", "cm.mv")) else "Zcmt" if name.startswith("cm.j") else "Zcb"
        return [[ext, "Zmmul"]] if name == "c.mul" else [[ext, "Zba"]] if name=="c.zext.w" else [[ext, "Zbb"]] if name in {"c.sext.b", "c.sext.h", "c.zext.h"} else [[ext]]
    if s.path == "zilsd.adoc":
        return [["Zclsd", "Zilsd"]] if name.startswith("c.") else [["Zilsd"]]
    if s.path == "zicond.adoc":
        return [["Zicond"]]
    if s.path == "bfloat16.adoc":
        return [["Zvfbfwma"]] if name.startswith("vfwmacc") else [["Zvfbfmin"]] if name.startswith("v") else [["Zfbfmin"]]
    if not extensions:
        raise ValueError(f"No extension evidence: {s.path} {name}")
    return [[x] for x in extensions]


def section_names(s):
    if ".[" in s.name:
        stem, suffix = s.name.split(".[")
        return [stem + "." + x for x in suffix.rstrip("]").split(",")]
    if s.name == "vsha2c[hl].vv":
        return ["vsha2ch.vv", "vsha2cl.vv"]
    if s.name == "vfwmaccbf16":
        return ["vfwmaccbf16.vv", "vfwmaccbf16.vf"]
    return [s.name]


def named_instructions():
    for s in named_sections():
        names = section_names(s)
        blocks = list(waves(s.text))
        for i, (block, fields) in enumerate(blocks):
            name = names[i] if len(names) > 1 else names[0]
            caption = s.text[:block.start()].splitlines()[-4:]
            caption = " ".join(caption)
            xlens = [32] if re.search(r"Encoding\s*\(RV32\)", caption) else [64] if re.search(r"Encoding\s*\(RV64\)", caption) else [32, 64]
            if s.path == "zilsd.adoc" or name.startswith("aes32") or re.match(r"sha512.*[hlr]$", name) or name in {"zip", "unzip"}:
                xlens = [32]
            elif name.startswith("aes64") or re.fullmatch(r"sha512(?:sig|sum)[01]", name) or name in {"add.uw", "clzw", "ctzw", "cpopw", "packw", "rolw", "rorw", "roriw", "slli.uw"} or name.endswith(".uw"):
                xlens = [64]
            fields = resolve_symbols(fields)
            src = evidence(s.path, s.offset + block.start(), s.offset + block.end(), "literal-wavedrom")
            v = encoding(fields, xlens, section_requirements(s, name), src)
            if name=="vaesdf.vs":
                v["sources"].append(source_for("vector-crypto.adoc",r"\| 101001 .*VAES.vs","corroborate-stray-backtick"))
            for reserved in re.finditer(r"(?m)^Reserved Encodings::?\s*\n(.*?)(?=^\w[^\n]*::|\n\n\w|\Z)", s.text, re.S):
                v["constraints"].append(clean(reserved[1]))
            # Context needed by a decoder, not encoded in match/mask alone.
            if name == "aes64ks1i":
                v["constraints"].append("rnum must be in 0..10; 11..15 reserved")
            if name.startswith("cm."):
                v["constraints"].append("Register-list/index/stack-adjustment restrictions follow the per-instruction manual section")
            if name in {"ld", "sd", "c.ld", "c.sd", "c.ldsp", "c.sdsp"} and s.path == "zilsd.adoc":
                load=name.removeprefix('c.').startswith('l')
                v["summary"] = f"使用偶数/奇数相邻整数寄存器对{'加载' if load else '存储'} 64 位数据，寄存器对与异常规则见规范。"
                v["summaryEn"] = f"{'Load' if load else 'Store'} 64-bit data using an even/odd adjacent integer-register pair; pair and exception rules follow the specification."
                v["constraints"].append("RV32 register-pair access; encoded rd/rs2 must be even where required")
            semantic = re.search(r"(?m)^Description::?", s.text)
            sem_src = evidence(s.path, s.offset + (semantic.start() if semantic else 0), s.offset + len(s.text), "instruction-description")
            add(name, v, sem_src, s.synopsis)


def supplementary_instructions():
    """Explicit prose/diagram adapters, with all constants backed by the manual."""
    # Zacas/Zabha retain the same AMO operation bits; the new widths and CAS
    # operation code are printed in their respective extension diagrams.
    cas_source = source_for("zacas.adoc", r"\[wavedrom", "cas-diagram")
    for suffix, width, xlens in [("w",2,[32,64]),("d",3,[32,64]),("q",4,[64])]:
        base = deepcopy(INSTRUCTIONS["amoswap.w"]["encodings"][0])
        # Main table splits funct5/aq/rl, making this a literal replacement.
        replacements = {(31,27):5, (14,12):width}
        v = change(base, replacements, xlens, [["Zacas", "Zaamo"]], cas_source)
        if suffix in {"d", "q"}:
            v["constraints"].append("Multi-register operands must use even base registers: D on RV32, Q on RV64")
        add("amocas."+suffix, v, source_for("zacas.adoc", r"For RV32, `AMOCAS.W`"))
    zabha_source = source_for("zabha.adoc", r"\[wavedrom", "amo-width-derivation")
    for op in ["swap", "add", "and", "or", "xor", "min", "minu", "max", "maxu", "cas"]:
        for suffix, width in [("b",0),("h",1)]:
            v = change(INSTRUCTIONS["amo"+op+".w"]["encodings"][0], {(14,12):width}, [32,64], [["Zabha", "Zacas"]] if op == "cas" else [["Zabha"]], zabha_source)
            v["sources"].append(INSTRUCTIONS["amo"+op+".w"]["encodings"][0]["sources"][0])
            add("amo"+op+"."+suffix, v, source_for("zabha.adoc", r"Byte and halfword AMOs always"))
    cache_and_mop_instructions()
    additional_float_instructions()
    cfi_and_hints()
    text=(SOURCE/"smctr.adoc").read_text(encoding="utf-8")
    start=text.index("==== Supervisor CTR Clear Instruction")
    block,fields=next(waves(text[start:]))
    for f in fields:
        f["value"]={"opcode":0x73,"rd":0,"funct3":0,"rs1":0,"func12":0x104}[f["name"]]
        f.pop("operand",None)
    src=evidence("smctr.adoc",start+block.start(),start+block.end(),"literal-sctrclr-attributes")
    v=encoding(fields,[32,64],[["Smctr"],["Ssctr"]],src)
    v["privilege"]="S"
    v["constraints"].append("Smstateen CTR permissions and virtualization trap controls apply")
    add("sctrclr",v,source_for("smctr.adoc",r"The SCTRCLR instruction performs"))


def cache_and_mop_instructions():
    path="cmo.adoc"
    text=(SOURCE/path).read_text(encoding="utf-8")
    for m,fields in waves(text):
        heads=list(re.finditer(r"(?m)^={2,5} ([a-z]+\.[a-z]+)\s*$",text[:m.start()]))
        if not heads:
            continue
        name=heads[-1][1]
        if name not in {"cbo.clean","cbo.flush","cbo.inval","cbo.zero","prefetch.i","prefetch.r","prefetch.w"}:
            continue
        ext="Zicboz" if name=="cbo.zero" else "Zicbop" if name.startswith("prefetch") else "Zicbom"
        add(name,encoding(fields,[32,64],[[ext]],evidence(path,m.start(),m.end(),"literal-cache-encoding")),evidence(path,heads[-1].start(),m.end(),"cache-instruction-description"))
    for form,count in [("r",32),("rr",8)]:
        path=f"images/wavedrom/mop-{form}.edn"
        text=(SOURCE/path).read_text(encoding="utf-8")
        block,base=next(waves(text))
        for n in range(count):
            fields=deepcopy(base)
            for f in fields:
                if f.get("operand")=="n":
                    shift=f.get("operandBits",[0])[-1]
                    f["value"]=(n>>shift)&((1<<(f["msb"]-f["lsb"]+1))-1)
                    f.pop("operand");f.pop("operandBits",None)
            add(f"mop.{form}.{n}",encoding(fields,[32,64],[["Zimop"]],evidence(path,block.start(),block.end(),"indexed-mop-family")),source_for("zimop.adoc",r"defines 32 MOP instructions" if form=="r" else r"additionally defines 8 MOP"))
    text=(SOURCE/"zimop.adoc").read_text(encoding="utf-8")
    for row in re.finditer(r"(?m)^\|C.MOP.(\d+)\s*\|\s*`([01]{16})`",text):
        name="c.mop."+row[1]
        add(name,encoding([field("instruction",15,0,int(row[2],2))],[32,64],[["Zcmop"]],evidence("zimop.adoc",row.start(),row.end(),"literal-compressed-mop-table")),source_for("zimop.adoc",r"This section defines.*Zcmop"))


def fp_fields(funct7,funct3=None,rs2=None,rs1=None):
    return [field("opcode",6,0,0x53),field("rd",11,7),field("rm" if funct3 is None else "funct3",14,12,funct3),field("rs1",19,15,rs1),field("rs2",24,20,rs2),field("funct7",31,25,funct7)]


def additional_float_instructions():
    source=source_for("zfa.adoc",r"=== Load-Immediate Instructions","prose-encoding-derivation")
    for fmt,fmtbits,ext in [("s",0,"F"),("d",1,"D"),("h",2,"Zfh"),("q",3,"Q")]:
        requires=[["Zfa",ext]]
        if fmt=="h":
            requires.append(["Zfa","Zvfh"])
        fields=fp_fields(0b1111000|fmtbits,0,1)
        # FLI uses rs1 as a constant index, not an integer register operand.
        index=next(f for f in fields if f["name"]=="rs1")
        index["name"]="constantIndex";index["operand"]="constantIndex"
        table=(SOURCE/"zfa.adoc").read_text(encoding="utf-8")
        index["values"]={row[1]:clean(row[2]) for row in re.finditer(r"(?m)^\|(\d+)\s*\|([^|]*)\|",table)}
        index["values"]["1"]="Minimum positive normal for the destination format"
        if fmt=="h":
            index["values"]["29"]="Positive infinity (2^16 is not representable in binary16)"
        add("fli."+fmt,encoding(fields,[32,64],requires,source),source)
        for op,rs2 in [("fround",4),("froundnx",5)]:
            src=source_for("zfa.adoc",r"=== Round-to-Integer Instructions","prose-encoding-derivation")
            add(op+"."+fmt,encoding(fp_fields(0b0100000|fmtbits,None,rs2),[32,64],[["Zfa",ext]],src),src)
        for op,funct3 in [("fminm",2),("fmaxm",3),("fleq",4),("fltq",5)]:
            src=source_for("zfa.adoc",r"=== Minimum and Maximum" if op.startswith(("fmin","fmax")) else r"=== Comparison Instructions","prose-encoding-derivation")
            add(op+"."+fmt,encoding(fp_fields((0b0010100 if op.startswith(("fmin","fmax")) else 0b1010000)|fmtbits,funct3),[32,64],[["Zfa",ext]],src),src)
    src=source_for("zfa.adoc",r"=== Modular Convert-to-Integer Instruction","prose-encoding-derivation")
    add("fcvtmod.w.d",encoding(fp_fields(0b1100001,1,8),[32,64],[["Zfa","D"]],src),src)
    src=source_for("zfa.adoc",r"=== Move Instructions","prose-encoding-derivation")
    for name,f7,rs2,xlens,ext in [("fmvh.x.d",0b1110001,1,[32],"D"),("fmvp.d.x",0b1011001,None,[32],"D"),("fmvh.x.q",0b1110011,1,[64],"Q"),("fmvp.q.x",0b1011011,None,[64],"Q")]:
        add(name,encoding(fp_fields(f7,0,rs2),xlens,[["Zfa",ext]],src),src)


def cfi_and_hints():
    text=(SOURCE/"unpriv-cfi.adoc").read_text(encoding="utf-8")
    specs=[("lpad",1), ("sspush",2), ("c.sspush",1), ("sspopchk",2), ("c.sspopchk",1), ("ssrdp",1), ("ssamoswap",2)]
    blocks=list(waves(text))
    if len(blocks)!=len(specs):
        raise ValueError("CFI instruction diagrams changed")
    symbols={"AUIPC":0x17,"SYSTEM":0x73,"AMO":0x2f,"C1":1}
    for (base,count),(m,basefields) in zip(specs,blocks):
        for i in range(count):
            name=base+(".w" if i==0 else ".d") if base=="ssamoswap" else base
            fields=deepcopy(basefields)
            for f in fields:
                if "value" in f:
                    continue
                labels=f.get("sourceLabels",[])
                label=str(labels[min(i,len(labels)-1)]) if labels else ""
                if label in symbols or re.fullmatch("[01]+",label):
                    f["value"]=symbols[label] if label in symbols else int(label,2)
                    f.pop("operand",None)
                    f.pop("operandBits",None)
            ext="Zicfilp" if base=="lpad" else "Zicfiss"
            v=encoding(fields,[64] if name=="ssamoswap.d" else [32,64],[[ext]],evidence("unpriv-cfi.adoc",m.start(),m.end(),"literal-cfi-diagram"))
            v["constraints"].append("Behavior depends on active CFI/shadow-stack environment controls; see the source section")
            add(name,v,source_for("unpriv-cfi.adoc",re.escape(name),"cfi-semantics"))
    src=source_for("zihintntl.adoc",r"NTL.P1","hint-operand-derivation")
    # The chapter assigns x2..x5 as the second operand of ADD/C.ADD hints.
    for suffix,rs2 in [("p1",2),("pall",3),("s1",4),("all",5)]:
        v=change(INSTRUCTIONS["add"]["encodings"][0],{(24,20):rs2,(19,15):0,(11,7):0},[32,64],[["Zihintntl"]],src)
        add("ntl."+suffix,v,src)
        base=INSTRUCTIONS["c.add"]["encodings"][0]
        v=change(base,{(6,2):rs2,(11,7):0},[32,64],[["Zihintntl","Zca"]],src)
        # Remove ordinary C.ADD's rd!=0 restriction: these hints use rd=x0.
        v["constraints"]=[]
        add("c.ntl."+suffix,v,src)


def register_file_variants():
    """Zfinx-family reuse is explicitly specified, excluding FP transfers."""
    source=source_for("zfinx.adoc",r"The Zfinx extension adds all","same-encoding-in-integer-registers")
    excluded={"flw","fsw","fmv.w.x","fmv.x.w","fld","fsd","fmv.d.x","fmv.x.d","flh","fsh","fmv.h.x","fmv.x.h"}
    for name,inst in list(INSTRUCTIONS.items()):
        if not name.startswith("f") or name in excluded:
            continue
        for base in list(inst["encodings"]):
            extensions={x for req in base["requires"] for x in req}
            if not extensions or not extensions<= {"F","D","Zfh"}:
                continue
            ext="Zhinx" if "Zfh" in extensions else "Zdinx" if "D" in extensions else "Zfinx"
            v=deepcopy(base)
            v["requires"]=[[ext]]
            if ext=="Zhinx" and name in {"fcvt.h.s","fcvt.s.h"}:
                v["requires"].append(["Zhinxmin"])
            if ext=="Zhinx" and "D" in extensions:
                v["requires"]=[["Zhinx","Zdinx"],["Zhinxmin","Zdinx"]]
            v["registerFile"]="integer"
            v["summary"]=inst["summary"].replace("F(","X(")+"；浮点操作数使用同编号整数寄存器。"
            v["summaryEn"]=inst["summaryEn"].replace("F(","X(")+"; FP operands use the same-numbered integer registers."
            v["sources"].append(source)
            v["constraints"].append("Narrow results are sign-extended in x registers; RV32Zdinx double operands use even/odd pairs, with x0 pair semantics")
            add(name,v,source)


def aliases():
    recipes=[
        ("nop","addi",{(31,20):0,(19,15):0,(11,7):0},"rv32.adoc"),
        ("mv","addi",{(31,20):0},"rv32.adoc"),
        ("not","xori",{(31,20):4095},"rv32.adoc"),
        ("sext.w","addiw",{(31,20):0},"rv64.adoc"),
        ("zext.w","add.uw",{(24,20):0},"b-st-ext.adoc"),
        ("seqz","sltiu",{(31,20):1},"rv32.adoc"),
        ("snez","sltu",{(19,15):0},"rv32.adoc"),
        ("j","jal",{(11,7):0},"rv32.adoc"),
        ("jr","jalr",{(31,20):0,(11,7):0},"rv32.adoc"),
        ("ret","jalr",{(31,20):0,(11,7):0,(19,15):1},"rv32.adoc"),
    ]
    for name,base,replacements,path in recipes:
        needle = {"not": r"pseudoinstruction NOT", "j": r"Plain unconditional jumps \(assembler pseudoinstruction J\)",
                  "zext.w": r"zext.w _rd_, _rs1_ &#8594;"}.get(name, r"\b"+re.escape(name)+r"\b")
        src=source_for(path,needle,"manual-pseudoinstruction")
        v=change(INSTRUCTIONS[base]["encodings"][0],replacements,source=src)
        add(name,v,src)
        INSTRUCTIONS[name]["kind"]="alias"
        INSTRUCTIONS[name]["aliasOf"]=base
    for root,address in [("cycle",0xc00),("time",0xc01),("instret",0xc02)]:
        for high in [False,True]:
            name="rd"+root+("h" if high else "")
            src=source_for("counters.adoc",re.escape(name),"counter-alias")
            v=change(INSTRUCTIONS["csrrs"]["encodings"][0],{(31,20):address+(0x80 if high else 0),(19,15):0},[32] if high else [32,64],[["Zicntr"]],src)
            add(name,v,src);INSTRUCTIONS[name]["kind"]="alias";INSTRUCTIONS[name]["aliasOf"]="csrrs"


def finalize():
    """Normalize derived operands and add machine-readable encoding restrictions."""
    for name,inst in INSTRUCTIONS.items():
        for v in inst["encodings"]:
            if v["requires"]==[["M"]] and name.startswith("mul"):
                v["requires"].append(["Zmmul"])
            if v["requires"]==[["I"]]:
                v["requires"].append(["E"])
                v["constraints"].append("With an E base, integer register operands x16..x31 are reserved")
                v["sources"].append(source_for("rv32e.adoc",r"RV32E and RV64E Instruction Set Encoding","reduced-register-base"))
            if v["requires"]==[["Zca"]]:
                v["requires"].append(["C"])
            if name in {"flh","fsh","fmv.x.h","fmv.h.x","fcvt.s.h","fcvt.h.s","fcvt.d.h","fcvt.h.d","fcvt.q.h","fcvt.h.q"} and v.get("registerFile")!="integer":
                v["requires"] += [["Zfhmin" if x=="Zfh" else x for x in req] for req in v["requires"] if "Zfh" in req]
                v["sources"].append(source_for("zfh.adoc",r"The Zfhmin extension includes the following","minimal-half-precision-subset"))
            if v["requires"]==[["V"]]:
                root=name.split('.')[0]
                vector_fp=root.startswith(("vf","vmf"))
                memory64=bool(re.search(r"(?:e|ei)64(?:ff)?\.v$",name))
                embedded="Zve32f" if vector_fp else "Zve64x" if memory64 else "Zve32x"
                v["requires"].append([embedded])
                v["sources"].append(source_for("v-st-ext.adoc",r"All Zve\* extensions support all vector load","embedded-vector-subsets"))
                v["constraints"].append("Embedded vector profiles support only their specified EEWs/ELEN; FP64 and widening FP32-to-FP64 require V or Zve64d; FP16 requires Zvfh/Zvfhmin as applicable")
                if root.startswith(("vmulh","vsmul")):
                    v["constraints"].append("Zve64* excludes EEW=64 high-half/fractional multiplies; V includes them")
                if memory64 and "ei64" in name:
                    v["constraints"].append("Zve64* excludes 64-bit indexed offsets when XLEN=32")
            v["requires"]=sorted([sorted(set(req)) for req in v["requires"]])
            v["constraints"]=list(dict.fromkeys(v["constraints"]))
            tests=[]
            def test(mask,op,values,reason):
                tests.append({"mask":hex(mask),"operator":op,"values":values,"reason":reason})
            for f in v["fields"]:
                if "value" in f:
                    continue
                raw_operand=f.get("operand",f["name"])
                if raw_operand in {"imm","simm","nzimm","uimm","nzuimm","spimm","shamt","i5"}:
                    f["operandSigned"]=raw_operand in {"imm","simm","nzimm"}
                operand=f.get("operand",f["name"]).replace("ʹ","'").replace("′","'")
                operand=operand.replace("`","'")
                operand=operand.replace("rs1/rd","rd/rs1")
                operand=operand.replace("rs1'/rd'","rd'/rs1'")
                if operand=="i5":
                    operand="uimm";f["operandBits"]=[5]
                if operand in {"imm","uimm","nzuimm","nzimm"}:
                    operand="imm"
                f["operand"]=operand
            if name in {"c.addi","c.addiw","c.lwsp","c.ldsp","c.jr","c.jalr"}:
                test(0xf80,"notIn",[0],"rd/rs1=0 belongs to another encoding or is reserved")
            if name in {"c.mv","c.add"}:
                test(0x7c,"notIn",[0],"rs2=0 belongs to JR/JALR/EBREAK rather than MV/ADD")
            if name=="c.lui":
                test(0xf80,"notIn",[2<<7],"rd=x2 selects ADDI16SP")
                test(0x107c,"notIn",[0],"zero LUI immediate is reserved or redefined as C.MOP")
            if name in {"c.slli","c.srli","c.srai"}:
                tests.append({"mask":"0x1000","operator":"in","values":[0],"xlens":[32],"reason":"RV32 standard compressed shifts use five-bit shift amounts"})
            if name in {"c.addi4spn","c.addi16sp"}:
                test(0x1fe0 if name=="c.addi4spn" else 0x107c,"notIn",[0],"zero immediate is reserved")
            if name in {"c.slli","c.srli","c.srai"}:
                v["constraints"].append("On RV32, instruction bit 12 must be zero; bit 12=1 is custom/reserved rather than a standard shift")
            if name.startswith(("cm.push","cm.pop")):
                test(0xf0,"notIn",[0,16,32,48],"rlist=0..3 is reserved")
                v["constraints"].append("On RV32E rlist must be 4..6")
            if name in {"cm.jt","cm.jalt"}:
                test(0x3fc,"in" if name=="cm.jt" else "notIn",[i<<2 for i in range(32)],"indices 0..31 select JT; 32..255 select JALT")
            if name=="aes64ks1i":
                test(0xf00000,"in",[i<<20 for i in range(11)],"rnum=11..15 reserved")
            if name in {"cm.mvsa01","cm.mva01s"}:
                for f in v["fields"]:
                    if f.get("operand") in {"r1s'","r2s'"}:
                        f.pop("registerOffset",None)
                        f["registerMap"]=[8,9,18,19,20,21,22,23]
                v["constraints"].append("RV32E permits only s0/s1 encodings; CM.MVSA01 requires different destination saved registers")
            if tests:
                v["encodingConstraints"]=tests
            # A compact register may use two spellings for the same 3-bit field.
            for f in v["fields"]:
                if "value" not in f and "registerMap" not in f and "'" in f.get("operand","") and f["msb"]-f["lsb"]==2:
                    f["registerOffset"]=8
        if not inst["semanticSources"]:
            raise ValueError(f"No semantic evidence: {name}")


def assert_upstream():
    upstream = SOURCE.parent
    result = subprocess.run(["git", "-C", str(upstream), "rev-parse", "HEAD"], check=True, capture_output=True, text=True)
    if result.stdout.strip() != REVISION:
        raise ValueError("Manual revision does not match the source lock")
    if subprocess.run(["git", "-C", str(upstream), "status", "--porcelain"], check=True, capture_output=True, text=True).stdout.strip():
        raise ValueError("Manual has local changes")


def build():
    INSTRUCTIONS.clear()
    DIAGNOSTICS.clear()
    base_instructions()
    named_instructions()
    supplementary_instructions()
    from manual_vector_instructions import vector_instructions
    vector_instructions(add, source_for)
    register_file_variants()
    aliases()
    finalize()
    from instruction_context import add_context
    add_context(INSTRUCTIONS,source_for)
    from manual_aliases import add_aliases
    add_aliases(INSTRUCTIONS, source_for)
    return INSTRUCTIONS


def write():
    INST.mkdir(parents=True, exist_ok=True)
    files = sorted("manual-"+name+".json" for name in INSTRUCTIONS)
    names = [filename[len("manual-"):-len(".json")] for filename in files]
    # Do not delete arbitrary files: this importer only owns its namespace.
    for old in INST.glob("manual-*.json"):
        if old.name not in files:
            old.unlink()
    for name, filename in zip(names, files):
        inst = INSTRUCTIONS[name]
        inst["encodings"].sort(key=lambda v:(v["length"],v["xlens"],v["match"],v["requires"]))
        (INST/filename).write_text(json.dumps(inst,ensure_ascii=False,indent=2)+"\n",encoding="utf-8", newline="\n")
    hashes={filename:hashlib.sha256((INST/filename).read_bytes()).hexdigest() for filename in files}
    from manual_pseudoinstructions import build as build_pseudos, review as review_pseudos
    pseudos = build_pseudos(INSTRUCTIONS)
    review_pseudos(pseudos, INSTRUCTIONS)
    pseudo_files = []
    for entry in sorted(pseudos, key=lambda item: item["name"]):
        filename = f"pseudo-{entry['name']}.json"
        pseudo_files.append(filename)
        (INST/filename).write_text(json.dumps(entry,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    pseudo_hashes = {filename:hashlib.sha256((INST/filename).read_bytes()).hexdigest() for filename in pseudo_files}
    catalog = {"schemaVersion":2,"requiredFeatures":["operand-relations-v1","alias-assembly-v1","pseudo-references-v1"],"id":"riscv-instruction-catalog","architecture":"riscv","namespace":"manual",
        "version":"0.2.0","title":"RISC-V 指令离线数据库","source":{"title":"RISC-V ISA Manual 20250508","revision":REVISION,"url":REPOSITORY,"license":"CC-BY-4.0"},
        "directory":"inst","files":files,"fileHashes":hashes,"pseudoFiles":pseudo_files,"pseudoFileHashes":pseudo_hashes}
    CATALOG.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+"\n",encoding="utf-8", newline="\n")
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    REPORT.write_text(json.dumps({"revision":REVISION,"instructions":len(names),"encodings":sum(len(x["encodings"]) for x in INSTRUCTIONS.values()),"diagnostics":DIAGNOSTICS},ensure_ascii=False,indent=2)+"\n",encoding="utf-8", newline="\n")
    print(json.dumps({"instructions":len(names),"encodings":sum(len(x["encodings"]) for x in INSTRUCTIONS.values())}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    assert_upstream()
    build()
    write()
