package com.example.customkeyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CustomKeyboardService : InputMethodService() {

    private enum class Lang { EN, AR }
    private enum class Mode { LETTERS, SYMBOLS, EMOJI, CLIPBOARD }

    private var currentLang = Lang.EN
    private var currentMode = Mode.LETTERS
    private var shiftOn = false
    private var capsLock = false
    private var lastShiftTapTime = 0L
    private val wordBuffer = StringBuilder()

    private lateinit var rootContainer: LinearLayout
    private lateinit var topBarContainer: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipHistory: ClipboardHistory

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
    private fun accentColor() = Color.parseColor("#8AB4F8")
    private fun pressHighlightColor() = if (isDarkMode()) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")
    private fun enterIconColor() = Color.parseColor("#1B1C1E")

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
        rootContainer.setPadding(dp(4), dp(6), dp(4), dp(4))

        topBarContainer = buildTopBar()
        rootContainer.addView(topBarContainer)

        when (currentMode) {
            Mode.CLIPBOARD -> rootContainer.addView(buildClipboardPanel())
            Mode.EMOJI -> {
                rootContainer.addView(buildRow(KeyboardLayoutData.emojiRows[0], isEmoji = true))
                rootContainer.addView(buildRow(KeyboardLayoutData.emojiRows[1], isEmoji = true))
                rootContainer.addView(buildRow(KeyboardLayoutData.emojiRows[2], isEmoji = true))
            }
            Mode.SYMBOLS -> {
                rootContainer.addView(buildRow(KeyboardLayoutData.numberRow))
                rootContainer.addView(buildRow(KeyboardLayoutData.symbolsRows[0]))
                rootContainer.addView(buildRow(KeyboardLayoutData.symbolsRows[1]))
                rootContainer.addView(buildRow(KeyboardLayoutData.symbolsRows[2]))
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
        render()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- top bar: suggestions / clipboard / settings ----------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(4))
        }

        when {
            currentMode == Mode.CLIPBOARD -> {
                bar.addView(TextView(this).apply {
                    text = "Clipboard"
                    setTextColor(textColor())
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                bar.addView(iconButton("←") { switchMode(Mode.LETTERS) })
            }
            currentLang == Lang.EN && currentMode == Mode.LETTERS && wordBuffer.isNotEmpty() -> {
                val suggestions = Dictionary.suggestions(wordBuffer.toString(), 3)
                if (suggestions.isEmpty()) {
                    bar.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                } else {
                    suggestions.forEach { word -> bar.addView(suggestionChip(word)) }
                }
                bar.addView(iconButton("⧉") { switchMode(Mode.CLIPBOARD) })
            }
            else -> {
                bar.addView(TextView(this).apply {
                    text = ""
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
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

    private fun suggestionChip(word: String): TextView {
        return TextView(this).apply {
            text = word
            setTextColor(textColor())
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            applyKeyTouchBehavior(this, pressHighlightColor()) {
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
            val size = dp(32)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
            applyKeyTouchBehavior(this, pressHighlightColor()) { onClick() }
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(8)
            }
        }
        val fontSize = if (isEmoji || isLetterRow) 22f else 18f
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(8)
            }
        }
        row.addView(spacer(0.5f))
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, fontSize = 22f) { commitLetter(display) })
        }
        row.addView(spacer(0.5f))
        return row
    }

    private fun buildLetterRowWithShiftAndBackspace(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(8)
            }
        }
        if (currentLang == Lang.EN) {
            row.addView(makeSpecialKey(if (capsLock) "⇪" else "⇧", weight = 1.5f, textHighlighted = shiftOn || capsLock) {
                onShiftTapped()
            })
        }
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, fontSize = 22f) { commitLetter(display) })
        }
        row.addView(makeSpecialKey("⌫", weight = 1.5f) { deleteChar() })
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(8)
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
        row.addView(makeSpecialKey(spaceLabel, weight = 4f) { commitPunctuationOrSpace(" ") })

        row.addView(makeSpecialKey(".", weight = 1f) { commitPunctuationOrSpace(".") })
        row.addView(makeSpecialKey("⏎", weight = 1.5f, filled = true) { handleEnter() })

        return row
    }

    private fun spacer(weight: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }
    }

    // ---------- key factories ----------

    private fun makeKey(label: String, weight: Float, fontSize: Float = 20f, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            textSize = fontSize
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            }
            applyKeyTouchBehavior(this, pressHighlightColor()) { onClick() }
        }
    }

    private fun makeSpecialKey(
        label: String,
        weight: Float,
        textHighlighted: Boolean = false,
        filled: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(
                when {
                    filled -> enterIconColor()
                    textHighlighted -> accentColor()
                    else -> textColor()
                }
            )
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            val resting = if (filled) roundedDrawable(accentColor(), radiusDp = 26) else null
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting) { onClick() }
        }
    }

    private fun roundedDrawable(color: Int, radiusDp: Int = 10): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun applyKeyTouchBehavior(view: TextView, pressColor: Int, restingBackground: GradientDrawable? = null, onTap: () -> Unit) {
        view.isClickable = true
        view.isHapticFeedbackEnabled = true
        var pressed = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = roundedDrawable(pressColor)
                    v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(45).start()
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

    private fun commitPunctuationOrSpace(boundary: String) {
        autocorrectPendingWord()
        currentInputConnection?.commitText(boundary, 1)
        wordBuffer.clear()
        refreshTopBar()
    }

    private fun autocorrectPendingWord() {
        if (currentLang != Lang.EN || wordBuffer.isEmpty()) return
        val word = wordBuffer.toString()
        if (Dictionary.contains(word)) return
        val correction = Dictionary.closestMatch(word) ?: return
        currentInputConnection?.deleteSurroundingText(word.length, 0)
        currentInputConnection?.commitText(correction, 1)
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
        autocorrectPendingWord()
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
