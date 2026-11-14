package app.dsh.sugar

/** Keep the paper against its current edge while it fits, including small changes near the threshold. */
internal fun dockNoteOnLeft(previous: Boolean?, left: Int, right: Int, preferred: Int, slack: Int): Boolean {
    if (previous == null) return left >= right
    val current = if (previous) left else right
    val other = if (previous) right else left
    return if (current < preferred - slack && other > current + slack) !previous else previous
}
