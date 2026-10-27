"""Import hart CSRs from ratified Debug 1.0 XML without running upstream code.

Trigger formats sharing tdata1/tdata3 are views, never duplicate CSR identities.
DM/DMI, JTAG, abstract-command and debugger-virtual registers are not hart CSRs.
"""
from copy import deepcopy
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from csr_storage import read_catalog, write_catalog, catalog_sha256

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
UPSTREAM = ROOT / "upstream/riscv-debug-spec"
REPOSITORY = "https://github.com/riscv/riscv-debug-spec"
REVISION = "5695c0aaa7a3c228e14702e1268eb5a5e50e3cb8"
TAG = "1.0"
ATTRIBUTION = dict(namespace="debug", title="RISC-V Debug Specification 1.0", revision=REVISION,
                   url=f"{REPOSITORY}/tree/{REVISION}", license="CC-BY-4.0")
FORMATS = {"mcontrol": 2, "icount": 3, "itrigger": 4, "etrigger": 5, "mcontrol6": 6, "tmexttrigger": 7}
FILES = ("core_registers.xml", "hwbp_registers.xml")

# Concise translations supplement, rather than replace, the pinned XML evidence.
SEMANTICS = {
    "debugver": ("报告实现的外部调试规范版本。", "Report the implemented external debug specification version."),
    "extcause": ("cause=7 时细分调试进入原因；0 表示 critical error。", "Refine Debug Mode entry cause 7; zero denotes a critical error."),
    "cetrig": ("Smdbltrp critical error 时选择进入 Debug Mode 或向平台报告错误。", "Choose Debug Mode entry or a platform signal on an Smdbltrp critical error."),
    "pelp": ("保存进入调试前的预期着陆垫状态，退出时用于恢复。", "Save the prior expected landing-pad state for restoration on debug exit."),
    "stepie": ("控制单步期间是否允许中断。", "Control whether interrupts remain enabled during single stepping."),
    "stopcount": ("选择在 Debug Mode 和相关单步期间冻结性能计数器。", "Select whether counters freeze in Debug Mode and applicable single steps."),
    "stoptime": ("选择在 Debug Mode 期间冻结时间计数器；实现可固定为 0。", "Select whether time freezes in Debug Mode; implementations may hardwire zero."),
    "cause": ("报告进入 Debug Mode 的原因，由硬件更新。", "Report the hardware-updated reason for entering Debug Mode."),
    "v": ("保存进入调试前的虚拟化状态，配合 PRV 决定恢复模式。", "Save virtualization state; combine with PRV to determine the resume mode."),
    "mprven": ("允许 Debug Mode 的内存访问使用 mstatus.MPRV 规则。", "Allow Debug Mode memory accesses to use mstatus.MPRV."),
    "nmip": ("报告是否有不可屏蔽中断待处理；实现可固定为 0。", "Report a pending non-maskable interrupt; implementations may hardwire zero."),
    "step": ("退出 Debug Mode 后执行单步，再进入调试。", "Single-step after resuming and re-enter Debug Mode."),
    "prv": ("保存进入调试前的特权模式，写入可改变恢复模式。", "Save prior privilege; writing can change the resume privilege mode."),
    "dpc": ("保存恢复执行的虚拟地址；具体取值取决于进入调试的原因。", "Hold the resume virtual address, depending on the reason for debug entry."),
    "dscratch0": ("供调试器保存临时数据，可选实现。", "Optional scratch storage for the debugger."),
    "dscratch1": ("供调试器保存临时数据，可选实现。", "Optional scratch storage for the debugger."),
    "index": ("选择后续触发器 CSR 访问的触发器索引，编号从 0 连续分配。", "Select the trigger accessed by subsequent trigger CSRs; indices are contiguous from zero."),
    "type": ("选择或报告当前触发器类型。", "Select or report the current trigger type."),
    "dmode": ("控制是否仅允许 Debug Mode 修改所选触发器。", "Control whether only Debug Mode can modify the selected trigger."),
    "data": ("访问所选触发器的类型相关数据，含义由 TYPE 决定。", "Access type-specific data for the selected trigger, interpreted by TYPE."),
    "version": ("报告触发器模块所采用的 Sdtrig 语义版本。", "Report the trigger module's Sdtrig semantic version."),
    "info": ("位 n 表示支持 type=n；值为 1 表示所选触发器不存在。", "Bit n advertises type n; value one denotes a nonexistent selected trigger."),
    "mpte": ("保存进入 M 态陷阱前的 MTE，供 MRET 恢复。", "Save MTE on an M-mode trap for restoration by MRET."),
    "mte": ("控制 action=0 的触发器能否在 M 态匹配或触发。", "Control whether action=0 triggers match or fire in M-mode."),
    "hcontext": ("保存机器/Hypervisor 上下文编号，用于触发器上下文过滤。", "Hold the machine/hypervisor context identifier used for trigger filtering."),
    "maskmax": ("报告 NAPOT 地址范围匹配支持的最大掩码长度。", "Report the maximum mask length supported for NAPOT address matching."),
    "sizehi": ("RV64 的访问大小编码高 2 位，与 SIZELO 拼接解释。", "Upper two access-size bits on RV64, interpreted together with SIZELO."),
    "sizelo": ("访问大小编码低 2 位；完整含义由 SIZEHI:SIZELO 决定。", "Low two access-size bits; interpret the combined SIZEHI:SIZELO encoding."),
    "hit": ("记录触发器命中或触发；未实现时读零，可由软件置位或清除。", "Record a trigger match/fire; software may set or clear it, or it may read zero if absent."),
    "select": ("选择比较地址、数据，或外部触发源编号；具体含义依格式而定。", "Select address/data comparison or an external source, according to the format."),
    "timing": ("选择在命中指令退休前或退休后执行触发动作。", "Select whether the trigger action occurs before or after retirement."),
    "action": ("选择断点、进入调试、trace 动作或 TM 外部输出信号。", "Select breakpoint, debug entry, trace action or a TM external output signal."),
    "chain": ("要求下一触发器也满足条件，以组成触发器链。", "Require the next trigger's condition as part of a trigger chain."),
    "match": ("选择与 tdata2 比较的相等、范围或掩码条件。", "Select equality, range or mask comparison against tdata2."),
    "uncertain": ("报告命中信息是否不确定。", "Report whether the hit information is uncertain."),
    "uncertainen": ("控制是否允许触发器进行不确定性报告。", "Control whether the trigger may report uncertainty."),
    "hit0": ("HIT1:HIT0 组合状态的低位，报告是否及何时命中。", "Low bit of HIT1:HIT0, reporting whether and when the trigger hit."),
    "hit1": ("HIT1:HIT0 组合状态的高位；两位在寄存器中不连续。", "High bit of HIT1:HIT0; the two bits are noncontiguous in the register."),
    "size": ("选择匹配的访问或指令宽度；支持的大小取决于实现。", "Select the matched access/instruction width, subject to implementation support."),
    "count": ("指令计数触发器的递减计数值，按选定执行模式计数。", "Decrementing instruction-trigger count, qualified by the selected execution modes."),
    "pending": ("记录计数触发器已达到触发条件，等待合适时机执行动作。", "Record a count trigger awaiting an appropriate point to perform its action."),
    "nmi": ("允许不可屏蔽中断参与中断触发器匹配。", "Include non-maskable interrupts in interrupt-trigger matching."),
    "intctl": ("允许所连接中断控制器发出的触发信号触发本触发器。", "Allow a trigger signal from the attached interrupt controller to fire this trigger."),
    "mhvalue": ("保存 MHSELECT 所选机器/Hypervisor 上下文的比较值。", "Hold the machine/hypervisor context comparison selected by MHSELECT."),
    "mhselect": ("选择忽略、上下文编号或 VMID 比较；编码 1/2/5/6 还使用本字段 bit 2。", "Select no filtering, context or VMID comparison; encodings 1/2/5/6 also use bit 2."),
    "sbytemask": ("每位为 1 时忽略 SCONTEXT 比较中对应字节；仅 SSELECT=1 时生效。", "Each set bit ignores its SCONTEXT byte; effective only when SSELECT=1."),
    "svalue": ("保存 SSELECT 选择的 SCONTEXT 或 ASID 比较值。", "Hold the SCONTEXT or ASID comparison value selected by SSELECT."),
    "sselect": ("选择忽略、SCONTEXT 或当前 satp/vsatp 的 ASID 比较。", "Select no filtering, SCONTEXT or ASID comparison from satp/vsatp."),
}
for mode in ("m", "s", "u", "vs", "vu"):
    SEMANTICS[mode] = (f"允许本触发器匹配涉及 {mode.upper()} 模式的事件。", f"Enable this trigger for events involving {mode.upper()} mode.")
for mode in ("m", "s", "u", "vs", "vu"):
    SEMANTICS["ebreak" + mode] = (f"选择 {mode.upper()} 模式的 EBREAK 产生断点异常还是进入 Debug Mode。", f"Choose breakpoint exception or Debug Mode for EBREAK in {mode.upper()} mode.")
for event, zh in (("execute", "指令执行"), ("load", "加载"), ("store", "存储")):
    SEMANTICS[event] = (f"允许{zh}事件参与本触发器匹配。", f"Enable {event} events for trigger matching.")

ZH = {
    "none": "没有调试支持或触发器", "1.0": "符合 Debug 1.0", "custom": "自定义实现", "critical error": "critical error 状态",
    "NO_LP_EXPECTED": "无需着陆垫", "LP_EXPECTED": "预期着陆垫", "exception": "产生断点异常", "debug mode": "进入 Debug Mode",
    "disabled": "禁止", "enabled": "允许", "interrupts disabled": "禁止中断", "interrupts enabled": "允许中断",
    "normal": "正常计数", "freeze": "冻结计数", "ebreak": "EBREAK 指令", "trigger": "触发器",
    "haltreq": "调试器 halt 请求", "step": "单步完成", "resethaltreq": "复位后的 halt 请求", "group": "halt group", "other": "其他原因，结合 EXTCAUSE",
    "legacy": "旧版触发器类型", "mcontrol": "旧版地址/数据匹配", "mcontrol6": "地址/数据匹配 type 6",
    "icount": "指令计数触发器", "itrigger": "中断触发器", "etrigger": "异常触发器", "tmexttrigger": "TM 外部触发源",
    "both": "M 态和 Debug Mode 都可修改", "dmode": "仅 Debug Mode 可修改", "address": "比较虚拟地址", "data": "比较数据或指令值",
    "before": "指令退休前", "after": "指令退休后", "any": "任意大小（受 SELECT 与 XLEN 条件约束）",
    "breakpoint": "产生断点异常", "trace on": "开启 trace（行为由 Trace 规范定义）", "trace off": "关闭 trace（行为由 Trace 规范定义）",
    "trace notify": "通知 trace（行为由 Trace 规范定义）", "external0": "向 TM 外部触发输出 0 发信号", "external1": "向 TM 外部触发输出 1 发信号",
    "equal": "无符号相等", "napot": "NAPOT 范围匹配", "ge": "无符号大于等于", "lt": "无符号小于",
    "mask low": "低半掩码比较", "mask high": "高半掩码比较", "not equal": "不相等", "not napot": "NAPOT 范围不匹配",
    "not mask low": "低半掩码不匹配", "not mask high": "高半掩码不匹配", "certain": "命中信息确定", "uncertain": "命中信息不确定",
    "false": "未命中", "immediately after": "紧接命中指令退休后", "ignore": "不进行对应上下文比较",
    "mcontext": "比较 MCONTEXT/HCONTEXT", "scontext": "比较 SCONTEXT", "asid": "比较当前 satp/vsatp 的 ASID",
}


def arithmetic(text, width):
    match = re.fullmatch(r"(XLEN|DXLEN|\d+)(?:-(\d+))?", text)
    if not match:
        raise ValueError("Unsupported bit expression: " + text)
    return (width if match[1] in ("XLEN", "DXLEN") else int(match[1])) - int(match[2] or 0)


def xml_sources():
    rows = {}
    for filename in FILES:
        text = (UPSTREAM / "xml" / filename).read_text(encoding="utf-8")
        assert "<!DOCTYPE" not in text and "<!ENTITY" not in text
        tree = ET.fromstring(text)
        for reg in tree.findall("register"):
            short = reg.get("short")
            block = re.search(r'<register\b[^>]*short="' + re.escape(short) + r'"[^>]*>.*?</register>', text, re.S)
            first = text.count("\n", 0, block.start()) + 1
            last = text.count("\n", 0, block.end()) + 1
            rows[short] = (reg, f"{REPOSITORY}/blob/{REVISION}/xml/{filename}#L{first}-L{last}")
    return rows


def entries(field):
    values = {}
    for v in field.findall("value"):
        name = v.get("name")
        keys = [int(v.get("v"), 0)] if v.get("v") is not None else list(range(int(v.get("range").split(":")[0]), int(v.get("range").split(":")[1]) + 1))
        zh = ZH.get(name)
        if name.endswith("bit") and name[:-3].isdigit():
            zh = name[:-3] + " 位访问或指令"
        if name in ("0", "1"):
            zh = "2023-02-02 的早期 Sdtrig 语义" if name == "0" else "正式批准的 Sdtrig 1.0 语义"
        assert zh is not None, name
        for key in keys:
            en = ("Early Sdtrig semantics at commit 5a5c078 (2023-02-02)" if name == "0" else "Ratified Sdtrig 1.0 semantics") if name in ("0", "1") else name
            values[key] = (zh, en)
    return values


def enums(reg, name, node, width):
    values = entries(node)
    rule = ""
    if reg == "mcontrol" and name in ("sizelo", "sizehi"):
        original = entries(next(f for f in xml_sources()["mcontrol"][0].findall("field") if f.get("name") == "sizelo"))
        rule = "完整大小编码=(SIZEHI<<2)|SIZELO：" + "；".join(f"{k}={v[0]}" for k, v in original.items()) + "；其余保留。RV32 没有 SIZEHI，视为 0。"
        values = {i: (f"大小组合编码的{'低' if name == 'sizelo' else '高'} 2 位为 {i}", f"{'Low' if name == 'sizelo' else 'High'} two size bits equal {i}") for i in range(4)}
    elif reg == "mcontrol6" and name in ("hit0", "hit1"):
        rule = "HIT=(HIT1<<1)|HIT0：0 未命中；1 退休前；2 退休后；3 紧接退休后。两位不连续，不可作为连续 2 位字段解码。"
        values = {i: (f"HIT 组合状态的{'低' if name == 'hit0' else '高'}位为 {i}", f"{'Low' if name == 'hit0' else 'High'} HIT bit equals {i}") for i in range(2)}
    elif name == "type" and reg in FORMATS:
        values = {FORMATS[reg]: (f"固定类型 {FORMATS[reg]}：{reg}", f"Fixed type {FORMATS[reg]}: {reg}")}
    elif name == "dmode" and not values:
        values = {0: (ZH["both"], "M-mode and Debug Mode may modify"), 1: (ZH["dmode"], "Only Debug Mode may modify")}
    elif name == "prv":
        values = {0: ("U 模式（V=1 时为 VU）", "U-mode, or VU when V=1"), 1: ("S/HS 模式（V=1 时为 VS）", "S/HS-mode, or VS when V=1"), 2: ("保留", "Reserved"), 3: ("M 模式", "M-mode")}
    elif name == "mhselect":
        values = {0: ("忽略 MHVALUE", "Ignore MHVALUE"), 4: ("比较上下文与 MHVALUE", "Compare context to MHVALUE"),
                  1: ("比较上下文与 {MHVALUE,0}（需要 H）", "Compare context to {MHVALUE,0}; requires H"),
                  5: ("比较上下文与 {MHVALUE,1}（需要 H）", "Compare context to {MHVALUE,1}; requires H"),
                  2: ("比较 VMID 与 {MHVALUE,0}（需要 H）", "Compare VMID to {MHVALUE,0}; requires H"),
                  6: ("比较 VMID 与 {MHVALUE,1}（需要 H）", "Compare VMID to {MHVALUE,1}; requires H")}
    elif name == "sselect" and not values:
        values = {0: (ZH["ignore"], "Ignore SVALUE"), 1: (ZH["scontext"], "Compare SCONTEXT"), 2: (ZH["asid"], "Compare ASID")}
    if width == 1 and not values:
        values = {0: ("未置位/禁止该功能", "Clear/disable this function"), 1: ("置位/允许该功能", "Set/enable this function")}
        if name in ("hit", "nmip", "pending", "mpte"):
            values = {0: ("未置位（状态为 0）", "Clear; state is zero"), 1: ("已置位（状态为 1）", "Set; state is one")}
        if reg == "dcsr" and name == "step":
            values = {0: ("正常恢复执行", "Resume normal execution"), 1: ("单步后重新进入调试", "Single-step and re-enter Debug Mode")}
        if reg == "dcsr" and name == "v":
            values = {0: ("恢复到非虚拟模式", "Resume without virtualization"), 1: ("结合 PRV 恢复到 VS/VU", "Resume to VS/VU as selected by PRV")}
    if name == "action":
        values[1] = ("进入 Debug Mode（要求 DMODE=1 且实现 Sdext）", "Enter Debug Mode; requires DMODE=1 and Sdext")
        values[5] = ("为 Trace 规范保留", "Reserved for the trace specification")
        rule = "ACTION=1 仅在 DMODE=1 且实现 Sdext 时合法；2/3/4 的行为由独立 Trace 规范定义。触发器链仅执行最后一个成员的动作。"
    if values and name not in ("type",) and width <= 8:
        values = {i: values.get(i, ("保留/本版未定义", "Reserved/not defined in this version")) for i in range(1 << width)}
    if name == "type" and reg == "tdata1":
        values = {i: values.get(i, ("保留", "Reserved")) for i in range(16)}
    return values, rule


def parse_fields(short, node, source, view="", only_width=None):
    fields, regions = [], []
    for raw in node.findall("field"):
        name = raw.get("name")
        widths = [only_width] if only_width else [32, 64]
        if short == "mcontrol" and name == "sizehi":
            widths = [w for w in widths if w == 64]
        layouts = []
        for w in widths:
            parts = raw.get("bits").split(":")
            high, low = arithmetic(parts[0], w), arithmetic(parts[-1], w)
            if high >= low and low < w:
                assert 0 <= low <= high < w, (short, name, w, high, low)
                layouts.append(dict(xlen=w, msb=high, lsb=low))
        if not layouts:
            continue
        access = {"R": "RO", "R/W": "RW"}.get(raw.get("access"), raw.get("access"))
        if name == "0":
            regions.append(dict(label="0", access="RO0", summary="显式只读零位。", summaryEn="Explicit read-only-zero bits.", layouts=layouts, sourceUrl=source, view=view))
            continue
        zh, en = SEMANTICS[name]
        if short == "scontext" and name == "data":
            zh, en = "保存供触发器比较的监管态上下文编号，高位可按实现固定为零。", "Hold the supervisor context identifier for trigger comparison; high bits may read zero."
        if short == "tmexttrigger" and name == "select":
            zh, en = "用独立位选择最多 16 个 TM 外部触发输入，可同时选择多个输入。", "Select any combination of up to 16 TM external trigger inputs using a bitmap."
        elif short in ("mcontrol", "mcontrol6") and name == "select":
            zh, en = "选择比较虚拟地址，或加载/存储数据及指令编码。", "Select comparison of virtual addresses or load/store data and instruction bits."
        first_width = layouts[0]["msb"] - layouts[0]["lsb"] + 1
        vals, rule = enums(short, name, raw, first_width)
        if short == "mcontrol" and name == "hit":
            rule = "可由软件置位或清除；未实现时读零。触发器触发时必须置位，匹配时也可置位。"
        if short == "tmexttrigger" and name == "type":
            rule = "规范正文及 TDATA1 类型表规定 TYPE=7，但此 XML 字段 reset=4；按正文显示格式 7，并保留该来源差异，不将 4 当作新类型定义。"
        if short == "tdata1" and name == "data":
            rule = "TYPE=0 时 DATA 硬连为零；TYPE=15 时 DATA 被忽略；其他类型按所选格式解释。"
        if short in ("tdata2", "tdata3") and name == "data":
            rule = "XLEN<DXLEN 时写入按符号扩展；触发器禁用时可写入该触发器任一支持类型接受的值。"
        field = dict(name=(view + "." if view else "") + name.upper(), summary=zh, summaryEn=en,
            access=access, layouts=layouts, condition="", aliases=[name], sourceUrl=source,
            values={str(k): v[0] for k, v in vals.items()}, valuesEn={str(k): v[1] for k, v in vals.items()},
            valuesByXlen={}, valuesEnByXlen={}, typeRule=rule, view=view)
        if name in ("cetrig", "extcause"):
            field["condition"] = "Smdbltrp" if name == "cetrig" else "DCSR.CAUSE=7"
        elif name == "pelp":
            field["condition"] = "Zicfilp"
        elif name in ("vs", "vu", "ebreakvs", "ebreakvu", "v"):
            field["condition"] = "H"
        fields.append(field)
    return fields, regions


def enrich(registers):
    registers = deepcopy([r for r in registers if r["namespace"] != "debug"])
    existing = {r["name"]: r for r in registers if r["namespace"] == "manual"}
    xml = xml_sources()
    supplemental = []
    added = []
    for short, (node, source) in xml.items():
        if short in FORMATS or short.startswith("textra"):
            continue
        address = int(node.get("address"), 16)
        reg = existing.get(short)
        core = short in ("dcsr", "dpc", "dscratch0", "dscratch1")
        privilege = "D" if core else "H" if short == "hcontext" else "S" if short in ("scontext", "mscontext") else "M"
        if reg is None:
            reg = dict(id=f"riscv/debug/csr/{short}", name=short, namespace="debug", locator=dict(type="riscv-csr", address=address),
                title=node.get("name"), summary="", summaryEn="", privilege=privilege, access="RW", xlens=[32, 64],
                condition="", notes="", sourceUrl=source, sourceRevision=REVISION, license="CC-BY-4.0", coverage="fields", extensions=[], fields=[], regions=[])
            added.append(reg)
        else:
            assert reg["locator"]["address"] == address
            supplemental.append(short)
        reg["sources"] = [s for s in reg.get("sources", []) if s["namespace"] != "debug"] + [ATTRIBUTION.copy()]
        reg["coverage"] = "fields"
        reg["privilege"] = privilege
        reg["extensions"] = list(dict.fromkeys(reg.get("extensions", []) + (["Sdext"] if core else ["Sdtrig"])))
        reg["summary"] = {"dcsr": "控制调试进入、单步和恢复状态。", "dpc": "保存调试恢复执行的虚拟地址。", "dscratch0": "调试器临时保存寄存器 0。", "dscratch1": "调试器临时保存寄存器 1。",
            "tselect": "选择要访问的触发器。", "tdata1": "配置所选触发器的类型、权限及触发条件。", "tdata2": "保存所选触发器的比较数据或原因位掩码。", "tdata3": "保存所选触发器的附加上下文过滤条件。",
            "tinfo": "报告所选触发器支持的类型和 Sdtrig 版本。", "tcontrol": "控制 M 态断点触发器及陷阱前使能状态。", "hcontext": "从 HS 态访问 MCONTEXT 的上下文编号。", "scontext": "保存触发器匹配用的监管态上下文编号。", "mcontext": "保存触发器匹配用的机器/Hypervisor 上下文编号。", "mscontext": "提供旧版 SCONTEXT 兼容别名。"}[short]
        reg["summaryEn"] = {"dcsr": "Control debug entry, stepping and resume state.", "dpc": "Hold the virtual address at which execution resumes.",
            "dscratch0": "Debugger scratch storage 0.", "dscratch1": "Debugger scratch storage 1.",
            "tselect": "Select the trigger to access.", "tdata1": "Configure the selected trigger's type, permissions and conditions.",
            "tdata2": "Hold trigger comparison data or cause bitmaps.", "tdata3": "Hold additional trigger context filters.",
            "tinfo": "Report supported trigger types and the Sdtrig version.", "tcontrol": "Control M-mode breakpoint triggers and saved enable state.",
            "hcontext": "Access the MCONTEXT identifier from HS-mode.", "scontext": "Hold the supervisor context identifier for trigger filtering.",
            "mcontext": "Hold the machine/hypervisor context identifier for trigger filtering.", "mscontext": "Provide a legacy alias for SCONTEXT."}[short]
        reg["condition"] = "Sdext；仅 Debug Mode 可访问" if core else "Sdtrig；由 tselect 选择触发器，可选实现受规范限制"
        reg["notes"] = "Debug 1.0；DM/DMI、JTAG 及调试器虚拟寄存器不属于 hart CSR。" + (
            " 除 V、PRV、CAUSE 和 NMIP 等规范规定状态外，控制字段仅由外部调试器写入。" if core else
            " 触发器在 Debug Mode 中不触发；DMODE=1 时只有 Debug Mode 可修改配置。功能是否实现需通过规范探测。")
        if short == "tinfo":
            reg["access"] = "RO"
        reg["fields"], reg["regions"] = parse_fields(short, node, source)
        if short == "dcsr":
            reg["regions"].append(dict(label="Reserved", access="本版未规定", summary="本版 DCSR 位表只定义 bits 31:0；不推断高位为 WPRI。", summaryEn="This DCSR table defines only bits 31:0; no WPRI behavior is inferred above them.", layouts=[dict(xlen=64, msb=63, lsb=32)], sourceUrl=source))
        if short in ("hcontext", "mscontext"):
            parent = "mcontext" if short == "hcontext" else "scontext"
            reg["fields"], reg["regions"] = parse_fields(parent, xml[parent][0], xml[parent][1])
            reg["notes"] += " " + ("HCONTEXT 别名到 MCONTEXT；需要 H 和已实现的 MCONTEXT，HS 访问受 mstateen0.CONTEXT 控制。" if short == "hcontext" else "兼容 0.13 的旧编码；尽管地址编码不遵循通常映射，规范允许 S/HS、M 和 Debug Mode 访问。新实现优先使用 SCONTEXT。")
            if short == "hcontext":
                reg["condition"] += " H；要求实现 MCONTEXT"
        if short in ("scontext", "mcontext"):
            reg["notes"] += " SCONTEXT 访问受 mstateen0/hstateen0 的 CONTEXT 位控制；比较能力与实际实现宽度相关。"
    combined = existing | {r["name"]: r for r in added}
    for short, type_number in FORMATS.items():
        fields, regions = parse_fields(short, xml[short][0], xml[short][1], short)
        for f in fields:
            f["condition"] = f"Sdtrig tdata1.TYPE={type_number}" + ("；类型 2 已弃用，优先使用 mcontrol6" if short == "mcontrol" else "") + (" H" if f["name"].endswith((".VS", ".VU")) else "")
        combined["tdata1"]["fields"] += fields
        combined["tdata1"]["regions"] += regions
    for width in (32, 64):
        short = f"textra{width}"
        fields, regions = parse_fields(short, xml[short][0], xml[short][1], "textra", width)
        for f in fields:
            f["condition"] = "Sdtrig tdata1.TYPE=2/3/4/5/6"
            previous = next((old for old in combined["tdata3"]["fields"] if old["name"] == f["name"]), None)
            if previous:
                previous["layouts"] += f["layouts"]
            else:
                combined["tdata3"]["fields"].append(f)
        combined["tdata3"]["regions"] += regions
    combined["tdata1"]["notes"] += " ACTION=2/3/4 分别为 trace on/off/notify；具体 trace 行为由独立 Trace 规范定义，不代表实现了某种 trace 编码器 CSR。"
    combined["tdata2"]["fields"][0].update(summary="TYPE=2/6 时用于地址或数据比较；TYPE=4/5 时每位选择中断或异常原因。", summaryEn="Address/data comparison for TYPE=2/6; interrupt/exception cause bitmap for TYPE=4/5.")
    for name in ("mstateen0", "mstateen0h", "hstateen0", "hstateen0h"):
        reg = existing[name]
        reg["sources"] = [s for s in reg.get("sources", []) if s["namespace"] != "debug"] + [ATTRIBUTION.copy()]
        f = next(f for f in reg["fields"] if f["name"] == "CONTEXT")
        f.update(summary="控制低特权态访问 SCONTEXT/HCONTEXT；对应触发器上下文寄存器仍须实际实现。", summaryEn="Control lower-privilege SCONTEXT/HCONTEXT access; the trigger context registers must still be implemented.", sourceUrl=xml["scontext"][1], condition="Smstateen Ssstateen Sdtrig")
        supplemental.append(name)
    return registers + added, dict(new=sorted(r["name"] for r in added), supplemented=sorted(supplemental))


def verify_checkout():
    actual = subprocess.check_output(["git", "-C", str(UPSTREAM), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISION:
        raise ValueError("Debug checkout must be pinned to ratified 1.0")
    if subprocess.check_output(["git", "-C", str(UPSTREAM), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
        raise ValueError("Debug checkout has local modifications")


def update_metadata():
    path = ROOT / "tools/data-sources.lock.json"
    obj = json.loads(path.read_text(encoding="utf-8"))
    obj["sources"] = [s for s in obj["sources"] if s["namespace"] != "debug"] + [dict(namespace="debug", directory="upstream/riscv-debug-spec", repository=REPOSITORY, revision=REVISION, tag=TAG, license="CC-BY-4.0")]
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    notice = ASSETS / "THIRD_PARTY_NOTICES.txt"
    text = notice.read_text(encoding="utf-8")
    if f"Debug source: {REPOSITORY}" not in text:
        text += (f"\nRISC-V Debug Specification; RISC-V International and contributors.\nDebug source: {REPOSITORY}\nTag: {TAG}\nCommit: {REVISION}\nLicense chosen: CC-BY-4.0 (XML files also offer BSD-2-Clause).\nChanges: extract hart CSR addresses, trigger format views and field/enum definitions; translate concise Chinese descriptions and retain English summaries; exclude DM/DMI/JTAG and virtual registers.\n")
        notice.write_text(text, encoding="utf-8", newline="\n")


def run():
    # All source supplements are regenerated together to avoid losing another spec's overlays.
    from import_isa_manual import run as regenerate
    regenerate()


if __name__ == "__main__":
    run()
