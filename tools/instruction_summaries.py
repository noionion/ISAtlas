"""Concise bilingual semantics reviewed against the pinned manual chapters.

Formula notation: X/F/V are register files, M is memory, sext/zext extend
to XLEN, and arithmetic results wrap to the destination width unless stated.
These are search summaries, not executable models or substitutes for the spec.
Unknown mnemonics fail generation instead of receiving placeholder summaries.
"""
import re

TEXT = {
    "nop": ("空操作，不改变架构状态（除正常推进 PC 和计数器）。", "No operation, apart from normal PC and counter advancement."),
    "mv": ("X(rd) = X(rs1)",) * 2,
    "not": ("X(rd) = ~X(rs1)",) * 2,
    "neg": ("X(rd) = -X(rs1)",) * 2,
    "negw": ("X(rd) = sext32(-X(rs1))",) * 2,
    "sext.w": ("X(rd) = sext(X(rs1)[31:0])",) * 2,
    "zext.w": ("X(rd) = zext(X(rs1)[31:0])",) * 2,
    "seqz": ("X(rd) = (X(rs1) == 0)",) * 2,
    "snez": ("X(rd) = (X(rs1) != 0)",) * 2,
    "sltz": ("X(rd) = (signed(X(rs1)) < 0)",) * 2,
    "sgtz": ("X(rd) = (signed(X(rs1)) > 0)",) * 2,
    "j": ("PC += sext(offset)",) * 2,
    "jr": ("PC = X(rs1) & ~1",) * 2,
    "ret": ("PC = X(x1) & ~1",) * 2,
    "lui": ("X(rd) = sext(imm20 << 12)",) * 2,
    "auipc": ("X(rd) = PC + sext(imm20 << 12)",) * 2,
    "jal": ("X(rd) = PC + 4; PC += sext(offset)",) * 2,
    "jalr": ("X(rd) = PC + 4; PC = (X(rs1) + sext(imm)) & ~1",) * 2,
    "fence": ("按 pred/succ 指定的类型排序前后访存与设备 I/O。", "Order preceding and succeeding memory/device accesses selected by pred/succ."),
    "fence.tso": ("排序读与读写、写与写，允许非原子写后读重排。", "Order loads before loads/stores and stores before stores; allow non-AMO store-to-load reordering."),
    "fence.i": ("同步本 hart 的数据写入与后续取指。", "Synchronize this hart's prior data stores with subsequent instruction fetches."),
    "pause": ("提示自旋等待，可暂时降低执行资源占用。", "Hint a spin-wait loop and optionally reduce execution resource use."),
    "ecall": ("触发当前特权态的环境调用异常。", "Raise an environment-call exception for the current privilege mode."),
    "ebreak": ("触发断点异常，交由调试环境或异常处理程序处理。", "Raise a breakpoint exception for the debugger or trap handler."),
    "sret": ("从 S 模式陷阱返回，恢复特权态、中断使能及 PC。", "Return from an S-mode trap, restoring privilege, interrupt enable and PC."),
    "mret": ("从 M 模式陷阱返回，恢复特权态、中断使能及 PC。", "Return from an M-mode trap, restoring privilege, interrupt enable and PC."),
    "mnret": ("从可恢复 NMI 返回并恢复 mnstatus 保存的状态。", "Return from a resumable NMI and restore the state saved in mnstatus."),
    "wfi": ("等待中断事件，允许实现提前恢复或按空操作执行。", "Wait for an interrupt; implementations may resume early or implement a no-op."),
    "sctrclr": ("清零所有控制流跟踪记录、周期计数及有效标记。", "Clear all control-transfer records, the cycle counter and its validity flag."),
    "wrs.nto": ("保留集有效且无待处理中断时等待，允许提前恢复。", "Wait while the reservation set is valid and no interrupt is pending; early resumption is permitted."),
    "wrs.sto": ("等待保留集变化或中断，受实现定义的短超时限制。", "Wait for reservation-set change or interrupt, bounded by an implementation-defined short timeout."),
    "sfence.vma": ("同步页表写入与后续地址转换，按 rs1/rs2 限定虚拟地址和 ASID。", "Synchronize page-table stores with translations, scoped by rs1 virtual address and rs2 ASID."),
    "hfence.vvma": ("同步 VS 阶段页表写入与地址转换，按地址和 ASID 限定。", "Synchronize VS-stage page-table stores with translations, scoped by address and ASID."),
    "hfence.gvma": ("同步 G 阶段页表写入与地址转换，按客体物理地址和 VMID 限定。", "Synchronize G-stage page-table stores with translations, scoped by guest physical address and VMID."),
    "sinval.vma": ("使指定虚拟地址/ASID 的地址转换缓存项失效。", "Invalidate address-translation cache entries selected by virtual address and ASID."),
    "hinval.vvma": ("使指定地址/ASID 的 VS 阶段地址转换缓存项失效。", "Invalidate VS-stage translation-cache entries selected by address and ASID."),
    "hinval.gvma": ("使指定客体物理地址/VMID 的 G 阶段地址转换缓存项失效。", "Invalidate G-stage translation-cache entries selected by guest physical address and VMID."),
    "sfence.w.inval": ("将先前页表写入排序在后续地址转换失效操作之前。", "Order prior page-table stores before subsequent translation invalidations."),
    "sfence.inval.ir": ("将先前地址转换失效操作排序在后续隐式页表读取之前。", "Order prior translation invalidations before subsequent implicit page-table reads."),
    "cbo.clean": ("将目标缓存块中的脏数据写回至规定的可见点。", "Write dirty data in the addressed cache block to the specified visibility point."),
    "cbo.flush": ("清理并使目标缓存块失效。", "Clean and invalidate the addressed cache block."),
    "cbo.inval": ("使目标缓存块失效，实际行为受环境配置约束。", "Invalidate the addressed cache block, subject to environment configuration."),
    "cbo.zero": ("将目标缓存块覆盖的内存字节写零。", "Zero the memory bytes covered by the addressed cache block."),
    "prefetch.i": ("提示预取目标地址附近的指令。", "Hint instruction prefetch near the addressed location."),
    "prefetch.r": ("提示为后续读取预取目标数据。", "Hint data prefetch for a subsequent read."),
    "prefetch.w": ("提示为后续写入预取目标数据。", "Hint data prefetch for a subsequent write."),
    "czero.eqz": ("X(rd) = (X(rs2) == 0) ? 0 : X(rs1)",) * 2,
    "czero.nez": ("X(rd) = (X(rs2) != 0) ? 0 : X(rs1)",) * 2,
    "add.uw": ("X(rd) = zext(X(rs1)[31:0]) + X(rs2)",) * 2,
    "andn": ("X(rd) = X(rs1) & ~X(rs2)",) * 2,
    "orn": ("X(rd) = X(rs1) | ~X(rs2)",) * 2,
    "xnor": ("X(rd) = ~(X(rs1) ^ X(rs2))",) * 2,
    "orc.b": ("将源寄存器中每个非零字节变为 0xff，零字节保持零。", "Replace each nonzero source byte with 0xff and retain zero bytes."),
    "rev8": ("反转源寄存器的字节顺序。", "Reverse the byte order of the source register."),
    "brev8": ("分别反转源寄存器中每个字节的位顺序。", "Reverse the bit order independently within each source byte."),
    "pack": ("将 rs2、rs1 的低半寄存器依次拼接为 rd。", "Concatenate the low halves of rs2 and rs1 into rd, with rs1 in the low half."),
    "packh": ("X(rd) = zext({X(rs2)[7:0], X(rs1)[7:0]})",) * 2,
    "packw": ("X(rd) = sext({X(rs2)[15:0], X(rs1)[15:0]})",) * 2,
    "zip": ("将源寄存器上下半部的位交错放入奇偶位。", "Interleave upper-half and lower-half source bits into odd and even destination bits."),
    "unzip": ("将源寄存器奇数位和偶数位分别聚集到上下半部。", "Gather odd and even source bits into the upper and lower destination halves."),
    "clmul": ("计算无进位乘积，保留低 XLEN 位。", "Compute the low XLEN bits of the carry-less product."),
    "clmulh": ("计算无进位乘积，保留高 XLEN 位。", "Compute the high XLEN bits of the carry-less product."),
    "clmulr": ("计算无进位乘积并取位 [2*XLEN-2:XLEN-1]。", "Return bits [2*XLEN-2:XLEN-1] of the carry-less product."),
    "xperm4": ("以 rs2 的各半字节为索引查找 rs1 的半字节，越界返回零。", "Look up rs1 nibbles using rs2 nibble indices, returning zero for out-of-range indices."),
    "xperm8": ("以 rs2 的各字节为索引查找 rs1 的字节，越界返回零。", "Look up rs1 bytes using rs2 byte indices, returning zero for out-of-range indices."),
    "slli.uw": ("X(rd) = zext(X(rs1)[31:0]) << shamt",) * 2,
    "cm.push": ("分配栈帧并保存 ra 和选定的 s 寄存器。", "Allocate a stack frame and save ra and the selected saved registers."),
    "cm.pop": ("恢复 ra 和选定的 s 寄存器并释放栈帧。", "Restore ra and the selected saved registers, then deallocate the stack frame."),
    "cm.popret": ("恢复寄存器、释放栈帧并返回 ra。", "Restore registers, deallocate the stack frame and return through ra."),
    "cm.popretz": ("恢复寄存器、释放栈帧，将 a0 置零并返回 ra。", "Restore registers, deallocate the stack frame, clear a0 and return through ra."),
    "cm.mvsa01": ("将 a0/a1 分别复制到两个选定的 s0–s7 寄存器。", "Copy a0/a1 into two selected s0–s7 registers."),
    "cm.mva01s": ("将两个选定的 s0–s7 寄存器分别复制到 a0/a1。", "Copy two selected s0–s7 registers into a0/a1."),
    "cm.jt": ("通过 jvt 索引读取目标地址并跳转，不写链接寄存器。", "Jump through the jvt-indexed table entry without writing a link register."),
    "cm.jalt": ("通过 jvt 索引读取目标地址并跳转，同时将返回地址写入 ra。", "Jump through the jvt-indexed table entry and write the return address to ra."),
    "sspush": ("将 x1 或 x5 压入影子栈并更新 ssp。", "Push x1 or x5 onto the shadow stack and update ssp."),
    "sspopchk": ("弹出影子栈并与 x1 或 x5 比较，不匹配时触发软件检查异常。", "Pop the shadow stack and compare with x1 or x5, raising a software-check exception on mismatch."),
    "ssrdp": ("读取影子栈指针 ssp，未启用时读取零。", "Read the shadow-stack pointer ssp, or zero when shadow stacks are disabled."),
    "ssamoswap.w": ("在影子栈内原子交换一个字，旧值符号扩展后写入 rd。", "Atomically exchange a word in shadow-stack memory and sign-extend the old value into rd."),
    "ssamoswap.d": ("在影子栈内原子交换一个双字并将旧值写入 rd。", "Atomically exchange a doubleword in shadow-stack memory and return the old value in rd."),
    "lpad": ("校验间接控制流目标的着陆垫及标签。", "Validate the landing pad and label of an indirect control-flow target."),
    "c.sspush": ("将 x1 压入影子栈并更新 ssp。", "Push x1 onto the shadow stack and update ssp."),
    "c.sspopchk": ("弹出影子栈并与 x5 比较，检查返回地址完整性。", "Pop the shadow stack and compare with x5 to check return-address integrity."),
}


def summary(name):
    if name in TEXT:
        return TEXT[name]
    if name.startswith("mop."):
        return ("当前定义为将 rd 写零的可重定义操作。", "Currently write zero to rd; the operation may be redefined by another extension.")
    if re.fullmatch(r"rd(cycle|time|instret)h?",name):
        root=name[2:].rstrip("h")
        return (f"读取 {root} 计数器{'的高 32 位' if name.endswith('h') else ''}。", f"Read {'the high 32 bits of ' if name.endswith('h') else ''}the {root} counter.")
    if name.startswith("c.mop."):
        return ("当前定义为空操作的压缩可重定义操作。", "A compressed may-be-operation currently defined as a no-op.")
    if name.startswith(("ntl.", "c.ntl.")):
        meaning = {"p1": ("最近一级私有缓存", "the innermost private cache"), "pall": ("所有私有缓存", "all private caches"), "s1": ("最近一级共享缓存", "the innermost shared cache"), "all": ("所有缓存", "all caches")}[name.split(".")[-1]]
        return (f"提示下一次显式访存的数据在{meaning[0]}中缺少时间局部性。", f"Hint that the next explicit memory access lacks temporal locality in {meaning[1]}.")
    # Compressed instructions have implicit operands, so use their own summaries.
    if name.startswith("c."):
        op = name[2:]
        special = {
            "addi4spn": ("X(rd′) = sp + zext(nzuimm)",) * 2,
            "addi16sp": ("sp += sext(nzimm)",) * 2,
            "li": ("X(rd) = sext(imm)",) * 2,
            "lui": ("X(rd) = sext(nzimm6 << 12)",) * 2,
            "mv": ("X(rd) = X(rs2)",) * 2,
            "jr": ("PC = X(rs1) & ~1",) * 2,
            "jalr": ("x1 = PC + 2; PC = X(rs1) & ~1",) * 2,
            "j": ("PC += sext(offset)",) * 2,
            "jal": ("x1 = PC + 2; PC += sext(offset)",) * 2,
            "beqz": ("if X(rs1′) == 0: PC += sext(offset)",) * 2,
            "bnez": ("if X(rs1′) != 0: PC += sext(offset)",) * 2,
            "nop": ("空操作，不改变架构状态（除正常推进 PC 和计数器）。", "No operation, apart from normal PC and counter advancement."),
            "ebreak": TEXT["ebreak"],
            "not": ("X(rd′) = ~X(rd′)",) * 2,
        }
        if op in special:
            return special[op]
        load = re.fullmatch(r"(f?)(l[bdwhq]u?)(sp)?", op)
        store = re.fullmatch(r"(f?)(s[bdwhq])(sp)?", op)
        if load or store:
            m = load or store
            operation = "加载" if load else "存储"
            action = "Load" if load else "Store"
            base = "sp" if m[3] else "X(rs1′)"
            width = {"b": 8, "h": 16, "w": 32, "d": 64, "q": 128}[m[2][1]]
            extra = "浮点" if m[1] else "无符号" if m[2].endswith("u") else ""
            en_extra = "floating-point " if m[1] else "unsigned " if m[2].endswith("u") else ""
            return (f"从 {base} + uimm 地址{operation} {width} 位{extra}数据。", f"{action} {width}-bit {en_extra}data at {base} + uimm.")
        if op in {"addi", "addiw", "add", "sub", "addw", "subw", "and", "andi", "or", "xor", "slli", "srli", "srai", "mul"}:
            word = op.endswith("w")
            root = op[:-1] if word else op
            rhs = "sext(imm)" if root in {"addi", "andi"} else "shamt" if root in {"slli", "srli", "srai"} else "X(rs2)"
            operator = {"add": "+", "addi": "+", "sub": "-", "and": "&", "andi": "&", "or": "|", "xor": "^", "slli": "<<", "srli": ">>u", "srai": ">>s", "mul": "*"}[root]
            expr = f"X(rd) {operator} {rhs}"
            text = f"X(rd) = sext32({expr})" if word else f"X(rd) = {expr}"
            return text, text
        if op.startswith(("sext.", "zext.")):
            ext, size = op.split(".")
            text = f"X(rd′) = {ext}(X(rd′)[{ {'b':7,'h':15,'w':31}[size]}:0])"
            return text, text
    branches = {"beq": "==", "bne": "!=", "blt": "<s", "bge": ">=s", "bltu": "<u", "bgeu": ">=u"}
    if name in branches:
        text = f"if X(rs1) {branches[name]} X(rs2): PC += sext(offset)"
        return text, text
    if name in {"lb", "lh", "lw", "ld", "lbu", "lhu", "lwu", "sb", "sh", "sw", "sd"}:
        size = {"b": 8, "h": 16, "w": 32, "d": 64}[name[1]]
        if name.startswith("l"):
            text = f"X(rd) = {'zext' if name.endswith('u') else 'sext'}(M{size}[X(rs1) + sext(imm)])"
        else:
            text = f"M{size}[X(rs1) + sext(imm)] = X(rs2)[{size - 1}:0]"
        return text, text
    if name.startswith("hlv") or name.startswith("hsv"):
        load = name.startswith("hlv")
        return (f"按虚拟机的地址转换与权限{'加载' if load else '存储'}指定宽度数据{'（HLVX 使用执行权限）' if name.startswith('hlvx') else ''}。", f"{'Load' if load else 'Store'} data using virtual-machine translation and permissions{' (HLVX checks execute permission)' if name.startswith('hlvx') else ''}.")
    if name.startswith(("lr.", "sc.")):
        if name.startswith("lr."):
            return ("从 rs1 地址加载数据并建立保留集，字结果符号扩展。", "Load at rs1 and establish a reservation, sign-extending a word result.")
        return ("保留集允许时条件存储；rd=0 表示成功，非零表示失败。", "Store conditionally if the reservation permits; rd is zero on success and nonzero on failure.")
    if name.startswith("amo"):
        op = name.split(".")[0][3:]
        if op == "cas":
            return ("原子比较内存与 rd，相等时写入 rs2，并将内存旧值写回 rd。", "Atomically compare memory with rd, store rs2 on equality, and return the old memory value in rd.")
        action = {"swap": ("交换", "exchange"), "add": ("加", "add"), "and": ("按位与", "bitwise AND"), "or": ("按位或", "bitwise OR"), "xor": ("按位异或", "bitwise XOR"), "min": ("有符号最小值", "signed minimum"), "max": ("有符号最大值", "signed maximum"), "minu": ("无符号最小值", "unsigned minimum"), "maxu": ("无符号最大值", "unsigned maximum")}[op]
        return (f"原子执行{action[0]}并将内存旧值写入 rd，窄结果符号扩展。", f"Atomically perform {action[1]} and return the old memory value in rd, sign-extending narrow results.")
    if name.startswith("csr"):
        immediate = name.endswith("i")
        op = name.rstrip("i")
        source = "zimm" if immediate else "X(rs1)"
        action = {"csrrw": ("写入", "replace with"), "csrrs": ("按位置位", "set bits from"), "csrrc": ("按位清零", "clear bits from")}[op]
        return (f"读取 CSR 旧值到 rd，并用 {source} {action[0]}；读写抑制遵循 rd/rs1/zimm 规则。", f"Return the old CSR in rd and {action[1]} {source}; suppress reads/writes according to rd/rs1/zimm rules.")
    simple = {"add": "+", "sub": "-", "and": "&", "or": "|", "xor": "^", "sll": "<<", "srl": ">>u", "sra": ">>s", "slt": "<s", "sltu": "<u"}
    word = name in {"addw", "subw", "sllw", "srlw", "sraw", "addiw", "slliw", "srliw", "sraiw"}
    root = name[:-1] if word else name
    immediate = root in {"addi", "andi", "ori", "xori", "slti", "sltiu", "slli", "srli", "srai"}
    base = root.replace("i", "") if immediate else root
    if base in simple:
        rhs = "shamt" if immediate and base.startswith("s") and base not in {"slt", "sltu"} else "sext(imm)" if immediate else "X(rs2)"
        if base in {"sll", "srl", "sra"} and not immediate:
            rhs += " & " + ("31" if word else "(XLEN - 1)")
        lhs=("signed32(X(rs1))" if base=="sra" else "low32(X(rs1))") if word else "X(rs1)"
        text = f"{lhs} {simple[base]} ({rhs})"
        text = f"X(rd) = sext32({text})" if word else f"X(rd) = {text}"
        return text, text
    if name in {"mul", "mulh", "mulhu", "mulhsu", "mulw", "div", "divu", "rem", "remu", "divw", "divuw", "remw", "remuw"}:
        if name.startswith("mulh"):
            signed = {"mulh": "有符号×有符号", "mulhu": "无符号×无符号", "mulhsu": "有符号×无符号"}[name]
            en = {"mulh": "signed × signed", "mulhu": "unsigned × unsigned", "mulhsu": "signed × unsigned"}[name]
            return (f"返回{signed}乘积的高 XLEN 位。", f"Return the high XLEN bits of the {en} product.")
        op = "*" if name.startswith("mul") else "div" if name.startswith("div") else "rem"
        signed = "unsigned" if "u" in name and not name.startswith("mul") else "signed"
        cast=signed+("32" if name.endswith('w') else "")
        text = f"X(rd) = {'sext32(' if name.endswith('w') else ''}{cast}(X(rs1)) {op} {cast}(X(rs2)){')' if name.endswith('w') else ''}"
        if not name.startswith("mul"):
            return (text + "；除零及溢出按规范定义返回值。", text + "; division by zero and overflow use the specified architectural results.")
        return text, text
    if re.fullmatch(r"b(clr|ext|inv|set)i?", name):
        index = "shamt" if name.endswith("i") else "X(rs2) & (XLEN - 1)"
        op = name[1:].rstrip("i")
        text = {"clr": f"X(rs1) & ~(1 << ({index}))", "ext": f"(X(rs1) >> ({index})) & 1", "inv": f"X(rs1) ^ (1 << ({index}))", "set": f"X(rs1) | (1 << ({index}))"}[op]
        return ("X(rd) = " + text,) * 2
    if re.fullmatch(r"(clz|ctz|cpop)w?", name):
        action = {"clz": ("前导零", "leading zero"), "ctz": ("尾随零", "trailing zero"), "cpop": ("置位", "set")}[name.rstrip("w")]
        width = "低 32" if name.endswith("w") else "XLEN"
        return (f"统计源寄存器{width}位中的{action[0]}位数。", f"Count {action[1]} bits in the {'low 32' if name.endswith('w') else 'XLEN'} source bits.")
    if name in {"min", "minu", "max", "maxu"}:
        text = f"X(rd) = {name[:3]}_{'u' if name.endswith('u') else 's'}(X(rs1), X(rs2))"
        return text, text
    if re.fullmatch(r"ro[lr]i?w?", name):
        right = "r" in name[2:3]
        return (f"将源寄存器{'低 32 位' if name.endswith('w') else ''}循环{'右' if right else '左'}移，{'字结果符号扩展。' if name.endswith('w') else '移位量取有效低位。'}", f"Rotate {'the low source word' if name.endswith('w') else 'the source'} {'right' if right else 'left'} by the masked shift amount{' and sign-extend the word result' if name.endswith('w') else ''}.")
    if name.startswith(("sext.", "zext.")):
        ext, size = name.split(".")
        text = f"X(rd) = {ext}(X(rs1)[{ {'b':7,'h':15}[size]}:0])"
        return text, text
    if re.fullmatch(r"sh[123]add(?:\.uw)?", name):
        src = "zext(X(rs1)[31:0])" if name.endswith(".uw") else "X(rs1)"
        return (f"X(rd) = ({src} << {name[2]}) + X(rs2)",) * 2
    if name.startswith(("aes", "sha", "sm3", "sm4")):
        return crypto_summary(name)
    if name.startswith("v"):
        return vector_summary(name)
    if name.startswith("f"):
        return float_summary(name)
    raise ValueError(f"Missing reviewed summary: {name}")


def crypto_summary(name):
    if name.startswith("aes"):
        action = {"dsi": ("解密末轮", "final decryption round"), "dsmi": ("解密中间轮", "middle decryption round"), "esi": ("加密末轮", "final encryption round"), "esmi": ("加密中间轮", "middle encryption round"), "ds": ("解密末轮", "final decryption round"), "dsm": ("解密中间轮", "middle decryption round"), "es": ("加密末轮", "final encryption round"), "esm": ("加密中间轮", "middle encryption round"), "im": ("逆 MixColumns 变换", "inverse MixColumns transform"), "ks1i": ("带轮常量的密钥扩展步骤", "key-schedule step with round constant"), "ks2": ("密钥扩展异或步骤", "key-schedule XOR step")}[name[5:]]
        return (f"执行 AES {action[0]}，用于 RV{name[3:5]}。", f"Perform the AES {action[1]} for RV{name[3:5]}.")
    if name.startswith("sha"):
        m = re.fullmatch(r"sha(256|512)(sig[01]|sum[01])([hlr]?)", name)
        kind = "σ" if m[2].startswith("sig") else "Σ"
        part = {"h": ("高半部", "high half"), "l": ("低半部", "low half"), "r": ("RV32 分步结果", "RV32 split-register result"), "": ("结果", "result")}[m[3]]
        return (f"计算 SHA-{m[1]} 的 {kind}{m[2][-1]} 变换{part[0]}。", f"Compute the {part[1]} of SHA-{m[1]} {kind}{m[2][-1]}.")
    if name.startswith("sm3p"):
        return (f"计算 SM3 的 P{name[-1]} 置换并符号扩展 32 位结果。", f"Compute SM3 permutation P{name[-1]} and sign-extend the 32-bit result.")
    return ("执行 SM4 单字节轮变换，辅助加解密或密钥扩展。", "Perform an SM4 byte round transform for encryption/decryption or key expansion.") if name == "sm4ed" else ("执行 SM4 密钥扩展的单字节变换。", "Perform the SM4 key-schedule byte transform.")


def float_summary(name):
    parts = name.split(".")
    op = parts[0]
    if op in {"flh", "flw", "fld", "flq", "fsh", "fsw", "fsd", "fsq"}:
        size = {"h":16,"w":32,"d":64,"q":128}[op[-1]]
        return (f"{'加载' if op[1] == 'l' else '存储'} {size} 位浮点寄存器位模式，地址为 X(rs1)+sext(imm)。", f"{'Load' if op[1] == 'l' else 'Store'} a {size}-bit FP register bit pattern at X(rs1)+sext(imm).")
    if op == "fcvt":
        return (f"将 {parts[2].upper()} 格式转换为 {parts[1].upper()} 格式，按适用舍入模式处理。", f"Convert {parts[2].upper()} to {parts[1].upper()} using the applicable rounding mode.")
    if op == "fcvtmod":
        return ("将双精度数向零截断并模 2^32 转为符号扩展整数，NaN/无穷返回零。", "Truncate a double toward zero, reduce modulo 2^32 and sign-extend; NaN/infinity return zero.")
    if op in {"fmv", "fmvh", "fmvp"}:
        detail = {"fmv": ("复制位模式，窄浮点到整数的结果符号扩展。", "Copy the bit pattern, sign-extending narrow FP-to-integer results."), "fmvh": ("将浮点寄存器高半部复制到整数寄存器。", "Copy the high half of an FP register into an integer register."), "fmvp": ("将两个整数寄存器的位模式拼接到浮点寄存器。", "Concatenate two integer-register bit patterns into an FP register.")}
        return detail[op]
    formulas = {"fadd":"a + b", "fsub":"a - b", "fmul":"a * b", "fdiv":"a / b", "fsqrt":"sqrt(a)", "fmadd":"a*b + c", "fmsub":"a*b - c", "fnmsub":"-(a*b) + c", "fnmadd":"-(a*b) - c"}
    if op in formulas:
        text = f"F(rd) = round_{parts[-1].upper()}({formulas[op]})"
        return text, text
    if op in {"fmin", "fmax", "fminm", "fmaxm"}:
        nan = "任一输入为 NaN 时返回规范 NaN" if op.endswith("m") else "仅一个输入为 NaN 时返回数值输入"
        en = "propagate a canonical NaN if either input is NaN" if op.endswith("m") else "return the numeric input if only one input is NaN"
        return (f"取浮点{'最小' if 'min' in op else '最大'}值；{nan}。", f"Select the FP {'minimum' if 'min' in op else 'maximum'}; {en}.")
    if op in {"feq", "flt", "fle", "fltq", "fleq"}:
        quiet = "，仅信令 NaN 触发无效操作标志" if op in {"feq", "fltq", "fleq"} else "，任一 NaN 触发无效操作标志"
        relation = "==" if op == "feq" else "<=" if op.startswith("fle") else "<"
        return (f"浮点比较 a {relation} b，成立写 1，否则写 0{quiet}。", f"Set rd to (a {relation} b); {'only signaling' if op in {'feq','fltq','fleq'} else 'any'} NaNs raise invalid.")
    if op == "fclass":
        return ("将浮点数分类为无穷、正规、次正规、零或 NaN，返回十位分类掩码。", "Classify the FP value as infinity, normal, subnormal, zero or NaN in a ten-bit mask.")
    if op in {"fsgnj", "fsgnjn", "fsgnjx"}:
        signs = {"fsgnj": "sign(b)", "fsgnjn": "~sign(b)", "fsgnjx": "sign(a) XOR sign(b)"}
        text = f"F(rd) = magnitude(a) with sign = {signs[op]}"
        return text, text
    if op == "fli":
        return ("按 rs1 的五位索引加载规范表中的浮点常数，不修改异常标志。", "Load the FP constant indexed by the five-bit rs1 field without changing exception flags.")
    if op in {"fround", "froundnx"}:
        return (f"将浮点输入舍入为浮点格式的整数值，{'可设置' if op.endswith('nx') else '不设置'}不精确标志。", f"Round the FP input to an integral FP value, {'allowing' if op.endswith('nx') else 'suppressing'} the inexact flag.")
    raise ValueError(f"Missing floating-point summary: {name}")


VECTOR_TEXT = {
    "vsetvli": ("按立即数 vtype 与 AVL 设置向量类型和 vl，并将 vl 写入 rd。", "Set vtype and vl from immediate vtype and AVL, returning vl in rd."),
    "vsetivli": ("按立即数 AVL 和 vtype 设置向量配置，并将 vl 写入 rd。", "Set vector configuration from immediate AVL and vtype, returning vl in rd."),
    "vsetvl": ("按 rs2 中的 vtype 与 AVL 设置向量配置，并将 vl 写入 rd。", "Set vector configuration from vtype in rs2 and AVL, returning vl in rd."),
    "vcompress": ("按 vs1 掩码压紧 vs2 的选中元素写入 vd。", "Compress selected vs2 elements into vd using the vs1 mask."),
    "vid": ("将活动元素索引写入 vd。", "Write active element indices to vd."),
    "viota": ("统计源掩码中位于各元素之前的置位数。", "Count source-mask set bits preceding each element."),
    "vfirst": ("返回首个活动置位掩码索引，未找到时返回 -1。", "Return the first active set-mask index, or -1 if none exists."),
    "vcpop": ("统计源掩码中的活动置位数。", "Count active set bits in the source mask."),
    "vmsbf": ("生成首个置位之前为 1 的前缀掩码。", "Generate a prefix mask with bits set before the first source set bit."),
    "vmsof": ("生成仅首个置位位置为 1 的掩码。", "Generate a mask containing only the first source set bit."),
    "vmsif": ("生成截至首个置位（含该位）为 1 的前缀掩码。", "Generate a prefix mask through and including the first source set bit."),
    "vfsqrt": ("逐活动元素计算浮点平方根。", "Compute FP square roots for active elements."),
    "vfrsqrt7": ("逐活动元素计算约 7 位有效精度的浮点倒平方根估计。", "Estimate FP reciprocal square roots with about seven significant bits."),
    "vfrec7": ("逐活动元素计算约 7 位有效精度的浮点倒数估计。", "Estimate FP reciprocals with about seven significant bits."),
    "vfclass": ("逐活动元素生成浮点十位分类掩码。", "Generate ten-bit FP classification masks for active elements."),
    "vbrev": ("逐元素反转位顺序。", "Reverse the bits of each element."),
    "vbrev8": ("逐字节反转位顺序。", "Reverse bits within each byte."),
    "vrev8": ("逐元素反转字节顺序。", "Reverse the byte order within each element."),
    "vclz": ("逐元素统计前导零。", "Count leading zero bits in each element."),
    "vctz": ("逐元素统计尾随零。", "Count trailing zero bits in each element."),
    "vclmul": ("逐元素计算无进位乘积的低半部。", "Compute the low halves of element-wise carry-less products."),
    "vclmulh": ("逐元素计算无进位乘积的高半部。", "Compute the high halves of element-wise carry-less products."),
    "vghsh": ("对 128 位元素组执行 GHASH 异或及有限域乘法。", "Perform GHASH XOR and Galois-field multiplication on 128-bit element groups."),
    "vgmul": ("对 128 位元素组执行 GHASH 有限域乘法。", "Perform GHASH Galois-field multiplication on 128-bit element groups."),
    "vaesdf": ("对元素组执行 AES 解密末轮。", "Perform the final AES decryption round on element groups."),
    "vaesdm": ("对元素组执行 AES 解密中间轮。", "Perform an AES middle decryption round on element groups."),
    "vaesef": ("对元素组执行 AES 加密末轮。", "Perform the final AES encryption round on element groups."),
    "vaesem": ("对元素组执行 AES 加密中间轮。", "Perform an AES middle encryption round on element groups."),
    "vaeskf1": ("按轮立即数生成 AES-128 下一轮密钥。", "Generate the next AES-128 round key using the round immediate."),
    "vaeskf2": ("按轮立即数生成 AES-256 下一轮密钥。", "Generate the next AES-256 round key using the round immediate."),
    "vaesz": ("将 AES 状态与标量轮密钥异或。", "XOR AES state element groups with the scalar round key."),
    "vsha2ch": ("对 SHA-2 元素组执行两轮压缩并写入高半部状态。", "Perform two SHA-2 compression rounds and write the high-half state."),
    "vsha2cl": ("对 SHA-2 元素组执行两轮压缩并写入低半部状态。", "Perform two SHA-2 compression rounds and write the low-half state."),
    "vsha2ms": ("生成 SHA-2 消息扩展字。", "Generate SHA-2 message-schedule words."),
    "vsm3c": ("按轮立即数执行两轮 SM3 压缩。", "Perform two SM3 compression rounds selected by the round immediate."),
    "vsm3me": ("生成 SM3 消息扩展字。", "Generate SM3 message-schedule words."),
    "vsm4k": ("按轮立即数生成四个 SM4 轮密钥。", "Generate four SM4 round keys selected by the round immediate."),
    "vsm4r": ("对元素组执行四轮 SM4 加解密变换。", "Perform four SM4 encryption/decryption rounds on element groups."),
    "vfwmaccbf16": ("以 BF16 输入相乘并融合累加至 FP32 元素。", "Fused-multiply BF16 inputs and accumulate into FP32 elements."),
}


def vector_summary(name):
    root = name.split(".")[0]
    if root == "vcpop" and name.endswith(".v"):
        return ("逐元素统计置位数。", "Count set bits in each element.")
    if root in VECTOR_TEXT:
        return VECTOR_TEXT[root]
    if root.startswith(("vl", "vs")) and re.search(r"(?:e|ei)\d+(?:ff)?$", root):
        load = root.startswith("vl")
        fault = root.endswith("ff")
        width=re.search(r"(?:e|ei)(\d+)",root)[1]
        if "ei" in root:
            ordered="ox" in root
            return (f"使用 {width} 位{'有序' if ordered else '无序'}字节偏移索引{'加载' if load else '存储'} SEW 位向量元素。", f"{'Load' if load else 'Store'} SEW-bit vector elements using {width}-bit {'ordered' if ordered else 'unordered'} byte-offset indices.")
        return (f"{'加载' if load else '存储'}指定宽度的向量元素{'，首元素之后的异常可缩短 vl' if fault else ''}。", f"{'Load' if load else 'Store'} vector elements at the encoded width{' with fault-only-first behavior' if fault else ''}.")
    if re.fullmatch(r"(?:vl[1248]re(?:8|16|32|64)|vs[1248]r)", root):
        return ("传送整组向量寄存器，传送长度由寄存器组大小决定。", "Transfer a whole vector-register group, with length determined by the group size.")
    if root in {"vlm", "vsm"}:
        return ("按压缩字节格式传送向量掩码。", "Transfer vector-mask bits in packed-byte form.")
    if re.fullmatch(r"vmv[1248]r", root):
        return ("复制整个向量寄存器组。", "Copy an entire vector-register group.")
    if root in {"vmv", "vfmv"}:
        return ("在标量与向量间传送或广播元素，具体形式决定方向。", "Move or broadcast elements between scalar and vector operands as selected by the form.")
    if root in {"vmerge", "vfmerge"}:
        return ("按 v0 掩码逐元素选择两个输入。", "Select between two input elements using the v0 mask.")
    if root.startswith(("vfcvt", "vfwcvt", "vfncvt")):
        return ("按指令形式转换整数或浮点元素，并执行指定的扩宽、缩窄与舍入。", "Convert integer/FP elements with the widening, narrowing and rounding specified by the instruction form.")
    if root in {"vzext", "vsext"}:
        return ("逐元素" + ("零" if root == "vzext" else "符号") + "扩展，源宽度由 vf2/vf4/vf8 指定。", "Extend elements with " + ("zeros" if root == "vzext" else "sign bits") + ", using the vf2/vf4/vf8 source-width factor.")
    # Stem descriptions intentionally preserve saturation/rounding/reduction and
    # accumulator distinctions rather than labelling every opcode 'vector op'.
    mapping = {
        "vadd":("加法","addition"), "vsub":("减法","subtraction"), "vrsub":("反向减法","reverse subtraction"),
        "vminu":("无符号最小值","unsigned minimum"), "vmin":("有符号最小值","signed minimum"), "vmaxu":("无符号最大值","unsigned maximum"), "vmax":("有符号最大值","signed maximum"),
        "vand":("按位与","bitwise AND"), "vor":("按位或","bitwise OR"), "vxor":("按位异或","bitwise XOR"), "vandn":("与取反操作数的按位与","bitwise AND with inverted operand"),
        "vsll":("逻辑左移","logical left shift"), "vsrl":("逻辑右移","logical right shift"), "vsra":("算术右移","arithmetic right shift"), "vrol":("循环左移","rotate left"), "vror":("循环右移","rotate right"), "vwsll":("扩宽逻辑左移","widening logical left shift"),
        "vadc":("带进位加法（v0 提供进位输入）","addition with carry input from v0"), "vmadc":("加法进位掩码生成","addition carry-mask generation"), "vsbc":("带借位减法（v0 提供借位输入）","subtraction with borrow input from v0"), "vmsbc":("减法借位掩码生成","subtraction borrow-mask generation"),
        "vsaddu":("无符号饱和加法","unsigned saturating addition"), "vsadd":("有符号饱和加法","signed saturating addition"), "vssubu":("无符号饱和减法","unsigned saturating subtraction"), "vssub":("有符号饱和减法","signed saturating subtraction"),
        "vaaddu":("无符号舍入平均加法","unsigned rounded averaging addition"), "vaadd":("有符号舍入平均加法","signed rounded averaging addition"), "vasubu":("无符号舍入平均减法","unsigned rounded averaging subtraction"), "vasub":("有符号舍入平均减法","signed rounded averaging subtraction"),
        "vssrl":("带舍入的逻辑右移","rounded logical right shift"), "vssra":("带舍入的算术右移","rounded arithmetic right shift"), "vnsrl":("缩窄逻辑右移","narrowing logical right shift"), "vnsra":("缩窄算术右移","narrowing arithmetic right shift"), "vnclipu":("无符号舍入饱和缩窄","unsigned rounded saturating narrowing"), "vnclip":("有符号舍入饱和缩窄","signed rounded saturating narrowing"),
        "vmul":("乘积低半部","low-half multiplication"), "vmulh":("有符号乘积高半部","signed high-half multiplication"), "vmulhu":("无符号乘积高半部","unsigned high-half multiplication"), "vmulhsu":("有符号×无符号乘积高半部","signed-by-unsigned high-half multiplication"), "vsmul":("带舍入和饱和的分数乘法","rounded saturating fractional multiplication"),
        "vdiv":("有符号除法","signed division"), "vdivu":("无符号除法","unsigned division"), "vrem":("有符号余数","signed remainder"), "vremu":("无符号余数","unsigned remainder"),
        "vmadd":("乘加：vd = vs1*vd + vs2","multiply-add: vd = vs1*vd + vs2"), "vnmsub":("负乘减：vd = -(vs1*vd) + vs2","negated multiply-subtract: vd = -(vs1*vd) + vs2"), "vmacc":("乘累加：vd += vs1*vs2","multiply-accumulate: vd += vs1*vs2"), "vnmsac":("负乘累加：vd -= vs1*vs2","negated multiply-accumulate: vd -= vs1*vs2"),
        "vrgather":("按索引聚集元素，越界索引返回零","indexed gather, returning zero for out-of-range indices"), "vrgatherei16":("按 16 位索引聚集元素","gather using 16-bit indices"),
        "vslideup":("向高索引滑动元素","slide toward higher indices"), "vslidedown":("向低索引滑动元素","slide toward lower indices"), "vslide1up":("向高索引滑动一位并插入标量","slide up one element and insert a scalar"), "vslide1down":("向低索引滑动一位并插入标量","slide down one element and insert a scalar"),
        "vfslide1up":("浮点元素向高索引滑动一位并插入标量","FP slide up one element and insert a scalar"), "vfslide1down":("浮点元素向低索引滑动一位并插入标量","FP slide down one element and insert a scalar"),
    }
    for prefix, cn, en in [("vwaddu","无符号扩宽加法","unsigned widening addition"),("vwadd","有符号扩宽加法","signed widening addition"),("vwsubu","无符号扩宽减法","unsigned widening subtraction"),("vwsub","有符号扩宽减法","signed widening subtraction"),("vwmulu","无符号扩宽乘法","unsigned widening multiplication"),("vwmulsu","有符号×无符号扩宽乘法","signed-by-unsigned widening multiplication"),("vwmul","有符号扩宽乘法","signed widening multiplication"),("vwmaccu","无符号扩宽乘累加","unsigned widening multiply-accumulate"),("vwmaccsu","有符号×无符号扩宽乘累加","signed-by-unsigned widening multiply-accumulate"),("vwmaccus","无符号×有符号扩宽乘累加","unsigned-by-signed widening multiply-accumulate"),("vwmacc","有符号扩宽乘累加","signed widening multiply-accumulate")]:
        mapping[prefix]=(cn,en)
    relation = {"eq":"==", "ne":"!=", "ltu":"<u", "lt":"<s", "leu":"<=u", "le":"<=s", "gtu":">u", "gt":">s"}
    if root.startswith("vms") and root[3:] in relation:
        return (f"逐元素比较 vs2 {relation[root[3:]]} src1，将结果写入掩码。", f"Compare elements using vs2 {relation[root[3:]]} src1 and write mask bits.")
    if root in {"vmandn","vmand","vmor","vmxor","vmorn","vmnand","vmnor","vmxnor"}:
        text={"vmandn":"vs2 & ~vs1","vmand":"vs2 & vs1","vmor":"vs2 | vs1","vmxor":"vs2 ^ vs1","vmorn":"vs2 | ~vs1","vmnand":"~(vs2 & vs1)","vmnor":"~(vs2 | vs1)","vmxnor":"~(vs2 ^ vs1)"}[root]
        return (f"vd.mask[i] = {text}",) * 2
    if root.startswith(("vred", "vwred", "vfred", "vfwred")):
        return reduction_summary(root)
    if root.startswith("vf"):
        fp = root[2:]
        formulas = {"add":"vs2+src1","sub":"vs2-src1","rsub":"src1-vs2","mul":"vs2*src1","div":"vs2/src1","rdiv":"src1/vs2","madd":"src1*vd+vs2","nmadd":"-(src1*vd)-vs2","msub":"src1*vd-vs2","nmsub":"-(src1*vd)+vs2","macc":"src1*vs2+vd","nmacc":"-(src1*vs2)-vd","msac":"src1*vs2-vd","nmsac":"-(src1*vs2)+vd","wadd":"widen(vs2)+widen(src1)","wsub":"widen(vs2)-widen(src1)","wmul":"widen(vs2)*widen(src1)","wmacc":"widen(src1)*widen(vs2)+vd","wnmacc":"-(widen(src1)*widen(vs2))-vd","wmsac":"widen(src1)*widen(vs2)-vd","wnmsac":"-(widen(src1)*widen(vs2))+vd"}
        if fp in formulas:
            return (f"逐活动元素计算浮点 vd = round({formulas[fp]})。", f"For active FP elements, vd = round({formulas[fp]}).")
        if fp in {"min","max","sgnj","sgnjn","sgnjx"}:
            a,b=float_summary("f"+fp+".s")
            for old,new in [(r'F\(rd\)','vd'),(r'\ba\b','vs2'),(r'\bb\b','src1')]:
                a=re.sub(old,new,a);b=re.sub(old,new,b)
            return "逐活动元素："+a, "For active elements: "+b
    if root.startswith("vmf"):
        relation={"vmfeq":"==","vmfne":"!=","vmflt":"<","vmfle":"<=","vmfgt":">","vmfge":">="}[root]
        return (f"逐元素进行浮点 vs2 {relation} src1 比较并写入掩码。", f"Compare FP elements using vs2 {relation} src1 and write mask bits.")
    if root in mapping:
        cn,en=mapping[root]
        return f"逐活动元素执行{cn}。", f"Perform {en} for active elements."
    raise ValueError(f"Missing vector summary: {name}")


def reduction_summary(root):
    fp = root.startswith(("vfred", "vfwred"))
    widening = root.startswith(("vwred", "vfwred"))
    op=re.sub(r"^v(?:fw|f|w)?red", "", root)
    action={"sum":("求和","sum"),"sumu":("无符号求和","unsigned sum"),"usum":("无序求和","unordered sum"),"osum":("有序求和","ordered sum"),"and":("按位与","bitwise AND"),"or":("按位或","bitwise OR"),"xor":("按位异或","bitwise XOR"),"min":("最小值","minimum"),"max":("最大值","maximum"),"minu":("无符号最小值","unsigned minimum"),"maxu":("无符号最大值","unsigned maximum")}[op]
    return (f"以 vs1[0] 为初值，对活动{'浮点' if fp else ''}元素进行{'扩宽' if widening else ''}{action[0]}归约。", f"Reduce active {'FP ' if fp else ''}elements by {'widening ' if widening else ''}{action[1]}, seeded by vs1[0].")
