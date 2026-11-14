package app.dsh.sugar

/** A rendered band, grip, or gesture target in Activity-window pixels. */
internal data class TaskRailRegion(
    val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float = 0f
) {
    val valid get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        radius.isFinite() && right > left && bottom > top
    val corner get() = radius.coerceIn(0f, minOf(right - left, bottom - top) * .5f)

    fun contains(x: Float, y: Float): Boolean {
        if (!valid || x < left || y < top || x >= right || y >= bottom) return false
        val r = corner
        if (r == 0f) return true
        val dx = x - x.coerceIn(left + r, right - r)
        val dy = y - y.coerceIn(top + r, bottom - r)
        return dx * dx + dy * dy <= r * r
    }
}
