package dev.isatlas

import android.content.Context
import android.util.AtomicFile
import dev.isatlas.core.*
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class LocalPackagePreview(val data: LocalPackageData, val conflicts: PackageConflict, val replacing: LocalPackageRecord?, val problem: String?)
data class LocalPackageSnapshot(val catalogs: LocalCatalogs, val packages: List<ManagedLocalPackage>, val warning: String? = null)

/** This store never opens a socket, logs package data, or keeps the document-provider URI. */
internal class LocalPackageRepository(context: Context, private val appVersion: Int,
    private val directory: File = File(context.noBackupFilesDir,"local-packages")) {
    private val registry get() = AtomicFile(File(directory,"index.json"))
    private var records=emptyList<LocalPackageRecord>()
    private var decoded=emptyList<ManagedLocalPackage>()
    private lateinit var base: LocalCatalogs
    private val json=Json

    @Synchronized fun load(base: LocalCatalogs): LocalPackageSnapshot {
        this.base=base
        directory.mkdirs()
        var warning: String?=null
        records=try {
            if (!registry.baseFile.exists() && !File(directory,"index.json.bak").exists()) emptyList()
            else registry.openRead().bufferedReader().use { json.decodeFromString<List<LocalPackageRecord>>(it.readText()) }.also { items ->
                require(items.size<=8 && items.map { it.id }.distinct().size==items.size)
                require(items.all { it.file.matches(Regex("[0-9a-f]{64}\\.zip")) })
            }
        } catch (_: Exception) { warning="本地包索引不可读取，内置数据库仍可使用；原文件已保留。"; emptyList() }
        var expanded=0
        decoded=records.map { record ->
            try {
                val bytes=File(directory,record.file).inputStream().use { LocalPackages.readBounded(it) }
                val data=decode(bytes)
                expanded+=data.expandedBytes
                require(expanded<=16*1024*1024) { "全部本地包解压后合计超过 16 MiB" }
                require(data.manifest.id==record.id && data.manifest.version==record.version && record.file=="${data.sha256}.zip")
                ManagedLocalPackage(record,data)
            } catch (e: Exception) { ManagedLocalPackage(record,null,if (e is PackageValidationException) e.message else "数据包损坏或不可读取") }
        }
        return snapshot(warning)
    }

    @Synchronized fun rebase(catalogs: LocalCatalogs): LocalPackageSnapshot {
        val previous = base
        base = catalogs
        return try { snapshot() } catch (error: Exception) { base = previous; throw error }
    }

    private fun decode(bytes: ByteArray) = LocalPackages.decode(bytes,appVersion,base.compiler.tokens.toSet()+setOf("s","u"),
        base.csr.registers.map { it.namespace }.toSet()+base.instructions.instructions.map { it.namespace })

    @Synchronized fun preview(bytes: ByteArray): LocalPackagePreview {
        val data=decode(bytes)
        val others=decoded.filter { it.record.id!=data.manifest.id }
        require(others.sumOf { it.data?.expandedBytes ?: 0 }+data.expandedBytes<=16*1024*1024) { "全部本地包解压后合计超过 16 MiB" }
        require(others.none { it.data?.manifest?.namespace==data.manifest.namespace }) { "命名空间已被其他本地包使用" }
        require(others.size<8) { "最多导入 8 个本地包，请先移除不再需要的包" }
        val candidate=ManagedLocalPackage(LocalPackageRecord(data.manifest.id,data.manifest.name,data.manifest.version,"${data.sha256}.zip",true),data)
        val prospective=LocalPackages.statuses(others+candidate)
        // Validate coexistence, including extension-name ownership, even if a package is disabled.
        LocalPackages.merge(base,(others.mapNotNull { it.data }+data))
        val current=LocalPackages.merge(base,LocalPackages.statuses(others).filter { it.active }.mapNotNull { it.data })
        return LocalPackagePreview(data,LocalPackages.conflicts(data,current),records.find { it.id==data.manifest.id },
            prospective.single { it.record.id==data.manifest.id }.problem)
    }

    @Synchronized fun install(bytes: ByteArray): LocalPackageSnapshot {
        val preview=preview(bytes) // Revalidate against the current installed state before committing.
        val data=preview.data
        val record=LocalPackageRecord(data.manifest.id,data.manifest.name,data.manifest.version,"${data.sha256}.zip",preview.replacing?.enabled ?: true)
        val candidate=decoded.filter { it.record.id!=record.id }+ManagedLocalPackage(record,data)
        val result=makeSnapshot(candidate)
        require((candidate.sumOf { item -> if (item.record.id==record.id) bytes.size.toLong() else File(directory,item.record.file).length() })<=32L*1024*1024) { "本地包合计超过 32 MiB，请先移除不再需要的包" }
        val archive=File(directory,record.file)
        write(AtomicFile(archive),bytes) // Also repair a corrupted archive with the same content-addressed name.
        save(candidate.map { it.record })
        decoded=candidate
        cleanup()
        return result
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean): LocalPackageSnapshot {
        val target=decoded.single { it.record.id==id }
        if (enabled) require(target.data!=null) { target.problem ?: "数据包不可读取" }
        val candidate=decoded.map { if (it.record.id==id) it.copy(record=it.record.copy(enabled=enabled)) else it }
        val result=makeSnapshot(candidate)
        if (enabled) require(result.packages.single { it.record.id==id }.problem==null) { result.packages.single { it.record.id==id }.problem.orEmpty() }
        save(candidate.map { it.record })
        decoded=candidate
        return result
    }

    @Synchronized fun remove(id: String): LocalPackageSnapshot {
        val candidate=decoded.filter { it.record.id!=id }
        val result=makeSnapshot(candidate)
        save(candidate.map { it.record })
        decoded=candidate
        cleanup()
        return result
    }

    private fun makeSnapshot(items: List<ManagedLocalPackage>): LocalPackageSnapshot {
        val statuses=LocalPackages.statuses(items)
        // Invalid local data must never prevent the built-in catalog from loading.
        val accepted=mutableListOf<LocalPackageData>()
        var safe=statuses.map { item ->
            if (!item.active) item else try {
                LocalPackages.merge(base,accepted+item.data!!)
                accepted.add(item.data!!)
                item
            } catch (_: Exception) { item.copy(problem="与其他启用包的命名空间或扩展定义冲突，请停用或替换") }
        }
        repeat(items.size) {
            val activeIds=safe.filter { it.active }.map { it.record.id }.toSet()
            safe=safe.map { item ->
                if (item.active && item.data!!.manifest.dependencies.any { it.id !in activeIds })
                    item.copy(problem="依赖包当前不可启用") else item
            }
        }
        return LocalPackageSnapshot(LocalPackages.merge(base,safe.filter { it.active }.mapNotNull { it.data }),safe)
    }
    private fun snapshot(warning: String?=null) = makeSnapshot(decoded).copy(warning=warning)
    private fun save(items: List<LocalPackageRecord>) {
        write(registry,json.encodeToString(items).toByteArray(Charsets.UTF_8))
        records=items
    }
    private fun write(target: AtomicFile, bytes: ByteArray) {
        val output=target.startWrite()
        try { output.write(bytes); target.finishWrite(output) } catch (e: Exception) { target.failWrite(output); throw e }
        // AtomicFile reports some rename failures only through the platform log. Verify persistence before publishing state.
        val stored=target.openRead().use { LocalPackages.readBounded(it) }
        if (!stored.contentEquals(bytes)) throw java.io.IOException("Atomic package commit did not persist")
    }
    private fun cleanup() {
        val keep=records.map { it.file }.toSet()
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}\\.zip")) && it.name !in keep }.forEach { it.delete() }
    }
}
