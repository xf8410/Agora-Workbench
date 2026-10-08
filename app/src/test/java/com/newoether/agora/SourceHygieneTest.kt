package com.newoether.agora

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码卫生闸（对齐 hualuo-repo-tool 的 SourceHygieneTest——那套闸门就是被
 * 转义/括号逃逸的雷炸出来的，Agora 同样吃过：hint 串引号被脚本吃掉成裸引号
 * (#789)、嵌套注释 Unclosed comment 八月底连环红、4 连引号脆弱写法今天仍在）。
 *
 * 三条红线，全部纯 JVM 可测：
 *  1. 括号/花括号词法平衡（Kotlin 感知：行注释/块注释/单串/三引号/字符字面量）；
 *  2. 禁 4 连引号（""""）——kotlinc 恰好容忍的脆弱写法，正则里的内容引号
 *     必须改成字符串拼接或 \x{22} 转义，不许赌解析器；
 *  3. 禁单串内裸 \s \d \w（非法转义序列）——正则一律进三引号或双反斜杠。
 *
 * 扫描范围：app/src 全部 .kt（main/fdroid/play/test）。任何一条红 = 测试红，
 * 让 CI 在合并前拦住，不再靠 20 分钟一轮的编译红来发现。
 */
class SourceHygieneTest {

    private val roots = listOf(File("app/src"))

    @Test
    fun allKotlinSourcesAreLexicallyBalanced() {
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.mapNotNull { f ->
                scan(f.readText())?.let { (d, p, st) -> "${f.path}: depth=$d paren=$p state=$st" }
            }
        }
        assertTrue(
            "词法不平衡（括号逃逸/字符串未闭合）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun quadrupleQuotesAreRejected() {
        // 词法态感知：只判 code 态下真开新串的 4 连引号（tq 收尾后跟普通字符是合法写法，
        // 如 Regex("""\s+"""))——子串匹配会误报，必须逐字符状态机。
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.mapNotNull { f ->
                val txt = f.readText()
                var i = 0; val n = txt.length; var state = "code"; var line = 1; var hit: Int? = null
                while (i < n && hit == null) {
                    val c = txt[i]
                    if (c == '\n') line++
                    when (state) {
                        "code" -> when {
                            txt.startsWith("//", i) -> { state = "lc"; i += 2; continue }
                            txt.startsWith("/*", i) -> { state = "bc"; i += 2; continue }
                            txt.startsWith("\"\"\"", i) -> { state = "tq"; i += 3; continue }
                            c == '"' -> { state = "str"; i += 1; continue }
                        }
                        "lc" -> if (c == '\n') state = "code"
                        "bc" -> if (txt.startsWith("*/", i)) { state = "code"; i += 2; continue }
                        "str" -> when {
                            c == '\\' -> { i += 2; continue }
                            c == '"' -> state = "code"
                        }
                        "tq" -> if (txt.startsWith("\"\"", i)) {
                            if (txt.startsWith("\"\"\"\"", i) && i + 4 < n && txt[i + 4] != ')' && txt[i + 4] != ',' && txt[i + 4] != '\n' && txt[i + 4] != ' ') {
                                hit = line
                            } else { state = "code"; i += 3; continue }
                        }
                    }
                    i++
                }
                hit?.let { "${f.path}:$it" }
            }
        }
        assertTrue(
            "4 连引号是脆弱写法（kotlinc 左贪婪容忍≠安全）：改字符串拼接。命中：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun rawEscapeSequencesInSingleLineStringsAreRejected() {
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
                f.readText().split('\n').mapIndexedNotNull { i, l ->
                    // 剥行注释：只看代码段里的字符串字面量
                    val code = if (l.contains("//") && !l.substringBefore("//").trimEnd().endsWith("\"")) l.substringBefore("//") else l
                    Regex("\"(?:[^\"\\\\]|\\\\.)*\"").findAll(code).forEach { m ->
                        val seg = m.value
                        if (seg.contains("\\s") || seg.contains("\\d") || seg.contains("\\w") || seg.contains("\\S") || seg.contains("\\D") || seg.contains("\\W")) {
                            return@mapIndexedNotNull "${f.path}:${i + 1} ${seg.take(48)}"
                        }
                    }
                    null
                }
            }
        }
        assertTrue(
            "单串里的裸 \\s \\d \\w 是非法转义（编译红或静默变 $）：正则进三引号或写 \\\\s。命中：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 词法感知扫描：返回 null=健康；否则 (花括号差, 圆括号差, 终态)。 */
    private fun scan(txt: String): Triple<Int, Int, String>? {
        var i = 0
        val n = txt.length
        var depth = 0
        var paren = 0
        var state = "code"
        while (i < n) {
            val c = txt[i]
            when (state) {
                "code" -> when {
                    txt.startsWith("//", i) -> { state = "lc"; i += 2; continue }
                    txt.startsWith("/*", i) -> { state = "bc"; i += 2; continue }
                    txt.startsWith("\"\"\"", i) -> { state = "tq"; i += 3; continue }
                    c == '\'' -> when {
                        // 字符字面量：'\x' 转义或 'x'
                        i + 1 < n && txt[i + 1] == '\\' -> { i += 4; continue }
                        i + 2 < n && txt[i + 2] == '\'' -> { i += 3; continue }
                    }
                    c == '"' -> { state = "str"; i += 1; continue }
                    c == '{' -> depth++
                    c == '}' -> depth--
                    c == '(' -> paren++
                    c == ')' -> paren--
                }
                "lc" -> if (c == '\n') state = "code"
                "bc" -> if (txt.startsWith("*/", i)) { state = "code"; i += 2; continue }
                "str" -> when {
                    c == '\\' -> { i += 2; continue }
                    c == '"' -> state = "code"
                }
                "tq" -> if (txt.startsWith("\"\"\"", i)) { state = "code"; i += 3; continue }
            }
            i++
        }
        return if (depth == 0 && paren == 0 && state == "code") null else Triple(depth, paren, state)
    }
}
