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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * GitHub workspace tools: fork/baseline/PR operations plus read-only experiment
 * version locking, run diagnostics and the promotion gate. Experiment tools never
 * mutate a repository; the promotion gate fails closed on any inconsistency.
 */
class GitHubWorkspaceToolProvider(context: Context) : ToolProvider {
    private val api = GitHubApiClient(context.applicationContext)
    private val workspace = GitHubWorkspaceClient(api)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val names = setOf(
        PERMISSIONS, BRANCHES, CREATE_FORK, CREATE_BASELINE, SYNC_BASELINE,
        CREATE_UPSTREAM_PR, RERUN, RERUN_FAILED, CANCEL_RUN, SYNC_PLAN, DIAGNOSE_RUN,
        LOCK_EXPERIMENT, VERIFY_EXPERIMENT, PROMOTION_GATE,
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        tool(PERMISSIONS, "Read repository permissions and fork parent/source metadata.", mapOf("repo" to text("Repository owner/name.")), listOf("repo")),
        tool(BRANCHES, "List repository branches and exact head SHAs for workspace selection.", mapOf("repo" to text("Repository owner/name.")), listOf("repo")),
        tool(SYNC_PLAN, "Read-only upstream synchronization plan. Reports exact SHAs, ahead/behind, divergence, and whether an isolated workbench merge is required. Never mutates a repository.", mapOf(
            "fork_repo" to text("Writable fork owner/name."), "fork_branch" to text("Fork branch to synchronize."),
            "upstream_repo" to text("Parent/upstream repository owner/name."), "upstream_branch" to text("Upstream branch.")),
            listOf("fork_repo", "fork_branch", "upstream_repo", "upstream_branch")),
        tool(DIAGNOSE_RUN, "Diagnose one Actions run. Distinguishes code/test failure from a run that produced no jobs (jobs: []).", mapOf(
            "repo" to text("Repository owner/name."), "run_id" to ToolProperty("integer", "Positive Actions run ID.")), listOf("repo", "run_id")),
        tool(LOCK_EXPERIMENT, "Lock an experiment to one exact source SHA by resolving the branch now. All A/B/C tests of this experiment must use the returned source_sha; any other SHA invalidates the experiment.", mapOf(
            "repo" to text("Experiment repository."), "branch" to text("Experiment branch."),
            "workflow" to text("Workflow file the tests will run."),
            "patches" to text("Comma-separated explicitly allowed runtime patch scripts; empty means none allowed.")),
            listOf("repo", "branch")),
        tool(VERIFY_EXPERIMENT, "Verify the experiment branch still points at the locked SHA. A moved branch invalidates all previous test results.", mapOf(
            "repo" to text("Experiment repository."), "branch" to text("Locked experiment branch."),
            "expected_sha" to text("Exact 40-character locked SHA.")), listOf("repo", "branch", "expected_sha")),
        tool(PROMOTION_GATE, "Decide whether an experiment may be promoted. Requires: run produced jobs, all required tests passed, attribute/score/PT deltas all positive, and every observed job SHA equals the locked source SHA. Fails closed.", mapOf(
            "run_diagnosis_json" to text("JSON returned by github_workspace_diagnose_run."),
            "attribute_delta" to ToolProperty("number", "Attribute improvement; must be > 0."),
            "score_delta" to ToolProperty("number", "Score improvement; must be > 0."),
            "pt_delta" to ToolProperty("number", "PT improvement; must be > 0."),
            "tests_passed" to ToolProperty("integer", "Number of required tests passed."),
            "tests_required" to ToolProperty("integer", "Number of required tests."),
            "source_sha" to text("Exact 40-character locked source SHA."),
            "observed_shas" to text("Comma-separated SHAs observed by the test jobs.")),
            listOf("run_diagnosis_json", "attribute_delta", "score_delta", "pt_delta", "tests_passed", "tests_required", "source_sha", "observed_shas")),
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
        val args = runCatching { json.decodeFromString<Map<String, JsonPrimitive?>>(arguments.ifBlank { "{}" }) }
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
                SYNC_PLAN -> syncPlan(s("fork_repo"), s("fork_branch"), s("upstream_repo"), s("upstream_branch"))
                DIAGNOSE_RUN -> diagnoseRun(s("repo"), l("run_id"))
                LOCK_EXPERIMENT -> lockExperiment(s("repo"), s("branch"), s("workflow"), s("patches"))
                VERIFY_EXPERIMENT -> verifyExperiment(s("repo"), s("branch"), s("expected_sha"))
                PROMOTION_GATE -> promotionGate(s("run_diagnosis_json"), s("attribute_delta").toDoubleOrNull(), s("score_delta").toDoubleOrNull(), s("pt_delta").toDoubleOrNull(), l("tests_passed"), l("tests_required"), s("source_sha"), s("observed_shas"))
                CREATE_FORK -> buildJsonObject { put("repository", workspace.createFork(s("upstream_repo"), s("organization").ifBlank { null })); put("ok", true) }.toString()
                CREATE_BASELINE -> buildJsonObject { put("sha", workspace.createBaselineBranch(s("fork_repo"), s("branch"), s("upstream_repo"), s("upstream_branch"))); put("ok", true) }.toString()
                SYNC_BASELINE -> buildJsonObject { put("sha", workspace.syncBaselineBranch(s("fork_repo"), s("branch"), s("upstream_repo"), s("upstream_branch"), s("expected_fork_sha"), b("allow_reset"))); put("ok", true) }.toString()
                CREATE_UPSTREAM_PR -> json.encodeToString(workspace.createCrossForkPullRequest(s("source_repo"), s("source_branch"), s("expected_source_sha"), s("target_repo"), s("target_branch"), s("title"), s("body"), b("draft")))
                RERUN -> { workspace.rerunWorkflow(s("repo"), l("run_id"), false); ok() }
                RERUN_FAILED -> { workspace.rerunWorkflow(s("repo"), l("run_id"), true); ok() }
                CANCEL_RUN -> { workspace.cancelWorkflow(s("repo"), l("run_id")); ok() }
                else -> error("Unknown workspace tool")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error(e.message ?: "GitHub workspace operation failed") }
    }

    /** Read-only: resolve the branch to an exact SHA and record the experiment lock. */
    private suspend fun lockExperiment(repo: String, branch: String, workflow: String, patches: String): String {
        val safe = api.validateRepo(repo)
        require(branch.isNotBlank()) { "Branch is required" }
        val sha = ref(safe, branch)
        val patchList = patches.split(',').map(String::trim).filter(String::isNotBlank)
        return buildJsonObject {
            put("ok", true)
            put("experiment_id", "$safe:$branch@$sha")
            put("repository", safe); put("branch", branch); put("source_sha", sha)
            put("workflow", workflow)
            putJsonList("patches_declared", patchList)
            put("patches_allowed", patchList.isEmpty())
            put("instruction", "All A/B/C tests must run this exact source_sha. A moved branch or a different observed SHA invalidates every previous result.")
        }.toString()
    }

    /** Read-only: confirm the branch still points at the locked SHA. */
    private suspend fun verifyExperiment(repo: String, branch: String, expected: String): String {
        fullSha(expected)
        val actual = ref(api.validateRepo(repo), branch)
        val match = actual.equals(expected, ignoreCase = true)
        return buildJsonObject {
            put("ok", true); put("source_sha_match", match); put("promotion_allowed", match)
            put("expected_sha", expected); put("actual_sha", actual)
            put("verdict", if (match) "experiment_source_unchanged" else "SOURCE_MOVED_ALL_PREVIOUS_RESULTS_INVALID")
        }.toString()
    }

    /** Fails closed: empty jobs, SHA mismatch, any non-positive delta, or missing tests block promotion. */
    private fun promotionGate(diagnosis: String, attribute: Double?, score: Double?, pt: Double?, passed: Long, required: Long, source: String, observed: String): String {
        val diag = runCatching { json.parseToJsonElement(diagnosis).jsonObject }.getOrNull()
        val jobs = diag?.get("job_count")?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
        val shas = observed.split(',').map(String::trim).filter(String::isNotBlank)
        val shaFormatOk = source.matches(Regex("[0-9a-fA-F]{40}"))
        val shaConsistent = shaFormatOk && shas.isNotEmpty() && shas.all { it.equals(source, ignoreCase = true) }
        val metricsOk = attribute != null && attribute > 0 && score != null && score > 0 && pt != null && pt > 0
        val testsOk = required > 0 && passed >= required
        val promotable = jobs > 0 && shaConsistent && metricsOk && testsOk
        val reason = when {
            jobs == 0L -> "jobs_empty_or_not_verified"
            jobs < 0 -> "run_diagnosis_missing_or_invalid"
            !shaFormatOk -> "source_sha_not_a_full_40_char_sha"
            !shaConsistent -> "observed_sha_mismatch_jobs_ran_a_different_source"
            !metricsOk -> "attribute_score_pt_must_all_be_positive"
            !testsOk -> "required_tests_not_all_passed"
            else -> "all_promotion_gates_passed"
        }
        return buildJsonObject {
            put("ok", true); put("promotable", promotable); put("reason", reason)
            put("jobs", jobs); put("source_sha_consistent", shaConsistent)
            put("attribute_positive", attribute != null && attribute > 0)
            put("score_positive", score != null && score > 0)
            put("pt_positive", pt != null && pt > 0)
            put("tests_complete", testsOk)
            put("next_action", if (promotable) "extract_production_files_and_open_release_candidate" else "do_not_promote_fix_the_failing_gate_first")
        }.toString()
    }

    private suspend fun syncPlan(fork: String, forkBranch: String, upstream: String, upstreamBranch: String): String {
        val safeFork = api.validateRepo(fork); val safeUpstream = api.validateRepo(upstream)
        require(forkBranch.isNotBlank() && upstreamBranch.isNotBlank()) { "Both branch names are required" }
        val forkRef = ref(safeFork, forkBranch)
        val upstreamRef = ref(safeUpstream, upstreamBranch)
        val owner = safeFork.substringBefore('/')
        val compareResponse = api.request("GET", "/repos/$safeUpstream/compare/${api.encodeSegment(upstreamBranch)}...${api.encodeSegment("$owner:$forkBranch")}")
        requireSuccess(compareResponse.code, compareResponse.body)
        val compare = json.parseToJsonElement(compareResponse.body).jsonObject
        val ahead = compare.number("ahead_by"); val behind = compare.number("behind_by")
        val status = compare.string("status", "unknown")
        val action = when {
            status == "identical" -> "up_to_date"
            ahead == 0L && behind > 0L -> "fast_forward_fork_from_upstream"
            ahead > 0L && behind == 0L -> "upstream_can_fast_forward_from_fork"
            else -> "diverged_requires_merge_resolution"
        }
        return buildJsonObject {
            put("ok", true); put("fork_repository", safeFork); put("fork_branch", forkBranch); put("fork_sha", forkRef)
            put("upstream_repository", safeUpstream); put("upstream_branch", upstreamBranch); put("upstream_sha", upstreamRef)
            put("status", status); put("ahead_by", ahead); put("behind_by", behind); put("action", action)
            put("safe_to_direct_fast_forward", action == "fast_forward_fork_from_upstream")
            put("requires_isolated_workbench_merge", action == "diverged_requires_merge_resolution")
            put("warning", if (action == "diverged_requires_merge_resolution") "Do not reset either branch. Create a workbench/* merge branch, resolve conflicts, validate, then open a PR." else "Read-only plan; no remote mutation was performed.")
        }.toString()
    }

    private suspend fun diagnoseRun(repo: String, runId: Long): String {
        val safe = api.validateRepo(repo); require(runId > 0)
        val runResponse = api.request("GET", "/repos/$safe/actions/runs/$runId")
        requireSuccess(runResponse.code, runResponse.body)
        val run = json.parseToJsonElement(runResponse.body).jsonObject
        val jobsResponse = api.request("GET", "/repos/$safe/actions/runs/$runId/jobs?per_page=100")
        requireSuccess(jobsResponse.code, jobsResponse.body)
        val jobs = json.parseToJsonElement(jobsResponse.body).jsonObject["jobs"]?.jsonArray ?: JsonArray(emptyList())
        val status = run.string("status"); val conclusion = run.string("conclusion")
        val category = when {
            jobs.isEmpty() -> "workflow_not_executed_or_jobs_unavailable"
            conclusion == "failure" -> "code_or_test_failure"
            conclusion == "cancelled" -> "workflow_cancelled"
            conclusion == "skipped" -> "workflow_skipped"
            status != "completed" -> "workflow_in_progress"
            else -> "workflow_completed"
        }
        return buildJsonObject {
            put("ok", true); put("run_id", runId); put("name", run.string("name")); put("status", status)
            put("conclusion", conclusion); put("head_branch", run.string("head_branch")); put("head_sha", run.string("head_sha"))
            put("job_count", jobs.size); put("category", category)
            put("is_code_failure", category == "code_or_test_failure")
            put("needs_actions_or_permission_check", category == "workflow_not_executed_or_jobs_unavailable")
            put("advice", if (jobs.isEmpty()) "No job was created. Check workflow trigger, file validity, Actions permissions, fork policy, and billing/availability before changing code." else "Inspect failed job steps and logs before editing source code.")
        }.toString()
    }

    private suspend fun ref(repo: String, branch: String): String {
        val response = api.request("GET", "/repos/$repo/git/ref/heads/${api.encodeSegment(branch)}")
        requireSuccess(response.code, response.body)
        return json.parseToJsonElement(response.body).jsonObject["object"]!!.jsonObject.string("sha")
    }
    private fun requireSuccess(code: Int, body: String) { if (code !in 200..299) error("GitHub HTTP $code: ${body.take(500)}") }
    private fun fullSha(value: String) { require(value.matches(Regex("[0-9a-fA-F]{40}"))) { "Expected an exact 40-character SHA" } }
    private fun JsonObject.string(key: String, default: String = "") = this[key]?.jsonPrimitive?.content ?: default
    private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonList(key: String, values: List<String>) {
        kotlinx.serialization.json.putJsonArray(key) { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }
    private fun runProperties() = mapOf("repo" to text("Repository owner/name."), "run_id" to ToolProperty("integer", "Positive Actions run ID."))
    private fun text(description: String) = ToolProperty("string", description)
    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String>) = ToolDefinition(function = ToolFunction(name = name, description = description, parameters = ToolParameters(properties = properties, required = required)))
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
        const val SYNC_PLAN = "github_workspace_sync_plan"
        const val DIAGNOSE_RUN = "github_workspace_diagnose_run"
        const val LOCK_EXPERIMENT = "github_workspace_lock_experiment"
        const val VERIFY_EXPERIMENT = "github_workspace_verify_experiment"
        const val PROMOTION_GATE = "github_workspace_promotion_gate"
    }
}
