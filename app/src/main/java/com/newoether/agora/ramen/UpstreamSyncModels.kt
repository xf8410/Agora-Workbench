package com.newoether.agora.ramen

import kotlinx.serialization.Serializable

/** Risk level assigned to one upstream update candidate. */
@Serializable
enum class UpstreamChangeRisk {
    LOW,
    MEDIUM,
    HIGH,
}

/** One changed path reported by an upstream ref comparison. */
@Serializable
data class UpstreamChangedPath(
    val path: String,
    val status: String = "modified",
)

/** Immutable identity and compatibility axes for one upstream source snapshot. */
@Serializable
data class UpstreamSourceManifest(
    val repository: String,
    val ref: String,
    val commit: String,
    val commitTime: String,
    val schemaVersion: Int? = null,
    val dataVersion: String? = null,
    val testsOverviewSha: String? = null,
    val cargoLockSha: String? = null,
    val gameConfigSha: String? = null,
    val gameDataTreeSha: String? = null,
) {
    init {
        require(repository.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) {
            "repository must be in owner/name form"
        }
        require(ref.isNotBlank()) { "ref must not be blank" }
        require(commit.matches(Regex("[0-9a-fA-F]{40}"))) { "commit must be a 40-character SHA" }
        require(schemaVersion == null || schemaVersion > 0) { "schemaVersion must be positive" }
    }
}

/** Summary used by the update gate before any verified revision is promoted. */
@Serializable
data class UpstreamChangeAssessment(
    val risk: UpstreamChangeRisk,
    val changedPaths: List<UpstreamChangedPath>,
    val reasons: List<String>,
    val requiresWorkspaceTests: Boolean,
    val requiresRamenTests: Boolean,
    val requiresHostAdapterCompile: Boolean,
    val requiresAndroidCrossCompile: Boolean,
    val requiresSchemaCompatibility: Boolean,
    val requiresSampleReplay: Boolean,
)

/**
 * Classifies an upstream comparison without reading patches or depending on Rust internals.
 * Unknown source changes are intentionally medium-risk instead of being silently accepted.
 */
object UpstreamRiskClassifier {
    private val highRiskPrefixes = listOf(
        "crates/umasim/src/game/ramen/",
        "crates/umasim/src/gamedata/",
    )
    private val highRiskExact = setOf(
        "crates/umasim/src/game/traits.rs",
        "Cargo.lock",
        "Cargo.toml",
    )
    private val mediumRiskPrefixes = listOf(
        "crates/umasim/src/trainer/",
        "crates/umasim/src/bin/",
        "crates/umasim/src/utils.rs",
        "gamedata/",
    )
    private val mediumRiskExact = setOf(
        "game_config.toml",
    )
    private val lowRiskPrefixes = listOf(
        ".trae/documents/",
        "docs/",
    )
    private val lowRiskExact = setOf(
        "README.md",
        "README_CN.md",
        "AGENTS.md",
    )

    /** Classify changed repository paths and derive the required verification matrix. */
    fun assess(paths: List<UpstreamChangedPath>): UpstreamChangeAssessment {
        val normalized = paths.map { it.copy(path = normalize(it.path)) }
        val pathRisks = normalized.map { it.path to classifyPath(it.path) }
        val risk = pathRisks.maxOfOrNull { it.second } ?: UpstreamChangeRisk.LOW
        val reasons = pathRisks
            .filter { it.second == risk }
            .map { (path, _) -> reasonFor(path, risk) }
            .distinct()
            .take(20)

        return UpstreamChangeAssessment(
            risk = risk,
            changedPaths = normalized,
            reasons = reasons,
            requiresWorkspaceTests = normalized.isNotEmpty(),
            requiresRamenTests = risk >= UpstreamChangeRisk.MEDIUM,
            requiresHostAdapterCompile = risk >= UpstreamChangeRisk.MEDIUM,
            requiresAndroidCrossCompile = risk == UpstreamChangeRisk.HIGH,
            requiresSchemaCompatibility = normalized.any(::touchesContractOrInterface),
            requiresSampleReplay = risk == UpstreamChangeRisk.HIGH,
        )
    }

    /** Return the risk of a single normalized repository path. */
    fun classifyPath(path: String): UpstreamChangeRisk {
        val value = normalize(path)
        return when {
            value in highRiskExact || highRiskPrefixes.any(value::startsWith) -> UpstreamChangeRisk.HIGH
            value in mediumRiskExact || mediumRiskPrefixes.any(value::startsWith) -> UpstreamChangeRisk.MEDIUM
            value in lowRiskExact || lowRiskPrefixes.any(value::startsWith) -> UpstreamChangeRisk.LOW
            else -> UpstreamChangeRisk.MEDIUM
        }
    }

    private fun touchesContractOrInterface(item: UpstreamChangedPath): Boolean {
        val path = item.path.lowercase()
        return path.contains("contract") ||
            path.contains("schema") ||
            path == "crates/umasim/src/game/traits.rs" ||
            path.startsWith("crates/umasim/src/lib.rs")
    }

    private fun reasonFor(path: String, risk: UpstreamChangeRisk): String = when (risk) {
        UpstreamChangeRisk.HIGH -> "高风险核心或数据加载变更：$path"
        UpstreamChangeRisk.MEDIUM -> "需要编译与回归验证的实现变更：$path"
        UpstreamChangeRisk.LOW -> "仅文档或说明变更：$path"
    }

    private fun normalize(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./")
}
