package com.newoether.agora.lsp

import com.newoether.agora.sandbox.SandboxManager

/**
 * Orchestrates language-pack checks against the [SandboxManager] interface only,
 * so the whole feature is unit-testable on the JVM with a fake manager — no
 * device, no proot, no CI-blindness.
 *
 * Honesty rules baked in (each one maps to a past incident):
 *  - "clean" is only ever claimed when every executed step exited 0 and parsed
 *    no error-severity diagnostics (D2/D3: exit codes and installer booleans
 *    are never trusted).
 *  - A step that exits non-zero with zero parsed diagnostics is flagged
 *    unparsedFailure and the raw output tail rides along — the model reads it.
 *  - Missing tools are reported with the exact install hint, never as an
 *    empty success list (silent-degradation lesson).
 *  - Install success is decided by re-probing the binaries after apk, never
 *    by the apkInstall() return value.
 */
class SandboxLspEngine(
    private val manager: SandboxManager,
    private val probeCache: MutableMap<String, Boolean> = mutableMapOf(),
) {

    companion object {
        const val SCRATCH_DIR = "/tmp/agora-lsp"
        const val MAX_DIAGNOSTICS_INLINE = 200
        const val RAW_TAIL_CHARS = 2_000

        /** File names crossing into the sandbox scratch dir must be boring. */
        fun sanitizeFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = base.map { ch ->
                if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
            }.joinToString("")
            val noLeadingDots = cleaned.trimStart('.')
            val clipped = noLeadingDots.take(64).ifEmpty { "snippet" }
            // Never allow an empty extensionless weirdness like "..": dots only as separators.
            return if (clipped.replace(".", "").isEmpty()) "snippet" else clipped
        }

        fun extensionOf(name: String): String =
            name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 8 }.orEmpty()
    }

    /** One probe run answers every recipe binary at once; result cached per manager instance. */
    suspend fun probeTools(force: Boolean = false): Map<String, Boolean> {
        if (!force && probeCache.isNotEmpty()) return probeCache.toMap()
        val bins = LanguageRegistry.allBinaries
        if (bins.isEmpty()) return emptyMap()
        val list = bins.joinToString(" ")
        val cmd = "for b in $list; do if command -v \$b >/dev/null 2>&1; then echo \"\$b=ok\"; else echo \"\$b=no\"; fi; done"
        val r = try {
            manager.executeCommand(cmd, timeoutMs = 20_000)
        } catch (e: Exception) {
            return emptyMap().also { probeCache.clear() }
        }
        val out = r.stdout + "\n" + r.stderr
        probeCache.clear()
        for (bin in bins) {
            probeCache[bin] = out.contains("$bin=ok")
        }
        return probeCache.toMap()
    }

    private suspend fun versionLine(binary: String): String {
        val r = try {
            manager.executeCommand("$binary --version 2>&1 | head -n 1", timeoutMs = 15_000)
        } catch (e: Exception) {
            return "?"
        }
        return (r.stdout + r.stderr).lineSequence().firstOrNull()?.take(120).orEmpty()
    }

    /**
     * Check [content] (written to a scratch file named [fileName]) or an existing
     * sandbox file at [existingPath] against the resolved [spec]'s recipe.
     */
    suspend fun check(spec: LanguageSpec, fileName: String?, content: String?, existingPath: String?): CheckOutcome {
        val target: String = if (existingPath != null) {
            val bad = validateExistingPath(existingPath)
            if (bad != null) return CheckOutcome(spec.id, existingPath, false, emptyList(), emptyList(), emptyList(), bad)
            existingPath
        } else {
            if (content == null || fileName.isNullOrBlank()) {
                return CheckOutcome(spec.id, "", false, emptyList(), emptyList(), emptyList(),
                    "需要 content+filename，或改传沙盒内已存在的 path")
            }
            val safe = sanitizeFileName(fileName)
            val withExt = if (extensionOf(safe).isEmpty()) "$safe.${spec.extensions.first()}" else safe
            val path = "$SCRATCH_DIR/$withExt"
            val mkdir = manager.executeCommand("mkdir -p $SCRATCH_DIR", timeoutMs = 15_000)
            if (mkdir.exitCode != 0) {
                return CheckOutcome(spec.id, path, false, emptyList(), emptyList(), emptyList(),
                    "沙盒内建目录失败(exit=${mkdir.exitCode}): ${(mkdir.stdout + mkdir.stderr).take(200)}")
            }
            val writeErr = manager.fileWrite(path, content)
            if (writeErr != null) {
                return CheckOutcome(spec.id, path, false, emptyList(), emptyList(), emptyList(),
                    "写入失败: $writeErr")
            }
            path
        }

        val available = probeTools()
        val runnable = spec.steps.filter { step -> step.needs.all { available[it] == true } }
        val missing = if (runnable.isEmpty())
            spec.steps.flatMap { it.needs }.distinct().filter { available[it] != true }
        else emptyList()
        if (runnable.isEmpty()) {
            return CheckOutcome(
                langId = spec.id, target = target, clean = false,
                ranSteps = emptyList(), skippedSteps = emptyList(), missingTools = missing,
                error = "缺少工具 ${missing.joinToString()}: 先 lsp_install lang=${spec.id}",
            )
        }

        val dir = target.substringBeforeLast('/', "")
        val ran = mutableListOf<StepOutcome>()
        val skipped = mutableListOf<String>()
        var stop = false
        for (step in spec.steps) {
            if (step !in runnable) { skipped += step.command; continue }
            if (stop) { skipped += step.command; continue }
            val cmd = step.command.replace("{file}", quoteSh(target)).replace("{dir}", quoteSh(dir))
            val r = try {
                manager.executeCommand(cmd, timeoutMs = step.timeoutMs)
            } catch (e: Exception) {
                ran += StepOutcome(cmd, -1, emptyList(), true, "执行异常: ${e.message}")
                stop = true
                continue
            }
            val merged = r.stdout + "\n" + r.stderr
            val diags = DiagnosticParsers.parse(step.parser, merged)
            val errors = diags.count { it.severity == "error" }
            val unparsed = r.exitCode != 0 && diags.isEmpty()
            ran += StepOutcome(
                command = cmd, exitCode = r.exitCode,
                diagnostics = diags.take(MAX_DIAGNOSTICS_INLINE),
                unparsedFailure = unparsed,
                rawTail = if (unparsed || r.exitCode != 0) merged.takeLast(RAW_TAIL_CHARS) else "",
            )
            if (errors > 0 || r.exitCode != 0) stop = true
        }
        val anyError = ran.any { it.exitCode != 0 || it.diagnostics.any { d -> d.severity == "error" } }
        return CheckOutcome(
            langId = spec.id, target = target, clean = !anyError,
            ranSteps = ran, skippedSteps = skipped, missingTools = emptyList(),
        )
    }

    /** apk install candidates, then VERIFY binaries — the apk boolean is never trusted. */
    suspend fun install(spec: LanguageSpec): InstallOutcome {
        if (!manager.isAvailableSync() && !manager.isAvailable()) {
            return InstallOutcome(spec.id, false, emptyList(), emptyMap(),
                "沙盒未安装：先在 设置→沙盒 完成 rootfs 安装")
        }
        val attempts = mutableListOf<String>()
        for (candidate in spec.apkCandidates) {
            attempts += "apk add $candidate"
            val flag = try {
                manager.apkInstall(candidate)
            } catch (e: Exception) {
                attempts += "异常: ${e.message}"
                continue
            }
            val verified = verifyBinaries(spec)
            if (verified.isNotEmpty()) {
                return InstallOutcome(spec.id, true, attempts, verified, null)
            }
            attempts += "$candidate 装完仍无可执行文件(apk 返回 $flag)，继续试下一个候选"
        }
        return InstallOutcome(spec.id, false, attempts, emptyMap(),
            "所有 apk 候选都没能带来可运行的检查器: ${spec.apkCandidates}")
    }

    /** Binaries of this spec that now resolve, mapped to a version line. */
    suspend fun verifyBinaries(spec: LanguageSpec): Map<String, String> {
        val needed = spec.steps.flatMap { it.needs }.distinct()
        val present = needed.filter { neededAll -> manager.executeCommand("command -v $neededAll", timeoutMs = 10_000).exitCode == 0 }
        return present.associateWith { versionLine(it) }
    }

    private suspend fun List<String>.filter(p: suspend (String) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        for (item in this) if (p(item)) out += item
        return out
    }

    private fun validateExistingPath(path: String): String? = when {
        !path.startsWith("/") -> "path 必须是沙盒内绝对路径"
        path.contains("..") -> "path 不允许 .."
        else -> null
    }

    private fun quoteSh(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
