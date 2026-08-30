package com.notesapp.offline.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notesapp.offline.data.ChecklistItem
import com.notesapp.offline.data.Note
import com.notesapp.offline.data.NoteColor
import com.notesapp.offline.data.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NoteFilter { ALL, PINNED, CHECKLISTS, DRAWINGS, ARCHIVED }

class NotesViewModel(private val repo: NotesRepository) : ViewModel() {

    private val _allNotes = MutableStateFlow<List<Note>>(emptyList())

    private val _filter = MutableStateFlow(NoteFilter.ALL)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val notes: StateFlow<List<Note>> = combine(_allNotes, _filter, _query) { all, filter, query ->
        val base = when (filter) {
            NoteFilter.ALL -> all.filterNot { it.archived }
            NoteFilter.PINNED -> all.filterNot { it.archived }.filter { it.pinned }
            NoteFilter.CHECKLISTS -> all.filterNot { it.archived }.filter { it.isChecklist }
            NoteFilter.DRAWINGS -> all.filterNot { it.archived }.filter { it.isDrawing }
            NoteFilter.ARCHIVED -> all.filter { it.archived }
        }
        val searched = if (query.isBlank()) {
            base
        } else {
            base.filter { n ->
                n.title.contains(query, ignoreCase = true) ||
                    n.body.contains(query, ignoreCase = true) ||
                    n.checklist.any { item -> item.text.contains(query, ignoreCase = true) }
            }
        }
        searched.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refresh()
    }

    /** Public so screens that write to the repo directly (e.g. the magic
     *  lock flow, which bypasses this ViewModel's in-memory cache on
     *  purpose since it can run without one ever having been created for
     *  this session) can force a reload once they're done. */
    fun refresh() {
        viewModelScope.launch {
            _allNotes.value = repo.loadAll()
            _loaded.value = true
        }
    }

    fun setFilter(f: NoteFilter) { _filter.value = f }
    fun setQuery(q: String) { _query.value = q }

    fun getNote(id: String): Note? = _allNotes.value.firstOrNull { it.id == id }

    fun save(note: Note) {
        // Update in-memory state synchronously first (so getNote() and the
        // list reflect the change immediately, even if the caller navigates
        // away this same frame), then persist to disk in the background.
        val updated = note.copy(updatedAt = System.currentTimeMillis())
        val current = _allNotes.value
        val idx = current.indexOfFirst { it.id == updated.id }
        _allNotes.value = if (idx >= 0) {
            current.toMutableList().also { it[idx] = updated }
        } else {
            listOf(updated) + current
        }
        viewModelScope.launch { repo.upsert(updated) }
    }

    fun delete(noteId: String) {
        _allNotes.value = _allNotes.value.filterNot { it.id == noteId }
        viewModelScope.launch { repo.delete(noteId) }
    }

    fun deleteMany(noteIds: Set<String>) {
        if (noteIds.isEmpty()) return
        _allNotes.value = _allNotes.value.filterNot { it.id in noteIds }
        viewModelScope.launch {
            noteIds.forEach { repo.delete(it) }
        }
    }

    fun togglePin(noteId: String) {
        val note = getNote(noteId) ?: return
        save(note.copy(pinned = !note.pinned))
    }

    fun togglePinMany(noteIds: Set<String>) {
        if (noteIds.isEmpty()) return
        val targetNotes = _allNotes.value.filter { it.id in noteIds }
        val allPinned = targetNotes.all { it.pinned }
        val newPinned = !allPinned
        val now = System.currentTimeMillis()
        val updated = _allNotes.value.map { n ->
            if (n.id in noteIds) n.copy(pinned = newPinned, updatedAt = now) else n
        }
        _allNotes.value = updated
        viewModelScope.launch {
            updated.filter { it.id in noteIds }.forEach { repo.upsert(it) }
        }
    }

    fun toggleArchive(noteId: String) {
        val note = getNote(noteId) ?: return
        save(note.copy(archived = !note.archived))
    }

    fun toggleArchiveMany(noteIds: Set<String>) {
        if (noteIds.isEmpty()) return
        val targetNotes = _allNotes.value.filter { it.id in noteIds }
        val allArchived = targetNotes.all { it.archived }
        val newArchived = !allArchived
        val now = System.currentTimeMillis()
        val updated = _allNotes.value.map { n ->
            if (n.id in noteIds) n.copy(archived = newArchived, updatedAt = now) else n
        }
        _allNotes.value = updated
        viewModelScope.launch {
            updated.filter { it.id in noteIds }.forEach { repo.upsert(it) }
        }
    }

    fun setColor(noteId: String, color: NoteColor) {
        val note = getNote(noteId) ?: return
        save(note.copy(color = color))
    }

    fun setChecklist(noteId: String, checklist: List<ChecklistItem>) {
        val note = getNote(noteId) ?: return
        save(note.copy(checklist = checklist))
    }

    fun saveDrawing(noteId: String, pngBase64: String?) {
        val note = getNote(noteId) ?: return
        save(note.copy(drawingPngBase64 = pngBase64))
    }
}

class NotesViewModelFactory(private val repo: NotesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return NotesViewModel(repo) as T
    }
}
