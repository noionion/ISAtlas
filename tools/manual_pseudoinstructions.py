"""Reference-only expansions from the pinned manual; never invent a machine encoding."""
from copy import deepcopy
import hashlib
import json
import re

from instruction_sources import SOURCE, REVISION, evidence


def source(path, start, stop):
    text = (SOURCE / path).read_text(encoding="utf-8")
    first = text.index(start)
    last = text.index(stop, first)
    return evidence(path, first, last, "manual-pseudoinstruction-expansion")


def expansion(syntax, condition, condition_en, steps, clobbers=()):
    return {"syntax": syntax, "condition": condition, "conditionEn": condition_en,
            "steps": [{"name": step.split()[0], "assembly": step} for step in steps],
            "clobbers": list(clobbers)}


def build(instructions):
    entries = []
    call_source = source("unpriv-cfi.adoc", "When the register source and register destination are both link registers,", "Software guarded branches may also")
    vector_source = source("v-st-ext.adoc", "The `vmsge{u}.vx` operation can be synthesized", "Compares effectively AND")
    call_notes = "手册中的序列示例，最终序列及立即数由汇编器、目标地址和重定位决定，不在此生成机器码。ra 表示链接寄存器 x1 或 x5；语义直接调用的地址必须来自目标符号的 PC 相对或绝对偏移。"
    call_notes_en = "Manual sequence examples only: the assembler, target and relocations determine the final sequence and immediates. No machine code is generated here. ra denotes link register x1 or x5; a semantically direct call must derive its address from a PC-relative or absolute symbol offset."
    recipes = [
        ("call", "调用目标过程并保存返回地址。", "Call a procedure and save the return address.", [["E"], ["I"]], call_source,
         [expansion("call offset", "PC 相对地址示例", "PC-relative address example", ["auipc ra, imm20", "jalr ra, imm12(ra)"], ["ra"]),
          expansion("call offset", "绝对地址示例", "Absolute address example", ["lui ra, imm20", "jalr ra, imm12(ra)"], ["ra"])], call_notes, call_notes_en),
        ("tail", "尾调用目标过程，不保存新的返回地址。", "Tail-call a procedure without saving a new return address.", [["E"], ["I"]], call_source,
         [expansion("tail offset", "PC 相对地址示例", "PC-relative address example", ["auipc x7, imm20", "jalr x0, x7"], ["x7"]),
          expansion("tail offset", "绝对地址示例", "Absolute address example", ["lui x7, imm20", "jalr x0, x7"], ["x7"])],
         "仅展示该手册的结构示例，保留其 jalr 写法；此处未定义完整立即数拆分与重定位，不作为通用汇编器展开规则。x7 会被改写；地址必须来自目标符号的 PC 相对或绝对偏移。",
         "Structural examples from this manual only, retaining its jalr notation. The passage does not specify complete immediate splitting or relocations; this is not a universal assembler expansion rule. x7 is clobbered; the address must derive from a PC-relative or absolute symbol offset."),
    ]
    for unsigned in (False, True):
        name = "vmsgeu.vx" if unsigned else "vmsge.vx"
        base = "vmsltu.vx" if unsigned else "vmslt.vx"
        requires = deepcopy(instructions[base]["encodings"][0]["requires"])
        recipes.append((name, f"逐元素比较 va {'≥u' if unsigned else '≥s'} x，将结果写入掩码。",
            f"Compare vector elements va >= x using {'unsigned' if unsigned else 'signed'} ordering and write mask bits.", requires, vector_source,
            [expansion(f"{name} vd, va, x", "无掩码", "Unmasked", [f"{base} vd, va, x", "vmnand.mm vd, vd, vd"]),
             expansion(f"{name} vd, va, x, v0.t", "使用掩码且 vd ≠ v0", "Masked and vd != v0", [f"{base} vd, va, x, v0.t", "vmxor.mm vd, vd, v0"]),
             expansion(f"{name} vd, va, x, v0.t, vt", "使用掩码且 vd = v0", "Masked and vd == v0", [f"{base} vt, va, x", "vmandn.mm vd, vd, vt"], ["vt"]),
             expansion(f"{name} vd, va, x, v0.t, vt", "使用掩码，适用于任意 vd", "Masked, any vd", [f"{base} vt, va, x", "vmandn.mm vt, v0, vt", "vmandn.mm vd, vd, v0", "vmor.mm vd, vt, vd"], ["vt"]),
             expansion(f"{name} vd, va, x, vm", "x 大于最小可表示值，减一不回绕；t0 为临时整数寄存器", "x is above the minimum and decrement cannot wrap; t0 is a temporary integer register",
                       ["addi t0, x, -1", f"vmsgt{'u' if unsigned else ''}.vx vd, va, t0, vm"], ["t0"])],
            "vt 是临时向量寄存器，必须不同于 vd，并会被改写。按实际掩码和目的寄存器条件选择序列，仍需满足向量状态及各基础指令的执行条件。",
            "vt is a temporary vector register distinct from vd and is clobbered. Select a sequence using the actual mask and destination conditions; vector state and the underlying instruction requirements still apply."))
    for unsigned in [False, True]:
        for op, base_op, comparison in [("vmslt", "vmsle", "<"), ("vmsge", "vmsgt", "≥")]:
            name = f"{op}{'u' if unsigned else ''}.vi"
            base = f"{base_op}{'u' if unsigned else ''}.vi"
            marker = "va < i          " if op == "vmslt" else "va >= i         "
            text = (SOURCE / "v-st-ext.adoc").read_text(encoding="utf-8")
            start = text.index(marker)
            stop = text.index("NOTE: The `vmsgt`", start)
            src = evidence("v-st-ext.adoc", start, stop, "immediate-comparison-transformation")
            notes = ("展开使用 i-1，必须满足基础指令的 5 位符号扩展立即数编码；这里保留符号变换，不生成机器码。" +
                     ("无符号常量范围为 1..16 或 2^SEW-15..2^SEW，0 与 2^SEW 的恒真/恒假边界见手册。" if unsigned else "有符号 i 的有效范围为 -15..16。"))
            notes_en = ("The expansion uses i-1 and must fit the base instruction's five-bit sign-extended immediate. This reference does not generate code. " +
                        ("Unsigned ranges are 1..16 or 2^SEW-15..2^SEW; see the manual for constant results at 0 and 2^SEW." if unsigned else "The effective signed i range is -15..16."))
            recipes.append((name, f"逐元素比较 va {comparison}{'u' if unsigned else 's'} i，将结果写入掩码。",
                f"Compare va {comparison} i using {'unsigned' if unsigned else 'signed'} ordering and write mask bits.",
                deepcopy(instructions[base]["encodings"][0]["requires"]), src,
                [expansion(f"{name} vd, va, i, vm", "将立即数 i 减一后使用基础比较指令", "Use the base comparison with immediate i-1", [f"{base} vd, va, i-1, vm"])], notes, notes_en))
    for name, zh, en, requires, src, variants, notes, notes_en in recipes:
        entries.append({"schemaVersion": 1, "id": f"riscv/manual/pseudo/{name}", "architecture": "riscv",
            "namespace": "manual", "name": name, "kind": "pseudo", "summary": zh, "summaryEn": en,
            "xlens": [32, 64], "privilege": "U", "requires": requires, "sourceRevision": REVISION,
            "license": "CC-BY-4.0", "semanticSources": [src], "notes": notes, "notesEn": notes_en, "expansions": variants})
    mentions = [
        ("li", "将立即数装入整数寄存器（手册示例名称）。", "Load an immediate into an integer register (name used in manual examples).", "example",
         "a-st-ext.adoc", "        li a0, 0", "The AMOSWAP instruction", [["E"], ["I"]],
         "手册示例使用 li 设置整数值，但未给出针对任意常量和 XLEN 的完整汇编器展开规则；此处仅保留名称和来源，不猜测指令序列。",
         "The manual uses li to set integer values but does not define a complete assembler expansion for arbitrary constants and XLENs. This entry records the name and source without guessing a sequence."),
        ("la", "获取符号地址（手册示例名称）。", "Obtain a symbol address (name used in a manual example).", "example",
         "machine.adoc", "            la t1, mtimecmp", "In a multi-hart system", [["E"], ["I"]],
         "手册示例用 la 获取 mtimecmp 地址，未定义通用的代码模型、PIC、重定位或链接器松弛规则；不从示例推定唯一展开或编码。",
         "The example uses la for the mtimecmp address but does not define general code-model, PIC, relocation or linker-relaxation rules. No unique sequence or encoding is inferred."),
        ("rev", "历史位反转伪指令名称，手册将其关联到 grevi。", "Historical bit-reversal pseudo name associated with grevi in the manual.", "historical",
         "scalar-crypto.adoc", "Recall that `rev`, `zip` and `unzip`", "[%header", [],
         "该段是历史说明，未给出 rev/grevi 在当前版本中的完整编码与参数映射；不将其与当前 rev8 混同，也不推定支持扩展。",
         "This historical note gives no complete current encoding or parameter mapping for rev/grevi. It is not treated as rev8, and no supported extension is inferred."),
    ]
    history = {"mftx.s": "fmv.x.s", "mftx.d": "fmv.x.d", "mxtf.s": "fmv.s.x", "mxtf.d": "fmv.d.x", "mffsr": "frcsr", "mtfsr": "fscsr"}
    for name, current in history.items():
        mentions.append((name, f"历史名称参考，后续手册改称 {current}。", f"Historical name subsequently renamed to {current}.", "historical",
            "colophon.adoc", "* The `MFTX.S`", "* `FCLASS.S`", [],
            "版本历史还记载了操作数字段等变化，不能仅凭名称对应就视为当前编码的兼容别名；仅保留来源查阅，不参与编码反查。",
            "The revision history also records operand-field changes. A name correspondence alone does not establish a current encoding alias; this is a source reference excluded from code lookup."))
    for name, zh, en, status, path, first, last, requires, notes, notes_en in mentions:
        text = (SOURCE / path).read_text(encoding="utf-8")
        start = text.index(first)
        if path in {"a-st-ext.adoc", "machine.adoc"}:
            # Preserve the complete enclosing literal example without claiming a general mapping.
            stop = text.index("\n...." if path == "machine.adoc" else "\n\n", start)
        else:
            stop = text.index(last, start)
        entries.append({"schemaVersion": 1, "id": f"riscv/manual/pseudo/{name}", "architecture": "riscv", "namespace": "manual",
            "name": name, "kind": "pseudo", "summary": zh, "summaryEn": en, "referenceStatus": status,
            "xlens": [32, 64], "privilege": "U", "requires": requires, "sourceRevision": REVISION, "license": "CC-BY-4.0",
            "semanticSources": [evidence(path, start, stop, "mentioned-name-only")], "notes": notes, "notesEn": notes_en, "expansions": []})
    return entries


def review(entries, instructions):
    """Check every reference, bilingual field, line fingerprint and literal vector expansion."""
    from review_manual_instructions import bilingual, source_check, require, EXTENSIONS
    cache = {}
    require(len({entry["id"] for entry in entries}) == len(entries), "Duplicate pseudo identity")
    for entry in entries:
        require(set(entry) <= {"schemaVersion", "id", "architecture", "namespace", "name", "kind", "summary", "summaryEn", "xlens", "privilege", "requires", "sourceRevision", "license", "semanticSources", "notes", "notesEn", "expansions", "referenceStatus"}, "Unexpected pseudo metadata")
        require(entry["kind"] == "pseudo" and "encodings" not in entry, "Pseudo must not pretend to have a single encoding")
        require(entry["id"] == f"riscv/manual/pseudo/{entry['name']}" and re.fullmatch(r"[a-z][a-z0-9.]*", entry["name"]), "Invalid pseudo identity")
        require(entry["sourceRevision"] == REVISION and entry["license"] == "CC-BY-4.0", "Invalid pseudo provenance")
        require(entry["xlens"] == [32, 64] and entry["privilege"] == "U", "Invalid pseudo applicability")
        status = entry.get("referenceStatus", "defined")
        require(status in {"defined", "example", "historical"}, "Invalid reference status")
        require((entry["requires"] or status == "historical") and all(group and set(group) <= EXTENSIONS for group in entry["requires"]), "Invalid pseudo requirements")
        bilingual(entry)
        bilingual({"summary": entry["notes"], "summaryEn": entry["notesEn"]})
        require(entry["semanticSources"] and (entry["expansions"] or status != "defined"), "Missing pseudo source or expansion")
        for src in entry["semanticSources"]:
            source_check(src, cache)
        for variant in entry["expansions"]:
            bilingual({"summary": variant["condition"], "summaryEn": variant["conditionEn"]})
            require(variant["syntax"].startswith(entry["name"] + " ") and len(variant["steps"]) >= 1, "Invalid pseudo syntax/sequence")
            for step in variant["steps"]:
                require(step["name"] == step["assembly"].split()[0] and step["name"] in instructions, "Unresolved pseudo step")
            if entry["name"] in {"vmsge.vx", "vmsgeu.vx"}:
                text = (SOURCE / "v-st-ext.adoc").read_text(encoding="utf-8")
                generic = "; ".join(step["assembly"] for step in variant["steps"])
                for stem in ["vmslt", "vmsgt"]:
                    generic = generic.replace(stem+"u.vx", stem+"{u}.vx").replace(stem+".vx", stem+"{u}.vx")
                require(re.sub(r"\s+", " ", generic) in re.sub(r"\s+", " ", text), "Vector sequence differs from manual")
    return {"pseudoinstructions": len(entries), "expansions": sum(len(entry["expansions"]) for entry in entries), "errors": []}


def review_assets(assets, instructions):
    manifest = json.loads((assets / "riscv-instructions.json").read_text(encoding="utf-8"))
    files = manifest.get("pseudoFiles", [])
    hashes = manifest.get("pseudoFileHashes", {})
    if files != sorted(set(files)) or set(files) != set(hashes):
        raise ValueError("Missing or duplicate pseudo index/hash")
    if set(files) != {path.name for path in (assets / "inst").glob("pseudo-*.json")}:
        raise ValueError("Unindexed pseudo file")
    entries = []
    for filename in files:
        if not re.fullmatch(r"pseudo-[a-z][a-z0-9.]*\.json", filename):
            raise ValueError("Invalid pseudo filename")
        data = (assets / "inst" / filename).read_bytes()
        if b"\r" in data or hashlib.sha256(data).hexdigest() != hashes[filename]:
            raise ValueError("Pseudo file hash or newline mismatch")
        entry = json.loads(data)
        if filename != f"pseudo-{entry['name']}.json":
            raise ValueError("Pseudo filename/identity mismatch")
        entries.append(entry)
    result = review(entries, instructions)
    if sorted(entries, key=lambda item: item["name"]) != sorted(build(instructions), key=lambda item: item["name"]):
        raise ValueError("Stored pseudo data differs from the source adapter")
    return result
