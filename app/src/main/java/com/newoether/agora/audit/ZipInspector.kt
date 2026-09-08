package com.newoether.agora.audit

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Read-only ZIP entry enumeration + extraction for stored audit binaries (APK / XAPK / zip).
 * The central directory is read directly — a 2.3GB APK costs the same memory as a 2MB one.
 * Extraction streams entry-by-entry into [BinaryAuditStore], so extracting global-metadata.dat
 * from the full game package never touches the heap.
 */
class ZipInspector(private val store: BinaryAuditStore) {

    data class EntryInfo(val name: String, val sizeBytes: Long, val compressedBytes: Long, val method: String)

    /** Lists entries of the stored binary (must be a real zip/APK). Bounded output upstream. */
    fun listEntries(file: File): List<EntryInfo> {
        require(file.isFile) { "stored bytes missing — re-import" }
        ZipFile(file).use { zip ->
            return zip.entries().asSequence()
                .map { EntryInfo(it.name, it.size, it.compressedSize, compressionName(it.method)) }
                .sortedBy { it.name }
                .toList()
        }
    }

    /**
     * Extracts matching entries of the stored zip into the audit store as their own entries.
     * [nameFilter]: exact entry name, OR plain substring match (case-insensitive) when no
     * entry equals it exactly. At most [maxEntries] extracted per call.
     */
    fun extractEntries(file: File, nameFilter: String, maxEntries: Int = 5): List<BinaryAuditEntry> {
        require(file.isFile) { "stored bytes missing — re-import" }
        require(nameFilter.isNotBlank()) { "name filter is required" }
        val results = ArrayList<BinaryAuditEntry>()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val exact = entries.filter { it.name == nameFilter }
            val targets = if (exact.isNotEmpty()) exact.take(maxEntries)
            else entries.filter { it.name.contains(nameFilter, ignoreCase = true) }.take(maxEntries)
            require(targets.isNotEmpty()) {
                "no zip entry matches '$nameFilter' — audit_zip_list to inspect entries first"
            }
            for (entry in targets) {
                require(!entry.isDirectory) { "'${entry.name}' is a directory" }
                val stream = zip.getInputStream(entry)
                val derived = store.import(
                    name = derivedName(file, entry.name),
                    open = { stream },
                )
                results += derived
            }
        }
        return results
    }

    private fun derivedName(sourceFile: File, entryName: String): String {
        val base = sourceFile.name.removeSuffix(".apk").removeSuffix(".zip").removeSuffix(".xapk")
        val leaf = entryName.substringAfterLast('/')
        return "$base::$leaf".take(120)
    }

    private fun compressionName(method: Int): String = when (method) {
        java.util.zip.ZipEntry.STORED -> "STORE"
        java.util.zip.ZipEntry.DEFLATED -> "DEFLATE"
        else -> "OTHER($method)"
    }
}
