package com.notesapp.offline.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
private data class BackupManifest(
    val formatVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis()
)

/** Result of a successful [BackupRepository.import] — just enough for the
 *  settings screen to show a meaningful "restored N notes, M effects"
 *  confirmation instead of a bare "Done". */
data class BackupSummary(
    val noteCount: Int,
    val effectCount: Int,
    /** True if the backup had a home-screen widget picked — since a real
     *  widget binding can't survive being copied to a different install
     *  (or even a fresh reinstall of the same app), the caller should tell
     *  the person they'll need to re-pick it in Settings. */
    val widgetNeedsRepick: Boolean
)

/** Thrown when [BackupRepository.import] is given a file that isn't a
 *  backup this app produced (wrong format, corrupted zip, notes.json
 *  missing entirely, etc.) — callers show its message to the user rather
 *  than silently doing nothing or half-restoring. */
class InvalidBackupException(message: String) : Exception(message)

/**
 * Exports/imports everything the app knows about — notes (including
 * embedded drawings, which are already just base64 inside each Note),
 * every Magic Settings effect, and the actual bytes of every photo/icon
 * override those effects reference — as one self-contained ZIP file.
 *
 * The point of bundling the *actual bytes* of referenced media (lock
 * background, home-screen wallpaper, Notes-icon override, decoy app icon
 * overrides) rather than just the JSON that points at them: those paths
 * live under this install's private `filesDir`, which is wiped on
 * uninstall and doesn't exist at all on a different device. A backup that
 * only copied the JSON would "restore" successfully but silently lose
 * every custom photo. Notes themselves need no separate media handling —
 * drawings are already embedded as base64 directly in Note.drawingPngBase64.
 *
 * Restoring is a full replace, not a merge: it's meant for "get back to
 * exactly what this backup captured", most commonly right after a fresh
 * install. Anything created since the backup was made is discarded in
 * favor of what's in the file.
 */
class BackupRepository(
    private val notesRepo: NotesRepository,
    private val magicRepo: MagicRepository,
    private val themeRepo: ThemeRepository
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) {
        val notes = notesRepo.loadAll()
        val store = magicRepo.load()
        val theme = themeRepo.load()

        ZipOutputStream(output).use { zip ->
            fun writeEntry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            writeEntry("manifest.json", json.encodeToString(BackupManifest()).toByteArray())
            writeEntry("notes.json", json.encodeToString(notes).toByteArray())
            writeEntry("magic_store.json", json.encodeToString(store).toByteArray())
            writeEntry("theme.json", json.encodeToString(mapOf("mode" to theme.name)).toByteArray())

            // Every photo/icon override the Magic Settings reference, keyed
            // by filename under media/ — a plain Set so a file referenced
            // from more than one field (unlikely, but cheap to guard) isn't
            // written twice.
            val mediaPaths = buildSet {
                store.lockBackgroundPath?.let { add(it) }
                store.homeWallpaperPath?.let { add(it) }
                store.notesIconPath?.let { add(it) }
                addAll(store.appIconOverrides.values)
            }
            for (path in mediaPaths) {
                val file = File(path)
                if (file.exists()) {
                    writeEntry("media/${file.name}", file.readBytes())
                }
            }
        }
    }

    suspend fun import(input: InputStream): BackupSummary = withContext(Dispatchers.IO) {
        var notes: List<Note>? = null
        var store: MagicStore? = null
        var themeMode: ThemeMode? = null
        val mediaBytes = mutableMapOf<String, ByteArray>() // filename -> bytes

        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    when {
                        name == "notes.json" ->
                            notes = runCatching { json.decodeFromString<List<Note>>(String(bytes)) }.getOrNull()
                        name == "magic_store.json" ->
                            store = runCatching { json.decodeFromString<MagicStore>(String(bytes)) }.getOrNull()
                        name == "theme.json" -> {
                            val map = runCatching { json.decodeFromString<Map<String, String>>(String(bytes)) }.getOrNull()
                            themeMode = map?.get("mode")?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                        }
                        name.startsWith("media/") -> mediaBytes[name.removePrefix("media/")] = bytes
                        // manifest.json is read but unused for now — reserved
                        // for a future formatVersion migration check.
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // notes.json missing/unparsable means this either isn't one of our
        // backups or the file is corrupted — refuse rather than silently
        // wiping the person's real notes with an empty list.
        val restoredNotes = notes ?: throw InvalidBackupException(
            "That file doesn't look like a N.list backup — nothing was restored."
        )

        // Copy every referenced media file into THIS install's own media
        // directory (never trust the paths baked into the backup itself —
        // they belonged to a different install) and rewrite the store's
        // path fields to point at the new locations.
        val remappedByFilename = mutableMapOf<String, String>()
        for ((filename, bytes) in mediaBytes) {
            val dest = File(magicRepo.mediaDir, filename)
            dest.writeBytes(bytes)
            remappedByFilename[filename] = dest.absolutePath
        }
        fun remap(path: String?): String? = path?.let { p -> remappedByFilename[File(p).name] ?: p }

        val hadWidget = store?.homeWidgetProvider != null && (store?.homeWidgetId ?: -1) >= 0
        val restoredStore = (store ?: MagicStore()).let { s ->
            s.copy(
                lockBackgroundPath = remap(s.lockBackgroundPath),
                homeWallpaperPath = remap(s.homeWallpaperPath),
                notesIconPath = remap(s.notesIconPath),
                appIconOverrides = s.appIconOverrides.mapValues { (_, p) -> remap(p) ?: p },
                // A widget's binding is tied to the AppWidgetHost of the
                // install that picked it — it cannot be carried over to a
                // different install (or even survive a fresh reinstall of
                // this same app), so this always comes back unset rather
                // than pointing at an ID that no longer means anything.
                homeWidgetProvider = null,
                homeWidgetId = -1
            )
        }

        notesRepo.replaceAll(restoredNotes)
        magicRepo.save(restoredStore)
        themeMode?.let { themeRepo.save(it) }

        BackupSummary(
            noteCount = restoredNotes.size,
            effectCount = restoredStore.effects.size,
            widgetNeedsRepick = hadWidget
        )
    }
}
