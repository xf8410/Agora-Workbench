package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class UmaFinalizerDerivedBlobCheckpoint(
    val sessionId: String,
    val repository: String,
    val blobs: List<UmaSessionUploadedBlob> = emptyList(),
)

/** Creates one complete Tree and advances the existing workbench branch once, without force. */
class UmaSessionUploadFinalizer(
    private val filesClient: UmaStorageFilesClient,
    private val treeClient: UmaGitTreeClient,
    private val commitClient: UmaGitCommitClient,
    private val taskStore: UmaSessionUploadTaskStore,
    private val generator: UmaSessionDerivedJsonGenerator = UmaSessionDerivedJsonGenerator(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun finalize(taskId: String, rootDirectory: File): UmaSessionUploadProgress =
        withContext(Dispatchers.IO) {
            val record = requireNotNull(taskStore.read(taskId)) { "upload task does not exist" }
            require(record.progress.phase in setOf(
                UmaSessionUploadPhase.TREE,
                UmaSessionUploadPhase.COMMIT,
            )) { "upload task is not ready for finalization" }
            val task = record.task
            val indexed = filesClient.listAll(task.sessionId)
            val raw = readRawCheckpoint(
                File(rootDirectory, UmaSessionGitBlobPipeline.CHECKPOINT_FILE_NAME)
            )
            val derivedCheckpoint = readDerivedCheckpoint(
                File(rootDirectory, UmaSessionDerivedBlobBatchExecutor.CHECKPOINT_FILE_NAME)
            )
            require(raw.sessionId == task.sessionId && raw.repository == task.repository) {
                "raw Blob checkpoint identity mismatch"
            }
            require(derivedCheckpoint.sessionId == task.sessionId &&
                derivedCheckpoint.repository == task.repository) {
                "derived Blob checkpoint identity mismatch"
            }
            require(raw.blobs.size == indexed.size) { "raw Blob upload is incomplete" }
            require(raw.blobs.map { it.relativePath }.toSet().size == raw.blobs.size) {
                "raw Blob checkpoint contains duplicate paths"
            }

            val derived = generator.generate(task.sessionId, rootDirectory, indexed)
            val expectedDerivedPaths = derived.files.map { validateUmaArchivePath(it.first) }.toSet()
            require(derivedCheckpoint.blobs.size == expectedDerivedPaths.size) {
                "derived Blob upload is incomplete"
            }
            require(derivedCheckpoint.blobs.map { it.relativePath }.toSet() == expectedDerivedPaths) {
                "derived Blob checkpoint paths differ from generated files"
            }
            val all = (raw.blobs + derivedCheckpoint.blobs).map {
                UmaGitTreeBlob(validateUmaArchivePath(it.relativePath), it.blobSha)
            }
            require(all.map { it.relativePath }.toSet().size == all.size) {
                "duplicate path across raw and derived Blobs"
            }

            val checkpointFile = File(rootDirectory, CHECKPOINT_FILE_NAME)
            val prior = readPublishCheckpoint(checkpointFile)
            if (prior != null) {
                require(prior.session_id == task.sessionId && prior.repository == task.repository &&
                    prior.branch == task.branch && prior.target_directory == task.targetDirectory) {
                    "publish checkpoint belongs to different task arguments"
                }
                val current = commitClient.readBranchBase(task.repository, task.branch)
                if (current.treeSha == prior.tree_sha) {
                    return@withContext persistComplete(record, derived, prior.tree_sha, current.headCommitSha)
                }
                require(current.headCommitSha == prior.expected_head_sha) {
                    "branch head changed after publish checkpoint"
                }
            }

            val base = commitClient.readBranchBase(task.repository, task.branch)
            if (prior != null) require(base.headCommitSha == prior.expected_head_sha) {
                "branch head changed before finalization"
            }
            val treeSha = if (prior != null) {
                prior.tree_sha
            } else {
                val tree = treeClient.createBatched(
                    repo = task.repository,
                    baseTreeSha = base.treeSha,
                    directory = task.targetDirectory,
                    blobs = all,
                )
                require(tree.entryCount == all.size) { "Git tree entry count mismatch" }
                writePublishCheckpoint(checkpointFile, UmaSessionPublishCheckpoint(
                    session_id = task.sessionId,
                    repository = task.repository,
                    branch = task.branch,
                    target_directory = task.targetDirectory,
                    expected_head_sha = base.headCommitSha,
                    tree_sha = tree.treeSha,
                ))
                taskStore.update(taskId) { current -> current.copy(progress = current.progress.copy(
                    phase = UmaSessionUploadPhase.COMMIT,
                    treeSha = tree.treeSha,
                    checkpointUpdatedAtMs = clock(),
                    lastError = null,
                )) }
                tree.treeSha
            }

            val commit = commitClient.commitAndAdvance(
                task.repository,
                base,
                treeSha,
                task.commitMessage,
            )
            writePublishCheckpoint(checkpointFile, UmaSessionPublishCheckpoint(
                session_id = task.sessionId,
                repository = task.repository,
                branch = task.branch,
                target_directory = task.targetDirectory,
                expected_head_sha = base.headCommitSha,
                tree_sha = treeSha,
                commit_sha = commit.commitSha,
            ))
            persistComplete(record, derived, treeSha, commit.commitSha)
        }

    private fun persistComplete(
        original: UmaSessionUploadTaskRecord,
        derived: UmaSessionDerivedJsonResult,
        treeSha: String,
        commitSha: String,
    ): UmaSessionUploadProgress = taskStore.update(original.task.taskId) { current ->
        require(current.task == original.task) { "upload task arguments changed during finalization" }
        current.copy(progress = current.progress.copy(
            phase = UmaSessionUploadPhase.COMPLETE,
            derivedTotalFiles = derived.files.size,
            derivedCompletedFiles = derived.files.size,
            checkpointUpdatedAtMs = clock(),
            lastError = null,
            treeSha = treeSha,
            commitSha = commitSha,
        ))
    }.progress

    private fun readRawCheckpoint(file: File): UmaSessionGitBlobCheckpoint {
        require(file.isFile) { "raw Blob checkpoint is missing" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun readDerivedCheckpoint(file: File): UmaFinalizerDerivedBlobCheckpoint {
        require(file.isFile) { "derived Blob checkpoint is missing" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun readPublishCheckpoint(file: File): UmaSessionPublishCheckpoint? {
        if (!file.exists()) return null
        require(file.isFile) { "publish checkpoint path is not a file" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun writePublishCheckpoint(file: File, checkpoint: UmaSessionPublishCheckpoint) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) require(file.delete()) { "cannot replace publish checkpoint" }
        require(temporary.renameTo(file)) { "cannot commit publish checkpoint" }
    }

    companion object {
        const val CHECKPOINT_FILE_NAME = ".uma-session-publish.json"
    }
}
