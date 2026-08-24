package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.github.GitHubWorkspaceClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Generic repository/fork workspace tools; no repository or branch is compiled into this provider. */
class GitHubWorkspaceToolProvider(context: Context) : ToolProvider {
    private val workspace = GitHubWorkspaceClient(GitHubApiClient(context.applicationContext))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val names = setOf(
        PERMISSIONS, BRANCHES, CREATE_FORK, CREATE_BASELINE, SYNC_BASELINE,
        CREATE_UPSTREAM_PR, RERUN, RERUN_FAILED, CANCEL_RUN,
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        tool(PERMISSIONS, "Read repository permissions and fork parent/source metadata.", mapOf("repo" to text("Repository owner/name.")), listOf("repo")),
        tool(BRANCHES, "List repository branches and exact head SHAs for workspace selection.", mapOf("repo" to text("Repository owner/name.")), listOf("repo")),
        tool(CREATE_FORK, "Create a fork after explicit confirmation and wait until it is ready.", mapOf(
            "upstream_repo" to text("Repository to fork."), "organization" to text("Optional destination organization.")), listOf("upstream_repo")),
        tool(CREATE_BASELINE, "Create a configurable baseline branch in a verified fork from an upstream branch after confirmation.", mapOf(
            "fork_repo" to text("Writable fork."), "branch" to text("New baseline branch."),
            "upstream_repo" to text("Verified upstream repository."), "upstream_branch" to text("Existing upstream branch.")),
            listOf("fork_repo", "branch", "upstream_repo", "upstream_branch")),
        tool(SYNC_BASELINE, "Fast-forward or explicitly reset a fork baseline to an upstream branch with exact old-SHA verification.", mapOf(
            "fork_repo" to text("Writable fork."), "branch" to text("Fork baseline branch."),
            "upstream_repo" to text("Verified upstream repository."), "upstream_branch" to text("Upstream branch."),
            "expected_fork_sha" to text("Exact current 40-character fork SHA."),
            "allow_reset" to ToolProperty("boolean", "True only after the user explicitly requests a destructive reset.")),
            listOf("fork_repo", "branch", "upstream_repo", "upstream_branch", "expected_fork_sha")),
        tool(CREATE_UPSTREAM_PR, "Create and verify a cross-fork upstream PR. Source must be a workbench/* branch and is pinned to an exact SHA.", mapOf(
            "source_repo" to text("Writable fork repository."), "source_branch" to text("Fork workbench/* branch."),
            "expected_source_sha" to text("Exact 40-character source SHA."), "target_repo" to text("Upstream repository receiving the PR."),
            "target_branch" to text("Upstream base branch."), "title" to text("PR title."), "body" to text("Optional PR body."),
            "draft" to ToolProperty("boolean", "Create as draft; defaults false.")),
            listOf("source_repo", "source_branch", "expected_source_sha", "target_repo", "target_branch", "title")),
        tool(RERUN, "Rerun a complete Actions run after confirmation.", runProperties(), listOf("repo", "run_id")),
        tool(RERUN_FAILED, "Rerun only failed jobs in an Actions run after confirmation.", runProperties(), listOf("repo", "run_id")),
        tool(CANCEL_RUN, "Cancel an Actions run after confirmation.", runProperties(), listOf("repo", "run_id")),
    )

    override fun handles(name: String): Boolean = name in names

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return error("Invalid tool arguments") }
        fun s(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        fun b(key: String) = s(key).toBooleanStrictOrNull() ?: false
        fun l(key: String) = s(key).toLongOrNull() ?: 0L
        if (ctx.githubWorkspaceMode) {
            listOf("repo", "fork_repo", "upstream_repo", "source_repo", "target_repo")
                .map(::s).filter { it.isNotBlank() }.forEach { repo ->
                    require(repo in ctx.githubAllowedRepositories) { "Repository is outside the active workspace stage" }
                }
        }
        return try {
            when (name) {
                PERMISSIONS -> json.encodeToString(workspace.permissions(s("repo")))
                BRANCHES -> json.encodeToString(workspace.branches(s("repo")))
                CREATE_FORK -> buildJsonObject { put("repository", workspace.createFork(s("upstream_repo"), s("organization").ifBlank { null })); put("ok", true) }.toString()
                CREATE_BASELINE -> buildJsonObject { put("sha", workspace.createBaselineBranch(s("fork_repo"), s("branch"), s("upstream_repo"), s("upstream_branch"))); put("ok", true) }.toString()
                SYNC_BASELINE -> buildJsonObject { put("sha", workspace.syncBaselineBranch(s("fork_repo"), s("branch"), s("upstream_repo"), s("upstream_branch"), s("expected_fork_sha"), b("allow_reset"))); put("ok", true) }.toString()
                CREATE_UPSTREAM_PR -> json.encodeToString(workspace.createCrossForkPullRequest(
                    s("source_repo"), s("source_branch"), s("expected_source_sha"), s("target_repo"),
                    s("target_branch"), s("title"), s("body"), b("draft")))
                RERUN -> { workspace.rerunWorkflow(s("repo"), l("run_id"), false); ok() }
                RERUN_FAILED -> { workspace.rerunWorkflow(s("repo"), l("run_id"), true); ok() }
                CANCEL_RUN -> { workspace.cancelWorkflow(s("repo"), l("run_id")); ok() }
                else -> error("Unknown workspace tool")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error(e.message ?: "GitHub workspace operation failed") }
    }

    private fun runProperties() = mapOf("repo" to text("Repository owner/name."), "run_id" to ToolProperty("integer", "Positive Actions run ID."))
    private fun text(description: String) = ToolProperty("string", description)
    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String>) =
        ToolDefinition(function = ToolFunction(name, description, ToolParameters(properties, required)))
    private fun ok() = buildJsonObject { put("ok", true) }.toString()
    private fun error(message: String) = buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    private companion object {
        const val PERMISSIONS = "github_workspace_permissions"
        const val BRANCHES = "github_workspace_branches"
        const val CREATE_FORK = "github_create_fork"
        const val CREATE_BASELINE = "github_create_baseline_branch"
        const val SYNC_BASELINE = "github_sync_baseline_branch"
        const val CREATE_UPSTREAM_PR = "github_create_upstream_pull_request"
        const val RERUN = "github_rerun_workflow"
        const val RERUN_FAILED = "github_rerun_failed_jobs"
        const val CANCEL_RUN = "github_cancel_workflow_run"
    }
}
