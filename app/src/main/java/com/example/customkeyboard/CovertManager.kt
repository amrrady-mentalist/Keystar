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
        
        // Completed lines are lines before the current active line being typed
        val previousLines = if (lines.size > 1) lines.dropLast(1) else emptyList()
        // Non-empty completed lines (ignoring blank lines or lines with only spaces)
        val completedNonEmptyLines = previousLines.filter { it.trim().isNotEmpty() }

        // -------------------------------------------------------------
        // Phase 1: Covert Line (Performer Secret Input)
        // -------------------------------------------------------------
        // If there are no completed non-empty lines before the cursor,
        // we are on the Covert Line (Line 1).
        if (completedNonEmptyLines.isEmpty()) {
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

                    // Auto-dispatch to Inject API if configured
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
                // If typing continues after sentence and period, output spaces or normal char
                return if (originalText == " ") " " else originalText
            }
        }

        // -------------------------------------------------------------
        // Phase 2: Spectator Lines (Multi-line Acrostic / Column Reveal)
        // -------------------------------------------------------------
        // Disregard any empty lines or lines with only whitespace.
        val secret = capturedSecretWord
        if (secret.isEmpty()) {
            return originalText
        }

        // The first non-empty line was the Covert Line (index 0 in completedNonEmptyLines).
        // Spectator line 1 is when completedNonEmptyLines.size == 1 (index 0 of secret word)
        // Spectator line 2 is when completedNonEmptyLines.size == 2 (index 1 of secret word), etc.
        val spectatorIndex = completedNonEmptyLines.size - 1

        if (spectatorIndex < 0 || spectatorIndex >= secret.length) {
            // All letters of the secret word have already been revealed on previous lines
            return originalText
        }

        val targetSecretChar = secret[spectatorIndex]

        // Current active line being typed
        val currentLineRaw = lines.lastOrNull() ?: ""
        // Count how many non-whitespace characters have already been typed on this current line
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
            // Capitalize if it's the first letter of the line or if the secret character is uppercase
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

        val fullText = textBeforeCursor?.toString() ?: ""
        val lines = fullText.split('\n')
        val previousLines = if (lines.size > 1) lines.dropLast(1) else emptyList()
        val completedNonEmptyLines = previousLines.filter { it.trim().isNotEmpty() }

        if (completedNonEmptyLines.isEmpty()) {
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
    }

    /**
     * Dispatches the captured secret word to the configured Inject API.
     * Schema format matches:
     * {"count":1417,"value":"<secret_word>","receiveCount":463,"source":"phone","thumperId":1,"ai":null}
     */
    fun dispatchInjectApi(word: String, onResult: ((Boolean, String) -> Unit)? = null) {
        var endpoint = injectApiUrl.trim()
        if (endpoint.isEmpty()) {
            onResult?.invoke(false, "API URL is empty")
            return
        }

        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "https://$endpoint"
        }

        val secretPayload = word.trim()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.doOutput = true
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                if (injectApiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${injectApiKey.trim()}")
                }

                val payload = JSONObject().apply {
                    put("count", 1417)
                    put("value", secretPayload)
                    put("receiveCount", 463)
                    put("source", "phone")
                    put("thumperId", 1)
                    put("ai", JSONObject.NULL)
                    put("word", secretPayload)
                    put("secret", secretPayload)
                }

                val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payloadBytes.size)

                connection.outputStream.use { os ->
                    os.write(payloadBytes)
                    os.flush()
                }

                val code = connection.responseCode
                val isSuccess = code in 200..299

                val responseBody = try {
                    val stream = if (isSuccess) connection.inputStream else connection.errorStream
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }

                val msg = if (responseBody.isNotEmpty()) {
                    "HTTP $code: ${responseBody.take(80)}"
                } else {
                    "HTTP $code ${connection.responseMessage ?: ""}".trim()
                }

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
