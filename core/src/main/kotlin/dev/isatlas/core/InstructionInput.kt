package dev.isatlas.core

import java.util.Locale

data class InstructionInput(val word: Long? = null, val error: String? = null, val pattern: InstructionPattern? = null) {
    companion object {
        fun parse(input: String): InstructionInput {
            val text = input.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_]"), "")
            val prefixed = text.startsWith("0x") || text.startsWith("0b")
            if ('?' in text) {
                val digits = if (prefixed) text.drop(2) else text
                val binary = text.startsWith("0b") || (!prefixed && digits.length in listOf(16, 32) && digits.all { it in "01?" })
                val alphabet = if (binary) "01?" else "0123456789abcdef?"
                if (digits.any { it !in alphabet }) return InstructionInput(error = "部分编码仅支持 0x 十六进制或 0b 二进制，使用 ? 表示未知位。")
                if (digits.length !in if (binary) listOf(16, 32) else listOf(4, 8))
                    return InstructionInput(error = "含 ? 的编码需要完整长度：4 / 8 位十六进制或 16 / 32 位二进制。")
                val step = if (binary) 1 else 4
                var value = 0L
                var mask = 0L
                digits.forEach { digit ->
                    value = value shl step
                    mask = mask shl step
                    if (digit != '?') { value = value or digit.digitToInt(if (binary) 2 else 16).toLong(); mask = mask or ((1L shl step) - 1) }
                }
                val length = digits.length * step
                if (mask and 3L == 3L && ((length == 16 && value and 3L == 3L) || (length == 32 && value and 3L != 3L)))
                    return InstructionInput(error = "部分编码的已知低位与 $length 位指令长度不符；请检查输入长度。")
                if (length == 32 && mask and 31L == 31L && value and 31L == 31L)
                    return InstructionInput(error = "已知低位指示长度超过 32 位，当前仅支持 16 / 32 位指令。")
                return InstructionInput(pattern = InstructionPattern(value, mask, length, if (binary) 2 else 16))
            }
            val binary = text.startsWith("0b") || (!prefixed && text.length in listOf(16, 32) && text.all { it in "01" })
            val numeric = prefixed || text.firstOrNull()?.isDigit() == true || text.matches(Regex("[a-f0-9]{4}|[a-f0-9]{8}"))
            if (!numeric) return InstructionInput()
            val digits = if (prefixed) text.drop(2) else text
            val alphabet = if (binary) "01" else "0123456789abcdef"
            if (digits.isEmpty() || digits.any { it !in alphabet }) return InstructionInput(error = if (binary) "二进制编码只能包含 0 和 1。" else "十六进制编码只能包含 0–9、a–f。")
            if (!prefixed && digits.length !in if (binary) listOf(16, 32) else listOf(4, 8))
                return InstructionInput(error = "无前缀编码需要 4 / 8 位十六进制或 16 / 32 位二进制；也可以添加 0x / 0b 前缀。")
            if (digits.length > if (binary) 32 else 8) return InstructionInput(error = "编码超出 32 位；当前支持单条 16 / 32 位指令。")
            val word = digits.toLongOrNull(if (binary) 2 else 16) ?: return InstructionInput(error = "无法解析指令编码。")
            if (word and 3L != 3L && word > 0xffffL) return InstructionInput(error = "编码低位指示 16 位指令，但输入包含非零高 16 位；请检查编码或逐条查询。")
            if (word and 0x1fL == 0x1fL) return InstructionInput(error = "编码低位指示长度超过 32 位，当前仅支持 16 / 32 位指令。")
            return InstructionInput(word)
        }
    }
}

enum class InstructionFilterDimension(val label: String) { WIDTH("位宽"), PRIVILEGE("权限"), EXTENSION("扩展"), SOURCE("来源") }
enum class InstructionDiagnosticKind { INPUT, FILTERED, RESERVED, UNKNOWN, TOO_BROAD }
data class InstructionDiagnostic(val kind: InstructionDiagnosticKind, val message: String,
    val clear: Set<InstructionFilterDimension> = emptySet(), val suggestedXlen: Int? = null)

object InstructionCsr {
    /** Execution privilege filters must not hide a CSR named by an instruction operand. */
    fun resolve(pack: DataPack, address: Int, xlen: Int, namespace: String? = null): List<RegisterDefinition> =
        pack.registers.filter { (it.locator as? RegisterLocator.Csr)?.address == address && xlen in it.xlens &&
            (namespace == null || it.namespace.ifBlank { pack.namespace } == namespace) }
            .sortedWith(compareBy<RegisterDefinition> { it.namespace.ifBlank { pack.namespace } }.thenBy { it.name }.thenBy { it.id })
    fun address(encoding: InstructionEncoding, word: Long): Int? = encoding.operands(word)["csr"]?.toIntOrNull()
        ?: encoding.fields.firstOrNull { it.name == "csr" && it.value != null }?.value?.toInt()
}
