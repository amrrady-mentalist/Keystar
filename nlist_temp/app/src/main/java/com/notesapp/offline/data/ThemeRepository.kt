package com.notesapp.offline.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ThemeRepository(context: Context) {
    private val file: File = File(context.filesDir, "theme_mode.txt")

    suspend fun load(): ThemeMode = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ThemeMode.DARK
        runCatching { ThemeMode.valueOf(file.readText().trim()) }.getOrDefault(ThemeMode.DARK)
    }

    suspend fun save(mode: ThemeMode) = withContext(Dispatchers.IO) {
        file.writeText(mode.name)
    }
}
