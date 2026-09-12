package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.lsp.LanguageRegistry
import com.newoether.agora.lsp.LspDiagnostic
import com.newoether.agora.lsp.SandboxLspEngine
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sandbox language-pack checker exposed to the model as lsp_check / lsp_languages
 * / lsp_install — closes roadmap item rm_mtxvkl3v_zqh ("local compile feedback").
 *
 * Availability philosophy, per the silent-degradation lessons (A9/tools_status):
 * the tools are ALWAYS advertised. When the sandbox is missing the execution
 * returns an explicit machine-readable reason ("sandbox_unavailable" + what to
 * do) instead of the tool silently vanishing from the panel.
 *
 * The recipe engine lives in com.newoether.agora.lsp (pure interface over
 * SandboxManager) — everything here is argument plumbing and JSON shaping.
 */
class LspToolProvider(
    private val sandboxFactory: SandboxManagerFactory? = null,
) : ToolProvider {

    private val probeCache = mutableMapOf<String, Boolean>()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "lsp_check",
                description = "Compile-check code in the proot sandbox for any registered compiled language " +
                    "(c, cpp, rust, go, zig, java, kotlin, csharp, fortran, pascal, ada, haskell, ocaml, nim). " +
                    "Returns line/column diagnostics. Pass content+filename (written to a scratch dir) or an " +
                    "existing sandbox path. This is the local compiler feedback loop — run it before pushing to CI.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "lang" to ToolProperty("string", "Language id (optional if filename/path makes it obvious)."),
                        "filename" to ToolProperty("string", "Name for the scratch file, e.g. main.rs (with content)."),
                        "content" to ToolProperty("string", "Source code to check."),
                        "path" to ToolProperty("string", "Absolute path of an existing sandbox file (alternative to content)."),
                    )
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "lsp_languages",
                description = "List every compiled language the sandbox LSP layer knows about, with recipe " +
                    "commands and (when probe=true) live install/version state probed inside the sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "probe" to ToolProperty("string", "\"true\" to run a live availability probe (default false, cached).")
                    )
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "lsp_install",
                description = "Install the language pack (apk) for one language into the sandbox and VERIFY the " +
                    "checker binaries actually respond — install success is decided by re-probing, never by the " +
                    "apk return value. Use when lsp_check reports missing_tools.",
                parameters = ToolParameters(
                    properties = mapOf("lang" to ToolProperty("string", "Language id, e.g. rust.")),
                    required = listOf("lang")
                )
            )
        ),
    )

    override fun handles(name: String) = name in setOf("lsp_check", "lsp_languages", "lsp_install")

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return jsonError(name, "invalid_arguments", "参数不是合法 JSON") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

        val factory = sandboxFactory
            ?: return jsonError(name, "sandbox_missing", "本机构建没有沙盒实现（play flavor 或工厂构造失败）")
        if (!factory.isAvailable()) {
            return jsonError(name, "sandbox_unavailable", "沙盒工厂报告不可用；检查 设置→沙盒")
        }
        val manager = try {
            factory.create()
        } catch (e: Exception) {
            return jsonError(name, "sandbox_create_failed", "沙盒实例创建失败: ${e.message}")
        }
        try {
            val engine = SandboxLspEngine(manager, probeCache)
            return when (name) {
                "lsp_languages" -> languages(engine, text("probe").equals("true", ignoreCase = true))
                "lsp_check" -> check(engine, text("lang"), text("filename"), args, text("path"))
                "lsp_install" -> install(engine, text("lang"))
                else -> jsonError(name, "unknown_tool", "Unknown lsp tool")
            }
        } catch (e: Exception) {
            return jsonError(name, "lsp_error", "LSP 执行异常: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            manager.close()
        }
    }

    private suspend fun languages(engine: SandboxLspEngine, probe: Boolean): String {
        val probed = if (probe) engine.probeTools(force = true) else probeCache.toMap()
        return buildJsonObject {
            put("type", "lsp_languages")
            put("scratchDir", SandboxLspEngine.SCRATCH_DIR)
            put("probed", probe && probed.isNotEmpty())
            put("languages", buildJsonArray {
                for (spec in LanguageRegistry.all) add(buildJsonObject {
                    put("id", spec.id)
                    put("name", spec.displayName)
                    put("extensions", spec.extensions.joinToString(","))
                    put("recipe", spec.steps.joinToString(" ; ") { it.command })
                    put("apkCandidates", spec.apkCandidates.joinToString(","))
                    put("unverifiedRecipe", spec.unverifiedRecipe)
                    if (spec.note.isNotEmpty()) put("note", spec.note)
                    if (probed.isNotEmpty()) {
                        val need = spec.steps.flatMap { s -> s.needs }.distinct()
                        put("installed", need.isNotEmpty() && need.all { probed[it] == true })
                    }
                })
            })
        }.toString()
    }

    private suspend fun check(
        engine: SandboxLspEngine,
        lang: String,
        filename: String,
        args: Map<String, JsonElement>,
        path: String,
    ): String {
        val content = (args["content"] as? JsonPrimitive)?.content
        val spec = LanguageRegistry.resolve(lang.ifBlank { null }, filename.ifBlank { null } ?: path.ifBlank { null })
            ?: return jsonError("lsp_check", "unknown_language",
                "无法确定语言：lang 不在表内或文件名没有可识别扩展名。可查 lsp_languages。")
        val outcome = engine.check(
            spec = spec,
            fileName = filename.ifBlank { null },
            content = content,
            existingPath = path.ifBlank { null },
        )
        return buildJsonObject {
            put("type", "lsp_check")
            put("lang", outcome.langId)
            put("target", outcome.target)
            put("clean", outcome.clean)
            outcome.error?.let { put("error", it) }
            if (outcome.missingTools.isNotEmpty()) {
                put("missing_tools", outcome.missingTools.joinToString(","))
                put("install_hint", "lsp_install lang=${outcome.langId}")
            }
            put("steps", buildJsonArray {
                for (s in outcome.ranSteps) add(buildJsonObject {
                    put("command", s.command)
                    put("exit_code", s.exitCode)
                    put("diagnostics", s.diagnostics.size)
                    if (s.unparsedFailure) {
                        put("unparsed_failure", true)
                        put("raw_tail", s.rawTail)
                    }
                })
            })
            if (outcome.skippedSteps.isNotEmpty()) put("skipped", outcome.skippedSteps.joinToString(" ; "))
            put("diagnostics", buildJsonArray {
                for (d in outcome.diagnostics) add(diagJson(d))
            })
            val errCount = outcome.diagnostics.count { it.severity == "error" }
            val warnCount = outcome.diagnostics.count { it.severity == "warning" }
            put("summary", if (outcome.clean) "clean" else "$errCount errors, $warnCount warnings")
        }.toString()
    }

    private suspend fun install(engine: SandboxLspEngine, lang: String): String {
        val spec = LanguageRegistry.resolve(lang.ifBlank { null }, null)
            ?: return jsonError("lsp_install", "unknown_language", "lang 不在表内，可查 lsp_languages。")
        val outcome = engine.install(spec)
        probeCache.clear() // install changed the world — next probe must be fresh
        return buildJsonObject {
            put("type", "lsp_install")
            put("lang", outcome.langId)
            put("ok", outcome.ok)
            put("attempts", buildJsonArray { outcome.attempts.forEach { add(JsonPrimitive(it)) } })
            outcome.error?.let { put("error", it) }
            put("verified", buildJsonObject {
                outcome.verified.forEach { (bin, version) -> put(bin, version) }
            })
        }.toString()
    }

    private fun diagJson(d: LspDiagnostic) = buildJsonObject {
        put("file", d.file)
        put("line", d.line)
        put("column", d.column)
        put("severity", d.severity)
        if (d.code.isNotEmpty()) put("code", d.code)
        put("message", d.message)
    }

    private fun jsonError(tool: String, type: String, message: String): String = buildJsonObject {
        put("type", tool)
        put("error", type)
        put("message", message)
    }.toString()
}
