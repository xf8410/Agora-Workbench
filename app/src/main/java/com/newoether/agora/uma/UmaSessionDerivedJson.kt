package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UmaDerivedDecodeRecord(
    val source_path: String,
    val json_path: String,
    val byte_length: Long,
    val consumed_bytes: Int,
)

@Serializable
data class UmaDerivedDecodeError(
    val source_path: String,
    val byte_offset: Int,
    val error: String,
)

@Serializable
data class UmaExchangeIndexRecord(
    val direction: String,
    val exchange_identity: String,
    val source_directory: String,
    val url_path: String? = null,
    val headers_path: String? = null,
    val payload_path: String? = null,
    val decoded_json_path: String? = null,
)

@Serializable
data class UmaRawManifestRecord(
    val file_id: Long,
    val relative_path: String,
    val content_type: String,
    val byte_length: Long,
    val sha256: String? = null,
    val created_at_ms: Long,
)

@Serializable
data class UmaSessionDerivedManifest(
    val session_id: String,
    val raw_file_count: Int,
    val raw_total_bytes: Long,
    val decoded_payload_count: Int,
    val decode_error_count: Int,
    val exchange_count: Int,
    val raw_files: List<UmaRawManifestRecord>,
)

data class UmaSessionDerivedJsonResult(
    val files: List<Pair<String, File>>,
    val decodedCount: Int,
    val errorCount: Int,
    val exchangeCount: Int,
)

/** Generates rebuildable JSON and a human-readable TXT report beside unchanged raw session files. */
class UmaSessionDerivedJsonGenerator(
    private val decoder: UmaMessagePackJsonDecoder = UmaMessagePackJsonDecoder(),
    private val catalog: UmaSessionJsonCatalog = UmaSessionJsonCatalog(),
    private val json: Json = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true },
) {
    fun generate(
        sessionId: String,
        rawRoot: File,
        indexedFiles: List<UmaStorageFile>,
    ): UmaSessionDerivedJsonResult {
        require(sessionId.isNotBlank()) { "session_id must not be blank" }
        val derivedRoot = File(rawRoot, DERIVED_DIRECTORY)
        require(derivedRoot.mkdirs() || derivedRoot.isDirectory) { "cannot create derived directory" }
        val outputs = mutableListOf<Pair<String, File>>()
        val decoded = mutableListOf<UmaDerivedDecodeRecord>()
        val errors = mutableListOf<UmaDerivedDecodeError>()
        val decodedBySource = mutableMapOf<String, String>()
        val jsonRoles = mutableListOf<UmaSessionJsonRole>()

        indexedFiles.filter { it.relativePath.endsWith("/payload.bin") }.forEach { indexed ->
            val sourcePath = validateUmaArchivePath(indexed.relativePath)
            val source = resolveUnder(rawRoot, sourcePath)
            require(source.isFile && source.length() == indexed.byteLength) {
                "payload source is missing or changed: $sourcePath"
            }
            val derivedPath = "$DERIVED_DIRECTORY/messagepack/$sourcePath.json"
            try {
                val result = decoder.decode(source.readBytes())
                val target = resolveUnder(rawRoot, derivedPath)
                writeAtomic(target, json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), result.value))
                outputs += derivedPath to target
                decoded += UmaDerivedDecodeRecord(sourcePath, derivedPath, indexed.byteLength, result.consumedBytes)
                decodedBySource[sourcePath] = derivedPath
                jsonRoles += catalog.decoded(sourcePath, derivedPath, result.value)
            } catch (failure: Throwable) {
                val offset = (failure as? UmaMessagePackDecodeException)?.byteOffset ?: -1
                errors += UmaDerivedDecodeError(sourcePath, offset, failure.message ?: failure::class.java.name)
            }
        }

        val exchanges = indexedFiles
            .filter { it.relativePath.startsWith("protocol/request/") || it.relativePath.startsWith("protocol/response/") }
            .groupBy { it.relativePath.substringBeforeLast('/') }
            .map { (directory, members) ->
                val direction = if (directory.startsWith("protocol/request/")) "request" else "response"
                val identity = directory.substringAfter(if (direction == "request") "protocol/request/" else "protocol/response/")
                fun member(name: String) = members.firstOrNull { it.relativePath.endsWith("/$name") }?.relativePath
                val payload = member("payload.bin")
                UmaExchangeIndexRecord(
                    direction = direction,
                    exchange_identity = identity,
                    source_directory = directory,
                    url_path = member("url.txt"),
                    headers_path = member("headers.raw"),
                    payload_path = payload,
                    decoded_json_path = payload?.let(decodedBySource::get),
                )
            }.sortedWith(compareBy({ it.direction }, { it.exchange_identity }))

        fun writeDerived(name: String, content: String) {
            val path = "$DERIVED_DIRECTORY/$name"
            val target = resolveUnder(rawRoot, path)
            writeAtomic(target, content)
            outputs += path to target
        }
        writeDerived("decoded_payloads.json", json.encodeToString(decoded))
        writeDerived("decode_errors.json", json.encodeToString(errors))
        writeDerived("exchanges.json", json.encodeToString(exchanges))
        writeDerived("manifest.json", json.encodeToString(UmaSessionDerivedManifest(
            session_id = sessionId,
            raw_file_count = indexedFiles.size,
            raw_total_bytes = indexedFiles.sumOf { it.byteLength },
            decoded_payload_count = decoded.size,
            decode_error_count = errors.size,
            exchange_count = exchanges.size,
            raw_files = indexedFiles.map { UmaRawManifestRecord(
                it.fileId, it.relativePath, it.contentType, it.byteLength, it.sha256, it.createdAtMs
            ) },
        )))

        val completeRoles = (catalog.builtInFiles() + jsonRoles).sortedBy { it.json_path }
        writeDerived(JSON_CATALOG_FILE, json.encodeToString(completeRoles))
        writeDerived(TEXT_REPORT_FILE, catalog.renderText(sessionId, completeRoles))

        return UmaSessionDerivedJsonResult(outputs, decoded.size, errors.size, exchanges.size)
    }

    private fun resolveUnder(root: File, relativePath: String): File {
        val safe = validateUmaArchivePath(relativePath)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, safe).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) { "path escapes session root" }
        return target
    }

    private fun writeAtomic(target: File, content: String) {
        require(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
            "cannot create derived parent directory"
        }
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists()) require(target.delete()) { "cannot replace derived file" }
        require(temporary.renameTo(target)) { "cannot commit derived file" }
    }

    companion object {
        const val DERIVED_DIRECTORY = "derived"
        const val JSON_CATALOG_FILE = "json_catalog.json"
        const val TEXT_REPORT_FILE = "json_catalog.txt"
    }
}
