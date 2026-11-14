package app.dsh.sugar

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val DEFAULT_PREVIEW_FRACTION = .90f
internal const val PREVIEW_FRAME_MARGIN_DP = 2
internal const val PREVIEW_TOOLBAR_WIDTH_DP = 48
internal const val PREVIEW_SIDE_BOTTOM_INSET_DP = 10

internal data class VirtualPreviewSize(val width: Int, val height: Int)

internal data class VirtualPreviewRange(val small: VirtualPreviewSize, val large: VirtualPreviewSize) {
    fun at(fraction: Float): VirtualPreviewSize {
        val t = fraction.coerceIn(0f, 1f)
        return VirtualPreviewSize(
            small.width + ((large.width - small.width) * t).roundToInt(),
            small.height + ((large.height - small.height) * t).roundToInt()
        )
    }

    // The lower-right grip on a centered dialog moves half a dimension's change.
    fun dragged(start: Float, dx: Float, dy: Float): Float {
        val widthSpan = (large.width - small.width) * .5f
        val heightSpan = (large.height - small.height) * .5f
        val lengthSquared = widthSpan * widthSpan + heightSpan * heightSpan
        val change = when {
            lengthSquared == 0f -> 0f
            // A narrow dialog can stay at toolbar width through the whole zoom range.
            widthSpan == 0f && abs(dx) > abs(dy) -> dx / heightSpan
            else -> (widthSpan * dx + heightSpan * dy) / lengthSquared
        }
        return (start + change).coerceIn(0f, 1f)
    }
}

/** Compare the fitted screenshot in both layouts at the same initial zoom, independent of a drag. */
internal fun useSidePreviewToolbar(width: Int, height: Int, density: Float, headerChrome: Int, aspect: Float): Boolean {
    val toolbar = (density * PREVIEW_TOOLBAR_WIDTH_DP).roundToInt()
    val margins = 2 * (density * PREVIEW_FRAME_MARGIN_DP).roundToInt()
    if (aspect >= 1f || width <= margins + toolbar) return false

    val sideChrome = headerChrome + (density * PREVIEW_SIDE_BOTTOM_INSET_DP).roundToInt()
    val sideRange = virtualPreviewRange(width, height, density, sideChrome, aspect, sideToolbar = true)
    val minActionHeight = (40 * density).roundToInt()
    if (width >= 5 * toolbar && sideRange.small.height < 5 * minActionHeight) return false

    val bottomChrome = headerChrome + toolbar
    val bottom = virtualPreviewRange(width, height, density, bottomChrome, aspect).at(DEFAULT_PREVIEW_FRACTION)
    val side = sideRange.at(DEFAULT_PREVIEW_FRACTION)
    val bottomImageHeight = minOf(bottom.height.toFloat(), (bottom.width - margins) / aspect)
    val sideImageHeight = minOf(side.height.toFloat(), (side.width - margins - toolbar) / aspect)
    fun fill(size: VirtualPreviewSize, imageHeight: Float, chrome: Int) =
        (imageHeight * aspect / size.width) * (imageHeight / (size.height + chrome))
    return sideImageHeight >= bottomImageHeight &&
        fill(side, sideImageHeight, sideChrome) > fill(bottom, bottomImageHeight, bottomChrome)
}

/** Window width and image viewport height, measured in pixels inside the available safe area. */
internal fun virtualPreviewRange(
    width: Int, height: Int, density: Float, chrome: Int, aspect: Float, sideToolbar: Boolean = false
): VirtualPreviewRange {
    fun dp(value: Int) = (density * value).roundToInt()
    val safeWidth = width.coerceAtLeast(1)
    val toolbar = dp(PREVIEW_TOOLBAR_WIDTH_DP)
    val horizontalChrome = 2 * dp(PREVIEW_FRAME_MARGIN_DP) + if (sideToolbar) toolbar else 0
    val usableWidth = (safeWidth - horizontalChrome).coerceAtLeast(1)
    val usableHeight = (height - chrome).coerceAtLeast(1)
    val maxImageWidth = (usableHeight * aspect).coerceIn(1f, usableWidth.toFloat()).roundToInt()
    val maxHeight = (usableWidth / aspect).coerceIn(1f, usableHeight.toFloat()).roundToInt()
    val fittedWidth = (maxImageWidth + horizontalChrome).coerceAtMost(safeWidth)
    if (sideToolbar) {
        // Five full-height actions fit in 240dp. Keep the grip usable on shorter screens too.
        val minHeight = minOf(5 * toolbar, (maxHeight * .85f).roundToInt().coerceAtLeast(1))
        val minImageWidth = (minHeight * aspect).roundToInt().coerceIn(1, maxImageWidth)
        val minWidth = (minImageWidth + horizontalChrome).coerceAtMost(safeWidth)
        return VirtualPreviewRange(VirtualPreviewSize(minWidth, minHeight), VirtualPreviewSize(fittedWidth, maxHeight))
    }
    val minWidth = minOf(dp(240), safeWidth)
    val maxWidth = maxOf(minWidth, fittedWidth)
    // Keep the frame fitted at saved zoom levels too. If the toolbar fixes the width,
    // retain vertical travel even when there is insufficient height to fill that width.
    val minHeight = if (minWidth == maxWidth) {
        (maxHeight * 2f / 3f).roundToInt().coerceAtLeast(1)
    } else {
        ((minWidth - horizontalChrome) / aspect).roundToInt().coerceIn(1, maxHeight)
    }
    return VirtualPreviewRange(VirtualPreviewSize(minWidth, minHeight), VirtualPreviewSize(maxWidth, maxHeight))
}