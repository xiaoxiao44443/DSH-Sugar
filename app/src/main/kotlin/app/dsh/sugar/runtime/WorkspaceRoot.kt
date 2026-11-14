package app.dsh.sugar.runtime

private val nestedMount = Regex("/workspace/(?:local|phone)-[0-9a-f]{64}/.*")

/** Keep in sync with guest/workspace-root.cjs. Root bindings never import system or profile directories. */
internal fun isWorkspaceRoot(path: String): Boolean {
    if (!(path.startsWith("/workspace/") || path.startsWith("/home/")) || path.toByteArray().size > 4095) return false
    if (path == "/home/sugar" || path == "/workspace/deepseek-harness" || nestedMount.matches(path)) return false
    return path.substring(1).split('/').all { part ->
        part.isNotBlank() && !part.startsWith('.') && '\\' !in part && part.none(Char::isISOControl)
    }
}
