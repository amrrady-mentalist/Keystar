package com.notesapp.offline.data

/**
 * Direct port of the original web app's force-list math
 * (listActualItems / listCodeDigits / buildListForceHtml). Kept as pure
 * functions so the behavior is easy to verify against the original and
 * easy to unit test later.
 */
object ForceListEngine {

    /** Non-blank items, capped at 1000 — mirrors listActualItems(). */
    fun actualItems(items: List<String>): List<String> =
        items.filter { it.isNotBlank() }.take(1000)

    /** 2 digits for lists up to 100 items, 3 digits beyond — mirrors listCodeDigits(). */
    fun codeDigits(items: List<String>): Int =
        if (actualItems(items).size > 100) 3 else 2

    /**
     * Reorders [items] so [forceWord] lands at the position encoded by the
     * last [codeDigits] digits of [pinDigits] (wrapping via modulo, "000"/
     * "00" == the last position). If [forceWord] isn't found, or the list
     * is empty, returns the plain (unforced) list.
     */
    fun buildForcedList(items: List<String>, forceWord: String, pinDigits: String): List<String> {
        val arr = actualItems(items).toMutableList()
        val n = arr.size
        if (n == 0) return arr

        val digits = codeDigits(items)
        val base = if (digits == 2) 100 else 1000
        val value = pinDigits.toIntOrNull() ?: 0
        val effectiveValue = if (value == 0) base else value
        val targetPos = ((effectiveValue - 1) % n) + 1 // 1-indexed

        val trimmed = forceWord.trim()
        if (trimmed.isNotEmpty()) {
            val idx = arr.indexOfFirst { it.trim() == trimmed }
            if (idx > -1) {
                arr.removeAt(idx)
                val insertAt = (targetPos - 1).coerceAtMost(arr.size)
                arr.add(insertAt, trimmed)
            }
        }
        return arr
    }

    /**
     * Inserts [value] into [items] at the position encoded by the last
     * [codeDigits] digits of [pinDigits] (wrapping via modulo, "000"/"00"
     * == the last position) — used instead of [buildForcedList] when the
     * Force Item is literally --value--, since the value coming back from
     * Inject is whatever the spectator wrote down somewhere else and isn't
     * necessarily one of the predefined items [buildForcedList] searches
     * for. The count used for the position math includes the inserted
     * value itself, matching what the spectator will actually see once
     * it's on screen.
     */
    fun insertForcedValue(items: List<String>, value: String, pinDigits: String): List<String> {
        val arr = actualItems(items).toMutableList()
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) return arr

        val finalCount = arr.size + 1
        val digits = codeDigits(items)
        val base = if (digits == 2) 100 else 1000
        val pinValue = pinDigits.toIntOrNull() ?: 0
        val effectiveValue = if (pinValue == 0) base else pinValue
        val targetPos = ((effectiveValue - 1) % finalCount) + 1 // 1-indexed

        val insertAt = (targetPos - 1).coerceAtMost(arr.size)
        arr.add(insertAt, trimmedValue)
        return arr
    }

    /** The last N digits of the entered PIN, where N = codeDigits(items). */
    fun relevantDigits(items: List<String>, pin: String): String {
        val digits = codeDigits(items)
        return if (pin.length >= digits) pin.takeLast(digits) else pin
    }
}
