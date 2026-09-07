package com.newoether.agora.audit

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class BinaryAuditEntry(
    val sourceId: String,
    val name: String,
    val byteLength: Long,
    val sha256: String,
    val importedAt: Long
)

/**
 * Content-addressed on-device store for imported large binaries (APK extracts, libil2cpp.so,
 * global-metadata.dat, asset bundles). Bytes live in filesDir — never Room, never the heap —
 * so there is no size cap and a 110 MB library cannot OOM the app. Import streams through a
 * fixed 256 KiB buffer; identical content deduplicates by SHA-256, which doubles as the load
 * cache: re-imports of the same file resolve to the existing entry without touching storage.
 */
class BinaryAuditStore(private val context: Context) {

    @Serializable
    private data class IndexFile(val version: Int = 1, val entries: List<BinaryAuditEntry> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    private val root: File
        get() = File(context.filesDir, ROOT_DIR).apply { if (!exists()) mkdirs() }

    @Synchronized
    fun entries(): List<BinaryAuditEntry> = readIndex().entries.sortedBy { it.importedAt }

    /** Exact id first, then a unique prefix. Throws when a prefix is ambiguous. */
    @Synchronized
    fun resolve(sourceId: String): BinaryAuditEntry {
        require(sourceId.isNotBlank()) { "sourceId is required" }
        val all = readIndex().entries
        all.firstOrNull { it.sourceId == sourceId }?.let { return it }
        val prefixMatches = all.filter { it.sourceId.startsWith(sourceId, ignoreCase = true) }
        require(prefixMatches.isNotEmpty()) { "sourceId '$sourceId' not found — run audit_import or audit_list first" }
        require(prefixMatches.size == 1) {
            "sourceId '$sourceId' is ambiguous (${prefixMatches.size} matches) — pass more characters"
        }
        return prefixMatches.single()
    }

    fun fileOf(entry: BinaryAuditEntry): File = File(root, entry.sha256)

    /**
     * Streams [open] into the store. Returns the existing entry when the content (SHA-256)
     * already exists; the temporary copy is discarded in that case.
     */
    @Synchronized
    fun import(name: String, open: () -> InputStream): BinaryAuditEntry {
        val temp = File.createTempFile("audit-import-", ".tmp", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            open().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            length += read
                        }
                    }
                    output.fd.sync()
                }
            }
            require(length > 0) { "source is empty" }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            readIndex().entries.firstOrNull { it.sha256 == sha }?.let { existing ->
                temp.delete()
                return existing
            }
            val target = File(root, sha)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            val entry = BinaryAuditEntry(
                sourceId = sha,
                name = sanitize(name.ifBlank { sha.take(12) }),
                byteLength = length,
                sha256 = sha,
                importedAt = System.currentTimeMillis()
            )
            writeIndex(readIndex().entries + entry)
            return entry
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /** Accepts content://, file:// and absolute paths (mirrors AttachmentSourceReader semantics). */
    fun openSource(source: String): () -> InputStream = {
        when {
            source.startsWith("content:", ignoreCase = true) ->
                context.contentResolver.openInputStream(Uri.parse(source))
                    ?: error("cannot open $source")
            source.startsWith("file:", ignoreCase = true) -> FileInputStream(File(URI(source)))
            File(source).isAbsolute -> FileInputStream(source)
            else -> error("unsupported source: use content://, file:// or an absolute path")
        }
    }

    private fun readIndex(): IndexFile = runCatching {
        json.decodeFromString(IndexFile.serializer(), File(root, INDEX_NAME).readText())
    }.getOrDefault(IndexFile())

    private fun writeIndex(index: IndexFile) {
        val target = File(root, INDEX_NAME)
        val temp = File(root, "$INDEX_NAME.tmp-${System.nanoTime()}")
        temp.writeText(json.encodeToString(IndexFile.serializer(), index))
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\t]"), " ")
            .take(200)
            .ifBlank { "unnamed" }

    companion object {
        const val ROOT_DIR = "binary-audit"
        const val INDEX_NAME = "index.json"
        const val COPY_BUFFER = 256 * 1024
    }
}
