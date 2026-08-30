package com.notesapp.offline.ui

import com.notesapp.offline.data.EffectType
import com.notesapp.offline.data.ForceListEngine
import com.notesapp.offline.data.MagicEffect
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.MagicStore
import com.notesapp.offline.data.Note
import com.notesapp.offline.data.NotesRepository

/**
 * Shared helper to turn one PIN-reveal effect (Force List or Multiple Outs,
 * any instance of either) on or off. Since at most one can be active at a
 * time, it unpins whichever OTHER Force List note was previously showing on
 * the main notes screen, and ensures the active Force List's note is created
 * and pinned immediately.
 */
suspend fun activatePinEffect(
    repo: MagicRepository,
    notesRepo: NotesRepository,
    fx: MagicEffect,
    enabled: Boolean
): MagicStore {
    val currentStore = repo.load()
    val previouslyActive = currentStore.enabledPinEffect
    repo.setEffectEnabled(fx.id, enabled)
    var freshStore = repo.load()

    if (enabled && previouslyActive != null && previouslyActive.id != fx.id &&
        previouslyActive.type == EffectType.LIST
    ) {
        previouslyActive.linkedNoteId?.let { noteId ->
            notesRepo.loadAll().firstOrNull { it.id == noteId }?.let { note ->
                notesRepo.upsert(note.copy(pinned = false))
            }
        }
    }

    if (enabled && fx.type == EffectType.LIST) {
        val plain = ForceListEngine.actualItems(fx.items)
        if (plain.isNotEmpty()) {
            val numbered = plain.mapIndexed { i, item -> "${i + 1} - $item" }.joinToString("\n")
            val existingNote = fx.linkedNoteId?.let { id -> notesRepo.loadAll().firstOrNull { it.id == id } }
            val note = (existingNote ?: Note(magicEffectId = fx.id)).copy(
                title = fx.title,
                body = numbered,
                checklist = emptyList(),
                pinned = true,
                archived = false
            )
            notesRepo.upsert(note)
            if (existingNote == null) {
                repo.updateEffect(fx.copy(linkedNoteId = note.id))
                freshStore = repo.load()
            }
        }
    }
    return freshStore
}
