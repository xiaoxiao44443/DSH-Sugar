package app.dsh.sugar

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout

/** Both recovery surfaces use the physical window, including the outer edge beyond a landscape cutout. */
@Suppress("DEPRECATION")
internal fun Activity.showRecoveryNavigation(navigation: RecoveryNavigation) {
    window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
    else window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    val outer = FrameLayout(this).apply {
        setBackgroundColor(getColor(R.color.sugar_recovery_paper))
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            } else {
                val cutout = insets.displayCutout
                view.setPadding(maxOf(insets.systemWindowInsetLeft,cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.systemWindowInsetTop,cutout?.safeInsetTop ?: 0),
                    maxOf(insets.systemWindowInsetRight,cutout?.safeInsetRight ?: 0),
                    maxOf(insets.systemWindowInsetBottom,cutout?.safeInsetBottom ?: 0))
            }
            insets
        }
        addView(navigation,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
    }
    setContentView(outer)
    applyRecoveryGesturePreference()
    outer.requestApplyInsets()
}

@Suppress("DEPRECATION")
internal fun Activity.applyRecoveryGesturePreference() {
    if (getSharedPreferences("edge-navigation", Context.MODE_PRIVATE).getBoolean("fullEdgeGestures",false)) {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.navigationBars())
        } else window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
