package com.example.customkeyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class CustomKeyboardService : InputMethodService() {

    private enum class Lang { EN, AR }
    private enum class Mode { LETTERS, SYMBOLS, EMOJI, CLIPBOARD }

    private var currentLang = Lang.EN
    private var currentMode = Mode.LETTERS
    private var shiftOn = false
    private var capsLock = false
    private var lastShiftTapTime = 0L
    private var symbolsPage = 1
    private val wordBuffer = StringBuilder()

    private lateinit var rootContainer: LinearLayout
    private lateinit var topBarContainer: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipHistory: ClipboardHistory

    // ---------- sizing constants (kept tight to match system keyboards like Gboard) ----------
    private val KEY_RADIUS_DP = 8
    private val PILL_RADIUS_DP = 24
    private val ROW_HEIGHT_DP = 44
    private val ROW_SPACING_DP = 4
    private val TOP_BAR_HEIGHT_DP = 36
    private val ICON_GLYPH_DP = 26

    private val commonEmojis = listOf("😀", "😂", "❤️", "👍", "🙏", "🔥", "😊", "🎉", "👀", "✅", "😉", "💯")

    private val systemClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotBlank()) clipHistory.add(text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipHistory = ClipboardHistory(this)
        clipboardManager.addPrimaryClipChangedListener(systemClipListener)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(systemClipListener)
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentMode = Mode.LETTERS
        shiftOn = false
        capsLock = false
        symbolsPage = 1
        wordBuffer.clear()
        render()
    }

    // ---------- theming ----------

    private fun isDarkMode(): Boolean {
        return when (prefs.getString("theme_override", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun bgColor() = if (isDarkMode()) Color.parseColor("#131314") else Color.parseColor("#E9EAED")
    private fun textColor() = if (isDarkMode()) Color.parseColor("#E3E3E3") else Color.parseColor("#1F1F1F")

    // Individual key "box" colors - distinct from the keyboard background so every key reads
    // as its own tile, similar to Gboard/Material You.
    private fun keyColor() = if (isDarkMode()) Color.parseColor("#2D2E30") else Color.parseColor("#FFFFFF")
    private fun specialKeyColor() = if (isDarkMode()) Color.parseColor("#3C3F41") else Color.parseColor("#F1F3F4")
    private fun pressHighlightColor() = if (isDarkMode()) Color.parseColor("#4C4F52") else Color.parseColor("#DADCE0")

    // Material You dynamic accent when available (Android 12+), with a sensible fallback.
    private fun accentColor(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                val resId = if (isDarkMode()) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
                getColor(resId)
            } catch (e: Exception) {
                fallbackAccent()
            }
        }
        return fallbackAccent()
    }

    private fun fallbackAccent() = if (isDarkMode()) Color.parseColor("#A8C7FA") else Color.parseColor("#0B57D0")
    private fun enterIconColor() = if (isDarkMode()) Color.parseColor("#062E6F") else Color.parseColor("#FFFFFF")

    /** A translucent wash of the accent color, used as the shift key's background while active. */
    private fun accentTintColor(): Int {
        val c = accentColor()
        return Color.argb(70, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun applyWindowChrome() {
        val win = window?.window
        win?.navigationBarColor = bgColor()
        win?.decorView?.setBackgroundColor(bgColor())
    }

    // ---------- view construction ----------

    override fun onCreateInputView(): View {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        render()
        return rootContainer
    }

    private fun render() {
        applyWindowChrome()
        rootContainer.removeAllViews()
        rootContainer.setBackgroundColor(bgColor())
        rootContainer.setPadding(dp(3), dp(4), dp(3), dp(2))

        topBarContainer = buildTopBar()
        rootContainer.addView(topBarContainer)

        when (currentMode) {
            Mode.CLIPBOARD -> rootContainer.addView(buildClipboardPanel())
            Mode.EMOJI -> {
                rootContainer.addView(buildRow(KeyboardLayoutData.emojiRows[0], isEmoji = true))
                rootContainer.addView(buildRow(KeyboardLayoutData.emojiRows[1], isEmoji = true))
                // Delete stays reachable from the emoji screen too, not just letters.
                rootContainer.addView(buildEmojiBottomRow(KeyboardLayoutData.emojiRows[2]))
            }
            Mode.SYMBOLS -> {
                val pageRows = if (symbolsPage == 1) KeyboardLayoutData.symbolsPage1Rows else KeyboardLayoutData.symbolsPage2Rows
                rootContainer.addView(buildRow(KeyboardLayoutData.numberRow))
                rootContainer.addView(buildSymbolsRow(pageRows[0]))
                rootContainer.addView(buildSymbolsRow(pageRows[1], prependToggle = true))
                // Delete stays reachable from the symbols screen too, not just letters.
                rootContainer.addView(buildSymbolsBottomRow(KeyboardLayoutData.symbolsSharedRow))
            }
            Mode.LETTERS -> {
                val rows = if (currentLang == Lang.EN) KeyboardLayoutData.englishRows else KeyboardLayoutData.arabicRows
                rootContainer.addView(buildRow(KeyboardLayoutData.numberRow))
                rootContainer.addView(buildRow(rows[0], applyShift = currentLang == Lang.EN, isLetterRow = true))
                rootContainer.addView(buildLetterRowWithShift(rows[1]))
                rootContainer.addView(buildLetterRowWithShiftAndBackspace(rows[2]))
            }
        }

        if (currentMode != Mode.CLIPBOARD) {
            rootContainer.addView(buildBottomRow())
        }
    }

    private fun refreshTopBar() {
        if (!::topBarContainer.isInitialized || !::rootContainer.isInitialized) return
        val index = rootContainer.indexOfChild(topBarContainer)
        if (index < 0) return
        val newBar = buildTopBar()
        rootContainer.removeViewAt(index)
        rootContainer.addView(newBar, index)
        topBarContainer = newBar
    }

    private fun switchMode(mode: Mode) {
        currentMode = mode
        if (mode != Mode.LETTERS) wordBuffer.clear()
        if (mode == Mode.SYMBOLS) symbolsPage = 1
        render()
    }

    private fun toggleSymbolsPage() {
        symbolsPage = if (symbolsPage == 1) 2 else 1
        render()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- top bar: suggestions / common emojis / clipboard / settings ----------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR_HEIGHT_DP))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(2))
        }

        val hasSuggestions = currentLang == Lang.EN && currentMode == Mode.LETTERS &&
            wordBuffer.isNotEmpty() && Dictionary.suggestions(wordBuffer.toString(), 1).isNotEmpty()

        when {
            currentMode == Mode.CLIPBOARD -> {
                bar.addView(TextView(this).apply {
                    text = "Clipboard"
                    setTextColor(textColor())
                    setTypeface(Typeface.DEFAULT_BOLD)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                // Delete is reachable here too, so it's always available everywhere.
                bar.addView(iconButton("⌫") { deleteChar() })
                bar.addView(iconButton("←") { switchMode(Mode.LETTERS) })
            }
            // Suggestions only ever get inserted when the user taps a chip - nothing here is
            // auto-applied, so typing is never silently corrected.
            hasSuggestions -> {
                bar.addView(buildSuggestionsScroll(wordBuffer.toString()))
                bar.addView(iconButton("⧉") { switchMode(Mode.CLIPBOARD) })
            }
            else -> {
                // No word suggestions to show right now - surface the most commonly used
                // emojis instead of leaving this bar empty.
                bar.addView(buildCommonEmojiScroll())
                bar.addView(iconButton("⧉") { switchMode(Mode.CLIPBOARD) })
                bar.addView(iconButton("⚙") {
                    val intent = android.content.Intent(this, MainActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                })
            }
        }
        return bar
    }

    private fun buildSuggestionsScroll(prefix: String): HorizontalScrollView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Backed by a ~1200-word dictionary now, so there's a much deeper pool of matches to
        // scroll through than before - shown as a scrollable strip since they can't all fit.
        Dictionary.suggestions(prefix, 8).forEach { word -> inner.addView(suggestionChip(word)) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildCommonEmojiScroll(): HorizontalScrollView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        commonEmojis.forEach { emoji ->
            inner.addView(TextView(this).apply {
                text = emoji
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                applyKeyTouchBehavior(this, pressHighlightColor(), null, KEY_RADIUS_DP) {
                    currentInputConnection?.commitText(emoji, 1)
                }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun suggestionChip(word: String): TextView {
        return TextView(this).apply {
            text = word
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            applyKeyTouchBehavior(this, pressHighlightColor(), null, KEY_RADIUS_DP) {
                // Suggestions are only ever committed by an explicit tap - never automatically.
                currentInputConnection?.deleteSurroundingText(wordBuffer.length, 0)
                currentInputConnection?.commitText("$word ", 1)
                wordBuffer.clear()
                refreshTopBar()
            }
        }
    }

    private fun iconButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            setTextColor(textColor())
            textSize = 16f
            gravity = Gravity.CENTER
            val size = dp(28)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
            applyKeyTouchBehavior(this, pressHighlightColor(), null, KEY_RADIUS_DP) { onClick() }
        }
    }

    // ---------- clipboard panel ----------

    private fun buildClipboardPanel(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = clipHistory.getAll()

        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No clipboard items yet"
                setTextColor(textColor())
                alpha = 0.6f
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        } else {
            items.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener {
                        currentInputConnection?.commitText(item, 1)
                        switchMode(Mode.LETTERS)
                    }
                }
                val itemLabel = TextView(this).apply {
                    text = if (item.length > 60) item.substring(0, 60) + "…" else item
                    setTextColor(textColor())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val delete = TextView(this).apply {
                    text = "✕"
                    setTextColor(textColor())
                    alpha = 0.6f
                    setPadding(dp(10), 0, dp(4), 0)
                    setOnClickListener {
                        clipHistory.remove(item)
                        render()
                    }
                }
                row.addView(itemLabel)
                row.addView(delete)
                list.addView(row)
            }
            list.addView(TextView(this).apply {
                text = "Clear all"
                setTextColor(accentColor())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    clipHistory.clear()
                    render()
                }
            })
        }
        scroll.addView(list)
        return scroll
    }

    // ---------- key rows ----------

    private fun buildRow(keys: List<String>, applyShift: Boolean = false, isEmoji: Boolean = false, isLetterRow: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(ROW_SPACING_DP)
            }
        }
        val fontSize = when {
            isEmoji -> 22f
            isLetterRow -> 24f
            else -> 20f
        }
        keys.forEach { k ->
            val display = if (applyShift && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, fontSize = fontSize) {
                when {
                    isEmoji -> currentInputConnection?.commitText(display, 1)
                    isLetterRow -> commitLetter(display)
                    else -> commitSymbol(display)
                }
            })
        }
        return row
    }

    private fun buildLetterRowWithShift(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(ROW_SPACING_DP)
            }
        }
        row.addView(spacer(0.5f))
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, fontSize = 24f) { commitLetter(display) })
        }
        row.addView(spacer(0.5f))
        return row
    }

    private fun buildLetterRowWithShiftAndBackspace(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(ROW_SPACING_DP)
            }
        }
        if (currentLang == Lang.EN) {
            row.addView(makeShiftKey(weight = 1.5f))
        }
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, fontSize = 24f) { commitLetter(display) })
        }
        // Swipe left on backspace to delete more than one character at a time.
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildSymbolsRow(keys: List<String>, prependToggle: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(ROW_SPACING_DP)
            }
        }
        if (prependToggle) {
            val label = if (symbolsPage == 1) "1/2" else "2/2"
            row.addView(makeSpecialKey(label, weight = 1.3f) { toggleSymbolsPage() })
        }
        keys.forEach { k -> row.addView(makeKey(k, weight = 1f, fontSize = 20f) { commitSymbol(k) }) }
        return row
    }

    private fun buildSymbolsBottomRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(ROW_SPACING_DP)
            }
        }
        keys.forEach { k -> row.addView(makeKey(k, weight = 1f, fontSize = 20f) { commitSymbol(k) }) }
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildEmojiBottomRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(ROW_SPACING_DP)
            }
        }
        keys.forEach { k -> row.addView(makeKey(k, weight = 1f, fontSize = 22f) { currentInputConnection?.commitText(k, 1) }) }
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(ROW_SPACING_DP)
            }
        }

        when (currentMode) {
            Mode.SYMBOLS, Mode.EMOJI -> {
                row.addView(makeSpecialKey("ABC", weight = 1.5f) { switchMode(Mode.LETTERS) })
            }
            else -> {
                row.addView(makeSpecialKey("?123", weight = 1.5f) { switchMode(Mode.SYMBOLS) })
                row.addView(makeSpecialKey("🙂", weight = 1f) { switchMode(Mode.EMOJI) })
            }
        }

        row.addView(makeSpecialKey("🌐", weight = 1f) { switchLanguage() })

        val spaceLabel = if (currentLang == Lang.EN) "English" else "العربية مصر"
        // Swipe left/right on the space bar to move the cursor through existing text.
        row.addView(makeSpaceKey(spaceLabel, weight = 4f))

        row.addView(makeSpecialKey(".", weight = 1f) { commitPunctuationOrSpace(".") })
        row.addView(makeEnterKey(weight = 1.5f))

        return row
    }

    private fun spacer(weight: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }
    }

    // ---------- key factories ----------

    private fun makeKey(label: String, weight: Float, fontSize: Float = 20f, onClick: () -> Unit): TextView {
        val resting = roundedDrawable(keyColor(), radiusDp = KEY_RADIUS_DP)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = fontSize
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        }
    }

    private fun makeSpecialKey(
        label: String,
        weight: Float,
        textHighlighted: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val resting = roundedDrawable(specialKeyColor(), radiusDp = KEY_RADIUS_DP)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (textHighlighted) accentColor() else textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        }
    }

    /** Filled, pill-shaped enter/send key drawn with a hand-built glyph (no bitmap assets)
     *  and a Material-You-aware accent color, so it reads as part of the same design
     *  language as the rest of the keyboard instead of a plain text character. */
    private fun makeEnterKey(weight: Float): View {
        val resting = roundedDrawable(accentColor(), radiusDp = PILL_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            background = resting
        }
        val icon = GlyphIconView(this, GlyphIconView.Glyph.RETURN).apply {
            iconColor = enterIconColor()
            layoutParams = FrameLayout.LayoutParams(dp(ICON_GLYPH_DP), dp(ICON_GLYPH_DP), Gravity.CENTER)
        }
        container.addView(icon)
        applyKeyTouchBehavior(container, pressHighlightColor(), resting, PILL_RADIUS_DP) { handleEnter() }
        return container
    }

    /** Shift/caps-lock key drawn with a hand-built arrow glyph so it's crisp and sized to
     *  match the other icon keys (a plain unicode ⇧ character renders tiny in most fonts). */
    private fun makeShiftKey(weight: Float): View {
        val active = shiftOn || capsLock
        val bgColor = if (active) accentTintColor() else specialKeyColor()
        val resting = roundedDrawable(bgColor, radiusDp = KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            background = resting
        }
        val icon = GlyphIconView(this, GlyphIconView.Glyph.SHIFT).apply {
            iconColor = if (active) accentColor() else textColor()
            locked = capsLock
            layoutParams = FrameLayout.LayoutParams(dp(ICON_GLYPH_DP), dp(ICON_GLYPH_DP), Gravity.CENTER)
        }
        container.addView(icon)
        applyKeyTouchBehavior(container, pressHighlightColor(), resting, KEY_RADIUS_DP) { onShiftTapped() }
        return container
    }

    /** Backspace key: a normal tap deletes one character, and dragging further left while
     *  held deletes progressively more characters, like a swipe-to-delete-more gesture. */
    private fun makeBackspaceKey(weight: Float): View {
        val resting = roundedDrawable(specialKeyColor(), radiusDp = KEY_RADIUS_DP)
        val pressedBg = roundedDrawable(pressHighlightColor(), radiusDp = KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            background = resting
            isClickable = true
            isHapticFeedbackEnabled = true
        }
        val icon = GlyphIconView(this, GlyphIconView.Glyph.BACKSPACE).apply {
            iconColor = textColor()
            layoutParams = FrameLayout.LayoutParams(dp(ICON_GLYPH_DP), dp(ICON_GLYPH_DP), Gravity.CENTER)
        }
        container.addView(icon)

        var down = false
        var startX = 0f
        var deletedSteps = 0
        val stepPx = dp(16)

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = true
                    startX = event.rawX
                    deletedSteps = 0
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = pressedBg
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(45).start()
                    deleteChar()
                    deletedSteps = 1
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (down) {
                        val draggedLeft = startX - event.rawX
                        if (draggedLeft > 0) {
                            val targetSteps = 1 + (draggedLeft / stepPx).toInt()
                            if (targetSteps > deletedSteps) {
                                repeat(targetSteps - deletedSteps) {
                                    deleteChar()
                                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                                }
                                deletedSteps = targetSteps
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    down = false
                    v.background = resting
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    true
                }
                else -> false
            }
        }
        return container
    }

    /** Space key: a tap inserts a space as usual. Dragging left/right before releasing moves
     *  the text cursor through the existing text instead of inserting anything, matching the
     *  space-bar cursor gesture found on most modern keyboards. */
    private fun makeSpaceKey(label: String, weight: Float): View {
        val resting = roundedDrawable(specialKeyColor(), radiusDp = KEY_RADIUS_DP)
        val pressedBg = roundedDrawable(pressHighlightColor(), radiusDp = KEY_RADIUS_DP)
        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            background = resting
            isClickable = true
            isHapticFeedbackEnabled = true
        }

        var startX = 0f
        var lastStepX = 0f
        var isDragging = false
        val stepPx = dp(18).toFloat()
        val dragThreshold = dp(9).toFloat()

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    lastStepX = event.rawX
                    isDragging = false
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = pressedBg
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - startX
                    if (!isDragging && abs(totalDx) > dragThreshold) isDragging = true
                    if (isDragging) {
                        val dxSinceStep = event.rawX - lastStepX
                        if (abs(dxSinceStep) >= stepPx) {
                            val steps = (dxSinceStep / stepPx).toInt()
                            val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                            repeat(abs(steps)) {
                                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                            }
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                            lastStepX += steps * stepPx
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.background = resting
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (!isDragging) {
                        commitPunctuationOrSpace(" ")
                    } else if (wordBuffer.isNotEmpty()) {
                        // Cursor was moved away from the word being typed - drop the in-progress
                        // suggestion buffer rather than keep suggesting against stale text.
                        wordBuffer.clear()
                        refreshTopBar()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.background = resting
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    true
                }
                else -> false
            }
        }
        return tv
    }

    private fun roundedDrawable(color: Int, radiusDp: Int = KEY_RADIUS_DP): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun applyKeyTouchBehavior(
        view: View,
        pressColor: Int,
        restingBackground: GradientDrawable?,
        radiusDp: Int,
        onTap: () -> Unit
    ) {
        view.isClickable = true
        view.isHapticFeedbackEnabled = true
        var pressed = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = roundedDrawable(pressColor, radiusDp)
                    v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(45).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val margin = dp(14)
                    val within = event.x >= -margin && event.x <= v.width + margin &&
                        event.y >= -margin && event.y <= v.height + margin
                    if (!within && pressed) {
                        pressed = false
                        v.background = restingBackground
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    v.background = restingBackground
                    if (pressed) {
                        pressed = false
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    v.background = restingBackground
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- input actions ----------

    private fun commitLetter(letter: String) {
        currentInputConnection?.commitText(letter, 1)
        if (currentLang == Lang.EN) {
            wordBuffer.append(letter.lowercase())
        }
        if (shiftOn && !capsLock) {
            shiftOn = false
            render()
        } else {
            refreshTopBar()
        }
    }

    private fun commitSymbol(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (wordBuffer.isNotEmpty()) wordBuffer.clear()
        refreshTopBar()
    }

    // Word boundaries no longer silently rewrite what was typed - suggestions are only ever
    // applied when the user explicitly taps a suggestion chip in the top bar.
    private fun commitPunctuationOrSpace(boundary: String) {
        currentInputConnection?.commitText(boundary, 1)
        wordBuffer.clear()
        refreshTopBar()
    }

    private fun deleteChar() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (wordBuffer.isNotEmpty()) {
            wordBuffer.deleteCharAt(wordBuffer.length - 1)
        }
        refreshTopBar()
    }

    private fun onShiftTapped() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 300) {
            capsLock = !capsLock
            shiftOn = false
        } else {
            if (capsLock) {
                capsLock = false
                shiftOn = false
            } else {
                shiftOn = !shiftOn
            }
        }
        lastShiftTapTime = now
        render()
    }

    private fun switchLanguage() {
        currentLang = if (currentLang == Lang.EN) Lang.AR else Lang.EN
        currentMode = Mode.LETTERS
        shiftOn = false
        capsLock = false
        wordBuffer.clear()
        render()
    }

    private fun handleEnter() {
        wordBuffer.clear()
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (info != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
        refreshTopBar()
    }
}

/**
 * Small self-drawn glyph icons for the enter/return, shift, and backspace keys, so those keys
 * use crisp, consistently-sized icons instead of unicode text characters (which render at
 * inconsistent, often tiny sizes depending on the system font) - no external icon assets
 * required.
 */
private class GlyphIconView(context: Context, private val glyph: Glyph) : View(context) {
    enum class Glyph { RETURN, BACKSPACE, SHIFT }

    var iconColor: Int = Color.BLACK
    /** Only used by Glyph.SHIFT - draws an underline bar beneath the arrow to indicate caps lock. */
    var locked: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        paint.color = iconColor
        paint.strokeWidth = h * 0.10f

        when (glyph) {
            Glyph.RETURN -> {
                val left = w * 0.24f
                val right = w * 0.76f
                val top = h * 0.26f
                val bottom = h * 0.66f
                canvas.drawLine(left, top, right, top, paint)
                canvas.drawLine(right, top, right, bottom, paint)
                canvas.drawLine(right, bottom, left, bottom, paint)
                canvas.drawLine(left, bottom, left + w * 0.18f, bottom - h * 0.16f, paint)
                canvas.drawLine(left, bottom, left + w * 0.18f, bottom + h * 0.16f, paint)
            }
            Glyph.BACKSPACE -> {
                // A generously-sized arrow-box outline with a clearly-inset X, so the X never
                // crowds the edges of the box at small key sizes.
                val left = w * 0.10f
                val notch = w * 0.30f
                val right = w * 0.90f
                val top = h * 0.16f
                val bottom = h * 0.84f
                val midY = h * 0.5f
                canvas.drawLine(left, midY, notch, top, paint)
                canvas.drawLine(notch, top, right, top, paint)
                canvas.drawLine(right, top, right, bottom, paint)
                canvas.drawLine(right, bottom, notch, bottom, paint)
                canvas.drawLine(notch, bottom, left, midY, paint)
                val xLeft = notch + w * 0.10f
                val xRight = right - w * 0.10f
                val xTop = top + h * 0.16f
                val xBottom = bottom - h * 0.16f
                canvas.drawLine(xLeft, xTop, xRight, xBottom, paint)
                canvas.drawLine(xRight, xTop, xLeft, xBottom, paint)
            }
            Glyph.SHIFT -> {
                val midX = w * 0.5f
                val top = h * 0.14f
                val chevronBottom = h * 0.52f
                val leftX = w * 0.16f
                val rightX = w * 0.84f
                val stemBottom = h * 0.78f
                canvas.drawLine(midX, top, leftX, chevronBottom, paint)
                canvas.drawLine(midX, top, rightX, chevronBottom, paint)
                canvas.drawLine(midX, chevronBottom * 0.9f, midX, stemBottom, paint)
                if (locked) {
                    canvas.drawLine(w * 0.22f, h * 0.90f, w * 0.78f, h * 0.90f, paint)
                }
            }
        }
    }
}
