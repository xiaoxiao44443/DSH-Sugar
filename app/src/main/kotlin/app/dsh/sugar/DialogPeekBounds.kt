package app.dsh.sugar

import kotlin.math.roundToInt

internal data class DialogPeekBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Give the illustration first use of spare height in a preview that already fills the safe area. */
internal fun dialogPeekHeadroom(spare: Int, cap: Int, preview: Boolean): Int =
    minOf(cap, if (preview) spare else spare / 2)

internal data class PreviewVerticalPlacement(val shift: Int, val top: Int, val bottom: Int, val windowY: Int)

/** Move the visible card. The transparent margins for the mascot can shrink at either screen edge. */
internal fun previewVerticalPlacement(spare: Int, cap: Int, requestedShift: Int): PreviewVerticalPlacement {
    val defaultTop = dialogPeekHeadroom(spare, cap, preview = true)
    val defaultBottom = minOf(defaultTop, spare - defaultTop)
    val initialCardTop = (spare - defaultTop - defaultBottom) / 2 + defaultTop
    val shift = requestedShift.coerceIn(-initialCardTop, spare - initialCardTop)
    val cardTop = initialCardTop + shift
    val top = minOf(cardTop, cap)
    val bottom = minOf(spare - cardTop, cap)
    val centeredWindowTop = (spare - top - bottom) / 2
    return PreviewVerticalPlacement(shift, top, bottom, cardTop - top - centeredWindowTop)
}

/** Keep the illustration inside the panel's width and the system-safe owner window. */
internal fun dialogPeekBounds(panelLeft: Int, panelTop: Int, panelRight: Int, safeLeft: Int,
    safeTop: Int, safeRight: Int, density: Float, compact: Boolean): DialogPeekBounds? {
    fun dp(value: Int) = (value * density).roundToInt()
    val right = minOf(panelRight, safeRight) - dp(12)
    val left = maxOf(panelLeft, safeLeft) + dp(10)
    val bottom = panelTop + dp(4)
    val maxHeight = minOf(dp(if (compact) 38 else 61), bottom - safeTop, ((right - left) / 1.68f).toInt())
    if (maxHeight < dp(14)) return null
    val width = (maxHeight * 1.68f).roundToInt()
    return DialogPeekBounds(right - width, bottom - maxHeight, right, bottom)
}
