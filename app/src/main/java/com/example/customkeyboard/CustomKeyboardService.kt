package com.example.customkeyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
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

    private lateinit var rootContainer: LinearLayout
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

    private fun bgColor() = if (isDarkMode()) Color.parseColor("#1B1C1E") else Color.parseColor("#E3E5E8")
    private fun keyColor() = if (isDarkMode()) Color.parseColor("#33363B") else Color.WHITE
    private fun specialKeyColor() = if (isDarkMode()) Color.parseColor("#2B2D30") else Color.parseColor("#C4C7C9")
    private fun textColor() = if (isDarkMode()) Color.parseColor("#F5F5F5") else Color.parseColor("#1B1C1E")
    private fun accentColor() = Color.parseColor("#8AB4F8")

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
        rootContainer.removeAllViews()
        rootContainer.setBackgroundColor(bgColor())
        rootContainer.setPadding(dp(4), dp(6), dp(4), dp(4))

        rootContainer.addView(buildTopBar())

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
                rootContainer.addView(buildRow(rows[0], applyShift = currentLang == Lang.EN))
                rootContainer.addView(buildLetterRowWithShift(rows[1]))
                rootContainer.addView(buildLetterRowWithShiftAndBackspace(rows[2]))
            }
        }

        if (currentMode != Mode.CLIPBOARD) {
            rootContainer.addView(buildBottomRow())
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- top bar: clipboard + settings ----------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(4))
        }

        val label = TextView(this).apply {
            text = if (currentMode == Mode.CLIPBOARD) "Clipboard" else ""
            setTextColor(textColor())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(label)

        if (currentMode == Mode.CLIPBOARD) {
            bar.addView(iconButton("←") {
                currentMode = Mode.LETTERS
                render()
            })
        } else {
            bar.addView(iconButton("⧉") {
                currentMode = Mode.CLIPBOARD
                render()
            })
            bar.addView(iconButton("⚙") {
                val intent = android.content.Intent(this, MainActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            })
        }
        return bar
    }

    private fun iconButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            setTextColor(textColor())
            textSize = 16f
            gravity = Gravity.CENTER
            val size = dp(32)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
    }

    // ---------- clipboard panel ----------

    private fun buildClipboardPanel(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
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
                        currentMode = Mode.LETTERS
                        render()
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
            val clearAll = TextView(this).apply {
                text = "Clear all"
                setTextColor(accentColor())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    clipHistory.clear()
                    render()
                }
            }
            list.addView(clearAll)
        }
        scroll.addView(list)
        return scroll
    }

    // ---------- key rows ----------

    private fun buildRow(keys: List<String>, applyShift: Boolean = false, isEmoji: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(6)
            }
        }
        keys.forEach { k ->
            val display = if (applyShift && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f, isEmoji = isEmoji) {
                if (isEmoji) {
                    currentInputConnection?.commitText(display, 1)
                } else {
                    commitChar(display)
                }
            })
        }
        return row
    }

    private fun buildLetterRowWithShift(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(6)
            }
        }
        // side padding so middle row looks staggered like a real keyboard
        row.addView(spacer(0.5f))
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f) { commitChar(display) })
        }
        row.addView(spacer(0.5f))
        return row
    }

    private fun buildLetterRowWithShiftAndBackspace(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(6)
            }
        }
        if (currentLang == Lang.EN) {
            row.addView(makeSpecialKey(if (capsLock) "⇪" else "⇧", weight = 1.5f, highlighted = shiftOn || capsLock) {
                onShiftTapped()
            })
        } else {
            row.addView(spacer(1.5f))
        }
        keys.forEach { k ->
            val display = if (currentLang == Lang.EN && (shiftOn || capsLock)) k.uppercase() else k
            row.addView(makeKey(display, weight = 1f) { commitChar(display) })
        }
        row.addView(makeSpecialKey("⌫", weight = 1.5f) { deleteChar() })
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(6)
            }
        }

        if (currentMode == Mode.SYMBOLS) {
            row.addView(makeSpecialKey("ABC", weight = 1.5f) {
                currentMode = Mode.LETTERS
                render()
            })
        } else if (currentMode == Mode.EMOJI) {
            row.addView(makeSpecialKey("ABC", weight = 1.5f) {
                currentMode = Mode.LETTERS
                render()
            })
        } else {
            row.addView(makeSpecialKey("?123", weight = 1.5f) {
                currentMode = Mode.SYMBOLS
                render()
            })
            row.addView(makeSpecialKey("🙂", weight = 1f) {
                currentMode = Mode.EMOJI
                render()
            })
        }

        row.addView(makeSpecialKey("🌐", weight = 1f) { switchLanguage() })

        val spaceLabel = if (currentLang == Lang.EN) "English" else "العربية"
        row.addView(makeSpecialKey(spaceLabel, weight = 4f) {
            currentInputConnection?.commitText(" ", 1)
        })

        row.addView(makeSpecialKey(".", weight = 1f) { commitChar(".") })
        row.addView(makeSpecialKey("⏎", weight = 1.5f, highlighted = true) { handleEnter() })

        return row
    }

    private fun spacer(weight: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }
    }

    // ---------- key factories ----------

    private fun makeKey(label: String, weight: Float, isEmoji: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            textSize = if (isEmoji) 20f else 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            background = roundedDrawable(keyColor())
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
    }

    private fun makeSpecialKey(label: String, weight: Float, highlighted: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (highlighted) accentColor() else textColor())
            textSize = 15f
            typeface = Typeface.DEFAULT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            background = roundedDrawable(specialKeyColor())
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
    }

    private fun roundedDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
        }
    }

    // ---------- input actions ----------

    private fun commitChar(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (shiftOn && !capsLock) {
            shiftOn = false
            render()
        }
    }

    private fun deleteChar() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun onShiftTapped() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 300) {
            // double tap -> caps lock
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
        render()
    }

    private fun handleEnter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (info != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }
}
