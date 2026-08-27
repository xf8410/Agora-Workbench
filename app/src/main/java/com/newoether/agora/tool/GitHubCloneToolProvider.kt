package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubAuthManager
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.GitHubMutationConfirmation
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Git operations over Agora's persistent Local Sandbox workspace.
 *
 * Repositories live under `/workspace/repos/<owner>/<repo>`. Besides cloning, this
 * provider exposes the complete safe upstream-sync cycle requested by the user:
 * fetch -> merge -> push. Fetch and merge mutate only the local workspace; push is
 * protected by the existing GitHub mutation confirmation gate.
 */
class GitHubCloneToolProvider(
    context: Context,
    sandboxFactory: SandboxManagerFactory?,
) : ToolProvider {
    private val auth = GitHubAuthManager(context.applicationContext)
    private val sandbox: SandboxManager? = sandboxFactory?.takeIf { it.isAvailable() }?.create()
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (sandbox == null) return emptyList()
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        fun bool(description: String) = ToolProperty("boolean", description)
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = TOOL_CLONE,
                description = "Clone one GitHub repository into the fixed persistent /workspace/repos/<owner>/<repo> directory. Defaults to a shallow single-branch clone; never initializes submodules.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to string("Repository in owner/name form."),
                        "ref" to string("Optional branch or tag. Defaults to the remote default branch."),
                        "depth" to integer("Shallow depth, 1-50; defaults to 1."),
                    ),
                    required = listOf("repo"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = TOOL_FETCH,
                description = "Fetch a remote in an existing /workspace/repos clone. Adds or updates the remote when remote_url is supplied. Use unshallow=true before merging long-diverged branches cloned at depth 1.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to string("Local clone in owner/name form."),
                        "remote" to string("Remote name; defaults to upstream."),
                        "remote_url" to string("Optional owner/name shorthand or an https Git URL. Required when the named remote does not exist."),
                        "unshallow" to bool("Expand a shallow clone to full origin history before fetching the requested remote; defaults false."),
                    ),
                    required = listOf("repo"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = TOOL_MERGE,
                description = "Merge one fetched ref into the current branch with git merge --no-edit. Refuses a dirty worktree. On conflict returns exact paths and preserves merge state for resolution; abort=true rolls it back. ref is required unless abort=true.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to string("Local clone in owner/name form."),
                        "ref" to string("Ref to merge, for example upstream/ramen_workbench or an exact SHA."),
                        "abort" to bool("Abort the current merge instead of starting one; defaults false."),
                    ),
                    required = listOf("repo"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = TOOL_PUSH,
                description = "Push one local branch to a configured remote with the logged-in GitHub token. Requires explicit mutation confirmation.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to string("Local clone in owner/name form."),
                        "branch" to string("Local branch to push."),
                        "remote" to string("Remote name; defaults to origin."),
                        "set_upstream" to bool("Set upstream tracking with -u; defaults false."),
                    ),
                    required = listOf("repo", "branch"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name in TOOLS

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in TOOLS) return error("Unknown git tool")
        val manager = sandbox ?: return error("Local F-Droid sandbox is unavailable")
        if (!manager.isAvailable()) return error("Local Sandbox is not installed")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return error("Invalid tool arguments") }
        fun arg(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        fun bool(key: String) = arg(key).toBooleanStrictOrNull() ?: false

        return try {
            when (name) {
                TOOL_CLONE -> clone(
                    manager,
                    arg("repo"),
                    arg("ref"),
                    arg("depth").toIntOrNull()?.coerceIn(1, 50) ?: 1,
                )
                TOOL_FETCH -> fetch(
                    manager,
                    arg("repo"),
                    arg("remote").ifBlank { "upstream" },
                    arg("remote_url"),
                    bool("unshallow"),
                )
                TOOL_MERGE -> merge(manager, arg("repo"), arg("ref"), bool("abort"))
                TOOL_PUSH -> push(
                    manager,
                    arg("repo"),
                    arg("branch"),
                    arg("remote").ifBlank { "origin" },
                    bool("set_upstream"),
                )
                else -> error("Unknown git tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(e.message ?: "Git operation failed")
        }
    }

    private suspend fun clone(manager: SandboxManager, repo: String, ref: String, depth: Int): String {
        val target = targetFor(repo) ?: return error("Repository must be in owner/name form")
        if (ref.isNotEmpty() && !validRef(ref)) return error("Invalid Git ref")
        if (!GitHubMutationConfirmation.confirm(
                "Clone $repo${if (ref.isBlank()) "" else "@$ref"} into $target"
            )) {
            return error("Clone denied or confirmation unavailable")
        }
        return withAskPass(manager) { askPass ->
            val branchArgs = if (ref.isBlank()) "" else " --branch ${quote(ref)}"
            val command = "set -eu; " + gitAvailable() +
                "test ! -e ${quote(target)} || { echo 'target already exists' >&2; exit 17; }; " +
                "mkdir -p ${quote(target.substringBeforeLast('/'))}; " +
                "${authEnv(askPass)} git -c core.hooksPath=/dev/null clone --depth $depth --single-branch --no-tags$branchArgs ${quote("https://github.com/$repo.git")} ${quote(target)}; " +
                "git -C ${quote(target)} rev-parse HEAD; " +
                "git -C ${quote(target)} rev-parse --is-shallow-repository"
            val result = manager.executeCommand(command, "/workspace", 120_000)
            if (result.exitCode != 0) return@withAskPass commandError("clone", repo, result)
            val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val head = lines.firstOrNull { SHA.matches(it) }.orEmpty()
            val shallow = lines.lastOrNull { it == "true" || it == "false" }
                ?.toBooleanStrictOrNull() ?: true
            buildJsonObject {
                put("ok", true)
                put("operation", "clone")
                put("repo", repo)
                put("ref", ref.ifBlank { "default" })
                put("path", target)
                put("head_sha", head)
                put("shallow", shallow)
                put("depth", depth)
            }.toString()
        }
    }

    private suspend fun fetch(
        manager: SandboxManager,
        repo: String,
        remote: String,
        remoteUrlInput: String,
        unshallow: Boolean,
    ): String {
        val target = targetFor(repo) ?: return error("Repository must be in owner/name form")
        if (!REMOTE.matches(remote)) return error("Invalid remote name")
        val remoteUrl = normalizeRemoteUrl(remoteUrlInput) ?: return error(
            "remote_url must be owner/name shorthand or an https URL"
        )
        return withAskPass(manager) { askPass ->
            val configureRemote = if (remoteUrl.isBlank()) {
                "git -C ${quote(target)} remote get-url ${quote(remote)} >/dev/null 2>&1 || { echo 'remote does not exist; provide remote_url' >&2; exit 21; }; "
            } else {
                "if git -C ${quote(target)} remote get-url ${quote(remote)} >/dev/null 2>&1; then " +
                    "git -C ${quote(target)} remote set-url ${quote(remote)} ${quote(remoteUrl)}; " +
                    "else git -C ${quote(target)} remote add ${quote(remote)} ${quote(remoteUrl)}; fi; "
            }
            val expandHistory = if (unshallow) {
                "if test \"\$(git -C ${quote(target)} rev-parse --is-shallow-repository)\" = true; then " +
                    "${authEnv(askPass)} git -C ${quote(target)} fetch --unshallow --no-tags origin; fi; "
            } else ""
            val command = "set -eu; " + gitAvailable() + repoExists(target) + configureRemote +
                expandHistory +
                "${authEnv(askPass)} git -C ${quote(target)} fetch --no-tags ${quote(remote)}; " +
                "printf 'HEAD=%s\\n' \"\$(git -C ${quote(target)} rev-parse HEAD)\"; " +
                "printf 'FETCH_HEAD=%s\\n' \"\$(git -C ${quote(target)} rev-parse FETCH_HEAD)\"; " +
                "printf 'AHEAD_BEHIND=%s\\n' \"\$(git -C ${quote(target)} rev-list --left-right --count HEAD...FETCH_HEAD)\"; " +
                "printf 'SHALLOW=%s\\n' \"\$(git -C ${quote(target)} rev-parse --is-shallow-repository)\""
            val result = manager.executeCommand(command, "/workspace", 600_000)
            if (result.exitCode != 0) return@withAskPass commandError("fetch", repo, result)
            val values = keyValues(result.stdout)
            val counts = values["AHEAD_BEHIND"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
            buildJsonObject {
                put("ok", true)
                put("operation", "fetch")
                put("repo", repo)
                put("remote", remote)
                put("remote_url", remoteUrl.ifBlank { "configured" })
                put("head_sha", values["HEAD"].orEmpty())
                put("fetch_head_sha", values["FETCH_HEAD"].orEmpty())
                put("ahead", counts.getOrNull(0)?.toIntOrNull() ?: 0)
                put("behind", counts.getOrNull(1)?.toIntOrNull() ?: 0)
                put("shallow", values["SHALLOW"].toBooleanStrictOrNull() ?: false)
            }.toString()
        }
    }

    private suspend fun merge(manager: SandboxManager, repo: String, ref: String, abort: Boolean): String {
        val target = targetFor(repo) ?: return error("Repository must be in owner/name form")
        if (abort) {
            val result = manager.executeCommand(
                "set -eu; ${gitAvailable()}${repoExists(target)}git -C ${quote(target)} merge --abort",
                "/workspace",
                30_000,
            )
            return if (result.exitCode == 0) buildJsonObject {
                put("ok", true); put("operation", "merge_abort"); put("repo", repo)
            }.toString() else commandError("merge_abort", repo, result)
        }
        if (!validRef(ref)) return error("Invalid or missing Git ref")
        val dirty = manager.executeCommand(
            "set -eu; ${gitAvailable()}${repoExists(target)}git -C ${quote(target)} status --porcelain",
            "/workspace",
            30_000,
        )
        if (dirty.exitCode != 0) return commandError("merge_precheck", repo, dirty)
        if (dirty.stdout.isNotBlank()) return buildJsonObject {
            put("ok", false)
            put("operation", "merge")
            put("repo", repo)
            put("error", "worktree is not clean; commit/stash changes or abort an existing merge first")
            put("status", dirty.stdout.take(4_000))
        }.toString()

        val result = manager.executeCommand(
            "set -eu; ${gitAvailable()}${repoExists(target)}git -C ${quote(target)} merge --no-edit ${quote(ref)}; " +
                "printf 'HEAD=%s\\n' \"\$(git -C ${quote(target)} rev-parse HEAD)\"; " +
                "printf 'BRANCH=%s\\n' \"\$(git -C ${quote(target)} branch --show-current)\"",
            "/workspace",
            300_000,
        )
        if (result.exitCode != 0) {
            val conflictResult = manager.executeCommand(
                "git -C ${quote(target)} diff --name-only --diff-filter=U",
                "/workspace",
                30_000,
            )
            val conflicts = conflictResult.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return buildJsonObject {
                put("ok", false)
                put("operation", "merge")
                put("repo", repo)
                put("ref", ref)
                put("merge_state_preserved", true)
                put("error", (result.stderr.ifBlank { result.stdout }).take(4_000))
                put("conflicts", buildJsonArray { conflicts.forEach { add(JsonPrimitive(it)) } })
                put("recovery", "resolve and commit, or call github_git_merge with abort=true")
            }.toString()
        }
        val values = keyValues(result.stdout)
        return buildJsonObject {
            put("ok", true)
            put("operation", "merge")
            put("repo", repo)
            put("ref", ref)
            put("branch", values["BRANCH"].orEmpty())
            put("head_sha", values["HEAD"].orEmpty())
            put("output", result.stdout.take(4_000))
        }.toString()
    }

    private suspend fun push(
        manager: SandboxManager,
        repo: String,
        branch: String,
        remote: String,
        setUpstream: Boolean,
    ): String {
        val target = targetFor(repo) ?: return error("Repository must be in owner/name form")
        if (!validRef(branch)) return error("Invalid or missing branch")
        if (!REMOTE.matches(remote)) return error("Invalid remote name")
        if (!GitHubMutationConfirmation.confirm("Push $repo branch $branch to remote $remote")) {
            return error("Push denied or confirmation unavailable")
        }
        return withAskPass(manager) { askPass ->
            val track = if (setUpstream) " -u" else ""
            val command = "set -eu; " + gitAvailable() + repoExists(target) +
                "git -C ${quote(target)} show-ref --verify --quiet ${quote("refs/heads/$branch")} || { echo 'local branch does not exist' >&2; exit 22; }; " +
                "${authEnv(askPass)} git -C ${quote(target)} push$track ${quote(remote)} ${quote("$branch:$branch")}; " +
                "printf 'HEAD=%s\\n' \"\$(git -C ${quote(target)} rev-parse ${quote(branch)})\""
            val result = manager.executeCommand(command, "/workspace", 300_000)
            if (result.exitCode != 0) return@withAskPass commandError("push", repo, result)
            buildJsonObject {
                put("ok", true)
                put("operation", "push")
                put("repo", repo)
                put("remote", remote)
                put("branch", branch)
                put("head_sha", keyValues(result.stdout)["HEAD"].orEmpty())
                put("set_upstream", setUpstream)
                put("output", (result.stderr.ifBlank { result.stdout }).take(4_000))
            }.toString()
        }
    }

    private suspend fun withAskPass(
        manager: SandboxManager,
        operation: suspend (askPass: String?) -> String,
    ): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val askPass = "/workspace/tmp/github-askpass-$nonce.sh"
        val token = auth.loadSession()?.token
        try {
            if (!token.isNullOrBlank()) {
                val script = "#!/bin/sh\ncase \"\$1\" in\n  *Username*) printf '%s\\n' 'x-access-token' ;;\n  *) printf '%s\\n' '${shellSingleQuoteBody(token)}' ;;\nesac\n"
                manager.fileWrite(askPass, script)?.let { return error(it) }
            }
            return operation(if (token.isNullOrBlank()) null else askPass)
        } finally {
            if (!token.isNullOrBlank()) runCatching {
                manager.executeCommand("rm -f ${quote(askPass)}", "/workspace", 5_000)
            }
        }
    }

    private fun targetFor(repo: String): String? =
        repo.takeIf(REPO::matches)?.let { "/workspace/repos/$it" }

    private fun normalizeRemoteUrl(input: String): String? {
        if (input.isBlank()) return ""
        if (REPO.matches(input)) return "https://github.com/$input.git"
        return input.takeIf {
            it.startsWith("https://") && it.length <= 500 &&
                it.none { c -> c.isWhitespace() || c == '\'' || c == '\r' || c == '\n' }
        }
    }

    private fun validRef(value: String): Boolean =
        REF.matches(value) && ".." !in value && !value.startsWith('/') && !value.endsWith('/') && !value.startsWith('-')

    private fun authEnv(askPass: String?): String =
        if (askPass == null) "GIT_TERMINAL_PROMPT=0"
        else "GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=${quote(askPass)}"

    private fun gitAvailable() =
        "command -v git >/dev/null 2>&1 || { echo 'git is not installed in Local Sandbox' >&2; exit 127; }; "

    private fun repoExists(target: String) =
        "test -d ${quote("$target/.git")} || { echo 'repository is not cloned at $target' >&2; exit 18; }; "

    private fun keyValues(stdout: String): Map<String, String> = stdout.lines().mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
    }.toMap()

    private fun commandError(operation: String, repo: String, result: SandboxManager.SandboxResult) =
        buildJsonObject {
            put("ok", false)
            put("operation", operation)
            put("repo", repo)
            put("exit_code", result.exitCode)
            put("error", (result.stderr.ifBlank { result.stdout }).take(4_000))
        }.toString()

    private fun quote(value: String) = "'" + shellSingleQuoteBody(value) + "'"
    private fun shellSingleQuoteBody(value: String) = value.replace("'", "'\\''")
    private fun error(message: String) = buildJsonObject {
        put("ok", false); put("error", message.take(4_000))
    }.toString()

    private companion object {
        const val TOOL_CLONE = "github_clone_repository"
        const val TOOL_FETCH = "github_git_fetch"
        const val TOOL_MERGE = "github_git_merge"
        const val TOOL_PUSH = "github_git_push"
        val TOOLS = setOf(TOOL_CLONE, TOOL_FETCH, TOOL_MERGE, TOOL_PUSH)
        val REPO = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
        val REMOTE = Regex("[A-Za-z0-9_.-]{1,100}")
        val REF = Regex("[A-Za-z0-9._/-]{1,200}")
        val SHA = Regex("[0-9a-fA-F]{40}")
    }
}
