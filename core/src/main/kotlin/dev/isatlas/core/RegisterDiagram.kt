package dev.isatlas.core

/** A field or undescribed range clipped to a display lane, in MSB-to-LSB order. */
data class DiagramSegment(val field: FieldDefinition?, val msb: Int, val lsb: Int, val region: BitRegion? = null) {
    val width get() = msb - lsb + 1
    val range get() = if (msb == lsb) "[$msb]" else "[$msb:$lsb]"
}

data class DiagramLane(val msb: Int, val lsb: Int, val segments: List<DiagramSegment>)

/** Hit-test the actual bit position, without expanding adjacent one-bit targets. */
fun DiagramLane.fieldAtFraction(fraction: Float): FieldDefinition? {
    if (!fraction.isFinite() || fraction < 0f || fraction >= 1f) return null
    val bit = msb - (fraction * (msb - lsb + 1)).toInt().coerceAtMost(msb - lsb)
    return segments.firstOrNull { bit in it.lsb..it.msb }?.field
}

fun registerDiagramLanes(fields: List<FieldDefinition>, xlen: Int, laneBits: Int, regions: List<BitRegion> = emptyList()): List<DiagramLane> {
    require(xlen in listOf(32, 64) && laneBits in listOf(16, 32))
    fun owner(bit: Int) = fields.firstOrNull { it.layout(xlen)?.let { bits -> bit in bits.lsb..bits.msb } == true } to
        regions.firstOrNull { it.layout(xlen)?.let { bits -> bit in bits.lsb..bits.msb } == true }
    return (xlen - 1 downTo 0 step laneBits).map { high ->
        val low = high - laneBits + 1
        val segments = mutableListOf<DiagramSegment>()
        var bit = high
        while (bit >= low) {
            val (field, region) = owner(bit)
            val start = bit
            while (bit > low && owner(bit - 1) == (field to region)) bit--
            segments += DiagramSegment(field, start, bit, region)
            bit--
        }
        DiagramLane(high, low, segments)
    }
}
