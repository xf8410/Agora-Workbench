package com.newoether.agora.lsp

/**
 * Parses real compiler diagnostics out of merged stdout+stderr text.
 *
 * One pure function per dialect — never guesses: a line that does not match is
 * simply not a diagnostic. The engine separately refuses to call a run "clean"
 * when the exit code is non-zero but nothing parsed (D3 lesson: an exit code
 * that is ignored or a boolean that lies must never become a green report).
 */
object DiagnosticParsers {

    fun parse(style: ParseStyle, text: String): List<LspDiagnostic> = when (style) {
        ParseStyle.GCC_LIKE -> parseGccLike(text)
        ParseStyle.RUST -> parseRust(text)
        ParseStyle.JAVAC -> parseJavac(text)
        ParseStyle.KOTLIN -> parseKotlin(text)
        ParseStyle.GO_COLON -> parseGoColon(text)
        ParseStyle.NIM_FPC -> parseNimFpc(text)
        ParseStyle.OCAML -> parseOcaml(text)
    }

    // path:3:5: error: message [-Wflag]
    private val gccLike = Regex("^(.+?):(\\d+):(\\d+): (error|warning|note|fatal): (.*)$")

    private fun parseGccLike(text: String): List<LspDiagnostic> =
        text.lineSequence().mapNotNull { line ->
            gccLike.matchEntire(line.trim())?.let { m ->
                val (file, ln, col, sev, msg) = m.destructured
                LspDiagnostic(
                    file = file, line = ln.toIntOrNull() ?: 0, column = col.toIntOrNull() ?: 1,
                    severity = normalizeSeverity(sev), code = trailingFlag(msg), message = msg.trim(),
                )
            }
        }.toList()

    // rustc legacy one-liner: path:3:5: error[E0308]: message
    private val rustOneLine = Regex("^(.+?):(\\d+):(\\d+): (error|warning)(?:\\[([EW]\\w+)\\])?: (.*)$")

    // modern rustc: "error[E0308]: msg" followed by "  --> path:3:5"
    private val rustHead = Regex("^(error|warning)(?:\\[([EW]\\w+)\\])?: (.*)$")
    private val rustArrow = Regex("^--> (.+?):(\\d+):(\\d+)$")

    private fun parseRust(text: String): List<LspDiagnostic> {
        val out = mutableListOf<LspDiagnostic>()
        var pending: Triple<String, String, String>? = null // sev, code, message
        for (raw in text.lines()) {
            val line = raw.trim()
            val one = rustOneLine.matchEntire(line)
            if (one != null) {
                val (file, ln, col, sev, code, msg) = one.destructured
                out += LspDiagnostic(
                    file = file, line = ln.toIntOrNull() ?: 0, column = col.toIntOrNull() ?: 1,
                    severity = normalizeSeverity(sev), code = code, message = msg.trim(),
                )
                pending = null
                continue
            }
            val head = rustHead.matchEntire(line)
            if (head != null) {
                val (sev, code, msg) = head.destructured
                pending = Triple(sev, code, msg.trim())
                continue
            }
            val arrow = rustArrow.matchEntire(line)
            if (arrow != null && pending != null) {
                val (file, ln, col) = arrow.destructured
                out += LspDiagnostic(
                    file = file, line = ln.toIntOrNull() ?: 0, column = col.toIntOrNull() ?: 1,
                    severity = normalizeSeverity(pending!!.first), code = pending!!.second,
                    message = pending!!.third,
                )
                pending = null
            }
        }
        return out
    }

    // javac: path.java:12: error: cannot find symbol   (column unknown -> 1)
    private val javac = Regex("^(.+?):(\\d+): (error|warning): (.*)$")

    private fun parseJavac(text: String): List<LspDiagnostic> =
        text.lineSequence().mapNotNull { line ->
            javac.matchEntire(line.trim())?.let { m ->
                val (file, ln, sev, msg) = m.destructured
                LspDiagnostic(
                    file = file, line = ln.toIntOrNull() ?: 0, column = 1,
                    severity = normalizeSeverity(sev), code = "", message = msg.trim(),
                )
            }
        }.toList()

    // kotlinc: /tmp/x.kt: (3, 5): error: unresolved reference: foo
    private val kotlin = Regex("^(.+?): \\((\\d+), ?(\\d+)\\): (error|warning): (.*)$")

    private fun parseKotlin(text: String): List<LspDiagnostic> =
        text.lineSequence().mapNotNull { line ->
            kotlin.matchEntire(line.trim())?.let { m ->
                val (file, ln, col, sev, msg) = m.destructured
                LspDiagnostic(
                    file = file, line = ln.toIntOrNull() ?: 0, column = col.toIntOrNull() ?: 1,
                    severity = normalizeSeverity(sev), code = "", message = msg.trim(),
                )
            }
        }.toList()

    // gofmt -e / go vet: x.go:5:11: expected declaration, found 'package'
    private val goColon = Regex("^(.+?):(\\d+):(\\d+): (.+)$")

    private fun parseGoColon(text: String): List<LspDiagnostic> =
        text.lineSequence().mapNotNull { line ->
            goColon.matchEntire(line.trim())?.let { m ->
                val (file, ln, col, msg) = m.destructured
                LspDiagnostic(
                    file = file, line = ln.toIntOrNull() ?: 0, column = col.toIntOrNull() ?: 1,
                    severity = "error", code = "", message = msg.trim(),
                )
            }
        }.toList()

    // nim / fpc: /tmp/x.nim(5, 7) Error: type mismatch
    private val nimFpc = Regex("^(.+?)\\((\\d+), ?(\\d+)\\) (Error|Warning|Fatal): (.*)$")

    private fun parseNimFpc(text: String): List<LspDiagnostic> =
        text.lineSequence().mapNotNull { line ->
            nimFpc.matchEntire(line.trim())?.let { m ->
                val (file, ln, col, sev, msg) = m.destructured
                LspDiagnostic(
                    file = file, line = ln.toIntOrNull() ?: 0, column = col.toIntOrNull() ?: 1,
                    severity = normalizeSeverity(sev), code = "", message = msg.trim(),
                )
            }
        }.toList()

    // ocaml header: File "x.ml", line 3, characters 4-8:
    // then either " Error: msg" on the same line or the next line.
    private val ocamlHead = Regex("^File \"([^\"]+)\", line (\\d+), characters (\\d+)(?:-(\\d+))?: ?(.*)$")
    private val ocamlMsg = Regex("^(Error|Warning)[^:]*: (.*)$")

    private fun parseOcaml(text: String): List<LspDiagnostic> {
        val out = mutableListOf<LspDiagnostic>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val m = ocamlHead.matchEntire(lines[i].trim())
            if (m != null) {
                val (file, ln, col, _, rest) = m.destructured
                var message = rest.trim()
                var severity = "error"
                if (message.isEmpty() && i + 1 < lines.size) {
                    val nxt = ocamlMsg.matchEntire(lines[i + 1].trim())
                    if (nxt != null) {
                        val (sev, msg) = nxt.destructured
                        severity = normalizeSeverity(sev)
                        message = msg.trim()
                        i += 1
                    }
                } else if (message.isNotEmpty()) {
                    val sevWord = message.substringBefore(':', "").trim()
                    severity = normalizeSeverity(sevWord.ifEmpty { "error" })
                }
                if (message.isNotEmpty()) {
                    out += LspDiagnostic(
                        file = file, line = ln.toIntOrNull() ?: 0, column = col.toIntOrNull() ?: 1,
                        severity = severity, code = "", message = message,
                    )
                }
            }
            i += 1
        }
        return out
    }

    /** gcc/clang style trailing warning flag, e.g. "… [-Wunused-variable]" -> "-Wunused-variable". */
    private val trailingFlag = Regex("\\[(#[^\\]]+|-\\w[\\w+-]*)]\\s*$")

    private fun trailingFlag(message: String): String =
        trailingFlag.find(message)?.groupValues?.get(1).orEmpty()
}
