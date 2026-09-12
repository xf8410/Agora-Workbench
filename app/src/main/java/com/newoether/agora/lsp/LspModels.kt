package com.newoether.agora.lsp

/**
 * Language-pack diagnostics models (pure JVM, unit-testable).
 *
 * This is the data layer of the sandbox LSP feature: every compiled language
 * the Alpine sandbox can install is described by a [LanguageSpec]; a check run
 * produces [LspDiagnostic]s parsed from real compiler output by
 * [DiagnosticParsers]. No Android or proot dependency lives in this file.
 */

/** Output dialect of one check step — selects the parser in [DiagnosticParsers]. */
enum class ParseStyle {
    /** `path:line:col: error: message` (gcc/clang/zig/ghc/gfortran style). */
    GCC_LIKE,

    /** rustc: same dialect plus the modern `error[E0308]: msg` + `--> path:line:col` pair. */
    RUST,

    /** `path:line: error: message` (column unknown, reported as 1). */
    JAVAC,

    /** `path: (line, col): error: message` (kotlinc). */
    KOTLIN,

    /** `path:line:col: message` with no severity word (gofmt -e / go vet). */
    GO_COLON,

    /** `path(line, col) Error: message` (nim check / fpc). */
    NIM_FPC,

    /** `File "path", line N, characters A-B:` with the message on the next line. */
    OCAML,
}

/** One diagnostic line extracted from compiler output. 1-based line/column. */
data class LspDiagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    /** "error" | "warning" | "info" — normalized, lowercase. */
    val severity: String,
    /** Diagnostic code when the tool prints one (E0308, -Wunused, CS1002, …). */
    val code: String,
    val message: String,
)

/** A single runnable check step for one language. */
data class CheckStep(
    /** Shell command template; `{file}` / `{dir}` are substituted by the engine. */
    val command: String,
    /** Binaries that must exist in the sandbox before this step can run. */
    val needs: List<String>,
    val parser: ParseStyle,
    val timeoutMs: Int = 60_000,
)

/** A compiled language served by the sandbox language packs. */
data class LanguageSpec(
    val id: String,
    val displayName: String,
    /** Lowercase file extensions without the dot (e.g. "rs", "kt"). */
    val extensions: List<String>,
    /**
     * Steps run in order; execution stops at the first step that yields error
     * diagnostics or a non-zero exit code. Later steps are skipped as redundant.
     */
    val steps: List<CheckStep>,
    /** apk package names tried in order by lsp_install (names self-correct at runtime). */
    val apkCandidates: List<String>,
    /** Recipe not yet validated on a real device — surfaced honestly in outputs. */
    val unverifiedRecipe: Boolean = false,
    val note: String = "",
)

/** What one recipe step actually produced. */
data class StepOutcome(
    val command: String,
    val exitCode: Int,
    val diagnostics: List<LspDiagnostic>,
    /** True when exit != 0 but no diagnostic line was parsed — never treated as clean. */
    val unparsedFailure: Boolean,
    /** Tail of raw output so the model can still read what the compiler said. */
    val rawTail: String,
)

/** Result of one lsp_check run. [clean] may only be true when every step passed. */
data class CheckOutcome(
    val langId: String,
    val target: String,
    val clean: Boolean,
    val ranSteps: List<StepOutcome>,
    val skippedSteps: List<String>,
    /** Binaries missing for every step to run — install hint goes with this. */
    val missingTools: List<String>,
    /** Non-null when the check could not run at all (bad args, write failure…). */
    val error: String? = null,
) {
    val diagnostics: List<LspDiagnostic>
        get() = ranSteps.flatMap { it.diagnostics }
}

/** Result of one lsp_install run. Success is verified post-install, never trusted from apk. */
data class InstallOutcome(
    val langId: String,
    val ok: Boolean,
    val attempts: List<String>,
    /** Binary → version line observed after install (empty means still not runnable). */
    val verified: Map<String, String>,
    val error: String? = null,
)

/** Tiny helper shared by parsers: normalize a severity word to our lowercase scale. */
internal fun normalizeSeverity(word: String): String = when (word.lowercase()) {
    "error", "fatal", "err" -> "error"
    "warning", "warn" -> "warning"
    else -> "info" // note / info / hint
}
