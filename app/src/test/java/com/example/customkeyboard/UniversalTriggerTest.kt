package com.example.customkeyboard

import org.junit.Assert.*
import org.junit.Test

class UniversalTriggerTest {

    @Test
    fun testDelayUnitsConstants() {
        assertEquals("seconds", TriggerManager.UNIT_SECONDS)
        assertEquals("minutes", TriggerManager.UNIT_MINUTES)
    }

    @Test
    fun testDelayDurationCalculations() {
        fun computeDurationMs(value: Int, unit: String): Long {
            return if (unit == TriggerManager.UNIT_MINUTES) {
                value * 60 * 1000L
            } else {
                value * 1000L
            }
        }

        assertEquals(5000L, computeDurationMs(5, TriggerManager.UNIT_SECONDS))
        assertEquals(10000L, computeDurationMs(10, TriggerManager.UNIT_SECONDS))
        assertEquals(15000L, computeDurationMs(15, TriggerManager.UNIT_SECONDS))
        assertEquals(30000L, computeDurationMs(30, TriggerManager.UNIT_SECONDS))
        assertEquals(60000L, computeDurationMs(1, TriggerManager.UNIT_MINUTES))
        assertEquals(120000L, computeDurationMs(2, TriggerManager.UNIT_MINUTES))
        assertEquals(300000L, computeDurationMs(5, TriggerManager.UNIT_MINUTES))
    }

    @Test
    fun testDelayFormattedDescription() {
        fun formatDelay(value: Int, unit: String): String {
            return "$value ${if (unit == TriggerManager.UNIT_MINUTES) "min" else "sec"}"
        }

        assertEquals("5 sec", formatDelay(5, TriggerManager.UNIT_SECONDS))
        assertEquals("10 sec", formatDelay(10, TriggerManager.UNIT_SECONDS))
        assertEquals("1 min", formatDelay(1, TriggerManager.UNIT_MINUTES))
        assertEquals("2 min", formatDelay(2, TriggerManager.UNIT_MINUTES))
    }

    @Test
    fun testEnterActionTypeTriggerLabelMapping() {
        fun getEnterTriggerLabel(actionType: CustomKeyboardService.EnterActionType): String {
            return when (actionType) {
                CustomKeyboardService.EnterActionType.SEARCH -> "Enter / Search Key (SEARCH)"
                CustomKeyboardService.EnterActionType.SEND -> "Enter / Search Key (SEND)"
                CustomKeyboardService.EnterActionType.GO -> "Enter / Search Key (GO)"
                CustomKeyboardService.EnterActionType.DONE -> "Enter / Search Key (DONE)"
                CustomKeyboardService.EnterActionType.NEXT -> "Enter / Search Key (NEXT)"
                CustomKeyboardService.EnterActionType.PREVIOUS -> "Enter / Search Key (PREVIOUS)"
                CustomKeyboardService.EnterActionType.NEWLINE -> "Enter / Search Key (NEWLINE)"
            }
        }

        assertEquals("Enter / Search Key (SEARCH)", getEnterTriggerLabel(CustomKeyboardService.EnterActionType.SEARCH))
        assertEquals("Enter / Search Key (SEND)", getEnterTriggerLabel(CustomKeyboardService.EnterActionType.SEND))
        assertEquals("Enter / Search Key (GO)", getEnterTriggerLabel(CustomKeyboardService.EnterActionType.GO))
        assertEquals("Enter / Search Key (DONE)", getEnterTriggerLabel(CustomKeyboardService.EnterActionType.DONE))
        assertEquals("Enter / Search Key (NEWLINE)", getEnterTriggerLabel(CustomKeyboardService.EnterActionType.NEWLINE))
    }

    @Test
    fun testPendingPayloadStateTransitions() {
        TriggerManager.clearPendingQueue()
        assertFalse(TriggerManager.hasPendingPayload())

        TriggerManager.pendingDeletedWord = "mystery"
        assertTrue(TriggerManager.hasPendingPayload())

        TriggerManager.pendingDeletedWord = null
        assertFalse(TriggerManager.hasPendingPayload())

        TriggerManager.pendingMathPayload = "42"
        assertTrue(TriggerManager.hasPendingPayload())

        TriggerManager.pendingMathPayload = null
        assertFalse(TriggerManager.hasPendingPayload())

        TriggerManager.pendingCovertWord = "card"
        assertTrue(TriggerManager.hasPendingPayload())

        TriggerManager.pendingCovertWord = null
        assertFalse(TriggerManager.hasPendingPayload())

        TriggerManager.pendingTextPeekPayload = "spectator note"
        assertTrue(TriggerManager.hasPendingPayload())

        TriggerManager.clearPendingQueue()
        assertFalse(TriggerManager.hasPendingPayload())
        assertNull(TriggerManager.pendingDeletedWord)
        assertNull(TriggerManager.pendingMathPayload)
        assertNull(TriggerManager.pendingCovertWord)
        assertNull(TriggerManager.pendingTextPeekPayload)
    }
}
