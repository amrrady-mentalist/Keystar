package com.example.customkeyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Stores recent clipboard text entries locally on the device (SharedPreferences).
 * Nothing here is transmitted anywhere - it's purely a local convenience list,
 * the same as the clipboard manager built into Gboard / SwiftKey.
 */
class ClipboardHistory(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)

    private val maxItems = 25

    fun getAll(): MutableList<String> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val list = getAll()
        list.remove(trimmed)
        list.add(0, trimmed)
        while (list.size > maxItems) list.removeAt(list.size - 1)
        save(list)
    }

    fun remove(text: String) {
        val list = getAll()
        list.remove(text)
        save(list)
    }

    fun clear() {
        save(mutableListOf())
    }

    private fun save(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
