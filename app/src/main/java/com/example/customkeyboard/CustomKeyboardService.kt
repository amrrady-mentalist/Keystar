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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class CustomKeyboardService : InputMethodService() {

    private enum class Lang { EN, AR }
    private enum class Mode { LETTERS, NUMBERS, SYMBOLS, EMOJI, CLIPBOARD }

    private var currentLang = Lang.EN
    private var currentMode = Mode.LETTERS
    private var shiftOn = false
    private var capsLock = false
    private var lastShiftTapTime = 0L
    private var symbolsPage = 1
    private val wordBuffer = StringBuilder()
    private var lastCommittedWord = ""
    private var selectedClipboardEffectTab = "covert"

    private lateinit var rootContainer: LinearLayout
    private lateinit var topBarContainer: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipHistory: ClipboardHistory
    private lateinit var covertManager: CovertManager

    // ---------- sizing helpers (customizable via Settings preferences) ----------
    private val KEY_RADIUS_DP = 8
    private val PILL_RADIUS_DP = 24
    private val ICON_GLYPH_DP = 26
    private val KEY_INSET_H_DP = 2
    private val KEY_INSET_V_DP = 4

    private fun getRowHeightDp(): Int {
        return when (prefs.getString("keyboard_height", "normal")) {
            "compact" -> 44
            "tall" -> 54
            "extra_tall" -> 60
            else -> 48
        }
    }

    private fun getTopBarHeightDp(): Int {
        return when (prefs.getString("keyboard_height", "normal")) {
            "compact" -> 42
            "tall" -> 50
            "extra_tall" -> 54
            else -> 46
        }
    }

    private fun getLetterFontSize(): Float {
        return when (prefs.getString("key_font_size", "normal")) {
            "small" -> 20f
            "large" -> 25f
            "extra_large" -> 28f
            else -> 23f
        }
    }

    private fun getSuggestionFontSize(): Float {
        return when (prefs.getString("key_font_size", "normal")) {
            "small" -> 19f
            "large" -> 24f
            "extra_large" -> 27f
            else -> 22f
        }
    }

    private fun getSymbolFontSize(): Float {
        return when (prefs.getString("key_font_size", "normal")) {
            "small" -> 18f
            "large" -> 22f
            "extra_large" -> 25f
            else -> 20f
        }
    }

    private fun getSpecialKeyFontSize(): Float {
        return when (prefs.getString("key_font_size", "normal")) {
            "small" -> 13f
            "large" -> 16f
            "extra_large" -> 18f
            else -> 14.5f
        }
    }

    private fun getEmojiFontSize(): Float {
        return when (prefs.getString("key_font_size", "normal")) {
            "small" -> 20f
            "large" -> 24f
            "extra_large" -> 27f
            else -> 22f
        }
    }

    private val commonEmojis = listOf("😀", "😂", "❤️", "👍", "🙏", "🔥", "😊", "🎉", "👀", "✅", "😉", "💯")
    private var currentEmojiCategory: String = "Smileys"

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
        TriggerManager.onCaptureLiveCursorContext = {
            val before = currentInputConnection?.getTextBeforeCursor(4000, 0)?.toString() ?: ""
            val after = currentInputConnection?.getTextAfterCursor(1000, 0)?.toString() ?: ""
            Pair(before, after)
        }
        TriggerManager.onCaptureLiveText = {
            currentInputConnection?.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        }
        TriggerManager.onExecuteTextReplacement = { _, cm ->
            val success = executeRemoteTextReplacement(cm)
            if (success) {
                // If enter behavior is set to auto_effect or search_only, automatically click search
                if (cm.enterKeyBehavior == "auto_effect" || cm.enterKeyBehavior == "search_only") {
                    Handler(Looper.getMainLooper()).postDelayed({
                        triggerSearchAfterReplacement()
                    }, 120L)
                }
            }
            success
        }
        Dictionary.init(this)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(systemClipListener)
        TriggerManager.stopActiveSession(this)
        TriggerManager.onCaptureLiveCursorContext = null
        TriggerManager.onCaptureLiveText = null
        TriggerManager.onExecuteTextReplacement = null
        super.onDestroy()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        TriggerManager.startActiveSession(this)
        if (covertManager.isTextReplaceEnabled) {
            covertManager.fetchLatestApiValue()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // Keep trigger session alive so triggers work even if spectator dismissed keyboard
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Keep trigger session alive so triggers work even if spectator dismissed keyboard
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        TriggerManager.startActiveSession(this)
        if (covertManager.isTextReplaceEnabled) {
            covertManager.fetchLatestApiValue()
        }
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
                rootContainer.addView(buildEmojiPanel())
            }
            Mode.NUMBERS -> {
                rootContainer.addView(buildNumbersView())
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

        if (currentMode != Mode.CLIPBOARD && currentMode != Mode.NUMBERS) {
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

    private fun isWordCharacter(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-' || (c in '\u0600'..'\u06FF')
    }

    private fun getActiveTypingContext(): Pair<String, String> {
        val textBefore = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString() ?: ""
        if (textBefore.isEmpty()) {
            return Pair(wordBuffer.toString(), lastCommittedWord)
        }
        val lastChar = textBefore.last()
        if (lastChar.isWhitespace() || !isWordCharacter(lastChar)) {
            // Space or punctuation -> current word is empty, extract previous word
            val words = textBefore.trim().split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
            val prev = words.lastOrNull() ?: lastCommittedWord
            return Pair("", prev)
        } else {
            // Typing in-progress word -> extract active word prefix and preceding word
            var i = textBefore.length - 1
            while (i >= 0 && isWordCharacter(textBefore[i])) {
                i--
            }
            val activeWord = textBefore.substring(i + 1)
            val beforeActive = textBefore.substring(0, i + 1).trim()
            val words = beforeActive.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
            val prev = words.lastOrNull() ?: lastCommittedWord
            return Pair(activeWord, prev)
        }
    }

    // ---------- top bar: suggestions / common emojis / clipboard / settings ----------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getTopBarHeightDp()))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(2))
        }

        val isArabic = currentLang == Lang.AR
        val (currentWord, prevWord) = getActiveTypingContext()
        val contextualSuggestions = if (currentMode == Mode.LETTERS) {
            Dictionary.getContextualSuggestions(currentWord, prevWord, isArabic, limit = 16)
        } else emptyList()

        when {
            currentMode == Mode.CLIPBOARD -> {
                bar.addView(iconButton(R.drawable.ic_arrow_back, "Back") { switchMode(Mode.LETTERS) })
                bar.addView(TextView(this).apply {
                    text = "Clipboard History"
                    setTextColor(textColor())
                    setTypeface(Typeface.DEFAULT_BOLD)
                    textSize = 14f
                    setPadding(dp(8), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                bar.addView(iconButtonText("⌫") { deleteChar() })
            }
            contextualSuggestions.isNotEmpty() -> {
                bar.addView(buildSuggestionsScroll(contextualSuggestions))
                bar.addView(iconButton(R.drawable.ic_clipboard, "Clipboard") { switchMode(Mode.CLIPBOARD) })
                bar.addView(iconButton(R.drawable.ic_settings, "Settings") {
                    val intent = android.content.Intent(this, MainActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                })
            }
            else -> {
                bar.addView(buildStandardToolbar())
            }
        }
        return bar
    }

    private fun buildStandardToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        bar.addView(toolbarIconButton(R.drawable.ic_grid, "Menu") {
            Toast.makeText(this, "Quick Tools", Toast.LENGTH_SHORT).show()
        })
        bar.addView(toolbarIconButton(R.drawable.ic_clipboard, "Clipboard") {
            switchMode(Mode.CLIPBOARD)
        })
        bar.addView(toolbarIconButton(R.drawable.ic_emoji_toolbar, "Emojis") {
            switchMode(if (currentMode == Mode.EMOJI) Mode.LETTERS else Mode.EMOJI)
        })
        bar.addView(toolbarIconButton(R.drawable.ic_settings, "Settings") {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        })
        bar.addView(toolbarIconButton(R.drawable.ic_translate, "Language") {
            switchLanguage()
        })
        bar.addView(toolbarIconButton(R.drawable.ic_mic, "Voice") {
            triggerVoiceInput()
        })

        return bar
    }

    private fun toolbarIconButton(drawableResId: Int, contentDesc: String, onClick: () -> Unit): View {
        val size = dp(32)
        val pad = dp(5)
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val iv = ImageView(this@CustomKeyboardService).apply {
                setImageResource(drawableResId)
                setColorFilter(textColor())
                contentDescription = contentDesc
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            }
            addView(iv)
            applyKeyTouchBehavior(this, pressHighlightColor(), null, KEY_RADIUS_DP) { onClick() }
        }
    }

    private fun triggerVoiceInput() {
        try {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSuggestionsScroll(items: List<Dictionary.SuggestionItem>): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val hasEmojis = items.any { it.isEmoji }
        items.forEachIndexed { index, item ->
            if (index > 0) {
                container.addView(createSuggestionDivider())
            }
            if (item.isEmoji) {
                container.addView(emojiChip(item.text))
            } else {
                container.addView(suggestionChip(item, hasEmojis))
            }
        }
        return container
    }

    private fun createSuggestionDivider(): View {
        val divColor = if (isDarkMode()) Color.argb(45, 255, 255, 255) else Color.argb(35, 0, 0, 0)
        return View(this).apply {
            setBackgroundColor(divColor)
            val w = dp(1)
            val h = dp(18)
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(1)
                marginEnd = dp(1)
            }
        }
    }

    private fun emojiChip(emoji: String): TextView {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = emoji
            textSize = 17f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            }
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) {
                currentInputConnection?.commitText("$emoji ", 1)
                wordBuffer.clear()
                refreshTopBar()
            }
        }
    }

    private fun buildCommonEmojiScroll(): HorizontalScrollView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        commonEmojis.forEachIndexed { index, emoji ->
            if (index > 0) {
                inner.addView(createSuggestionDivider())
            }
            inner.addView(TextView(this).apply {
                text = emoji
                textSize = 18f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
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

    private fun suggestionChip(item: Dictionary.SuggestionItem, hasEmojis: Boolean = false): TextView {
        val isPrimary = item.isPrimary
        val isCorrection = item.isCorrection
        val isNextWord = item.isNextWord
        val resting = when {
            isCorrection -> keyBackground(if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#E8F0FE"), KEY_RADIUS_DP)
            isPrimary -> keyBackground(specialKeyColor(), KEY_RADIUS_DP)
            else -> null
        }
        return TextView(this).apply {
            text = if (isCorrection && !isNextWord && !item.text.contains("'")) "${item.text} ✓" else item.text
            setTextColor(when {
                isCorrection -> accentColor()
                isPrimary -> accentColor()
                else -> textColor()
            })
            setTypeface(if (isPrimary || isCorrection) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            textSize = getSuggestionFontSize()
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            val weight = if (hasEmojis) 1.25f else 1f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                if (isPrimary || isCorrection) {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                }
            }
            if (resting != null) background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) {
                val (activeWord, _) = getActiveTypingContext()
                Dictionary.recordUsedWord(item.text)
                lastCommittedWord = item.text
                val lengthToDelete = if (activeWord.isNotEmpty()) activeWord.length else wordBuffer.length
                if (lengthToDelete > 0) {
                    currentInputConnection?.deleteSurroundingText(lengthToDelete, 0)
                }
                wordBuffer.clear()
                currentInputConnection?.commitText("${item.text} ", 1)
                refreshTopBar()
            }
        }
    }

    private fun iconButton(drawableResId: Int, contentDesc: String, onClick: () -> Unit): View {
        val size = dp(32)
        val pad = dp(5)
        return ImageView(this).apply {
            setImageResource(drawableResId)
            setColorFilter(textColor())
            contentDescription = contentDesc
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(3)
                marginEnd = dp(2)
            }
            applyKeyTouchBehavior(this, pressHighlightColor(), null, KEY_RADIUS_DP) { onClick() }
        }
    }

    private fun iconButtonText(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            setTextColor(textColor())
            textSize = 16f
            gravity = Gravity.CENTER
            val size = dp(28)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(3) }
            applyKeyTouchBehavior(this, pressHighlightColor(), null, KEY_RADIUS_DP) { onClick() }
        }
    }

    // ---------- clipboard panel with hidden covert effect toggles ----------

    private fun buildClipboardPanel(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(235))
            isFillViewport = true
        }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        // 1. Covert Effect Quick-Toggle Bar (Discreet flat white icons with indicator lights)
        val effectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val barBg = GradientDrawable().apply {
                setColor(specialKeyColor())
                cornerRadius = dp(8).toFloat()
            }
            background = barBg
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

        // Effect 1: Covert Typing (Google Incognito Fedora & Glasses)
        effectBar.addView(createEffectIconButton(
            iconRes = R.drawable.ic_effect_covert,
            title = "Covert Typing",
            isActive = covertManager.isCovertActive,
            isSelectedTab = selectedClipboardEffectTab == "covert"
        ) {
            if (selectedClipboardEffectTab != "covert") {
                selectedClipboardEffectTab = "covert"
            } else {
                covertManager.toggleCovert()
            }
            render()
        })

        // Effect 2: Math Magic Equation (Google Material Calculator)
        effectBar.addView(createEffectIconButton(
            iconRes = R.drawable.ic_effect_math,
            title = "Math Equation",
            isActive = covertManager.isMathEnabled,
            isSelectedTab = selectedClipboardEffectTab == "math"
        ) {
            if (selectedClipboardEffectTab != "math") {
                selectedClipboardEffectTab = "math"
            } else {
                covertManager.isMathEnabled = !covertManager.isMathEnabled
            }
            render()
        })

        // Effect 3: Delete Peek (Material Trash Bin + Peek Eye)
        effectBar.addView(createEffectIconButton(
            iconRes = R.drawable.ic_effect_delete_peek,
            title = "Delete Peek",
            isActive = covertManager.isDeletePeekEnabled,
            isSelectedTab = selectedClipboardEffectTab == "delete_peek"
        ) {
            if (selectedClipboardEffectTab != "delete_peek") {
                selectedClipboardEffectTab = "delete_peek"
            } else {
                covertManager.isDeletePeekEnabled = !covertManager.isDeletePeekEnabled
            }
            render()
        })

        // Effect 4: Any Word / Line Text Peek (Material Visibility Eye)
        effectBar.addView(createEffectIconButton(
            iconRes = R.drawable.ic_effect_text_peek,
            title = "Text Peek",
            isActive = covertManager.isTextPeekEnabled,
            isSelectedTab = selectedClipboardEffectTab == "text_peek"
        ) {
            if (selectedClipboardEffectTab != "text_peek") {
                selectedClipboardEffectTab = "text_peek"
            } else {
                covertManager.isTextPeekEnabled = !covertManager.isTextPeekEnabled
            }
            render()
        })

        // Effect 5: API Text Replace (Material Swap Arrows)
        effectBar.addView(createEffectIconButton(
            iconRes = R.drawable.ic_effect_replace,
            title = "Text Replace",
            isActive = covertManager.isTextReplaceEnabled,
            isSelectedTab = selectedClipboardEffectTab == "replace"
        ) {
            if (selectedClipboardEffectTab != "replace") {
                selectedClipboardEffectTab = "replace"
            } else {
                covertManager.isTextReplaceEnabled = !covertManager.isTextReplaceEnabled
            }
            render()
        })

        mainLayout.addView(effectBar)

        // 2. Active Effect Sub-Effects & Line Selector Inspector Card
        mainLayout.addView(buildSubEffectsPanel(selectedClipboardEffectTab))

        // 3. Enter Button Behavior Toggle Chip Row
        val enterToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }

        val enterModes = listOf(
            Triple("auto_effect", "Based on Effect", "⚡ Effect"),
            Triple("auto_field", "Based on Field", "📝 Field"),
            Triple("newline_only", "Next Line Only", "↵ Next Line"),
            Triple("search_only", "Search Action Only", "🔍 Search")
        )

        val currentEnterMode = covertManager.enterKeyBehavior
        enterModes.forEach { (modeKey, fullDesc, chipLabel) ->
            val isSelected = currentEnterMode == modeKey
            val chip = TextView(this).apply {
                text = chipLabel
                textSize = 11f
                setTypeface(if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (isSelected) enterIconColor() else textColor())
                setPadding(dp(4), dp(4), dp(4), dp(4))
                val bg = GradientDrawable().apply {
                    setColor(if (isSelected) accentColor() else specialKeyColor())
                    cornerRadius = dp(6).toFloat()
                    if (!isSelected) {
                        setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
                    }
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    covertManager.enterKeyBehavior = modeKey
                    Toast.makeText(this@CustomKeyboardService, "Enter key: $fullDesc", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            enterToggleRow.addView(chip)
        }
        mainLayout.addView(enterToggleRow)

        // 4. Regular Clipboard Item List
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
                    setPadding(dp(12), dp(8), dp(12), dp(8))
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
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    clipHistory.clear()
                    render()
                }
            })
        }
        mainLayout.addView(list)
        scroll.addView(mainLayout)
        return scroll
    }

    /**
     * Dedicated Sub-Effects inspector card that dynamically adapts to whichever magic effect is selected.
     * Includes interactive line selectors, stepper wheels, sub-effect toggle switches, and mode chips.
     */
    private fun buildSubEffectsPanel(effectKey: String): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(if (isDarkMode()) Color.parseColor("#1F2225") else Color.parseColor("#F8F9FA"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), if (isDarkMode()) Color.parseColor("#34383C") else Color.parseColor("#E0E0E0"))
            }
            background = bg
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

        // Header: Icon + Title + Master Armed/Standby Switch
        val isMasterActive = when (effectKey) {
            "covert" -> covertManager.isCovertActive
            "math" -> covertManager.isMathEnabled
            "delete_peek" -> covertManager.isDeletePeekEnabled
            "text_peek" -> covertManager.isTextPeekEnabled
            "replace" -> covertManager.isTextReplaceEnabled
            else -> false
        }
        val effectTitle = when (effectKey) {
            "covert" -> "Covert Typing Engine"
            "math" -> "Math Magic Equation"
            "delete_peek" -> "Delete Peek Magic"
            "text_peek" -> "Universal Text Peek"
            "replace" -> "API Text Replace"
            else -> "Magic Effect"
        }
        val effectIconRes = when (effectKey) {
            "covert" -> R.drawable.ic_effect_covert
            "math" -> R.drawable.ic_effect_math
            "delete_peek" -> R.drawable.ic_effect_delete_peek
            "text_peek" -> R.drawable.ic_effect_text_peek
            "replace" -> R.drawable.ic_effect_replace
            else -> R.drawable.ic_clipboard
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val headerIcon = ImageView(this).apply {
            setImageResource(effectIconRes)
            setColorFilter(if (isMasterActive) (if (isDarkMode()) Color.parseColor("#00E676") else Color.parseColor("#2E7D32")) else textColor())
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        }
        val headerTitle = TextView(this).apply {
            text = effectTitle
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val masterSwitch = createCustomSwitchView(isMasterActive) { newChecked ->
            when (effectKey) {
                "covert" -> covertManager.isCovertActive = newChecked
                "math" -> covertManager.isMathEnabled = newChecked
                "delete_peek" -> covertManager.isDeletePeekEnabled = newChecked
                "text_peek" -> covertManager.isTextPeekEnabled = newChecked
                "replace" -> covertManager.isTextReplaceEnabled = newChecked
            }
            val status = if (newChecked) "ARMED" else "OFF"
            Toast.makeText(this@CustomKeyboardService, "$effectTitle: $status", Toast.LENGTH_SHORT).show()
            render()
        }

        headerRow.addView(headerIcon)
        headerRow.addView(headerTitle)
        headerRow.addView(masterSwitch)
        panel.addView(headerRow)

        // Subtle divider
        val div = View(this).apply {
            setBackgroundColor(if (isDarkMode()) Color.parseColor("#34383C") else Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(6)
            }
        }
        panel.addView(div)

        // Sub-Effects based on effectKey
        when (effectKey) {
            "math" -> {
                // Target Mode Chips (Formula Total vs Specific Line)
                panel.addView(buildOptionChipsRow(
                    label = "Target Calculation Mode",
                    options = listOf("total" to "∑ Total Formula", "line" to "🎯 Specific Line"),
                    selectedKey = covertManager.mathTargetMode
                ) { newMode ->
                    covertManager.mathTargetMode = newMode
                    render()
                })

                // Line Number Selector & Wheel
                panel.addView(buildLineSelectorWheel(
                    currentLine = covertManager.mathTargetLine,
                    maxLines = 10,
                    label = "Target Line Number (N.list)",
                    subtitle = if (covertManager.mathTargetMode == "line") "Extracts spectator's number from line #" else "Can also reference line # in formula"
                ) { newLine ->
                    covertManager.mathTargetLine = newLine
                    render()
                })

                // Sub-effect toggles
                panel.addView(buildSubEffectToggleRow(
                    title = "Send to Inject API",
                    subtitle = "Webhook POST result to spectator remote server",
                    isChecked = covertManager.mathSendToInject
                ) {
                    covertManager.mathSendToInject = it
                    render()
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Local Push Notification",
                    subtitle = "Preview result in system notification shade",
                    isChecked = covertManager.mathLocalNotification
                ) {
                    covertManager.mathLocalNotification = it
                    render()
                })

                // Formula Presets quick row
                panel.addView(buildOptionChipsRow(
                    label = "Formula Equation",
                    options = listOf(
                        "L1+L2" to "L1 + L2",
                        "L1-L2" to "L1 - L2",
                        "L1*L2" to "L1 × L2",
                        "L1+L2+L3" to "L1+L2+L3"
                    ),
                    selectedKey = covertManager.mathEquation
                ) { eq ->
                    covertManager.mathEquation = eq
                    Toast.makeText(this, "Formula set: $eq", Toast.LENGTH_SHORT).show()
                    render()
                })
            }

            "text_peek" -> {
                // Peek Scope Chips
                panel.addView(buildOptionChipsRow(
                    label = "Peek Target Scope",
                    options = listOf(
                        "all" to "All Text",
                        "cursor_line" to "Cursor Line",
                        "line" to "🎯 Line Number"
                    ),
                    selectedKey = covertManager.textPeekMode
                ) { newScope ->
                    covertManager.textPeekMode = newScope
                    render()
                })

                // Line Number Selector & Wheel
                panel.addView(buildLineSelectorWheel(
                    currentLine = covertManager.textPeekTargetLine,
                    maxLines = 10,
                    label = "Target Line Number",
                    subtitle = "Reads spectator text on this specific line"
                ) { newLine ->
                    covertManager.textPeekTargetLine = newLine
                    render()
                })

                // Sub-effect toggles
                panel.addView(buildSubEffectToggleRow(
                    title = "Send to Inject API",
                    subtitle = "Webhook POST text peek payload remotely",
                    isChecked = covertManager.textPeekSendToInject
                ) {
                    covertManager.textPeekSendToInject = it
                    render()
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Local Push Notification",
                    subtitle = "Show peeked text in system notifications",
                    isChecked = covertManager.textPeekLocalNotification
                ) {
                    covertManager.textPeekLocalNotification = it
                    render()
                })
            }

            "covert" -> {
                // Letter Reveal Position Wheel / Stepper
                val posLabels = listOf(
                    0 to "1st Letter",
                    1 to "2nd Letter",
                    2 to "3rd Letter",
                    -1 to "Last Letter"
                )
                panel.addView(buildPositionSelectorWheel(
                    currentPos = covertManager.revealLetterPosition,
                    options = posLabels,
                    label = "Secret Reveal Letter Position",
                    subtitle = "Which character embeds the secret word on line 2"
                ) { newPos ->
                    covertManager.revealLetterPosition = newPos
                    render()
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Send to Inject API",
                    subtitle = "Transmit secret word upon capture to webhook",
                    isChecked = covertManager.covertSendToInject
                ) {
                    covertManager.covertSendToInject = it
                    render()
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Local Push Notification",
                    subtitle = "Show captured secret word in status bar",
                    isChecked = covertManager.covertLocalNotification
                ) {
                    covertManager.covertLocalNotification = it
                    render()
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Spacebar Double-Tap Trigger",
                    subtitle = "Double space triggers secret word capture",
                    isChecked = covertManager.stealthSpacebarTrigger
                ) {
                    covertManager.stealthSpacebarTrigger = it
                    render()
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Stealth Haptic Feedback",
                    subtitle = "Subtle vibration confirmation on secret capture",
                    isChecked = covertManager.stealthHapticFeedback
                ) {
                    covertManager.stealthHapticFeedback = it
                    render()
                })
            }

            "delete_peek" -> {
                panel.addView(TextView(this).apply {
                    text = "Captures and exposes text deleted by the spectator using backspace."
                    setTextColor(textColor())
                    alpha = 0.75f
                    textSize = 11.5f
                    setPadding(0, 0, 0, dp(6))
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Send to Inject API",
                    subtitle = "Forward deleted characters/words to webhook",
                    isChecked = covertManager.deletePeekSendToInject
                ) {
                    covertManager.deletePeekSendToInject = it
                    render()
                })

                panel.addView(buildSubEffectToggleRow(
                    title = "Local Push Notification",
                    subtitle = "Display deleted text peek in notification",
                    isChecked = covertManager.deletePeekLocalNotification
                ) {
                    covertManager.deletePeekLocalNotification = it
                    render()
                })
            }

            "replace" -> {
                panel.addView(buildOptionChipsRow(
                    label = "Value Source",
                    options = listOf("api" to "🌐 Remote API", "custom" to "✍️ Custom Text"),
                    selectedKey = covertManager.replaceSourceMode
                ) { newSrc ->
                    covertManager.replaceSourceMode = newSrc
                    render()
                })

                panel.addView(TextView(this).apply {
                    val preview = covertManager.getEffectiveReplacementValue()
                    text = "Placeholder: ${covertManager.replacePlaceholder}\nEffective Value: \"$preview\""
                    setTextColor(textColor())
                    textSize = 11.5f
                    setPadding(0, dp(2), 0, dp(4))
                })
            }
        }

        return panel
    }

    /**
     * Interactive Line Number Selector and Wheel Stepper with quick selection chips.
     */
    private fun buildLineSelectorWheel(
        currentLine: Int,
        maxLines: Int = 10,
        label: String,
        subtitle: String,
        onLineSelected: (Int) -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }

        val titleView = TextView(this).apply {
            text = label
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 12f
        }
        val subView = TextView(this).apply {
            text = subtitle
            setTextColor(textColor())
            alpha = 0.65f
            textSize = 10.5f
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(titleView)
        container.addView(subView)

        // Wheel Stepper Row: [ ◀ ]  [ Line 3 ]  [ ▶ ]
        val stepperRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }

        // Decrement button
        val decBtn = TextView(this).apply {
            text = "◀"
            textSize = 13f
            setTextColor(if (currentLine > 1) textColor() else Color.GRAY)
            gravity = Gravity.CENTER
            val size = dp(34)
            layoutParams = LinearLayout.LayoutParams(size, size)
            val btnBg = GradientDrawable().apply {
                setColor(specialKeyColor())
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
            }
            background = btnBg
            if (currentLine > 1) {
                applyKeyTouchBehavior(this, pressHighlightColor(), null, 17) {
                    onLineSelected(currentLine - 1)
                }
            }
        }

        // Center wheel indicator
        val centerBadge = TextView(this).apply {
            text = "Line $currentLine"
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(accentColor())
                cornerRadius = dp(17).toFloat()
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        }

        // Increment button
        val incBtn = TextView(this).apply {
            text = "▶"
            textSize = 13f
            setTextColor(if (currentLine < maxLines) textColor() else Color.GRAY)
            gravity = Gravity.CENTER
            val size = dp(34)
            layoutParams = LinearLayout.LayoutParams(size, size)
            val btnBg = GradientDrawable().apply {
                setColor(specialKeyColor())
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
            }
            background = btnBg
            if (currentLine < maxLines) {
                applyKeyTouchBehavior(this, pressHighlightColor(), null, 17) {
                    onLineSelected(currentLine + 1)
                }
            }
        }

        stepperRow.addView(decBtn)
        stepperRow.addView(centerBadge)
        stepperRow.addView(incBtn)
        container.addView(stepperRow)

        // Quick line selector horizontal scroll chips: [Line 1] [Line 2] [Line 3] ...
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }

        for (i in 1..maxLines) {
            val isSelected = i == currentLine
            val chip = TextView(this).apply {
                text = "Line $i"
                textSize = 11f
                setTypeface(if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
                setTextColor(if (isSelected) Color.WHITE else textColor())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                val chipBg = GradientDrawable().apply {
                    setColor(if (isSelected) accentColor() else specialKeyColor())
                    cornerRadius = dp(12).toFloat()
                    if (!isSelected) {
                        setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
                    }
                }
                background = chipBg
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    onLineSelected(i)
                }
            }
            chipRow.addView(chip)
        }
        scroll.addView(chipRow)
        container.addView(scroll)

        return container
    }

    /**
     * Stepper wheel for reveal character positions (0, 1, 2, -1).
     */
    private fun buildPositionSelectorWheel(
        currentPos: Int,
        options: List<Pair<Int, String>>,
        label: String,
        subtitle: String,
        onSelect: (Int) -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }

        val titleView = TextView(this).apply {
            text = label
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 12f
        }
        val subView = TextView(this).apply {
            text = subtitle
            setTextColor(textColor())
            alpha = 0.65f
            textSize = 10.5f
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(titleView)
        container.addView(subView)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }

        options.forEach { (posValue, posLabel) ->
            val isSelected = currentPos == posValue
            val chip = TextView(this).apply {
                text = posLabel
                textSize = 11f
                setTypeface(if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
                setTextColor(if (isSelected) Color.WHITE else textColor())
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                val chipBg = GradientDrawable().apply {
                    setColor(if (isSelected) accentColor() else specialKeyColor())
                    cornerRadius = dp(12).toFloat()
                    if (!isSelected) {
                        setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
                    }
                }
                background = chipBg
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    onSelect(posValue)
                }
            }
            chipRow.addView(chip)
        }
        container.addView(chipRow)

        return container
    }

    /**
     * Reusable toggle row with title, description, and interactive switch.
     */
    private fun buildSubEffectToggleRow(
        title: String,
        subtitle: String,
        isChecked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 12.5f
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(textColor())
            alpha = 0.65f
            textSize = 10.5f
        }
        textCol.addView(tvTitle)
        textCol.addView(tvSub)

        val switchView = createCustomSwitchView(isChecked) {
            onToggle(!isChecked)
        }

        row.addView(textCol)
        row.addView(switchView)

        row.setOnClickListener {
            onToggle(!isChecked)
        }

        return row
    }

    /**
     * Custom styled pill toggle switch with smooth active indicator.
     */
    private fun createCustomSwitchView(isChecked: Boolean, onClick: (Boolean) -> Unit): View {
        val track = FrameLayout(this).apply {
            val w = dp(42)
            val h = dp(24)
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                marginStart = dp(6)
            }
            val bg = GradientDrawable().apply {
                if (isChecked) {
                    setColor(Color.parseColor("#00E676")) // Neon emerald active
                } else {
                    setColor(if (isDarkMode()) Color.parseColor("#455A64") else Color.parseColor("#B0BEC5"))
                }
                cornerRadius = dp(12).toFloat()
            }
            background = bg
        }

        val thumb = View(this).apply {
            val thumbSize = dp(18)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = bg
            val params = FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
                gravity = if (isChecked) (Gravity.END or Gravity.CENTER_VERTICAL) else (Gravity.START or Gravity.CENTER_VERTICAL)
                if (isChecked) marginEnd = dp(3) else marginStart = dp(3)
            }
            layoutParams = params
        }

        track.addView(thumb)
        track.setOnClickListener {
            onClick(!isChecked)
        }
        return track
    }

    /**
     * Segmented option chips row.
     */
    private fun buildOptionChipsRow(
        label: String,
        options: List<Pair<String, String>>,
        selectedKey: String,
        onSelect: (String) -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val titleView = TextView(this).apply {
            text = label
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 12f
            setPadding(0, 0, 0, dp(3))
        }
        container.addView(titleView)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        options.forEach { (key, optTitle) ->
            val isSelected = key == selectedKey
            val chip = TextView(this).apply {
                text = optTitle
                textSize = 11f
                setTypeface(if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
                setTextColor(if (isSelected) Color.WHITE else textColor())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                val chipBg = GradientDrawable().apply {
                    setColor(if (isSelected) accentColor() else specialKeyColor())
                    cornerRadius = dp(12).toFloat()
                    if (!isSelected) {
                        setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
                    }
                }
                background = chipBg
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    onSelect(key)
                }
            }
            chipRow.addView(chip)
        }
        container.addView(chipRow)

        return container
    }

    /**
     * Helper to construct a toggle button with sharper icon and a vivid active/inactive indicator light for the clipboard bar.
     */
    private fun createEffectIconButton(
        iconRes: Int,
        title: String,
        isActive: Boolean,
        isSelectedTab: Boolean,
        onToggle: () -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                setMargins(dp(2), dp(1), dp(2), dp(1))
            }
            val bg = GradientDrawable().apply {
                if (isActive) {
                    setColor(if (isDarkMode()) Color.parseColor("#1B382B") else Color.parseColor("#E8F5E9"))
                    val strokeColor = if (isSelectedTab) accentColor() else (if (isDarkMode()) Color.parseColor("#00E676") else Color.parseColor("#2E7D32"))
                    setStroke(dp(2), strokeColor)
                } else {
                    setColor(if (isSelectedTab) (if (isDarkMode()) Color.parseColor("#32363A") else Color.parseColor("#E8EAED")) else (if (isDarkMode()) Color.parseColor("#25282B") else Color.parseColor("#F1F3F4")))
                    val strokeColor = if (isSelectedTab) accentColor() else (if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
                    setStroke(if (isSelectedTab) dp(2) else dp(1), strokeColor)
                }
                cornerRadius = dp(8).toFloat()
            }
            background = bg
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(if (isActive) (if (isDarkMode()) Color.WHITE else Color.parseColor("#1B5E20")) else (if (isDarkMode()) Color.parseColor("#90A4AE") else Color.parseColor("#5F6368")))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }

        val indicator = View(this).apply {
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isActive) {
                    setColor(Color.parseColor("#00E676")) // Vivid neon emerald green
                    setStroke(dp(2), Color.parseColor("#B9F6CA")) // Bright luminous glow ring
                } else {
                    setColor(if (isDarkMode()) Color.parseColor("#455A64") else Color.parseColor("#B0BEC5")) // Clear visible standby
                    setStroke(dp(1), if (isDarkMode()) Color.parseColor("#607D8B") else Color.parseColor("#90A4AE"))
                }
            }
            background = dot
            val dotSize = dp(8)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                topMargin = dp(4)
            }
        }

        container.addView(icon)
        container.addView(indicator)

        applyKeyTouchBehavior(container, pressHighlightColor(), null, KEY_RADIUS_DP) {
            onToggle()
        }

        return container
    }

    // ---------- key rows ----------

    private fun buildRow(keys: List<String>, applyShift: Boolean = false, isEmoji: Boolean = false, isLetterRow: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        val fontSize = when {
            isEmoji -> getEmojiFontSize()
            isLetterRow -> getLetterFontSize()
            else -> getSymbolFontSize()
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        row.addView(spacer(0.5f))
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, fontSize = getLetterFontSize()) { commitLetter(display) })
        }
        row.addView(spacer(0.5f))
        return row
    }

    private fun buildLetterRowWithShiftAndBackspace(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        if (currentLang == Lang.EN) {
            row.addView(makeShiftKey(weight = 1.5f))
        }
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, fontSize = getLetterFontSize()) { commitLetter(display) })
        }
        // Swipe left on backspace to delete more than one character at a time.
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildSymbolsRow(keys: List<String>, prependToggle: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        if (prependToggle) {
            val label = if (symbolsPage == 1) "1/2" else "2/2"
            row.addView(makeSpecialKey(label, weight = 1.3f) { toggleSymbolsPage() })
        }
        keys.forEach { k -> row.addView(makeKey(k, weight = 1f, fontSize = getSymbolFontSize()) { commitSymbol(k) }) }
        return row
    }

    private fun buildSymbolsBottomRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        keys.forEach { k -> row.addView(makeKey(k, weight = 1f, fontSize = getSymbolFontSize()) { commitSymbol(k) }) }
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildEmojiPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 1. Category Bar
        val catBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        KeyboardLayoutData.emojiCategoryIcons.forEach { (icon, catName) ->
            val isSelected = (catName == currentEmojiCategory)
            val tabBg = if (isSelected) keyBackground(accentColor(), KEY_RADIUS_DP) else keyBackground(specialKeyColor(), KEY_RADIUS_DP)
            val tv = TextView(this).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                }
                background = tabBg
                applyKeyTouchBehavior(this, pressHighlightColor(), tabBg, KEY_RADIUS_DP) {
                    currentEmojiCategory = catName
                    render()
                }
            }
            catBar.addView(tv)
        }
        panel.addView(catBar)

        // 2. All emojis in selected category
        val emojis = KeyboardLayoutData.emojiCategoryData[currentEmojiCategory]
            ?: KeyboardLayoutData.emojiCategoryData["Smileys"]
            ?: emptyList()

        val row1 = mutableListOf<String>()
        val row2 = mutableListOf<String>()
        val row3 = mutableListOf<String>()
        for (i in emojis.indices) {
            when (i % 3) {
                0 -> row1.add(emojis[i])
                1 -> row2.add(emojis[i])
                2 -> row3.add(emojis[i])
            }
        }

        val emojiContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(getRowHeightDp()))
        }
        row1.forEach { em -> r1.addView(makeEmojiKey(em)) }
        emojiContent.addView(r1)

        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(getRowHeightDp()))
        }
        row2.forEach { em -> r2.addView(makeEmojiKey(em)) }
        emojiContent.addView(r2)

        val r3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(getRowHeightDp()))
        }
        row3.forEach { em -> r3.addView(makeEmojiKey(em)) }
        emojiContent.addView(r3)

        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(emojiContent)
        }
        panel.addView(scrollView)

        return panel
    }

    private fun makeEmojiKey(emoji: String): TextView {
        val resting = keyBackground(keyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = emoji
            textSize = getEmojiFontSize()
            gravity = Gravity.CENTER
            val size = dp(getRowHeightDp() - 6)
            layoutParams = LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) {
                currentInputConnection?.commitText(emoji, 1)
            }
        }
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }

        when (currentMode) {
            Mode.SYMBOLS -> {
                row.addView(make123Key("ABC", weight = 1.4f) { switchMode(Mode.LETTERS) })
                row.addView(makeSpecialKey("123", weight = 1.1f) { switchMode(Mode.NUMBERS) })
            }
            Mode.EMOJI -> {
                row.addView(make123Key("ABC", weight = 1.5f) { switchMode(Mode.LETTERS) })
            }
            else -> {
                row.addView(make123Key("?123", weight = 1.5f) { switchMode(Mode.NUMBERS) })
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

    // ---------- dedicated numbers page (matching Gboard screenshot) ----------

    private fun buildNumbersView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 1. Upper 3 rows block (Left math column + Right 3x3 numbers & actions)
        val upperBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp() * 3))
        }

        // Left column: (+ - *) box spanning 2 rows + (/) key spanning 1 row
        upperBlock.addView(buildMathOperatorColumn())

        // Right 3-row layout
        val rightLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 6.75f)
        }

        // Row 1: 1 2 3 %
        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        r1.addView(makeNumberKey("1", weight = 1.8f) { commitLetter("1") })
        r1.addView(makeNumberKey("2", weight = 1.8f) { commitLetter("2") })
        r1.addView(makeNumberKey("3", weight = 1.8f) { commitLetter("3") })
        r1.addView(makeNumberSpecialKey("%", weight = 1.35f) { commitSymbol("%") })
        rightLayout.addView(r1)

        // Row 2: 4 5 6 ␣
        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        r2.addView(makeNumberKey("4", weight = 1.8f) { commitLetter("4") })
        r2.addView(makeNumberKey("5", weight = 1.8f) { commitLetter("5") })
        r2.addView(makeNumberKey("6", weight = 1.8f) { commitLetter("6") })
        r2.addView(makeNumberSpaceKey(weight = 1.35f))
        rightLayout.addView(r2)

        // Row 3: 7 8 9 ⌫
        val r3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        r3.addView(makeNumberKey("7", weight = 1.8f) { commitLetter("7") })
        r3.addView(makeNumberKey("8", weight = 1.8f) { commitLetter("8") })
        r3.addView(makeNumberKey("9", weight = 1.8f) { commitLetter("9") })
        r3.addView(makeBackspaceKey(weight = 1.35f))
        rightLayout.addView(r3)

        upperBlock.addView(rightLayout)
        container.addView(upperBlock)

        // 2. Row 4: ABC , !?# 0 = . ↵
        val r4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        r4.addView(makeSpecialKey("ABC", weight = 1.25f) { switchMode(Mode.LETTERS) })
        r4.addView(makeSpecialKey(",", weight = 0.85f) { commitPunctuationOrSpace(",") })
        r4.addView(makeSpecialKey("!?#", weight = 1.15f) { switchMode(Mode.SYMBOLS) })
        r4.addView(makeNumberKey("0", weight = 1.8f) { commitLetter("0") })
        r4.addView(makeSpecialKey("=", weight = 0.95f) { commitSymbol("=") })
        r4.addView(makeSpecialKey(".", weight = 0.85f) { commitPunctuationOrSpace(".") })
        r4.addView(makeEnterKey(weight = 1.35f))

        container.addView(r4)

        return container
    }

    private fun buildMathOperatorColumn(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f)
        }

        // Top box spanning 2 rows
        val topBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp() * 2))
            val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
            background = resting
        }

        val opsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        listOf("+", "-", "*").forEach { op ->
            val tv = TextView(this).apply {
                text = op
                gravity = Gravity.CENTER
                setTextColor(textColor())
                setTypeface(Typeface.DEFAULT_BOLD)
                textSize = 21f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                applyKeyTouchBehavior(this, pressHighlightColor(), null, KEY_RADIUS_DP) {
                    commitSymbol(op)
                }
            }
            opsLayout.addView(tv)
        }

        val scrollTrack = View(this).apply {
            val trackBg = GradientDrawable().apply {
                setColor(if (isDarkMode()) Color.parseColor("#5A5D60") else Color.parseColor("#BDC1C6"))
                cornerRadius = dp(2).toFloat()
            }
            background = trackBg
            val w = dp(2)
            layoutParams = FrameLayout.LayoutParams(w, dp(34)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = dp(3)
            }
        }

        topBox.addView(opsLayout)
        topBox.addView(scrollTrack)
        col.addView(topBox)

        // Bottom box (Row 3): '/' key
        val divKey = TextView(this).apply {
            text = "/"
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 21f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
            val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) {
                commitSymbol("/")
            }
        }
        col.addView(divKey)

        return col
    }

    private fun makeNumberKey(label: String, weight: Float, onClick: () -> Unit): TextView {
        val resting = keyBackground(keyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = getLetterFontSize() + 3f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        }
    }

    private fun makeNumberSpecialKey(label: String, weight: Float, onClick: () -> Unit): TextView {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 21f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        }
    }

    private fun makeNumberSpaceKey(weight: Float): View {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
        }
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_space_bar)
            setColorFilter(textColor())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val iconSize = dp(20)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        container.addView(iv)
        applyKeyTouchBehavior(container, pressHighlightColor(), resting, KEY_RADIUS_DP) {
            commitPunctuationOrSpace(" ")
        }
        return container
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
            textSize = getSpecialKeyFontSize()
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
            textSize = getSpecialKeyFontSize()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        }
    }

    /**
     * Determines whether the Enter key currently acts as a search / action button or as a newline return.
     */
    private fun isEnterActingAsSearch(): Boolean {
        val info = currentInputEditorInfo
        val inputType = info?.inputType ?: 0
        val isMultiLineField = (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE) != 0 ||
                (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

        val behavior = covertManager.enterKeyBehavior
        return when (behavior) {
            "newline_only" -> false
            "search_only" -> true
            "auto_field" -> !isMultiLineField
            else -> { // "auto_effect" (Default)
                // If effect requires multiple lines (Math list, Line text peek, Covert typing), it must act as enter only
                val requiresMultiLineEffect = (covertManager.isCovertActive) ||
                        (covertManager.isMathEnabled) ||
                        (covertManager.isTextPeekEnabled && (covertManager.textPeekMode == "line" || covertManager.textPeekMode == "cursor_line" || covertManager.textPeekMode == "last_word"))

                if (requiresMultiLineEffect) {
                    false
                } else if (covertManager.isTextReplaceEnabled) {
                    // API Text replace does not require multi-lines -> click search
                    true
                } else {
                    !isMultiLineField
                }
            }
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
        val isSearch = isEnterActingAsSearch()
        val glyph = if (isSearch) GlyphIconView.Glyph.SEARCH else GlyphIconView.Glyph.RETURN
        val icon = GlyphIconView(this, glyph).apply {
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

    /** Backspace key: a normal tap deletes one character, holding continuously deletes (auto-repeat),
     *  and dragging left performs swipe-to-delete-more. Supports deleting selected text (Select All). */
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

        val repeatHandler = Handler(Looper.getMainLooper())
        var down = false
        var startX = 0f
        var deletedSteps = 0
        val stepPx = dp(16)
        var isSwiping = false
        var repeatCount = 0

        lateinit var repeatRunnable: Runnable
        repeatRunnable = Runnable {
            if (down && !isSwiping) {
                repeatCount++
                deleteChar()
                container.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                // Accelerate deletion speed smoothly as user continues holding
                val nextDelay = if (repeatCount > 15) 30L else if (repeatCount > 5) 45L else 60L
                repeatHandler.postDelayed(repeatRunnable, nextDelay)
            }
        }

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = true
                    startX = event.rawX
                    deletedSteps = 0
                    isSwiping = false
                    repeatCount = 0
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = pressedBg
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(45).start()

                    deleteChar()
                    deletedSteps = 1

                    // Schedule repeat if user holds down the delete button (350ms initial delay)
                    repeatHandler.removeCallbacks(repeatRunnable)
                    repeatHandler.postDelayed(repeatRunnable, 350L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (down) {
                        val draggedLeft = startX - event.rawX
                        // If user swipes left, cancel auto-repeat and switch to swipe-step deletion
                        if (draggedLeft > dp(12)) {
                            if (!isSwiping) {
                                isSwiping = true
                                repeatHandler.removeCallbacks(repeatRunnable)
                            }
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
                    isSwiping = false
                    repeatHandler.removeCallbacks(repeatRunnable)
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
            textSize = getSpecialKeyFontSize()
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
        val ic = currentInputConnection ?: return

        // 1. Check if there is an active selection (e.g., Select All or highlighted text)
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            val chunk = selectedText.toString()
            DeletePeekMemory.recordDeletedChunk(chunk, this, covertManager)
            if (covertManager.isCovertActive) {
                val textBeforeCursor = ic.getTextBeforeCursor(4000, 0)
                covertManager.handleBackspace(textBeforeCursor)
            }
            // In Android InputConnection, commitText("", 1) replaces the selection with empty text (deleting it)
            val committed = ic.commitText("", 1)
            if (!committed) {
                // Fallback: send hardware DEL key events to delete the selection
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            wordBuffer.clear()
            refreshTopBar()
            return
        }

        // 2. Normal deletion of 1 character before the cursor
        val textBefore = ic.getTextBeforeCursor(1, 0)
        if (!textBefore.isNullOrEmpty()) {
            val charDeleted = textBefore[0]
            DeletePeekMemory.recordDeletedChar(charDeleted, this, covertManager)
        }
        if (covertManager.isCovertActive) {
            val textBeforeCursor = ic.getTextBeforeCursor(4000, 0)
            covertManager.handleBackspace(textBeforeCursor)
        }
        val deleted = ic.deleteSurroundingText(1, 0)
        if (!deleted) {
            // Fallback: send hardware DEL key event
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
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
        val textBefore = currentInputConnection?.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        val textAfter = currentInputConnection?.getTextAfterCursor(1000, 0)?.toString() ?: ""
        if (covertManager.isMathEnabled) {
            val payload = covertManager.extractMathPayload(textBefore)
            if (payload != null) {
                TriggerManager.queueMathPayload(payload, this, covertManager)
            }
        }
        if (covertManager.isTextPeekEnabled) {
            val peekPayload = covertManager.extractTextPeekPayload(textBefore, textAfter)
            if (peekPayload != null) {
                TriggerManager.queueTextPeek(peekPayload, this, covertManager)
            }
        }

        val ic = currentInputConnection
        val info = currentInputEditorInfo
        val isSearch = isEnterActingAsSearch()

        if (isSearch) {
            val rawAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
            val action = if (rawAction != EditorInfo.IME_ACTION_NONE && rawAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
                rawAction
            } else {
                EditorInfo.IME_ACTION_SEARCH
            }
            val performed = ic?.performEditorAction(action) ?: false
            if (!performed) {
                // If IME action didn't trigger, send hardware ENTER / DPAD_CENTER key event
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        } else {
            ic?.commitText("\n", 1)
        }
        refreshTopBar()
    }

    /**
     * Replaces the configured placeholder (e.g. "--value--") in the active text field
     * with the remote data received from the API or pre-saved custom text.
     * If the placeholder is empty/blank, replaces ALL text in the writing area.
     */
    private fun executeRemoteTextReplacement(cm: CovertManager): Boolean {
        val ic = currentInputConnection ?: return false
        val placeholder = cm.replacePlaceholder.trim()
        val replacement = cm.getEffectiveReplacementValue().trim()
        if (replacement.isEmpty()) return false

        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(1000, 0)?.toString() ?: ""

        // If placeholder field was left empty, replace ALL text in the writing area
        if (placeholder.isEmpty()) {
            val totalBefore = before.length
            val totalAfter = after.length
            if (totalBefore > 0 || totalAfter > 0) {
                ic.deleteSurroundingText(totalBefore, totalAfter)
            }
            ic.commitText(replacement, 1)
            wordBuffer.clear()
            refreshTopBar()
            return true
        }

        if (before.contains(placeholder)) {
            val idx = before.lastIndexOf(placeholder)
            val charsToStartOfPlaceholder = before.length - idx
            val suffix = before.substring(idx + placeholder.length)

            ic.deleteSurroundingText(charsToStartOfPlaceholder, 0)
            ic.commitText(replacement + suffix, 1)
            wordBuffer.clear()
            refreshTopBar()
            return true
        } else if (placeholder.isNotEmpty() && after.contains(placeholder)) {
            val idx = after.indexOf(placeholder)
            val charsToDeleteAfter = idx + placeholder.length
            val prefixAfterMatch = after.substring(0, idx)
            val suffixAfterMatch = after.substring(idx + placeholder.length)

            ic.deleteSurroundingText(0, charsToDeleteAfter)
            ic.commitText(prefixAfterMatch + replacement + suffixAfterMatch, 1)
            wordBuffer.clear()
            refreshTopBar()
            return true
        } else if (placeholder.isNotEmpty() && (before + after).contains(placeholder)) {
            val combined = before + after
            val idx = combined.indexOf(placeholder)
            if (idx != -1) {
                val deleteBefore = (before.length - idx).coerceAtLeast(0)
                val deleteAfter = ((idx + placeholder.length) - before.length).coerceAtLeast(0)
                ic.deleteSurroundingText(deleteBefore, deleteAfter)
                ic.commitText(replacement, 1)
                wordBuffer.clear()
                refreshTopBar()
                return true
            }
        } else {
            // If placeholder not found but text area is empty, insert the replacement
            if (before.isEmpty() && after.isEmpty()) {
                ic.commitText(replacement, 1)
                wordBuffer.clear()
                refreshTopBar()
                return true
            }
        }
        return false
    }

    /**
     * Called immediately after text replacement succeeds to auto-click search if configured.
     */
    private fun triggerSearchAfterReplacement() {
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        val rawAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val action = if (rawAction != EditorInfo.IME_ACTION_NONE && rawAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
            rawAction
        } else {
            EditorInfo.IME_ACTION_SEARCH
        }
        val performed = ic.performEditorAction(action)
        if (!performed) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (TriggerManager.isVolumeTriggerEnabled(this) &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val textBefore = currentInputConnection?.getTextBeforeCursor(4000, 0)?.toString() ?: ""
            if (covertManager.isMathEnabled && TriggerManager.pendingMathPayload == null) {
                val payload = covertManager.extractMathPayload(textBefore)
                if (payload != null) {
                    TriggerManager.pendingMathPayload = payload
                }
            }
            if (covertManager.isTextPeekEnabled && TriggerManager.pendingTextPeekPayload == null) {
                val textAfter = currentInputConnection?.getTextAfterCursor(1000, 0)?.toString() ?: ""
                val peek = covertManager.extractTextPeekPayload(textBefore, textAfter)
                if (peek != null) {
                    TriggerManager.pendingTextPeekPayload = peek
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
private class GlyphIconView(context: Context, var glyph: Glyph) : View(context) {
    enum class Glyph { RETURN, SEARCH, BACKSPACE, SHIFT }

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
            Glyph.SEARCH -> {
                // Flat magnifying glass search icon
                val cx = w * 0.42f
                val cy = h * 0.42f
                val radius = w * 0.22f
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(cx, cy, radius, paint)
                val handleStartX = cx + radius * 0.707f
                val handleStartY = cy + radius * 0.707f
                val handleEndX = w * 0.78f
                val handleEndY = h * 0.78f
                canvas.drawLine(handleStartX, handleStartY, handleEndX, handleEndY, paint)
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
