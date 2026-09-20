package com.example.customkeyboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

class CustomKeyboardService : InputMethodService() {

    private enum class Lang { EN, AR }
    private enum class Mode { LETTERS, NUMBERS, SYMBOLS, EMOJI, CLIPBOARD }

    private var currentLang = Lang.EN
    private var currentMode = Mode.LETTERS
    private var lastAltMode = Mode.NUMBERS
    private var shiftOn = false
    private var capsLock = false
    private var lastShiftTapTime = 0L
    private var symbolsPage = 1
    private val wordBuffer = StringBuilder()
    private var lastCommittedWord = ""
    private var selectedClipboardEffectTab = "covert"

    // In-bar voice typing state
    private var isVoiceListening = false
    private var voicePreviewText = ""
    private var speechRecognizer: SpeechRecognizer? = null
    private val voiceHandler = Handler(Looper.getMainLooper())
    private var voiceTimeoutRunnable: Runnable? = null

    private lateinit var rootOverlayContainer: FrameLayout
    private lateinit var rootContainer: LinearLayout
    private lateinit var topBarContainer: ViewGroup
    private var keyPopupManager: KeyPopupPreviewManager? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipHistory: ClipboardHistory
    private lateinit var covertManager: CovertManager

    // Clipboard & Screenshot suggestion state
    private var pendingClipText: String? = null
    private var pendingClipTime: Long = 0L
    private var lastPastedClipText: String? = null
    private var pendingScreenshotUri: Uri? = null
    private var pendingScreenshotTime: Long = 0L
    private var userStartedTyping: Boolean = false
    private var screenshotObserver: ContentObserver? = null

    // Track active selection bounds
    private var currentSelStart = 0
    private var currentSelEnd = 0

    // ---------- sizing helpers (customizable via Settings preferences) ----------
    private fun getKeyRadiusDp(): Int {
        return when (getThemeMode()) {
            "liquid_glass" -> 6 // Apple iOS rounded key radius
            "material_you" -> 9 // Material 3 squircle key radius
            else -> 8
        }
    }

    private val KEY_RADIUS_DP: Int
        get() = getKeyRadiusDp()

    private val PILL_RADIUS_DP = 24
    private val ICON_GLYPH_DP = 30
    private val KEY_INSET_V_DP = 4
    private val baselineArabicLetters = setOf("ط", "ك", "ف", "ث", "ا", "ة", "ظ", "د", "ب", "ت", "ذ", "ه", "ء")

    private fun getKeyInsetHDp(): Int {
        return when (prefs.getString("button_width", "wide")) {
            "standard" -> 2
            else -> 1 // "wide" default: wider buttons, reduced gap for fewer miss-types
        }
    }

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
        val isSystemFont = prefs.getString("font_style", "bold") == "system"
        val baseSize = when (prefs.getString("key_font_size", "normal")) {
            "small" -> 19f
            "large" -> 26.5f
            "extra_large" -> 30.5f
            else -> 23f
        }
        return if (isSystemFont) baseSize + 1f else baseSize
    }

    private fun getSuggestionFontSize(): Float {
        val isSystemFont = prefs.getString("font_style", "bold") == "system"
        val baseSize = when (prefs.getString("key_font_size", "normal")) {
            "small" -> 15f
            "large" -> 19.5f
            "extra_large" -> 22f
            else -> 17f
        }
        return if (isSystemFont) baseSize + 1f else baseSize
    }

    private fun getSymbolFontSize(): Float {
        val isSystemFont = prefs.getString("font_style", "bold") == "system"
        val baseSize = when (prefs.getString("key_font_size", "normal")) {
            "small" -> 18f
            "large" -> 26f
            "extra_large" -> 30f
            else -> 22.5f
        }
        return if (isSystemFont) baseSize + 1.5f else baseSize
    }

    private fun getSpecialKeyFontSize(): Float {
        val isSystemFont = prefs.getString("font_style", "bold") == "system"
        val baseSize = when (prefs.getString("key_font_size", "normal")) {
            "small" -> 13.5f
            "large" -> 17.5f
            "extra_large" -> 20f
            else -> 15f
        }
        return if (isSystemFont) baseSize + 1f else baseSize
    }

    private fun getHintFontSize(isArabic: Boolean = (currentLang == Lang.AR)): Float {
        return when (prefs.getString("key_font_size", "normal")) {
            "small" -> if (isArabic) 6.5f else 7.0f
            "large" -> if (isArabic) 8.0f else 8.5f
            "extra_large" -> if (isArabic) 8.5f else 9.5f
            else -> if (isArabic) 7.5f else 8.0f
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
            val item = clip.getItemAt(0)
            val uri = item.uri
            if (uri != null && (clip.description?.hasMimeType("image/*") == true || uri.toString().contains("image", ignoreCase = true))) {
                pendingScreenshotUri = uri
                pendingScreenshotTime = System.currentTimeMillis()
                userStartedTyping = false
                refreshTopBar()
            } else {
                val text = item.coerceToText(this).toString()
                if (text.isNotBlank()) {
                    clipHistory.add(text)
                    pendingClipText = text
                    pendingClipTime = System.currentTimeMillis()
                    userStartedTyping = false
                    refreshTopBar()
                }
            }
        }
    }

    private fun registerScreenshotObserver() {
        try {
            screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    checkRecentScreenshot()
                }
            }
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                screenshotObserver!!
            )
        } catch (e: Exception) {
            // Ignore observer failure
        }
    }

    private fun checkRecentScreenshot() {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                    val id = it.getLong(idCol)
                    val dateAddedSec = it.getLong(dateCol)
                    val name = it.getString(nameCol) ?: ""
                    val nowSec = System.currentTimeMillis() / 1000

                    if ((nowSec - dateAddedSec) in 0..300) {
                        val isScreenshot = name.contains("screenshot", ignoreCase = true) ||
                                           name.contains("capture", ignoreCase = true) ||
                                           name.startsWith("Screenshot", ignoreCase = true)
                        if (isScreenshot) {
                            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            if (uri != pendingScreenshotUri) {
                                pendingScreenshotUri = uri
                                pendingScreenshotTime = dateAddedSec * 1000
                                if (!userStartedTyping) {
                                    refreshTopBar()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Permission not granted or query error
        }
    }

    private fun checkPrimaryClipOnInputStart() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val uri = item.uri
                if (uri != null && (clip.description?.hasMimeType("image/*") == true || uri.toString().contains("image", ignoreCase = true))) {
                    pendingScreenshotUri = uri
                    pendingScreenshotTime = System.currentTimeMillis()
                } else {
                    val text = item.coerceToText(this)?.toString()
                    if (!text.isNullOrBlank() && text != lastPastedClipText) {
                        pendingClipText = text
                        pendingClipTime = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun notifyUserTypingAction() {
        if (!userStartedTyping) {
            userStartedTyping = true
            val hadPending = pendingClipText != null || pendingScreenshotUri != null
            pendingClipText = null
            pendingScreenshotUri = null
            if (hadPending) {
                refreshTopBar()
            }
        }
    }

    private var lastReplacedValue: String = ""

    companion object {
        var activeInstance: CustomKeyboardService? = null
    }

    fun refreshKeyboardSettings() {
        if (::rootContainer.isInitialized) {
            render()
        }
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "key_font_size", "font_style", "keyboard_height", "theme_override", "button_width", "key_popup_preview" -> {
                if (::rootContainer.isInitialized) {
                    render()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        val savedAltMode = prefs.getString("last_alt_mode", Mode.NUMBERS.name)
        lastAltMode = try {
            val m = Mode.valueOf(savedAltMode ?: Mode.NUMBERS.name)
            if (m == Mode.NUMBERS || m == Mode.SYMBOLS) m else Mode.NUMBERS
        } catch (e: Exception) {
            Mode.NUMBERS
        }
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
                // If enter behavior is set to auto_effect or search_only, automatically click search / confirm
                if (cm.enterKeyBehavior == "auto_effect" || cm.enterKeyBehavior == "search_only") {
                    if (CovertAccessibilityService.isAccessibilityServiceEnabled(this)) {
                        CovertAccessibilityService.scheduleConfirmationClicks()
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed({
                            triggerSearchAfterReplacement()
                        }, 120L)
                    }
                }
            }
            success
        }
        Dictionary.init(this)
        registerScreenshotObserver()
        checkRecentScreenshot()
    }

    override fun onDestroy() {
        stopVoiceTyping(cancel = true)
        if (activeInstance == this) activeInstance = null
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        }
        clipboardManager.removePrimaryClipChangedListener(systemClipListener)
        screenshotObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {}
        }
        TriggerManager.stopActiveSession(this)
        TriggerManager.onCaptureLiveCursorContext = null
        TriggerManager.onCaptureLiveText = null
        TriggerManager.onExecuteTextReplacement = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Disallow extracted fullscreen mode to ensure smooth insets animations and prevent CUJ timeouts
        return false
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!isFullscreenMode) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        TriggerManager.startActiveSession(this)
        if (covertManager.isTextReplaceEnabled || (covertManager.isCovertActive && covertManager.covertMode == "reveal")) {
            covertManager.fetchLatestApiValue()
        }
        // Avoid calling render() here; view hierarchy is already ready and rebuilding here drops animation frames
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopVoiceTyping(cancel = false)
        keyPopupManager?.hidePopup(immediate = true)
        // Keep trigger session alive so triggers work even if spectator dismissed keyboard
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopVoiceTyping(cancel = false)
        keyPopupManager?.hidePopup(immediate = true)
        // Keep trigger session alive so triggers work even if spectator dismissed keyboard
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Avoid redundant render() during input initialization
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        userStartedTyping = false
        checkPrimaryClipOnInputStart()
        checkRecentScreenshot()
        lastReplacedValue = ""
        TriggerManager.startActiveSession(this)
        if (covertManager.isTextReplaceEnabled || (covertManager.isCovertActive && covertManager.covertMode == "reveal")) {
            covertManager.fetchLatestApiValue()
        }
        if (TriggerManager.isDelayTriggerEnabled(this) && (TriggerManager.hasPendingPayload() || covertManager.isTextReplaceEnabled)) {
            TriggerManager.scheduleDelayTrigger(this, "Input View Started")
        }
        // Automatically open the keyboard to the numbers page when the field only accepts numbers
        val inputType = info?.inputType ?: 0
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val isNumericField = inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME

        val targetMode = if (isNumericField) Mode.NUMBERS else Mode.LETTERS
        val modeChanged = currentMode != targetMode
        currentMode = targetMode
        shiftOn = false
        capsLock = false
        symbolsPage = 1
        wordBuffer.clear()
        val textBeforeRaw = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: ""
        if (textBeforeRaw.isEmpty()) {
            covertManager.resetSession()
        } else if (covertManager.isCovertActive) {
            covertManager.syncSessionWithText(textBeforeRaw)
        }
        val textBefore = textBeforeRaw.trim()
        lastCommittedWord = textBefore.split(Regex("\\s+")).lastOrNull { it.isNotEmpty() } ?: ""

        if (!::rootContainer.isInitialized || rootContainer.childCount == 0 || modeChanged) {
            render()
        } else {
            refreshTopBar()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        currentSelStart = newSelStart
        currentSelEnd = newSelEnd
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            val (currentWord, prevWord) = getActiveTypingContext()
            wordBuffer.clear()
            if (currentWord.isNotEmpty()) {
                wordBuffer.append(currentWord)
            }
            if (prevWord.isNotEmpty()) {
                lastCommittedWord = prevWord
            }
            refreshTopBar()
        }
    }

    // ---------- theming ----------

    private fun getThemeMode(): String {
        return prefs.getString("theme_override", "dark") ?: "dark"
    }

    private fun isDarkMode(): Boolean {
        return when (getThemeMode()) {
            "pitch_black", "dark", "liquid_glass" -> true
            "light" -> false
            "material_you", "system" -> {
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            else -> true
        }
    }

    private fun bgColor(): Int {
        return when (getThemeMode()) {
            "pitch_black" -> Color.parseColor("#000000") // Pure AMOLED Pitch Black
            "light" -> Color.parseColor("#E9EAED")
            else -> Color.parseColor("#1E1F21") // User requested keyboard background behind the buttons
        }
    }

    private fun textColor(): Int {
        return when (getThemeMode()) {
            "pitch_black" -> Color.parseColor("#FFFFFF")
            "light" -> Color.parseColor("#1F1F1F")
            else -> Color.parseColor("#FFFFFF") // Crisp, clear pure white as requested
        }
    }

    private fun textSecondaryColor(): Int {
        return when (getThemeMode()) {
            "pitch_black" -> Color.parseColor("#9E9E9E")
            "light" -> Color.parseColor("#757575")
            else -> Color.parseColor("#9AA0A6")
        }
    }

    // Individual key "box" colors - distinct from the keyboard background so every key reads
    // as its own tile.
    private fun keyColor(): Int {
        return when (getThemeMode()) {
            "pitch_black" -> Color.parseColor("#141414") // Pure Black AMOLED Tile
            "light" -> Color.parseColor("#FFFFFF")
            else -> Color.parseColor("#38393B") // User requested button behind the letter
        }
    }

    private fun specialKeyColor(): Int {
        return when (getThemeMode()) {
            "pitch_black" -> Color.parseColor("#212121")
            "light" -> Color.parseColor("#DFE0E6")
            else -> Color.parseColor("#38393B") // Matching screenshot keys (shift, backspace, comma, etc.)
        }
    }

    private fun pressHighlightColor(): Int {
        return when (getThemeMode()) {
            "pitch_black" -> Color.parseColor("#363636")
            "light" -> Color.parseColor("#DADCE0")
            else -> Color.parseColor("#4D4E52")
        }
    }

    // Material You dynamic accent when available (Android 12+), with a sensible fallback.
    private fun accentColor(): Int {
        val theme = getThemeMode()
        if (theme == "pitch_black") {
            return Color.parseColor("#5A95FF")
        }
        return Color.parseColor("#A8C7FA") // Soft blue matching enter pill button in screenshot
    }

    private fun fallbackAccent(): Int {
        return Color.parseColor("#A8C7FA")
    }

    private fun enterIconColor(): Int {
        if (getThemeMode() == "pitch_black") {
            return Color.parseColor("#FFFFFF")
        }
        return Color.parseColor("#041E49") // Deep navy blue matching return icon in screenshot
    }

    /** A translucent wash of the accent color, used as the shift key's background while active. */
    private fun accentTintColor(): Int {
        val c = accentColor()
        return Color.argb(70, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun getKeyTypeface(): Typeface {
        return if (prefs.getString("font_style", "bold") == "system") {
            Typeface.DEFAULT
        } else {
            Typeface.DEFAULT_BOLD
        }
    }

    private fun toggleFontStyle() {
        val current = prefs.getString("font_style", "bold") ?: "bold"
        val newStyle = if (current == "bold") "system" else "bold"
        prefs.edit().putString("font_style", newStyle).apply()
        val label = if (newStyle == "system") getString(R.string.font_switched_system) else getString(R.string.font_switched_bold)
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun applyWindowChrome() {
        val win = window?.window
        win?.navigationBarColor = bgColor()
        win?.decorView?.setBackgroundColor(bgColor())
    }

    // ---------- view construction ----------

    override fun onCreateInputView(): View {
        rootOverlayContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }
        rootOverlayContainer.addView(rootContainer)

        keyPopupManager = KeyPopupPreviewManager(
            this,
            rootOverlayContainer,
            { getThemeMode() },
            { getKeyTypeface() }
        ).apply {
            isEnabled = prefs.getBoolean("key_popup_preview", true)
        }

        render()
        return rootOverlayContainer
    }

    private fun render() {
        applyWindowChrome()
        keyPopupManager?.applyTheme()
        keyPopupManager?.isEnabled = prefs.getBoolean("key_popup_preview", true)
        rootContainer.removeAllViews()
        rootContainer.setBackgroundColor(bgColor())
        rootContainer.setPadding(dp(1), dp(3), dp(1), dp(2))

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
                if (currentLang == Lang.AR) {
                    rootContainer.addView(buildArabicNumberRow())
                    rootContainer.addView(buildArabicLetterRow(0))
                    rootContainer.addView(buildArabicLetterRow(1))
                    rootContainer.addView(buildArabicLetterRow2WithBackspace())
                } else {
                    rootContainer.addView(buildRow(KeyboardLayoutData.numberRow, isLetterRow = true))
                    rootContainer.addView(buildEnglishLetterRow0())
                    rootContainer.addView(buildEnglishLetterRow1())
                    rootContainer.addView(buildEnglishLetterRow2WithShiftAndBackspace())
                }
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
        if (mode == Mode.NUMBERS || mode == Mode.SYMBOLS) {
            lastAltMode = mode
            try {
                prefs.edit().putString("last_alt_mode", mode.name).apply()
            } catch (e: Exception) {
                // Ignore
            }
        }
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
    private fun dpF(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun isWordCharacter(c: Char): Boolean {
        if (c == '؟' || c == '،' || c == '؛') return false
        return c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-' || (c in '\u0600'..'\u06FF' && Character.isLetter(c))
    }

    class TypingContext(
        val currentWord: String,
        val previousWords: List<String>
    ) {
        operator fun component1(): String = currentWord
        operator fun component2(): String = previousWords.firstOrNull() ?: ""
        val prev1: String get() = previousWords.getOrNull(0) ?: ""
        val prev2: String get() = previousWords.getOrNull(1) ?: ""
        val prev3: String get() = previousWords.getOrNull(2) ?: ""
    }

    private fun getActiveTypingContext(): TypingContext {
        val textBefore = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString() ?: ""
        if (textBefore.isEmpty()) {
            val prevs = if (lastCommittedWord.isNotEmpty()) listOf(lastCommittedWord) else emptyList()
            return TypingContext(wordBuffer.toString(), prevs)
        }
        val lastChar = textBefore.last()
        if (lastChar.isWhitespace() || !isWordCharacter(lastChar)) {
            // Space or punctuation -> current word is empty, extract previous 2-3 words
            val words = textBefore.trim().split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
            val prevs = words.takeLast(3).reversed().toMutableList()
            if (prevs.isEmpty() && lastCommittedWord.isNotEmpty()) {
                prevs.add(lastCommittedWord)
            }
            return TypingContext("", prevs)
        } else {
            // Typing in-progress word -> extract active word prefix and preceding 2-3 words
            var i = textBefore.length - 1
            while (i >= 0 && isWordCharacter(textBefore[i])) {
                i--
            }
            val activeWord = textBefore.substring(i + 1)
            val beforeActive = textBefore.substring(0, i + 1).trim()
            val words = beforeActive.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
            val prevs = words.takeLast(3).reversed().toMutableList()
            if (prevs.isEmpty() && lastCommittedWord.isNotEmpty()) {
                prevs.add(lastCommittedWord)
            }
            return TypingContext(activeWord, prevs)
        }
    }

    // ---------- top bar: suggestions / common emojis / clipboard / settings ----------

    private fun buildTopBar(): ViewGroup {
        val isArabic = currentLang == Lang.AR
        val typingContext = getActiveTypingContext()
        val contextualSuggestions = if (currentMode == Mode.LETTERS) {
            Dictionary.getContextualSuggestions(typingContext.currentWord, typingContext.previousWords, isArabic, limit = 6)
        } else emptyList()

        val hasPendingMedia = !userStartedTyping && wordBuffer.isEmpty() && (pendingClipText != null || pendingScreenshotUri != null)

        return when {
            isVoiceListening -> {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getTopBarHeightDp()))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), 0, dp(6), dp(2))
                    addView(buildVoiceTypingBar())
                }
            }
            currentMode == Mode.CLIPBOARD -> {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getTopBarHeightDp()))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), 0, dp(6), dp(2))
                    addView(iconButton(R.drawable.ic_arrow_back, "Back") { switchMode(Mode.LETTERS) })
                    addView(TextView(this@CustomKeyboardService).apply {
                        text = "Clipboard History"
                        setTextColor(textColor())
                        setTypeface(Typeface.DEFAULT_BOLD)
                        textSize = 14f
                        setPadding(dp(8), 0, dp(8), 0)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(iconButtonText("⌫") { deleteChar() })
                }
            }
            contextualSuggestions.isNotEmpty() || hasPendingMedia -> {
                buildSuggestionsTopBar(contextualSuggestions)
            }
            else -> {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getTopBarHeightDp()))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), 0, dp(6), dp(2))
                    addView(buildStandardToolbar())
                }
            }
        }
    }

    private fun buildSuggestionsTopBar(items: List<Dictionary.SuggestionItem>): FrameLayout {
        val root = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getTopBarHeightDp()))
            setPadding(0, 0, 0, dp(2))
            clipChildren = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // 1. Full-width scrollable suggestions bar (extends behind the right-side icons)
        val suggestionsView = buildSuggestionsScroll(items)
        root.addView(suggestionsView)

        // 2. Right action group:
        //    - Fade view: strictly to the left of the mic button, smoothly fading from transparent to solid bg
        //    - Icons container: 100% solid, fully opaque background so NO words ever overlap under the mic
        val bg = bgColor()
        val rightGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.RIGHT
            )
        }

        val fadeView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.MATCH_PARENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, bg)
            )
        }
        rightGroup.addView(fadeView)

        val iconsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bg)
            setPadding(dp(2), 0, dp(6), 0)
        }

        iconsContainer.addView(iconButton(R.drawable.ic_mic, "Voice Typing") { triggerVoiceInput() })
        iconsContainer.addView(iconButton(R.drawable.ic_clipboard, "Clipboard") { switchMode(Mode.CLIPBOARD) })
        iconsContainer.addView(iconButton(R.drawable.ic_settings, "Settings") {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        })

        rightGroup.addView(iconsContainer)
        root.addView(rightGroup)
        return root
    }

    private fun buildStandardToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        bar.addView(toolbarIconButton(R.drawable.ic_mic, "Voice") {
            triggerVoiceInput()
        })
        bar.addView(toolbarIconButton(R.drawable.ic_translate, "Language") {
            switchLanguage()
        })
        bar.addView(toolbarIconButton(R.drawable.ic_font_switch, "Toggle Font Style") {
            toggleFontStyle()
        })
        bar.addView(toolbarIconButton(R.drawable.ic_settings, "Settings") {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        })
        bar.addView(toolbarIconButton(R.drawable.ic_emoji_toolbar, "Emojis") {
            switchMode(if (currentMode == Mode.EMOJI) Mode.LETTERS else Mode.EMOJI)
        })
        bar.addView(toolbarIconButton(R.drawable.ic_clipboard, "Clipboard") {
            switchMode(Mode.CLIPBOARD)
        })
        bar.addView(toolbarIconButton(R.drawable.ic_grid, "Font Quick Switch") {
            toggleFontStyle()
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

    private fun buildVoiceTypingBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }

        // Animated red/accent pulsing mic icon
        val micContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = keyBackground(Color.parseColor("#33EA4335"), 18)
            isClickable = true
        }
        val micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.parseColor("#EA4335"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
        }
        micContainer.addView(micIcon)
        micContainer.setOnClickListener {
            if (isVoiceListening) {
                restartListeningIfActive()
            } else {
                startVoiceTyping()
            }
        }
        bar.addView(micContainer)

        // Live text preview before committing to the typing field
        val previewTextView = TextView(this).apply {
            text = if (voicePreviewText.isNotEmpty()) voicePreviewText else (if (currentLang == Lang.AR) "جارٍ الاستماع... تكلّم الآن" else "Listening... Speak now")
            setTextColor(if (voicePreviewText.isNotEmpty()) textColor() else textSecondaryColor())
            textSize = 14f
            setTypeface(Typeface.DEFAULT, if (voicePreviewText.isNotEmpty()) Typeface.BOLD else Typeface.ITALIC)
            setPadding(dp(10), 0, dp(10), 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(previewTextView)

        // Language toggle pill (EN / AR) so user can switch voice typing language on the fly
        val langBtn = TextView(this).apply {
            text = if (currentLang == Lang.AR) "AR" else "EN"
            setTextColor(accentColor())
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = keyBackground(specialKeyColor(), 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                switchLanguage()
                // Restart listening with the new language
                startVoiceTyping()
            }
        }
        bar.addView(langBtn)

        // Commit button (send preview words to input connection)
        val sendBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = keyBackground(accentColor(), 18)
            val sendIcon = ImageView(this@CustomKeyboardService).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
            }
            addView(sendIcon)
            setOnClickListener {
                commitVoicePreview()
                stopVoiceTyping(cancel = false)
            }
        }
        bar.addView(sendBtn)

        // Close / cancel button
        val closeBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginStart = dp(4)
            }
            val closeIcon = ImageView(this@CustomKeyboardService).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(textColor())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
            }
            addView(closeIcon)
            setOnClickListener {
                stopVoiceTyping(cancel = true)
            }
        }
        bar.addView(closeBtn)

        return bar
    }

    fun isArabicLanguage(): Boolean = currentLang == Lang.AR

    fun commitVoiceText(text: String) {
        if (text.isNotBlank()) {
            val textToInsert = "${text.trim()} "
            currentInputConnection?.commitText(textToInsert, 1)
            voicePreviewText = text.trim()
            refreshTopBar()
        }
    }

    private fun triggerVoiceInput() {
        if (isVoiceListening) {
            commitVoicePreview()
            stopVoiceTyping(cancel = false)
            return
        }

        // 1. Check microphone runtime permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Toast.makeText(this, "Microphone permission required for voice typing", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Check if SpeechRecognizer service is available
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val intent = Intent(this, VoicePermissionActivity::class.java).apply {
                putExtra(VoicePermissionActivity.EXTRA_START_SPEECH_INTENT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            return
        }

        startVoiceTyping()
    }

    fun startVoiceTyping() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val intent = Intent(this, VoicePermissionActivity::class.java).apply {
                putExtra(VoicePermissionActivity.EXTRA_START_SPEECH_INTENT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            return
        }

        isVoiceListening = true
        voicePreviewText = ""
        resetVoiceTimeout()
        refreshTopBar()
        startListeningInternal()
    }

    private fun startListeningInternal() {
        if (!isVoiceListening) return
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (ignored: Exception) {}
        speechRecognizer = null

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        resetVoiceTimeout()
                    }

                    override fun onBeginningOfSpeech() {
                        resetVoiceTimeout()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        // User paused speaking. Keep listening session alive.
                    }

                    override fun onError(error: Int) {
                        if (!isVoiceListening) return
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_CLIENT,
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                // Keep voice bar visible and continuously listen
                                voicePreviewText = if (currentLang == Lang.AR) "تكلّم الآن..." else "Listening..."
                                refreshTopBar()
                                voiceHandler.postDelayed({
                                    if (isVoiceListening) {
                                        restartListeningIfActive()
                                    }
                                }, 350)
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                stopVoiceTyping(cancel = true)
                                val intent = Intent(this@CustomKeyboardService, VoicePermissionActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                startActivity(intent)
                            }
                            else -> {
                                voicePreviewText = if (currentLang == Lang.AR) "اضغط على الميكروفون للتحدث" else "Tap mic to speak"
                                refreshTopBar()
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val recognized = matches[0].trim()
                            if (recognized.isNotEmpty()) {
                                val ic = currentInputConnection
                                if (ic != null) {
                                    ic.commitText("$recognized ", 1)
                                }
                                voicePreviewText = recognized
                                refreshTopBar()
                            }
                        }
                        if (isVoiceListening) {
                            voiceHandler.postDelayed({ restartListeningIfActive() }, 400)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0].trim()
                            if (partial.isNotEmpty()) {
                                voicePreviewText = partial
                                refreshTopBar()
                            }
                        }
                        resetVoiceTimeout()
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val targetLangCode = if (currentLang == Lang.AR) "ar-SA" else "en-US"
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLangCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLangCode)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, targetLangCode)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }

            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            voicePreviewText = if (currentLang == Lang.AR) "اضغط على الميكروفون للتحدث" else "Tap mic to speak"
            refreshTopBar()
        }
    }

    private fun restartListeningIfActive() {
        if (!isVoiceListening) return
        startListeningInternal()
    }

    private fun resetVoiceTimeout() {
        voiceTimeoutRunnable?.let { voiceHandler.removeCallbacks(it) }
        val timeoutSeconds = prefs.getInt("voice_typing_timeout_sec", 180)
        val runnable = Runnable {
            if (isVoiceListening) {
                commitVoicePreview()
                stopVoiceTyping(cancel = false)
            }
        }
        voiceTimeoutRunnable = runnable
        voiceHandler.postDelayed(runnable, timeoutSeconds * 1000L)
    }

    private fun commitVoicePreview() {
        if (voicePreviewText.isNotBlank()) {
            val textToInsert = "$voicePreviewText "
            currentInputConnection?.commitText(textToInsert, 1)
            voicePreviewText = ""
        }
    }

    private fun stopVoiceTyping(cancel: Boolean = false) {
        voiceTimeoutRunnable?.let { voiceHandler.removeCallbacks(it) }
        voiceTimeoutRunnable = null
        if (!cancel) {
            commitVoicePreview()
        }
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (ignored: Exception) {}
        speechRecognizer = null
        isVoiceListening = false
        voicePreviewText = ""
        refreshTopBar()
    }

    private fun buildSuggestionsScroll(items: List<Dictionary.SuggestionItem>): View {
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Start margin for the first word, and end padding (145dp) so the last suggestion
            // can be scrolled fully clear of the mic, clipboard, and settings icons!
            setPadding(dp(6), 0, dp(145), 0)
            clipToPadding = false
        }

        var hasLeadingChip = false

        // 1. Show copied text or screenshot 1st if user hasn't started typing yet
        if (!userStartedTyping && wordBuffer.isEmpty()) {
            val clipText = pendingClipText
            val shotUri = pendingScreenshotUri

            if (shotUri != null && pendingScreenshotTime > pendingClipTime) {
                container.addView(buildScreenshotChip(shotUri))
                hasLeadingChip = true
                if (!clipText.isNullOrBlank()) {
                    container.addView(createSuggestionDivider())
                    container.addView(buildCopiedTextChip(clipText))
                }
            } else if (!clipText.isNullOrBlank()) {
                container.addView(buildCopiedTextChip(clipText))
                hasLeadingChip = true
                if (shotUri != null) {
                    container.addView(createSuggestionDivider())
                    container.addView(buildScreenshotChip(shotUri))
                }
            }
        }

        // 2. Regular contextual suggestions
        val topWords = items.filter { !it.isEmoji }.take(6)
        topWords.forEachIndexed { index, item ->
            if (index > 0 || hasLeadingChip) {
                container.addView(createSuggestionDivider())
            }
            container.addView(suggestionChip(item))
        }
        scroll.addView(container)
        return scroll
    }

    private fun buildCopiedTextChip(text: String): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            val normalBg = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
            val pressedBg = roundedDrawable(pressHighlightColor(), KEY_RADIUS_DP)
            val sld = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                addState(intArrayOf(), normalBg)
            }
            background = sld
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard)
            setColorFilter(textColor())
            val iconSize = dp(15)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(6)
            }
        }
        container.addView(icon)

        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        val display = if (cleanText.length > 32) cleanText.take(30) + "…" else cleanText
        val tv = TextView(this).apply {
            this.text = display
            setTextColor(textColor())
            setTypeface(getKeyTypeface())
            textSize = 13f
            isSingleLine = true
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        container.addView(tv)

        container.setOnClickListener {
            container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            currentInputConnection?.commitText(text, 1)
            lastPastedClipText = text
            pendingClipText = null
            userStartedTyping = true
            refreshTopBar()
        }

        return container
    }

    private fun buildScreenshotChip(uri: Uri): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            val normalBg = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
            val pressedBg = roundedDrawable(pressHighlightColor(), KEY_RADIUS_DP)
            val sld = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                addState(intArrayOf(), normalBg)
            }
            background = sld
            isClickable = true
            isFocusable = true
        }

        var thumbnailLoaded = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thumb = contentResolver.loadThumbnail(uri, android.util.Size(dp(26), dp(26)), null)
                if (thumb != null) {
                    val iv = ImageView(this).apply {
                        setImageBitmap(thumb)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            marginEnd = dp(6)
                        }
                        clipToOutline = true
                    }
                    container.addView(iv)
                    thumbnailLoaded = true
                }
            }
        } catch (e: Throwable) {
            // fallback
        }

        if (!thumbnailLoaded) {
            val iv = ImageView(this).apply {
                setImageResource(R.drawable.ic_grid)
                setColorFilter(textColor())
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(6)
                }
            }
            container.addView(iv)
        }

        val tv = TextView(this).apply {
            this.text = "Send Screenshot"
            setTextColor(textColor())
            setTypeface(getKeyTypeface(), Typeface.BOLD)
            textSize = 12.5f
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        container.addView(tv)

        container.setOnClickListener {
            container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            sendScreenshotToChat(uri)
            pendingScreenshotUri = null
            userStartedTyping = true
            refreshTopBar()
        }

        return container
    }

    private fun sendScreenshotToChat(uri: Uri): Boolean {
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        if (ic != null && info != null) {
            try {
                val mimeType = contentResolver.getType(uri) ?: "image/png"
                val description = ClipDescription("Screenshot", arrayOf(mimeType, "image/png", "image/jpeg"))
                val contentInfo = InputContentInfoCompat(uri, description, null)
                val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION

                val success = InputConnectionCompat.commitContent(ic, info, contentInfo, flags, null)
                if (success) {
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.w("CustomKeyboard", "Direct commitContent error", e)
            }
        }

        // Fallback: copy to clipboard so user can paste immediately
        try {
            val clipData = ClipData.newUri(contentResolver, "Screenshot", uri)
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(this, "Screenshot copied to clipboard. Paste to send.", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            android.util.Log.e("CustomKeyboard", "Clipboard copy error", e)
        }
        return false
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

    private fun emojiChip(emoji: String, isFewItems: Boolean = false): TextView {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        return TextView(this).apply {
            text = emoji
            textSize = 17f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = if (isFewItems) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f).apply {
                    setMargins(dp(2), dp(4), dp(2), dp(4))
                }
            } else {
                LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    setMargins(dp(2), dp(4), dp(2), dp(4))
                }
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

    private fun suggestionChip(item: Dictionary.SuggestionItem): View {
        val tv = TextView(this).apply {
            text = item.text
            setTextColor(textColor())
            setTypeface(getKeyTypeface())
            textSize = getSuggestionFontSize()
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            ellipsize = null // Show the full word, never truncate!
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            minimumWidth = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Use StateListDrawable for press highlight without blocking HorizontalScrollView scroll gestures
        val normal = ColorDrawable(Color.TRANSPARENT)
        val pressed = roundedDrawable(pressHighlightColor(), KEY_RADIUS_DP)
        val sld = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
        tv.background = sld
        tv.isClickable = true
        tv.isFocusable = true

        tv.setOnClickListener {
            tv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            val ctx = getActiveTypingContext()
            Dictionary.recordUsedWord(item.text, ctx.prev1, ctx.prev2)
            lastCommittedWord = item.text
            val lengthToDelete = if (ctx.currentWord.isNotEmpty()) ctx.currentWord.length else wordBuffer.length
            if (lengthToDelete > 0) {
                currentInputConnection?.deleteSurroundingText(lengthToDelete, 0)
            }
            wordBuffer.clear()
            currentInputConnection?.commitText("${item.text} ", 1)
            refreshTopBar()
        }
        return tv
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
            Triple("auto_field", "Based on Field", "📝 Field"),
            Triple("auto_effect", "Based on Effect", "⚡ Effect"),
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

        // 3.5 Universal Trigger Status and Quick Toggles
        val triggerHeader = TextView(this).apply {
            text = "⚡ Universal Triggers"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDarkMode()) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8"))
            setPadding(dp(12), dp(8), dp(12), dp(2))
        }
        mainLayout.addView(triggerHeader)

        val triggerChipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }

        val volOn = TriggerManager.isVolumeTriggerEnabled(this)
        val proxOn = TriggerManager.isProximityTriggerEnabled(this)
        val enterOn = TriggerManager.isEnterTriggerEnabled(this)
        val delayOn = TriggerManager.isDelayTriggerEnabled(this)
        val delayStr = TriggerManager.getDelayFormatted(this)

        val triggerItems = listOf(
            Triple("Vol Up/Dn", volOn) {
                TriggerManager.setVolumeTriggerEnabled(this@CustomKeyboardService, !volOn)
                Toast.makeText(this@CustomKeyboardService, "Volume Trigger: ${if (!volOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                render()
            },
            Triple("Proximity", proxOn) {
                TriggerManager.setProximityTriggerEnabled(this@CustomKeyboardService, !proxOn)
                Toast.makeText(this@CustomKeyboardService, "Proximity Trigger: ${if (!proxOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                render()
            },
            Triple("Enter/Search", enterOn) {
                TriggerManager.setEnterTriggerEnabled(this@CustomKeyboardService, !enterOn)
                Toast.makeText(this@CustomKeyboardService, "Enter/Search Trigger: ${if (!enterOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                render()
            },
            Triple("Timer ($delayStr)", delayOn) {
                TriggerManager.setDelayTriggerEnabled(this@CustomKeyboardService, !delayOn)
                Toast.makeText(this@CustomKeyboardService, "Time Delay Trigger: ${if (!delayOn) "ON ($delayStr)" else "OFF"}", Toast.LENGTH_SHORT).show()
                render()
            }
        )

        for ((label, isEnabled, onClick) in triggerItems) {
            val chip = TextView(this).apply {
                text = "${if (isEnabled) "✓ " else ""}$label"
                textSize = 10.5f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(5), dp(4), dp(5))
                setTextColor(if (isEnabled) Color.WHITE else (if (isDarkMode()) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")))
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(if (isEnabled) (if (isDarkMode()) Color.parseColor("#1A73E8") else Color.parseColor("#185ABC"))
                             else (if (isDarkMode()) Color.parseColor("#292A2D") else Color.parseColor("#F1F3F4")))
                    if (!isEnabled) {
                        setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
                    }
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener { onClick() }
            }
            triggerChipsRow.addView(chip)
        }
        mainLayout.addView(triggerChipsRow)

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
                // Covert Mode Selection (Standard Covert Typing vs Covert Reveal API typing)
                panel.addView(buildOptionChipsRow(
                    label = "Covert Typing Effect Mode",
                    options = listOf(
                        "standard" to "🎭 Standard Covert",
                        "reveal" to "✨ Covert Reveal"
                    ),
                    selectedKey = covertManager.covertMode
                ) { newMode ->
                    covertManager.covertMode = newMode
                    if (newMode == "reveal") {
                        covertManager.resetRevealSession()
                        covertManager.fetchLatestApiValue()
                    }
                    render()
                })

                if (covertManager.covertMode == "reveal") {
                    val apiInfo = covertManager.getEffectiveApiRevealValue()
                    val isDone = covertManager.isRevealCompleted
                    val prog = if (isDone) "Finished (appended .)" else "${covertManager.revealIndex}/${apiInfo.length} chars typed"

                    panel.addView(TextView(this).apply {
                        text = "API Info: \"$apiInfo\"\nProgress: $prog\n• Type ANY key to reveal API data character-by-character.\n• Automatically adds (.) and stealth vibrates when done."
                        setTextColor(textColor())
                        textSize = 11.5f
                        setPadding(0, dp(2), 0, dp(4))
                    })

                    // Quick action buttons for Covert Reveal: Fetch API Now & Reset
                    val revealButtonsRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    val actionBtnBg = {
                        GradientDrawable().apply {
                            setColor(specialKeyColor())
                            cornerRadius = dp(8).toFloat()
                            setStroke(dp(1), if (isDarkMode()) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0"))
                        }
                    }
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

                    revealButtonsRow.addView(TextView(this).apply {
                        text = "🔄 Fetch API Now"
                        textSize = 11f
                        setTextColor(textColor())
                        background = actionBtnBg()
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) }
                        setOnClickListener {
                            Toast.makeText(this@CustomKeyboardService, "Fetching latest API info...", Toast.LENGTH_SHORT).show()
                            covertManager.fetchLatestApiValue { success, result ->
                                mainHandler.post {
                                    Toast.makeText(this@CustomKeyboardService, if (success) "Fetched: \"$result\"" else "API: $result", Toast.LENGTH_SHORT).show()
                                    render()
                                }
                            }
                        }
                    })
                    revealButtonsRow.addView(TextView(this).apply {
                        text = "↺ Reset Reveal"
                        textSize = 11f
                        setTextColor(textColor())
                        background = actionBtnBg()
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
                        setOnClickListener {
                            covertManager.resetRevealSession()
                            Toast.makeText(this@CustomKeyboardService, "Covert Reveal reset! Ready to type.", Toast.LENGTH_SHORT).show()
                            render()
                        }
                    })
                    panel.addView(revealButtonsRow)
                } else {
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
                // Replacement Target Scope Chips (Placeholder Tag vs Current Line)
                panel.addView(buildOptionChipsRow(
                    label = "Replacement Target Scope",
                    options = listOf(
                        "tag" to "🏷️ Placeholder / All",
                        "cursor_line" to "🎯 Current Line"
                    ),
                    selectedKey = if (covertManager.replaceCurrentLine) "cursor_line" else "tag"
                ) { newScope ->
                    covertManager.replaceCurrentLine = (newScope == "cursor_line")
                    render()
                })

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
                    val targetDesc = if (covertManager.replaceCurrentLine) "Target: Full Current Cursor Line" else "Placeholder: ${covertManager.replacePlaceholder}"
                    text = "$targetDesc\nEffective Value: \"$preview\""
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

    private fun buildEnglishLetterRow0(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        val keys = KeyboardLayoutData.englishRows[0]
        val hints = KeyboardLayoutData.englishHints[0]
        keys.forEachIndexed { index, k ->
            val display = if (shiftOn || capsLock) k.uppercase() else k
            val hint = hints.getOrNull(index)
            row.addView(
                makeKey(
                    label = display,
                    weight = 1f,
                    fontSize = getLetterFontSize(),
                    hint = hint,
                    onLongClick = hint?.let { h -> { commitSymbol(h) } }
                ) {
                    commitLetter(display)
                }
            )
        }
        return row
    }

    private fun buildEnglishLetterRow1(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        row.addView(spacer(0.5f))
        val keys = KeyboardLayoutData.englishRows[1]
        val hints = KeyboardLayoutData.englishHints[1]
        keys.forEachIndexed { index, k ->
            val display = if (shiftOn || capsLock) k.uppercase() else k
            val hint = hints.getOrNull(index)
            row.addView(
                makeKey(
                    label = display,
                    weight = 1f,
                    fontSize = getLetterFontSize(),
                    hint = hint,
                    onLongClick = hint?.let { h -> { commitSymbol(h) } }
                ) {
                    commitLetter(display)
                }
            )
        }
        row.addView(spacer(0.5f))
        return row
    }

    private fun buildEnglishLetterRow2WithShiftAndBackspace(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        row.addView(makeShiftKey(weight = 1.5f))
        val keys = KeyboardLayoutData.englishRows[2]
        val hints = KeyboardLayoutData.englishHints[2]
        keys.forEachIndexed { index, k ->
            val display = if (shiftOn || capsLock) k.uppercase() else k
            val hint = hints.getOrNull(index)
            row.addView(
                makeKey(
                    label = display,
                    weight = 1f,
                    fontSize = getLetterFontSize(),
                    hint = hint,
                    onLongClick = hint?.let { h -> { commitSymbol(h) } }
                ) {
                    commitLetter(display)
                }
            )
        }
        // Swipe left on backspace to delete more than one character at a time.
        row.addView(makeBackspaceKey(weight = 1.5f))
        return row
    }

    private fun buildArabicNumberRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        KeyboardLayoutData.arabicNumberRow.forEach { num ->
            row.addView(makeKey(num, weight = 1f, fontSize = getLetterFontSize()) {
                commitSymbol(num)
            })
        }
        return row
    }

    private fun buildArabicLetterRow(rowIndex: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        val keys = KeyboardLayoutData.arabicRows[rowIndex]
        val hints = KeyboardLayoutData.arabicHints[rowIndex]
        keys.forEachIndexed { index, letter ->
            val hint = hints.getOrNull(index)
            row.addView(
                makeKey(
                    label = letter,
                    weight = 1f,
                    fontSize = getLetterFontSize(),
                    hint = hint,
                    onLongClick = hint?.let { h -> { commitSymbol(h) } }
                ) {
                    commitLetter(letter)
                }
            )
        }
        return row
    }

    private fun buildArabicLetterRow2WithBackspace(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        val keys = KeyboardLayoutData.arabicRows[2]
        val hints = KeyboardLayoutData.arabicHints[2]
        keys.forEachIndexed { index, letter ->
            val hint = hints.getOrNull(index)
            row.addView(
                makeKey(
                    label = letter,
                    weight = 1f,
                    fontSize = getLetterFontSize(),
                    hint = hint,
                    onLongClick = hint?.let { h -> { commitSymbol(h) } }
                ) {
                    commitLetter(letter)
                }
            )
        }
        // 11th key in row 3 is Backspace with weight 1f to complete the 11-column grid
        row.addView(makeBackspaceKey(weight = 1f))
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
        keys.forEach { rawKey ->
            val k = if (rawKey == "?" && currentLang == Lang.AR) "؟" else rawKey
            row.addView(makeKey(k, weight = 1f, fontSize = getSymbolFontSize()) { commitSymbol(k) })
        }
        return row
    }

    private fun buildSymbolsBottomRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        keys.forEach { rawKey ->
            val k = if (rawKey == "?" && currentLang == Lang.AR) "؟" else rawKey
            row.addView(makeKey(k, weight = 1f, fontSize = getSymbolFontSize()) { commitSymbol(k) })
        }
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

        if (currentLang == Lang.AR && currentMode == Mode.LETTERS) {
            row.addView(make123Key("؟٣٢١", weight = 1.5f) { switchMode(lastAltMode) })
            row.addView(makeArabicCommaEmojiKey(weight = 1f))
            row.addView(makeGlobeKey(weight = 1f) { switchLanguage() })
            row.addView(makeSpaceKey("العربية", weight = 5f))
            row.addView(makeArabicPeriodTashkeelKey(weight = 1f))
            row.addView(makeEnterKey(weight = 1.5f))
            return row
        }

        when (currentMode) {
            Mode.SYMBOLS -> {
                row.addView(make123Key("ABC", weight = 1.4f) { switchMode(Mode.LETTERS) })
                val numLabel = if (currentLang == Lang.AR) "١٢٣" else "123"
                row.addView(makeSpecialKey(numLabel, weight = 1.1f) { switchMode(Mode.NUMBERS) })
            }
            Mode.EMOJI -> {
                row.addView(make123Key("ABC", weight = 1.5f) { switchMode(Mode.LETTERS) })
            }
            else -> {
                val altLabel = if (currentLang == Lang.AR) "؟123" else "?123"
                row.addView(make123Key(altLabel, weight = 1.5f) { switchMode(lastAltMode) })
                row.addView(makeCommaEmojiKey(weight = 1f))
            }
        }

        row.addView(makeGlobeKey(weight = 1f) { switchLanguage() })

        val spaceLabel = if (currentLang == Lang.EN) "English" else "العربية"
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

        // 2. Row 4: ABC !?# 0 , . = ↵
        val r4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getRowHeightDp()))
        }
        r4.addView(makeSpecialKey("ABC", weight = 1.25f) { switchMode(Mode.LETTERS) })
        val symbolToggleLabel = if (currentLang == Lang.AR) "!؟#" else "!?#"
        r4.addView(makeSpecialKey(symbolToggleLabel, weight = 1.25f) { switchMode(Mode.SYMBOLS) })
        r4.addView(makeNumberKey("0", weight = 1.8f) { commitLetter("0") })
        r4.addView(makeSpecialKey(",", weight = 0.85f) { commitPunctuationOrSpace(",") })
        r4.addView(makeSpecialKey(".", weight = 0.85f) { commitPunctuationOrSpace(".") })
        r4.addView(makeSpecialKey("=", weight = 0.85f) { commitSymbol("=") })
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
                setTypeface(getKeyTypeface())
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
            setTypeface(getKeyTypeface())
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
            setTypeface(getKeyTypeface())
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
            setTypeface(getKeyTypeface())
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
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            setTextColor(textCl)
            setTypeface(getKeyTypeface())
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

    private fun makeKey(
        label: String,
        weight: Float,
        fontSize: Float = 20f,
        hint: String? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        val resting = keyBackground(keyColor(), KEY_RADIUS_DP)
        val lookupKey = if (label.length == 1) label.lowercase() else label
        val rawVariations = KeyboardLayoutData.characterVariations[label] ?: KeyboardLayoutData.characterVariations[lookupKey]
        val twoRowSplit = KeyboardLayoutData.twoRowVariations[label] ?: KeyboardLayoutData.twoRowVariations[lookupKey]

        val isShiftActive = shiftOn || capsLock
        val variations: List<String> = when {
            twoRowSplit != null -> {
                val combined = twoRowSplit.first + twoRowSplit.second
                val list = if (hint != null && !combined.contains(hint)) combined + hint else combined
                val withBase = if (!list.contains(label)) listOf(label) + list else list
                if (isShiftActive) withBase.map { if (it.length == 1 && Character.isLetter(it[0])) it.uppercase() else it } else withBase
            }
            rawVariations != null -> {
                val list = if (hint != null && !rawVariations.contains(hint)) rawVariations + hint else rawVariations
                val withBase = if (!list.contains(label)) listOf(label) + list else list
                if (isShiftActive) withBase.map { if (it.length == 1 && Character.isLetter(it[0])) it.uppercase() else it } else withBase
            }
            hint != null -> listOf(label, hint)
            else -> listOf(label)
        }

        val defaultSelected: String? = when {
            twoRowSplit != null -> {
                val candidate = twoRowSplit.first.getOrNull(1) ?: twoRowSplit.first.firstOrNull()
                if (candidate != null && isShiftActive && candidate.length == 1 && Character.isLetter(candidate[0])) candidate.uppercase() else candidate
            }
            variations.size > 1 -> {
                variations.firstOrNull { it != label && it != lookupKey && it != hint } ?: hint ?: variations.firstOrNull()
            }
            else -> label
        }

        if (hint == null) {
            val isArabic = currentLang == Lang.AR
            return TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(textColor())
                setTypeface(getKeyTypeface())
                textSize = fontSize
                includeFontPadding = if (isArabic) true else false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                background = resting
                if (isArabic) {
                    val isBaseline = label in baselineArabicLetters
                    val raiseDp = if (isBaseline) {
                        if (prefs.getString("key_font_size", "normal") == "extra_large") 0.5f else 0f
                    } else {
                        when (prefs.getString("key_font_size", "normal")) {
                            "extra_large" -> 2.5f
                            "large" -> 1.5f
                            else -> 1f
                        }
                    }
                    translationY = -dpF(raiseDp)
                }
                applyKeyTouchBehavior(
                    this,
                    pressHighlightColor(),
                    resting,
                    KEY_RADIUS_DP,
                    popupLabel = label,
                    popupHint = null,
                    variations = variations,
                    defaultSelected = defaultSelected,
                    twoRowSplit = twoRowSplit,
                    onLongClick = onLongClick
                ) { onClick() }
            }
        }

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            clipChildren = false
            clipToPadding = false
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val isArabic = currentLang == Lang.AR
        val tvMain = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(getKeyTypeface())
            textSize = fontSize
            includeFontPadding = if (isArabic) true else false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Baseline letters (ط, ك, ف, ث, ا, ة, ظ, د, etc.) have no descenders; keep them centered without colliding into hints.
            // Descender letters (ض, ص, ي, ر, ز, و, ى, ش, س, ق, etc.) have low tails; apply a gentle lift to stay inside key borders.
            val raiseDp = if (isArabic) {
                if (label in baselineArabicLetters) {
                    if (prefs.getString("key_font_size", "normal") == "extra_large") 0.5f else 0f
                } else {
                    when (prefs.getString("key_font_size", "normal")) {
                        "extra_large" -> 2.5f
                        "large" -> 1.5f
                        else -> 1f
                    }
                }
            } else {
                when (prefs.getString("key_font_size", "normal")) {
                    "extra_large" -> 2f
                    "large" -> 1.2f
                    else -> 0.8f
                }
            }
            translationY = -dpF(raiseDp)
        }
        container.addView(tvMain)

        val tvHint = TextView(this).apply {
            text = hint
            setTextColor(textColor())
            alpha = 0.55f
            textSize = getHintFontSize(isArabic)
            setTypeface(Typeface.DEFAULT)
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.RIGHT
            ).apply {
                topMargin = dp(if (isArabic) 3 else 2)
                rightMargin = dp(if (isArabic) 3 else 3)
            }
            // Underscore "_" is drawn at the bottom baseline of its font box; raise it into the top-right corner
            if (hint == "_") {
                translationY = -dpF(6f)
            }
        }
        container.addView(tvHint)

        val effectiveLongClick = onLongClick ?: {
            commitSymbol(hint)
        }

        applyKeyTouchBehavior(
            container,
            pressHighlightColor(),
            resting,
            KEY_RADIUS_DP,
            popupLabel = label,
            popupHint = hint,
            variations = variations,
            defaultSelected = defaultSelected,
            twoRowSplit = twoRowSplit,
            onLongClick = effectiveLongClick
        ) { onClick() }

        return container
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
            setTypeface(getKeyTypeface())
            textSize = getSpecialKeyFontSize()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            applyKeyTouchBehavior(this, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        }
    }

    private fun makeCommaEmojiKey(weight: Float): View {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            isClickable = true
            isHapticFeedbackEnabled = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val ivEmoji = ImageView(this).apply {
            setImageResource(R.drawable.ic_emoji_toolbar)
            setColorFilter(textColor())
            val emojiSize = dp(14)
            layoutParams = LinearLayout.LayoutParams(emojiSize, emojiSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(0)
            }
            translationY = dpF(2.5f)
        }
        content.addView(ivEmoji)

        val tvComma = TextView(this).apply {
            text = ","
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(textColor())
            setTypeface(getKeyTypeface())
            textSize = 19f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = -dp(2)
            }
        }
        content.addView(tvComma)

        container.addView(content)

        applyKeyTouchBehavior(
            container,
            pressHighlightColor(),
            resting,
            KEY_RADIUS_DP,
            onLongClick = {
                switchMode(Mode.EMOJI)
            }
        ) {
            commitPunctuationOrSpace(",")
        }

        return container
    }

    private fun makeGlobeKey(weight: Float, onClick: () -> Unit): View {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
        }
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_globe)
            setColorFilter(textColor())
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        }
        container.addView(iv)
        applyKeyTouchBehavior(container, pressHighlightColor(), resting, KEY_RADIUS_DP) { onClick() }
        return container
    }

    private fun makeArabicCommaEmojiKey(weight: Float): View {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            isClickable = true
            isHapticFeedbackEnabled = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val ivEmoji = ImageView(this).apply {
            setImageResource(R.drawable.ic_emoji_toolbar)
            setColorFilter(textColor())
            val emojiSize = dp(14)
            layoutParams = LinearLayout.LayoutParams(emojiSize, emojiSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(1)
            }
        }
        content.addView(ivEmoji)

        val tvComma = TextView(this).apply {
            text = "،"
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(textColor())
            setTypeface(getKeyTypeface())
            textSize = 19f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = -dp(1)
            }
        }
        content.addView(tvComma)

        container.addView(content)

        applyKeyTouchBehavior(
            container,
            pressHighlightColor(),
            resting,
            KEY_RADIUS_DP,
            onLongClick = {
                switchMode(Mode.EMOJI)
            }
        ) {
            commitPunctuationOrSpace("،")
        }
        return container
    }

    private fun makeArabicPeriodTashkeelKey(weight: Float): View {
        val resting = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
        }
        val tvPeriod = TextView(this).apply {
            text = "."
            gravity = Gravity.CENTER
            setTextColor(textColor())
            setTypeface(getKeyTypeface())
            textSize = 21f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dp(2)
            }
        }
        container.addView(tvPeriod)

        val tvTashkeel = TextView(this).apply {
            text = "◌ً"
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(textColor())
            alpha = 0.65f
            textSize = 10f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(3)
            }
        }
        container.addView(tvTashkeel)

        val dotVariations = listOf("َ", "ُ", "ِ", "ً", "ٌ", "ٍ", "ّ", "ْ", "ـ", ".")
        val dotTwoRow = Pair(listOf("َ", "ُ", "ِ", "ً", "ٌ"), listOf("ٍ", "ّ", "ْ", "ـ", "."))

        applyKeyTouchBehavior(
            container,
            pressHighlightColor(),
            resting,
            KEY_RADIUS_DP,
            popupLabel = ".",
            popupHint = "◌ً",
            variations = dotVariations,
            defaultSelected = "َ",
            twoRowSplit = dotTwoRow,
            onLongClick = null
        ) {
            commitPunctuationOrSpace(".")
        }
        return container
    }

    private fun showTashkeelPopup(anchorView: View) {
        if (anchorView.windowToken == null) return
        val marks = listOf(
            "َ", "ُ", "ِ", "ً", "ٌ", "ٍ", "ّ", "ْ", "ـ"
        )
        val popupLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = keyBackground(specialKeyColor(), KEY_RADIUS_DP)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(8).toFloat()
        }
        var popupWindow: PopupWindow? = null
        marks.forEach { mark ->
            val markBtn = TextView(this).apply {
                text = "ـ$mark"
                textSize = 20f
                setTextColor(textColor())
                gravity = Gravity.CENTER
                val size = dp(36)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                background = keyBackground(keyColor(), KEY_RADIUS_DP)
                isClickable = true
                setOnClickListener {
                    currentInputConnection?.commitText(mark, 1)
                    popupWindow?.dismiss()
                }
            }
            popupLayout.addView(markBtn)
        }
        try {
            popupWindow = PopupWindow(
                popupLayout,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                isOutsideTouchable = true
                showAsDropDown(anchorView, -dp(140), -dp(70) - anchorView.height)
            }
        } catch (_: Exception) {
        }
    }

    internal enum class EnterActionType {
        NEWLINE,
        SEARCH,
        SEND,
        GO,
        NEXT,
        PREVIOUS,
        DONE
    }

    /**
     * Determines the exact enter action and visual glyph based on the typing field (EditorInfo).
     * In normal mode, the enter button acts ONLY based on the typing field:
     * - Search bar -> Searches (magnifying glass)
     * - Note-taking area / multi-line text -> Newline (return arrow)
     * - Chat / messaging -> Sends (send paper airplane)
     * - Browser URL bar -> Go (forward arrow)
     * - Form next field -> Next (next arrow)
     * - Form done field -> Done (checkmark)
     */
    private fun getEnterActionType(): EnterActionType {
        val info = currentInputEditorInfo ?: return EnterActionType.NEWLINE

        // If covert typing mode is actively on, handle any covert overrides
        if (covertManager.isCovertActive) {
            when (covertManager.enterKeyBehavior) {
                "newline_only" -> return EnterActionType.NEWLINE
                "search_only" -> return EnterActionType.SEARCH
                "auto_effect" -> {
                    val requiresMultiLineEffect = covertManager.isCovertActive ||
                            covertManager.isMathEnabled ||
                            (covertManager.isTextPeekEnabled && (covertManager.textPeekMode == "line" || covertManager.textPeekMode == "cursor_line" || covertManager.textPeekMode == "last_word"))
                    if (requiresMultiLineEffect) {
                        return EnterActionType.NEWLINE
                    } else if (covertManager.isTextReplaceEnabled) {
                        return EnterActionType.SEARCH
                    }
                }
                // "auto_field" proceeds to standard field inspection below
            }
        }

        // Normal mode: enter button acts strictly based on the active typing field.
        val inputType = info.inputType
        val imeOptions = info.imeOptions
        val typeVariation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val rawAction = imeOptions and EditorInfo.IME_MASK_ACTION

        val isMultiLineFlag = (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
        val isLongMessage = typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
        val hasNoEnterActionFlag = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        // 1. Note-taking area or multi-line text area
        if (hasNoEnterActionFlag) {
            return EnterActionType.NEWLINE
        }
        if (isMultiLineFlag || isLongMessage) {
            // In note taking apps, document editors, and multiline text areas, Enter must always go to the next line.
            // Only if an app explicitly specifies SEARCH or SEND without no-enter flag should an action be triggered.
            if (rawAction != EditorInfo.IME_ACTION_SEARCH && rawAction != EditorInfo.IME_ACTION_SEND) {
                return EnterActionType.NEWLINE
            }
        }

        // 2. Search bar
        val isSearch = rawAction == EditorInfo.IME_ACTION_SEARCH ||
                info.actionId == EditorInfo.IME_ACTION_SEARCH ||
                typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_FILTER ||
                typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT ||
                info.actionLabel?.toString()?.contains("search", ignoreCase = true) == true ||
                info.actionLabel?.toString()?.contains("بحث", ignoreCase = true) == true ||
                info.hintText?.toString()?.contains("search", ignoreCase = true) == true ||
                info.hintText?.toString()?.contains("بحث", ignoreCase = true) == true ||
                info.fieldName?.contains("search", ignoreCase = true) == true
        if (isSearch) {
            return EnterActionType.SEARCH
        }

        // 3. Send action (e.g. Chat apps)
        val isSend = rawAction == EditorInfo.IME_ACTION_SEND ||
                info.actionId == EditorInfo.IME_ACTION_SEND ||
                typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
                info.actionLabel?.toString()?.contains("send", ignoreCase = true) == true ||
                info.actionLabel?.toString()?.contains("إرسال", ignoreCase = true) == true
        if (isSend) {
            return EnterActionType.SEND
        }

        // 4. Go action (e.g. Browser URL bar)
        val isGo = rawAction == EditorInfo.IME_ACTION_GO ||
                info.actionId == EditorInfo.IME_ACTION_GO ||
                typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_URI ||
                info.actionLabel?.toString()?.contains("go", ignoreCase = true) == true
        if (isGo) {
            return EnterActionType.GO
        }

        // 5. Next action (e.g. Form input field)
        val isNext = rawAction == EditorInfo.IME_ACTION_NEXT ||
                info.actionId == EditorInfo.IME_ACTION_NEXT ||
                info.actionLabel?.toString()?.contains("next", ignoreCase = true) == true ||
                info.actionLabel?.toString()?.contains("التالي", ignoreCase = true) == true
        if (isNext) {
            return EnterActionType.NEXT
        }

        // 6. Previous action
        val isPrevious = rawAction == EditorInfo.IME_ACTION_PREVIOUS ||
                info.actionId == EditorInfo.IME_ACTION_PREVIOUS
        if (isPrevious) {
            return EnterActionType.PREVIOUS
        }

        // 7. Done action (e.g. Single-line form finish)
        val isDone = rawAction == EditorInfo.IME_ACTION_DONE ||
                info.actionId == EditorInfo.IME_ACTION_DONE ||
                info.actionLabel?.toString()?.contains("done", ignoreCase = true) == true ||
                info.actionLabel?.toString()?.contains("تم", ignoreCase = true) == true
        if (isDone) {
            return EnterActionType.DONE
        }

        // 8. Fallback
        return if (isMultiLineFlag || isLongMessage) {
            EnterActionType.NEWLINE
        } else {
            EnterActionType.NEWLINE
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
        val actionType = getEnterActionType()
        val glyph = when (actionType) {
            EnterActionType.SEARCH -> GlyphIconView.Glyph.SEARCH
            EnterActionType.SEND -> GlyphIconView.Glyph.SEND
            EnterActionType.GO -> GlyphIconView.Glyph.GO
            EnterActionType.NEXT -> GlyphIconView.Glyph.NEXT
            EnterActionType.PREVIOUS -> GlyphIconView.Glyph.PREVIOUS
            EnterActionType.DONE -> GlyphIconView.Glyph.DONE
            EnterActionType.NEWLINE -> GlyphIconView.Glyph.RETURN
        }
        val icon = GlyphIconView(this, glyph).apply {
            iconColor = enterIconColor()
            isRtl = currentLang == Lang.AR
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
            isActive = active
            layoutParams = FrameLayout.LayoutParams(dp(ICON_GLYPH_DP), dp(ICON_GLYPH_DP), Gravity.CENTER)
        }
        container.addView(icon)
        applyKeyTouchBehavior(container, pressHighlightColor(), resting, KEY_RADIUS_DP) { onShiftTapped() }
        return container
    }

    /** Backspace key: a normal tap deletes one character, holding continuously deletes (auto-repeat),
     *  and dragging left performs swipe-to-delete with live word-by-word highlight in the text field. */
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
            isRtl = currentLang == Lang.AR
            layoutParams = FrameLayout.LayoutParams(dp(ICON_GLYPH_DP), dp(ICON_GLYPH_DP), Gravity.CENTER)
        }
        container.addView(icon)

        val repeatHandler = Handler(Looper.getMainLooper())
        var down = false
        var startX = 0f
        var isSwiping = false
        var repeatCount = 0
        var wordsSelectedCount = 0
        var initialCursorPos = 0
        var initialTextBefore = ""
        val wordOffsets = mutableListOf<Int>()
        val stepPx = dp(24)

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
                    isSwiping = false
                    repeatCount = 0
                    wordsSelectedCount = 0
                    wordOffsets.clear()

                    val ic = currentInputConnection
                    val et = ic?.getExtractedText(ExtractedTextRequest(), 0)
                    initialCursorPos = if (et != null && et.selectionEnd >= 0) {
                        et.selectionEnd
                    } else if (currentSelEnd > 0) {
                        currentSelEnd
                    } else {
                        ic?.getTextBeforeCursor(3000, 0)?.length ?: 0
                    }
                    initialTextBefore = ic?.getTextBeforeCursor(3000, 0)?.toString() ?: ""

                    // Precompute word boundaries backwards from end of initialTextBefore
                    var i = initialTextBefore.length
                    while (i > 0) {
                        while (i > 0 && initialTextBefore[i - 1].isWhitespace()) {
                            i--
                        }
                        if (i == 0) break
                        while (i > 0 && !initialTextBefore[i - 1].isWhitespace()) {
                            i--
                        }
                        var wordStart = i
                        while (wordStart > 0 && initialTextBefore[wordStart - 1] == ' ') {
                            wordStart--
                            break
                        }
                        val charCount = initialTextBefore.length - wordStart
                        wordOffsets.add(charCount)
                        i = wordStart
                    }

                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = pressedBg
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(45).start()

                    // Schedule repeat if user holds down the delete button (380ms initial delay)
                    repeatHandler.removeCallbacks(repeatRunnable)
                    repeatHandler.postDelayed(repeatRunnable, 380L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (down) {
                        val draggedLeft = startX - event.rawX
                        val ic = currentInputConnection
                        // If user swipes left, cancel auto-repeat and switch to live highlight swipe-to-delete
                        if (draggedLeft > dp(14)) {
                            if (!isSwiping) {
                                isSwiping = true
                                repeatHandler.removeCallbacks(repeatRunnable)
                            }
                            val targetCount = ((draggedLeft - dp(14)) / stepPx).toInt() + 1
                            val clamped = if (wordOffsets.isNotEmpty()) {
                                targetCount.coerceIn(1, wordOffsets.size)
                            } else {
                                targetCount.coerceAtLeast(1)
                            }
                            if (clamped != wordsSelectedCount) {
                                wordsSelectedCount = clamped
                                val charsToSelect = if (wordOffsets.isNotEmpty()) {
                                    wordOffsets[clamped - 1]
                                } else {
                                    clamped
                                }
                                val selStart = (initialCursorPos - charsToSelect).coerceAtLeast(0)
                                val selEnd = initialCursorPos
                                ic?.setSelection(selStart, selEnd)
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                            }
                        } else if (isSwiping && draggedLeft <= dp(8)) {
                            // User slid back to the right to cancel swipe deletion
                            if (wordsSelectedCount > 0) {
                                wordsSelectedCount = 0
                                ic?.setSelection(initialCursorPos, initialCursorPos)
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    down = false
                    repeatHandler.removeCallbacks(repeatRunnable)
                    v.background = resting
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

                    if (isSwiping) {
                        if (wordsSelectedCount > 0) {
                            val sel = currentInputConnection?.getSelectedText(0)
                            if (!sel.isNullOrEmpty()) {
                                deleteChar()
                            } else {
                                val charsToDelete = if (wordOffsets.isNotEmpty()) {
                                    wordOffsets[(wordsSelectedCount - 1).coerceIn(0, wordOffsets.size - 1)]
                                } else {
                                    wordsSelectedCount
                                }
                                currentInputConnection?.deleteSurroundingText(charsToDelete, 0)
                                wordBuffer.clear()
                                val tb = currentInputConnection?.getTextBeforeCursor(4000, 0)
                                covertManager.handleBackspace(tb)
                                refreshTopBar()
                            }
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                        } else {
                            currentInputConnection?.setSelection(initialCursorPos, initialCursorPos)
                        }
                        isSwiping = false
                        wordsSelectedCount = 0
                    } else {
                        // Normal tap: if auto-repeat hasn't fired yet, delete 1 char
                        if (repeatCount == 0) {
                            deleteChar()
                        }
                    }
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
            setTypeface(getKeyTypeface())
            textSize = getSpecialKeyFontSize()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            background = resting
            isClickable = true
            isHapticFeedbackEnabled = true
        }

        var startX = 0f
        var startY = 0f
        var lastStepX = 0f
        var lastStepY = 0f
        var isDragging = false
        var isLongPressed = false
        var isSelectionMode = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (!isDragging) {
                isLongPressed = true
                if (covertManager.stealthSpacebarTrigger) {
                    covertManager.toggleCovert()
                    tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    render()
                } else {
                    // Activate Drag Selection Mode for extending text selection in all directions
                    isSelectionMode = true
                    tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                }
            }
        }
        val stepPx = dp(14).toFloat()
        val stepYPx = dp(18).toFloat()
        val dragThreshold = dp(8).toFloat()

        fun sendDpadMovement(keyCode: Int, isSelection: Boolean) {
            try {
                val meta = if (isSelection) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
                val now = android.os.SystemClock.uptimeMillis()
                currentInputConnection?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
                currentInputConnection?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
            } catch (e: Exception) {
                android.util.Log.e("CustomKeyboard", "Error sending DPAD movement", e)
            }
        }

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    startX = event.rawX
                    startY = event.rawY
                    lastStepX = event.rawX
                    lastStepY = event.rawY
                    isDragging = false
                    isLongPressed = false
                    isSelectionMode = false
                    longPressHandler.postDelayed(longPressRunnable, 350)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = pressedBg
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    val totalDx = event.rawX - startX
                    val totalDy = event.rawY - startY
                    if (!isDragging && (kotlin.math.abs(totalDx) > dragThreshold || kotlin.math.abs(totalDy) > dragThreshold)) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        // Horizontal cursor / selection movement (Left & Right)
                        val dxSinceStep = event.rawX - lastStepX
                        if (kotlin.math.abs(dxSinceStep) >= stepPx) {
                            val steps = (dxSinceStep / stepPx).toInt()
                            val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                            repeat(kotlin.math.abs(steps)) {
                                sendDpadMovement(keyCode, isSelectionMode)
                            }
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                            lastStepX += steps * stepPx
                        }

                        // Vertical cursor / selection movement (Up & Down lines)
                        val dySinceStep = event.rawY - lastStepY
                        if (kotlin.math.abs(dySinceStep) >= stepYPx) {
                            val stepsY = (dySinceStep / stepYPx).toInt()
                            val keyCodeY = if (stepsY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                            repeat(kotlin.math.abs(stepsY)) {
                                sendDpadMovement(keyCodeY, isSelectionMode)
                            }
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                            lastStepY += stepsY * stepYPx
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
        insetHDp: Int = getKeyInsetHDp(),
        insetVDp: Int = KEY_INSET_V_DP
    ): Drawable {
        return InsetDrawable(roundedDrawable(color, radiusDp), dp(insetHDp), dp(insetVDp), dp(insetHDp), dp(insetVDp))
    }

    private fun applyKeyTouchBehavior(
        view: View,
        pressColor: Int,
        restingBackground: Drawable?,
        radiusDp: Int,
        popupLabel: String? = null,
        popupHint: String? = null,
        variations: List<String> = emptyList(),
        defaultSelected: String? = null,
        twoRowSplit: Pair<List<String>, List<String>>? = null,
        onLongClick: (() -> Unit)? = null,
        onTap: () -> Unit
    ) {
        view.isClickable = true
        view.isHapticFeedbackEnabled = true
        var pressed = false
        var isLongPressed = false
        var downRawX = 0f
        var downRawY = 0f
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (pressed) {
                isLongPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                if (variations.isNotEmpty() || twoRowSplit != null) {
                    keyPopupManager?.transitionToVariations(
                        anchor = view,
                        variations = variations,
                        defaultSelected = defaultSelected ?: popupHint ?: popupLabel,
                        twoRow = twoRowSplit,
                        initialTouchX = downRawX,
                        initialTouchY = downRawY
                    )
                } else if (popupHint != null) {
                    keyPopupManager?.transitionToVariations(
                        anchor = view,
                        variations = listOf(popupLabel ?: "", popupHint).filter { it.isNotEmpty() },
                        defaultSelected = popupHint,
                        twoRow = null,
                        initialTouchX = downRawX,
                        initialTouchY = downRawY
                    )
                } else if (popupLabel != null) {
                    keyPopupManager?.transitionToVariations(
                        anchor = view,
                        variations = listOf(popupLabel),
                        defaultSelected = popupLabel,
                        twoRow = null,
                        initialTouchX = downRawX,
                        initialTouchY = downRawY
                    )
                } else {
                    onLongClick?.invoke()
                }
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    pressed = true
                    isLongPressed = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val hasLongPress = variations.isNotEmpty() || twoRowSplit != null || popupHint != null || popupLabel != null || onLongClick != null
                    if (hasLongPress) {
                        longPressHandler.postDelayed(longPressRunnable, 280)
                    }
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    v.background = keyBackground(pressColor, radiusDp)
                    if (popupLabel != null) {
                        keyPopupManager?.showPopup(v, popupLabel, popupHint, hasAlternates = variations.size > 1 || twoRowSplit != null)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    if (isLongPressed) {
                        keyPopupManager?.updateSelectionFromTouch(event.rawX, event.rawY)
                    } else {
                        // Allow generous drift upward toward popup without cancelling long-press
                        val dx = kotlin.math.abs(event.rawX - downRawX)
                        val dy = event.rawY - downRawY
                        val cancelThreshold = dp(60)
                        if ((dx > cancelThreshold || dy > cancelThreshold) && pressed) {
                            pressed = false
                            longPressHandler.removeCallbacks(longPressRunnable)
                            v.background = restingBackground
                            keyPopupManager?.hidePopup(immediate = true)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.background = restingBackground
                    if (pressed) {
                        pressed = false
                        if (isLongPressed) {
                            val chosen = keyPopupManager?.getSelectedVariation() ?: defaultSelected ?: popupHint ?: popupLabel
                            keyPopupManager?.hidePopup(immediate = false)
                            if (chosen != null && chosen.isNotEmpty()) {
                                if (chosen.length == 1 && Character.isLetter(chosen[0])) {
                                    commitLetter(chosen)
                                } else {
                                    commitSymbol(chosen)
                                }
                            } else {
                                onLongClick?.invoke()
                            }
                        } else {
                            keyPopupManager?.hidePopup(immediate = false)
                            onTap()
                        }
                    } else {
                        keyPopupManager?.hidePopup(immediate = true)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    pressed = false
                    isLongPressed = false
                    v.background = restingBackground
                    keyPopupManager?.hidePopup(immediate = true)
                    true
                }
                else -> false
            }
        }
    }

    // ---------- input actions ----------

    private fun handleKeyCommit(originalText: String, isLetter: Boolean) {
        if (originalText.isEmpty()) return
        notifyUserTypingAction()
        try {
            if (TriggerManager.isDelayTriggerEnabled(this)) {
                TriggerManager.scheduleDelayTrigger(this, "Key Typed")
            }

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
        } catch (e: Exception) {
            android.util.Log.e("CustomKeyboard", "Error in handleKeyCommit", e)
        }
    }

    private fun commitLetter(letter: String) {
        if (letter.isEmpty()) return
        handleKeyCommit(letter, isLetter = true)
    }

    private fun commitSymbol(text: String) {
        if (text.isEmpty()) return
        try {
            if (text == "?" || text == "؟" || text == "!" || text == "." || text == ",") {
                commitPunctuationOrSpace(text)
                return
            }
            val ctx = getActiveTypingContext()
            if (ctx.currentWord.isNotEmpty()) {
                Dictionary.recordUsedWord(ctx.currentWord, ctx.prev1, ctx.prev2)
                lastCommittedWord = ctx.currentWord
            } else if (wordBuffer.isNotEmpty()) {
                val typed = wordBuffer.toString().trim()
                Dictionary.recordUsedWord(typed, ctx.prev1, ctx.prev2)
                lastCommittedWord = typed
            }
            handleKeyCommit(text, isLetter = false)
            if (wordBuffer.isNotEmpty()) wordBuffer.clear()
            refreshTopBar()
        } catch (e: Exception) {
            android.util.Log.e("CustomKeyboard", "Error in commitSymbol", e)
        }
    }

    // Word boundaries no longer silently rewrite what was typed - suggestions are only ever
    // applied when the user explicitly taps a suggestion chip in the top bar.
    private fun commitPunctuationOrSpace(boundary: String) {
        if (boundary.isEmpty()) return
        try {
            val ctx = getActiveTypingContext()
            if (ctx.currentWord.isNotEmpty()) {
                Dictionary.recordUsedWord(ctx.currentWord, ctx.prev1, ctx.prev2)
                lastCommittedWord = ctx.currentWord
            } else if (wordBuffer.isNotEmpty()) {
                val typed = wordBuffer.toString().trim()
                Dictionary.recordUsedWord(typed, ctx.prev1, ctx.prev2)
                lastCommittedWord = typed
            } else {
                val textBefore = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()?.trim() ?: ""
                val p = textBefore.split(Regex("\\s+")).lastOrNull { it.isNotEmpty() } ?: ""
                if (p.isNotEmpty()) {
                    lastCommittedWord = p
                }
            }
            handleKeyCommit(boundary, isLetter = false)
            wordBuffer.clear()
            refreshTopBar()
        } catch (e: Exception) {
            android.util.Log.e("CustomKeyboard", "Error in commitPunctuationOrSpace", e)
        }
    }

    private fun deleteChar() {
        notifyUserTypingAction()
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
        if (currentMode != Mode.SYMBOLS && currentMode != Mode.NUMBERS) {
            currentMode = Mode.LETTERS
        }
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
        if (covertManager.isCovertActive && covertManager.capturedSecretWord.isNotEmpty()) {
            TriggerManager.queueCovertWord(covertManager.capturedSecretWord, this, covertManager)
        }

        val ic = currentInputConnection
        val info = currentInputEditorInfo
        val actionType = getEnterActionType()

        if (TriggerManager.isEnterTriggerEnabled(this)) {
            val label = when (actionType) {
                EnterActionType.SEARCH -> "Enter / Search Key (SEARCH)"
                EnterActionType.SEND -> "Enter / Search Key (SEND)"
                EnterActionType.GO -> "Enter / Search Key (GO)"
                EnterActionType.DONE -> "Enter / Search Key (DONE)"
                EnterActionType.NEXT -> "Enter / Search Key (NEXT)"
                EnterActionType.PREVIOUS -> "Enter / Search Key (PREVIOUS)"
                else -> "Enter / Search Key (NEWLINE)"
            }
            TriggerManager.fireTrigger(label, this)
        }

        when (actionType) {
            EnterActionType.NEWLINE -> {
                val inputType = info?.inputType ?: 0
                val isMultiLineFlag = (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                        (inputType and android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0 ||
                        (inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
                        (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

                if (isMultiLineFlag) {
                    ic?.commitText("\n", 1)
                } else {
                    val rawAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                    if (rawAction != EditorInfo.IME_ACTION_NONE && rawAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
                        val performed = ic?.performEditorAction(rawAction) ?: false
                        if (!performed) {
                            sendHardwareEnter(ic)
                        }
                    } else if (info?.actionId != null && info.actionId != 0) {
                        val performed = ic?.performEditorAction(info.actionId) ?: false
                        if (!performed) {
                            sendHardwareEnter(ic)
                        }
                    } else {
                        val performed = ic?.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED) ?: false
                        if (!performed) {
                            sendHardwareEnter(ic)
                        }
                    }
                }
            }
            EnterActionType.SEARCH -> {
                val action = if (info?.actionId != null && info.actionId != 0) {
                    info.actionId
                } else {
                    EditorInfo.IME_ACTION_SEARCH
                }
                val performed = ic?.performEditorAction(action) ?: false
                if (!performed) {
                    sendHardwareEnter(ic)
                }
            }
            EnterActionType.SEND -> {
                val action = if (info?.actionId != null && info.actionId != 0) {
                    info.actionId
                } else {
                    EditorInfo.IME_ACTION_SEND
                }
                val performed = ic?.performEditorAction(action) ?: false
                if (!performed) {
                    sendHardwareEnter(ic)
                }
            }
            EnterActionType.GO -> {
                val action = if (info?.actionId != null && info.actionId != 0) {
                    info.actionId
                } else {
                    EditorInfo.IME_ACTION_GO
                }
                val performed = ic?.performEditorAction(action) ?: false
                if (!performed) {
                    sendHardwareEnter(ic)
                }
            }
            EnterActionType.NEXT -> {
                val action = if (info?.actionId != null && info.actionId != 0) {
                    info.actionId
                } else {
                    EditorInfo.IME_ACTION_NEXT
                }
                val performed = ic?.performEditorAction(action) ?: false
                if (!performed) {
                    sendHardwareEnter(ic)
                }
            }
            EnterActionType.PREVIOUS -> {
                val action = if (info?.actionId != null && info.actionId != 0) {
                    info.actionId
                } else {
                    EditorInfo.IME_ACTION_PREVIOUS
                }
                val performed = ic?.performEditorAction(action) ?: false
                if (!performed) {
                    sendHardwareEnter(ic)
                }
            }
            EnterActionType.DONE -> {
                val action = if (info?.actionId != null && info.actionId != 0) {
                    info.actionId
                } else {
                    EditorInfo.IME_ACTION_DONE
                }
                val performed = ic?.performEditorAction(action) ?: false
                if (!performed) {
                    sendHardwareEnter(ic)
                }
            }
        }
        refreshTopBar()
    }

    private fun sendHardwareEnter(ic: android.view.inputmethod.InputConnection?) {
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    /**
     * Replaces the configured placeholder (e.g. "--value--") in the active text field
     * with the remote data received from the API or pre-saved custom text.
     * If the placeholder is empty/blank, replaces ALL text in the writing area.
     * When triggered repeatedly, updates the previous replacement with the new value
     * instead of clearing or reversing the field.
     */
    private fun executeRemoteTextReplacement(cm: CovertManager): Boolean {
        val ic = currentInputConnection ?: return false
        val placeholder = cm.replacePlaceholder.trim()
        val replacement = cm.getEffectiveReplacementValue().trim()
        if (replacement.isEmpty()) return false

        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(1000, 0)?.toString() ?: ""

        ic.beginBatchEdit()
        try {
            // Case 0: Option to replace current cursor line
            // Replaces the entire line that the cursor is on now after any trigger is activated
            if (cm.replaceCurrentLine) {
                val lastNewlineBefore = before.lastIndexOfAny(charArrayOf('\n', '\r'))
                val charsToDeleteBefore = if (lastNewlineBefore != -1) {
                    before.length - (lastNewlineBefore + 1)
                } else {
                    before.length
                }

                val firstNewlineAfter = after.indexOfAny(charArrayOf('\n', '\r'))
                val charsToDeleteAfter = if (firstNewlineAfter != -1) {
                    firstNewlineAfter
                } else {
                    after.length
                }

                if (charsToDeleteBefore > 0 || charsToDeleteAfter > 0) {
                    ic.deleteSurroundingText(charsToDeleteBefore, charsToDeleteAfter)
                }
                ic.commitText(replacement, 1)
                lastReplacedValue = replacement
                wordBuffer.clear()
                refreshTopBar()
                return true
            }

            // Case 1: If placeholder field was left empty, replace ALL text in the writing area
            if (placeholder.isEmpty()) {
                val totalBefore = before.length
                val totalAfter = after.length
                if (totalBefore > 0 || totalAfter > 0) {
                    ic.deleteSurroundingText(totalBefore, totalAfter)
                }
                ic.commitText(replacement, 1)
                lastReplacedValue = replacement
                wordBuffer.clear()
                refreshTopBar()
                return true
            }

            // Case 2: Standard placeholder match
            if (before.contains(placeholder)) {
                val idx = before.lastIndexOf(placeholder)
                val charsToStartOfPlaceholder = before.length - idx
                val suffix = before.substring(idx + placeholder.length)

                ic.deleteSurroundingText(charsToStartOfPlaceholder, 0)
                ic.commitText(replacement + suffix, 1)
                lastReplacedValue = replacement
                wordBuffer.clear()
                refreshTopBar()
                return true
            } else if (after.contains(placeholder)) {
                val idx = after.indexOf(placeholder)
                val charsToDeleteAfter = idx + placeholder.length
                val prefixAfterMatch = after.substring(0, idx)
                val suffixAfterMatch = after.substring(idx + placeholder.length)

                ic.deleteSurroundingText(0, charsToDeleteAfter)
                ic.commitText(prefixAfterMatch + replacement + suffixAfterMatch, 1)
                lastReplacedValue = replacement
                wordBuffer.clear()
                refreshTopBar()
                return true
            } else if ((before + after).contains(placeholder)) {
                val combined = before + after
                val idx = combined.indexOf(placeholder)
                if (idx != -1) {
                    val deleteBefore = (before.length - idx).coerceAtLeast(0)
                    val deleteAfter = ((idx + placeholder.length) - before.length).coerceAtLeast(0)
                    ic.deleteSurroundingText(deleteBefore, deleteAfter)
                    ic.commitText(replacement, 1)
                    lastReplacedValue = replacement
                    wordBuffer.clear()
                    refreshTopBar()
                    return true
                }
            }

            // Case 3: REPEAT TRIGGER SUPPORT
            // If placeholder is not found, but a previous replacement was made, replace the previous replacement with the new value
            if (lastReplacedValue.isNotEmpty()) {
                if (before.contains(lastReplacedValue)) {
                    val idx = before.lastIndexOf(lastReplacedValue)
                    val charsToStartOfVal = before.length - idx
                    val suffix = before.substring(idx + lastReplacedValue.length)

                    ic.deleteSurroundingText(charsToStartOfVal, 0)
                    ic.commitText(replacement + suffix, 1)
                    lastReplacedValue = replacement
                    wordBuffer.clear()
                    refreshTopBar()
                    return true
                } else if (after.contains(lastReplacedValue)) {
                    val idx = after.indexOf(lastReplacedValue)
                    val charsToDeleteAfter = idx + lastReplacedValue.length
                    val prefixAfterMatch = after.substring(0, idx)
                    val suffixAfterMatch = after.substring(idx + lastReplacedValue.length)

                    ic.deleteSurroundingText(0, charsToDeleteAfter)
                    ic.commitText(prefixAfterMatch + replacement + suffixAfterMatch, 1)
                    lastReplacedValue = replacement
                    wordBuffer.clear()
                    refreshTopBar()
                    return true
                } else if ((before + after).contains(lastReplacedValue)) {
                    val combined = before + after
                    val idx = combined.indexOf(lastReplacedValue)
                    if (idx != -1) {
                        val deleteBefore = (before.length - idx).coerceAtLeast(0)
                        val deleteAfter = ((idx + lastReplacedValue.length) - before.length).coerceAtLeast(0)
                        ic.deleteSurroundingText(deleteBefore, deleteAfter)
                        ic.commitText(replacement, 1)
                        lastReplacedValue = replacement
                        wordBuffer.clear()
                        refreshTopBar()
                        return true
                    }
                }
            }

            // Case 4: Field is empty, insert the replacement
            if (before.isEmpty() && after.isEmpty()) {
                ic.commitText(replacement, 1)
                lastReplacedValue = replacement
                wordBuffer.clear()
                refreshTopBar()
                return true
            }

            return false
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Called immediately after text replacement succeeds when Accessibility Service is not active,
     * to safely send the appropriate IME action or Enter key.
     */
    private fun triggerSearchAfterReplacement() {
        if (CovertAccessibilityService.isAccessibilityServiceEnabled(this)) {
            if (CovertAccessibilityService.clickActiveConfirmationButton()) {
                return
            }
        }

        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        val rawAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE

        if (rawAction != EditorInfo.IME_ACTION_NONE && rawAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
            val performed = ic.performEditorAction(rawAction)
            if (performed) return
        }

        // Try single standard search or send action
        if (ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) return
        if (ic.performEditorAction(EditorInfo.IME_ACTION_SEND)) return
        if (ic.performEditorAction(EditorInfo.IME_ACTION_DONE)) return

        // Single clean Enter key event as final fallback
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private var volumeKeyHandledByTrigger = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val volumeEnabled = TriggerManager.isVolumeTriggerEnabled(this)
            val magicActive = covertManager.isAnyMagicEffectActive()
            if (volumeEnabled && magicActive) {
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
                if (fired) {
                    volumeKeyHandledByTrigger = true
                    return true
                }
            }
            volumeKeyHandledByTrigger = false
            return super.onKeyDown(keyCode, event)
        } else if (TriggerManager.isEnterTriggerEnabled(this) &&
            (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
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
            TriggerManager.fireTrigger("Enter Hardware Key (IME)", this)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeKeyHandledByTrigger) {
                volumeKeyHandledByTrigger = false
                return true
            }
            return super.onKeyUp(keyCode, event)
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
    enum class Glyph { RETURN, SEARCH, SEND, GO, NEXT, PREVIOUS, DONE, BACKSPACE, SHIFT }

    var iconColor: Int = Color.BLACK
    /** Only used by Glyph.SHIFT - draws an underline bar beneath the arrow to indicate caps lock. */
    var locked: Boolean = false
    var isActive: Boolean = false
    var isRtl: Boolean = false

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
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = h * 0.11f

                if (isRtl) {
                    val tipX = w * 0.70f
                    val leftX = w * 0.30f
                    val hookTopY = h * 0.32f
                    val midY = h * 0.52f
                    val headSize = w * 0.16f

                    val path = Path().apply {
                        moveTo(leftX, hookTopY)
                        lineTo(leftX, midY)
                        lineTo(tipX, midY)
                    }
                    canvas.drawPath(path, paint)
                    canvas.drawLine(tipX, midY, tipX - headSize, midY - headSize, paint)
                    canvas.drawLine(tipX, midY, tipX - headSize, midY + headSize, paint)
                } else {
                    val tipX = w * 0.30f
                    val rightX = w * 0.70f
                    val hookTopY = h * 0.32f
                    val midY = h * 0.52f
                    val headSize = w * 0.16f

                    val path = Path().apply {
                        moveTo(rightX, hookTopY)
                        lineTo(rightX, midY)
                        lineTo(tipX, midY)
                    }
                    canvas.drawPath(path, paint)
                    canvas.drawLine(tipX, midY, tipX + headSize, midY - headSize, paint)
                    canvas.drawLine(tipX, midY, tipX + headSize, midY + headSize, paint)
                }
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
            Glyph.SEND -> {
                paint.style = Paint.Style.STROKE
                val path = Path().apply {
                    moveTo(w * 0.24f, h * 0.26f)
                    lineTo(w * 0.80f, h * 0.50f)
                    lineTo(w * 0.24f, h * 0.74f)
                    lineTo(w * 0.38f, h * 0.50f)
                    close()
                }
                canvas.drawPath(path, paint)
                canvas.drawLine(w * 0.38f, h * 0.50f, w * 0.80f, h * 0.50f, paint)
            }
            Glyph.GO -> {
                paint.style = Paint.Style.STROKE
                val startX = w * 0.25f
                val endX = w * 0.75f
                val midY = h * 0.50f
                canvas.drawLine(startX, midY, endX, midY, paint)
                val headSize = w * 0.18f
                canvas.drawLine(endX, midY, endX - headSize, midY - headSize, paint)
                canvas.drawLine(endX, midY, endX - headSize, midY + headSize, paint)
            }
            Glyph.NEXT -> {
                paint.style = Paint.Style.STROKE
                val startX = w * 0.22f
                val endX = w * 0.64f
                val midY = h * 0.50f
                canvas.drawLine(startX, midY, endX, midY, paint)
                val headSize = w * 0.16f
                canvas.drawLine(endX, midY, endX - headSize, midY - headSize, paint)
                canvas.drawLine(endX, midY, endX - headSize, midY + headSize, paint)
                canvas.drawLine(w * 0.76f, h * 0.32f, w * 0.76f, h * 0.68f, paint)
            }
            Glyph.PREVIOUS -> {
                paint.style = Paint.Style.STROKE
                val startX = w * 0.78f
                val endX = w * 0.36f
                val midY = h * 0.50f
                canvas.drawLine(startX, midY, endX, midY, paint)
                val headSize = w * 0.16f
                canvas.drawLine(endX, midY, endX + headSize, midY - headSize, paint)
                canvas.drawLine(endX, midY, endX + headSize, midY + headSize, paint)
                canvas.drawLine(w * 0.24f, h * 0.32f, w * 0.24f, h * 0.68f, paint)
            }
            Glyph.DONE -> {
                paint.style = Paint.Style.STROKE
                val p1X = w * 0.24f
                val p1Y = h * 0.52f
                val p2X = w * 0.42f
                val p2Y = h * 0.70f
                val p3X = w * 0.76f
                val p3Y = h * 0.32f
                canvas.drawLine(p1X, p1Y, p2X, p2Y, paint)
                canvas.drawLine(p2X, p2Y, p3X, p3Y, paint)
            }
            Glyph.BACKSPACE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = h * 0.088f

                if (isRtl) {
                    val tipX = w * 0.90f
                    val notchX = w * 0.65f
                    val leftX = w * 0.10f
                    val topY = h * 0.22f
                    val bottomY = h * 0.78f
                    val midY = h * 0.50f

                    val path = Path().apply {
                        moveTo(tipX, midY)
                        lineTo(notchX, topY)
                        lineTo(leftX, topY)
                        lineTo(leftX, bottomY)
                        lineTo(notchX, bottomY)
                        close()
                    }
                    canvas.drawPath(path, paint)

                    // In Gboard, the delete X is centered inside the body part of the badge
                    val bodyCenterX = (notchX + leftX) * 0.5f
                    val bodyCenterY = midY
                    val crossRadius = (notchX - leftX) * 0.28f
                    canvas.drawLine(bodyCenterX - crossRadius, bodyCenterY - crossRadius, bodyCenterX + crossRadius, bodyCenterY + crossRadius, paint)
                    canvas.drawLine(bodyCenterX + crossRadius, bodyCenterY - crossRadius, bodyCenterX - crossRadius, bodyCenterY + crossRadius, paint)
                } else {
                    val tipX = w * 0.10f
                    val notchX = w * 0.35f
                    val rightX = w * 0.90f
                    val topY = h * 0.22f
                    val bottomY = h * 0.78f
                    val midY = h * 0.50f

                    val path = Path().apply {
                        moveTo(tipX, midY)
                        lineTo(notchX, topY)
                        lineTo(rightX, topY)
                        lineTo(rightX, bottomY)
                        lineTo(notchX, bottomY)
                        close()
                    }
                    canvas.drawPath(path, paint)

                    // Centered square X inside the body portion of the key tag
                    val bodyCenterX = (notchX + rightX) * 0.5f
                    val bodyCenterY = midY
                    val crossRadius = (rightX - notchX) * 0.28f
                    canvas.drawLine(bodyCenterX - crossRadius, bodyCenterY - crossRadius, bodyCenterX + crossRadius, bodyCenterY + crossRadius, paint)
                    canvas.drawLine(bodyCenterX + crossRadius, bodyCenterY - crossRadius, bodyCenterX - crossRadius, bodyCenterY + crossRadius, paint)
                }
            }
            Glyph.SHIFT -> {
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = h * 0.088f

                // Gboard style arrow: wider balanced proportions with clean vertical stem
                val midX = w * 0.50f
                val topY = h * 0.15f
                val arrowWingsY = h * 0.48f
                val arrowWingLeft = w * 0.16f
                val arrowWingRight = w * 0.84f
                val stemLeft = w * 0.35f
                val stemRight = w * 0.65f
                val stemBottom = if (locked) h * 0.70f else h * 0.82f

                val path = Path().apply {
                    moveTo(midX, topY)
                    lineTo(arrowWingLeft, arrowWingsY)
                    lineTo(stemLeft, arrowWingsY)
                    lineTo(stemLeft, stemBottom)
                    lineTo(stemRight, stemBottom)
                    lineTo(stemRight, arrowWingsY)
                    lineTo(arrowWingRight, arrowWingsY)
                    close()
                }

                if (isActive) {
                    paint.style = Paint.Style.FILL_AND_STROKE
                    canvas.drawPath(path, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    canvas.drawPath(path, paint)
                }

                if (locked) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = h * 0.088f
                    val barY = h * 0.85f
                    canvas.drawLine(w * 0.24f, barY, w * 0.76f, barY, paint)
                }
            }
        }
    }
}
