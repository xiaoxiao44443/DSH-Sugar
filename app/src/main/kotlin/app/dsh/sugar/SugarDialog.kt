package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import app.dsh.sugar.SugarGlassMaterial.Companion.withAlpha
import java.util.WeakHashMap
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Native Sugar dialogs share the page's glass tint and keep the system's button and list behavior. */
internal object SugarDialog {
    private data class Palette(val surface: Int, val ink: Int, val opacity: Double, val blurDp: Double)

    private data class Dock(val onLeft: Boolean, val width: Int)
    private val docks = WeakHashMap<Activity, Dock>()
    private val palettes = WeakHashMap<Activity, Palette>()
    private val landscapeCaps = WeakHashMap<AlertDialog, Int?>()
    private data class Preview(var size: Size, var shiftX: Int = 0)
    private val previews = WeakHashMap<AlertDialog, Preview>()
    private val hex = Regex("#[0-9a-fA-F]{6}")

    fun updatePalette(activity: Activity, surface: String, ink: String, opacity: Double, blurDp: Double) {
        if (!hex.matches(surface) || !hex.matches(ink) || opacity !in 0.0..1.0 || blurDp !in 0.0..32.0) return
        val next = Palette(Color.parseColor(surface), Color.parseColor(ink), opacity, blurDp)
        if (palettes.put(activity, next) == next) return
        landscapeCaps.keys.filter { it.ownerActivity === activity && it.isShowing }.forEach { dialog ->
            paintWindow(activity, dialog, next)
            paintContents(dialog, next)
        }
    }

    private fun palette(activity: Activity) = palettes[activity] ?: Palette(
        activity.getColor(R.color.sugar_paper), activity.getColor(R.color.sugar_ink), .90, 14.0
    )

    fun updateDock(activity: Activity, onLeft: Boolean, width: Int) {
        val dock = Dock(onLeft, width.coerceAtLeast(0))
        if (docks.put(activity, dock) == dock) return
        for (dialog in landscapeCaps.keys.filter { it.ownerActivity === activity && it.isShowing })
            position(activity, dialog)
    }

    fun setPreviewSize(activity: Activity, dialog: AlertDialog, cardSize: Size?) {
        val preview = previews[dialog]
        if (preview?.size == cardSize) return
        if (cardSize == null) previews.remove(dialog)
        else if (preview == null) previews[dialog] = Preview(cardSize)
        else preview.size = cardSize
        val decor = dialog.window?.decorView ?: return
        val surface = decor.getTag(R.id.sugar_dialog_surface) as SugarDialogPeek
        surface.requestedCardHeight = cardSize?.height ?: 0
        decor.postInvalidateOnAnimation()
        position(activity, dialog)
    }

    fun movePreviewBy(activity: Activity, dialog: AlertDialog, dx: Int, dy: Int) {
        val preview = previews[dialog] ?: return
        val decor = dialog.window?.decorView ?: return
        val surface = decor.getTag(R.id.sugar_dialog_surface) as SugarDialogPeek
        if (dy != 0) {
            surface.previewShiftY += dy
            decor.postInvalidateOnAnimation()
        }
        if (dx != 0) {
            preview.shiftX += dx
            position(activity, dialog)
        }
    }

    @Suppress("DEPRECATION")
    internal fun systemInsets(root: View, includeIme: Boolean = false): Insets {
        val insets = root.rootWindowInsets ?: return Insets.NONE
        if (Build.VERSION.SDK_INT >= 30) {
            var types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            if (includeIme) types = types or WindowInsets.Type.ime()
            return insets.getInsets(types)
        }
        val cutout = insets.displayCutout
        return Insets.of(maxOf(insets.stableInsetLeft, cutout?.safeInsetLeft ?: 0),
            maxOf(insets.stableInsetTop, cutout?.safeInsetTop ?: 0),
            maxOf(insets.stableInsetRight, cutout?.safeInsetRight ?: 0),
            maxOf(if (includeIme) insets.systemWindowInsetBottom else insets.stableInsetBottom, cutout?.safeInsetBottom ?: 0))
    }

    fun previewSpace(activity: Activity): Size {
        val root = activity.window.decorView
        val metrics = root.resources.displayMetrics
        val safe = systemInsets(root, includeIme = true)
        val dock = docks[activity]
        val gap = (12 * metrics.density).roundToInt()
        val width = dockedDialogSize(root.width.takeIf { it > 0 } ?: metrics.widthPixels, safe.left, safe.right,
            dock?.onLeft == true, dock?.width ?: 0, gap, Int.MAX_VALUE).width
        val height = (root.height.takeIf { it > 0 } ?: metrics.heightPixels) - safe.top - safe.bottom - 2 * gap
        return Size(width, height.coerceAtLeast(1))
    }

    private fun position(activity: Activity, dialog: AlertDialog) {
        val window = dialog.window ?: return
        val root = activity.window.decorView
        if (root.width <= 0) return
        val dock = docks[activity]
        val attrs = window.attributes
        var width = WindowManager.LayoutParams.WRAP_CONTENT
        var offset = 0
        val landscapeCap = landscapeCaps[dialog]?.takeIf {
            root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val preview = previews[dialog]
        val previewWidth = preview?.size?.width
        if ((dock?.width ?: 0) > 0 || landscapeCap != null || previewWidth != null) {
            val safe = systemInsets(root)
            val density = root.resources.displayMetrics.density
            val position = dockedDialogSize(root.width, safe.left, safe.right, dock?.onLeft == true, dock?.width ?: 0,
                gap = (12 * density).roundToInt(), maxWidth = previewWidth ?: ((landscapeCap ?: 560) * density).roundToInt())
            width = position.width
            offset = position.offset
            if (preview != null) {
                val available = dockedDialogSize(root.width, safe.left, safe.right, dock?.onLeft == true,
                    dock?.width ?: 0, gap = 0, maxWidth = Int.MAX_VALUE).width
                val travel = (available - width).coerceAtLeast(0) / 2
                preview.shiftX = preview.shiftX.coerceIn(-travel, travel)
                offset += preview.shiftX
            }
        }
        if (attrs.width == width && attrs.x == offset) return
        attrs.gravity = Gravity.CENTER
        attrs.width = width
        attrs.x = offset
        window.attributes = attrs
    }

    fun builder(activity: Activity, landscapeMaxWidthDp: Int? = null): AlertDialog.Builder {
        val dark = Color.luminance(palette(activity).ink) > .5f
        return object : AlertDialog.Builder(activity, if (dark) R.style.SugarPopupDark else R.style.SugarPopupLight) {
            override fun create(): AlertDialog = super.create().also { dialog ->
                dialog.setOwnerActivity(activity)
                landscapeCaps[dialog] = landscapeMaxWidthDp
                val window = dialog.window ?: return@also
                val decor = window.decorView
                val surface = SugarDialogPeek(activity, window)
                decor.setTag(R.id.sugar_dialog_surface, surface)
                dialog.window?.setBackgroundDrawable(surface)
                decor.addOnAttachStateChangeListener(surface)
                installDialogFrameTouch(dialog, decor)
                paintWindow(activity, dialog, palette(activity))
                position(activity, dialog)
                val root = activity.window.decorView
                val onLayout = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                    if (r - l != oldR - oldL || b - t != oldB - oldT) position(activity, dialog)
                }
                decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        root.addOnLayoutChangeListener(onLayout)
                        position(activity, dialog)
                    }
                    override fun onViewDetachedFromWindow(view: View) = root.removeOnLayoutChangeListener(onLayout)
                })
                // The platform populates standard titles, lists and buttons during show().
                decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                        paintContents(dialog, palette(activity))
                        return true
                    }
                })
                if (Build.VERSION.SDK_INT >= 31) {
                    val wm = activity.getSystemService(WindowManager::class.java)
                    val changed = Consumer<Boolean> { paintWindow(activity, dialog, palette(activity)) }
                    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = wm.addCrossWindowBlurEnabledListener(changed)
                        override fun onViewDetachedFromWindow(v: View) = wm.removeCrossWindowBlurEnabledListener(changed)
                    })
                }
            }
        }
    }

    private fun paintWindow(activity: Activity, dialog: AlertDialog, palette: Palette) {
        val window = dialog.window ?: return
        val density = activity.resources.displayMetrics.density
        val canBlur = Build.VERSION.SDK_INT >= 31 &&
            activity.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        val material = SugarGlassMaterial(palette.surface, Color.luminance(palette.surface) < .36)
        val blur = if (canBlur && palette.blurDp > 0) material.modalBlurDp else 0.0
        val surface = window.decorView.getTag(R.id.sugar_dialog_surface) as SugarDialogPeek
        surface.paint(material, material.modalFill(palette.ink, blur > 0, palette.opacity), (blur * density).toFloat())
        window.decorView.outlineSpotShadowColor = material.shadow
        window.decorView.outlineAmbientShadowColor = material.shadow

    }

    private fun paintContents(dialog: AlertDialog, palette: Palette) {
        val decor = dialog.window?.decorView ?: return
        val ink = palette.ink
        val material = SugarGlassMaterial(palette.surface, Color.luminance(palette.surface) < .36)
        val density = decor.resources.displayMetrics.density
        val halo = .8 * (1.0 - material.modalDepth)
        val haloColor = withAlpha(material.modalTint, halo)
        val colors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(withAlpha(ink, .44), ink)
        )
        fun tint(node: View) {
            if (node is TextView) {
                node.setTextColor(colors)
                node.setHintTextColor(withAlpha(ink, .90))
                node.setShadowLayer(if (halo > 0) .85f * density else 0f, 0f, 0f, haloColor)
            }
            if (node is ViewGroup) for (i in 0 until node.childCount) tint(node.getChildAt(i))
        }
        tint(decor)
        fun dp(value: Int) = (value * density).roundToInt()
        val blue = material.accent()
        dialog.listView?.apply {
            cacheColorHint = Color.TRANSPARENT
            divider = null
            selector = RippleDrawable(ColorStateList.valueOf(withAlpha(blue, .15)), null,
                GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE) })
        }
        for (role in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(role) ?: continue
            val primary = role == AlertDialog.BUTTON_POSITIVE
            val tone = material.accent(violet = role == AlertDialog.BUTTON_NEGATIVE)
            val shape = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(material.buttonFill(tone, primary, material.modalTint))
                setStroke(1, withAlpha(tone, if (material.dark) .16 else .12))
            }
            // Inset the paint while preserving Android's button layout, minimum hit area and listeners.
            val start = button.paddingStart
            val top = button.paddingTop
            val end = button.paddingEnd
            val bottom = button.paddingBottom
            button.backgroundTintList = null
            button.background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(withAlpha(tone, .19)), shape, null),
                dp(2), dp(4), dp(2), dp(4))
            button.setPaddingRelative(start, top, end, bottom)
        }
    }
}
