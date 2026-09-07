package com.newoether.agora.courier

/**
 * Pure path mapping between filesystem paths and SAF tree document IDs.
 *
 * A persisted tree grant looks like content://…/tree/primary%3AAndroid%2Fdata whose tree
 * document ID is "primary:Android/data" — it covers everything under the primary external
 * storage's Android/data directory. This mapper converts an absolute filesystem path into
 * the matching document ID for one of the authorized trees, or null when no grant covers it.
 *
 * Kept free of android.* imports so it is unit-testable on the JVM.
 */
object SafPathMapper {

    /** Storage volume prefix of a tree document ID, e.g. "primary" in "primary:Android/data". */
    const val PRIMARY_VOLUME = "primary"

    /**
     * @param path absolute filesystem path (already normalized, no "..", starts with '/')
     * @param primaryRoot filesystem root of the primary external storage,
     *        typically "/storage/emulated/0"
     * @param authorizedTreeDocumentIds document IDs of the user's persisted tree grants,
     *        e.g. ["primary:Android/data", "primary:"]
     * @return document ID addressing [path] inside the matching tree, or null when no grant
     *         covers the path or the path is outside primary storage.
     */
    fun documentIdForPath(
        path: String,
        primaryRoot: String,
        authorizedTreeDocumentIds: Collection<String>,
    ): String? {
        val normalized = requireNormalized(path)
        if (!normalized.startsWith(primaryRoot.trimEnd('/') + "/")) return null
        val relative = normalized.removePrefix(primaryRoot.trimEnd('/')).trimStart('/')
        if (relative.isEmpty()) return null

        var best: String? = null
        var bestDepth = -1
        for (tree in authorizedTreeDocumentIds) {
            val volume = tree.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: continue
            if (volume != PRIMARY_VOLUME) continue
            val treeRelative = tree.substringAfter(':', "").trim('/')
            val covered = treeRelative.isEmpty() ||
                relative == treeRelative ||
                relative.startsWith("$treeRelative/")
            if (!covered) continue
            // Deepest matching grant wins so a dedicated Android/data grant beats a root grant.
            val depth = treeRelative.split('/').size
            if (depth > bestDepth) {
                bestDepth = depth
                best = "$PRIMARY_VOLUME:$relative"
            }
        }
        return best
    }

    /** True when [path] is covered by at least one authorized tree. */
    fun isCovered(path: String, primaryRoot: String, authorizedTreeDocumentIds: Collection<String>): Boolean =
        documentIdForPath(path, primaryRoot, authorizedTreeDocumentIds) != null

    /** Rejects relative, traversal and control-character paths outright. */
    fun requireNormalized(path: String): String {
        require(path.startsWith('/')) { "path must be absolute: $path" }
        require(!path.contains("//")) { "path must be normalized: $path" }
        require(path.split('/').none { it == "." || it == ".." }) { "path must not contain . or .. segments" }
        require(path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "path contains control characters" }
        require(path.length <= MAX_PATH_LENGTH) { "path too long" }
        return path
    }

    /** Turns an absolute path into a safe relative path for ZIP entries / manifest listings. */
    fun relativeUnder(path: String, root: String): String {
        val normalized = requireNormalized(path)
        val rootNorm = requireNormalized(root).trimEnd('/')
        require(normalized.startsWith("$rootNorm/")) { "path is not under $rootNorm" }
        return normalized.removePrefix("$rootNorm/")
    }

    private const val MAX_PATH_LENGTH = 1_024
}
