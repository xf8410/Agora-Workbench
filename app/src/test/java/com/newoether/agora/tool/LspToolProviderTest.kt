package com.newoether.agora.tool

import com.newoether.agora.lsp.LanguageRegistry
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 层只测"门面话"：工具永远在场、坏消息必须带着原因和指引出门、
 * JSON 形状稳定可解析。引擎逻辑在 SandboxLspEngineTest 里锁。
 */
class LspToolProviderTest {

    private fun ctx() = GenerationContext()

    private fun availableFactory(
        okBins: Set<String>,
        recipeExit: Int = 0,
        recipeOutput: String = "",
    ): SandboxManagerFactory {
        val manager = mockk<SandboxManager>(relaxed = true)
        coEvery { manager.executeCommand(any(), any(), any()) } answers {
            val cmd = firstArg<String>()
            when {
                cmd.startsWith("for b in") -> SandboxManager.SandboxResult(
                    LanguageRegistry.allBinaries.joinToString("\n") { if (it in okBins) "$it=ok" else "$it=no" }, "", 0
                )
                cmd.startsWith("mkdir") -> SandboxManager.SandboxResult("", "", 0)
                else -> SandboxManager.SandboxResult(recipeOutput, "", recipeExit)
            }
        }
        coEvery { manager.fileWrite(any(), any()) } returns null
        every { manager.isAvailableSync() } returns true
        val factory = mockk<SandboxManagerFactory>()
        every { factory.isAvailable() } returns true
        every { factory.create() } returns manager
        return factory
    }

    @Test
    fun `工具永远在场哪怕没有沙盒`() {
        val provider = LspToolProvider(null)
        val names = provider.definitions(ctx()).map { it.function.name }.toSet()
        assertEquals(setOf("lsp_check", "lsp_languages", "lsp_install"), names)
        for (n in names) assertTrue("$n 必须可路由", provider.handles(n))
    }

    @Test
    fun `无沙盒执行必须带原因出门而不是沉默`() {
        val provider = LspToolProvider(null)
        val json = Json.parseToJsonElement(
            runBlocking { provider.execute("lsp_check", """{"lang":"rust","filename":"x.rs","content":"fn"}""", ctx()) }
        ).jsonObject
        assertEquals("sandbox_missing", json["error"]?.jsonPrimitive?.content)
        assertTrue("必须给下一步指引: $json", json["message"]!!.jsonPrimitive.content.contains("沙盒"))
    }

    @Test
    fun `未知语言当场报错`() {
        val provider = LspToolProvider(availableFactory(setOf("rustc")))
        val json = Json.parseToJsonElement(
            runBlocking { provider.execute("lsp_check", """{"filename":"x.cobol","content":"hello"}""", ctx()) }
        ).jsonObject
        assertEquals("unknown_language", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `干净检查出 clean true 的 JSON 形状`() {
        val provider = LspToolProvider(availableFactory(setOf("rustc")))
        val json = Json.parseToJsonElement(
            runBlocking { provider.execute("lsp_check", """{"lang":"rust","filename":"x.rs","content":"fn main(){}"}""", ctx()) }
        ).jsonObject
        assertEquals("lsp_check", json["type"]?.jsonPrimitive?.content)
        assertEquals(true, json["clean"]?.jsonPrimitive?.boolean)
        assertEquals("clean", json["summary"]?.jsonPrimitive?.content)
        assertTrue(json["diagnostics"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `报错检查带行列诊断出门`() {
        val provider = LspToolProvider(availableFactory(setOf("rustc"), recipeExit = 1,
            recipeOutput = "error[E0308]: mismatched types\n --> /tmp/agora-lsp/x.rs:3:5\n"))
        val json = Json.parseToJsonElement(
            runBlocking { provider.execute("lsp_check", """{"lang":"rust","filename":"x.rs","content":"bad"}""", ctx()) }
        ).jsonObject
        assertEquals(false, json["clean"]?.jsonPrimitive?.boolean)
        val diag = json["diagnostics"]!!.jsonArray.first().jsonObject
        assertEquals(3, diag["line"]!!.jsonPrimitive.content.toInt())
        assertEquals("E0308", diag["code"]?.jsonPrimitive?.content)
        assertTrue(json["summary"]!!.jsonPrimitive.content.startsWith("1 errors"))
    }

    @Test
    fun `语言清单全量在场且带扩展名和配方`() {
        val provider = LspToolProvider(availableFactory(emptySet()))
        val json = Json.parseToJsonElement(
            runBlocking { provider.execute("lsp_languages", "{}", ctx()) }
        ).jsonObject
        val langs = json["languages"]!!.jsonArray
        assertEquals(LanguageRegistry.all.size, langs.size)
        val first = langs.first().jsonObject
        assertTrue(first["recipe"]!!.jsonPrimitive.content.contains("{file}"))
        assertTrue(first["extensions"]!!.jsonPrimitive.content.isNotEmpty())
    }
}
