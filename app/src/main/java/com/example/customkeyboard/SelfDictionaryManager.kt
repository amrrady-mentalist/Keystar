package com.example.customkeyboard

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Self / Personal Dictionary Manager
 *
 * Allows users to define custom words, names, abbreviations, and terminology in normal settings.
 * When typing the first 2 or 3 letters of a word, matching entries from this dictionary
 * are prioritized and placed immediately at the front of the suggestion bar.
 */
object SelfDictionaryManager {

    private const val PREFS_NAME = "personal_self_dictionary_prefs"
    private const val KEY_WORDS = "user_custom_words_set"
    private const val KEY_ENABLED = "self_dictionary_enabled"

    private var prefs: SharedPreferences? = null
    private val customWords = ConcurrentSkipListSet<String>(String.CASE_INSENSITIVE_ORDER)
    private var isInitialized = false

    private val defaultStarterWords = listOf(
        "Ahmed",
        "Mohammed",
        "CustomKeyboard",
        "Android",
        "Welcome",
        "أحمد",
        "محمد",
        "مرحبا",
        "شكرا",
        "السلام عليكم"
    )

    fun init(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedSet = prefs?.getStringSet(KEY_WORDS, null)
        if (savedSet != null) {
            customWords.addAll(savedSet)
        } else {
            // Check if a backup exists on the phone storage (e.g. after reinstall)
            val restored = restoreFromPhoneStorage(appContext)
            if (!restored) {
                // First time setup: seed with starter words
                customWords.addAll(defaultStarterWords)
                saveToStorage(appContext)
            }
        }
        isInitialized = true
    }

    private fun getBackupFile(context: Context): java.io.File {
        // Safe location in external media / app external files on device that survives reinstalls
        // or user accessible Documents/Download / externalFilesDir
        val extDir = context.getExternalFilesDir(null) ?: context.filesDir
        val backupDir = java.io.File(extDir.parentFile?.parentFile?.parentFile?.parentFile, "Download/CustomKeyboardBackup").apply {
            if (!exists()) mkdirs()
        }
        return if (backupDir.exists() && backupDir.canWrite()) {
            java.io.File(backupDir, "personal_dictionary_backup.txt")
        } else {
            java.io.File(context.filesDir, "personal_dictionary_backup.txt")
        }
    }

    /**
     * Automatically attempts to restore from standard phone storage locations.
     */
    fun restoreFromPhoneStorage(context: Context): Boolean {
        try {
            val candidates = listOf(
                java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CustomKeyboard_Personal_Words.txt"),
                java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "CustomKeyboard_Personal_Words.txt"),
                getBackupFile(context)
            )
            for (file in candidates) {
                if (file.exists() && file.canRead()) {
                    val lines = file.readLines()
                        .map { it.trim() }
                        .filter { it.length >= 2 && !it.startsWith("#") }
                    if (lines.isNotEmpty()) {
                        customWords.addAll(lines)
                        saveToStorage(context)
                        return true
                    }
                }
            }
        } catch (_: Exception) { }
        return false
    }

    /**
     * Exports words to a text stream.
     */
    fun exportToStream(outputStream: java.io.OutputStream): Int {
        val words = getAllWords()
        val writer = outputStream.bufferedWriter()
        writer.write("# CustomKeyboard Personal Dictionary Backup\n")
        writer.write("# Generated on: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n")
        for (w in words) {
            writer.write(w)
            writer.write("\n")
        }
        writer.flush()
        return words.size
    }

    /**
     * Imports words from an input stream. Returns count of newly added words.
     */
    fun importFromStream(inputStream: java.io.InputStream, context: Context): Int {
        val lines = inputStream.bufferedReader().readLines()
        var addedCount = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length >= 2 && !trimmed.startsWith("#")) {
                if (customWords.add(trimmed)) {
                    addedCount++
                }
            }
        }
        if (addedCount > 0) {
            saveToStorage(context)
        }
        return addedCount
    }

    /**
     * Saves backup file directly to Downloads / Documents so reinstalling the app still finds it.
     */
    fun backupToPhoneStorage(context: Context): Pair<Boolean, String> {
        return try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val backupFile = java.io.File(downloadsDir, "CustomKeyboard_Personal_Words.txt")
            backupFile.outputStream().use { os ->
                exportToStream(os)
            }
            // Also write to local safe backup file
            try {
                getBackupFile(context).outputStream().use { os ->
                    exportToStream(os)
                }
            } catch (_: Exception) {}
            true to backupFile.absolutePath
        } catch (e: Exception) {
            false to (e.message ?: "Failed to write backup")
        }
    }

    fun isEnabled(context: Context): Boolean {
        val p = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val p = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getAllWords(): List<String> {
        return customWords.toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun addWord(word: String): Boolean {
        val trimmed = word.trim()
        if (trimmed.length < 2) return false
        val added = customWords.add(trimmed)
        if (added) {
            saveToStorage()
        }
        return added
    }

    fun removeWord(word: String): Boolean {
        val removed = customWords.remove(word.trim())
        if (removed) {
            saveToStorage()
        }
        return removed
    }

    fun clearAll() {
        customWords.clear()
        saveToStorage()
    }

    fun containsWord(word: String): Boolean {
        return customWords.contains(word.trim())
    }

    /**
     * Finds matching words from the personal dictionary starting with the given prefix.
     * Evaluates when prefix.length >= 2 (e.g. typing first 2 or 3 letters).
     */
    fun getMatchingWords(prefix: String, isArabic: Boolean, limit: Int = 4): List<String> {
        val trimmed = prefix.trim()
        if (trimmed.length < 2) return emptyList()

        val normPrefix = if (isArabic) normalizeArabic(trimmed) else trimmed.lowercase()
        val results = mutableListOf<String>()

        for (word in customWords) {
            val normWord = if (isArabic) normalizeArabic(word) else word.lowercase()
            if (normWord.startsWith(normPrefix)) {
                results.add(word)
                if (results.size >= limit) break
            }
        }
        return results
    }

    private fun normalizeArabic(text: String): String {
        return text
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace("\u064B", "") // fathatan
            .replace("\u064C", "") // dammatan
            .replace("\u064D", "") // kasratan
            .replace("\u064E", "") // fatha
            .replace("\u064F", "") // damma
            .replace("\u0650", "") // kasra
            .replace("\u0651", "") // shadda
            .replace("\u0652", "") // sukun
    }

    fun saveToStorage(context: Context? = null) {
        val p = prefs ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p?.edit()?.putStringSet(KEY_WORDS, HashSet(customWords))?.apply()
        // Auto mirror to local backup
        if (context != null) {
            try {
                getBackupFile(context).outputStream().use { os ->
                    exportToStream(os)
                }
            } catch (_: Exception) {}
        }
    }
}
