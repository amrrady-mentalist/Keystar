package com.example.customkeyboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Global memory storage and trigger dispatcher for the Delete Peek magic effect,
 * ported and enhanced from the N.list mentalism engine.
 *
 * Accurately accumulates deleted characters/words of any length across consecutive
 * backspaces or selection deletions, stores them in memory, and delivers them via:
 * 1) Inject API (if enabled with an apiUrl)
 * 2) Local discreet push notification to the device (if enabled for smartwatch/lockscreen peek)
 * 3) In-keyboard stealth peek banner
 */
object DeletePeekMemory {

    /** The last deleted text/word captured from any text field in memory. */
    @Volatile
    var lastDeletedWord: String = ""
        private set

    /** In-memory history of recent deleted words for inspection */
    val history = mutableListOf<String>()

    /** Notification Channel ID for Delete Peek push notifications. */
    private const val CHANNEL_ID = "magic_delete_peek_channel"
    private const val NOTIFICATION_ID = 9002

    private var lastDeleteTimeMs: Long = 0L
    private val buffer = StringBuilder()
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingDebounceRunnable: Runnable? = null

    // Listener for UI updates
    var onDeletedWordChanged: ((String) -> Unit)? = null

    /**
     * Records a deleted character from backspace in real-time.
     */
    @Synchronized
    fun recordDeletedChar(char: Char, context: Context, covertManager: CovertManager? = null) {
        val now = System.currentTimeMillis()
        val isRecent = (now - lastDeleteTimeMs) < 4000L // 4-second window for consecutive backspaces

        if (isRecent && buffer.isNotEmpty()) {
            // Consecutive backspacing: prepend character
            buffer.insert(0, char)
            lastDeleteTimeMs = now
        } else {
            // Fresh sequence
            buffer.clear()
            buffer.append(char)
            lastDeleteTimeMs = now
        }

        val result = buffer.toString().trim()
        if (result.isNotEmpty()) {
            lastDeletedWord = result
            if (!history.contains(result)) {
                history.add(0, result)
                if (history.size > 20) history.removeAt(history.size - 1)
            }
            onDeletedWordChanged?.invoke(result)

            // Dispatch or queue with debounce for smooth delivery
            covertManager?.let { cm ->
                if (cm.isDeletePeekEnabled) {
                    pendingDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        TriggerManager.queueDeletedWord(result, context, cm)
                    }
                    pendingDebounceRunnable = r
                    debounceHandler.postDelayed(r, 250L)
                }
            }
        }
    }

    /**
     * Records contiguous deleted chunks (e.g. word or selection delete).
     */
    @Synchronized
    fun recordDeletedChunk(chunk: String, context: Context, covertManager: CovertManager? = null) {
        if (chunk.isEmpty()) return
        val now = System.currentTimeMillis()
        val isRecent = (now - lastDeleteTimeMs) < 4000L

        if (isRecent && buffer.isNotEmpty()) {
            buffer.insert(0, chunk)
            lastDeleteTimeMs = now
        } else {
            buffer.clear()
            buffer.append(chunk)
            lastDeleteTimeMs = now
        }

        val result = buffer.toString().trim()
        if (result.isNotEmpty()) {
            lastDeletedWord = result
            if (!history.contains(result)) {
                history.add(0, result)
                if (history.size > 20) history.removeAt(history.size - 1)
            }
            onDeletedWordChanged?.invoke(result)

            covertManager?.let { cm ->
                if (cm.isDeletePeekEnabled) {
                    pendingDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        TriggerManager.queueDeletedWord(result, context, cm)
                    }
                    pendingDebounceRunnable = r
                    debounceHandler.postDelayed(r, 250L)
                }
            }
        }
    }

    fun clearBuffer() {
        pendingDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        buffer.clear()
        lastDeleteTimeMs = 0L
    }

    fun showPushNotification(context: Context, word: String) {
        if (word.isBlank()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keyboard Sync Notifications"
                setShowBadge(false)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 40)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Cancel previous notifications immediately to prevent stale notification buildup or out-of-order popups
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}

        // Stealth high-priority heads-up notification showing the peek word
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(word)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setSilent(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
