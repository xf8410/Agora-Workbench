package com.newoether.agora.lsp

/**
 * Every compiled language the Alpine sandbox can serve, as pure data.
 *
 * Design rules (learned the hard way in this repo):
 *  - Registration is data, not code: adding a language is one [LanguageSpec]
 *    entry, no logic changes; [LanguageRegistryTest] locks the table shape.
 *  - Recipes use the compiler itself in syntax/semantic check mode (guaranteed
 *    present with the language apk), never a third-party server binary we are
 *    unsure about. Interactive language servers come later on the sandbox
 *    rewrite line; the tool contract (lsp_check -> line/col diagnostics) is
 *    already satisfied by these recipes.
 *  - apk names are candidate lists verified post-install (D3: never trust the
 *    installer's boolean). A wrong candidate self-reports instead of lying.
 */
object LanguageRegistry {

    val all: List<LanguageSpec> = listOf(
        LanguageSpec(
            id = "c", displayName = "C",
            extensions = listOf("c", "h"),
            steps = listOf(
                CheckStep("gcc -fsyntax-only -std=c17 -Wall -Wextra {file}", listOf("gcc"), ParseStyle.GCC_LIKE),
                CheckStep("clang -fsyntax-only -std=c17 -Wall -Wextra {file}", listOf("clang"), ParseStyle.GCC_LIKE),
            ),
            apkCandidates = listOf("gcc", "clang"),
        ),
        LanguageSpec(
            id = "cpp", displayName = "C++",
            extensions = listOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"),
            steps = listOf(
                CheckStep("g++ -fsyntax-only -std=c++17 -Wall -Wextra {file}", listOf("g++"), ParseStyle.GCC_LIKE),
                CheckStep("clang++ -fsyntax-only -std=c++17 -Wall -Wextra {file}", listOf("clang++"), ParseStyle.GCC_LIKE),
                CheckStep("clang -x c++ -fsyntax-only -std=c++17 -Wall -Wextra {file}", listOf("clang"), ParseStyle.GCC_LIKE),
            ),
            apkCandidates = listOf("g++", "gcc", "clang"),
            note = "多文件工程需把全部源文件放进同一目录并显式传 path。",
        ),
        LanguageSpec(
            id = "rust", displayName = "Rust",
            extensions = listOf("rs"),
            steps = listOf(
                CheckStep(
                    "rustc --edition 2021 --crate-type lib --emit=metadata -o /dev/null {file}",
                    listOf("rustc"), ParseStyle.RUST, timeoutMs = 90_000,
                ),
            ),
            apkCandidates = listOf("rust"),
        ),
        LanguageSpec(
            id = "go", displayName = "Go",
            extensions = listOf("go"),
            steps = listOf(
                CheckStep("gofmt -e {file} > /dev/null", listOf("gofmt"), ParseStyle.GO_COLON),
                CheckStep("go vet {file}", listOf("go"), ParseStyle.GO_COLON, timeoutMs = 120_000),
            ),
            apkCandidates = listOf("go"),
            note = "gofmt 只查语法；go vet 做类型检查，外部依赖需要包在 module 里。",
        ),
        LanguageSpec(
            id = "zig", displayName = "Zig",
            extensions = listOf("zig"),
            steps = listOf(
                CheckStep("zig ast-check {file}", listOf("zig"), ParseStyle.GCC_LIKE),
            ),
            apkCandidates = listOf("zig"),
            note = "ast-check 是语法级；完整语义检查需 zig build（工程级，暂不覆盖）。",
        ),
        LanguageSpec(
            id = "java", displayName = "Java",
            extensions = listOf("java"),
            steps = listOf(
                CheckStep("javac -Xlint:all -d {dir} {file}", listOf("javac"), ParseStyle.JAVAC, timeoutMs = 90_000),
            ),
            apkCandidates = listOf("openjdk17", "openjdk21", "openjdk11"),
        ),
        LanguageSpec(
            id = "kotlin", displayName = "Kotlin",
            extensions = listOf("kt", "kts"),
            steps = listOf(
                CheckStep(
                    "kotlinc -nowarn -d {dir}/agora-lsp-out.jar {file}",
                    listOf("kotlinc"), ParseStyle.KOTLIN, timeoutMs = 180_000,
                ),
            ),
            apkCandidates = listOf("kotlin"),
            unverifiedRecipe = true,
            note = "Alpine 是否直接提供 kotlin 包以设备实测为准；缺则装包会如实报 missing。",
        ),
        LanguageSpec(
            id = "csharp", displayName = "C#",
            extensions = listOf("cs"),
            steps = listOf(
                CheckStep("mcs -target:library -out:{dir}/agora-lsp-out.dll {file}", listOf("mcs"), ParseStyle.NIM_FPC),
            ),
            apkCandidates = listOf("mono"),
            note = "mcs 输出 path(line,col): error CSxxxx: msg，由共享括号方言解析。",
        ),
        LanguageSpec(
            id = "fortran", displayName = "Fortran",
            extensions = listOf("f90", "f95", "f03", "f08", "for", "f"),
            steps = listOf(
                CheckStep("gfortran -fsyntax-only {file}", listOf("gfortran"), ParseStyle.GCC_LIKE),
            ),
            apkCandidates = listOf("gfortran", "gcc-fortran"),
        ),
        LanguageSpec(
            id = "pascal", displayName = "Pascal (FreePascal)",
            extensions = listOf("pas", "pp"),
            steps = listOf(
                CheckStep("fpc -nl {file}", listOf("fpc"), ParseStyle.NIM_FPC),
            ),
            apkCandidates = listOf("fpc"),
        ),
        LanguageSpec(
            id = "ada", displayName = "Ada",
            extensions = listOf("adb", "ads"),
            steps = listOf(
                CheckStep("gnatmake -cargs -gnats {file} -o {dir}/agora-lsp-ada.o", listOf("gnatmake"), ParseStyle.GCC_LIKE),
            ),
            apkCandidates = listOf("gnat", "gcc-ada"),
            unverifiedRecipe = true,
            note = "-gnats = 纯语法检查；配方未上机验证，输出如实呈现。",
        ),
        LanguageSpec(
            id = "haskell", displayName = "Haskell (GHC)",
            extensions = listOf("hs", "lhs"),
            steps = listOf(
                CheckStep("ghc -fno-code -v0 {file}", listOf("ghc"), ParseStyle.GCC_LIKE, timeoutMs = 120_000),
            ),
            apkCandidates = listOf("ghc"),
        ),
        LanguageSpec(
            id = "ocaml", displayName = "OCaml",
            extensions = listOf("ml", "mli"),
            steps = listOf(
                CheckStep("ocamlc -i {file}", listOf("ocamlc"), ParseStyle.OCAML),
            ),
            apkCandidates = listOf("ocaml"),
        ),
        LanguageSpec(
            id = "nim", displayName = "Nim",
            extensions = listOf("nim"),
            steps = listOf(
                CheckStep("nim check --hints:off {file}", listOf("nim"), ParseStyle.NIM_FPC, timeoutMs = 120_000),
            ),
            apkCandidates = listOf("nim"),
        ),
    )

    val byId: Map<String, LanguageSpec> = all.associateBy { it.id }

    /** Extension map — first language declaring an extension owns it (conflict = test failure). */
    val byExtension: Map<String, LanguageSpec> = buildMap {
        for (spec in all) {
            for (ext in spec.extensions) {
                val existing = this[ext]
                require(existing == null) { "extension '$ext' owned by both ${existing?.id} and ${spec.id}" }
                put(ext, spec)
            }
        }
    }

    /** All binaries any recipe needs — one probe run answers install state for every language. */
    val allBinaries: List<String> = all.flatMap { spec -> spec.steps.flatMap { it.needs } }.distinct().sorted()

    /** Resolve by explicit lang id, else by filename extension. Null = cannot route. */
    fun resolve(lang: String?, filename: String?): LanguageSpec? {
        val langId = lang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (langId != null) {
            // Accept a few common aliases the model may try.
            val alias = when (langId) {
                "c++", "cxx", "cpp20" -> "cpp"
                "cs", "c#", "mono" -> "csharp"
                "kt" -> "kotlin"
                "rs" -> "rust"
                "golang" -> "go"
                "f90", "fortran90" -> "fortran"
                "fp", "freepascal" -> "pascal"
                "ml" -> "ocaml"
                "hs" -> "haskell"
                else -> langId
            }
            byId[alias]?.let { return it }
            if (alias != langId) byId[langId]?.let { return it }
            return null
        }
        val name = filename?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext.isEmpty()) null else byExtension[ext]
    }
}
