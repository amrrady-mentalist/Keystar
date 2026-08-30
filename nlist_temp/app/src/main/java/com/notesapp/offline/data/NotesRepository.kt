package com.notesapp.offline.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Simple file-based storage, the native equivalent of the old app's
 * localStorage usage. All notes live in one JSON array on disk at
 * filesDir/notes.json — no database, no schema migrations, easy to inspect
 * or back up by just pulling the file.
 *
 * Every public function is a suspend function that hops onto Dispatchers.IO,
 * so it's safe to call directly from a ViewModel without blocking the UI.
 */
class NotesRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File = File(context.filesDir, "notes.json")
    private val tempFile: File = File(context.filesDir, "notes.json.tmp")

    /** Same reasoning as MagicRepository's mutex: upsert()/delete() are
     *  both "load the whole list, mutate it, save the whole list back"
     *  sequences with no coordination between callers otherwise. Without
     *  this, two overlapping writes (say, one from editing a note while
     *  the lock flow's effect resolution is also writing a forced note in
     *  the background) could interleave and corrupt the file — which the
     *  next load() would silently read back as "no notes at all", and the
     *  very next save would make that permanent. */
    private val mutex = Mutex()

    suspend fun loadAll(): List<Note> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked(): List<Note> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<Note>>(file.readText())
        }.getOrElse {
            // Exists but failed to parse — genuinely corrupted. Preserve it
            // under a different name rather than silently discarding it;
            // nothing else touches that copy, so it's never at risk of
            // being overwritten by a later save.
            runCatching {
                file.copyTo(File(file.parentFile, "notes.corrupt.json"), overwrite = true)
            }
            emptyList()
        }
    }

    private fun saveLocked(notes: List<Note>) {
        // Temp-file-then-rename instead of writing the real file directly
        // — see MagicRepository.saveLocked() for why a direct writeText()
        // risks leaving a half-written, unparsable file behind if the
        // process dies mid-write.
        tempFile.writeText(json.encodeToString(notes))
        tempFile.renameTo(file)
    }

    suspend fun upsert(note: Note) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = loadLocked().toMutableList()
            val index = current.indexOfFirst { it.id == note.id }
            if (index >= 0) {
                current[index] = note
            } else {
                current.add(0, note)
            }
            saveLocked(current)
        }
    }

    suspend fun delete(noteId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = loadLocked().filterNot { it.id == noteId }
            saveLocked(current)
        }
    }

    /** Full overwrite — used by backup restore, which replaces everything
     *  on disk with exactly what's in the backup file rather than merging
     *  it with whatever notes already exist. */
    suspend fun replaceAll(notes: List<Note>) = withContext(Dispatchers.IO) {
        mutex.withLock { saveLocked(notes) }
    }
}
