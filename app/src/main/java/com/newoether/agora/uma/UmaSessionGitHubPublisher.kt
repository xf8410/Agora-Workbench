package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class UmaSessionPublishCheckpoint(
    val session_id: String,
    val repository: String,
    val branch: String,
    val target_directory: String,
    val expected_head_sha: String,
    val tree_sha: String,
    val commit_sha: String? = null,
)

data class UmaSessionGitHubPublishResult(
    val sessionId: String,
    val repository: String,
    val branch: String,
    val targetDirectory: String,
    val rawFileCount: Int,
    val derivedFileCount: Int,
    val totalTreeEntries: Int,
    val decodedPayloadCount: Int,
    val decodeErrorCount: Int,
    val exchangeCount: Int,
    val treeSha: String,
    val commitSha: String,
    val resumed: Boolean,
)

/** Publishes unchanged raw session files and rebuildable derived JSON in one verified commit. */
class UmaSessionGitHubPublisher(
    private val filesClient: UmaStorageFilesClient,
    private val blobPipeline: UmaSessionGitBlobPipeline,
    private val blobUploader: UmaGitBlobUploader,
    private val treeClient: UmaGitTreeClient,
    private val commitClient: UmaGitCommitClient,
    private val derivedGenerator: UmaSessionDerivedJsonGenerator = UmaSessionDerivedJsonGenerator(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    suspend fun publish(
        sessionId: String,
        repository: String,
        branch: String,
        targetDirectory: String,
        commitMessage: String,
        rootDirectory: File,
    ): UmaSessionGitHubPublishResult = withContext(Dispatchers.IO) {
        requireUmaWorkbenchBranch(branch)
        val safeTarget = validateTargetDirectory(targetDirectory)
        require(commitMessage.isNotBlank() && commitMessage.length <= 500) {
            "commit_message must be 1-500 characters"
        }
        val raw = blobPipeline.upload(sessionId, repository, rootDirectory)
        val indexed = filesClient.listAll(sessionId)
        require(indexed.size == raw.fileCount) { "SO file index changed after raw blob upload" }
        require(indexed.sumOf { it.byteLength } == raw.totalBytes) {
            "SO byte total changed after raw blob upload"
        }

        val derived = derivedGenerator.generate(sessionId, rootDirectory, indexed)
        val derivedBlobs = derived.files.map { (relativePath, file) ->
            val result = blobUploader.upload(repository, file)
            require(result.byteLength == file.length()) { "derived blob length mismatch: $relativePath" }
            UmaGitTreeBlob(relativePath, result.blobSha)
        }
        val rawBlobs = raw.blobs.map { UmaGitTreeBlob(it.relativePath, it.blobSha) }
        val allBlobs = rawBlobs + derivedBlobs
        require(allBlobs.map { it.relativePath }.toSet().size == allBlobs.size) {
            "duplicate path across raw and derived files"
        }

        val checkpointFile = File(rootDirectory, CHECKPOINT_FILE_NAME)
        val prior = readCheckpoint(checkpointFile)
        if (prior != null) {
            require(prior.session_id == sessionId && prior.repository == repository &&
                prior.branch == branch && prior.target_directory == safeTarget) {
                "publish checkpoint belongs to different arguments"
            }
            val current = commitClient.readBranchBase(repository, branch)
            if (current.treeSha == prior.tree_sha) {
                return@withContext result(
                    sessionId, repository, branch, safeTarget, raw.fileCount, derived,
                    allBlobs.size, prior.tree_sha, current.headCommitSha, true,
                )
            }
            require(current.headCommitSha == prior.expected_head_sha) {
                "branch head changed after publish checkpoint"
            }
        }

        val base = commitClient.readBranchBase(repository, branch)
        if (prior != null) require(base.headCommitSha == prior.expected_head_sha) {
            "branch head changed before tree creation"
        }
        val tree = treeClient.create(repository, base.treeSha, safeTarget, allBlobs)
        require(tree.entryCount == allBlobs.size) { "Git tree entry count mismatch" }
        writeCheckpoint(checkpointFile, UmaSessionPublishCheckpoint(
            sessionId, repository, branch, safeTarget, base.headCommitSha, tree.treeSha,
        ))
        val commit = commitClient.commitAndAdvance(repository, base, tree.treeSha, commitMessage)
        writeCheckpoint(checkpointFile, UmaSessionPublishCheckpoint(
            sessionId, repository, branch, safeTarget, base.headCommitSha, tree.treeSha, commit.commitSha,
        ))
        result(
            sessionId, repository, branch, safeTarget, raw.fileCount, derived,
            allBlobs.size, tree.treeSha, commit.commitSha, false,
        )
    }

    private fun result(
        sessionId: String,
        repository: String,
        branch: String,
        targetDirectory: String,
        rawCount: Int,
        derived: UmaSessionDerivedJsonResult,
        totalEntries: Int,
        treeSha: String,
        commitSha: String,
        resumed: Boolean,
    ) = UmaSessionGitHubPublishResult(
        sessionId, repository, branch, targetDirectory, rawCount, derived.files.size,
        totalEntries, derived.decodedCount, derived.errorCount, derived.exchangeCount,
        treeSha, commitSha, resumed,
    )

    private fun validateTargetDirectory(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "target_directory must not be blank" }
        validateUmaArchivePath("$trimmed/probe")
        return trimmed
    }

    private fun readCheckpoint(file: File): UmaSessionPublishCheckpoint? =
        if (!file.exists()) null else json.decodeFromString(file.readText(Charsets.UTF_8))

    private fun writeCheckpoint(file: File, checkpoint: UmaSessionPublishCheckpoint) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) require(file.delete()) { "cannot replace publish checkpoint" }
        require(temporary.renameTo(file)) { "cannot commit publish checkpoint" }
    }

    companion object { const val CHECKPOINT_FILE_NAME = ".uma-session-publish.json" }
}
