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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Clones one GitHub repository into the fixed persistent /workspace/repos tree. */
class GitHubCloneToolProvider(
    context: Context,
    sandboxFactory: SandboxManagerFactory?,
) : ToolProvider {
    private val auth = GitHubAuthManager(context.applicationContext)
    private val sandbox: SandboxManager? = sandboxFactory?.takeIf { it.isAvailable() }?.create()
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (sandbox == null) return emptyList()
        return listOf(ToolDefinition(function = ToolFunction(
            name = TOOL,
            description = "Clone one GitHub repository into the fixed persistent /workspace/repos/<owner>/<repo> directory. Defaults to a shallow single-branch clone; never initializes submodules.",
            parameters = ToolParameters(
                properties = mapOf(
                    "repo" to ToolProperty("string", "Repository in owner/name form."),
                    "ref" to ToolProperty("string", "Optional branch or tag. Defaults to the remote default branch."),
                    "depth" to ToolProperty("integer", "Shallow depth, 1-50; defaults to 1."),
                ),
                required = listOf("repo"),
            ),
        )))
    }

    override fun handles(name: String): Boolean = name == TOOL

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != TOOL) return error("Unknown clone tool")
        val manager = sandbox ?: return error("Local F-Droid sandbox is unavailable")
        if (!manager.isAvailable()) return error("Local Sandbox is not installed")
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return error("Invalid tool arguments") }
        fun arg(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        val repo = arg("repo").trim()
        val ref = arg("ref").trim()
        val depth = arg("depth").toIntOrNull()?.coerceIn(1, 50) ?: 1
        if (!REPO.matches(repo)) return error("Repository must be in owner/name form")
        if (ref.isNotEmpty() && (!REF.matches(ref) || ref.contains("..") || ref.startsWith('/') || ref.endsWith('/'))) {
            return error("Invalid Git ref")
        }
        val (owner, repository) = repo.split('/', limit = 2)
        val target = "/workspace/repos/$owner/$repository"
        if (!GitHubMutationConfirmation.confirm("Clone $repo${if (ref.isBlank()) "" else "@$ref"} into $target")) {
            return error("Clone denied or confirmation unavailable")
        }

        val nonce = UUID.randomUUID().toString().replace("-", "")
        val askPass = "/workspace/tmp/github-askpass-$nonce.sh"
        val token = auth.loadSession()?.token
        try {
            if (!token.isNullOrBlank()) {
                val script = "#!/bin/sh\ncase \"\$1\" in\n  *Username*) printf '%s\\n' 'x-access-token' ;;\n  *) printf '%s\\n' '${shellSingleQuoteBody(token)}' ;;\nesac\n"
                manager.fileWrite(askPass, script)?.let { return error(it) }
            }
            val branchArgs = if (ref.isBlank()) "" else " --branch ${quote(ref)}"
            val authEnv = if (token.isNullOrBlank()) "GIT_TERMINAL_PROMPT=0" else
                "GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=${quote(askPass)}"
            val command = "set -eu; " +
                "command -v git >/dev/null 2>&1 || { echo 'git is not installed in Local Sandbox' >&2; exit 127; }; " +
                "test ! -e ${quote(target)} || { echo 'target already exists' >&2; exit 17; }; " +
                "mkdir -p ${quote("/workspace/repos/$owner")}; " +
                "$authEnv git -c core.hooksPath=/dev/null clone --depth $depth --single-branch --no-tags$branchArgs ${quote("https://github.com/$repo.git")} ${quote(target)}; " +
                "git -C ${quote(target)} rev-parse HEAD; " +
                "git -C ${quote(target)} rev-parse --is-shallow-repository"
            val result = manager.executeCommand(command, "/workspace", 120_000)
            if (result.exitCode != 0) return buildJsonObject {
                put("ok", false); put("repo", repo); put("path", target); put("exit_code", result.exitCode)
                put("error", (result.stderr.ifBlank { result.stdout }).take(1_000))
            }.toString()
            val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val head = lines.firstOrNull { it.matches(Regex("[0-9a-fA-F]{40}")) }.orEmpty()
            val shallow = lines.lastOrNull { it == "true" || it == "false" }?.toBooleanStrictOrNull() ?: true
            return buildJsonObject {
                put("ok", true); put("repo", repo); put("ref", ref.ifBlank { "default" }); put("path", target)
                put("head_sha", head); put("shallow", shallow); put("depth", depth)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return error(e.message ?: "Clone failed")
        } finally {
            if (!token.isNullOrBlank()) runCatching {
                manager.executeCommand("rm -f ${quote(askPass)}", "/workspace", 5_000)
            }
        }
    }

    private fun quote(value: String) = "'" + shellSingleQuoteBody(value) + "'"
    private fun shellSingleQuoteBody(value: String) = value.replace("'", "'\\''")
    private fun error(message: String) = buildJsonObject { put("ok", false); put("error", message.take(1_000)) }.toString()

    private companion object {
        const val TOOL = "github_clone_repository"
        val REPO = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
        val REF = Regex("[A-Za-z0-9._/-]{1,200}")
    }
}
