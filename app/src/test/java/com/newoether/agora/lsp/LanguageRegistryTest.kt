package com.newoether.agora.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table lock for the language registry. Adding/removing a language is a data
 * change — this test is where the change must be acknowledged, in Chinese
 * comparison-table style: expected vs actual printed on failure.
 */
class LanguageRegistryTest {

    @Test
    fun `编译语言全表锁定——加语言必须同步这张表`() {
        val expected = listOf(
            "c", "cpp", "rust", "go", "zig", "java", "kotlin",
            "csharp", "fortran", "pascal", "ada", "haskell", "ocaml", "nim",
        )
        val actual = LanguageRegistry.all.map { it.id }
        assertEquals("语言表内容不符（期望 vs 实际）", expected, actual)
    }

    @Test
    fun `扩展名全局唯一归属`() {
        val declared = LanguageRegistry.all.flatMap { it.extensions }
        assertEquals(
            "有扩展名被两个语言抢（期望无重复 vs 实际有重复）",
            declared.size, declared.distinct().size,
        )
        // byExtension 的构建里有 require——能走到这里本身就证明无冲突。
        assertTrue(LanguageRegistry.byExtension.isNotEmpty())
    }

    @Test
    fun `每条配方都必须带文件占位符和依赖工具`() {
        for (spec in LanguageRegistry.all) {
            assertTrue("${spec.id} 没有任何步骤", spec.steps.isNotEmpty())
            assertTrue("${spec.id} 没有 apk 候选", spec.apkCandidates.isNotEmpty())
            assertTrue("${spec.id} 没有扩展名", spec.extensions.isNotEmpty())
            for (step in spec.steps) {
                assertTrue("${spec.id} 步骤缺 needs: ${step.command}", step.needs.isNotEmpty())
                assertTrue("${spec.id} 步骤缺 {file}: ${step.command}", step.command.contains("{file}"))
            }
        }
    }

    @Test
    fun `id 和扩展名路由与别名表`() {
        val rows = listOf(
            // (lang, filename, expectedId)
            Triple("rust", "anything.rs", "rust"),
            Triple("", "main.c", "c"),
            Triple("", "App.java", "java"),
            Triple("", "a.kt", "kotlin"),
            Triple("c++", "x.any", "cpp"),
            Triple("golang", "y", "go"),
            Triple("cs", "z", "csharp"),
            Triple("rs", "w", "rust"),
            Triple("f90", "v", "fortran"),
            Triple("hs", "u", "haskell"),
            Triple("", "noextension", null),
            Triple("cobol", "x.cob", null),
        )
        for ((lang, filename, want) in rows) {
            val got = LanguageRegistry.resolve(lang.ifEmpty { null }, filename.ifEmpty { null })
            assertEquals("resolve(lang='$lang', filename='$filename')", want, got?.id)
        }
        assertNull(LanguageRegistry.resolve(null, null))
    }

    @Test
    fun `显式 lang 优先于文件名扩展名`() {
        assertSame(
            LanguageRegistry.byId["cpp"],
            LanguageRegistry.resolve("cpp", "x.h"),
        )
    }

    @Test
    fun `探测清单覆盖所有配方依赖`() {
        val needed = LanguageRegistry.all.flatMap { s -> s.steps.flatMap { it.needs } }.toSet()
        assertEquals("allBinaries 与配方 needs 不一致", needed, LanguageRegistry.allBinaries.toSet())
        assertEquals("allBinaries 必须无重复", LanguageRegistry.allBinaries.size, LanguageRegistry.allBinaries.distinct().size)
    }
}
