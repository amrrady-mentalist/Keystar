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
import android.text.TextPaint
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
    private var lastAltMode = Mode.SYMBOLS
    private var shiftOn = false
    private var capsLock = false
    private var lastShiftTapTime = 0L
    private var symbolsPage = 1
    private val wordBuffer = StringBuilder()
    private var lastCommittedWord = ""
    private var selectedClipboardEffectTab = "covert"

    // In-bar voice typing state
    private var isVoiceListening = false
    private var voiceDisplayText = ""
    private var voiceIsStatusPrompt = true
    private var uncommittedVoiceText = ""
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
    private var lastSeenScreenshotUri: Uri? = null
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
    // Wide/descender Arabic letters whose sweeping tails, deep bowls, or bottom dots require optical proportional sizing
    private val wideArabicLetters = setOf("ص", "ض", "س", "ش", "ي", "ى", "ئ")

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

    private fun getLetterFontSize(label: String? = null, isArabic: Boolean = (currentLang == Lang.AR)): Float {
        val isSystemFont = prefs.getString("font_style", "bold") == "system"
        val isWide = isArabic && label != null && label in wideArabicLetters
        val baseSize = when (prefs.getString("key_font_size", "normal")) {
            "small" -> if (isWide) 18.5f else 19f
            "large" -> if (isWide) 24.5f else 26.5f
            "extra_large" -> if (isWide) 25.5f else 30.5f
            else -> if (isWide) 22f else 23f
        }
        return if (isSystemFont) baseSize + (if (isWide) 0.5f else 1f) else baseSize
    }

    /**
     * Dynamically verifies that a key's letter fits completely inside the key's printable area
     * across any screen width, font scale, or key-inset setting without clipping any glyph strokes.
     */
    private fun computeEffectiveKeyFontSize(
        label: String,
        baseFontSize: Float,
        isArabic: Boolean,
        weight: Float
    ): Float {
        if (!isArabic || label.isEmpty()) return baseFontSize
        return try {
            val testPaint = TextPaint().apply {
                typeface = getKeyTypeface()
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    baseFontSize,
                    resources.displayMetrics
                )
            }
            val textWidth = testPaint.measureText(label)
            val metrics = testPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent

            val screenWidthPx = resources.displayMetrics.widthPixels
            // In standard Arabic phone layouts, letter rows contain 11 keys across
            val estKeyWidthPx = (screenWidthPx / 11f) * weight
            val hInset = dp(getKeyInsetHDp())
            // Safe horizontal printable width inside the key background with padding
            val safeWidthPx = estKeyWidthPx - (hInset * 2 + dpF(3.5f))
            // Safe vertical printable height inside the key
            val safeHeightPx = dp(getRowHeightDp()) - dp(7)

            var scale = 1f
            if (textWidth > 0f && textWidth > safeWidthPx) {
                scale = minOf(scale, safeWidthPx / textWidth)
            }
            if (textHeight > 0f && textHeight > safeHeightPx) {
                scale = minOf(scale, safeHeightPx / textHeight)
            }
            baseFontSize * scale
        } catch (e: Exception) {
            baseFontSize
        }
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
                lastSeenScreenshotUri = null
                if (!userStartedTyping) {
                    refreshTopBar()
                }
            } else {
                val text = item.coerceToText(this).toString()
                if (text.isNotBlank()) {
                    clipHistory.add(text)
                    pendingClipText = text
                    pendingClipTime = System.currentTimeMillis()
                    lastPastedClipText = null
                    if (!userStartedTyping) {
                        refreshTopBar()
                    }
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
                            if (uri != pendingScreenshotUri && uri != lastSeenScreenshotUri) {
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
                    if (uri != lastSeenScreenshotUri) {
                        pendingScreenshotUri = uri
                        pendingScreenshotTime = System.currentTimeMillis()
                    }
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
            pendingClipText?.let { lastPastedClipText = it }
            pendingScreenshotUri?.let { lastSeenScreenshotUri = it }
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

        fun formatCopiedTextPreview(text: String): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            return clean.take(4) + "..."
        }
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
        val savedAltMode = prefs.getString("last_alt_mode", Mode.SYMBOLS.name)
        lastAltMode = try {
            val m = Mode.valueOf(savedAltMode ?: Mode.SYMBOLS.name)
            if (m == Mode.NUMBERS || m == Mode.SYMBOLS) m else Mode.SYMBOLS
        } catch (e: Exception) {
            Mode.SYMBOLS
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
        if (!restarting) {
            userStartedTyping = false
            checkPrimaryClipOnInputStart()
            checkRecentScreenshot()
        }
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
                rootContainer.addView(buildSymbolsRow0())
                rootContainer.addView(buildSymbolsRow1())
                rootContainer.addView(buildSymbolsRow2())
                rootContainer.addView(buildSymbolsBottomRow())
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

        if (currentMode != Mode.CLIPBOARD && currentMode != Mode.NUMBERS && currentMode != Mode.SYMBOLS) {
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

    // Rebuilds only the suggestion bar and the letter/number rows (for shift-state
    // re-casing), leaving the bottom row, window chrome, and popup theme untouched.
    // A full render() tears down and reinflates the *entire* keyboard — every key,
    // every listener, every background — which was firing on every single letter
    // typed right after auto-capitalization (i.e. the start of nearly every
    // sentence). That was a major source of visible lag, and the resulting dropped
    // frames were also why key-preview popups sometimes read a not-yet-laid-out
    // key position and rendered bunched up at the top instead of above the key.
    private fun refreshAfterShiftAutoOff() {
        refreshTopBar()
        if (currentMode != Mode.LETTERS) return
        if (!::topBarContainer.isInitialized || !::rootContainer.isInitialized) return
        val topBarIndex = rootContainer.indexOfChild(topBarContainer)
        if (topBarIndex < 0) return
        val bottomRowIndex = rootContainer.childCount - 1
        if (bottomRowIndex <= topBarIndex) return
        for (i in bottomRowIndex - 1 downTo topBarIndex + 1) {
            rootContainer.removeViewAt(i)
        }
        var insertAt = topBarIndex + 1
        if (currentLang == Lang.AR) {
            rootContainer.addView(buildArabicNumberRow(), insertAt++)
            rootContainer.addView(buildArabicLetterRow(0), insertAt++)
            rootContainer.addView(buildArabicLetterRow(1), insertAt++)
            rootContainer.addView(buildArabicLetterRow2WithBackspace(), insertAt++)
        } else {
            rootContainer.addView(buildRow(KeyboardLayoutData.numberRow, isLetterRow = true), insertAt++)
            rootContainer.addView(buildEnglishLetterRow0(), insertAt++)
            rootContainer.addView(buildEnglishLetterRow1(), insertAt++)
            rootContainer.addView(buildEnglishLetterRow2WithShiftAndBackspace(), insertAt++)
        }
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
            text = if (voiceDisplayText.isNotEmpty()) {
                voiceDisplayText
            } else {
                if (currentLang == Lang.AR) "جارٍ الاستماع... تكلّم الآن" else "Listening... Speak now"
            }
            setTextColor(if (!voiceIsStatusPrompt && voiceDisplayText.isNotEmpty()) textColor() else textSecondaryColor())
            textSize = 14f
            setTypeface(Typeface.DEFAULT, if (!voiceIsStatusPrompt && voiceDisplayText.isNotEmpty()) Typeface.BOLD else Typeface.ITALIC)
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
            voiceIsStatusPrompt = false
            voiceDisplayText = text.trim()
            uncommittedVoiceText = ""
            refreshTopBar()
        }
    }

    private fun triggerVoiceInput() {
        if (isVoiceListening) {
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
        voiceDisplayText = ""
        voiceIsStatusPrompt = true
        uncommittedVoiceText = ""
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
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                stopVoiceTyping(cancel = true)
                                val intent = Intent(this@CustomKeyboardService, VoicePermissionActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                startActivity(intent)
                            }
                            else -> {
                                // Every other error (no match, speech timeout, client, busy,
                                // network, audio, server, disconnected, unsupported/unavailable
                                // language, etc.) is treated as transient — e.g. someone nearby
                                // spoke in a language the recognizer doesn't understand, so that
                                // bit just gets dismissed and listening keeps going, still only
                                // ever committing what it can recognize in the supported
                                // language. Keep the mic session alive for the full configured
                                // time frame by retrying instead of silently giving up and
                                // requiring a manual tap.
                                voiceIsStatusPrompt = true
                                voiceDisplayText = if (currentLang == Lang.AR) "تكلّم الآن..." else "Listening..."
                                uncommittedVoiceText = ""
                                refreshTopBar()
                                voiceHandler.postDelayed({
                                    if (isVoiceListening) {
                                        restartListeningIfActive()
                                    }
                                }, 350)
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
                                voiceIsStatusPrompt = false
                                voiceDisplayText = recognized
                                uncommittedVoiceText = ""
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
                                voiceIsStatusPrompt = false
                                voiceDisplayText = partial
                                uncommittedVoiceText = partial
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
            voiceIsStatusPrompt = true
            voiceDisplayText = if (currentLang == Lang.AR) "اضغط على الميكروفون للتحدث" else "Tap mic to speak"
            uncommittedVoiceText = ""
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
                stopVoiceTyping(cancel = false)
            }
        }
        voiceTimeoutRunnable = runnable
        voiceHandler.postDelayed(runnable, timeoutSeconds * 1000L)
    }

    private fun commitVoicePreview() {
        if (!voiceIsStatusPrompt && uncommittedVoiceText.isNotBlank()) {
            val textToInsert = "${uncommittedVoiceText.trim()} "
            currentInputConnection?.commitText(textToInsert, 1)
            uncommittedVoiceText = ""
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
        voiceDisplayText = ""
        voiceIsStatusPrompt = true
        uncommittedVoiceText = ""
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
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val normalBg = ColorDrawable(Color.TRANSPARENT)
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
            val iconSize = dp(16)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(6)
            }
        }
        container.addView(icon)

        val display = formatCopiedTextPreview(text)
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
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val normalBg = ColorDrawable(Color.TRANSPARENT)
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
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                            marginEnd = dp(6)
                        }
                        background = roundedDrawable(Color.TRANSPARENT, dp(4))
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
            this.text = if (currentLang == Lang.AR) "لقطة شاشة" else "Screenshot"
            setTextColor(textColor())
            setTypeface(getKeyTypeface())
            textSize = 13f
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        container.addView(tv)

        container.setOnClickListener {
            container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            sendScreenshotToChat(uri)
            lastSeenScreenshotUri = uri
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
                        title = "Send to Inje
