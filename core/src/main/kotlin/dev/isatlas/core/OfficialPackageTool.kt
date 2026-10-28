package dev.isatlas.core

import java.io.File

/** Validate candidate public data with the same codecs and overlay rules as Android. */
object OfficialPackageTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Expected a generated data asset directory" }
        val directory = File(args.single()).canonicalFile
        val packages = OfficialPackages(File(directory, "riscv-packages.json").readText()) { path ->
            val file = File(directory, path).canonicalFile
            require(file.toPath().startsWith(directory.toPath()))
            file.readBytes()
        }
        val compiler = CompilerIsaOptions.decode(File(directory, "march-extensions.json").readText())
        for (mask in 0..7) {
            val enabled = listOf("aia", "debug", "spmp").filterIndexed { bit, _ -> mask and (1 shl bit) != 0 }.toSet()
            val catalogs = packages.catalogs(enabled, compiler)
            println("Valid combination ${enabled.sorted()}: ${catalogs.csr.registers.size} CSR, ${catalogs.instructions.instructions.size} instructions")
        }
    }
}
