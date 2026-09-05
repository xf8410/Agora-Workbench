package com.newoether.agora.ramen

import android.content.Context
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.uma.UmaGitBlobResult
import com.newoether.agora.uma.UmaGitBlobUploader
import com.newoether.agora.uma.UmaGitCommitClient
import com.newoether.agora.uma.UmaGitTreeBlob
import com.newoether.agora.uma.UmaGitTreeClient
import com.newoether.agora.util.Constants
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class RamenUploadResult(
    val repository: String,
    val path: String,
    val recordCount: Int,
    val commitSha: String,
)

/** Extracts the sequence number used to advance the /data after cursor. */
internal fun ramenRecordSeq(record: JsonElement): Long? =
    ((record as? JsonObject)?.get(Constants.RAMEN_RECORD_SEQ_FIELD) as? JsonPrimitive)
        ?.content?.toLongOrNull()

/**
 * Returns the next after-cursor for the /data pagination, or null when this page
 * signals the end of the data (short page without sequence numbers).
 */
internal fun ramenNextAfter(records: List<JsonElement>, pageLimit: Int, currentAfter: Long): Long? {
    val seqs = records.mapNotNull(::ramenRecordSeq)
    return when {
        seqs.isNotEmpty() -> {
            val next = seqs.max()
            require(next > currentAfter) {
                "对端分页未推进（after=$currentAfter），已停止以避免重复上传"
            }
            next
        }
        records.size < pageLimit -> null
        else -> throw IllegalStateException("对端记录缺少 ${Constants.RAMEN_RECORD_SEQ_FIELD} 字段，无法继续分页")
    }
}

/** JSONL body = one untouched record per line; PC-side tools group by run, so fields stay raw. */
internal fun buildRamenJsonl(records: List<JsonElement>): String =
    records.joinToString(separator = "\n", postfix = "\n") { it.toString() }

/** Peer-compatible object path: data/{yyyyMMdd}/{HHmmss-SSS}-{uuid8}.jsonl (globally unique). */
internal fun ramenUploadObjectPath(now: ZonedDateTime, uuid8: String): String {
    val day = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).format(now)
    val clock = DateTimeFormatter.ofPattern("HHmmss-SSS", Locale.US).format(now)
    require(uuid8.length == 8 && uuid8.none { it.isWhitespace() }) { "invalid uuid8 segment" }
    return "data/$day/$clock-$uuid8.jsonl"
}

internal fun ramenUuid8(): String = UUID.randomUUID().toString().substring(0, 8)

internal fun ramenCommitMessage(recordCount: Int): String =
    "${Constants.RAMEN_UPLOAD_COMMIT_MESSAGE_PREFIX} $recordCount 条决策记录"

/** The ramen data repository commits to its fixed main branch, unlike workbench/* session archives. */
internal fun requireRamenUploadBranch(branch: String): String {
    require(branch == Constants.RAMEN_UPLOAD_BRANCH) {
        "ramen uploads must target the fixed ${Constants.RAMEN_UPLOAD_BRANCH} branch"
    }
    return branch
}

/**
 * Uploads the full decision-log dataset of a juece-ramen peer to the fixed GitHub repository,
 * reusing the mature Uma blob → tree → commit pipeline. One JSONL file per upload, matching the
 * peer's own fallback-channel path format. A successful upload never clears the peer's memory.
 */
class RamenGitHubUploader(
    context: Context,
    private val github: GitHubApiClient = GitHubApiClient(context),
) {
    private val appContext = context.applicationContext
    private val blobUploader = UmaGitBlobUploader(github)
    private val treeClient = UmaGitTreeClient(github)
    private val commitClient = UmaGitCommitClient(github, requireBranch = ::requireRamenUploadBranch)

    suspend fun uploadAll(dataSource: RamenJueceClient): RamenUploadResult = try {
        require(github.isSignedIn()) { "GitHub 未登录：请先在「GitHub Workbench」设置中登录" }
        val records = fetchAllRecords(dataSource)
        if (records.isEmpty()) {
            RamenUploadResult(Constants.RAMEN_UPLOAD_REPO, path = "", recordCount = 0, commitSha = "")
        } else {
            val jsonl = buildRamenJsonl(records)
            val byteLength = jsonl.toByteArray(Charsets.UTF_8).size.toLong()
            require(byteLength <= Constants.RAMEN_UPLOAD_MAX_JSONL_BYTES) {
                "JSONL 超过单文件上限（${Constants.RAMEN_UPLOAD_MAX_JSONL_BYTES / 1024 / 1024} MiB），请先清空对端数据"
            }
            val path = ramenUploadObjectPath(ZonedDateTime.now(), ramenUuid8())
            val blob = uploadBlob(jsonl)
            val base = commitClient.readBranchBase(Constants.RAMEN_UPLOAD_REPO, Constants.RAMEN_UPLOAD_BRANCH)
            val tree = treeClient.create(
                Constants.RAMEN_UPLOAD_REPO,
                base.treeSha,
                directory = "",
                blobs = listOf(UmaGitTreeBlob(path, blob.blobSha)),
            )
            require(tree.entryCount == 1) { "Git tree entry count mismatch" }
            val commit = commitClient.commitAndAdvance(
                Constants.RAMEN_UPLOAD_REPO,
                base,
                tree.treeSha,
                ramenCommitMessage(records.size),
            )
            RamenUploadResult(Constants.RAMEN_UPLOAD_REPO, path, records.size, commit.commitSha)
        }
    } catch (error: Throwable) {
        throw mapUploadError(error)
    }

    private suspend fun fetchAllRecords(dataSource: RamenJueceClient): List<JsonElement> {
        val records = mutableListOf<JsonElement>()
        var after = 0L
        while (true) {
            require(records.size <= Constants.RAMEN_UPLOAD_MAX_RECORDS) {
                "对端数据超过单次上传上限（${Constants.RAMEN_UPLOAD_MAX_RECORDS} 条），请先清空对端数据"
            }
            val page = dataSource.data(Constants.RAMEN_UPLOAD_PAGE_LIMIT, after)
            if (page.records.isEmpty()) break
            records += page.records
            val next = ramenNextAfter(page.records, Constants.RAMEN_UPLOAD_PAGE_LIMIT, after) ?: break
            after = next
        }
        return records
    }

    private suspend fun uploadBlob(content: String): UmaGitBlobResult = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }
        val file = File(directory, "upload-${ramenUuid8()}-${ramenUuid8()}.jsonl")
        try {
            file.writeText(content, Charsets.UTF_8)
            blobUploader.upload(Constants.RAMEN_UPLOAD_REPO, file)
        } finally {
            file.delete()
        }
    }

    private fun mapUploadError(error: Throwable): Throwable = when {
        error.message?.contains(NOT_SIGNED_IN_MARK) == true ->
            IllegalStateException("GitHub 未登录：请先在「GitHub Workbench」设置中登录", error)
        error.message?.contains("(HTTP 403)") == true || error.message?.contains("(HTTP 404)") == true ->
            IllegalStateException("当前 token 无该仓库写入权限，请在 GitHub token 设置中补充（${error.message}）", error)
        else -> error
    }

    private companion object {
        const val TEMP_DIR_NAME = "ramen-upload"
        const val NOT_SIGNED_IN_MARK = "GitHub is not signed in"
    }
}
