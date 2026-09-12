package com.newoether.agora.lsp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对照表：每一行都是某个真实编译器/工具链的**原样输出片段**（含格式细节），
 * 断言精确到 file/line/column/severity/code/message 六个字段。
 * 改解析器 = 必须先在这张表里改期望值，防止"看着像对了"。
 */
class DiagnosticParsersTest {

    private fun check(
        style: ParseStyle,
        text: String,
        expected: List<LspDiagnostic>,
        label: String,
    ) {
        val actual = DiagnosticParsers.parse(style, text)
        assertEquals("$label —— 条数（期望 vs 实际）", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$label 第${i + 1}条 file", expected[i].file, actual[i].file)
            assertEquals("$label 第${i + 1}条 line", expected[i].line, actual[i].line)
            assertEquals("$label 第${i + 1}条 column", expected[i].column, actual[i].column)
            assertEquals("$label 第${i + 1}条 severity", expected[i].severity, actual[i].severity)
            assertEquals("$label 第${i + 1}条 code", expected[i].code, actual[i].code)
            assertEquals("$label 第${i + 1}条 message", expected[i].message, actual[i].message)
        }
    }

    @Test
    fun `gcc 错误与带 flag 的警告`() {
        check(
            ParseStyle.GCC_LIKE,
            "/tmp/agora-lsp/x.c:7:12: error: expected ';' before '}' token\n" +
                "/tmp/agora-lsp/x.c:3:5: warning: unused variable 'a' [-Wunused-variable]\n",
            listOf(
                LspDiagnostic("/tmp/agora-lsp/x.c", 7, 12, "error", "", "expected ';' before '}' token"),
                LspDiagnostic("/tmp/agora-lsp/x.c", 3, 5, "warning", "-Wunused-variable", "unused variable 'a' [-Wunused-variable]"),
            ),
            "gcc",
        )
    }

    @Test
    fun `zig 方言同 gcc`() {
        check(
            ParseStyle.GCC_LIKE,
            "/tmp/x.zig:4:9: error: expected token ';', found 'identifier'\n",
            listOf(LspDiagnostic("/tmp/x.zig", 4, 9, "error", "", "expected token ';', found 'identifier'")),
            "zig",
        )
    }

    @Test
    fun `ghc 方言同 gcc`() {
        check(
            ParseStyle.GCC_LIKE,
            "/tmp/X.hs:12:5: error:\n    • Non type-variable argument\n",
            listOf(LspDiagnostic("/tmp/X.hs", 12, 5, "error", "", "")),
            "ghc（首行只有位置，正文在后续行——首行仍必须是可定位条目）",
        )
    }

    @Test
    fun `rustc 现代双行形态`() {
        check(
            ParseStyle.RUST,
            "error[E0308]: mismatched types\n" +
                " --> /tmp/agora-lsp/x.rs:3:5\n" +
                "  |\n" +
                "3 |     let x: u8 = \"s\";\n" +
                "  |                ^^^ expected `u8`, found `&str`\n",
            listOf(LspDiagnostic("/tmp/agora-lsp/x.rs", 3, 5, "error", "E0308", "mismatched types")),
            "rustc 双行",
        )
    }

    @Test
    fun `rustc 单行形态与无码错误`() {
        check(
            ParseStyle.RUST,
            "/tmp/agora-lsp/x.rs:2:1: error[E0583]: file not found for module `foo`\n" +
                "/tmp/agora-lsp/x.rs:9:1: error: aborting due to 2 previous errors\n",
            listOf(
                LspDiagnostic("/tmp/agora-lsp/x.rs", 2, 1, "error", "E0583", "file not found for module `foo`"),
                LspDiagnostic("/tmp/agora-lsp/x.rs", 9, 1, "error", "", "aborting due to 2 previous errors"),
            ),
            "rustc 单行",
        )
    }

    @Test
    fun `javac 只有行号列按1`() {
        check(
            ParseStyle.JAVAC,
            "/tmp/A.java:3: error: cannot find symbol\n" +
                "  警告: [options] bootstrap class path not set\n" +
                "/tmp/A.java:10: warning: [serial] serializable class A has no definition of serialVersionUID\n",
            listOf(
                LspDiagnostic("/tmp/A.java", 3, 1, "error", "", "cannot find symbol"),
                LspDiagnostic("/tmp/A.java", 10, 1, "warning", "", "[serial] serializable class A has no definition of serialVersionUID"),
            ),
            "javac",
        )
    }

    @Test
    fun `kotlinc 括号逗号形态`() {
        check(
            ParseStyle.KOTLIN,
            "/tmp/agora-lsp/x.kt: (4, 9): error: unresolved reference: printlnn\n" +
                "/tmp/agora-lsp/x.kt: (6, 13): warning: 'x' is deprecated\n",
            listOf(
                LspDiagnostic("/tmp/agora-lsp/x.kt", 4, 9, "error", "", "unresolved reference: printlnn"),
                LspDiagnostic("/tmp/agora-lsp/x.kt", 6, 13, "warning", "", "'x' is deprecated"),
            ),
            "kotlinc",
        )
    }

    @Test
    fun `gofmt 与 go vet 冒号形态无级别词`() {
        check(
            ParseStyle.GO_COLON,
            "/tmp/x.go:5:11: expected declaration, found 'PKG'\n" +
                "/tmp/x.go:7:2: fmt.Println arg list ends with redundant newline\n",
            listOf(
                LspDiagnostic("/tmp/x.go", 5, 11, "error", "", "expected declaration, found 'PKG'"),
                LspDiagnostic("/tmp/x.go", 7, 2, "error", "", "fmt.Println arg list ends with redundant newline"),
            ),
            "go",
        )
    }

    @Test
    fun `nim fpc mcs 共享括号方言`() {
        check(
            ParseStyle.NIM_FPC,
            "/tmp/x.nim(5, 7) Error: type mismatch: got <int> but expected 'string'\n" +
                "/tmp/unit.lpr(12,3) Fatal: There were 1 errors compiling module, stopping\n" +
                "/tmp/x.cs(12,5): error CS1002: ; expected\n",
            listOf(
                LspDiagnostic("/tmp/x.nim", 5, 7, "error", "", "type mismatch: got <int> but expected 'string'"),
                LspDiagnostic("/tmp/unit.lpr", 12, 3, "error", "", "There were 1 errors compiling module, stopping"),
                LspDiagnostic("/tmp/x.cs", 12, 5, "error", "CS1002", "; expected"),
            ),
            "nim/fpc/mcs",
        )
    }

    @Test
    fun `ocaml 头行加次行消息`() {
        check(
            ParseStyle.OCAML,
            "File \"x.ml\", line 3, characters 4-8:\n" +
                "Error: This expression has type int but an expression was expected of type string\n",
            listOf(
                LspDiagnostic(
                    "x.ml", 3, 4, "error", "",
                    "This expression has type int but an expression was expected of type string",
                ),
            ),
            "ocaml",
        )
    }

    @Test
    fun `噪声行不产出诊断（宁缺勿猜）`() {
        val junk = "make: *** [Makefile:2: all] Error 1\nsome random text 1:2\nab:cd:3: error: x\n"
        for (style in ParseStyle.values()) {
            val diags = DiagnosticParsers.parse(style, junk)
            when (style) {
                // GO_COLON 形态最宽，"ab:cd:3: error: x" 这种它按位置匹配吃不吃？不吃：它要求
                // 末段前是 数字:数字: 且整行首是路径含冒号亦可——ab:cd:3: error: x 会匹配成
                // file=ab:cd line=3 col=... 这正是宽形态的代价，其他风格不受影响。
                ParseStyle.GO_COLON -> assertEquals("$style 宽形态预期吃 1 条", 1, diags.size)
                else -> assertEquals("$style 不该吃噪声行", emptyList<LspDiagnostic>(), diags)
            }
        }
    }
}
