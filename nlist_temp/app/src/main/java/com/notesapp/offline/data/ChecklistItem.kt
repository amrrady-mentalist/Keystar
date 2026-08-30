package com.notesapp.offline.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val done: Boolean = false
)
