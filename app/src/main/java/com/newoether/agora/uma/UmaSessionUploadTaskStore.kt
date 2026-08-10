package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UmaSessionUploadTaskRecord(
    val task: UmaSessionUploadTask,
    val progress: UmaSessionUploadProgress,
) {
    init {
        require(task.taskId == progress.taskId) { "task and progress IDs differ" }
        require(task.sessionId == progress.sessionId) { "task and progress session IDs differ" }
        require(task.repository == progress.repository) { "task and progress repositories differ" }
        require(task.branch == progress.branch) { "task and progress branches differ" }
        require(task.targetDirectory == progress.targetDirectory) {
            "task and progress target directories differ"
        }
    }
}

/** Durable task state used by short tool calls and a long-running upload worker. */
class UmaSessionUploadTaskStore(
    rootDirectory: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    },
) {
    private val root = File(rootDirectory, DIRECTORY_NAME)
    private val lock = Any()

    init {
        require(root.mkdirs() || root.isDirectory) { "cannot create Uma upload task directory" }
    }

    fun create(record: UmaSessionUploadTaskRecord): UmaSessionUploadTaskRecord = synchronized(lock) {
        val target = taskFile(record.task.taskId)
        require(!target.exists()) { "upload task already exists" }
        writeAtomic(target, record)
        record
    }

    fun read(taskId: String): UmaSessionUploadTaskRecord? = synchronized(lock) {
        val target = taskFile(taskId)
        if (!target.exists()) return@synchronized null
        require(target.isFile) { "upload task path is not a file" }
        json.decodeFromString(target.readText(Charsets.UTF_8))
    }

    fun update(
        taskId: String,
        transform: (UmaSessionUploadTaskRecord) -> UmaSessionUploadTaskRecord,
    ): UmaSessionUploadTaskRecord = synchronized(lock) {
        val target = taskFile(taskId)
        require(target.isFile) { "upload task does not exist" }
        val current = json.decodeFromString<UmaSessionUploadTaskRecord>(target.readText(Charsets.UTF_8))
        val updated = transform(current)
        require(updated.task.taskId == taskId) { "upload task ID cannot change" }
        require(updated.task == current.task) { "upload task arguments cannot change" }
        require(updated.progress.checkpointUpdatedAtMs >= current.progress.checkpointUpdatedAtMs) {
            "upload task checkpoint time cannot move backwards"
        }
        writeAtomic(target, updated)
        updated
    }

    fun list(): List<UmaSessionUploadTaskRecord> = synchronized(lock) {
        root.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(FILE_SUFFIX) && !it.name.endsWith(TEMP_SUFFIX) }
            .map { json.decodeFromString<UmaSessionUploadTaskRecord>(it.readText(Charsets.UTF_8)) }
            .sortedByDescending { it.progress.checkpointUpdatedAtMs }
    }

    private fun taskFile(taskId: String): File {
        require(TASK_ID.matches(taskId)) { "task_id has an invalid format" }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, taskId + FILE_SUFFIX).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "task_id escapes task directory"
        }
        return target
    }

    private fun writeAtomic(target: File, record: UmaSessionUploadTaskRecord) {
        val temporary = File(target.parentFile, target.name + TEMP_SUFFIX)
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(record).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists()) require(target.delete()) { "cannot replace upload task checkpoint" }
        require(temporary.renameTo(target)) { "cannot commit upload task checkpoint" }
    }

    companion object {
        const val DIRECTORY_NAME = "upload-tasks"
        private const val FILE_SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private val TASK_ID = Regex("[A-Za-z0-9._-]{1,240}")
    }
}
