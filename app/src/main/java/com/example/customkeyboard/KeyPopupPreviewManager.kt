package com.example.customkeyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manages key popup previews and Arabic/character variations bubble.
 *
 * 1. Quick tap: displays a crisp, circular preview bubble above the pressed key.
 * 2. Hold (long-press): transitions into a Material 3 floating pill showing all
 *    character variations (e.g. ك -> ک, گ, / or 2-row layout for ا -> إ, أ, ٱ, ء, -, آ).
 * 3. Drag / slide: tracks finger movement across variations and dynamically updates
 *    the highlighted item with smooth haptic feedback ticks.
 * 4. Release: commits the currently selected variation.
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
    val itemWidthDp = 42
    val itemHeightDp = 46

    // Single key tap preview view
    private val popupView: FrameLayout
    private val normalContainer: LinearLayout
    private val tvLabel: TextView
    private val tvDots: TextView

    // Variations bubble view
    private val variationsView: FrameLayout
    private val variationsContainer: FrameLayout
    private var currentVariations: List<String> = emptyList()
    private var twoRowSplit: Pair<List<String>, List<String>>? = null
    private var selectedVariation: String? = null
    private val itemViews = mutableListOf<VariationCell>()
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var activeRow: Int = 0

    // Geometry of the variations bubble, in overlay coordinates. Touch mapping uses these
    // numbers (not the animated/scaled views) so the highlight always follows the finger.
    private var bubbleLeftPx = 0f
    private var bubbleTopPx = 0f
    private var bubbleHeightPx = 0f
    private var cellWidthPx = 0f
    private var cellHeightPx = 0f
    private var gridPadPx = 0f
    private var gridCols = 0
    private var hasMovedOutOfDeadZone = false

    // Final Y (translationY) of the single-key preview bubble
    private var popupBaseY = 0f

    private var currentAnchor: View? = null
    var isLongPressActive = false
        private set
    var isEnabled: Boolean = true

    private data class VariationCell(
        val character: String,
        val row: Int,
        val col: Int,
        val container: FrameLayout,
        val textView: TextView
    )

    init {
        val popupDiameter = dp(popupDiameterDp)
        popupView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(popupDiameter, popupDiameter, Gravity.TOP or Gravity.LEFT)
            visibility = View.GONE
            elevation = dpF(8f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }

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

        // Floating variations pill/card
        variationsView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.LEFT
            )
            visibility = View.GONE
            elevation = dpF(12f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }

        variationsContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        variationsView.addView(variationsContainer)

        overlayContainer.addView(popupView)
        overlayContainer.addView(variationsView)
        applyTheme()
    }

    private fun getColors(): PopupColors {
        val theme = getThemeMode()
        return when (theme) {
            "light" -> PopupColors(
                bubbleBg = Color.parseColor("#FFFFFF"),
                bubbleStroke = Color.parseColor("#E0E0E0"),
                textColor = Color.parseColor("#1F1F1F"),
                accentBadge = Color.parseColor("#D3E3FD"),
                accentText = Color.parseColor("#041E49"),
                dotsColor = Color.parseColor("#757575")
            )
            "pitch_black" -> PopupColors(
                bubbleBg = Color.parseColor("#262626"),
                bubbleStroke = null,
                textColor = Color.parseColor("#FFFFFF"),
                accentBadge = Color.parseColor("#5A95FF"),
                accentText = Color.parseColor("#FFFFFF"),
                dotsColor = Color.parseColor("#9E9E9E")
            )
            else -> PopupColors(
                bubbleBg = Color.parseColor("#32353A"),
                bubbleStroke = null,
                textColor = Color.parseColor("#FFFFFF"),
                accentBadge = Color.parseColor("#A8C7FA"),
                accentText = Color.parseColor("#041E49"),
                dotsColor = Color.parseColor("#B0B0B0")
            )
        }
    }

    private data class PopupColors(
        val bubbleBg: Int,
        val bubbleStroke: Int?,
        val textColor: Int,
        val accentBadge: Int,
        val accentText: Int,
        val dotsColor: Int
    )

    fun applyTheme() {
        val colors = getColors()

        val popupRadius = dp(popupDiameterDp / 2).toFloat()
        popupView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = popupRadius
            setColor(colors.bubbleBg)
            if (colors.bubbleStroke != null) {
                setStroke(dp(1), colors.bubbleStroke)
            }
        }

        tvLabel.setTextColor(colors.textColor)
        tvLabel.typeface = getKeyTypeface()
        tvDots.setTextColor(colors.dotsColor)

        updateVariationsVisuals()
    }

    private var currentSessionId = 0

    fun showPopup(
        anchor: View,
        label: String,
        hint: String?,
        hasAlternates: Boolean = false
    ) {
        if (!isEnabled) return
        val sessionId = ++currentSessionId
        currentAnchor = anchor
        isLongPressActive = false
        selectedVariation = null

        popupView.animate().setListener(null).cancel()
        variationsView.animate().setListener(null).cancel()
        variationsView.visibility = View.GONE

        applyTheme()

        tvLabel.text = label
        tvDots.visibility = if (hasAlternates || hint != null) View.VISIBLE else View.GONE

        normalContainer.visibility = View.VISIBLE

        updatePosition(anchor)

        popupView.visibility = View.VISIBLE
        popupView.scaleX = 0.72f
        popupView.scaleY = 0.72f
        popupView.alpha = 0.7f
        popupView.translationY = popupBaseY + dpF(6f)

        popupView.animate()
            .setListener(null)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .translationY(popupBaseY)
            .setDuration(55)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()
    }

    fun transitionToVariations(
        anchor: View,
        variations: List<String>,
        defaultSelected: String?,
        twoRow: Pair<List<String>, List<String>>? = null,
        initialTouchX: Float = 0f,
        initialTouchY: Float = 0f
    ) {
        if (!isEnabled || (variations.isEmpty() && twoRow == null)) return
        val sessionId = ++currentSessionId
        currentAnchor = anchor
        isLongPressActive = true
        twoRowSplit = twoRow
        this.initialTouchX = initialTouchX
        this.initialTouchY = initialTouchY
        hasMovedOutOfDeadZone = false
        currentVariations = if (twoRow != null) {
            twoRow.first + twoRow.second
        } else {
            variations
        }
        selectedVariation = defaultSelected ?: currentVariations.firstOrNull()

        if (twoRow != null) {
            val topIdx = twoRow.first.indexOf(selectedVariation)
            activeRow = if (topIdx >= 0) 0 else 1
        } else {
            activeRow = 0
        }

        // Cancel and detach any lingering animators
        popupView.animate().setListener(null).cancel()
        popupView.visibility = View.GONE

        variationsView.animate().setListener(null).cancel()

        applyTheme()
        buildVariationsLayout()
        positionVariationsBubble(anchor)

        variationsView.visibility = View.VISIBLE
        variationsView.scaleX = 0.75f
        variationsView.scaleY = 0.75f
        variationsView.alpha = 0.7f

        variationsView.animate()
            .setListener(null)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(85)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    private fun buildVariationsLayout() {
        variationsContainer.removeAllViews()
        itemViews.clear()

        val colors = getColors()
        val rootWidth = overlayContainer.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val maxAvailableW = (rootWidth - dp(16)).coerceAtLeast(dp(100))
        val itemH = dp(itemHeightDp)
        val cellPad = dp(4)

        if (twoRowSplit != null) {
            // Two-row grid (e.g., Alef with Hamza variations from Screenshot 7)
            val (topRow, bottomRow) = twoRowSplit!!
            val colCount = maxOf(topRow.size, bottomRow.size)
            val calculatedItemW = minOf(dp(itemWidthDp), (maxAvailableW - dp(12)) / maxOf(1, colCount))
            val itemW = maxOf(dp(28), calculatedItemW)
            val bubbleW = colCount * itemW + dp(12)
            val bubbleH = itemH * 2 + dp(12)
            cellWidthPx = itemW.toFloat()
            cellHeightPx = itemH.toFloat()
            gridPadPx = dp(6).toFloat()
            gridCols = colCount

            val rootLinear = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = FrameLayout.LayoutParams(bubbleW, bubbleH)
            }

            fun addRowLayout(rowItems: List<String>, rowIndex: Int) {
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        itemH
                    )
                }
                rowItems.forEachIndexed { colIndex, char ->
                    val cell = createVariationCell(char, rowIndex, colIndex, itemW, itemH, cellPad)
                    rowLayout.addView(cell.container)
                    itemViews.add(cell)
                }
                rootLinear.addView(rowLayout)
            }

            addRowLayout(topRow, 0)
            addRowLayout(bottomRow, 1)

            variationsView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(20f)
                setColor(colors.bubbleBg)
                if (colors.bubbleStroke != null) setStroke(dp(1), colors.bubbleStroke)
            }
            variationsContainer.addView(rootLinear)
        } else {
            // Single-row pill (e.g. Kaf, Jeem, Feh, Qaf, Sheen, Yeh from Screenshots 1-6)
            val itemCount = currentVariations.size
            val calculatedItemW = minOf(dp(itemWidthDp), (maxAvailableW - dp(8)) / maxOf(1, itemCount))
            val itemW = maxOf(dp(28), calculatedItemW)
            val bubbleW = itemCount * itemW + dp(8)
            val bubbleH = itemH + dp(8)
            cellWidthPx = itemW.toFloat()
            cellHeightPx = itemH.toFloat()
            gridPadPx = dp(4).toFloat()
            gridCols = itemCount

            val rowLinear = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = FrameLayout.LayoutParams(bubbleW, bubbleH)
            }

            currentVariations.forEachIndexed { colIndex, char ->
                val cell = createVariationCell(char, 0, colIndex, itemW, itemH, cellPad)
                rowLinear.addView(cell.container)
                itemViews.add(cell)
            }

            variationsView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(26f)
                setColor(colors.bubbleBg)
                if (colors.bubbleStroke != null) setStroke(dp(1), colors.bubbleStroke)
            }
            variationsContainer.addView(rowLinear)
        }

        updateVariationsVisuals()
    }

    private fun createVariationCell(
        char: String,
        rowIndex: Int,
        colIndex: Int,
        width: Int,
        height: Int,
        pad: Int
    ): VariationCell {
        val cellFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            setPadding(pad, pad, pad, pad)
        }

        val tv = TextView(context).apply {
            text = char
            gravity = Gravity.CENTER
            textSize = 20f
            includeFontPadding = true
            typeface = getKeyTypeface()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        cellFrame.addView(tv)

        return VariationCell(char, rowIndex, colIndex, cellFrame, tv)
    }

    private fun updateVariationsVisuals() {
        val colors = getColors()
        val pillRadius = dpF(22f)

        itemViews.forEach { cell ->
            val isSelected = cell.character == selectedVariation
            if (isSelected) {
                cell.container.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = pillRadius
                    setColor(colors.accentBadge)
                }
                cell.textView.setTextColor(colors.accentText)
                cell.textView.typeface = Typeface.DEFAULT_BOLD
            } else {
                cell.container.background = null
                cell.textView.setTextColor(colors.textColor)
                cell.textView.typeface = getKeyTypeface()
            }
        }
    }

    /** Number of cells in the given row of the current bubble. */
    private fun cellsInRow(row: Int): Int {
        val split = twoRowSplit
        return if (split != null) {
            if (row == 0) split.first.size else split.second.size
        } else {
            currentVariations.size
        }
    }

    /** Horizontal centre of a cell, measured from the bubble's left edge (rows are centred). */
    private fun cellCenterRelX(row: Int, col: Int): Float {
        val rowStart = gridPadPx + (gridCols - cellsInRow(row)) * cellWidthPx / 2f
        return rowStart + (col + 0.5f) * cellWidthPx
    }

    fun updateSelectionFromTouch(rawX: Float, rawY: Float) {
        if (!isLongPressActive || itemViews.isEmpty() || variationsView.visibility != View.VISIBLE) return

        // Everything below is in overlay coordinates and uses the stored bubble geometry,
        // so the pop-in scale animation can never skew where the cells "are".
        val overlayLoc = IntArray(2)
        overlayContainer.getLocationOnScreen(overlayLoc)
        val x = rawX - overlayLoc[0]
        val y = rawY - overlayLoc[1]

        // Keep the default highlight until the finger really moves (ignores touch jitter)
        if (!hasMovedOutOfDeadZone) {
            if (initialTouchX != 0f || initialTouchY != 0f) {
                val dead = dpF(10f)
                val dx = kotlin.math.abs(rawX - initialTouchX)
                val dy = kotlin.math.abs(rawY - initialTouchY)
                if (dx <= dead && dy <= dead) return
            }
            hasMovedOutOfDeadZone = true
        }

        // Row: a single monotonic function of the finger's Y, so moving up never
        // selects a lower row (finger below the bubble = bottom row, above = top row).
        val targetRow: Int
        if (twoRowSplit != null) {
            val boundaryY = bubbleTopPx + gridPadPx + cellHeightPx
            val hysteresis = dpF(6f)
            targetRow = if (activeRow == 0) {
                if (y > boundaryY + hysteresis) 1 else 0
            } else {
                if (y < boundaryY - hysteresis) 0 else 1
            }
            activeRow = targetRow
        } else {
            targetRow = 0
            activeRow = 0
        }

        val rowCells = itemViews.filter { it.row == targetRow }.ifEmpty { itemViews }
        if (rowCells.isEmpty()) return

        // Column: nearest cell centre to the finger's X
        var bestCell: VariationCell = rowCells.first()
        var minDistance = Float.MAX_VALUE
        rowCells.forEach { cell ->
            val centerX = bubbleLeftPx + cellCenterRelX(cell.row, cell.col)
            val dist = kotlin.math.abs(x - centerX)
            if (dist < minDistance) {
                minDistance = dist
                bestCell = cell
            }
        }

        if (bestCell.character != selectedVariation) {
            selectedVariation = bestCell.character
            updateVariationsVisuals()
            variationsView.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    fun getSelectedVariation(): String? = selectedVariation

    fun hidePopup(immediate: Boolean = false) {
        val sessionToHide = currentSessionId
        popupView.animate().setListener(null).cancel()
        variationsView.animate().setListener(null).cancel()

        fun cleanup() {
            if (sessionToHide != currentSessionId && !immediate) return
            popupView.visibility = View.GONE
            variationsView.visibility = View.GONE
            popupView.scaleX = 1f
            popupView.scaleY = 1f
            popupView.alpha = 1f
            popupView.translationY = 0f
            variationsView.scaleX = 1f
            variationsView.scaleY = 1f
            variationsView.alpha = 1f
            isLongPressActive = false
            currentAnchor = null
            selectedVariation = null
            initialTouchX = 0f
            initialTouchY = 0f
            activeRow = 0
            hasMovedOutOfDeadZone = false
        }

        if (immediate || (!popupView.isShown && !variationsView.isShown)) {
            cleanup()
        } else {
            popupView.animate()
                .setListener(null)
                .scaleX(0.75f)
                .scaleY(0.75f)
                .alpha(0f)
                .setDuration(50)
                .start()

            variationsView.animate()
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        variationsView.animate().setListener(null)
                        cleanup()
                    }
                })
                .scaleX(0.75f)
                .scaleY(0.75f)
                .alpha(0f)
                .setDuration(50)
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
        val maxPopupLeft = maxOf(margin, rootWidth - popupDiameter - margin)
        popupLeft = popupLeft.coerceIn(margin, maxPopupLeft)

        // Position directly above the key
        val popupBottom = keyY + dp(2)
        var popupTop = popupBottom - popupDiameter
        popupTop = maxOf(dp(2), popupTop)

        popupView.x = popupLeft.toFloat()
        popupView.y = popupTop.toFloat()
        // Remember where the bubble must finish; the pop-in animation starts a few dp lower
        popupBaseY = popupView.translationY
    }

    private fun positionVariationsBubble(anchor: View) {
        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val rootLoc = IntArray(2)
        overlayContainer.getLocationInWindow(rootLoc)

        val keyX = anchorLoc[0] - rootLoc[0]
        val keyY = anchorLoc[1] - rootLoc[1]
        val keyWidth = anchor.width

        val rootWidth = overlayContainer.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val margin = dp(4)

        variationsView.measure(
            View.MeasureSpec.makeMeasureSpec(rootWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bubbleW = variationsView.measuredWidth
        val bubbleH = variationsView.measuredHeight

        val keyCenterX = keyX + keyWidth / 2

        // Find the default item so its cell sits directly above the key
        val split = twoRowSplit
        var defRow = 0
        var defCol = 0
        if (split != null) {
            val topIdx = split.first.indexOf(selectedVariation)
            if (topIdx >= 0) {
                defRow = 0; defCol = topIdx
            } else {
                defRow = 1; defCol = split.second.indexOf(selectedVariation).coerceAtLeast(0)
            }
        } else {
            defCol = currentVariations.indexOf(selectedVariation).coerceAtLeast(0)
        }

        var bubbleLeft = (keyCenterX - cellCenterRelX(defRow, defCol)).toInt()
        val maxLeft = maxOf(margin, rootWidth - bubbleW - margin)
        bubbleLeft = bubbleLeft.coerceIn(margin, maxLeft)

        // Position floating above the key
        val bubbleTop = maxOf(dp(2), keyY - bubbleH - dp(6))

        variationsView.x = bubbleLeft.toFloat()
        variationsView.y = bubbleTop.toFloat()

        bubbleLeftPx = bubbleLeft.toFloat()
        bubbleTopPx = bubbleTop.toFloat()
        bubbleHeightPx = bubbleH.toFloat()
    }
}
