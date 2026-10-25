"""Explicit assembler aliases in the pinned manual, derived from canonical encodings."""
from copy import deepcopy
import re

from instruction_sources import REVISION


def add_aliases(instructions, source_for):
    added = []

    def alias(name, base, zh, en, path, needle, operands, fixed=None, relations=(), variants=None, extra_sources=()):
        if name in instructions:
            raise ValueError("Duplicate alias: " + name)
        src = source_for(path, needle, "documented-assembler-alias")
        entry = {"schemaVersion": 1, "id": f"riscv/manual/inst/{name}", "architecture": "riscv", "namespace": "manual",
                 "name": name, "summary": zh, "summaryEn": en, "kind": "alias", "aliasOf": base,
                 "assemblyOperands": operands, "sourceRevision": REVISION, "license": "CC-BY-4.0",
                 "semanticSources": [src, *extra_sources], "encodings": []}
        for original in variants or instructions[base]["encodings"]:
            v = deepcopy(original)
            for f in v["fields"]:
                if f.get("operand") in (fixed or {}):
                    value = fixed[f["operand"]]
                    mapping = f.get("operandBits", list(range(f["msb"] - f["lsb"], -1, -1)))
                    width = max(mapping) + 1
                    value %= 1 << width
                    f["value"] = sum(((value >> target) & 1) << (len(mapping) - index - 1) for index, target in enumerate(mapping))
                    for key in ("operand", "registerClass", "operandBits", "operandSigned", "registerOffset", "registerMap"):
                        f.pop(key, None)
            v["mask"] = "0x" + format(sum(((1 << (f["msb"]-f["lsb"]+1))-1) << f["lsb"] for f in v["fields"] if "value" in f), f"0{v['length']//4}x")
            v["match"] = "0x" + format(sum(f["value"] << f["lsb"] for f in v["fields"] if "value" in f), f"0{v['length']//4}x")
            if relations:
                v["operandRelations"] = [{"left": a, "right": b, "operator": "eq"} for a, b in relations]
                v["constraints"].extend(f"{a} = {b}" for a, b in relations)
            v["sources"].extend([src, *extra_sources])
            if v.get("registerFile") == "integer":
                v["summary"], v["summaryEn"] = zh.replace("F(", "X("), en.replace("F(", "X(")
            else:
                v.pop("summary", None)
                v.pop("summaryEn", None)
            entry["encodings"].append(v)
        instructions[name] = entry
        added.append(name)

    csr_source = ("zicsr.adoc", r"The assembler pseudoinstruction to read a CSR")
    for name, base, operands, fixed, zh, en in [
        ("csrr", "csrrs", ["rd", "csr"], {"rs1": 0}, "读取 CSR 到 rd，不修改 CSR。", "Read a CSR into rd without modifying it."),
        ("csrw", "csrrw", ["csr", "rs1"], {"rd": 0}, "将 rs1 写入 CSR，不读取旧值。", "Write rs1 to a CSR without reading its old value."),
        ("csrwi", "csrrwi", ["csr", "imm"], {"rd": 0}, "将 5 位无符号立即数写入 CSR，不读取旧值。", "Write a five-bit unsigned immediate to a CSR without reading its old value."),
        ("csrs", "csrrs", ["csr", "rs1"], {"rd": 0}, "按 rs1 掩码置位 CSR，不保留读取值。", "Set CSR bits selected by rs1 without retaining the read value."),
        ("csrc", "csrrc", ["csr", "rs1"], {"rd": 0}, "按 rs1 掩码清零 CSR，不保留读取值。", "Clear CSR bits selected by rs1 without retaining the read value."),
        ("csrsi", "csrrsi", ["csr", "imm"], {"rd": 0}, "按 5 位立即数掩码置位 CSR，不保留读取值。", "Set CSR bits selected by a five-bit immediate without retaining the read value."),
        ("csrci", "csrrci", ["csr", "imm"], {"rd": 0}, "按 5 位立即数掩码清零 CSR，不保留读取值。", "Clear CSR bits selected by a five-bit immediate without retaining the read value."),
    ]:
        src = csr_source if name in {"csrr", "csrw", "csrwi"} else ("zicsr.adoc", "Further assembler pseudoinstructions")
        alias(name, base, zh, en, *src, operands, fixed)

    for csr, address, read, write in [("fcsr", 3, "frcsr", "fscsr"), ("frm", 2, "frrm", "fsrm"), ("fflags", 1, "frflags", "fsflags")]:
        for name, base, operands, fixed, zh, en in [
            (read, "csrrs", ["rd"], {"csr": address, "rs1": 0}, f"读取 {csr} 到整数寄存器 rd。", f"Read {csr} into integer register rd."),
            (write, "csrrw", ["rd", "rs1"], {"csr": address}, f"将 rs1 写入 {csr}，旧值保存到整数寄存器 rd。", f"Write rs1 to {csr} and save the old value in integer register rd."),
        ]:
            variants = []
            for ext in ["F", "Zfinx"]:
                v = deepcopy(instructions[base]["encodings"][0])
                v["requires"] = [[ext]]
                if ext == "F":
                    fp = next(item for item in instructions["fadd.s"]["encodings"] if ["F"] in item["requires"])
                    v["contextRules"].extend(deepcopy(rule) for rule in fp["contextRules"] if rule["check"] == "fp-state")
                variants.append(v)
            extra = [source_for("zfinx.adoc", "The Zfinx extension depends", "integer-fp-control-registers"),
                     source_for("priv-csrs.adoc", r"\|`0x001`", "floating-csr-addresses")]
            needle = "The `fcsr` register can be read" if csr == "fcsr" else "The fields within the `fcsr` can also"
            alias(name, base, zh, en, "f-st-ext.adoc", needle, operands, fixed, variants=variants, extra_sources=extra)

    for suffix, path in [("s", "f-st-ext.adoc"), ("d", "d-st-ext.adoc"), ("q", "q-st-ext.adoc"), ("h", "zfh.adoc")]:
        for stem, base_stem, formula in [("fmv", "fsgnj", "F(rd) = F(rs1)"), ("fneg", "fsgnjn", "F(rd) = -F(rs1)"), ("fabs", "fsgnjx", "F(rd) = abs(F(rs1))")]:
            base = f"{base_stem}.{suffix}"
            extra = [] if suffix == "s" else [source_for(path, "[Ss]ign.[Ii]njection", "precision-sign-injection")]
            alias(f"{stem}.{suffix}", base, formula, formula, "f-st-ext.adoc", r"Note, FSGNJ.S", ["rd", "rs1"], relations=[("rs1", "rs2")], extra_sources=extra)

    branch_src = ("rv32.adoc", "Note, BGT, BGTU, BLE, and BLEU")
    for name, base, cmp in [("bgt", "blt", ">s"), ("bgtu", "bltu", ">u"), ("ble", "bge", "≤s"), ("bleu", "bgeu", "≤u")]:
        formula = f"if X(rs2) {cmp} X(rs1): PC += sext(offset)"
        alias(name, base, formula, formula, *branch_src, ["rs2", "rs1", "pc:imm"])
    alias("c.sext.w", "c.addiw", "X(rd) = sext32(X(rd))", "X(rd) = sext32(X(rd))", "zc.adoc", "_c.sext.w_ is a pseudoinstruction", ["rd/rs1"], {"imm": 0})
    alias("neg", "sub", "X(rd) = -X(rs2)", "X(rd) = -X(rs2)", "b-st-ext.adoc", r"using the following sequence: \*neg", ["rd", "rs2"], {"rs1": 0})
    alias("beqz", "beq", "if X(rs1) == 0: PC += sext(offset)", "if X(rs1) == 0: PC += sext(offset)", "unpriv-cfi.adoc", r"beqz t0, zicfiss_not_active", ["rs1", "pc:imm"], {"rs2": 0})
    alias("bnez", "bne", "if X(rs1) != 0: PC += sext(offset)", "if X(rs1) != 0: PC += sext(offset)", "a-st-ext.adoc", r"bnez t0, cas", ["rs1", "pc:imm"], {"rs2": 0})

    vector = "v-st-ext.adoc"
    recipes = [
        ("vneg.v", "vrsub.vx", "V(vd) = -V(vs2)", ["vd", "vs2", "vm"], {"rs1": 0}, (), r"assembly pseudoinstruction `vneg.v"),
        ("vnot.v", "vxor.vi", "V(vd) = ~V(vs2)", ["vd", "vs2", "vm"], {"imm": -1}, (), r"pseudoinstruction `vnot.v"),
        ("vwcvt.x.x.v", "vwadd.vx", "V(vd) = sext_2SEW(V(vs2))", ["vd", "vs2", "vm"], {"rs1": 0}, (), r"pseudoinstructions `vwcvt.x.x.v"),
        ("vwcvtu.x.x.v", "vwaddu.vx", "V(vd) = zext_2SEW(V(vs2))", ["vd", "vs2", "vm"], {"rs1": 0}, (), r"pseudoinstructions `vwcvt.x.x.v"),
        ("vncvt.x.x.w", "vnsrl.wx", "V(vd) = low_SEW(V(vs2))", ["vd", "vs2", "vm"], {"rs1": 0}, (), r"pseudoinstruction is provided `vncvt.x.x.w"),
        ("vfneg.v", "vfsgnjn.vv", "V(vd) = -V(vs2)", ["vd", "vs2", "vm"], {}, [("vs1", "vs2")], r"pseudoinstruction is provided: `vfneg.v"),
        ("vfabs.v", "vfsgnjx.vv", "V(vd) = abs(V(vs2))", ["vd", "vs2", "vm"], {}, [("vs1", "vs2")], r"pseudoinstruction is provided: `vfabs.v"),
        ("vmmv.m", "vmand.mm", "V(vd).mask = V(vs2).mask", ["vd", "vs2"], {}, [("vs1", "vs2")], r"vmmv.m vd, vs  =>"),
        ("vmclr.m", "vmxor.mm", "V(vd).mask = 0", ["vd"], {}, [("vs1", "vd"), ("vs2", "vd")], r"vmmv.m vd, vs  =>"),
        ("vmset.m", "vmxnor.mm", "V(vd).mask = 1", ["vd"], {}, [("vs1", "vd"), ("vs2", "vd")], r"vmmv.m vd, vs  =>"),
        ("vmnot.m", "vmnand.mm", "V(vd).mask = ~V(vs2).mask", ["vd", "vs2"], {}, [("vs1", "vs2")], r"vmmv.m vd, vs  =>"),
    ]
    for name, base, formula, operands, fixed, relations, needle in recipes:
        alias(name, base, formula, formula, vector, needle, operands, fixed, relations)
    for unsigned in [False, True]:
        for op, base_op, cmp in [("vmsgt", "vmslt", ">"), ("vmsge", "vmsle", "≥")]:
            name, base = f"{op}{'u' if unsigned else ''}.vv", f"{base_op}{'u' if unsigned else ''}.vv"
            formula = f"V(vd).mask = (V(vs1) {cmp}{'u' if unsigned else 's'} V(vs2))"
            needle = rf"va {'>' if op == 'vmsgt' else '>='} vb[^\n]*{op}\{{u\}}"
            alias(name, base, formula, formula, vector, needle, ["vd", "vs1", "vs2", "vm"])
    for name, base, cmp in [("vmfgt.vv", "vmflt.vv", ">"), ("vmfge.vv", "vmfle.vv", "≥")]:
        formula = f"V(vd).mask = (FP(V(vs1)) {cmp} FP(V(vs2)))"
        alias(name, base, formula, formula, vector, rf"va {'>' if name == 'vmfgt.vv' else '>='} vb[^\n]*{name}", ["vd", "vs1", "vs2", "vm"])

    for count in [1, 2, 4, 8]:
        base = f"vl{count}re8.v"
        zh, en = f"整组装载 {count} 个向量寄存器，EEW=8。", f"Load a whole group of {count} vector registers with EEW=8."
        alias(f"vl{count}r.v", base, zh, en, vector, rf"vl{count}r\.v\s", ["vd", "(rs1)"])

    compat = [
        ("fmv.x.s", "fmv.x.w", "f-st-ext.adoc", "The FMV.W.X and FMV.X.W instructions were previously", ["rd", "rs1"]),
        ("fmv.s.x", "fmv.w.x", "f-st-ext.adoc", "The FMV.W.X and FMV.X.W instructions were previously", ["rd", "rs1"]),
        ("vle1.v", "vlm.v", vector, "The previous assembler mnemonics `vle1.v`", ["vd", "(rs1)"]),
        ("vse1.v", "vsm.v", vector, "The previous assembler mnemonics `vle1.v`", ["vs3", "(rs1)"]),
        ("vfrsqrte7.v", "vfrsqrt7.v", vector, "An earlier draft version had used the assembler name `vfrsqrte7`", ["vd", "vs2", "vm"]),
        ("vfrece7.v", "vfrec7.v", vector, "An earlier draft version had used the assembler name `vfrece7`", ["vd", "vs2", "vm"]),
        ("vfredsum.vs", "vfredusum.vs", vector, "Older assembler mnemonic `vfredsum`", ["vd", "vs2", "vs1", "vm"]),
        ("vfwredsum.vs", "vfwredusum.vs", vector, "Older assembler mnemonic `vfwredsum`", ["vd", "vs2", "vs1", "vm"]),
        ("vmandnot.mm", "vmandn.mm", vector, "The previous assembler mnemonics `vmandnot`", ["vd", "vs2", "vs1"]),
        ("vmornot.mm", "vmorn.mm", vector, "The previous assembler mnemonics `vmandnot`", ["vd", "vs2", "vs1"]),
        ("vpopc.m", "vcpop.m", vector, "This instruction previously had the assembler mnemonic `vpopc.m`", ["rd", "vs2", "vm"]),
    ]
    for width in [8, 16, 32, 64]:
        compat.append((f"vsxei{width}.v", f"vsoxei{width}.v", vector, "The indexed operations mnemonics have", ["vs3", "(rs1)", "vs2", "vm"]))
    for name, base, path, needle, operands in compat:
        original = instructions[base]
        alias(name, base, f"{base} 的兼容旧名称；{original['summary']}", f"Legacy alias for {base}; {original['summaryEn']}", path, needle, operands)
    # vmcpy.m is the documented old name of the vmmv.m specialization, not a separate opcode.
    original = instructions["vmmv.m"]
    alias("vmcpy.m", "vmand.mm", "复制向量掩码寄存器（vmmv.m 的兼容旧名称）。", "Copy a vector mask register (legacy name for vmmv.m).", vector,
          "The `vmmv.m` instruction was previously called `vmcpy.m`", ["vd", "vs2"], relations=[("vs1", "vs2")])
    for name, base in [("scall", "ecall"), ("sbreak", "ebreak")]:
        original = instructions[base]
        alias(name, base, f"{base} 的兼容旧名称；{original['summary']}", f"Legacy alias for {base}; {original['summaryEn']}",
              "rv32.adoc", "ECALL and EBREAK were previously named SCALL", [])
    return added
