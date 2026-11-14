package app.dsh.sugar

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import kotlin.math.roundToInt

/** One window surface carries the blurred card, its controls and the illustration during window animations. */
internal class SugarDialogPeek(private val activity: Activity, private val window: Window) : Drawable(),
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private val owner = activity.window.decorView
    private val decor = window.decorView
    private val figure = activity.getDrawable(R.drawable.mobile_mascot_peek)!!.mutate()
    private val density = activity.resources.displayMetrics.density
    private val radius = 17f * density
    private val card = Rect()
    private val visibleArea = Rect()
    var requestedCardHeight = 0
    var previewShiftY = 0
    private var headroom = 0
    private var footroom = 0
    private var figureShown = false
    private var frame: SugarGlassFrame? = null
    private var backdrop: DialogGlassBackdrop? = null
    private var blur = 0f

    fun paint(material: SugarGlassMaterial, fill: Int, blurRadius: Float) {
        frame = SugarGlassFrame(material, fill, radius, density).also { it.bounds = card }
        blur = blurRadius
        decor.invalidateOutline()
        invalidateSelf()
    }

    override fun onViewAttachedToWindow(view: View) {
        backdrop = DialogGlassBackdrop(activity, decor) { invalidateSelf() }
        decor.clipToOutline = false
        decor.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onViewDetachedFromWindow(view: View) {
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
        if (figureShown) decor.overlay.remove(figure)
        figureShown = false
        backdrop?.dispose()
        backdrop = null
    }

    override fun onBoundsChange(bounds: Rect) {
        card.set(bounds.left, bounds.top + headroom, bounds.right, (bounds.bottom - footroom).coerceAtLeast(bounds.top + headroom))
        frame?.bounds = card
    }

    override fun onPreDraw(): Boolean {
        if (decor.width == 0 || decor.height == 0) return true
        val safe = SugarDialog.systemInsets(owner, includeIme = true)
        decor.getWindowVisibleDisplayFrame(visibleArea)
        val usable = (owner.height - safe.top - safe.bottom).coerceAtMost(
            visibleArea.height().takeIf { it > 0 } ?: owner.height)
        // Release space reserved for the illustration as the preview grows.
        val body = maxOf(requestedCardHeight, decor.height - decor.paddingTop - decor.paddingBottom)
        val compact = owner.width > owner.height
        val cap = ((if (compact) 34 else 57) * density).roundToInt()
        val spare = (usable - body).coerceAtLeast(0)
        val placement = if (requestedCardHeight > 0) previewVerticalPlacement(spare, cap, previewShiftY) else null
        previewShiftY = placement?.shift ?: 0
        val windowY = placement?.windowY ?: 0
        val attrs = window.attributes
        if (attrs.y != windowY) {
            attrs.y = windowY
            window.attributes = attrs
        }
        val top = placement?.top ?: dialogPeekHeadroom(spare, cap, preview = false)
        val bottom = placement?.bottom ?: top
        if (headroom != top || footroom != bottom || decor.paddingTop != top || decor.paddingBottom != bottom) {
            headroom = top
            footroom = bottom
            decor.setPadding(0, top, 0, bottom)
            onBoundsChange(bounds)
            decor.invalidateOutline()
            return false
        }
        val area = dialogPeekBounds(card.left, card.top, card.right, bounds.left, bounds.top, bounds.right,
            density, compact)
        if (area != null) {
            if (figure.bounds.run { left != area.left || top != area.top || right != area.right || bottom != area.bottom })
                figure.setBounds(area.left, area.top, area.right, area.bottom)
            if (!figureShown) decor.overlay.add(figure)
        } else if (figureShown) decor.overlay.remove(figure)
        figureShown = area != null
        backdrop?.update(card, radius, blur)
        return true
    }

    override fun getPadding(padding: Rect): Boolean {
        padding.set(0, headroom, 0, footroom)
        return headroom > 0
    }

    override fun draw(canvas: Canvas) {
        if (card.isEmpty) return
        backdrop?.draw(canvas)
        frame?.draw(canvas)
    }

    override fun getOutline(outline: Outline) {
        if (frame == null || card.isEmpty) outline.setEmpty() else frame?.getOutline(outline)
    }

    override fun setAlpha(alpha: Int) { frame?.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { frame?.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in the Android SDK")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
