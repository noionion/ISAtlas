package dev.isatlas.core

data class InstructionUsageExample(val code: String, val explanation: String, val explanationEn: String, val word: Long? = null) {
    val copyText get() = "$code\n# $explanation"
}

/** Construct an independent syntax example, never a replacement for a queried machine word. */
object InstructionExamples {
    fun create(instruction: InstructionDefinition, encoding: InstructionEncoding, xlen: Int, abi: Boolean = true,
               csrName: String? = null): InstructionUsageExample? {
        if (xlen !in encoding.xlens) return null
        val name = instruction.name
        var preferred = 0L
        encoding.fields.filter { it.operand != null }.forEach { field ->
            val operand = field.operand!!
            val value = if (field.registerClass != null) {
                val desired = when {
                    field.registerClass == "vector" -> when (operand) { "vd", "vs3" -> 8; "vs1" -> 24; else -> 16 }
                    operand == "r1s'" -> 8
                    operand == "r2s'" -> 9
                    operand == "rd" && name in setOf("jal", "jalr") -> 1
                    operand in setOf("rs1", "rs1'", "fs1") -> 11
                    operand in setOf("rs2", "rs2'") -> 12
                    operand == "rs3" -> 13
                    else -> 10
                }
                (field.registerMap?.indexOf(desired)?.takeIf { it >= 0 } ?: (desired - field.registerOffset)).toLong()
            } else when (operand) {
                "vm" -> 1L
                "csr" -> 0x340L
                "vtypei" -> 0xd0L // e32,m1,ta,ma
                "rlist" -> 4L
                "pred", "succ" -> 3L
                "shamt" -> 1L
                "imm", "simm" -> when {
                    name in setOf("lui", "auipc", "c.lui") -> 0x1000L
                    name == "c.nop" || name == "jalr" -> 0L
                    name.startsWith("csr") -> 1L
                    name.startsWith("v") -> 3L
                    name.matches(Regex("(?:c\\.)?(?:f?[ls][bhwdqu].*|addi16sp)")) -> 16L
                    else -> 8L
                }
                else -> 0L
            }
            val positions = field.operandBits ?: (field.msb - field.lsb downTo 0).toList()
            positions.forEachIndexed { index, target -> preferred = preferred or (((value shr target) and 1) shl (field.msb - index)) }
        }
        val word = instructionExampleWord(encoding, xlen, preferred) ?: return null
        if (!encoding.matches(word, xlen)) return null
        val hit = InstructionHit(instruction, encoding, 0, word)
        val assembly = InstructionAssembly.render(hit, abi, csrName, xlen)?.takeIf { it.complete } ?: return null
        val raw = encoding.operands(word)
        val operands = raw.mapValues { InstructionAssembly.register(it.value, abi) }
        val rd = operands["rd"] ?: operands["rd/rs1"] ?: operands["rd'"] ?: operands["rd'/rs1'"]
        val rs1 = operands["rs1"] ?: operands["rs1'"] ?: operands["rd/rs1"] ?: operands["rd'/rs1'"]
        val rs2 = operands["rs2"] ?: operands["rs2'"]
        val imm = raw["imm"]?.toLongOrNull()
        val note = when {
            name == "add" -> "假设 $rs1 = 3、$rs2 = 5，执行后 $rd = 8。" to "With $rs1 = 3 and $rs2 = 5, $rd becomes 8."
            name == "sub" -> "假设 $rs1 = 9、$rs2 = 4，执行后 $rd = 5。" to "With $rs1 = 9 and $rs2 = 4, $rd becomes 5."
            name in setOf("addi", "c.addi", "c.addi4spn", "c.addi16sp") && imm != null -> {
                val source = rs1 ?: if (name in setOf("c.addi4spn", "c.addi16sp")) InstructionAssembly.register("x2", abi) else rd
                val dest = rd ?: source
                "假设 $source = 32，执行后 $dest = ${32 + imm}。" to "With $source = 32, $dest becomes ${32 + imm}."
            }
            name == "mul" -> "假设 $rs1 = 3、$rs2 = 5，执行后 $rd = 15。" to "With $rs1 = 3 and $rs2 = 5, $rd becomes 15."
            name in setOf("and", "or", "xor") -> {
                val result = when (name) { "and" -> 8; "or" -> 14; else -> 6 }
                "假设 $rs1 = 10、$rs2 = 12，执行后 $rd = $result。" to "With $rs1 = 10 and $rs2 = 12, $rd becomes $result."
            }
            name in setOf("lb", "lbu", "lh", "lhu", "lw", "lwu") ->
                "假设 $rs1 + $imm 指向有效且满足对齐要求的内存，其值为 42；读取后 $rd = 42。" to
                    "Assume $rs1 + $imm is a valid, appropriately aligned address containing 42; $rd receives 42."
            name in setOf("sb", "sh", "sw") ->
                "假设 $rs2 = 42，将其低 ${when (name) { "sb" -> 8; "sh" -> 16; else -> 32 }} 位写入 $rs1 + $imm 指向的有效内存。" to
                    "With $rs2 = 42, store its low ${when (name) { "sb" -> 8; "sh" -> 16; else -> 32 }} bits to valid memory at $rs1 + $imm."
            name in setOf("beq", "bne") ->
                "当 $rs1 与 $rs2 ${if (name == "beq") "相等" else "不等"}时，跳转到当前 PC ${if ((imm ?: 0) < 0) "" else "+ "}$imm。" to
                    "Branch to the current PC plus $imm when $rs1 and $rs2 are ${if (name == "beq") "equal" else "different"}."
            name == "jal" -> "跳转到 PC + $imm，并将下一条指令地址 PC + 4 写入 $rd。" to
                "Jump to PC + $imm and write the next instruction address PC + 4 to $rd."
            name == "ret" -> "跳转到 ${InstructionAssembly.register("x1", abi)} 保存的返回地址；目标地址的最低位清零。" to
                "Return to the address held in ${InstructionAssembly.register("x1", abi)}, clearing the least significant target bit."
            InstructionCsr.address(encoding, word) == 0x340 ->
                "以 mscratch（0x340）为例；访问该 CSR 需要机器态权限。" to
                    "Use mscratch (0x340) as the example CSR; accessing it requires machine privilege."
            else -> "示例中的操作数按当前编码变体填写；执行前仍需满足扩展、权限及状态条件。" to
                "Operands demonstrate this encoding variant; extensions, privilege and runtime conditions still apply."
        }
        return InstructionUsageExample(assembly.text, note.first, note.second, word)
    }

    fun create(pseudo: InstructionPseudo): InstructionUsageExample? {
        if (pseudo.referenceStatus == "historical") return null
        if (pseudo.name == "li") return InstructionUsageExample("li a0, 0", "将整数寄存器 a0 置为 0；具体展开由汇编器决定。", "Set integer register a0 to zero; the assembler determines the expansion.")
        if (pseudo.name == "la") return InstructionUsageExample("la t1, mtimecmp", "将符号 mtimecmp 的地址放入 t1；具体展开与重定位由汇编器决定。", "Load the address of symbol mtimecmp into t1; expansion and relocation depend on the assembler.")
        val first = pseudo.expansions.firstOrNull() ?: return null
        val replacements = mapOf("vd" to "v8", "va" to "v16", "vt" to "v24", "x" to "a0", "i" to "3", "offset" to "target")
        val code = Regex("\\b(vd|va|vt|x|i|offset)\\b").replace(first.syntax.replace(", vm", "")) { replacements.getValue(it.value) }
        val note = when (pseudo.name) {
            "call" -> "调用符号 target 指向的过程，保存返回地址；地址拆分和重定位由汇编器、链接器决定。" to "Call procedure target and save the return address; the assembler and linker determine address materialization and relocation."
            "tail" -> "尾调用符号 target，不保存新的返回地址；展开可能改写临时寄存器。" to "Tail-call symbol target without saving a new return address; expansion may clobber a temporary register."
            else -> "使用无掩码形式；展开条件及临时寄存器见下方参考序列。" to "Use the unmasked form; see the reference sequences for expansion conditions and temporary registers."
        }
        return InstructionUsageExample(code, note.first, note.second)
    }
}
