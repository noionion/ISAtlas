package dev.isatlas.core

import java.io.File

/** An explicit local CLI invocation may print validation results; the app never logs private package content. */
object LocalPackageTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected package ZIP and compiler vocabulary JSON" }
        val compiler = CompilerIsaOptions.decode(File(args[1]).readText())
        val bytes = File(args[0]).inputStream().use { LocalPackages.readBounded(it) }
        val result = LocalPackages.decode(bytes,9,compiler.tokens.toSet()+setOf("s","u"),setOf("manual","aia","debug","spmp"))
        println("Valid local package: ${result.manifest.id} ${result.manifest.version}; ${result.registers.size} CSR, ${result.instructions.size} instructions")
        println("SHA-256: ${result.sha256}")
    }
}
