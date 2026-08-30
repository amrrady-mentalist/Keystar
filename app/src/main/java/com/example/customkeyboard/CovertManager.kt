package com.example.customkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class CovertManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("covert_engine_prefs", Context.MODE_PRIVATE)

    data class CovertPreset(
        val id: String,
        val category: String,
        val label: String,
        val text: String
    )

    // Master armed state
    var isCovertActive: Boolean
        get() = prefs.getBoolean("key_covert_active", false)
        set(value) {
            prefs.edit().putBoolean("key_covert_active", value).apply()
        }

    // Pre-saved cover sentence displayed on line 1
    var coverSentence: String
        get() = prefs.getString("key_cover_sentence", "Today we need to review all the project requirements.") ?: "Today we need to review all the project requirements."
        set(value) {
            prefs.edit().putString("key_cover_sentence", value).apply()
        }

    // Position of cover sentence character
    var coverSentenceIndex: Int
        get() = prefs.getInt("key_cover_sentence_index", 0)
        set(value) {
            prefs.edit().putInt("key_cover_sentence_index", value).apply()
        }

    // Captured secret word from double-space gesture (e.g., "elephant" or "Sam")
    var capturedSecretWord: String
        get() = prefs.getString("key_captured_secret_word", "Sam") ?: "Sam"
        set(value) {
            prefs.edit().putString("key_captured_secret_word", value).apply()
        }

    // Has the secret word been captured during this active covert session?
    var isSecretWordCaptured: Boolean
        get() = prefs.getBoolean("key_is_secret_captured", false)
        set(value) {
            prefs.edit().putBoolean("key_is_secret_captured", value).apply()
        }

    // Reveal position on spectator lines: 0 = 1st letter, 1 = 2nd letter, 2 = 3rd letter, -1 = Last letter
    var revealLetterPosition: Int
        get() = prefs.getInt("key_reveal_letter_pos", 0)
        set(value) {
            prefs.edit().putInt("key_reveal_letter_pos", value).apply()
        }

    // Inject API settings
    var isInjectApiEnabled: Boolean
        get() = prefs.getBoolean("key_inject_api_enabled", false)
        set(value) {
            prefs.edit().putBoolean("key_inject_api_enabled", value).apply()
        }

    var injectApiUrl: String
        get() = prefs.getString("key_inject_api_url", "https://api.inject.app/v1/event") ?: "https://api.inject.app/v1/event"
        set(value) {
            prefs.edit().putString("key_inject_api_url", value).apply()
        }

    var injectApiKey: String
        get() = prefs.getString("key_inject_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("key_inject_api_key", value).apply()
        }

    // Stealth mechanics
    var stealthSpacebarTrigger: Boolean
        get() = prefs.getBoolean("key_spacebar_trigger", true)
        set(value) {
            prefs.edit().putBoolean("key_spacebar_trigger", value).apply()
        }

    var stealthHapticFeedback: Boolean
        get() = prefs.getBoolean("key_haptic_feedback", true)
        set(value) {
            prefs.edit().putBoolean("key_haptic_feedback", value).apply()
        }

    // In-memory runtime state for live typing session
    private val rawSecretInputBuffer = StringBuilder()
    private var consecutiveSpaceCount = 0
    private var covertLineContent: String = ""
    private var hasFinalizedPeriod = false

    fun armCovert(newCoverSentence: String? = null) {
        if (!newCoverSentence.isNullOrBlank()) {
            coverSentence = newCoverSentence
        }
        resetSession()
        isCovertActive = true
        triggerStealthVibrate(doublePulse = true)
    }

    fun disarmCovert() {
        isCovertActive = false
        resetSession()
        triggerStealthVibrate(doublePulse = false)
    }

    fun toggleCovert(): Boolean {
        return if (isCovertActive) {
            disarmCovert()
            false
        } else {
            armCovert()
            true
        }
    }

    fun resetSession() {
        coverSentenceIndex = 0
        isSecretWordCaptured = false
        rawSecretInputBuffer.clear()
        consecutiveSpaceCount = 0
        covertLineContent = ""
        hasFinalizedPeriod = false
    }

    /**
     * Core Covert Typing Processor.
     * Determines what character should actually be committed to the InputConnection.
     *
     * @param originalText The raw text the user typed (letter, space, or symbol).
     * @param isLetter Whether the key pressed is a letter.
     * @param textBeforeCursor The text currently before the cursor in the input field.
     * @return String to commit to InputConnection.
     */
    fun processCommit(
        originalText: String,
        isLetter: Boolean,
        textBeforeCursor: CharSequence?
    ): String {
        if (!isCovertActive) return originalText

        val fullText = textBeforeCursor?.toString() ?: ""
        val lines = fullText.split('\n')

        // -------------------------------------------------------------
        // Phase 1: Line 1 (Covert Line - Performer Secret Input)
        // -------------------------------------------------------------
        // If the secret word hasn't been captured yet, or if we are still on
        // the initial covert line (no subsequent spectator lines entered yet):
        if (!isSecretWordCaptured || lines.size <= 1 || covertLineContent.isEmpty()) {
            val sentence = coverSentence.ifEmpty { "Shopping list for today:" }

            if (originalText == " ") {
                consecutiveSpaceCount++
                if (consecutiveSpaceCount >= 2 && !isSecretWordCaptured) {
                    // Double space detected! Capture the word typed before the spaces.
                    val secretWord = rawSecretInputBuffer.toString().trim()
                    if (secretWord.isNotEmpty()) {
                        capturedSecretWord = secretWord
                    }
                    isSecretWordCaptured = true
                    triggerStealthVibrate(doublePulse = true)

                    // Dispatch to Inject API if configured
                    if (isInjectApiEnabled && capturedSecretWord.isNotEmpty()) {
                        dispatchInjectApi(capturedSecretWord)
                    }
                }
            } else {
                consecutiveSpaceCount = 0
                if (!isSecretWordCaptured && isLetter) {
                    rawSecretInputBuffer.append(originalText)
                }
            }

            // Output the next character from the pre-saved cover sentence
            val idx = coverSentenceIndex
            if (idx < sentence.length) {
                val nextChar = sentence[idx]
                coverSentenceIndex = idx + 1
                return nextChar.toString()
            } else if (!hasFinalizedPeriod) {
                // When pre-saved sentence finishes, add a period '.' so the performer
                // knows this is the last letter to finalize the sentence.
                hasFinalizedPeriod = true
                coverSentenceIndex = idx + 1
                return "."
            } else {
                // If the user continues typing after the sentence and period, output spaces or normal char
                return if (originalText == " ") " " else originalText
            }
        }

        // -------------------------------------------------------------
        // Phase 2: Spectator Lines (Multi-line Acrostic / Column Reveal)
        // -------------------------------------------------------------
        // Disregard any empty lines or lines with only whitespace.
        // Identify all non-empty lines in the text before cursor.
        val nonEmptyLines = mutableListOf<String>()
        for (line in lines) {
            if (line.trim().isNotEmpty()) {
                nonEmptyLines.add(line)
            }
        }

        val secret = capturedSecretWord
        if (secret.isEmpty()) {
            return originalText
        }

        // Current line text being typed (the last line in the textBeforeCursor)
        val currentLineRaw = lines.lastOrNull() ?: ""
        val currentLineTrimmed = currentLineRaw.trim()

        // How many non-empty lines exist BEFORE this current line?
        // Note: The first non-empty line was the Covert Line.
        // Spectator line 1 is the 2nd non-empty line (index 1), Spectator line 2 is index 2, etc.
        val previousNonEmptyCount = if (currentLineTrimmed.isEmpty()) {
            nonEmptyLines.size // We are on a fresh line, so all previous non-empty lines are counted
        } else {
            (nonEmptyLines.size - 1).coerceAtLeast(0)
        }

        // Spectator index (0 for 1st spectator line, 1 for 2nd spectator line, etc.)
        val spectatorIndex = (previousNonEmptyCount - 1).coerceAtLeast(0)

        if (spectatorIndex >= secret.length) {
            // All letters of the secret word have already been revealed on previous lines
            return originalText
        }

        val targetSecretChar = secret[spectatorIndex]

        // Count how many non-space characters have already been typed on this current line
        val currentLineLetterCount = currentLineRaw.count { !it.isWhitespace() }

        val revealPos = revealLetterPosition
        val shouldForceOnThisChar = when (revealPos) {
            0 -> currentLineLetterCount == 0 // 1st letter of line (Acrostic)
            1 -> currentLineLetterCount == 1 // 2nd letter of line
            2 -> currentLineLetterCount == 2 // 3rd letter of line
            3 -> currentLineLetterCount == 3 // 4th letter of line
            else -> currentLineLetterCount == 0 // Default to 1st letter
        }

        if (shouldForceOnThisChar && isLetter) {
            // Capitalize if it's the first letter of the line or if the secret letter is capitalized
            val formattedChar = if (revealPos == 0 || targetSecretChar.isUpperCase()) {
                targetSecretChar.uppercaseChar()
            } else {
                targetSecretChar.lowercaseChar()
            }
            return formattedChar.toString()
        }

        return originalText
    }

    /**
     * Handle backspace step back.
     */
    fun handleBackspace(textBeforeCursor: CharSequence?) {
        if (!isCovertActive) return

        if (!isSecretWordCaptured) {
            if (rawSecretInputBuffer.isNotEmpty()) {
                rawSecretInputBuffer.deleteCharAt(rawSecretInputBuffer.length - 1)
            }
            if (consecutiveSpaceCount > 0) {
                consecutiveSpaceCount--
            }
            if (coverSentenceIndex > 0) {
                coverSentenceIndex--
            }
        } else {
            if (coverSentenceIndex > 0) {
                coverSentenceIndex--
            }
        }
    }

    /**
     * Dispatches the captured secret word to the configured Inject API.
     */
    fun dispatchInjectApi(word: String, onResult: ((Boolean, String) -> Unit)? = null) {
        val endpoint = injectApiUrl.trim()
        if (endpoint.isEmpty()) {
            onResult?.invoke(false, "API URL is empty")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.setRequestProperty("Accept", "application/json")
                if (injectApiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${injectApiKey.trim()}")
                }

                val payload = JSONObject().apply {
                    put("word", word)
                    put("value", word)
                    put("secret", word)
                    put("timestamp", System.currentTimeMillis())
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = connection.responseCode
                val isSuccess = code in 200..299
                val msg = "HTTP $code"
                connection.disconnect()
                onResult?.invoke(isSuccess, msg)
            } catch (e: Exception) {
                onResult?.invoke(false, e.localizedMessage ?: "Connection error")
            }
        }
    }

    private fun triggerStealthVibrate(doublePulse: Boolean) {
        if (!stealthHapticFeedback) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (doublePulse) {
                    val timings = longArrayOf(0, 35, 60, 35)
                    val amplitudes = intArrayOf(0, 180, 0, 180)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(45, 120))
                }
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Presets Management ----------

    fun getPresets(): List<CovertPreset> {
        val json = prefs.getString("key_presets_list", null) ?: return defaultPresets()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<CovertPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CovertPreset(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        category = obj.optString("category", "Cover Sentences"),
                        label = obj.optString("label", ""),
                        text = obj.optString("text", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            defaultPresets()
        }
    }

    fun savePresets(presets: List<CovertPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("category", p.category)
            obj.put("label", p.label)
            obj.put("text", p.text)
            arr.put(obj)
        }
        prefs.edit().putString("key_presets_list", arr.toString()).apply()
    }

    fun addPreset(category: String, label: String, text: String) {
        val list = getPresets().toMutableList()
        list.add(CovertPreset(UUID.randomUUID().toString(), category, label, text))
        savePresets(list)
    }

    fun deletePreset(id: String) {
        val list = getPresets().filter { it.id != id }
        savePresets(list)
    }

    private fun defaultPresets(): List<CovertPreset> {
        val defaults = listOf(
            CovertPreset(UUID.randomUUID().toString(), "Cover Sentences", "Shopping List", "Shopping list for the week: milk, eggs, bread and coffee."),
            CovertPreset(UUID.randomUUID().toString(), "Cover Sentences", "Meeting Agenda", "Today we need to review all the project requirements."),
            CovertPreset(UUID.randomUUID().toString(), "Cover Sentences", "Recipe Notes", "Preheat oven to 350 degrees and mix the dry ingredients."),
            CovertPreset(UUID.randomUUID().toString(), "Cover Sentences", "Book Notes", "Chapter summary and key concepts from the reading assignment."),
            CovertPreset(UUID.randomUUID().toString(), "Secret Words", "Sam", "Sam"),
            CovertPreset(UUID.randomUUID().toString(), "Secret Words", "Elephant", "elephant"),
            CovertPreset(UUID.randomUUID().toString(), "Secret Words", "Paris", "Paris"),
            CovertPreset(UUID.randomUUID().toString(), "Secret Words", "Ace", "Ace")
        )
        savePresets(defaults)
        return defaults
    }
}
