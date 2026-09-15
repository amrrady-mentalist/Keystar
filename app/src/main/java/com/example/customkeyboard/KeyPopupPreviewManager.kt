package com.example.customkeyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manages the high-performance key popup preview.
 *
 * Displays a snappy, circular preview bubble above pressed keys.
 * On quick tap: shows the pressed key label and optional three-dots indicator for alternates.
 * On hold (long-press): transitions with a snappy pop animation into an accented Material 3
 * inner badge displaying the associated alternate character.
 */
class KeyPopupPreviewManager(
    private val context: Context,
    private val overlayContainer: FrameLayout,
    private val getThemeMode: () -> String,
    private val getKeyTypeface: () -> Typeface
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun dpF(v: Float): Float = v * density

    val popupDiameterDp = 52
    val innerBadgeDiameterDp = 38

    private val popupView: FrameLayout
    private val normalContainer: LinearLayout
    private val tvLabel: TextView
    private val tvDots: TextView

    private val longPressContainer: FrameLayout
    private val tvHint: TextView

    private var currentAnchor: View? = null
    var isLongPressActive = false
        private set
    var isEnabled: Boolean = true

    init {
        val popupDiameter = dp(popupDiameterDp)
        popupView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(popupDiameter, popupDiameter)
            visibility = View.GONE
            elevation = dpF(8f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }

        // 1. Normal state container (letter + optional dots)
        normalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        tvLabel = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            includeFontPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        normalContainer.addView(tvLabel)

        tvDots = TextView(context).apply {
            text = "···"
            gravity = Gravity.CENTER
            textSize = 10f
            letterSpacing = 0.18f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = -dp(1)
            }
        }
        normalContainer.addView(tvDots)
        popupView.addView(normalContainer)

        // 2. Long-press accented container (inner circle with associated character)
        val innerSize = dp(innerBadgeDiameterDp)
        longPressContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
            visibility = View.GONE
            clipToOutline = true
        }

        tvHint = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        longPressContainer.addView(tvHint)
        popupView.addView(longPressContainer)

        overlayContainer.addView(popupView)
        applyTheme()
    }

    fun applyTheme() {
        val theme = getThemeMode()
        val isLight = theme == "light"
        val isPitchBlack = theme == "pitch_black"

        val bubbleBgColor = when {
            isPitchBlack -> Color.parseColor("#262626")
            isLight -> Color.parseColor("#FFFFFF")
            else -> Color.parseColor("#32353A") // Charcoal elevated bubble matching screenshot
        }

        val textColor = when {
            isLight -> Color.parseColor("#1F1F1F")
            else -> Color.parseColor("#FFFFFF")
        }

        val dotsColor = when {
            isLight -> Color.parseColor("#80000000")
            else -> Color.parseColor("#80FFFFFF")
        }

        val accentBadgeColor = when {
            isPitchBlack -> Color.parseColor("#5A95FF")
            isLight -> Color.parseColor("#D3E3FD")
            else -> Color.parseColor("#A8C7FA") // Soft blue matching screenshot
        }

        val accentTextColor = when {
            isLight -> Color.parseColor("#041E49")
            else -> Color.parseColor("#041E49") // Deep navy text for contrast on accent
        }

        popupView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bubbleBgColor)
            if (isLight) {
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
        }

        longPressContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentBadgeColor)
        }

        tvLabel.setTextColor(textColor)
        tvLabel.typeface = getKeyTypeface()

        tvDots.setTextColor(dotsColor)

        tvHint.setTextColor(accentTextColor)
    }

    fun showPopup(anchor: View, label: String, hint: String?) {
        if (!isEnabled) return
        currentAnchor = anchor
        isLongPressActive = false

        applyTheme()

        tvLabel.text = label
        tvHint.text = hint ?: ""
        tvDots.visibility = if (hint != null) View.VISIBLE else View.GONE

        normalContainer.visibility = View.VISIBLE
        longPressContainer.visibility = View.GONE

        updatePosition(anchor)

        popupView.animate().cancel()
        popupView.visibility = View.VISIBLE
        popupView.scaleX = 0.72f
        popupView.scaleY = 0.72f
        popupView.alpha = 0.7f
        popupView.translationY = dpF(6f)

        popupView.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .translationY(0f)
            .setDuration(55)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()
    }

    fun transitionToLongPress(hint: String) {
        if (!isEnabled || popupView.visibility != View.VISIBLE) return
        isLongPressActive = true

        tvHint.text = hint
        normalContainer.visibility = View.GONE

        longPressContainer.visibility = View.VISIBLE
        longPressContainer.scaleX = 0.65f
        longPressContainer.scaleY = 0.65f
        longPressContainer.alpha = 0.8f

        longPressContainer.animate().cancel()
        longPressContainer.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(75)
            .setInterpolator(OvershootInterpolator(1.25f))
            .start()
    }

    fun hidePopup(immediate: Boolean = false) {
        if (popupView.visibility != View.VISIBLE) return
        popupView.animate().cancel()
        longPressContainer.animate().cancel()

        if (immediate) {
            popupView.visibility = View.GONE
            isLongPressActive = false
            currentAnchor = null
        } else {
            popupView.animate()
                .scaleX(0.75f)
                .scaleY(0.75f)
                .alpha(0f)
                .setDuration(45)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        popupView.visibility = View.GONE
                        popupView.scaleX = 1f
                        popupView.scaleY = 1f
                        popupView.alpha = 1f
                        isLongPressActive = false
                        currentAnchor = null
                    }
                })
                .start()
        }
    }

    private fun updatePosition(anchor: View) {
        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val rootLoc = IntArray(2)
        overlayContainer.getLocationInWindow(rootLoc)

        val keyX = anchorLoc[0] - rootLoc[0]
        val keyY = anchorLoc[1] - rootLoc[1]
        val keyWidth = anchor.width

        val popupDiameter = dp(popupDiameterDp)
        val keyCenterX = keyX + keyWidth / 2
        var popupLeft = keyCenterX - popupDiameter / 2

        val margin = dp(4)
        val rootWidth = overlayContainer.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        popupLeft = popupLeft.coerceIn(margin, rootWidth - popupDiameter - margin)

        // Position directly above the key (overlapping the top border by 2dp)
        val popupBottom = keyY + dp(2)
        var popupTop = popupBottom - popupDiameter
        popupTop = maxOf(dp(2), popupTop)

        popupView.x = popupLeft.toFloat()
        popupView.y = popupTop.toFloat()
    }
}
