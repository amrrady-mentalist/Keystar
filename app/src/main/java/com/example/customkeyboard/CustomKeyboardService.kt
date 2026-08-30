package com.example.customkeyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var lastCommittedWord = ""

    private lateinit var rootContainer: LinearLayout
    private lateinit var topBarContainer: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipHistory: ClipboardHistory
    private lateinit var covertManager: CovertManager

    // ---------- sizing constants (kept tight to match system keyboards like Gboard) ----------
    private val KEY_RADIUS_DP = 8
    private val PILL_RADIUS_DP = 24
    private val ROW_HEIGHT_DP = 44
    private val TOP_BAR_HEIGHT_DP = 36
    private val ICON_GLYPH_DP = 26
    // The visual gap between keys is drawn as a cosmetic inset on the key's background, not as
    // a real margin - a real margin would create a dead zone between keys where fast/light taps
    // don't register on anything. Insets keep the touch target the full, contiguous cell.
    private val KEY_INSET_H_DP = 2
    private val KEY_INSET_V_DP = 4

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
        covertManager = CovertManager(this)
        TriggerManager.init(this, covertManager)
        Dictionary.init(this)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(systemClipListener)
        TriggerManager.stopSensors()
        TriggerManager.stopVolumeObserver(this)
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentMode = Mode.LETTERS
        shiftOn = false
        capsLock = false
        symbolsPage = 1
        wordBuffer.clear()
        val textBefore = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()?.trim() ?: ""
        lastCommittedWord = textBefore.split(Regex("\\s+")).lastOrNull { it.isNotEmpty() } ?: ""
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

        val isArabic = currentLang == Lang.AR
        val currentWord = wordBuffer.toString()
        val contextualSuggestions = if (currentMode == Mode.LETTERS) {
            Dictionary.getContextualSuggestions(currentWord, lastCommittedWord, isArabic, limit = 16)
        } else emptyList()

        when {
            currentMode == Mode.CLIPBOARD -> {
                bar.addView(TextView(this).apply {
                    text = "Clipboard"
                    setTextColor(textColor())
                    setTypeface(Typeface.DEFAULT_BOLD)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                bar.addView(iconButton("⌫") { deleteChar() })
                bar.addView(iconButton("←") { switchMode(Mode.LETTERS) })
            }
            contextualSuggestions.isNotEmpty() -> {
                bar.addView(buildSuggestionsScroll(contextualSuggestions))
                bar.addView(iconButton("⧉") { switchMode(Mode.CLIPBOARD) })
                bar.addView(iconButton("⚙") {
                    val intent = android.content.Intent(this, MainActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                })
            }
            else -> {
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

    private fun buildSuggestionsScroll(items: List<Dictionary.SuggestionItem>): HorizontalScrollView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        items.forEach { item ->
            if (item.isEmoji) {
                inner.addView(emojiChip(item.text))
            } else {
                inner.addView(suggestionChip(item))
            }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun emojiChip(emoji: String): TextView {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = emoji
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) {
                currentInputConnection?.commitText(emoji, 1)
                refreshTopBar()
            }
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

    private fun suggestionChip(item: Dictionary.SuggestionItem): TextView {
        val isPrimary = item.isPrimary
        val resting = if (isPrimary) keyBackground(specialKeyColor(), KEY_RADIUS_DP) else null
        return TextView(this).apply {
            text = item.text
            setTextColor(if (isPrimary) accentColor() else textColor())
            setTypeface(if (isPrimary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (isPrimary) {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            }
            if (resting != null) background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) {
                Dictionary.recordUsedWord(item.text)
                lastCommittedWord = item.text
                if (wordBuffer.isNotEmpty()) {
                    currentInputConnection?.deleteSurroundingText(wordBuffer.length, 0)
                    wordBuffer.clear()
                }
                currentInputConnection?.commitText("${item.text} ", 1)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
        }
        keys.forEach { k -> row.addView(makeKey(k, weight = 1f, fontSize = 20f) { commitSymbol(k) }) }
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildEmojiBottomRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
        }
        keys.forEach { k -> row.addView(makeKey(k, weight = 1f, fontSize = 22f) { currentInputConnection?.commitText(k, 1) }) }
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
        }

        when (currentMode) {
            Mode.SYMBOLS, Mode.EMOJI -> {
                row.addView(make123Key("ABC", weight = 1.5f) { switchMode(Mode.LETTERS) })
            }
            else -> {
                row.addView(make123Key("?123", weight = 1.5f) { switchMode(Mode.SYMBOLS) })
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

    /**
     * Specialized ?123 / ABC key with covert magic trigger.
     * When covert typing is active, button color changes to match the Enter button (accentColor / enterIconColor).
     * Long-press (400ms) arms/disarms covert mode.
     */
    private fun make123Key(label: String, weight: Float, onClick: () -> Unit): View {
        val isCovert = covertManager.isCovertActive
        val keyBgColor = if (isCovert) accentColor() else specialKeyColor()
        val textCl = if (isCovert) enterIconColor() else textColor()
        val resting = keyBackground(keyBgColor, KEY_RADIUS_DP)
        val pressedBg = keyBackground(pressHighlightColor(), KEY_RADIUS_DP)

        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textCl)
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            isClickable = true
            isHapticFeedbackEnabled = true
        }

        var isLongPressed = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            isLongPressed = true
            covertManager.toggleCovert()
            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            render()
        }

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressed = false
                    longPressHandler.postDelayed(longPressRunnable, 400)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = pressedBg
                    v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(45).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.background = resting
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (!isLongPressed) {
                        onClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.background = resting
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    true
                }
                else -> false
            }
        }
        return tv
    }

    private fun makeKey(label: String, weight: Float, fontSize: Float = 20f, onClick: () -> Unit): TextView {
        val resting = keyBackground(keyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = fontSize
            // No margins here on purpose - the touch target stays the full cell (edge-to-edge
            // with neighboring keys) even though the painted box looks smaller, so a light or
            // fast tap near a key's edge still registers instead of landing in a dead zone.
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
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
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (textHighlighted) accentColor() else textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        }
    }

    /** Filled, pill-shaped enter/send key drawn with a hand-built glyph (no bitmap assets)
     *  and a Material-You-aware accent color, so it reads as part of the same design
     *  language as the rest of the keyboard instead of a plain text character. */
    private fun makeEnterKey(weight: Float): View {
        val resting = keyBackground(accentColor(), PILL_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
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
        val resting = keyBackground(bgColor, KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
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
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        val pressedBg = keyBackground(pressHighlightColor(), KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
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
     *  space-bar cursor gesture found on most modern keyboards. Long-press activates secret Magic Force. */
    private fun makeSpaceKey(label: String, weight: Float): View {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        val pressedBg = keyBackground(pressHighlightColor(), KEY_RADIUS_DP)
        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            isClickable = true
            isHapticFeedbackEnabled = true
        }

        var startX = 0f
        var lastStepX = 0f
        var isDragging = false
        var isLongPressed = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (!isDragging && covertManager.stealthSpacebarTrigger) {
                isLongPressed = true
                covertManager.toggleCovert()
                tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                render()
            }
        }
        val stepPx = dp(18).toFloat()
        val dragThreshold = dp(9).toFloat()

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    lastStepX = event.rawX
                    isDragging = false
                    isLongPressed = false
                    longPressHandler.postDelayed(longPressRunnable, 750)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = pressedBg
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - startX
                    if (!isDragging && abs(totalDx) > dragThreshold) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
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
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.background = resting
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (!isDragging && !isLongPressed) {
                        commitPunctuationOrSpace(" ")
                    } else if (wordBuffer.isNotEmpty() && isDragging) {
                        // Cursor was moved away from the word being typed - drop the in-progress
                        // suggestion buffer rather than keep suggesting against stale text.
                        wordBuffer.clear()
                        refreshTopBar()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
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

    /** Builds a key's visual box as an inset drawable: the View itself stays the full,
     *  edge-to-edge cell (so the touch target has zero dead space), while only the painted
     *  box is shrunk inward to create the visible gap between keys. */
    private fun keyBackground(
        color: Int,
        radiusDp: Int = KEY_RADIUS_DP,
        insetHDp: Int = KEY_INSET_H_DP,
        insetVDp: Int = KEY_INSET_V_DP
    ): Drawable {
        return InsetDrawable(roundedDrawable(color, radiusDp), dp(insetHDp), dp(insetVDp), dp(insetHDp), dp(insetVDp))
    }

    private fun applyKeyTouchBehavior(
        view: View,
        pressColor: Int,
        restingBackground: Drawable?,
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
                    v.background = keyBackground(pressColor, radiusDp)
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

    private fun handleKeyCommit(originalText: String, isLetter: Boolean) {
        if (covertManager.isCovertActive) {
            val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(4000, 0)
            val output = covertManager.processCommit(originalText, isLetter, textBeforeCursor)
            currentInputConnection?.commitText(output, 1)

            if (shiftOn && !capsLock && isLetter) {
                shiftOn = false
                render()
            }
            return
        }

        currentInputConnection?.commitText(originalText, 1)
        if (isLetter) {
            wordBuffer.append(originalText.lowercase())
        }

        if (shiftOn && !capsLock && isLetter) {
            shiftOn = false
            render()
        } else {
            refreshTopBar()
        }
    }

    private fun commitLetter(letter: String) {
        handleKeyCommit(letter, isLetter = true)
    }

    private fun commitSymbol(text: String) {
        handleKeyCommit(text, isLetter = false)
        if (wordBuffer.isNotEmpty()) wordBuffer.clear()
        refreshTopBar()
    }

    // Word boundaries no longer silently rewrite what was typed - suggestions are only ever
    // applied when the user explicitly taps a suggestion chip in the top bar.
    private fun commitPunctuationOrSpace(boundary: String) {
        if (wordBuffer.isNotEmpty()) {
            lastCommittedWord = wordBuffer.toString().trim()
            Dictionary.recordUsedWord(lastCommittedWord)
        } else {
            val textBefore = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()?.trim() ?: ""
            val prev = textBefore.split(Regex("\\s+")).lastOrNull { it.isNotEmpty() } ?: ""
            if (prev.isNotEmpty()) {
                lastCommittedWord = prev
            }
        }
        handleKeyCommit(boundary, isLetter = false)
        wordBuffer.clear()
        refreshTopBar()
    }

    private fun deleteChar() {
        val textBefore = currentInputConnection?.getTextBeforeCursor(1, 0)
        if (!textBefore.isNullOrEmpty()) {
            val charDeleted = textBefore[0]
            DeletePeekMemory.recordDeletedChar(charDeleted, this, covertManager)
        }
        if (covertManager.isCovertActive) {
            val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(4000, 0)
            covertManager.handleBackspace(textBeforeCursor)
        }
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
        if (covertManager.isMathEnabled) {
            val textBefore = currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            val payload = covertManager.extractMathPayload(textBefore)
            if (payload != null) {
                TriggerManager.queueMathPayload(payload, this, covertManager)
            }
        }
        val info = currentInputEditorInfo
        val inputType = info?.inputType ?: 0
        val isMultiLine = (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE) != 0 ||
                (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE

        if (!isMultiLine && info != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
        refreshTopBar()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (TriggerManager.isVolumeTriggerEnabled(this) &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (covertManager.isMathEnabled && TriggerManager.pendingMathPayload == null) {
                val textBefore = currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString() ?: ""
                val payload = covertManager.extractMathPayload(textBefore)
                if (payload != null) {
                    TriggerManager.pendingMathPayload = payload
                }
            }
            val fired = TriggerManager.fireTrigger("Volume Hardware Key (IME)", this)
            if (fired) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (TriggerManager.isVolumeTriggerEnabled(this) &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
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
