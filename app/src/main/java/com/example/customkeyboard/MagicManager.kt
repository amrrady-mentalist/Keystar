package com.example.customkeyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MagicManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("magic_trick_prefs", Context.MODE_PRIVATE)

    data class PresetNote(
        val id: String,
        val title: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ShortcutItem(
        val id: String,
        val shortcut: String,
        val expansion: String
    )

    // Force Typing Properties
    var isForceEnabled: Boolean
        get() = prefs.getBoolean("key_force_enabled", false)
        set(value) = prefs.edit().putBoolean("key_force_enabled", value).apply()

    var forceText: String
        get() = prefs.getString("key_force_text", "Ace of Spades") ?: "Ace of Spades"
        set(value) {
            prefs.edit().putString("key_force_text", value).apply()
        }

    var forceIndex: Int
        get() = prefs.getInt("key_force_index", 0)
        set(value) = prefs.edit().putInt("key_force_index", value).apply()

    var triggerOnSpaceLongPress: Boolean
        get() = prefs.getBoolean("key_trigger_space_long_press", true)
        set(value) = prefs.edit().putBoolean("key_trigger_space_long_press", value).apply()

    fun resetForceIndex() {
        forceIndex = 0
    }

    /**
     * Retrieves the next character to progressively force.
     * Advances the internal index automatically.
     */
    fun getNextForceChar(): Char? {
        val target = forceText
        if (target.isEmpty()) return null
        val idx = forceIndex
        if (idx < target.length) {
            val char = target[idx]
            forceIndex = idx + 1
            return char
        }
        return null
    }

    /**
     * Steps back the force index when backspace is pressed.
     */
    fun stepBackForceChar() {
        val idx = forceIndex
        if (idx > 0) {
            forceIndex = idx - 1
        }
    }

    // ---------- Preset Notes Management ----------

    fun getPresetNotes(): List<PresetNote> {
        val json = prefs.getString("key_preset_notes", null) ?: return defaultPresetNotes()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<PresetNote>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PresetNote(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        content = obj.optString("content", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            defaultPresetNotes()
        }
    }

    private fun savePresetNotes(notes: List<PresetNote>) {
        val arr = JSONArray()
        notes.forEach { note ->
            val obj = JSONObject()
            obj.put("id", note.id)
            obj.put("title", note.title)
            obj.put("content", note.content)
            obj.put("timestamp", note.timestamp)
            arr.put(obj)
        }
        prefs.edit().putString("key_preset_notes", arr.toString()).apply()
    }

    fun addPresetNote(title: String, content: String) {
        val current = getPresetNotes().toMutableList()
        current.add(0, PresetNote(id = UUID.randomUUID().toString(), title = title, content = content))
        savePresetNotes(current)
    }

    fun updatePresetNote(id: String, title: String, content: String) {
        val current = getPresetNotes().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(title = title, content = content)
            savePresetNotes(current)
        }
    }

    fun deletePresetNote(id: String) {
        val current = getPresetNotes().filter { it.id != id }
        savePresetNotes(current)
    }

    private fun defaultPresetNotes(): List<PresetNote> {
        val list = listOf(
            PresetNote(UUID.randomUUID().toString(), "Card Prediction", "The card you freely chose was the Queen of Hearts ♥"),
            PresetNote(UUID.randomUUID().toString(), "PIN Number Force", "7492"),
            PresetNote(UUID.randomUUID().toString(), "City Prediction", "Tokyo, Japan 🗼"),
            PresetNote(UUID.randomUUID().toString(), "Book Test Word", "Serendipity (Page 142)")
        )
        savePresetNotes(list)
        return list
    }

    // ---------- Shortcuts Management ----------

    fun getShortcuts(): List<ShortcutItem> {
        val json = prefs.getString("key_shortcuts", null) ?: return defaultShortcuts()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<ShortcutItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ShortcutItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        shortcut = obj.optString("shortcut", ""),
                        expansion = obj.optString("expansion", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            defaultShortcuts()
        }
    }

    private fun saveShortcuts(shortcuts: List<ShortcutItem>) {
        val arr = JSONArray()
        shortcuts.forEach { sc ->
            val obj = JSONObject()
            obj.put("id", sc.id)
            obj.put("shortcut", sc.shortcut)
            obj.put("expansion", sc.expansion)
            arr.put(obj)
        }
        prefs.edit().putString("key_shortcuts", arr.toString()).apply()
    }

    fun addShortcut(shortcut: String, expansion: String) {
        val current = getShortcuts().toMutableList()
        current.add(ShortcutItem(id = UUID.randomUUID().toString(), shortcut = shortcut, expansion = expansion))
        saveShortcuts(current)
    }

    fun updateShortcut(id: String, shortcut: String, expansion: String) {
        val current = getShortcuts().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(shortcut = shortcut, expansion = expansion)
            saveShortcuts(current)
        }
    }

    fun deleteShortcut(id: String) {
        val current = getShortcuts().filter { it.id != id }
        saveShortcuts(current)
    }

    private fun defaultShortcuts(): List<ShortcutItem> {
        val list = listOf(
            ShortcutItem(UUID.randomUUID().toString(), ".force", "The spectator's thought-of number is 42!"),
            ShortcutItem(UUID.randomUUID().toString(), "3h", "3 of Hearts ♥"),
            ShortcutItem(UUID.randomUUID().toString(), "as", "Ace of Spades ♠"),
            ShortcutItem(UUID.randomUUID().toString(), "kd", "King of Diamonds ♦"),
            ShortcutItem(UUID.randomUUID().toString(), ".pin", "The secret code is 1984")
        )
        saveShortcuts(list)
        return list
    }

    /**
     * Checks if buffer ends with any registered shortcut.
     * Returns the matching shortcut item and the matched trigger string.
     */
    fun findExpansion(buffer: String): Pair<ShortcutItem, String>? {
        if (buffer.isEmpty()) return null
        val list = getShortcuts()
        for (item in list) {
            if (item.shortcut.isNotEmpty() && buffer.endsWith(item.shortcut, ignoreCase = true)) {
                return Pair(item, item.shortcut)
            }
        }
        return null
    }
}
