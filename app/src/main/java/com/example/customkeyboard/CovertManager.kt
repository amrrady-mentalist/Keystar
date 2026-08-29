package com.example.customkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CovertManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("covert_engine_prefs", Context.MODE_PRIVATE)

    data class CovertPreset(
        val id: String,
        val category: String,
        val label: String,
        val text: String
    )

    var isCovertActive: Boolean
        get() = prefs.getBoolean("key_covert_active", false)
        set(value) {
            prefs.edit().putBoolean("key_covert_active", value).apply()
        }

    var targetText: String
        get() = prefs.getString("key_covert_target", "Queen of Hearts") ?: "Queen of Hearts"
        set(value) {
            prefs.edit().putString("key_covert_target", value).apply()
        }

    var currentIndex: Int
        get() = prefs.getInt("key_covert_index", 0)
        set(value) {
            prefs.edit().putInt("key_covert_index", value).apply()
        }

    var autoDisarmOnFinish: Boolean
        get() = prefs.getBoolean("key_auto_disarm", true)
        set(value) {
            prefs.edit().putBoolean("key_auto_disarm", value).apply()
        }

    var stealthSpacebarTrigger: Boolean
        get() = prefs.getBoolean("key_spacebar_trigger", true)
        set(value) {
            prefs.edit().putBoolean("key_spacebar_trigger", value).apply()
        }

    var secretPin: String
        get() = prefs.getString("key_secret_pin", "") ?: ""
        set(value) {
            prefs.edit().putString("key_secret_pin", value).apply()
        }

    var stealthHapticFeedback: Boolean
        get() = prefs.getBoolean("key_haptic_feedback", true)
        set(value) {
            prefs.edit().putBoolean("key_haptic_feedback", value).apply()
        }

    fun resetIndex() {
        currentIndex = 0
    }

    fun armCovert(text: String? = null) {
        if (text != null && text.isNotEmpty()) {
            targetText = text
        }
        resetIndex()
        isCovertActive = true
        triggerStealthVibrate(doublePulse = true)
    }

    fun disarmCovert() {
        isCovertActive = false
        resetIndex()
        triggerStealthVibrate(doublePulse = false)
    }

    fun toggleCovert(): Boolean {
        if (isCovertActive) {
            disarmCovert()
            return false
        } else {
            armCovert()
            return true
        }
    }

    /**
     * Progressive character spoofing.
     * Returns the next character in sequence from the target string.
     */
    fun getNextChar(): Char? {
        if (!isCovertActive) return null
        val target = targetText
        if (target.isEmpty()) return null

        val idx = currentIndex
        if (idx < target.length) {
            val c = target[idx]
            val nextIdx = idx + 1
            currentIndex = nextIdx

            // Check if finished
            if (nextIdx >= target.length && autoDisarmOnFinish) {
                isCovertActive = false
                currentIndex = 0
                triggerStealthVibrate(doublePulse = false)
            }
            return c
        } else {
            if (autoDisarmOnFinish) {
                isCovertActive = false
                currentIndex = 0
            }
            return null
        }
    }

    /**
     * Steps back the progressive index when backspace is pressed.
     */
    fun stepBack() {
        if (!isCovertActive) return
        val idx = currentIndex
        if (idx > 0) {
            currentIndex = idx - 1
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
                        category = obj.optString("category", "General"),
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
            CovertPreset(UUID.randomUUID().toString(), "Playing Cards", "Queen of Hearts", "Queen of Hearts ♥"),
            CovertPreset(UUID.randomUUID().toString(), "Playing Cards", "Ace of Spades", "Ace of Spades ♠"),
            CovertPreset(UUID.randomUUID().toString(), "Playing Cards", "King of Diamonds", "King of Diamonds ♦"),
            CovertPreset(UUID.randomUUID().toString(), "PIN & Numbers", "PIN Force", "7492"),
            CovertPreset(UUID.randomUUID().toString(), "PIN & Numbers", "Birth Year", "1994"),
            CovertPreset(UUID.randomUUID().toString(), "Mentalism Words", "Book Test Word", "Serendipity"),
            CovertPreset(UUID.randomUUID().toString(), "Destinations", "City Force", "Tokyo, Japan 🗼"),
            CovertPreset(UUID.randomUUID().toString(), "Zodiac Signs", "Scorpio", "Scorpio ♏")
        )
        savePresets(defaults)
        return defaults
    }
}
