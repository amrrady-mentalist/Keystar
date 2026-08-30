package com.notesapp.offline.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.notesapp.offline.R

/**
 * Global memory storage and trigger dispatcher for the Delete Peek magic effect.
 *
 * Accurately accumulates deleted characters/words of any length across consecutive
 * backspaces or selection deletions, stores them in memory, and delivers them via:
 * 1) Inject API (if enabled with an apiUrl)
 * 2) Local push notification to the device (if enabled)
 * when a physical trigger (Volume button or Proximity sensor) is pressed/tripped.
 */
object DeletePeekMemory {
    /** The last deleted text/word captured from any note in memory. */
    var lastDeletedWord: String = ""

    /** Notification Channel ID for Delete Peek push notifications. */
    private const val CHANNEL_ID = "magic_delete_peek_channel"
    private const val NOTIFICATION_ID = 9002

    private var currentAnchorIndex: Int = -1
    private var lastDeleteTimeMs: Long = 0L
    private val buffer = StringBuilder()

    /**
     * Records a text change event and tracks contiguous deletions (e.g. Letter by letter backspacing,
     * forward delete, or selection deletions) into a full accumulated word/phrase.
     */
    fun recordEdit(oldText: String, newText: String, start: Int, oldEnd: Int, newEnd: Int) {
        val deletedLength = oldEnd - start
        val insertedLength = newEnd - start

        if (deletedLength <= 0) {
            // User typed or inserted text without deleting: reset any previous deletion sequence
            if (insertedLength > 0) {
                resetSession()
            }
            return
        }

        val deletedChunk = oldText.substring(start, oldEnd)
        val now = System.currentTimeMillis()
        val isRecent = (now - lastDeleteTimeMs) < 6000L // 6s timeout for consecutive keystrokes

        if (isRecent && buffer.isNotEmpty()) {
            if (start == currentAnchorIndex - deletedLength) {
                // Backspace (Right-to-Left): prepend character(s)
                buffer.insert(0, deletedChunk)
                currentAnchorIndex = start
                lastDeleteTimeMs = now
            } else if (start == currentAnchorIndex) {
                // Forward delete (Left-to-Right): append character(s)
                buffer.append(deletedChunk)
                lastDeleteTimeMs = now
            } else {
                // Discontinuous cursor position: start fresh deletion sequence
                buffer.clear()
                buffer.append(deletedChunk)
                currentAnchorIndex = start
                lastDeleteTimeMs = now
            }
        } else {
            // Fresh deletion sequence
            buffer.clear()
            buffer.append(deletedChunk)
            currentAnchorIndex = start
            lastDeleteTimeMs = now
        }

        val result = buffer.toString().trim()
        if (result.isNotEmpty()) {
            lastDeletedWord = result
        }

        // If replacement edit (both deletion and insertion happened in one keystroke), finalize session
        if (insertedLength > 0) {
            resetSession()
        }
    }

    private fun resetSession() {
        buffer.clear()
        currentAnchorIndex = -1
        lastDeleteTimeMs = 0L
    }

    fun showPushNotification(context: Context, word: String) {
        if (word.isBlank()) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notes Notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Only show the deleted word itself with no "Deleted Content" or secondary header
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(word)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
