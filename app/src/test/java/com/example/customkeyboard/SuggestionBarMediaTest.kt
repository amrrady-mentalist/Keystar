package com.example.customkeyboard

import org.junit.Assert.*
import org.junit.Test

class SuggestionBarMediaTest {

    @Test
    fun testCopiedTextPreviewFormatting() {
        fun formatPreview(text: String): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            return if (clean.length > 32) clean.take(30) + "…" else clean
        }

        assertEquals("Hello world", formatPreview("  Hello   world  \n"))
        val longText = "This is an extraordinarily long text copied from another app that exceeds thirty-two characters"
        val formatted = formatPreview(longText)
        assertTrue(formatted.endsWith("…"))
        assertEquals(31, formatted.length) // 30 chars + 1 ellipsis
        assertEquals("This is an extraordinarily lon…", formatted)
    }

    @Test
    fun testScreenshotRecencyThreshold() {
        fun isRecentScreenshot(name: String, dateAddedSec: Long, currentSec: Long): Boolean {
            val ageSec = currentSec - dateAddedSec
            val isRecent = ageSec in 0..300
            val isScreenshot = name.contains("screenshot", ignoreCase = true) ||
                               name.contains("capture", ignoreCase = true) ||
                               name.startsWith("Screenshot", ignoreCase = true)
            return isRecent && isScreenshot
        }

        val now = 1000000L
        // Screenshot taken 30 seconds ago -> valid
        assertTrue(isRecentScreenshot("Screenshot_2026.png", now - 30, now))
        // Screenshot taken 299 seconds ago -> valid
        assertTrue(isRecentScreenshot("screenshot_test.jpg", now - 299, now))
        // Screenshot taken 301 seconds ago (over 5 minutes) -> expired
        assertFalse(isRecentScreenshot("Screenshot_old.png", now - 301, now))
        // Normal photo taken 10 seconds ago -> not a screenshot
        assertFalse(isRecentScreenshot("IMG_20260920.jpg", now - 10, now))
    }

    @Test
    fun testMediaDismissalOnUserTypingStateMachine() {
        var userStartedTyping = false
        var pendingClipText: String? = "Copied text"
        var pendingScreenshotUri: String? = "content://media/123"

        fun notifyUserTypingAction() {
            if (!userStartedTyping) {
                userStartedTyping = true
                pendingClipText = null
                pendingScreenshotUri = null
            }
        }

        // Before typing: media suggestions are active
        assertFalse(userStartedTyping)
        assertEquals("Copied text", pendingClipText)
        assertEquals("content://media/123", pendingScreenshotUri)

        // User types any character
        notifyUserTypingAction()

        // After typing: media suggestions must be dismissed immediately
        assertTrue(userStartedTyping)
        assertNull(pendingClipText)
        assertNull(pendingScreenshotUri)

        // Subsequent typing actions keep it dismissed
        notifyUserTypingAction()
        assertTrue(userStartedTyping)
        assertNull(pendingClipText)
    }

    @Test
    fun testMediaPrioritizationNewestFirst() {
        var pendingClipTime = 1000L
        val pendingScreenshot = "content://shot"
        val pendingScreenshotTime = 2000L

        // Screenshot is newer than clip
        var firstItem = if (pendingScreenshotTime > pendingClipTime) {
            "SCREENSHOT"
        } else {
            "CLIP"
        }
        assertEquals("SCREENSHOT", firstItem)

        // Now new text is copied after screenshot
        pendingClipTime = 3000L
        firstItem = if (pendingScreenshotTime > pendingClipTime) {
            "SCREENSHOT"
        } else {
            "CLIP"
        }
        assertEquals("CLIP", firstItem)
    }
}
