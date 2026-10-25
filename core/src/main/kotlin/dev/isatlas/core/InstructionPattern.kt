package dev.isatlas.core

/** Unknown bits are never a concrete machine word. Matching asks whether a valid completion exists. */
data class InstructionPattern(val value: Long, val knownMask: Long, val length: Int, val radix: Int = 16) {
    init {
        require(length in listOf(16, 32))
        require(radix in listOf(2, 16))
        require(value in 0 until (1L shl length) && knownMask in 0 until (1L shl length))
    }
    val knownBits get() = java.lang.Long.bitCount(knownMask)
    val notation get() = if (radix == 2) "二进制" else "十六进制"
    val query: String get() {
        val nibbles = (length - 4 downTo 0 step 4).map { (knownMask shr it) and 15 }
        return if (radix == 16 && nibbles.all { it == 0L || it == 15L }) "0x" + (length - 4 downTo 0 step 4).joinToString("") {
            if ((knownMask shr it) and 15 == 0L) "?" else ((value shr it) and 15).toString(16)
        } else "0b" + (length - 1 downTo 0).joinToString("") {
            if (knownMask and (1L shl it) == 0L) "?" else ((value shr it) and 1).toString()
        }
    }

    fun fixedMatches(encoding: InstructionEncoding): Boolean = length == encoding.length &&
        ((value xor encoding.match.removePrefix("0x").toLong(16)) and knownMask and encoding.mask.removePrefix("0x").toLong(16)) == 0L

    fun matches(encoding: InstructionEncoding, xlen: Int): Boolean {
        if (!fixedMatches(encoding)) return false
        return PatternCompletion(encoding, xlen).exists(value, knownMask)
    }

    /** Report only operands whose entire encoded bit range was supplied by the user. */
    fun operands(encoding: InstructionEncoding): Map<String, String?> {
        val decoded = encoding.operands(value)
        return encoding.fields.filter { it.operand != null }.groupBy { it.operand!! }.mapValues { (name, fields) ->
            val mask = fields.fold(0L) { bits, f -> bits or (((1L shl (f.msb - f.lsb + 1)) - 1) shl f.lsb) }
            if (knownMask and mask == mask) decoded[name] else null
        }
    }
}

/** Choose an independent example satisfying the same static restrictions as partial searches. */
internal fun instructionExampleWord(encoding: InstructionEncoding, xlen: Int, preferred: Long): Long? =
    PatternCompletion(encoding, xlen, preferred).complete(0, 0)

/** A small bit constraint solver; it branches only on static restrictions, never all unknown operands. */
private class PatternCompletion(encoding: InstructionEncoding, xlen: Int, private val preferred: Long = 0) {
    private data class Clause(val ones: Long, val zeros: Long)
    private data class Choices(val mask: Long, val values: List<Long>)
    private val fixedMask = encoding.mask.removePrefix("0x").toLong(16)
    private val fixedValue = encoding.match.removePrefix("0x").toLong(16)
    private val wordMask = (1L shl encoding.length) - 1
    private val clauses = mutableListOf<Clause>()
    private val choices = mutableListOf<Choices>()
    private var supported = true

    init {
        encoding.encodingConstraints.filter { xlen in it.xlens }.forEach { constraint ->
            val mask = constraint.mask.removePrefix("0x").toLong(16)
            when (constraint.operator) {
                "in" -> choices += Choices(mask, constraint.values)
                "notIn" -> constraint.values.forEach { forbidden -> clauses += Clause(mask and forbidden.inv(), mask and forbidden) }
                else -> supported = false
            }
        }
        encoding.operandRelations.forEach { relation ->
            val left = encoding.fields.filter { it.operand == relation.left }
            val right = encoding.fields.filter { it.operand == relation.right }
            fun positions(fields: List<InstructionField>) = fields.flatMap { field ->
                (field.operandBits ?: (field.msb - field.lsb downTo 0).toList()).mapIndexed { index, target -> target to field.msb - index }
            }.toMap()
            val a = positions(left)
            val b = positions(right)
            if (relation.operator != "eq" || left.isEmpty() || right.isEmpty()) supported = false
            else if (a.keys == b.keys && left.first().registerClass == right.first().registerClass &&
                left.first().registerOffset == right.first().registerOffset && left.first().registerMap == null && right.first().registerMap == null) {
                a.forEach { (target, bit) ->
                    val lhs = 1L shl bit
                    val rhs = 1L shl b.getValue(target)
                    if (lhs != rhs) { clauses += Clause(lhs, rhs); clauses += Clause(rhs, lhs) }
                }
            } else {
                // Mapped register operands are compared in the decoded register namespace too.
                fun domain(bits: Map<Int, Int>, name: String): List<Pair<Long, String>> =
                    if (bits.size > 5) emptyList() else (0 until (1 shl bits.size)).map { raw ->
                        val word = bits.values.sorted().mapIndexed { index, bit -> ((raw.toLong() shr index) and 1) shl bit }.fold(0L, Long::or)
                        word to encoding.operands(word).getValue(name)
                    }
                val lhs = domain(a, relation.left)
                val rhs = domain(b, relation.right)
                val maskA = a.values.fold(0L) { mask, bit -> mask or (1L shl bit) }
                val maskB = b.values.fold(0L) { mask, bit -> mask or (1L shl bit) }
                choices += Choices(maskA or maskB, lhs.flatMap { (wordA, regA) -> rhs.mapNotNull { (wordB, regB) ->
                    if (regA == regB && (wordA xor wordB) and maskA and maskB == 0L) wordA or wordB else null
                } }.distinct())
            }
        }
    }

    fun exists(value: Long, known: Long): Boolean = complete(value, known) != null

    fun complete(value: Long, known: Long): Long? {
        if (!supported || (value xor fixedValue) and known and fixedMask != 0L) return null
        return solve((value and known) or fixedValue, known or fixedMask)
    }

    private fun solve(initialValue: Long, initialMask: Long): Long? {
        var value = initialValue
        var known = initialMask
        var shortest: Long
        do {
            var changed = false
            shortest = 0L
            for (clause in clauses) {
                if ((value and known and clause.ones) != 0L || (value.inv() and known and clause.zeros) != 0L) continue
                val remaining = (clause.ones or clause.zeros) and known.inv()
                if (remaining == 0L) return null
                if (java.lang.Long.bitCount(remaining) == 1) {
                    if (clause.ones and remaining != 0L) value = value or remaining
                    known = known or remaining
                    changed = true
                } else if (shortest == 0L || java.lang.Long.bitCount(remaining) < java.lang.Long.bitCount(shortest)) shortest = remaining
            }
            for (choice in choices) {
                val possible = choice.values.filter { (it xor value) and known and choice.mask == 0L }
                if (possible.isEmpty()) return null
                if (possible.size == 1 && known and choice.mask != choice.mask) {
                    value = (value and choice.mask.inv()) or possible.single()
                    known = known or choice.mask
                    changed = true
                }
            }
        } while (changed)
        val branch = choices.filter { known and it.mask != it.mask }.minByOrNull { choice ->
            choice.values.count { (it xor value) and known and choice.mask == 0L }
        }
        if (branch != null) {
            for (candidate in branch.values.sortedBy { java.lang.Long.bitCount((it xor preferred) and branch.mask) }) {
                if ((candidate xor value) and known and branch.mask != 0L) continue
                solve((value and branch.mask.inv()) or candidate, known or branch.mask)?.let { return it }
            }
            return null
        }
        if (shortest == 0L) return (value and known) or (preferred and known.inv() and wordMask)
        val bit = java.lang.Long.lowestOneBit(shortest)
        val preferredValue = (value and bit.inv()) or (preferred and bit)
        return solve(preferredValue, known or bit) ?: solve(preferredValue xor bit, known or bit)
    }
}
