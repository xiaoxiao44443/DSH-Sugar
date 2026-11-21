package app.dsh.sugar.workspace

/** The browser receives a directory itself; a regular file opens its parent. */
internal fun folderRevealComponents(path: String, directory: Boolean): List<String> {
    val parts = workspaceFilePath(path)
    return if (directory) parts else parts.dropLast(1)
}
