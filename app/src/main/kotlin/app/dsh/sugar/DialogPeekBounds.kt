package app.dsh.sugar

import kotlin.math.roundToInt

internal data class DialogPeekBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Give the illustration first use of spare height in a preview that already fills the safe area. */
internal fun dialogPeekHeadroom(spare: Int, cap: Int, preview: Boolean): Int =
    minOf(cap, if (preview) spare else spare / 2)

internal data class PreviewVerticalPlacement(val shift: Int, val top: Int, val bottom: Int, val windowY: Int)

/** Drag the whole preview within the safe area, keeping its padding and contents unchanged. */
internal fun previewVerticalPlacement(spare: Int, cap: Int, requestedShift: Int): PreviewVerticalPlacement {
    val top = dialogPeekHeadroom(spare, cap, preview = true)
    val bottom = minOf(top, spare - top)
    val travel = spare - top - bottom
    val centeredWindowTop = travel / 2
    val shift = requestedShift.coerceIn(-centeredWindowTop, travel - centeredWindowTop)
    return PreviewVerticalPlacement(shift, top, bottom, shift)
}

/** Fit the width; draggable previews keep their scale and clip at the window's top edge. */
internal fun dialogPeekBounds(panelLeft: Int, panelTop: Int, panelRight: Int, safeLeft: Int,
    safeTop: Int, safeRight: Int, density: Float, compact: Boolean, preview: Boolean = false): DialogPeekBounds? {
    fun dp(value: Int) = (value * density).roundToInt()
    val right = minOf(panelRight, safeRight) - dp(12)
    val left = maxOf(panelLeft, safeLeft) + dp(10)
    val bottom = panelTop + dp(4)
    val fittedHeight = minOf(dp(if (compact) 38 else 61), ((right - left) / 1.68f).toInt())
    val maxHeight = if (preview) fittedHeight else minOf(fittedHeight, bottom - safeTop)
    if (maxHeight < dp(14) || bottom <= safeTop) return null
    val width = (maxHeight * 1.68f).roundToInt()
    return DialogPeekBounds(right - width, bottom - maxHeight, right, bottom)
}
