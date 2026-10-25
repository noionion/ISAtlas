package dev.isatlas.core

data class AssemblyRendering(val text: String, val complete: Boolean = true)

/** Format verified operand families; unsupported syntax stays explicitly labelled as decoded fields. */
object InstructionAssembly {
    private val integerAbi = listOf("zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1",
        "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6")
    private val floatAbi = listOf("ft0", "ft1", "ft2", "ft3", "ft4", "ft5", "ft6", "ft7", "fs0", "fs1",
        "fa0", "fa1", "fa2", "fa3", "fa4", "fa5", "fa6", "fa7", "fs2", "fs3", "fs4", "fs5", "fs6", "fs7", "fs8", "fs9", "fs10", "fs11", "ft8", "ft9", "ft10", "ft11")
    fun register(value: String, abi: Boolean): String {
        if (!abi) return value
        val number = value.drop(1).toIntOrNull() ?: return value
        return when (value.firstOrNull()) { 'x' -> integerAbi.getOrNull(number); 'f' -> floatAbi.getOrNull(number); else -> null } ?: value
    }

    fun render(hit: InstructionHit, abi: Boolean = true, csrName: String? = null, xlen: Int = 64): AssemblyRendering? {
        val word = hit.word ?: return null
        val encoding = hit.encoding
        val raw = encoding.operands(word)
        val operands = raw.mapValues { register(it.value, abi) }
        val name = hit.instruction.name
        fun number(key: String) = raw[key]?.toLongOrNull()
        fun fallback() = AssemblyRendering(name + if (operands.isEmpty()) "" else " · " + operands.entries.joinToString { "${it.key}=${it.value}" }, false)
        fun line(args: List<String>, mnemonic: String = name) = AssemblyRendering(mnemonic + if (args.isEmpty()) "" else " " + args.joinToString(", "))
        fun value(key: String) = operands.getValue(key)
        fun reg(number: Int) = register("x$number", abi)
        hit.instruction.assemblyOperands?.let { order ->
            val args = order.mapNotNull { token -> when {
                token == "vm" -> if (number("vm") == 0L) "v0.t" else null
                token == "csr" -> csrName ?: "0x${number("csr")!!.toString(16).padStart(3, '0')}"
                token.startsWith("pc:") -> number(token.removePrefix("pc:"))!!.let { "." + if (it < 0) it.toString() else "+$it" }
                token.startsWith("(") -> "(${value(token.trim('(', ')'))})"
                else -> value(token)
            } }
            return line(args)
        }
        if ("constantIndex" in raw) {
            val constants = listOf("-1.0", "min", "0.0000152587890625", "0.000030517578125", "0.00390625", "0.0078125", "0.0625", "0.125",
                "0.25", "0.3125", "0.375", "0.4375", "0.5", "0.625", "0.75", "0.875", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0", "4.0", "8.0", "16.0", "128.0", "256.0", "32768.0", "65536.0", "inf", "nan")
            val index = number("constantIndex")!!.toInt()
            return line(listOf(value("rd"), if (name == "fli.h" && index == 29) "inf" else constants[index]))
        }
        if (name in setOf("cm.jt", "cm.jalt")) return line(listOf(value("index")))
        if (name in setOf("cm.mva01s", "cm.mvsa01")) return line(listOf(value("r1s'"), value("r2s'")))
        if ("rlist" in raw) {
            val rlist = number("rlist")!!.toInt()
            if (rlist !in 4..15 || xlen !in listOf(32, 64)) return fallback()
            val count = if (rlist == 15) 13 else rlist - 3
            val registers = listOf(1, 8, 9, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27).take(count)
            val list = if (abi && count > 2) "{ra, s0-s${count - 2}}" else registers.joinToString(", ", "{", "}") { reg(it) }
            val adjustment = ((count * xlen / 8 + 15) / 16 * 16 + number("spimm")!! * 16) * if (name == "cm.push") -1 else 1
            return line(listOf(list, adjustment.toString()))
        }
        if (name in setOf("sspush", "sspopchk")) return line(listOf(reg(((word shr if (name == "sspush") 20 else 15) and 31).toInt())))
        if (name in setOf("c.sspush", "c.sspopchk")) return line(listOf(reg(if (name == "c.sspush") 1 else 5)))
        if (name == "fence.i") return line(emptyList())
        if (name == "fence") {
            if (number("fm") != 0L) return fallback()
            fun flags(key: String) = listOf(8L to "i", 4L to "o", 2L to "r", 1L to "w").filter { (number(key) ?: 0) and it.first != 0L }.joinToString("") { it.second }.ifEmpty { "0" }
            return line(listOf(flags("pred"), flags("succ")))
        }
        if (name == "c.nop") return if (number("imm") == 0L) line(emptyList()) else fallback()
        if (raw.isEmpty()) return line(emptyList())
        if (name.startsWith("csr") && "csr" in raw) return line(listOf(value("rd"), csrName ?: "0x${number("csr")!!.toString(16).padStart(3, '0')}", value(if ("rs1" in raw) "rs1" else "imm")))
        if ("aq" in raw || "rl" in raw) {
            val suffix = when { number("aq") == 1L && number("rl") == 1L -> ".aqrl"; number("aq") == 1L -> ".aq"; number("rl") == 1L -> ".rl"; else -> "" }
            return line(listOfNotNull(operands["rd"], operands["rs2"], operands["rs1"]?.let { "($it)" }), name + suffix)
        }
        val scalarLoads = setOf("lb", "lbu", "lh", "lhu", "lw", "lwu", "ld", "flh", "flw", "fld", "flq")
        val scalarStores = setOf("sb", "sh", "sw", "sd", "fsh", "fsw", "fsd", "fsq")
        if (name in scalarLoads || name in scalarStores || name == "jalr") return line(listOf(value(if (name in scalarStores) "rs2" else "rd"), "${value("imm")}(${value("rs1")})"))
        if (name.startsWith("prefetch.")) return line(listOf("${value("imm")}(${value("rs1")})"))
        if (name.startsWith("cbo.")) return line(listOf("(${value("rs1")})"))
        if (name.startsWith("hlv." ) || name.startsWith("hlvx.")) return line(listOf(value("rd"), "(${value("rs1")})"))
        if (name.startsWith("hsv.")) return line(listOf(value("rs2"), "(${value("rs1")})"))
        if (name in setOf("lui", "auipc", "c.lui")) return line(listOf(value("rd"), "0x${((number("imm")!! shr 12) and 0xfffff).toString(16)}"))
        if (name == "c.addi16sp") return line(listOf(reg(2), value("imm")))
        if (name == "c.addi4spn") return line(listOf(value("rd'"), reg(2), value("imm")))
        if (name in setOf("beq", "bne", "blt", "bge", "bltu", "bgeu", "jal", "j", "c.j", "c.jal", "c.beqz", "c.bnez")) {
            val offset = number("imm")!!
            val target = "." + if (offset < 0) offset.toString() else "+$offset"
            return line(listOfNotNull(operands["rd"], operands["rs1"], operands["rs1'"], operands["rs2"], target))
        }
        val compressedLoad = Regex("c\\.(?:l[bhwd]u?|fl[wd])(?:sp)?")
        val compressedStore = Regex("c\\.(?:s[bhwd]|fs[wd])(?:sp)?")
        if (compressedLoad.matches(name) || compressedStore.matches(name)) {
            val store = compressedStore.matches(name)
            val target = if (store) if ("rs2'" in raw) "rs2'" else "rs2" else if ("rd'" in raw) "rd'" else "rd"
            val base = if (name.endsWith("sp")) reg(2) else value("rs1'")
            return line(listOf(value(target), "${value("imm")}($base)"))
        }
        if (name == "vsetvl") return line(listOf(value("rd"), value("rs1"), value("rs2")))
        if (name.startsWith("vset") && "vtypei" in raw) {
            val vtype = number("vtypei")!!
            val lmul = listOf("m1", "m2", "m4", "m8", null, "mf8", "mf4", "mf2")[(vtype and 7).toInt()] ?: return fallback()
            if (vtype shr 8 != 0L) return fallback()
            return line(listOf(value("rd"), value(if ("rs1" in raw) "rs1" else "imm"), "e${8 shl ((vtype shr 3) and 7).toInt()}", lmul,
                if (vtype and 64 != 0L) "ta" else "tu", if (vtype and 128 != 0L) "ma" else "mu"))
        }
        if (name.startsWith("v")) {
            val args = mutableListOf<String>()
            if (Regex("v[ls](?:e[0-9]|se[0-9]|[ou]xei|[ou]xseg|seg|sseg|[1248]r|m\\.v).*" ).matches(name)) {
                args += value(if ("vd" in raw) "vd" else "vs3")
                args += "(${value("rs1")})"
                operands["rs2"]?.let { args += it }
                operands["vs2"]?.let { args += it }
            } else {
                val target = listOf("vd", "rd", "fd").firstOrNull { it in raw } ?: return fallback()
                args += value(target)
                val fused = Regex("v(?:f|fw|w)?(?:n?m(?:acc|add|sub|sac)|msac).*" ).matches(name)
                val order = if (fused) listOf("vs1", "rs1", "fs1", "vs2", "simm", "imm") else listOf("vs2", "vs1", "rs1", "fs1", "simm", "imm")
                order.filter { it in raw }.forEach { args += value(it) }
            }
            if (name.endsWith("vvm") || name.endsWith("vxm") || name.endsWith("vim") || name == "vfmerge.vfm") args += "v0"
            else if (number("vm") == 0L) args += "v0.t"
            val supported = setOf("vd", "vs3", "rd", "fd", "vs2", "vs1", "rs1", "rs2", "fs1", "simm", "imm", "vm")
            if (raw.keys.any { it !in supported }) return fallback()
            return line(args)
        }
        val order = listOf("rd", "rd'", "rd/rs1", "rd'/rs1'", "rs1", "rs1'", "rs", "rs2", "rs2'", "rs3", "imm", "shamt", "bs", "rnum", "LPL", "rm")
        if (raw.keys.any { it !in order }) return fallback()
        val args = order.filter { it in raw }.map { key ->
            if (key == "rm") listOf("rne", "rtz", "rdn", "rup", "rmm", "reserved", "reserved", "dyn")[number(key)!!.toInt()] else value(key)
        }
        return line(args)
    }
}
