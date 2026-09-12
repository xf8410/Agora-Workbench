package com.newoether.agora.lsp

import com.newoether.agora.sandbox.SandboxManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎全行为对照：每一步真实发往沙盒的命令都被记录下来逐条断言。
 * 重点锁三件事——"clean 不轻信退出码"、"缺工具必须给出安装指引"、
 * "apk 返回值永远不是成功判据"。
 */
class SandboxLspEngineTest {

    private val commands = mutableListOf<String>()
    private val writes = mutableMapOf<String, String>()
    private var probeOk: Set<String> = emptySet()
    private var probeExit = 0
    private var stepExit = 0
    private var stepOutput = ""
    private var apkFlag = false
    private var commandVExit = 1

    private fun fakeManager(): SandboxManager {
        val manager = mockk<SandboxManager>(relaxed = true)
        coEvery { manager.executeCommand(any(), any(), any()) } answers {
            val cmd = firstArg<String>()
            commands += cmd
            when {
                cmd.startsWith("for b in") ->
                    SandboxManager.SandboxResult(probeLine(), "", probeExit)
                cmd.startsWith("mkdir") -> SandboxManager.SandboxResult("", "", 0)
                cmd.startsWith("command -v") -> SandboxManager.SandboxResult("", "", commandVExit)
                cmd.contains("--version") -> SandboxManager.SandboxResult("1.0.0-test", "", 0)
                else -> SandboxManager.SandboxResult(stepOutput, "", stepExit)
            }
        }
        coEvery { manager.fileWrite(any(), any()) } answers {
            writes[firstArg<String>()] = secondArg<String>()
            null
        }
        coEvery { manager.apkInstall(any(), any()) } answers { apkFlag = true; true }
        coEvery { manager.isAvailable() } returns true
        every { manager.isAvailableSync() } returns true
        return manager
    }

    private fun probeLine(): String =
        LanguageRegistry.allBinaries.joinToString("\n") { if (it in probeOk) "$it=ok" else "$it=no" }

    private fun engine(): SandboxLspEngine = SandboxLspEngine(fakeManager())

    @Test
    fun `干净运行才报 clean 且命令顺序是 建目录-探测-配方`() {
        probeOk = setOf("rustc")
        stepExit = 0
        stepOutput = ""
        val outcome = runBlocking { engine().check(LanguageRegistry.byId["rust"]!!, "x.rs", "fn main(){}", null) }
        assertTrue("clean 应当为真，实际 ranSteps=${outcome.ranSteps}", outcome.clean)
        assertEquals("/tmp/agora-lsp/x.rs", writes.keys.first())
        assertTrue("mkdir 必须先行", commands[0].startsWith("mkdir -p /tmp/agora-lsp"))
        assertTrue("探测在配方前", commands[1].startsWith("for b in"))
        assertTrue("配方被引用包裹", commands[2].contains("'/tmp/agora-lsp/x.rs'"))
        assertEquals(1, outcome.ranSteps.size)
    }

    @Test
    fun `第一步报错就不跑后续步骤`() {
        probeOk = setOf("gofmt", "go")
        stepExit = 1
        stepOutput = "/tmp/x.go:5:11: expected declaration, found 'PKG'\n"
        val outcome = runBlocking { engine().check(LanguageRegistry.byId["go"]!!, "x.go", "package", null) }
        assertFalse(outcome.clean)
        assertEquals("go 两步只准跑第一步", 1, outcome.ranSteps.size)
        assertEquals("vet 必须被跳过", 1, outcome.skippedSteps.size)
        assertEquals("error", outcome.diagnostics.first().severity)
    }

    @Test
    fun `非零退出配零解析等于未验证不许当干净`() {
        probeOk = setOf("gcc")
        stepExit = 2
        stepOutput = "cc1: internal compiler error: segmented input\n"
        val outcome = runBlocking { engine().check(LanguageRegistry.byId["c"]!!, "x.c", "int main(){}", null) }
        assertFalse("exit!=0 永远不许 clean", outcome.clean)
        val step = outcome.ranSteps.first()
        assertTrue("必须标记 unparsedFailure: $step", step.unparsedFailure)
        assertTrue("原文尾部必须带上", step.rawTail.contains("internal compiler error"))
    }

    @Test
    fun `全缺工具时给出安装指引而不是空成功`() {
        probeOk = emptySet()
        val outcome = runBlocking { engine().check(LanguageRegistry.byId["c"]!!, "x.c", "int main(){}", null) }
        assertFalse(outcome.clean)
        assertTrue("missing 必须列全: ${outcome.missingTools}", outcome.missingTools.containsAll(listOf("gcc", "clang")))
        assertTrue("错误必须指向 lsp_install: ${outcome.error}", outcome.error!!.contains("lsp_install lang=c"))
    }

    @Test
    fun `探测失败必须炸出来不许伪装成全缺失`() {
        probeExit = 3
        var thrown = false
        try {
            runBlocking { engine().check(LanguageRegistry.byId["c"]!!, "x.c", "int main(){}", null) }
        } catch (e: IllegalStateException) {
            thrown = true
            assertTrue("原因带退出码: ${e.message}", e.message!!.contains("exit=3"))
        }
        assertTrue("probe 失败必须抛 IllegalStateException，而不是返回 missingTools", thrown)
    }

    @Test
    fun `path 模式拒非绝对与穿越且不跑任何命令`() {
        for ((bad, want) in listOf("rel/x.rs" to "绝对路径", "/tmp/../etc/passwd" to "..")) {
            commands.clear()
            val outcome = runBlocking { engine().check(LanguageRegistry.byId["rust"]!!, null, null, bad) }
            assertFalse(outcome.clean)
            assertTrue("报错含 '$want'，实际 ${outcome.error}", outcome.error!!.contains(want))
            assertTrue("校验失败不得发任何沙盒命令，实际 $commands", commands.isEmpty())
        }
    }

    @Test
    fun `清洗文件名对照表`() {
        val rows = listOf(
            "../evil.c" to "evil.c",
            ".." to "snippet",
            "a b/../c.rs" to "c.rs",
            "x".repeat(100) + ".rs" to null,
            "中文.rs" to null,
            "ok_name-1.kt" to "ok_name-1.kt",
        )
        for ((raw, want) in rows) {
            val got = SandboxLspEngine.sanitizeFileName(raw)
            assertTrue("'$raw' 清洗后仍含路径分隔: $got", !got.contains('/') && !got.contains('\\'))
            assertTrue("'$raw' 清洗后仍含控制字符: $got", got.none { it.code < 32 })
            assertTrue("'$raw' 长度失控: $got", got.length <= 64)
            if (want != null) assertEquals("sanitize('$raw')", want, got)
        }
    }

    @Test
    fun `apk 说成功不算成功复验说了算`() {
        apkFlag = true
        commandVExit = 1 // 装完 command -v 仍然找不到 → 真失败
        val outcome = runBlocking { engine().install(LanguageRegistry.byId["c"]!!) }
        assertFalse("apkInstall=true 但二进制不在，必须报失败", outcome.ok)
        assertTrue("过程记录必须留痕 apk 返回值: ${outcome.attempts}",
            outcome.attempts.any { it.contains("返回 true") })
    }

    @Test
    fun `复验通过才算装成并带版本`() {
        apkFlag = false
        commandVExit = 0
        val outcome = runBlocking { engine().install(LanguageRegistry.byId["c"]!!) }
        assertTrue("复验通了必须算成功: $outcome", outcome.ok)
        assertTrue("版本行缺失: ${outcome.verified}", outcome.verified.containsKey("gcc"))
    }
}
