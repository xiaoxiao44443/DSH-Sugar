package app.dsh.sugar

internal data class DockedDialogSize(val width: Int, val offset: Int)

/** Android centers a floating window inside the system-safe frame. The dock also
 * includes its side's cutout, so reserve only the part extending past that inset. */
internal fun dockedDialogSize(viewWidth: Int, safeLeft: Int, safeRight: Int,
    onLeft: Boolean, dockWidth: Int, gap: Int, maxWidth: Int): DockedDialogSize {
    val safeWidth = (viewWidth - safeLeft - safeRight).coerceAtLeast(1)
    val insetAtDock = if (onLeft) safeLeft else safeRight
    val reserve = (dockWidth - insetAtDock).coerceIn(0, safeWidth - 1)
    val available = safeWidth - reserve
    val width = minOf(maxWidth, (available - 2 * gap).coerceAtLeast(1))
    return DockedDialogSize(width, if (onLeft) reserve / 2 else -reserve / 2)
}
