package com.newoether.agora.workspace

import android.content.Context
import com.newoether.agora.github.GitHubApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class WorkspaceLaneId { ITERATION, RELEASE }

@Serializable
data class WorkspaceLaneConfig(
    val id: WorkspaceLaneId,
    val title: String,
    val description: String,
    val forkRepository: String,
    val forkBaseBranch: String,
    val upstreamRepository: String,
    val upstreamBaseBranch: String,
    val squashRequired: Boolean,
)

@Serializable
data class WorkspaceLaneSnapshot(
    val config: WorkspaceLaneConfig,
    val status: String = "idle",
    val aheadBy: Int = 0,
    val behindBy: Int = 0,
    val forkHeadSha: String = "",
    val upstreamHeadSha: String = "",
    val checkedAt: Long? = null,
    val error: String? = null,
)

@Serializable
data class GitHubWorkspaceState(
    val workspaceId: String = "umaai-rs",
    val selectedLane: WorkspaceLaneId = WorkspaceLaneId.ITERATION,
    val lanes: List<WorkspaceLaneSnapshot> = defaultWorkspaceLanes().map(::WorkspaceLaneSnapshot),
)

fun defaultWorkspaceLanes(): List<WorkspaceLaneConfig> = listOf(
    WorkspaceLaneConfig(
        id = WorkspaceLaneId.ITERATION,
        title = "实验迭代",
        description = "保留实验历史、矩阵、工作流与报告",
        forkRepository = "xf8410/umaai-rs",
        forkBaseBranch = "master",
        upstreamRepository = "xulai1001/umaai-rs",
        upstreamBaseBranch = "ramen_workbench",
        squashRequired = false,
    ),
    WorkspaceLaneConfig(
        id = WorkspaceLaneId.RELEASE,
        title = "正式发布",
        description = "从发布基线向上游 master 提交精炼成果",
        forkRepository = "xf8410/umaai-rs",
        forkBaseBranch = "upstream",
        upstreamRepository = "xulai1001/umaai-rs",
        upstreamBaseBranch = "master",
        squashRequired = true,
    ),
)

/** Persists the workspace independently from ordinary chat conversations and drafts. */
class GitHubWorkspaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("github_workspaces", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): GitHubWorkspaceState = runCatching {
        json.decodeFromString<GitHubWorkspaceState>(prefs.getString(KEY_STATE, "").orEmpty())
    }.getOrElse { GitHubWorkspaceState() }

    fun save(state: GitHubWorkspaceState) {
        prefs.edit().putString(KEY_STATE, json.encodeToString(GitHubWorkspaceState.serializer(), state)).apply()
    }

    private companion object { const val KEY_STATE = "active_workspace" }
}

/** Read-only lane status loader. Each lane is addressed explicitly; there is no global branch. */
class GitHubWorkspaceStatusLoader(context: Context) {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun refresh(config: WorkspaceLaneConfig): WorkspaceLaneSnapshot = withContext(Dispatchers.IO) {
        runCatching {
            val upstream = client.request(
                "GET",
                "/repos/${client.validateRepo(config.upstreamRepository)}/git/ref/heads/${client.encodeSegment(config.upstreamBaseBranch)}",
            )
            requireSuccess(upstream.code, upstream.body)
            val upstreamSha = json.parseToJsonElement(upstream.body).jsonObject
                .getValue("object").jsonObject.getValue("sha").jsonPrimitive.content

            val fork = client.request(
                "GET",
                "/repos/${client.validateRepo(config.forkRepository)}/git/ref/heads/${client.encodeSegment(config.forkBaseBranch)}",
            )
            requireSuccess(fork.code, fork.body)
            val forkSha = json.parseToJsonElement(fork.body).jsonObject
                .getValue("object").jsonObject.getValue("sha").jsonPrimitive.content

            val owner = config.forkRepository.substringBefore('/')
            val compare = client.request(
                "GET",
                "/repos/${client.validateRepo(config.upstreamRepository)}/compare/" +
                    client.encodeSegment(config.upstreamBaseBranch) + "..." +
                    client.encodeSegment("$owner:${config.forkBaseBranch}"),
            )
            requireSuccess(compare.code, compare.body)
            val result = json.parseToJsonElement(compare.body).jsonObject
            WorkspaceLaneSnapshot(
                config = config,
                status = result["status"]?.jsonPrimitive?.content ?: "unknown",
                aheadBy = result["ahead_by"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                behindBy = result["behind_by"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                forkHeadSha = forkSha,
                upstreamHeadSha = upstreamSha,
                checkedAt = System.currentTimeMillis(),
            )
        }.getOrElse { failure ->
            WorkspaceLaneSnapshot(
                config = config,
                status = "error",
                checkedAt = System.currentTimeMillis(),
                error = failure.message ?: "Unable to refresh workspace lane",
            )
        }
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) error("GitHub HTTP $code: ${body.take(300)}")
    }
}
