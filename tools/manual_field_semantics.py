"""Source-specific field descriptions, translated from the pinned ISA manual.

Keep register context when interpreting a field name: MODE, BASE, and CY do
not have one universal meaning. Conditions remain part of the description;
an implementation-dependent field must not be reduced to unconditional RW.
"""
import re


STATE_VALUES = {"0": "Off：关闭扩展状态", "1": "Initial：初始状态", "2": "Clean：状态未修改", "3": "Dirty：状态已修改"}
XLEN_VALUES = {"1": "RV32", "2": "RV64", "3": "保留编码"}
STATUS = {
    "MIE": ("M 模式全局中断允许位。在 M 模式下，1 允许、0 禁止可屏蔽中断；还需满足对应中断源的使能条件。", "RW"),
    "SIE": ("S 模式全局中断允许位。在 S 模式下控制可屏蔽中断；未实现 S 模式时可为只读零。", "RW / RO=0（依实现）"),
    "MPIE": ("记录进入 M 模式陷阱前的全局中断允许状态；MRET 用它恢复 MIE，随后将该位置 1。", "RW"),
    "SPIE": ("记录进入 S 模式陷阱前的全局中断允许状态；SRET 用它恢复 SIE，随后将该位置 1。", "RW / RO=0（依实现）"),
    "MPP": ("记录进入 M 模式陷阱前的特权级；MRET 据此恢复特权级，仅能保存实现支持的模式。", "WARL"),
    "SPP": ("记录进入 S 模式陷阱前的特权级：0 为 U，1 为 S；SRET 据此返回并将该位置 0。", "WARL"),
    "MPRV": ("修改 M 模式显式数据访存的有效特权级；置 1 时按 MPP 指定的模式进行权限检查，不影响取指。", "WARL"),
    "SUM": ("控制 S 模式是否可访问 U=1 的用户页；不允许 S 模式从用户页取指，且不改变 U 模式的权限。", "RW / RO=0（依实现）"),
    "MXR": ("允许把可执行页作为可读页，用于加载权限检查；不影响写权限，也不绕过物理内存保护。", "RW / RO=0（依实现）"),
    "TVM": ("置 1 时，S 模式访问 satp 或执行 SFENCE.VMA 等受控地址转换操作会陷入，便于虚拟化内存管理。", "WARL"),
    "TW": ("控制低特权级 WFI 的陷入；受控的 WFI 若未在实现规定的有限时间内完成，将触发异常。", "WARL"),
    "TSR": ("置 1 时使 S 模式执行 SRET 触发非法指令异常。", "WARL"),
    "FS": ("跟踪浮点寄存器与浮点控制状态，供软件决定是否保存/恢复上下文；缺少相关状态时可为只读零。", "WARL"),
    "VS": ("跟踪向量寄存器与向量 CSR 的上下文状态；缺少向量状态时可为只读零。", "WARL"),
    "XS": ("汇总其他扩展所维护的上下文状态，反映这些扩展中最脏的状态；软件不能直接写入。", "RO"),
    "SD": ("Dirty 状态汇总；当 FS、VS 或 XS 中存在 Dirty 状态时置 1，便于快速判断是否需要保存扩展上下文。", "RO"),
    "UXL": ("选择 U 模式有效 XLEN；编码与 misa.MXL 相同，取值受实现和更高特权级的宽度限制。", "WARL"),
    "SXL": ("选择 S 模式有效 XLEN；编码与 misa.MXL 相同，取值不得超过 M 模式有效 XLEN。", "WARL"),
    "UBE": ("控制 U 模式显式数据访问的字节序；不改变指令取指的字节序。", "WARL"),
    "SBE": ("控制 S 模式显式数据访问以及适用页表访问的字节序；仅支持一种字节序的实现可将其固定。", "WARL"),
    "MBE": ("控制 M 模式显式数据访问的字节序；不改变指令取指的字节序。", "WARL"),
    "MPV": ("保存进入 M 模式陷阱前的虚拟化状态，并参与 MRET 与 MPRV 的有效访存模式选择。", "WARL"),
    "GVA": ("指示当前陷阱提供的地址信息是否为 guest virtual address，帮助区分访客虚拟地址与其他地址。", "RW（由陷阱更新）"),
    "MDT": ("M 模式双重陷阱控制。用于标识关键陷阱处理阶段；再次发生意外陷阱时按 Smdbltrp/Smrnmi 规则处理。", "WARL"),
    "SDT": ("S 模式双重陷阱控制；再次陷阱发生于尚未准备好可重入的处理阶段时，按 Ssdbltrp 规则处理。", "WARL"),
    "MPELP": ("保存进入 M 模式陷阱前的预期 landing-pad 状态；MRET 按 Zicfilp 规则恢复。", "WARL"),
    "SPELP": ("保存进入 S 模式陷阱前的预期 landing-pad 状态；SRET 按 Zicfilp 规则恢复。", "WARL"),
}

HSTATUS = {
    "VSBE": ("控制 VS 模式显式数据访问的字节序；改变时需同步相关访客状态与页表解释。", "WARL"),
    "SPV": ("保存进入 HS 模式陷阱前的虚拟化模式 V；SRET 按此恢复虚拟化状态。", "RW"),
    "SPVP": ("保存进入 HS 模式陷阱前的访客特权级，并供 HLV/HLVX/HSV 等访客访存指令选择 VS 或 VU 权限。", "RW"),
    "HU": ("控制 U 模式能否执行 HLV/HLVX/HSV 访客内存访问指令；不等同于启用其他特权操作。", "WARL"),
    "VGEIN": ("选择送往 VS 级的 guest external interrupt 源；0 不选择，1 至 GEILEN 分别选择对应编号，其他值不受支持。", "WARL"),
    "VTVM": ("置 1 时，使 VS 模式访问 satp 或执行相关地址转换操作触发虚拟指令异常。", "WARL"),
    "VTW": ("控制 VS 模式 WFI 的虚拟指令陷入，与 mstatus.TW 共同决定最终行为。", "WARL"),
    "VTSR": ("置 1 时使 VS 模式 SRET 触发虚拟指令异常。", "WARL"),
    "VSXL": ("选择 VS 模式有效 XLEN；采用 misa.MXL 编码，值受 HSXLEN 和实现能力限制。", "WARL"),
    "HUPMM": ("选择 HU 访客访存适用的指针屏蔽模式；合法值和可用性由相应指针屏蔽扩展规定。", "WARL"),
}

ENVCFG = {
    "FIOM": "Fence of I/O implies Memory：使受影响低特权级的 I/O 排序约束同时涵盖主存访问。",
    "LPE": "控制受影响低特权模式的 Zicfilp landing-pad 检查。",
    "SSE": "控制受影响低特权模式的 Zicfiss shadow-stack 功能及相关指令行为。",
    "CBIE": "控制低特权模式的缓存块无效化操作，可禁止、替换为 flush，或允许 invalidate。",
    "CBCFE": "控制低特权模式能否执行缓存块 clean/flush 操作；受更高层控制的约束。",
    "CBZE": "控制低特权模式能否执行缓存块清零操作；受更高层控制的约束。",
    "PMM": "选择受影响特权模式的指针屏蔽模式；地址解释和合法值由相应指针屏蔽扩展规定。",
    "DTE": "控制下一层特权模式的双重陷阱检测功能。",
    "CDE": "控制 Zicntr/Zihpm 计数器向 S 模式委托，启用 Smcdeleg/Ssccfg 规定的功能。",
    "ADUE": "控制硬件是否更新页表项的 A/D 位，并影响 Svadu/Svade 的行为。",
    "PBMTE": "控制是否启用 Svpbmt 定义的页表内存类型字段。",
    "STCE": "控制低特权级 Sstc 定时比较 CSR 的访问与使用。",
}

STATEEN = {
    "C": "自定义扩展状态", "FCSR": "使用整数寄存器的 Zfinx 类浮点指令及 fcsr 状态",
    "JVT": "Zcmt 的 jvt CSR", "CTR": "控制流转移记录 CTR 状态", "SRMCFG": "Ssqosid 的 srmcfg CSR",
    "P1P13": "特权规范 1.13 引入的 hedelegh CSR", "CONTEXT": "Sdtrig 的 scontext/hcontext 上下文 CSR",
    "IMSIC": "IMSIC 中断控制器状态与相应 top-external-interrupt CSR", "AIA": "未由其他控制位单独管理的 Ssaia 状态",
    "CSRIND": "Sscsrind 的间接 CSR 选择与访问", "ENVCFG": "更低特权级的环境配置 CSR", "SE0": "更低特权级的 state-enable 0 CSR",
}


def entry(summary, access=None, values=None, chapter="machine.adoc", rule=""):
    return {"summary": summary, "access": access, "values": values or {}, "chapter": chapter, "rule": rule}


def describe(reg, field):
    csr, name = reg["name"], field["name"]
    if csr.startswith("mhpmevent"):
        if name == "EVENT":
            return entry("保存逻辑事件选择编码的高 24 位。" if csr.endswith("h") else "选择由平台定义的性能监视事件，完整事件编码为 0 时不计数。", "WARL", {"0": "完整事件编码为零时不计数"}, rule="此布局包含 Sscofpmf 定义的高位控制字段；RV32 须合并 mhpmevent 与 mhpmeventh 的事件编码部分，任一半部为零不代表完整编码为零。未实现扩展时的事件编码仍由平台定义。")
        if name == "OF":
            return entry("记录计数器溢出并禁止再次产生该计数器的溢出中断。", "RW（硬件溢出置位）", {"0": "允许下次溢出产生中断", "1": "已记录溢出，禁止再次请求中断"}, chapter="sscofpmf.adoc")
        if name.endswith("INH"):
            mode = {"MINH": "M", "SINH": "S/HS", "UINH": "U", "VSINH": "VS", "VUINH": "VU"}[name]
            return entry(f"禁止计数器在 {mode} 模式下统计事件。", "RW / RO=0（未实现该模式时）", {"0": "允许本模式计数", "1": "禁止本模式计数"}, chapter="sscofpmf.adoc")
    if csr == "scountovf":
        return entry(f"读取性能计数器 {name.removeprefix('OF')} 的溢出标志，受 counteren 可见性限制。", "RO", {"0": "未溢出或当前模式不可见", "1": "已溢出"}, chapter="sscofpmf.adoc")
    if name in ("GEIP", "GEIE"):
        return entry("按 guest external interrupt 编号报告待处理中断。" if name == "GEIP" else "选择参与 HS 级 guest external interrupt 汇总的中断源。", "RO" if name == "GEIP" else "WARL", chapter="hypervisor.adoc", rule="编号从 1 开始；实现数量为 GEILEN，未实现的位为只读零，bit 0 固定为零。每位 0 表示未挂起/未使能，1 表示挂起/使能；这是独立位掩码而非单值枚举。")
    if csr in ("htimedelta", "htimedeltah"):
        return entry("设置虚拟机 64 位时间偏移的高 32 位。" if csr.endswith("h") else "设置虚拟机 time 读取值相对于实际时间的偏移。", "RW", chapter="hypervisor.adoc", rule="访客读取值为实际 time 加偏移，按 64 位模加法计算；RV32 的 htimedeltah 访问高 32 位。")
    if csr in ("mstatus", "mstatush", "sstatus", "vsstatus") and name in STATUS:
        summary, access = STATUS[name]
        if csr == "vsstatus":
            summary = summary.replace("S 模式", "VS 模式").replace("U 模式", "VU 模式").replace("SRET", "虚拟化 SRET")
        values = STATE_VALUES if name in ("FS", "VS", "XS") else XLEN_VALUES if name in ("SXL", "UXL") else {"0": "U 模式", "1": "S 模式", "3": "M 模式"} if name == "MPP" else {"0": "U 模式", "1": "S 模式"} if name == "SPP" else {"0": "小端", "1": "大端"} if name in ("UBE", "SBE", "MBE") else {}
        chapter = "hypervisor.adoc" if csr == "vsstatus" or name in ("GVA", "MPV") else "supervisor.adoc" if name == "SDT" else "priv-cfi.adoc" if name in ("MPELP", "SPELP") else "machine.adoc"
        return entry(summary, access, values, chapter=chapter)
    if csr == "hstatus" and name in HSTATUS:
        return entry(*HSTATUS[name], values=XLEN_VALUES if name == "VSXL" else None, chapter="zpm.adoc" if name == "HUPMM" else "hypervisor.adoc")
    if "envcfg" in csr and name in ENVCFG:
        chapter = "supervisor.adoc" if csr.startswith("s") else "hypervisor.adoc" if csr.startswith("h") else "machine.adoc"
        return entry(ENVCFG[name] + "未实现对应扩展时的固定值及上层约束见规范。", "RV32：RO=0；RV64：WARL" if name == "PMM" else "WARL（受扩展与上层控制约束）", chapter="zpm.adoc" if name == "PMM" else chapter)
    if "stateen" in csr and (name in STATEEN or re.fullmatch(r"SE[123]", name)):
        meaning = STATEEN.get(name, f"第 {name[-1]} 组更低特权级 state-enable CSR")
        return entry("控制低特权模式对" + meaning + "的访问。", "WARL（可为只读零）", chapter="smctr.adoc" if name == "CTR" else "smstateen.adoc", rule="0 时相关访问可触发非法或虚拟指令异常；置 1 仍受更高层 state-enable 限制。")
    pmp = re.fullmatch(r"PMP(\d+)([LAXWR])", name)
    if csr.startswith("pmpcfg") and pmp:
        index, kind = pmp.groups()
        meanings = {"L": "锁定 PMP 配置及关联地址，复位前通常不能修改；锁定还影响 M 模式的权限检查，Smepmp 可细化其语义。",
                    "A": "选择 PMP 地址匹配方式；实现粒度会限制可用的 NA4/NAPOT 编码。",
                    "X": "控制匹配区域的指令执行权限。", "W": "控制匹配区域的写权限。", "R": "控制匹配区域的读权限。"}
        values = {"0": "OFF：不匹配", "1": "TOR：上界匹配", "2": "NA4：自然对齐的 4 字节区域", "3": "NAPOT：自然对齐的二次幂区域"} if kind == "A" else {}
        rule = "基础 PMP 中 R/W/X 共同组成 WARL 字段，R=0 且 W=1 的组合为保留编码；Smepmp.MML 启用时另有编码解释。L 锁定位及 TOR 的地址锁定联动需一并考虑。"
        return entry(f"PMP 条目 {index}：" + meanings[kind], "WARL（受锁定位和组合约束）", values, rule=rule)
    if csr.startswith("pmpaddr") and name == "ADDRESS":
        return entry("PMP 地址编码，保存物理地址移除低两位后的值；其匹配范围由关联 A 字段决定，NAPOT 还使用低位编码区域大小。", "WARL（受粒度和锁定约束）")
    if csr in ("mcounteren", "scounteren", "hcounteren", "mcountinhibit", "scountinhibit"):
        target = {"CY": "周期计数器", "TM": "时间计数器", "IR": "退休指令计数器"}.get(name)
        if name.startswith("HPM"):
            target = f"性能监视计数器 {name[3:]}"
        if target:
            summary = f"控制{target}是否继续计数。" if csr.endswith("inhibit") else f"控制较低特权级是否可读取{target}。"
            return entry(summary, "WARL（未实现或未委托时可只读零）", chapter="smcdeleg.adoc" if csr == "scountinhibit" else "hypervisor.adoc" if csr.startswith("h") else "supervisor.adoc" if csr.startswith("s") else "machine.adoc")
    if name == csr.upper() and re.fullmatch(r"(?:m?(?:cycle|instret)|time|m?hpmcounter\d+|mhpmevent\d+)(h?)", csr):
        if csr.startswith("mhpmevent"):
            summary = "选择关联性能监视计数器所统计的事件；事件 0 表示不计数，其他事件编码由平台定义。Sscofpmf 等扩展还可定义高位控制字段。"
        elif "hpmcounter" in csr:
            summary = "性能事件计数值，由相应 mhpmevent 选择事件；计数精度与可实现值受硬件能力约束。"
        elif "instret" in csr:
            summary = "已退休指令数；未退休或触发异常的指令不计入。"
        elif "cycle" in csr:
            summary = "处理器周期计数值；计数速率、共享方式及访问权限由规范与平台约定。"
        else:
            summary = "实时时间计数值，是平台时间源的只读视图，速率与周期计数器可以不同。"
        if csr.endswith("h"):
            summary = "读取或设置逻辑 64 位计数值的高 32 位。" + summary if csr.startswith("m") else "读取逻辑 64 位计数值的高 32 位。" + summary
        else:
            summary += "RV32 访问低 32 位，RV64 访问对应的完整可见值。"
        return entry(summary, chapter="machine.adoc" if csr.startswith("m") else "counters.adoc")
    if name in ("INT", "CODE") and "cause" in csr:
        return entry("陷阱类别：1 表示中断，0 表示同步异常。" if name == "INT" else "陷阱原因编号；需要结合 Interrupt 位区分中断原因和异常原因，具体编号以对应 cause 表为准。",
                     "RW（由陷阱入口更新）" if name == "INT" else None, {"0": "同步异常", "1": "中断"} if name == "INT" else None,
                     chapter="rnmi.adoc" if csr.startswith("mn") else "hypervisor.adoc" if csr.startswith("vs") else "supervisor.adoc" if csr.startswith("s") else "machine.adoc",
                     rule="CODE 枚举须结合 INT 解释；异常编号 ≥64 为保留，中断编号 ≥16 由平台定义。H 特有编号仅在实现 H 时适用；双重陷阱、软件检查、硬件错误和溢出中断还要求对应扩展。实际可到达的原因受陷阱委托和接收模式限制。VS 中断号按 2→1、6→5、10→9 转换。" if name == "CODE" and csr != "mncause" else "RNMI 中断原因由平台定义，0 表示未提供原因；INT=0 时保存引发双重陷阱的原异常编号，异常编码见 machine.adoc/hypervisor.adoc。" if name == "CODE" else "")
    if name == "SCRATCH":
        return entry("供陷阱处理程序与软件保存临时值的通用暂存区；硬件不规定保存内容，常用于交换陷阱入口所需的上下文指针。", "RW", chapter="rnmi.adoc" if csr.startswith("mn") else "hypervisor.adoc" if csr.startswith("vs") else "supervisor.adoc" if csr.startswith("s") else "machine.adoc")
    if csr.endswith("epc"):
        return entry("保存陷阱发生时的返回指令地址；对应的特权返回指令从这里恢复执行。可实现地址及低位对齐规则受架构和 IALIGN 约束。", "WARL", chapter="rnmi.adoc" if csr.startswith("mn") else "hypervisor.adoc" if csr.startswith("vs") else "supervisor.adoc" if csr.startswith("s") else "machine.adoc")
    if csr in ("satp", "vsatp", "hgatp"):
        summary = {"MODE": "选择地址转换模式；Bare 不执行本级分页，支持的分页模式由 XLEN 与实现决定。",
                   "ASID": "地址空间标识符，用于区分不同地址空间的地址转换缓存；实际可写位数由实现决定。",
                   "VMID": "虚拟机标识符，用于区分不同虚拟机的 G-stage 地址转换缓存；实际可写位数由实现决定。",
                   "PPN": "根页表物理页号；须按本级转换模式的地址宽度与根页表对齐规则解释。"}.get(name)
        if summary:
            rule = "RV32 MODE：0=Bare，1=Sv32（hgatp 为 Sv32x4）。RV64 MODE：0=Bare，8=Sv39，9=Sv48，10=Sv57；hgatp 对应 Sv39x4/Sv48x4/Sv57x4。并非所有编码都必须实现。" if name == "MODE" else ""
            return entry(summary, "WARL", chapter="supervisor.adoc" if csr == "satp" else "hypervisor.adoc", rule=rule)
    if csr in ("mtvec", "stvec", "vstvec", "jvt"):
        if name == "BASE":
            return entry("跳转表基地址，高位保存实际地址去掉低 6 位后的值，要求 64 字节对齐。" if csr == "jvt" else "陷阱入口基地址，存放地址的高 XLEN-2 位；最低两位由 MODE 占用，向量模式可能要求更严格对齐。", "WARL", chapter="zc.adoc" if csr == "jvt" else "supervisor.adoc" if csr == "stvec" else "hypervisor.adoc" if csr == "vstvec" else "machine.adoc")
        if name == "MODE":
            return entry("选择跳转表模式；当前标准模式为 0，其他值为保留编码。" if csr == "jvt" else "选择陷阱入口方式：Direct 使用 BASE；Vectored 对异步中断使用 BASE+4×cause，同步异常仍进入 BASE。", "WARL", {"0": "当前标准表跳转模式"} if csr == "jvt" else {"0": "Direct", "1": "Vectored", "2": "保留", "3": "保留"}, chapter="zc.adoc" if csr == "jvt" else "supervisor.adoc" if csr == "stvec" else "hypervisor.adoc" if csr == "vstvec" else "machine.adoc")
    if csr in ("fflags", "frm", "fcsr"):
        flags = {"NV": "无效浮点操作", "DZ": "浮点除零", "OF": "浮点溢出", "UF": "浮点下溢", "NX": "浮点结果不精确"}
        if name in flags:
            return entry(flags[name] + "累计异常标志；相关运算可将其置 1，软件通过 CSR 写入清除或更新。", "RW（运算累积置位）", {"0": "未记录该异常", "1": "已记录该异常"}, chapter="f-st-ext.adoc")
        if name == "ROUNDINGMODE":
            return entry("动态浮点舍入模式；指令选择动态舍入时使用该值。frm 中保留编码的行为按规范处理。", "RW", {"0": "RNE：最近，偶数优先", "1": "RTZ：向零", "2": "RDN：向负无穷", "3": "RUP：向正无穷", "4": "RMM：最近，最大绝对值优先", "5": "保留", "6": "保留", "7": "frm 中为保留编码"}, chapter="f-st-ext.adoc")
    interrupt = re.fullmatch(r"(LCOF|SGE|VSE|VST|VSS|ME|SE|MT|ST|MS|SS)I([EP])", name)
    if interrupt and csr in ("mie", "mip", "sie", "sip", "vsie", "vsip", "hie", "hip", "hvip"):
        kind, flag = interrupt.groups()
        kind_zh = {"LCOF": "本地计数器溢出", "SGE": "访客外部中断汇总", "VSE": "VS 外部", "VST": "VS 定时器", "VSS": "VS 软件", "ME": "M 外部", "SE": "S 外部", "MT": "M 定时器", "ST": "S 定时器", "MS": "M 软件", "SS": "S 软件"}[kind]
        chapter = "hypervisor.adoc" if csr.startswith(("h", "vs")) else "supervisor.adoc" if csr.startswith("s") else "machine.adoc"
        summary = kind_zh + ("中断允许位，仍受全局使能和委托规则约束。" if flag == "E" else "中断待处理状态。")
        values = {"0": "禁止该中断源", "1": "允许该中断源"} if flag == "E" else {"0": "未挂起", "1": "已挂起"}
        access, rule = "WARL（未实现的中断源为只读零）", ""
        if csr.startswith("vs"):
            summary = summary.replace("S 外部", "VS 外部").replace("S 定时器", "VS 定时器").replace("S 软件", "VS 软件")
            rule = "由 hideleg 对应的 VS 中断委托位控制可见性；未委托时为只读零，委托后别名到对应 hip/hie 位。"
        if flag == "P":
            access = "RO" if (csr == "mip" and kind in ("ME", "MT", "MS")) or (csr == "hip" and kind in ("SGE", "VSE", "VST")) or (csr in ("sip", "vsip") and kind in ("SE", "ST")) else "RW / RO=0（依实现及委托）"
            if csr == "hvip":
                access, rule = "RW", "软件置位向 VS 模式注入相应中断；实际接收仍受委托和中断使能控制。"
            elif csr == "mip" and name == "SEIP":
                rule = "读取值为软件位与外部中断信号的逻辑或；CSR 读改写只修改软件位，不把外部信号写回。"
            elif csr == "mip" and name == "STIP":
                rule = "基础规范允许软件写入；Sstc 且 menvcfg.STCE=1 时改由 stimecmp 比较结果控制，为只读。"
            elif csr == "hip" and name == "SGEIP":
                rule = "当 hgeip 与 hgeie 按位与非零时置位。"
            elif csr == "hip" and name == "VSEIP":
                rule = "为 hvip.VSEIP、VGEIN 选中的 hgeip 位与其他平台 VS 外部中断源的逻辑或。"
            elif csr == "hip" and name == "VSTIP":
                rule = "为 hvip.VSTIP 与其他 VS 定时器中断源的逻辑或；Sstc 可加入 vstimecmp 比较结果。"
            elif csr == "hip" and name == "VSSIP":
                access, rule = "RW", "hvip.VSSIP 的可写别名。"
            if name == "LCOFIP":
                rule = "Sscofpmf 溢出请求置位；软件可清零，未实现该扩展时只读零。"
        return entry(summary, access, values, chapter=chapter, rule=rule)
    if csr in ("medeleg", "medelegh", "mideleg", "hedeleg", "hedelegh", "hideleg"):
        level = "VS" if csr.startswith("h") else "S"
        what = "中断" if "ideleg" in csr else "同步异常"
        return entry(f"按原因编号选择向 {level} 模式委托的{what}，可委托范围由实现和架构约束决定。", "WARL", chapter="hypervisor.adoc" if csr.startswith("h") else "machine.adoc", rule="这是原因位掩码而非连续枚举；每一位分别表示不委托/委托，部分位固定为零或有专门限制。")
    if csr in ("mtval", "stval", "vstval", "mtval2", "htval", "mtinst", "htinst"):
        if "inst" in csr:
            summary = "保存陷阱相关的变换后指令或规定的伪指令编码；不提供该信息时可为零。"
        elif csr in ("mtval2", "htval"):
            summary = "保存两阶段地址转换陷阱的补充信息；适用时记录右移两位的访客物理地址。"
        else:
            summary = "保存陷阱附加信息，例如出错地址或指令信息；不提供该类信息时为零。"
        return entry(summary, "WARL / RO=0（依实现）", chapter="hypervisor.adoc" if csr.startswith(("h", "vs")) or csr in ("mtinst", "mtval2") else "supervisor.adoc" if csr.startswith("s") else "machine.adoc")
    if csr == "misa":
        rule = ""
        if name == "EXTENSIONS":
            rule = "这是位掩码而非单值枚举；置位表示支持对应能力。A 原子，B 位操作，C 压缩，D 双精度，E 嵌入式整数基集，F 单精度，H 虚拟化，I 整数基集，M 乘除，Q 四精度，S 监管模式，U 用户模式，V 向量，X 非标准扩展；G/J/K/L/O/R/T/W/Y/Z 为保留，N/P 在本版暂为预留。B=1 表示具备 Zba/Zbb/Zbs；M=0 不排除 Zmmul。"
        return entry("报告 M 模式的原生整数寄存器位宽。" if name == "MXL" else "按字母 A 到 Z 编码已实现的单字母 ISA 扩展，第 0 位对应 A。", "RO" if name == "MXL" else "WARL", XLEN_VALUES if name == "MXL" else None, rule=rule)
    if csr in ("vstart", "vxsat", "vxrm", "vcsr", "vl", "vtype", "vlenb"):
        meanings = {"VSTART": "指定向量指令开始或重新开始执行的元素索引。", "VXSAT": "累计记录定点向量运算是否发生饱和。", "VXRM": "选择定点向量运算的舍入模式。", "VL": "指定当前向量指令参与操作的元素数量。", "VLENB": "报告向量寄存器的字节长度 VLEN/8。", "VILL": "标记最近设置的向量类型不受实现支持。", "VMA": "选择被掩码屏蔽的目标元素是否可采用 agnostic 策略。", "VTA": "选择尾部目标元素是否可采用 agnostic 策略。", "VSEW": "选择向量元素宽度 SEW。", "VLMUL": "选择向量寄存器组倍率 LMUL。"}
        access = "RO（由向量配置指令更新）" if csr in ("vtype", "vl") else "RO" if csr == "vlenb" else "RW"
        return entry(meanings[name], access, chapter="v-st-ext.adoc")
    if "stimecmp" in csr:
        return entry(("保存 64 位定时比较值的高 32 位；" if csr.endswith("h") else "保存定时比较值；") + "时间计数值达到或超过完整比较值时产生相应 S/VS 定时器中断。", "RW", chapter="sstc.adoc")
    if csr == "ssp":
        return entry("保存 Zicfiss 影子栈指针，影子栈指令隐式通过它执行压栈或返回地址校验。", "WARL", chapter="unpriv-cfi.adoc")
    if csr == "seed":
        meanings = {"OPST": "报告熵源的自检、等待、数据可用或故障状态。", "CUSTOM": "保留给自定义和实验性熵源用途。", "ENTROPY": "在 OPST=ES16 时提供 16 位物理熵数据。"}
        values = {"0": "BIST：自检中", "1": "WAIT：等待熵", "2": "ES16：16 位熵可用", "3": "DEAD：熵源故障"} if name == "OPST" else {}
        return entry(meanings[name], "读取结果；CSR 写值忽略", values, chapter="scalar-crypto.adoc", rule="seed 的轮询必须使用带写访问的 CSR 指令；纯只读的 CSR 访问方式会触发非法指令异常。")
    if csr == "hstatus" and name == "GVA":
        return entry("标记送入 HS 模式的陷阱信息中是否含有访客虚拟地址。", "RW（由陷阱更新）", chapter="hypervisor.adoc")
    if csr == "mnstatus":
        meanings = {"MNPP": "保存进入可恢复非屏蔽中断前的特权级，供 MNRET 恢复。", "MNPV": "保存进入可恢复非屏蔽中断前的虚拟化模式。", "MNPELP": "保存进入可恢复非屏蔽中断前的预期 landing-pad 状态。", "NMIE": "控制非屏蔽中断使能；清零时屏蔽全部中断，软件只能将其置一。"}
        return entry(meanings[name], "置一有效，清零忽略（硬件可清零）" if name == "NMIE" else "WARL", chapter="rnmi.adoc")
    if csr in ("mseccfg", "mseccfgh"):
        meanings = {"MML": "启用 M 模式 PMP lockdown 规则，置一后直到 PMP 复位前不能清零。", "MMWP": "使未匹配 PMP 规则的 M 模式访问默认被拒绝，置一后直到 PMP 复位前不能清零。", "RLB": "允许修改被锁定的 PMP 规则，清零后在存在锁定条目时不能重新置一。", "USEED": "控制 U 模式是否允许访问 seed 熵源 CSR。", "SSEED": "控制 S 模式是否允许访问 seed 熵源 CSR。", "MLPE": "启用 M 模式 Zicfilp landing-pad 检查。", "PMM": "选择 M 模式的指针屏蔽模式。"}
        return entry(meanings[name], "RW（受粘滞位/PMP 锁定约束）" if name in ("MML", "MMWP", "RLB") else "RV32：RO=0；RV64：WARL" if name == "PMM" else "WARL", chapter="smepmp.adoc" if name in ("MML", "MMWP", "RLB") else "scalar-crypto.adoc" if name in ("USEED", "SSEED") else "zpm.adoc" if name == "PMM" else "machine.adoc")
    ids = {"BANK": "编码 JEDEC 厂商标识的 continuation-code 数量。", "OFFSET": "编码 JEDEC 厂商标识最后一个非 continuation 字节的低七位。", "ARCHITECTUREID": "标识 hart 的基础微架构实现。", "IMPLEMENTATION": "标识处理器实现的版本，由具体实现定义其编码。", "HARTID": "标识当前硬件线程，系统中的 hart ID 必须唯一。", "MCONFIGPTR": "指向平台配置数据结构，未实现时返回零。"}
    if name in ids:
        return entry(ids[name], "RO")
    return None


ENGLISH = {
    "MIE": "Control global interrupt enable in M-mode.", "SIE": "Control global interrupt enable in S-mode.",
    "MPIE": "Save the pre-trap M-mode interrupt-enable state.", "SPIE": "Save the pre-trap S-mode interrupt-enable state.",
    "MPP": "Save the privilege mode before an M-mode trap.", "SPP": "Save the privilege mode before an S-mode trap.",
    "MPRV": "Select MPP-based privilege for explicit data accesses.", "SUM": "Permit S-mode data access to user pages.", "MXR": "Allow loads from executable pages.",
    "TVM": "Intercept S-mode virtual-memory management operations.", "TW": "Control trapping of lower-privilege WFI instructions.", "TSR": "Trap S-mode SRET instructions.",
    "FS": "Track floating-point context state.", "VS": "Track vector context state.", "XS": "Summarize context state maintained by other extensions.", "SD": "Summarize dirty floating-point, vector, or other extension state.",
    "UXL": "Select the effective XLEN for U-mode.", "SXL": "Select the effective XLEN for S-mode.", "UBE": "Select endianness for explicit U-mode data accesses.", "SBE": "Select endianness for S-mode data and applicable page-table accesses.", "MBE": "Select endianness for explicit M-mode data accesses.",
    "MPV": "Save or select the virtualized privilege state.", "GVA": "Indicate that trap information contains a guest virtual address.", "MDT": "Control detection of unexpected M-mode traps.", "SDT": "Control detection of unexpected S-mode traps.", "MPELP": "Save the pre-trap expected landing-pad state for M-mode.", "SPELP": "Save the pre-trap expected landing-pad state for S-mode.",
    "VSBE": "Select endianness for explicit VS-mode data accesses.", "SPV": "Save the pre-trap virtualization mode.", "SPVP": "Select guest privilege for hypervisor virtual-memory accesses.", "HU": "Permit hypervisor virtual-memory accesses from U-mode.", "VGEIN": "Select the guest external interrupt source for VS-level.", "VTVM": "Intercept VS-mode virtual-memory management operations.", "VTW": "Control virtual-instruction traps for VS-mode WFI.", "VTSR": "Intercept VS-mode SRET instructions.", "VSXL": "Select the effective XLEN for VS-mode.", "HUPMM": "Select pointer masking for HU guest-memory accesses.",
    "FIOM": "Extend applicable I/O ordering constraints to main memory.", "LPE": "Enable landing-pad enforcement for the controlled privilege mode.", "SSE": "Enable shadow-stack operations for the controlled privilege mode.", "CBIE": "Control lower-privilege cache-block invalidate operations.", "CBCFE": "Enable lower-privilege cache-block clean and flush operations.", "CBZE": "Enable lower-privilege cache-block zero operations.", "PMM": "Select the applicable pointer-masking mode.", "DTE": "Enable double-trap detection at the controlled privilege level.", "CDE": "Enable counter delegation to S-mode.", "ADUE": "Select hardware updating of page-table access and dirty bits.", "PBMTE": "Enable page-based memory types.", "STCE": "Enable the controlled Sstc timer-comparison facilities.",
    "VSTART": "Select the first vector element to execute or restart.", "VXSAT": "Accumulate fixed-point vector saturation status.", "VXRM": "Select fixed-point vector rounding mode.", "VL": "Specify the active vector length.", "VLENB": "Report vector-register length in bytes.", "VILL": "Indicate an unsupported vector-type configuration.", "VMA": "Select masked-off destination element policy.", "VTA": "Select tail destination element policy.", "VSEW": "Select the vector element width.", "VLMUL": "Select the vector-register group multiplier.",
    "SSP": "Hold the shadow-stack pointer used by Zicfiss operations.", "OPST": "Report entropy-source operational status.", "CUSTOM": "Provide space for custom or experimental entropy-source use.", "ENTROPY": "Provide 16 entropy bits when OPST reports ES16.",
    "MXL": "Report the native M-mode integer register width.", "EXTENSIONS": "Report implemented single-letter ISA extensions as an A-to-Z bit mask.",
    "INT": "Distinguish interrupts from synchronous exceptions.", "CODE": "Identify the trap cause within its interrupt or exception class.", "SCRATCH": "Hold software-defined trap-handler scratch data.",
    "NX": "Accumulate the floating-point inexact-result flag.", "UF": "Accumulate the floating-point underflow flag.", "OF": "Accumulate the floating-point overflow flag.", "DZ": "Accumulate the floating-point divide-by-zero flag.", "NV": "Accumulate the floating-point invalid-operation flag.", "ROUNDINGMODE": "Select the dynamic floating-point rounding mode.",
    "MNPP": "Save the privilege mode before an RNMI.", "MNPV": "Save the virtualization mode before an RNMI.", "MNPELP": "Save the expected landing-pad state before an RNMI.", "NMIE": "Enable non-maskable interrupts through a software set-only control.",
    "MML": "Enable sticky machine-mode PMP lockdown semantics.", "MMWP": "Deny unmatched M-mode PMP accesses until PMP reset.", "RLB": "Bypass PMP rule locking subject to the architectural lock constraints.", "USEED": "Permit U-mode access to the seed CSR.", "SSEED": "Permit S-mode access to the seed CSR.", "MLPE": "Enable M-mode landing-pad enforcement.",
    "BANK": "Encode the JEDEC continuation-code count.", "OFFSET": "Encode the low seven bits of the final JEDEC manufacturer byte.", "ARCHITECTUREID": "Identify the hart's base microarchitecture.", "IMPLEMENTATION": "Report an implementation-defined processor revision.", "HARTID": "Identify the current hardware thread uniquely within the system.", "MCONFIGPTR": "Point to the platform configuration data structure, or return zero.",
}


def english_summary(reg, field):
    csr, name = reg["name"], field["name"]
    if csr.startswith("mhpmevent"):
        return ("Hold the upper 24 bits of the logical event selector." if csr.endswith("h") else "Select the platform-defined performance event.") if name == "EVENT" else "Latch counter overflow and disable further overflow interrupt requests." if name == "OF" else "Inhibit counting in " + name.removesuffix("INH") + "-mode."
    if csr == "scountovf":
        return "Report overflow for performance counter " + name.removeprefix("OF") + "."
    if name in ("GEIP", "GEIE"):
        return "Report pending guest external interrupts by source number." if name == "GEIP" else "Select guest external interrupts contributing to the HS-level summary."
    if csr in ("htimedelta", "htimedeltah"):
        return "Hold the upper 32 bits of the 64-bit guest time offset." if csr.endswith("h") else "Offset guest time reads from the physical time counter."
    if "stateen" in csr:
        meanings = {"C": "custom extension state", "FCSR": "integer-register floating-point state", "JVT": "the Zcmt jump-table CSR", "CTR": "control-transfer record state", "SRMCFG": "resource-management configuration", "P1P13": "the hedelegh CSR", "CONTEXT": "debug context CSRs", "IMSIC": "IMSIC state", "AIA": "other AIA state", "CSRIND": "indirect CSR access", "ENVCFG": "environment configuration CSRs", "SE0": "lower-privilege state-enable CSRs"}
        return "Control lower-privilege access to " + meanings.get(name, "state-enable register group " + name[-1]) + "."
    pmp = re.fullmatch(r"PMP(\d+)([LAXWR])", name)
    if pmp:
        meanings = {"L": "lock its configuration and address", "A": "select its address-matching mode", "X": "control execute permission", "W": "control write permission", "R": "control read permission"}
        return f"PMP entry {pmp[1]}: {meanings[pmp[2]]}."
    if csr.startswith("pmpaddr"):
        return "Encode the PMP address and, for NAPOT, the matched region size."
    if csr in ("mcounteren", "scounteren", "hcounteren", "mcountinhibit", "scountinhibit"):
        return ("Inhibit counting by " if csr.endswith("inhibit") else "Permit lower-privilege reads of ") + ({"CY": "the cycle counter", "IR": "the retired-instruction counter", "TM": "the time counter"}.get(name, "performance counter " + name.removeprefix("HPM"))) + "."
    if name == csr.upper() and re.fullmatch(r"(?:m?(?:cycle|instret)|time|m?hpmcounter\d+|mhpmevent\d+)(h?)", csr):
        meaning = "Select the platform-defined performance event" if "event" in csr else "Count retired instructions" if "instret" in csr else "Count processor cycles" if "cycle" in csr else "Report real-time counter state" if "time" in csr else "Count the selected performance events"
        return meaning + (" through its RV32 high-half CSR." if csr.endswith("h") else ".")
    if re.fullmatch(r"(LCOF|SGE|VSE|VST|VSS|ME|SE|MT|ST|MS|SS)I[EP]", name):
        types = {"LCOF": "local counter-overflow", "SGE": "supervisor guest external", "VSE": "virtual supervisor external", "VST": "virtual supervisor timer", "VSS": "virtual supervisor software", "ME": "machine external", "SE": "supervisor external", "MT": "machine timer", "ST": "supervisor timer", "MS": "machine software", "SS": "supervisor software"}
        kind = types[name[:-2]]
        if csr.startswith("vs"):
            kind = kind.replace("supervisor", "virtual supervisor")
        return ("Enable the " if name.endswith("E") else "Report the pending ") + kind + " interrupt source."
    if "deleg" in csr:
        return "Select trap causes delegated to " + ("VS-mode." if csr.startswith("h") else "S-mode.")
    if csr.endswith("epc"):
        return "Hold the trap return program counter subject to address and alignment constraints."
    if csr in ("mtval", "stval", "vstval", "mtval2", "htval", "mtinst", "htinst"):
        return "Hold transformed trap-instruction information, or zero." if "inst" in csr else "Hold supplementary guest-physical-address trap information." if csr in ("mtval2", "htval") else "Hold trap-specific address or instruction information, or zero."
    if "stimecmp" in csr:
        return "Hold the timer-comparison value" + (" through its RV32 high-half CSR." if csr.endswith("h") else ".")
    if name == "MODE":
        return "Select the page-table translation mode." if csr in ("satp", "vsatp", "hgatp") else "Select the jump-table mode." if csr == "jvt" else "Select direct or vectored trap entry."
    if name == "BASE":
        return "Encode the aligned jump-table base address." if csr == "jvt" else "Encode the aligned trap-handler base address."
    if name in ("ASID", "VMID", "PPN"):
        return {"ASID": "Tag the address space for address-translation caching.", "VMID": "Tag the virtual machine for G-stage translation caching.", "PPN": "Encode the root page-table physical page number."}[name]
    return ENGLISH[name].replace("S-mode", "VS-mode").replace("U-mode", "VU-mode") if csr == "vsstatus" else ENGLISH[name]


CONTROL_VALUES = {
    "LPE": ({"0": "关闭 landing-pad 检查", "1": "启用 landing-pad 检查"}, {"0": "Landing-pad checks disabled", "1": "Landing-pad checks enabled"}),
    "MLPE": ({"0": "关闭 M 模式 landing-pad 检查", "1": "启用 M 模式 landing-pad 检查"}, {"0": "M-mode landing-pad checks disabled", "1": "M-mode landing-pad checks enabled"}),
    "SSE": ({"0": "关闭影子栈功能", "1": "启用影子栈功能"}, {"0": "Shadow-stack operations disabled", "1": "Shadow-stack operations enabled"}),
    "CBCFE": ({"0": "拦截缓存块 clean/flush", "1": "允许缓存块 clean/flush"}, {"0": "Trap cache-block clean/flush", "1": "Permit cache-block clean/flush"}),
    "CBZE": ({"0": "拦截缓存块清零", "1": "允许缓存块清零"}, {"0": "Trap cache-block zero", "1": "Permit cache-block zero"}),
    "DTE": ({"0": "关闭受控模式的双重陷阱功能", "1": "启用受控模式的双重陷阱功能"}, {"0": "Controlled-mode double-trap facility disabled", "1": "Controlled-mode double-trap facility enabled"}),
    "CDE": ({"0": "关闭计数器委托功能", "1": "启用向 S 模式的计数器委托"}, {"0": "Counter-delegation facility disabled", "1": "Counter delegation to S-mode enabled"}),
    "PBMTE": ({"0": "PTE 的 PBMT 字段为保留位", "1": "启用 PTE 的 PBMT 字段"}, {"0": "PTE PBMT field reserved", "1": "PTE PBMT field enabled"}),
    "STCE": ({"0": "禁止受控的定时比较 CSR 访问", "1": "允许受控的定时比较 CSR 访问"}, {"0": "Controlled timer-comparison CSR access disabled", "1": "Controlled timer-comparison CSR access enabled"}),
    "HU": ({"0": "U 模式访客访存指令陷入", "1": "允许 U 模式执行访客访存指令"}, {"0": "Trap U-mode hypervisor memory accesses", "1": "Permit U-mode hypervisor memory accesses"}),
    "MML": ({"0": "基础 PMP 权限解释", "1": "启用 PMP lockdown 权限解释"}, {"0": "Base PMP permission semantics", "1": "PMP lockdown permission semantics"}),
    "MMWP": ({"0": "未匹配 PMP 的 M 模式访问默认允许", "1": "未匹配 PMP 的 M 模式访问默认拒绝"}, {"0": "Unmatched M-mode accesses allowed by default", "1": "Unmatched M-mode accesses denied by default"}),
    "RLB": ({"0": "遵守 PMP 锁定规则", "1": "按规范条件绕过 PMP 锁定"}, {"0": "PMP locking applies", "1": "Bypass PMP locking subject to its constraints"}),
    "USEED": ({"0": "禁止 U 模式访问 seed", "1": "允许 U 模式访问 seed"}, {"0": "U-mode seed access disabled", "1": "U-mode seed access enabled"}),
    "SSEED": ({"0": "禁止 S 模式访问 seed", "1": "允许 S 模式访问 seed"}, {"0": "S-mode seed access disabled", "1": "S-mode seed access enabled"}),
}


def cause_values(csr):
    """CODE is conditional on INT; keep both meanings at the same numeric key."""
    exceptions = {
        0: ("指令地址未对齐", "Instruction address misaligned"),
        1: ("指令访问故障", "Instruction access fault"),
        2: ("非法指令", "Illegal instruction"), 3: ("断点", "Breakpoint"),
        4: ("加载地址未对齐", "Load address misaligned"), 5: ("加载访问故障", "Load access fault"),
        6: ("存储/AMO 地址未对齐", "Store/AMO address misaligned"),
        7: ("存储/AMO 访问故障", "Store/AMO access fault"),
        8: ("U 模式环境调用（H：也含 VU）", "Environment call from U-mode (also VU with H)"),
        9: ("S/HS 模式环境调用", "Environment call from S/HS-mode"),
        12: ("指令页故障", "Instruction page fault"), 13: ("加载页故障", "Load page fault"),
        15: ("存储/AMO 页故障", "Store/AMO page fault"),
        18: ("软件检查异常", "Software check"), 19: ("硬件错误", "Hardware error"),
    }
    if csr in ("mcause", "mncause"):
        exceptions.update({11: ("M 模式环境调用", "Environment call from M-mode"), 16: ("双重陷阱", "Double trap")})
    if csr in ("mcause", "scause", "mncause"):
        exceptions.update({10: ("H：VS 模式环境调用；否则保留", "H: environment call from VS-mode; otherwise reserved"),
            20: ("H：指令 guest-page 故障；否则保留", "H: instruction guest-page fault; otherwise reserved"),
            21: ("H：加载 guest-page 故障；否则保留", "H: load guest-page fault; otherwise reserved"),
            22: ("H：虚拟指令异常；否则保留", "H: virtual instruction; otherwise reserved"),
            23: ("H：存储/AMO guest-page 故障；否则保留", "H: store/AMO guest-page fault; otherwise reserved")})
    interrupts = {1: ("S 软件中断", "Supervisor software interrupt"),
        5: ("S 定时器中断", "Supervisor timer interrupt"),
        9: ("S 外部中断", "Supervisor external interrupt"),
        13: ("Sscofpmf：计数器溢出中断", "Sscofpmf: counter-overflow interrupt")}
    if csr == "mcause":
        interrupts.update({3: ("M 软件中断", "Machine software interrupt"), 7: ("M 定时器中断", "Machine timer interrupt"), 11: ("M 外部中断", "Machine external interrupt")})
    if csr in ("mcause", "scause"):
        interrupts.update({2: ("H：VS 软件中断；否则保留", "H: virtual supervisor software interrupt; otherwise reserved"),
            6: ("H：VS 定时器中断；否则保留", "H: virtual supervisor timer interrupt; otherwise reserved"),
            10: ("H：VS 外部中断；否则保留", "H: virtual supervisor external interrupt; otherwise reserved"),
            12: ("H：S 访客外部中断；否则保留", "H: supervisor guest external interrupt; otherwise reserved")})
    zh, en = {}, {}
    for code in range(64):
        exception = exceptions.get(code, ("自定义用途", "Custom use") if 24 <= code <= 31 or 48 <= code <= 63 else ("保留", "Reserved"))
        interrupt = interrupts.get(code, ("平台定义", "Platform use") if code >= 16 else ("保留", "Reserved"))
        if csr == "mncause":
            interrupt = ("未知 RNMI 原因" if code == 0 else "平台定义的 RNMI 原因", "Unknown RNMI cause" if code == 0 else "Platform-defined RNMI cause")
        zh[str(code)] = f"INT=0：{exception[0]}；INT=1：{interrupt[0]}"
        en[str(code)] = f"INT=0: {exception[1]}; INT=1: {interrupt[1]}"
    return zh, en


def enums(reg, field, data):
    """Return matched Chinese/English enum maps; bit masks are not flat enums."""
    csr, name = reg["name"], field["name"]
    zh, en = dict(data["values"]), {}
    if name == "CODE" and "cause" in csr:
        zh, en = cause_values(csr)
    elif csr.startswith("mhpmevent"):
        if name == "EVENT":
            widths = {loc["xlen"] for loc in field["layouts"]}
            return {}, {}, {width: {"0": "不统计事件" if width == 64 else "本半部为零；完整事件编码为零时不计数"} for width in widths}, {width: {"0": "No event" if width == 64 else "This half is zero; no counting only if the full selector is zero"} for width in widths}
        en = {"0": "Allow the next overflow interrupt", "1": "Overflow latched; suppress further requests"} if name == "OF" else {"0": "Count in this mode", "1": "Inhibit counting in this mode"}
    elif csr == "scountovf":
        en = {"0": "No overflow, or not visible to this mode", "1": "Overflow recorded"}
    elif name in ("FS", "VS", "XS"):
        en = {"0": "Off", "1": "Initial", "2": "Clean", "3": "Dirty"}
    elif name in ("MXL", "UXL", "SXL", "VSXL"):
        zh = {"0": "保留", "1": "RV32", "2": "RV64", "3": "保留"}
        en = {"0": "Reserved", "1": "RV32", "2": "RV64", "3": "Reserved"}
    elif name in ("MPP", "MNPP"):
        zh = {"0": "U 模式", "1": "S 模式", "2": "保留", "3": "M 模式"}
        en = {"0": "U-mode", "1": "S-mode", "2": "Reserved", "3": "M-mode"}
    elif name == "SPP":
        en = {"0": "U-mode", "1": "S-mode"}
    elif name in ("UBE", "SBE", "MBE", "VSBE"):
        zh, en = {"0": "小端", "1": "大端"}, {"0": "Little-endian", "1": "Big-endian"}
    elif name == "ROUNDINGMODE":
        en = {"0": "RNE: nearest, ties to even", "1": "RTZ: toward zero", "2": "RDN: toward negative infinity", "3": "RUP: toward positive infinity", "4": "RMM: nearest, ties to maximum magnitude", "5": "Reserved", "6": "Reserved", "7": "Reserved in frm"}
    elif name == "VXRM":
        zh = {"0": "RNU：最近，平局向上", "1": "RNE：最近，偶数优先", "2": "RDN：截去低位", "3": "ROD：向奇数舍入"}
        en = {"0": "RNU: nearest, ties up", "1": "RNE: nearest, ties to even", "2": "RDN: truncate", "3": "ROD: round to odd"}
    elif name == "VLMUL":
        zh = {str(i): value for i, value in enumerate(["LMUL=1", "LMUL=2", "LMUL=4", "LMUL=8", "保留", "LMUL=1/8", "LMUL=1/4", "LMUL=1/2"])}
        en = {key: "Reserved" if value == "保留" else value for key, value in zh.items()}
    elif name == "VSEW":
        zh = {str(i): f"SEW={8 << i} 位" if i < 4 else "保留" for i in range(8)}
        en = {str(i): f"SEW={8 << i} bits" if i < 4 else "Reserved" for i in range(8)}
    elif name in ("VMA", "VTA"):
        zh, en = {"0": "保持未操作元素", "1": "允许 agnostic 策略"}, {"0": "Undisturbed", "1": "Agnostic"}
    elif name == "OPST":
        en = {"0": "BIST: self-test", "1": "WAIT: entropy unavailable", "2": "ES16: 16 entropy bits available", "3": "DEAD: entropy-source failure"}
    elif re.fullmatch(r"PMP\d+A", name):
        en = {"0": "OFF: disabled", "1": "TOR: top of range", "2": "NA4: aligned four-byte region", "3": "NAPOT: aligned power-of-two region"}
    elif name == "INT":
        en = {"0": "Synchronous exception", "1": "Interrupt"}
    elif name == "PMM" or name == "HUPMM":
        zh64, en64 = {"0": "不屏蔽指针位", "1": "保留", "2": "PMLEN=7", "3": "PMLEN=16"}, {"0": "Pointer masking disabled", "1": "Reserved", "2": "PMLEN=7", "3": "PMLEN=16"}
        widths = {loc["xlen"] for loc in field["layouts"]}
        by_zh = {width: zh64 if width == 64 else {"0": "只读零，不屏蔽指针位", "1": "RV32 不支持", "2": "RV32 不支持", "3": "RV32 不支持"} for width in widths}
        by_en = {width: en64 if width == 64 else {"0": "Read-only zero; masking disabled", "1": "Not supported on RV32", "2": "Not supported on RV32", "3": "Not supported on RV32"} for width in widths}
        return {}, {}, by_zh, by_en
    elif name == "CBIE":
        zh = {"0": "禁止 cbo.inval", "1": "按 cbo.flush 执行", "2": "保留", "3": "允许 cbo.inval"}
        en = {"0": "Trap cbo.inval", "1": "Execute as cbo.flush", "2": "Reserved", "3": "Execute cbo.inval"}
    elif name == "MODE" and csr in ("mtvec", "stvec", "vstvec"):
        en = {"0": "Direct", "1": "Vectored", "2": "Reserved", "3": "Reserved"}
    elif name == "MODE" and csr == "jvt":
        zh = {str(i): "当前标准表跳转模式" if i == 0 else "保留" for i in range(64)}
        en = {str(i): "Standard table-jump mode" if i == 0 else "Reserved" for i in range(64)}
    elif name == "MODE" and csr in ("satp", "vsatp", "hgatp"):
        zh32, en32 = {"0": "Bare：关闭本级分页", "1": "Sv32x4" if csr == "hgatp" else "Sv32"}, {"0": "Bare: no translation", "1": "Sv32x4" if csr == "hgatp" else "Sv32"}
        zh64 = {str(i): "保留" for i in range(16)}
        en64 = {str(i): "Reserved" for i in range(16)}
        for i, label in [(0, "Bare"), (8, "Sv39"), (9, "Sv48"), (10, "Sv57")]:
            label += "x4" if csr == "hgatp" and i else ""
            zh64[str(i)], en64[str(i)] = label, label
        return {}, {}, {32: zh32, 64: zh64}, {32: en32, 64: en64}
    elif csr in ("fflags", "fcsr") and name in ("NV", "DZ", "OF", "UF", "NX"):
        en = {"0": "No exception recorded", "1": "Exception recorded"}
    elif all(loc["msb"] == loc["lsb"] for loc in field["layouts"]):
        if name.endswith("IP"):
            zh, en = {"0": "未挂起", "1": "已挂起"}, {"0": "Not pending", "1": "Pending"}
        elif name in ("SPIE", "MPIE"):
            zh, en = {"0": "先前中断关闭", "1": "先前中断开启"}, {"0": "Previous interrupts disabled", "1": "Previous interrupts enabled"}
        elif name in ("MDT", "SDT"):
            zh, en = {"0": "可接受正常陷阱", "1": "标记不可重入阶段"}, {"0": "Normal trap entry permitted", "1": "Non-reentrant trap phase"}
        elif name in ("MPV", "MNPV", "SPV"):
            zh, en = {"0": "非虚拟化状态", "1": "虚拟化状态"}, {"0": "Non-virtualized", "1": "Virtualized"}
        elif name == "GVA":
            zh, en = {"0": "未标为访客虚拟地址", "1": "访客虚拟地址"}, {"0": "Not marked as a guest virtual address", "1": "Guest virtual address"}
        elif name in ("SD", "VXSAT", "VILL", "NV", "DZ", "OF", "UF", "NX"):
            if name == "SD":
                zh, en = {"0": "无 Dirty 扩展状态", "1": "存在 Dirty 扩展状态"}, {"0": "No dirty extension state", "1": "Dirty extension state present"}
            elif name == "VXSAT":
                zh, en = {"0": "未记录饱和", "1": "已记录饱和"}, {"0": "No saturation recorded", "1": "Saturation recorded"}
            else:
                zh, en = {"0": "向量类型受支持", "1": "向量类型不受支持"}, {"0": "Supported vector type", "1": "Unsupported vector type"}
        elif csr.endswith("inhibit"):
            zh, en = {"0": "继续计数", "1": "禁止计数"}, {"0": "Counting enabled", "1": "Counting inhibited"}
        elif re.fullmatch(r"PMP\d+L", name):
            zh, en = {"0": "未锁定", "1": "锁定（受 Smepmp 规则约束）"}, {"0": "Unlocked", "1": "Locked, subject to Smepmp"}
        elif name in ("MPELP", "SPELP", "MNPELP"):
            zh, en = {"0": "不期待 landing pad", "1": "期待 landing pad"}, {"0": "No landing pad expected", "1": "Landing pad expected"}
        elif name in ("TVM", "TW", "TSR", "VTVM", "VTW", "VTSR"):
            zh, en = {"0": "不强制该陷入", "1": "按本字段规则拦截"}, {"0": "No interception by this control", "1": "Intercept as specified"}
        elif "stateen" in csr:
            zh, en = {"0": "阻止相关低特权访问", "1": "允许访问，仍受上层限制"}, {"0": "Block the lower-privilege access", "1": "Allow access, subject to higher-level controls"}
        elif name == "SPVP":
            zh, en = {"0": "按 VU 权限访问", "1": "按 VS 权限访问"}, {"0": "Use VU privilege", "1": "Use VS privilege"}
        elif name in ("MIE", "SIE"):
            zh, en = {"0": "关闭本级全局中断", "1": "允许本级全局中断"}, {"0": "Disable interrupts at this level", "1": "Enable interrupts at this level"}
        elif name == "MPRV":
            zh, en = {"0": "按当前特权级访存", "1": "数据访存按 MPP 特权级"}, {"0": "Use current privilege", "1": "Use MPP privilege for data accesses"}
        elif name == "SUM":
            zh, en = {"0": "S 模式不能访问用户页数据", "1": "允许 S 模式访问用户页数据"}, {"0": "Disallow S-mode access to user-page data", "1": "Allow S-mode access to user-page data"}
        elif name == "MXR":
            zh, en = {"0": "加载要求页可读", "1": "可执行页也可用于加载"}, {"0": "Loads require readable pages", "1": "Executable pages also satisfy loads"}
        elif name == "FIOM":
            zh, en = {"0": "维持普通 I/O 排序含义", "1": "I/O 排序同时涵盖主存"}, {"0": "Normal I/O ordering semantics", "1": "I/O ordering also covers memory"}
        elif name == "ADUE":
            zh, en = {"0": "不由硬件更新 A/D 位", "1": "启用硬件更新 A/D 位"}, {"0": "Hardware A/D updates disabled", "1": "Hardware A/D updates enabled"}
        elif name == "NMIE":
            zh, en = {"0": "全部中断被屏蔽", "1": "允许 RNMI，其他中断仍受常规控制"}, {"0": "All interrupts masked", "1": "RNMI enabled; normal controls still apply to other interrupts"}
        elif re.fullmatch(r"(LCOF|SGE|VSE|VST|VSS|ME|SE|MT|ST|MS|SS)IE", name):
            zh, en = {"0": "禁止该中断源", "1": "允许该中断源"}, {"0": "Disable this interrupt source", "1": "Enable this interrupt source"}
        elif csr.endswith("counteren"):
            zh, en = {"0": "禁止低特权级读取", "1": "允许读取，仍受上层控制约束"}, {"0": "Block lower-privilege reads", "1": "Permit reads, subject to higher-level controls"}
        elif name in CONTROL_VALUES:
            zh, en = CONTROL_VALUES[name]
        elif re.fullmatch(r"PMP\d+[RWX]", name):
            operation = {"R": "读取", "W": "写入", "X": "执行"}[name[-1]]
            operation_en = {"R": "reads", "W": "writes", "X": "execution"}[name[-1]]
            zh, en = {"0": "基础 PMP：禁止" + operation, "1": "基础 PMP：允许" + operation}, {"0": "Base PMP: deny " + operation_en, "1": "Base PMP: permit " + operation_en}
        else:
            raise ValueError(f"Missing specific Boolean semantics: {csr}.{name}")
    if zh and not en:
        raise ValueError(f"Missing English enum: {csr}.{name}")
    return zh, en, {}, {}


REGISTER_ZH = {
    "mstatus": "机器模式状态与陷阱控制。", "mstatush": "RV32 的机器状态高半部。", "misa": "已实现 ISA 扩展与原生整数位宽。",
    "sstatus": "监管模式可见的状态与陷阱控制。", "vsstatus": "虚拟监管模式的状态与陷阱控制。", "hstatus": "虚拟机状态与 HS 模式陷阱控制。",
    "mvendorid": "JEDEC 厂商标识。", "marchid": "微架构标识。", "mimpid": "处理器实现版本。", "mhartid": "当前硬件线程标识。", "mconfigptr": "平台配置数据结构指针。",
    "fflags": "累计浮点异常标志。", "frm": "动态浮点舍入模式。", "fcsr": "浮点舍入模式与累计异常状态。",
    "vstart": "向量指令起始元素索引。", "vxsat": "向量定点运算的累计饱和标志。", "vxrm": "向量定点舍入模式。", "vcsr": "向量定点舍入与饱和状态。", "vl": "当前向量长度。", "vtype": "向量元素宽度与寄存器组配置。", "vlenb": "向量寄存器字节长度。",
    "ssp": "影子栈指针。", "seed": "熵源状态与随机熵数据。", "jvt": "表跳转基地址与模式。", "mnstatus": "可恢复非屏蔽中断状态。",
    "mseccfg": "机器模式安全与 PMP 配置。", "mseccfgh": "RV32 的机器安全配置高半部。", "scountovf": "性能计数器溢出状态。", "scountinhibit": "已委托计数器的监管模式计数控制。",
    "htimedelta": "虚拟机时间偏移。", "htimedeltah": "RV32 的虚拟机时间偏移高半部。", "hgeip": "访客外部中断待处理位图。", "hgeie": "访客外部中断使能位图。",
    "dcsr": "调试控制与状态。", "dpc": "调试程序计数器。", "dscratch0": "调试软件暂存寄存器 0。", "dscratch1": "调试软件暂存寄存器 1。", "tselect": "调试触发器选择。", "tdata1": "调试触发器数据 1。", "tdata2": "调试触发器数据 2。", "tdata3": "调试触发器数据 3。", "mcontext": "机器模式调试上下文。", "scontext": "监管模式调试上下文。", "hcontext": "虚拟机调试上下文。",
}


def register_summary(reg):
    name = reg["name"]
    if name in REGISTER_ZH:
        return REGISTER_ZH[name]
    mode = "虚拟监管" if name.startswith("vs") else "虚拟机" if name.startswith("h") else "监管" if name.startswith("s") else "机器"
    if "stateen" in name:
        return f"{mode}模式的扩展状态访问控制。"
    if "envcfg" in name:
        return f"{mode}模式环境配置。"
    if "scratch" in name:
        return f"{mode}模式陷阱处理暂存区。"
    if name.endswith("epc"):
        return f"{mode}模式陷阱返回地址。"
    if "cause" in name:
        return f"{mode}模式陷阱原因。"
    if name.endswith("tvec"):
        return f"{mode}模式陷阱入口地址与模式。"
    if name in ("satp", "vsatp", "hgatp"):
        return f"{mode}模式地址转换根配置。"
    if "deleg" in name:
        return f"{mode}模式陷阱委托位图。"
    if name.endswith("counteren"):
        return f"{mode}模式的低特权计数器访问控制。"
    if name.endswith("inhibit"):
        return "性能计数器计数禁止控制。"
    if name.endswith(("ie", "ip")):
        return f"{mode}模式中断" + ("使能位图。" if name.endswith("ie") else "待处理位图。")
    if "stimecmp" in name:
        return f"{mode}模式定时比较值。"
    if name.startswith("pmpcfg"):
        return "PMP 区域权限、匹配方式与锁定配置。"
    if name.startswith("pmpaddr"):
        return "PMP 区域地址编码。"
    if name.startswith("mhpmevent"):
        return "性能事件选择与模式过滤控制。"
    if re.fullmatch(r"(?:m?(?:cycle|instret)|time|m?hpmcounter\d+)(h?)", name):
        text = "退休指令计数" if "instret" in name else "周期计数" if "cycle" in name else "实时时间计数" if "time" in name else "性能事件计数"
        return text + ("的 RV32 高半部。" if name.endswith("h") else "值。")
    if name.endswith("tval2"):
        return f"{mode}模式的补充地址陷阱信息。"
    if name.endswith("tval"):
        return f"{mode}模式的地址或指令陷阱信息。"
    if name.endswith("tinst"):
        return f"{mode}模式的变换后陷阱指令信息。"
    raise ValueError(f"Missing Chinese CSR summary: {name}")


def enrich(registers, source_root, source_url):
    audit = {"describedFields": 0, "enumeratedFields": 0, "pendingFields": [], "sources": {}}
    for reg in registers:
        reg["summaryEn"] = reg["summary"]
        reg["summary"] = register_summary(reg)
        for field in reg["fields"]:
            data = describe(reg, field)
            if data is None:
                audit["pendingFields"].append(reg["name"] + "." + field["name"])
                continue
            chapter = data["chapter"]
            assert (source_root / chapter).is_file(), chapter
            full = data["summary"]
            summary = full.split("。", 1)[0] + "。"
            field["summary"] = summary
            field["summaryEn"] = english_summary(reg, field)
            if data["access"]:
                field["access"] = data["access"]
            zh, en, zh_by_width, en_by_width = enums(reg, field, data)
            field.update(values=zh, valuesEn=en, valuesByXlen=zh_by_width, valuesEnByXlen=en_by_width)
            rule = data["rule"]
            if summary != full:
                rule = full + ("\n" + rule if rule else "")
            if rule:
                field["typeRule"] = rule
            field["sourceUrl"] = source_url(chapter)
            audit["sources"][reg["name"] + "." + field["name"]] = chapter
            audit["describedFields"] += 1
            audit["enumeratedFields"] += bool(zh or zh_by_width)
        for region in reg["regions"]:
            label, access = region["label"], region["access"]
            if access == "WPRI":
                region["summary"] = "写入其他字段时保留该区域，读取值应忽略。"
                region["summaryEn"] = "Preserve this region when updating other fields and ignore its read value."
            elif label == "0" and access == "RO":
                region["summary"] = "只读零区域。"
                region["summaryEn"] = "This region reads as zero."
            elif access == "Reserved":
                region["summaryEn"] = "Reserved by the specification; follow the original write rules."
            elif access == "WARL":
                region["summaryEn"] = "Writes may be constrained to architecturally legal values."
            else:
                region["summaryEn"] = "Follow the zero-value or write-zero rule specified for this region."
    if audit["pendingFields"]:
        raise ValueError("Unfinished field semantics: " + ", ".join(audit["pendingFields"]))
    return audit
