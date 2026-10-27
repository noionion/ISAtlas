"""Merge direct AIA CSRs and source-backed supplements into the offline catalog.

Indirect IMSIC selectors and APLIC MMIO offsets are never CSR addresses.
The upstream checkout is input data; no AsciiDoc or build scripts are executed.
"""
from copy import deepcopy
import json
from pathlib import Path
import re
import subprocess

from csr_storage import read_catalog, write_catalog, catalog_sha256

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = ROOT / "upstream/riscv-aia"
ASSETS = ROOT / "app/src/main/assets"
REVISION = "20318d1b03f0ac8253458b82f8244d0d0db074e9"
REPOSITORY = "https://github.com/riscv/riscv-aia"
TAG = "20250312"
ATTRIBUTION = dict(namespace="aia", title="RISC-V Advanced Interrupt Architecture 20250312",
                   revision=REVISION, url=f"{REPOSITORY}/tree/{REVISION}", license="CC-BY-4.0")


def section(path, marker, end=None):
    lines = (UPSTREAM / "src" / path).read_text(encoding="utf-8").splitlines(keepends=True)
    start = next(i for i, line in enumerate(lines) if marker in line)
    stop = next((i for i in range(start + 1, len(lines)) if end and end in lines[i]), len(lines))
    return f"{REPOSITORY}/blob/{REVISION}/src/{path}#L{start + 1}-L{stop}"


def csr_rows():
    text = (UPSTREAM / "src/CSRs.adoc").read_text(encoding="utf-8")
    rows = {}
    for marker in ("CSRs-M", "CSRs-S", "CSRs-hypervisor"):
        table = text.split("[[" + marker + "]]", 1)[1].split("|===", 2)[1]
        cells = table.split("|")[1:]
        for i, cell in enumerate(cells):
            numbers = re.findall(r"0x[0-9A-Fa-f]+", cell)
            if not numbers:
                continue
            names = re.findall(r"`([a-z][a-z0-9]+)`", cells[i + 3])
            permissions = re.findall(r"[MSH]R[WO]", cells[i + 1])
            widths = re.findall(r"(?:[MSVH]+)?XLEN|\b(?:32|64)\b", cells[i + 2])
            assert len(numbers) == len(names) == len(permissions) == len(widths)
            for number, name, permission, width in zip(numbers, names, permissions, widths):
                assert name not in rows
                rows[name] = dict(address=int(number, 16), permission=permission, width=width)
    assert len(rows) == 41
    return rows


def field(name, high, low, zh, en, access, source, widths=(32, 64), condition="", values=None, aliases=()):
    pairs = values or {}
    return dict(name=name, summary=zh, summaryEn=en, access=access,
                layouts=[dict(xlen=w, msb=min(high, w - 1), lsb=low) for w in widths if low < w],
                condition=condition, aliases=list(aliases), sourceUrl=source,
                values={str(k): v[0] for k, v in pairs.items()},
                valuesEn={str(k): v[1] for k, v in pairs.items()}, valuesByXlen={}, valuesEnByXlen={})


def binary(kind="pending"):
    return ({0: ("无待处理中断", "No interrupt pending"), 1: ("中断待处理", "Interrupt pending")}
            if kind == "pending" else
            {0: ("禁止或不委托", "Disable or do not delegate"), 1: ("允许或委托", "Enable or delegate")})


def reserved(reg, source):
    """Fill only explicitly zero-reserved holes in newly defined fixed CSR formats."""
    reg["regions"] = []
    for width in reg["xlens"]:
        used = {bit for f in reg["fields"] for loc in f["layouts"] if loc["xlen"] == width
                for bit in range(loc["lsb"], loc["msb"] + 1)}
        holes = sorted(set(range(width)) - used)
        while holes:
            low = high = holes.pop(0)
            while holes and holes[0] == high + 1:
                high = holes.pop(0)
            reg["regions"].append(dict(label="0", access="RO0（保留）", summary="保留位，读零。",
                summaryEn="Reserved; reads as zero.", sourceUrl=source,
                layouts=[dict(xlen=width, msb=high, lsb=low)]))


def local_fields(reg, source, shift=0):
    pending = reg["name"].removesuffix("h").endswith("ip")
    suffix = "IP" if pending else "IE"
    zh_action = "待处理位" if pending else "使能位"
    en_action = "pending bits" if pending else "enable bits"
    visibility = reg["name"] in ("mvien", "hvien")
    if visibility:
        zh_action, en_action = "虚拟可见控制位", "virtual visibility controls"
    groups = [(16, 23, "LOCAL16_23", "标准本地中断", "standard local interrupts"),
              (24, 31, "CUSTOM24_31", "自定义中断", "custom interrupts"),
              (32, 34, "LOCAL32_34", "标准本地中断", "standard local interrupts"),
              (35, 35, "RASL" + suffix, "低优先级 RAS 事件", "low-priority RAS event"),
              (36, 42, "LOCAL36_42", "标准本地中断", "standard local interrupts"),
              (43, 43, "RASH" + suffix, "高优先级 RAS 事件", "high-priority RAS event"),
              (44, 47, "LOCAL44_47", "标准本地中断", "standard local interrupts"),
              (48, 63, "CUSTOM48_63", "自定义中断", "custom interrupts")]
    for low, high, name, zh, en in groups:
        if high < shift:
            continue
        low -= shift
        high -= shift
        layouts = [w for w in reg["xlens"] if low < w]
        if not layouts:
            continue
        description = f"{zh}的{zh_action}；未实现的中断位读零。"
        if pending:
            description = f"{zh}的{zh_action}；标准本地中断置位后保持，直至软件清除。"
        reg["fields"].append(field(name, high, low, description,
            f"{en_action.capitalize()} for {en}; unsupported sources read as zero.",
            "RW/RO0/RO1（依实现）" if visibility else "RW（受实现、委托及虚拟注入条件约束）", source, layouts,
            condition="Smaia" if reg["privilege"] == "M" else "Smaia Ssaia",
            values=binary("pending" if pending else "enable") if high == low else None,
            aliases=("interrupts",)))


def enrich(registers):
    registers = deepcopy([r for r in registers if r["namespace"] != "aia"])
    existing = {r["name"]: r for r in registers if r["namespace"] == "manual"}
    table_source = section("CSRs.adoc", "[[CSRs]]", "=== Virtual instruction exceptions")
    ms = section("MSLevel.adoc", "[[virtIntrs-S]]", "[[intrs-S]]")
    machine = section("MSLevel.adoc", "=== Interrupts at machine level", "[[intrPrios-M]]")
    local = section("MSLevel.adoc", "[[majorIntrs]]", "=== Interrupts at machine level")
    vs = section("VSLevel.adoc", "==== Virtual interrupts for VS level", "[[vstopi]]")
    priorities = section("VSLevel.adoc", "==== Configuring priorities of major interrupts", "==== Virtual interrupts for VS level")
    imsic = section("IMSIC.adoc", "=== Indirectly accessed interrupt-file registers", "=== Interrupt delivery and handling")
    top_external = section("IMSIC.adoc", "===  Top external interrupt CSRs", "=== Interrupt delivery and handling")
    state = section("CSRs.adoc", "[[CSRs-stateen]]")
    touched = set()
    new = []

    def attach(reg):
        touched.add(reg["name"])
        extensions = ["Smaia"] if reg["privilege"] == "M" else ["Smaia", "Ssaia"]
        reg["extensions"] = list(dict.fromkeys(reg.get("extensions", []) + extensions))
        if reg["namespace"] != "aia":
            reg["sources"] = [s for s in reg.get("sources", []) if s["namespace"] != "aia"] + [ATTRIBUTION.copy()]
        return reg

    summaries = {
        "miselect": ("选择机器态间接寄存器。", "Select a machine-level indirect register."),
        "mireg": ("访问 miselect 选中的间接寄存器。", "Access the indirect register selected by miselect."),
        "siselect": ("选择监管态间接寄存器。", "Select a supervisor-level indirect register."),
        "sireg": ("访问 siselect 选中的间接寄存器。", "Access the indirect register selected by siselect."),
        "vsiselect": ("选择虚拟监管态间接寄存器。", "Select a virtual supervisor-level indirect register."),
        "vsireg": ("访问 vsiselect 选中的间接寄存器。", "Access the indirect register selected by vsiselect."),
        "mvien": ("控制向 S 态呈现的虚拟中断。", "Control virtual interrupts exposed to supervisor level."),
        "mvip": ("保存向 S 态注入的虚拟中断待处理位。", "Hold virtual interrupt-pending bits for supervisor level."),
        "hvien": ("控制向 VS 态呈现的虚拟中断。", "Control virtual interrupts exposed to VS level."),
        "hvictl": ("控制 VS 虚拟中断的身份、优先级及访问截获。", "Control VS virtual interrupt identity, priority and access interception."),
        "hviprio1": ("配置 VS 中断 1、5、13–15 的优先级。", "Configure VS interrupt priorities for causes 1, 5 and 13–15."),
        "hviprio2": ("配置 VS 中断 16–23 的优先级。", "Configure VS interrupt priorities for causes 16–23."),
    }
    for name, row in csr_rows().items():
        if name in existing:
            assert existing[name]["locator"]["address"] == row["address"]
            attach(existing[name])
            continue
        privilege = "VS" if name.startswith("vs") else row["permission"][0]
        widths = [32] if row["width"] == "32" else [32, 64]
        zh, en = summaries.get(name, ("读取本级最高优先级的待处理中断。", "Read this level's highest-priority pending interrupt."))
        if row["width"] == "32":
            parent = {"midelegh": "mideleg", "hidelegh": "hideleg"}.get(name, name[:-1])
            zh, en = f"访问 {parent} 的高 32 位（仅 RV32）。", f"Access bits 63:32 of {parent} (RV32 only)."
        if name.endswith("topei"):
            zh, en = "读取 IMSIC 的最高优先级外部中断；写操作领取该中断。", "Read the highest-priority external interrupt; a write claims the current interrupt."
        reg = dict(id=f"riscv/aia/csr/{name}", namespace="aia", name=name, title=en.rstrip("."),
            summary=zh, summaryEn=en, locator=dict(type="riscv-csr", address=row["address"]),
            privilege=privilege, access=row["permission"][1:], xlens=widths,
            condition="Smaia" if privilege == "M" else "Smaia Ssaia" + (" H" if privilege in ("H", "VS") else ""),
            notes="AIA 20250312；可用性取决于特权模式、XLEN 与 IMSIC 实现。", sourceUrl=table_source,
            sourceRevision=REVISION, license="CC-BY-4.0", coverage="fields", fields=[], regions=[], extensions=[], sources=[ATTRIBUTION.copy()])
        attach(reg)
        if name in ("mvien", "mvip", "mvienh", "mviph", "midelegh"):
            reg["condition"] += " S-mode"
        new.append(reg)
    all_regs = existing | {r["name"]: r for r in new}

    # Preserve base CSR fields; add only AIA-defined ranges above bit 15.
    for name in ("mie", "mip", "sie", "sip", "hvip", "vsie", "vsip"):
        reg = all_regs[name]
        local_fields(reg, local)
        if name in ("mie", "mip"):
            reference = existing["hie" if name == "mie" else "hip"]
            for original in reference["fields"]:
                addition = deepcopy(original)
                addition["condition"] = "H"
                reg["fields"].append(addition)
            # Remove old schematic zeros only where H fields now supply a bit.
            occupied = {loc["lsb"] for f in reference["fields"] for loc in f["layouts"]}
            reg["regions"] = [region for region in reg["regions"]
                if not any(set(range(loc["lsb"], loc["msb"] + 1)) & occupied for loc in region["layouts"])]
        if name in ("sie", "sip", "vsie", "vsip", "hvip"):
            reg["notes"] += " AIA：13–63 位按委托和虚拟注入规则呈现；低位仍遵守基础特权规范。"
        if name in ("sip", "vsip"):
            f = next(f for f in reg["fields"] if f["name"] == "LCOFIP")
            f.update(summary="计数器溢出待处理位；AIA 下为可写的保持位，可由软件清除，呈现受委托/注入条件控制。",
                     summaryEn="Sticky, software-clearable counter-overflow pending bit under AIA, subject to delegation/injection controls.",
                     sourceUrl=ms if name == "sip" else vs, access="RW/RO0（依委托和注入条件）")
        if name == "mip":
            next(f for f in reg["fields"] if f["name"] == "SEIP").update(
                summary="S 外部中断待处理；mvien.SEIE=0 时含软件注入位，=1 时仅反映外部控制器信号。",
                summaryEn="Supervisor external pending; mvien bit 9 separates the software-injected pending bit when set.", sourceUrl=ms,
                access="RW（mvien[9]=0）/RO（mvien[9]=1）",
                typeRule="无 AIA 或 mvien[9]=0：读取为软件位与外部信号 OR，CSR 读改写仅修改软件位；AIA 且 mvien[9]=1：mip.SEIP 只读，仅反映外部控制器信号，软件位改由 mvip[9] 保存。")
        if name == "sip":
            next(f for f in reg["fields"] if f["name"] == "SEIP").update(
                summary="S 外部中断待处理；AIA 且 mideleg[9]=0、mvien[9]=1 时是 mvip[9] 的可写别名。",
                summaryEn="Supervisor external pending; with AIA, mideleg[9]=0 and mvien[9]=1 make it a writable alias of mvip[9].",
                access="RO / RW / RO0（依委托和虚拟注入路径）", sourceUrl=ms,
                typeRule="mideleg[9]=0 且 mvien[9]=1 时别名到 mvip[9]；二者都为 0 时读零；mideleg[9]=1 时遵守基础规范的 mip 只读视图规则。")

    for name in ("mideleg", "hideleg"):
        reg = all_regs[name]
        f = reg["fields"][0]
        f.update(summary="按原因位委托中断；AIA 扩宽至 64 位，RV32 的高半通过对应 h 寄存器访问。",
                 summaryEn="Delegate interrupts by cause bit; AIA extends to 64 bits, with RV32 high halves in the corresponding h CSR.")
        if name == "hideleg":
            f.update(summary="委托到 VS；AIA 的 13–63 位仅在 mideleg 或 mvien 对应位为 1 时才可能可写。",
                     summaryEn="Delegate to VS; AIA bits 13–63 may be writable only if the corresponding mideleg or mvien bit is set.")
        f["sourceUrl"] = ms

    # Virtual injection bitmaps; local/custom causes 13–63 have implementation-dependent writability.
    for name in ("mvien", "mvip", "hvien"):
        reg = all_regs[name]
        pending = name == "mvip"
        for bit in ((1, 5, 9) if pending else (1, 9) if name == "mvien" else ()):
            prefix = {1: "SS", 5: "ST", 9: "SE"}[bit]
            desc = ("S 软件虚拟待处理位；mvien[1]=0 时别名 mip.SSIP，否则独立。" if bit == 1 else
                    "mip.STIP 的可写别名；STIP 不可写时读零。" if bit == 5 else
                    "S 外部中断的软件注入位；mvien[9]=1 时与 mip.SEIP 分离。") if pending else "允许 S 态独立控制此虚拟中断；mideleg 对应位为 0 时生效。"
            eng = ("Virtual supervisor pending bit; aliasing depends on mvien and writable mip state." if pending else
                   "Enable independent supervisor control of this virtual interrupt when its mideleg bit is clear.")
            reg["fields"].append(field(prefix + ("IP" if pending else "IE"), bit, bit, desc, eng,
                "RW/RO0（依实现及别名条件）", ms, values=binary("pending" if pending else "enable")))
        reg["fields"].append(field("LOCAL13_15", 15, 13, "本地中断 13–15 的虚拟注入控制；可写性依实现而定。",
            "Virtual injection control for local causes 13–15; writability is implementation-dependent.",
            "RW/RO0/RO1（mvien/hvien）；RW/RO0（mvip）" if not pending else "RW/RO0（依 mvien 对应位）", vs if name == "hvien" else ms))
        local_fields(reg, vs if name == "hvien" else ms)
        reserved(reg, vs if name == "hvien" else ms)
    all_regs["mvip"]["notes"] += " mvien[1] 从 0 变 1 后 mvip[1] 为 UNSPECIFIED；改变 mvien[9] 不改变 mvip[9] 的值。"
    reg = all_regs["hvip"]
    reg["fields"].append(field("LOCAL13_15", 15, 13,
        "本地中断 13–15 的虚拟待处理位；可写或只读零，取决于 hvien 和实现。",
        "Virtual pending bits for local causes 13–15; writability depends on hvien and the implementation.",
        "RW/RO0", vs, condition="Smaia Ssaia"))
    # Preserve the low reserved positions 11/12 when removing the old 15:11 zero region.
    for region in reg["regions"]:
        for loc in region["layouts"]:
            if loc["msb"] == 15 and loc["lsb"] == 11:
                loc["msb"] = 12

    for name in ("mtopi", "stopi", "vstopi", "mtopei", "stopei", "vstopei"):
        reg = all_regs[name]
        external = name.endswith("topei")
        src = top_external if external else section("VSLevel.adoc", "[[vstopi]]", "==== Interrupt traps") if name == "vstopi" else section("MSLevel.adoc", "[[" + name + "]]", "=== WFI" if name == "stopi" else "[[virtIntrs-S]]")
        reg["fields"] = [field("IID", 26 if external else 27, 16,
            "最高优先级外部中断的次级身份号；0 表示没有满足条件的中断。" if external else "最高优先级候选中断的主原因号；整寄存器为 0 时没有候选。",
            "Minor interrupt identity; zero denotes no eligible external interrupt." if external else "Major identity of the highest-priority candidate; a zero CSR denotes no candidate.",
            "RO（写 CSR 领取中断）" if external else "RO", src, aliases=("InterruptIdentity",),
            values=None if external else {35: ("低优先级 RAS 本地中断", "Low-priority RAS local interrupt"), 43: ("高优先级 RAS 本地中断", "High-priority RAS local interrupt")}),
            field("IPRIO", 10 if external else 7, 0,
                "外部中断优先级，等于身份号；数值越小优先级越高。" if external else "候选中断优先级；0 为最高、255 为最低可表示优先级，简化实现可固定为 1。",
                "External priority equals identity; smaller values have higher priority." if external else "Reported priority: 0 is highest, 255 lowest representable; simplified implementations may report 1.",
                "RO（写 CSR 领取中断）" if external else "RO", src)]
        if external:
            reg["condition"] += " IMSIC" + (" hstatus.VGEIN 指定已实现的非零 guest interrupt file" if name == "vstopei" else "")
            reg["notes"] += " 写入值被忽略，写操作清除当前报告身份的 pending 位；通常用同一条 CSR 指令原子读取并领取。不受 eidelivery 或 *ie 全局信号控制。"
        else:
            reg["notes"] += " TOPI 不受本级全局中断使能位影响；返回 0 表示没有候选。"
        reserved(reg, src)

    reg = all_regs["hvictl"]
    reg["fields"] = [
        field("VTI", 30, 30, "截获 VS 对中断状态的访问与可能清除 pending 的写操作，并由 hvictl 指定候选。",
            "Intercept VS interrupt-state accesses and pending-clearing writes, and select the hvictl candidate.", "RW", vs,
            values={0: ("使用 vsip/vsie 及 hviprio 候选", "Use vsip/vsie and hviprio candidates"), 1: ("启用截获与虚拟候选控制", "Enable interception and virtual candidate control")}),
        field("IID", 27, 16, "虚拟中断主原因号；至少实现低 6 位，IID=9 可配合 IPRIO=0 表示无中断。",
            "Virtual major identity; at least six bits implemented; IID=9 with IPRIO=0 can represent no interrupt.", "WARL", vs),
        field("DPR", 9, 9, "VTI=1 且 IID≠9 时，指定相对 S 外部中断的默认优先次序。",
            "With VTI=1 and IID not 9, set default rank relative to supervisor external interrupts.", "RW", vs,
            values={0: ("默认优先级高于 SEI", "Default rank above SEI"), 1: ("默认优先级低于 SEI", "Default rank below SEI")}),
        field("IPRIOM", 8, 8, "选择 vstopi 返回固定优先级 1，或返回按实际候选计算的优先级。",
            "Choose fixed vstopi priority 1 or the priority computed from the selected candidate.", "RW", vs,
            values={0: ("vstopi.IPRIO=1", "vstopi.IPRIO=1"), 1: ("按候选优先级及饱和规则报告", "Report candidate priority with saturation")}),
        field("IPRIO", 7, 0, "虚拟中断优先级，完整支持 8 位；用于 hvictl 指定的候选。",
            "Full eight-bit priority for the candidate specified by hvictl.", "RW", vs)]
    reserved(reg, vs)

    for name, identities in (("hviprio1", [None, 1, None, 5, None, 13, 14, 15]), ("hviprio2", list(range(16, 24)))):
        reg = all_regs[name]
        for index, identity in enumerate(identities):
            if identity is not None:
                reg["fields"].append(field(f"IPRIO{identity}", index * 8 + 7, index * 8,
                    f"VS 中断 {identity} 的优先级；可为只读零，否则至少实现 max(IPRIOLEN,6) 位。",
                    f"VS cause {identity} priority; read-only zero or at least max(IPRIOLEN,6) implemented bits.",
                    "WARL（可为 RO0）", priorities))
        reserved(reg, priorities)

    for name in ("miselect", "siselect", "vsiselect"):
        reg = all_regs[name]
        virtual = name == "vsiselect"
        vals = {112: ("IMSIC eidelivery：中断递送控制", "IMSIC eidelivery: delivery control"),
                114: ("IMSIC eithreshold：中断阈值", "IMSIC eithreshold: interrupt threshold")}
        for i in range(16):
            vals[48 + i] = (("VS 不可访问 iprio" if virtual else f"iprio{i}；RV64 仅偶数索引有效"),
                            "Inaccessible at VS level" if virtual else f"iprio{i}; only even indices on RV64")
        for i in range(64):
            for base, array in ((128, "eip"), (192, "eie")):
                vals[base + i] = (f"IMSIC {array}{i}；RV64 仅偶数索引有效", f"IMSIC {array}{i}; only even indices on RV64")
        reg["fields"] = [field("SELECT", 63, 0,
            "选择间接寄存器；最高位为自定义选择空间，其他保留号不可当作 CSR 地址。",
            "Select an indirect register; the top bit designates custom selectors, not CSR addresses.", "WARL", table_source,
            values=vals, aliases=("eidelivery", "eithreshold", "eip", "eie", "iprio"))]
        reg["notes"] += (" vsiselect 至少支持 0–0x1FF；IMSIC 访问要求有效的非零 hstatus.VGEIN。" if virtual else
                         " miselect 至少支持 0–0x3F，有 IMSIC 时至少支持 0–0xFF；建议 siselect 至少支持 0–0x1FF。")
    for name in ("mireg", "sireg", "vsireg"):
        reg = all_regs[name]
        select = name.replace("ireg", "iselect")
        reg["fields"] = [field("DATA", 63, 0,
            f"访问 {select} 选择的 iprio 或 IMSIC eidelivery/eithreshold/eip/eie；访问属性由所选寄存器决定。",
            f"Access iprio or IMSIC state selected by {select}; access semantics depend on that register.",
            "依 selector：WARL/WLRL/RW/RO0", imsic)]
        reg["notes"] += " IMSIC：eidelivery 为 WARL（0 禁止、1 允许、0x40000000 可选旁路；guest 不支持旁路）；eithreshold 为 WLRL（0 无阈值，P>0 时仅 ID<P 可递送）。eip/eie 的未实现身份和 ID0 读零。RV64 的奇数 eip/eie selector 访问陷阱；0x71、0x73–0x7F 读零写忽略。"
        if name == "vsireg":
            reg["notes"] += " VS 不存在硬件 iprio 数组；selector 0x30–0x3F 不可访问，可由 hypervisor 模拟。有效 guest file 由 hstatus.VGEIN 选择。"
        else:
            reg["notes"] += " iprio selector 0x30–0x3F：RV32 每项四个 8 位优先级，RV64 仅偶数项有效、每项八个；每个优先级为 WARL 或只读零。"

    # RV32 high-half CSRs project the actual parent layouts, never duplicate low bits.
    for name, row in csr_rows().items():
        if row["width"] != "32":
            continue
        parent = all_regs[{"midelegh": "mideleg", "hidelegh": "hideleg"}.get(name, name[:-1])]
        reg = all_regs[name]
        for f in parent["fields"]:
            loc = next((b for b in f["layouts"] if b["xlen"] == 64 and b["msb"] >= 32), None)
            if loc:
                copied = deepcopy(f)
                copied["layouts"] = [dict(xlen=32, msb=loc["msb"] - 32, lsb=max(loc["lsb"], 32) - 32)]
                # Parent-wide bitmap enumeration cannot describe an isolated high half.
                if loc["lsb"] < 32:
                    copied.update(values={}, valuesEn={}, valuesByXlen={}, valuesEnByXlen={})
                    copied.update(summary=f"{parent['name']} 高 32 位的中断位图；本字段 bit i 对应中断原因 i+32。",
                                  summaryEn=f"Upper interrupt bitmap of {parent['name']}; bit i corresponds to interrupt cause i+32.")
                reg["fields"].append(copied)
        reserved(reg, table_source)

    # State-enable fields keep their base-manual identity and AIA-specific access rules.
    for name in ("mstateen0", "mstateen0h", "hstateen0", "hstateen0h"):
        reg = attach(existing[name])
        for f in reg["fields"]:
            if f["name"] in ("CSRIND", "AIA", "IMSIC"):
                f.update(sourceUrl=state, condition="Smstateen Smaia Ssaia", access="WARL（可为 RO0）")
                f["summary"], f["summaryEn"] = {
                    "CSRIND": ("控制低特权态访问 *iselect/*ireg；M 禁止时非法指令，H 禁止时 VS/VU 虚拟指令异常。", "Control lower-privilege indirect CSR access; M denial is illegal instruction, H denial is a virtual instruction exception."),
                    "AIA": ("控制不由 CSRIND/IMSIC 管理的 AIA 状态；为 0 时截获低特权态访问。", "Control AIA state outside CSRIND/IMSIC; zero intercepts lower-privilege access."),
                    "IMSIC": ("控制低特权态访问 IMSIC 状态；无 IMSIC 时读零，不会强制 hgeip/hgeie/VGEIN 为零。", "Control lower-privilege IMSIC access; absent IMSIC reads zero without forcing hgeip/hgeie/VGEIN to zero.")}[f["name"]]
    for name in ("hstatus", "hgeip", "hgeie"):
        reg = attach(existing[name])
        for f in reg["fields"]:
            if f["name"] in ("VGEIN", "GEIP", "GEIE"):
                f["sourceUrl"] = table_source if f["name"] == "VGEIN" else state
                if f["name"] == "VGEIN":
                    f.update(summary="选择 IMSIC guest interrupt file；0 或未实现编号使 VS 的 IMSIC 窗口和 vstopei 不可访问。",
                        summaryEn="Select an IMSIC guest interrupt file; zero or unimplemented indices make VS IMSIC windows and vstopei inaccessible.")
                else:
                    f["summary"] += " AIA：mstateen0.IMSIC=0 不强制此字段读零。"
                    f["summaryEn"] += " AIA: mstateen0.IMSIC=0 does not force this field to zero."
    for name in ("mcause", "scause", "vscause"):
        reg = attach(existing[name])
        f = next(f for f in reg["fields"] if f["name"] == "CODE")
        for code in range(16, 64):
            zh, en = ("低优先级 RAS 本地中断", "Low-priority RAS local interrupt") if code == 35 else (
                ("高优先级 RAS 本地中断", "High-priority RAS local interrupt") if code == 43 else
                ("为标准本地中断保留", "Reserved for standard local interrupts") if code < 24 or 32 <= code < 48 else
                ("自定义中断", "Custom interrupt"))
            f["values"][str(code)] = f["values"].get(str(code), "INT=0：保留；INT=1：平台定义").split("INT=1：")[0] + "INT=1：AIA " + zh
            old = f["valuesEn"].get(str(code), "INT=0: reserved; INT=1: platform-defined")
            f["valuesEn"][str(code)] = old.split("INT=1:")[0] + "INT=1: AIA " + en
        f["summary"] += " AIA 在 INT=1 时定义 35/43 为低/高优先级 RAS 本地中断。"
        f["summaryEn"] += " AIA defines interrupt causes 35/43 as low/high priority RAS events."
    for reg in registers + new:
        reg["fields"] = [f for f in reg["fields"] if f["layouts"]]
    return registers + new, dict(new=sorted(r["name"] for r in new), supplemented=sorted(touched - {r["name"] for r in new}))


def update_metadata():
    path = ROOT / "tools/data-sources.lock.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["date"] = "2026-10-27"
    data["sources"] = [s for s in data["sources"] if s["namespace"] != "aia"] + [dict(
        namespace="aia", directory="upstream/riscv-aia", repository=REPOSITORY,
        revision=REVISION, tag=TAG, license="CC-BY-4.0")]
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    notice = ASSETS / "THIRD_PARTY_NOTICES.txt"
    text = notice.read_text(encoding="utf-8")
    if f"AIA source: {REPOSITORY}" not in text:
        text += (f"\nRISC-V Advanced Interrupt Architecture; RISC-V International and contributors.\n"
                 f"AIA source: {REPOSITORY}\nTag: {TAG}\nCommit: {REVISION}\nLicense: CC-BY-4.0\n"
                 "Changes: extract direct CSR addresses and fields; supplement existing interrupt CSR definitions; "
                 "translate and summarize fields in Chinese and English; document indirect IMSIC access without inventing CSR addresses.\n")
        notice.write_text(text, encoding="utf-8", newline="\n")


def verify_checkout():
    actual = subprocess.check_output(["git", "-C", str(UPSTREAM), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISION:
        raise ValueError(f"Expected AIA tag {TAG} ({REVISION}), found {actual}")
    if subprocess.check_output(["git", "-C", str(UPSTREAM), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
        raise ValueError("AIA checkout has local modifications")


def run():
    # Use the common source pipeline so later Debug overlays cannot be lost.
    from import_isa_manual import run as regenerate
    regenerate()


if __name__ == "__main__":
    run()
