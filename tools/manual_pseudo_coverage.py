"""Fail closed on unreviewed pseudo/assembler-alias mentions in the entire pinned src tree."""
import hashlib
import json
from pathlib import Path
import re

from instruction_sources import SOURCE, ROOT, REVISION, REPOSITORY

MARKER = re.compile(r"pseudo[ -]?instructions?|assembler.{0,45}alias|older assembler|previous assembler|earlier draft.{0,30}assembler|reversing the operands|same functionality and encoding", re.I)

# Explicitly reviewed named forms, including inherited FP precision variants and retained spellings.
GROUPS = {
    "base": "j jr mv nop not ret seqz sext.w snez zext.w bgt bgtu ble bleu neg beqz bnez scall sbreak c.sext.w",
    "counters": "rdcycle rdcycleh rdtime rdtimeh rdinstret rdinstreth",
    "csr": "csrr csrw csrwi csrs csrc csrsi csrci frcsr fscsr frrm fsrm frflags fsflags",
    "scalar-fp": "fmv.s fneg.s fabs.s fmv.d fneg.d fabs.d fmv.q fneg.q fabs.q fmv.h fneg.h fabs.h fmv.x.s fmv.s.x",
    "vector": "vneg.v vnot.v vwcvt.x.x.v vwcvtu.x.x.v vncvt.x.x.w vfneg.v vfabs.v vmmv.m vmclr.m vmset.m vmnot.m vmsgt.vv vmsgtu.vv vmsge.vv vmsgeu.vv vmfgt.vv vmfge.vv vl1r.v vl2r.v vl4r.v vl8r.v",
    "vector-compatibility": "vle1.v vse1.v vfrsqrte7.v vfrece7.v vfredsum.vs vfwredsum.vs vmandnot.mm vmornot.mm vpopc.m vmcpy.m vsxei8.v vsxei16.v vsxei32.v vsxei64.v",
    "reference-sequences": "call tail vmsge.vx vmsgeu.vx vmslt.vi vmsltu.vi vmsge.vi vmsgeu.vi",
    "mentioned-only": "li la rev mftx.s mftx.d mxtf.s mxtf.d mffsr mtfsr",
}


def review_coverage(instructions, references, write_report=False):
    entries = {entry["name"]: entry for entry in [*instructions.values(), *references]}
    expected = {name for names in GROUPS.values() for name in names.split()}
    missing = sorted(expected - entries.keys())
    if missing:
        raise ValueError("Missing reviewed pseudo names: " + ", ".join(missing))
    unexpected = {name for name, entry in entries.items() if entry["kind"] in {"alias", "pseudo"}} - expected
    if unexpected:
        raise ValueError("Unreviewed pseudo names: " + ", ".join(sorted(unexpected)))
    records = []
    examples = []
    files = sorted(SOURCE.rglob("*.adoc"))
    for path in files:
        rel = path.relative_to(SOURCE).as_posix()
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines, 1):
            example = re.match(r"^\s*(?:[A-Za-z_]\w*:\s*)?([a-z][a-z0-9.]*)\s+((?:[axftsv][0-9]+|rd|rs[123]?|vd|vs[123]?|sp|zero|ra)\s*,.*)", line)
            if example:
                name = example[1]
                canonical = re.sub(r"\.(aqrl|aq|rl)$", "", name)
                if name in entries:
                    kind = "catalogued"
                elif canonical in instructions:
                    kind = "atomic-order-suffix"
                elif re.fullmatch(r"v(?:f|w|n)?op\.(?:vv|vx|vi|vf|wv|wx)", name):
                    kind = "syntax-template"
                elif name == "as" and example[2].startswith("zero, the"):
                    kind = "prose"
                else:
                    raise ValueError(f"Unreviewed assembly example name: {rel}:{number}: {name}")
                examples.append({"path": rel, "line": number, "name": name, "classification": kind})
            if not MARKER.search(line):
                continue
            related = sorted(name for name in expected if any(source["path"] == rel and source["lineStart"] <= number <= source["lineEnd"]
                                                            for source in entries[name]["semanticSources"]))
            status, reason = "covered", "关联条目保留该段来源指纹。"
            if not related:
                if rel == "hypervisor.adoc" and 2236 <= number <= 2547:
                    status, reason = "not-assembler", "mtinst/htinst 的特殊陷阱记录值，不是可执行的汇编伪指令。"
                elif rel == "colophon.adoc" and "expanded pseudoinstruction listing" in line:
                    status, reason = "general-note", "版本历史的概括性更新说明，没有新增命名定义。"
                elif rel == "c-st-ext.adoc" and "pseudoinstruction, which instead uses ADDI" in line:
                    status, reason, related = "cross-reference", "与 C.MV 对比的 MV 伪指令引用。", ["mv"]
                elif rel == "counters.adoc":
                    status, reason, related = "cross-reference", "计数器读取别名的重复说明。", GROUPS["counters"].split()
                elif rel == "b-st-ext.adoc" and line.strip() == "Pseudoinstructions::":
                    status, reason, related = "definition-heading", "紧随其后的 zext.w 展开定义。", ["zext.w"]
                elif rel == "v-st-ext.adoc" and line.strip().startswith("Comparison      Assembler Mapping"):
                    status, reason = "definition-heading", "整数或浮点比较映射表，表内全部命名形式已逐项收录。"
                    related = ["vmfgt.vv", "vmfge.vv"] if "pseudoinstruction" in line else ["vmsgt.vv", "vmsgtu.vv", "vmsge.vv", "vmsgeu.vv", "vmslt.vi", "vmsltu.vi", "vmsge.vi", "vmsgeu.vi"]
                elif rel == "v-st-ext.adoc" and ("Several assembler pseudoinstructions" in line or "4+| output  | instruction | pseudoinstruction" in line):
                    status, reason, related = "definition-heading", "掩码逻辑简写及真值表，四种命名形式已收录。", ["vmmv.m", "vmclr.m", "vmset.m", "vmnot.m"]
                else:
                    raise ValueError(f"Unreviewed pseudo/alias mention: {rel}:{number}: {line}")
            records.append({"path": rel, "line": number, "url": f"{REPOSITORY}/blob/{REVISION}/src/{rel}#L{number}",
                            "sha256": hashlib.sha256(line.encode()).hexdigest(), "classification": status, "reason": reason, "names": related})
    report = {"sourceRevision": REVISION, "sourceFilesScanned": len(files), "mentionsReviewed": len(records),
              "namedForms": len(expected), "encodedAliases": sum(entry["kind"] == "alias" for entry in entries.values()),
              "sequenceReferences": sum(entry["kind"] == "pseudo" and entry.get("referenceStatus", "defined") == "defined" for entry in entries.values()),
              "mentionedOnly": sum(entry["kind"] == "pseudo" and entry.get("referenceStatus", "defined") != "defined" for entry in entries.values()),
              "assemblyExamplesReviewed": len(examples), "assemblyExamples": examples,
              "groups": {key: value.split() for key, value in GROUPS.items()}, "records": records, "errors": []}
    if write_report:
        destination = ROOT / "docs/data/pseudo-coverage-review.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")
    return report
