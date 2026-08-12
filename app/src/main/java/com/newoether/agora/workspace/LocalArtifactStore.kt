package com.newoether.agora.workspace

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class LocalArtifactReference(
    val relativePath: String,
    val byteLength: Long,
    val sha256: String,
    val mediaType: String = "text/plain; charset=utf-8",
)

/**
 * Content-addressed, app-private fact store for payloads that must outlive a failed model request.
 * Room messages keep small previews and these references; complete bytes are never silently clipped.
 */
class LocalArtifactStore(context: Context) {
    private val root = File(context.filesDir, DIRECTORY_NAME)

    init {
        require(root.mkdirs() || root.isDirectory) { "cannot create local artifact directory" }
    }

    fun putText(content: String, mediaType: String = inferMediaType(content)): LocalArtifactReference {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val sha = sha256(bytes)
        val relativePath = "sha256/${sha.take(2)}/$sha"
        val target = resolve(relativePath)
        if (!target.exists()) writeAtomic(target, bytes)
        require(target.isFile && target.length() == bytes.size.toLong()) {
            "local artifact length mismatch"
        }
        return LocalArtifactReference(relativePath, bytes.size.toLong(), sha, mediaType)
    }

    fun readText(reference: LocalArtifactReference): String {
        val target = resolve(reference.relativePath)
        require(target.isFile) { "local artifact is missing" }
        val bytes = target.readBytes()
        require(bytes.size.toLong() == reference.byteLength) { "local artifact length changed" }
        require(sha256(bytes).equals(reference.sha256, ignoreCase = true)) {
            "local artifact checksum changed"
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun file(reference: LocalArtifactReference): File = resolve(reference.relativePath)

    private fun resolve(relativePath: String): File {
        require(SAFE_PATH.matches(relativePath)) { "invalid local artifact path" }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "local artifact path escapes root"
        }
        return target
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        require(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
            "cannot create local artifact parent"
        }
        val temporary = File(target.parentFile, target.name + ".tmp-${System.nanoTime()}")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            if (!target.isFile) {
                temporary.delete()
                error("cannot commit local artifact")
            }
            temporary.delete()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "agora-workspace/artifacts"
        private val SAFE_PATH = Regex("sha256/[0-9a-f]{2}/[0-9a-f]{64}")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun inferMediaType(content: String): String {
            val trimmed = content.trimStart()
            return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                "application/json; charset=utf-8"
            } else {
                "text/plain; charset=utf-8"
            }
        }
    }
}
